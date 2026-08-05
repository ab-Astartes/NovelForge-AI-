# NovelForge IDE Plugins

> NovelForge Studio在VSCode、IntelliJ IDEA、Trae三大IDE中的内嵌集成方案。

## 设计原则

### 🎯 核心哲学：插件是轻量前端，StudioServer是后端

所有插件都遵循同一原则：
- **插件只做两件事**：启动/连接本地StudioServer + 内嵌WebView显示UI
- **所有业务逻辑在StudioServer**：写作、审计、状态管理、配置管理
- **配置不重复**：插件只管端口+是否自动启动；写作配置通过`/api/config`管理
- **API是桥梁**：插件通过HTTP API与StudioServer通信，同origin无CORS问题

### 🔄 统一架构模型

```
┌─────────────────────────────────────────┐
│ IDE Plugin (轻量前端)                    │
│                                         │
│ 入口: activate/init                     │
│ 面板: WebView / JCEF Browser            │
│ API: StudioApiClient (HTTP → Studio)    │
│ 配置: 端口、自动启动、Java路径           │
│                                         │
│ ┌─ IDE侧边栏面板 ─────────────────────┐ │
│ │ NovelForge Studio UI                │ │
│ │ (iframe/JCEF → localhost:8964)      │ │
│ └─────────────────────────────────────┘ │
└──────────────────┬──────────────────────┘
                   │ HTTP REST API
                   ▼
┌─────────────────────────────────────────┐
│ StudioServer (Java HTTP Server)         │
│ localhost:8964                           │
│                                         │
│ /api/books      → 书籍列表              │
│ /api/write      → 写作流水线            │
│ /api/audit      → 审计评分              │
│ /api/state      → TruthState管理        │
│ /api/config     → 配置管理              │
│ /api/version    → 版本信息              │
│ /studio/*       → 前端静态资源           │
│                                         │
│ 9-Agent Pipeline | TruthState | SSE     │
└─────────────────────────────────────────┘
```

## 插件目录结构

```
plugins/
  vscode-novelforge/          # VSCode插件
    src/
      extension.ts            # 入口：注册SidebarProvider, 启动Server
      sidebar-provider.ts     # WebViewProvider：内嵌Studio HTML
      api-client.ts           # 与StudioServer HTTP API通信
      config-manager.ts       # VSCode配置桥接
    package.json               # Extension manifest (contributes views/commands/config)
    tsconfig.json              # TypeScript配置
    webpack.config.js          # 打包配置
    media/icon.svg             # 侧边栏图标
    .vscodeignore              # VSIX打包排除
    README.md                  # 安装与使用说明
    
  idea-novelforge/             # IntelliJ IDEA插件
    src/main/java/
      NovelForgeSettings.java  # 持久化配置 (PersistentStateComponent)
      StudioToolWindowFactory.java  # ToolWindow创建入口
      StudioBrowserPanel.java  # JCEF内嵌浏览器面板
      ServerManager.java       # StudioServer子进程生命周期管理
      ConfigPanel.java         # Settings > Tools > NovelForge UI
      StartServerAction.java   # 启动Server Action
      StopServerAction.java    # 停止Server Action
      OpenInBrowserAction.java # 外部浏览器打开
      RestartServerAction.java # 重启Server
      NovelForgeNotifier.java  # IntelliJ通知工具
    src/main/resources/
      META-INF/plugin.xml      # IntelliJ plugin descriptor
      META-INF/plugin-extra.xml # 通知组注册
      icons/novelforge.svg     # ToolWindow图标
    build.gradle.kts           # Gradle构建 (IntelliJ Platform SDK 2.x)
    settings.gradle.kts        # Gradle设置
    README.md                  # 安装与使用说明
    
  trae-novelforge/             # Trae IDE插件（VSCode兼容）
    src/
      extension.ts             # 入口（微调：publisher, 输出通道）
      sidebar-provider.ts      # ← 直接复制vscode-novelforge版本
      api-client.ts            # ← 直接复制vscode-novelforge版本
      config-manager.ts        # ← 直接复制vscode-novelforge版本
    package.json               # publisher改为"novelforge-trae"
    tsconfig.json              # ← 复制
    webpack.config.js          # ← 复制
    media/icon.svg             # ← 复制
    .vscodeignore              # ← 复制
    README.md                  # Trae适配说明
```

## 各IDE集成方式对比

| 特性 | VSCode | IntelliJ IDEA | Trae |
|------|--------|---------------|------|
| UI嵌入 | WebView (iframe) | JCEF Browser | WebView (iframe) |
| 配置持久化 | settings.json | PersistentStateComponent | settings.json |
| 通知方式 | StatusBarItem | NotificationGroup | StatusBarItem |
| Server管理 | child_process.spawn | ProcessBuilder | child_process.spawn |
| API通信 | Node.js http模块 | HttpURLConnection | Node.js http模块 |
| 构建工具 | webpack + vsce | Gradle + IntelliJ Platform | webpack + vsce |
| 安装格式 | .vsix | .zip (plugin) | .vsix |
| 语言 | TypeScript | Java 17 | TypeScript |
| 市场分发 | VSCode Marketplace | JetBrains Marketplace | Trae Marketplace |

## 构建与验证

### VSCode插件
```bash
cd plugins/vscode-novelforge
npm install
npm run compile    # webpack生产构建
```

### IntelliJ插件
```bash
cd plugins/idea-novelforge
./gradlew buildPlugin    # 生成 build/distributions/*.zip
```

### Trae插件
```bash
cd plugins/trae-novelforge
npm install
npm run compile    # 同VSCode构建流程
```

## 配置一致性

所有三个插件的配置项完全对齐：

| 配置项 | 默认值 | VSCode key | IntelliJ key | 说明 |
|--------|--------|-----------|-------------|------|
| 端口 | 8964 | novelforge.serverPort | serverPort | 与CLI默认端口一致 |
| 自动启动 | true | novelforge.autoStart | autoStart | 激活时自动启动Server |
| Java路径 | (空) | novelforge.javaPath | javaPath | 空=自动检测JAVA_HOME |
| Jar路径 | (空) | novelforge.studioJarPath | studioJarPath | 空=自动查找 |
| 启动超时 | 30000ms | novelforge.serverTimeout | serverTimeout | Server启动等待时间 |

## 版本同步

插件版本号与NovelForge核心版本号保持一致（当前0.4.3）。
更新核心版本时，同步更新三个插件的version字段。

## License

MIT
