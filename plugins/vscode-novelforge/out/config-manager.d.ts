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
export declare class ConfigManager {
    private readonly _context;
    constructor(_context: vscode.ExtensionContext);
    /** Get a configuration value */
    get<T>(key: string): T;
    /** Update a configuration value */
    update(key: string, value: any, global?: boolean): Promise<void>;
    /** Get the StudioServer URL base */
    getServerUrl(): string;
    /** Get the API base URL */
    getApiUrl(): string;
    get serverPort(): number;
    get autoStart(): boolean;
    get javaPath(): string;
    get studioJarPath(): string;
    get serverTimeout(): number;
    /** Register a listener for configuration changes */
    onDidChange(callback: (e: vscode.ConfigurationChangeEvent) => void): vscode.Disposable;
}
//# sourceMappingURL=config-manager.d.ts.map