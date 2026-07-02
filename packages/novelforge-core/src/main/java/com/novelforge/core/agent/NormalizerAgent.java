package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
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
        log.info("Normalizer: adjusting length for chapter {}", context.getBook().nextChapterNumber());

        String chapterDraft = context.getCurrentChapterDraft();

        // Skip if draft is already within range (±20% tolerance)
        int estimatedWords = estimateChineseWords(chapterDraft);
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
        log.info("Normalizer: text adjusted ({}/{})", estimateChineseWords(response), estimatedWords);

        return new PipelineResult(context, response, name());
    }

    private static int estimateChineseWords(String text) {
        if (text == null) return 0;
        // CJK Unified Ideographs + Extensions A/B
        int chineseChars = (int) text.chars().filter(c ->
                (c >= 0x4E00 && c <= 0x9FFF) ||    // CJK Unified
                (c >= 0x3400 && c <= 0x4DBF) ||    // Extension A
                (c >= 0x20000 && c <= 0x2A6DF)     // Extension B
        ).count();
        int otherChars = text.length() - chineseChars;
        return chineseChars + otherChars / 5;
    }
}
