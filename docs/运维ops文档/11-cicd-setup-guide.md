# CI/CD 流水线搭建指导手册

> 本手册记录 interview-guide 项目从手动部署到自动化部署（CI/CD）的完整搭建过程。
> 包含原理讲解、步骤详解、踩坑记录、疑问解答。
> 适合：想了解这套流水线怎么搭的、以后要改流水线的、或要给别人搭类似系统的。
>
> 最终成果：`git push origin main` → 5 分钟后线上自动更新。

---

## 目录

- [一、背景：为什么要搭 CI/CD](#一背景为什么要搭-cicd)
- [二、核心概念：CI/CD 是什么](#二核心概念cicd-是什么)
- [三、技术选型与架构](#三技术选型与架构)
- [四、搭建步骤详解](#四搭建步骤详解)
- [五、踩坑记录与解决方案](#五踩坑记录与解决方案)
- [六、疑问解答汇总](#六疑问解答汇总)
- [七、日常使用手册](#七日常使用手册)
- [八、核心配置文件清单](#八核心配置文件清单)

---

## 一、背景：为什么要搭 CI/CD

### 1.1 痛点：手动部署有多痛苦

在搭 CI/CD 之前,项目的部署流程是全手动的：

```
改代码（本地 D:\interview-guide）
   ↓ 手动 tar 打包（排除 node_modules、.git 等）
scp 上传到服务器
   ↓
ssh 登录服务器解压
   ↓
docker compose up -d --build（服务器现场编译,10-20 分钟）
   ↓
部署完成
```

**每改一次代码,这 4 步都要手动重走一遍。** 实际踩过的坑：

| 坑 | 现象 | 耗时 |
|----|------|------|
| Docker Hub 国内限速 | 拉基础镜像（gradle 800MB）卡死或超时 | 数小时反复重试 |
| npm registry 国外源断连 | 前端 `pnpm install` 报 ECONNRESET | 反复改 Dockerfile |
| 服务器内存不足 | 3.4G 内存既要编译又要运行,OOM | Java 进程被杀 |
| Java 版本不对 | 本地 17,源项目要 21（虚拟线程） | 装 JDK、切版本 |
| 文件传输遗漏 | tar 打包忘记排除文件,或传错版本 | 排查困难 |

**一次部署花了 2 小时,大部分时间在等镜像下载和排错。**

### 1.2 CI/CD 解决了什么

CI/CD 把"手动搬运"变成"全自动流水线"：

```
改代码 → git push → [全自动] → 线上更新
         ↑
     你只做这一步
```

具体解决：

| 手动部署的问题 | CI/CD 怎么解决 |
|--------------|--------------|
| 手动打包传文件 | Git 管理代码,push 即触发 |
| 服务器现场编译（占内存、慢） | Runner（云端机器）编译,服务器只拉结果 |
| 镜像从 Docker Hub 拉（国内慢） | 镜像推到阿里云 ACR,服务器内网拉取 |
| 手动 SSH 部署 | Runner 自动 SSH 执行部署命令 |
| 出错手动排查 | 流水线每步有日志,失败立刻标红 |
| 回滚困难（重新编译） | 拉上一个版本镜像,30 秒回滚 |

---

## 二、核心概念：CI/CD 是什么

### 2.1 CI（Continuous Integration，持续集成）

你把代码 push 上去,机器自动帮你**编译 + 测试**,确认代码没问题。

- 解决：代码质量（编译过没、测试过没）
- 触发：每次 push/PR
- 失败了：拦住你,不让烂代码上线

### 2.2 CD（Continuous Deployment，持续部署）

CI 通过后,机器自动帮你把新代码**部署到服务器**。

- 解决：交付速度（自动上线）
- 触发：CI 通过后
- 失败了：回滚到上一个好版本

### 2.3 三个核心设计原则

整个 CI/CD 体系遵循三个原则,每个设计决策都能追溯到这三条：

**① 不可变（Immutability）**
- Git commit 不可变 + Docker 镜像不可变
- 部署的不是"一堆会变的文件",是"一个固定的快照"
- 今天部署的和明天部署的,只要 commit 一样,结果必须一样

**② 可追溯（Traceability）**
- 每个线上版本都能追溯到一个 commit
- 出问题了能回答"这是哪次改动引入的"
- 回滚就是"部署上一个 commit"

**③ 最小权限（Least Privilege）**
- CI/CD 系统只拿它需要的最小权限
- 部署密钥只能 docker compose,不能 rm -rf
- API Key 不进代码,只在运行时注入

---

## 三、技术选型与架构

### 3.1 技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| Git 平台 | **GitHub**（公开仓库） | 源项目在 GitHub;公开仓库 Actions 免费无限 |
| CI/CD 引擎 | **GitHub Actions** | GitHub 自带,配置简单,写个 yml 就能用 |
| 镜像仓库 | **阿里云 ACR**（个人版,广州） | 阿里云生态,比 Docker Hub 国内快 |
| 部署方式 | **SSH 推送** | 单服务器场景最简单,Runner SSH 过去执行命令 |

### 3.2 架构图：极简版 vs 企业真实版

CI/CD 的核心概念在个人项目和企业里完全一致，差别只在工具和规模。下面两张图并排对比，看懂极简版就能理解企业版。

#### 3.2.1 极简版（本项目实际用的）

```
【你电脑】
  git push
    │
    ▼
┌─────────────────────────────────────────┐
│ 【GitHub】（= 代码仓库 + CI 引擎一体）    │
│                                         │
│  ① 存代码（Git 仓库）                    │
│  ② 解析 deploy.yml，派任务给 Runner      │
└──────────────────┬──────────────────────┘
                   │ GitHub 内部分配
                   ▼
┌─────────────────────────────────────────┐
│ 【GitHub Actions Runner】（云端临时机）   │
│                                         │
│  ③ 从 GitHub 拉源代码                    │
│  ④ docker build（编译 + 打镜像）         │
│  ⑤ docker push 到阿里云 ACR              │
│  ⑥ SSH 到生产服务器执行部署命令           │
└──────┬───────────────────┬──────────────┘
       │ docker push       │ SSH
       ▼                   ▼
┌──────────────┐    ┌──────────────┐
│ 【阿里云 ACR】│    │ 【生产服务器】│
│ （镜像中转站）│    │ 121.40.130.172│
│ 存编译好的镜像│◄───│ docker pull  │
└──────────────┘    │ docker run   │
                    └──────────────┘
```

**特点**：Server 和 Runner 都在 GitHub 云端（托管），你只管 push 和维护生产服务器。镜像仓库用阿里云 ACR（公有云托管）。整套架构零运维（除了生产服务器）。

#### 3.2.2 企业真实版（自建 GitLab + Harbor）

```
【开发者电脑】
  git push
    │
    ▼
┌─────────────────────────────────────────┐
│ 【GitLab Server】（公司机房，自建）        │
│                                         │
│  ① 存代码（私有 Git 仓库，代码不出公司）   │
│  ② 读 .gitlab-ci.yml，解析出任务列表      │
│  ③ 把任务派给 Runner                     │
└──────────────────┬──────────────────────┘
                   │ 长连接派任务（Runner 主动连 Server）
                   ▼
┌─────────────────────────────────────────┐
│ 【GitLab Runner】（公司机房，自建，可多台） │
│                                         │
│  ④ 从 Server 拉源代码                    │
│  ⑤ docker build（编译 + 打镜像）         │
│  ⑥ docker push 到 Harbor                │
│  ⑦ SSH 到生产服务器执行部署命令           │
└──────┬───────────────────┬──────────────┘
       │ docker push       │ SSH
       ▼                   ▼
┌──────────────┐    ┌──────────────┐
│ 【Harbor】    │    │ 【生产服务器】│
│ （自建镜像    │    │ （或 K8s 集群）│
│   仓库）      │◄───│ docker pull  │
│ 存镜像+漏洞扫 │    │ docker run   │
└──────────────┘    └──────────────┘
```

**特点**：Server、Runner、Harbor、生产服务器全部在公司内网。代码和镜像都不出公司网络。Runner 自建（通常多台，按编译任务类型分工）。镜像仓库用 Harbor（自建，带漏洞扫描、LDAP 集成、镜像签名）。生产环境可能是一台服务器，更常见是 K8s 集群。

#### 3.2.3 通信流程详解（两版通用）

不管极简版还是企业版，**通信方向和流程顺序完全一致**。箭头方向 = 主动方 → 被动方：

```
① 你 ──push──► Server              （你主动推代码）
② Server 解析 yml，生成任务
③ Server ──派任务──► Runner         （Server 通过长连接派活）
④ Runner ──git clone──► Server      （Runner 主动拉源码）
⑤ Runner ──docker pull──► Docker Hub（Runner 拉基础镜像当编译环境）
⑥ Runner ──docker build──► 本地     （编译你的代码，打成业务镜像）
⑦ Runner ──docker push──► 镜像仓库  （Runner 主动推业务镜像到仓库）
⑧ Runner ──SSH──► 生产服务器        （Runner 主动登录生产服务器）
⑨ 生产服务器 ──docker pull──► 镜像仓库（生产服务器从仓库拉业务镜像）
⑩ 生产服务器 ──docker run──► 新版本上线
```

**关键认知**：
- **Server 管调度**（存代码、解析 yml、派任务），**Runner 管执行**（拉码、编译、推镜像、SSH 部署）
- **Runner 是所有操作的主动方**——它连 Server 拉码、连 Docker Hub 拉基础镜像、连镜像仓库推业务镜像、SSH 连生产服务器部署
- **生产服务器是被动的**——它不会"自己知道有新版本"，是 Runner SSH 进去命令它 `docker pull && docker run`
- **镜像仓库是中转站**——Runner 推、生产拉。没有它，Runner 跑完销毁，镜像就丢了
- **"拉镜像"有两件事别混**：第 ⑤ 步拉的是"工具"（基础镜像，当编译环境），第 ⑨ 步拉的是"产品"（你编译好的业务镜像，拿来运行）

#### 3.2.4 极简版 vs 企业版的差异对比

| 维度 | 极简版（本项目）| 企业真实版 |
|------|--------------|----------|
| **代码仓库** | GitHub（公有云，免费）| GitLab CE（自建，代码不出公司）|
| **CI 引擎** | GitHub Actions | GitLab CI |
| **Runner** | GitHub 云端提供 | 公司自建（1-N 台，按任务分工）|
| **镜像仓库** | 阿里云 ACR（公有云托管）| Harbor（自建，带扫描/签名/LDAP）|
| **部署目标** | 1 台 ECS + Docker Compose | K8s 集群（几十台节点）|
| **环境** | 只有生产 | dev / test / staging / prod 四套 |
| **部署审批** | 无（push 即上线）| 生产部署要 2 人审批（Jira 工单）|
| **密钥管理** | GitHub Secrets | Vault / KMS（专用密钥管理系统）|
| **监控** | 无 | Prometheus + Grafana + 自动告警 |
| **回滚** | 手动 docker compose | K8s 一键滚动回滚 |
| **配置管理** | 写死 .env | 配置中心（Apollo/Nacos）动态下发 |
| **代码安全** | 公开仓库（全世界可见）| 内网，VPN 才能访问 |
| **运维成本** | 几乎零（全托管）| 高（Server/Runner/Harbor/K8s 全要运维）|
| **适合** | 个人/创业/学习 | 中大公司/金融/政府 |

**核心区别一句话**：极简版是"平台全托管，省心但代码在第三方"，企业版是"全部自建，麻烦但代码在自己手里"。**角色和通信流程完全一致**，差别只在工具的复杂度、规模和安全要求。

### 3.3 三个角色的职责

| 角色 | 干什么 | 特点 |
|------|--------|------|
| **Runner（工厂）** | 拉代码 + 编译 + 打包镜像 + 推送 + 触发部署 | 临时存在,任务完就销毁,资源充足 |
| **镜像仓库（ACR/Harbor）** | 存编译好的镜像 | 持久存在,被动,等服务器来拉 |
| **服务器（门店）** | 从镜像仓库拉镜像 + 运行容器 | 只跑不编译,3.4G 内存够用 |

**关键分工**：Runner 是生产者（构建镜像推给仓库）,镜像仓库是被动中转站（等服务器来拉）,服务器是消费者（拉下来运行）。三者角色不能搞反。

### 3.4 为什么要这样分工（原理）

**为什么不在服务器上编译？**
服务器只有 3.4G 内存。如果在上面跑 Gradle 编译（JVM 要 1G+）同时还要跑 PG/Redis/MinIO/App,OOM 概率极高。CI/CD 把"编译"（在 Runner）和"运行"（在服务器）彻底分开。

**为什么需要镜像仓库（ACR）？**
Runner 是临时的,跑完就销毁。镜像必须存在一个持久的地方。服务器和 ACR 同在阿里云,拉取快（跨区域走公网稍慢,但比 Docker Hub 快得多）。

**为什么用 SSH 部署（不用 Kubernetes）？**
K8s 解决"多台服务器协同调度",但只有一台服务器时是杀鸡用牛刀。K8s 自己要占 1-2G 内存,3.4G 机器扛不住。SSH + Docker Compose 是单机最优解。

---

## 四、搭建步骤详解

### 步骤 1：代码进 GitHub

**做什么**：把本地项目代码推到 GitHub 仓库,作为 CI/CD 的起点。

**为什么**：CI/CD 是"事件驱动"的——需要"你 push 了代码"这个事件来触发。没有 Git 仓库,就没有触发机制。同时 Git 提供版本确定性（每次 push 对应唯一 commit,可追溯可回滚）。

**怎么做的**：

```bash
cd D:\interview-guide

# 删掉源项目的 .git 历史（那是原作者的,不是你的）
rm -rf .git

# 重新初始化
git init
git branch -M main
git add .
git commit -m "init: interview-guide 项目初始化"

# 关联远程仓库
git remote add origin https://github.com/TheBestXi/xi-interview-guide.git
git push -u origin main
```

**踩过的坑**：
- `nul` 文件冲突：Windows 保留设备名被误创建,git add 报错。解决：`rm -f ./nul`
- `.gitignore` 有 `/docs` 规则会忽略设计文档。解决：删掉这行,让文档进仓库
- `.env` 含百炼 API Key,必须确认被 .gitignore 保护。用 `git check-ignore .env` 验证

### 步骤 2：开通阿里云 ACR

**做什么**：在阿里云开通容器镜像服务,创建命名空间和两个镜像仓库。

**为什么**：Runner 构建出的镜像要存到一个持久中转站,服务器再从这个中转站拉。没有 ACR,Runner 跑完销毁,镜像就丢了。

**怎么做的**：

1. 阿里云控制台 → 容器镜像服务 → 个人实例（免费）
2. 设置固定密码（访问凭证）
3. 创建命名空间 `interview-guide`
4. 创建两个仓库：`app`（后端镜像）、`frontend`（前端镜像）

**地址结构**：
```
crpi-tpudmz0tztfynq35.cn-guangzhou.personal.cr.aliyuncs.com/interview-guide/app
└────────────────── Registry ───────────────────────┘ └命名空间┘ └仓库名┘
```

**为什么两个仓库**：后端镜像（Java jar）和前端镜像（Nginx + 静态文件）是两种完全不同的东西,各自独立管理版本、独立回滚、清晰不混乱。一个仓库对应一个会独立演进的镜像,这是工程最佳实践。

**注意**：ACR 在广州,服务器在杭州,跨区域走公网（稍慢,可能有少量流量费）。同区域最快,但学习项目不值得为此重开。

### 步骤 3：生成部署专用 SSH 密钥

**做什么**：生成一对新的 SSH 密钥,专门给 GitHub Actions 部署用,不用你个人的密钥。

**为什么**：最小权限原则 + 可吊销。详见 [疑问解答 Q5](#q5)。

**怎么做的**：

```bash
# 1. 在本地生成密钥对
ssh-keygen -t ed25519 -f ~/.ssh/deploy_key -C "github-actions-deploy"

# 2. 公钥装服务器（让服务器认得这把密钥）
cat ~/.ssh/deploy_key.pub | ssh xi "cat >> ~/.ssh/authorized_keys"

# 3. 验证新密钥能登录
ssh -i ~/.ssh/deploy_key root@121.40.130.172 "echo OK"

# 4. 查看私钥内容（填到 GitHub Secrets）
cat ~/.ssh/deploy_key
```

**原理**：
- 私钥留本地（给 GitHub Actions 用）
- 公钥装服务器（让服务器认得这把私钥）
- Runner 拿着私钥登录,服务器看到公钥就放行

**踩过的坑**：
- Windows ssh-keygen 不认 `-N ""`（空密码参数）。解决：去掉 `-N ""`,手动按两次回车
- `.ssh` 目录不存在。解决：`mkdir ~/.ssh`
- `deploy_key` 目录残留（上一次失败留下）。解决：`rm -rf ~/.ssh/deploy_key` 后重新生成

### 步骤 4：配置 GitHub Secrets

**做什么**：在 GitHub 仓库设置里添加加密的环境变量。

**为什么**：API Key、密码、私钥这些敏感信息绝不能写进代码（尤其公开仓库）。Secrets 是"代码和密钥分离"的实现——代码可以公开,密钥永远只在运行时注入。

**注意**：配 **Repository secrets**（不是 Environment secrets）。deploy.yml 没声明 environment,只有 Repository 级别才能被读到。

**怎么做的**：

GitHub 仓库 → Settings → Secrets and variables → Actions → New repository secret,添加 3 个：

| Secret 名 | 值 | 来源 |
|-----------|-----|------|
| `ACR_USERNAME` | ACR 登录用户名 | ACR 控制台「访问凭证」 |
| `ACR_PASSWORD` | ACR 登录密码 | 开通时设的固定密码 |
| `DEPLOY_SSH_KEY` | 部署私钥全文 | 步骤 3 生成的 deploy_key |

**Secrets 只有 Name 和 Secret 两个字段**——这就是个加密的键值对,名字给代码引用,值运行时注入,日志自动打码成 `***`。

### 步骤 5：改造 Dockerfile 和 docker-compose.yml

**做什么**：让配置从"本地编译"模式切换到"CI/CD 编译 + ACR 拉取"模式。

**三个改动**：

**① app/Dockerfile：ENTRYPOINT 改成读 JAVA_OPTS**

```dockerfile
# 改前（写死的,不读环境变量）
ENTRYPOINT ["java", "-jar", "app.jar"]

# 改后（让 JAVA_OPTS 生效,限制堆内存）
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**为什么**：服务器只有 3.4G 内存,Java 默认吃 1/4 物理内存（~850MB）。必须限制堆内存（`-Xmx768m`）,否则 OOM。但原 ENTRYPOINT 是写死的,不管你设什么 `JAVA_OPTS` 都不理。

**② frontend/Dockerfile：npm/pnpm 走国内源**

```dockerfile
# 改前（走默认 npmjs.org 国外源,国内断连）
RUN npm install -g pnpm && pnpm install

# 改后（走 npmmirror 国内源）
RUN npm config set registry https://registry.npmmirror.com \
    && npm install -g pnpm --registry=https://registry.npmmirror.com \
    && pnpm config set registry https://registry.npmmirror.com \
    && pnpm install
```

**为什么**：服务器/Runner 在 Docker 构建时访问 `registry.npmjs.org`（国外）,网络断连报 ECONNRESET。走国内源稳定。

**③ docker-compose.yml：build 改成 image**

```yaml
# 改前（本地现场编译）
  app:
    build:
      context: .
      dockerfile: app/Dockerfile

# 改后（从 ACR 拉取 CI 构建好的镜像）
  app:
    image: ${ACR_REGISTRY:-crpi-...}/interview-guide/app:latest
    pull_policy: always
    # ... 还加了 JAVA_OPTS 和 mem_limit
```

**为什么**：CI/CD 模式下,编译在 Runner 上完成,服务器只负责拉取运行。服务器不再需要编译,内存压力骤减。

### 步骤 6：编写 GitHub Actions 流水线配置

**做什么**：写 `.github/workflows/deploy.yml`,定义整个自动化流程。

**这是核心文件,定义了 8 个步骤**（详见 [第八节](#八核心配置文件清单)）：

```
触发：push 到 main 分支
  ↓
Step 1: 拉代码（actions/checkout）
Step 2: 装 JDK 21（actions/setup-java）
Step 3: 后端编译检查（gradle compileJava）
Step 4: 装 Node + 前端构建检查（pnpm build）
Step 5: 登录 ACR（用 Secrets）
Step 6: 构建推送后端镜像（docker build + push）
Step 7: 构建推送前端镜像
Step 8: SSH 部署到服务器（docker compose pull + up + 健康检查）
```

**关键设计**：
- 编译检查不通过 → 流水线停住,不部署烂代码
- 镜像打两个 tag：`latest`（最新）+ commit 号（可追溯可回滚）
- 健康检查连试 5 次,每次隔 10 秒,确保后端真的起来了

### 步骤 7：触发第一次流水线

**做什么**：`git push` 触发,盯 Actions 页面看跑通。

**踩过的坑**：
- 第一次跑报 `exit code 126`——gradlew 文件在 git 里是 `100644`（不可执行）,Linux Runner 拒绝执行。解决：`git update-index --chmod=+x gradlew`
- Node.js 20 deprecated 警告——GitHub Action 内部 runtime 过期,强制升到 24。只是警告不影响运行,忽略

**验证成功的四道铁证**：
```
① 容器 IMAGE 列显示 ACR 地址（不是本地 build 的）
② 后端健康检查 HTTP 200
③ 外部访问 HTTP 200
④ ACR 里两个新镜像构建时间戳是刚才
```

---

## 五、踩坑记录与解决方案

### 坑 1：Docker Hub 国内限速,镜像拉不下来

**现象**：服务器 `docker pull` 卡在 "Downloading" 几小时不动,36,000 行日志全是重复的 Downloading。

**根因**：Docker Hub 在国内被限速,即使配了镜像加速器也慢。

**解决**：换多个国内镜像加速器,在 `/etc/docker/daemon.json` 配 4 源轮询：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1panel.live",
    "https://dockerpull.org",
    "https://docker.1ms.run"
  ]
}
```

重启 Docker 生效：`systemctl restart docker`

### 坑 2：前端 pnpm install 报 ECONNRESET

**现象**：前端 Dockerfile 里 `npm install -g pnpm` 报 `ECONNRESET`,网络断连。

**根因**：Docker 构建时访问 `registry.npmjs.org`（国外）,网络不稳定。

**解决**：改 Dockerfile,让 npm 和 pnpm 都走国内源 `registry.npmmirror.com`。

### 坑 3：服务器内存不足导致 OOM

**现象**：Java 进程默认吃 ~850MB 内存,加上 PG/Redis/MinIO,3.4G 服务器紧张。

**解决**：两道防线：
- `JAVA_OPTS=-Xmx768m -Xms512m`：限制 Java 堆最大 768MB
- `mem_limit: 1200m`：Docker 层面硬上限,即使 Java 膨胀也捅不破

同时改了 Dockerfile 的 ENTRYPOINT,让 JAVA_OPTS 能生效（原版写死不读环境变量）。

### 坑 4：Java 17 跑不了源项目

**现象**：源项目用 Java 21 的虚拟线程（`Executors.newVirtualThreadPerTaskExecutor()`）,17 直接 crash。

**解决**：在 D 盘装独立的 Java 21,写 `start-jdk21.bat` 脚本临时切换,不动全局 17。

### 坑 5：gradlew 不可执行（exit code 126）

**现象**：GitHub Actions 报 `exit code 126`,gradlew 没有执行权限。

**根因**：Windows 不认 Unix 文件权限位,gradlew 在 git 里是 `100644`（不可执行）。Linux Runner checkout 后拒绝执行。

**解决**：`git update-index --chmod=+x gradlew`,改 git 里权限为 `100755`。

### 坑 6：Windows ssh-keygen 参数不兼容

**现象**：`ssh-keygen -N ""` 报 `Too many arguments`。

**根因**：Windows 自带 OpenSSH 对引号处理比 Linux 严格,`""` 被吞掉。

**解决**：去掉 `-N ""`,手动在 passphrase 提示时按两次回车。

### 坑 7：.env 文件安全检查

**现象**：.env 含百炼 API Key,公开仓库推上去 = 全世界可见。

**解决**：确认 `.gitignore` 第 12 行有 `.env` 规则。用 `git check-ignore .env` 验证被保护。`.env.example`（占位符）可以推,真实 `.env` 不进 git。

---

## 六、疑问解答汇总

### Q1：拉 Docker 镜像的目的是什么？跟下载中间件在 Windows 本地一样吗？

**是的,类比完全正确。**

Docker 镜像本质上就是一个 tar 包,里面装着"运行某个服务所需的全部文件 + 配置"。跟你在 Windows 下安装 PostgreSQL、Redis 没有本质区别,只是"安装"变成了"拉镜像","启动服务"变成了"启动容器"。

区别在于隔离方式：Docker 把每个服务装进隔离的容器,标准化、可移植、删干净。传统安装会污染系统、残留配置。

对于本项目,后端镜像 = Java 21 JRE + 编译好的 jar 包;前端镜像 = Nginx + 编译好的 React 静态文件。

### Q2：一个服务器如果有多个项目,各自的 Docker 互不相关可以各自启动吗？

**完全正确。** 这是 Docker 的核心设计哲学——每个项目是独立的封闭单元。

- **容器名隔离**：不同 compose 项目的容器名独立
- **网络隔离**：每个 compose 自动创建独立的虚拟网络
- **数据卷隔离**：各自命名卷,互不可见
- **端口**：唯一共享资源,需要手动错开（interview-guide 用 5432,博客用 5433）

### Q3：这个项目用的百炼 API 拿来干嘛？

百炼 API 是项目的命脉,有 4 个用途：

| 用途 | 模型 | 用在哪 |
|------|------|--------|
| LLM | qwen3.5-flash | 简历点评、面试出题、RAG 问答、JD 解析 |
| Embedding | text-embedding-v3 | 知识库向量化（1024 维）|
| ASR | qwen3-asr-flash-realtime | 语音面试（语音转文字）|
| TTS | qwen3-tts-flash-realtime | 语音面试（文字转语音）|

几乎所有 AI 能力都挂在百炼上。百炼挂了 = 整个产品瘫了。

### Q4：Runner 自己分析有什么镜像？还是自己先拉取让 ACR 准备？

**都不对。** Runner 是没有大脑的执行机器,只做 deploy.yml 里写的事。你在配置里写了几条 `docker build`,它就构建几个。

ACR 是被动仓库,不主动准备任何东西。Runner 构建好镜像后**主动推**给 ACR,服务器再从 ACR **主动拉**。三者角色：Runner 是生产者,ACR 是中转站,服务器是消费者。

### Q5：为什么要生成部署专用 SSH 密钥？

**核心原因：权限隔离 + 可吊销。**

如果 CI/CD 用你个人的密钥（root 最高权限）：
- 仓库被入侵 → 攻击者拿到密钥 → 服务器沦陷 → 灾难

部署专用密钥的好处：
- **独立**：跟你个人密钥无关
- **可弃**：泄露了立刻吊销换新的,不影响日常访问
- **可审计**：每次 CI/CD 用它部署都有日志

即使泄露,逃生路径清晰：删公钥 → 生成新密钥 → 更新 Secrets → 恢复 CI/CD。全程你的日常访问不受影响。

### Q6：为什么要创建两个 ACR 仓库？

因为项目有两种完全不同的镜像：后端（Java jar）和前端（Nginx + 静态文件）。

一个仓库对应一个会独立演进的镜像。两个仓库各自管理版本、各自回滚、清晰不混乱。这是工程最佳实践。

### Q7：镜像到底是什么东西？

镜像是一个"打包好的文件夹 + 启动说明书"——本质是 tar 包。

后端镜像里面装着：eclipse-temurin:21-jre 的完整运行环境（Linux 基础库 + Java 21 JRE + 系统字体）+ 你的 app.jar（编译后的代码 + 所有依赖 + 配置文件）。

前端镜像里面装着：nginx:alpine 运行环境 + 你 React 编译后的 dist 静态文件。

整个镜像 = 应用 + 运行它所需的全部环境,打包成不可变的整体。到哪台机器 `docker run`,行为一模一样。

### Q8：配 Repository secrets 还是 Environment secrets？

**Repository secrets。**

deploy.yml 没声明 `environment:` 字段,只有 Repository 级别才能被读到。Environment secrets 适合多环境（dev/staging/prod 分开管理）的场景。

### Q9：为什么 GitHub Secrets 只有 Name 和 Secret 两个字段？

因为 Secrets 的本质就是一个加密的键值对。名字给代码引用（`${{ secrets.XXX }}`）,值运行时注入,日志自动打码。不需要别的元数据。

---

## 七、日常使用手册

### 7.1 改代码后部署

```bash
# 改完代码,提交推送
git add .
git commit -m "你的改动说明"
git push origin main

# 去 https://github.com/TheBestXi/xi-interview-guide/actions 看进度
# 约 5 分钟后线上更新
```

**你只做这一件事。** 剩下全自动。

### 7.2 流水线各步骤预期耗时

| 步骤 | 耗时 |
|------|------|
| 拉代码 + 装 JDK | ~30 秒 |
| 后端编译检查 | ~1 分钟 |
| 前端构建检查 | ~1.5 分钟 |
| 登录 + 构建推送镜像 | ~1.5 分钟 |
| SSH 部署 + 健康检查 | ~1.5 分钟 |
| **总计** | **~5-6 分钟** |

### 7.3 流水线失败了怎么办

1. 去 Actions 页面,点失败的那次 run
2. 展开红色的步骤,看错误信息
3. 常见错误：
   - **编译失败** → 代码有语法错,改了重新 push
   - **镜像推送失败** → ACR 密码错了,检查 `ACR_PASSWORD` Secret
   - **SSH 部署失败** → 部署密钥不对,检查 `DEPLOY_SSH_KEY` Secret
   - **健康检查失败** → 后端启动失败,SSH 进服务器看 `docker logs interview-app`

### 7.4 手动回滚

如果新版本有问题,回滚到上一个版本：

```bash
ssh xi
cd /opt/interview-guide

# 看所有镜像版本
docker images

# 用上一个 commit 的镜像（替换 tag）
# 改 docker-compose.yml 里的 image tag,或直接指定
docker compose up -d  # 用旧镜像重启
```

### 7.5 查看 CI/CD 历史

https://github.com/TheBestXi/xi-interview-guide/actions

每次 push 都有一条记录,能看到：
- 哪次成功（绿勾）/失败（红叉）
- 每步耗时
- 完整日志
- 对应的 commit

### 7.6 修改流水线

改 `.github/workflows/deploy.yml`,push 后生效。常见修改：
- 加测试步骤（在编译检查后加 `run: ./gradlew test`）
- 加通知（部署成功/失败发钉钉/企微通知）
- 加环境变量（在 env 块加）
- 改触发条件（比如 PR 也触发）

---

## 八、核心配置文件清单

### 8.1 `.github/workflows/deploy.yml`（流水线核心）

8 个步骤：拉代码 → 装 JDK → 后端编译 → 装 Node → 前端构建 → 登录 ACR → 构建推送镜像 → SSH 部署。

关键配置：
- `ACR_REGISTRY`: `crpi-tpudmz0tztfynq35.cn-guangzhou.personal.cr.aliyuncs.com`
- `ACR_NAMESPACE`: `interview-guide`
- `SERVER_HOST`: `121.40.130.172`
- Secrets：`ACR_USERNAME` / `ACR_PASSWORD` / `DEPLOY_SSH_KEY`

### 8.2 `docker-compose.yml`（服务器编排）

6 个服务：postgres / redis / minio / createbuckets / app / frontend

关键改动：
- app 和 frontend 从 `build:` 改成 `image:`（从 ACR 拉）
- app 加 `JAVA_OPTS: -Xmx768m -Xms512m` + `mem_limit: 1200m`
- 镜像地址用 `${ACR_REGISTRY:-默认值}` 变量,方便切换

### 8.3 `app/Dockerfile`（后端镜像构建）

两阶段构建：gradle 编译 → temurin 运行。

关键改动：ENTRYPOINT 改 `sh -c "java $JAVA_OPTS -jar app.jar"`,让内存限制生效。

### 8.4 `frontend/Dockerfile`（前端镜像构建）

两阶段构建：node 编译 → nginx 托管。

关键改动：npm/pnpm 走国内 npmmirror 源。

### 8.5 `.env.production`（生产环境配置模板）

不含真实密钥,只是模板。服务器上真实的 `.env` 不进 git,手动维护。

### 8.6 GitHub Secrets（3 个）

| Secret | 用途 |
|--------|------|
| `ACR_USERNAME` | 登录 ACR 推拉镜像 |
| `ACR_PASSWORD` | ACR 密码 |
| `DEPLOY_SSH_KEY` | Runner SSH 登录服务器 |

---

## 附录：本地开发环境

| 项 | 值 |
|----|-----|
| Java（全局）| 17（Microsoft,日常用）|
| Java（项目专用）| 21（D 盘 Temurin,跑 interview-guide 用）|
| 切换脚本 | `start-jdk21.bat`（双击临时切到 21）|
| JDK 21 路径 | `D:\jdk\jdk-21.0.11+10` |
| Docker Desktop | 已装（但 CI/CD 不依赖本地 Docker）|
| Node | v24.11.1 |
| pnpm | 9.15.9 |
| Git | 已配置 GitHub 凭证 |

---

## 附录：服务器环境

| 项 | 值 |
|----|-----|
| 地址 | 121.40.130.172（阿里云杭州）|
| 配置 | 2核 / 3.4G 内存 / 2G swap / 40G 磁盘 |
| 系统 | Ubuntu 22.04 |
| Docker | 29.6.1 + Compose v5.3.0 |
| 项目目录 | `/opt/interview-guide` |
| 镜像加速器 | daocloud / 1panel / dockerpull / 1ms（四源轮询）|
| SSH 别名 | `xi`（个人密钥）/ `deploy_key`（CI/CD 专用）|
| 安全组端口 | 22 / 80 / 443 / 8080 / 9001 |

---

## 附录：访问地址

| 服务 | 地址 |
|------|------|
| 前端 | http://121.40.130.172 |
| 后端 API | http://121.40.130.172:8080 |
| Swagger | http://121.40.130.172:8080/swagger-ui.html |
| MinIO 控制台 | http://121.40.130.172:9001 |
| GitHub 仓库 | https://github.com/TheBestXi/xi-interview-guide |
| Actions 页面 | https://github.com/TheBestXi/xi-interview-guide/actions |
| ACR 控制台 | https://cr.console.aliyun.com/ |

---

*文档完成于：2026-07-12*
*流水线首次跑通：2026-07-12*
*部署方式：手动 → GitHub Actions + 阿里云 ACR + SSH 部署*
