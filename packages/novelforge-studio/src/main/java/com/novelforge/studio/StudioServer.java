package com.novelforge.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.core.genre.GenreManager;
import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.AgentApiConfig;
import com.novelforge.core.models.AuditResult;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.Reference;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.models.TextUtils;
import com.novelforge.core.models.WritingStyle;
import com.novelforge.core.models.StudioConfig;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.pipeline.PipelineRunner;
import com.novelforge.core.project.BookProject;
import com.novelforge.core.prompt.PromptBuilder;
import com.novelforge.core.state.TruthState;
import com.novelforge.core.Version;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;





/**

 * StudioServer — embedded HTTP server providing the NovelForge Studio Web UI.

 * REST API endpoints + static HTML frontend.

 */

public class StudioServer {



    private static final Logger log = LoggerFactory.getLogger(StudioServer.class);

    private static final int DEFAULT_PORT = 8964;

    private static final long API_TIMEOUT_MS = 120_000; // 2-minute timeout for long operations (fixes #12)

    private static final ObjectMapper mapper = new ObjectMapper();



    private final HttpServer server;

    private final Path booksRoot;

    private final ConcurrentHashMap<String, String> apiKeys = new ConcurrentHashMap<>();



    // 🟡-1: Simple auth token for local Studio access

    private final String authToken;



    // Pipeline components (configured per-request based on user's API key)

    private PipelineConfig defaultConfig;



    // Full studio config (global + per-agent API overrides + presets)

    private volatile StudioConfig studioConfig;



    // ModelRouter for pipeline (updated when config changes)

    private volatile ModelRouter modelRouter;



    // fixes #28: Configuration hot-reload — watches pipeline.json for changes

    private final ScheduledExecutorService configWatcher = Executors.newSingleThreadScheduledExecutor();

    private long configLastModified = 0;



    // 🟡-2: Async write job queue — clients submit a job, then poll for progress

    private final ConcurrentHashMap<String, WriteJob> writeJobs = new ConcurrentHashMap<>();

    private final AtomicLong jobIdCounter = new AtomicLong(0);

    private final ScheduledExecutorService writeExecutor = Executors.newScheduledThreadPool(2);



    /** Async write job record */

    private static class WriteJob {

        final String jobId;

        volatile String status = "pending"; // pending, running, completed, failed

        volatile String result = null;

        volatile String error = null;

        volatile int progress = 0; // 0-100

        volatile long startTime = System.currentTimeMillis();

        // SSE progress events stored for both streaming and polling clients

        final java.util.List<String> events = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        WriteJob(String jobId) { this.jobId = jobId; }

    }



    public StudioServer() throws IOException {

        this(DEFAULT_PORT);

    }



    public StudioServer(int port) throws IOException {

        this.booksRoot = Paths.get(System.getProperty("user.home"), "NovelForge", "books");

        Files.createDirectories(booksRoot);

        this.defaultConfig = new PipelineConfig();

        // Load studio config (global API + per-agent overrides + presets)

        this.studioConfig = StudioConfig.load();

        this.defaultConfig = studioConfig.getPipelineConfig();

        this.modelRouter = new ModelRouter(studioConfig.getGlobalDefault().toModelConfig());

        // Apply per-agent overrides from studio config

        for (Map.Entry<String, AgentApiConfig> entry : studioConfig.getAgentOverrides().entrySet()) {

            modelRouter.setAgentModel(entry.getKey(), entry.getValue().toModelConfig());

        }

        // 🟡-1: Generate random auth token for local API access

        this.authToken = generateToken();

        this.server = HttpServer.create(new InetSocketAddress("localhost", port), 0);



        // Static frontend

        server.createContext("/", this::serveStatic);



        // API endpoints

        // API endpoints with CORS + auth

        server.createContext("/api/books", corsWrap(this::handleBooksApi));

        server.createContext("/api/book/create", corsWrap(this::handleBookCreate));

        server.createContext("/api/book/delete", corsWrap(this::handleBookDeleteApi));  // 🟢-4

        server.createContext("/api/book/info", corsWrap(this::handleBookInfo));
        server.createContext("/api/book/chapter", corsWrap(this::handleBookChapterApi));
        server.createContext("/api/book/outline", corsWrap(this::handleBookOutlineApi));
        server.createContext("/api/book/intent", corsWrap(this::handleBookIntentApi));
        server.createContext("/api/book/chapter-title", corsWrap(this::handleBookChapterTitleApi));
        server.createContext("/api/book/edit", corsWrap(this::handleBookEditApi));
        server.createContext("/api/search", corsWrap(this::handleSearchApi));

        server.createContext("/api/write", corsWrap(this::handleWriteApi));

        server.createContext("/api/write/status", corsWrap(this::handleWriteStatusApi));  // 🟡-2: job status polling

        server.createContext("/api/audit", corsWrap(this::handleAuditApi));

        server.createContext("/api/state", corsWrap(this::handleStateApi));

        server.createContext("/api/export", corsWrap(this::handleExportApi));

        server.createContext("/api/config", corsWrap(this::handleConfigApi));

        server.createContext("/api/config/presets", corsWrap(this::handleConfigPresetsApi));

        server.createContext("/api/config/sample", corsWrap(this::handleConfigSampleApi));

        server.createContext("/api/write/stream", corsWrap(this::handleWriteStreamApi));

        server.createContext("/api/progress", corsWrap(this::handleProgressApi));

        server.createContext("/api/diff", corsWrap(this::handleDiffApi));

        server.createContext("/api/write/resume", corsWrap(this::handleWriteResumeApi));
        server.createContext("/api/write/cancel", corsWrap(this::handleWriteCancelApi));

        server.createContext("/api/rollback", corsWrap(this::handleRollbackApi));

        server.createContext("/api/style", corsWrap(this::handleStyleApi));
        server.createContext("/api/version", corsWrap(this::handleVersionApi));
        server.createContext("/api/outline/synopsis", corsWrap(this::handleOutlineSynopsisApi));
        server.createContext("/api/volume/synopsis", corsWrap(this::handleVolumeSynopsisApi));
        server.createContext("/api/ai-trace", corsWrap(this::handleAiTraceApi));
        server.createContext("/api/outline/generate", corsWrap(this::handleOutlineGenerateApi));
        server.createContext("/api/volume/generate", corsWrap(this::handleVolumeGenerateApi));
        server.createContext("/api/chapter/revise", corsWrap(this::handleChapterReviseApi));
        server.createContext("/api/characters", corsWrap(this::handleCharactersApi));
        server.createContext("/api/hooks", corsWrap(this::handleHooksApi));
        server.createContext("/api/chapter/synopsis", corsWrap(this::handleChapterSynopsisApi));
        server.createContext("/api/book/references", corsWrap(this::handleBookReferencesApi));
        server.createContext("/api/book/inspirations", corsWrap(this::handleBookInspirationsApi));



        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));

    }



    public void start() {

        server.start();

        // fixes #28: Start config file watcher for hot-reload

        Path globalConfigFile = Paths.get(System.getProperty("user.home"), "NovelForge", "config", "pipeline.json");

        if (Files.exists(globalConfigFile)) {

            try { configLastModified = Files.getLastModifiedTime(globalConfigFile).toMillis(); } catch (Exception e) { /* ignore */ }

        }

        configWatcher.scheduleAtFixedRate(() -> {

            try {

                if (Files.exists(globalConfigFile)) {

                    long currentModified = Files.getLastModifiedTime(globalConfigFile).toMillis();

                    if (currentModified != configLastModified) {

                        configLastModified = currentModified;

                        defaultConfig.reloadFromJson(globalConfigFile);

                        log.info("[Hot-reload] Configuration updated from {}", globalConfigFile);

                    }

                }

            } catch (Exception e) { log.warn("Config watcher error: {}", e.getMessage()); }

        }, 5, 5, TimeUnit.SECONDS);  // check every 5 seconds

        // Splash ASCII art welcome banner
        int port = server.getAddress().getPort();
        String splash = "\n" +
            "  ╔═════════════════════════════════════════════════════╗\n" +
            "  ║                                                     ║\n" +
            "  ║    ██╗  ███╗   ██╗ ██████╗ ██╗  ██╗ ███████╗      ║\n" +
            "  ║    ██║ ████╗  ██║ ██╔══██╗ ██║ ██╔╝ ██╔════╝      ║\n" +
            "  ║    ██║ ██╔██╗ ██║ ██████╔╝ █████╔╝  ███████╗      ║\n" +
            "  ║    ██║ ██║╚██╗██║ ██╔═══╗  ██╔═██╗  ╚════██║      ║\n" +
            "  ║    ██║ ██║ ╚████║ ██████╗  ██║  ██╗ ███████║      ║\n" +
            "  ║    ╚═╝ ╚═╝  ╚═══╝ ╚═════╝  ╚═╝  ╚═╝ ╚══════╝      ║\n" +
            "  ║                                                     ║\n" +
            "  ║   🔥  AI Novel Writing Engine  —  v" + Version.VERSION + "            ║\n" +
            "  ║                                                     ║\n" +
            "  ║   Studio: http://localhost:" + port + "                    ║\n" +
            "  ║                                                     ║\n" +
            "  ╚═════════════════════════════════════════════════════╝\n";
        System.out.println(splash);
        log.info("{} Studio started at http://localhost:{}", Version.full(), port);

        System.out.println("Auth token: " + authToken);  // 🟡-1: show token for frontend to use

    }



    public void stop() {

        configWatcher.shutdownNow();

        writeExecutor.shutdownNow();  // 🟡-2: stop write executor on shutdown

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

            try (java.nio.file.DirectoryStream<Path> dirStream = Files.newDirectoryStream(booksRoot)) {

                for (Path p : dirStream) {

                    Path bookJsonPath = p.resolve("book.json");

                    if (Files.exists(bookJsonPath)) {

                        try {

                            String jsonStr = Files.readString(bookJsonPath, StandardCharsets.UTF_8);

                            JsonNode bookJson = mapper.readTree(jsonStr);

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

            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

        }

    }



    // --- API: Book info ---

    private void handleBookInfo(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }



        String query = exchange.getRequestURI().getQuery();

        String bookPath = getQueryParam(query, "path");

        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path parameter required and must be within books directory\"}"); return; }



        try {

            Book book = BookProject.loadBook(Paths.get(bookPath));

            TruthState state = new TruthState(Paths.get(bookPath));

            ObjectNode result = mapper.createObjectNode();

            result.put("title", book.getTitle());

            result.put("genre", book.getGenre());

            result.put("author", book.getAuthor());

            result.put("chapters", book.getChapters().size());
            result.put("nextChapter", book.nextChapterNumber());
            result.put("referencesCount", book.getReferences() != null ? book.getReferences().size() : 0);
            result.put("inspirationsCount", book.getInspirations() != null ? book.getInspirations().size() : 0);
            result.put("characters", state.characters().getSummary());

            result.put("world", state.world().getSummary());

            result.put("hooks", state.hooks().getSummary());
            if (book.getOutline() != null) result.put("outlinePreview", TextUtils.truncate(book.getOutline(), 300));
            if (book.getAuthorIntent() != null) result.put("intentPreview", TextUtils.truncate(book.getAuthorIntent(), 300));

            // Chapter details: number, title, word count, audit score
            ArrayNode chaptersNode = mapper.createArrayNode();
            for (Chapter ch : book.getChapters()) {
                ObjectNode chNode = mapper.createObjectNode();
                chNode.put("number", ch.getNumber());
                chNode.put("title", ch.getTitle() != null ? ch.getTitle() : "第" + ch.getNumber() + "章");
                chNode.put("wordCount", ch.getWordCount());
                if (ch.getAuditResult() != null) {
                    chNode.put("auditScore", ch.getAuditResult().getOverallScore());
                    chNode.put("passed", ch.getAuditResult().getOverallScore() >= 6.0);
                }
                chaptersNode.add(chNode);
            }
            result.set("chapterDetails", chaptersNode);

            sendJson(exchange, 200, mapper.writeValueAsString(result));

        } catch (Exception e) {

            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

        }

    }

    // --- API: Get chapter content ---
    private void handleBookChapterApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (method.equals("GET")) {
            String query = exchange.getRequestURI().getQuery();
            String bookPath = getQueryParam(query, "path");
            String chapterNum = getQueryParam(query, "chapter");
            if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}"); return; }
            if (chapterNum == null) { sendJson(exchange, 400, "{\"error\":\"chapter parameter required\"}"); return; }
            try {
                Book book = BookProject.loadBook(Paths.get(bookPath));
                int num = Integer.parseInt(chapterNum);
                Chapter ch = book.getChapters().stream()
                    .filter(c -> c.getNumber() == num)
                    .findFirst()
                    .orElse(null);
                if (ch == null) { sendJson(exchange, 404, "{\"error\":\"Chapter " + num + " not found\"}"); return; }
                ObjectNode result = mapper.createObjectNode();
                result.put("number", ch.getNumber());
                result.put("title", ch.getTitle() != null ? ch.getTitle() : "第" + ch.getNumber() + "章");
                result.put("wordCount", ch.getWordCount());
                result.put("draftText", ch.getDraftText() != null ? ch.getDraftText() : "");
                result.put("finalText", ch.getFinalText() != null ? ch.getFinalText() : "");
                if (ch.getAuditResult() != null) {
                    ObjectNode audit = mapper.createObjectNode();
                    audit.put("overallScore", ch.getAuditResult().getOverallScore());
                    audit.put("passed", ch.getAuditResult().getOverallScore() >= 6.0);
                    result.set("audit", audit);
                }
                sendJson(exchange, 200, mapper.writeValueAsString(result));
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
            }
        } else if (method.equals("POST")) {
            JsonNode body = readBody(exchange);
            String bookPath = body.has("path") ? body.get("path").asText() : null;
            int chapterNum = body.has("chapter") ? body.get("chapter").asInt() : -1;
            String finalText = body.has("finalText") ? body.get("finalText").asText() : null;
            String draftText = body.has("draftText") ? body.get("draftText").asText() : null;
            if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
            if (chapterNum < 1) { sendJson(exchange, 400, "{\"error\":\"chapter number required\"}"); return; }
            try {
                Book book = BookProject.loadBook(Paths.get(bookPath));
                Chapter ch = book.getChapters().stream()
                    .filter(c -> c.getNumber() == chapterNum)
                    .findFirst()
                    .orElse(null);
                if (ch == null) { sendJson(exchange, 404, "{\"error\":\"Chapter " + chapterNum + " not found\"}"); return; }
                if (finalText != null) ch.setFinalText(finalText);
                if (draftText != null) ch.setDraftText(draftText);
                ch.setWordCount(TextUtils.estimateChineseWordCount(finalText != null ? finalText : draftText));
                BookProject.saveChapter(Paths.get(bookPath), ch);
                BookProject.saveBookMetadata(Paths.get(bookPath), book);
                sendJson(exchange, 200, "{\"status\":\"saved\",\"chapter\":" + chapterNum + ",\"wordCount\":" + ch.getWordCount() + "}");
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
            }
        } else {
            sendJson(exchange, 405, "{\"error\":\"GET/POST only\"}");
        }
    }


    // --- API: Delete book/project (🟢-4) ---


    // --- API: Book outline (GET/POST) ---
    private void handleBookOutlineApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            String query = exchange.getRequestURI().getQuery();
            String bookPath = getQueryParam(query, "path");
            if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
            try {
                Book book = BookProject.loadBook(Paths.get(bookPath));
                ObjectNode result = mapper.createObjectNode();
                result.put("outline", book.getOutline() != null ? book.getOutline() : "");
                sendJson(exchange, 200, mapper.writeValueAsString(result));
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
            }
        } else if ("POST".equals(method)) {
            JsonNode body = readBody(exchange);
            String bookPath = body.has("path") ? body.get("path").asText() : null;
            String outline = body.has("outline") ? body.get("outline").asText() : null;
            if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
            if (outline == null) { sendJson(exchange, 400, "{\"error\":\"outline content required\"}"); return; }
            try {
                Path outlineFile = Paths.get(bookPath).resolve("outline.md");
                Files.writeString(outlineFile, outline);
                Book book = BookProject.loadBook(Paths.get(bookPath));
                book.setOutline(outline);
                BookProject.saveBookMetadata(Paths.get(bookPath), book);
                sendJson(exchange, 200, "{\"status\":\"saved\",\"length\":" + outline.length() + "}");
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
            }
        } else {
            sendJson(exchange, 405, "{\"error\":\"GET or POST only\"}");
        }
    }

    // --- API: Book author intent (GET/POST) ---
    private void handleBookIntentApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            String query = exchange.getRequestURI().getQuery();
            String bookPath = getQueryParam(query, "path");
            if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
            try {
                Book book = BookProject.loadBook(Paths.get(bookPath));
                ObjectNode result = mapper.createObjectNode();
                result.put("intent", book.getAuthorIntent() != null ? book.getAuthorIntent() : "");
                sendJson(exchange, 200, mapper.writeValueAsString(result));
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
            }
        } else if ("POST".equals(method)) {
            JsonNode body = readBody(exchange);
            String bookPath = body.has("path") ? body.get("path").asText() : null;
            String intent = body.has("intent") ? body.get("intent").asText() : null;
            if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
            if (intent == null) { sendJson(exchange, 400, "{\"error\":\"intent content required\"}"); return; }
            try {
                Path intentFile = Paths.get(bookPath).resolve("author_intent.md");
                Files.writeString(intentFile, intent);
                Book book = BookProject.loadBook(Paths.get(bookPath));
                book.setAuthorIntent(intent);
                BookProject.saveBookMetadata(Paths.get(bookPath), book);
                sendJson(exchange, 200, "{\"status\":\"saved\",\"length\":" + intent.length() + "}");
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
            }
        } else {
            sendJson(exchange, 405, "{\"error\":\"GET or POST only\"}");
        }
    }

    // --- API: Chapter title update (POST) ---
    private void handleBookChapterTitleApi(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        int chapterNum = body.has("chapter") ? body.get("chapter").asInt() : -1;
        String title = body.has("title") ? body.get("title").asText() : null;
        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
        if (chapterNum < 1) { sendJson(exchange, 400, "{\"error\":\"chapter number required (>=1)\"}"); return; }
        if (title == null || title.trim().isEmpty()) { sendJson(exchange, 400, "{\"error\":\"title content required\"}"); return; }
        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            Chapter ch = book.getChapters().stream()
                .filter(c -> c.getNumber() == chapterNum)
                .findFirst()
                .orElse(null);
            if (ch == null) { sendJson(exchange, 404, "{\"error\":\"Chapter " + chapterNum + " not found\"}"); return; }
            ch.setTitle(title.trim());
            BookProject.saveBookMetadata(Paths.get(bookPath), book);
            sendJson(exchange, 200, "{\"status\":\"saved\",\"chapter\":" + chapterNum + "}");
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    private void handleBookEditApi(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        String title = body.has("title") ? body.get("title").asText() : null;
        String author = body.has("author") ? body.get("author").asText() : null;
        String genre = body.has("genre") ? body.get("genre").asText() : null;
        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
        if (title == null || title.trim().isEmpty()) { sendJson(exchange, 400, "{\"error\":\"title required\"}"); return; }
        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            book.setTitle(title.trim());
            if (author != null) book.setAuthor(author.trim());
            if (genre != null) book.setGenre(genre.trim());
            BookProject.saveBookMetadata(Paths.get(bookPath), book);
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("status", "saved");
            result.put("title", book.getTitle());
            result.put("author", book.getAuthor());
            result.put("genre", book.getGenre());
            sendJson(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    private void handleSearchApi(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }
        String query = exchange.getRequestURI().getQuery();
        String bookPath = getQueryParam(query, "path");
        String keyword = getQueryParam(query, "keyword");
        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
        if (keyword == null || keyword.trim().isEmpty()) { sendJson(exchange, 400, "{\"error\":\"keyword required\"}"); return; }
        keyword = keyword.trim();
        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            ArrayNode results = mapper.createArrayNode();
            for (Chapter ch : book.getChapters()) {
                String text = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
                if (text != null && text.contains(keyword)) {
                    ObjectNode hit = mapper.createObjectNode();
                    hit.put("chapter", ch.getNumber());
                    hit.put("title", ch.getTitle() != null ? ch.getTitle() : "第" + ch.getNumber() + "章");
                    // Find snippet context (50 chars before + keyword + 50 chars after)
                    int idx = text.indexOf(keyword);
                    int start = Math.max(0, idx - 50);
                    int end = Math.min(text.length(), idx + keyword.length() + 50);
                    hit.put("snippet", text.substring(start, end));
                    hit.put("position", idx);
                    results.add(hit);
                }
            }
            // Also search outline
            String outline = book.getOutline();
            if (outline != null && outline.contains(keyword)) {
                ObjectNode hit = mapper.createObjectNode();
                hit.put("chapter", 0);
                hit.put("title", "大纲");
                int idx = outline.indexOf(keyword);
                int start = Math.max(0, idx - 50);
                int end = Math.min(outline.length(), idx + keyword.length() + 50);
                hit.put("snippet", outline.substring(start, end));
                hit.put("position", idx);
                results.add(hit);
            }
            sendJson(exchange, 200, mapper.writeValueAsString(results));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    private void handleBookDeleteApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }



        JsonNode body = readBody(exchange);

        String bookPath = body.has("path") ? body.get("path").asText() : null;

        String type = body.has("type") ? body.get("type").asText() : "project";



        if (bookPath == null) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }



        Path path = Paths.get(bookPath).normalize();

        Path normalizedRoot = booksRoot.normalize();

        if (!path.startsWith(normalizedRoot)) { sendJson(exchange, 403, "{\"error\":\"path must be within books directory\"}"); return; }

        try {

            if (type.equals("chapter")) {

                // Delete the last chapter file

                Book book = BookProject.loadBook(path);

                int lastNum = book.getChapters().size();

                if (lastNum > 0) {

                    Chapter last = book.getChapters().remove(lastNum - 1);

                    Path chapterFile = path.resolve("chapters/chapter-" + String.format("%03d", last.getNumber()) + ".md");

                    if (Files.exists(chapterFile)) Files.delete(chapterFile);

                    Path draftFile = path.resolve("chapters/chapter-" + String.format("%03d", last.getNumber()) + ".draft.md");

                    if (Files.exists(draftFile)) Files.delete(draftFile);

                    BookProject.saveBookMetadata(path, book);

                    sendJson(exchange, 200, "{\"status\":\"deleted\",\"type\":\"chapter\",\"chapterNumber\":\"" + last.getNumber() + "\"}");

                } else {

                    sendJson(exchange, 400, "{\"error\":\"no chapters to delete\"}");

                }

            } else {

                // Delete entire project directory

                if (Files.exists(path)) {

                    // Recursively delete

                    Files.walk(path)

                         .sorted(java.util.Comparator.reverseOrder())

                         .forEach(p -> { try { Files.delete(p); } catch (Exception e) { /* ignore */ } });

                    sendJson(exchange, 200, "{\"status\":\"deleted\",\"type\":\"project\"}");

                } else {

                    sendJson(exchange, 404, "{\"error\":\"project not found\"}");

                }

            }

        } catch (Exception e) {

            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

        }

    }



    // --- API: Write next chapter (async 🟡-2) ---

    // Submit returns a jobId immediately; client polls /api/write/status?jobId=<id> for progress

    private void handleWriteApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }



        JsonNode body = readBody(exchange);

        String bookPath = body.has("path") ? body.get("path").asText() : null;

        String reqApiKey = body.has("apiKey") ? body.get("apiKey").asText() : null;

        String reqBaseUrl = body.has("baseUrl") ? body.get("baseUrl").asText() : null;

        String reqModelId = body.has("model") ? body.get("model").asText() : null;

        // Determine ModelRouter: if request provides apiKey, use request params; otherwise use instance modelRouter

        final ModelRouter router;

        if (reqApiKey != null && !reqApiKey.isEmpty()) {

            String baseUrl = reqBaseUrl != null && !reqBaseUrl.isEmpty() ? reqBaseUrl : "https://api.openai.com/v1";

            String modelId = reqModelId != null && !reqModelId.isEmpty() ? reqModelId : "gpt-4o";

            router = new ModelRouter(new ModelRouter.ModelConfig("openai", modelId, baseUrl, reqApiKey));

        } else {

            router = this.modelRouter;

        }

        String mode = body.has("mode") ? body.get("mode").asText() : "next";



        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}"); return; }

        if (router == null) { sendJson(exchange, 400, "{\"error\":\"apiKey required\"}"); return; }

        String jobId = "job-" + jobIdCounter.incrementAndGet();

        WriteJob job = new WriteJob(jobId);

        writeJobs.put(jobId, job);



        // Submit to background executor

        writeExecutor.submit(() -> {

            job.status = "running";

            job.progress = 10;

            job.events.add("event: pipeline_start\ndata: {\"jobId\":\"" + jobId + "\"}\n\n");

            try {

                Book book = BookProject.loadBook(Paths.get(bookPath));

                TruthState state = new TruthState(Paths.get(bookPath));

                PipelineConfig config = loadConfig(Paths.get(bookPath));

                // Use the router determined above (either from request params or instance modelRouter)

                PipelineRunner runner = new PipelineRunner(config, router);



                // Attach progress listener to push SSE events into WriteJob

                final int totalAgents = 9;

                runner.setProgressListener(new com.novelforge.core.pipeline.ProgressListener() {

                    @Override public void onAgentStart(String name, int step, int total) {

                        job.events.add("event: agent_start\ndata: {\"agent\":\"" + name + "\",\"step\":" + step + ",\"total\":" + total + "}\n\n");

                        job.progress = 10 + (int)((step / (float)total) * 70);

                    }

                    @Override public void onAgentComplete(String name, int step, int total, long elapsedMs, String summary) {

                        job.events.add("event: agent_complete\ndata: {\"agent\":\"" + name + "\",\"step\":" + step + ",\"total\":" + total + ",\"elapsed\":" + elapsedMs + ",\"summary\":\"" + sanitizeForJson(summary) + "\"}\n\n");

                        job.progress = 10 + (int)(((step + 1) / (float)total) * 70);

                    }

                    @Override public void onAgentSkip(String name, int step, int total) {

                        job.events.add("event: agent_skip\ndata: {\"agent\":\"" + name + "\",\"step\":" + step + "}\n\n");

                    }

                    @Override public void onAgentFail(String name, int step, int total, String error) {

                        job.events.add("event: agent_fail\ndata: {\"agent\":\"" + name + "\",\"error\":\"" + sanitizeForJson(error) + "\"}\n\n");

                    }

                    @Override public void onPipelineComplete(int chapters, int words, double score) {

                        job.events.add("event: pipeline_complete\ndata: {\"chapters\":" + chapters + ",\"words\":" + words + ",\"score\":" + score + "}\n\n");

                    }

                    @Override public void onPipelineFail(String error) {

                        job.events.add("event: pipeline_fail\ndata: {\"error\":\"" + sanitizeForJson(error) + "\"}\n\n");

                    }

                });



                job.progress = 30;

                PipelineResult result = null;

                int batchCount = body.has("count") ? body.get("count").asInt() : 0;



                if (mode.equals("batch") && batchCount > 0) {

                    // Batch: write N chapters sequentially

                    int successCount = 0;

                    for (int i = 0; i < batchCount && i < 20; i++) {
                        if (job.status.equals("cancelled")) break; // User cancelled
                        job.events.add("event: batch_chapter_start\ndata: {\"chapter\":" + (i + 1) + ",\"total\":" + Math.min(batchCount, 20) + "}\n\n");

                        PipelineConfig batchConfig = config.clone();

                        if (i > 0) batchConfig.setRunArchitect(false); // outline already built

                        PipelineRunner batchRunner = new PipelineRunner(batchConfig, router);

                        result = batchRunner.writeNextChapter(book, state);

                        if (result.success()) {

                            Chapter chapter = book.getChapters().get(book.getChapters().size() - 1);

                            BookProject.saveChapter(Paths.get(bookPath), chapter);

                            BookProject.saveBookMetadata(Paths.get(bookPath), book);

                            state.saveAll();

                            successCount++;
                            job.events.add("event: batch_chapter_complete\ndata: {\"chapter\":" + (i + 1) + ",\"total\":" + Math.min(batchCount, 20) + "}\n\n");

                        } else {

                            job.error = "Chapter " + (i + 1) + " failed: " + result.errorMessage();

                            job.status = "failed";

                            job.events.add("event: pipeline_fail\ndata: {\"error\":\"" + sanitizeForJson(job.error) + "\"}\n\n");

                            break;

                        }

                    }

                    if (job.status == null || (!job.status.equals("failed") && !job.status.equals("cancelled"))) {

                        job.result = "{\"status\":\"ok\",\"chaptersWritten\":\"" + successCount + "\"}";

                        job.progress = 100;

                        job.status = "completed";

                    }

                } else if (mode.equals("draft")) {

                    result = runner.runDraftOnly(book, state);

                } else {

                    result = runner.writeNextChapter(book, state);

                }



                job.progress = 80;

                if (mode.equals("batch")) {

                    // Batch already handled save/status in loop; skip single-chapter logic

                    // done event will be sent at end of method

                } else if (result != null && result.success()) {

                    Chapter chapter = book.getChapters().get(book.getChapters().size() - 1);

                    Path bookDir = Paths.get(bookPath);

                    BookProject.saveChapter(bookDir, chapter);

                    BookProject.saveBookMetadata(bookDir, book);

                    if (result.hasWarning()) {

                        job.result = "{\"status\":\"ok\",\"warning\":\"" + sanitizeForJson(result.errorMessage()) + "\"}";

                    } else {

                        job.result = "{\"status\":\"ok\"}";

                    }

                    job.progress = 100;

                    job.status = "completed";

                } else if (result != null) {

                    job.error = result.errorMessage();

                    job.status = "failed";

                    job.events.add("event: pipeline_fail\ndata: {\"error\":\"" + sanitizeForJson(result.errorMessage()) + "\"}\n\n");

                } else {

                    job.error = "Unknown pipeline error";

                    job.status = "failed";

                }

            } catch (Exception e) {

                job.error = e.getMessage();

                job.status = "failed";

                job.events.add("event: pipeline_fail\ndata: {\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}\n\n");

            }

        });



        // Return jobId immediately

        ObjectNode response = mapper.createObjectNode();

        response.put("jobId", jobId);

        response.put("status", "pending");

        sendJson(exchange, 200, mapper.writeValueAsString(response));

    }



    // --- API: Write SSE stream (real-time progress) ---

    private void handleWriteStreamApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }



        String query = exchange.getRequestURI().getQuery();

        String jobId = getQueryParam(query, "jobId");

        if (jobId == null) { sendJson(exchange, 400, "{\"error\":\"jobId required\"}"); return; }



        WriteJob job = writeJobs.get(jobId);

        if (job == null) { sendJson(exchange, 404, "{\"error\":\"job not found\"}"); return; }



        // SSE response headers

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");

        exchange.getResponseHeaders().set("Cache-Control", "no-cache");

        exchange.getResponseHeaders().set("Connection", "keep-alive");

        addCorsHeaders(exchange);

        exchange.sendResponseHeaders(200, 0); // 0 = chunked/streaming



        OutputStream os = exchange.getResponseBody();

        int eventIndex = 0;



        try {

            // Stream events until job completes/fails
            while (job.status.equals("pending") || job.status.equals("running")) {

                // Drain buffered events since last check

                while (eventIndex < job.events.size()) {

                    String evt = job.events.get(eventIndex++);

                    os.write(evt.getBytes(StandardCharsets.UTF_8));

                    os.flush();

                }

                Thread.sleep(500); // poll interval

            }

            // Final drain: send remaining events + terminal event

            while (eventIndex < job.events.size()) {

                String evt = job.events.get(eventIndex++);

                os.write(evt.getBytes(StandardCharsets.UTF_8));

                os.flush();

            }

            // Send terminal SSE event

            String terminal = "event: done\ndata: {\"jobId\":\"" + jobId + "\",\"status\":\"" + job.status + "\"}\n\n";

            os.write(terminal.getBytes(StandardCharsets.UTF_8));

            os.flush();

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

        } finally {

            try { os.close(); } catch (Exception ignored) {}

        }

    }



    // --- API: Write job status polling (🟡-2) ---

    private void handleWriteStatusApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }



        String query = exchange.getRequestURI().getQuery();

        String jobId = getQueryParam(query, "jobId");

        if (jobId == null) { sendJson(exchange, 400, "{\"error\":\"jobId required\"}"); return; }



        WriteJob job = writeJobs.get(jobId);

        if (job == null) { sendJson(exchange, 404, "{\"error\":\"job not found\"}"); return; }



        ObjectNode response = mapper.createObjectNode();

        response.put("jobId", job.jobId);

        response.put("status", job.status);

        response.put("progress", job.progress);

        response.put("elapsedSeconds", (System.currentTimeMillis() - job.startTime) / 1000);

        if (job.result != null) response.put("result", job.result);

        if (job.error != null) response.put("error", sanitizeForJson(job.error));

        // Include recent SSE events for polling clients (last 20 events max)

        if (!job.events.isEmpty()) {

            ArrayNode evtArr = mapper.createArrayNode();

            synchronized (job.events) {

                int start = Math.max(0, job.events.size() - 20);

                for (int i = start; i < job.events.size(); i++) {

                    // Extract data portion from SSE formatted string

                    String evt = job.events.get(i);

                    evtArr.add(evt);

                }

            }

            response.set("events", evtArr);

        }

        // Auto-cleanup completed/failed jobs after 5 minutes

        if (job.status.equals("completed") || job.status.equals("failed")) {

            long elapsed = System.currentTimeMillis() - job.startTime;

            if (elapsed > 300_000) writeJobs.remove(jobId);

        }

        sendJson(exchange, 200, mapper.writeValueAsString(response));

    }



    // --- API: Audit ---

    private void handleAuditApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }



        JsonNode body = readBody(exchange);

        String bookPath = body.has("path") ? body.get("path").asText() : null;

        int chapterNum = body.has("chapter") ? body.get("chapter").asInt() : -1;

        String reqApiKey = body.has("apiKey") ? body.get("apiKey").asText() : null;

        final ModelRouter router = resolveModelRouter(body);

        if (bookPath == null || router == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required; path must be within books directory\"}"); return; }



        try {

            Book book = BookProject.loadBook(Paths.get(bookPath));

            TruthState state = new TruthState(Paths.get(bookPath));

            // Use resolved router (either from request params or instance modelRouter with per-agent overrides)

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

            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

        }

    }



    // --- API: State ---

    private void handleStateApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }



        String query = exchange.getRequestURI().getQuery();

        String bookPath = getQueryParam(query, "path");

        String type = getQueryParam(query, "type"); // characters, world, hooks, timeline



        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}"); return; }



        try {

            TruthState state = new TruthState(Paths.get(bookPath));

            String summary;

            switch (type != null ? type : "all") {

                case "characters" -> summary = state.characters().getSummary();

                case "world" -> summary = state.world().getSummary();

                case "hooks" -> summary = state.hooks().getSummary();

                case "timeline" -> summary = state.timeline().getSummary();

                default -> summary = "角色:\n" + state.characters().getSummary() +

                        "\n世界:\n" + state.world().getSummary() +

                        "\n悬念:\n" + state.hooks().getSummary() +

                        "\n时间线:\n" + state.timeline().getSummary();

            }

            ObjectNode response = mapper.createObjectNode();

            response.put("type", type != null ? type : "all");

            response.put("summary", summary);

            sendJson(exchange, 200, mapper.writeValueAsString(response));

        } catch (Exception e) {

            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

        }

    }



    // --- API: Export ---

    // 🟡-7 fix: use core BookExporter for proper EPUB/TXT/MD generation

    private void handleExportApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }



        JsonNode body = readBody(exchange);

        String bookPath = body.has("path") ? body.get("path").asText() : null;

        String format = body.has("format") ? body.get("format").asText() : "txt";

        String coverPath = body.has("cover") ? body.get("cover").asText() : null;



        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}"); return; }



        try {

            Book book = BookProject.loadBook(Paths.get(bookPath));

            String ext = format.equals("epub") ? "epub" : format.equals("md") ? "md" : "txt";

            Path outputPath = Paths.get(bookPath).resolve(book.getTitle() + "." + ext);



            switch (format.toLowerCase()) {

                case "txt" -> com.novelforge.core.export.BookExporter.exportTxt(book, outputPath);

                case "md"  -> com.novelforge.core.export.BookExporter.exportMd(book, outputPath);

                case "epub" -> com.novelforge.core.export.BookExporter.exportEpub(book, outputPath, coverPath);

                default -> { sendJson(exchange, 400, "{\"error\":\"unsupported format\"}"); return; }

            }



            ObjectNode response = mapper.createObjectNode();

            response.put("status", "ok");

            response.put("format", format);

            response.put("outputPath", outputPath.toString());

            response.put("chapters", book.getChapters().size());

            sendJson(exchange, 200, mapper.writeValueAsString(response));

        } catch (Exception e) {

            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

        }

    }



    // --- API: Config ---

    private void handleConfigApi(HttpExchange exchange) throws IOException {

        if (exchange.getRequestMethod().equals("GET")) {

            // Return full studio config (pipeline + global API + agent overrides + presets)

            ObjectNode config = studioConfig.toJson();

            config.put("genreKeys", GenreManager.getInstance().listGenreKeys().toString());

            sendJson(exchange, 200, mapper.writeValueAsString(config));

        } else if (exchange.getRequestMethod().equals("POST")) {

            JsonNode body = readBody(exchange);

            // Pipeline config

            if (body.has("chapterWordsMin")) defaultConfig.setChapterWordsMin(body.get("chapterWordsMin").asInt());

            if (body.has("chapterWordsMax")) defaultConfig.setChapterWordsMax(body.get("chapterWordsMax").asInt());

            if (body.has("auditPassThreshold")) defaultConfig.setAuditPassThreshold(body.get("auditPassThreshold").asDouble());

            if (body.has("maxRevisionPasses")) defaultConfig.setMaxRevisionPasses(body.get("maxRevisionPasses").asInt());

            // Global API config

            if (body.has("globalDefault")) {

                AgentApiConfig globalCfg = AgentApiConfig.fromJson(body.get("globalDefault"));

                studioConfig.setGlobalDefault(globalCfg);

                modelRouter = new ModelRouter(globalCfg.toModelConfig());

            }

            // Per-agent API overrides

            if (body.has("agentOverrides")) {

                JsonNode ov = body.get("agentOverrides");

                java.util.Map<String, AgentApiConfig> overrides = new java.util.LinkedHashMap<>();

                ov.fields().forEachRemaining(field -> {

                    AgentApiConfig cfg = AgentApiConfig.fromJson(field.getValue());

                    overrides.put(field.getKey(), cfg);

                    modelRouter.setAgentModel(field.getKey(), cfg.toModelConfig());

                });

                studioConfig.setAgentOverrides(overrides);

            }

            // Preset switch

            if (body.has("activePreset")) {

                String presetName = body.get("activePreset").asText();

                studioConfig.applyPreset(presetName);

                modelRouter = new ModelRouter(studioConfig.getGlobalDefault().toModelConfig());

                for (java.util.Map.Entry<String, AgentApiConfig> e : studioConfig.getAgentOverrides().entrySet()) {

                    modelRouter.setAgentModel(e.getKey(), e.getValue().toModelConfig());

                }

            }

            // Agent toggles

            if (body.has("runArchitect")) defaultConfig.setRunArchitect(body.get("runArchitect").asBoolean());

            if (body.has("runPlanner")) defaultConfig.setRunPlanner(body.get("runPlanner").asBoolean());

            if (body.has("runComposer")) defaultConfig.setRunComposer(body.get("runComposer").asBoolean());

            if (body.has("runWriter")) defaultConfig.setRunWriter(body.get("runWriter").asBoolean());

            if (body.has("runObserver")) defaultConfig.setRunObserver(body.get("runObserver").asBoolean());

            if (body.has("runReflector")) defaultConfig.setRunReflector(body.get("runReflector").asBoolean());

            if (body.has("runNormalizer")) defaultConfig.setRunNormalizer(body.get("runNormalizer").asBoolean());

            if (body.has("runAuditor")) defaultConfig.setRunAuditor(body.get("runAuditor").asBoolean());

            if (body.has("runReviser")) defaultConfig.setRunReviser(body.get("runReviser").asBoolean());

            // Persist config to disk

            saveDefaultConfig();

            studioConfig.save();

            sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode().put("status", "updated")));

        } else {

            sendJson(exchange, 405, mapper.writeValueAsString(mapper.createObjectNode().put("error", "method not allowed")));

        }

    }

    // --- API: Config Presets ---
    private void handleConfigPresetsApi(HttpExchange exchange) throws IOException {

        if (exchange.getRequestMethod().equals("GET")) {

            ObjectNode result = mapper.createObjectNode();

            for (java.util.Map.Entry<String, StudioConfig.PresetEntry> entry : studioConfig.getPresets().entrySet()) {

                result.set(entry.getKey(), entry.getValue().toJson());

            }

            result.put("activePreset", studioConfig.getActivePreset() != null ? studioConfig.getActivePreset() : "");

            sendJson(exchange, 200, mapper.writeValueAsString(result));

        } else if (exchange.getRequestMethod().equals("POST")) {

            JsonNode body = readBody(exchange);

            String action = body.has("action") ? body.get("action").asText() : "";

            if ("apply".equals(action) && body.has("name")) {

                String presetName = body.get("name").asText();

                studioConfig.applyPreset(presetName);

                modelRouter = new ModelRouter(studioConfig.getGlobalDefault().toModelConfig());

                for (java.util.Map.Entry<String, AgentApiConfig> e : studioConfig.getAgentOverrides().entrySet()) {

                    modelRouter.setAgentModel(e.getKey(), e.getValue().toModelConfig());

                }

                studioConfig.save();

                sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode().put("status", "preset applied").put("activePreset", presetName)));

            } else if ("save".equals(action) && body.has("name")) {

                String name = body.get("name").asText();

                String desc = body.has("description") ? body.get("description").asText() : "";

                StudioConfig.PresetEntry entry = new StudioConfig.PresetEntry(desc, studioConfig.getGlobalDefault().copy(), new java.util.LinkedHashMap<>(studioConfig.getAgentOverrides()));

                studioConfig.getPresets().put(name, entry);

                studioConfig.save();

                sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode().put("status", "preset saved").put("name", name)));

            } else if ("delete".equals(action) && body.has("name")) {

                String name = body.get("name").asText();

                studioConfig.getPresets().remove(name);

                studioConfig.save();

                sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode().put("status", "preset deleted").put("name", name)));

            } else {

                sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "action required: apply|save|delete")));

            }

        } else {

            sendJson(exchange, 405, mapper.writeValueAsString(mapper.createObjectNode().put("error", "method not allowed")));

        }

    }

    // --- API: Config Sample ---
    private void handleConfigSampleApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {

            sendJson(exchange, 405, mapper.writeValueAsString(mapper.createObjectNode().put("error", "GET only")));

            return;

        }

        sendJson(exchange, 200, StudioConfig.getSamplePresetsJsonString());

    }



    // --- API: Writing Progress ---

    private void handleProgressApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }



        String query = exchange.getRequestURI().getQuery();

        String bookPath = getQueryParam(query, "path");

        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}"); return; }



        try {

            Book book = BookProject.loadBook(Paths.get(bookPath));

            com.novelforge.core.models.WritingProgress progress = book.getProgress();

            ObjectNode result = mapper.createObjectNode();

            result.put("totalChapters", progress.getTotalChapters());

            result.put("totalWords", progress.getTotalWords());

            result.put("averageWordsPerChapter", progress.getAverageWordsPerChapter());

            result.put("auditedChapters", progress.getAuditedChapters());

            result.put("passedChapters", progress.getPassedChapters());

            result.put("averageAuditScore", progress.getAverageAuditScore());

            result.put("totalPipelineTimeMs", progress.getTotalPipelineTimeMs());



            // Per-chapter progress data

            ArrayNode chapters = mapper.createArrayNode();

            for (com.novelforge.core.models.WritingProgress.ChapterProgress cp : progress.getChapterProgresses()) {

                ObjectNode cpNode = mapper.createObjectNode();

                cpNode.put("chapterNumber", cp.getChapterNumber());

                cpNode.put("chapterTitle", cp.getChapterTitle());

                cpNode.put("wordCount", cp.getWordCount());

                cpNode.put("audited", cp.isAudited());

                cpNode.put("passed", cp.isPassed());

                cpNode.put("auditScore", cp.getAuditScore());

                cpNode.put("pipelineTimeMs", cp.getPipelineTimeMs());

                ArrayNode timings = mapper.createArrayNode();

                for (com.novelforge.core.models.WritingProgress.AgentTiming at : cp.getAgentTimings()) {

                    ObjectNode atNode = mapper.createObjectNode();

                    atNode.put("agentName", at.getAgentName());

                    atNode.put("durationMs", at.getDurationMs());

                    atNode.put("outputChars", at.getOutputChars());

                    timings.add(atNode);

                }

                cpNode.set("agentTimings", timings);

                chapters.add(cpNode);

            }

            result.set("chapters", chapters);



            sendJson(exchange, 200, mapper.writeValueAsString(result));

        } catch (Exception e) {

            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

        }

    }



    /** Handle diff API — paragraph-level comparison of draft vs final text for a chapter */

    private void handleDiffApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }



        String query = exchange.getRequestURI().getQuery();

        String bookPath = getQueryParam(query, "path");

        int chapterNum = getQueryParam(query, "chapter") != null ? Integer.parseInt(getQueryParam(query, "chapter")) : -1;

        if (bookPath == null || !isPathWithinBooksRoot(bookPath) || chapterNum < 1) {

            sendJson(exchange, 400, "{\"error\":\"path and chapter (>=1) required\"}");

            return;

        }



        try {

            Book book = BookProject.loadBook(Paths.get(bookPath));

            if (chapterNum > book.getChapters().size()) {

                sendJson(exchange, 404, "{\"error\":\"Chapter " + chapterNum + " not found\"}");

                return;

            }

            Chapter chapter = book.getChapters().get(chapterNum - 1);

            String draft = chapter.getDraftText() != null ? chapter.getDraftText() : "";

            String final_ = chapter.getFinalText() != null ? chapter.getFinalText() : "";



            // Split into paragraphs (by double newline or single newline for Chinese text)

            String[] draftParas = draft.split("\\n\\n|\\n");

            String[] finalParas = final_.split("\\n\\n|\\n");



            // Simple paragraph-level diff using LCS-like matching

            ArrayNode diffResult = computeParagraphDiff(draftParas, finalParas);

            ObjectNode response = mapper.createObjectNode();

            response.put("chapterNumber", chapterNum);

            response.put("draftParagraphs", draftParas.length);

            response.put("finalParagraphs", finalParas.length);

            response.set("diff", diffResult);

            sendJson(exchange, 200, mapper.writeValueAsString(response));

        } catch (Exception e) {

            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

        }

    }



    /** Compute paragraph-level diff between draft and final paragraphs */

    private ArrayNode computeParagraphDiff(String[] draftParas, String[] finalParas) {

        ArrayNode result = mapper.createArrayNode();

        // Simple approach: match paragraphs by content, track additions/removes

        java.util.Set<String> draftSet = new java.util.HashSet<>();

        for (String p : draftParas) { draftSet.add(p.trim()); }

        java.util.Set<String> finalSet = new java.util.HashSet<>();

        for (String p : finalParas) { finalSet.add(p.trim()); }



        // Walk through final paragraphs — if in draft, it's "kept"; if not, it's "added"

        int di = 0;

        for (int fi = 0; fi < finalParas.length; fi++) {

            String fp = finalParas[fi].trim();

            if (fp.isEmpty()) continue;

            ObjectNode node = mapper.createObjectNode();

            node.put("index", fi);

            node.put("text", finalParas[fi]);

            if (di < draftParas.length && draftParas[di].trim().equals(fp)) {

                node.put("type", "kept");

                di++;

            } else if (draftSet.contains(fp)) {

                node.put("type", "moved");

            } else {

                node.put("type", "added");

            }

            result.add(node);

        }

        // Remaining draft paragraphs that weren't matched = "removed"

        while (di < draftParas.length) {

            String dp = draftParas[di].trim();

            if (!dp.isEmpty()) {

                ObjectNode node = mapper.createObjectNode();

                node.put("index", di);

                node.put("text", draftParas[di]);

                node.put("type", "removed");

                result.add(node);

            }

            di++;

        }

        return result;

    }



    /** Handle rollback API — list backups or rollback truth state */

    private void handleRollbackApi(HttpExchange exchange) throws IOException {

        String query = exchange.getRequestURI().getQuery();

        String bookPath = getQueryParam(query, "path");

        String action = getQueryParam(query, "action"); // "list" or "rollback"

        String timestamp = getQueryParam(query, "timestamp");



        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) {

            sendJson(exchange, 400, "{\"error\":\"path required\"}");

            return;

        }



        try {

            Book book = BookProject.loadBook(Paths.get(bookPath));

            TruthState truthState = new TruthState(Paths.get(bookPath));



            if ("list".equals(action)) {

                List<Long> versions = truthState.getBackupVersions();

                ArrayNode arr = mapper.createArrayNode();

                for (Long ts : versions) {

                    ObjectNode node = mapper.createObjectNode();

                    node.put("timestamp", ts);

                    node.put("display", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(ts)));

                    arr.add(node);

                }

                ObjectNode response = mapper.createObjectNode();

                response.set("backups", arr);

                sendJson(exchange, 200, mapper.writeValueAsString(response));

            } else if ("rollback".equals(action)) {

                boolean success;

                if (timestamp != null) {

                    success = truthState.rollbackTo(Long.parseLong(timestamp));

                } else {

                    success = truthState.rollback();

                }

                // Reload book after rollback

                book = BookProject.loadBook(Paths.get(bookPath));

                sendJson(exchange, 200, "{\"success\":\"" + success + "\"}");

            } else {

                sendJson(exchange, 400, "{\"error\":\"action must be 'list' or 'rollback'\"}");

            }

        } catch (Exception e) {

            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

        }

    }



    /** Handle write resume API — POST: resume from checkpoint */


    private void handleWriteCancelApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, mapper.writeValueAsString(mapper.createObjectNode().put("error", "method not allowed"))); return; }

        JsonNode body = readBody(exchange);
        String jobId = body.has("jobId") ? body.get("jobId").asText() : null;

        if (jobId == null) { sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "jobId required"))); return; }

        WriteJob job = writeJobs.get(jobId);
        if (job == null) { sendJson(exchange, 404, mapper.writeValueAsString(mapper.createObjectNode().put("error", "job not found"))); return; }

        job.status = "cancelled";
        job.error = "Cancelled by user";
        job.events.add("event: pipeline_fail\ndata: {\"error\":\"Cancelled by user\"}\n\n");
        sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode().put("status", "cancelled").put("jobId", jobId)));
    }

    private void handleWriteResumeApi(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("POST")) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }



        JsonNode body = readBody(exchange);

        String bookPath = body.has("path") ? body.get("path").asText() : null;

        final ModelRouter router = resolveModelRouter(body);

        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}"); return; }

        if (router == null) { sendJson(exchange, 400, "{\"error\":\"apiKey required\"}"); return; }



        Path bookDir = Paths.get(bookPath);

        Path checkpointFile = bookDir.resolve("checkpoint.json");

        if (!java.nio.file.Files.exists(checkpointFile)) { sendJson(exchange, 404, "{\"error\":\"No checkpoint found for this book\"}"); return; }



        String jobId = "job-" + jobIdCounter.incrementAndGet();

        WriteJob job = new WriteJob(jobId);

        writeJobs.put(jobId, job);



        writeExecutor.submit(() -> {

            job.status = "running";

            job.progress = 10;

            job.events.add("event: pipeline_start\ndata: {\"jobId\":\"" + jobId + "\",\"mode\":\"resume\"}\n\n");

            try {

                com.fasterxml.jackson.databind.ObjectMapper cpMapper = new com.fasterxml.jackson.databind.ObjectMapper();

                JsonNode cpNode = cpMapper.readTree(java.nio.file.Files.readString(checkpointFile));

                int lastIndex = cpNode.has("lastIndex") ? cpNode.get("lastIndex").asInt() : 0;

                String lastAgent = cpNode.has("lastAgent") ? cpNode.get("lastAgent").asText() : "";



                Book book = BookProject.loadBook(bookDir);

                TruthState state = new TruthState(bookDir);

                PipelineConfig config = loadConfig(bookDir);

                // Use resolved router (either from request params or instance modelRouter with per-agent overrides)



                PipelineContext resumeCtx = new PipelineContext(book, state, config);

                resumeCtx.updateCheckpoint(lastIndex, lastAgent);

                if (cpNode.has("architectOutput")) resumeCtx.setArchitectOutput(cpNode.get("architectOutput").asText());

                if (cpNode.has("plannerOutput")) resumeCtx.setPlannerOutput(cpNode.get("plannerOutput").asText());

                if (cpNode.has("composerOutput")) resumeCtx.setComposerOutput(cpNode.get("composerOutput").asText());

                if (cpNode.has("writerDraft")) resumeCtx.setWriterDraft(cpNode.get("writerDraft").asText());

                if (cpNode.has("observerOutput")) resumeCtx.setObserverOutput(cpNode.get("observerOutput").asText());

                if (cpNode.has("reflectorOutput")) resumeCtx.setReflectorOutput(cpNode.get("reflectorOutput").asText());

                if (cpNode.has("normalizerOutput")) resumeCtx.setNormalizerOutput(cpNode.get("normalizerOutput").asText());



                PipelineRunner runner = new PipelineRunner(config, router);

                runner.setProgressListener(new com.novelforge.core.pipeline.ProgressListener() {

                    @Override public void onAgentStart(String name, int step, int total) {

                        job.events.add("event: agent_start\ndata: {\"agent\":\"" + name + "\",\"step\":" + step + ",\"total\":" + total + "}\n\n");

                        job.progress = 10 + (int)((step / (float)total) * 70);

                    }

                    @Override public void onAgentComplete(String name, int step, int total, long elapsedMs, String summary) {

                        job.events.add("event: agent_complete\ndata: {\"agent\":\"" + name + "\",\"step\":" + step + ",\"total\":" + total + ",\"elapsed\":" + elapsedMs + "}\n\n");

                        job.progress = 10 + (int)(((step + 1) / (float)total) * 70);

                    }

                    @Override public void onAgentSkip(String name, int step, int total) {

                        job.events.add("event: agent_skip\ndata: {\"agent\":\"" + name + "}\n\n");

                    }

                    @Override public void onAgentFail(String name, int step, int total, String error) {

                        job.events.add("event: agent_fail\ndata: {\"agent\":\"" + name + "\",\"error\":\"" + sanitizeForJson(error) + "}\n\n");

                    }

                    @Override public void onPipelineComplete(int chapters, int words, double score) {

                        job.events.add("event: pipeline_complete\ndata: {\"chapters\":" + chapters + ",\"words\":" + words + ",\"score\":" + score + "}\n\n");

                    }

                    @Override public void onPipelineFail(String error) {

                        job.events.add("event: pipeline_fail\ndata: {\"error\":\"" + sanitizeForJson(error) + "}\n\n");

                    }

                });



                job.progress = 30;

                PipelineResult result = runner.resumeChapter(book, state, resumeCtx);



                if (result != null && result.success()) {

                    Chapter chapter = book.getChapters().get(book.getChapters().size() - 1);

                    BookProject.saveChapter(bookDir, chapter);

                    BookProject.saveBookMetadata(bookDir, book);

                    state.saveAll();

                    java.nio.file.Files.deleteIfExists(checkpointFile);

                    job.result = mapper.writeValueAsString(mapper.createObjectNode().put("status", "ok").put("resumedFrom", lastAgent));

                    job.progress = 100;

                    job.status = "completed";

                } else if (result != null) {

                    job.error = result.errorMessage();

                    job.status = "failed";

                    job.events.add("event: pipeline_fail\ndata: {\"error\":\"" + sanitizeForJson(result.errorMessage()) + "}\n\n");

                } else {

                    job.error = "Unknown resume error";

                    job.status = "failed";

                }

            } catch (Exception e) {

                job.error = e.getMessage();

                job.status = "failed";

                job.events.add("event: pipeline_fail\ndata: {\"error\":\"" + sanitizeForJson(e.getMessage()) + "}\n\n");

            }

        });



        ObjectNode response = mapper.createObjectNode();

        response.put("jobId", jobId);

        response.put("status", "pending");

        response.put("resumingFrom", bookPath);

        sendJson(exchange, 200, mapper.writeValueAsString(response));

    }

    /** Handle style API — GET current style, POST set/update style */

    private void handleStyleApi(HttpExchange exchange) throws IOException {

        String query = exchange.getRequestURI().getQuery();

        String bookPath = getQueryParam(query, "path");



        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) {

            sendJson(exchange, 400, "{\"error\":\"path required\"}");

            return;

        }



        try {

            if (exchange.getRequestMethod().equals("GET")) {

                Book book = BookProject.loadBook(Paths.get(bookPath));

                WritingStyle style = book.getStyle();

                ObjectNode response = mapper.createObjectNode();

                if (style != null) {

                    response.put("name", style.getName() != null ? style.getName() : "");

                    response.put("description", style.getDescription() != null ? style.getDescription() : "");

                    response.put("vocabularyPattern", style.getVocabularyPattern() != null ? style.getVocabularyPattern() : "");

                    response.put("sentenceStructure", style.getSentenceStructure() != null ? style.getSentenceStructure() : "");

                    response.put("pacingPattern", style.getPacingPattern() != null ? style.getPacingPattern() : "");

                    response.put("dialogueStyle", style.getDialogueStyle() != null ? style.getDialogueStyle() : "");

                    response.put("descriptionStyle", style.getDescriptionStyle() != null ? style.getDescriptionStyle() : "");

                    response.put("referenceSample", style.getReferenceSample() != null ? style.getReferenceSample() : "");

                    response.put("hasStyle", true);

                } else {

                    response.put("hasStyle", false);

                }

                sendJson(exchange, 200, mapper.writeValueAsString(response));

            } else if (exchange.getRequestMethod().equals("POST")) {

                JsonNode json = readBody(exchange);

                Book book = BookProject.loadBook(Paths.get(bookPath));

                WritingStyle style = book.getStyle();

                if (style == null) style = new WritingStyle();

                if (json.has("name")) style.setName(json.get("name").asText());

                if (json.has("description")) style.setDescription(json.get("description").asText());

                if (json.has("vocabularyPattern")) style.setVocabularyPattern(json.get("vocabularyPattern").asText());

                if (json.has("sentenceStructure")) style.setSentenceStructure(json.get("sentenceStructure").asText());

                if (json.has("pacingPattern")) style.setPacingPattern(json.get("pacingPattern").asText());

                if (json.has("dialogueStyle")) style.setDialogueStyle(json.get("dialogueStyle").asText());

                if (json.has("descriptionStyle")) style.setDescriptionStyle(json.get("descriptionStyle").asText());

                if (json.has("referenceSample")) style.setReferenceSample(json.get("referenceSample").asText());

                book.setStyle(style);

                BookProject.saveBookMetadata(Paths.get(bookPath), book);

                sendJson(exchange, 200, "{\"success\":true}");

            } else {

                sendJson(exchange, 405, "{\"error\":\"GET or POST only\"}");

            }

        } catch (Exception e) {

            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

        }

    }




        // --- API: Outline Synopsis ---
    private void handleOutlineSynopsisApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        final ModelRouter router = resolveModelRouter(body);

        if (bookPath == null || router == null || !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path and apiKey required; path must be within books directory\"}"); return;
        }
        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            TruthState state = new TruthState(Paths.get(bookPath));
            // Use resolved router (either from request params or instance modelRouter with per-agent overrides)
            LlmClient client = router.getClientForAgent("Architect");
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildOutlineSynopsisPrompt(book, state);
            String result = client.chatComplete(messages, router.getModelForAgent("Architect"), 0.5, 8000);
            // Save outline to book
            book.setOutline(result);
            BookProject.saveBookMetadata(Paths.get(bookPath), book);
            ObjectNode response = mapper.createObjectNode();
            response.put("status", "ok");
            response.put("outline", result);
            sendJson(exchange, 200, response.toString());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    // --- API: Volume Synopsis ---
    private void handleVolumeSynopsisApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        int volumeStart = body.has("volumeStart") ? body.get("volumeStart").asInt() : 1;
        int volumeEnd = body.has("volumeEnd") ? body.get("volumeEnd").asInt() : 10;
        final ModelRouter router = resolveModelRouter(body);

        if (bookPath == null || router == null || !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path and apiKey required; path must be within books directory\"}"); return;
        }
        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            TruthState state = new TruthState(Paths.get(bookPath));
            if (book.getChapters().isEmpty()) {
                sendJson(exchange, 400, "{\"error\":\"No chapters written yet; write chapters first\"}"); return;
            }
            // Use resolved router (either from request params or instance modelRouter with per-agent overrides)
            LlmClient client = router.getClientForAgent("Architect");
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildVolumeSynopsisPrompt(book, state, volumeStart, volumeEnd);
            String result = client.chatComplete(messages, router.getModelForAgent("Architect"), 0.4, 6000);
            ObjectNode response = mapper.createObjectNode();
            response.put("status", "ok");
            response.put("synopsis", result);
            response.put("volumeStart", volumeStart);
            response.put("volumeEnd", volumeEnd);
            sendJson(exchange, 200, response.toString());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    // --- API: AI Trace Detection & Removal ---
    private void handleAiTraceApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        int chapterNum = body.has("chapter") ? body.get("chapter").asInt() : -1;
        final ModelRouter router = resolveModelRouter(body);

        if (bookPath == null || router == null || !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path and apiKey required; path must be within books directory\"}"); return;
        }
        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            if (book.getChapters().isEmpty()) {
                sendJson(exchange, 400, "{\"error\":\"No chapters written yet\"}"); return;
            }
            int idx = chapterNum > 0 ? chapterNum - 1 : book.getChapters().size() - 1;
            Chapter ch = book.getChapters().get(idx);
            String text = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
            if (text == null || text.isEmpty()) {
                sendJson(exchange, 400, "{\"error\":\"Chapter text is empty\"}"); return;
            }
            // Use resolved router (either from request params or instance modelRouter with per-agent overrides)
            LlmClient client = router.getClientForAgent("Auditor");
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildAiTracePrompt(text);
            String result = client.chatComplete(messages, router.getModelForAgent("Auditor"), 0.3, 8000);
            ObjectNode response = mapper.createObjectNode();
            response.put("status", "ok");
            response.put("chapter", chapterNum > 0 ? chapterNum : book.getChapters().size());
            response.put("analysis", result);
            sendJson(exchange, 200, response.toString());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    // --- API: Outline Generate (from prompt + genre) ---
    private void handleOutlineGenerateApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String prompt = body.has("prompt") ? body.get("prompt").asText() : null;
        String genre = body.has("genre") ? body.get("genre").asText() : "xuanhuan";
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        final ModelRouter router = resolveModelRouter(body);

        if (prompt == null || router == null) {
            sendJson(exchange, 400, "{\"error\":\"prompt required\"}"); return;
        }
        if (bookPath != null && !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path must be within books directory\"}"); return;
        }
        try {
            // Use resolved router
            LlmClient client = router.getClientForAgent("Architect");
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildOutlineFromPromptPrompt(prompt, genre);
            String result = client.chatComplete(messages, router.getModelForAgent("Architect"), 0.6, 8000);
            // Optionally save to book if path provided
            if (bookPath != null) {
                Book book = BookProject.loadBook(Paths.get(bookPath));
                book.setOutline(result);
                BookProject.saveBookMetadata(Paths.get(bookPath), book);
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("status", "ok");
            response.put("outline", result);
            sendJson(exchange, 200, response.toString());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    // --- API: Volume Outline Generate (from outline + prompt + genre) ---
    private void handleVolumeGenerateApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String outline = body.has("outline") ? body.get("outline").asText() : null;
        String prompt = body.has("prompt") ? body.get("prompt").asText() : "";
        String genre = body.has("genre") ? body.get("genre").asText() : "xuanhuan";
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        final ModelRouter router = resolveModelRouter(body);

        if (router == null) { sendJson(exchange, 400, "{\"error\":\"apiKey required\"}"); return; }

        if (outline == null && bookPath != null && isPathWithinBooksRoot(bookPath)) {
            try {
                Book book = BookProject.loadBook(Paths.get(bookPath));
                outline = book.getOutline();
            } catch (Exception e) { outline = ""; }
        }
        if (outline == null || outline.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"outline required (provide in body or select a book with existing outline)\"}"); return;
        }
        try {
            // Use resolved router
            LlmClient client = router.getClientForAgent("Architect");
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildVolumeOutlinePrompt(outline, prompt, genre);
            String result = client.chatComplete(messages, router.getModelForAgent("Architect"), 0.5, 8000);
            ObjectNode response = mapper.createObjectNode();
            response.put("status", "ok");
            response.put("volumeOutline", result);
            sendJson(exchange, 200, response.toString());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    // --- API: Chapter Revise (from outline/volume + prompt) ---
    private void handleChapterReviseApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        int chapterNum = body.has("chapter") ? body.get("chapter").asInt() : -1;
        String prompt = body.has("prompt") ? body.get("prompt").asText() : null;
        String source = body.has("source") ? body.get("source").asText() : "outline";
        final ModelRouter router = resolveModelRouter(body);

        if (bookPath == null || router == null || prompt == null || !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path, apiKey, and prompt required; path must be within books directory\"}"); return;
        }
        if (chapterNum < 1) {
            sendJson(exchange, 400, "{\"error\":\"chapter number required (>=1)\"}"); return;
        }
        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            TruthState state = new TruthState(Paths.get(bookPath));
            if (chapterNum > book.getChapters().size()) {
                sendJson(exchange, 400, "{\"error\":\"Chapter " + chapterNum + " not found (book has " + book.getChapters().size() + " chapters)\"}"); return;
            }
            // Get source content (outline or volume outline)
            String sourceContent;
            if ("volume".equals(source)) {
                // Try to use volume outline if available, fallback to outline
                sourceContent = book.getOutline() != null ? book.getOutline() : "";
            } else {
                sourceContent = book.getOutline() != null ? book.getOutline() : "";
            }
            // Use resolved router
            LlmClient client = router.getClientForAgent("Reviser");
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildChapterRevisionPrompt(book, state, chapterNum, prompt, sourceContent);
            String result = client.chatComplete(messages, router.getModelForAgent("Reviser"), 0.4, 8000);
            // Save revised chapter text
            Chapter ch = book.getChapters().get(chapterNum - 1);
            ch.setFinalText(result);
            BookProject.saveChapter(Paths.get(bookPath), ch);
            BookProject.saveBookMetadata(Paths.get(bookPath), book);
            ObjectNode response = mapper.createObjectNode();
            response.put("status", "ok");
            response.put("chapter", chapterNum);
            response.put("revisedText", result);
            sendJson(exchange, 200, response.toString());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    // --- API: Characters CRUD ---
    private void handleCharactersApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getQuery();
        String bookPath = getQueryParam(query, "path");
        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}"); return;
        }
        try {
            TruthState state = new TruthState(Paths.get(bookPath));
            switch (method) {
                case "GET" -> {
                    JsonNode chars = state.characters().listAll();
                    sendJson(exchange, 200, mapper.writeValueAsString(chars));
                }
                case "PUT" -> {
                    JsonNode body = readBody(exchange);
                    String name = body.has("name") ? body.get("name").asText() : null;
                    if (name == null) { sendJson(exchange, 400, "{\"error\":\"name required\"}"); return; }
                    state.characters().upsertCharacter(name, body);
                    state.characters().save();
                    sendJson(exchange, 200, "{\"status\":\"ok\"}");
                }
                case "DELETE" -> {
                    JsonNode body = readBody(exchange);
                    String name = body.has("name") ? body.get("name").asText() : null;
                    if (name == null) { sendJson(exchange, 400, "{\"error\":\"name required\"}"); return; }
                    state.characters().deleteCharacter(name);
                    sendJson(exchange, 200, "{\"status\":\"ok\"}");
                }
                default -> sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            }
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    // --- API: Hooks CRUD ---
    private void handleHooksApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getQuery();
        String bookPath = getQueryParam(query, "path");
        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}"); return;
        }
        try {
            TruthState state = new TruthState(Paths.get(bookPath));
            switch (method) {
                case "GET" -> {
                    JsonNode hooks = state.hooks().listAll();
                    sendJson(exchange, 200, mapper.writeValueAsString(hooks));
                }
                case "PUT" -> {
                    JsonNode body = readBody(exchange);
                    String hookId = body.has("id") ? body.get("id").asText() : null;
                    String description = body.has("description") ? body.get("description").asText() : "";
                    String priority = body.has("priority") ? body.get("priority").asText() : "medium";
                    if (hookId == null) { sendJson(exchange, 400, "{\"error\":\"id required\"}"); return; }
                    state.hooks().updateHook(hookId, description, priority);
                    sendJson(exchange, 200, "{\"status\":\"ok\"}");
                }
                case "DELETE" -> {
                    JsonNode body = readBody(exchange);
                    String hookId = body.has("id") ? body.get("id").asText() : null;
                    if (hookId == null) { sendJson(exchange, 400, "{\"error\":\"id required\"}"); return; }
                    state.hooks().deleteHook(hookId);
                    sendJson(exchange, 200, "{\"status\":\"ok\"}");
                }
                default -> sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            }
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    // --- API: Chapter Synopsis Generation ---
    private void handleChapterSynopsisApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String outlineOrVolume = body.has("source") ? body.get("source").asText() : null;
        String prompt = body.has("prompt") ? body.get("prompt").asText() : "";
        String genre = body.has("genre") ? body.get("genre").asText() : "xuanhuan";
        final ModelRouter router = resolveModelRouter(body);

        // Also accept path-based source: load outline from book if source not provided directly
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        if (router == null) { sendJson(exchange, 400, "{\"error\":\"apiKey required\"}"); return; }
        // If source text not provided directly, try to load from book outline
        if (outlineOrVolume == null || outlineOrVolume.isEmpty()) {
            if (bookPath != null && isPathWithinBooksRoot(bookPath)) {
                try {
                    Book book = BookProject.loadBook(Paths.get(bookPath));
                    outlineOrVolume = book.getOutline() != null ? book.getOutline() : "";
                } catch (Exception e) {
                    outlineOrVolume = "";
                }
            } else {
                outlineOrVolume = "";
            }
        }
        try {
            // Use resolved router
            LlmClient client = router.getClientForAgent("Architect");
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildChapterSynopsisPrompt(outlineOrVolume, prompt, genre);
            String result = client.chatComplete(messages, router.getModelForAgent("Architect"), 0.7, 8000);
            // If book path provided, save synopsis into book outline (append chapter synopsis section)
            if (bookPath != null && isPathWithinBooksRoot(bookPath)) {
                Book book = BookProject.loadBook(Paths.get(bookPath));
                String existingOutline = book.getOutline() != null ? book.getOutline() : "";
                // Append chapter synopsis section
                String updatedOutline = existingOutline + "\n\n--- 章节梗概 ---\n" + result;
                book.setOutline(updatedOutline);
                BookProject.saveBookMetadata(Paths.get(bookPath), book);
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("status", "ok");
            response.put("synopsis", result);
            sendJson(exchange, 200, response.toString());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    private void handleVersionApi(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String json = mapper.writeValueAsString(Map.of(
                    "version", Version.VERSION,
                    "name", Version.NAME,
                    "full", Version.full()
                ));
                sendJson(exchange, 200, json);
            } else {
                sendJson(exchange, 405, "{\"error\":\"GET only\"}");
            }
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    /** Sanitize string for safe embedding in JSON — uses ObjectMapper for correctness */

    private String sanitizeForJson(String s) {

        if (s == null) return "null";

        try {

            return mapper.writeValueAsString(s);

        } catch (Exception e) {

            // fallback: manual escape including \u2028/\u2029

            StringBuilder sb = new StringBuilder(s.length());

            for (int i = 0; i < s.length(); i++) {

                char c = s.charAt(i);

                switch (c) {

                    case '"'  -> sb.append("\\\"");

                    case '\\' -> sb.append("\\\\");

                    case '\n'  -> sb.append("\\n");

                    case '\r'  -> sb.append("\\r");

                    case '\t'  -> sb.append("\\t");

                    case '\b'  -> sb.append("\\b");

                    case '\f'  -> sb.append("\\f");

                    default   -> {

                        if (c < 0x20 || c == '\u2028' || c == '\u2029') sb.append(String.format("\\u%04x", (int) c));

                        else sb.append(c);

                    }

                }

            }

            return sb.toString();

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

        // 🟡-4 fix: use shared reloadFromJson instead of duplicating the parsing logic

        config.reloadFromJson(configFile);

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

        addCorsHeaders(exchange);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

        exchange.sendResponseHeaders(code, bytes.length);

        OutputStream os = exchange.getResponseBody();

        os.write(bytes);

        os.close();

    }



    /** 🟡-1: Generate a random 16-char token using SecureRandom for local API authentication */

    private String generateToken() {

        SecureRandom rng = new SecureRandom();

        byte[] bytes = new byte[16];

        rng.nextBytes(bytes);

        StringBuilder sb = new StringBuilder(16);

        for (byte b : bytes) sb.append((char) ('A' + ((b & 0xFF) % 26)));

        return sb.toString();

    }



    /** 🟡-1: Validate auth token from request header or query param */

    private boolean validateAuth(HttpExchange exchange) {

        // Check Authorization header: Bearer <token>

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {

            return authHeader.substring(7).equals(authToken);

        }

        // Check query param: ?token=<token>

        String query = exchange.getRequestURI().getQuery();

        String tokenParam = getQueryParam(query, "token");

        if (tokenParam != null) {

            return tokenParam.equals(authToken);

        }

        // Static resources and OPTIONS don't need auth

        return false;

    }



    /** 🔴-2: Validate path is within booksRoot — prevents path traversal */

    private boolean isPathWithinBooksRoot(String rawPath) {

        if (rawPath == null) return false;

        Path path = Paths.get(rawPath).normalize();

        return path.startsWith(booksRoot.normalize());

    }



    /** 🔴-3: Escape HTML special characters to prevent XSS */

    private String escapeHtml(String s) {

        if (s == null) return "";

        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

                .replace("\"", "&quot;").replace("'", "&#x27;");

    }



    /** 🟡-1: Send 401 Unauthorized */

    private void sendUnauthorized(HttpExchange exchange) throws IOException {

        sendJson(exchange, 401, "{\"error\":\"authentication required — provide token via Authorization header or ?token param\"}");

    }



    /** Add CORS headers for cross-origin requests (fixes 🔴-3: all POST endpoints blocked by browser CORS) */

    private void addCorsHeaders(HttpExchange exchange) {

        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");

        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");

    }



    /** Handle CORS preflight OPTIONS requests */

    /** Resolve API credentials from request body, falling back to studio config. */

    private String[] resolveApiCredentials(JsonNode body) {

        String apiKey = body.has("apiKey") ? body.get("apiKey").asText() : null;

        String baseUrl = body.has("baseUrl") ? body.get("baseUrl").asText() : null;

        String modelId = body.has("model") ? body.get("model").asText() : null;

        if (apiKey == null || apiKey.isEmpty()) apiKey = studioConfig.getGlobalDefault().resolveApiKey();

        if (baseUrl == null || baseUrl.isEmpty()) baseUrl = studioConfig.getGlobalDefault().getBaseUrl();

        if (modelId == null || modelId.isEmpty()) modelId = studioConfig.getGlobalDefault().getModel();

        if (baseUrl == null || baseUrl.isEmpty()) baseUrl = "https://api.openai.com/v1";

        if (modelId == null || modelId.isEmpty()) modelId = "gpt-4o";

        return new String[]{apiKey, baseUrl, modelId};

    }

    /** Resolve ModelRouter: if request provides apiKey, build from request params; otherwise use instance modelRouter (with per-agent overrides). */
    private ModelRouter resolveModelRouter(JsonNode body) {
        String reqApiKey = body.has("apiKey") ? body.get("apiKey").asText() : null;
        String reqBaseUrl = body.has("baseUrl") ? body.get("baseUrl").asText() : null;
        String reqModelId = body.has("model") ? body.get("model").asText() : null;
        if (reqApiKey != null && !reqApiKey.isEmpty()) {
            String baseUrl = reqBaseUrl != null && !reqBaseUrl.isEmpty() ? reqBaseUrl : "https://api.openai.com/v1";
            String modelId = reqModelId != null && !reqModelId.isEmpty() ? reqModelId : "gpt-4o";
            return new ModelRouter(new ModelRouter.ModelConfig("openai", modelId, baseUrl, reqApiKey));
        }
        return this.modelRouter;
    }





    private void handleCorsPreflight(HttpExchange exchange) throws IOException {

        addCorsHeaders(exchange);

        exchange.sendResponseHeaders(204, -1); // 204 No Content for preflight

        exchange.getResponseBody().close();

    }



    // ==================== 参考文献 / 参照作品 API ====================

    private void handleBookReferencesApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getQuery();

        // GET /api/book/references?path=xxx — 获取参考文献列表
        if (method.equals("GET") && query != null) {
            String bookPath = getQueryParam(query, "path");
            if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "path required"))); return; }
            Book book = BookProject.loadBook(Paths.get(bookPath));

            ArrayNode refsArr = mapper.createArrayNode();
            for (Reference ref : book.getReferences()) {
                ObjectNode refNode = mapper.createObjectNode();
                refNode.put("id", ref.getId());
                refNode.put("title", ref.getTitle());
                if (ref.getAuthor() != null) refNode.put("author", ref.getAuthor());
                if (ref.getType() != null) refNode.put("type", ref.getType());
                if (ref.getSummary() != null) refNode.put("summary", ref.getSummary());
                if (ref.getNotes() != null) refNode.put("notes", ref.getNotes());
                if (ref.getUrl() != null) refNode.put("url", ref.getUrl());
                refsArr.add(refNode);
            }
            sendJson(exchange, 200, mapper.writeValueAsString(refsArr));
            return;
        }

        // POST /api/book/references — 添加或更新参考文献
        if (method.equals("POST")) {
            JsonNode body = readBody(exchange);
            String bookPath = body.has("path") ? body.get("path").asText() : null;
            if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "path required"))); return; }
            Book book = BookProject.loadBook(Paths.get(bookPath));

            Reference ref = new Reference();
            if (body.has("id")) ref.setId(body.get("id").asText());
            ref.setTitle(body.has("title") ? body.get("title").asText() : "Untitled");
            ref.setAuthor(body.has("author") ? body.get("author").asText() : null);
            ref.setType(body.has("type") ? body.get("type").asText() : "book");
            ref.setCategory("reference");
            ref.setSummary(body.has("summary") ? body.get("summary").asText() : null);
            ref.setNotes(body.has("notes") ? body.get("notes").asText() : null);
            ref.setUrl(body.has("url") ? body.get("url").asText() : null);

            // If id matches existing, update it; otherwise append
            java.util.List<Reference> refs = book.getReferences();
            boolean updated = false;
            for (int i = 0; i < refs.size(); i++) {
                if (refs.get(i).getId().equals(ref.getId())) { refs.set(i, ref); updated = true; break; }
            }
            if (!updated) refs.add(ref);

            BookProject.saveBookMetadata(Paths.get(bookPath), book);
            ObjectNode resp = mapper.createObjectNode().put("status", "ok").put("id", ref.getId());
            sendJson(exchange, 200, mapper.writeValueAsString(resp));
            return;
        }

        // DELETE /api/book/references?path=xxx&id=yyy — 删除参考文献
        if (method.equals("DELETE") && query != null) {
            String bookPath = getQueryParam(query, "path");
            String refId = getQueryParam(query, "id");
            if (bookPath == null || !isPathWithinBooksRoot(bookPath) || refId == null) { sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "path and id required"))); return; }
            Book book = BookProject.loadBook(Paths.get(bookPath));
            boolean removed = book.getReferences().removeIf(r -> r.getId().equals(refId));
            if (!removed) { sendJson(exchange, 404, mapper.writeValueAsString(mapper.createObjectNode().put("error", "reference not found"))); return; }
            BookProject.saveBookMetadata(Paths.get(bookPath), book);
            sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode().put("status", "deleted").put("id", refId)));
            return;
        }

        sendJson(exchange, 405, mapper.writeValueAsString(mapper.createObjectNode().put("error", "method not allowed")));
    }

    private void handleBookInspirationsApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getQuery();

        // GET /api/book/inspirations?path=xxx — 获取参照作品列表
        if (method.equals("GET") && query != null) {
            String bookPath = getQueryParam(query, "path");
            if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "path required"))); return; }
            Book book = BookProject.loadBook(Paths.get(bookPath));

            ArrayNode inspArr = mapper.createArrayNode();
            for (Reference insp : book.getInspirations()) {
                ObjectNode inspNode = mapper.createObjectNode();
                inspNode.put("id", insp.getId());
                inspNode.put("title", insp.getTitle());
                if (insp.getAuthor() != null) inspNode.put("author", insp.getAuthor());
                if (insp.getType() != null) inspNode.put("type", insp.getType());
                if (insp.getSummary() != null) inspNode.put("summary", insp.getSummary());
                if (insp.getNotes() != null) inspNode.put("notes", insp.getNotes());
                if (insp.getUrl() != null) inspNode.put("url", insp.getUrl());
                inspArr.add(inspNode);
            }
            sendJson(exchange, 200, mapper.writeValueAsString(inspArr));
            return;
        }

        // POST /api/book/inspirations — 添加或更新参照作品
        if (method.equals("POST")) {
            JsonNode body = readBody(exchange);
            String bookPath = body.has("path") ? body.get("path").asText() : null;
            if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "path required"))); return; }
            Book book = BookProject.loadBook(Paths.get(bookPath));

            Reference insp = new Reference();
            if (body.has("id")) insp.setId(body.get("id").asText());
            insp.setTitle(body.has("title") ? body.get("title").asText() : "Untitled");
            insp.setAuthor(body.has("author") ? body.get("author").asText() : null);
            insp.setType(body.has("type") ? body.get("type").asText() : "book");
            insp.setCategory("inspiration");
            insp.setSummary(body.has("summary") ? body.get("summary").asText() : null);
            insp.setNotes(body.has("notes") ? body.get("notes").asText() : null);
            insp.setUrl(body.has("url") ? body.get("url").asText() : null);

            java.util.List<Reference> insps = book.getInspirations();
            boolean updated = false;
            for (int i = 0; i < insps.size(); i++) {
                if (insps.get(i).getId().equals(insp.getId())) { insps.set(i, insp); updated = true; break; }
            }
            if (!updated) insps.add(insp);

            BookProject.saveBookMetadata(Paths.get(bookPath), book);
            ObjectNode resp = mapper.createObjectNode().put("status", "ok").put("id", insp.getId());
            sendJson(exchange, 200, mapper.writeValueAsString(resp));
            return;
        }

        // DELETE /api/book/inspirations?path=xxx&id=yyy — 删除参照作品
        if (method.equals("DELETE") && query != null) {
            String bookPath = getQueryParam(query, "path");
            String inspId = getQueryParam(query, "id");
            if (bookPath == null || !isPathWithinBooksRoot(bookPath) || inspId == null) { sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "path and id required"))); return; }
            Book book = BookProject.loadBook(Paths.get(bookPath));
            boolean removed = book.getInspirations().removeIf(r -> r.getId().equals(inspId));
            if (!removed) { sendJson(exchange, 404, mapper.writeValueAsString(mapper.createObjectNode().put("error", "inspiration not found"))); return; }
            BookProject.saveBookMetadata(Paths.get(bookPath), book);
            sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode().put("status", "deleted").put("id", inspId)));
            return;
        }

        sendJson(exchange, 405, mapper.writeValueAsString(mapper.createObjectNode().put("error", "method not allowed")));
    }

    /** Persist defaultConfig to ~/.NovelForge/config/pipeline.json */

    private void saveDefaultConfig() {

        Path configDir = Paths.get(System.getProperty("user.home"), "NovelForge", "config");

        try {

            Files.createDirectories(configDir);

            ObjectNode cfg = mapper.createObjectNode();

            cfg.put("chapterWordsMin", defaultConfig.getChapterWordsMin());

            cfg.put("chapterWordsMax", defaultConfig.getChapterWordsMax());

            cfg.put("auditPassThreshold", defaultConfig.getAuditPassThreshold());

            cfg.put("maxRevisionPasses", defaultConfig.getMaxRevisionPasses());

            cfg.put("runArchitect", defaultConfig.isRunArchitect());

            cfg.put("runPlanner", defaultConfig.isRunPlanner());

            cfg.put("runComposer", defaultConfig.isRunComposer());

            cfg.put("runWriter", defaultConfig.isRunWriter());

            cfg.put("runObserver", defaultConfig.isRunObserver());

            cfg.put("runReflector", defaultConfig.isRunReflector());

            cfg.put("runNormalizer", defaultConfig.isRunNormalizer());

            cfg.put("runAuditor", defaultConfig.isRunAuditor());

            cfg.put("runReviser", defaultConfig.isRunReviser());

            // LLM defaults (🟡-11: pipeline.json should include model/provider/baseUrl)

            String envProvider = System.getenv().containsKey("LLM_PROVIDER") ? System.getenv("LLM_PROVIDER") : "openai";

            String envModel = System.getenv().containsKey("LLM_MODEL") ? System.getenv("LLM_MODEL") : "gpt-4o";

            String envBaseUrl = System.getenv().containsKey("LLM_BASE_URL") ? System.getenv("LLM_BASE_URL") : "https://api.openai.com/v1";

            cfg.put("defaultProvider", envProvider);

            cfg.put("defaultModel", envModel);

            cfg.put("defaultBaseUrl", envBaseUrl);

            Files.writeString(configDir.resolve("pipeline.json"), mapper.writeValueAsString(cfg));

        } catch (Exception e) {

            log.warn("Failed to save default config: {}", e.getMessage());

        }

    }



    // --- CORS wrapper: generic lambda handles OPTIONS preflight + auth check ---
    private HttpHandler corsWrap(HttpHandler handler) {
        return ex -> {
            if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
            if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
            try { handler.handle(ex); } catch (IOException e) { throw e; }
        };
    }

    /** Main entry for Studio standalone launch */

    public static void main(String[] args) throws IOException {

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number: " + args[0] + ". Using default port " + DEFAULT_PORT);
            }
        }

        StudioServer studio = new StudioServer(port);

        studio.start();

        System.out.println("Press Enter to stop...");

        try { System.in.read(); } catch (IOException e) { /* ctrl+c or closed */ }

        studio.stop();

    }

}

