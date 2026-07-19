# 运维基础概念——用你的项目讲明白

> 这份文档不讲理论，用你 interview-guide 项目里的真实文件一行行解释。
> 目标：以后 AI 帮你生成 Dockerfile，你能看懂它在干什么——出问题了能排查。

---

## 一、Dockerfile 是什么——"装软件的食谱"

### 最简单的类比

你去一家新公司，入职第一天要装开发环境。老员工给你一张纸条：

```
1. 下载 JDK 21 安装包
2. 安装到 D 盘
3. 配好 JAVA_HOME
4. 把项目代码 clone 下来
5. 编译
6. 运行
```

你照着做，环境搭好了。**Dockerfile 就是这张纸条，但它不是给人看的——是给 Docker 引擎看的。**

区别在于：人照着纸条搭环境可能搭错（少装个依赖、版本不对），Docker 照着 Dockerfile 搭**永远一模一样**。

### 你的后端 Dockerfile 拆解

看 `app/Dockerfile`，我标了行号：

```dockerfile
# 行 5
FROM gradle:8.14-jdk21 AS builder
```

**FROM = 选基础"操作系统+工具"组合包**

这句话的意思是："我要用 gradle:8.14-jdk21 这个官方打包好的环境当起点"。

这个镜像里有什么？Ubuntu 系统 + JDK 21 + Gradle 构建工具，已经全装好了。你不用从空白 Linux 开始装——就像新电脑已经装好了 Windows + Office。

`AS builder` 是给它起个别名，后面引用用。

```dockerfile
# 行 7
WORKDIR /workspace
```

**设定工作目录。** 相当于你打开终端 `cd /workspace`。后续的 `COPY`、`RUN` 命令默认都在这个目录下操作。

```dockerfile
# 行 13-15
COPY settings.gradle gradlew ./
COPY gradle gradle
COPY app/build.gradle app/
```

**COPY = 把本地文件复制到镜像里**

这里分了三次 COPY，顺序很有讲究——不是为了整洁，是为了**缓存**。

原理：Docker 构建是一层层叠上去的。每一层有缓存：**如果这一层的输入没变，就用缓存，跳过执行。**

你改代码最频繁的是什么？src/ 目录下的 Java 文件。`build.gradle` 改吗？很少改。所以：

- 先 COPY `build.gradle` → 这层缓存住
- 然后 `RUN gradle dependencies` 下载依赖 → 这层也缓存住
- 最后 COPY `src/` 源码 → 只有这层每次重新执行

如果你一次性 `COPY . .`（把所有文件复制进去），你改一行 Java 代码 → Docker 认为"所有文件都变了"→ 重新下载依赖 → 慢死。

**这就是为什么你的 Dockerfile 比单纯的 `COPY . .` 多好几行。** 不是啰嗦，是工程实践。

```dockerfile
# 行 27
RUN gradle dependencies --no-daemon || true
```

**RUN = 在镜像里执行命令**

这条是"预下载依赖"，让后面的编译不用再等下载。

`|| true` 的意思是：**即使这个命令失败了，也别停，继续。** 为什么？因为这个步骤是优化（加速构建），不是必须的。失败了后面 `bootJar` 会重新下载。

```dockerfile
# 行 37
RUN gradle :app:bootJar --no-daemon -x test
```

编译你的 Spring Boot 项目，产出是一个 jar 包。

```dockerfile
# 行 44
FROM eclipse-temurin:21-jre
```

**第二个 FROM！这就是多阶段构建。**

上面用的是 `gradle:8.14-jdk21`——这个镜像有完整的 JDK + Gradle，800MB+。

现在换 `eclipse-temurin:21-jre`——这个镜像只有 Java 运行时（JRE），~200MB。

为什么搞两个阶段？**编译需要大家伙，运行只需要小家伙。** 编译环境用完就扔，最终镜像只保留运行需要的东西。

类比：你在厨房做菜，需要锅碗瓢盆菜刀砧板（第一阶段）。做完端上桌，只需要盘子（第二阶段）。你不会把砧板也端上桌。

```dockerfile
# 行 49
COPY --from=builder /workspace/app/build/libs/*.jar app.jar
```

从第一阶段（`builder`）把编译好的 jar 文件拿出来，放到当前镜像。`--from=builder` 是跨阶段复制。

```dockerfile
# 行 56
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**ENTRYPOINT = 容器启动时执行什么命令。** 这个镜像被运行时，自动执行 `java -jar app.jar`。

`sh -c "java $JAVA_OPTS -jar app.jar"` —— 用 shell 解析 `$JAVA_OPTS` 环境变量。如果不包 `sh -c`，`$JAVA_OPTS` 不会展开，Java 吃默认内存（~850MB），你的 3.4G 服务器就 OOM 了。

**这就是你之前踩坑后改的**——原来写的是 `ENTRYPOINT ["java", "-jar", "app.jar"]`，`JAVA_OPTS` 环境变量不生效。

---

## 二、docker-compose.yml 是什么——"乐高拼装图纸"

### 最简单的类比

Dockerfile 定义了**单个容器怎么做**。compose 定义了**多个容器怎么配合**。

你开一家餐厅：
- Dockerfile = 每种菜的做法（鱼香肉丝怎么做、回锅肉怎么做）
- docker-compose.yml = 厨房布局图（灶台在哪、冰箱在哪、出菜口在哪）

你的项目需要 6 个服务（PG、Redis、MinIO、初始化任务、后端、前端），compose 告诉 Docker：把这 6 个容器启动起来，并且让它们能互相找到。

### 逐段拆解

```yaml
postgres:
  image: pgvector/pgvector:pg16
  container_name: interview-postgres
```

- `image`：用哪个镜像（别人打包好的，不需要你自己写 Dockerfile 去构建 PG）
- `container_name`：给容器起名。如果不写，Docker 自动生成一个随机名（像 `postgres-abc123`），不方便管理。

```yaml
  environment:
    POSTGRES_USER: postgres
    POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-password}
    POSTGRES_DB: interview_guide
```

**`${POSTGRES_PASSWORD:-password}` 是什么意思？**

从 shell 环境变量（或 .env 文件）读取 `POSTGRES_PASSWORD`。如果没设，用默认值 `password`。

**这就是为什么你在服务器上有 `.env` 文件**——敏感信息不进 git，运行时注入。

```yaml
  volumes:
    - postgres_data:/var/lib/postgresql/data
```

**volume = 把容器内的某个目录挂到宿主机（或 Docker 管理的存储）上**

为什么需要这个？**容器一删，里面的数据全没。**

类比：容器是租的房子，`/var/lib/postgresql/data` 是房间里的一个柜子。volume 是把你自己的柜子搬到出租屋——退租（删容器）的时候柜子带走（数据保留）。

如果不配 volume：删了 PG 容器 = 数据库没了 = 所有用户数据没了。生产环境这是灾难。

```yaml
  ports:
    - "5432:5432"
```

**端口映射：宿主机端口:容器内端口**

- 左边 `5432`：你服务器上别人能访问的端口
- 右边 `5432`：PG 容器内部监听的端口

你的 app 容器访问 PG 时用 `postgres:5432`（Docker 内部网络，不经过端口映射）。你从服务器外部连 PG（比如用 DataGrip 调试）才需要 `121.40.130.172:5432`。

```yaml
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U postgres"]
    interval: 5s
    timeout: 5s
    retries: 5
```

**健康检查：Docker 怎么知道这个容器"好了"？**

`pg_isready -U postgres` 是一个 PG 自带的命令，返回 0 表示数据库准备好接受连接了。

- `interval: 5s`：每 5 秒查一次
- `retries: 5`：连续 5 次失败才标记 unhealthy
- `timeout: 5s`：单次检查超过 5 秒算失败

**为什么需要这个？** 看 app 服务的配置：

```yaml
depends_on:
  postgres:
    condition: service_healthy
```

意思是：**后端的 app 容器，必须等 PG 的健康检查通过后才能启动。** 不能只等"容器起来了"——容器起来不代表数据库已经能接受连接（PG 启动要花几秒做恢复检查）。`service_healthy` 比 `service_started` 更严格。

```yaml
createbuckets:
  image: minio/mc
  depends_on:
    minio:
      condition: service_healthy
  entrypoint: >
    /bin/sh -c "
    ...
    /usr/bin/mc mb myminio/interview-guide
    ...
    "
```

这是一个**一次性任务容器**——跑完就退出的那种。

MinIO 启动后，里面是空的，没有"桶"（bucket，类似文件夹）。你不创建桶，后端代码想往里面存文件就会报错。

这个 createbuckets 容器做的事情：
1. 等 MinIO 起来
2. 登录 MinIO，看看 `interview-guide` 桶存在不
3. 不存在就创建
4. 设置公开读权限
5. 确认创建成功 → 退出

跑完就退，不占资源。下次 `docker compose up` 不会再执行（因为已经有桶了，脚本里做了判断）。

```yaml
app:
  image: ${ACR_REGISTRY:-crpi-...}/interview-guide/app:latest
  pull_policy: always
```

- `image`：从阿里云 ACR 拉取镜像，不再本地 build（因为你已经配了 CI/CD）
- `${ACR_REGISTRY:-crpi-...}`：如果环境变量 `ACR_REGISTRY` 没设，用 `crpi-...` 当默认值
- `pull_policy: always`：每次 `docker compose up` 都尝试拉最新镜像

```yaml
  mem_limit: 1200m
```

**Docker 层的内存硬上限。** 你的服务器只有 3.4G，不加这个限制 → Java 能吃光物理内存 → Linux OOM Killer 随机杀进程 → 系统不稳定。

加上 `mem_limit: 1200m`，Java 最多吃 1.2G，捅不破这个顶。

```yaml
volumes:
  postgres_data:
  redis_data:
  minio_data:
```

最底部的 `volumes` 是**声明卷**。声明了 Docker 才会创建。顶部各服务里的 `volumes: - postgres_data:/var/...` 是**使用卷**。先声明，再使用。

---

## 三、Nginx 配置是什么——"前台接待"

### 最简单的类比

你开公司，Nginx 是前台接待员。所有人进了大门先到前台：

- 有人问"给我看你们官网（首页）"→ 直接给静态页面
- 有人问"我要办业务（`/api/xxx`）"→ 转接到后端办公室

你的 `frontend/nginx.conf`：

```nginx
server {
    listen 80;
    server_name localhost;

    root /usr/share/nginx/html;
    index index.html;
```

- `listen 80`：在前台 80 号窗口接待（HTTP 默认端口）
- `root`：静态文件放哪（React 编译后的一堆 HTML/JS/CSS 文件）
- `index`：默认首页文件

```nginx
    location / {
        try_files $uri $uri/ /index.html;
    }
```

**这是 React/Vue 单页应用必须配的一句话。**

问题：你的 React 应用只有 `index.html` 一个入口。用户访问 `https://你的域名/interview/room/123`——服务器上根本没有 `interview/room/123` 这个文件（只有 index.html）。

`try_files` 的逻辑：
1. 先看看请求的文件在不在（`$uri`）
2. 不在的话看看是不是目录（`$uri/`）
3. 都不是 → 返回 `index.html`，让 React Router 在前端处理

配这句话，用户刷新页面不会 404。

```nginx
    location /api/ {
        proxy_pass http://app:8080;
        proxy_set_header Host $host;
        proxy_read_timeout 300s;
    }
```

**反向代理：凡是 `/api/` 开头的请求，转发给后端的 app 服务。**

`http://app:8080` —— `app` 是 docker-compose 里的服务名。**Docker 内部有 DNS，服务名可以直接当域名用。**

`proxy_read_timeout 300s` —— 后端处理超时时间。你项目有 AI 调用（百炼），一次 LLM 请求可能几秒到几十秒，默认 60 秒可能不够，拉长到 300 秒。

**整个请求链路是这样的**：

```
浏览器 → http://121.40.130.172 → Nginx(frontend容器:80)
  ├── GET / → 返回 React 页面
  └── POST /api/resume/review → 转发到 app:8080 → Spring Boot → PG/Redis/MinIO/百炼
```

---

## 四、DNS 是什么——"电话簿"

### 最简单的类比

你手机通讯录里存着"张三 138xxxx1234"。你打电话找"张三"，手机自动帮你拨 138xxxx1234。

互联网也一样：
- 域名 = 通讯录里的名字（`xi266405.top`）
- IP 地址 = 电话号码（`121.40.130.172`）
- DNS = 通讯录（存着域名 → IP 的映射）

**当你在浏览器输入 `xi266405.top` 时发生了什么**：

```
1. 浏览器查本地缓存：xi266405.top → ？
   （没有，往下走）

2. 问操作系统：你知道 xi266405.top 的 IP 吗？
   （操作系统查 hosts 文件 + 本地 DNS 缓存）

3. 问 DNS 服务器（通常是你的路由器或 114.114.114.114）：
   "xi266405.top 的 IP 是什么？"

4. DNS 服务器沿着域名层级逐级查：
   .top 的 DNS → xi266405.top 的 DNS → 找到 A 记录 → 返回 121.40.130.172

5. 浏览器拿到 IP，连接服务器，发 HTTP 请求
```

**A 记录**：DNS 里最常见的记录类型，A = Address，域名 → IPv4 地址。你在腾讯云/阿里云 DNS 控制台加的就是这个。

你现在的状态：`xi266405.top` DNS 已经配好，`dig xi266405.top` 能返回 `121.40.130.172`。下一步要加 HTTPS——但那是 Phase 3 的事。

---

## 五、镜像和容器到底是什么关系——"安装包和正在运行的程序"

这是新手最容易混的概念。

| 概念 | 类比 | 特点 |
|------|------|------|
| **镜像（Image）** | `.exe` 安装包 | 只读的，一个模板。存的是"程序 + 运行它所需的全部文件" |
| **容器（Container）** | 双击 .exe 后正在运行的程序 | 镜像的实例。可读可写。关了容器 = 退出程序 |
| **Dockerfile** | 制作 .exe 的编译脚本 | 定义怎么从零做出镜像 |
| **Registry（ACR/Docker Hub）** | 软件下载站 | 存镜像的地方。`docker pull` = 下载。`docker push` = 上传 |

一个镜像可以跑出无数个容器，互不影响。就像你可以开三个记事本窗口——底层都是一样的 notepad.exe，但各自编辑不同的文件。

**你的 CI/CD 流程里发生了什么**：

```
GitHub Runner（云端临时机）:
  docker build -t xxx/app:latest .       ← 照着 Dockerfile 做出镜像
  docker push xxx/app:latest              ← 把镜像上传到 ACR

服务器 (121.40.130.172):
  docker pull xxx/app:latest              ← 从 ACR 下载镜像
  docker compose up                       ← 用镜像启动容器
```

镜像通过 ACR 从 Runner 传递到服务器。ACR 是"中转站"——Runner 上传，服务器下载。

---

## 六、环境变量和 .env 是什么——"不写死在代码里的配置"

你的 `docker-compose.yml` 里有这些：

```yaml
environment:
  POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-password}
  AI_BAILIAN_API_KEY: ${AI_BAILIAN_API_KEY}
```

`${XXX}` 是占位符，运行时从外部取值。取值优先级：
1. Shell 环境变量（`export AI_BAILIAN_API_KEY=sk-xxx`）
2. 同目录的 `.env` 文件
3. `:-` 后面的默认值（`password`）

**为什么要这样？**

因为 `AI_BAILIAN_API_KEY=sk-07473b5a...` 如果写死在 `docker-compose.yml` 里，这份文件就进了 git——公开仓库，全世界都能看到你的 API Key。别人拿你 Key 调用百炼，你付钱。

所以：
- 真实值放 `.env`（gitignore 保护，不进仓库）
- 模板值放 `.env.example`（可以进仓库，告诉别人"你需要填哪些变量"）
- 代码里只有 `${变量名}` 占位符

**这就是"配置和代码分离"**——面试常考的概念。你现在已经在用了，但之前不知道叫什么。

---

## 七、你不需要"手写"这些，但你需要"读懂"这些

回到你最开始的问题——这些东西都是 AI 帮你写的，自己不会手写。

老实说，我工作 7 年也没手写过几次 Dockerfile。常用模式就那几种，JetBrains 的 IDE 有 Docker 插件能帮你生成。工作中大家都是：找公司现有的 Dockerfile 复制一份，改改项目名和端口。不行就 Google/问 AI。

**但你必须能读懂。** 因为：

1. **AI 写的东西不一定对**——你的 `ENTRYPOINT` 原来就是 AI 生成的写死版本，JAVA_OPTS 不生效。如果你看不懂，这个问题你可能永远发现不了。

2. **出问题了你得排查**——哪天 CI/CD 红了，报 "exec format error"，你知道是 Dockerfile 哪行的问题吗？是 FROM 的镜像选了 arm64 还是 amd64？

3. **面试会问**——不是让你背命令，是让你讲原理。面试官问"多阶段构建是干嘛的"，你不需要背 Dockerfile 写法，但得讲出"编译环境大、运行环境小、最终镜像瘦身"。

你的 Dockerfile 已经是最好的教材——它是真实项目、跑通的、有注释。没事多读两遍，读到你能给别人讲明白每一行在干什么，就算过关了。

---

## 下一步

读完这份文档，下一步怎么做：

1. 打开你的 `app/Dockerfile`，指着每一行，能用自己的话说出它在干什么
2. 打开你的 `docker-compose.yml`，理解 6 个服务的依赖关系：`frontend 等 app → app 等 postgres/redis/minio/createbuckets → createbuckets 等 minio`
3. 画一张图：用户浏览器请求 → Nginx → 后端 → 数据库 的完整链路，标出每一步用的是什么端口、什么协议
4. 去 Phase 0 开始干活——加测试步骤到 CI/CD

你这 4 份文件（两个 Dockerfile + compose + nginx.conf）如果能自己讲明白，就已经超过大部分初级运维候选人了。

---

*写于 2026-07-12*
