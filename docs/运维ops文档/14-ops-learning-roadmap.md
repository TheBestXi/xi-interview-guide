# 运维开发学习路线（实操版）

> 以 interview-guide 项目为训练场，从"能跑"到"生产可用"。
> 服务器：121.40.130.172（阿里云杭州，2核/3.4G/40G）
>
> **使用方式**：这不是一份"看完就忘"的文档。每个 Phase 是你下次打开终端要做的事。
> 做完一项打 ✅，做完一个 Phase 把收获补到对应的运维笔记里。
>
> 和 `12-devops-learning-roadmap.md` 的关系：12 是技能全景图（面试考什么），本文是怎么练（一步一步做什么）。

---

## 开头：你现在的水平，实话实说

你现在能干什么：
- 写 Dockerfile、配 docker-compose、把 6 个容器跑起来 ✅
- 配 GitHub Actions + ACR，git push 自动上线 ✅
- Nginx 反代、DNS 解析 ✅

你现在还不会的（也是这份计划要解决的）：
- CI/CD 只有一条线：push 即部署，没有测试、没有 Review、没有多环境
- 服务出问题你不知道——没有监控告警
- 看日志要 SSH 上去 `docker logs`——没有日志聚合
- 回滚靠手动改 tag——没有一键回滚
- 域名有 HTTP 没 HTTPS——用户数据明文传输
- 服务器安全基线基本没做——22 端口裸奔

**一句话**：你的项目"能跑"，但离"生产可用"还差一截。这份计划就是填这个差距。

---

## 路线总览

```
Phase 0  加固 CI/CD（加测试 + 分支策略 + 多环境）    ← 先把现在的坑填上
Phase 1  监控告警（Prometheus + Grafana + 钉钉通知）  ← 别等挂了才知道
Phase 2  日志聚合（Loki + Promtail）                  ← 别再 SSH + docker logs
Phase 3  域名 + HTTPS（Let's Encrypt DNS 验证）       ← 绕过备案拿证书
Phase 4  安全加固（SSH + fail2ban + UFW + Docker）    ← 别裸奔
Phase 5  备份与灾备（PG 自动备份 + 恢复演练）          ← 数据丢了能救回来
Phase 6  运维自动化（一键部署/回滚/健康检查脚本）       ← 把操作变成脚本
Phase 7  K8s 入门（Minikube + 翻译 compose→K8s YAML） ← 面试必考
Phase 8  面试包装（简历条目 + 故障故事 + 架构图）      ← 把经验变成 offer
```

每个 Phase 里我都标注了：**真实工作要求 vs 学习项目可以偷的懒**。你心里要有数——面试时面试官会追问"为什么这样做"。

---

## Phase 0：加固 CI/CD —— 先把现在的坑填上

**现状问题**：push main → 直接部署。没有测试、没有 Review、没有环境隔离。这在真实工作是会被打回来的。

### 0.1 加测试环节（30 分钟）

**干什么**：在 deploy.yml 的编译检查后面，加一步跑测试。

**为什么**：你现在只有 `compileJava`——只确认代码能编译，不确认逻辑对不对。如果有人改了简历解析逻辑但引入 bug，编译过、部署了、线上炸了。

**怎么做**：

在 deploy.yml 的步骤 3（编译检查）后面加：

```yaml
- name: 🧪 后端单元测试
  run: ./gradlew :app:test --no-daemon
```

**真实工作**：CI 必须跑全量测试，测试不通过流水线直接红，不允许部署。
**学习偷懒**：你目前测试覆盖率低，先跑起来就行，后续补测试。

### 0.2 分支策略：别直接推 main（1 小时）

**干什么**：引入 feature 分支 + PR 机制。

**为什么**：你现在 `git push origin main` 直接部署。真实工作里 main 是保护分支，你不能直接推。正确流程是：

```
你开 feature/xxx 分支 → 写代码 → 提 PR → 同事 Review → 合并到 main → CI 自动部署
```

**怎么做**：

1. 在 GitHub 仓库 Settings → Branches → 给 main 加保护规则：
   - Require a pull request before merging
   - Require approvals（先设 0，自己 Review 自己；真实工作是 1-2 人）

2. 改 deploy.yml 触发条件：main 分支 push 仍然触发部署，但加上 PR 触发测试（不部署）：

```yaml
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
```

3. 加一个 job 判断是不是 PR（PR 只测试不部署）：

```yaml
jobs:
  test:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      # ... 只做编译 + 测试，不构建镜像不部署

  build-and-deploy:
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    # ... 你现在的东西
```

**真实工作**：PR 必须至少 1 人 Approve，CI 全绿才能合并。
**学习偷懒**：你自己 Review 自己，先养成"不直接推 main"的习惯。

### 0.3 多环境：dev 和 prod 分开（2 小时）

**干什么**：在服务器上跑两套环境——dev（测试用）和 prod（线上）。

**为什么**：你现在只有一套环境。想试个新配置？直接在线上试——改炸了用户就看到了。真实工作至少有 dev/test/staging/prod 四套。

**怎么做**：

1. 服务器上建两个目录：
   ```
   /opt/interview-guide/prod/   ← 现在的，线上
   /opt/interview-guide/dev/    ← 新建，测试
   ```

2. dev 用不同端口，避免和 prod 冲突：
   ```yaml
   # dev/docker-compose.yml 里改端口
   frontend:
     ports:
       - "8081:80"    # prod 用 80，dev 用 8081
   app:
     ports:
       - "8082:8080"  # prod 用 8080，dev 用 8082
   ```

3. dev 用独立的 PG/Redis 数据卷（加 `_dev` 后缀），别跟 prod 共享数据。

4. CI/CD 加一个手动触发的 dev 部署 workflow（不改 deploy.yml，新建 `deploy-dev.yml`）：

```yaml
name: Deploy to Dev
on:
  workflow_dispatch:  # 手动触发，在 Actions 页面点按钮
```

**真实工作**：dev → test → staging → prod 四套环境，每套配置独立管理。staging 和 prod 完全一致（只差数据）。
**学习偷懒**：先搞 dev + prod 两套。你的服务器 3.4G 内存跑不动四套完整环境。面试时能讲清楚"为什么生产要四套环境"就行。

### 0.4 配置文件管理：别散落（1 小时）

**干什么**：把散落的配置收拢到一处，用环境变量区分 dev/prod。

**为什么**：你现在 `.env` 在服务器上手动维护，百炼 Key、PG 密码、加密 Key 散落各处。哪天要改个配置，你记得改哪几处吗？

**怎么做**：

1. 服务器上 `.env` 分级：
   ```
   /opt/interview-guide/prod/.env      ← prod 环境变量
   /opt/interview-guide/dev/.env       ← dev 环境变量
   ```

2. 敏感信息（百炼 Key）通过 GitHub Secrets → CI/CD 注入，不要在服务器明文存。

3. 非敏感配置（端口、内存限制）放在 `docker-compose.yml` 的 `environment` 块，用 `${VAR:-默认值}` 语法。

**真实工作**：配置中心（Apollo/Nacos/Consul）集中管理，改配置不重启。密钥用 Vault/KMS 管理，有审计日志。
**学习偷懒**：`.env` + GitHub Secrets 对个人项目够用。面试时能讲"为什么企业要用配置中心"就行。

### Phase 0 简历条目

> 负责 CI/CD 流水线设计与实现：基于 GitHub Actions + 阿里云 ACR 搭建自动化部署流水线，引入分支保护 + PR Review 机制，实现 dev/prod 双环境隔离。编译、测试、镜像构建、部署全链路自动化，单次部署耗时 < 6 分钟。

---

## Phase 1：监控告警 —— 别等挂了才知道

**现状**：没有监控。服务器 OOM 了？AI 调用超时了？你只有等哪天打不开网页了才知道。

### 1.1 Prometheus + Grafana 部署（2 小时）

**干什么**：在 docker-compose 里加 Prometheus（采集指标）+ Grafana（可视化面板）。

**为什么**：监控是运维的眼镜。没监控 = 闭眼开车。

**怎么做**：

1. `docker-compose.yml` 加两个服务：

```yaml
prometheus:
  image: prom/prometheus:latest
  container_name: interview-prometheus
  volumes:
    - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
    - prometheus_data:/prometheus
  command:
    - '--storage.tsdb.retention.time=15d'  # 数据保留 15 天
    - '--config.file=/etc/prometheus/prometheus.yml'
  restart: unless-stopped

grafana:
  image: grafana/grafana:latest
  container_name: interview-grafana
  ports:
    - "3000:3000"
  volumes:
    - grafana_data:/var/lib/grafana
  environment:
    - GF_SECURITY_ADMIN_USER=admin
    - GF_SECURITY_ADMIN_PASSWORD=your_password
  restart: unless-stopped
```

2. 写 `prometheus/prometheus.yml` 配置，抓 Spring Boot Actuator 指标 + 宿主机指标：

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'spring-boot'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['app:8080']

  - job_name: 'node'
    static_configs:
      - targets: ['node-exporter:9100']
```

3. 你的 Spring Boot 项目需要加 `micrometer-registry-prometheus` 依赖，暴露 `/actuator/prometheus` 端点。

4. 加 node-exporter 采集宿主机 CPU/内存/磁盘：

```yaml
node-exporter:
  image: prom/node-exporter:latest
  container_name: interview-node-exporter
  volumes:
    - /proc:/host/proc:ro
    - /sys:/host/sys:ro
    - /:/rootfs:ro
  command:
    - '--path.procfs=/host/proc'
    - '--path.sysfs=/host/sys'
    - '--path.rootfs=/rootfs'
  restart: unless-stopped
```

5. Grafana 导入现成面板（ID 搜）：
   - JVM 面板：4701（JVM Micrometer）
   - Docker 面板：179（Docker monitoring）
   - Linux 面板：1860（Node Exporter）

**真实工作**：Prometheus + Grafana 是标配。生产环境的 Prometheus 要配远程存储（不丢数据）、联邦集群（分担负载）。面板要根据业务自定义。
**学习偷懒**：直接用社区面板，够用。你的服务器 3.4G 内存加这三个（Prometheus ~200M + Grafana ~150M + node-exporter ~50M）还可以。

### 1.2 告警规则（1 小时）

**干什么**：配置"出问题时通知你"。

**配什么规则**：

| 告警 | 条件 | 为什么 |
|------|------|--------|
| 内存 > 80% | `node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes < 0.2` | 你的服务器只有 3.4G，内存是稀缺资源 |
| 磁盘 > 85% | `node_filesystem_avail_bytes / node_filesystem_size_bytes < 0.15` | Docker 日志膨胀很快 |
| 服务 down | `up == 0` | 任何容器挂了立刻知道 |
| JVM 堆内存 > 80% | `jvm_memory_used_bytes / jvm_memory_max_bytes > 0.8` | Java OOM 的前兆 |

**怎么做**：

在 `prometheus/prometheus.yml` 里加：

```yaml
rule_files:
  - 'alert.rules.yml'
```

写 `prometheus/alert.rules.yml`，然后在 Grafana 里配 Alerting → Notification channel → 钉钉 Webhook。

### 1.3 告警通知到钉钉/企微（30 分钟）

**干什么**：Grafana Alerting 发消息到你的手机。

**怎么做**：

1. 钉钉群 → 群设置 → 智能群助手 → 添加机器人 → Webhook → 拿到 URL
2. Grafana → Alerting → Contact points → New → DingDing → 填 Webhook URL
3. 测试：手动停一个容器，看手机会不会响

**真实工作**：告警分级（P0 电话 + 短信，P1 钉钉，P2 邮件），有值班轮转，告警静默窗口（凌晨 3 点非紧急不发）。
**学习偷懒**：钉钉一个渠道全收，够用。

### Phase 1 简历条目

> 搭建 Prometheus + Grafana 监控体系，采集 JVM（堆内存/GC/线程）、Docker 容器、Linux 宿主机三重指标。配置内存 >80%、磁盘 >85%、服务存活告警规则，对接钉钉实时通知。

---

## Phase 2：日志聚合 —— 别每次都 SSH

**现状**：看日志 = `ssh xi` → `docker logs interview-app`。容器重启日志就丢。多个服务日志分散在各自容器里，找问题要开四五个终端。

### 2.1 Loki + Promtail 部署（1.5 小时）

**干什么**：所有容器日志自动收集到 Loki，在 Grafana 里统一检索。

**为什么**：集中式日志让你能"搜一次看全部"——而不是"挨个容器 tail"。

**怎么做**：

1. docker-compose 加 Loki + Promtail：

```yaml
loki:
  image: grafana/loki:latest
  container_name: interview-loki
  volumes:
    - ./loki/loki-config.yaml:/etc/loki/local-config.yaml
    - loki_data:/loki
  command: -config.file=/etc/loki/local-config.yaml
  restart: unless-stopped

promtail:
  image: grafana/promtail:latest
  container_name: interview-promtail
  volumes:
    - /var/lib/docker/containers:/var/lib/docker/containers:ro
    - /var/log:/var/log:ro
    - ./promtail/promtail-config.yaml:/etc/promtail/config.yml
  command: -config.file=/etc/promtail/config.yml
  restart: unless-stopped
```

2. Grafana 里加 Loki 数据源（URL: `http://loki:3100`）

3. 在 Grafana Explore 里用 LogQL 搜：
   ```
   {container_name="interview-app"} |= "ERROR"
   {container_name="interview-app"} | json | level = "ERROR"
   ```

**真实工作**：日志量大时 Loki 前面加 Kafka 削峰；日志保留策略（30 天热、90 天冷）；日志脱敏（手机号、身份证自动打码）。
**学习偷懒**：Loki ~300M 内存，你的服务器加得上。保留 7 天日志就行。

### 2.2 日志告警（30 分钟）

**干什么**：日志里出现 ERROR 关键字 → 发通知。

**怎么做**：Grafana Alerting 里加一条规则，LogQL 查询：
```
count_over_time({container_name="interview-app"} |= "ERROR" [5m]) > 3
```

5 分钟内超过 3 条 ERROR → 发钉钉。

### Phase 2 简历条目

> 搭建 Loki + Promtail 集中式日志系统，统一采集多容器日志，在 Grafana 实现指标-日志联动查询。配置 ERROR 日志 5 分钟超阈值自动告警，替代传统 SSH + docker logs 排障方式。

---

## Phase 3：域名 + HTTPS —— 数据不能明文跑

**现状**：`http://xi266405.top` HTTP 200，但没 HTTPS。浏览器标"不安全"，语音面试功能（需要麦克风权限）用不了。

**你的特殊约束**：域名没 ICP 备案，不能用 80 端口做 HTTP 验证。但你**可以用 DNS 验证绕过**。

### 3.1 Let's Encrypt DNS 验证（1 小时）

**干什么**：用 certbot + DNS 插件申请证书，不需要开 80 端口。

**为什么 DNS 验证能绕过备案**：
- HTTP 验证：Let's Encrypt 访问 `http://你的域名/.well-known/acme-challenge/xxx` → 你的服务器必须在 80 端口响应 → 国内没备案 80 端口运营商可能拦截
- DNS 验证：Let's Encrypt 让你在 DNS 加一条 TXT 记录 → 它查 DNS 确认域名是你的 → 完全不经过你的服务器端口 → 绕过备案限制

**怎么做**：

```bash
# 服务器上装 certbot
ssh xi
apt install -y certbot

# DNS 手动验证方式（腾讯云 DNS）
certbot certonly --manual --preferred-challenges dns -d xi266405.top -d '*.xi266405.top'

# certbot 会输出一条 TXT 记录，让你添加到 DNS
# 去腾讯云 DNS 控制台加 _acme-challenge.xi266405.top → TXT → certbot 给的值
# 等 DNS 生效（dig _acme-challenge.xi266405.top TXT 验证）
# 回车继续
```

**注意**：你的域名 DNS 在哪个平台？（腾讯云？阿里云？）如果是阿里云 DNS，可以用 `certbot-dns-aliyun` 插件全自动续期。如果是腾讯云，也有对应插件。手动验证每次续期要重新操作——不过 Let's Encrypt 90 天有效期，手动也还行。

**真实工作**：用 certbot 的 DNS 插件（自动更新 TXT 记录）+ crontab 自动续期。证书到期前 30 天自动续，永不中断。
**学习偷懒**：先用手动 DNS 验证搞到第一张证书。后续配自动续期。

### 3.2 Nginx 配 HTTPS（30 分钟）

**干什么**：改 frontend 容器的 Nginx 配置，开 443 端口 + 强制 HTTPS 跳转 + HTTP/2。

**怎么做**：

改 `frontend/nginx.conf`：

```nginx
server {
    listen 80;
    server_name xi266405.top;
    return 301 https://$host$request_uri;  # 强制跳 HTTPS
}

server {
    listen 443 ssl http2;
    server_name xi266405.top;

    ssl_certificate     /etc/letsencrypt/live/xi266405.top/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/xi266405.top/privkey.pem;

    # TLS 安全配置（禁用老旧的 TLS 1.0/1.1，只用 1.2/1.3）
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:...;
    ssl_prefer_server_ciphers on;

    # 其余配置保持不变（静态资源 + /api 反代）
}
```

3. 改 docker-compose.yml 把证书目录挂进 frontend 容器，并暴露 443 端口。
4. 阿里云安全组放行 443 端口。
5. `docker compose restart frontend` 生效。

### 3.3 证书自动续期（30 分钟）

```bash
# 测试续期（不真续，模拟）
certbot renew --dry-run

# 加 crontab 每月跑一次续期
echo "0 3 1 * * certbot renew --quiet && docker restart interview-frontend" | crontab -
```

### Phase 3 简历条目

> 为域名 xi266405.top 部署 HTTPS：通过 Let's Encrypt DNS 验证方式在无备案情况下获取免费 SSL 证书，配置 Nginx SSL 终止 + TLS 1.2/1.3 + HTTP/2 + 强制 HTTPS 跳转，证书到期前自动续期。

---

## Phase 4：安全加固 —— 别裸奔

**现状**：SSH 22 端口密码登录开着。没有 fail2ban。UFW 可能没配或规则不全。Docker 容器跑 root。

### 4.1 SSH 加固（20 分钟）

```bash
# 编辑 /etc/ssh/sshd_config
PermitRootLogin prohibit-password   # root 只能用密钥，禁密码
PasswordAuthentication no           # 所有用户禁用密码登录
Port 2222                          # 改掉默认 22（减少扫描噪音）

systemctl restart sshd
```

**注意**：改端口前先把新端口加到阿里云安全组。改完别关当前终端，新开一个验证能登录再关旧的——锁外面就麻烦了。

### 4.2 fail2ban（20 分钟）

```bash
apt install -y fail2ban

# 创建 /etc/fail2ban/jail.local
[sshd]
enabled = true
port = 2222
maxretry = 3
bantime = 3600  # 封 1 小时

[nginx-http-auth]
enabled = true

systemctl enable fail2ban --now
```

### 4.3 UFW 防火墙（15 分钟）

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 8080/tcp
ufw allow 2222/tcp   # 新的 SSH 端口
ufw enable
```

**注意**：Docker 的端口映射会绕过 UFW。要配 `iptables` 规则或者在 `docker-compose.yml` 里服务端口只 expose 不 publish（让 Nginx 反代，不直接暴露）。

### 4.4 Docker 安全（30 分钟）

**干什么**：容器不用 root 跑，限制能力。

**怎么做**：改 docker-compose.yml 里 app 和 frontend 服务：

```yaml
app:
  # ... 现有配置
  user: "1000:1000"           # 非 root 用户
  cap_drop:
    - ALL                     # 去掉所有 Linux capabilities
  cap_add:
    - NET_BIND_SERVICE        # 只保留绑定端口的能力
  read_only: true             # 根文件系统只读
  tmpfs:
    - /tmp                     # /tmp 可写
```

**真实工作**：容器安全扫描（Trivy/Clair）、镜像签名（Cosign）、Pod Security Policy/Admission Control。
**学习偷懒**：先做 user + cap_drop + read_only。够拦住 90% 的容器逃逸。

### Phase 4 简历条目

> 负责服务器安全基线加固：SSH 密钥登录 + 禁用密码 + 非默认端口 + fail2ban 防暴力破解，UFW 最小端口原则。容器非 root 运行 + 最小 capabilities + 根文件系统只读，降低容器逃逸风险。

---

## Phase 5：备份与灾备 —— 数据丢了能救回来

### 5.1 PostgreSQL 自动备份（30 分钟）

```bash
# /opt/scripts/backup-pg.sh
#!/bin/bash
BACKUP_DIR="/opt/backups/pg"
RETENTION_DAYS=7
mkdir -p "$BACKUP_DIR"
docker exec interview-postgres pg_dump -U postgres interview_guide \
  | gzip > "$BACKUP_DIR/db_$(date +%Y%m%d_%H%M).sql.gz"
find "$BACKUP_DIR" -mtime +$RETENTION_DAYS -delete
echo "Backup done: $(date)"
```

crontab：每天凌晨 4 点跑。

### 5.2 恢复演练（1 小时）

**干什么**：别等真丢了才第一次恢复——那时候你手忙脚乱。

**怎么做**：

1. 故意删掉 PG 数据卷：`docker compose down -v postgres`
2. 重建：`docker compose up -d postgres`
3. 恢复：`gunzip < db_20260712_0400.sql.gz | docker exec -i interview-postgres psql -U postgres -d interview_guide`
4. 验证：查几条数据，启动 app 确认功能正常。

**把这一步写进文档**。面试时这是你的王牌故事："我做过恢复演练，知道步骤、耗时、会遇到什么问题。"

### Phase 5 简历条目

> 制定 PostgreSQL 每日自动备份策略（pg_dump + gzip + 7 天轮转），完成一次模拟故障恢复演练（删库→重建→恢复→验证），恢复耗时 < 3 分钟。

---

## Phase 6：运维自动化 —— 把操作变成脚本

**干什么**：SSH 上去敲命令 → 变成本地一行脚本搞定。

### 6.1 一键部署脚本 `deploy.sh`（1 小时）

```bash
#!/bin/bash
# 本地跑到服务器一条龙
set -e

echo "=== 开始部署 ==="
ssh xi "cd /opt/interview-guide/prod && docker compose pull && docker compose up -d"

echo "=== 等待健康检查 ==="
for i in {1..10}; do
  if curl -sf http://121.40.130.172:8080/actuator/health > /dev/null; then
    echo "✅ 部署成功"
    exit 0
  fi
  sleep 5
done

echo "❌ 健康检查失败，回滚..."
ssh xi "cd /opt/interview-guide/prod && docker compose down && docker compose up -d"  # 用旧镜像重启
exit 1
```

### 6.2 一键回滚脚本 `rollback.sh`（30 分钟）

```bash
#!/bin/bash
# 回滚到上一个 commit 的镜像
COMMIT=$1  # 例如：./rollback.sh abc1234
ssh xi "
  cd /opt/interview-guide/prod
  sed -i 's/:latest/:${COMMIT}/g' docker-compose.yml
  docker compose up -d
"
```

**真实工作**：回滚应该是 CI/CD 系统自带的能力——GitHub Actions 的 Rollback、K8s 的 `rollout undo`。
**学习偷懒**：脚本够用。但要能讲清楚"为什么生产用 K8s rollout undo 不是改 tag"。

### Phase 6 简历条目

> 编写一键部署/回滚/健康检查 Shell 脚本，将日常运维操作从"手动敲命令"转变为"跑脚本"。部署失败自动健康检查 + 回滚。

---

## Phase 7：K8s 入门 —— 面试必考

**说明**：你的服务器 3.4G 内存跑不动 K8s（K8s 自己就要 1-2G）。这部分在你**本地 Windows 上做**，目的是学概念和写 YAML，不是生产部署。

### 7.1 Minikube 本地跑起来（1 小时）

装 Minikube（用 Docker Desktop 驱动），起一个单节点 K8s：

```bash
minikube start --driver=docker --memory=2048
kubectl get nodes  # 应该看到一个 Ready 的节点
```

### 7.2 把你的 compose 翻译成 K8s YAML（2 小时）

目标：把 `docker-compose.yml` 里的 6 个服务翻译成 K8s 资源。

| Compose | K8s | 说明 |
|---------|-----|------|
| `services.postgres` | Deployment + Service + PVC | 有状态服务需要 PVC |
| `services.redis` | Deployment + Service | 无状态（先不配持久化） |
| `services.minio` | Deployment + Service + PVC | |
| `services.app` | Deployment + Service + ConfigMap | 配置用 ConfigMap/Secret |
| `services.frontend` | Deployment + Service + Ingress | 入口用 Ingress |
| `depends_on` | initContainers | K8s 没有 depends_on，用 initContainer 等 PG 就绪 |

**产出**：一份 `k8s/` 目录，里面有 `app-deployment.yaml`, `app-service.yaml`, `pg-deployment.yaml` 等。**这份 YAML 是面试时你拿出来给面试官看的**——证明你不只会 compose，也懂 K8s 资源配置。

### 7.3 理解 K8s 核心概念（1 小时，纯读书）

| 概念 | 一句话 | 面试会怎么问 |
|------|--------|------------|
| Pod | 最小调度单位，一个或多个容器共享网络和存储 | "Pod 和容器的区别？" |
| Deployment | 管理 Pod 的副本数 + 滚动更新策略 | "滚动更新怎么做的？" |
| Service | 给 Pod 提供稳定的访问入口（IP 不变） | "ClusterIP vs NodePort vs LoadBalancer？" |
| ConfigMap/Secret | 配置和密钥和代码分离 | "为什么不用环境变量？" |
| Ingress | HTTP 路由入口（类似 Nginx 反代）| "Ingress 和 Service 的区别？" |
| PVC | 持久化存储（类似 Docker Volume）| "StatefulSet 和 Deployment 区别？" |
| liveness/readiness probe | 存活检查/就绪检查 | "两个 probe 的区别？" |

### 7.4 关键面试题（理解就行，不用全实操）

1. **Pod 挂了 K8s 怎么处理？** → kubelet 发现 liveness probe 失败 → 杀 Pod → Deployment 按 replicas 数创建新 Pod
2. **Node 挂了怎么办？** → 控制器发现 Node NotReady → 把上面的 Pod 调度到其他健康 Node 重建
3. **滚动更新是什么？** → 逐步替换旧 Pod 为新 Pod（先起新的 → 健康检查过了 → 杀一个旧的 → 循环）
4. **ConfigMap 更新后 Pod 会自动重启吗？** → 不会。要手动 rollout restart 或用 reloader 工具
5. **Service 怎么找到 Pod？** → 通过 Label Selector（不是 IP）。Pod 有标签 `app: interview-app`，Service 选 `app: interview-app`

### Phase 7 简历条目

> 掌握 Kubernetes 核心概念（Pod/Deployment/Service/ConfigMap/Ingress/PVC），能将 Docker Compose 编排翻译为 K8s YAML 资源配置。理解调度、自愈、滚动更新、网络模型等核心机制。

---

## Phase 8：面试包装 —— 把经验变成 offer

### 8.1 简历条目汇总

把你前面做的事浓缩成 5-7 条简历描述。关键原则：**用数字、用动词开头、用结果收尾**。

```markdown
## 运维开发能力

- **CI/CD 流水线**：基于 GitHub Actions + 阿里云 ACR 搭建自动化部署流水线，
  实现编译、测试、镜像构建、多环境部署全链路自动化，引入分支保护 + PR Review
  机制，单次部署耗时 < 6 分钟
- **容器化部署**：Docker Compose 编排 6 服务（PostgreSQL+Redis+MinIO+Spring
  Boot+React+Nginx），配置资源限制与健康检查，实现一键部署与回滚
- **监控告警体系**：搭建 Prometheus + Grafana + Loki 可观测性平台，采集 JVM/
  Docker/Linux 三重指标，配置内存/磁盘/服务存活告警规则，对接钉钉通知
- **Linux 运维**：独立管理阿里云 ECS 服务器（Ubuntu 22.04），完成安全加固
  （SSH 密钥/UFW/fail2ban/Docker 权限最小化）、HTTPS 部署（Let's Encrypt
  DNS 验证 + Nginx SSL 终止）、PostgreSQL 每日自动备份与恢复演练
- **Kubernetes**：掌握 Pod/Deployment/Service/Ingress 等核心资源，能将 Docker
  Compose 编排转换为 K8s YAML，理解调度、自愈、滚动更新机制
```

### 8.2 面试故事准备

面试官最喜欢听的不是"我搭了什么"，而是**"我遇到过什么问题 → 我怎么排查的 → 怎么解决的"**。

准备 3 个故事：

| 故事 | 情节 |
|------|------|
| **OOM 排查** | 服务器内存不够 → Java 进程被杀 → 查 `dmesg` 看到 OOM Killer → 加 `-Xmx768m` + Docker `mem_limit` → 从此内存稳定 |
| **镜像拉取失败** | Docker Hub 国内限速 → `docker pull` 卡死几小时 → 换国内镜像加速器四源轮询 → 问题解决。还顺便把 CI/CD 改成 ACR 推送，不走 Docker Hub |
| **恢复演练** | 模拟生产数据丢失 → 从备份恢复 PG → 发现恢复后序列号冲突（sequence 没重置）→ 修复脚本 → 重新演练成功 |

### 8.3 架构图

画一张你能在白板上讲清楚的架构图。必须包含：
- 用户 → Nginx（HTTPS 终止）→ 前端静态资源 / 后端 API
- 后端 → PG / Redis / MinIO / 外部 AI API
- 监控链路：被监控对象 → Prometheus → Grafana → 告警
- 日志链路：容器日志 → Promtail → Loki → Grafana
- CI/CD 链路：GitHub → Runner → ACR → 服务器

---

## 学习节奏建议

```
第 1 周：Phase 0（CI/CD 加固）→ 把流水线搞规范
第 2 周：Phase 1（监控）+ Phase 3（HTTPS）→ 并行推进
第 3 周：Phase 2（日志）+ Phase 4（安全加固）
第 4 周：Phase 5（备份）+ Phase 6（自动化脚本）
第 5 周：Phase 7（K8s 入门，本地做）
第 6 周：Phase 8（面试包装 + 整理文档 + 故障故事）
```

每天投入 1-2 小时，6 周走完。如果时间紧，K8s 可以压缩到只学概念不写 YAML。

---

## 关键区分：真实工作 vs 学习项目偷懒

这是我作为带过人的运维给你的清单——面试时面试官能听出你是不是真的干过。

| 方面 | 学习项目（你现在可以做的）| 真实工作（你知道为什么就行）|
|------|------------------------|---------------------------|
| 环境 | dev + prod 两套 | dev → test → staging → prod 四套，每套独立 |
| CI/CD 触发 | push main 即部署 | PR → Review → 合并 → 部署，有审批卡点 |
| 监控告警 | 钉钉通知你一个人 | 值班轮转、告警分级（P0-P3）、静默窗口 |
| 日志 | Loki 保留 7 天 | Kafka 削峰 + 热冷分层 + 日志脱敏 |
| 配置管理 | .env + GitHub Secrets | Apollo/Nacos + Vault/KMS |
| 备份恢复 | 手动恢复演练 | 定期自动恢复演练 + 异地备份 + 备份加密 |
| 容器安全 | user + cap_drop + read_only | 镜像扫描 + 签名 + Admission Control |
| K8s | Minikube 单节点 + 学概念 | 多节点集群 + Helm + GitOps(ArgoCD) |
| HTTPS | certbot 手动 DNS 验证 | certbot 自动 DNS 插件 + 多域名 SAN 证书 |
| 回滚 | 脚本改镜像 tag | K8s rollout undo / 蓝绿部署 |

**面试时的说法**："我在个人项目里实践了这些能力（说你实际操作的部分），在公司环境下规模更大、工具更专业（说你理解的企业方案）。"

---

## 文档体系（你在做的过程中维护）

```
docs/运维ops文档/
├── 10-deployment-ops.md           ← 已有，部署运维手册
├── 11-cicd-setup-guide.md         ← 已有，CI/CD 搭建指南
├── 12-devops-learning-roadmap.md  ← 已有，技能全景图
├── 14-ops-learning-roadmap.md     ← 本文，实操路线图
├── 15-ops-notes/                  ← 新建，实操笔记
│   ├── phase0-cicd-hardening.md       ← Phase 0 做完后写
│   ├── phase1-monitoring.md           ← Phase 1 做完后写
│   ├── phase2-logging.md
│   ├── phase3-https.md
│   ├── phase4-security.md
│   ├── phase5-backup.md
│   ├── phase6-automation.md
│   ├── phase7-k8s.md
│   └── phase8-interview-prep.md
└── 16-incident-log.md             ← 故障记录（最重要的一份）
```

**特别注意 `16-incident-log.md`**：每次遇到故障——不管是你搞出来的还是意外发生的——记录下来：

```
## 2026-07-12：服务器 OOM
- 现象：网页打不开，docker compose ps 显示 app 容器 Exited
- 排查：dmesg | grep -i oom → 看到 Java 进程被 OOM Killer 杀了
- 根因：大量请求导致 Java 堆内存飙升超过 1200m 限制
- 修复：加 swap、调低 Xmx
- 教训：3.4G 不适合做高并发，提前加限流
```

这份文档是你的面试核武器。面试官问"你遇到过什么故障"，你不是编故事，是翻这份记录讲。

---

*最后更新：2026-07-12*
