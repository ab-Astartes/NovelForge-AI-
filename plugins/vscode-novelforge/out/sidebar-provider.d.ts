import * as vscode from "vscode";
import { StudioApiClient } from "./api-client";
import { ConfigManager } from "./config-manager";
export declare class NovelForgeSidebarProvider implements vscode.WebviewViewProvider {
    private readonly _extensionUri;
    private readonly _apiClient;
    private readonly _configManager;
    private readonly _outputChannel;
    static readonly viewType = "novelforge-studio";
    private _view?;
    private _disposables;
    constructor(_extensionUri: vscode.Uri, _apiClient: StudioApiClient, _configManager: ConfigManager, _outputChannel: vscode.OutputChannel);
    resolveWebviewView(webviewView: vscode.WebviewView, _context: vscode.WebviewViewResolveContext, _token: vscode.CancellationToken): void;
    private _renderStudio;
    /** Fetch the Studio index.html from StudioServer */
    private _fetchStudioHtml;
    /**
     * Wrap Studio HTML for VSCode webview.
     * - Replace relative URLs with absolute localhost URLs
     * - Add postMessage bridge for API calls (inject auth token)
     * - Set proper CSP for VSCode webview
     */
    private _wrapStudioHtml;
    private _getOfflineHtml;
    private _handleMessage;
}
//# sourceMappingURL=sidebar-provider.d.ts.map