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
 * ComposerAgent — assembles context package for Writer.
 * Takes Planner's hook agenda + truth state + previous chapter,
 * compiles into a structured context package that Writer can directly use.
 */
public class ComposerAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ComposerAgent.class);

    private ModelRouter router;
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Override public String name() { return "Composer"; }
    @Override public String model() { return null; }
    @Override public double temperature() { return 0.3; }

    @Override public void init(ModelRouter router) { this.router = router; }

    @Override
    public PipelineResult execute(PipelineContext context) {
        log.info("Composer: assembling context for chapter {}", context.getBook().nextChapterNumber());

        String plannerOutput = context.getCurrentChapterDraft();

        List<Map<String, String>> messages = promptBuilder.buildComposerPrompt(
                context.getBook(), context.getTruthState(), plannerOutput, context.getConfig());

        LlmClient client = router.getClientForAgent(name());
        String modelId = router.getModelForAgent(name());

        String response = client.chatComplete(messages, modelId, temperature(), 2000);

        // Replace draft with composed context (this will be Writer's input)
        context.setCurrentChapterDraft(response);
        log.info("Composer: context assembled ({})", response.length());

        return new PipelineResult(context, response, name());
    }
}
