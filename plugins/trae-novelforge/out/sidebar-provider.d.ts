/**
 * NovelForge Sidebar WebView Provider
 *
 * Embeds the NovelForge Studio UI inside the VSCode sidebar.
 * The StudioServer serves the HTML/JS/CSS — we proxy it into the webview.
 */
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
    /**
     * Generate HTML that embeds Studio via an iframe.
     * Note: VSCode webviews restrict iframe loading of localhost URLs by default.
     * We use a postMessage bridge pattern instead — the webview JS fetches
     * the Studio HTML from the server and renders it inline.
     */
    private _getStudioHtml;
    private _getOfflineHtml;
    private _handleMessage;
}
//# sourceMappingURL=sidebar-provider.d.ts.map