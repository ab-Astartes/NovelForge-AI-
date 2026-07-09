package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.models.TextUtils;
import com.novelforge.core.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * NormalizerAgent — adjusts chapter text length to fit word count target range.
 * Low temperature (0.3) to preserve content while trimming/expanding.
 */
public class NormalizerAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(NormalizerAgent.class);

    private ModelRouter router;
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Override public String name() { return "Normalizer"; }
    @Override public String model() { return null; }
    @Override public double temperature() { return 0.3; }

    @Override public void init(ModelRouter router) { this.router = router; }

    @Override
    public PipelineResult execute(PipelineContext context) {
        try {
            log.info("Normalizer: adjusting length for chapter {}", context.getBook().nextChapterNumber());

            String chapterDraft = context.getCurrentChapterDraft();

            // Input validation — abort if critical input is null/empty
            if (chapterDraft == null || chapterDraft.isEmpty()) {
                log.warn("Normalizer: chapter draft is null/empty, skipping normalization");
                return new PipelineResult(context, "（空章节）", name());
            }

            // Skip if draft is already within range (±20% tolerance)
            int estimatedWords = TextUtils.estimateChineseWordCount(chapterDraft);
            int minTarget = context.getConfig().getChapterWordsMin();
            int maxTarget = context.getConfig().getChapterWordsMax();
            double tolerance = 0.20;

            if (estimatedWords >= minTarget * (1 - tolerance) && estimatedWords <= maxTarget * (1 + tolerance)) {
                log.info("Normalizer: draft already within range ({}/{}-{}), skipping", estimatedWords, minTarget, maxTarget);
                return new PipelineResult(context, chapterDraft, name());
            }

            List<Map<String, String>> messages = promptBuilder.buildNormalizerPrompt(
                    context.getBook(), context.getTruthState(), chapterDraft, context.getConfig());

            LlmClient client = router.getClientForAgent(name());
            String modelId = router.getModelForAgent(name());

            String response = client.chatComplete(messages, modelId, temperature(), 6000);

            // Replace draft with normalized version
            context.setCurrentChapterDraft(response);
            context.setNormalizerOutput(response);
            log.info("Normalizer: text adjusted ({}/{})", TextUtils.estimateChineseWordCount(response), estimatedWords);

            return new PipelineResult(context, response, name());
        } catch (Exception e) {
            System.err.println("[Normalizer] execute error: " + e.getMessage());
            e.printStackTrace();
            return PipelineResult.recovery(context, "[Error] " + e.getMessage(), name(), "Agent exception: " + e.getMessage());
        }
    }


}
