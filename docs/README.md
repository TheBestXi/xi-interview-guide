# 文档索引

> 本目录是 interview-guide 项目的所有文档集合。按职能分目录,中文命名。

## 目录结构

```
docs/
├── README.md          ← 本文件（文档索引）
├── 总览文档/           ← 全局性文档（产品总纲、技术宪法、路线图）
├── 模块设计/           ← 各业务模块的开发技术文档
├── 模块功能/           ← 各业务模块的功能/产品文档
├── 运维ops/            ← 部署、CI/CD、运维学习
└── 提示词/             ← AI 角色扮演提示词
```

## 文档清单

### 总览文档（先读这几份建立全局认知）

| 文档 | 作用 |
|------|------|
| [00-master-features.md](./总览文档/00-master-features.md) | **总功能文档**：产品总纲、模块关系、用户旅程、全局决策 |
| [00-master-tech.md](./总览文档/00-master-tech.md) | **总技术文档**：技术栈、架构、开发总计划（Phase 0-6）|
| [01-technical-design.md](./总览文档/01-technical-design.md) | 技术宪法：选型/架构/编码规范 |
| [02-development-roadmap.md](./总览文档/02-development-roadmap.md) | 开发路线图：Phase 总览 + 模块依赖 |

### 模块设计（开发技术文档，给写代码的人看）

| 编号 | 模块 | 文档 |
|------|------|------|
| 03 | 用户体系（登录/角色/游客）| [03-auth-and-role-design.md](./模块设计/03-auth-and-role-design.md) |
| 04 | 简历分析（解析/点评/半遮面）| [04-resume-module-dev.md](./模块设计/04-resume-module-dev.md) |
| 05 | 对话式面试引擎（动态追问）| [05-interview-conversational-dev.md](./模块设计/05-interview-conversational-dev.md) |
| -  | 语音面试架构（源项目扒透）| [voice-interview-architecture.md](./模块设计/voice-interview-architecture.md) |

### 模块功能（功能/产品文档，给产品/甲方看）

| 编号 | 模块 | 文档 |
|------|------|------|
| 03 | 用户体系 | [03-auth-and-role-features.md](./模块功能/03-auth-and-role-features.md) |
| 04 | 简历分析 | [04-resume-module-features.md](./模块功能/04-resume-module-features.md) |
| 05 | 对话式面试 | [05-interview-conversational-features.md](./模块功能/05-interview-conversational-features.md) |

### 运维ops（部署/CI/CD/学习）

| 文档 | 作用 |
|------|------|
| [10-deployment-ops.md](./运维ops/10-deployment-ops.md) | 部署运维手册：启停/日志/备份/排障 |
| [11-cicd-setup-guide.md](./运维ops/11-cicd-setup-guide.md) | CI/CD 搭建完整指导（含原理+步骤+踩坑）|
| [12-devops-learning-roadmap.md](./运维ops/12-devops-learning-roadmap.md) | DevOps 学习路线图（旧版）|
| [13-ops-fundamentals.md](./运维ops/13-ops-fundamentals.md) | 运维基础概念 |
| [14-ops-learning-roadmap.md](./运维ops/14-ops-learning-roadmap.md) | 运维学习路线图（新版）|
| [15-ops-notes/phase0-cicd-hardening.md](./运维ops/15-ops-notes/phase0-cicd-hardening.md) | 阶段笔记：CI/CD 加固 |

### 提示词（AI 角色扮演）

| 文档 | 作用 |
|------|------|
| [00-client-roleplay-prompt.md](./提示词/00-client-roleplay-prompt.md) | 甲方角色扮演提示词（需求评审用）|
| [00-dev-assistant-prompt.md](./提示词/00-dev-assistant-prompt.md) | 开发助手角色扮演提示词（写代码用）|

---

## 命名规范

- **编号**：`{两位数}-{模块名}-{类型}.md`（如 `05-interview-conversational-dev.md`）
- **类型后缀**：`-dev`（开发技术）/ `-features`（功能产品）/ `-design`（旧版设计文档,保留）
- **编号一致**：模块设计/模块功能里同编号的文档是配套的（如 `03-xxx-design.md` 对应 `03-xxx-features.md`）

## 阅读建议

| 你的角色 | 建议阅读顺序 |
|---------|------------|
| **第一次接触项目** | 总览文档 → 模块功能 → 模块设计 |
| **要写代码** | 总览文档 → 对应模块的"模块设计" |
| **要评审需求** | 总览文档 → 对应模块的"模块功能" |
| **要部署/运维** | 运维ops 全部 |
