package com.novelforge.core.agent;

import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;

/**
 * Agent interface — each agent in the writing pipeline implements this.
 * Agents are initialized with ModelRouter for LLM routing.
 * The execute() method uses PromptBuilder internally to construct prompts,
 * then calls LLM via ModelRouter.
 */
public interface Agent {

    /** Human-readable agent name (e.g. "Architect", "Writer") */
    String name();

    /** Suggested model ID override (null = use global default).
     *  (fixes #24: when non-null, ModelRouter.getClientForAgent(name()) will use this model
     *   instead of global default. Set via ModelRouter.setAgentModel() or CLI --agent-model flags.) */
    String model();

    /** Suggested temperature for this agent's creative phase */
    double temperature();

    /** Initialize agent with LLM router */
    void init(ModelRouter router);

    /** Execute the agent's task within the pipeline context.
     *  Returns a PipelineResult carrying updated state, generated text, and audit data. */
    PipelineResult execute(PipelineContext context);
}
