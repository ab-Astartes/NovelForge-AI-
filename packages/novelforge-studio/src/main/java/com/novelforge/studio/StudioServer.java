package com.novelforge.studio;

import com.novelforge.core.pipeline.PipelineRunner;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.llm.ModelRouter;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * StudioServer — embedded HTTP server providing the NovelForge Studio Web UI.
 * Runs on localhost:8964 by default. Provides REST API + static HTML frontend.
 */
public class StudioServer {

    private static final Logger log = LoggerFactory.getLogger(StudioServer.class);
    private static final int DEFAULT_PORT = 8964;

    private final HttpServer server;
    private final PipelineRunner pipelineRunner;

    public StudioServer() throws IOException {
        this(DEFAULT_PORT);
    }

    public StudioServer(int port) throws IOException {
        PipelineConfig config = new PipelineConfig();
        ModelRouter router = new ModelRouter(new ModelRouter.ModelConfig("openai", "gpt-4o", "https://api.openai.com/v1", ""));
        this.pipelineRunner = new PipelineRunner(config, router);
        this.server = HttpServer.create(new InetSocketAddress("localhost", port), 0);

        // Static frontend
        server.createContext("/", this::serveStatic);

        // API endpoints
        server.createContext("/api/book", this::handleBookApi);
        server.createContext("/api/write", this::handleWriteApi);
        server.createContext("/api/audit", this::handleAuditApi);
        server.createContext("/api/state", this::handleStateApi);
        server.createContext("/api/export", this::handleExportApi);

        server.setExecutor(null); // default executor
    }

    public void start() {
        server.start();
        log.info("NovelForge Studio started at http://localhost:{}", server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        log.info("NovelForge Studio stopped");
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        // TODO: Serve index.html and other static resources from resources/studio/
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.equals("/index.html")) {
            Path htmlPath = Path.of("src/main/resources/studio/index.html");
            if (Files.exists(htmlPath)) {
                byte[] content = Files.readAllBytes(htmlPath);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            } else {
                String msg = "Studio frontend not found. Run from project root.";
                exchange.sendResponseHeaders(404, msg.length());
                exchange.getResponseBody().write(msg.getBytes());
            }
        }
        exchange.getResponseBody().close();
    }

    private void handleBookApi(HttpExchange exchange) throws IOException {
        // TODO: REST API for book CRUD
        sendJson(exchange, 200, "{\"status\": \"ok\", \"books\": []}");
    }

    private void handleWriteApi(HttpExchange exchange) throws IOException {
        // TODO: REST API to trigger write pipeline
        sendJson(exchange, 200, "{\"status\": \"ok\", \"message\": \"write endpoint ready\"}");
    }

    private void handleAuditApi(HttpExchange exchange) throws IOException {
        // TODO: REST API to run audit
        sendJson(exchange, 200, "{\"status\": \"ok\", \"message\": \"audit endpoint ready\"}");
    }

    private void handleStateApi(HttpExchange exchange) throws IOException {
        // TODO: REST API to query truth state
        sendJson(exchange, 200, "{\"status\": \"ok\", \"message\": \"state endpoint ready\"}");
    }

    private void handleExportApi(HttpExchange exchange) throws IOException {
        // TODO: REST API to export book
        sendJson(exchange, 200, "{\"status\": \"ok\", \"message\": \"export endpoint ready\"}");
    }

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, json.length());
        OutputStream os = exchange.getResponseBody();
        os.write(json.getBytes());
        os.close();
    }

    /** Main entry for Studio standalone launch */
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        StudioServer studio = new StudioServer(port);
        studio.start();
        System.out.println("Press Enter to stop...");
        System.in.read();
        studio.stop();
    }
}
