/**
 * NovelForge Studio API Client
 *
 * Communicates with the local StudioServer HTTP API.
 * All requests go to http://localhost:{port}/api/...
 * Since both the extension host and StudioServer run on the same machine,
 * there are no CORS issues (requests are made from Node.js, not from browser context).
 */
import * as http from "http";
import * as vscode from "vscode";
import { ConfigManager } from "./config-manager";

export interface ApiResponse {
  ok: boolean;
  status: number;
  data: any;
}

export class StudioApiClient {
  /** Auth token extracted from StudioServer stdout */
  private _authToken: string = "";

  constructor(
    private readonly _config: ConfigManager,
    private readonly _outputChannel: vscode.OutputChannel
  ) {}

  /** Set the auth token (extracted from server stdout) */
  setAuthToken(token: string): void {
    this._authToken = token;
    this._outputChannel.appendLine(`[API] Auth token set: ${token.substring(0, 4)}...${token.substring(token.length - 4)}`);
  }

  /** Get the current auth token */
  getAuthToken(): string {
    return this._authToken;
  }

  /** Check if StudioServer is reachable */
  async isServerRunning(): Promise<boolean> {
    try {
      const port = this._config.get<number>("serverPort");
      const resp = await this._fetch(`http://localhost:${port}/api/version`);
      return resp.ok;
    } catch {
      return false;
    }
  }

  /** Generic API request */
  async request(method: string, path: string, body?: any): Promise<ApiResponse> {
    const port = this._config.get<number>("serverPort");
    const url = `http://localhost:${port}${path}`;
    return this._fetch(url, method, body);
  }

  /** Raw request — used by webview message bridge */
  async rawRequest(method: string, path: string, body?: any): Promise<ApiResponse> {
    const port = this._config.get<number>("serverPort");
    const url = `http://localhost:${port}${path}`;
    return this._fetch(url, method, body);
  }

  // ─────── Convenience API ───────

  /** Get server version */
  async getVersion(): Promise<string | null> {
    const resp = await this.request("GET", "/api/version");
    if (resp.ok && resp.data?.version) {
      return resp.data.version;
    }
    return null;
  }

  /** List all books */
  async listBooks(): Promise<any[]> {
    const resp = await this.request("GET", "/api/books");
    return resp.ok ? resp.data : [];
  }

  /** Get book info */
  async getBookInfo(bookPath: string): Promise<any | null> {
    const resp = await this.request("GET", `/api/book/info?path=${encodeURIComponent(bookPath)}`);
    return resp.ok ? resp.data : null;
  }

  /** Create a new book */
  async createBook(title: string, genre: string, author?: string): Promise<any | null> {
    const resp = await this.request("POST", "/api/book/create", {
      title,
      genre,
      author: author || "",
    });
    return resp.ok ? resp.data : null;
  }

  /** Start writing next chapter (async job) */
  async writeNextChapter(
    bookPath: string,
    apiKey: string,
    baseUrl?: string,
    model?: string
  ): Promise<string | null> {
    const resp = await this.request("POST", "/api/write", {
      path: bookPath,
      apiKey,
      baseUrl: baseUrl || "https://api.openai.com/v1",
      model: model || "gpt-4o",
      mode: "next",
    });
    return resp.ok ? resp.data?.jobId : null;
  }

  /** Poll write job status */
  async getWriteStatus(jobId: string): Promise<any | null> {
    const resp = await this.request("GET", `/api/write/status?jobId=${encodeURIComponent(jobId)}`);
    return resp.ok ? resp.data : null;
  }

  // ─────── Internal ───────

  private async _fetch(
    url: string,
    method: string = "GET",
    body?: any
  ): Promise<ApiResponse> {
    const port = this._config.get<number>("serverPort");

    const urlObj = new URL(url);
    const options: http.RequestOptions = {
      hostname: urlObj.hostname,
      port: urlObj.port || port,
      path: urlObj.pathname + urlObj.search,
      method,
      headers: {
        "Content-Type": "application/json",
        "Accept": "application/json",
      } as Record<string, string | number>,
      timeout: 120_000, // 2min for long ops like writing
    };

    // Add auth token if available
    if (this._authToken) {
      (options.headers as Record<string, string>)["Authorization"] = `Bearer ${this._authToken}`;
    }

    if (body) {
      const bodyStr = JSON.stringify(body);
      (options.headers as Record<string, string | number>)['Content-Length'] = Buffer.byteLength(bodyStr);
    }

    return new Promise<ApiResponse>((resolve) => {
      const req = http.request(options, (res: http.IncomingMessage) => {
        let data = "";
        res.on("data", (chunk: Buffer) => (data += chunk.toString()));
        res.on("end", () => {
          let parsed: any;
          try {
            parsed = JSON.parse(data);
          } catch {
            parsed = data;
          }
          this._outputChannel.appendLine(
            `[API] ${method} ${urlObj.pathname} → ${res.statusCode}`
          );
          resolve({
            ok: res.statusCode! >= 200 && res.statusCode! < 300,
            status: res.statusCode!,
            data: parsed,
          });
        });
      });

      req.on("error", (err: Error) => {
        this._outputChannel.appendLine(`[API] ${method} ${url} → ERROR: ${err.message}`);
        resolve({ ok: false, status: 0, data: { error: err.message } });
      });

      req.on("timeout", () => {
        req.destroy();
        resolve({ ok: false, status: 0, data: { error: "timeout" } });
      });

      if (body) {
        req.write(JSON.stringify(body));
      }
      req.end();
    });
  }
}
