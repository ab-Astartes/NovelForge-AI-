package com.novelforge.core.project;

import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.TextUtils;
import com.novelforge.core.state.TruthState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * BookProject — creates and manages a novel project directory.
 * Directory structure:
 *   bookDir/
 *     book.json           — book metadata
 *     outline.md          — book outline
 *     author_intent.md    — free-form author intent
 *     truth/
 *       characters.json   — character state
 *       world.json        — world state
 *       timeline.json     — timeline events
 *       hooks.json        — hook management
 *     chapters/
 *       chapter-001.md    — chapter text files
 *       chapter-001.intent.md — chapter intent
 *     config/
 *       pipeline.json     — pipeline configuration override
 */
public class BookProject {

    private static final Logger log = LoggerFactory.getLogger(BookProject.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Create a new book project directory with all required files.
     */
    public static Path create(Path parentDir, String title, String genre, String author) throws IOException {
        String dirName = sanitize(title);
        Path bookDir = parentDir.resolve(dirName);

        if (Files.exists(bookDir)) {
            throw new IOException("Book directory already exists: " + bookDir);
        }

        // Create directory structure
        Files.createDirectories(bookDir);
        Files.createDirectories(bookDir.resolve("truth"));
        Files.createDirectories(bookDir.resolve("chapters"));
        Files.createDirectories(bookDir.resolve("config"));

        // Write book.json
        ObjectNode bookJson = mapper.createObjectNode();
        bookJson.put("id", UUID.randomUUID().toString());
        bookJson.put("title", title);
        bookJson.put("genre", genre);
        bookJson.put("author", author != null ? author : "");
        bookJson.put("createdAt", java.time.Instant.now().toString());
        bookJson.putArray("chapters");
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                Files.newOutputStream(bookDir.resolve("book.json")), bookJson);

        // Write author_intent.md
        String intentTemplate = """
            # 作者意图
            
            在这里描述你想要写的小说的核心概念、故事方向、主角设定等。
            
            ## 核心概念
            
            ## 主角设定
            
            ## 故事方向
            
            ## 想避免的套路
            
            """;
        Files.writeString(bookDir.resolve("author_intent.md"), intentTemplate);

        // Write outline.md (empty template)
        String outlineTemplate = """
            # 小说大纲
            
            （大纲将由 Architect Agent 自动生成和更新）
            
            """;
        Files.writeString(bookDir.resolve("outline.md"), outlineTemplate);

        // Truth state files will be created by TruthState.loadAll()
        TruthState state = new TruthState(bookDir);
        state.saveAll();

        // Write default pipeline config (including agent toggles)
        ObjectNode pipelineJson = mapper.createObjectNode();
        pipelineJson.put("chapterWordsMin", 2000);
        pipelineJson.put("chapterWordsMax", 4000);
        pipelineJson.put("auditPassThreshold", 7.0);
        pipelineJson.put("maxRevisionPasses", 1);
        // Agent toggles — all enabled by default
        pipelineJson.put("runArchitect", true);
        pipelineJson.put("runPlanner", true);
        pipelineJson.put("runComposer", true);
        pipelineJson.put("runWriter", true);
        pipelineJson.put("runObserver", true);
        pipelineJson.put("runReflector", true);
        pipelineJson.put("runNormalizer", true);
        pipelineJson.put("runAuditor", true);
        pipelineJson.put("runReviser", true);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                Files.newOutputStream(bookDir.resolve("config/pipeline.json")), pipelineJson);

        log.info("Book project created: {} at {}", title, bookDir);
        return bookDir;
    }

    /**
     * Load an existing book project.
     */
    public static Book loadBook(Path bookDir) throws IOException {
        Path bookJsonPath = bookDir.resolve("book.json");
        if (!Files.exists(bookJsonPath)) {
            throw new IOException("No book.json found in " + bookDir);
        }

        Book book = new Book();
        JsonNode root = mapper.readTree(Files.newInputStream(bookJsonPath));
        book.setId(root.has("id") ? root.get("id").asText() : UUID.randomUUID().toString());
        book.setTitle(root.has("title") ? root.get("title").asText() : "Unknown Title");
        book.setGenre(root.has("genre") ? root.get("genre").asText() : "general");
        book.setAuthor(root.has("author") ? root.get("author").asText() : "");

        // Load outline
        Path outlinePath = bookDir.resolve("outline.md");
        if (Files.exists(outlinePath)) {
            book.setOutline(Files.readString(outlinePath));
        }

        // Load author intent
        Path intentPath = bookDir.resolve("author_intent.md");
        if (Files.exists(intentPath)) {
            book.setAuthorIntent(Files.readString(intentPath));
        }

        // Load chapters from directory (efficient sorted stream, fixes #10)
        Path chaptersDir = bookDir.resolve("chapters");
        if (Files.exists(chaptersDir)) {
            Files.list(chaptersDir)
                .filter(p -> p.getFileName().toString().startsWith("chapter-") && p.getFileName().toString().endsWith(".md") && !p.getFileName().toString().endsWith(".intent.md"))
                .sorted()
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    String numStr = name.replace("chapter-", "").replace(".md", "");
                    try {
                        int num = Integer.parseInt(numStr);
                        Chapter ch = new Chapter();
                        ch.setNumber(num);
                        ch.setFinalText(Files.readString(p));

                        // Load intent if exists
                        Path intentFile = chaptersDir.resolve("chapter-" + numStr + ".intent.md");
                        if (Files.exists(intentFile)) {
                            ch.setIntent(Files.readString(intentFile));
                        }

                        book.getChapters().add(ch);
                    } catch (NumberFormatException e) {
                        log.warn("Skipping non-standard chapter file: {}", name);
                    } catch (java.io.IOException e) {
                        log.warn("Failed to read chapter file: {}", name, e);
                    }
                });
        }

        return book;
    }

    /**
     * Save a chapter to the book directory and update book.json metadata.
     * Saves both finalText and draftText separately.
     */
    public static void saveChapter(Path bookDir, Chapter chapter) throws IOException {
        Path chaptersDir = bookDir.resolve("chapters");
        Files.createDirectories(chaptersDir);

        // Save final text (or draft if no final text exists)
        String finalText = chapter.getFinalText() != null ? chapter.getFinalText() : chapter.getDraftText();
        Path chapterFile = chaptersDir.resolve("chapter-" + String.format("%03d", chapter.getNumber()) + ".md");
        if (finalText != null) {
            Files.writeString(chapterFile, finalText);
        }

        // Save draft text separately (preserves original Writer output for traceability)
        if (chapter.getDraftText() != null && chapter.getFinalText() != null) {
            Path draftFile = chaptersDir.resolve("chapter-" + String.format("%03d", chapter.getNumber()) + ".draft.md");
            Files.writeString(draftFile, chapter.getDraftText());
        }

        log.info("Chapter {} saved to {}", chapter.getNumber(), chapterFile);
    }

    /**
     * Save book metadata back to book.json (e.g. after adding a chapter).
     */
    public static void saveBookMetadata(Path bookDir, Book book) throws IOException {
        Path bookJsonPath = bookDir.resolve("book.json");
        ObjectNode bookJson = mapper.createObjectNode();
        bookJson.put("id", book.getId());
        bookJson.put("title", book.getTitle());
        bookJson.put("genre", book.getGenre());
        bookJson.put("author", book.getAuthor() != null ? book.getAuthor() : "");
        bookJson.put("createdAt", java.time.Instant.now().toString());

        // Store chapter metadata (number + title only, not full text)
        ArrayNode chaptersArr = bookJson.putArray("chapters");
        for (Chapter ch : book.getChapters()) {
            ObjectNode chNode = mapper.createObjectNode();
            chNode.put("number", ch.getNumber());
            chNode.put("title", ch.getTitle() != null ? ch.getTitle() : "第" + ch.getNumber() + "章");
            chNode.put("wordCount", TextUtils.estimateChineseWordCount(ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText()));
            chaptersArr.add(chNode);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(Files.newOutputStream(bookJsonPath), bookJson);
        log.info("book.json metadata saved ({} chapters)", book.getChapters().size());
    }

    // estimateWordCount moved to TextUtils.estimateChineseWordCount

    /** Sanitize title for directory name */
    private static String sanitize(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|]", "_")
                     .replaceAll("\\s+", "-")
                     .trim();
    }
}
