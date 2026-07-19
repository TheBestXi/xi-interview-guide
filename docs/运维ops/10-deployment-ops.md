# 部署运维文档

> interview-guide 服务器部署记录与日常运维手册。
> 服务器：121.40.130.172（阿里云杭州，Ubuntu 22.04，2核/3.4G/+2G swap/40G）
> SSH 别名：`xi`（root + ed25519 密钥）

---

## 1. 部署架构

```
用户浏览器 ──HTTPS──► Nginx (frontend 容器, :80)
                        ├── 静态资源（React build 产物）
                        └── /api/* 反代 → app:8080
                                              │
                    ┌──────────────────────────┘
                    ▼
              Spring Boot 后端 (app 容器, :8080)
              JAVA_OPTS=-Xmx768m  mem_limit=1200m
                    │
        ┌───────────┼───────────┬─────────────┐
        ▼           ▼           ▼             ▼
   PostgreSQL    Redis      MinIO        DashScope
   (pgvector)   (:6379)    (:9000)       (外部API)
   (:5432)                 (:9001控制台)
```

6 个容器：postgres / redis / minio / createbuckets(一次性) / app / frontend

---

## 2. 关键配置（部署时已固化）

### 2.1 服务器目录

```
/opt/interview-guide/          # 项目根
├── .env                       # 环境变量（含百炼 Key，不进 git）
├── docker-compose.yml         # 生产编排（已加 JAVA_OPTS + mem_limit）
├── app/Dockerfile             # 已改支持 JAVA_OPTS
└── ...                        # 源码
```

### 2.2 .env 内容

```bash
AI_BAILIAN_API_KEY=sk-07473b5a...        # 百炼（LLM+Embedding+ASR+TTS）
APP_AI_CONFIG_ENCRYPTION_KEY=...         # Provider Key 加密（部署后勿改）
AI_MODEL=qwen3.5-flash                   # 默认 LLM 模型
POSTGRES_PASSWORD=password               # PG 密码（对齐 compose 默认）
```

### 2.3 资源限制（防 OOM）

| 服务 | 限制 | 理由 |
|------|------|------|
| app（Java）| `mem_limit: 1200m` + `-Xmx768m -Xms512m` | Java 默认吃 1/4 物理内存，必须限制 |
| 其他 | 无限制 | PG/Redis/MinIO/Nginx 自身克制 |

**3.4G 内存分配**：系统 ~500M + PG ~400M + Redis ~50M + MinIO ~150M + **app ~1.2G** + Nginx ~20M ≈ 2.3G，留 1.1G 缓冲。

---

## 3. 日常运维命令

### 3.1 启停

```bash
ssh xi
cd /opt/interview-guide

docker compose ps                          # 看状态
docker compose up -d                       # 启动（已构建过）
docker compose down                        # 停止（数据保留在卷）
docker compose down -v                     # 停止并删数据（慎用）
docker compose up -d --build               # 重新构建并启动（代码更新后）
docker compose restart app                 # 只重启后端
```

### 3.2 日志

```bash
docker compose logs -f app                 # 实时看后端日志
docker compose logs -f frontend            # 实时看 Nginx 日志
docker compose logs --tail=100 app         # 后端最近 100 行
docker compose logs app 2>&1 | grep -i error   # 搜错误
```

### 3.3 进容器调试

```bash
docker exec -it interview-app sh           # 进后端容器
docker exec -it interview-postgres psql -U postgres -d interview_guide  # 进 PG
docker exec -it interview-redis redis-cli  # 进 Redis
```

### 3.4 资源监控

```bash
free -h                                    # 内存
df -h /                                    # 磁盘
docker stats                               # 各容器资源占用
docker compose ps                          # 健康状态
```

---

## 4. 健康检查

### 4.1 各服务健康端点

| 服务 | 检查方式 |
|------|---------|
| 后端 | `curl http://localhost:8080/actuator/health`（在服务器上）|
| PG | `docker exec interview-postgres pg_isready -U postgres` |
| Redis | `docker exec interview-redis redis-cli ping` |
| MinIO | `curl http://localhost:9000/minio/health/live` |
| 前端 | `curl http://localhost/` |

### 4.2 外部访问地址

| 服务 | 地址 | 说明 |
|------|------|------|
| 前端 | http://121.40.130.172 | 需安全组放行 80 |
| 后端 API | http://121.40.130.172:8080 | 需安全组放行 8080 |
| Swagger | http://121.40.130.172:8080/swagger-ui.html | 同上 |
| MinIO 控制台 | http://121.40.130.172:9001 | 需安全组放行 9001（账号 minioadmin/minioadmin）|

---

## 5. 阿里云安全组（部署时必须开）

UFW 放行不够，阿里云控制台「安全组→入方向」必须单独加规则：

| 端口 | 协议 | 授权对象 | 用途 | 必须？ |
|------|------|---------|------|-------|
| 80 | TCP | 0.0.0.0/0 | 前端 HTTP | ✅ |
| 8080 | TCP | 0.0.0.0/0 | 后端 API（调试）| ✅ |
| 9001 | TCP | 0.0.0.0/0 | MinIO 控制台 | 可选 |
| 443 | TCP | 0.0.0.0/0 | HTTPS（上 SSL 后）| 后续 |

---

## 6. 代码更新流程

改了代码后，同步并重建：

```bash
# 本地：打包（排除重目录）
cd D:/interview-guide/reference
tar --exclude='.git' --exclude='node_modules' --exclude='.gradle' \
    --exclude='build' --exclude='dist' --exclude='.env' \
    -czf /tmp/project.tar.gz .

# 上传 + 解压
scp /tmp/project.tar.gz xi:/opt/interview-guide/
ssh xi "cd /opt/interview-guide && tar -xzf project.tar.gz && rm project.tar.gz"

# 服务器：重建（只重建改动的镜像）
ssh xi "cd /opt/interview-guide && docker compose up -d --build"
```

---

## 7. 数据备份

### 7.1 PG 逻辑备份（手动）

```bash
ssh xi "docker exec interview-postgres pg_dump -U postgres interview_guide > /opt/backup/db_$(date +%Y%m%d).sql"
```

### 7.2 定时备份（建议加 crontab）

```bash
# 每天凌晨 4 点备份 PG
ssh xi 'echo "0 4 * * * docker exec interview-postgres pg_dump -U postgres interview_guide > /opt/backup/db_\$(date +\%Y\%m\%d).sql && find /opt/backup -mtime +7 -delete" | crontab -'
```

### 7.3 数据卷位置

```
postgres_data  → PG 数据
redis_data     → Redis 持久化
minio_data     → 上传的文件
```

`docker compose down` 不删卷，数据保留；`docker compose down -v` 才删。

---

## 8. 已知限制与风险

| 项 | 说明 | 影响 |
|----|------|------|
| 内存紧张 | 3.4G 跑全套，已限 Java 堆 768M | 高并发可能 OOM，需观察 |
| 无 HTTPS | 当前 HTTP，443 未上 SSL | 浏览器会提示不安全，麦克风（语音面试）需要 HTTPS |
| 无域名 | 仅 IP | SSL 证书需要域名（Let's Encrypt）|
| 无监控 | 未接 Prometheus/Grafana | 故障靠看日志 |
| 无日志收集 | 日志在容器内 | `docker compose logs` 看，容器删则丢 |

---

## 9. SSL 升级路径（后续）

1. 备域名 → DNS 解析到 121.40.130.172
2. 服务器装 certbot
3. 申 Let's Encrypt 证书
4. 改 frontend 容器的 Nginx 配置，开 443 + 强制 HTTPS
5. 安全组放行 443

语音面试（需要麦克风权限）**必须 HTTPS**，所以这块等做语音面试时再补。
