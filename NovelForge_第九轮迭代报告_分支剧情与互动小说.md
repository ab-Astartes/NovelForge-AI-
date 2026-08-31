# NovelForge（墨阁）第九轮迭代报告 —— 分支剧情 · 互动小说

> 日期：2026-08-28 ｜ 模块：分支剧情 · 互动小说
> 承接第六轮竞品对标路线图候选项：分支剧情 / 互动小说。

---

## 一、为什么做

互动叙事（分支剧情、多结局、选择支）是网文与轻小说的一条高潜力赛道：对话小说、游戏化叙事、可玩梗的「你的选择决定结局」形态，能显著拉高读者沉浸感与复读率。但这类能力的传统实现要么重度依赖 LLM 实时生成（成本高、不可控），要么需要专门的引擎与后端。

墨阁这一轮的做法沿用一贯策略：**零 LLM 成本**，把已写好的章节组织成可视化的**剧情树**，作者手动编排选择支与结局，系统负责可达性校验、结构告警与一键导出**零依赖互动阅读器**（纯本地 HTML，浏览器直接打开，无需后端）。与关系图谱、疆域地图、智能取名、设定集、资源账本、情绪张力曲线同一技术路线。

它同时解决两个痛点：
1. **结构可视**：长篇多线叙事容易写出「死胡同」「永远到不了结局」「孤立章节」，肉眼难查；BFS 可达性分析一眼定位。
2. **可分发**：导出的互动阅读器是单文件 HTML，作者可直接发给读者/发到社区试读，零部署。

---

## 二、数据模型

持久化文件：`truth/branching.json`，结构如下：

```json
{
  "nodes": [
    { "id": "n1", "title": "起点", "type": "start",    "chapterRef": 1, "excerpt": "故事开始了" },
    { "id": "n2", "title": "抉择", "type": "scene",    "chapterRef": 2, "excerpt": "主角面临选择" },
    { "id": "n3", "title": "结局", "type": "ending",   "chapterRef": 3, "excerpt": "尘埃落定" }
  ],
  "edges": [
    { "from": "n1", "to": "n2", "choice": "东行" },
    { "from": "n2", "to": "n3", "choice": "决战" }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `nodes[].id / title / type` | 节点唯一 id、标题、类型：`start`(起点) / `scene`(场景) / `ending`(结局) |
| `nodes[].chapterRef` | 关联章节号（用于回跳正文、在互动阅读器中标注「第 N 章」） |
| `nodes[].excerpt` | 该节点的正文摘要 / 剧情要点 |
| `edges[].from / to / choice` | 有向选择支：从 A 到 B，选择文案（如「东行」「决战」） |

**骨架自动生成**：书暂无 `branching.json` 时，按章节目录（`chapters/*.md`）自动建骨架——每章一个节点，首章记为 `start`，标题含「结局」标记的章记为 `ending`，其余为 `scene`。作者可在骨架上直接增删节点与选择支。

---

## 三、剧情树统计与六类结构告警

后端以**起点为根做 BFS 分层**：从 `start` 出发逐层扩散算 `depth`，未访问到的节点标记为「不可达」；统计起点数、结局数、场景数、可达数、纵深（最大层深）。据此产出六类告警：

| 类型 | 级别 | 触发条件 | 提示 |
|---|---|---|---|
| **死胡同 deadend** | error | 非结局节点无出边 | 读者无路可走，需补选择支或改为结局 |
| **孤立 isolated** | warn | 既无入边也无出边 | 建议接入主线或删除 |
| **不可达 unreachable** | warn | 从起点 BFS 无法到达 | 当前剧情树到不了该节点，需补入边 |
| **缺少结局 noending** | error | 无任何 `ending` 节点 | 读者永远无法通关 |
| **多起点 multistart** | info | 起点 > 1 | 互动小说通常只有一个入口 |
| **环路 cycle** | info | 分支回到已走过节点 | 循环结构通常合理，但需确认非笔误 |

前后端**双实现同一套校验逻辑**（后端给保存前兜底，前端给编辑实时反馈），保证「编辑即见告警」。

---

## 四、后端实现要点（BranchingBuilder.java）

`BranchingBuilder.build(mapper, bookDir)` 是核心入口，返回 `{ok, book, nodes[], edges[], scaffolded, stats, warnings[]}`；`StudioServer` 在 `/api/branching` 注册 GET（读/骨架）+ POST（保存）两个 handler，并放行 `/branching.js` 静态资源。

关键工程细节（踩坑与修复）：

1. **编译错误①`ObjectMapper` 未导入**：`BranchingBuilder` 用到了 `ObjectMapper` 但漏写 `import`，编译期报「找不到符号」。已补 `import com.fasterxml.jackson.databind.ObjectMapper;`（对齐 `TensionBuilder` / `StudioServer`）。
2. **编译错误②`int` 无法转 `String`**：`addWarn(..., String node, ...)` 的 `node` 形参是 `String`，而「无节点」「缺结局」「多起点」「环路」四类告警我误传了 `0`（int）。已统一改为 `""`（空串）。
3. **避免 `Set.of` / `Map.of` 坑**：大词表/多节点场景下 `Set.of` 重复元素不会编译报错但类加载时抛 `IllegalArgumentException`；`Map.of` 上限 10 对。本模块统一用 `new HashSet<>(...)` 与 `LinkedHashMap` 持久化字段，规避历史雷区。
4. **骨架识别规则**：扫描章节标题，含「结局」二字（无论前后）即判定为 `ending`，首章固定 `start`，其余 `scene`——冒烟验证「终章 【结局】」被正确识别为结局节点。
5. **POST 防御性校验**：保存前逐条校验边不能悬空（引用不存在的节点）、不能自环（`from == to`），否则返回 400 并指明 offending 边；通过后用 `writerWithDefaultPrettyPrinter()` 写出规整 JSON，复用 `isPathWithinBooksRoot` 防路径穿越。

---

## 五、前端（branching.js）

侧栏「🌿 分支」面板，全部复用 `app.js` 既有能力（`authUrl` / `authHeaders` / `escapeHtml` / `showToast` / `downloadFile`），零新增全局依赖：

- **SVG 分层剧情树**：以 BFS 层深排布节点，起点绿 / 场景蓝 / 结局金三色区分，`type` 文字标注；边用贝塞尔曲线 + 箭头，线上标注选择支文案；不可达节点置灰 + 虚线描边。
- **节点编辑**：点击树中节点或左侧节点列表进入编辑态，可改标题 / 类型 / 章节引用 / 摘要，增删选择支（出边），删除节点。
- **实时结构校验**：客户端 `computeBranchWarnings()` 与后端同逻辑，编辑时同步显隐告警条（ok / error / warn / info 四色）。
- **一键导出互动阅读器**：`buildInteractiveHtml()` 生成**自包含 HTML**（内联 `<script>` + `DATA` 全局对象，DFS 渲染当前节点、选择支按钮、前进/返回/重开），`downloadFile` 触发下载。读者双击即可在浏览器中游玩，全程离线、零后端。

---

## 六、实现要点

1. **面板命名**：新增 `nav-branching` / `panel-branching`，预先核查无 id 冲突（沿用 P8 的命名约定）。
2. **导航接线**：`app.js` 的 `PANEL_MAP` 加 `'branching':'branching'`；`showPanel` 加 branching 分支（进面板即 `populateBookSelects()` + `loadBranching()`）；`selects` 数组加 `'branching-book'` 使其随书目下拉刷新。
3. **静态资源**：`StudioServer` 在 `/tension.js` 之后放行 `/branching.js`。
4. **样式**：`style.css` 补 `.branching-*` 系列（剧情树画布 / 节点三色 / 选择支行 / 编辑器表单 / 节点列表），对齐 tension 的暗色墨纸风。
5. **审计纳管**：`.audit/checkids.js` 的 js 数组加入 `'branching.js'`，保证面板↔导航 0 缺口。

---

## 七、验证

- **前端审计**：HTML ids 289 / JS 引用 257，纳入 branching 后 **[A] 引用但不存在的 id = 0、[B] 绑定未定义函数 = 0、[C] 重复函数 1（仅历史既有 `escapeHtml` 双定义，与本轮无关）、[D] 用到无 CSS 的 class = 0、[E] 面板↔导航 16↔16 全匹配**。
- **Maven `-o compile`**：修复上述两处编译错误后 `BUILD SUCCESS`（studio 模块含 `BranchingBuilder` + `StudioServer`）。
- **Maven `-o test -am`**：`StudioServerTest` **8/8 通过**（0 failures / 0 errors）。
- **Studio 冒烟（:8964，--no-auth）**：
  - `GET ?scaffold=1`：真实测试书生成 3 节点（start / scene / ending，正确识别「终章 【结局】」），stats `reachable:1, unreachable:2, depth:0`，并触发 deadend / isolated / unreachable 告警 ✓
  - `POST` 保存 3 节点 / 2 边 → 写入 `truth/branching.json`（pretty-print）✓
  - `GET`（无 scaffold）回读持久化结构，`scaffolded:false`，stats `reachable:3, unreachable:0, depth:2, warnings:0`（结构健康）✓
  - 测试书目已清理，未残留于 `~/NovelForge/books`。

---

## 八、文件改动清单

- 新增：`BranchingBuilder.java`（后端剧情树构建 / 统计 / 校验）、`branching.js`（前端 SVG 树 / 节点编辑 / 实时校验 / 零依赖导出）
- 改动：`StudioServer.java`（`/api/branching` 路由 + handler + `/branching.js` 静态放行）、`index.html`（导航 + 面板 + script 引入）、`app.js`（PANEL_MAP、书下拉、showPanel 分支）、`style.css`（`.branching-*` 样式）、`.audit/checkids.js`（纳管 branching.js）、`README.md`（补「分支剧情 · 互动小说」一节）
- 文档：本报告 + README 功能段

---

## 九、下一步候选

- 路线图余项：守护进程后台日更（inkos daemon）、多语种翻译工作台
- 分支剧情增强：
  - **与章节正文联动**：导出互动阅读器时把 `chapterRef` 对应的正文片段内联进节点，读者点节点即可读该章原文（而非仅摘要）
  - **条件分支**：选择支支持门槛（如某属性/Flag 达标才出现），互动阅读器内置轻量状态机
  - **剧情树统计增强**：最短路到结局、最长链、分支宽度分布，辅助判断「是不是一上来就分叉太多 / 太久不分叉」
  - **与大纲联动**：读取 `outline.md` 的预期关键抉择点，校验剧情树是否覆盖了主线分支
  - **可视化布局优化**：节点超多时支持缩放/折叠子树、按卷着色
