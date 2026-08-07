/**
 * NovelForge Studio — VSCode Extension Entry Point
 *
 * Lifecycle:
 * - activate: register SidebarProvider, optionally auto-start StudioServer
 * - deactivate: stop StudioServer child process (if we started it)
 */
import * as vscode from "vscode";
import * as path from "path";
import * as fs from "fs";
import * as child_process from "child_process";
import { NovelForgeSidebarProvider } from "./sidebar-provider";
import { StudioApiClient } from "./api-client";
import { ConfigManager } from "./config-manager";

let apiClient: StudioApiClient;
let configManager: ConfigManager;
let serverProcess: child_process.ChildProcess | null = null;
let outputChannel: vscode.OutputChannel;
let statusBarItem: vscode.StatusBarItem;

export async function activate(context: vscode.ExtensionContext) {
  outputChannel = vscode.window.createOutputChannel("NovelForge");
  outputChannel.appendLine("[NovelForge] Extension activated");

  configManager = new ConfigManager(context);
  apiClient = new StudioApiClient(configManager, outputChannel);

  // Status bar item — shows server state
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
      outputChannel.appendLine("[NovelForge] StudioServer already running on port " + configManager.get<number>("serverPort"));
      updateStatusBar("running");
    }
  }
}

export function deactivate() {
  if (serverProcess) {
    outputChannel.appendLine("[NovelForge] Stopping StudioServer on deactivate");
    serverProcess.kill();
    serverProcess = null;
  }
  if (outputChannel) {
    outputChannel.dispose();
  }
}

// ─────────── Server Lifecycle ───────────

async function startServer(): Promise<void> {
  const port = configManager.get<number>("serverPort");
  const running = await apiClient.isServerRunning();
  if (running) {
    vscode.window.showInformationMessage(`NovelForge StudioServer already running on port ${port}`);
    updateStatusBar("running");
    return;
  }

  const javaPath = configManager.get<string>("javaPath") || findJava();
  if (!javaPath) {
    vscode.window.showErrorMessage(
      "Java 17+ not found. Set novelforge.javaPath or install Java 17+ and configure JAVA_HOME."
    );
    return;
  }

  const jarPath = configManager.get<string>("studioJarPath") || await findStudioJar();
  if (!jarPath) {
    vscode.window.showErrorMessage(
      "novelforge-studio.jar not found. Set novelforge.studioJarPath or build the project first (mvn package)."
    );
    return;
  }

  outputChannel.appendLine(`[NovelForge] Starting StudioServer: ${javaPath} -jar ${jarPath} --port ${port}`);
  outputChannel.show(true);

  serverProcess = child_process.spawn(javaPath, ["-jar", jarPath, "--port", String(port)], {
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });

  serverProcess.stdout?.on("data", (data: Buffer) => {
    outputChannel.append(data.toString());
  });
  serverProcess.stderr?.on("data", (data: Buffer) => {
    outputChannel.append("[stderr] " + data.toString());
  });
  serverProcess.on("exit", (code: number | null) => {
    outputChannel.appendLine(`[NovelForge] StudioServer exited with code ${code}`);
    updateStatusBar("stopped");
    serverProcess = null;
  });

  // Wait for server to become responsive
  const timeout = configManager.get<number>("serverTimeout");
  const started = await waitForServer(port, timeout);
  if (started) {
    vscode.window.showInformationMessage(`NovelForge StudioServer started on port ${port}`);
    updateStatusBar("running");
  } else {
    vscode.window.showWarningMessage("StudioServer startup timed out. Check output channel for details.");
    updateStatusBar("starting");
  }
}

function stopServer(): Promise<void> {
  if (serverProcess) {
    serverProcess.kill();
    serverProcess = null;
    updateStatusBar("stopped");
    outputChannel.appendLine("[NovelForge] StudioServer stopped (child process killed)");
    vscode.window.showInformationMessage("NovelForge StudioServer stopped");
  } else {
    vscode.window.showInformationMessage("No managed StudioServer process to stop. If started externally, stop it manually.");
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
  const managed = serverProcess ? "Managed by extension" : "External process";
  vscode.window.showInformationMessage(
    `NovelForge StudioServer: ${status} on port ${port}\n${managed}`
  );
}

// ─────────── Helpers ───────────

function findJava(): string | null {
  // 1. Check JAVA_HOME
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const javaBin = path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java");
    if (fs.existsSync(javaBin)) {
      return javaBin;
    }
  }
  // 2. Check JDK_HOME
  const jdkHome = process.env.JDK_HOME;
  if (jdkHome) {
    const javaBin = path.join(jdkHome, "bin", process.platform === "win32" ? "java.exe" : "java");
    if (fs.existsSync(javaBin)) {
      return javaBin;
    }
  }
  // 3. Try PATH resolution
  try {
    const result = child_process.execSync(
      process.platform === "win32" ? "where java.exe" : "which java",
      { encoding: "utf-8", timeout: 5000 }
    ).trim().split(/\r?\n/)[0];
    if (result && fs.existsSync(result)) {
      return result;
    }
  } catch { /* not in PATH */ }
  return null;
}

async function findStudioJar(): Promise<string | null> {
  const homeDir = process.env.USERPROFILE || process.env.HOME || "";
  const searchPaths = [
    // Project build output
    path.join(homeDir, "Desktop", "ab", "demo", "NovelForge", "packages", "novelforge-studio", "target", "novelforge-studio.jar"),
    // dist-app directory
    path.join(homeDir, "Desktop", "ab", "demo", "NovelForge", "dist-app", "NovelForgeStudio", "app", "novelforge-studio.jar"),
    // Common locations
    path.join(homeDir, "NovelForge", "novelforge-studio.jar"),
    path.join(homeDir, ".novelforge", "novelforge-studio.jar"),
  ];
  for (const p of searchPaths) {
    try {
      if (fs.existsSync(p)) return p;
    } catch { /* skip */ }
  }
  return null;
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
      statusBarItem.color = undefined;
      break;
    case "starting":
      statusBarItem.text = "$(sync~spin) NovelForge";
      statusBarItem.tooltip = `StudioServer starting... (port ${port})`;
      statusBarItem.color = new vscode.ThemeColor("notificationsWarningIcon.foreground");
      break;
    case "running":
      statusBarItem.text = "$(check) NovelForge";
      statusBarItem.tooltip = `StudioServer running (port ${port})`;
      statusBarItem.color = new vscode.ThemeColor("notificationsSuccessIcon.foreground");
      break;
  }
}
