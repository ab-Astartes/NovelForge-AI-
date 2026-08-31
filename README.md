# NovelForge 🔥 — AI 小说锻造工坊

> 基于 Java 的多 Agent 流水线小说创作引擎，本地优先，网文专精。

## 架构

NovelForge 采用 **monorepo + 多 Agent Pipeline** 架构：

```
NovelForge/
├── packages/
│   ├── novelforge-core/     # 核心引擎
│   ├── novelforge-cli/      # 命令行界面
│   └── novelforge-studio/   # Web UI 工作台
└── pom.xml                  # 父 POM
```

### 9-Agent 写作流水线

| Agent | 職责 | 温度 |
|-------|------|------|
| Architect | 理解意图，构建大纲 | 0.5 |
| Planner | 章节规划，hook agenda | 0.4 |
| Composer | 上下文组装，规则栈编译 | 0.3 |
| Writer | 创意写作 | 0.7 |
| Observer | 事实提取（9 类） | 0.5 |
| Reflector | 增量更新（hookOps、statePatch） | 0.3 |
| Normalizer | 长度治理 | 0.3 |
| Auditor | 33 维质量检查 | 0.2 |
| Reviser | 修复（polish/spot-fix/rewrite/anti-detect） | 0.4 |

### Truth State 系统

结构化状态文件（`characters.json`、`world.json`、`timeline.json`、`hooks.json`），每个 Agent 只读+增量写入，保证叙事一致性。支持增量备份与回滚（保留最近 10 版本）。

### LLM 路由

支持 OpenAI、Anthropic、Ollama（本地离线）、自定义 OpenAI-compatible endpoint。每个 Agent 可配置不同模型/provider。指数退避重试，SSE 畸形行容错。

Ollama 离线链路：Studio 配置面板内置 `ollama` 预设（`http://localhost:11434/v1`），「检测本地 Ollama 模型」按钮一键拉取已装模型列表（`GET /api/ollama/models`），断网/隐私场景可全程本地生成。

## 使用

### CLI

```bash
# 创建项目
novelforge book create --title "龙血战神" --genre xuanhuan

# 写下一章（完整流水线）
novelforge write next --book ./my-book

# 写草稿（仅 Architect→Writer）
novelforge write draft --book ./my-book

# 批量写作（连续写多章）
novelforge write batch 5 --book ./my-book

# 从中断恢复（流水线失败后自动保存 checkpoint）
novelforge write resume --book ./my-book

# 审计
novelforge audit --book ./my-book --chapter 5

# 导出 EPUB
novelforge export --book ./my-book --format epub

# 导出 EPUB（带封面）
novelforge export --book ./my-book --format epub --cover ./cover.png

# 风格导入
novelforge style clone --reference ./sample.txt

# 交互模式
novelforge interact --book ./my-book
```

### Studio (Web UI)

```bash
novelforge studio          # 启动 Web UI (localhost:8964)
novelforge studio 3000     # 自定义端口
```

Studio 功能：
- **写作面板** — 单章写作、批量写作、续笔恢复
- **SSE 实时进度** — Agent 执行进度实时推送，失败自动降级轮询
- **审计评分** — 33 维规则引擎 + LLM 双轨评分（60/40 权重）
- **风格面板** — 导入参考文本写作风格
- **状态管理** — 角色/世界观/时间线查看与回滚
- **段落级 Diff** — 对比 draft 与 final 文本差异
- **Agent Toggle** — 启用/禁用特定 Agent，热重载配置
- **关系图谱** — 从时间线与章节正文自动挖掘共现关系，力导向 SVG 可视化；同势力聚类着色、按章节演变播放、节点过滤（类型/提及数/搜索）
- **实力分布地图** — Voronoi 色块疆域图：势力范围、下辖归属、特点与风格标签、敌对/同盟关系线，从角色档案与正文自动挖掘（零 LLM 成本）；支持拖拽调整势力中心、一键导出高清 PNG
- **智能取名** — 人物/武功/道具/兵器/势力/坐骑 六类取名，11 种风格词库，单击收藏、批量落库角色表（纯本地，零 AI 依赖）
- **设定集 / 术语表** — 从正文自动抽取六类设定实体 + 词频/首现章节/释义，校验「高频未登记 / 疑似异写 / 称谓粘连 / 低频孤词」四类设定漏洞，可导出设定集 Markdown
- **资源与战力账本** — 按章抽取灵石/丹药/贡献点等 22 种资源收支并结算余额，追踪人物境界进阶，告警「凭空支出 / 战力膨胀 / 境界倒退」，支持手工补账与 CSV 导出
- **情绪张力曲线与节拍器** — 按章计算张力分（句长节奏 / 对话占比 / 动作冲突词 / 转折词 / 情绪极性），叠加「起承转合」节拍目标带，告警「流水账 / 高潮疲劳 / 节奏断崖 / 偏离节拍」

### 关系图谱（P3 增强）

数据源为 `truth/characters.json`、`truth/world.json`、`truth/timeline.json` 与 `chapters/*.md`（零 LLM 成本，纯共现挖掘 + 关键词启发式）。

- **关系推断**：事件/章节内人物共现建边，按「敌对 > 师徒 > 亲情 > 爱慕 > 友盟」优先级自动标注关系类型
- **势力归属**：显式 `faction` 字段 > world.json 势力名命中 > 描述正则启发式（`X家/X门/X宗…`），并做后缀归并（「青阳镇萧家」→「萧家」）
- **势力聚类着色**：同势力角色同色系 + 力模拟中的势力质心引力 + 画布上的势力包围圈（convex hull，成员 ≥2 自动圈出），阵营一眼可辨
- **章节演变播放**：按章节序逐帧点亮首次出现的关系边，新增边橙色脉冲高亮，可视化「关系如何随剧情演变」

### 实力分布地图（P4）

数据源同图谱（`truth/characters.json` + `truth/world.json` + `chapters/*.md`，零 LLM 成本），输出「势力版图」：

- **Voronoi 色块疆域**：每势力一块凸多边形疆域（Sutherland–Hodgman 半平面裁剪，完整划分画布、互不重叠），核心势力按权重居中，羊皮纸底色 + 网格线呈现标准地图观感
- **势力档案**：点击色块查看范围（域名提取）、下辖势力（「麾下/门下/附属/隶属」正文模式 + world.json `parent` 字段）、特点标签（家族传承/丹道炼药/魔道邪修等 8 类关键词聚合）、风格定性（以武立族 · 剑修风流 · 行事隐秘等叠加）
- **势力间关系**：正文同句共现 + 敌对/友盟关系词推断，地图上虚线标注（红=敌对，绿=同盟）
- **world.json 显式增强**：`factions` 数组支持 `name/description/domain/style/parent/relation` 字段，显式定义优先于启发式推断
- **拖拽调整（P6）**：工具栏「✥ 拖拽调整」开关进入编辑模式，拖动势力中心手柄实时重算 Voronoi 疆域；「⬇ 导出 PNG」将 SVG 序列化为 2x 高清位图下载，可直接用作设定集插图
- **节点过滤**：类型 chips（人物/地点/势力/物品/体系/规则）、提及数阈值滑块、名称/描述搜索
- **核心人物榜**：按关联度 Top10 侧栏，点击聚焦；节点拖拽固定、滚轮缩放、空白拖拽平移

接口：`GET /api/graph?path=<书目录绝对路径>`，返回 `{nodes, edges, chapters, stats}`。

### 智能取名（P4-P6）

纯本地词库生成（零 AI 依赖、毫秒级响应），覆盖六类名称：**人物姓名 / 武功招式 / 道具 / 兵器 / 势力 / 坐骑**。

- **11 种风格**：仙侠飘逸、武侠刚正、玄幻霸气、古典文雅、凶煞凌厉、清丽雅致 + 恐怖惊悚、科幻未来、奇幻瑰丽、神秘莫测、诡异乖张（后五种按类目着色词池：科幻=量子/星舰/曲率，恐怖=血祭/亡灵/怨咒，奇幻=精灵/龙裔/圣辉…）
- **寓意释义**：每个名字附带逐字释义（「芷=白芷清芬；兰=空谷幽兰」）
- **关键字注入 / 姓氏 / 性别 / 数量**：可指定必含字（如关键字「墨」），人物支持姓氏锁定与男女切换
- **去重**：人物类自动排除现有角色表重名
- **单击收藏 ♡ + 批量落库（P6）**：结果卡一键收藏入夹；收藏夹可整批写入 `truth/characters.json` 角色表（自动带上性别/来源标签），或保存为 `truth/naming_favorites.json` 灵感库

接口：`GET/POST /api/naming`（生成）、`POST /api/naming/save`（收藏/落库）。

### 设定集 / 术语表（P7）

零 LLM 成本，从 `chapters/*.md` + `truth/characters.json` + `truth/world.json` 抽取六类实体：

- **人物** — 说话动词（说道/冷哼/喃喃…）前 2-4 字 + 角色表已登记者
- **功法典籍** — 《》书名号 + `诀/经/大法/剑法/心法/真解/神通…` 后缀
- **法宝兵器** — `剑/刀/塔/印/炉/鼎/珠/符/丹/葫芦…` 后缀
- **地名** — `镇/城/山/谷/郡/州/域/界/渊/殿/宫/阁…` 后缀
- **境界体系** — 修真 / 武道 / 斗气三大体系 13 阶 + `层/重/段/阶/品`
- **术语** — 「X，是/即/指的是/被称为…」定义句自动抓释义

**降噪**：60+ 泛词黑名单、虚词/代词/介词过滤、姓氏人名纠偏（「萧远山」判人物而非地名）。

**四类一致性告警**：高频未登记（≥3 次但不在设定文件）、疑似异写（同长仅差一字，如混沌诀/混元诀）、称谓粘连（「长老萧远山」含「萧远山」）、低频孤词（仅 2 次，疑似笔误）。

**导出**：设定集 Markdown（按类型分章，含释义/例句/告警）。

接口：`GET /api/glossary?path=<书目录绝对路径>`。

### 资源与战力账本（P7）

- **22 种资源 × 计量单位**：灵石（极品/上品/中品/下品）、灵晶、金币、银两、贡献点、丹药、符箓、妖丹、寿元…
- **中文数字解析**：`三百块灵石`、`一千二百`、`3.5`、`几颗丹药` 均可识别（十/百/千/万/亿 进位）
- **收支判定**：数量前 14 字窗口内命中收入动词（获得/赏赐/赚取…）记正，支出动词（花费/消耗/支付…）记负，逐章结算余额
- **境界进阶**：句中同时出现「已登记角色 + 境界词」才记录；按章取出现次数最多的境界（避免单句假设污染），生成每人进阶时间线
- **四类告警**：凭空支出（余额转负）、战力膨胀（单章跨阶 > 2）、境界倒退（低于前章）、数值无上下文
- **手工账**：`truth/ledger.json`（`resource/delta/chapter/note`）合并入结算
- **导出**：CSV（含 BOM，Excel 直接打开）

接口：`GET /api/ledger?path=<书目录绝对路径>`。

### 情绪张力曲线与节拍器（P8）

零 LLM 成本，按章统计七个节奏指标并合成张力分（0-100）：

| 指标 | 说明 |
|---|---|
| 句长紧凑度 / 句长方差 | 短句密集 = 紧张；忽长忽短 = 有起伏 |
| 对话占比 | 对话推进节奏 |
| 动作冲突密度 | 斩/轰/爆/生死/搏杀…（每千字） |
| 转折密度 | 突然/猛然/千钧一发/不料… |
| 感叹疑问密度 | ！？密度 |
| 情绪极性 | 高张力情绪词 vs 平静词，-100 ~ +100 |

**节拍目标带**（起承转合）：前 25% 铺垫 32→46，中段爬升 46→72，后段高潮 72→78，末章收束回落 58。曲线上以虚线带呈现，实际张力叠在其上对照。

**四类节奏告警**：

1. **流水账**（warn）— 连续 ≥3 章张力 < 35
2. **高潮疲劳**（warn）— 连续 ≥3 章张力 > 65（读者会疲劳，需缓冲）
3. **节奏断崖**（warn）— 相邻章落差 > 40（骤升缺铺垫 / 高潮后无余波）
4. **偏离节拍**（info）— 与目标带偏差 > 25（该推进时温吞 / 该铺垫时过早发力）

系数经真实网文语料标定：典型战斗章落在 50-66，爆点章可上探 70+，纯铺垫章 15-30，避免全线饱和。

**导出**：CSV（含逐章指标 + 高潮片段 + 告警）。

接口：`GET /api/tension?path=<书目录绝对路径>`。

### 分支剧情 · 互动小说（P9）

零 LLM 成本，把长篇小说组织成可视化的**剧情树**，支持多结局、多选择支的互动叙事编排（纯本地统计与 SVG 渲染，导出物可直接浏览器打开，无需后端）。

**数据模型**（`truth/branching.json`）：

| 字段 | 说明 |
|---|---|
| `nodes[].id / title / type` | 节点唯一 id、标题、类型：`start`(起点) / `scene`(场景) / `ending`(结局) |
| `nodes[].chapterRef` | 关联的章节号（用于回跳正文、在互动阅读器中标注「第 N 章」） |
| `nodes[].excerpt` | 该节点的正文摘要 / 剧情要点 |
| `edges[].from / to / choice` | 有向选择支：从 A 节点到 B 节点，选择文案（如「东行」「决战」） |

**骨架生成**：书暂无 `branching.json` 时，按章节目录自动建骨架 —— 每章一个节点，首章为 `start`，标题含「结局」标记的章记为 `ending`，其余为 `scene`。

**剧情树统计与六类结构告警**（BFS 可达性分析）：

1. **死胡同 deadend**（error）— 非结局节点无出边，读者无路可走
2. **孤立 isolated**（warn）— 既无入边也无出边
3. **不可达 unreachable**（warn）— 从起点 BFS 无法到达
4. **缺少结局 noending**（error）— 无 `ending` 节点，读者无法通关
5. **多起点 multistart**（info）— 互动小说通常仅一个入口
6. **环路 cycle**（info）— 分支回到已走过的节点

**编辑与导出**：侧栏「🌿 分支」面板用分层 SVG 渲染剧情树（起点绿 / 场景蓝 / 结局金，不可达节点置灰），支持点击编辑节点、增删选择支、保存结构，并**一键导出零依赖互动阅读器**（自包含 HTML，DFS 渲染、可前进/返回/重开，纯本地离线可用）。

接口：`GET /api/branching?path=<书目录绝对路径>`（加 `&scaffold=1` 强制按章节重建骨架）；`POST /api/branching` 保存 `{path, nodes[], edges[]}`（自动校验边不可悬空/自环，pretty-print 写入 `truth/branching.json`）。

**章节正文内联（P9.1）**：`GET` 响应与导出的互动阅读器会按 `nodes[].chapterRef` 自动抽取对应 `chapters/chapter-NNN.md` 的正文（跳过首行标题、去除 Markdown 标记、保留段落换行），内联进每个节点。编辑器内新增「正文预览」（只读）便于作者核对；导出阅读器时读者点节点即可读该章**原文**而非仅摘要。正文属派生视图，**不写入** `branching.json`（每次读取实时从章节抽取，永不冗余、永不陈旧）。

**增强套件（P9.2）**：

- **树统计增强**：`GET /api/branching` 的 `stats` 新增 `shortestToEnding`（BFS 到最近结局的边数，无可达结局为 -1）、`longestChain`（最长简单链，忽略回边）、`maxBranchWidth`（最大出度）、`widthDist`（出度→节点数分布）、`startBranch`（起点分叉数）。面板内新增「分支宽度分布」条形图，并对「开局即分叉过多 / 最长链偏长」给出提示，辅助判断节奏是否合理。
- **条件分支 + 轻量状态机**：`edges[]` 可携带 `requires`（门槛）与 `sets`（设置）：
  - `requires`：`{ flags:[...], attrs:{ "gold":">=10" } }` —— 满足才显示该选项；支持 `>= > <= < == !=` 数值比较与 Flag 持有判定。
  - `sets`：`{ flags:[...], attrs:{ "gold":"+5" } }` —— 选择后生效；属性支持 `+5 / -3` 增减，否则赋值。
  - `nodes[]` 的 `state`（仅起点生效）定义初始状态 `{ flags:[], attrs:{} }`。导出的互动阅读器内嵌该状态机：维护 flags/attrs，门槛未达标的选项**置灰并注明原因**，选择时应用 `sets`，「返回」会还原状态快照，实现真正的条件解锁与分支记忆。
- **大纲联动校验**：若书含 `outline.md`，后端解析其「卷」归属（第一卷/Vol/Part/部 + 章节区间）与关键抉择点（含 抉择/分支/选择/分歧/关键节点 等标记的行）。面板「📑 大纲联动」区展示检测到的卷（供按卷着色）与**未覆盖的抉择点缺口**（大纲有、剧情树缺），并有 `volumeMap` 把每个节点映射到所属卷。
- **可视化布局优化**：剧情树 SVG 支持 **滚轮缩放 / 拖拽平移 / 点节点折叠子树**（折叠节点的子树整体收起，节点显示 ⊕/⊖ 切换）；「🎨 按卷着色」开关开启后，节点按 `volumeMap` 的卷着色（无卷时回退类型色），并配图例。

## 内置 Genre Profiles

**中文网文**：玄幻、仙侠、都市、恐怖、言情

**英文小说**：Fantasy、Thriller、Romance、Sci-Fi、Mystery

## 差异化功能

1. **Java 实现** — 纯 Java，不依赖 Node.js，跨平台
2. **本地优先** — 所有数据在项目目录内，无遥测
3. **网文专精** — 深度适配中文网文写作习惯
4. **风格克隆** — 分析参考文本一键导入写作风格
5. **反 AIGC 检测** — 11 条规则 + LLM 验证
6. **33 维审计** — 覆盖节奏、对话、世界观、大纲、风格、hook、反AI
7. **流水线中断恢复** — checkpoint 自动保存，失败后可从断点续笔
8. **批量写作** — CLI `write batch` 或 Studio 一键写多章
9. **SSE 实时进度** — Studio 端实时推送 Agent 执行状态
10. **EPUB/TXT/MD 导出** — 支持封面图片
11. **增量备份回滚** — TruthState 保留最近 10 版本，一键回滚
12. **关系图谱可视化** — 零依赖力导向 SVG，势力聚类着色 + 章节演变播放 + 节点过滤（6 个对标竞品均无此能力）
13. **实力分布地图** — Voronoi 色块疆域图 + 势力档案（范围/下辖/特点/风格）+ 敌对/同盟关系线 + 拖拽调整 + PNG 导出（竞品均无此能力）
14. **智能取名** — 人物姓名 / 武功招式 / 道具 / 兵器 / 势力 / 坐骑 六类一键生成，11 种风格词库（含恐怖/科幻/奇幻/神秘/诡异）+ 寓意释义 + 关键字注入 + 排除现有角色重名 + 收藏夹/批量落库角色表（纯本地，零 AI 依赖）
15. **Ollama 离线链路** — 本地大模型一键接入与自动探测，断网/隐私场景全程本地生成（对标竞品多为云 API 强依赖）
16. **设定集自动抽取** — 六类设定实体 + 四类一致性告警，把「写了 80 章后自己都忘了的设定」显性化（对标 LoreWeave 的 RAG glossary，墨阁为零 LLM 成本实现）
17. **资源与战力账本** — 资源收支余额结算 + 境界进阶曲线 + 凭空支出/战力膨胀告警，机器核验网文两大硬伤（对标 inkos / StoryWeaver）
18. **情绪张力曲线与节拍器** — 七指标合成张力分 + 起承转合目标带 + 四类节奏告警，把「写得平不平」变成可量化曲线（对标 StoryWeaver 的情绪张力节拍器）

## 测试

270 个单元测试 + 8 个集成测试，全部通过。覆盖：Agent 独立测试、Pipeline checkpoint/resume、AuditEngine、TextUtils、WorldState、TimelineState、TruthState 备份回滚、StudioServer HTTP 端点。

## 构建

```bash
mvn compile        # 编译
mvn package        # 打包
mvn test           # 测试
```

要求：Java 17+, Maven 3.8+

## 安装与启动（Windows EXE）

### 一键构建

使用构建脚本一键打包为自带 JRE 的独立 Windows 应用：

```bash
# 便携版（默认，双击即可运行）
build-studio-exe.bat

# MSI 安装包（需安装 WiX Toolset v3）
build-studio-exe.bat msi

# EXE 安装包（需安装 WiX Toolset v3）
build-studio-exe.bat exe
```

脚本会自动完成：
1. 全量构建（`mvn clean package`）
2. jpackage 打包（生成自带 JRE 的独立应用）
3. 输出验证

### 输出位置

| 打包类型 | 输出目录 |
|---------|--------|
| app-image（便携版） | `packages/novelforge-studio/target/jpackage/NovelForgeStudio/` |
| MSI 安装包 | `packages/novelforge-studio/target/jpackage-msi/` |
| EXE 安装包 | `packages/novelforge-studio/target/jpackage-exe/` |

便携版双击 `NovelForgeStudio.exe` 即可启动，无需安装 Java。MSI/EXE 安装包支持安装目录选择、桌面快捷方式、开始菜单。

### jpackage 手动构建

```bash
# 先全量构建
mvn clean package

# 便携版
mvn package -Pjpackage-studio -pl packages/novelforge-studio

# MSI 安装包
mvn package -Pjpackage-studio-msi -pl packages/novelforge-studio

# EXE 安装包
mvn package -Pjpackage-studio-exe -pl packages/novelforge-studio
```

### 功能特性

- `--icon` 自定义应用图标（橙焰锻造风格）
- `--win-console` 保留控制台窗口方便查看日志
- MSI/EXE 支持：安装目录选择、桌面快捷方式、开始菜单组
- 版本号自动从 `pom.xml` 读取

### 前置要求

- JDK 17+（需要 `jpackage` 工具，位于 JDK 的 bin 目录）
- Maven 3.8+
- 确认 `jpackage` 可用：`jpackage --version`
- MSI/EXE 安装包额外需要 [WiX Toolset v3](https://wixtoolset.org/releases/)

## License

MIT
