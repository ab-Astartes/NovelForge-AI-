# NovelForge Studio — VSCode Extension

> 在VSCode侧边栏内嵌NovelForge Studio面板，一键启动/管理本地StudioServer。

## 功能

- 📖 **侧边栏集成** — NovelForge Studio UI直接嵌入VSCode侧边栏
- 🚀 **一键启动** — 自动检测并启动StudioServer（Java子进程）
- 🔌 **API桥接** — 插件直接调用本地StudioServer HTTP API，无CORS问题
- 📊 **状态监控** — 状态栏实时显示Server运行状态
- ⚙️ **配置管理** — 端口、自动启动、Java路径等均可配置

## 安装

### 方式1: 从VSIX安装（推荐）

1. 构建插件：
   ```bash
   cd plugins/vscode-novelforge
   npm install
   npm run compile
   ```

2. 打包为VSIX：
   ```bash
   npx vsce package
   ```

3. 在VSCode中安装：
   - 打开VSCode → Extensions → `...` → Install from VSIX
   - 或命令行: `code --install-extension vscode-novelforge-0.4.3.vsix`

### 方式2: 开发模式调试

1. 克隆NovelForge仓库
2. `cd plugins/vscode-novelforge && npm install`
3. 在VSCode中打开此目录，按F5启动Extension Development Host

## 前置条件

- **Java 17+** — StudioServer需要Java运行环境
- **novelforge-studio.jar** — 需先构建NovelForge项目 (`mvn package`)

插件会自动查找：
1. `JAVA_HOME/bin/java`
2. 系统`java`命令
3. 用户配置的`novelforge.javaPath`

StudioServer jar查找：
1. `$HOME/NovelForge/novelforge-studio.jar`
2. 用户配置的`novelforge.studioJarPath`

## 配置

在VSCode Settings中搜索"NovelForge"：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `novelforge.serverPort` | 8964 | StudioServer端口 |
| `novelforge.autoStart` | true | 激活时自动启动Server |
| `novelforge.javaPath` | "" | Java 17+路径（空=自动检测） |
| `novelforge.studioJarPath` | "" | studio.jar路径（空=自动查找） |
| `novelforge.serverTimeout` | 30000 | Server启动超时(ms) |

## 命令

| 命令 | 说明 |
|------|------|
| `NovelForge: Start Studio Server` | 启动本地StudioServer |
| `NovelForge: Stop Studio Server` | 停止由插件管理的Server进程 |
| `NovelForge: Restart Studio Server` | 重启Server |
| `NovelForge: Open Studio in Browser` | 在外部浏览器打开Studio |
| `NovelForge: Show Server Status` | 显示Server状态信息 |

快捷键: `Ctrl+Shift+N F` / `Cmd+Shift+N F` → 打开Studio

## 架构

```
┌─────────────────────────────────────┐
│ VSCode Extension Host (Node.js)     │
│                                     │
│  extension.ts ← activate/deactivate │
│  sidebar-provider.ts ← WebView UI   │
│  api-client.ts ← HTTP API calls     │
│  config-manager.ts ← Settings bridge│
│                                     │
│  ┌─ Status Bar ─────────────────┐   │
│  │ ✅ NovelForge (port 8964)    │   │
│  └──────────────────────────────┘   │
│                                     │
│  ┌─ Sidebar WebView ───────────┐   │
│  │ NovelForge Studio UI        │   │
│  │ (iframe → localhost:8964)   │   │
│  └──────────────────────────────┘   │
└──────────────────┬──────────────────┘
                   │ HTTP API
                   ▼
┌─────────────────────────────────────┐
│ StudioServer (Java subprocess)      │
│ localhost:8964                       │
│                                     │
│ /api/books, /api/write, etc.        │
│ /studio/index.html (frontend)       │
└─────────────────────────────────────┘
```

## 开发

```bash
npm install          # 安装依赖
npm run compile      # 生产构建
npm run watch        # 开发监听
npm run dev          # 开发构建
```

## License

MIT
