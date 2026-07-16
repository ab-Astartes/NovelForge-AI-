package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.AuditResult;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.models.RevisionPlan;
import com.novelforge.core.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * ReviserAgent — fixes chapter text based on audit findings.
 * Mode: polish / spot-fix / rewrite / anti-detect.
 * Selects mode based on audit score and critical issue count.
 */
public class ReviserAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ReviserAgent.class);

    private ModelRouter router;
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Override public String name() { return "Reviser"; }
    @Override public String model() { return null; }
    @Override public double temperature() { return 0.4; }

    @Override public void init(ModelRouter router) { this.router = router; }

    @Override
    public PipelineResult execute(PipelineContext context) {
        try {
            log.info("Reviser: reviewing chapter {}", context.getBook().nextChapterNumber());

            String chapterDraft = context.getCurrentChapterDraft();
            AuditResult auditResult = context.getAuditResult();

            // Skip revision if audit passed with high score
            if (auditResult != null && auditResult.isPass() && auditResult.getOverallScore() >= context.getConfig().getAuditPassThreshold()) {
                log.info("Reviser: audit passed (score {}), no revision needed", String.format("%.1f", auditResult.getOverallScore()));
                return new PipelineResult(context, chapterDraft, name());
            }

            // Need revision
            if (auditResult == null) {
                log.warn("Reviser: no audit result, skipping revision");
                return new PipelineResult(context, chapterDraft, name());
            }

            // Select revision mode based on audit score and critical issues
            RevisionPlan.Mode mode = selectRevisionMode(auditResult);
            log.info("Reviser: selected mode {} (score {}, {} critical issues",
                    mode, String.format("%.1f", auditResult.getOverallScore()),
                    auditResult.getCriticalIssues() != null ? auditResult.getCriticalIssues().size() : 0);

            List<Map<String, String>> messages = promptBuilder.buildReviserPrompt(
                    context.getBook(), context.getTruthState(), chapterDraft, auditResult, context.getConfig(), mode);

            LlmClient client = router.getClientForAgent(name());
            String modelId = router.getModelForAgent(name());

            String response = client.chatComplete(messages, modelId, temperature(), PromptBuilder.MAX_PROMPT_LENGTH);

            // Replace draft with revised version
            context.setCurrentChapterDraft(response);
            log.info("Reviser: chapter revised (mode {}, audit score {} → revised)", mode, String.format("%.1f", auditResult.getOverallScore()));

            return new PipelineResult(context, response, name());
        } catch (Exception e) {
            log.error("[{}] execute error: {}", name(), e.getMessage(), e);
            // If we have a draft, apply light revision as best-effort fallback
            String draft = context.getCurrentChapterDraft();
            if (draft != null && !draft.trim().isEmpty()) {
                String lightRevision = lightRevise(draft);
                context.setCurrentChapterDraft(lightRevision);
                return PipelineResult.recovery(context, lightRevision, name(),
                        "Reviser exception, light whitespace normalization applied as fallback: " + e.getMessage());
            }
            // No draft available — hard failure
            return new PipelineResult(name(), "Reviser exception: " + e.getMessage());
        }
    }

    /** Lightweight fallback revision — normalize whitespace and remove excessive blank lines. */
    private String lightRevise(String text) {
        if (text == null) return "";
        return text.replaceAll("\n{3,}", "\n\n").replaceAll("  +", " ");
    }

    /**
     * Select revision mode based on audit results.
     * - score >= 7.5, only warnings → polish (light touch)
     * - score 6.0-7.5 → spot-fix (targeted repairs)
     * - score < 6.0 → rewrite (major overhaul)
     * - low anti-AI scores → add anti-detect overlay
     */
    private RevisionPlan.Mode selectRevisionMode(AuditResult audit) {
        double score = audit.getOverallScore();
        int criticalCount = audit.getCriticalIssues() != null ? audit.getCriticalIssues().size() : 0;

        RevisionPlan.Mode baseMode;
        if (score >= 7.5 && criticalCount == 0) {
            baseMode = RevisionPlan.Mode.POLISH;
        } else if (score >= 6.0) {
            baseMode = RevisionPlan.Mode.SPOT_FIX;
        } else {
            baseMode = RevisionPlan.Mode.REWRITE;
        }

        // Check anti-AI dimensions for overlay
        Map<String, Double> scores = audit.getDimensionScores();
        if (scores != null) {
            double antiAI = 0;
            int antiAICount = 0;
            for (String dim : new String[]{"antiAI.repetitivePatterns", "antiAI.genericExpressions", "antiAI.overlyBalancedStructure"}) {
                Double d = scores.get(dim);
                if (d != null) { antiAI += d; antiAICount++; }
            }
            if (antiAICount > 0 && antiAI / antiAICount < 5.0) {
                // Low anti-AI scores — use anti-detect mode regardless of base
                return RevisionPlan.Mode.ANTI_DETECT;
            }
        }

        return baseMode;
    }
}
