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
 * PlannerAgent — creates chapter plan and hook agenda.
 * Takes Architect's outline output, generates mustAdvance/newHook/eligibleResolve
 * and chapter rhythm plan (起承转合).
 */
public class PlannerAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);

    private ModelRouter router;
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Override public String name() { return "Planner"; }
    @Override public String model() { return null; }
    @Override public double temperature() { return 0.4; }

    @Override public void init(ModelRouter router) { this.router = router; }

    @Override
    public PipelineResult execute(PipelineContext context) {
        log.info("Planner: creating hook agenda for chapter {}", context.getBook().nextChapterNumber());

        // Get Architect output
        String architectOutput = context.getArchitectOutput();
        if (architectOutput == null || architectOutput.isEmpty()) {
            architectOutput = context.getBook().getOutline() != null ? context.getBook().getOutline() : "";
        }

        List<Map<String, String>> messages = promptBuilder.buildPlannerPrompt(
                context.getBook(), context.getTruthState(), architectOutput, context.getConfig());

        LlmClient client = router.getClientForAgent(name());
        String modelId = router.getModelForAgent(name());

        String response = client.chatComplete(messages, modelId, temperature(), 3000);

        // Store planner output in dedicated field
        context.setPlannerOutput(response);
        log.info("Planner: hook agenda generated ({})", response.length());

        return new PipelineResult(context, response, name());
    }
}
