# NovelForge Studio — IntelliJ IDEA Plugin

> 在IntelliJ IDEA内嵌NovelForge Studio面板，通过JCEF浏览器加载Studio UI。

## 功能

- 📖 **ToolWindow集成** — NovelForge Studio面板嵌入IDE右侧
- 🌐 **JCEF浏览器** — Chromium内嵌浏览器直接加载StudioServer UI
- 🚀 **一键启动** — 自动启动StudioServer Java子进程
- 🔔 **通知系统** — Server状态变更通过IntelliJ通知推送
- ⚙️ **Settings面板** — Settings > Tools > NovelForge 管理配置
- 🔄 **JCEF Fallback** — 如JCEF不可用，提供"Open in Browser"按钮

## 安装

### 方式1: 从磁盘安装

1. 构建插件：
   ```bash
   cd plugins/idea-novelforge
   ./gradlew buildPlugin
   ```

2. 生成的插件位于: `build/distributions/novelforge-studio-idea-0.4.3.zip`

3. 在IntelliJ IDEA中安装：
   - File → Settings → Plugins → ⚙ → Install Plugin from Disk...
   - 选择生成的zip文件

### 方式2: 开发模式调试

1. 克隆NovelForge仓库
2. `cd plugins/idea-novelforge`
3. 在IntelliJ中打开此目录作为项目
4. 运行 `runIde` Gradle task（启动沙盒IDE实例）

## 前置条件

- **IntelliJ IDEA 2024.2+** — JCEF浏览器组件需要此版本
- **Java 17+** — StudioServer运行需要
- **novelforge-studio.jar** — 先构建NovelForge项目 (`mvn package`)

## 配置

在 Settings → Tools → NovelForge：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| Server Port | 8964 | StudioServer端口 |
| Auto Start Server | true | 插件初始化时自动启动 |
| Java 17+ Path | (空) | Java路径（空=自动检测JAVA_HOME） |
| Studio Jar Path | (空) | studio.jar路径（空=自动查找） |
| Startup Timeout | 30000 | 启动超时(ms) |

## 命令

Tools → NovelForge 菜单：

| 命令 | 说明 |
|------|------|
| Start Studio Server | 启动本地StudioServer |
| Stop Studio Server | 停止由插件管理的Server |
| Open Studio in Browser | 在外部浏览器打开Studio |
| Restart Studio Server | 重启Server |

## 架构

```
┌───────────────────────────────────────┐
│ IntelliJ IDEA                         │
│                                       │
│ NovelForgeSettings ← 持久化配置       │
│ ServerManager ← 子进程生命周期管理     │
│ StudioToolWindowFactory ← 面板创建    │
│ StudioBrowserPanel ← JCEF内嵌浏览器   │
│ ConfigPanel ← Settings UI             │
│ NovelForgeNotifier ← 通知推送         │
│                                       │
│ ┌─ ToolWindow (right) ─────────────┐ │
│ │ JCEF Browser → localhost:8964    │ │
│ │ NovelForge Studio UI             │ │
│ └──────────────────────────────────┘ │
└──────────────────┬────────────────────┘
                   │ HTTP API
                   ▼
┌───────────────────────────────────────┐
│ StudioServer (Java subprocess)        │
│ localhost:8964                         │
│                                       │
│ /api/books, /api/write, etc.          │
│ /studio/index.html (frontend)         │
└───────────────────────────────────────┘
```

## JCEF说明

- IntelliJ IDEA 2024.2+自带JCEF（基于Chromium）
- JCEF在Community Edition和Ultimate Edition均可用
- 如JCEF不可用（极少数情况），面板会显示Fallback UI，提供"Open in Browser"按钮

## 开发

```bash
./gradlew buildPlugin      # 构建插件zip
./gradlew runIde           # 在沙盒IDE中运行调试
./gradlew verifyPlugin     # 验证插件兼容性
```

## License

MIT
