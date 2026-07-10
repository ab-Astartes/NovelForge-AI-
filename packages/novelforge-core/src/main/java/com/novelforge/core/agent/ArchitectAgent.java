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
        try {
            int nextChapter = context.getBook().nextChapterNumber();
            log.info("Architect: planning chapter {} outline", nextChapter);

            // Distinguish first call (full outline generation) vs subsequent calls (incremental update)
            boolean hasExistingOutline = context.getBook().getOutline() != null && !context.getBook().getOutline().isEmpty();

            List<Map<String, String>> messages;
            if (hasExistingOutline) {
                messages = promptBuilder.buildArchitectIncrementalPrompt(
                        context.getBook(), context.getTruthState(), context.getConfig());
            } else {
                messages = promptBuilder.buildArchitectPrompt(
                        context.getBook(), context.getTruthState(), context.getConfig());
            }

            LlmClient client = router.getClientForAgent(name());
            String modelId = router.getModelForAgent(name());

            String response = client.chatComplete(messages, modelId, temperature(), 4000);

            // Update architect output — don't blindly overwrite book outline in incremental mode
            context.setArchitectOutput(response);
            if (!hasExistingOutline) {
                // First generation: set as the book outline
                context.getBook().setOutline(response);
            } else {
                // Incremental update: store in architectOutput only,
                // BookProject.mergeOutline() will handle merging when saving
                log.info("Architect: incremental outline stored in architectOutput, not overwriting book outline");
            }
            log.info("Architect: outline {} for chapter {} ({})",
                    hasExistingOutline ? "updated" : "generated", nextChapter, response.length());

            return new PipelineResult(context, response, name());
        } catch (Exception e) {
            log.error("[{}] execute error: {}", name(), e.getMessage(), e);
            return new PipelineResult(name(), "Agent exception: " + e.getMessage());
        }
    }
}
