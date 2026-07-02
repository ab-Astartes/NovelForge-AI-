package com.novelforge.cli.commands;

import com.novelforge.core.agent.AuditorAgent;
import com.novelforge.core.agent.AgentPipeline;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.AuditResult;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.pipeline.PipelineRunner;
import com.novelforge.core.project.BookProject;
import com.novelforge.core.state.TruthState;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AuditCommand — standalone 33-dimension quality audit on a chapter.
 * Usage: novelforge audit --book <path> --chapter <num> [--api-key <key>]
 */
public class AuditCommand {

    public void execute(String[] args) {
        String bookPath = findOption(args, "--book");
        String chapterNum = findOption(args, "--chapter");
        String apiKey = findOption(args, "--api-key");
        String baseUrl = findOption(args, "--base-url");
        String modelId = findOption(args, "--model");

        if (bookPath == null) {
            System.err.println("Error: --book <path> is required");
            System.err.println("Usage: novelforge audit --book <path> --chapter <num> [--api-key <key>] [--model <id>]");
            return;
        }

        if (apiKey == null) apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null) apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: No API key. Use --api-key or set OPENAI_API_KEY");
            return;
        }
        if (baseUrl == null) baseUrl = System.getenv().getOrDefault("LLM_BASE_URL", "https://api.openai.com/v1");
        if (modelId == null) modelId = "gpt-4o";

        Path bookDir = Paths.get(bookPath);
        int chapter = chapterNum != null ? Integer.parseInt(chapterNum) : -1;

        try {
            Book book = BookProject.loadBook(bookDir);
            TruthState state = new TruthState(bookDir);
            PipelineConfig config = new PipelineConfig();
            ModelRouter router = new ModelRouter(new ModelRouter.ModelConfig("openai", modelId, baseUrl, apiKey));
            PipelineRunner runner = new PipelineRunner(config, router);

            // Find chapter text
            String chapterText;
            if (chapter > 0 && chapter <= book.getChapters().size()) {
                Chapter ch = book.getChapters().get(chapter - 1);
                chapterText = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
            } else {
                // Audit last chapter
                Chapter ch = book.getChapters().get(book.getChapters().size() - 1);
                chapterText = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
                chapter = ch.getNumber();
            }

            if (chapterText == null) {
                System.err.println("❌ Chapter " + chapter + " has no text to audit");
                return;
            }

            System.out.println("🔍 Auditing chapter " + chapter + " (" + chapterText.length() + " chars)...");

            PipelineResult result = runner.runAuditOnly(book, state, chapterText);

            if (result.success()) {
                AuditResult audit = result.updatedContext().getAuditResult();
                if (audit != null) {
                    System.out.printf("📊 Overall score: %.1f/10%n", audit.getOverallScore());
                    System.out.println("   Pass: " + (audit.isPass() ? "✅ YES" : "❌ NO"));

                    if (audit.getDimensionScores() != null && !audit.getDimensionScores().isEmpty()) {
                        System.out.println("\n   Dimension scores:");
                        audit.getDimensionScores().forEach((dim, score) ->
                                System.out.printf("     %-30s %.1f%n", dim, score));
                    }

                    if (audit.getCriticalIssues() != null && !audit.getCriticalIssues().isEmpty()) {
                        System.out.println("\n   ⚠️ Critical issues:");
                        audit.getCriticalIssues().forEach(i -> System.out.println("     - " + i));
                    }

                    if (audit.getWarnings() != null && !audit.getWarnings().isEmpty()) {
                        System.out.println("\n   💡 Warnings:");
                        audit.getWarnings().forEach(w -> System.out.println("     - " + w));
                    }
                }
            } else {
                System.err.println("❌ Audit failed: " + result.errorMessage());
            }
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    private String findOption(String[] args, String key) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return null;
    }
}
