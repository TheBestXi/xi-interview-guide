package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.ScoreDetail;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.Suggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ResumeGradingService 单元测试
 *
 * <p>核心目标：探出静默 bug。
 *
 * <p>已知 bug（探查发现）：
 * <ol>
 *   <li><b>静默吞异常</b>：analyzeResume 行 128-131 外层 catch(Exception) 会把
 *       LLM 失败的 BusinessException 吞掉，转成"总分 0 的成功响应"。
 *       调用方 markCompleted 会把它当成功存库，用户看到一份"分析失败但显示成功"的简历。</li>
 *   <li><b>null NPE</b>：resumeText=null 时 resumeText.length() 直接 NPE。</li>
 * </ol>
 *
 * <p>测试策略：用 mock 控制结构化输出返回值或抛异常，
 * 验证"应该发生的行为"（不一定是当前行为）。
 *
 * <p>注意：构造器要读 classpath 的 .st 文件，用 DefaultResourceLoader 走真实加载。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeGradingService 测试")
class ResumeGradingServiceTest {

  @Mock private LlmProviderRegistry llmProviderRegistry;
  @Mock private StructuredOutputInvoker structuredOutputInvoker;
  @Mock private ChatClient chatClient;

  private ResumeGradingService service;

  @BeforeEach
  void setUp() throws IOException {
    ResumeAnalysisProperties properties = new ResumeAnalysisProperties();
    // 用真实 ResourceLoader 读取 classpath:prompts/*.st
    service = new ResumeGradingService(
        llmProviderRegistry,
        structuredOutputInvoker,
        properties,
        new DefaultResourceLoader()
    );
  }

  // ===========================================================================
  // 正常路径
  // ===========================================================================
  @Nested
  @DisplayName("正常路径：LLM 返回完整 DTO")
  class HappyPathTests {

    @Test
    @DisplayName("LLM 正常返回时应构造完整的 ResumeAnalysisResponse")
    void shouldBuildResponseOnSuccess() throws Exception {
      // 给定：LLM 返回的中间 DTO（通过反射构造，因为是 private record）
      Object dto = newDto(85, 22, 18, 20, 13, 12,
          "简历整体不错",
          List.of("项目经验丰富", "技能匹配度高"),
          List.of(newDtoSuggestion("项目", "高", "项目描述不够量化", "用 STAR 重写")));

      when(llmProviderRegistry.getDefaultChatClient()).thenReturn(chatClient);
      when(structuredOutputInvoker.invoke(
          eq(chatClient), anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
          .thenReturn(dto);

      // 当：调用 analyzeResume
      ResumeAnalysisResponse response = service.analyzeResume("张三的简历内容");

      // 那么：字段映射正确
      assertEquals(85, response.overallScore());
      assertEquals(22, response.scoreDetail().contentScore());
      assertEquals(18, response.scoreDetail().structureScore());
      assertEquals(20, response.scoreDetail().skillMatchScore());
      assertEquals(13, response.scoreDetail().expressionScore());
      assertEquals(12, response.scoreDetail().projectScore());
      assertEquals("简历整体不错", response.summary());
      assertEquals(2, response.strengths().size());
      assertEquals(1, response.suggestions().size());
      assertEquals("张三的简历内容", response.originalText());
    }

    @Test
    @DisplayName("原始简历文本应透传到 response.originalText")
    void shouldPassThroughOriginalText() throws Exception {
      Object dto = newDto(70, 18, 14, 17, 11, 10, "summary", List.of(), List.of());

      when(llmProviderRegistry.getDefaultChatClient()).thenReturn(chatClient);
      when(structuredOutputInvoker.invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
          .thenReturn(dto);

      String originalText = "我的特殊简历内容 %s _d %%";
      ResumeAnalysisResponse response = service.analyzeResume(originalText);

      assertEquals(originalText, response.originalText());
    }
  }

  // ===========================================================================
  // Bug 1（核心）：LLM 失败时不应静默降级
  // ===========================================================================
  @Nested
  @DisplayName("Bug 1：LLM 失败时的行为")
  class LlmFailureTests {

    /**
     * 这是最关键的测试：当前实现会把 LLM 异常吞掉返回 0 分响应。
     * 期望行为应该是抛 BusinessException，让上游知道失败了。
     */
    @Test
    @DisplayName("LLM 调用抛异常时应抛出 BusinessException（而不是返回 0 分响应）")
    void shouldThrowWhenLlmFails() {
      when(llmProviderRegistry.getDefaultChatClient()).thenReturn(chatClient);
      when(structuredOutputInvoker.invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
          .thenThrow(new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "LLM 超时"));

      // 期望：抛 BusinessException（不是返回 0 分响应）
      BusinessException ex = assertThrows(BusinessException.class,
          () -> service.analyzeResume("简历内容"));

      // 错误码应该是 RESUME_ANALYSIS_FAILED，不是被伪装成"成功"
      assertEquals(ErrorCode.RESUME_ANALYSIS_FAILED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("LLM 失败时不应返回 0 分 + 单条系统建议的伪成功响应")
    void shouldNotReturnZeroScoreErrorResponse() {
      when(llmProviderRegistry.getDefaultChatClient()).thenReturn(chatClient);
      when(structuredOutputInvoker.invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
          .thenThrow(new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "失败"));

      // 当前实现（有 bug）：返回了 0 分响应。
      // 修了之后：抛 BusinessException。
      // 这个测试断言"不该返回 0 分响应"，bug 修复前是红的，修复后是绿的。
      try {
        ResumeAnalysisResponse response = service.analyzeResume("内容");
        // 如果走到这里说明 bug 还在——返回了响应而不是抛异常
        fail("LLM 失败时不应返回响应（即使是 0 分），应抛 BusinessException。"
            + "实际返回：" + response.overallScore() + " 分，suggestions=" + response.suggestions().size());
      } catch (BusinessException expected) {
        // 期望路径：抛 BusinessException
        assertTrue(true);
      }
    }
  }

  // ===========================================================================
  // Bug 2：null 输入
  // ===========================================================================
  @Nested
  @DisplayName("Bug 2：null 输入")
  class NullInputTests {

    /**
     * 当前实现：resumeText=null 时 resumeText.length() 直接 NPE。
     * 期望：明确抛 BusinessException(BAD_REQUEST) 或类似，而不是 NPE。
     */
    @Test
    @DisplayName("null 简历文本应抛 BusinessException 而不是 NPE")
    void shouldThrowForNullInput() {
      BusinessException ex = assertThrows(BusinessException.class,
          () -> service.analyzeResume(null));
      assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("空字符串输入应被显式拒绝（抛 BAD_REQUEST）")
    void shouldRejectEmptyInput() {
      BusinessException ex = assertThrows(BusinessException.class,
          () -> service.analyzeResume(""));
      assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("纯空白字符串也应被拒绝")
    void shouldRejectBlankInput() {
      BusinessException ex = assertThrows(BusinessException.class,
          () -> service.analyzeResume("   \n\t  "));
      assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }
  }

  // ===========================================================================
  // 评分越界（探查发现的边界）
  // ===========================================================================
  @Nested
  @DisplayName("LLM 返回越界评分")
  class OutOfRangeScoreTests {

    /**
     * 当前实现：不校验 LLM 返回的分数是否越界（满分 100，各维度满分固定）。
     * 也就是说 LLM 返回 overallScore=150 会被原样返回。
     * 这是已知脆弱点，测试锁定当前行为。
     */
    @Test
    @DisplayName("LLM 返回越界分数（150 分）时当前不校验（锁定现状）")
    void shouldCurrentlyNotValidateOutOfRangeScores() {
      Object dto = newDto(150, 30, 30, 30, 30, 30, "超满分", List.of(), List.of());

      when(llmProviderRegistry.getDefaultChatClient()).thenReturn(chatClient);
      when(structuredOutputInvoker.invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
          .thenReturn(dto);

      ResumeAnalysisResponse response = service.analyzeResume("内容");

      // 当前行为：原样返回越界分数（不校验）
      // 如果以后加了校验逻辑，改成 assertThrows 即可
      assertEquals(150, response.overallScore(), "当前不校验越界分数——锁定现状");
    }
  }

  // ===========================================================================
  // 辅助方法：构造 private record DTO
  // ===========================================================================

  /**
   * 通过反射构造 ResumeAnalysisResponseDTO（它是 private record，外部无法直接 new）。
   * 参数顺序对应源码里的 record 定义。
   */
  private Object newDto(int overallScore,
                        int contentScore, int structureScore, int skillMatchScore, int expressionScore, int projectScore,
                        String summary,
                        List<String> strengths,
                        List<Object> suggestions) {
    try {
      Class<?> dtoClass = Class.forName("interview.guide.modules.resume.service.ResumeGradingService$ResumeAnalysisResponseDTO");
      Class<?> scoreDetailClass = Class.forName("interview.guide.modules.resume.service.ResumeGradingService$ScoreDetailDTO");

      var scoreDetailCtor = scoreDetailClass
          .getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
      scoreDetailCtor.setAccessible(true);
      Object scoreDetail = scoreDetailCtor.newInstance(contentScore, structureScore, skillMatchScore, expressionScore, projectScore);

      var dtoCtor = dtoClass.getDeclaredConstructor(
          int.class, scoreDetailClass, String.class, List.class, List.class);
      dtoCtor.setAccessible(true);
      return dtoCtor.newInstance(overallScore, scoreDetail, summary, strengths, suggestions);
    } catch (Exception e) {
      throw new RuntimeException("构造测试 DTO 失败：" + e.getMessage(), e);
    }
  }

  /** 构造 SuggestionDTO。 */
  private Object newDtoSuggestion(String category, String priority, String issue, String recommendation) {
    try {
      Class<?> suggestionClass = Class.forName("interview.guide.modules.resume.service.ResumeGradingService$SuggestionDTO");
      var ctor = suggestionClass.getDeclaredConstructor(String.class, String.class, String.class, String.class);
      ctor.setAccessible(true);
      return ctor.newInstance(category, priority, issue, recommendation);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
