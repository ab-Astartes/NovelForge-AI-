package com.novelforge.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.novelforge.core.pipeline.PipelineRunner;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.genre.GenreManager;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.AuditResult;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.project.BookProject;
import com.novelforge.core.state.TruthState;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StudioServer — embedded HTTP server providing the NovelForge Studio Web UI.
 * REST API endpoints + static HTML frontend.
 */
public class StudioServer {

    private static final Logger log = LoggerFactory.getLogger(StudioServer.class);
    private static final int DEFAULT_PORT = 8964;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpServer server;
    private final Path booksRoot;
    private final ConcurrentHashMap<String, String> apiKeys = new ConcurrentHashMap<>();

    // Pipeline components (configured per-request based on user's API key)
    private PipelineConfig defaultConfig;

    public StudioServer() throws IOException {
        this(DEFAULT_PORT);
    }

    public StudioServer(int port) throws IOException {
        this.booksRoot = Paths.get(System.getProperty("user.home"), "NovelForge", "books");
        Files.createDirectories(booksRoot);
        this.defaultConfig = new PipelineConfig();
        this.server = HttpServer.create(new InetSocketAddress("localhost", port), 0);

        // Static frontend
        server.createContext("/", this::serveStatic);

        // API endpoints
        server.createContext("/api/books", this::handleBooksApi);
        server.createContext("/api/book/create", this::handleBookCreate);
        server.createContext("/api/book/info", this::handleBookInfo);
        server.createContext("/api/write", this::handleWriteApi);
        server.createContext("/api/audit", this::handleAuditApi);
        server.createContext("/api/state", this::handleStateApi);
        server.createContext("/api/export", this::handleExportApi);
        server.createContext("/api/config", this::handleConfigApi);

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
    }

    public void start() {
        server.start();
        log.info("NovelForge Studio started at http://localhost:{}", server.getAddress().getPort());
        System.out.println("NovelForge Studio: http://localhost:" + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        log.info("NovelForge Studio stopped");
    }

    // --- Static frontend ---
    private void serveStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.equals("/index.html")) {
            serveResource(exchange, "/studio/index.html", "text/html; charset=utf-8");
        } else if (path.equals("/style.css")) {
            serveResource(exchange, "/studio/style.css", "text/css; charset=utf-8");
        } else if (path.equals("/app.js")) {
            serveResource(exchange, "/studio/app.js", "application/javascript; charset=utf-8");
        } else {
            sendJson(exchange, 404, "{\"error\": \"not found\"}");
        }
    }

    private void serveResource(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        InputStream is = StudioServer.class.getResourceAsStream(resourcePath);
        if (is != null) {
            byte[] content = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.getResponseBody().close();
        } else {
            // Fallback: try file path for dev mode
            Path file = Paths.get("packages/novelforge-studio/src/main/resources" + resourcePath);
            if (Files.exists(file)) {
                byte[] content = Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
                exchange.getResponseBody().close();
            } else {
                sendJson(exchange, 404, "{\"error\": \"resource not found\"}");
            }
        }
    }

    // --- API: List books ---
    private void handleBooksApi(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { sendJson(exchange, 405, "{\"error\":\"method not allowed\"}"); return; }

        ArrayNode books = mapper.createArrayNode();
        if (Files.exists(booksRoot)) {
            for (Path p : Files.newDirectoryStream(booksRoot)) {
                if (Files.exists(p.resolve("book.json"))) {
                    try {
                        JsonNode bookJson = mapper.readTree(Files.newInputStream(p.resolve("book.json")));
                        ObjectNode item = mapper.createObjectNode();
                        item.put("title", bookJson.get("title").asText());
                        item.put("genre", bookJson.get("genre").asText());
                        item.put("path", p.toString());
                        item.put("chapters", bookJson.has("chapters") ? bookJson.get("chapters").size() : 0);
                        books.add(item);
                    } catch (Exception e) { log.warn("Failed to read book at {}", p); }
                }
            }
        }
        sendJson(exchange, 200, mapper.writeValueAsString(books));
    }

    // --- API: Create book ---
    private void handleBookCreate(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }

        JsonNode body = readBody(exchange);
        String title = body.has("title") ? body.get("title").asText() : null;
        String genre = body.has("genre") ? body.get("genre").asText() : "xuanhuan";
        String author = body.has("author") ? body.get("author").asText() : "";

        if (title == null) { sendJson(exchange, 400, "{\"error\":\"title required\"}"); return; }

        try {
            Path bookDir = BookProject.create(booksRoot, title, genre, author);
            ObjectNode result = mapper.createObjectNode();
            result.put("status", "created");
            result.put("path", bookDir.toString());
            result.put("title", title);
            sendJson(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // --- API: Book info ---
    private void handleBookInfo(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }

        String query = exchange.getRequestURI().getQuery();
        String bookPath = getQueryParam(query, "path");
        if (bookPath == null) { sendJson(exchange, 400, "{\"error\":\"path parameter required\"}"); return; }

        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            TruthState state = new TruthState(Paths.get(bookPath));
            ObjectNode result = mapper.createObjectNode();
            result.put("title", book.getTitle());
            result.put("genre", book.getGenre());
            result.put("author", book.getAuthor());
            result.put("chapters", book.getChapters().size());
            result.put("nextChapter", book.nextChapterNumber());
            result.put("characters", state.characters().getSummary());
            result.put("world", state.world().getSummary());
            result.put("hooks", state.hooks().getSummary());
            sendJson(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // --- API: Write next chapter ---
    private void handleWriteApi(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }

        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        String apiKey = body.has("apiKey") ? body.get("apiKey").asText() : null;
        String baseUrl = body.has("baseUrl") ? body.get("baseUrl").asText() : "https://api.openai.com/v1";
        String modelId = body.has("model") ? body.get("model").asText() : "gpt-4o";
        String mode = body.has("mode") ? body.get("mode").asText() : "next";

        if (bookPath == null) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
        if (apiKey == null || apiKey.isEmpty()) { sendJson(exchange, 400, "{\"error\":\"apiKey required\"}"); return; }

        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            TruthState state = new TruthState(Paths.get(bookPath));
            PipelineConfig config = loadConfig(Paths.get(bookPath));
            ModelRouter router = new ModelRouter(new ModelRouter.ModelConfig("openai", modelId, baseUrl, apiKey));
            PipelineRunner runner = new PipelineRunner(config, router);

            PipelineResult result;
            if (mode.equals("draft")) {
                result = runner.runDraftOnly(book, state);
            } else {
                result = runner.writeNextChapter(book, state);
            }

            ObjectNode response = mapper.createObjectNode();
            response.put("status", result.success() ? "ok" : "error");
            if (result.success()) {
                Chapter chapter = book.getChapters().get(book.getChapters().size() - 1);
                Path bookDir = Paths.get(bookPath);
                BookProject.saveChapter(bookDir, chapter);
                BookProject.saveBookMetadata(bookDir, book);
                response.put("chapterNumber", chapter.getNumber());
                response.put("length", chapter.getFinalText() != null ? chapter.getFinalText().length() : 0);
                if (chapter.getAuditResult() != null) {
                    response.put("auditScore", chapter.getAuditResult().getOverallScore());
                }
            } else {
                response.put("error", result.errorMessage());
            }
            sendJson(exchange, 200, mapper.writeValueAsString(response));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // --- API: Audit ---
    private void handleAuditApi(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }

        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        int chapterNum = body.has("chapter") ? body.get("chapter").asInt() : -1;
        String apiKey = body.has("apiKey") ? body.get("apiKey").asText() : null;
        String baseUrl = body.has("baseUrl") ? body.get("baseUrl").asText() : "https://api.openai.com/v1";
        String modelId = body.has("model") ? body.get("model").asText() : "gpt-4o";

        if (bookPath == null || apiKey == null) { sendJson(exchange, 400, "{\"error\":\"path and apiKey required\"}"); return; }

        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            TruthState state = new TruthState(Paths.get(bookPath));
            ModelRouter router = new ModelRouter(new ModelRouter.ModelConfig("openai", modelId, baseUrl, apiKey));
            PipelineRunner runner = new PipelineRunner(defaultConfig, router);

            int idx = chapterNum > 0 ? chapterNum - 1 : book.getChapters().size() - 1;
            Chapter ch = book.getChapters().get(idx);
            String text = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();

            PipelineResult result = runner.runAuditOnly(book, state, text);
            AuditResult audit = result.updatedContext().getAuditResult();

            ObjectNode response = mapper.createObjectNode();
            response.put("status", "ok");
            response.put("overallScore", audit.getOverallScore());
            response.put("pass", audit.isPass());
            if (audit.getDimensionScores() != null) {
                ObjectNode scores = mapper.createObjectNode();
                audit.getDimensionScores().forEach(scores::put);
                response.set("dimensionScores", scores);
            }
            if (audit.getCriticalIssues() != null) {
                ArrayNode issues = mapper.createArrayNode();
                audit.getCriticalIssues().forEach(issues::add);
                response.set("criticalIssues", issues);
            }
            if (audit.getWarnings() != null) {
                ArrayNode warnings = mapper.createArrayNode();
                audit.getWarnings().forEach(warnings::add);
                response.set("warnings", warnings);
            }
            sendJson(exchange, 200, mapper.writeValueAsString(response));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // --- API: State ---
    private void handleStateApi(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }

        String query = exchange.getRequestURI().getQuery();
        String bookPath = getQueryParam(query, "path");
        String type = getQueryParam(query, "type"); // characters, world, hooks, timeline

        if (bookPath == null) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }

        try {
            TruthState state = new TruthState(Paths.get(bookPath));
            String summary;
            switch (type != null ? type : "all") {
                case "characters" -> summary = state.characters().getSummary();
                case "world" -> summary = state.world().getSummary();
                case "hooks" -> summary = state.hooks().getSummary();
                default -> summary = "角色:\n" + state.characters().getSummary() +
                        "\n世界:\n" + state.world().getSummary() +
                        "\n悬念:\n" + state.hooks().getSummary();
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("type", type != null ? type : "all");
            response.put("summary", summary);
            sendJson(exchange, 200, mapper.writeValueAsString(response));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // --- API: Export ---
    private void handleExportApi(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }

        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        String format = body.has("format") ? body.get("format").asText() : "txt";

        if (bookPath == null) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }

        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            StringBuilder content = new StringBuilder();
            content.append("# ").append(book.getTitle()).append("\n\n");
            for (Chapter ch : book.getChapters()) {
                String text = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
                if (text == null) continue;
                content.append("## 第").append(ch.getNumber()).append("章\n\n").append(text).append("\n\n");
            }

            String ext = format.equals("epub") ? "epub" : format.equals("md") ? "md" : "txt";
            Path outputPath = Paths.get(bookPath).resolve(book.getTitle() + "." + ext);
            Files.writeString(outputPath, content.toString());

            ObjectNode response = mapper.createObjectNode();
            response.put("status", "ok");
            response.put("format", format);
            response.put("outputPath", outputPath.toString());
            response.put("chapters", book.getChapters().size());
            sendJson(exchange, 200, mapper.writeValueAsString(response));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // --- API: Config ---
    private void handleConfigApi(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("GET")) {
            ObjectNode config = mapper.createObjectNode();
            config.put("chapterWordsMin", defaultConfig.getChapterWordsMin());
            config.put("chapterWordsMax", defaultConfig.getChapterWordsMax());
            config.put("auditPassThreshold", defaultConfig.getAuditPassThreshold());
            config.put("maxRevisionPasses", defaultConfig.getMaxRevisionPasses());
            config.put("genreKeys", new GenreManager().listGenreKeys().toString());
            sendJson(exchange, 200, mapper.writeValueAsString(config));
        } else if (exchange.getRequestMethod().equals("POST")) {
            JsonNode body = readBody(exchange);
            if (body.has("chapterWordsMin")) defaultConfig.setChapterWordsMin(body.get("chapterWordsMin").asInt());
            if (body.has("chapterWordsMax")) defaultConfig.setChapterWordsMax(body.get("chapterWordsMax").asInt());
            if (body.has("auditPassThreshold")) defaultConfig.setAuditPassThreshold(body.get("auditPassThreshold").asDouble());
            if (body.has("maxRevisionPasses")) defaultConfig.setMaxRevisionPasses(body.get("maxRevisionPasses").asInt());
            sendJson(exchange, 200, "{\"status\":\"updated\"}");
        } else {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
        }
    }

    // --- Helpers ---
    private JsonNode readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        byte[] bytes = is.readAllBytes();
        if (bytes.length == 0) return mapper.createObjectNode();
        return mapper.readTree(bytes);
    }

    private PipelineConfig loadConfig(Path bookDir) {
        Path configFile = bookDir.resolve("config/pipeline.json");
        PipelineConfig config = new PipelineConfig();
        if (Files.exists(configFile)) {
            try {
                JsonNode root = mapper.readTree(Files.newInputStream(configFile));
                if (root.has("chapterWordsMin")) config.setChapterWordsMin(root.get("chapterWordsMin").asInt());
                if (root.has("chapterWordsMax")) config.setChapterWordsMax(root.get("chapterWordsMax").asInt());
                if (root.has("auditPassThreshold")) config.setAuditPassThreshold(root.get("auditPassThreshold").asDouble());
                if (root.has("maxRevisionPasses")) config.setMaxRevisionPasses(root.get("maxRevisionPasses").asInt());
                // Agent toggles
                if (root.has("runArchitect")) config.setRunArchitect(root.get("runArchitect").asBoolean());
                if (root.has("runPlanner")) config.setRunPlanner(root.get("runPlanner").asBoolean());
                if (root.has("runComposer")) config.setRunComposer(root.get("runComposer").asBoolean());
                if (root.has("runWriter")) config.setRunWriter(root.get("runWriter").asBoolean());
                if (root.has("runObserver")) config.setRunObserver(root.get("runObserver").asBoolean());
                if (root.has("runReflector")) config.setRunReflector(root.get("runReflector").asBoolean());
                if (root.has("runNormalizer")) config.setRunNormalizer(root.get("runNormalizer").asBoolean());
                if (root.has("runAuditor")) config.setRunAuditor(root.get("runAuditor").asBoolean());
                if (root.has("runReviser")) config.setRunReviser(root.get("runReviser").asBoolean());
            } catch (Exception e) { log.warn("Failed to load pipeline config"); }
        }
        return config;
    }

    private String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv[0].equals(key)) return kv.length > 1 ? kv[1] : "";
        }
        return null;
    }

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
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
