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
export declare class NovelForgeSidebarProvider implements vscode.WebviewViewProvider {
    private readonly _extensionUri;
    private readonly _apiClient;
    private readonly _configManager;
    private readonly _outputChannel;
    static readonly viewType = "novelforge-studio";
    private _view?;
    constructor(_extensionUri: vscode.Uri, _apiClient: StudioApiClient, _configManager: ConfigManager, _outputChannel: vscode.OutputChannel);
    resolveWebviewView(webviewView: vscode.WebviewView, _context: vscode.WebviewViewResolveContext, _token: vscode.CancellationToken): void;
    private _render;
    private _getControlPanelHtml;
    private _handleMessage;
}
//# sourceMappingURL=sidebar-provider.d.ts.map