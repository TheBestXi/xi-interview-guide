# 对话式面试引擎 · 开发设计文档

> 本文档定义**对话式模拟面试模块**（Conversational Interview Engine）的技术实现方案。
> 这是源项目（问卷式面试）的**核心改造版本**，不是简单复现。
> 配套产品视角文档见 `05-interview-conversational-features.md`。
> 上游依赖：`03-auth-and-role-design.md`（登录）、`04-resume-module-dev.md`（简历）。

---

## 0. TL;DR

源项目的文字面试是"**一次性出 N 道题、逐题作答、最后评分**"——题目预设、无上下文、无动态追问、按题打分。本模块把它改造成"**AI 实时对话、根据回答动态追问、按能力维度打分**"，更接近真实面试体验。

**一句话总结**：源项目的面试 = 问卷 + 评分引擎；本模块的面试 = Agent + 状态机 + 维度评分。

---

## 1. 为什么改：源项目的硬伤

### 1.1 源项目的四个产品硬伤

| 问题 | 源项目的表现 | 真实面试里 |
|------|-------------|----------|
| 题目预设 | 创建会话时一次性生成全部题目（含 followUp）| 面试官根据你答的内容动态追问 |
| 无上下文 | 每题独立，AI 不记得你前一轮说了什么 | 面试官会"你刚提到 XX，能展开吗" |
| 答得跑题也不纠偏 | 不管你答得对错，到下一题就是下一题 | 面试官会拉回主题、或深挖薄弱点 |
| 按题打分 | 每题独立打分，加总平均 | 真实评估看的是"Redis 掌握度 75 分"，不是"第 3 题 80 分" |

### 1.2 这些硬伤的根因（代码层证据）

扒源码 `InterviewSessionService.java`：

```java
// 第 78 行：题目在创建会话时一次性生成完
List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
    ..., request.questionCount(), historicalQuestions, ...
);

// 第 90 行：题单存 Redis
sessionCache.saveSession(sessionId, ..., questions, 0, SessionStatus.CREATED);

// 第 292-334 行：submitAnswer 只做"写答案 + 推 currentIndex"
//   完全没有调 LLM、没有评估当前回答、没有决定下一题
```

**根因**：题目是"静态列表"，AI 在答题过程中完全不参与。这跟"AI 面试官"的产品定位严重不符。

### 1.3 改造后的目标

| 维度 | 源项目（问卷式）| 本模块（对话式）|
|------|---------------|---------------|
| 出题时机 | 一次性全出 | 按需动态出 |
| 追问来源 | 预设 followUp 字段 | LLM 根据回答实时生成 |
| 上下文 | 无 | 多轮累积，带状态摘要 |
| 答题跑题 | 不纠偏 | AI 拉回主题 |
| 评分对象 | 每道题 | 每个能力维度 |
| 路径 | 所有人走相同题单 | 每个人路径不同 |

---

## 2. 核心架构：三轮驱动 + 状态机

整个对话式引擎由**三个独立的 LLM 调用**协同，加上一个**状态机**串起来。这是本设计的核心。

```
┌──────────────────────────────────────────────────────────────┐
│                  对话式面试引擎主循环                          │
│                                                              │
│  用户回答 ──► ① 状态评估 ──► 决策 ──► ② 内容生成 ──► AI回复  │
│                  │                              │            │
│                  │   累积更新到 State           │            │
│                  └──────────────────────────────┘            │
│                                                              │
│  面试结束 ──► ③ 维度评分（异步）                              │
└──────────────────────────────────────────────────────────────┘
```

### 2.1 三个独立的 LLM 角色（关键设计）

**这是跟源项目最大的区别。** 源项目一次 LLM 调用干所有事（出题+评估），本模块拆成三个独立角色，各司其职：

#### 角色 A：状态评估器（State Evaluator）

**职责**：每轮用户回答后，先调一次 LLM 做决策——"接下来该干什么"。

```yaml
输入：
  - 当前 Skill 方向（java-backend 等）
  - 已问过的考点 + 用户在每个考点的表现
  - 最近 3-5 轮对话
  - 总时长/总轮数/难度梯度

输出（JSON）：
  action: CONTINUE_DEEPEN | SWITCH_TOPIC | GIVE_HINT | END_INTERVIEW
  reason: "用户已答对 Redis 持久化核心点，深挖空间不大"
  nextTopic: "MySQL 索引"     # 仅 SWITCH_TOPIC 时填
  deepeningAngle: "AOF fsync 策略对延迟的影响"  # 仅 CONTINUE_DEEPEN 时填
```

**为什么独立**：决策逻辑复杂、需要严格 JSON 输出、容错要求高。跟"生成自然语言回复"混在一起，模型容易跑偏（一边聊天一边决策，容易顾此失彼）。

**类比**：状态评估器是"面试官的大脑"，只管"下一步该干嘛"，不管"怎么把话说漂亮"。

#### 角色 B：面试官回复生成器（Interviewer Agent）

**职责**：根据状态评估器的决策，生成**自然语言回复**给用户。

```yaml
输入：
  - 状态评估器的 action + nextTopic/deepeningAngle
  - 最近对话历史（保持连贯）
  - Skill 方向的参考知识库（注入参考基线）

输出：
  - 自然语言面试官回复（流式，打字机式）
  - 例如："不错，那 AOF 的 fsync 策略有哪些？你能说说每种的延迟和安全性权衡吗？"
```

**为什么独立**：生成回复是"创意性"任务，需要温度稍高（0.7）、需要流式输出、需要参考 Skill 知识库。这跟决策的"严格性"要求冲突。

**类比**：这是面试官的"嘴"，把脑子里的决策表达成流畅的话。

#### 角色 C：维度评估器（Dimension Evaluator）

**职责**：面试结束后（或异步触发），按**能力维度**而非题目打分。

```yaml
输入：
  - 整场对话历史
  - Skill 定义的所有考点（categories）
  - 简历文本（可选，参考候选人背景）

输出：
  dimensionScores:
    redis: 78
    mysql: 65
    distributed: 42
    spring: 88
  overallScore: 72
  strengths: ["Redis 底层原理掌握扎实", ...]
  improvements: ["分布式事务场景理解偏浅", ...]
  evidence: [{dimension, quote, comment}, ...]   # 每个维度的证据
```

**为什么独立**：评估需要"看完整体再打分"，不能边问边打。而且评估的 prompt 很长（整场对话），跟实时对话的"短 prompt 快速响应"完全不同。

**类比**：这是面试官的"评分表"，面试结束后才填。

### 2.2 三角色协作流程

```
用户："我用了 Redis 的 ZSet 做排行榜"
   │
   ├─ 角色 A（状态评估器）：
   │     "用户答到了 ZSet，但没说为什么用。可以深挖底层。"
   │     → action: CONTINUE_DEEPEN
   │     → deepeningAngle: "ZSet 底层数据结构（跳表 vs 压缩列表）"
   │
   ├─ 角色 B（面试官 Agent）：
   │     接到 action=CONTINUE_DEEPEN + angle="ZSet 底层结构"
   │     流式生成："那 ZSet 底层用了什么数据结构？为什么不用红黑树？"
   │
   └─ 用户看到回复，继续答
   
   ... 多轮后 ...
   
用户完成面试 / 时间到 / 角色 A 决策 END_INTERVIEW
   │
   └─ 角色 C（维度评估器，异步）：
         看整场对话历史
         按 categories 打分
         生成报告
```

### 2.3 状态机（State Machine）

整个面试由一个状态机驱动，每个状态对应 AI 的不同行为：

```
                    ┌──────────────────┐
                    │   INIT           │ 初始化：加载 Skill + 简历
                    │   加载考点 + 简历 │ 生成第一个考点
                    └────────┬─────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │   ASKING         │ AI 提问中
                    │   AI 等用户回答   │
                    └────────┬─────────┘
                             │ 用户提交回答
                             ▼
                    ┌──────────────────┐
                    │   EVALUATING     │ 角色 A：状态评估
       ┌───────────►│   决策下一步动作 │◄───────────┐
       │            └────────┬─────────┘            │
       │                     │                      │
       │   ┌─────────────────┼─────────────────┐    │
       │   │                 │                 │    │
       │   ▼                 ▼                 ▼    │
       │ CONTINUE_DEEPEN  SWITCH_TOPIC     GIVE_HINT│
       │ (深挖当前)        (换考点)         (给提示) │
       │   │                 │                 │    │
       │   └─────────────────┴─────────────────┘    │
       │                     │                      │
       │                     ▼                      │
       │            ┌──────────────────┐            │
       │            │   RESPONDING     │ 角色 B：生成回复
       │            │   流式输出        │────────────┘
       │            └────────┬─────────┘
       │                     │
       │                     ▼
       │            用户继续答（回到 EVALUATING）
       │
       └─ 当 action == END_INTERVIEW 或 超时 或 用户主动结束
                    ┌──────────────────┐
                    │   COMPLETED      │ 面试结束
                    └────────┬─────────┘
                             │ 异步
                             ▼
                    ┌──────────────────┐
                    │   EVALUATED      │ 角色 C：维度评分
                    │   生成报告        │
                    └──────────────────┘
```

**为什么需要状态机**：避免 AI 行为混乱。比如"AI 正在流式输出时用户又提交了新答案"——必须明确"流式输出时是 RESPONDING 状态，不接受新输入"。

---

## 3. 状态管理：State Snapshot 机制

这是对话式最核心的技术难点——**怎么让 LLM 在多轮对话里"记得住"。**

### 3.1 三种主流方案对比

| 方案 | 做法 | 优点 | 缺点 |
|------|------|------|------|
| A. 全量历史回灌 | 每轮把全部历史塞进 prompt | 简单 | token 爆炸、长上下文注意力下降 |
| B. 滑动窗口 | 只带最近 N 轮 | token 可控 | 早期对话信息丢失 |
| C. **状态快照（选定）** | 维护结构化状态摘要 + 最近少量历史 | 兼顾记忆和成本 | 实现复杂 |

### 3.2 状态快照设计

维护一个**结构化状态对象**，每轮更新。调 LLM 时，传这个快照 + 最近 3-5 轮对话。

```java
public record InterviewState(
    String sessionId,
    String skillId,
    String resumeText,                    // 简历（可选）
    
    // === 时间进度 ===
    int totalTurns,                       // 已对话轮数
    int maxTurns,                         // 上限（如 15 轮）
    Duration elapsed,                     // 已耗时
    Duration maxDuration,                 // 上限（如 30 分钟）
    
    // === 考点覆盖（核心）===
    List<TopicCoverage> topics,           // Skill 定义的考点 + 当前覆盖情况
    
    // === 难度梯度 ===
    DifficultyLevel currentDifficulty,    // EASY/MEDIUM/HARD
    float targetDifficultyDistribution,   // 目标分布（30/50/20）
    
    // === 对话上下文 ===
    List<Message> recentMessages,         // 最近 3-5 轮原始对话
    String sessionSummary,                // 整场对话的累积摘要（每轮更新）
    
    // === 状态机 ===
    InterviewStatus status                // INIT/ASKING/EVALUATING/...
) {}

public record TopicCoverage(
    String topicKey,                      // 如 "redis"
    String topicLabel,                    // "Redis"
    TopicStatus status,                   // NOT_STARTED / IN_PROGRESS / COVERED / SKIPPED
    int depthLevel,                       // 0-3，已挖多深
    int lastScore,                        // 0-100，用户在这个考点的表现
    String notes                          // 评估器备注："对跳表理解到位，AOF 不熟"
) {}
```

### 3.3 为什么用快照而不是全量历史

**例**：一场 15 轮的面试，全量历史大约 5000-8000 token。

```
方案 A（全量回灌）：
  第 1 轮 prompt：3000 token
  第 10 轮 prompt：6000 token
  第 15 轮 prompt：8000 token
  
  问题：
  1. token 成本线性增长
  2. 模型对长 prompt 注意力下降（"lost in the middle"）
  3. 重要信息被大量无关对话稀释

方案 C（快照 + 最近 3 轮）：
  每轮 prompt = 快照（~1000 token 稳定）+ 最近 3 轮（~1500 token）+ Skill 参考（~2000 token）
            ≈ 4500 token 稳定
  
  快照里 sessionSummary 字段是整场对话的压缩摘要
  每 3 轮用小 LLM 调用更新一次摘要（异步，不阻塞对话）
```

**核心**：用结构化快照保留"重要信息"（考点覆盖、难度、分数），用摘要保留"对话要点"，用最近 3 轮保留"语感连贯"。

### 3.4 状态持久化

```
Redis（热路径）：
  key: interview:state:{sessionId}
  value: InterviewState 对象
  TTL: 24 小时（跟源项目对齐）

PostgreSQL（持久化）：
  interview_sessions 表：会话元数据 + status
  interview_messages 表：每轮对话（user/assistant）
  interview_state_snapshots 表：状态快照（每 3 轮存一份，用于复盘）
```

---

## 4. Skill 知识库复用：从"题库"变"考点地图"

**关键决策：不重写 Skill 系统，把它从"出题源"改造成"考点地图"。**

### 4.1 源项目的 Skill（保留）

源项目 11 个方向的 `SKILL.md` + `skill.meta.yml` 仍然有价值——它定义了"该考什么"。但**用法变了**：

```
原用法（问卷式）：
  Skill 定义 categories（考点） → 出题器按 categories 生成题目 → 用户逐题答

新用法（对话式）：
  Skill 定义 categories（考点） → 当作"考点地图"
  AI 在地图上自由走，根据用户表现决定深挖还是切换
```

### 4.2 考点地图怎么用

```
Skill: java-backend
  categories:
    - MYSQL          (priority: CORE)
    - REDIS          (priority: CORE)
    - SPRING         (priority: ALWAYS_ONE)
    - JVM            (priority: NORMAL)
    - DISTRIBUTED    (priority: NORMAL)
    - DESIGN_PATTERN (priority: NORMAL)

AI 的工作方式：
  1. 必考 CORE 考点（MySQL/Redis）
  2. 至少问一个 ALWAYS_ONE（Spring）
  3. NORMAL 按用户简历/回答动态选
  4. 每个考点深挖 1-3 层，根据用户表现决定深度
```

### 4.3 参考知识库注入

源项目 `skills/_shared/references/redis.md` 等参考文档**继续用**，但注入时机变了：

```
原注入：出题时一次性注入所有考点参考（可能 12000 token）
新注入：每轮只注入"当前正在聊的考点"参考（~2000 token）

例：AI 当前在聊 Redis
  → 注入 redis.md（~2000 token）
  → AI 拿到标准答案，能判断用户答得对不对
  → 切到 MySQL 时，redis.md 摘掉，注入 mysql.md
```

**省 token + 注意力更聚焦**。

---

## 5. 决策引擎：四种动作的细节

状态评估器（角色 A）输出四种动作，每种对应不同行为：

### 5.1 CONTINUE_DEEPEN（继续深挖当前考点）

**触发条件**：
- 用户答对了一部分但有深挖空间
- 考点 priority 高（CORE）且 depthLevel < 3
- 用户主动表达了兴趣（"我对这块挺熟"）

**AI 行为**：基于 deepeningAngle 生成追问。

```
用户："Redis 用了 ZSet 做排行榜"
状态评估：action=CONTINUE_DEEPEN, angle="ZSet 底层数据结构"
AI 回复："那 ZSet 底层用了什么？为什么选这个而不是红黑树？"
```

### 5.2 SWITCH_TOPIC（切换到下一个考点）

**触发条件**：
- 当前考点已覆盖（status=COVERED）
- 用户明显答不动（连续 2 次低分）
- 当前考点 priority 低（NORMAL），不必死磕
- 时间/轮数预算需要分配给其他考点

**AI 行为**：自然过渡 + 抛出 nextTopic 的第一个问题。

```
状态评估：action=SWITCH_TOPIC, nextTopic="MySQL 索引", reason="Redis 已覆盖 3 层"
AI 回复："好的，Redis 我们聊到这。换个方向——MySQL 的索引底层是什么结构？"
```

### 5.3 GIVE_HINT（给提示，不直接给答案）

**触发条件**：
- 用户明显卡住（连续 2 次回答不完整）
- 考点 priority 高，值得多花时间
- 用户回答方向对但细节错

**AI 行为**：给方向性提示，引导用户继续思考。

```
用户："ZSet 底层是...我忘了"
状态评估：action=GIVE_HINT, hint="提示：跟有序性 + 范围查询效率有关"
AI 回复："想想看，如果要支持 O(logN) 的范围查询，什么样的数据结构合适？"
```

### 5.4 END_INTERVIEW（结束面试）

**触发条件**：
- 所有 CORE 考点都已 COVERED
- 总轮数达到 maxTurns（如 15）
- 总耗时达到 maxDuration（如 30 分钟）
- 用户主动结束

**AI 行为**：礼貌收尾 + 触发异步评估。

```
状态评估：action=END_INTERVIEW, reason="所有核心考点已覆盖，时长 28 分钟"
AI 回复："好的，今天的面试就到这里。你的 Redis 和 Spring 表现不错，分布式可以再深入。我们会生成详细的评估报告。"
→ 触发维度评估（异步）
```

---

## 6. 维度评分：从"按题打分"到"按能力打分"

这是另一个核心改造点。源项目按每道题打分，本模块按每个考点打分。

### 6.1 为什么维度评分

**问题**：对话式路径不同，没法按题打分。

```
张三的对话：Redis → 跳表 → 持久化 → MySQL 索引 → B+ 树
李四的对话：Redis → 集群 → 哨兵 → Spring → IOC

两人聊的题完全不一样，但都考了 Redis 和 MySQL。
维度评分：两人都按 "Redis 掌握度 / MySQL 掌握度" 打分，可以横向对比。
```

### 6.2 评分维度定义

```java
public record DimensionEvaluation(
    String dimensionKey,        // "redis"
    String dimensionLabel,      // "Redis"
    int score,                  // 0-100
    ScoreLevel level,           // EXCELLENT/GOOD/AVERAGE/WEAK
    String summary,             // "底层原理掌握扎实，集群理解偏浅"
    List<EvidenceQuote> evidence  // 支撑评分的对话引用
) {}

public record EvidenceQuote(
    String userQuote,           // 用户原话
    String aiQuestion,          // AI 问的题
    String comment,             // "这里答错了，把 ZSet 的跳表说成了红黑树"
    int quoteScore              // 这一段对话的分数
) {}
```

### 6.3 评估输入（异步触发）

```yaml
输入：
  - 整场对话历史（全部 messages）
  - Skill 定义的所有考点（dimensions）
  - 每个考点的预期深度（CORE 要 3 层，NORMAL 要 1 层）
  - 简历（可选）

输出：
  overallScore: 72
  dimensionScores:
    redis: {score: 85, level: EXCELLENT, evidence: [...]}
    mysql: {score: 60, level: AVERAGE, evidence: [...]}
    spring: {score: 78, level: GOOD, evidence: [...]}
    distributed: {score: 45, level: WEAK, evidence: [...]}
  strengths: ["Redis 底层数据结构理解深入，能讲清跳表的实现原理"]
  improvements: ["分布式事务场景理解偏浅，建议补强 Seata / 消息事务"]
```

### 6.4 评估复用源项目的"分批 + 汇总"

源项目 `UnifiedEvaluationService` 的分批评估 + 二次汇总机制**可以复用**，但改造维度：

```
原批次：按题目分批（每批 8 题）
新批次：按对话片段分批（每批 5-8 轮对话）

原汇总：题目分数加总
新汇总：维度分数聚合（同一维度的多个片段分数加权平均）
```

---

## 7. Prompt 模板设计

新增 4 个 prompt 模板，放 `resources/prompts/`：

### 7.1 `interview-state-evaluator-system.st`（角色 A）

```markdown
# Role
你是面试官的大脑，负责"决策下一步动作"。你不直接面对用户，只输出 JSON 决策。

# Task
基于当前面试状态（已问考点、用户表现、剩余预算），决定下一步。

# Decision Rules
1. CORE 考点必须覆盖到 depthLevel >= 2
2. 用户连续 2 次低分 → GIVE_HINT 或 SWITCH_TOPIC
3. 所有 CORE 覆盖完 + 剩余预算 < 30% → 考虑 END_INTERVIEW
4. 不要在同一考点死磕超过 4 轮

# Output Format
严格输出 JSON，字段：action, reason, nextTopic?, deepeningAngle?, hint?
```

### 7.2 `interview-agent-system.st`（角色 B）

```markdown
# Role
你是一位经验丰富的技术面试官。你正在和候选人对话。

# Persona
- 专业、礼貌但有挑战性
- 一次只问一个问题
- 不要一次输出超过 3 句话
- 不要 Markdown 列表，用自然语言
- 用户答错时不要直接纠正，先追问

# Input
- 当前 action（来自状态评估器）
- deepeningAngle / nextTopic / hint
- 最近对话历史

# Constraint
每轮只问一个问题，不要一次抛多个。
```

### 7.3 `interview-dimension-evaluator-system.st`（角色 C）

```markdown
# Role
你是面试评估专家。基于整场对话，按能力维度评分。

# Task
对每个 dimension 输出：
- score (0-100)
- level (EXCELLENT/GOOD/AVERAGE/WEAK)
- summary (一句话)
- evidence (用户原话 + 评价)

# Scoring Rubric
- EXCELLENT (85+): 能讲清底层原理 + 实战经验 + 量化产出
- GOOD (70-84): 理解原理，能讲清核心机制
- AVERAGE (50-69): 知道概念，细节模糊
- WEAK (<50): 概念错误或完全不会
```

### 7.4 `interview-state-summary.st`（状态摘要更新）

每 3 轮调一次，把累积对话压缩成结构化摘要，更新到 state.sessionSummary。

---

## 8. 数据库设计

### 8.1 表结构

```sql
-- 会话（改造自源项目 interview_sessions）
CREATE TABLE interview_sessions (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64) UNIQUE NOT NULL,
    user_id         BIGINT NOT NULL,
    resume_id       BIGINT,                     -- 可选
    skill_id        VARCHAR(64) NOT NULL,
    difficulty      VARCHAR(20),
    llm_provider    VARCHAR(64),
    
    -- 对话式新增字段
    max_turns       INTEGER DEFAULT 15,
    max_duration_sec INTEGER DEFAULT 1800,
    current_topic   VARCHAR(64),                -- 当前正在聊的考点
    
    -- 状态机
    status          VARCHAR(20) NOT NULL,       -- INIT/ASKING/EVALUATING/RESPONDING/COMPLETED/EVALUATED
    
    -- 评估产物
    overall_score   INTEGER,
    dimension_scores JSONB,                      -- 维度评分 JSON
    overall_feedback TEXT,
    strengths_json  TEXT,
    improvements_json TEXT,
    
    -- 异步评估
    evaluate_status VARCHAR(20),                 -- PENDING/PROCESSING/COMPLETED/FAILED
    
    created_at      TIMESTAMP DEFAULT NOW(),
    completed_at    TIMESTAMP,
    CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- 对话消息（改造自源项目 interview_answers）
CREATE TABLE interview_messages (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL,
    message_order   INTEGER NOT NULL,
    role            VARCHAR(20) NOT NULL,        -- USER / ASSISTANT / STATE_DECISION
    content         TEXT NOT NULL,
    topic           VARCHAR(64),                 -- 这条消息聊的考点
    action          VARCHAR(30),                 -- ASSISTANT 时记录 action
    metadata        JSONB,                       -- 扩展字段
    created_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(session_id, message_order)
);

-- 状态快照（新增，用于复盘和断点续面）
CREATE TABLE interview_state_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL,
    snapshot_turn   INTEGER NOT NULL,
    state_json      JSONB NOT NULL,              -- 完整 InterviewState
    created_at      TIMESTAMP DEFAULT NOW()
);
```

### 8.2 跟源项目表的关系

| 源项目表 | 本模块对应 | 变化 |
|---------|----------|------|
| `interview_sessions` | `interview_sessions`（改造）| 加对话式字段 + 维度评分字段 |
| `interview_answers` | `interview_messages`（改造）| 从"按题"改成"按轮次"，role 不再是题 |
| 无 | `interview_state_snapshots`（新增）| 状态快照，支持断点续面 |

---

## 9. 接口设计

### 9.1 创建面试会话

```
POST /api/interview/sessions
{
  "skillId": "java-backend",
  "resumeId": 123,                    // 可选
  "difficulty": "mid",                // junior/mid/senior
  "maxTurns": 15,
  "maxDurationSec": 1800
}

Response:
{
  "sessionId": "a3f9c2d4e5f67890",
  "firstQuestion": "你好，我们开始面试。先聊聊你简历里提到的电商订单系统...",  // 角色 B 流式
  "status": "ASKING"
}
```

**关键**：创建后立即调一次角色 B 生成开场白（基于 Skill + 简历）。

### 9.2 提交回答（核心接口）

```
POST /api/interview/sessions/{sessionId}/messages
{
  "content": "我用了 Redis 的 ZSet 做排行榜"
}

Response（流式 SSE）:
data: {"type": "thinking"}                     // AI 思考中
data: {"type": "chunk", "content": "不错"}      // 流式输出片段
data: {"type": "chunk", "content": "，那 ZSet"}
data: {"type": "chunk", "content": "底层用了什么？"}
data: {"type": "meta", "action": "CONTINUE_DEEPEN", "topic": "redis"}
data: {"type": "done"}
```

**关键**：每次提交回答，后端走完整三步：状态评估 → 内容生成 → 流式返回。

### 9.3 结束面试

```
POST /api/interview/sessions/{sessionId}/end
→ 触发异步维度评估
→ 返回 evaluationId 供前端轮询
```

### 9.4 获取报告

```
GET /api/interview/sessions/{sessionId}/report

Response:
{
  "overallScore": 72,
  "dimensions": [
    {"key": "redis", "score": 85, "level": "EXCELLENT", "evidence": [...]},
    {"key": "mysql", "score": 60, "level": "AVERAGE", "evidence": [...]},
    ...
  ],
  "strengths": [...],
  "improvements": [...]
}
```

---

## 10. 跟源项目的差异对照表

| 维度 | 源项目（问卷式）| 本模块（对话式）|
|------|---------------|---------------|
| 出题时机 | 一次性生成全部 | 按需动态 |
| LLM 调用次数 | 2 次（出题 + 评估）| N×2+1 次（每轮 2 次 + 评估 1 次）|
| 上下文 | 无 | 状态快照 + 摘要 + 最近 3 轮 |
| 评分对象 | 每道题 | 每个能力维度 |
| 追问来源 | 预设 followUp | 状态评估器实时决策 |
| Skill 角色 | 出题源 | 考点地图 |
| Skill 参考注入 | 一次性全注入 | 按当前考点动态注入 |
| 数据模型 | sessions + answers | sessions + messages + snapshots |
| 流式输出 | 仅评估时可选 | 每轮对话都流式 |
| 评估复用 | 分批 + 汇总 | 同（改造维度）|

---

## 11. 成本与性能

### 11.1 成本测算

单场面试（15 轮对话）：

| 步骤 | 调用次数 | 单次 token | 小计 |
|------|---------|----------|------|
| 状态评估器 | 15 | ~3000 in / 200 out | 15 × 3200 = 48K |
| 面试官 Agent（流式）| 15 | ~2500 in / 300 out | 15 × 2800 = 42K |
| 状态摘要（每 3 轮）| 5 | ~4000 in / 500 out | 5 × 4500 = 22.5K |
| 维度评估（异步）| 1 | ~8000 in / 2000 out | 10K |
| **合计** | — | — | **~122K token** |

按 qwen-plus 价格（输入 0.0008/千、输出 0.002/千）：

```
约 0.10-0.15 元/场
对比源项目问卷式：~0.05 元/场
贵了 2-3 倍，但绝对值仍很低
```

### 11.2 响应延迟优化

**关键瓶颈**：每轮要调 2 次 LLM（状态评估 + 内容生成），串行延迟可能 5-10 秒。

**优化策略**：

| 优化点 | 收益 |
|-------|------|
| 状态评估器用便宜小模型（qwen-flash）| 快 1-2 秒 |
| 内容生成流式（边生成边推送）| 首字延迟降到 1-2 秒 |
| 状态摘要异步更新（不阻塞对话）| 主链路少一次 LLM 调用 |
| Skill 参考只注入当前考点 | token 减少 60%+ |

---

## 12. 待确认 / 风险

| 项 | 状态 | 说明 |
|----|------|------|
| 决策准确率 | ⏳ 待实测 | LLM 状态评估的准确率需大量测试调优 |
| 维度评分可比性 | ⏳ 待评估 | 不同路径打分是否真能横向对比 |
| 上下文丢失 | 🟡 已缓解 | 状态快照+摘要已处理，极端长对话仍可能丢细节 |
| 成本翻倍 | ✅ 可接受 | 2-3 倍但绝对值低（< 0.15 元/场）|
| Skill 知识库更新 | ⏳ 待定 | 11 个方向的 references 是否要扩充 |
| 反作弊 | ⏳ 待定 | 用户可能用 ChatGPT 作弊（暂时不处理）|

---

## 附录 A：包结构建议

```
com.interview.guide.modules.interview
├── controller/
│   └── InterviewController.java
├── service/
│   ├── InterviewSessionService.java         # 会话管理
│   ├── StateEvaluatorService.java           # 角色 A
│   ├── InterviewerAgentService.java         # 角色 B
│   ├── DimensionEvaluatorService.java       # 角色 C
│   └── StateSummaryService.java             # 状态摘要
├── engine/                                  # 新增
│   ├── InterviewStateMachine.java           # 状态机
│   ├── StateSnapshotManager.java            # 快照管理
│   └── TopicCoverageTracker.java            # 考点覆盖跟踪
├── dto/
│   ├── InterviewState.java
│   ├── TopicCoverage.java
│   ├── StateDecision.java
│   └── DimensionEvaluation.java
└── entity/
    ├── InterviewSessionEntity.java
    ├── InterviewMessageEntity.java
    └── InterviewStateSnapshotEntity.java
```

## 附录 B：Prompt 模板清单（新增）

| 文件 | 用途 |
|------|------|
| `interview-state-evaluator-system.st` | 角色 A 系统提示 |
| `interview-state-evaluator-user.st` | 角色 A 用户提示（注入 state + history）|
| `interview-agent-system.st` | 角色 B 系统提示 |
| `interview-agent-user.st` | 角色 B 用户提示（注入 action + history）|
| `interview-dimension-evaluator-system.st` | 角色 C 系统提示 |
| `interview-dimension-evaluator-user.st` | 角色 C 用户提示（注入对话历史 + dimensions）|
| `interview-state-summary.st` | 状态摘要更新 |
| `interview-opening.st` | 开场白生成 |
