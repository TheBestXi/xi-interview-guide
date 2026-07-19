# Phase 0 实操笔记：测试体系搭建 + CI 守护加固

> 完成日期：2026-07-19
> 对应路线图：`14-ops-learning-roadmap.md` Phase 0
>
> 这一阶段做的事：给项目搭测试体系，**用测试揪出真实 bug 并修复**，然后把测试接入 CI/CD。

---

## 一、做了什么

### 1. 加 JaCoCo 代码覆盖率

**改的文件**：`app/build.gradle`

```gradle
plugins {
    id 'jacoco'                    # 新增
}

jacoco {
    toolVersion = "0.8.12"
}

test {
    useJUnitPlatform()
    finalizedBy jacocoTestReport   # 跑完测试自动生成报告
}

jacocoTestReport {
    reports {
        xml.required = false
        html.required = true
        csv.required = false
    }
}
```

**为什么**：测试写完后要能看见"哪些代码没被测到"。JaCoCo 生成的 HTML 报告（`app/build/reports/jacoco/test/html/index.html`）用绿色/红色标出每行代码是否被覆盖。写完一批测试打开看，红色行就是测试缺口。

**踩坑**：没有。Gradle 内置 jacoco 插件，3 行配置就能用。

### 2. 写了 4 个核心模块的测试

| 测试类 | 测试数 | 测什么 |
|--------|-------|-------|
| `ApiKeyEncryptionServiceTest` | 33 | AES-GCM 加解密往返、nonce 随机性、密钥派生路径、解密篡改检测、启动配置 |
| `PromptSanitizerTest` | 60 | Prompt 注入检测（角色伪造/忽略指令/分隔符伪造/XML 边界）、不误杀正常文本、wrapWithDelimiters 防伪造 |
| `ResumeGradingServiceTest` | 8 | 正常路径、LLM 失败行为、null 输入、评分越界 |
| `AbstractStreamConsumerTest` | 16 | 5 个 ACK 分支、off-by-one 重试边界、parseRetryCount 边界、truncateError 截断 |
| **合计** | **117** | |

### 3. 修复 2 个真实 bug

#### Bug 1：ResumeGradingService 静默吞异常（核心 bug）

**位置**：`app/src/main/java/interview/guide/modules/resume/service/ResumeGradingService.java`

**原代码**：
```java
public ResumeAnalysisResponse analyzeResume(String resumeText) {
    try {
        // ... 调 LLM 评分
        try {
            dto = structuredOutputInvoker.invoke(...);
        } catch (Exception e) {
            throw new BusinessException(RESUME_ANALYSIS_FAILED, ...);  // ← 内层抛
        }
        return convertToResponse(dto, resumeText);
    } catch (Exception e) {
        // ← 外层 catch 把 BusinessException 吞了！
        return createErrorResponse(resumeText, e.getMessage());  // 返回总分 0 的"伪成功"
    }
}
```

**问题**：LLM 调用失败时，外层 `catch(Exception)` 把内层抛的 `BusinessException` 吞掉，返回了一个 `overallScore=0` 的伪响应。

**实际影响**：调用方 `AnalyzeStreamConsumer.processBusiness` 拿到这个"0 分响应"后会调 `saveAnalysis` 存库 + `markCompleted` 标记成功。**用户看到一份 0 分的简历，但系统记录的是"分析成功"**——既骗了用户又骗了运维。

**修复**：
```java
public ResumeAnalysisResponse analyzeResume(String resumeText) {
    if (resumeText == null || resumeText.isBlank()) {
        throw new BusinessException(BAD_REQUEST, "简历文本不能为空");  // null 防护
    }
    // ... 调 LLM
    try {
        dto = structuredOutputInvoker.invoke(...);
    } catch (BusinessException e) {
        throw e;  // ← 业务异常直接上抛，不再包装成伪响应
    } catch (Exception e) {
        throw new BusinessException(RESUME_ANALYSIS_FAILED, ...);  // 兜底包装
    }
    return convertToResponse(dto, resumeText);
}
```

**修复后的行为链**：LLM 失败 → `analyzeResume` 抛 BusinessException → `processBusiness` 也抛 → `AbstractStreamConsumer.processMessage` catch → 走重试或 `markFailed` → 简历状态变 FAILED，用户看到明确错误。**这才是对的**。

#### Bug 2：null 输入导致 NPE

**原代码**：`resumeText.length()` 在 null 上直接 NPE。

**修复**：开头加 null/空白检查，抛 `BusinessException(BAD_REQUEST)`。

### 4. deploy.yml 加测试步骤

**改的文件**：`.github/workflows/deploy.yml`

```yaml
# 步骤 3：后端编译检查
- name: 🔨 后端编译检查
  run: ./gradlew :app:compileJava --no-daemon

# 步骤 3.5：后端单元测试（新增）
- name: 🧪 后端单元测试
  run: ./gradlew :app:test --no-daemon

# 步骤 4：前端构建检查
...
```

**为什么**：之前只 `compileJava`——只查语法，不查逻辑。现在 `:app:test` 会跑 332 个测试（含新增的 117 个），任何测试失败立即停住流水线，不允许部署。

**测试在镜像构建之前**——失败的话根本不会构建镜像、不会部署。

---

## 二、还探查出但**没修**的设计问题（记录待评估）

写测试过程中发现几个设计层面的问题，**不是 bug，是设计选择**。锁定现状后写进了测试注释，将来要不要改需要业务讨论：

| 问题 | 位置 | 现状 | 潜在风险 |
|------|------|------|---------|
| 密钥派生歧义 | `ApiKeyEncryptionService.resolveKeyBytes` | 配置字符串恰好 32 字节 Base64 → 当原始 key；否则 SHA-256 派生 | 运维误把人类可读密码配成 44 字符 Base64 时会走"原始 key"路径，跨实例解密失败 |
| `detectInjectionAttempt` 漏检 | `PromptSanitizer` | 只查 ROLE + PHRASE，不查 DELIMITER + BOUNDARY | 分隔符注入和 XML 边界注入只被 sanitize 清洗，不进告警日志 |
| 评分越界不校验 | `ResumeGradingService` | LLM 返回 `overallScore=150` 原样透传 | 理论上 LLM 不会返回越界分数，但 GPT 类模型偶发幻觉可能产出 |
| `permits` 硬编码 `"1"` | `RateLimitAspect` | Lua 脚本每令牌扣 1 | 未来要支持批量扣减时是坑 |
| AbstractStreamConsumer 用无界队列 | `init()` | `LinkedBlockingQueue` 无界 + `AbortPolicy` | 配置不一致：实际永远不会触发 AbortPolicy，任务会无限堆积 |
| EncryptedValue 无密钥版本号 | `EncryptedValue` record | 只有 nonce + ciphertext | 一旦轮换密钥，旧密文全部解不开 |

**这 6 个问题都不算紧急 bug，但写进了测试注释和本文档，将来评估**。

---

## 三、本次踩的坑

| 坑 | 现象 | 解决 |
|----|------|------|
| bash 不认 `cd /d` | 第一条 gradle 命令报 `cd: too many arguments` | Windows 上用 bash 调用，写 `cd "D:/path"` |
| private record 反射调用 | `IllegalAccessException` | 必须 `constructor.setAccessible(true)` |
| `replaceFirst` 贪婪匹配 | 提取 UUID id 的正则吃掉整个字符串 | 改用 `String.lines().filter(...)` 逐行匹配 |
| 测试用例打错字 | `analyzealyzeResume` | 编译错误立刻暴露，修 |

---

## 四、数字总结

| 项 | 数字 |
|----|------|
| 新增测试用例 | 117 |
| 新增测试文件 | 4 |
| 修改的源码文件 | 2（build.gradle + deploy.yml + ResumeGradingService）|
| 修复的 bug | 2（核心静默 bug + null NPE）|
| 探查出未修的设计问题 | 6 |
| 全量测试总数 | 332 |
| 全量测试失败数 | 0 |
| 原有 Disabled 测试 | 49（未动）|
| 工时 | 约 4 小时 |

---

## 五、验证清单

- [x] `./gradlew :app:test --no-daemon` 本地 BUILD SUCCESSFUL
- [x] JaCoCo 报告生成（`app/build/reports/jacoco/test/html/index.html`）
- [x] deploy.yml 加了测试步骤
- [x] 2 个 bug 已修复，修复后的行为有测试守护
- [x] 修复没破坏调用方（`AnalyzeStreamConsumer.processBusiness` 走外层 catch，新行为更合理）

**未做的**（留给下一步）：
- [ ] push 到 main 看 CI 跑通
- [ ] 故意改坏一个测试验证 CI 能拦住部署
- [ ] main 分支保护 + PR 流程（下一阶段的事）

---

## 六、简历能讲的故事

这一阶段做完，简历可以这么写：

> 负责 interview-guide 项目测试体系建设：基于 JUnit 5 + Mockito 搭建单元测试，引入 JaCoCo 覆盖率工具。在测试过程中发现并修复 2 个生产级 bug（简历评分服务静默吞 LLM 异常返回伪成功响应、null 输入 NPE），将 117 个测试用例接入 GitHub Actions CI/CD 流水线，实现"测试不过不允许部署"的自动化质量门禁。

**面试官会追问的点**：
1. "静默吞异常"具体是什么 → 能讲清楚 try-catch 嵌套结构怎么把内层异常吞掉的
2. 怎么发现这个 bug 的 → 写测试时断言"LLM 失败应抛异常"，发现实际返回了 0 分响应
3. 测试为什么不只测 happy path → 因为 bug 90% 出在异常路径
4. JaCoCo 给你带来了什么 → 看见红色行知道哪些分支没测到，避免"我以为测全了"

---

## 下一步

按 `14-ops-learning-roadmap.md` Phase 0 的后续：
1. **现在**：把这次改动 push 到 main，看 CI 跑通
2. **下一步**：main 分支保护 + PR Review 流程
3. **再下一步**：dev/prod 双环境隔离
