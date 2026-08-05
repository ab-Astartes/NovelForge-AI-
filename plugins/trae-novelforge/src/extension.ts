/**
 * NovelForge Studio — Trae IDE Extension Entry Point
 *
 * Trae is ByteDance's IDE based on VSCode extension architecture.
 * This extension is essentially the same as vscode-novelforge with
 * Trae-specific publisher and compatibility adaptations.
 *
 * Key differences from VSCode version:
 * - publisher: "novelforge-trae" (Trae marketplace requirement)
 * - Trae may have additional AI-assisted coding APIs; future integration
 * - Trae uses its own marketplace for extension distribution
 */
import * as vscode from "vscode";
import { NovelForgeSidebarProvider } from "./sidebar-provider";
import { StudioApiClient } from "./api-client";
import { ConfigManager } from "./config-manager";

let apiClient: StudioApiClient;
let configManager: ConfigManager;
let serverProcess: ReturnType<typeof import("child_process").spawn> | null = null;
let outputChannel: vscode.OutputChannel;
let statusBarItem: vscode.StatusBarItem;

export async function activate(context: vscode.ExtensionContext) {
  outputChannel = vscode.window.createOutputChannel("NovelForge (Trae)");
  outputChannel.appendLine("[NovelForge] Trae extension activated");

  configManager = new ConfigManager(context);
  apiClient = new StudioApiClient(configManager, outputChannel);

  // Status bar item
  statusBarItem = vscode.window.createStatusBarItem(
    vscode.StatusBarAlignment.Right,
    100
  );
  statusBarItem.command = "novelforge.showServerStatus";
  updateStatusBar("stopped");
  statusBarItem.show();
  context.subscriptions.push(statusBarItem);

  // Register sidebar webview provider
  const sidebarProvider = new NovelForgeSidebarProvider(
    context.extensionUri,
    apiClient,
    configManager,
    outputChannel
  );
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(
      "novelforge-studio",
      sidebarProvider,
      { webviewOptions: { retainContextWhenHidden: true } }
    )
  );

  // Commands
  context.subscriptions.push(
    vscode.commands.registerCommand("novelforge.startServer", () => startServer()),
    vscode.commands.registerCommand("novelforge.stopServer", () => stopServer()),
    vscode.commands.registerCommand("novelforge.restartServer", async () => {
      await stopServer();
      await startServer();
    }),
    vscode.commands.registerCommand("novelforge.openStudio", () => openStudio()),
    vscode.commands.registerCommand("novelforge.showServerStatus", () => showStatus())
  );

  // Auto-start if configured
  const autoStart = configManager.get<boolean>("autoStart");
  if (autoStart) {
    const running = await apiClient.isServerRunning();
    if (!running) {
      outputChannel.appendLine("[NovelForge] Auto-starting StudioServer...");
      await startServer();
    } else {
      outputChannel.appendLine("[NovelForge] StudioServer already running");
      updateStatusBar("running");
    }
  }
}

export function deactivate() {
  if (serverProcess) {
    serverProcess.kill();
    serverProcess = null;
  }
  if (outputChannel) {
    outputChannel.dispose();
  }
}

// ─── Server lifecycle (identical to VSCode version) ───

async function startServer(): Promise<void> {
  const port = configManager.get<number>("serverPort");
  const running = await apiClient.isServerRunning();
  if (running) {
    vscode.window.showInformationMessage(`NovelForge StudioServer already running on port ${port}`);
    updateStatusBar("running");
    return;
  }

  const javaPath = configManager.get<string>("javaPath") || "java";
  const jarPath = configManager.get<string>("studioJarPath");

  if (!jarPath) {
    vscode.window.showErrorMessage(
      "novelforge-studio.jar not found. Set novelforge.studioJarPath or build the project first."
    );
    return;
  }

  outputChannel.appendLine(`[NovelForge] Starting: ${javaPath} -jar ${jarPath} --port ${port}`);

  const { spawn } = require("child_process") as typeof import("child_process");
  serverProcess = spawn(javaPath, ["-jar", jarPath, "--port", String(port)], {
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });

  serverProcess.stdout?.on("data", (data: Buffer) => outputChannel.append(data.toString()));
  serverProcess.stderr?.on("data", (data: Buffer) => outputChannel.append("[stderr] " + data.toString()));
  serverProcess.on("exit", (code: number) => {
    outputChannel.appendLine(`[NovelForge] Server exited (${code})`);
    updateStatusBar("stopped");
    serverProcess = null;
  });

  const timeout = configManager.get<number>("serverTimeout");
  const started = await waitForServer(port, timeout);
  if (started) {
    vscode.window.showInformationMessage(`NovelForge StudioServer started on port ${port}`);
    updateStatusBar("running");
  } else {
    vscode.window.showWarningMessage("StudioServer startup timed out.");
    updateStatusBar("starting");
  }
}

function stopServer(): Promise<void> {
  if (serverProcess) {
    serverProcess.kill();
    serverProcess = null;
    updateStatusBar("stopped");
    vscode.window.showInformationMessage("NovelForge StudioServer stopped");
  }
  return Promise.resolve();
}

function openStudio(): void {
  const port = configManager.get<number>("serverPort");
  vscode.env.openExternal(vscode.Uri.parse(`http://localhost:${port}`));
}

async function showStatus(): Promise<void> {
  const port = configManager.get<number>("serverPort");
  const running = await apiClient.isServerRunning();
  const status = running ? "✅ Running" : "❌ Stopped";
  vscode.window.showInformationMessage(`NovelForge StudioServer: ${status} on port ${port}`);
}

async function waitForServer(port: number, timeoutMs: number): Promise<boolean> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (await apiClient.isServerRunning()) return true;
    await new Promise((r) => setTimeout(r, 1000));
  }
  return false;
}

function updateStatusBar(state: "stopped" | "starting" | "running"): void {
  const port = configManager.get<number>("serverPort");
  switch (state) {
    case "stopped":
      statusBarItem.text = "$(circle-slash) NovelForge";
      statusBarItem.tooltip = `StudioServer stopped (port ${port})`;
      break;
    case "starting":
      statusBarItem.text = "$(sync~spin) NovelForge";
      statusBarItem.tooltip = `StudioServer starting... (port ${port})`;
      break;
    case "running":
      statusBarItem.text = "$(check) NovelForge";
      statusBarItem.tooltip = `StudioServer running (port ${port})`;
      break;
  }
}
