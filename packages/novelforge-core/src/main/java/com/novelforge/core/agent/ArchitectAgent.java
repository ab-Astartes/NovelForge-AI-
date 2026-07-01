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
 * ArchitectAgent — understands author intent, constructs book outline and chapter plan.
 * First agent in the pipeline. Reads author intent + genre profile,
 * produces structured outline and chapter-level plan.
 */
public class ArchitectAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ArchitectAgent.class);

    private ModelRouter router;
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Override public String name() { return "Architect"; }
    @Override public String model() { return null; } // use global default
    @Override public double temperature() { return 0.5; }

    @Override
    public void init(ModelRouter router) {
        this.router = router;
    }

    @Override
    public PipelineResult execute(PipelineContext context) {
        log.info("Architect: building outline for chapter {}", context.getBook().nextChapterNumber());

        List<Map<String, String>> messages = promptBuilder.buildArchitectPrompt(
                context.getBook(), context.getTruthState(), context.getConfig());

        LlmClient client = router.getClientForAgent(name());
        String modelId = router.getModelForAgent(name());

        String response = client.chatComplete(messages, modelId, temperature(), 4000);

        // Update book outline with architect's output
        context.getBook().setOutline(response);
        log.info("Architect: outline generated ({})", response.length());

        return new PipelineResult(context, response, name());
    }
}
