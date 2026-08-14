# NovelForge Studio — VSCode Extension

> 在 VSCode 侧边栏内嵌入 NovelForge Studio 面板，一键启动/管理本地 StudioServer。

## 功能

- 📋 **侧边栏集成** — NovelForge Studio 控制面板直接嵌入 VSCode 侧边栏
- 🚀 **一键启动** — 自动检测并启动 StudioServer（Java 子进程）
- 🔌 **API 桥接** — 插件直接调用本地 StudioServer HTTP API，无 CORS 问题
- 📊 **状态监控** — 状态栏实时显示 Server 运行状态
- ⚙️ **配置管理** — 端口、自动启动、Java 路径等均可配置

## 安装

### 方式 1: 从 VSIX 安装（推荐）

1. 构建插件：
   ```bash
   cd plugins/vscode-novelforge
   npm install
   npm run compile
   ```

2. 打包为 VSIX：
   ```bash
   npx vsce package
   ```

3. 在 VSCode 中安装：
   - 打开 VSCode → Extensions → `...` → Install from VSIX
   - 或命令行: `code --install-extension vscode-novelforge-0.5.0.vsix`

### 方式 2: 开发模式调试

1. 克隆 NovelForge 仓库
2. `cd plugins/vscode-novelforge && npm install`
3. 在 VSCode 中打开此目录，按 F5 启动 Extension Development Host

## 前置条件

- **Java 17+** — StudioServer 需要 Java 运行环境
- **novelforge-studio.jar** — 需先构建 NovelForge 项目 (`mvn package`)

插件会自动查找：
1. `JAVA_HOME/bin/java`
2. 系统 `java` 命令
3. 用户配置的 `novelforge.javaPath`

StudioServer jar 查找：
1. `$HOME/NovelForge/novelforge-studio.jar`
2. 用户配置的 `novelforge.studioJarPath`

## 配置

在 VSCode Settings 中搜索 "NovelForge"：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `novelforge.serverPort` | 8964 | StudioServer 端口 |
| `novelforge.autoStart` | true | 激活时自动启动 Server |
| `novelforge.javaPath` | "" | Java 17+ 路径（空=自动检测） |
| `novelforge.studioJarPath` | "" | studio.jar 路径（空=自动查找） |
| `novelforge.serverTimeout` | 30000 | Server 启动超时(ms) |

## 命令

| 命令 | 说明 |
|------|------|
| `NovelForge: Start Studio Server` | 启动本地 StudioServer |
| `NovelForge: Stop Studio Server` | 停止由插件管理的 Server 进程 |
| `NovelForge: Restart Studio Server` | 重启 Server |
| `NovelForge: Open Studio in Browser` | 在外部浏览器打开 Studio |
| `NovelForge: Show Server Status` | 显示 Server 状态信息 |

快捷键: `Ctrl+Shift+N F` / `Cmd+Shift+N F` → 打开 Studio

## 架构

```
┌──────────────────────────────────────┐
│ VSCode Extension Host (Node.js)      │
│                                      │
│ extension.ts → activate/deactivate   │
│ sidebar-provider.ts → WebView UI     │
│ api-client.ts → HTTP API calls       │
│ config-manager.ts → Settings bridge  │
│                                      │
│ ┌─── Status Bar ──────────────────┐  │
│ │ ✅ NovelForge (port 8964)       │  │
│ └─────────────────────────────────┘  │
│                                      │
│ ┌─── Sidebar Control Panel ───────┐  │
│ │ 启动/停止/打开 Studio 按钮       │  │
│ │ 状态灯 + 端口信息               │  │
│ └─────────────────────────────────┘  │
└──────────────────────────────────────┘
                   │ HTTP API
                   ▼
┌──────────────────────────────────────┐
│ StudioServer (Java subprocess)       │
│ localhost:8964                        │
│                                      │
│ /api/books, /api/write, etc.         │
│ /studio/index.html (frontend)        │
└──────────────────────────────────────┘
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
