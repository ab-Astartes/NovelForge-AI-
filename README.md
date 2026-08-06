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

支持 OpenAI、Anthropic、自定义 OpenAI-compatible endpoint。每个 Agent 可配置不同模型/provider。指数退避重试，SSE 畸形行容错。

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
