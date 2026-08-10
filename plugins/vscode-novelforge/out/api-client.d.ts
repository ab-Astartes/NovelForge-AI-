import * as vscode from "vscode";
import { ConfigManager } from "./config-manager";
export interface ApiResponse {
    ok: boolean;
    status: number;
    data: any;
}
export declare class StudioApiClient {
    private readonly _config;
    private readonly _outputChannel;
    /** Auth token extracted from StudioServer stdout */
    private _authToken;
    constructor(_config: ConfigManager, _outputChannel: vscode.OutputChannel);
    /** Set the auth token (extracted from server stdout) */
    setAuthToken(token: string): void;
    /** Get the current auth token */
    getAuthToken(): string;
    /** Check if StudioServer is reachable */
    isServerRunning(): Promise<boolean>;
    /** Generic API request */
    request(method: string, path: string, body?: any): Promise<ApiResponse>;
    /** Raw request — used by webview message bridge */
    rawRequest(method: string, path: string, body?: any): Promise<ApiResponse>;
    /** Get server version */
    getVersion(): Promise<string | null>;
    /** List all books */
    listBooks(): Promise<any[]>;
    /** Get book info */
    getBookInfo(bookPath: string): Promise<any | null>;
    /** Create a new book */
    createBook(title: string, genre: string, author?: string): Promise<any | null>;
    /** Start writing next chapter (async job) */
    writeNextChapter(bookPath: string, apiKey: string, baseUrl?: string, model?: string): Promise<string | null>;
    /** Poll write job status */
    getWriteStatus(jobId: string): Promise<any | null>;
    private _fetch;
}
//# sourceMappingURL=api-client.d.ts.map