# 技术设计文档（TDD）

> 本文档是 interview-guide 复现项目的总体技术宪法，后续所有模块开发、技术选型变更都以此为准。
> 参照源：`reference/` 目录下的上游仓库（Snailclimb/interview-guide）。

---

## 1. 项目定位

AI 面试辅助平台，三大核心能力：

1. **简历智能分析** — 上传 PDF/DOCX，自动结构化抽取 + AI 深度点评
2. **AI 模拟面试** — 基于简历内容生成针对性问题，支持文本 + 语音双模式
3. **知识库 RAG 检索** — 面试八股文/技术文档向量化检索，增强问答准确性

复现策略：**参照源码逐模块重写**（非直接 clone 跑），目的是深度学习每个模块的设计与实现。

---

## 2. 技术选型（与上游对齐）

### 2.1 后端

| 类别 | 选型 | 版本 | 说明 |
|------|------|------|------|
| 语言 | Java | 21（本地先 17 试跑） | 上游硬性要求 21 |
| 框架 | Spring Boot | 4.1.0 | 模块化 starter 设计 |
| 构建 | Gradle | 8.x（wrapper） | 自带 gradlew，无需全局安装 |
| AI | Spring AI | 2.0.0 | OpenAI 兼容协议接 DashScope |
| AI 工具 | spring-ai-agent-utils | 0.10.0 | 社区增强库 |
| ORM | Spring Data JPA | 随 Boot | Repository 模式 |
| 数据库 | PostgreSQL | 16 | + pgvector 向量插件 |
| 缓存/MQ | Redis | 7 | Redisson 4.0 + Redis Stream 异步 |
| 对象存储 | MinIO | latest | S3 兼容（上游 README 写 RustFS，compose 实际用 MinIO） |
| 文档解析 | Apache Tika | 2.9.2 | PDF/DOCX/TXT |
| 对象映射 | MapStruct | 1.6.3 | 配合 Lombok |
| API 文档 | SpringDoc OpenAPI | 3.0.2 | Swagger UI |
| PDF 导出 | iText | 8.0.5 | + font-asian 中文 |
| 语音 | DashScope SDK | 2.22.7 | Qwen3 ASR/TTS |
| 监控 | Micrometer + Prometheus | 随 Boot | Actuator 暴露指标 |

### 2.2 前端

| 类别 | 选型 | 版本 |
|------|------|------|
| 框架 | React | 18.3 |
| 语言 | TypeScript | 5.6 |
| 构建 | Vite | 5.4 |
| 样式 | TailwindCSS | 4.1 |
| 路由 | react-router-dom | 7.11 |
| HTTP | axios | 1.7 |
| 图表 | recharts | 3.6 |
| 图标 | lucide-react + react-icons | — |
| Markdown | react-markdown + remark-gfm | — |
| 动画 | framer-motion | 12.x |
| 日历 | react-big-calendar | 1.19 |
| 虚拟列表 | react-virtuoso | 4.x |
| 端侧推理 | onnxruntime-web | 1.24（语音相关） |
| 包管理 | pnpm | 9.x / 10.x |

### 2.3 基础设施

| 组件 | 用途 |
|------|------|
| Docker + Docker Compose | 本地开发 + 生产部署统一编排 |
| PostgreSQL + pgvector | 业务数据 + 向量数据 |
| Redis | 缓存 + Redis Stream 异步队列 |
| MinIO | 简历/文档/头像对象存储 |
| Nginx | 前端静态托管 + API 反向代理 + SSL 终止 |
| Let's Encrypt / certbot | SSL 证书（部署阶段） |

### 2.4 AI 模型策略

- **默认 Provider**：阿里云百炼 DashScope，模型 `qwen3.5-flash`
- **多 Provider 支持**：Kimi / DeepSeek / GLM / 自定义 OpenAI 兼容端点
- **Provider 管理**：数据库存储 + API Key 加密（`APP_AI_CONFIG_ENCRYPTION_KEY`）
- **统一接入**：`LlmProviderRegistry.getChatClientOrDefault(provider)`
- 向量维度 **1024**，距离类型 **COSINE**

---

## 3. 系统架构

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
│       │            │             │              │          │
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

## 4. 后端模块划分

包根：`com.interview.guide`（上游用 `interview.guide`，我们统一加 `com` 前缀）

### 4.1 业务模块 `modules/`

| 模块 | 包名 | 核心职责 | 依赖外部 |
|------|------|----------|----------|
| 简历分析 | `modules.resume` | 上传、解析、结构化、AI 点评 | Tika / MinIO / LLM |
| 模拟面试 | `modules.interview` | 问题生成、对话、评分报告 | LLM / Redis Stream |
| 面试日程 | `modules.interviewschedule` | 日历管理、提醒 | — |
| 知识库 | `modules.knowledgebase` | 文档入库、向量化、RAG 检索 | pgvector / LLM |
| 模型管理 | `modules.llmprovider` | 多 Provider 配置、Key 加密 | DB / 加密 |
| 语音面试 | `modules.voiceinterview` | STT/TTS、语音对话 | DashScope ASR/TTS |

每个模块内部自包含 `controller / service / repository / entity / dto` 分层。

### 4.2 通用层 `common/`

| 子包 | 职责 |
|------|------|
| `ai` | LLM 调用封装、Provider 注册表、结构化输出 |
| `annotation` | `@RateLimit` 等自定义注解 |
| `aspect` | 限流切面等 AOP |
| `async` | 异步模板、Stream 生产/消费者基类 |
| `config` | 各类 `@Configuration` |
| `constant` | 常量、枚举 |
| `evaluation` | 面试评估相关 |
| `exception` | `BusinessException` / `ErrorCode` / 全局异常处理器 |
| `model` | 通用模型（如分页、上下文） |
| `result` | `Result<T>` 统一响应 |
| `transaction` | 事务相关工具 |

### 4.3 基础设施层 `infrastructure/`

| 子包 | 职责 |
|------|------|
| `file` | MinIO/S3 文件存储封装 |
| `export` | iText PDF 导出 |
| `redis` | Redisson 配置、Stream 封装 |
| `mapper` | MapStruct 全局映射配置 |

---

## 5. 编码规范（摘自上游 AGENTS.md，作为我们的硬性约定）

- Controller 只做路由/校验/委托，**不含业务逻辑**
- Service 承担业务编排，`@Transactional` **只在 Service 层**且范围最小
- 对外响应统一 `Result<T>`，**禁止返回 Entity**
- 业务异常用 `BusinessException(ErrorCode.XXX, "描述")`，全局处理器返回 HTTP 200 + `Result.error`
- 请求体用不可变 `record`，命名后缀 `XxxRequest/Response/DTO/Entity`
- Entity→DTO 优先 MapStruct
- 构造器注入 + Lombok `@RequiredArgsConstructor`
- **不散落 `@Value`**，用 `@ConfigurationProperties`
- LLM/S3/外部 HTTP **不得在事务内调用**
- 线程池显式 `ThreadPoolExecutor`，禁用 `Executors.newXxx`
- 日志 SLF4J 占位符，异常作为最后参数
- 2 空格缩进、无通配符导入

---

## 6. 数据库设计要点

- `ddl-auto`：开发 `update`，**生产禁用**，用 Flyway/手写 SQL
- pgvector：向量列 `vector(1024)`，索引 `<=>`(COSINE)
- 敏感字段（API Key）应用层加密后存库
- 软删除：`deleted` 字段 + `@Where` 或查询显式过滤

详细 ER 图在模块开发阶段（Phase 4）逐模块产出。

---

## 7. 开发环境

| 项 | 值 |
|----|-----|
| 本地 OS | Windows 11 |
| 后端端口 | 8080 |
| 前端 dev 端口 | 5173（Vite 默认）|
| PG | localhost:5432 (db=interview_guide, user=postgres, pwd=password) |
| Redis | localhost:6379 |
| MinIO | API localhost:9000 / 控制台 localhost:9001 (minioadmin/minioadmin) |
| 服务器 | 121.40.130.172（阿里云杭州，SSH 22 通，443 待部署阶段开）|

---

## 8. 待确认 / 风险项

| 项 | 状态 | 处理 |
|----|------|------|
| Java 版本 | 本地 17，上游要 21 | 先试跑，遇 Spring AI 2.0 特性问题再升 |
| pnpm 全局安装 | `D:\node.js\` 不可写 | 需管理员授权一次，或全程用 `corepack pnpm` |
| 服务器 SSH 密钥 | 用户自行配置中 | 等用户确认用户名 + 私钥路径 |
| AI API Key | 未申请 | 需注册阿里云百炼，开发到 AI 模块时再处理 |
| 域名 | 仅有 IP | SSL 部署阶段需要域名（或用 IP 自签证书） |
