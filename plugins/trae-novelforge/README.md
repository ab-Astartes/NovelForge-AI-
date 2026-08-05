# NovelForge Studio — Trae IDE Extension

> 在Trae IDE侧边栏内嵌NovelForge Studio面板，一键启动/管理本地StudioServer。

## 关于Trae适配

Trae是字节跳动的IDE产品，基于VSCode扩展架构。本插件直接复用`vscode-novelforge`的核心代码，仅修改了以下内容：

- **publisher**: `novelforge-trae`（Trae marketplace要求）
- **displayName**: 带Trae标识
- **extension.ts**: 输出通道标注"(Trae)"

核心逻辑（sidebar-provider, api-client, config-manager）完全相同，无需修改。

## 功能

与VSCode版本完全一致：
- 📖 侧边栏集成NovelForge Studio UI
- 🚀 一键启动StudioServer
- 🔌 HTTP API桥接
- 📊 状态栏监控
- ⚙️ 配置管理

## 安装

### 在Trae中安装

Trae IDE目前支持VSCode扩展格式，安装方式：

1. 构建插件：
   ```bash
   cd plugins/trae-novelforge
   npm install
   npm run compile
   ```

2. 打包为VSIX：
   ```bash
   npx vsce package
   ```

3. 在Trae中安装：Extensions → Install from VSIX

### 从VSCode marketplace安装

如果Trae支持VSCode marketplace直接安装，可直接搜索"NovelForge Studio"。

## 前置条件

同VSCode版本：
- Java 17+
- novelforge-studio.jar（先构建项目）

## 配置

同VSCode版本，在Settings中搜索"NovelForge"。

## Trae特有扩展（规划）

未来可考虑的Trae专属功能：
- 与Trae的AI编码助手联动（写作建议→代码上下文）
- Trae AI Panel集成（在AI对话中调用NovelForge写作API）
- Trae项目模板（创建小说项目时自动配置workspace）

## 开发

```bash
npm install          # 安装依赖
npm run compile      # 生产构建
npm run watch        # 开发监听
```

## 与vscode-novelforge的关系

```
vscode-novelforge/          ← 核心实现
  src/extension.ts
  src/sidebar-provider.ts
  src/api-client.ts
  src/config-manager.ts

trae-novelforge/             ← 复用核心，适配Trae
  src/extension.ts           ← 微调（publisher, 输出通道）
  src/sidebar-provider.ts    ← 直接复制
  src/api-client.ts          ← 直接复制
  src/config-manager.ts      ← 直接复制
```

## License

MIT
