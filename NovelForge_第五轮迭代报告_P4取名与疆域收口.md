# NovelForge 第五轮迭代报告 · P4 智能取名 + 疆域地图收口

> 日期：2026-08-28 · 版本主线 v0.6.0
> 范围：① 收口上一轮中断的「实力分布地图（疆域）」实测与静态路由；② 新增「智能取名」全功能（人物/武功招式/道具/兵器/势力/坐骑）；③ 修复疆域接口一例回归缺陷；④ 验证全绿、写报告、提交并推送。

---

## 一、本轮交付项

### 1. 智能取名（新增功能，纯本地零 AI 依赖）

**后端** `StudioServer.handleNamingApi` → `/api/naming`（GET）
- 六类生成：人物 / 武功招式 / 道具 / 兵器 / 势力 / 坐骑
- 六套风格词库：仙侠飘逸 / 武侠刚正 / 玄幻霸气 / 古典文雅 / 凶煞凌厉 / 清丽雅致
- 人物支持性别（男/女，不同字池）、指定姓氏、单字/双字名（35% / 65%）
- 关键字注入：所有类别可嵌用户关键字（人物作名、其余前缀拼贴）
- 寓意释义：人物逐字查《字义词典》自动组句；其余类别按部件拼接释义模板
- 书籍去重：传入 `path` 时读取 `truth/characters.json`，排除现有角色重名
- 参数：`type / style / gender / surname / keyword / count(1-30) / path`，返回 `{ok,type,style,generated,names:[{name,meaning}]}`

**前端** `naming.js` + `index.html(nav-naming/panel-naming)` + `app.js` 接线 + `style.css`
- 类别 chips、风格下拉、性别切换（仅人物）、姓氏/关键字输入、数量选择
- 结果卡片：名称（金箔大字）+ 释义 + 一键复制
- 复用书籍下拉 → 人物模式自动排除重名

### 2. 实力分布地图（上轮中断项收口）

- 静态资源路由补注册：`/factionmap.js` 已加入 `serveStatic` 白名单（此前 404 根因）
- 修复一处**疆域接口回归缺陷**：`buildFactionMapJson` 中「成员归位」循环早于 `merged` 势力建档执行，导致无 `world.json` 显式势力的书（如《苍穹破》）输出 0 势力。已改为先以角色推断势力建档再归位成员。
- 实测《苍穹破》现正确输出：势力「萧家」含成员 萧尘、萧风，特点 家族传承/武道炼体/仙门道统，风格「以武立族」。

---

## 二、实现要点

| 模块 | 关键设计 |
|---|---|
| 取名词库 | 姓氏池（30，含复姓）/ 风格字池（6×22）/ 字义词典（120+ 字）/ 招式·道具·兵器·势力·坐骑 各 16-22 组意象词；`ThreadLocalRandom` 取随机 |
| 取名释义 | 人物：`「霄」高远九霄，...` 逐字查表；其余：`【神兵】破军之枪，锋锐无匹` 模板拼接 |
| 疆域修复 | `LinkedHashSet(charFaction.values())` 先建档 → 再 `merged.get(fn).members.add` 归位 → 最后合并 world.json 显式势力 |
| 前端接线 | 沿用既有 `PANEL_MAP` + `populateBookSelects` + `showPanel` 三处分支模式，审计脚本同步纳入 `naming.js` |

---

## 三、验证结果（全绿）

| 项 | 结果 |
|---|---|
| Maven `process-resources + compile` | ✅ |
| Maven `test` | ✅ 270 / 0 / 0（沿用基线，未改测试源码） |
| 前端审计 checkids / checkhtml / checkcss | ✅ [A]-[E]=0；[C] 重复函数已清零（移除 naming.js 内冗余 escapeHtml，复用 app.js 全局） |
| node --check | ✅ app.js / graph.js / factionmap.js / naming.js |
| Studio 实测 /api/naming | ✅ 六类各 4 条样例均合理；关键字注入生效；人物 20 条无重名 |
| Studio 实测 /api/faction-map | ✅ 苍穹破输出 萧家（成员/特点/风格正确） |
| 静态资源 | ✅ factionmap.js(19744B) / naming.js(4453B) / index / app.js / style.css 均 200 |

**取名样例（玄幻霸气）**
- 招式：八卦气罡、天罡指功、斩意
- 道具：地品寒玉符、混沌簪
- 兵器：破军枪、凤鸣重剑
- 势力：九幽谷、凌霄洞天
- 坐骑：裂空貔貅、赤焰火凤
- 人物（仙侠男）：叶峥霆、慕容珩、长孙辰

---

## 四、竞品对标

| 能力 | NovelForge | 主流写作工具 |
|---|---|---|
| 智能取名（6 类 + 风格 + 释义） | ✅ 本地零依赖 | 多数无 / 仅基础随机名 |
| 实力分布地图（Voronoi 色块 + 档案 + 关系线） | ✅ | 均无 |
| 关系图谱（聚类/演变/过滤） | ✅ P3 | 均无 |

---

## 五、下一步候选

1. 取名结果「收藏 / 批量导出到角色表」一键落库 `characters.json`
2. 疆域图支持拖拽调整势力中心、导出 PNG
3. 取名接入 LLM 做语义风格微调（可选，非必需）
4. 多用户 / PostgreSQL 化、Ollama 离线链路（长期）
