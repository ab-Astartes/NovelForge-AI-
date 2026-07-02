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
 * WriterAgent — the creative writing engine. High temperature (0.7) for vivid output.
 * Takes Composer's assembled context, generates chapter draft text.
 * This is the core creative step — quality depends heavily on prompt design.
 */
public class WriterAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(WriterAgent.class);

    private ModelRouter router;
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Override public String name() { return "Writer"; }
    @Override public String model() { return null; } // use best creative model
    @Override public double temperature() { return 0.7; }

    @Override public void init(ModelRouter router) { this.router = router; }

    @Override
    public PipelineResult execute(PipelineContext context) {
        int chapterNum = context.getBook().nextChapterNumber();
        log.info("Writer: drafting chapter {}", chapterNum);

        String composedContext = context.getCurrentChapterDraft();

        List<Map<String, String>> messages = promptBuilder.buildWriterPrompt(
                context.getBook(), context.getTruthState(), composedContext, context.getConfig());

        LlmClient client = router.getClientForAgent(name());
        String modelId = router.getModelForAgent(name());

        // Writer needs more tokens — Chinese text: ~1.5 tokens per char, add 50% buffer
        int estimatedMaxTokens = (int) (context.getConfig().getChapterWordsMax() * 1.5 * 1.5);
        int maxTokens = Math.max(4000, estimatedMaxTokens);

        String response = client.chatComplete(messages, modelId, temperature(), maxTokens);

        // Store the actual chapter draft text
        context.setCurrentChapterDraft(response);
        context.setWriterDraft(response);  // preserve original Writer output
        log.info("Writer: chapter {} drafted ({})", chapterNum, response.length());

        return new PipelineResult(context, response, name());
    }
}
