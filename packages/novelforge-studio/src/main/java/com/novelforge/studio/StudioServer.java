package com.novelforge.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.core.genre.GenreManager;
import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.llm.StreamHandler;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final boolean noAuth;

    // ========== Token Usage Tracking ==========
    private static final Object usageLock = new Object();
    private static long totalCalls = 0;
    private static long totalInputTokens = 0;
    private static long totalOutputTokens = 0;
    private static long totalCostCents = 0;
    private static final java.util.Map<String, long[]> perModelUsage = new java.util.concurrent.ConcurrentHashMap<>();

    private static void recordUsage(String model, int inTokens, int outTokens, long costCents) {
        synchronized (usageLock) {
            totalCalls++; totalInputTokens += inTokens; totalOutputTokens += outTokens; totalCostCents += costCents;
            long[] v = perModelUsage.getOrDefault(model, new long[4]);
            v[0]++; v[1] += inTokens; v[2] += outTokens; v[3] += costCents;
            perModelUsage.put(model, v);
        }
    }

    private static int estimateTokens(Object text) {
        if (text == null) return 0;
        String s = text.toString();
        int cn = 0, en = 0;
        for (char c : s.toCharArray()) { if (c >= 0x4E00 && c <= 0x9FFF) cn++; else en++; }
        return (int)(cn / 1.5 + en / 4.0);
    }

    private static String getUsageJson() {
        synchronized (usageLock) {
            try {
                var o = mapper.createObjectNode();
                o.put("totalCalls", totalCalls); o.put("totalInputTokens", totalInputTokens);
                o.put("totalOutputTokens", totalOutputTokens); o.put("totalCostCents", totalCostCents);
                var m = o.putObject("models");
                for (var e : perModelUsage.entrySet()) {
                    var n = m.putObject(e.getKey()); long[] v = e.getValue();
                    n.put("calls", v[0]); n.put("inputTokens", v[1]); n.put("outputTokens", v[2]); n.put("costCents", v[3]);
                }
                return mapper.writeValueAsString(o);
            } catch (Exception e) { return "{}"; }
        }
    }

    private static String resetUsageJson() {
        synchronized (usageLock) {
            totalCalls = 0; totalInputTokens = 0; totalOutputTokens = 0; totalCostCents = 0;
            perModelUsage.clear();
            return "{\"status\":\"reset\"}";
        }
    }



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
        this(port, false);
    }

    public StudioServer(int port, boolean noAuth) throws IOException {

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

        this.authToken = noAuth ? "" : generateToken();
        this.noAuth = noAuth;

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
        server.createContext("/api/memory", corsWrap(this::handleMemoryApi));

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
        server.createContext("/api/style/clone", corsWrap(this::handleStyleCloneApi));
        server.createContext("/api/cover", corsWrap(this::handleCoverApi));
        server.createContext("/api/radar", corsWrap(this::handleRadarApi));
        server.createContext("/api/graph", corsWrap(this::handleGraphApi));
        server.createContext("/api/faction-map", corsWrap(this::handleFactionMapApi));
        server.createContext("/api/naming", corsWrap(this::handleNamingApi));
        server.createContext("/api/naming/save", corsWrap(this::handleNamingSaveApi));
        server.createContext("/api/ollama/models", corsWrap(this::handleOllamaModelsApi));
        server.createContext("/api/ollama/health", corsWrap(this::handleOllamaHealthApi));
        server.createContext("/api/glossary", corsWrap(this::handleGlossaryApi));
        server.createContext("/api/ledger", corsWrap(this::handleLedgerApi));
        server.createContext("/api/tension", corsWrap(this::handleTensionApi));
        server.createContext("/api/version", corsWrap(this::handleVersionApi));
        server.createContext("/api/outline/synopsis", corsWrap(this::handleOutlineSynopsisApi));
            server.createContext("/api/usage", corsWrap(ex -> {
                if ("GET".equals(ex.getRequestMethod())) { sendJson(ex, 200, getUsageJson()); }
                else if ("DELETE".equals(ex.getRequestMethod())) { sendJson(ex, 200, resetUsageJson()); }
                else { sendJson(ex, 405, "{\"error\":\"Method not allowed\"}"); }
            }));
server.createContext("/api/chapter/continue/stream", corsWrap(this::handleChapterContinueStreamApi));

        server.createContext("/api/volume/synopsis", corsWrap(this::handleVolumeSynopsisApi));
        server.createContext("/api/ai-trace", corsWrap(this::handleAiTraceApi));
        server.createContext("/api/outline/generate", corsWrap(this::handleOutlineGenerateApi));
        server.createContext("/api/volume/generate", corsWrap(this::handleVolumeGenerateApi));
        server.createContext("/api/chapter/revise", corsWrap(this::handleChapterReviseApi));
        server.createContext("/api/characters", corsWrap(this::handleCharactersApi));
        server.createContext("/api/hooks", corsWrap(this::handleHooksApi));
        server.createContext("/api/chapter/synopsis", corsWrap(this::handleChapterSynopsisApi));
        server.createContext("/api/outline/generate/stream", corsWrap(this::handleOutlineGenerateStreamApi));
        server.createContext("/api/volume/generate/stream", corsWrap(this::handleVolumeGenerateStreamApi));
        server.createContext("/api/outline/synopsis/stream", corsWrap(this::handleOutlineSynopsisStreamApi));
        server.createContext("/api/volume/synopsis/stream", corsWrap(this::handleVolumeSynopsisStreamApi));
        server.createContext("/api/chapter/synopsis/stream", corsWrap(this::handleChapterSynopsisStreamApi));
        server.createContext("/api/book/references", corsWrap(this::handleBookReferencesApi));
        server.createContext("/api/book/inspirations", corsWrap(this::handleBookInspirationsApi));
        server.createContext("/api/world", corsWrap(this::handleWorldApi));
        server.createContext("/api/chat", corsWrap(this::handleChatApi));
        server.createContext("/api/ai/selection", corsWrap(this::handleAiSelectionApi));

        


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

        if (noAuth) {
            System.out.println("Auth: DISABLED (no-auth mode)");
        } else {
            if (noAuth) {
            System.out.println("Auth: DISABLED (no-auth mode)");
        } else {
            System.out.println("Auth token: " + authToken);
        }
        }

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

        } else if (path.equals("/graph.js")) {

            serveResource(exchange, "/studio/graph.js", "application/javascript; charset=utf-8");

        } else if (path.equals("/factionmap.js")) {

            serveResource(exchange, "/studio/factionmap.js", "application/javascript; charset=utf-8");

        } else if (path.equals("/naming.js")) {

            serveResource(exchange, "/studio/naming.js", "application/javascript; charset=utf-8");

        } else if (path.equals("/glossary.js")) {

            serveResource(exchange, "/studio/glossary.js", "application/javascript; charset=utf-8");

        } else if (path.equals("/ledger.js")) {

            serveResource(exchange, "/studio/ledger.js", "application/javascript; charset=utf-8");

        } else if (path.equals("/tension.js")) {

            serveResource(exchange, "/studio/tension.js", "application/javascript; charset=utf-8");

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

        } catch (IOException e) {

            if (e.getMessage() != null && e.getMessage().contains("already exists")) {

                String dirName = sanitize(title);

                Path existingDir = booksRoot.resolve(dirName);

                ObjectNode result = mapper.createObjectNode();

                result.put("status", "exists");

                result.put("path", existingDir.toString());

                result.put("title", title);

                result.put("error", "该书籍已存在");

                sendJson(exchange, 409, mapper.writeValueAsString(result));

            } else {

                sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");

            }

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

            Book book = null;

            try {

                book = BookProject.loadBook(Paths.get(bookPath));

                TruthState state = new TruthState(Paths.get(bookPath));

                PipelineConfig config = loadConfig(Paths.get(bookPath));

                // Long-term memory (RAG) — build store from book + embedding config
                if (studioConfig.isMemoryEnabled()) {
                    com.novelforge.core.memory.MemoryStore ms = buildMemoryStore(Paths.get(bookPath), book, state);
                    if (ms != null) config.setMemoryStore(ms);
                }

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

            // Fire webhooks for terminal pipeline events (fire-and-forget, never blocks writing)
            fireWebhookIfNeeded(job, book, bookPath);

        });



        // Return jobId immediately

        ObjectNode response = mapper.createObjectNode();

        response.put("jobId", jobId);

        response.put("status", "pending");

        sendJson(exchange, 200, mapper.writeValueAsString(response));

    }



    // --- API: Write SSE stream (real-time progress) ---

    /** Build (or rebuild) the long-term memory store for a book. Null when it fails. */
    private com.novelforge.core.memory.MemoryStore buildMemoryStore(Path bookDir, Book book, TruthState state) {
        try {
            String eb = studioConfig.getEmbeddingBaseUrl();
            String ek = studioConfig.getEmbeddingApiKey();
            String em = studioConfig.getEmbeddingModel();
            com.novelforge.core.memory.EmbeddingClient embedder = null;
            if (ek != null && !ek.isEmpty()) {
                if (eb == null || eb.isEmpty()) eb = studioConfig.getGlobalDefault().getBaseUrl();
                embedder = new com.novelforge.core.memory.OpenAiCompatibleEmbeddingClient(eb, ek, em);
            }
            com.novelforge.core.memory.MemoryStore ms = new com.novelforge.core.memory.MemoryStore(bookDir, embedder);
            ms.load();
            ms.rebuild(book, state);
            return ms;
        } catch (Exception e) {
            System.err.println("[Memory] failed to build store: " + e.getMessage());
            return null;
        }
    }

    private static final com.novelforge.core.notify.WebhookNotifier WEBHOOK_NOTIFIER =
            new com.novelforge.core.notify.WebhookNotifier();

    /** Fire configured webhooks when a write job reaches a terminal state. */
    private void fireWebhookIfNeeded(WriteJob job, Book book, String bookPath) {
        java.util.List<String> urls = studioConfig.getWebhooks();
        if (urls == null || urls.isEmpty()) return;
        if (!"completed".equals(job.status) && !"failed".equals(job.status)) return;
        com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
        payload.put("jobId", job.jobId);
        payload.put("status", job.status);
        payload.put("book", book != null ? (book.getTitle() != null ? book.getTitle() : "") : "");
        payload.put("bookPath", bookPath);
        payload.put("error", job.error != null ? job.error : "");
        WEBHOOK_NOTIFIER.notifyAll(urls, "pipeline." + job.status, payload);
    }

    /** API: /api/memory — long-term memory (RAG) status, rebuild and test retrieval. */
    private void handleMemoryApi(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                String bookPath = getQueryParam(query, "path");
                if (bookPath == null || bookPath.isEmpty()) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
                Book book = BookProject.loadBook(Paths.get(bookPath));
                TruthState state = new TruthState(Paths.get(bookPath));
                com.novelforge.core.memory.MemoryStore ms = buildMemoryStore(Paths.get(bookPath), book, state);

                com.fasterxml.jackson.databind.node.ObjectNode resp = mapper.createObjectNode();
                if (ms == null) {
                    resp.put("ok", false);
                    resp.put("error", "memory store build failed");
                    sendJson(exchange, 500, resp.toString());
                    return;
                }
                resp.put("ok", true);
                resp.put("vectorEnabled", ms.isVectorEnabled());
                resp.put("memoryEnabled", studioConfig.isMemoryEnabled());
                resp.put("totalChunks", ms.size());
                com.fasterxml.jackson.databind.node.ObjectNode byScope = mapper.createObjectNode();
                for (com.novelforge.core.memory.MemoryChunk c : ms.getChunks()) {
                    byScope.put(c.scope, byScope.path(c.scope).asInt(0) + 1);
                }
                resp.set("byScope", byScope);

                // Optional retrieval smoke-test: /api/memory?path=..&q=..&k=5
                String q = getQueryParam(query, "q");
                if (q != null && !q.isEmpty()) {
                    int k = 5;
                    String ks = getQueryParam(query, "k");
                    if (ks != null) { try { k = Integer.parseInt(ks); } catch (NumberFormatException ignored) {} }
                    String ctx = ms.retrieveContext(q, k, null);
                    resp.put("retrievalPreview", ctx.length() > 1500 ? ctx.substring(0, 1500) + "..." : ctx);
                }
                sendJson(exchange, 200, resp.toString());
            } else if ("POST".equals(exchange.getRequestMethod())) {
                JsonNode body = readBody(exchange);
                String bookPath = body.path("path").asText("");
                if (bookPath.isEmpty()) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
                Book book = BookProject.loadBook(Paths.get(bookPath));
                TruthState state = new TruthState(Paths.get(bookPath));
                com.novelforge.core.memory.MemoryStore ms = buildMemoryStore(Paths.get(bookPath), book, state);
                if (ms == null) { sendJson(exchange, 500, "{\"error\":\"rebuild failed\"}"); return; }
                com.fasterxml.jackson.databind.node.ObjectNode resp = mapper.createObjectNode();
                resp.put("ok", true);
                resp.put("rebuilt", true);
                resp.put("vectorEnabled", ms.isVectorEnabled());
                resp.put("totalChunks", ms.size());
                sendJson(exchange, 200, resp.toString());
            } else {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

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

            String ext = format.equals("epub") ? "epub"
                    : format.equals("md") ? "md"
                    : format.equals("html") ? "html"
                    : format.equals("docx") ? "docx"
                    : format.equals("pdf") ? "pdf" : "txt";

            Path outputPath = Paths.get(bookPath).resolve(book.getTitle() + "." + ext);



            switch (format.toLowerCase()) {

                case "txt" -> com.novelforge.core.export.BookExporter.exportTxt(book, outputPath);

                case "md"  -> com.novelforge.core.export.BookExporter.exportMd(book, outputPath);

                case "epub" -> com.novelforge.core.export.BookExporter.exportEpub(book, outputPath, coverPath);

                case "html" -> com.novelforge.core.export.BookExporter.exportHtml(book, outputPath);

                case "docx" -> com.novelforge.core.export.BookExporter.exportDocx(book, outputPath);

                case "pdf" -> com.novelforge.core.export.BookExporter.exportPdf(book, outputPath);

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

                // Preserve existing apiKey if not provided in request (frontend omits it when user leaves input empty)

                if (globalCfg.getApiKey() == null || globalCfg.getApiKey().isEmpty()) {

                    globalCfg.setApiKey(studioConfig.getGlobalDefault().getApiKey());

                }

                studioConfig.setGlobalDefault(globalCfg);

                modelRouter = new ModelRouter(globalCfg.toModelConfig());

            }

            // Per-agent API overrides

            if (body.has("agentOverrides")) {

                JsonNode ov = body.get("agentOverrides");

                java.util.Map<String, AgentApiConfig> overrides = new java.util.LinkedHashMap<>();

                ov.fields().forEachRemaining(field -> {

                    AgentApiConfig cfg = AgentApiConfig.fromJson(field.getValue());

                    // Preserve existing agent apiKey if not provided

                    if (cfg.getApiKey() == null || cfg.getApiKey().isEmpty()) {

                        AgentApiConfig existing = studioConfig.getAgentOverrides().get(field.getKey());

                        if (existing != null && existing.getApiKey() != null && !existing.getApiKey().isEmpty()) {

                            cfg.setApiKey(existing.getApiKey());

                        }

                    }

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

            // Memory / embedding config
            if (body.has("memory")) {
                JsonNode mem = body.get("memory");
                if (mem.has("enabled")) studioConfig.setMemoryEnabled(mem.get("enabled").asBoolean());
                if (mem.has("embeddingBaseUrl")) studioConfig.setEmbeddingBaseUrl(mem.get("embeddingBaseUrl").asText());
                if (mem.has("embeddingApiKey")) {
                    String k = mem.get("embeddingApiKey").asText();
                    if (k == null || k.isEmpty()) k = studioConfig.getEmbeddingApiKey(); // preserve if omitted
                    studioConfig.setEmbeddingApiKey(k);
                }
                if (mem.has("embeddingModel")) studioConfig.setEmbeddingModel(mem.get("embeddingModel").asText());
            }
            if (body.has("webhooks") && body.get("webhooks").isArray()) {
                java.util.List<String> ws = new java.util.ArrayList<>();
                body.get("webhooks").forEach(n -> ws.add(n.asText()));
                studioConfig.setWebhooks(ws);
            }

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

    /** API: /api/style/clone — extract a WritingStyle profile from a sample text via LLM. */
    private void handleStyleCloneApi(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        try {
            JsonNode body = readBody(exchange);
            String bookPath = body.path("path").asText("");
            String sample = body.path("sample").asText("");
            String styleName = body.path("name").asText("");
            if (bookPath.isEmpty() || sample.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"path and sample required\"}");
                return;
            }
            if (!isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path must be within books directory\"}"); return; }
            if (sample.length() > 8000) sample = sample.substring(0, 8000);

            ModelRouter router = resolveModelRouter(body);
            if (router == null) { sendJson(exchange, 400, "{\"error\":\"apiKey required (configure global LLM first)\"}"); return; }
            com.novelforge.core.llm.LlmClient client = router.getClientForAgent("Writer");
            String model = router.getModelForAgent("Writer");

            String system = "你是资深文学编辑。分析给定的写作样本，提炼其风格基因。只输出一个 JSON 对象，不要输出任何其他文字。字段："
                    + "{\"name\":\"风格名\",\"description\":\"一段风格总述\",\"vocabularyPattern\":\"用词偏好\","
                    + "\"sentenceStructure\":\"句式节奏\",\"pacingPattern\":\"叙事节奏\","
                    + "\"dialogueStyle\":\"对话风格\",\"descriptionStyle\":\"描写风格\"}。所有值用中文，每项 30~80 字。";
            String user = "写作样本：\n" + sample;
            String raw = client.chatComplete(java.util.List.of(
                    java.util.Map.of("role", "system", "content", system),
                    java.util.Map.of("role", "user", "content", user)), model, 0.3, 2000);

            // Strip markdown code fences if present
            String json = raw.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            int s = json.indexOf('{');
            int e = json.lastIndexOf('}');
            if (s >= 0 && e > s) json = json.substring(s, e + 1);
            JsonNode parsed = mapper.readTree(json);

            Book book = BookProject.loadBook(Paths.get(bookPath));
            WritingStyle existing = book.getStyle();
            final WritingStyle style = existing != null ? existing : new WritingStyle();
            if (!styleName.isEmpty()) style.setName(styleName);
            else if (!parsed.path("name").asText("").isEmpty()) style.setName(parsed.path("name").asText());
            copyIfPresent(parsed, "description", v -> style.setDescription(v));
            copyIfPresent(parsed, "vocabularyPattern", v -> style.setVocabularyPattern(v));
            copyIfPresent(parsed, "sentenceStructure", v -> style.setSentenceStructure(v));
            copyIfPresent(parsed, "pacingPattern", v -> style.setPacingPattern(v));
            copyIfPresent(parsed, "dialogueStyle", v -> style.setDialogueStyle(v));
            copyIfPresent(parsed, "descriptionStyle", v -> style.setDescriptionStyle(v));
            if (style.getReferenceSample() == null || style.getReferenceSample().isEmpty()) {
                style.setReferenceSample(sample.substring(0, Math.min(sample.length(), 2000)));
            }
            book.setStyle(style);
            BookProject.saveBookMetadata(Paths.get(bookPath), book);

            ObjectNode resp = mapper.createObjectNode();
            resp.put("success", true);
            resp.set("style", mapper.valueToTree(style));
            sendJson(exchange, 200, resp.toString());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    private interface StyleSetter { void set(String v); }

    private void copyIfPresent(JsonNode node, String field, StyleSetter setter) {
        String v = node.path(field).asText("");
        if (!v.isEmpty()) setter.set(v);
    }

    /** API: /api/cover — synthesize a book cover PNG (zero-dependency). */
    private void handleCoverApi(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        try {
            JsonNode body = readBody(exchange);
            String bookPath = body.path("path").asText("");
            Integer palette = body.has("palette") ? body.get("palette").asInt() : null;
            if (bookPath.isEmpty()) { sendJson(exchange, 400, "{\"error\":\"path required\"}"); return; }
            if (!isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path must be within books directory\"}"); return; }
            Book book = BookProject.loadBook(Paths.get(bookPath));
            java.nio.file.Path out = Paths.get(bookPath).resolve("cover.png");
            com.novelforge.core.export.CoverGenerator.generate(book, out, palette);
            ObjectNode resp = mapper.createObjectNode();
            resp.put("success", true);
            resp.put("outputPath", out.toString());
            sendJson(exchange, 200, resp.toString());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    /** API: /api/radar — LLM-driven market/topic radar for genre positioning (对标 InkOS 市场雷达). */
    private void handleRadarApi(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        try {
            JsonNode body = readBody(exchange);
            String genre = body.path("genre").asText("");
            String extra = body.path("extra").asText("");
            if (genre.isBlank()) { sendJson(exchange, 400, "{\"error\":\"genre required\"}"); return; }

            ModelRouter router = resolveModelRouter(body);
            if (router == null) { sendJson(exchange, 400, "{\"error\":\"apiKey required (configure global LLM first)\"}"); return; }
            com.novelforge.core.llm.LlmClient client = router.getClientForAgent("Architect");
            String model = router.getModelForAgent("Architect");

            String system = "你是资深网文市场分析师。基于你对中文网文市场（番茄/起点等平台）的了解，"
                    + "针对给定题材输出市场洞察。只输出 JSON，格式："
                    + "{\"positioning\":\"题材定位建议\",\"trends\":[\"当前流行方向1\",\"方向2\",\"方向3\"],"
                    + "\"hooks\":[\"高转化爽点/开篇钩子1\",\"钩子2\",\"钩子3\"],"
                    + "\"differentiation\":\"与同类作品的差异化切入点\",\"risks\":[\"同质化风险1\",\"风险2\"]}。"
                    + "全部中文，trends/hooks/risks 各 3~5 条，每条 15~40 字。";
            String user = "题材：" + genre + (extra.isBlank() ? "" : "\n补充信息：" + extra);
            String raw = client.chatComplete(java.util.List.of(
                    java.util.Map.of("role", "system", "content", system),
                    java.util.Map.of("role", "user", "content", user)), model, 0.5, 2000);

            String json = raw.trim().replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            int s = json.indexOf('{');
            int e = json.lastIndexOf('}');
            if (s >= 0 && e > s) json = json.substring(s, e + 1);
            JsonNode parsed = mapper.readTree(json);

            ObjectNode resp = mapper.createObjectNode();
            resp.put("success", true);
            resp.set("radar", parsed);
            sendJson(exchange, 200, resp.toString());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    /** API: /api/graph — relationship graph aggregated from characters/world/timeline/chapters (P2 差异化). */
    private void handleGraphApi(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }
        String query = exchange.getRequestURI().getQuery();
        String bookPath = getQueryParam(query, "path");
        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}");
            return;
        }
        try {
            sendJson(exchange, 200, buildGraphJson(Paths.get(bookPath)));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    private static final String[][] RELATION_PATTERNS = {
            {"敌对", "击败,羞辱,追杀,报复,挑衅,怒斥,威胁,围杀,斗法,激战,仇敌,恩怨,斩杀,反目,打压,搜查"},
            {"师徒", "师父,师傅,师尊,弟子,徒弟,指点,传授,教导,拜师,传承,授业"},
            {"亲情", "哥哥,妹妹,弟弟,姐姐,父亲,母亲,爹爹,娘亲,儿子,女儿,兄妹,父女,母子,叔伯,养育,血脉"},
            {"爱慕", "爱慕,心动,喜欢,恋人,夫妻,成亲,娶,倾心,红颜,定情,情愫"},
            {"友盟", "相助,救命,救援,保护,结盟,盟友,好友,知己,并肩,托付,赠予,信任,联手,共战"}
    };

    /** 势力名启发式（人物势力归属）：1~2 字名 + 家/门/宗等后缀（萧家、天机阁），失败再放宽 3~4 字名（星月神教）。 */
    private static final java.util.regex.Pattern FACTION_PAT_2 = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{1,2}(?:家|门|宗|派|族|殿|教|会|阁|盟|谷|宫)");
    private static final java.util.regex.Pattern FACTION_PAT_34 = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{3,4}(?:家|门|宗|派|族|殿|教|会|阁|盟|谷|宫)");

    private static String inferRelation(String desc) {
        for (String[] p : RELATION_PATTERNS) {
            for (String kw : p[1].split(",")) {
                if (desc.contains(kw)) return p[0];
            }
        }
        return null;
    }

    /** Build the relationship-graph JSON for a book directory. */
    private String buildGraphJson(Path bookDir) throws Exception {
        TruthState state = new TruthState(bookDir);

        Map<String, ObjectNode> nodeMap = new java.util.LinkedHashMap<>();
        Map<String, ObjectNode> edgeMap = new java.util.LinkedHashMap<>();
        Map<String, Integer> mentions = new java.util.HashMap<>();

        // ---- nodes: characters (with faction inference) ----
        JsonNode chars = state.characters().listAll();
        JsonNode world = state.world().getData();
        // 预收集 world.json 定义的势力名，供角色归属精确匹配
        java.util.Set<String> worldFactions = new java.util.HashSet<>();
        if (world != null && world.has("factions") && world.get("factions").isArray()) {
            for (JsonNode f : world.get("factions")) {
                String fn = f.isTextual() ? f.asText() : f.path("name").asText("");
                if (!fn.isBlank()) worldFactions.add(fn);
            }
        }
        // 第一遍：收集每个角色的候选势力（显式 faction > world 名命中 > 描述正则）
        java.util.Map<String, String> charFaction = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<String>> charCandidates = new java.util.HashMap<>();
        java.util.Set<String> allCands = new java.util.HashSet<>();
        if (chars != null && chars.isArray()) {
            for (JsonNode c : chars) {
                String name = c.path("name").asText("");
                if (name.isBlank()) continue;
                String desc = c.path("description").asText("");
                java.util.List<String> cands = new java.util.ArrayList<>();
                String faction = c.path("faction").asText("");
                if (!faction.isBlank()) cands.add(faction);
                if (!desc.isBlank()) {
                    if (!worldFactions.isEmpty()) {
                        for (String fn : worldFactions) { if (desc.contains(fn)) { cands.add(fn); break; } }
                    }
                    java.util.regex.Matcher m2 = FACTION_PAT_2.matcher(desc);
                    if (m2.find()) cands.add(m2.group());
                    else {
                        java.util.regex.Matcher m34 = FACTION_PAT_34.matcher(desc);
                        if (m34.find()) cands.add(m34.group());
                    }
                }
                charCandidates.put(name, cands);
                allCands.addAll(cands);
            }
            // 后缀归并：A 以 B 结尾（A≠B）→ 归一为 B（"青阳镇萧家"→"萧家"），消除地名前缀干扰
            java.util.Map<String, String> merge = new java.util.HashMap<>();
            for (String a : allCands) {
                String best = a;
                for (String b : allCands) {
                    if (!b.equals(a) && a.endsWith(b) && b.length() < best.length()) best = b;
                }
                merge.put(a, best);
            }
            for (java.util.Map.Entry<String, java.util.List<String>> en : charCandidates.entrySet()) {
                if (en.getValue().isEmpty()) continue;
                String best = null;
                for (String cd : en.getValue()) {
                    String norm = merge.getOrDefault(cd, cd);
                    if (best == null || norm.length() < best.length()) best = norm;
                }
                if (best != null && !best.isBlank()) charFaction.put(en.getKey(), best);
            }
        }
        // 第二遍：构建人物节点（携带归一化势力）
        if (chars != null && chars.isArray()) {
            for (JsonNode c : chars) {
                String name = c.path("name").asText("");
                if (name.isBlank()) continue;
                ObjectNode n = mapper.createObjectNode();
                n.put("id", name);
                n.put("label", name);
                n.put("group", "character");
                String desc = c.path("description").asText("");
                n.put("desc", desc);
                n.put("role", c.path("role").asText(""));
                String faction = charFaction.get(name);
                if (faction != null && !faction.isBlank()) n.put("faction", faction);
                nodeMap.put(name, n);
            }
        }

        // ---- nodes: world entities (locations/factions/items/systems/rules) ----
        if (world != null) {
            String[][] kinds = {{"locations", "location"}, {"factions", "faction"}, {"items", "item"}, {"systems", "system"}, {"rules", "rule"}};
            for (String[] k : kinds) {
                JsonNode arr = world.get(k[0]);
                if (arr == null || !arr.isArray()) continue;
                for (JsonNode e : arr) {
                    String name = e.isTextual() ? e.asText() : e.path("name").asText("");
                    if (name.isBlank() || nodeMap.containsKey(name)) continue;
                    ObjectNode n = mapper.createObjectNode();
                    n.put("id", name);
                    n.put("label", name);
                    n.put("group", k[1]);
                    n.put("desc", e.isObject() ? e.path("description").asText("") : "");
                    n.put("role", "");
                    nodeMap.put(name, n);
                }
            }
        }

        // ---- edges: timeline event co-occurrence (relation inference priority) ----
        JsonNode events = state.timeline().getData().path("events");
        if (events != null && events.isArray()) {
            for (JsonNode ev : events) {
                String desc = ev.path("description").asText("");
                if (desc.isBlank()) continue;
                List<String> names = findMentionedNames(desc, nodeMap.keySet());
                if (names.size() < 2) continue;
                String rel = inferRelation(desc);
                for (int i = 0; i < names.size(); i++) {
                    for (int j = i + 1; j < names.size(); j++) {
                        addGraphEdge(edgeMap, names.get(i), names.get(j), 1, rel);
                    }
                }
            }
        }

        // ---- edges: chapter co-occurrence + mention counts + evolution sequence ----
        Path chaptersDir = bookDir.resolve("chapters");
        java.util.Set<String> seenEdgeKeys = new java.util.HashSet<>();
        ArrayNode chapterEvolution = mapper.createArrayNode();
        int chapterIdx = 0;
        if (Files.isDirectory(chaptersDir)) {
            try (java.util.stream.Stream<Path> stream = Files.list(chaptersDir)) {
                List<Path> files = stream
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .filter(p -> !p.getFileName().toString().contains(".draft."))
                        .sorted().toList();
                for (Path f : files) {
                    if (chapterIdx >= 60) break;   // payload 保护：最多演进 60 章
                    chapterIdx++;
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    List<String> names = findMentionedNames(text, nodeMap.keySet());
                    if (names.size() < 2) continue;
                    // 本章首次出现的关系边（供前端演变动画逐帧点亮）
                    ArrayNode added = mapper.createArrayNode();
                    for (int i = 0; i < names.size(); i++) {
                        mentions.merge(names.get(i), countOccurrences(text, names.get(i)), Integer::sum);
                        for (int j = i + 1; j < names.size(); j++) {
                            int cmp = names.get(i).compareTo(names.get(j));
                            String key = cmp <= 0 ? names.get(i) + "\u0000" + names.get(j) : names.get(j) + "\u0000" + names.get(i);
                            if (!seenEdgeKeys.contains(key)) {
                                seenEdgeKeys.add(key);
                                ObjectNode ae = mapper.createObjectNode();
                                ae.put("source", cmp <= 0 ? names.get(i) : names.get(j));
                                ae.put("target", cmp <= 0 ? names.get(j) : names.get(i));
                                ae.put("chapter", chapterIdx);
                                added.add(ae);
                            }
                            addGraphEdge(edgeMap, names.get(i), names.get(j), 1, null);
                        }
                    }
                    if (added.size() > 0) {
                        ObjectNode ch = mapper.createObjectNode();
                        ch.put("index", chapterIdx);
                        ch.put("title", f.getFileName().toString().replaceFirst("\\.md$", ""));
                        ch.set("added", added);
                        chapterEvolution.add(ch);
                    }
                }
            }
        }

        // ---- sort edges by weight, cap for render ----
        List<ObjectNode> edgeList = new java.util.ArrayList<>(edgeMap.values());
        edgeList.sort((a, b) -> Integer.compare(b.path("weight").asInt(), a.path("weight").asInt()));
        int cap = Math.min(edgeList.size(), 400);
        ArrayNode finalEdges = mapper.createArrayNode();
        Map<String, Integer> degree = new java.util.HashMap<>();
        for (int i = 0; i < cap; i++) {
            ObjectNode e = edgeList.get(i);
            finalEdges.add(e);
            degree.merge(e.path("source").asText(), 1, Integer::sum);
            degree.merge(e.path("target").asText(), 1, Integer::sum);
        }

        ArrayNode finalNodes = mapper.createArrayNode();
        for (ObjectNode n : nodeMap.values()) {
            n.put("mentions", mentions.getOrDefault(n.path("id").asText(), 0));
            n.put("degree", degree.getOrDefault(n.path("id").asText(), 0));
            finalNodes.add(n);
        }

        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("book", bookDir.getFileName() != null ? bookDir.getFileName().toString() : bookDir.toString());
        resp.set("nodes", finalNodes);
        resp.set("edges", finalEdges);
        resp.set("chapters", chapterEvolution);
        ObjectNode stats = mapper.createObjectNode();
        stats.put("characters", chars != null && chars.isArray() ? chars.size() : 0);
        stats.put("worldEntities", nodeMap.size() - (chars != null && chars.isArray() ? chars.size() : 0));
        stats.put("events", events != null && events.isArray() ? events.size() : 0);
        stats.put("edges", finalEdges.size());
        stats.put("chapters", chapterIdx);
        resp.set("stats", stats);
        return mapper.writeValueAsString(resp);
    }

    // ========== 实力分布地图 (/api/faction-map) ==========
    private static final java.util.regex.Pattern PLACE_PAT = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{2,4}(?:镇|城|村|山|谷|郡|州|府|域|界)");
    private static final java.util.regex.Pattern FACTION_PAT_MAP = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{2,4}(?:家|门|宗|派|族|殿|教|会|阁|盟|谷|宫)");
    /** 下辖模式：X 麾下/门下/附属 Y，或 Y 隶属/归属 X */
    private static final java.util.regex.Pattern SUBORD_FWD_PAT = java.util.regex.Pattern.compile("([\\u4e00-\\u9fa5]{2,4}(?:家|门|宗|派|族|殿|教|会|阁|盟|谷|宫))(?:麾下|门下|附属|辖下|统辖|部下|附庸|麾下势力)(?:的)?([\\u4e00-\\u9fa5]{2,4}(?:家|门|宗|派|族|殿|教|会|阁|盟|谷|宫))?");
    private static final java.util.regex.Pattern SUBORD_REV_PAT = java.util.regex.Pattern.compile("([\\u4e00-\\u9fa5]{2,4}(?:家|门|宗|派|族|殿|教|会|阁|盟|谷|宫))(?:隶属|归属|依附|附庸|听从|归顺)(?:于)?(?:的)?([\\u4e00-\\u9fa5]{2,4}(?:家|门|宗|派|族|殿|教|会|阁|盟|谷|宫))");
    /** 特点关键词表：标签 → 命中词 */
    private static final String[][] TRAIT_KEYWORDS = {
            {"家族传承", "族,家,嫡,旁系,血脉,家规,宗祠"},
            {"宗派门阀", "宗,门,派,教,阁,殿,盟,宫"},
            {"武道炼体", "炼体,锻体,拳,掌,刀,剑,武,战"},
            {"丹道炼药", "丹,药,炉,火,炼药"},
            {"阵法禁制", "阵,禁制,符,封印"},
            {"御兽驯妖", "兽,妖,驯,灵兽"},
            {"仙门道统", "道,仙,法,灵根,灵气"},
            {"魔道邪修", "魔,邪,血,阴,毒"}
    };
    /** 风格定性词：模式 → 风格标签（描述命中即叠加） */
    private static final String[][] STYLE_PATTERNS = {
            {"以武立族", "族,家族,演武,考核"},
            {"宗门道统", "宗,门派,师尊,道统"},
            {"魔道妖风", "魔,邪,阴,毒,血"},
            {"丹道传家", "丹,药,炼丹"},
            {"剑修风流", "剑,剑修"},
            {"行事隐秘", "隐,暗,秘,影"},
            {"正大堂皇", "正,浩,光明,正道"},
            {"亦正亦邪", "亦正,亦邪,中立,两可"}
    };

    private void handleFactionMapApi(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"GET only\"}"); return; }
        String query = exchange.getRequestURI().getQuery();
        String bookPath = getQueryParam(query, "path");
        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}");
            return;
        }
        try {
            sendJson(exchange, 200, buildFactionMapJson(Paths.get(bookPath)));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    /** 势力分布地图：聚合角色归属 → 势力档案（范围/特点/风格/下辖）+ 正文增强 + 势力关系。 */
    private String buildFactionMapJson(Path bookDir) throws Exception {
        TruthState state = new TruthState(bookDir);
        JsonNode chars = state.characters().listAll();
        JsonNode world = state.world().getData();

        // ---- 1. 角色势力归属（复用图谱推断策略：显式 faction > world 名 > 描述正则 + 后缀归并）----
        java.util.Set<String> worldFactions = new java.util.HashSet<>();
        if (world != null && world.has("factions") && world.get("factions").isArray()) {
            for (JsonNode f : world.get("factions")) {
                String fn = f.isTextual() ? f.asText() : f.path("name").asText("");
                if (!fn.isBlank()) worldFactions.add(fn);
            }
        }
        java.util.Map<String, String> charFaction = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<String>> charCandidates = new java.util.HashMap<>();
        java.util.Set<String> allCands = new java.util.HashSet<>();
        java.util.Map<String, String> charDesc = new java.util.HashMap<>();
        java.util.Map<String, String> merge = new java.util.HashMap<>();   // 势力后缀归并表（正文扫描复用）
        if (chars != null && chars.isArray()) {
            for (JsonNode c : chars) {
                String name = c.path("name").asText("");
                if (name.isBlank()) continue;
                String desc = c.path("description").asText("");
                charDesc.put(name, desc);
                java.util.List<String> cands = new java.util.ArrayList<>();
                String faction = c.path("faction").asText("");
                if (!faction.isBlank()) cands.add(faction);
                if (!desc.isBlank()) {
                    if (!worldFactions.isEmpty()) {
                        for (String fn : worldFactions) { if (desc.contains(fn)) { cands.add(fn); break; } }
                    }
                    java.util.regex.Matcher m2 = FACTION_PAT_2.matcher(desc);
                    if (m2.find()) cands.add(m2.group());
                    else {
                        java.util.regex.Matcher m34 = FACTION_PAT_34.matcher(desc);
                        if (m34.find()) cands.add(m34.group());
                    }
                }
                charCandidates.put(name, cands);
                allCands.addAll(cands);
            }
            for (String a : allCands) {
                String best = a;
                for (String b : allCands) {
                    if (!b.equals(a) && a.endsWith(b) && b.length() < best.length()) best = b;
                }
                merge.put(a, best);
            }
            for (java.util.Map.Entry<String, java.util.List<String>> en : charCandidates.entrySet()) {
                if (en.getValue().isEmpty()) continue;
                String best = null;
                for (String cd : en.getValue()) {
                    String norm = merge.getOrDefault(cd, cd);
                    if (best == null || norm.length() < best.length()) best = norm;
                }
                if (best != null && !best.isBlank()) charFaction.put(en.getKey(), best);
            }
        }

        // ---- 2. world.json 显式势力（补充档案字段：domain/traits/subordinates/relation）----
        java.util.LinkedHashMap<String, ObjectNode> factionMap = new java.util.LinkedHashMap<>();
        java.util.Map<String, ObjectNode> worldFactionDefs = new java.util.LinkedHashMap<>();
        if (world != null && world.has("factions") && world.get("factions").isArray()) {
            for (JsonNode f : world.get("factions")) {
                String fn = f.isTextual() ? f.asText() : f.path("name").asText("");
                if (fn.isBlank() || worldFactionDefs.containsKey(fn)) continue;
                ObjectNode def = mapper.createObjectNode();
                def.put("name", fn);
                def.put("desc", f.isObject() ? f.path("description").asText("") : "");
                def.put("domain", f.isObject() ? f.path("domain").asText("") : "");
                def.put("style", f.isObject() ? f.path("style").asText("") : "");
                worldFactionDefs.put(fn, def);
                if (!factionMap.containsKey(fn)) factionMap.put(fn, def.deepCopy());
            }
        }

        // ---- 3. 正文扫描：势力提及频次 + 下辖关系 + 地域 + 例句 ----
        java.util.Map<String, Integer> factionMentions = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<String>> factionExamples = new java.util.HashMap<>();
        java.util.Map<String, java.util.Set<String>> factionPlaces = new java.util.HashMap<>();
        java.util.Map<String, java.util.Set<String>> factionSubords = new java.util.HashMap<>();
        java.util.List<String> chapterTexts = new java.util.ArrayList<>();
        Path chaptersDir = bookDir.resolve("chapters");
        if (Files.isDirectory(chaptersDir)) {
            try (java.util.stream.Stream<Path> stream = Files.list(chaptersDir)) {
                for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                        .filter(p -> !p.getFileName().toString().contains(".draft."))
                        .sorted().limit(20).toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    chapterTexts.add(text);
                    // 下辖正/反模式（以已知或候选势力名为锚，先扫描全部势力名）
                    java.util.regex.Matcher sf = SUBORD_FWD_PAT.matcher(text);
                    while (sf.find()) {
                        String host = sf.group(1), sub = sf.group(2);
                        if (sub != null && !sub.isBlank() && !sub.equals(host)) addSub(factionSubords, host, sub);
                    }
                    java.util.regex.Matcher sr = SUBORD_REV_PAT.matcher(text);
                    while (sr.find()) {
                        String sub = sr.group(1), host = sr.group(2);
                        if (sub != null && !host.isBlank() && !sub.equals(host)) addSub(factionSubords, host, sub);
                    }
                    // 地域与例句：命中已知/候选势力名的句子
                    for (String line : text.split("\n")) {
                        if (line.length() < 4 || line.length() > 200) continue;
                        java.util.Set<String> hit = new java.util.HashSet<>();
                        for (String fn : allCands) {
                            if (fn.length() >= 2 && line.contains(fn)) { hit.add(merge.getOrDefault(fn, fn)); factionMentions.merge(merge.getOrDefault(fn, fn), 1, Integer::sum); }
                        }
                        if (hit.isEmpty()) continue;
                        java.util.regex.Matcher pl = PLACE_PAT.matcher(line);
                        while (pl.find()) {
                            for (String h : hit) factionPlaces.computeIfAbsent(h, k -> new java.util.HashSet<>()).add(pl.group());
                        }
                        for (String h : hit) {
                            java.util.List<String> ex = factionExamples.computeIfAbsent(h, k -> new java.util.ArrayList<>());
                            if (ex.size() < 3) ex.add(line.trim());
                        }
                    }
                }
            }
        }

        // ---- 4. 聚合势力档案 ----
        java.util.LinkedHashMap<String, ObjectNode> merged = new java.util.LinkedHashMap<>();
        // 先以角色推断出的势力建档（保证无 world.json 势力的书也能输出）
        for (String fn : new java.util.LinkedHashSet<>(charFaction.values())) {
            merged.computeIfAbsent(fn, k -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("name", k);
                n.put("desc", "");
                n.putArray("traits");
                n.put("style", "");
                n.putArray("members");
                n.putArray("subordinates");
                n.put("mentions", 0);
                n.put("weight", 1);
                return n;
            });
        }
        // 成员按势力归位
        for (java.util.Map.Entry<String, String> en : charFaction.entrySet()) {
            ObjectNode f = merged.get(en.getValue());
            if (f == null) continue;
            if (!containsText(f.withArray("members"), en.getKey())) f.withArray("members").add(en.getKey());
            f.put("weight", f.withArray("members").size() + f.path("mentions").asInt() / 50);
        }
        // 合并 world.json 显式势力（含无成员势力）
        for (ObjectNode def : worldFactionDefs.values()) {
            String fn = def.path("name").asText();
            ObjectNode f = merged.computeIfAbsent(fn, k -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("name", k);
                n.put("desc", "");
                n.put("domain", "");
                n.putArray("traits");
                n.put("style", "");
                n.putArray("members");
                n.putArray("subordinates");
                n.put("mentions", 0);
                n.put("weight", 1);
                return n;
            });
            if (f.path("desc").asText().isBlank() && !def.path("desc").asText().isBlank()) f.put("desc", def.path("desc").asText());
            if (f.path("domain").asText().isBlank() && !def.path("domain").asText().isBlank()) f.put("domain", def.path("domain").asText());
            if (f.path("style").asText().isBlank() && !def.path("style").asText().isBlank()) f.put("style", def.path("style").asText());
            f.put("weight", f.withArray("members").size() + f.path("mentions").asInt() / 50);
        }
        // 成员描述汇总 → desc / traits / style / domain（自动推断）
        for (ObjectNode f : merged.values()) {
            String fn = f.path("name").asText();
            f.put("mentions", factionMentions.getOrDefault(fn, 0));
            // 汇总成员描述
            StringBuilder descSb = new StringBuilder();
            for (JsonNode m : f.withArray("members")) {
                String d = charDesc.getOrDefault(m.asText(), "");
                if (!d.isBlank()) descSb.append(m.asText()).append("：").append(d).append("；");
            }
            if (f.path("desc").asText().isBlank() && descSb.length() > 0) {
                f.put("desc", descSb.substring(0, Math.min(descSb.length(), 300)));
            }
            // 特点标签：聚合成员描述 + 例句
            String corpus = descSb.toString() + String.join(" ", factionExamples.getOrDefault(fn, java.util.List.of()));
            ArrayNode traits = (ArrayNode) f.get("traits");
            for (String[] t : TRAIT_KEYWORDS) {
                boolean hit = false;
                for (String kw : t[1].split(",")) { if (corpus.contains(kw)) { hit = true; break; } }
                if (hit && traits.size() < 4 && !containsText(traits, t[0])) traits.add(t[0]);
            }
            // 风格：world 显式 > 描述规则叠加
            if (f.path("style").asText().isBlank()) {
                StringBuilder st = new StringBuilder();
                for (String[] p : STYLE_PATTERNS) {
                    boolean hit = false;
                    for (String kw : p[1].split(",")) { if (corpus.contains(kw)) { hit = true; break; } }
                    if (hit) {
                        if (st.length() > 0) st.append(" · ");
                        st.append(p[0]);
                        if (st.toString().split("·").length >= 3) break;
                    }
                }
                f.put("style", st.length() > 0 ? st.toString() : "势力待考");
            }
            // 地域：显式 > 正文提取 > 成员描述
            if (f.path("domain").asText().isBlank()) {
                java.util.Set<String> places = factionPlaces.getOrDefault(fn, new java.util.HashSet<>());
                java.util.regex.Matcher dm = PLACE_PAT.matcher(descSb.toString());
                while (dm.find()) places.add(dm.group());
                if (!places.isEmpty()) {
                    String top = null; int bestCnt = 0;
                    for (String p : places) {
                        int c = descSb.toString().split(p, -1).length - 1;
                        if (c > bestCnt) { bestCnt = c; top = p; }
                    }
                    f.put("domain", top != null ? top : places.iterator().next());
                }
            }
            // 下辖势力：正文模式 + world 显式子域
            java.util.Set<String> subs = factionSubords.getOrDefault(fn, new java.util.HashSet<>());
            if (world != null && world.has("factions") && world.get("factions").isArray()) {
                for (JsonNode wf : world.get("factions")) {
                    if (wf.isObject()) {
                        String wfn = wf.path("name").asText("");
                        String parent = wf.path("parent").asText("");
                        if (!wfn.isBlank() && parent.equals(fn)) subs.add(wfn);
                    }
                }
            }
            for (String s : subs) if (!containsText(f.withArray("subordinates"), s)) f.withArray("subordinates").add(s);
            f.put("weight", f.withArray("members").size() + f.path("mentions").asInt() / 50 + f.withArray("subordinates").size() * 2);
        }

        // ---- 5. 势力间关系（敌对/同盟/竞争，正文同句共现 + 关系词）----
        ArrayNode relations = mapper.createArrayNode();
        java.util.Set<String> relKeys = new java.util.HashSet<>();
        java.util.List<String> factionNames = new java.util.ArrayList<>(merged.keySet());
        String allText = String.join("\n", chapterTexts);
        for (int i = 0; i < factionNames.size(); i++) {
            for (int j = i + 1; j < factionNames.size(); j++) {
                String a = factionNames.get(i), b = factionNames.get(j);
                int co = 0; String rel = null;
                for (String line : allText.split("\n")) {
                    if (line.contains(a) && line.contains(b)) {
                        co++;
                        String r = inferRelation(line);
                        if (r != null && (rel == null || r.equals("敌对") || r.equals("友盟"))) rel = r;
                    }
                }
                if (co > 0 && rel != null) {
                    ObjectNode r = mapper.createObjectNode();
                    r.put("source", a);
                    r.put("target", b);
                    r.put("type", rel);
                    r.put("weight", co);
                    relations.add(r);
                    relKeys.add(a + "\u0000" + b);
                }
            }
        }
        // world.json 显式关系补充
        if (world != null && world.has("factions") && world.get("factions").isArray()) {
            for (JsonNode wf : world.get("factions")) {
                if (wf.isObject() && wf.has("relation")) {
                    String a = wf.path("name").asText("");
                    JsonNode rn = wf.get("relation");
                    if (rn.isArray()) {
                        for (JsonNode rr : rn) {
                            String b = rr.path("with").asText("");
                            String t = rr.path("type").asText("");
                            if (a.isBlank() || b.isBlank() || t.isBlank()) continue;
                            String key = a.compareTo(b) <= 0 ? a + "\u0000" + b : b + "\u0000" + a;
                            if (relKeys.contains(key)) continue;
                            ObjectNode r = mapper.createObjectNode();
                            r.put("source", a);
                            r.put("target", b);
                            r.put("type", t);
                            r.put("weight", 1);
                            relations.add(r);
                            relKeys.add(key);
                        }
                    }
                }
            }
        }

        // ---- 6. 输出 ----
        ArrayNode factions = mapper.createArrayNode();
        for (ObjectNode f : merged.values()) factions.add(f);
        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("book", bookDir.getFileName() != null ? bookDir.getFileName().toString() : bookDir.toString());
        resp.set("factions", factions);
        resp.set("relations", relations);
        ObjectNode stats = mapper.createObjectNode();
        stats.put("factions", factions.size());
        stats.put("characters", chars != null && chars.isArray() ? chars.size() : 0);
        stats.put("covered", charFaction.size());
        int subCnt = 0; for (JsonNode f : factions) subCnt += f.path("subordinates").size();
        stats.put("subordinates", subCnt);
        stats.put("relations", relations.size());
        stats.put("chaptersScanned", Math.min(20, chapterTexts.size()));
        resp.set("stats", stats);
        return mapper.writeValueAsString(resp);
    }

    private void addSub(java.util.Map<String, java.util.Set<String>> map, String host, String sub) {
        if (host == null || sub == null || host.isBlank() || sub.isBlank() || host.equals(sub)) return;
        map.computeIfAbsent(host, k -> new java.util.HashSet<>()).add(sub);
    }

    // ===================== 智能取名引擎 =====================

    private static final String[] NAMING_SURNAMES = {
        "李","王","张","刘","陈","杨","赵","萧","林","叶","沈","苏","顾","楼","楚",
        "慕容","上官","欧阳","南宫","东方","司马","独孤","轩辕","公孙","令狐","长孙",
        "宇文","百里","北冥","西门","司空","拓跋","赫连"
    };

    private static final java.util.Map<String, String> NAMING_STYLE_DESC = new java.util.LinkedHashMap<>();
    static {
        NAMING_STYLE_DESC.put("xianxia", "仙侠飘逸");
        NAMING_STYLE_DESC.put("wuxia", "武侠刚正");
        NAMING_STYLE_DESC.put("xuanhuan", "玄幻霸气");
        NAMING_STYLE_DESC.put("classic", "古典文雅");
        NAMING_STYLE_DESC.put("fierce", "凶煞凌厉");
        NAMING_STYLE_DESC.put("elegant", "清丽雅致");
        // —— 第六轮新增：恐怖 / 科幻 / 奇幻 / 神秘 / 诡异 ——
        NAMING_STYLE_DESC.put("horror", "恐怖惊悚");
        NAMING_STYLE_DESC.put("scifi", "科幻硬核");
        NAMING_STYLE_DESC.put("fantasy", "奇幻瑰丽");
        NAMING_STYLE_DESC.put("mystery", "神秘悬疑");
        NAMING_STYLE_DESC.put("eerie", "诡异怪谈");
    }

    private static final java.util.Map<String, String[]> NAMING_GIVEN = new java.util.LinkedHashMap<>();
    static {
        NAMING_GIVEN.put("xianxia", new String[]{"霄","尘","渊","墨","凌","云","逸","清","玄","霜","夜","星","河","羽","辰","苍","溟","琅","瑾","珩","皓","绮"});
        NAMING_GIVEN.put("wuxia", new String[]{"风","云","龙","虎","啸","英","杰","豪","烈","锋","岩","峰","松","柏","涛","岳","霆","骁","翊","铮","崚","瀚"});
        NAMING_GIVEN.put("xuanhuan", new String[]{"穹","荒","蛮","焱","雷","狱","帝","皇","乾","坤","霸","绝","焚","戮","煌","烬","渊","溟","煞","苍","阙","熠"});
        NAMING_GIVEN.put("classic", new String[]{"瑾","瑜","琰","珩","珂","瑶","璋","璇","诗","书","礼","乐","润","彦","睿","哲","翰","霖","楚","晏","徽","翊"});
        NAMING_GIVEN.put("fierce", new String[]{"煞","冥","幽","血","骨","魂","殇","灭","寂","枯","寒","刑","斩","黯","凄","狱","夜","霜","戮","绝","枭","魇"});
        NAMING_GIVEN.put("elegant", new String[]{"芷","兰","荷","露","月","笙","弦","婉","嫣","霏","绮","黛","韵","绾","婳","汐","苓","苒","缦","纭","媱","翎"});
        // —— 第六轮新增风格字池（均为单字，拼接为字号）——
        NAMING_GIVEN.put("horror", new String[]{"尸","血","骨","咒","煞","怨","棺","魇","凄","黯","晦","冥","疫","疡","髓","蛊","怖","殁","瘴","髑","祟","殇"});
        NAMING_GIVEN.put("scifi", new String[]{"星","舰","弦","码","芯","枢","维","熵","极","曦","寰","岚","崖","澈","湛","煜","烁","烁","璇","珂","琅","珀"});
        NAMING_GIVEN.put("fantasy", new String[]{"璃","鳞","冠","蕾","薇","晶","歌","蔻","翎","瑟","缇","梵","檀","珈","璎","珑","旎","绯","霁","蘅","玥","珣"});
        NAMING_GIVEN.put("mystery", new String[]{"谜","雾","影","秘","幻","符","隐","渊","寂","谲","邃","翳","杳","寞","澜","黯","幽","徊","岚","霭","澹","澈"});
        NAMING_GIVEN.put("eerie", new String[]{"诡","畸","裂","呓","蜃","错","悖","魇","魍","魉","黯","黠","瘆","愔","惘","悚","悱","惚","杳","冥","晦","祟"});
    }

    private static final String[] NAMING_FEM_PREFIX = {"婉","嫣","芷","兰","月","笙","弦","露","霏","绮","黛","韵","绾","婳","汐","苓","苒","缦","瑶","璇","翎","媱"};
    private static final String[] NAMING_MASC_PREFIX = {"锋","岩","豪","烈","霆","骁","啸","杰","岳","苍","焱","渊","峥","铮","瀚","翊","崚","戬","朔","翎"};

    private static final java.util.Map<String, String> NAMING_GLOSS = new java.util.LinkedHashMap<>();
    static {
        String[] g = {
            "霄=高远九霄","尘=超然出尘","渊=深不可测","墨=翰墨儒雅","凌=凌云之上","云=自在如云","逸=洒脱飘逸","清=清雅纯粹","玄=玄妙莫测","霜=清冷如霜",
            "夜=幽夜深沉","星=星辰璀璨","河=河汉浩瀚","羽=轻灵如羽","辰=北辰指引","苍=苍茫辽阔","溟=溟海无垠","琅=琅玕美玉","瑾=怀瑾握瑜","珩=玉珩生辉",
            "风=逐风而行","龙=潜龙在渊","虎=虎踞龙盘","啸=长啸凌空","英=英雄豪杰","杰=卓尔不群","豪=豪迈洒脱","烈=刚烈如火","锋=锋芒毕露","岩=坚如磐石",
            "峰=登峰造极","松=岁寒松柏","柏=贞松劲柏","涛=惊涛拍岸","岳=山岳巍峨","霆=雷霆万钧","骁=骁勇善战","翊=翊赞辅翼","铮=铁骨铮铮","瀚=浩瀚无边",
            "穹=天穹苍穹","荒=洪荒古远","蛮=蛮荒之力","焱=烈焰焚天","雷=雷动九天","狱=幽狱深沉","帝=帝王之尊","皇=皇者之气","乾=乾纲独断","坤=坤厚载物",
            "霸=霸绝天下","绝=绝世独立","焚=焚尽八荒","戮=戮破苍穹","煌=金煌夺目","烬=劫灰余烬","阙=宫阙高耸","熠=熠熠生辉","煞=凶煞凌厉","枭=枭雄并起","魇=梦魇缠身",
            "琰=琰圭温润","珂=鸣珂锵玉","瑶=瑶台仙姿","璋=圭璋特达","璇=璇玑玉衡","诗=诗书传家","书=书香门第","礼=彬彬有礼","乐=礼乐和鸣","润=温润如玉",
            "彦=俊彦才士","睿=睿智清明","哲=哲思明达","翰=翰墨文章","霖=甘霖普润","楚=楚楚不凡","徽=徽音远扬","崚=崚嶒奇崛","戬=戬谷降祥","朔=朔风凛冽",
            "冥=幽冥莫测","幽=幽深静远","血=血气方刚","骨=铮铮铁骨","魂=魂牵梦萦","殇=国殇悲壮","灭=寂灭无常","寂=万籁俱寂","枯=枯荣有数","寒=寒英傲雪",
            "刑=刑天猛志","斩=斩浪破军","黯=黯然销魂","凄=凄清孤高","芷=芷兰幽芳","兰=兰心蕙质","荷=荷韵清涟","露=朝露晶莹","月=月华如练","笙=笙歌婉转",
            "弦=弦音清越","婉=婉约风流","嫣=嫣然巧笑","霏=霏微烟雨","绮=绮丽如锦","黛=黛色参天","韵=风韵天成","绾=青丝轻绾","婳=婳静姝丽","汐=潮汐有信",
            "苓=茯苓灵秀","苒=荏苒时光","缦=缦立远视","纭=纷纭多姿","媱=媱娥仙姿","翎=翠翎轻扬"
        };
        for (String s : g) { int i = s.indexOf('='); NAMING_GLOSS.put(s.substring(0, i), s.substring(i + 1)); }
        // —— 第六轮新增风格字释义 ——
        String[] g2 = {
            "尸=尸山血海","血=血气方殷","骨=铮铮铁骨","咒=咒术缠身","煞=凶煞凌厉","怨=怨念难消","棺=棺柩沉沉","魇=梦魇缠身","凄=凄清孤高","黯=黯然销魂","晦=晦暗不明","冥=幽冥莫测","疫=瘟疫横行","疡=溃疡不愈","髓=髓海深邃","蛊=蛊毒诡秘","怖=惊怖悚然","殁=殁于幽暗","瘴=瘴气弥漫","髑=髑髅森森","祟=鬼祟作乱","殇=国殇悲壮",
            "舰=星舰巡游","码=代码如织","芯=晶芯内蕴","枢=枢机运转","维=维度交错","熵=熵增无序","寰=寰宇浩渺","崖=崖岸嵯峨","湛=湛蓝澄澈","煜=煜煜生辉","烁=闪烁流光","璇=璇玑玉衡","珂=鸣珂锵玉","琅=琅玕美玉","珀=琥珀温润",
            "璃=琉璃剔透","鳞=龙鳞生辉","冠=冠冕堂皇","蕾=花蕾待放","薇=紫薇馥郁","晶=晶石璀璨","歌=歌吟悠远","蔻=豆蔻梢头","翎=翠翎轻扬","瑟=锦瑟华年","缇=缇绮流光","梵=梵音清远","檀=檀香袅袅","珈=珈蓝宝相","璎=璎珞垂珠","珑=玲珑剔透","旎=旖旎风光","绯=绯色灼灼","霁=霁月清风","蘅=蘅皋杜若","玥=玥玉含光","珣=珣玗美石",
            "谜=谜团深锁","雾=雾隐迷蒙","谲=诡谲多变","邃=深邃难测","翳=翳影蔽日","杳=杳渺无踪","寞=寂寞清寒","澜=波澜暗涌","徊=徘徊往复","岚=山岚氤氲","霭=暮霭沉沉","澹=澹泊宁静",
            "诡=诡谲莫测","畸=畸零异形","裂=裂隙横生","呓=呓语喃喃","蜃=蜃楼虚幻","错=错乱颠倒","悖=悖谬乖离","魍=魍魉徘徊","魉=魉魅随形","黠=黠慧狡黠","瘆=瘆人寒意","愔=愔愔寂然","惘=惘然若失","悚=悚然生惧","悱=悱恻缠绵","惚=惚兮恍兮"
        };
        for (String s : g2) { int i = s.indexOf('='); NAMING_GLOSS.put(s.substring(0, i), s.substring(i + 1)); }
    }

    private static final String[] SKILL_PREFIX = {"太乙","玄冥","九幽","焚天","天罡","紫霄","太虚","两仪","三才","四象","五行","六合","七星","八卦","无极","浑天","凌霄","碧落","黄泉","沧海","北冥","昆仑"};
    private static final String[] SKILL_CORE = {"剑","掌","指","拳","腿","刀","枪","棍","拂","鞭","气","元","神","魂","心","意","虚","影","风","雷","火","冰","霜","空"};
    private static final String[] SKILL_SUFFIX = {"式","诀","经","功","法","劲","罡","真解","心法","残卷","奥义","玄机"};
    private static final String[] SKILL_VERB = {"破","斩","惊","落","焚","镇","碎","裂","逐月","摘星","揽月","傲","啸","惊鸿","踏雪","听风","断","扫"};
    private static final String[] SKILL_FORM = {"斩","式","指","剑","掌","击","刺","挑","撩","劈","绝","破"};

    private static final String[] ITEM_MAT = {"琉璃","寒玉","玄铁","紫金","赤铜","星纹","月华","幽冥","碧落","混沌","太初","九天","云锦","青霜","灵晶","血玉"};
    private static final String[] ITEM_OBJ = {"瓶","鼎","炉","镜","灯","符","印","玺","珠","幡","铃","环","簪","匣","卷","盘","葫","梭","圭","铃铛"};
    private static final String[] ITEM_GRADE = {"天品","地品","玄品","上品","绝品","圣物","神器","灵宝","仙品","太古遗珍"};

    private static final String[] WEAPON_IMG = {"龙吟","凤鸣","霜刃","裂空","惊鸿","逐月","斩浪","破岳","吞日","饮血","鸣鸿","承影","赤霄","青锋","秋水","寒星","破军","绝影","流光","苍雷"};
    private static final String[] WEAPON_CLS = {"刀","剑","枪","戟","斧","钺","钩","叉","鞭","锏","锤","槊","弓","弩","拂尘","双钩","长戟","重剑"};

    private static final String[] FACTION_TERR = {"青冥","北荒","南疆","沧海","苍梧","凌霄","幽都","中州","东溟","西极","九幽","天雍","云梦","碧落","大荒","星陨","赤霄","白帝","玄霜","炎域","太虚","苍澜"};
    private static final String[] FACTION_SUF = {"宗","门","阁","殿","教","盟","谷","山庄","世家","王朝","圣地","天宫","书院","洞天","魔域","神庙","剑派","道观"};

    private static final String[] MOUNT_IMG = {"踏云","追风","凌霄","焚天","裂空","墨鳞","赤焰","玄霜","惊雷","逐月","栖霞","饮涧","御风","星驰","踏雪","破空","游霄","贯日"};
    private static final String[] MOUNT_SP = {"麒麟","白虎","青鸾","龙驹","玄狼","雷鹰","朱雀","玄武","白泽","狻猊","貔貅","天马","火凤","玉蟾","蛟","鹏","鲲","猊","驺虞","角端"};

    // —— 第六轮：按风格着色的词池（恐怖/科幻/奇幻/神秘/诡异），命中即用，未命中回退默认池 ——
    private static final java.util.Map<String, String[]> SKILL_PREFIX_BY_STYLE = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String[]> SKILL_CORE_BY_STYLE = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String[]> ITEM_MAT_BY_STYLE = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String[]> ITEM_OBJ_BY_STYLE = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String[]> WEAPON_IMG_BY_STYLE = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String[]> FACTION_TERR_BY_STYLE = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String[]> FACTION_SUF_BY_STYLE = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String[]> MOUNT_IMG_BY_STYLE = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String[]> MOUNT_SP_BY_STYLE = new java.util.LinkedHashMap<>();
    static {
        SKILL_PREFIX_BY_STYLE.put("horror", new String[]{"血祭","亡灵","诅咒","噬魂","腐朽","怨念","骨咒","冥河","煞血","尸傀"});
        SKILL_PREFIX_BY_STYLE.put("scifi", new String[]{"量子","星舰","曲速","纳米","基因","引力","熵增","矩阵","数据","脉冲"});
        SKILL_PREFIX_BY_STYLE.put("fantasy", new String[]{"精灵","巨龙","符文","圣光","奥术","德鲁伊","秘银","元素","自然","星辉"});
        SKILL_PREFIX_BY_STYLE.put("mystery", new String[]{"迷雾","幻影","秘仪","星象","虚空","低语","回响","命轮","残像","卦象"});
        SKILL_PREFIX_BY_STYLE.put("eerie", new String[]{"诡谲","错乱","畸变","呓语","裂瞳","幽冥","蜃楼","悖谬","畸零","幻肢"});

        SKILL_CORE_BY_STYLE.put("horror", new String[]{"咒","噬","葬","蚀","煞","怨","血祭"});
        SKILL_CORE_BY_STYLE.put("scifi", new String[]{"弦","场","网","链","核","波","码"});
        SKILL_CORE_BY_STYLE.put("fantasy", new String[]{"术","歌","印","光","鳞","冠","誓"});
        SKILL_CORE_BY_STYLE.put("mystery", new String[]{"影","谜","卦","象","符","秘","回"});
        SKILL_CORE_BY_STYLE.put("eerie", new String[]{"裂","畸","诡","呓","蜃","悖"});

        ITEM_MAT_BY_STYLE.put("horror", new String[]{"枯骨","怨血","腐朽","尸","咒","幽冥"});
        ITEM_MAT_BY_STYLE.put("scifi", new String[]{"量子","纳米","能量","合金","机甲","义体","芯片","反应堆"});
        ITEM_MAT_BY_STYLE.put("fantasy", new String[]{"精灵","龙鳞","秘银","符文","圣晶","神木","星辉"});
        ITEM_MAT_BY_STYLE.put("mystery", new String[]{"星盘","罗盘","秘典","符印","残卷","幻"});
        ITEM_MAT_BY_STYLE.put("eerie", new String[]{"畸","呓","裂","诡","蜃","错"});
        ITEM_OBJ_BY_STYLE.put("horror", new String[]{"棺","颅","瓶","幡","偶","匣","珠","铃"});
        ITEM_OBJ_BY_STYLE.put("scifi", new String[]{"核","芯","舱","甲","仪","片","阵","端"});
        ITEM_OBJ_BY_STYLE.put("fantasy", new String[]{"冠","杖","匣","晶","铃","盘","卷","圭"});
        ITEM_OBJ_BY_STYLE.put("mystery", new String[]{"盘","典","镜","印","卷","珠","匣"});
        ITEM_OBJ_BY_STYLE.put("eerie", new String[]{"匣","镜","偶","珠","卷","铃"});

        WEAPON_IMG_BY_STYLE.put("horror", new String[]{"噬魂","饮血","碎骨","哀嚎","锈刃","怨灵"});
        WEAPON_IMG_BY_STYLE.put("scifi", new String[]{"脉冲","激光","离子","磁轨","湮灭","量子"});
        WEAPON_IMG_BY_STYLE.put("fantasy", new String[]{"龙牙","精灵","符文","圣光","秘银"});
        WEAPON_IMG_BY_STYLE.put("mystery", new String[]{"幻影","星陨","残响","秘银"});
        WEAPON_IMG_BY_STYLE.put("eerie", new String[]{"裂瞳","呓语","畸变","诡刃"});

        FACTION_TERR_BY_STYLE.put("horror", new String[]{"血月","枯骨","亡灵","怨灵","幽冥","尸潮"});
        FACTION_TERR_BY_STYLE.put("scifi", new String[]{"星舰","轨道","银河","矩阵","赛博","新京"});
        FACTION_TERR_BY_STYLE.put("fantasy", new String[]{"精灵","龙裔","秘银","符文","圣光","星陨"});
        FACTION_TERR_BY_STYLE.put("mystery", new String[]{"迷雾","星象","秘社","虚空","低语"});
        FACTION_TERR_BY_STYLE.put("eerie", new String[]{"诡域","裂界","畸零","呓语","蜃楼"});
        FACTION_SUF_BY_STYLE.put("horror", new String[]{"教团","墓域","血族","瘟疫","魔渊"});
        FACTION_SUF_BY_STYLE.put("scifi", new String[]{"联邦","财团","议会","公司","枢纽"});
        FACTION_SUF_BY_STYLE.put("fantasy", new String[]{"王国","议会","圣堂","同盟","林地"});
        FACTION_SUF_BY_STYLE.put("mystery", new String[]{"学会","结社","圣殿","隐会","秘社"});
        FACTION_SUF_BY_STYLE.put("eerie", new String[]{"异教","错会","诡社","畸盟"});

        MOUNT_IMG_BY_STYLE.put("horror", new String[]{"骨翼","尸鸦","怨灵","幽骑"});
        MOUNT_IMG_BY_STYLE.put("scifi", new String[]{"机甲","悬浮","反重力","光翼"});
        MOUNT_IMG_BY_STYLE.put("fantasy", new String[]{"龙裔","独角","狮鹫","飞马"});
        MOUNT_IMG_BY_STYLE.put("mystery", new String[]{"星兽","幻驹","秘鳞"});
        MOUNT_IMG_BY_STYLE.put("eerie", new String[]{"畸兽","呓影","裂空"});
        MOUNT_SP_BY_STYLE.put("horror", new String[]{"骸骨","亡魂","尸鸦","魇马"});
        MOUNT_SP_BY_STYLE.put("scifi", new String[]{"机甲兽","悬浮车","光翼兽","纳米兽"});
        MOUNT_SP_BY_STYLE.put("fantasy", new String[]{"巨龙","独角兽","狮鹫","飞马","雪豹"});
        MOUNT_SP_BY_STYLE.put("mystery", new String[]{"星兽","幻驹","秘鳞兽"});
        MOUNT_SP_BY_STYLE.put("eerie", new String[]{"畸兽","呓影","裂空兽"});
    }

    private static String[] stylePool(java.util.Map<String, String[]> m, String style, String[] def) {
        String[] v = m.get(style); return v != null ? v : def;
    }

    private final java.util.concurrent.ThreadLocalRandom namingRnd = java.util.concurrent.ThreadLocalRandom.current();

    private String npick(String[] a) { return a[namingRnd.nextInt(a.length)]; }

    private void handleNamingApi(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}"); return; }
        String query = exchange.getRequestURI().getQuery();
        String type = getQueryParam(query, "type");
        String style = getQueryParam(query, "style");
        String gender = getQueryParam(query, "gender");
        String surname = getQueryParam(query, "surname");
        String keyword = getQueryParam(query, "keyword");
        String countStr = getQueryParam(query, "count");
        String bookPath = getQueryParam(query, "path");
        try {
            sendJson(exchange, 200, buildNamingJson(type, style, gender, surname, keyword, countStr, bookPath));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private String buildNamingJson(String type, String style, String gender, String surname, String keyword, String countStr, String bookPath) throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        if (type == null || type.isBlank()) { resp.put("ok", false); resp.put("error", "缺少 type 参数"); return mapper.writeValueAsString(resp); }
        if (!java.util.Arrays.asList("person", "skill", "item", "weapon", "faction", "mount").contains(type)) {
            resp.put("ok", false); resp.put("error", "未知 type: " + type); return mapper.writeValueAsString(resp);
        }
        int count = 10;
        try { if (countStr != null) count = Math.max(1, Math.min(30, Integer.parseInt(countStr.trim()))); } catch (Exception ignore) {}
        if (style == null || style.isBlank() || !NAMING_STYLE_DESC.containsKey(style)) style = "xianxia";
        if (gender == null || gender.isBlank()) gender = "male";
        if (keyword == null) keyword = "";
        if (surname == null) surname = "";

        java.util.Set<String> existing = new java.util.HashSet<>();
        if (bookPath != null && isPathWithinBooksRoot(bookPath)) {
            Path charsFile = Paths.get(bookPath, "truth", "characters.json");
            if (Files.exists(charsFile)) {
                try {
                    JsonNode arr = mapper.readTree(Files.readAllBytes(charsFile));
                    java.util.Iterator<JsonNode> it = null;
                    if (arr.isArray()) it = arr.elements();
                    else if (arr.isObject() && arr.path("characters").isArray()) it = arr.path("characters").elements();
                    else if (arr.isObject()) { java.util.Iterator<String> ks = arr.fieldNames(); while (ks.hasNext()) existing.add(ks.next()); }
                    if (it != null) while (it.hasNext()) { String nm = it.next().path("name").asText("").trim(); if (!nm.isEmpty()) existing.add(nm); }
                } catch (Exception ignore) {}
            }
        }

        java.util.List<ObjectNode> names = new java.util.ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        int guard = 0;
        while (names.size() < count && guard < count * 30 + 300) {
            guard++;
            String[] pair = generateOneName(type, style, gender, surname, keyword);
            if (pair == null) break;
            String name = pair[0];
            if (name.isEmpty() || used.contains(name)) continue;
            if (type.equals("person") && existing.contains(name)) continue;
            used.add(name);
            ObjectNode o = mapper.createObjectNode();
            o.put("name", name);
            o.put("meaning", pair[1]);
            names.add(o);
        }
        resp.put("ok", true);
        resp.put("type", type);
        resp.put("style", style);
        resp.put("generated", names.size());
        ArrayNode arr = mapper.createArrayNode();
        for (ObjectNode o : names) arr.add(o);
        resp.set("names", arr);
        return mapper.writeValueAsString(resp);
    }

    private String[] generateOneName(String type, String style, String gender, String surname, String keyword) {
        String styleLabel = NAMING_STYLE_DESC.getOrDefault(style, "古风");
        switch (type) {
            case "person": {
                String[] base = NAMING_GIVEN.get(style);
                java.util.List<String> pool = new java.util.ArrayList<>(java.util.Arrays.asList(base));
                if (gender.equals("female")) pool.addAll(java.util.Arrays.asList(NAMING_FEM_PREFIX));
                else pool.addAll(java.util.Arrays.asList(NAMING_MASC_PREFIX));
                String[] poolArr = pool.toArray(new String[0]);
                String sur = surname.isEmpty() ? npick(NAMING_SURNAMES) : surname;
                if (!keyword.isEmpty()) {
                    String given = keyword.length() <= 2 ? keyword : keyword.substring(0, 2);
                    String name = sur + given;
                    return new String[]{name, "【" + styleLabel + "】" + sur + "姓，取「" + given + "」为意，依用户关键字而定。"};
                }
                int n = namingRnd.nextDouble() < 0.35 ? 1 : 2;
                // 首字必取风格主池，保证风格辨识度；次字可从性别扩展池取
                String g1 = npick(base);
                StringBuilder gb = new StringBuilder(g1);
                if (n == 2) { String g2 = npick(poolArr); int t = 0; while (g2.equals(g1) && t++ < 8) g2 = npick(poolArr); gb.append(g2); }
                String given = gb.toString();
                String name = sur + given;
                return new String[]{name, "【" + styleLabel + "】" + sur + "姓，" + glossPhrase(given) + "。"};
            }
            case "skill": {
                String name; String meaning;
                if (namingRnd.nextBoolean()) {
                    String prefix = npick(stylePool(SKILL_PREFIX_BY_STYLE, style, SKILL_PREFIX));
                    String core = npick(stylePool(SKILL_CORE_BY_STYLE, style, SKILL_CORE));
                    String suffix = npick(SKILL_SUFFIX);
                    name = prefix + core + suffix;
                    meaning = "【" + styleLabel + "】" + prefix + "门意境，以" + core + "为体，" + suffix + "成法，威力深不可测。";
                } else {
                    String verb = npick(SKILL_VERB);
                    String core = npick(SKILL_CORE);
                    String form = namingRnd.nextBoolean() ? npick(SKILL_FORM) : "";
                    name = verb + core + form;
                    meaning = "【招式】" + verb + core + (form.isEmpty() ? "" : form) + "，凌厉杀招，出其不意。";
                }
                return withKeyword(name, meaning, keyword);
            }
            case "item": {
                String grade = namingRnd.nextDouble() < 0.5 ? npick(ITEM_GRADE) : "";
                String mat = npick(stylePool(ITEM_MAT_BY_STYLE, style, ITEM_MAT));
                String obj = npick(stylePool(ITEM_OBJ_BY_STYLE, style, ITEM_OBJ));
                String name = grade + mat + obj;
                String meaning = "【" + (grade.isEmpty() ? "灵物" : grade) + "】以" + mat + "所制" + obj + "，光华内蕴，灵性自生。";
                return withKeyword(name, meaning, keyword);
            }
            case "weapon": {
                String img = npick(stylePool(WEAPON_IMG_BY_STYLE, style, WEAPON_IMG));
                String cls = npick(WEAPON_CLS);
                String name = img + cls;
                String meaning = "【神兵】" + img + "之" + cls + "，锋锐无匹，饮血封喉。";
                return withKeyword(name, meaning, keyword);
            }
            case "faction": {
                String terr = npick(stylePool(FACTION_TERR_BY_STYLE, style, FACTION_TERR));
                String suf = npick(stylePool(FACTION_SUF_BY_STYLE, style, FACTION_SUF));
                String name = terr + suf;
                String meaning = "【势力】据" + terr + "之地而立" + suf + "，号令一方，威震四海。";
                return withKeyword(name, meaning, keyword);
            }
            case "mount": {
                String img = npick(stylePool(MOUNT_IMG_BY_STYLE, style, MOUNT_IMG));
                String sp = npick(stylePool(MOUNT_SP_BY_STYLE, style, MOUNT_SP));
                // 避免叠加重复词（如「狮鹫狮鹫」）：img 与 sp 相同或互含时直接用 sp
                if (sp.contains(img) || img.contains(sp)) {
                    String name = sp;
                    String meaning = "【坐骑】" + name + "，神骏通灵，日行万里。";
                    return withKeyword(name, meaning, keyword);
                }
                String name = img + sp;
                String meaning = "【坐骑】" + img + sp + "，神骏通灵，日行万里。";
                return withKeyword(name, meaning, keyword);
            }
            default:
                return null;
        }
    }

    private String[] withKeyword(String name, String meaning, String keyword) {
        if (!keyword.isEmpty()) return new String[]{keyword + name, meaning + "（嵌关键字「" + keyword + "」）"};
        return new String[]{name, meaning};
    }

    private String glossPhrase(String given) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < given.length(); i++) {
            String c = given.substring(i, i + 1);
            String g = NAMING_GLOSS.get(c);
            if (g != null) sb.append("「").append(c).append("」").append(g).append("，");
        }
        if (sb.length() == 0) sb.append("寓意清雅出尘");
        else sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    // ===================== 取名收藏 / 批量落库 =====================

    private void handleNamingSaveApi(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}"); return; }
        try {
            JsonNode body = readBody(exchange);
            String path = body.path("path").asText("").trim();
            String target = body.path("target").asText("favorites").trim();
            JsonNode entries = body.path("entries");
            if (path.isEmpty() || !isPathWithinBooksRoot(path)) {
                sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("ok", false).put("error", "无效书目路径")));
                return;
            }
            if (!entries.isArray()) {
                sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("ok", false).put("error", "entries 必须是数组")));
                return;
            }
            Path bookDir = Paths.get(path);
            Path truthDir = bookDir.resolve("truth");
            Files.createDirectories(truthDir);
            int saved = 0, skipped = 0;
            if ("characters".equals(target)) {
                Path charsFile = truthDir.resolve("characters.json");
                java.util.List<ObjectNode> list = new java.util.ArrayList<>();
                java.util.Set<String> existNames = new java.util.HashSet<>();
                if (Files.exists(charsFile)) {
                    JsonNode existing = mapper.readTree(Files.readAllBytes(charsFile));
                    if (existing.isArray()) {
                        for (JsonNode n : existing) { list.add((ObjectNode) n.deepCopy()); if (n.has("name")) existNames.add(n.path("name").asText("")); }
                    } else if (existing.isObject() && existing.path("characters").isArray()) {
                        // 标准形态 {"characters":[{name,description},...]}
                        for (JsonNode n : existing.path("characters")) { list.add((ObjectNode) n.deepCopy()); if (n.has("name")) existNames.add(n.path("name").asText("")); }
                    } else if (existing.isObject()) {
                        // 兼容 {"张三":{...}} 映射形态
                        java.util.Iterator<String> it = existing.fieldNames();
                        while (it.hasNext()) { String k = it.next(); ObjectNode o = mapper.createObjectNode(); o.put("name", k); list.add(o); existNames.add(k); }
                    }
                }
                for (JsonNode e : entries) {
                    if (!"person".equals(e.path("type").asText(""))) continue;
                    String nm = e.path("name").asText("").trim();
                    if (nm.isEmpty() || existNames.contains(nm)) { skipped++; continue; }
                    ObjectNode o = mapper.createObjectNode();
                    o.put("name", nm);
                    o.put("role", "（取名器生成）");
                    if (e.has("meaning")) o.put("meaning", e.path("meaning").asText(""));
                    o.put("source", "naming");
                    list.add(o); existNames.add(nm); saved++;
                }
                ObjectNode root = mapper.createObjectNode();
                ArrayNode arr = mapper.createArrayNode();
                for (ObjectNode o : list) arr.add(o);
                root.set("characters", arr);   // 写回标准形态 {"characters":[...]}，与既有 ledger 兼容
                Files.write(charsFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
                sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode()
                        .put("ok", true).put("target", "characters").put("saved", saved).put("skipped", skipped)
                        .put("message", "已导入 " + saved + " 个角色，跳过 " + skipped + " 个（重名或非人物）")));
            } else {
                Path favFile = truthDir.resolve("naming_favorites.json");
                java.util.List<ObjectNode> list = new java.util.ArrayList<>();
                if (Files.exists(favFile)) {
                    JsonNode existing = mapper.readTree(Files.readAllBytes(favFile));
                    if (existing.isArray()) for (JsonNode n : existing) list.add((ObjectNode) n.deepCopy());
                }
                for (JsonNode e : entries) {
                    String nm = e.path("name").asText("").trim();
                    if (nm.isEmpty()) { skipped++; continue; }
                    ObjectNode o = mapper.createObjectNode();
                    o.put("type", e.path("type").asText(""));
                    o.put("name", nm);
                    if (e.has("meaning")) o.put("meaning", e.path("meaning").asText(""));
                    list.add(o); saved++;
                }
                ArrayNode arr = mapper.createArrayNode();
                for (ObjectNode o : list) arr.add(o);
                Files.write(favFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(arr));
                sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode()
                        .put("ok", true).put("target", "favorites").put("saved", saved).put("skipped", skipped)
                        .put("message", "已收藏 " + saved + " 条到素材库（naming_favorites.json）")));
            }
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // ===================== Ollama 离线链路 =====================

    private void handleOllamaModelsApi(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}"); return; }
        String q = exchange.getRequestURI().getQuery();
        String base = getQueryParam(q, "baseUrl");
        if (base == null || base.isBlank()) base = "http://localhost:11434";
        try {
            String resp = ollamaGet(base, "/api/tags");
            JsonNode node = mapper.readTree(resp);
            java.util.List<String> models = new java.util.ArrayList<>();
            JsonNode ms = node.path("models");
            if (ms.isArray()) for (JsonNode m : ms) models.add(m.path("name").asText(""));
            sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode()
                    .put("ok", true).put("baseUrl", base).put("count", models.size()).set("models", mapper.valueToTree(models))));
        } catch (Exception e) {
            sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode()
                    .put("ok", false).put("baseUrl", base).put("error", e.getMessage()).set("models", mapper.createArrayNode())));
        }
    }

    private void handleOllamaHealthApi(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}"); return; }
        String q = exchange.getRequestURI().getQuery();
        String base = getQueryParam(q, "baseUrl");
        if (base == null || base.isBlank()) base = "http://localhost:11434";
        try {
            String resp = ollamaGet(base, "/api/tags");
            JsonNode node = mapper.readTree(resp);
            int count = node.path("models").isArray() ? node.path("models").size() : 0;
            sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode()
                    .put("ok", true).put("reachable", true).put("baseUrl", base).put("count", count)));
        } catch (Exception e) {
            sendJson(exchange, 200, mapper.writeValueAsString(mapper.createObjectNode()
                    .put("ok", true).put("reachable", false).put("baseUrl", base).put("error", e.getMessage())));
        }
    }

    // ===================== 设定集术语表 / 资源战力账本 =====================

    private void handleGlossaryApi(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}"); return; }
        String bookPath = getQueryParam(exchange.getRequestURI().getQuery(), "path");
        try {
            if (bookPath == null || bookPath.isBlank() || !isPathWithinBooksRoot(bookPath)) {
                sendJson(exchange, 400, "{\"ok\":false,\"error\":\"无效书目路径\"}");
                return;
            }
            ObjectNode resp = GlossaryBuilder.build(mapper, Paths.get(bookPath));
            sendJson(exchange, 200, mapper.writeValueAsString(resp));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleLedgerApi(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}"); return; }
        String bookPath = getQueryParam(exchange.getRequestURI().getQuery(), "path");
        try {
            if (bookPath == null || bookPath.isBlank() || !isPathWithinBooksRoot(bookPath)) {
                sendJson(exchange, 400, "{\"ok\":false,\"error\":\"无效书目路径\"}");
                return;
            }
            ObjectNode resp = LedgerBuilder.build(mapper, Paths.get(bookPath));
            sendJson(exchange, 200, mapper.writeValueAsString(resp));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleTensionApi(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}"); return; }
        String bookPath = getQueryParam(exchange.getRequestURI().getQuery(), "path");
        try {
            if (bookPath == null || bookPath.isBlank() || !isPathWithinBooksRoot(bookPath)) {
                sendJson(exchange, 400, "{\"ok\":false,\"error\":\"无效书目路径\"}");
                return;
            }
            ObjectNode resp = TensionBuilder.build(mapper, Paths.get(bookPath));
            sendJson(exchange, 200, mapper.writeValueAsString(resp));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private String ollamaGet(String baseUrl, String path) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + path;
        HttpClient hc = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Accept", "application/json").GET().timeout(java.time.Duration.ofSeconds(8)).build();
        HttpResponse<String> resp = hc.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("Ollama HTTP " + resp.statusCode());
        return resp.body();
    }

    private boolean containsText(JsonNode arr, String s) {
        if (arr == null) return false;
        for (JsonNode n : arr) if (n.isTextual() && n.asText().equals(s)) return true;
        return false;
    }

    /** Merge a weighted edge into the edge map (dedup by unordered pair). */
    private void addGraphEdge(Map<String, ObjectNode> edgeMap, String a, String b, int weight, String label) {
        if (a == null || b == null || a.equals(b)) return;
        String key = a.compareTo(b) <= 0 ? a + "\u0000" + b : b + "\u0000" + a;
        ObjectNode e = edgeMap.get(key);
        if (e == null) {
            e = mapper.createObjectNode();
            int cmp = a.compareTo(b);
            e.put("source", cmp <= 0 ? a : b);
            e.put("target", cmp <= 0 ? b : a);
            e.put("weight", weight);
            if (label != null) e.put("label", label);
            edgeMap.put(key, e);
        } else {
            e.put("weight", e.path("weight").asInt() + weight);
            if (label != null && !e.has("label")) e.put("label", label);
        }
    }

    /** Names (len>=2) that appear in the text. */
    private List<String> findMentionedNames(String text, java.util.Set<String> names) {
        List<String> found = new java.util.ArrayList<>();
        for (String name : names) {
            if (name.length() >= 2 && text.contains(name)) found.add(name);
        }
        return found;
    }

    private int countOccurrences(String text, String needle) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
        return Math.min(count, 999);
    }

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
            java.util.List<Reference> oRefs = null; java.util.List<Reference> oInsps = null;
            if (bookPath != null) { try { Book _b = BookProject.loadBook(Paths.get(bookPath)); oRefs = _b.getReferences(); oInsps = _b.getInspirations(); } catch (Exception ignored) {} }
            List<Map<String, String>> messages = pb.buildOutlineFromPromptPrompt(prompt, genre, oRefs, oInsps);
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
            java.util.List<Reference> vRefs2 = null; java.util.List<Reference> vInsps2 = null;
            if (bookPath != null) { try { Book _b = BookProject.loadBook(Paths.get(bookPath)); vRefs2 = _b.getReferences(); vInsps2 = _b.getInspirations(); } catch (Exception ignored) {} }
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildVolumeOutlinePrompt(outline, prompt, genre, vRefs2, vInsps2);
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
            java.util.List<Reference> cRefs = null; java.util.List<Reference> cInsps = null;
            if (bookPath != null) { try { Book _b = BookProject.loadBook(Paths.get(bookPath)); cRefs = _b.getReferences(); cInsps = _b.getInspirations(); } catch (Exception ignored) {} }
            List<Map<String, String>> messages = pb.buildChapterSynopsisPrompt(outlineOrVolume, prompt, genre, cRefs, cInsps);
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

    private String sanitize(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|]", "_")
                     .replaceAll("\\s+", "-")
                     .trim();
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
        if (noAuth) return true;

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
        private void handleWorldApi(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, 0); exchange.getResponseBody().close(); return;
        }
        String query = exchange.getRequestURI().getQuery();
        String path = getQueryParam(query, "path");
        if (path == null || path.isEmpty()) {
            sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "path required")));
            return;
        }
        try {
            var bookDir = booksRoot.resolve(path);
            var state = new TruthState(bookDir);
            var result = mapper.createObjectNode();
            // World data
            var worldData = state.world().getData();
            if (worldData != null && !worldData.isEmpty()) {
                result.set("world", worldData);
            }
            // Characters
            var charData = state.characters().listAll();
            if (charData != null && !charData.isEmpty()) {
                result.set("characters", charData);
            }
            // Hooks
            var hookData = state.hooks().listAll();
            if (hookData != null && !hookData.isEmpty()) {
                result.set("hooks", hookData);
            }
            result.put("bookPath", path);
            sendJson(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(mapper.createObjectNode().put("error", "Failed: " + e.getMessage())));
        }
    }
        private void handleAiSelectionApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, 0); exchange.getResponseBody().close(); return;
        }
        try {
            JsonNode body = readBody(exchange);
            String text = body.has("text") ? body.get("text").asText() : "";
            String action = body.has("action") ? body.get("action").asText() : "expand";
            String bookPath = body.has("path") ? body.get("path").asText() : null;
            
            if (text.isEmpty()) {
                sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "text required")));
                return;
            }
            
            var resolvedRouter = resolveModelRouter(body);
            var client = resolvedRouter.getClientForAgent("Writer");
            String model = resolvedRouter.getModelForAgent("Writer");
            
            // Build prompt based on action
            var messages = new java.util.ArrayList<java.util.Map<String, String>>();
            String systemPrompt;
            String userPrompt;
            
            switch (action.toLowerCase()) {
                case "expand" -> {
                    systemPrompt = "你是专业的小说写作助手，擅长扩写和丰富段落细节。请保持原文风格和语气，增加细节描写、感官体验和情感深度，使内容更加丰满生动。直接输出扩写后的文本，不要加任何解释说明。";
                    userPrompt = "请扩写以下段落，保持风格一致，增加细节和深度，字数扩展到原文的2-3倍：\n\n" + text;
                }
                case "polish" -> {
                    systemPrompt = "你是专业的文字编辑，擅长润色和优化文笔。请改善语言流畅度、修辞美感和节奏感，保持原意不变，不要大幅改变内容。直接输出润色后的文本，不要加任何解释说明。";
                    userPrompt = "请润色以下段落，提升文笔质量，保持原意：\n\n" + text;
                }
                case "rewrite" -> {
                    systemPrompt = "你是专业的小说写作助手，擅长用不同风格重写段落。请保持核心情节不变，但用不同的表达方式重新创作。直接输出改写后的文本，不要加任何解释说明。";
                    userPrompt = "请用不同风格改写以下段落，保持核心情节不变：\n\n" + text;
                }
                default -> {
                    systemPrompt = "你是专业的写作助手。请改进以下文本。直接输出结果，不要加解释。";
                    userPrompt = text;
                }
            }
            
            // Add book context if available
            if (bookPath != null && !bookPath.isEmpty()) {
                try {
                    var book = BookProject.loadBook(booksRoot.resolve(bookPath));
                    systemPrompt += "\n当前作品：《" + book.getTitle() + "》";
                    if (book.getGenre() != null) systemPrompt += "（" + book.getGenre() + "）";
                } catch (Exception ignored) {}
            }
            
            messages.add(java.util.Map.of("role", "system", "content", systemPrompt));
            messages.add(java.util.Map.of("role", "user", "content", userPrompt));
            
            // Streaming response
            sendSseHeaders(exchange);
            var os = exchange.getResponseBody();
            var fullResponse = new StringBuilder();
            
            client.chatCompleteStream(messages, model, 0.7, 4000, new com.novelforge.core.llm.StreamHandler() {
                @Override
                public void onChunk(String chunk) {
                    fullResponse.append(chunk);
                    try { sendSseEvent(os, "chunk", chunk); } catch (java.io.IOException ignored) {}
                }
                @Override
                public void onComplete(String result) {
                    try { sendSseEvent(os, "done", fullResponse.toString()); } catch (java.io.IOException ignored) {}
                }
                @Override
                public void onError(Exception e) {
                    try { sendSseEvent(os, "error", e.getMessage()); } catch (java.io.IOException ignored) {}
                }
            });
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(mapper.createObjectNode().put("error", e.getMessage())));
        }
    }
    
private void handleChatApi(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, 0); exchange.getResponseBody().close(); return;
        }
        try {
            JsonNode body = readBody(exchange);
            String bookPath = body.has("path") ? body.get("path").asText() : null;
            String message = body.has("message") ? body.get("message").asText() : "";
            String[] creds = resolveApiCredentials(body);
            String baseUrl = body.has("baseUrl") ? body.get("baseUrl").asText() : null;
            String model = body.has("model") ? body.get("model").asText() : null;
            
            if (message.isEmpty()) { sendJson(exchange, 400, mapper.writeValueAsString(mapper.createObjectNode().put("error", "message required"))); return; }
            
            var resolvedRouter = resolveModelRouter(body);
            // model resolved below
            
            // Build context from book if available
            var messages = new java.util.ArrayList<java.util.Map<String, String>>();
            var systemPrompt = new StringBuilder();
            systemPrompt.append("你是NovelForge智能写作助手。你精通小说创作的各个方面，包括情节构建、人物塑造、对话设计、场景描写、伏笔设置等。");
            
            if (bookPath != null && !bookPath.isEmpty()) {
                try {
                    var book = BookProject.loadBook(booksRoot.resolve(bookPath));
                    systemPrompt.append("\n\n当前作品：《").append(book.getTitle()).append("》");
                    if (book.getGenre() != null) systemPrompt.append("（").append(book.getGenre()).append("）");
                    if (book.getOutline() != null && !book.getOutline().isEmpty()) {
                        systemPrompt.append("\n大纲摘要：").append((CharSequence)(book.getOutline().length() > 500 ? book.getOutline().substring(0, 500) + "..." : book.getOutline()));
                    }
                    if (book.getAuthorIntent() != null && !book.getAuthorIntent().isEmpty()) {
                        systemPrompt.append("\n创作意图：").append(book.getAuthorIntent());
                    }
                    // Add recent chapter context
                    if (book.getChapters() != null && !book.getChapters().isEmpty()) {
                        var lastChapter = book.getChapters().get(book.getChapters().size() - 1);
                        String chapterText = lastChapter.getFinalText() != null ? lastChapter.getFinalText() : lastChapter.getDraftText();
                        if (chapterText != null && !chapterText.isEmpty()) {
                            systemPrompt.append("\n最新章节《").append(lastChapter.getTitle()).append("》：");
                            systemPrompt.append(chapterText.length() > 800 ? chapterText.substring(0, 800) + "..." : chapterText);
                        }
                    }
                } catch (Exception e) {
                    systemPrompt.append("\n（无法加载作品信息）");
                }
            }
            systemPrompt.append("\n\n请基于以上信息回答用户的问题，给出专业、具体的建议。");
            
            messages.add(java.util.Map.of("role", "system", "content", systemPrompt.toString()));
            messages.add(java.util.Map.of("role", "user", "content", message));
            
            // Use streaming response
            sendSseHeaders(exchange);
            var os = exchange.getResponseBody();
            var fullResponse = new StringBuilder();
            
            var client = resolvedRouter.getClientForAgent("Architect");
            String resolvedModel = resolvedRouter.getModelForAgent("Architect");
            if (body.has("model") && !body.get("model").asText().isEmpty()) resolvedModel = body.get("model").asText();
            client.chatCompleteStream(messages, resolvedModel, 0.7, 4000, new com.novelforge.core.llm.StreamHandler() {
                @Override
                public void onChunk(String chunk) {
                    fullResponse.append(chunk);
                    try { sendSseEvent(os, "chunk", chunk); } catch (java.io.IOException ignored) {}
                }
                @Override
                public void onComplete(String result) {
                    try { sendSseEvent(os, "done", fullResponse.toString()); } catch (java.io.IOException ignored) {}
                }
                @Override
                public void onError(Exception e) {
                    try { sendSseEvent(os, "error", e.getMessage()); } catch (java.io.IOException ignored) {}
                }
            });
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(mapper.createObjectNode().put("error", e.getMessage())));
        }
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
    // --- SSE Streaming Helpers ---
    private void sendSseHeaders(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        addCorsHeaders(exchange);
        exchange.sendResponseHeaders(200, 0);
    }

    private void sendSseEvent(OutputStream os, String event, String data) throws IOException {
        String msg = "event: " + event + "\ndata: " + data.replace("\n", "\\n") + "\n\n";
        os.write(msg.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    // --- SSE Stream: Outline Generate ---
    private void handleOutlineGenerateStreamApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String prompt = body.has("prompt") ? body.get("prompt").asText() : null;
        String genre = body.has("genre") ? body.get("genre").asText() : "xuanhuan";
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        final ModelRouter router = resolveModelRouter(body);
        if (prompt == null || router == null) { sendJson(exchange, 400, "{\"error\":\"prompt and apiKey required\"}"); return; }
        if (bookPath != null && !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path must be within books directory\"}"); return; }

        sendSseHeaders(exchange);
        OutputStream os = exchange.getResponseBody();
        StringBuilder fullText = new StringBuilder();
        try {
            LlmClient client = router.getClientForAgent("Architect");
            // Load references/inspirations from book for context injection
            java.util.List<Reference> refs = null;
            java.util.List<Reference> insps = null;
            if (bookPath != null) {
                try {
                    Book _b = BookProject.loadBook(Paths.get(bookPath));
                    refs = _b.getReferences();
                    insps = _b.getInspirations();
                } catch (Exception ignored) {}
            }
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildOutlineFromPromptPrompt(prompt, genre, refs, insps);
            client.chatCompleteStream(messages, router.getModelForAgent("Architect"), 0.6, 8000, new StreamHandler() {
                @Override public void onChunk(String chunk) {
                    fullText.append(chunk);
                    try { sendSseEvent(os, "chunk", chunk); } catch (IOException e) { /* stream closed */ }
                }
                @Override public void onComplete(String text) {
                    recordUsage("stream", estimateTokens(messages), estimateTokens(fullText), 0);
                    try {
                        if (bookPath != null) {
                            Book book = BookProject.loadBook(Paths.get(bookPath));
                            book.setOutline(text);
                            BookProject.saveBookMetadata(Paths.get(bookPath), book);
                        }
                        sendSseEvent(os, "done", "{\"status\":\"ok\"}");
                    } catch (Exception e) { /* ignore */ }
                }
                @Override public void onError(Exception e) {
                    try { sendSseEvent(os, "error", sanitizeForJson(e.getMessage())); } catch (IOException ignored) {}
                }
            });
        } catch (Exception e) {
            try { sendSseEvent(os, "error", sanitizeForJson(e.getMessage())); } catch (IOException ignored) {}
        } finally {
            try { os.close(); } catch (Exception ignored) {}
        }
    }

    // --- SSE Stream: Volume Generate ---
    private void handleVolumeGenerateStreamApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String outline = body.has("outline") ? body.get("outline").asText() : null;
        String prompt = body.has("prompt") ? body.get("prompt").asText() : "";
        String genre = body.has("genre") ? body.get("genre").asText() : "xuanhuan";
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        final ModelRouter router = resolveModelRouter(body);
        if (router == null) { sendJson(exchange, 400, "{\"error\":\"apiKey required\"}"); return; }
        if (outline == null && bookPath != null && isPathWithinBooksRoot(bookPath)) {
            try { outline = BookProject.loadBook(Paths.get(bookPath)).getOutline(); } catch (Exception e) { outline = ""; }
        }
        if (outline == null || outline.isEmpty()) { sendJson(exchange, 400, "{\"error\":\"outline required\"}"); return; }

        sendSseHeaders(exchange);
        OutputStream os = exchange.getResponseBody();
        StringBuilder fullText = new StringBuilder();
        final String outlineText = outline;
        try {
            LlmClient client = router.getClientForAgent("Architect");
            // Load references/inspirations from book for context injection
            java.util.List<Reference> vRefs = null; java.util.List<Reference> vInsps = null;
            if (bookPath != null) { try { Book _b = BookProject.loadBook(Paths.get(bookPath)); vRefs = _b.getReferences(); vInsps = _b.getInspirations(); } catch (Exception ignored) {} }
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildVolumeOutlinePrompt(outlineText, prompt, genre, vRefs, vInsps);
            client.chatCompleteStream(messages, router.getModelForAgent("Architect"), 0.5, 8000, new StreamHandler() {
                @Override public void onChunk(String chunk) {
                    fullText.append(chunk);
                    try { sendSseEvent(os, "chunk", chunk); } catch (IOException e) { /* stream closed */ }
                }
                @Override public void onComplete(String text) {
                    recordUsage("stream", estimateTokens(messages), estimateTokens(fullText), 0);
                    try { sendSseEvent(os, "done", "{\"status\":\"ok\"}"); } catch (IOException ignored) {}
                }
                @Override public void onError(Exception e) {
                    try { sendSseEvent(os, "error", sanitizeForJson(e.getMessage())); } catch (IOException ignored) {}
                }
            });
        } catch (Exception e) {
            try { sendSseEvent(os, "error", sanitizeForJson(e.getMessage())); } catch (IOException ignored) {}
        } finally {
            try { os.close(); } catch (Exception ignored) {}
        }
    }

    // --- SSE Stream: Outline Synopsis ---
    private void handleOutlineSynopsisStreamApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        final ModelRouter router = resolveModelRouter(body);
        if (bookPath == null || router == null || !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path and apiKey required\"}"); return;
        }

        sendSseHeaders(exchange);
        OutputStream os = exchange.getResponseBody();
        StringBuilder fullText = new StringBuilder();
        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            TruthState state = new TruthState(Paths.get(bookPath));
            LlmClient client = router.getClientForAgent("Architect");
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildOutlineSynopsisPrompt(book, state);
            client.chatCompleteStream(messages, router.getModelForAgent("Architect"), 0.5, 8000, new StreamHandler() {
                @Override public void onChunk(String chunk) {
                    fullText.append(chunk);
                    try { sendSseEvent(os, "chunk", chunk); } catch (IOException e) { /* stream closed */ }
                }
                @Override public void onComplete(String text) {
                    recordUsage("stream", estimateTokens(messages), estimateTokens(fullText), 0);
                    try {
                        Book b = BookProject.loadBook(Paths.get(bookPath));
                        b.setOutline(text);
                        BookProject.saveBookMetadata(Paths.get(bookPath), b);
                        sendSseEvent(os, "done", "{\"status\":\"ok\"}");
                    } catch (Exception ignored) {}
                }
                @Override public void onError(Exception e) {
                    try { sendSseEvent(os, "error", sanitizeForJson(e.getMessage())); } catch (IOException ignored) {}
                }
            });
        } catch (Exception e) {
            try { sendSseEvent(os, "error", sanitizeForJson(e.getMessage())); } catch (IOException ignored) {}
        } finally {
            try { os.close(); } catch (Exception ignored) {}
        }
    }

    // --- SSE Stream: Volume Synopsis ---
    private void handleVolumeSynopsisStreamApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        int volumeStart = body.has("volumeStart") ? body.get("volumeStart").asInt() : 1;
        int volumeEnd = body.has("volumeEnd") ? body.get("volumeEnd").asInt() : 10;
        final ModelRouter router = resolveModelRouter(body);
        if (bookPath == null || router == null || !isPathWithinBooksRoot(bookPath)) {
            sendJson(exchange, 400, "{\"error\":\"path and apiKey required\"}"); return;
        }

        sendSseHeaders(exchange);
        OutputStream os = exchange.getResponseBody();
        StringBuilder fullText = new StringBuilder();
        try {
            Book book = BookProject.loadBook(Paths.get(bookPath));
            TruthState state = new TruthState(Paths.get(bookPath));
            if (book.getChapters().isEmpty()) {
                sendSseEvent(os, "error", "No chapters written yet; write chapters first");
                return;
            }
            LlmClient client = router.getClientForAgent("Architect");
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildVolumeSynopsisPrompt(book, state, volumeStart, volumeEnd);
            client.chatCompleteStream(messages, router.getModelForAgent("Architect"), 0.4, 6000, new StreamHandler() {
                @Override public void onChunk(String chunk) {
                    fullText.append(chunk);
                    try { sendSseEvent(os, "chunk", chunk); } catch (IOException e) { /* stream closed */ }
                }
                @Override public void onComplete(String text) {
                    recordUsage("stream", estimateTokens(messages), estimateTokens(fullText), 0);
                    try { sendSseEvent(os, "done", "{\"status\":\"ok\"}"); } catch (IOException ignored) {}
                }
                @Override public void onError(Exception e) {
                    try { sendSseEvent(os, "error", sanitizeForJson(e.getMessage())); } catch (IOException ignored) {}
                }
            });
        } catch (Exception e) {
            try { sendSseEvent(os, "error", sanitizeForJson(e.getMessage())); } catch (IOException ignored) {}
        } finally {
            try { os.close(); } catch (Exception ignored) {}
        }
    }

    // --- SSE Stream: Chapter Synopsis ---
    private void handleChapterSynopsisStreamApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String outlineOrVolume = body.has("source") ? body.get("source").asText() : null;
        String prompt = body.has("prompt") ? body.get("prompt").asText() : "";
        String genre = body.has("genre") ? body.get("genre").asText() : "xuanhuan";
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        final ModelRouter router = resolveModelRouter(body);
        if (router == null) { sendJson(exchange, 400, "{\"error\":\"apiKey required\"}"); return; }
        if (outlineOrVolume == null || outlineOrVolume.isEmpty()) {
            if (bookPath != null && isPathWithinBooksRoot(bookPath)) {
                try { outlineOrVolume = BookProject.loadBook(Paths.get(bookPath)).getOutline(); } catch (Exception e) { outlineOrVolume = ""; }
            } else { outlineOrVolume = ""; }
        }

        sendSseHeaders(exchange);
        OutputStream os = exchange.getResponseBody();
        StringBuilder fullText = new StringBuilder();
        final String sourceText = outlineOrVolume;
        try {
            LlmClient client = router.getClientForAgent("Architect");
            // Load references/inspirations from book for context injection
            java.util.List<Reference> csRefs = null;
            java.util.List<Reference> csInsps = null;
            if (bookPath != null) {
                try {
                    Book _b = BookProject.loadBook(Paths.get(bookPath));
                    csRefs = _b.getReferences();
                    csInsps = _b.getInspirations();
                } catch (Exception ignored) {}
            }
            PromptBuilder pb = new PromptBuilder();
            List<Map<String, String>> messages = pb.buildChapterSynopsisPrompt(sourceText, prompt, genre, csRefs, csInsps);
            client.chatCompleteStream(messages, router.getModelForAgent("Architect"), 0.7, 8000, new StreamHandler() {
                @Override public void onChunk(String chunk) {
                    fullText.append(chunk);
                    try { sendSseEvent(os, "chunk", chunk); } catch (IOException e) { /* stream closed */ }
                }
                @Override public void onComplete(String text) {
                    recordUsage("stream", estimateTokens(messages), estimateTokens(fullText), 0);
                    try {
                        if (bookPath != null && isPathWithinBooksRoot(bookPath)) {
                            Book book = BookProject.loadBook(Paths.get(bookPath));
                            String existingOutline = book.getOutline() != null ? book.getOutline() : "";
                            String updatedOutline = existingOutline + "\n\n--- 章节梗概 ---\n" + text;
                            book.setOutline(updatedOutline);
                            BookProject.saveBookMetadata(Paths.get(bookPath), book);
                        }
                        sendSseEvent(os, "done", "{\"status\":\"ok\"}");
                    } catch (Exception ignored) {}
                }
                @Override public void onError(Exception e) {
                    try { sendSseEvent(os, "error", sanitizeForJson(e.getMessage())); } catch (IOException ignored) {}
                }
            });
        } catch (Exception e) {
            try { sendSseEvent(os, "error", sanitizeForJson(e.getMessage())); } catch (IOException ignored) {}
        } finally {
            try { os.close(); } catch (Exception ignored) {}
        }
    }
    private void handleChapterContinueStreamApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return; }
        JsonNode body = readBody(exchange);
        String bookPath = body.has("path") ? body.get("path").asText() : null;
        String chapterTitle = body.has("chapterTitle") ? body.get("chapterTitle").asText() : null;
        String currentText = body.has("currentText") ? body.get("currentText").asText() : "";
        String prompt = body.has("prompt") ? body.get("prompt").asText() : "";
        int maxWords = body.has("maxWords") ? body.get("maxWords").asInt() : 2000;
        final ModelRouter router = resolveModelRouter(body);
        if (router == null) { sendJson(exchange, 400, "{\"error\":\"apiKey or model required\"}"); return; }
        if (bookPath != null && !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path must be within books directory\"}"); return; }

        // Try to load chapter text from book if not provided
        if (currentText.isEmpty() && bookPath != null && chapterTitle != null) {
            try {
                Book _b = BookProject.loadBook(Paths.get(bookPath));
                if (_b != null) {
                    for (var ch : _b.getChapters()) {
                        if (chapterTitle.equals(ch.getTitle())) {
                            currentText = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (currentText.isEmpty()) { sendJson(exchange, 400, "{\"error\":\"No chapter text provided or found\"}"); return; }

        String context = currentText.length() > 6000 ? currentText.substring(currentText.length() - 6000) : currentText;
        String sysPrompt = "你是一个专业的小说续写助手。根据已有内容，自然地续写故事。保持风格、人物性格和情节发展的一致性。只输出续写内容，不要重复已有内容。";
        String userPrompt = "请续写以下内容的后续部分";
        if (!prompt.isEmpty()) userPrompt += "，要求：" + prompt;
        userPrompt += "（续写约" + maxWords + "字）：\n\n--- 已有内容 ---\n" + context + "\n\n--- 续写 ---\n";

        var systemMsg = new java.util.LinkedHashMap<String, String>();
        systemMsg.put("role", "system");
        systemMsg.put("content", sysPrompt);
        var userMsg = new java.util.LinkedHashMap<String, String>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        var messages = new java.util.ArrayList<java.util.Map<String, String>>();
        messages.add(systemMsg);
        messages.add(userMsg);

        sendSseHeaders(exchange);
        OutputStream os = exchange.getResponseBody();
        StringBuilder fullText = new StringBuilder();
        try {
            LlmClient client = router.getClientForAgent("Writer");
            client.chatCompleteStream(messages, router.getModelForAgent("Writer"), 0.8, 4096, new StreamHandler() {
                public void onChunk(String chunk) {
                    fullText.append(chunk);
                    try { sendSseEvent(os, "chunk", chunk); } catch (java.io.IOException ignored) {}
                }
                public void onComplete(String result) {
                    try { sendSseEvent(os, "done", fullText.toString()); } catch (java.io.IOException ignored) {}
                    recordUsage(router.getModelForAgent("Writer"), estimateTokens(messages), estimateTokens(fullText), 0);
                }
                public void onError(Exception e) {
                    try { sendSseEvent(os, "error", e.getMessage()); } catch (java.io.IOException ignored) {}
                }
            });
        } catch (Exception e) {
            try { sendSseEvent(os, "error", e.getMessage()); } catch (java.io.IOException ignored) {}
        }
    }


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
        boolean noAuth = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--no-auth")) {
                noAuth = true;
            } else if ((args[i].equals("--port") || args[i].equals("-p")) && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port number: " + args[i] + ". Using default port " + DEFAULT_PORT);
                }
            } else if (!args[i].startsWith("-")) {
                try {
                    port = Integer.parseInt(args[i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port number: " + args[i] + ". Using default port " + DEFAULT_PORT);
                }
            }
        }

        StudioServer studio = new StudioServer(port, noAuth);

        studio.start();

        // Keep alive: if running in a terminal, wait for Enter; otherwise block indefinitely
        if (System.console() != null) {
            System.out.println("Press Enter to stop...");
            try { System.in.read(); } catch (IOException e) { /* ctrl+c or closed */ }
        } else {
            // Non-interactive mode (child process): block until interrupted
            System.out.println("Running in non-interactive mode. Send SIGTERM or close to stop.");
            try { Thread.currentThread().join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        studio.stop();

    }

}