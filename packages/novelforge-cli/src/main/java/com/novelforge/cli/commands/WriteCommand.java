package com.novelforge.cli.commands;

import com.novelforge.core.project.BookProject;
import com.novelforge.core.models.AuditResult;
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

                    if (book.getChapters() == null || book.getChapters().isEmpty()) {
                        System.out.println("   Note: No existing chapters, starting from scratch");
                    }

                    PipelineResult result = runner.writeNextChapter(book, truthState);

                    if (result.success()) {
                        if (book.getChapters() == null || book.getChapters().isEmpty()) {
                            System.err.println("❌ Pipeline reported success but no chapter was added");
                            return;
                        }
                        // Save chapter to disk
                        Chapter chapter = book.getChapters().get(book.getChapters().size() - 1);
                        BookProject.saveChapter(bookDir, chapter);

                        // Update book.json metadata
                        BookProject.saveBookMetadata(bookDir, book);

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

                    if (book.getChapters() == null || book.getChapters().isEmpty()) {
                        System.out.println("   Note: No existing chapters, starting from scratch");
                    }

                    PipelineResult result = runner.runDraftOnly(book, truthState);

                    if (result.success()) {
                        if (book.getChapters() == null || book.getChapters().isEmpty()) {
                            System.err.println("❌ Draft reported success but no chapter was added");
                            return;
                        }
                        // Save draft chapter to disk
                        Chapter chapter = book.getChapters().get(book.getChapters().size() - 1);
                        BookProject.saveChapter(bookDir, chapter);
                        BookProject.saveBookMetadata(bookDir, book);

                        // Save outline if Architect updated it
                        if (book.getOutline() != null) {
                            Files.writeString(bookDir.resolve("outline.md"), book.getOutline());
                        }

                        System.out.println("✅ Draft completed and saved!");
                        System.out.println("   Length: " + (chapter.getDraftText() != null ?
                                chapter.getDraftText().length() : 0) + " chars");
                    } else {
                        System.err.println("❌ Draft failed: " + result.errorMessage());
                    }
                }
                case "audit" -> {
                    // Audit-only: run Auditor + Reviser on existing chapter
                    if (book.getChapters() == null || book.getChapters().isEmpty()) {
                        System.err.println("❌ No chapters to audit. Write a chapter first.");
                        return;
                    }
                    String chapterArg = findOption(args, "--chapter");
                    int chapterNum = chapterArg != null ? Integer.parseInt(chapterArg) : book.getChapters().size();

                    if (chapterNum <= 0 || chapterNum > book.getChapters().size()) {
                        System.err.println("❌ Invalid chapter number. Book has " + book.getChapters().size() + " chapters.");
                        return;
                    }

                    Chapter ch = book.getChapters().get(chapterNum - 1);
                    String text = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();

                    System.out.println("🔍 Auditing chapter " + chapterNum + " (" + text.length() + " chars)...");

                    PipelineResult result = runner.runAuditOnly(book, truthState, text);

                    if (result.success()) {
                        AuditResult audit = result.updatedContext().getAuditResult();
                        if (audit != null) {
                            System.out.printf("📊 Overall score: %.1f/10%n", audit.getOverallScore());
                            System.out.println("   Pass: " + (audit.isPass() ? "✅ YES" : "❌ NO"));
                            if (audit.getDimensionScores() != null) {
                                audit.getDimensionScores().forEach((dim, score) ->
                                        System.out.printf("     %-30s %.1f%n", dim, score));
                            }
                            if (audit.getCriticalIssues() != null && !audit.getCriticalIssues().isEmpty()) {
                                System.out.println("   ⚠️ Critical issues: " + audit.getCriticalIssues());
                            }
                        }

                        // Save updated chapter text if Reviser modified it
                        String revisedText = result.updatedContext().getCurrentChapterDraft();
                        if (revisedText != null && !revisedText.equals(text)) {
                            if (ch.getFinalText() != null) {
                                ch.setFinalText(revisedText);
                            } else {
                                ch.setDraftText(revisedText);
                            }
                            BookProject.saveChapter(bookDir, ch);
                            BookProject.saveBookMetadata(bookDir, book);
                            System.out.println("   ✅ Chapter text updated and saved after revision");
                        }
                    } else {
                        System.err.println("❌ Audit failed: " + result.errorMessage());
                    }
                }
                case "continue" -> {
                    // Continue writing: reuses last chapter's context, skips Architect
                    if (book.getChapters().isEmpty()) {
                        System.err.println("❌ No chapters yet. Use 'write next' to start.");
                        return;
                    }

                    Chapter lastChapter = book.getChapters().get(book.getChapters().size() - 1);
                    System.out.println("✏️ Continuing from chapter " + lastChapter.getNumber() + " → writing chapter " + book.nextChapterNumber() + "...");

                    // Disable Architect for continuation (outline already exists)
                    PipelineConfig contConfig = new PipelineConfig();
                    contConfig.setChapterWordsMin(config.getChapterWordsMin());
                    contConfig.setChapterWordsMax(config.getChapterWordsMax());
                    contConfig.setAuditPassThreshold(config.getAuditPassThreshold());
                    contConfig.setMaxRevisionPasses(config.getMaxRevisionPasses());
                    contConfig.setRunArchitect(false);  // skip outline rebuild
                    contConfig.setRunPlanner(true);
                    contConfig.setRunComposer(true);
                    contConfig.setRunWriter(true);
                    contConfig.setRunObserver(true);
                    contConfig.setRunReflector(true);
                    contConfig.setRunNormalizer(true);
                    contConfig.setRunAuditor(true);
                    contConfig.setRunReviser(true);

                    PipelineRunner contRunner = new PipelineRunner(contConfig, router);
                    PipelineResult result = contRunner.writeNextChapter(book, truthState);

                    if (result.success()) {
                        Chapter chapter = book.getChapters().get(book.getChapters().size() - 1);
                        BookProject.saveChapter(bookDir, chapter);
                        BookProject.saveBookMetadata(bookDir, book);
                        truthState.saveAll();

                        System.out.println("✅ Chapter " + chapter.getNumber() + " continued successfully!");
                        System.out.println("   Length: " + (chapter.getFinalText() != null ? chapter.getFinalText().length() : 0) + " chars");
                        if (chapter.getAuditResult() != null) {
                            System.out.printf("   Audit score: %.1f/10%n", chapter.getAuditResult().getOverallScore());
                        }
                    } else {
                        System.err.println("❌ Continue failed: " + result.errorMessage());
                    }
                }
                case "progress" -> {
                    com.novelforge.core.models.WritingProgress progress = book.getProgress();
                    System.out.println("📊 Writing Progress for '" + book.getTitle() + "':");
                    System.out.println("   Chapters: " + progress.getTotalChapters());
                    System.out.println("   Total words: " + progress.getTotalWords());
                    System.out.println("   Average words/chapter: " + progress.getAverageWordsPerChapter());
                    System.out.println("   Audited chapters: " + progress.getAuditedChapters() + "/" + progress.getTotalChapters());
                    System.out.println("   Passed chapters: " + progress.getPassedChapters() + "/" + progress.getTotalChapters());
                }
                default -> System.err.println("Unknown subcommand: write " + args[0] +
                    "\nValid: next, draft, audit, continue, progress");
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
            } catch (Exception e) {
                System.err.println("Warning: Failed to load pipeline config, using defaults");
            }
        }
        return config;
    }

    private String findOption(String[] args, String key) {
        // Support --key=value format
        for (String arg : args) {
            if (arg.startsWith(key + "=")) {
                return arg.substring(key.length() + 1);
            }
        }
        // Support --key value format
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return null;
    }
}
