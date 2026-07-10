# 简历模块开发设计文档

> 配合 `01-technical-design.md`、`03-auth-and-role-design.md` 使用。
> 本文档定义简历模块的**技术实现方案**：文件解析路由、多栏检测、多模态降级、
> 结构化字段提取、半遮面集成、定时清理。
> 对应的功能/产品描述见 `04-resume-module-features.md`。

---

## 1. 设计目标

1. **解析准确**：覆盖 PDF（单栏/多栏）+ Word，不因复杂排版翻车。
2. **成本可控**：单栏走 Tika（免费），多栏才走多模态（< 1 分钱/份），平均成本 < 1 分钱。
3. **结构化**：把简历拆成可查询的字段（姓名/电话/教育/工作/项目/技能），供面试出题复用。
4. **可被游客试用**：对接 `03-auth` 的半遮面机制（完整报告存 Redis，注册时绑定）。
5. **栈内实现**：Tika + PDFBox + Qwen-VL-OCR + iText，全部已在项目技术栈内。

---

## 2. 核心决策（已锁定）

| 决策项 | 选定方案 | 理由 |
|--------|---------|------|
| 支持格式 | PDF + Word（图片第一版拦截） | 覆盖主流场景，砍掉 OCR 全量成本 |
| Word 解析 | Tika | Word 是流式文档，Tika 顺序正确 |
| 单栏 PDF 解析 | Tika | 顺序正确，免费 |
| 多栏 PDF 解析 | Qwen-VL-OCR（PDF 转图后） | 多栏 Tika 会串读，看图最准 |
| 多栏检测 | PDFBox 读坐标 + 双信号校验 | 基于几何特征，准确率 95%+ |
| 多模态模型 | Qwen-VL-OCR（0.006 元/千token） | 专做 OCR，最便宜 |
| 结构化字段 | LLM 拆字段（单独 Prompt） | 字段供面试出题复用 |
| 解析后处理 | LLM 点评时输出 `layoutIssue` 兜底 | 零成本兜住检测漏判的 5% |
| 半遮面集成 | 完整报告存 Redis（reportId，TTL 12h） | 对接 03-auth §5.4 |
| 报告导出 | iText 8 生成 PDF，登录用户可用 | 本地渲染，零 token 成本 |

---

## 3. 解析路由总览

```
用户上传文件
    │
    ├─ 格式校验（拦截图片/超大文件）
    │
    ▼
文件类型分流
    │
    ├─ Word（.doc/.docx）
    │     └─ Tika 抽文本 → §6 结构化 + 点评
    │
    ├─ PDF
    │     └─ PDFBox 判栏（§4）
    │           ├─ 单栏 → Tika 抽文本 → §6 结构化 + 点评
    │           └─ 多栏 → §5 多模态降级 → §6 结构化 + 点评
    │
    └─ 图片（jpg/png/...）
          └─ 前端拦截：提示"暂不支持图片格式，请上传 PDF 或 Word"
```

### 3.1 为什么按格式分流，不靠"解析后检测"

| 方案 | 时机 | 问题 |
|------|------|------|
| 解析后检测文本混乱度 | Tika 之后 | 文本已丢坐标，只能"猜"，准确率 80% |
| **按格式 + 坐标分流（选定）** | Tika 之前 | 几何特征可靠，准确率 95%+ |

关键：**判断必须发生在 Tika 之前**。Tika 抽出文本后坐标就丢了，再判断就退化成猜。

---

## 4. 多栏检测算法（PDFBox + 双信号）

### 4.1 检测原理

PDF 文件保存了每个文字片段的 x/y 坐标。单栏 vs 多栏的几何差异：

- **单栏**：所有文字 x 坐标集中在一条窄带
- **多栏**：x 坐标分成两堆，中间有空白带

### 4.2 单栏 vs 多栏坐标分布

```
单栏（x 跨度窄）：
  |----[文字]----|
  0             pageWidth

双栏（x 分两堆 + 中间空白）：
  |[左栏]      [右栏]|
  0             pageWidth
```

### 4.3 朴素算法（会误判，需 §4.4 校验）

```java
boolean isMultiColumn(PDDocument doc) {
    List<Float> allX = extractAllXCoordinates(doc);

    // 把 x 范围切成垂直条带，统计每条带文字密度
    int[] stripCount = new int[30];
    for (float x : allX) {
        stripCount[(int)(x / stripWidth)]++;
    }

    // 找连续的文字条带（= 栏）
    List<Range> columns = findContinuousTextStrips(stripCount);
    return columns.size() >= 2;
}
```

### 4.4 双信号校验（防误判的关键）

**误判场景**：单栏简历右上角有联系方式框/二维码/表格框，朴素算法会误判成多栏。

**校验原理**：真·双栏的右栏**纵向贯穿整页**，而局部装饰元素（联系方式/二维码）只在页面局部。

#### 信号 1：纵向覆盖率

```
右侧文本块的 y 跨度 / 页面高度
  ≥ 60%  → 真·双栏
  < 60%  → 局部装饰（误判）
```

#### 信号 2：右侧文本块数

```
右侧独立的文本块数量
  ≥ 10   → 真·双栏（一整栏内容）
  < 10   → 局部装饰（几行字/一个二维码）
```

#### 最终判定（双信号同时满足）

```
疑似多栏 && 纵向覆盖率 ≥ 60% && 右侧文本块数 ≥ 10
  → 真·双栏 → 多模态
否则
  → 误判 → Tika
```

### 4.5 各场景判定结果

| 场景 | 纵向覆盖率 | 文本块数 | 判定 |
|------|----------|---------|------|
| 真·双栏 Canva | ~95% | ~30 | ✅ 多模态 |
| 左窄右宽双栏（左 sidebar） | ~80% | ~20 | ✅ 多模态（本就是双栏，判定正确） |
| 右上角联系方式框 | ~15% | 4 | ✅ Tika（不误判） |
| 右下角二维码 | ~10% | 1 | ✅ Tika（不误判） |
| 页眉 logo 文字 | ~5% | 2 | ✅ Tika（不误判） |

---

## 5. 多模态降级链路

### 5.1 触发条件

多栏检测（§4）判定为"真·多栏"时触发。

### 5.2 流程

```
多栏 PDF
  │
  ├─ PDFBox 渲染每页为图片（300 DPI）
  │
  ├─ 每张图调用 Qwen-VL-OCR 抽取文本
  │     ├─ 输入：图片 base64
  │     ├─ 折算：~256 token/张
  │     └─ 成本：256 × 0.006/1000 ≈ 0.0015 元/张
  │
  ├─ 多页则拼接结果
  │
  └─ 输出：干净的结构化文本 → §6 结构化 + 点评
```

### 5.3 关键参数

| 参数 | 值 | 说明 |
|------|-----|------|
| DPI | 300 | 清晰度甜点（72 太糊，600 太大） |
| 单页折算 token | ~256 | 阿里云规则：缩放至 448×448 |
| 单页 OCR 成本 | ~0.0015 元 | 256 × 0.006/1000 |
| 简历页数 | 1-2 页（通常） | 1-2 次模型调用 |

### 5.4 资源约束（重要）

- **内存**：300 DPI 的 A4 图片 ≈ 30MB BufferedImage
- **服务器**：2 核 3.4G（阿里云 Ubuntu）
- **并发限制**：配额 3 次/天天然限制并发；登录用户上传走 Redis Stream 异步，队列控制并发数
- **建议**：PDFRenderer 渲染限流，同时渲染图片任务数 ≤ 2

---

## 6. 结构化字段提取 + 点评

### 6.1 两步 LLM 调用

源项目只做"整段文本 → 点评 JSON"，不拆字段。本项目多做一步"结构化字段提取"，供面试出题复用。

```
干净文本（Tika 或多模态输出）
  │
  ├─ 第一步：结构化字段提取（LLM 调用 1）
  │     输入：简历文本
  │     输出：JSON { name, phone, email, education[], work[], projects[], skills[], selfEvaluation }
  │
  ├─ 第二步：点评（LLM 调用 2）
  │     输入：简历文本 + 结构化字段（可选）
  │     输出：评分 + 建议 + rewrite 范例
  │
  └─ 落库
```

### 6.2 结构化字段定义

```java
public record ResumeStructured(
    String name,                  // 姓名
    String phone,                 // 电话
    String email,                 // 邮箱
    List<EducationItem> education,      // 教育经历
    List<WorkItem> work,               // 工作/实习经历
    List<ProjectItem> projects,         // 项目经历
    List<String> skills,               // 技能
    String selfEvaluation             // 自我评价
) {}

public record EducationItem(String school, String major, String degree, String period) {}
public record WorkItem(String company, String position, String period, String description) {}
public record ProjectItem(String name, String role, String period, String description, String techStack) {}
```

### 6.3 点评输出（含 layoutIssue 兜底）

```java
public record ResumeAnalysis(
    Integer overallScore,                  // 总分 0-100
    ScoreDetail scoreDetail,                // 五维评分
    String summary,                         // 一句话总结
    List<String> strengths,                 // 优势点
    List<Suggestion> suggestions,           // 建议
    List<RewriteExample> rewrites,          // 重写范例（2-3 条）
    Boolean layoutIssue                     // 排版混乱兜底标记
) {}
```

`layoutIssue` 字段的作用：规则层漏判的多栏，LLM 读着别扭时输出 `true`，前端提示用户。

### 6.4 点评 Prompt 增强（在源项目基础上）

源项目 Prompt（`resume-analysis-system.st`）已包含：
- 五维评分（projectTechDepth / skillMatch / content / structure / expression）
- 2-3 条 rewrite 范例
- 技术名词纠错

**本项目新增**：
- 输出字段加 `layoutIssue`
- rewrite 范例每条输出 ~100 字，加强实操性

### 6.5 token 成本（含 rewrite 加强）

```
点评输入：简历文本 ~2000 + Prompt ~1500 = 3500 token
点评输出：~1500（源项目） + 400（rewrite 加强）= 1900 token
```

rewrite 加强后输出 token 涨约 27%，单份多花 < 1 厘，可忽略。

---

## 7. 半遮面集成（对接 03-auth §5.4）

### 7.1 数据流

```
游客上传简历
  │
  ├─ §3 解析路由 → 干净文本
  │
  ├─ §6 结构化字段 + 点评 → 完整报告
  │
  ├─ 生成 reportId（UUID）
  │
  ├─ 完整报告存 Redis
  │     key: guest:resume:report:{reportId}
  │     value: ResumeAnalysisFull 对象
  │     TTL: 12 小时
  │
  ├─ 数据库 resume_analysis 表只存摘要
  │     ├─ 评分
  │     ├─ 前 3 条建议
  │     ├─ report_id（外键指向 Redis）
  │     └─ expire_at = 创建时间 + 12h
  │
  ├─ 接口只返评分 + 前 3 条建议（半遮面）
  │
  └─ 前端展示 + "注册查看完整报告" CTA

游客注册（带 reportId）
  │
  ├─ 03-auth §6.2 的注册流程
  │
  ├─ 从 Redis 取完整报告 → 回填 resume_analysis
  │     └─ expire_at 置 NULL（变永久）
  │
  ├─ 删 Redis 那份
  │
  └─ 返 JWT + 完整报告
```

### 7.2 接口契约

| 接口 | 方法 | 角色 | 返回 |
|------|------|------|------|
| `/api/resume/upload` | POST | 游客 + 登录 | reportId（游客）/ 完整报告（登录） |
| `/api/resume/{reportId}` | GET | 游客 + 登录 | 摘要（游客）/ 完整（登录） |
| `/api/resume/history` | GET | 登录 | 历史报告列表 |

游客调 `/upload` 返回 reportId + 摘要；登录用户调 `/upload` 直接返回完整报告。

### 7.3 边界情况

| 情况 | 处理 |
|------|------|
| 游客注册时 Redis 已过期（超 12h） | 注册成功，报告不回填，提示"报告已过期，请重新上传" |
| 游客注册时没带 reportId | 正常注册，不回填 |
| 游客上传但未注册，12h 后 | Redis 过期，数据库摘要仍保留 12h 后被定时任务清 |
| 多栏误判走 Tika 翻车 | 点评 `layoutIssue=true`，前端提示重传纯文本版 |

---

## 8. 报告导出（iText 8）

### 8.1 触发

- **仅登录用户**可导出（游客锁登录，转化诱饵）
- 登录用户每日导出次数限制（建议 10 次/天，防止滥用 CPU）

### 8.2 技术方案

```
完整报告（评分 + 建议 + rewrite）
  │
  ├─ iText 8 模板渲染（本地，零 token 成本）
  │     ├─ 评分大数字
  │     ├─ 五维雷达图（iText Drawing）
  │     ├─ 建议列表
  │     ┉─ rewrite 范例
  │
  └─ 输出 PDF → MinIO 存储 → 返回下载链接
```

### 8.3 资源约束

- iText 渲染吃 CPU + 内存
- 2 核 3.4G 服务器，并发导出 5-10 份可能卡顿
- **限流**：登录用户 10 次/天，同时导出任务 ≤ 2

---

## 9. 数据库设计

### 9.1 resume_analysis 表

```sql
CREATE TABLE resume_analysis (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    device_id       VARCHAR(64),                -- 游客兜底找回

    -- 摘要（游客可见）
    overall_score   INTEGER,
    top_suggestions JSONB,                       -- 前 3 条建议

    -- 完整内容（注册后回填）
    full_report     JSONB,                       -- 完整点评 JSON
    structured      JSONB,                       -- 结构化字段 JSON

    -- 元数据
    file_name       VARCHAR(255),
    file_url        VARCHAR(500),               -- MinIO 存储 key
    report_id       VARCHAR(64),               -- Redis 半遮面外键
    parse_method    VARCHAR(20),               -- TIKA / MULTIMODAL
    layout_issue    BOOLEAN DEFAULT FALSE,

    -- 过期
    expire_at       TIMESTAMP,                  -- 游客 12h，登录 NULL
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_resume_user ON resume_analysis(user_id);
CREATE INDEX idx_resume_report ON resume_analysis(report_id);
CREATE INDEX idx_resume_expire ON resume_analysis(expire_at) WHERE expire_at IS NOT NULL;
```

### 9.2 字段说明

| 字段 | 游客状态 | 注册后 |
|------|---------|--------|
| overall_score | ✅ 有 | ✅ 有 |
| top_suggestions | ✅ 前 3 条 | ✅ 全部 |
| full_report | NULL | 完整报告 |
| structured | NULL | 结构化字段 |
| expire_at | 12h 后 | NULL（永久） |

### 9.3 与 03-auth 的字段对齐

- `user_id`：指向 Guest 或 Registered 的 user 记录（id 不变）
- `device_id`：游客兜底找回
- `expire_at`：游客 12h，注册后 NULL（被 `clearExpireAtByUserId` 清掉）
- `report_id`：Redis 半遮面的外键

---

## 10. 成本与耗时明细

### 10.1 成本（100 份简历）

口径：PDF 70%、Word 30%、多栏 PDF 占 PDF 的 15%

| 部分 | 份数 | 单份成本 | 小计 |
|------|------|---------|------|
| Word（Tika + 点评） | 30 | 0.006 元 | 0.18 元 |
| 单栏 PDF（PDFBox + Tika + 点评） | 59.5 | 0.006 元 | 0.36 元 |
| 多栏 PDF（PDFBox + 多模态 + 点评） | 10.5 | 0.007 元 | 0.074 元 |
| **合计** | **100** | — | **0.614 元** |

**平均每份 ≈ 0.006 元（不到 1 分钱）。**

### 10.2 耗时

| 路径 | 耗时 |
|------|------|
| PDFBox 判栏 | 50-100ms |
| Tika 解析 | 100-500ms |
| Qwen-VL-OCR（1 页） | 2-5 秒 |
| 点评 LLM | 5-10 秒 |

**用户体感区间：6-16 秒。** 异步处理（Redis Stream），用户不干等。

### 10.3 配额

- 游客：3 次/天（03-auth §4）
- 登录用户：建议放宽（10-20 次/天），具体见 03-auth §11 待确认项

---

## 11. 与源项目的差异

| 维度 | 源项目 | 本项目 |
|------|--------|--------|
| 格式支持 | PDF/DOCX/TXT/MD | PDF/Word（图片拦截） |
| PDF 解析 | Tika 一把梭 | 单栏 Tika / 多栏多模态 |
| 多栏检测 | 无 | PDFBox 坐标检测 + 双信号 |
| 多模态 | 无 | Qwen-VL-OCR（仅多栏 PDF） |
| 结构化字段 | 无（整段文本） | LLM 拆字段 |
| layoutIssue 兜底 | 无 | Prompt 加字段 |
| 半遮面 | 无 | 完整报告存 Redis |
| 报告导出 | 有（iText） | 有（iText，登录可用） |

---

## 12. 开发任务拆解

### 第 1 期：解析闭环

- [ ] **T1** `DocumentParseService` 改造：加 Word/PDF 分流入口
- [ ] **T2** `ColumnDetector`：PDFBox 读坐标 + 双信号校验
- [ ] **T3** `MultimodalOcrService`：PDF 转图 + Qwen-VL-OCR 调用
- [ ] **T4** `ResumeStructuredService`：LLM 拆字段（新增 Prompt）
- [ ] **T5** 点评 Prompt 增强：加 layoutIssue 字段
- [ ] **T6** `resume_analysis` 表 + Entity + Repository

### 第 2 期：半遮面 + 导出

- [ ] **T7** 半遮面：完整报告存 Redis，接口返摘要
- [ ] **T8** 前端简历报告页：评分 + 3 条 + "注册看完整" CTA
- [ ] **T9** iText 导出：模板渲染 + MinIO 上传
- [ ] **T10** 导出限流（登录用户 10 次/天）

**验收标准**：
- 单栏 PDF 走 Tika，多栏 PDF 走多模态，判定准确率 95%+
- 100 份简历成本 < 1 元
- 游客半遮面 3 条 → 注册后完整报告无缝接住
- 登录用户可导出 PDF

---

## 附录 A：包结构建议

```
com.interview.guide.modules.resume
├── controller/
│   └── ResumeController.java
├── service/
│   ├── ResumeService.java              # 编排：上传→解析→点评→落库
│   ├── ResumeParseService.java         # 解析路由入口
│   ├── ResumeStructuredService.java    # LLM 拆字段
│   └── ResumeAnalysisService.java      # LLM 点评
├── dto/
│   ├── ResumeStructured.java
│   ├── ResumeAnalysis.java
│   └── ...
└── entity/
    ├── ResumeAnalysisEntity.java
    └── ResumeRepository.java

com.interview.guide.infrastructure.file
├── DocumentParseService.java           # Tika 入口（保留源项目）
├── ColumnDetector.java                 # 多栏检测（新增）
├── MultimodalOcrService.java           # 多模态 OCR（新增）
├── ContentTypeDetectionService.java
├── FileStorageService.java
└── TextCleaningService.java
```
