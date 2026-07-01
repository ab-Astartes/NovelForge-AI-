package com.novelforge.core.agent;

import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * AgentPipeline — orchestrates the full write cycle:
 * Architect → Planner → Composer → Writer → Observer → Reflector
 * → Normalizer → Auditor → Reviser
 *
 * Each step receives updated PipelineContext from the previous step.
 * Supports partial execution (draft-only, audit-only, etc.)
 *
 * Pipeline design:
 * - Steps 0-3 (Architect→Writer): creative generation
 * - Steps 4-5 (Observer→Reflector): state update (can run concurrently)
 * - Step 6 (Normalizer): length adjustment
 * - Steps 7-8 (Auditor→Reviser): quality check + fix
 */
public class AgentPipeline {

    private static final Logger log = LoggerFactory.getLogger(AgentPipeline.class);

    private final List<Agent> agents;

    public AgentPipeline(ModelRouter router) {
        this.agents = List.of(
            new ArchitectAgent(),
            new PlannerAgent(),
            new ComposerAgent(),
            new WriterAgent(),
            new ObserverAgent(),
            new ReflectorAgent(),
            new NormalizerAgent(),
            new AuditorAgent(),
            new ReviserAgent()
        );
        // Initialize all agents with the same router
        for (Agent agent : agents) {
            agent.init(router);
        }
    }

    /** Run full pipeline from Architect through Reviser */
    public PipelineResult runFull(PipelineContext context) {
        PipelineContext current = context;
        PipelineResult result = null;

        for (Agent agent : agents) {
            log.info("=== Running agent: {} ===", agent.name());
            try {
                result = agent.execute(current);
                current = result.updatedContext();
                log.info("Agent {} completed successfully", agent.name());
            } catch (Exception e) {
                log.error("Agent {} failed: {}", agent.name(), e.getMessage(), e);
                return new PipelineResult(agent.name(), "Agent failed: " + e.getMessage());
            }
        }

        // Final: save truth state
        try {
            current.getTruthState().saveAll();
            log.info("Truth state saved after pipeline completion");
        } catch (Exception e) {
            log.warn("Failed to save truth state", e);
        }

        return result;
    }

    /** Run partial pipeline (e.g. draft only, audit only) */
    public PipelineResult runPartial(PipelineContext context, int fromIndex, int toIndex) {
        PipelineContext current = context;
        PipelineResult result = null;

        for (int i = fromIndex; i <= toIndex && i < agents.size(); i++) {
            Agent agent = agents.get(i);
            log.info("=== Running agent: {} (partial pipeline {}-{}) ===", agent.name(), fromIndex, toIndex);
            try {
                result = agent.execute(current);
                current = result.updatedContext();
            } catch (Exception e) {
                log.error("Agent {} failed", agent.name(), e);
                return new PipelineResult(agent.name(), "Agent failed: " + e.getMessage());
            }
        }

        return result;
    }

    /** Get agent by name */
    public Agent getAgent(String name) {
        return agents.stream()
            .filter(a -> a.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }
}
