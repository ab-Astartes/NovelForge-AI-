# 墨阁 NovelForge · 优化清单 + 待完善功能 + GitHub 竞品对比报告

> 生成时间：2026-08-27 ｜ 版本基线：v0.6.0 ｜ 范围：novelforge-studio 前端（index.html / app.js / style.css）+ 后端 API 能力盘点
> 审计工具：`.audit/checkids.js` · `checkcss.js`（已升级为作用域感知）· `checkhtml.js`

---

## 一、本轮已完成（本会话）

### 1.1 前端美化 / 主题修复
- **修正 3 个历史残留 CSS 变量**：`style.css` 中 `write-stats .stat-card` 误用未声明的 `var(--card)` / `var(--border)`，已统一改为墨韵主题变量 `var(--ink-card)` / `var(--ink-border)`。
- **新增 `--accent` 变量**：`app.js:2749` 内联 `var(--accent)` 此前无定义（"配置参考样例"文字无着色），在 `:root` 补 ` --accent: #c0392b`（cinnabar 别名）。
- **补齐 13 个新增组件样式**（此前 DOM 已建但 CSS 缺失，导致整块不可见/错位）：
  `header-actions` · `tool-card*` · `ledger-tabs/tab/pane` · `card-badge` · `ledger-body` · `hook-filter-bar` · `chip/chip-warn` · `usage-stats-grid` · `stat-card-lg` · `stat-card-cost` · `card-hint` · `table-wrap`。
- **改进审计工具 `checkcss.js`**：原朴素字符串匹配把"全局定义 + `@media` 覆盖"误判为重复选择器。改为**作用域感知**——只有同一作用域内（global 或同一 media）多次定义才报真重复，媒体查询响应式覆盖不再误报。

### 1.2 页面内化（CLI 能力搬进 Web UI）
- **新增「进度」面板（panel-progress）**：此前 `loadProgress()` / `showDiff()` 是**孤儿函数**（后端 `/api/progress` 早已就绪却无前端入口）。本次补齐：
  - 侧边栏新增 `nav-progress`（📈 进度），位于「落笔」之后。
  - 面板含 `progress-book` 书目选择器（已纳入 `BOOK_SELECT_IDS` 与 `populateBookSelects`，自动联动全局书目）、4 张统计卡（章节数 / 总字数 / 平均审计分 / 流水线耗时）、逐章明细表（字数·审核·评分·耗时·一键 Diff）。
  - `loadProgress()` 增加"有书显 summary、无书显 empty"的显隐切换；`showPanel('progress')` 自动 `populateBookSelects()` + `loadProgress()`；新增 `onProgressBookChange()` 同步全局书目。

### 1.3 审计结果（修复后全绿）
| 审计项 | 修复前 | 修复后 |
|---|---|---|
| 前端引用但不存在的 DOM id | 1（`progress-book`） | **0** |
| HTML 绑定但 JS 未定义的函数 | 0 | 0 |
| 重复定义的函数 | 0 | 0 |
| HTML 用到但 CSS 无定义的 class | 1（`card-hint`） | **0** |
| 面板 vs 导航一致性 | 缺 `usage` section | **9↔9 完全对齐** |
| 未声明 CSS 变量 | 3 | **0** |
| 真重复选择器（同作用域） | 5（含 4 个媒体覆盖误报） | **0** |
| HTML 重复属性 / 标签闭合 | 0 / 0 | 0 / 0 |

---

## 二、历史会话已完成（回顾）

- **导航切换崩溃**：`showPanel` 引用从未声明的 `panelId` → 引入 `currentPanel` + `PANEL_MAP` 重构，按面板名触发对应数据加载。
- **续写 / AI 划选改写 / 沉浸模式全面失效**：JS 大量引用不存在的 `#chapter-editor` / `.chapter-editor-textarea`，实际 DOM 是 `#chapter-edit-textarea` → 全局选择器统一。
- **台账数据不显示**：前端读 `data.characters` 但 `/api/characters` 返回裸数组 → `unwrapList` 兼容两种契约；重写 `loadCharacterSheet/loadHookTracker/loadWorldBuilding` + 新增 `loadTimelineLedger` 等。
- **预设切换静默失效**：`cfg-global-baseUrl` 大小写与 DOM 不符 → 统一小写。
- **快捷键系统重写**：`Ctrl+1~7` 切面板、`Ctrl+Enter` 落笔、`Ctrl+K` 搜索、`F11` 沉浸、`Esc` 关闭、`?` 帮助面板（此前 `openShortcutHelp` 未定义）。
- **新增「台账」「用量」两个面板**，CLI 的 TruthState 查看与 Token 账单能力完全可视化。

---

## 三、待完善功能（对标竞品后的差距清单）

### 3.1 能力缺口（竞品有、NovelForge 暂无）
| 优先级 | 能力 | 说明 | 竞品参照 |
|---|---|---|---|
| 🔴 P0 | **向量检索长程记忆（RAG）** | 当前仅 JSON TruthState 关键词匹配，百万字下语义一致性弱 | webnovel-writer / infinitenovel(ChromaDB) / AI-Novelist-RAG(FAISS) / NovelFlow-AI |
| 🔴 P0 | **完本导出（epub / pdf / docx）** | 仅有 txt/md，无法满足发布与排版 | NovelFlow-AI（`export --docx/epub/pdf`） |
| 🟠 P1 | **封面生成** | 调用图像模型生成封面占位与成品 | NovelFlow-AI / oh-story-claudecode |
| 🟠 P1 | **市场雷达 / 扫榜拆文** | 辅助选题与爽点对标，当前完全缺失 | oh-story-claudecode（扫榜/拆文） |
| 🟠 P1 | **守护进程（daemon）+ Webhook 通知** | 后台长任务推送，当前为前端轮询 | InkOS |
| 🟡 P2 | **关系图谱可视化** | 人物/势力/地点关系图，竞品少见，是可差异化亮点 | （空白，建议领先） |
| 🟡 P2 | **多用户 / 权限（PG）** | 当前单用户，无法协作 | MuMuAINovel |
| 🟡 P2 | **TUI 终端界面** | 部分用户偏好纯终端 | 多个竞品提供 |

### 3.2 CLI 已有但 Studio 尚未内化
| 能力 | 状态 | 建议 |
|---|---|---|
| `interact` 交互模式 | 未内化 | 落笔面板增加"对话式共创"子区 |
| `style clone` 风格克隆 | 仅 toolbox 部分 | 补全上传样本→提取特征→应用到生成的全流程 UI |
| `delete` 删除书籍 | 无前端入口（`delete-book` 选择器已预留但未接面板） | 书阁面板增加删除/归档按钮 + 二次确认 |
| `write resume` / `write draft` 模式切换 | 前端已有 batch/next/draft 模式 | 确认与 CLI 模式语义对齐 |

### 3.3 工程化待补
- **前端无构建/类型保护**：`app.js` 152KB 纯过程式 JS，缺乏模块化与单元测试（前端 0 测试，对比后端 270 单测）。建议引入轻量测试或至少 ESLint。
- **内联 style 偏多**：审计显示 36 处行内 style（含 5 处 `display:none`），建议逐步下沉到 CSS，降低维护成本。
- **命名撞车风险**：GitHub 已有 `RhythmicWave/NovelForge`（Python，1.1k⭐）同名项目，开源发布前需明确品牌区分（建议强调「墨阁 / 九重炼章」中文品牌）。

---

## 四、GitHub 同类高热度产品对比（2026-08 实测）

| 项目 | Star | 语言/形态 | 核心能力 | 可借鉴点 | NovelForge 现状 |
|---|---|---|---|---|---|
| **inkos**（Narcooo） | **~9.1k** | TS / Agent | 小说·剧本·翻译·互动游戏·IP 全产业链 | 多形态内容生产、IP 化工作流 | 你本地 `inkos v1.3.6` 即此系；可作灵感参考 |
| **oh-story-claudecode** | ~5.7k | JS / Skill 包 | 扫榜·拆文·写作·去AI味·封面图 | 市场雷达 + 去 AI 味流水线 | 缺扫榜/拆文；反 AIGC 检测可对标去 AI 味 |
| **AI-Novel-Writing-Assistant** | ~2.6k | TS / 引擎 | Agent+世界观+写法引擎+RAG+整本工作流 | 写法引擎、整本生产编排 | Agent 数（9）领先，但缺 RAG |
| **webnovel-writer** | 高热 | Claude Code | 200万字连载、防幻觉、5维质量审查、**RAG(向量库)** | **长程记忆标杆** | 有 33 维审计（更强），但无向量检索 |
| **AI-Novelist-RAG** | 参考实现 | Py / Langchain+FAISS | 长 context 记忆、RAG 上下文增强 | RAG 架构参考 | 可借鉴其向量化摘要链路 |
| **NovelFlow-AI** | 高热 | CLI(`novelflow`) | 策划→人物→世界观→大纲→多Agent→**长期记忆**→**伏笔**→**封面**→审稿→**导出 docx/epub/pdf** | **一条龙 + 完本导出 + 伏笔管理** | TruthState 类似其 canon/foreshadow；缺封面/导出 |
| **infinitenovel** | 中 | — / ChromaDB | 向量记忆、世界演化、智能节奏控制 | 节奏控制、世界演化系统 | 可补"节奏/爽点密度"调控 |
| **novel-studio** | 新兴 | Go / local-first | 多智能体世界推演、RAG 长程记忆、逐章审核、断点恢复 | 断点恢复、本地优先 | 已有增量备份回滚，可强化断点续写 |
| **NovelClaw** | 新兴 | Py / FastAPI | Dynamic-memory-first、RAG | 记忆优先架构 | — |
| **RhythmicWave/NovelForge** | 1.1k | Py / 卡片式 | JSON Schema 结构化生成 | （同名不同物，注意区分） | 本项目为 Java 九重炼章引擎，非同一产物 |

### NovelForge 的差异化优势（应继续放大）
1. **9-Agent 流水线**（Architect→…→Reviser），主流竞品多为 3–6 Agent。
2. **33 维审计 + 反 AIGC 检测**，强于 webnovel-writer 的 5 维质量审查。
3. **TruthState 结构化状态 + 增量备份回滚**，与 NovelFlow 的 canon/foreshadow 思路一致但工程化更完整。
4. **真正的 Web Studio（非纯 CLI/Skill）**，可视化与交互体验是护城河。
5. **SSE 流式写作 + 多模型 LLM 路由**，基础设施扎实。

---

## 五、优先级路线图建议

1. **P0（本月）**：① 引入向量检索长程记忆（ChromaDB/FAISS + 摘要嵌入），补百万字语义一致性短板；② 完本导出 epub/pdf/docx。
2. **P1（下月）**：③ 封面生成；④ 市场雷达/扫榜拆文；⑤ daemon + Webhook 长任务通知；⑥ 内化 `interact` / `style clone` / `delete` 三个 CLI 能力。
3. **P2（差异化）**：⑦ 关系图谱可视化（领先点）；⑧ 多用户/PG；⑨ TUI；⑩ 前端模块化 + 单测，内联 style 下沉。
4. **品牌**：开源前明确「墨阁 NovelForge / 九重炼章」中文品牌，规避与 `RhythmicWave/NovelForge` 同名混淆。

---
*附：所有前端改动均为静态资源（HTML/CSS/JS），Java 无需重编译；`.audit/` 三脚本可随时复跑校验。*
