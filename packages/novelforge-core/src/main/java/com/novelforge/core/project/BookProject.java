package com.novelforge.core.project;

import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.GenreProfile;
import com.novelforge.core.state.TruthState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
     *
     * @param parentDir  parent directory (e.g. ~/NovelForge/books)
     * @param title      book title
     * @param genre      genre key (xuanhuan, xianxia, urban, etc.)
     * @param author     author name (optional)
     * @return Path to the created book directory
     */
    public static Path create(Path parentDir, String title, String genre, String author) throws IOException {
        // Sanitize title for directory name
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

        // Write default pipeline config
        ObjectNode pipelineJson = mapper.createObjectNode();
        pipelineJson.put("chapterWordsMin", 2000);
        pipelineJson.put("chapterWordsMax", 4000);
        pipelineJson.put("auditPassThreshold", 7.0);
        pipelineJson.put("maxRevisionPasses", 1);
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
        book.setId(root.get("id").asText());
        book.setTitle(root.get("title").asText());
        book.setGenre(root.get("genre").asText());
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

        // Load chapters from directory
        Path chaptersDir = bookDir.resolve("chapters");
        if (Files.exists(chaptersDir)) {
            for (Path p : Files.newDirectoryStream(chaptersDir, "chapter-*.md")) {
                String name = p.getFileName().toString();
                if (name.endsWith(".intent.md")) continue; // skip intent files
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
                }
            }
        }

        return book;
    }

    /**
     * Save a chapter to the book directory.
     */
    public static void saveChapter(Path bookDir, Chapter chapter) throws IOException {
        Path chapterFile = bookDir.resolve("chapters")
                .resolve("chapter-" + String.format("%03d", chapter.getNumber()) + ".md");
        Files.writeString(chapterFile, chapter.getFinalText() != null ?
                chapter.getFinalText() : chapter.getDraftText());
        log.info("Chapter {} saved to {}", chapter.getNumber(), chapterFile);
    }

    /** Sanitize title for directory name */
    private static String sanitize(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|]", "_")
                     .replaceAll("\\s+", "-")
                     .trim();
    }
}
