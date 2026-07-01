package com.novelforge.cli.commands;

import com.novelforge.core.project.BookProject;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.state.TruthState;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.pipeline.PipelineRunner;
import com.novelforge.core.llm.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * WriteCommand — write next chapter or draft-only.
 * This is the primary writing command that runs the full agent pipeline.
 */
public class WriteCommand {

    public void execute(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: novelforge write <next|draft> --book <path> [--api-key <key>] [--model <id>]");
            return;
        }

        String bookPath = findOption(args, "--book");
        String apiKey = findOption(args, "--api-key");
        String baseUrl = findOption(args, "--base-url");
        String modelId = findOption(args, "--model");

        if (bookPath == null) {
            System.err.println("Error: --book <path> is required");
            return;
        }

        // API key: from argument, env var, or fail
        if (apiKey == null) {
            apiKey = System.getenv("OPENAI_API_KEY");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("LLM_API_KEY");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: No API key provided. Use --api-key or set OPENAI_API_KEY env var");
            return;
        }

        if (baseUrl == null) {
            baseUrl = System.getenv("LLM_BASE_URL");
            if (baseUrl == null) baseUrl = "https://api.openai.com/v1";
        }

        if (modelId == null) {
            modelId = "gpt-4o";
        }

        Path bookDir = Paths.get(bookPath);

        try {
            // Load book + truth state
            Book book = BookProject.loadBook(bookDir);
            TruthState truthState = new TruthState(bookDir);

            // Load pipeline config override if exists
            PipelineConfig config = loadPipelineConfig(bookDir);

            // Setup LLM router
            ModelRouter router = new ModelRouter(
                    new ModelRouter.ModelConfig("openai", modelId, baseUrl, apiKey));

            PipelineRunner runner = new PipelineRunner(config, router);

            switch (args[0]) {
                case "next" -> {
                    System.out.println("🔥 Writing chapter " + book.nextChapterNumber() + " for '" + book.getTitle() + "'...");
                    System.out.println("   Model: " + modelId + " @ " + baseUrl);

                    PipelineResult result = runner.writeNextChapter(book, truthState);

                    if (result.success()) {
                        // Save chapter to disk
                        Chapter chapter = book.getChapters().get(book.getChapters().size() - 1);
                        BookProject.saveChapter(bookDir, chapter);

                        // Update outline.md
                        if (book.getOutline() != null) {
                            Files.writeString(bookDir.resolve("outline.md"), book.getOutline());
                        }

                        System.out.println("✅ Chapter " + chapter.getNumber() + " written successfully!");
                        System.out.println("   Length: " + chapter.getFinalText().length() + " chars");

                        // Print audit result
                        if (chapter.getAuditResult() != null) {
                            System.out.printf("   Audit score: %.1f/10%n", chapter.getAuditResult().getOverallScore());
                            System.out.println("   Critical issues: " + (chapter.getAuditResult().getCriticalIssues() != null ?
                                    chapter.getAuditResult().getCriticalIssues().size() : 0));
                        }
                    } else {
                        System.err.println("❌ Pipeline failed: " + result.errorMessage());
                    }
                }
                case "draft" -> {
                    System.out.println("📝 Drafting chapter " + book.nextChapterNumber() + " (no quality check)...");

                    PipelineResult result = runner.runDraftOnly(book, truthState);

                    if (result.success()) {
                        System.out.println("✅ Draft completed!");
                        System.out.println("   Length: " + (result.updatedContext().getCurrentChapterDraft() != null ?
                                result.updatedContext().getCurrentChapterDraft().length() : 0) + " chars");
                    } else {
                        System.err.println("❌ Draft failed: " + result.errorMessage());
                    }
                }
                default -> System.err.println("Unknown subcommand: write " + args[0]);
            }
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private PipelineConfig loadPipelineConfig(Path bookDir) {
        Path configFile = bookDir.resolve("config/pipeline.json");
        PipelineConfig config = new PipelineConfig();
        if (Files.exists(configFile)) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(Files.newInputStream(configFile));
                if (root.has("chapterWordsMin")) config.setChapterWordsMin(root.get("chapterWordsMin").asInt());
                if (root.has("chapterWordsMax")) config.setChapterWordsMax(root.get("chapterWordsMax").asInt());
                if (root.has("auditPassThreshold")) config.setAuditPassThreshold(root.get("auditPassThreshold").asDouble());
                if (root.has("maxRevisionPasses")) config.setMaxRevisionPasses(root.get("maxRevisionPasses").asInt());
            } catch (Exception e) {
                System.err.println("Warning: Failed to load pipeline config, using defaults");
            }
        }
        return config;
    }

    private String findOption(String[] args, String key) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return null;
    }
}
