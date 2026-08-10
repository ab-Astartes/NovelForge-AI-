/**
 * NovelForge Sidebar WebView Provider
 *
 * Embeds the NovelForge Studio UI inside the VSCode sidebar.
 * Strategy: Fetch Studio HTML from StudioServer, inject into webview,
 * and bridge API requests via postMessage (avoids iframe CSP issues).
 */
import * as http from "http";
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
      // Fetch Studio HTML from server and inject into webview
      const studioHtml = await this._fetchStudioHtml(port);
      if (studioHtml) {
        this._view.webview.html = this._wrapStudioHtml(studioHtml, port);
      } else {
        this._view.webview.html = this._getOfflineHtml();
      }
    } else {
      this._view.webview.html = this._getOfflineHtml();
    }
  }

  /** Fetch the Studio index.html from StudioServer */
  private _fetchStudioHtml(port: number): Promise<string | null> {
    return new Promise((resolve) => {
      const token = this._apiClient.getAuthToken();
      const options: http.RequestOptions = {
        hostname: "localhost",
        port,
        path: `/?token=${encodeURIComponent(token)}`,
        method: "GET",
        headers: {
          "Accept": "text/html",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        timeout: 10_000,
      };

      const req = http.request(options, (res) => {
        let data = "";
        res.on("data", (chunk) => (data += chunk.toString()));
        res.on("end", () => {
          if (res.statusCode === 200) {
            resolve(data);
          } else {
            this._outputChannel.appendLine(`[Studio] Failed to fetch HTML: ${res.statusCode}`);
            resolve(null);
          }
        });
      });

      req.on("error", (err) => {
        this._outputChannel.appendLine(`[Studio] Fetch HTML error: ${err.message}`);
        resolve(null);
      });

      req.on("timeout", () => {
        req.destroy();
        resolve(null);
      });

      req.end();
    });
  }

  /**
   * Wrap Studio HTML for VSCode webview.
   * - Replace relative URLs with absolute localhost URLs
   * - Add postMessage bridge for API calls (inject auth token)
   * - Set proper CSP for VSCode webview
   */
  private _wrapStudioHtml(html: string, port: number): string {
    const nonce = getNonce();
    const token = this._apiClient.getAuthToken();

    // Replace relative resource URLs with absolute URLs
    let wrapped = html
      .replace(/href="\/style\.css"/g, `href="http://localhost:${port}/style.css?token=${token}"`)
      .replace(/src="\/app\.js"/g, `src="http://localhost:${port}/app.js?token=${token}"`)
      .replace(/href="https:\/\/fonts\.googleapis\.com/g, `href="https://fonts.googleapis.com`);

    // Inject a bridge script that intercepts fetch calls and adds auth token
    const bridgeScript = `
<script nonce="${nonce}">
(function() {
  const TOKEN = '${token}';
  const PORT = ${port};

  // Override fetch to add auth token
  const originalFetch = window.fetch;
  window.fetch = function(url, options) {
    options = options || {};
    options.headers = options.headers || {};

    // Add auth header for localhost requests
    if (typeof url === 'string' && url.includes('localhost')) {
      options.headers['Authorization'] = 'Bearer ' + TOKEN;
      // Also add token param if not already present
      if (!url.includes('token=')) {
        const sep = url.includes('?') ? '&' : '?';
        url = url + sep + 'token=' + encodeURIComponent(TOKEN);
      }
    }

    return originalFetch.call(this, url, options);
  };

  // Override XMLHttpRequest to add auth token
  const originalOpen = XMLHttpRequest.prototype.open;
  const originalSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url) {
    this._url = url;
    if (typeof url === 'string' && url.includes('localhost') && !url.includes('token=')) {
      const sep = url.includes('?') ? '&' : '?';
      url = url + sep + 'token=' + encodeURIComponent(TOKEN);
    }
    return originalOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function() {
    if (this._url && this._url.includes('localhost')) {
      this.setRequestHeader('Authorization', 'Bearer ' + TOKEN);
    }
    return originalSend.apply(this, arguments);
  };

  // Bridge to VSCode extension for API calls
  const vscodeApi = acquireVsCodeApi();
  window._vscodeBridge = {
    postMessage: function(msg) {
      vscodeApi.postMessage(msg);
    }
  };
})();
</script>`;

    // Insert bridge script before </head>
    if (wrapped.includes("</head>")) {
      wrapped = wrapped.replace("</head>", bridgeScript + "\n</head>");
    } else {
      wrapped = bridgeScript + wrapped;
    }

    // Add VSCode webview CSP meta tag (replace existing if any)
    const cspMeta = `<meta http-equiv="Content-Security-Policy" content="default-src 'self' 'nonce-${nonce}' http://localhost:${port} https://fonts.googleapis.com https://fonts.gstatic.com; connect-src http://localhost:${port} http://localhost:${port}/api/; img-src http://localhost:${port} data:; style-src 'self' 'unsafe-inline' http://localhost:${port} https://fonts.googleapis.com; script-src 'self' 'unsafe-inline' 'nonce-${nonce}' http://localhost:${port}; font-src http://localhost:${port} https://fonts.gstatic.com; frame-src http://localhost:${port};">`;

    // Remove existing CSP meta tags
    wrapped = wrapped.replace(/<meta[^>]*Content-Security-Policy[^>]*>/gi, "");
    // Insert our CSP
    if (wrapped.includes("<head>")) {
      wrapped = wrapped.replace("<head>", "<head>\n" + cspMeta);
    }

    return wrapped;
  }

  private _getOfflineHtml(): string {
    const nonce = getNonce();
    return /*html*/ `
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'self' 'nonce-${nonce}';">
  <style nonce="${nonce}">
    body { margin: 0; padding: 0; display: flex; flex-direction: column;
           align-items: center; justify-content: center; height: 100vh;
           background: #0d0d0d; color: #c0392b; font-family: 'Noto Serif SC', serif; text-align: center; }
    h2 { font-size: 24px; color: #e0e0e0; }
    p { margin: 8px 0; color: #999; }
    button { margin-top: 16px; padding: 8px 24px; background: #c0392b; color: #fff;
             border: none; border-radius: 4px; cursor: pointer; font-size: 14px; }
    button:hover { background: #a93226; }
    .logo { font-size: 48px; margin-bottom: 8px; }
  </style>
</head>
<body>
  <div class="logo">🔥</div>
  <h2>墨阁 · NovelForge Studio</h2>
  <p>StudioServer 未运行</p>
  <p>点击下方启动，或使用面板标题栏 ▶ 按钮</p>
  <button id="start-btn">启动服务器</button>

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
        // Wait a bit for server to start, then re-render
        await new Promise((resolve) => setTimeout(resolve, 3000));
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
