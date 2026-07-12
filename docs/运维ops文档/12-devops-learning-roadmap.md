# 运维开发学习路线

> 以 interview-guide 项目为实操载体，对标秋招运维/SRE 岗位要求。
> 服务器：121.40.130.172（阿里云 Ubuntu 22.04，2核/3.4G）
>
> 使用方式：读完本文建立全局认知，按 Phase 顺序逐项实操。
> 每完成一个 Phase，在对应任务前打 ✅。

---

## 一、你现在已经学会的（基础运维能力）

这些是你在搭建这个项目过程中已经掌握的：

| 技能 | 具体做了什么 | 对应 JD 要求 |
|------|------------|------------|
| **Linux 基础操作** | SSH 登录、文件操作、权限管理、进程管理 | Linux 常用命令 ✅ |
| **Docker 容器化** | 编写 Dockerfile、docker compose 编排 6 个服务、镜像构建/推送/拉取 | Docker 基本操作 ✅ |
| **Nginx 反向代理** | 配置 Nginx 做前后端分离、/api 转发、SPA 路由 | Nginx 配置 ✅ |
| **域名 & DNS** | 腾讯云配置 DNS A 记录、理解域名解析原理 | 域名解析 ✅ |
| **HTTPS/SSL** | （进行中）Let's Encrypt 申请免费证书、Nginx 配 SSL | HTTPS 部署 🔄 |
| **CI/CD 流水线** | GitHub Actions 自动化编译→构建→推镜像→部署全链路 | 自动化部署 ✅ |
| **镜像仓库** | 阿里云 ACR 存储和分发 Docker 镜像 | 容器镜像管理 ✅ |
| **Git 版本控制** | Git 工作流、commit 规范、分支管理 | 代码管理 ✅ |

---

## 二、秋招运维岗技能全景图

除了 JD 列出的要求，秋招运维/SRE 岗位通常考察以下能力。**标 ⭐ 的是高频面试考点**。

### 2.1 操作系统与网络

| 技能 | 面试频率 | 你的项目怎么练 |
|------|---------|--------------|
| ⭐ Linux 常用命令（top/ps/netstat/ss/lsof/find/grep/awk/sed） | 必问 | 服务器日常巡检 |
| ⭐ 进程管理（systemd/supervisor/信号/僵尸进程） | 高频 | 管理你的 Spring Boot 进程 |
| ⭐ 磁盘管理（df/du/lsblk/fdisk/LVM） | 中频 | 监控服务器磁盘使用 |
| ⭐ 网络基础（TCP 三次握手/DNS/HTTP 协议/负载均衡） | 必问 | 理解你的请求链路 |
| 性能分析（strace/perf/火焰图） | 中频 | 排查慢请求 |
| Shell 脚本 | 高频 | 写自动化运维脚本 |

### 2.2 容器与编排

| 技能 | 面试频率 | 你的项目怎么练 |
|------|---------|--------------|
| ⭐ Docker（Dockerfile 多阶段构建/网络/卷/资源限制） | 必问 | 你的项目已全面使用 |
| ⭐ Docker Compose（服务编排/依赖/健康检查） | 高频 | 你的项目已全面使用 |
| ⭐ Kubernetes 基础（Pod/Service/Deployment/ConfigMap/Ingress） | 必问 | 在服务器上搭 Minikube 练 |
| Helm Chart | 中频 | 把你的 compose 转成 Helm |

### 2.3 CI/CD 与自动化

| 技能 | 面试频率 | 你的项目怎么练 |
|------|---------|--------------|
| ⭐ GitLab CI / GitHub Actions | 必问 | 你的项目已有 GitHub Actions |
| ⭐ 容器镜像管理（Registry/镜像分层/多阶段构建） | 高频 | 你的项目已有 ACR |
| 蓝绿部署 / 金丝雀发布 | 中频 | 在 Nginx 层做流量切分实验 |
| GitOps（ArgoCD） | 中频 | 模拟 GitOps 流程 |

### 2.4 监控与可观测性

| 技能 | 面试频率 | 你的项目怎么练 |
|------|---------|--------------|
| ⭐ Prometheus + Grafana（指标采集/面板/告警） | 必问 | 你的项目 Actuator 已埋点 |
| ⭐ 日志系统（ELK / Loki + Promtail + Grafana） | 高频 | 搭建集中式日志 |
| 链路追踪（Jaeger / SkyWalking） | 中频 | 接 SkyWalking 到 Spring Boot |
| 告警通知（钉钉/企微/邮件） | 高频 | 配 Grafana Alerting |

### 2.5 数据库与中间件

| 技能 | 面试频率 | 你的项目怎么练 |
|------|---------|--------------|
| ⭐ PostgreSQL / MySQL（慢查询/索引/备份/主从） | 必问 | 你的项目用 PG |
| ⭐ Redis（数据类型/持久化/哨兵/集群） | 必问 | 你的项目用 Redis Stream |
| ⭐ 消息队列（Kafka/RabbitMQ） | 高频 | 理解 Redis Stream 原理，可装 Kafka 对比 |
| Nginx 高级（限流/缓存/负载均衡/SSL 终止） | 高频 | 在你的 Nginx 上实操 |

### 2.6 安全与合规

| 技能 | 面试频率 | 你的项目怎么练 |
|------|---------|--------------|
| ⭐ Linux 安全（防火墙/SSH 加固/fail2ban/最小权限） | 高频 | 加固你的服务器 |
| ⭐ HTTPS/TLS（证书链/双向认证/证书管理） | 高频 | 申请和管理证书 |
| 密钥管理（Vault/环境变量注入） | 中频 | 理解 GitHub Secrets 原理 |
| 容器安全（镜像扫描/非 root 运行/只读文件系统） | 中频 | 加固你的 Dockerfile |

### 2.7 云计算与基础设施即代码

| 技能 | 面试频率 | 你的项目怎么练 |
|------|---------|--------------|
| ⭐ 云服务基础（ECS/VPC/SLB/OSS/C DN） | 高频 | 了解你用的阿里云服务 |
| 基础设施即代码（Terraform/Pulumi） | 中频 | 用 Terraform 声明你的阿里云资源 |
| 配置管理（Ansible） | 中频 | 用 Ansible 管理服务器配置 |

---

## 三、分阶段学习计划

每个 Phase 围绕一个主题，以你的现有服务器为实操环境。按优先级和依赖关系排序。

### Phase 1：Linux 基本功强化（预计 1 周）

**干什么**：把 Linux 日常操作练到肌肉记忆。

| 任务 | 具体操作 | 产出 |
|------|---------|------|
| 1.1 编写服务器巡检脚本 | 写一个 shell 脚本，输出 CPU/内存/磁盘/进程 Top10/网络连接/Docker 状态 | 脚本文件 + crontab 定时执行 |
| 1.2 学会查日志 | 用 `journalctl` 查系统日志，`grep`/`awk`/`sed` 做日志分析 | 能快速定位问题 |
| 1.3 进程管理实操 | 用 `systemctl` 管理服务，理解 `kill` 信号（-9 vs -15），查僵尸进程 | 命令笔记 |
| 1.4 磁盘管理 | `df -h` / `du -sh` 查磁盘，理解 inode，清理 Docker 无用镜像/卷 | 磁盘清理记录 |
| 1.5 网络排错工具 | `ss -tlnp` 看端口、`curl -v` 看请求链路、`tcpdump` 抓包 | 排错能力 |

**面试能讲的故事**：我独立管理一台 2核 3.4G 的阿里云服务器，日常用 shell 脚本做巡检，用 systemd 管理所有服务进程。

### Phase 2：安全加固（预计 3 天）

**干什么**：把你现在的服务器从"能跑"提升到"安全地跑"。

| 任务 | 具体操作 | 产出 |
|------|---------|------|
| 2.1 SSH 安全加固 | 禁用密码登录（只用密钥）、改端口（非 22）、禁用 root 直接登录 | 安全配置备份 |
| 2.2 fail2ban 配置 | 装 fail2ban，防 SSH 暴力破解、防 web 扫描 | 规则配置 |
| 2.3 UFW 防火墙精调 | 只开放必要端口（80/443/SSH端口），其余全 deny | 防火墙规则 |
| 2.4 自动安全更新 | 配 `unattended-upgrades`，安全补丁自动装 | 定时任务 |
| 2.5 Docker 安全最佳实践 | 容器非 root 运行、限制 capabilities、只读文件系统 | 改造 Dockerfile |
| 2.6 数据库安全 | PG 只监听 localhost、强密码、限制连接 IP | 安全配置 |

**面试能讲的故事**：我负责服务器的安全基线配置——SSH 密钥登录 + fail2ban 防爆破 + UFW 最小端口原则 + 容器非 root 运行。任何对外服务都有安全考量。

### Phase 3：HTTPS 与证书管理（预计 1 天）

**干什么**：完成正在做的 HTTPS 部署，理解 TLS 原理。

| 任务 | 具体操作 | 产出 |
|------|---------|------|
| 3.1 域名 DNS 配置 | 在腾讯云配 A 记录 → 121.40.130.172 | DNS 生效 |
| 3.2 申请 Let's Encrypt 证书 | certbot + DNS 验证（绕过备案） | 证书文件 |
| 3.3 Nginx 配 HTTPS | 配 443/8443 端口、证书路径、强制 HTTPS 跳转 | 更新 nginx.conf |
| 3.4 证书自动续期 | certbot renew + crontab | 自动化 |
| 3.5 理解 TLS 原理 | 学习对称加密/非对称加密/证书链/DH 密钥交换 | 面试能讲清楚 |

**面试能讲的故事**：我为域名申请了 Let's Encrypt 免费证书，配置了 Nginx SSL 终止，设置了自动续期。能讲清楚 HTTPS 从握手到加密传输的完整过程。

### Phase 4：监控体系搭建（预计 1 周，⭐ 高频考点）

**干什么**：给你的服务加上工业级监控，能回答"现在系统健康吗"。

| 任务 | 具体操作 | 产出 |
|------|---------|------|
| 4.1 Prometheus 部署 | docker compose 加 Prometheus 服务，采集主机指标 + Spring Boot Actuator 指标 | compose 配置 |
| 4.2 Grafana 部署 | 配数据源 Prometheus，导入 JVM/Docker/Linux 标准面板 | 仪表板截图 |
| 4.3 自定义 JVM 面板 | 堆内存/GC/线程数/QPS 可视化 | 面板配置 |
| 4.4 告警规则 | 内存 >80%、磁盘 >85%、服务 down → 发通知 | 告警规则 |
| 4.5 告警通知 | Grafana Alerting → 钉钉/企业微信 webhook → 收到告警 | 告警链路打通 |
| 4.6 ⭐ 理解监控体系原理 | Metrics（Prometheus）/Logging（Loki）/Tracing（Jaeger）三大支柱 | 面试能讲清 |

**面试能讲的故事**：我基于 Prometheus + Grafana 搭建了完整的监控体系，采集了 JVM 指标（堆内存/GC/线程）、Docker 容器指标（CPU/内存/网络）和业务指标（QPS/响应时间），配置了内存>80%的告警规则，对接钉钉通知。

### Phase 5：集中式日志系统（预计 3 天）

**干什么**：不用 ssh + docker logs 手动看日志，所有日志聚合到一处检索。

| 任务 | 具体操作 | 产出 |
|------|---------|------|
| 5.1 Loki + Promtail 部署 | docker compose 加 Loki（存日志）+ Promtail（采集日志） | compose 配置 |
| 5.2 采集 Docker 容器日志 | Promtail 配规则采集 /var/lib/docker/containers 下的日志 | 采集规则 |
| 5.3 Grafana 日志查询 | 在 Grafana 里用 LogQL 检索日志，按时间/关键词过滤 | 检索能力 |
| 5.4 日志告警 | 日志中出现 ERROR 关键字 → 触发告警 | 告警规则 |

**面试能讲的故事**：我搭建了 Loki + Promtail 集中式日志系统，在 Grafana 统一查询和告警，替代了传统的手动 ssh + docker logs 看日志方式。

### Phase 6：备份与灾备（预计 2 天）

**干什么**：数据丢了能恢复。

| 任务 | 具体操作 | 产出 |
|------|---------|------|
| 6.1 PostgreSQL 定时备份 | `pg_dump` + crontab 每天凌晨备份，保留最近 7 天 | 备份脚本 |
| 6.2 MinIO 数据备份 | MinIO 数据目录打包备份 | 备份策略 |
| 6.3 备份恢复演练 | 模拟数据丢失，从备份恢复 PG 数据库 | 恢复文档 |
| 6.4 Docker 卷备份 | 备份命名卷数据 | 备份脚本 |

**面试能讲的故事**：我为 PostgreSQL 配置了每日自动备份，保留 7 天，并做过一次真实的恢复演练。所有备份文件放在独立路径，不会被误删。

### Phase 7：Kubernetes 入门（预计 2 周，⭐ 必考）

**干什么**：Docker Compose 是单机方案，K8s 是集群方案。面试必问但手头没有 K8s 集群。

| 任务 | 具体操作 | 产出 |
|------|---------|------|
| 7.1 K8s 核心概念学习 | Pod / Deployment / Service / ConfigMap / Secret / Ingress / HPA | 概念笔记 |
| 7.2 Minikube 本地搭建 | 在你 Windows 上用 Minikube 起一个单节点 K8s | 集群运行 |
| 7.3 把项目"翻译"成 K8s YAML | 把 docker-compose.yml 转成 Deployment + Service + ConfigMap 等 | K8s 配置文件 |
| 7.4 理解 K8s 网络 | ClusterIP vs NodePort vs LoadBalancer vs Ingress | 能讲清楚 |
| 7.5 理解调度与自愈 | Node 挂了 Pod 自动迁移、liveness/readiness probe | 能讲清楚 |

**注意**：你的服务器只有 3.4G，装不了 K8s。Minikube 装在你本地 Windows（用 Docker Desktop 驱动）。这部分学的是概念和配置，不是生产部署。

**面试能讲的故事**：我用 Docker Compose 管理单机部署，同时可以把服务"翻译"成 K8s Deployment + Service 配置。理解 Pod 调度、滚动更新、自愈机制和网络模型。

### Phase 8：运维自动化脚本（预计 1 周）

**干什么**：把重复操作变成一行命令。

| 任务 | 具体操作 | 产出 |
|------|---------|------|
| 8.1 一键部署脚本 | 代替 docker compose up -d --build，加健康检查 + 失败回滚 | deploy.sh |
| 8.2 一键备份脚本 | PG dump + MinIO 数据 + Docker 配置打包 | backup.sh |
| 8.3 日志分析脚本 | 统计最近 1 小时 ERROR 数量、Top10 错误类型 | analyze.sh |
| 8.4 健康检查脚本 | 检查所有服务 + 磁盘 + 内存，异常发通知 | health-check.sh |

**面试能讲的故事**：我写了一套运维脚本，覆盖部署、备份、监控、健康检查。把日常运维从"敲命令"变成"跑脚本"。

### Phase 9：面试专项准备（预计 1 周）

**干什么**：把实操经验翻译成面试能讲的故事。

| 任务 | 具体操作 |
|------|---------|
| 9.1 整理项目简历条目 | 提炼 3-5 条运维相关的简历描述 |
| 9.2 准备高频面试题 | Linux/Docker/K8s/网络/监控/CI/CD 各准备 3 个能讲的故事 |
| 9.3 模拟故障排查 | 故意制造故障（停容器/改错配置/磁盘写满），练习排错流程 |
| 9.4 画系统架构图 | 把你项目的完整架构画出来，能在白板上讲清楚 |
| 9.5 准备反问问题 | 面试结束时问面试官的问题 |

---

## 四、学习节奏建议

```
第 1-2 周：Phase 1（Linux 基础）+ Phase 2（安全加固）+ Phase 3（HTTPS）
           → 把服务器"收拾干净"

第 3-4 周：Phase 4（监控）+ Phase 5（日志）+ Phase 6（备份）
           → 可观测性三件套

第 5-7 周：Phase 8（自动化脚本）+ Phase 7（K8s 入门，可以在本地并行学）
           → 自动化 + 容器编排

第 8 周：  Phase 9（面试专项）+ 查漏补缺
           → 整理输出
```

**总计约 8 周**，每天投入 2-3 小时。如果时间紧，K8s 部分可以压缩（先学概念和写 YAML，不搭本地环境也行）。

---

## 五、你这台服务器的资源约束（心里要有数）

| 资源 | 现状 | 能加什么 |
|------|------|---------|
| 内存 3.4G | PG+Redis+MinIO+SpringBoot+Nginx ≈ 2.3G | **Prometheus(~200M) + Grafana(~150M) + Loki(~300M)** ≈ 650M，勉强够 |
| CPU 2核 | 常态低负载 | 监控和日志采集是轻量的 |
| 磁盘 40G（用了 16%） | 有空间 | 日志会膨胀，要配日志轮转和保留策略 |

**如果内存不够的降级方案**：
- Node Exporter（采集主机指标）→ 必须留
- Prometheus → 如果内存紧张，用 VictoriaMetrics（更省内存）替代
- Loki → 如果内存紧张，先不做集中日志，用 `docker logs` + `logrotate` 兜底

---

## 六、文档体系（建议你维护）

```
docs/运维学习笔记/
├── 01-linux-commands.md          # Linux 常用命令速查
├── 02-security-hardening.md      # 安全加固操作记录
├── 03-https-setup.md             # HTTPS 部署过程
├── 04-monitoring-setup.md        # 监控搭建记录
├── 05-logging-setup.md           # 日志系统搭建记录
├── 06-backup-strategy.md         # 备份策略与恢复演练
├── 07-k8s-study-notes.md         # K8s 学习笔记
├── 08-automation-scripts.md      # 运维脚本集合
├── 09-interview-prep.md          # 面试准备（简历条目 + 常见问题）
└── 10-incident-log.md            # 故障记录（什么故障、怎么排查、怎么修的）
```

**每做完一个操作就记下来。** 面试时面试官问"你做过什么"，你不是背书，而是讲你真实踩过的坑、修过的故障。**第 10 份"故障记录"是你面试最有力的武器**——面试官最喜欢听"我遇到过这个问题，我是这么排查和解决的"。
