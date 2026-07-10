# 用户体系与角色权限开发设计文档

> 配合 `01-technical-design.md` 使用。本文档定义用户身份体系（游客 + 注册用户）、
> 角色权限（STUDENT / MENTOR / ADMIN）、游客配额控制、半遮面渐进披露、
> 临时数据存储与清理的完整方案。
> 这是上游 interview-guide 的**增量设计**（上游无用户体系），需独立开发。

---

## 1. 设计目标

1. **游客可用**：未登录用户能体验核心功能（仅简历分析），降低使用门槛，作为引流。
2. **成本可控**：游客每日提问次数硬上限 3 轮，超出引导注册。AI 调用走最便宜模型。
3. **渐进披露引流**：简历报告"半遮面"——评分 + 前 3 条建议免费看，完整报告需注册。
4. **数据继承**：游客转注册后，半遮面阶段暂存在 Redis 的完整报告自动落库，无缝接住。
5. **角色分层**：求职者 / 导师 / 管理员三类角色，导师可切求职者视角。
6. **栈内实现**：所有方案落在 Java + Spring Boot + Redis + PostgreSQL，不引入额外运行时。

---

## 2. 核心决策（已锁定）

| 决策项 | 选定方案 | 一句话理由 |
|--------|---------|-----------|
| 游客身份标识 | `deviceId`（前端 `crypto.randomUUID()` 生成，存 localStorage） | 免 cookie，省事，UUID 防碰撞 |
| 身份传递方式 | 请求头 `X-Device-Id`（游客）/ `Authorization: Bearer <jwt>`（登录用户） | 两套身份并存，互不冲突 |
| 配额计数器 | Redis 原子递减 + 当天 TTL | 快、防超卖、自动按天归零 |
| 游客数据存储 | 全部落 PostgreSQL，带 `expire_at` 字段；**半遮面报告例外**，存 Redis（见 §5.4） | 简化栈，复现项目并发量低 |
| 数据清理 | Spring `@Scheduled` 定时任务 | 跟主应用同生共死，零额外运维 |
| 注册继承 | Guest 账号原地升级为 Registered，`user_id` 不变；半遮面报告从 Redis 落库 | 业务数据零迁移 + 无缝接住完整报告 |
| 游客每日提问上限 | **3 轮**（硬上限，所有 AI 调用合计） | 控成本 |
| 角色体系 | STUDENT / MENTOR / ADMIN 三类 | 导师可切求职者视角；管理员占位 |
| 登录方式 | 手机号 + 密码（唯一） | 学习项目，砍掉验证码短信 |
| 短信验证码 | **不做** | 学习项目，不引入短信通道 |
| 忘记密码 | **砍掉**（无验证码则不安全） | 见 §6.3 |
| 游客可用功能 | **仅简历分析**（模拟面试/语音/日程/知识库全部锁登录） | 聚焦引流点，控成本 |
| 简历报告缓存有效期 | **12 小时** | 给游客注册（填资料）留足时间 |
| 半遮面实现 | 后端只返评分 + 前 3 条，完整报告存 Redis | 防 F12 抓包绕过 |

---

## 3. 身份体系与角色设计

### 3.1 两类身份 + 三类角色

```
┌──────────────┐         ┌────────────────────────────────────┐
│   游客 GUEST  │ ──注册──►│       已注册 REGISTERED             │
│  靠 deviceId  │         │  role ∈ {STUDENT, MENTOR, ADMIN}    │
└──────────────┘         │  靠 JWT token                       │
                         └────────────────────────────────────┘
```

- **身份**：GUEST / REGISTERED —— 决定是否已登录
- **角色**：注册用户的 `role` 字段 —— 决定能做什么
- 游客没有角色概念，所有游客权限边界一致

### 3.2 角色定义

| 角色 | 说明 | 权限 |
|------|------|------|
| STUDENT（求职者） | 默认注册角色 | 全部业务功能（简历/面试/知识库/日程） |
| MENTOR（导师） | 可切换到 STUDENT 视角 | STUDENT 全部权限 + 导师专属功能（后定） |
| ADMIN（管理员） | 占位，第一版无路由无后台 | 仅 `@RequireRole(ADMIN)` 注解占位 |

### 3.3 user 表结构

```sql
CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    status      VARCHAR(20)  NOT NULL,    -- GUEST | REGISTERED
    role        VARCHAR(20),              -- REGISTERED 才有值：STUDENT | MENTOR | ADMIN
    device_id   VARCHAR(64),              -- 游客才有值，转正后置 NULL
    phone       VARCHAR(20)  UNIQUE,      -- 注册后才有（不验真，学习项目）
    password    VARCHAR(255),             -- 注册后才有（BCrypt 加密）
    nickname    VARCHAR(64),              -- 游客自动生成 "游客_a3f9"，注册可改
    created_at  TIMESTAMP    DEFAULT NOW(),
    updated_at  TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX idx_users_device_id ON users(device_id) WHERE status = 'GUEST';
```

- `phone` 加 `UNIQUE`：注册防重 + 改绑防重
- `phone` 不验真：学习项目，填了就用（见 §11 已知风险）

### 3.4 游客生命周期

#### 3.4.1 基本流程

```
首次访问
  │
  ├─ 前端生成 deviceId，存 localStorage
  ├─ 首次请求带 X-Device-Id
  └─ 后端拦截器首次见到该 deviceId
        └─ ⚠️ 此时【不建】Guest 记录（防膨胀，见 §3.4.2）

首次触发业务动作（上传简历）
  │
  └─ 此时才在 users 表建一条 status=GUEST 记录
        ├─ nickname = "游客_" + deviceId 前 4 位
        └─ 业务数据挂这个 user_id

后续每次请求
  └─ 后端靠 deviceId 查到 Guest 账号 → 注入请求上下文

注册
  └─ 把 Guest 记录原地升级（见 §6）
```

> **注**：游客唯一可用业务是简历分析（见 §8），所以"首次触发业务动作"= "首次上传简历"。

#### 3.4.2 懒创建策略（防 Guest 账号膨胀）

**问题**：若拦截器对每个新 deviceId 都建 Guest 记录，会引来垃圾：
- 搜索引擎爬虫（Googlebot、百度蜘蛛）每次访问可能带不同 deviceId
- 写脚本刷接口的人，几分钟几万条
- 安全扫描器、探测工具

`users` 表一周可能涨几十万条垃圾，定时清理来不及消化。

**策略**：**懒创建**——只在用户真正开始用功能时才建 Guest。

| 行为 | 是否建 Guest |
|------|------------|
| 浏览首页、看介绍页、看文档 | ❌ 不建 |
| 上传简历 | ✅ 建（首次触发时） |

拦截器只负责"识别 deviceId、注入上下文"，不负责建账号；建账号的动作下沉到简历 Service 首次执行时调用 `UserService.getOrCreateGuest(deviceId)`。

#### 3.4.3 IP 维度限流兜底

即便懒创建，脚本攻击仍可能在每个 IP 下高频创建。复用项目已有 `@RateLimit` 注解：

```java
@RateLimit(key = "ip:guest:create:{ip}", count = 10, period = 3600)  // 每 IP 每小时最多 10 个 Guest
public User getOrCreateGuest(String deviceId) { ... }
```

### 3.5 导师切换求职者视角

**机制**：纯 UI 切换，后端不换 token、不换身份。

- 前端切视角时，localStorage 存 `viewRole = 'student'`
- 刷新页面/重开浏览器，读 `viewRole` 决定渲染哪套 UI（**记住**切到哪个视角）
- 导师切回自己视角，清掉 `viewRole` 或设为 `mentor`

**后端约定**：所有求职者页面调用的 API，**同时接受 MENTOR 和 STUDENT 角色**——不靠角色拦截，靠"已登录"拦截。导师以自己账号调这些 API，产生的数据（传的简历、做的面试）**算导师自己的**。

**为什么不隔离数据**：导师切求职者视角多半是"体验/试用"，不是为了给学员操作。数据挂导师自己 user_id 即可，简单够用。

### 3.6 管理员占位

- `role` 枚举含 `ADMIN` 值
- 提供 `@RequireRole(ADMIN)` 注解 + 拦截器，第一版**无路由使用**
- 第一版**无管理后台页面**
- 后续要做管理后台时，直接给路由加 `@RequireRole(ADMIN)` 即可

---

## 4. 配额控制设计

### 4.1 计数规则

- **计数对象**：所有触发 LLM 调用的请求（仅简历分析，因游客只开放此功能）。
- **每日上限**：3 轮（硬上限，所有 AI 功能共用）。
- **重置周期**：自然日，每天 0 点 Redis key 过期自动归零。
- **扣减时机**：**先扣后判**（原子操作，防并发超卖）。

### 4.2 Redis Key 设计

```
key:    guest:quota:{deviceId}:{yyyyMMdd}
value:  剩余次数（初始 3）
TTL:    到当天 23:59:59（自动过期，次日归零）
```

### 4.3 扣减流程（Lua 脚本，原子完成初始化+扣减+设TTL）

```lua
-- file: resources/scripts/quota_decrement.lua
local key = KEYS[1]
local init = tonumber(ARGV[1])        -- 初始值 3
local ttl = tonumber(ARGV[2])         -- TTL 秒

local current = redis.call('GET', key)
if not current then
    redis.call('SET', key, init - 1, 'EX', ttl)
    return init - 1
else
    return redis.call('DECR', key)
end
```

返回值 `< 0` 即用尽，返回 `>= 0` 为剩余次数。

### 4.4 错误码

```java
// ErrorCode 枚举新增
QUOTA_EXCEEDED(4001, "今日免费体验次数已用完，注册后可继续使用"),
LOGIN_REQUIRED(4002, "此功能需要登录后使用"),
NEED_DEVICE_ID(4003, "缺少设备标识，请刷新页面重试"),
```

前端收到 `QUOTA_EXCEEDED` 或 `LOGIN_REQUIRED` → 弹出注册/登录弹窗。

---

## 5. 临时数据存储设计

### 5.1 统一存储策略

**绝大多数游客数据落 PostgreSQL**，带 `expire_at` 字段。唯一例外是**半遮面的完整报告**，存 Redis（见 §5.4）。

每条游客数据必须满足：
- 挂 `user_id`（指向 Guest 账号）
- 挂 `device_id`（兜底找回）
- 带 `expire_at` 字段（游客数据过期，登录用户数据置 NULL 永久保留）

### 5.2 各模块数据归类

| 模块 | 游客数据 | 游客是否可用 | 存储字段要点 |
|------|---------|------------|------------|
| 简历分析 | 完整报告（含 AI 点评） | ✅（占用配额） | 半遮面：完整报告存 Redis，落库字段见 §5.4 |
| 文字面试 | 对话流 | ❌ 锁登录 | — |
| 语音面试 | 对话流 + 音频 | ❌ 锁登录 | 成本过高 |
| 知识库上传 | 向量化数据 | ❌ 锁登录 | 存储成本高 |
| 知识库问答 | RAG 对话 | ❌ 锁登录 | — |
| 面试日程 | 日历记录 | ❌ 锁登录 | 需长期保留 |

> **重要变更**：文字面试从"游客可用"改为"锁登录"。游客唯一可用功能 = 简历分析。

### 5.3 expire_at 字段约定

```sql
-- 所有游客业务表统一加这个字段
expire_at TIMESTAMP  -- 游客数据 = 创建时间 + 12 小时；登录用户数据 = NULL（永久）
```

> **有效期调整**：从 7 天改为 **12 小时**。理由：游客唯一可用功能是简历分析，数据量小；
> 12 小时覆盖"游客注册填资料"的时间窗口即可；丢了重传也无妨。

写入规则（在 Service 层）：

```java
if (currentUser.isGuest()) {
    entity.setExpireAt(LocalDateTime.now().plusHours(12));
} else {
    entity.setExpireAt(null);  // 永久
}
```

### 5.4 半遮面报告：Redis 存完整，落库存摘要

**这是简历分析专属的渐进披露设计**，与其他业务数据不同。

#### 5.4.1 数据流

```
游客上传简历
  │
  ├─ 后端调 LLM 得【完整报告】（评分 + N 条建议 + 详细点评）
  │
  ├─ 完整报告存 Redis（不落库）
  │     key: guest:resume:report:{reportId}
  │     TTL: 12 小时
  │
  ├─ 数据库 resume_analysis 表只存【摘要】
  │     ├─ 评分
  │     ├─ 前 3 条建议
  │     ├─ report_id（外键指向 Redis key）
  │     ├─ expire_at = 12 小时
  │     └─ 其余详细内容 NULL
  │
  ├─ 前端接口只返评分 + 前 3 条建议
  │     前端展示 + "注册查看完整报告" CTA
  │
  └─ 游客注册（带 reportId）
        ├─ 后端从 Redis 取完整报告
        ├─ 用完整报告【回填】resume_analysis 表（update 该行）
        │     └─ expire_at 置 NULL（变永久）
        ├─ 删 Redis 那份
        └─ 返 JWT + 完整报告给前端
```

#### 5.4.2 为什么后端只返 3 条（不返全部让前端裁剪）

| 方案 | 风险 |
|------|------|
| 后端返全部，前端裁剪 | F12 抓包能看到全部，等于没遮 |
| **后端只返 3 条（选定）** | 真"半遮面"，懂行的人也绕不过 |

#### 5.4.3 为什么按 reportId 而非 deviceId 存 Redis

一个游客 12 小时内可能分析多份简历：
- 按 deviceId 存 → 后一份覆盖前一份，前面的完整报告丢了
- 按 reportId 存 → 每份报告独立，精准对应

注册时前端把 `reportId` 一起带上，后端精准取回那份。

#### 5.4.4 边界情况

| 情况 | 处理 |
|------|------|
| 游客注册时 Redis 里那份已过期（超 12h） | 提示"报告已过期，请重新上传"，引导重传 |
| 游客注册时没带 reportId | 正常注册，不回填，无完整报告 |
| 游客注册后想看旧报告但没绑定 | 已过期/没绑定，无法找回，重传 |

---

## 6. 注册与登录设计

### 6.1 接口清单

| 接口 | 方法 | 鉴权 | 说明 |
|------|------|------|------|
| `/api/auth/register` | POST | 游客（带 deviceId + 可选 reportId） | 游客转注册，绑定半遮面报告 |
| `/api/auth/login` | POST | 无 | 手机号 + 密码登录 |
| `/api/auth/change-password` | POST | 已登录（JWT） | 旧密码 + 新密码修改 |

> **砍掉的接口**：忘记密码、短信验证码、验证码登录（理由见 §6.3）

### 6.2 游客注册（核心：绑定半遮面报告）

```java
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ResumeAnalysisRepository resumeRepo;
    private final RedisService redisService;

    @Transactional
    public AuthResponse register(Long guestUserId, RegisterRequest req) {
        // 1. 原地升级 Guest 账号
        User user = userRepository.findById(guestUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.setStatus(UserStatus.REGISTERED);
        user.setRole(UserRole.STUDENT);                         // 默认求职者
        user.setPhone(req.phone());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setNickname(req.nickname() != null ? req.nickname() : user.getNickname());
        user.setDeviceId(null);

        // 2. 保存（依赖 DB 唯一约束防并发竞态，见 §6.4）
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_USED, "该手机号已注册");
        }

        // 3. 绑定半遮面报告（如果带了 reportId）
        if (req.reportId() != null) {
            String key = "guest:resume:report:" + req.reportId();
            FullResumeReport full = redisService.get(key, FullResumeReport.class);
            if (full != null) {
                // 用完整报告回填数据库（之前只存了摘要）
                resumeRepo.fillFullReportByUserId(user.getId(), full);
                resumeRepo.clearExpireAtByUserId(user.getId());   // 变永久
                redisService.delete(key);
            }
            // 若 Redis 已过期（full == null），不报错，提示重传即可
        }

        // 4. 发 JWT
        String token = jwtService.generate(user.getId(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getNickname(), user.getRole());
    }
}
```

### 6.3 为什么砍掉"忘记密码"

**逻辑链**：
- 验证码的真正用途是证明"这个手机号是你的"
- 学习项目砍掉短信通道 → 没有验证码 → 无法验证手机号归属
- 无法验证手机号 → 忘记密码不安全（任何人知道你手机号就能重置密码）
- → **砍掉最干净**

**替代方案**：忘记密码场景，引导用户重新注册一个新账号（学习项目，账号数据本就不珍贵）。

> 若将来上正式短信通道，再补"忘记密码"接口，预留 `@RequireRole` 同款思路的扩展位。

### 6.4 注册并发竞态防护

**风险**：用户狂点注册按钮，两个请求几乎同时打到后端。

**解法**：依赖数据库 `UNIQUE` 约束（不是锁），不做"先查后判"。

- `users.phone` 加 `UNIQUE` 约束（见 §3.3），数据库天生防并发重复
- Service 层不写 `existsByPhone` 预检查（查了也没用，并发下照样漏）
- 直接 `saveAndFlush`，捕获 `DataIntegrityViolationException` 转成 `PHONE_ALREADY_USED`
- 前端注册按钮点击后立即禁用 + 防抖（双保险）

**为什么不用消息队列**：注册是**同步、毫秒级、要即时反馈**的操作。
MQ 解决的是"耗时长、能容忍晚点知道结果"的场景。把注册塞进 MQ，
反而要引入轮询/WebSocket 才能让用户知道结果，杀鸡用牛刀。
**判断口诀**：耗时长 + 能异步 → MQ；快 + 要强一致 + 要即时反馈 → 同步做。

### 6.5 登录（手机号 + 密码）

```java
public AuthResponse login(LoginRequest req) {
    User user = userRepository.findByPhone(req.phone())
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

    if (!passwordEncoder.matches(req.password(), user.getPassword())) {
        throw new BusinessException(ErrorCode.PASSWORD_WRONG);
    }

    String token = jwtService.generate(user.getId(), user.getRole());
    return new AuthResponse(token, user.getId(), user.getNickname(), user.getRole());
}
```

### 6.6 修改密码（已登录）

```java
public void changePassword(Long userId, ChangePasswordRequest req) {
    User user = userRepository.findById(userId).orElseThrow(...);

    // 验旧密码（已登录，无需验证码）
    if (!passwordEncoder.matches(req.oldPassword(), user.getPassword())) {
        throw new BusinessException(ErrorCode.PASSWORD_WRONG);
    }

    user.setPassword(passwordEncoder.encode(req.newPassword()));
    userRepository.save(user);
    // 可选：作废旧 JWT，强制重新登录
}
```

### 6.7 数据继承逻辑（注册时为何数据不用迁移）

```
升级前（游客）：
  users(id=100, status=GUEST, device_id="a3f9-xxxx")
  resume_analysis(user_id=100, ...)          ← 只存了摘要（半遮面）
  Redis: guest:resume:report:{reportId}      ← 完整报告

升级后（注册）：
  users(id=100, status=REGISTERED, role=STUDENT, phone=..., device_id=NULL)
  resume_analysis(user_id=100, ...)          ← 回填了完整内容，expire_at=NULL
  Redis: 已删除
```

**关键**：`user_id` 始终是 100，业务数据零迁移；只是 resume_analysis 那行从"摘要"被 update 成"完整"。

---

## 7. 定时清理任务

### 7.1 实现（Spring @Scheduled）

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class GuestDataCleaner {

    private final ResumeAnalysisRepository resumeRepo;
    private final UserRepository userRepo;

    /**
     * 每天凌晨 3 点清理过期游客数据。
     * 只删 expire_at 已过期且非 NULL 的记录（登录用户数据不受影响）。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanExpiredGuestData() {
        LocalDateTime now = LocalDateTime.now();
        // 游客业务数据（只可能是 resume_analysis，游客唯一可用功能）
        int r1 = resumeRepo.deleteByExpireAtBefore(now);

        // 清理 Guest 账号本身（7 天前的孤儿账号）
        // 周期与数据保留期对齐：游客数据只留 12 小时，
        // Guest 账号给 7 天缓冲（防止注册流程中断的账号被误删）
        int r2 = userRepo.deleteOrphanGuestBefore(now.minusDays(7));

        log.info("清理游客数据：简历={}, 孤儿账号={}", r1, r2);
    }
}
```

### 7.2 启用条件

主启动类加注解：

```java
@SpringBootApplication
@EnableScheduling    // 启用定时任务
public class App { ... }
```

### 7.3 为什么不用 Python 脚本

- 与项目技术栈割裂，多维护一个运行时
- Docker 镜像要额外塞 Python + 驱动
- 数据库连接、密码、告警都要重复维护一份
- Spring `@Scheduled` 10 行代码搞定，零额外运维成本

---

## 8. 游客功能边界（产品侧）

| 功能 | 游客 | 登录用户 |
|------|------|---------|
| 简历上传分析 | ✅（占用配额，每日 3 次） | ✅ 无限/放宽 |
| 简历报告查看 | ⚠️ 半遮面（评分 + 前 3 条建议） | ✅ 完整报告 |
| 文字模拟面试 | ❌ → 弹登录 | ✅ |
| 语音面试 | ❌ → 弹登录 | ✅ |
| 知识库上传/管理 | ❌ → 弹登录 | ✅ |
| 知识库问答 | ❌ → 弹登录 | ✅ |
| 面试日程管理 | ❌ → 弹登录 | ✅ |
| PDF 报告导出 | ❌ → 弹登录（转化诱饵） | ✅ |
| 数据保留 | 12 小时 | 永久 |

**转化诱饵**：完整简历报告、文字/语音面试、知识库、日程、PDF 导出，全部设为登录才能用，自然引导注册。

---

## 9. 安全注意事项

| 风险 | 说明 | 应对 |
|------|------|------|
| deviceId 可伪造 | 任何人改请求头即可冒充 | 游客数据无价值，可接受；登录用户靠 JWT 签名 |
| 清缓存重置配额 | 清 localStorage → 新 deviceId → 配额重置 | 接受此漏洞；上量后可加 IP 维度限流兜底 |
| 手机号不验真 | 学习项目，注册随便填手机号 | 接受；将来上正式短信通道时补验证码 |
| Guest 账号被脚本刷 | 高频创建 Guest 撑爆 users 表 | 懒创建（§3.4.2）+ IP 限流（§3.4.3） |
| 暴力刷登录 | 撞库攻击 | 登录接口加 `@RateLimit`，按 IP + 手机号双维度限流 |

### 9.1 与现有 @RateLimit 的配合

项目已有可重复注解 `@RateLimit`，游客 + 登录场景叠加使用：

```java
// 游客简历分析：每小时 5 次（防短时刷），叠加每日 3 次配额
@RateLimit(key = "guest:resume:{deviceId}", count = 5, period = 3600)
@PostMapping("/resume/analyze")
public Result<ResumeBriefResponse> analyze(...) {
    quotaService.checkAndDeduct(deviceId);   // 每日 3 次配额
    return resumeService.analyze(...);        // 返摘要，完整存 Redis
}

// 登录接口：按 IP 限流防撞库
@RateLimit(key = "ip:login:{ip}", count = 10, period = 600)
@PostMapping("/auth/login")
public Result<AuthResponse> login(...) { ... }
```

- `@RateLimit`：防短期暴力刷（QPS 维度）
- 配额计数器：控每日总量（成本维度）

---

## 10. 开发任务拆解（落地顺序）

### 第 1 期：成本闸门 + 游客简历闭环（先做）

- [ ] **T1** 前端 deviceId 生成 + axios 请求头注入
- [ ] **T2** 后端 `GuestInterceptor`：识别 deviceId，注入游客上下文（不建账号）
- [ ] **T3** `users` 表 Entity + Repository（含 role 字段 + Guest 懒创建）
- [ ] **T4** Redis 配额服务（Lua 脚本原子扣减 + 当天 TTL）
- [ ] **T5** `ErrorCode` 新增 `QUOTA_EXCEEDED` / `LOGIN_REQUIRED` / `NEED_DEVICE_ID` / `PHONE_ALREADY_USED`
- [ ] **T6** 前端响应拦截器：收到上述错误码 → 弹登录/注册弹窗
- [ ] **T7** 简历分析半遮面：完整报告存 Redis，接口只返评分 + 前 3 条
- [ ] **T8** 前端简历报告页：展示评分 + 3 条建议 + "注册看完整" CTA

**验收标准**：游客用 3 次后第 4 次被拒；报告页只显示 3 条；Redis 里有完整报告 12 小时后自动过期。

### 第 2 期：账号体系（注册/登录闭环）

- [ ] **T9** `users` 表补 `phone/password/role/nickname` + BCrypt 密码编码
- [ ] **T10** `AuthService.register()`：Guest 原地升级 + 绑定半遮面报告（见 §6.2）
- [ ] **T11** `AuthService.login()`：手机号 + 密码（见 §6.5）
- [ ] **T12** `AuthService.changePassword()`：旧密码 + 新密码（见 §6.6）
- [ ] **T13** JWT 签发与校验（`JwtService` + `JwtAuthenticationFilter`，token 含 userId + role）
- [ ] **T14** `@RequireRole` 注解 + 拦截器（第一版无路由使用，占位）
- [ ] **T15** 各业务表 `expire_at` 字段：游客 12 小时，登录 NULL
- [ ] **T16** `GuestDataCleaner` 定时任务（见 §7）

**验收标准**：游客注册后报告自动补全且变永久；登录/改密正常；导师可在前端切求职者视角。

---

## 11. 待确认 / 风险项

| 项 | 状态 | 说明 |
|----|------|------|
| 3 轮/天上限 | ✅ 已定 | 后续可按真实成本数据调整 Redis 初始值 |
| 角色体系 | ✅ 已定 | STUDENT/MENTOR/ADMIN，导师可切求职者视角 |
| 登录方式 | ✅ 已定 | 手机号 + 密码唯一 |
| 短信/验证码 | ✅ 已定（不做） | 学习项目，砍掉 |
| 忘记密码 | ✅ 已定（砍掉） | 无验证码则不安全 |
| 12 小时保留期 | 🟡 默认值 | 可按实际观察调整 |
| 修改密码的"作废旧 JWT" | ⏳ 待定 | 改密后是否强制重登？建议第 2 期定 |
| 导师专属功能 | ⏳ 待定 | 第一版只有 STUDENT 视角共用，导师独有功能后定 |
| ADMIN 后台 | ⏳ 占位 | 第一版无后台，将来按需开发 |

### 11.1 已知风险（当前接受，不处理）

| 风险 | 触发场景 | 当前决策 | 后续可能的解法 |
|------|---------|---------|--------------|
| 多设备/多浏览器数据分裂 | 用户在 Chrome 传简历，又用 Firefox 打开 | ✅ 接受 | 注册时合并多个 deviceId 的数据（逻辑复杂，暂不做）|
| 无痕模式数据丢失 | 无痕窗口 localStorage 关窗即清 | ✅ 接受 | 第 2 期可做"分享码"机制 |
| 半遮面报告 Redis 过期 | 游客超 12 小时才注册 | ✅ 接受，提示重传 | 延长 TTL 或换存储（暂不调） |
| 手机号不验真 | 任何人可用任意手机号注册 | ✅ 接受 | 上正式短信通道时补验证码 |
| 清缓存重置配额 | 清 localStorage 重置 deviceId | ✅ 接受 | 上量后加 IP 维度限流 |

---

## 附录 A：包结构建议

```
com.interview.guide
├── common/
│   ├── auth/                      # 新增
│   │   ├── GuestInterceptor.java
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── RequireRole.java       # @RequireRole 注解（占位）
│   │   ├── RequireRoleInterceptor.java
│   │   ├── CurrentUser.java       # 请求上下文持有者
│   │   └── UserRole.java          # STUDENT / MENTOR / ADMIN
│   ├── quota/                     # 新增
│   │   ├── QuotaService.java
│   │   └── QuotaResult.java
│   └── ...
├── modules/
│   ├── auth/                      # 新增（第 2 期）
│   │   ├── AuthService.java
│   │   ├── AuthController.java
│   │   ├── JwtService.java
│   │   └── dto/{RegisterRequest,LoginRequest,AuthResponse}.java
│   └── user/                      # 新增
│       ├── User.java
│       ├── UserStatus.java        # GUEST | REGISTERED
│       ├── UserRepository.java
│       └── UserService.java
└── infrastructure/
    └── cleaner/                   # 新增
        └── GuestDataCleaner.java
```

## 附录 B：前端要点

```ts
// src/utils/device.ts
const DEVICE_KEY = 'deviceId';
export function getDeviceId(): string {
  let id = localStorage.getItem(DEVICE_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(DEVICE_KEY, id);
  }
  return id;
}

// src/api/interceptors.ts —— 请求拦截
axios.interceptors.request.use(cfg => {
  const token = localStorage.getItem('token');
  if (token) {
    cfg.headers.Authorization = `Bearer ${token}`;        // 登录用户优先
  } else {
    cfg.headers['X-Device-Id'] = getDeviceId();           // 游客
  }
  return cfg;
});

// src/api/interceptors.ts —— 响应拦截
axios.interceptors.response.use(
  res => res,
  err => {
    if (err.code === 4001 || err.code === 4002) {
      useAuthStore().showLoginModal();    // 弹登录框
    }
    return Promise.reject(err);
  }
);

// 导师切求职者视角（纯前端）
function switchToStudentView() {
  localStorage.setItem('viewRole', 'student');
  location.reload();    // 重新渲染 UI
}
```
