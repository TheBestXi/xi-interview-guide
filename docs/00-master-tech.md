# 总技术文档与开发总计划（Master Tech）

> 本文档是 interview-guide 项目的**技术总纲 + 执行计划**。
> - 上半部分（§1-§5）：系统架构、技术选型、模块边界、编码规范——回答"怎么搭"
> - 下半部分（§6-§9）：**开发总计划**——回答"按什么顺序做、每一步产出什么"
>
> 配合 `00-master-features.md`（产品总纲）使用。详细技术契约见 `01-technical-design.md`。

---

# 第一部分：技术总纲

## 1. 技术栈（与上游对齐）

### 1.1 后端

| 类别 | 选型 | 版本 | 说明 |
|------|------|------|------|
| 语言 | Java | 21（本地先 17 试跑）| 上游硬性要求 21 |
| 框架 | Spring Boot | 4.1.0 | 模块化 starter |
| 构建 | Gradle | 8.x（wrapper）| 自带 gradlew |
| AI | Spring AI | 2.0.0 | OpenAI 兼容协议接 DashScope |
| ORM | Spring Data JPA | 随 Boot | Repository 模式 |
| 数据库 | PostgreSQL | 16 | + pgvector |
| 缓存/MQ | Redis | 7 | Redisson 4.0 + Stream |
| 对象存储 | MinIO | latest | S3 兼容 |
| 文档解析 | Apache Tika | 2.9.2 | PDF/DOCX |
| 对象映射 | MapStruct | 1.6.3 | 配合 Lombok |
| PDF 导出 | iText | 8.0.5 | + 中文字体 |
| 语音 | DashScope SDK | 2.22.7 | Qwen3 ASR/TTS |
| 多模态 | Qwen-VL-OCR | — | 仅多栏 PDF 降级用 |

### 1.2 前端

| 类别 | 选型 |
|------|------|
| 框架 | React 18 + TypeScript 5.6 |
| 构建 | Vite 5.4 |
| 样式 | TailwindCSS 4.1 |
| 路由 | react-router-dom 7.11 |
| HTTP | axios 1.7 |
| 图表 | recharts 3.6 |
| 包管理 | pnpm 9.x/10.x |

### 1.3 基础设施

| 组件 | 用途 |
|------|------|
| Docker + Compose | 本地+生产统一编排 |
| PostgreSQL + pgvector | 业务数据 + 向量 |
| Redis | 缓存 + Stream 异步 |
| MinIO | 对象存储 |
| Nginx | 静态托管 + 反代 + SSL |

---

## 2. 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                        用户浏览器                            │
│              React 18 + Vite + TailwindCSS                  │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTPS (443)
┌──────────────────────────▼──────────────────────────────────┐
│                    Nginx (反向代理 + SSL)                    │
│   ┌─────────────┐        ┌──────────────────┐               │
│   │  静态资源   │        │  /api/* → app:8080│               │
│   └─────────────┘        └──────────────────┘               │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│              Spring Boot 4.1 后端 (app:8080)                │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Controller → Service → Repository (JPA)            │   │
│  │  统一 Result<T> / BusinessException / 全局异常      │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌──────────────┐ │
│  │ 简历模块 │ │ 面试模块 │ │ 知识库RAG │ │ LLM Provider │ │
│  └────┬─────┘ └────┬─────┘ └─────┬─────┘ └──────┬───────┘ │
│  ┌────▼────────────▼─────────────▼──────────────▼───────┐ │
│  │  common 层: AI调用 / 限流 / 异步 / 事务 / 异常       │ │
│  │  infrastructure 层: 文件 / 导出 / Redis / MapStruct  │ │
│  └──────────────────────────────────────────────────────┘ │
└────┬──────────────┬────────────────────┬──────────────┬───┘
     │              │                    │              │
┌────▼─────┐  ┌─────▼─────┐  ┌──────────▼───┐  ┌──────▼─────┐
│PostgreSQL│  │   Redis   │  │    MinIO     │  │ DashScope │
│+pgvector │  │ + Stream  │  │  (S3 兼容)   │  │  (外部API) │
└──────────┘  └───────────┘  └──────────────┘  └────────────┘
```

---

## 3. 后端包结构

包根：`com.interview.guide`

```
com.interview.guide
├── common/                        # 通用层
│   ├── ai/                        # LLM 调用、Provider 注册表、结构化输出
│   │   ├── LlmProviderRegistry.java
│   │   ├── PromptSanitizer.java
│   │   └── StructuredOutputInvoker.java
│   ├── annotation/                # @RateLimit
│   ├── aspect/                    # 限流切面
│   ├── async/                     # Stream 生产/消费者基类
│   │   ├── AbstractStreamProducer.java
│   │   └── AbstractStreamConsumer.java
│   ├── auth/                      # 游客拦截器、JWT 过滤器、@RequireRole
│   ├── config/                    # @ConfigurationProperties
│   ├── constant/                  # 常量、枚举
│   ├── evaluation/                # UnifiedEvaluationService（文字+语音共用）
│   ├── exception/                 # BusinessException / ErrorCode / 全局处理器
│   ├── model/                     # 通用模型
│   ├── quota/                     # 游客配额服务
│   ├── result/                    # Result<T>
│   └── transaction/               # TransactionalExecutor
├── infrastructure/                # 基础设施层
│   ├── file/                      # MinIO/Tika/DocumentParseService
│   ├── export/                    # PdfExportService (iText)
│   ├── redis/                     # RedisService / InterviewSessionCache
│   └── mapper/                    # MapStruct 全局配置
├── modules/                       # 业务模块
│   ├── auth/                      # 03 用户体系（注册/登录/JWT）
│   ├── resume/                    # 04 简历分析
│   ├── interview/                 # 05 文字面试
│   ├── knowledgebase/             # 06 知识库 RAG
│   ├── voiceinterview/            # 07 语音面试
│   ├── interviewschedule/         # 08 面试日程
│   └── llmprovider/               # 09 多模型管理
└── App.java                       # 主启动类
```

---

## 4. 贯穿全局的设计模式

这些是源项目验证过的、必须遵守的硬约束：

### 4.1 统一响应 + 全局异常

```java
// 所有 Controller 返回 Result<T>，HTTP 永远 200
@PostMapping("/resume/upload")
public Result<ResumeResponse> upload(...) {
    return Result.success(resumeService.analyze(...));
}

// 业务异常用 BusinessException，全局处理器转成 Result.error
throw new BusinessException(ErrorCode.QUOTA_EXCEEDED, "今日次数已用完");
```

### 4.2 异步任务统一 Redis Stream

所有耗时操作走同一套模板：
- 简历分析、面试评估、语音评估、知识库向量化
- 基类：`AbstractStreamProducer` / `AbstractStreamConsumer`
- 机制：Consumer Group + ACK + 重试 3 次 + 状态机（PENDING→PROCESSING→COMPLETED/FAILED）

### 4.3 LLM 调用统一入口

```java
// 不直接 new ChatClient，统一从 Registry 拿
ChatClient client = llmProviderRegistry.getChatClientOrDefault(provider);
```

### 4.4 Prompt 防注入

所有用户输入经 `PromptSanitizer.wrapWithDelimiters` 包裹，防止 prompt 注入。

### 4.5 事务边界

`@Transactional` 只在 Service 层，范围最小。**LLM/S3/外部 HTTP 不得在事务内调用**（用 `TransactionalExecutor.run()` 分离）。

### 4.6 双写策略（Redis + DB）

热数据 Redis 优先，DB 兜底。读取时缓存未命中从 DB 重建并回填。

---

## 5. 编码规范（硬性约定）

- Controller 只做路由/校验/委托，**不含业务逻辑**
- Service 承担业务编排，`@Transactional` **只在 Service 层**且范围最小
- 对外响应统一 `Result<T>`，**禁止返回 Entity**
- 业务异常用 `BusinessException(ErrorCode.XXX, "描述")`
- 请求体用不可变 `record`，命名后缀 `XxxRequest/Response/DTO/Entity`
- Entity→DTO 优先 MapStruct
- 构造器注入 + Lombok `@RequiredArgsConstructor`
- **不散落 `@Value`**，用 `@ConfigurationProperties`
- 线程池显式 `ThreadPoolExecutor`，禁用 `Executors.newXxx`（虚拟线程除外）
- 日志 SLF4J 占位符，异常作为最后参数
- 2 空格缩进、无通配符导入

---

# 第二部分：开发总计划

## 6. 开发阶段总览

```
Phase 0  前置准备 ──────────────────► ✅ 已完成
Phase 1  设计 ──────────────────────► ✅ 已完成（本文档体系）
Phase 2  基础设施与脚手架 ──────────► ⬜ 下一步
Phase 3  通用能力建设 ──────────────► ⬜
Phase 4  业务模块（按依赖顺序）─────► ⬜
Phase 5  测试与质量 ────────────────► ⬜
Phase 6  部署运维 ──────────────────► ⬜
```

**核心原则**：每个 Phase 完成有明确的"完成定义"（DoD），不达标不进下一阶段。

---

## 7. Phase 2：基础设施与脚手架（预计 3-5 天）

**目标**：docker compose up 起中间件，前后端空骨架能互访。

### 7.1 任务清单

| 任务 | 产出 | 验收标准 |
|------|------|---------|
| 2.1 Docker Compose 编排 | `docker-compose.yml` | PG+pgvector / Redis / MinIO 三服务起来 |
| 2.2 后端 Gradle 骨架 | `build.gradle.kts` + 包结构 | `./gradlew bootRun` 能起 8080 |
| 2.3 前端 Vite 骨架 | React + TS + Tailwind | `pnpm dev` 起 5173 |
| 2.4 三层连通验证 | 一个 ping 接口 | 前端调 `/api/ping` 拿到响应 |

### 7.2 技术选型确认点

- [ ] PG 启用 pgvector 扩展（`CREATE EXTENSION vector`）
- [ ] Redis 启用 Stream（默认支持，无需额外配置）
- [ ] MinIO 创建 bucket `interview-guide`
- [ ] 后端配置 `application.yml` 连三中间件
- [ ] 前端 axios 封装 + 请求/响应拦截器骨架

### 7.3 里程碑 M1：地基

**标志**：`docker compose up` 一键起所有服务，浏览器访问前端能调通后端 ping 接口。

---

## 8. Phase 3：通用能力建设（预计 5-7 天）

**目标**：common + infrastructure 层就绪，后续业务模块直接用。

### 8.1 任务清单（按依赖顺序）

| 任务 | 产出 | 说明 |
|------|------|------|
| 3.1 统一响应体系 | `Result<T>` / `BusinessException` / `ErrorCode` / 全局异常处理器 | 所有模块的地基 |
| 3.2 配置体系 | `@ConfigurationProperties` + `.env` | 替代散落的 `@Value` |
| 3.3 MapStruct 基建 | 全局 Mapper 配置 | Entity↔DTO 转换 |
| 3.4 Redis 封装 | `RedisService`（Redisson）| RBucket/RStream/RLock/RAtomicLong |
| 3.5 Stream 异步模板 | `AbstractStreamProducer/Consumer` | 所有异步任务基类 |
| 3.6 限流切面 | `@RateLimit` 注解 + Aspect | GLOBAL/IP/USER 三维度 |
| 3.7 MinIO 文件存储 | `FileStorageService` | 上传/下载/删除 |
| 3.8 文档解析 | `DocumentParseService`（Tika）| 简历+知识库共用 |
| 3.9 PDF 导出 | `PdfExportService`（iText）| + 中文字体 |
| 3.10 AI 调用入口 | `LlmProviderRegistry` + `StructuredOutputInvoker` | 见模块 09 |

### 8.2 里程碑 M2：通用能力就绪

**标志**：能写一个 demo Service 调 LLM 拿结构化输出，能发异步任务走 Stream 消费，能上传文件到 MinIO。

---

## 9. Phase 4：业务模块开发（核心，预计 4-8 周）

**核心原则**：按依赖顺序，每个模块独立闭环。

### 9.1 模块开发顺序（依赖链）

```
第一梯队（地基，必须最先）：
  4.1 多模型管理 (09)  ← 所有 AI 调用的入口
  4.2 用户体系 (03)    ← 身份地基

第二梯队（核心业务，引流闭环）：
  4.3 简历分析 (04)    ← 引流核心 + 面试数据源

第三梯队（扩展业务）：
  4.4 文字面试 (05)    ← 依赖 04 简历 + 09 Provider
  4.5 知识库 RAG (06)  ← 依赖 09 Provider + pgvector

第四梯队（高级功能，可推迟）：
  4.6 语音面试 (07)    ← 依赖 05 评估引擎，复杂度极高
  4.7 面试日程 (08)    ← 独立，任何时候可做
```

### 9.2 每个模块的开发步骤（统一 DoD）

每个业务模块开发时，按以下步骤走：

```
Step 1: 读对应的技术文档（module-design/）+ 功能文档（module-features/）
Step 2: 数据库表设计（Entity + Repository）
Step 3: DTO 设计（record + MapStruct Mapper）
Step 4: Service 实现（业务逻辑 + 事务边界）
Step 5: Controller（路由 + 校验 + 委托）
Step 6: Prompt 模板（如涉及 AI，放 resources/prompts/）
Step 7: 异步任务接入（如涉及，走 Stream 模板）
Step 8: 单元测试（至少 1 个 Service 单测）
Step 9: 集成测试（至少 1 个 Controller 集成测试）
Step 10: 前端页面 + API 客户端
```

### 9.3 各模块详细计划

#### 模块 09：多模型管理（Phase 4.1，预计 3-5 天）

| 任务 | 说明 |
|------|------|
| `LlmProviderEntity` + `LlmGlobalSettingEntity` | 两张表 |
| `ApiKeyEncryptionService` | AES/GCM/NoPadding 加密 |
| `LlmProviderConfigService` | CRUD + 切换默认 |
| `LlmProviderBootstrapService` | 启动引导（空库插入预置） |
| `LlmProviderRegistry` | 运行时 ChatClient 注册表 + reload |
| Controller | Provider CRUD + 测试连通性 |

**关键决策**：照搬源项目。ASR/TTS 第一版不做（语音面试推迟），所以 ASR/TTS 配置先不实现。

#### 模块 03：用户体系（Phase 4.2，预计 5-7 天）

详见 `03-auth-and-role-design.md`。两期：
- **第 1 期**：deviceId + 配额 + 半遮面（对接 04）
- **第 2 期**：注册/登录/JWT + 定时清理

#### 模块 04：简历分析（Phase 4.3，预计 7-10 天）

详见 `04-resume-module-dev.md`。核心：
- 解析路由（Word→Tika / 单栏 PDF→Tika / 多栏 PDF→Qwen-VL）
- 多栏检测（PDFBox 坐标 + 双信号）
- 结构化字段提取 + 点评（两步 LLM）
- 半遮面（对接 03）
- 版本管理（多份独立 + 评分趋势）
- 导出 PDF（iText，登录用户）

#### 模块 05：文字面试（Phase 4.4，预计 7-10 天）

详见 `05-interview-module-dev.md`。核心：
- Skill 驱动出题（11 个方向 + JD 解析）
- 并行出题（简历 60% + 方向 40%）
- 历史题目去重
- 会话管理（Redis 优先 + DB 兜底）
- 统一评估引擎（UnifiedEvaluationService）
- 异步评估（Redis Stream）

#### 模块 06：知识库 RAG（Phase 4.5，预计 5-7 天）

详见 `06-knowledgebase-dev.md`。核心：
- 上传 → Tika 解析 → 异步向量化（Redis Stream）
- pgvector 存储（HNSW + COSINE）
- 查询改写（rewrite）+ 短路检索
- 动态 topK/阈值（按问题长度分档）
- SSE 流式响应
- 会话式多轮（历史回灌）

#### 模块 07：语音面试（Phase 4.6，可推迟）

详见 `07-voice-interview-dev.md`。复杂度极高，建议文字面试跑通后再做：
- WebSocket 编排
- 千问三件套（ASR 长连接 / TTS 短连接 / LLM 无状态）
- 手动 submit 模型
- 回声防护
- 句子级 TTS 并发 + 顺序保证

#### 模块 08：面试日程（Phase 4.7，独立可做）

详见 `08-schedule-dev.md`。核心：
- 三段式解析（规则正则优先 + LLM 兜底）
- 状态机（PENDING/COMPLETED/CANCELLED/RESCHEDULED）
- 定时任务（过期转取消）
- 日历视图（前端）

---

## 10. Phase 5：测试与质量（预计 3-5 天）

| 任务 | 说明 |
|------|------|
| 单元测试 | JUnit5 + Mockito + AssertJ，每个 Service 至少 1 个 |
| 集成测试 | Testcontainers（PG + Redis + MinIO 真实容器）|
| 前端 build | `pnpm build` 无 TS 错误 |
| 端到端验证 | 关键路径手动走一遍（注册→简历→面试→导出）|

---

## 11. Phase 6：部署运维（预计 5-7 天）

服务器：`121.40.130.172`（阿里云杭州，2 核 3.4G Ubuntu）

| 任务 | 说明 |
|------|------|
| 6.1 服务器基线 | Docker 安装 / 防火墙 / swap |
| 6.2 镜像构建 | 后端 Dockerfile（多阶段）+ 前端 Nginx Dockerfile |
| 6.3 生产 compose | 含 Nginx + 应用 + 中间件 |
| 6.4 SSL 证书 | Let's Encrypt + 域名（或自签）|
| 6.5 Nginx 反代 | HTTPS 强制 + 静态托管 + API 转发 |
| 6.6 日志 | 文件 + logrotate（轻量方案）|
| 6.7 监控 | Prometheus + Grafana（Actuator 已埋点）|
| 6.8 备份 | PG 定时 dump + MinIO 数据 |

**里程碑 M5：上线**——`https://域名` 可访问，有监控和备份。

---

## 12. 关键里程碑（节奏建议）

| 里程碑 | 内容 | 标志 |
|--------|------|------|
| **M1 地基** | Phase 2 完成 | docker compose up 起中间件，前后端互访 |
| **M2 通用能力** | Phase 3 完成 | 能调 LLM、发异步任务、传文件 |
| **M3 第一个闭环** | 09+03+04 完成 | 游客上传简历→半遮面→注册→完整报告 |
| **M4 面试闭环** | 05 完成 | 完整模拟面试 + 评分报告 |
| **M5 RAG** | 06 完成 | 知识库检索能增强问答 |
| **M6 上线** | Phase 6 完成 | https 可访问，有监控备份 |

---

## 13. 成本预算（全项目）

### 13.1 AI 调用成本

按 100 个活跃用户/月估算：

| 场景 | 单次成本 | 月成本（100 用户）|
|------|---------|-----------------|
| 简历分析 | ~0.006 元 | ~60 元（每人 10 次）|
| 文字面试（每场 10 题）| ~0.05 元 | ~50 元（每人 1 场）|
| 知识库问答（每次）| ~0.01 元 | ~100 元（每人 10 次）|
| 语音面试（每场）| ~0.5 元 | ~50 元（每人 1 场）|
| **月合计** | — | **~260 元** |

### 13.2 基础设施成本

| 项 | 月费用 |
|---|--------|
| 阿里云 ECS（2 核 3.4G）| ~80 元 |
| 域名 | ~50 元/年 |
| **合计** | **~85 元/月** |

### 13.3 总计

**月运营成本 ≈ 350 元**（100 活跃用户）。单位用户成本 ≈ 3.5 元/月。

---

## 14. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| Java 17 vs 21 兼容 | Spring AI 2.0 可能要 21 | 先试跑，遇坑再升 |
| 服务器内存不足 | 2 核 3.4G 跑 PG+Redis+MinIO+App 紧张 | 限制并发，语音面试推迟 |
| AI 成本失控 | 游客白嫖 | 配额 3 次/天 + 模型降级 |
| 多栏 PDF 误判 | 错走多模态或漏判 | 双信号校验 + LLM layoutIssue 兜底 |
| 语音面试复杂度 | 开发周期长 | 推迟到文字面试跑通后 |
| pgvector 性能 | 知识库大时检索慢 | HNSW 索引 + 动态阈值 |

---

## 15. 文档维护规则

1. **决策变更**：任何技术决策变更，先改对应文档，再改代码
2. **文档同步**：每完成一个模块，更新本文档的 Phase 进度
3. **新增模块**：按 `module-design/` + `module-features/` 双文档规范产出
4. **命名约定**：`{编号}-{模块名}-{design|dev|features}.md`

---

## 附录：技术选型决策记录

| 日期 | 决策 | 理由 |
|------|------|------|
| 2026-07-07 | 复现方式=逐模块重写 | 深度学习 |
| 2026-07-07 | 构建=Gradle / 包管理=pnpm | 对齐上游 |
| 2026-07-07 | Java 先 17 试跑 | 本地现状 |
| 2026-07-08 | 游客仅开放简历 | 聚焦引流，控成本 |
| 2026-07-08 | 简历半遮面 + 12h 缓存 | 引流转化 |
| 2026-07-08 | 登录=手机号+密码 | 学习项目，砍验证码 |
| 2026-07-08 | 简历多栏走 Qwen-VL | 几何判断可靠 |
| 2026-07-08 | 简历版本=多份独立 | 对齐源项目 |
| 2026-07-08 | 语音面试推迟 | 复杂度极高 |
