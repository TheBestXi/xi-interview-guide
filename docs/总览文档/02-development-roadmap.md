# 开发路线图

> 配合 `01-technical-design.md` 使用。定义从脚手架到上线的完整执行顺序。

---

## Phase 总览与依赖关系

```
Phase 0  前置准备 ──────────────────────────────► (进行中)
  └─ 工具链 / 服务器 SSH / 参考仓库

Phase 1  设计 ──────────────────────────────────► (文档输出中)
  └─ 技术设计文档 ✓ / 本路线图 ✓ / 模块详细设计(随开发迭代)

Phase 2  基础设施与脚手架 ──────────────────────► (下一步)
  ├─ 2.1 Docker Compose 编排(PG+pgvector/Redis/MinIO)
  ├─ 2.2 后端 Gradle 多模块骨架 + 基础 common 层
  ├─ 2.3 前端 Vite+React+TS 骨架 + axios 封装
  └─ 2.4 三层连通性验证(前后端 + 中间件)

Phase 3  通用能力建设（common + infrastructure）
  ├─ Result<T> / BusinessException / ErrorCode / 全局异常
  ├─ @ConfigurationProperties 配置体系 + .env
  ├─ MapStruct 基建
  ├─ Redis/Redisson + Stream 异步模板
  ├─ @RateLimit 限流切面
  ├─ MinIO 文件存储封装
  └─ AI: LlmProviderRegistry + 结构化输出 + Prompt 模板

Phase 4  业务模块（按依赖顺序，每个模块独立闭环）
  4.1 llmprovider   ← 最底层，被所有 AI 模块依赖
  4.2 resume        ← 简历上传/解析/点评（依赖 4.1）
  4.3 knowledgebase ← 知识库 RAG（依赖 4.1 + pgvector）
  4.4 interview     ← 模拟面试（依赖 4.2 的简历数据）
  4.5 voiceinterview← 语音面试（依赖 4.4 + DashScope ASR/TTS）
  4.6 interviewschedule ← 面试日程（独立，可并行）

Phase 5  测试与质量
  ├─ 单元测试 JUnit5 + Mockito + AssertJ
  ├─ 集成测试（H2 + Testcontainers）
  └─ 前端 build 校验

Phase 6  部署运维（服务器 121.40.130.172）
  ├─ 6.1 服务器基线（Docker/防火墙/swap）
  ├─ 6.2 镜像构建（后端 Dockerfile + 前端 Nginx Dockerfile）
  ├─ 6.3 生产 docker-compose（含 Nginx + 应用）
  ├─ 6.4 SSL 证书（Let's Encrypt + 域名，或自签）
  ├─ 6.5 Nginx 反代 + HTTPS 强制
  ├─ 6.6 日志（Loki/ELK 或文件 + logrotate）
  ├─ 6.7 监控（Prometheus + Grafana，Actuator 已埋点）
  └─ 6.8 备份（PG 定时 dump + MinIO 数据）
```

---

## Phase 4 各模块的"完成定义"（DoD）

每个业务模块必须满足才算闭环：

- [ ] Entity + Repository（含自定义查询）
- [ ] Request/Response record + MapStruct Mapper
- [ ] Service 接口 + 实现（含事务边界）
- [ ] Controller + 输入校验
- [ ] 接入统一异常与 Result
- [ ] Prompt 模板（如涉及 AI）放在 `resources/prompts/`
- [ ] 至少 1 个 Service 单测 + 1 个 Controller 集成测试
- [ ] 前端对应页面 + API 客户端 + 类型定义
- [ ] 更新本路线图勾选

---

## 模块开发顺序的依赖理由

```
llmprovider ─────► 必须最先，所有 AI 调用的入口
    │
    ├──► resume         (需要 LLM 点评简历)
    ├──► knowledgebase  (需要 embedding + LLM)
    │
    └──► interview      (需要 LLM 生成问题，且读简历数据)
            │
            └──► voiceinterview  (在 interview 基础上加语音)

interviewschedule ──► 与 AI 无强依赖，任何时候可做
```

建议先做 **llmprovider → resume** 这条最短链路，跑通"上传简历→AI 点评"完整闭环，验证整个技术栈，再扩展其余模块。

---

## 关键里程碑（建议节奏）

| 里程碑 | 内容 | 标志 |
|--------|------|------|
| M1 地基 | Phase 2 完成 | `docker compose up` 起中间件，前后端空骨架能互访 |
| M2 第一个闭环 | resume 模块完成 | 浏览器上传 PDF，后端解析+AI 点评，前端展示结果 |
| M3 RAG | knowledgebase 完成 | 知识库检索能增强面试问答 |
| M4 面试 | interview 完成 | 完整模拟面试对话 + 评分报告 |
| M5 上线 | Phase 6 完成 | https://域名 可访问，有监控和备份 |

---

## 决策记录

| 日期 | 决策 | 理由 |
|------|------|------|
| 2026-07-07 | 复现方式=逐模块重写 | 用户选择，深度学习 |
| 2026-07-07 | 构建工具=Gradle | 对齐上游，避免翻译成本 |
| 2026-07-07 | 前端包管理=pnpm | 对齐上游 |
| 2026-07-07 | Java 先用 17 试跑 | 用户本地现状，遇坑再升 21 |
| 2026-07-07 | SSH 用户自行配密钥 | 用户已有密钥 |
