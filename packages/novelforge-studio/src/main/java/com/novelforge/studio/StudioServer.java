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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

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
        WriteJob(String jobId) { this.jobId = jobId; }
    }

    public StudioServer() throws IOException {
        this(DEFAULT_PORT);
    }

    public StudioServer(int port) throws IOException {
        this.booksRoot = Paths.get(System.getProperty("user.home"), "NovelForge", "books");
        Files.createDirectories(booksRoot);
        this.defaultConfig = new PipelineConfig();
        // 🟡-1: Generate random auth token for local API access
        this.authToken = generateToken();
        this.server = HttpServer.create(new InetSocketAddress("localhost", port), 0);

        // Static frontend
        server.createContext("/", this::serveStatic);

        // API endpoints
        // API endpoints with CORS + auth
        server.createContext("/api/books", this::corsThenBooksApi);
        server.createContext("/api/book/create", this::corsThenBookCreate);
        server.createContext("/api/book/delete", this::corsThenBookDeleteApi);  // 🟢-4
        server.createContext("/api/book/info", this::corsThenBookInfo);
        server.createContext("/api/write", this::corsThenWriteApi);
        server.createContext("/api/write/status", this::corsThenWriteStatusApi);  // 🟡-2: job status polling
        server.createContext("/api/audit", this::corsThenAuditApi);
        server.createContext("/api/state", this::corsThenStateApi);
        server.createContext("/api/export", this::corsThenExportApi);
        server.createContext("/api/config", this::corsThenConfigApi);
        server.createContext("/api/progress", this::corsThenProgressApi);

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
        log.info("NovelForge Studio started at http://localhost:{}", server.getAddress().getPort());
        System.out.println("NovelForge Studio: http://localhost:" + server.getAddress().getPort());
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
            result.put("characters", state.characters().getSummary());
            result.put("world", state.world().getSummary());
            result.put("hooks", state.hooks().getSummary());
            sendJson(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + sanitizeForJson(e.getMessage()) + "\"}");
        }
    }

    // --- API: Delete book/project (🟢-4) ---
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
        String apiKey = body.has("apiKey") ? body.get("apiKey").asText() : null;
        String baseUrl = body.has("baseUrl") ? body.get("baseUrl").asText() : "https://api.openai.com/v1";
        String modelId = body.has("model") ? body.get("model").asText() : "gpt-4o";
        String mode = body.has("mode") ? body.get("mode").asText() : "next";

        if (bookPath == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path required and must be within books directory\"}"); return; }
        if (apiKey == null || apiKey.isEmpty()) { sendJson(exchange, 400, "{\"error\":\"apiKey required\"}"); return; }
        String jobId = "job-" + jobIdCounter.incrementAndGet();
        WriteJob job = new WriteJob(jobId);
        writeJobs.put(jobId, job);

        // Submit to background executor
        writeExecutor.submit(() -> {
            job.status = "running";
            job.progress = 10;
            try {
                Book book = BookProject.loadBook(Paths.get(bookPath));
                TruthState state = new TruthState(Paths.get(bookPath));
                PipelineConfig config = loadConfig(Paths.get(bookPath));
                ModelRouter router = new ModelRouter(new ModelRouter.ModelConfig("openai", modelId, baseUrl, apiKey));
                PipelineRunner runner = new PipelineRunner(config, router);

                job.progress = 30;
                PipelineResult result;
                if (mode.equals("draft")) {
                    result = runner.runDraftOnly(book, state);
                } else {
                    result = runner.writeNextChapter(book, state);
                }

                job.progress = 80;
                if (result.success()) {
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
                } else {
                    job.error = result.errorMessage();
                    job.status = "failed";
                }
            } catch (Exception e) {
                job.error = e.getMessage();
                job.status = "failed";
            }
        });

        // Return jobId immediately
        ObjectNode response = mapper.createObjectNode();
        response.put("jobId", jobId);
        response.put("status", "pending");
        sendJson(exchange, 200, mapper.writeValueAsString(response));
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
        String apiKey = body.has("apiKey") ? body.get("apiKey").asText() : null;
        String baseUrl = body.has("baseUrl") ? body.get("baseUrl").asText() : "https://api.openai.com/v1";
        String modelId = body.has("model") ? body.get("model").asText() : "gpt-4o";

        if (bookPath == null || apiKey == null || !isPathWithinBooksRoot(bookPath)) { sendJson(exchange, 400, "{\"error\":\"path and apiKey required; path must be within books directory\"}"); return; }

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
                default -> summary = "角色:\n" + state.characters().getSummary() +
                        "\n世界:\n" + state.world().getSummary() +
                        "\n悬念:\n" + state.hooks().getSummary();
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
            ObjectNode config = mapper.createObjectNode();
            config.put("chapterWordsMin", defaultConfig.getChapterWordsMin());
            config.put("chapterWordsMax", defaultConfig.getChapterWordsMax());
            config.put("auditPassThreshold", defaultConfig.getAuditPassThreshold());
            config.put("maxRevisionPasses", defaultConfig.getMaxRevisionPasses());
            config.put("genreKeys", GenreManager.getInstance().listGenreKeys().toString());
            sendJson(exchange, 200, mapper.writeValueAsString(config));
        } else if (exchange.getRequestMethod().equals("POST")) {
            JsonNode body = readBody(exchange);
            if (body.has("chapterWordsMin")) defaultConfig.setChapterWordsMin(body.get("chapterWordsMin").asInt());
            if (body.has("chapterWordsMax")) defaultConfig.setChapterWordsMax(body.get("chapterWordsMax").asInt());
            if (body.has("auditPassThreshold")) defaultConfig.setAuditPassThreshold(body.get("auditPassThreshold").asDouble());
            if (body.has("maxRevisionPasses")) defaultConfig.setMaxRevisionPasses(body.get("maxRevisionPasses").asInt());
            // Persist config to disk
            saveDefaultConfig();
            sendJson(exchange, 200, "{\"status\":\"updated\"}");
        } else {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
        }
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
            sendJson(exchange, 200, mapper.writeValueAsString(result));
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
    }

    /** Handle CORS preflight OPTIONS requests */
    private void handleCorsPreflight(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1); // 204 No Content for preflight
        exchange.getResponseBody().close();
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

    // --- CORS wrappers: handle OPTIONS preflight, then dispatch to real handler ---
    private void corsThenBooksApi(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleBooksApi(ex);
    }
    private void corsThenBookCreate(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleBookCreate(ex);
    }
    private void corsThenBookDeleteApi(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleBookDeleteApi(ex);
    }
    private void corsThenBookInfo(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleBookInfo(ex);
    }
    private void corsThenWriteApi(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleWriteApi(ex);
    }
    private void corsThenAuditApi(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleAuditApi(ex);
    }
    private void corsThenStateApi(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleStateApi(ex);
    }
    private void corsThenExportApi(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleExportApi(ex);
    }
    private void corsThenConfigApi(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleConfigApi(ex);
    }
    private void corsThenProgressApi(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleProgressApi(ex);
    }
    private void corsThenWriteStatusApi(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { handleCorsPreflight(ex); return; }
        if (!validateAuth(ex)) { sendUnauthorized(ex); return; }
        handleWriteStatusApi(ex);
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