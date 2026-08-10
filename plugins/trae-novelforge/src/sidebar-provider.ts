/**
 * NovelForge Sidebar WebView Provider
 *
 * Embeds the NovelForge Studio UI inside the VSCode sidebar.
 * The StudioServer serves the HTML/JS/CSS — we proxy it into the webview.
 */
import * as vscode from "vscode";
import { StudioApiClient } from "./api-client";
import { ConfigManager } from "./config-manager";

export class NovelForgeSidebarProvider implements vscode.WebviewViewProvider {
  public static readonly viewType = "novelforge-studio";

  private _view?: vscode.WebviewView;
  private _disposables: vscode.Disposable[] = [];

  constructor(
    private readonly _extensionUri: vscode.Uri,
    private readonly _apiClient: StudioApiClient,
    private readonly _configManager: ConfigManager,
    private readonly _outputChannel: vscode.OutputChannel
  ) {}

  // ─────── WebviewViewProvider ───────

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

    this._renderStudio();
  }

  // ─────── Rendering ───────

  private async _renderStudio() {
    if (!this._view) return;

    const port = this._configManager.get<number>("serverPort");
    const running = await this._apiClient.isServerRunning();

    if (running) {
      // Proxy StudioServer frontend into webview via iframe approach
      // VSCode webviews can't directly load external URLs, so we inject
      // an iframe that communicates via postMessage bridge
      this._view.webview.html = this._getStudioHtml(port, this._apiClient.getAuthToken());
    } else {
      this._view.webview.html = this._getOfflineHtml();
    }
  }

  /**
   * Generate HTML that embeds Studio via an iframe.
   * Note: VSCode webviews restrict iframe loading of localhost URLs by default.
   * We use a postMessage bridge pattern instead — the webview JS fetches
   * the Studio HTML from the server and renders it inline.
   */
  private _getStudioHtml(port: number, token: string): string {
    const nonce = getNonce();
    return /*html*/ `
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="http-equiv" Content-Security-Policy"
        content="default-src 'self' 'nonce-${nonce}';
                 connect-src http://localhost:${port} http://localhost:${port}/api/;
                 img-src http://localhost:${port};
                 style-src 'self' 'unsafe-inline' http://localhost:${port};
                 script-src 'self' 'unsafe-inline' 'nonce-${nonce}' http://localhost:${port};
                 font-src http://localhost:${port};
                 frame-src http://localhost:${port};
                 child-src http://localhost:${port};">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>NovelForge Studio</title>
  <style nonce="${nonce}">
    body { margin: 0; padding: 0; overflow: hidden; height: 100vh; background: #1e1e2e; }
    #studio-frame { width: 100%; height: 100%; border: none; }
    #loading { display: flex; align-items: center; justify-content: center; height: 100vh;
               color: #ccc; font-family: sans-serif; }
    #loading .spinner { animation: spin 1s linear infinite; border: 3px solid #444;
                        border-top-color: #ff6b35; border-radius: 50%; width: 24px; height: 24px;
                        margin-right: 12px; }
    @keyframes spin { to { transform: rotate(360deg); } }
    #offline { display: flex; flex-direction: column; align-items: center; justify-content: center;
               height: 100vh; color: #ccc; font-family: sans-serif; text-align: center; }
    #offline button { margin-top: 16px; padding: 8px 24px; background: #ff6b35; color: #fff;
                      border: none; border-radius: 4px; cursor: pointer; font-size: 14px; }
    #offline button:hover { background: #e55a2b; }
  </style>
</head>
<body>
  <div id="loading">
    <div class="spinner"></div>
    <span>Loading NovelForge Studio...</span>
  </div>
  <div id="offline" style="display:none">
    <h2>🔥 NovelForge Studio</h2>
    <p>StudioServer not running</p>
    <button id="start-btn">Start Server</button>
  </div>

  <script nonce="${nonce}">
    const PORT = ${port};
    const vscodeApi = acquireVsCodeApi();

    // Try to load Studio content from local server
    async function loadStudio() {
      try {
        const resp = await fetch('http://localhost:' + PORT + '/');
        if (resp.ok) {
          const html = await resp.text();
          // We need to load CSS and JS resources too
          // Instead of injecting raw HTML (CSP issues), use iframe approach
          document.getElementById('loading').style.display = 'none';
          const iframe = document.createElement('iframe');
          iframe.id = 'studio-frame';
          iframe.src = 'http://localhost:' + PORT + '/?token=' + encodeURIComponent(TOKEN);
          document.body.appendChild(iframe);
        } else {
          showOffline();
        }
      } catch (e) {
        showOffline();
      }
    }

    function showOffline() {
      document.getElementById('loading').style.display = 'none';
      document.getElementById('offline').style.display = 'flex';
    }

    document.getElementById('start-btn')?.addEventListener('click', () => {
      vscodeApi.postMessage({ type: 'startServer' });
    });

    // Listen for messages from extension
    window.addEventListener('message', (event) => {
      const msg = event.data;
      if (msg.type === 'serverStarted') {
        loadStudio();
      } else if (msg.type === 'serverStopped') {
        showOffline();
      }
    });

    // Initial load
    loadStudio();
  </script>
</body>
</html>`;
  }

  private _getOfflineHtml(): string {
    const nonce = getNonce();
    return /*html*/ `
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="http-equiv" Content-Security-Policy"
        content="default-src 'self' 'nonce-${nonce}';">
  <style nonce="${nonce}">
    body { margin: 0; padding: 0; display: flex; flex-direction: column;
           align-items: center; justify-content: center; height: 100vh;
           background: #1e1e2e; color: #ccc; font-family: sans-serif; text-align: center; }
    h2 { font-size: 24px; }
    p { margin: 8px 0; }
    button { margin-top: 16px; padding: 8px 24px; background: #ff6b35; color: #fff;
             border: none; border-radius: 4px; cursor: pointer; font-size: 14px; }
    button:hover { background: #e55a2b; }
    .logo { font-size: 48px; margin-bottom: 8px; }
  </style>
</head>
<body>
  <div class="logo">🔥</div>
  <h2>NovelForge Studio</h2>
  <p>StudioServer not running</p>
  <p>Click below to start, or use the ▶ button in the panel title</p>
  <button id="start-btn">Start Server</button>

  <script nonce="${nonce}">
    const vscodeApi = acquireVsCodeApi();
    document.getElementById('start-btn').addEventListener('click', () => {
      vscodeApi.postMessage({ type: 'startServer' });
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
        // After start, re-render
        this._renderStudio();
        if (this._view) {
          this._view.webview.postMessage({ type: "serverStarted" });
        }
        break;
      case "stopServer":
        await vscode.commands.executeCommand("novelforge.stopServer");
        this._renderStudio();
        if (this._view) {
          this._view.webview.postMessage({ type: "serverStopped" });
        }
        break;
      case "refreshStudio":
        this._renderStudio();
        break;
      case "openInBrowser":
        vscode.commands.executeCommand("novelforge.openStudio");
        break;
      // Forward API requests from webview to StudioServer
      case "apiRequest":
        if (msg.method && msg.path) {
          const result = await this._apiClient.rawRequest(
            msg.method,
            msg.path,
            msg.body
          );
          this._view?.webview.postMessage({
            type: "apiResponse",
            requestId: msg.requestId,
            data: result,
          });
        }
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
