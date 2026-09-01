"""Mock OpenAI-compatible LLM server: logs incoming messages, returns fixed stream."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = "C:/Users/13631/Desktop/ab/demo/NovelForge/.audit/llm_capture.jsonl"
_lock = threading.Lock()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Access-Control-Allow-Methods", "*")
        self.end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        with _lock:
            with open(LOG, "a", encoding="utf-8") as f:
                f.write(json.dumps({
                    "path": self.path,
                    "model": body.get("model"),
                    "stream": body.get("stream"),
                    "messages": body.get("messages"),
                }, ensure_ascii=False) + "\n")
        if body.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            for ch in ["好的，", "这是测试大纲。"]:
                evt = "data: " + json.dumps(
                    {"choices": [{"delta": {"content": ch}}]}, ensure_ascii=False) + "\n\n"
                self.wfile.write(evt.encode("utf-8"))
            self.wfile.write(b"data: [DONE]\n\n")
        else:
            resp = {"choices": [{"message": {"content": "好的，这是测试大纲。"}}]}
            out = json.dumps(resp, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(out)))
            self.end_headers()
            self.wfile.write(out)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 9911), H).serve_forever()
