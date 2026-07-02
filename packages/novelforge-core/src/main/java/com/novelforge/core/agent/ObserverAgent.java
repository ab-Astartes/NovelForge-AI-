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
 * ObserverAgent — extracts 9 categories of factual changes from chapter text.
 * Low temperature for accuracy. Output feeds Reflector for state updates.
 */
public class ObserverAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ObserverAgent.class);

    private ModelRouter router;
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Override public String name() { return "Observer"; }
    @Override public String model() { return null; }
    @Override public double temperature() { return 0.5; }

    @Override public void init(ModelRouter router) { this.router = router; }

    @Override
    public PipelineResult execute(PipelineContext context) {
        int chapterNum = context.getBook().nextChapterNumber();
        log.info("Observer: extracting facts from chapter {}", chapterNum);

        String chapterDraft = context.getCurrentChapterDraft();

        List<Map<String, String>> messages = promptBuilder.buildObserverPrompt(
                context.getBook(), context.getTruthState(), chapterDraft, context.getConfig());

        LlmClient client = router.getClientForAgent(name());
        String modelId = router.getModelForAgent(name());

        String response = client.chatComplete(messages, modelId, temperature(), 2000);

        // Don't overwrite chapter draft — Observer output is separate metadata
        // Store in observerOutput for Reflector to consume
        context.setObserverOutput(response);
        log.info("Observer: facts extracted ({})", response.length());

        return new PipelineResult(context, response, name());
    }
}
