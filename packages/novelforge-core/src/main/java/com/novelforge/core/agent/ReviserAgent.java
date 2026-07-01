package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.AuditResult;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
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

        List<Map<String, String>> messages = promptBuilder.buildReviserPrompt(
                context.getBook(), context.getTruthState(), chapterDraft, auditResult, context.getConfig());

        LlmClient client = router.getClientForAgent(name());
        String modelId = router.getModelForAgent(name());

        String response = client.chatComplete(messages, modelId, temperature(), 8000);

        // Replace draft with revised version
        context.setCurrentChapterDraft(response);
        log.info("Reviser: chapter revised (audit score {} → revised)", String.format("%.1f", auditResult.getOverallScore()));

        return new PipelineResult(context, response, name());
    }
}
