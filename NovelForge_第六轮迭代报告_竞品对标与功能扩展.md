# NovelForge（墨阁）第六轮迭代报告 —— 竞品对标 + 功能扩展

> 日期：2026-08-28 ｜ 模块：智能取名升级 · 疆域地图增强 · Ollama 离线链路
> 品牌区分：GitHub 另有 `RhythmicWave/NovelForge`（Python 1.1k⭐）与 `all666666all/AI-novel---NovelForge--`（FastAPI 实现），均非本仓库产物。本仓库对外统一以「**墨阁 / NovelForge**」标识。

---

## 一、GitHub 同类产品对标分析

调研对象（均为开源 AI 小说/故事创作系统）：

| 项目 | 语言/栈 | 热度(约) | 核心卖点 |
|---|---|---|---|
| **inkos**（Narcooo/inkos） | Node/TS + Studio | **~7.9k⭐** | 多 Agent 流水线、7 真相文件、33 维审计、资源账本、伏笔追踪、文风仿写、互动世界(分支剧情)、多语种翻译工作台、Ollama 本地路由、守护进程日更、状态快照回滚 |
| **StoryWeaver**（linnyh） | Python | 中 | RAG+分层大纲、动态状态机(Power System)、动态人物关系网、哲学多智能体审稿委员会、情绪张力节拍器 |
| **InkForge**（smallletters/inkforge） | Node + Python | 中 | 10 Agent 管线、7 真相文件、多模型路由(**含 Ollama**)、自定义提示词 |
| **LoreWeave**（letuhao/lore-weave） | Go + NestJS + React | 中 | RAG 术语表自动抽取、多语种翻译(尊重自创术语)、Living-world MMO(LLM 驱动 NPC)、知识图谱自动抽取 |
| **NovelForge**（all666/AI-novel） | Vue3 + FastAPI | 中 | World Bible、概念→大纲→章节、多版本草稿、一致性审查、伏笔追踪、情感&节奏分析、RAG、分层优化器、管理后台 |
| **墨阁 / NovelForge**（本仓库） | Java17 + Maven + 原生前端 | — | 取名器(6类×11风格)、Voronoi 疆域地图、关系图谱(convex hull)、长程记忆 RAG、导出 docx/pdf/封面、市场雷达、审计、本轮回应的 Ollama 离线链路 |

### 1.1 功能矩阵（✅ 已有 / 🔲 缺失 / 🟡 部分）

| 能力 | inkos | StoryWeaver | LoreWeave | 墨阁 |
|---|---|---|---|---|
| 多 Agent 流水线 | ✅ | ✅ | 🟡 | ✅(九重炼章) |
| 长程记忆(RAG) | ✅ | ✅ | ✅ | ✅ |
| 关系图谱可视化 | 🟡 | ✅ | ✅ | ✅(convex hull) |
| 势力疆域地图 | 🔲 | 🔲 | 🔲 | ✅(Voronoi 色块) |
| 智能取名(多类目) | 🔲 | 🔲 | 🔲 | ✅(6类×11风格) |
| 文风仿写/克隆 | ✅ | 🔲 | 🔲 | ✅ |
| 资源/战力账本 | ✅ | ✅ | 🔲 | 🔲 |
| 情绪张力曲线/节拍器 | 🔲 | ✅ | 🔲 | 🟡(基础分析) |
| 分支剧情/互动世界 | ✅ | 🔲 | ✅ | 🔲 |
| 多语种翻译工作台 | ✅ | 🔲 | ✅ | 🔲(仅导出) |
| 术语表/设定集自动抽取 | 🔲 | 🔲 | ✅ | 🔲 |
| 本地模型(Ollama)路由 | ✅ | 🔲 | 🔲 | ✅(本轮新增) |
| 守护进程/后台自动写作 | ✅ | 🔲 | 🔲 | 🔲 |
| 状态快照/章节回滚 | ✅ | 🔲 | 🔲 | 🟡(版本化部分) |
| 通知(飞书/企微/钉钉/Telegram) | ✅ | 🔲 | 🔲 | 🟡(通用 Webhook) |
| 多 Agent 可视化流水线 | 🔲 | 🔲 | 🔲 | 🔲 |
| 哲学/多视角审稿委员会 | 🔲 | ✅ | 🔲 | 🔲 |

### 1.2 可完善的功能缺口（对标后提炼的路线图）

按「差异化领先 / 用户价值」排序，建议后续迭代重点关注：

1. **资源与战力账本（particle ledger）** —— inkos/StoryWeaver 的强项。墨阁可加 `truth/ledger.json`：物品/金钱/物资数量 + 衰减追踪，审计时校验「凭空出现/消失」。
2. **分支剧情与互动小说运行时** —— inkos Play、LoreWeave 已做。墨阁可做轻量版：分支选择图 + 变量/flag + 多结局导出。
3. **多语种翻译工作台** —— 导入 EPUB/TXT → 按章翻译 → 术语表锁定自创词 → 对照报告。LoreWeave 的「术语不被翻译歪曲」是核心壁垒。
4. **术语表 / 设定集自动抽取** —— LoreWeave 的 RAG glossary。可复用长程记忆管线，从章节自动抽人名/地名/术语表并锁定一致性。
5. **情绪张力曲线与章节节拍器** —— StoryWeaver。写作前规划张力曲线（起承转合），写作后渲染实际曲线，对标「流水账」预警。
6. **守护进程 / 后台自动日更** —— inkos `daemon`。墨阁可加一个无人值守连续写 N 章的后台任务 + 完成通知。
7. **状态快照与章节回滚** —— 每章自动快照，支持回滚到任意章重写。
8. **多 Agent 可视化流水线** —— 把九重炼章做成实时进度/日志面板，对标 InkForge 的可视化。
9. **多平台通知** —— 在现有 Webhook 基础上，内置飞书/企业微信/钉钉签名模板。
10. **哲学/多视角审稿委员会** —— 多个 Agent 分别从逻辑/爽点/立意维度评分，低于阈值自动打回重写（对标 StoryWeaver）。

---

## 二、本轮交付（P5 功能扩展）

### 2.1 智能取名 · 风格词库扩充（5 新增风格）

| 风格 key | 中文 | 适用氛围 |
|---|---|---|
| `horror` | 恐怖惊悚 | 尸/血/骨/咒/煞；技能血祭破、亡灵噬；兵器噬魂刃；势力血月墓域 |
| `scifi` | 科幻硬核 | 星/舰/弦/码；技能量子场、曲速网；兵器脉冲炮；势力星舰联邦 |
| `fantasy` | 奇幻瑰丽 | 璃/鳞/冠/蕾；技能精灵术、巨龙歌；坐骑巨龙/狮鹫；势力精灵圣堂 |
| `mystery` | 神秘悬疑 | 谜/雾/影/秘；技能迷雾影、星象卦；坐骑星兽幻驹；势力迷雾学会 |
| `eerie` | 诡异怪谈 | 诡/畸/裂/呓；技能诡谲裂、呓语畸；坐骑畸兽裂空；势力诡域异教 |

- 后端 `StudioServer`：`NAMING_STYLE_DESC` / `NAMING_GIVEN` 各 +5 风格字池；`NAMING_GLOSS` 新增 ~80 条释义；新增 `*_BY_STYLE` 词池（skill/item/weapon/faction/mount 按风格着色），命中即用、未命中回退默认池。
- 前端 `naming.js`：`NAMING_STYLES` 由 6 → **11** 项，风格下拉自动渲染。

### 2.2 取名单击收藏 + 批量落库角色表

- 每张结果卡片新增 **♡ 收藏** 按钮（会话内收藏夹 `namingFavs`）。
- 收藏夹面板（`#naming-favs`）列出已收藏项（类目+名称+移除）。
- 两个落库按钮：
  - **💾 收藏到素材库** → `POST /api/naming/save {target:"favorites"}` 写 `truth/naming_favorites.json`（跨类目素材库，持久化）。
  - **📥 批量落库角色表** → `POST /api/naming/save {target:"characters"}` 将「人物」类收藏写入 `truth/characters.json`（自动去重，兼容数组/对象两种形态）。

### 2.3 疆域地图 · 拖拽调整势力中心 + 导出 PNG

- 工具栏新增 **🖐 拖拽调整**：开启后在各势力中心出现可拖动手柄，拖动实时重算 Voronoi 单元（手调中心写入 `fmSeedOverride`，不破坏其他势力版图）。
- 工具栏新增 **💾 导出 PNG**：将 SVG 序列化（显式 `font-size`/`font-family` 属性以绕过外链 CSS 丢失）后 2× 超采样栅格化为高清 PNG 下载。
- 拖拽模式下点击不再触发选中，避免误触。

### 2.4 Ollama 离线链路

- 后端：
  - `ModelRouter` 新增 `ollama` provider（复用 OpenAI 兼容 `/v1` 端点，Ollama 暴露 OpenAI 兼容接口）。
  - 新增 `GET /api/ollama/models`（`/api/tags` 列出本地模型名）与 `GET /api/ollama/health`（连通性探测，5s 超时）。
- 前端：
  - `BUILTIN_PROVIDERS` 增加 `ollama` 预设（baseUrl `http://localhost:11434/v1`，默认模型 `qwen2.5:7b`）。
  - 配置面板 Provider 下拉新增「Ollama（本地离线）」选项 + 快速切换按钮。
  - 新增 **🔍 检测本地 Ollama 模型** 按钮，调用 `/api/ollama/models` 自动填充模型下拉（datalist 候选）。

---

## 三、验证

- 前端三审计（`.audit/checkids.js`）：**[A] 缺失 id 0 / [B] 未定义函数 0 / [C] 重复函数 0 / [D] 缺失 class 0 / [E] 面板↔导航 12↔12 全匹配**。
- `node --check`：naming.js / factionmap.js / app.js 全部通过。
- Maven `mvn -q -o test`：BUILD SUCCESS（270 基线 + StudioServerTest 8 项，0 failures / 0 errors）。
- Studio 冒烟（:8964 实测）：
  - 新风格取名：恐怖人物（欧阳棺）、科幻兵器（磁轨锏/离子长戟）、诡异武功（呓语裂玄机）、神秘势力（星象隐会）——风格辨识度确认；
  - `POST /api/naming/save`：落库角色表 / 收藏素材库均 `ok:true`，重名跳过逻辑生效；
  - `GET /api/ollama/models`：本机无 Ollama 时优雅返回 `reachable:false` + 空模型列表，不报错。

### 3.1 冒烟回归修复（3 处）

1. **坐骑叠词**：`狮鹫狮鹫`——MOUNT_IMG 与 MOUNT_SP 的 fantasy 池有重叠词；`generateOneName` mount 分支增加互含检测，img/sp 相同或互含时直接用 sp。
2. **人物风格稀释**：恐怖风格人物出现「骁焱」（通用阳刚池字）——首字改必取风格主池 `npick(base)`，次字仍从性别扩展池取，保证每个名字至少一个风格字。
3. **落库结构损坏**：`characters.json` 为标准对象形态 `{"characters":[...]}` 时被 `fieldNames()` 误转为 `[{"name":"characters"}]` 垃圾条目——save handler 读侧增加 `characters` 数组分支，写侧统一回写 `{"characters":[...]}` 标准形态；同修取名去重读取器（原只识别数组形态，对象形态书目重名检测失效）。

## 四、文件改动清单

- 后端：`StudioServer.java`（命名风格词库 + 收藏/落库 + Ollama 三接口 + 导入 + 3 处冒烟回归修复）、`ModelRouter.java`（ollama provider）
- 前端：`naming.js`、`factionmap.js`、`app.js`、`index.html`、`style.css`
- 文档：本报告 + README 补全（LLM 路由 Ollama、智能取名 P4-P6、疆域拖拽/PNG、差异化功能 13-15 条）
