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

| Agent | 职责 | 温度 |
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

结构化状态文件（`characters.json`、`world.json`、`timeline.json`、`hooks.json`），每个 Agent 只读+增量写入，保证叙事一致性。

### LLM 路由

支持 OpenAI、Anthropic、自定义 OpenAI-compatible endpoint。每个 Agent 可配置不同模型/provider。

## 使用

### CLI

```bash
# 创建项目
novelforge book create --title "龙血战神" --genre xuanhuan

# 写下一章（完整流水线）
novelforge write next --book ./my-book

# 写草稿（仅 Architect→Writer）
novelforge write draft --book ./my-book

# 审计
novelforge audit --book ./my-book --chapter 5

# 导出
novelforge export --book ./my-book --format epub

# 风格克隆
novelforge style clone --reference ./sample.txt

# 交互模式
novelforge interact --book ./my-book
```

### Studio

```bash
novelforge studio          # 启动 Web UI (localhost:8964)
novelforge studio 3000     # 自定义端口
```

## 内置 Genre Profiles

**中文网文**：玄幻、仙侠、都市、恐怖、言情

**英文小说**：Fantasy、Thriller、Romance、Sci-Fi、Mystery

## 差异化功能

1. **Java 实现** — 纯 Java，不依赖 Node.js，跨平台
2. **本地优先** — 所有数据在项目目录内，无遥测
3. **网文专精** — 深度适配中文网文写作习惯
4. **风格克隆** — 分析参考文本一键导入
5. **反 AIGC 检测** — 11 条规则 + LLM 验证
6. **33 维审计** — 覆盖节奏、对话、世界观、大纲、风格、hook、反AI
7. **封面生成** — DALL-E / Stable Diffusion 提示词生成
8. **EPUB/TXT/MD 导出**

## 构建

```bash
mvn compile        # 编译
mvn package        # 打包
mvn test           # 测试
```

要求：Java 17+, Maven 3.8+

## License

MIT
