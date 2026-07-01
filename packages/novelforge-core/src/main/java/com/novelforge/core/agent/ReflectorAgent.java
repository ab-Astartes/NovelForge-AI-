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
 * ReflectorAgent — converts Observer's extraction into state patch operations.
 * Outputs hookOps (UPSERT/MENTION/RESOLVE/DEFER) and statePatches
 * (characterDelta, worldDelta, timelineDelta).
 */
public class ReflectorAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ReflectorAgent.class);

    private ModelRouter router;
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Override public String name() { return "Reflector"; }
    @Override public String model() { return null; }
    @Override public double temperature() { return 0.3; }

    @Override public void init(ModelRouter router) { this.router = router; }

    @Override
    public PipelineResult execute(PipelineContext context) {
        log.info("Reflector: generating state patches for chapter {}", context.getBook().nextChapterNumber());

        // Observer's output is carried in the previous PipelineResult
        // We need to pass it through context — for now, use last agent output
        String observerOutput = context.getCurrentChapterDraft();

        List<Map<String, String>> messages = promptBuilder.buildReflectorPrompt(
                context.getBook(), context.getTruthState(), observerOutput, context.getConfig());

        LlmClient client = router.getClientForAgent(name());
        String modelId = router.getModelForAgent(name());

        String response = client.chatComplete(messages, modelId, temperature(), 1500);

        // TODO: Parse response JSON into HookOp list + state patches
        //       Apply to TruthState
        log.info("Reflector: patches generated ({})", response.length());

        return new PipelineResult(context, response, name());
    }
}
