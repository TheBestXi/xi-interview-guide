package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.ScoreDetail;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.Suggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历评分服务
 * 使用Spring AI调用LLM对简历进行评分和建议
 */
@Service
public class ResumeGradingService {
    
    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);
    
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<ResumeAnalysisResponseDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    
    // 中间DTO用于接收AI响应
    private record ResumeAnalysisResponseDTO(
        int overallScore,
        ScoreDetailDTO scoreDetail,
        String summary,
        List<String> strengths,
        List<SuggestionDTO> suggestions
    ) {}
    
    private record ScoreDetailDTO(
        int contentScore,
        int structureScore,
        int skillMatchScore,
        int expressionScore,
        int projectScore
    ) {}
    
    private record SuggestionDTO(
        String category,
        String priority,
        String issue,
        String recommendation
    ) {}
    
    public ResumeGradingService(
            LlmProviderRegistry llmProviderRegistry,
            StructuredOutputInvoker structuredOutputInvoker,
            ResumeAnalysisProperties properties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.outputConverter = new BeanOutputConverter<>(ResumeAnalysisResponseDTO.class);
    }
    
    /**
     * 分析简历并返回评分和建议
     *
     * @param resumeText 简历文本内容
     * @return 分析结果
     * @throws BusinessException 当 resumeText 为空，或 LLM 调用失败时
     */
    public ResumeAnalysisResponse analyzeResume(String resumeText) {
        // null/空防护：避免后续 resumeText.length() 抛 NPE
        if (resumeText == null || resumeText.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历文本不能为空");
        }
        log.info("开始分析简历，文本长度: {} 字符", resumeText.length());

        // 加载系统提示词
        String systemPrompt = systemPromptTemplate.render();

        // 加载用户提示词并填充变量
        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeText);
        String userPrompt = userPromptTemplate.render(variables);

        // 添加格式指令到系统提示词
        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();

        // 调用 AI
        ResumeAnalysisResponseDTO dto;
        try {
            ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
            dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                userPrompt,
                outputConverter,
                ErrorCode.RESUME_ANALYSIS_FAILED,
                "简历分析失败：",
                "简历分析",
                log
            );
            log.debug("AI响应解析成功: overallScore={}", dto.overallScore());
        } catch (BusinessException e) {
            // 业务异常（含 LLM 失败）：直接向上抛，让调用方知道失败了
            // 注意：不再吞掉异常返回"0 分伪成功响应"——那会让上游误以为分析成功
            log.error("简历分析失败: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            // 兜底：把未知运行时异常包装成业务异常
            log.error("简历分析出现未知异常: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析失败：" + e.getMessage(), e);
        }

        // 转换为业务对象
        ResumeAnalysisResponse result = convertToResponse(dto, resumeText);
        log.info("简历分析完成，总分: {}", result.overallScore());

        return result;
    }

    /**
     * 转换DTO为业务对象。
     * 对 LLM 可能返回的 null 字段做防御性处理，避免 convertToResponse 内部 NPE。
     */
    private ResumeAnalysisResponse convertToResponse(ResumeAnalysisResponseDTO dto, String originalText) {
        ScoreDetailDTO rawDetail = dto.scoreDetail();
        // LLM 偶尔会漏字段，用 0 兜底
        ScoreDetail scoreDetail = rawDetail == null
            ? new ScoreDetail(0, 0, 0, 0, 0)
            : new ScoreDetail(
                rawDetail.contentScore(),
                rawDetail.structureScore(),
                rawDetail.skillMatchScore(),
                rawDetail.expressionScore(),
                rawDetail.projectScore()
            );

        List<Suggestion> suggestions = dto.suggestions() == null
            ? List.of()
            : dto.suggestions().stream()
                .map(s -> new Suggestion(s.category(), s.priority(), s.issue(), s.recommendation()))
                .toList();

        return new ResumeAnalysisResponse(
            dto.overallScore(),
            scoreDetail,
            dto.summary(),
            dto.strengths() == null ? List.of() : dto.strengths(),
            suggestions,
            originalText
        );
    }
    
    /**
     * 创建错误响应
     */
    private ResumeAnalysisResponse createErrorResponse(String originalText, String errorMessage) {
        return new ResumeAnalysisResponse(
            0,
            new ScoreDetail(0, 0, 0, 0, 0),
            "分析过程中出现错误: " + errorMessage,
            List.of(),
            List.of(new Suggestion(
                "系统",
                "高",
                "AI分析服务暂时不可用",
                "请稍后重试，或检查AI服务是否正常运行"
            )),
            originalText
        );
    }
}
