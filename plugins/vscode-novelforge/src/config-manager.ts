/**
 * NovelForge Configuration Manager
 *
 * Bridges VSCode workspace configuration (settings.json) with extension runtime.
 * Configuration keys match `contributes.configuration` in package.json.
 *
 * Note: StudioServer also has its own /api/config endpoint for pipeline
 * configuration. This ConfigManager only handles the IDE-side settings
 * (port, autoStart, javaPath, etc.) — not the writing pipeline config.
 */
import * as vscode from "vscode";

const SECTION = "novelforge";

export class ConfigManager {
  constructor(private readonly _context: vscode.ExtensionContext) {}

  /** Get a configuration value */
  get<T>(key: string): T {
    const config = vscode.workspace.getConfiguration(SECTION);
    return config.get<T>(key)!;
  }

  /** Update a configuration value */
  async update(key: string, value: any, global?: boolean): Promise<void> {
    const config = vscode.workspace.getConfiguration(SECTION);
    const target = global
      ? vscode.ConfigurationTarget.Global
      : vscode.ConfigurationTarget.Workspace;
    await config.update(key, value, target);
  }

  /** Get the StudioServer URL base */
  getServerUrl(): string {
    const port = this.get<number>("serverPort");
    return `http://localhost:${port}`;
  }

  /** Get the API base URL */
  getApiUrl(): string {
    return this.getServerUrl() + "/api";
  }

  // ─────── Convenience getters ───────

  get serverPort(): number {
    return this.get<number>("serverPort");
  }

  get autoStart(): boolean {
    return this.get<boolean>("autoStart");
  }

  get javaPath(): string {
    return this.get<string>("javaPath");
  }

  get studioJarPath(): string {
    return this.get<string>("studioJarPath");
  }

  get serverTimeout(): number {
    return this.get<number>("serverTimeout");
  }

  // ─────── Watch for config changes ───────

  /** Register a listener for configuration changes */
  onDidChange(callback: (e: vscode.ConfigurationChangeEvent) => void): vscode.Disposable {
    return vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration(SECTION)) {
        callback(e);
      }
    });
  }
}
