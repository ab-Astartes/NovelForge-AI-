/**
 * NovelForge Sidebar WebView Provider
 *
 * Shows a compact control panel in the VSCode sidebar.
 * Full Studio UI opens in VSCode's built-in Simple Browser tab.
 * This avoids all CSP/iframe/script-loading issues with webviews.
 */
import * as vscode from "vscode";
import { StudioApiClient } from "./api-client";
import { ConfigManager } from "./config-manager";

export class NovelForgeSidebarProvider implements vscode.WebviewViewProvider {
  public static readonly viewType = "novelforge-studio";

  private _view?: vscode.WebviewView;

  constructor(
    private readonly _extensionUri: vscode.Uri,
    private readonly _apiClient: StudioApiClient,
    private readonly _configManager: ConfigManager,
    private readonly _outputChannel: vscode.OutputChannel
  ) {}

  public resolveWebviewView(
    webviewView: vscode.WebviewView,
    _context: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken
  ) {
    this._view = webviewView;

    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [this._extensionUri],
    };

    webviewView.webview.onDidReceiveMessage((msg) => this._handleMessage(msg));

    this._render();
  }

  public refresh() {
    this._render();
  }

  private async _render() {
    if (!this._view) return;

    const running = await this._apiClient.isServerRunning();
    const version = running ? await this._apiClient.getVersion() : null;
    this._view.webview.html = this._getControlPanelHtml(running, version);
  }

  private _getControlPanelHtml(running: boolean, version: string | null): string {
    const nonce = getNonce();
    const port = this._configManager.get<number>("serverPort");
    const token = this._apiClient.getAuthToken();

    if (running) {
      return /*html*/ `
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
  <style>
    :root { --red: #c0392b; --bg: #0d0d0d; --card: #1a1a1a; --border: #333; --text: #e0e0e0; --muted: #888; }
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { font-family: 'Segoe UI', system-ui, sans-serif; background: var(--bg); color: var(--text); padding: 12px; font-size: 13px; }
    .header { text-align: center; margin-bottom: 16px; }
    .header .logo { font-size: 32px; margin-bottom: 4px; }
    .header h2 { font-size: 16px; color: var(--text); font-weight: 600; }
    .header .ver { font-size: 11px; color: var(--muted); }
    .status { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: var(--card); border-radius: 6px; margin-bottom: 12px; border: 1px solid var(--border); }
    .status .dot { width: 8px; height: 8px; border-radius: 50%; background: #27ae60; flex-shrink: 0; }
    .status .label { color: var(--muted); font-size: 12px; }
    .btn { display: block; width: 100%; padding: 10px 16px; border: none; border-radius: 6px; cursor: pointer; font-size: 13px; font-weight: 500; text-align: center; margin-bottom: 8px; transition: opacity 0.2s; }
    .btn:hover { opacity: 0.85; }
    .btn-primary { background: var(--red); color: #fff; }
    .btn-secondary { background: var(--card); color: var(--text); border: 1px solid var(--border); }
    .btn-danger { background: transparent; color: #e74c3c; border: 1px solid #e74c3c; }
    .info { margin-top: 16px; padding: 10px; background: var(--card); border-radius: 6px; border: 1px solid var(--border); font-size: 12px; color: var(--muted); line-height: 1.6; }
    .info code { color: var(--red); background: #1a0a0a; padding: 1px 4px; border-radius: 3px; font-size: 11px; }
  </style>
</head>
<body>
  <div class="header">
    <div class="logo">🔥</div>
    <h2>墨阁 · NovelForge</h2>
    <div class="ver">v${version || '0.4.4'}</div>
  </div>

  <div class="status">
    <div class="dot"></div>
    <div class="label">服务器运行中 · 端口 ${port}</div>
  </div>

  <button class="btn btn-primary" id="open-btn">打开 Studio 工作台</button>
  <button class="btn btn-secondary" id="browser-btn">在浏览器中打开</button>
  <button class="btn btn-danger" id="stop-btn">停止服务器</button>

  <div class="info">
    访问地址：<code>http://localhost:${port}</code><br>
    认证令牌：<code>${token}</code><br>
    <br>
    💡 点击「打开 Studio 工作台」在 VSCode 内编辑
  </div>

  <script nonce="${nonce}">
    const vscodeApi = acquireVsCodeApi();
    document.getElementById('open-btn').addEventListener('click', () => {
      vscodeApi.postMessage({ type: 'openStudio' });
    });
    document.getElementById('browser-btn').addEventListener('click', () => {
      vscodeApi.postMessage({ type: 'openInBrowser' });
    });
    document.getElementById('stop-btn').addEventListener('click', () => {
      vscodeApi.postMessage({ type: 'stopServer' });
    });
  </script>
</body>
</html>`;
    }

    // Offline state
    return /*html*/ `
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
  <style>
    :root { --red: #c0392b; --bg: #0d0d0d; --card: #1a1a1a; --border: #333; --text: #e0e0e0; --muted: #888; }
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { font-family: 'Segoe UI', system-ui, sans-serif; background: var(--bg); color: var(--text); padding: 12px; font-size: 13px; }
    .header { text-align: center; margin-bottom: 20px; }
    .header .logo { font-size: 40px; margin-bottom: 8px; }
    .header h2 { font-size: 16px; color: var(--text); font-weight: 600; }
    .status { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: var(--card); border-radius: 6px; margin-bottom: 16px; border: 1px solid var(--border); }
    .status .dot { width: 8px; height: 8px; border-radius: 50%; background: #e74c3c; flex-shrink: 0; }
    .status .label { color: var(--muted); font-size: 12px; }
    .btn { display: block; width: 100%; padding: 10px 16px; border: none; border-radius: 6px; cursor: pointer; font-size: 13px; font-weight: 500; text-align: center; margin-bottom: 8px; transition: opacity 0.2s; }
    .btn:hover { opacity: 0.85; }
    .btn-primary { background: var(--red); color: #fff; }
    .btn-secondary { background: var(--card); color: var(--text); border: 1px solid var(--border); }
    .hint { margin-top: 16px; padding: 10px; background: var(--card); border-radius: 6px; border: 1px solid var(--border); font-size: 12px; color: var(--muted); line-height: 1.6; }
    .hint code { color: var(--red); background: #1a0a0a; padding: 1px 4px; border-radius: 3px; font-size: 11px; }
  </style>
</head>
<body>
  <div class="header">
    <div class="logo">🔥</div>
    <h2>墨阁 · NovelForge</h2>
  </div>

  <div class="status">
    <div class="dot"></div>
    <div class="label">服务器未运行</div>
  </div>

  <button class="btn btn-primary" id="start-btn">启动服务器</button>
  <button class="btn btn-secondary" id="settings-btn">配置</button>

  <div class="hint">
    端口：<code>${port}</code><br>
    <br>
    💡 首次使用请先配置 Java 路径和 Studio JAR 路径
  </div>

  <script nonce="${nonce}">
    const vscodeApi = acquireVsCodeApi();
    document.getElementById('start-btn').addEventListener('click', () => {
      vscodeApi.postMessage({ type: 'startServer' });
    });
    document.getElementById('settings-btn').addEventListener('click', () => {
      vscodeApi.postMessage({ type: 'openSettings' });
    });
  </script>
</body>
</html>`;
  }

  // ─────── Message Bridge ───────

  private async _handleMessage(msg: any) {
    switch (msg.type) {
      case "startServer":
        await vscode.commands.executeCommand("novelforge.startServer");
        // Wait for server to start, then refresh
        await new Promise((r) => setTimeout(r, 3000));
        this._render();
        break;
      case "stopServer":
        await vscode.commands.executeCommand("novelforge.stopServer");
        this._render();
        break;
      case "openStudio": {
        const port = this._configManager.get<number>("serverPort");
        const token = this._apiClient.getAuthToken();
        const url = `http://localhost:${port}/?token=${encodeURIComponent(token)}`;
        // Try Simple Browser first (built-in in VSCode 1.86+)
        try {
          await vscode.commands.executeCommand("simpleBrowser.show", url);
        } catch {
          // Fallback: open in VSCode's webview editor
          try {
            await vscode.commands.executeCommand("vscode.open", vscode.Uri.parse(url));
          } catch {
            // Last fallback: system browser
            vscode.env.openExternal(vscode.Uri.parse(url));
          }
        }
        break;
      }
      case "openInBrowser": {
        const port = this._configManager.get<number>("serverPort");
        const token = this._apiClient.getAuthToken();
        const url = `http://localhost:${port}/?token=${encodeURIComponent(token)}`;
        vscode.env.openExternal(vscode.Uri.parse(url));
        break;
      }
      case "openSettings":
        vscode.commands.executeCommand("workbench.action.openSettings", "novelforge");
        break;
      case "refreshStudio":
        this._render();
        break;
    }
  }
}

function getNonce(): string {
  let text = "";
  const possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  for (let i = 0; i < 32; i++) {
    text += possible.charAt(Math.floor(Math.random() * possible.length));
  }
  return text;
}
