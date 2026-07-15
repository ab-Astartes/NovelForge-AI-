package com.novelforge.core.agent;

import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

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
            // fixes #24: If agent declares a model() override, register it with the router
            // so that getClientForAgent/getModelForAgent will use the per-agent model.
            // This only registers if no explicit override was already set via CLI or API.
            if (agent.model() != null) {
                router.registerAgentModelIfAbsent(agent.name(), agent.model());
            }
        }
    }

    /** Run full pipeline from Architect through Reviser,
     *  respecting PipelineConfig agent toggle flags. */
    public PipelineResult runFull(PipelineContext context) {
        PipelineContext current = context;
        PipelineResult result = null;
        PipelineConfig config = context.getConfig();

        // Toggle map: agent name → config boolean field
        Map<String, Boolean> toggles = Map.of(
            "Architect",   config.isRunArchitect(),
            "Planner",     config.isRunPlanner(),
            "Composer",    config.isRunComposer(),
            "Writer",      config.isRunWriter(),
            "Observer",    config.isRunObserver(),
            "Reflector",   config.isRunReflector(),
            "Normalizer",  config.isRunNormalizer(),
            "Auditor",     config.isRunAuditor(),
            "Reviser",     config.isRunReviser()
        );

        for (Agent agent : agents) {
            boolean enabled = toggles.getOrDefault(agent.name(), true);
            if (!enabled) {
                log.info("=== Skipping disabled agent: {} ===", agent.name());
                continue;
            }
            log.info("=== Running agent: {} ===", agent.name());
            try {
                result = agent.execute(current);
                // 🟡-5 fix: Check for hard failure — updatedContext() returns null on error,
                // which would cause NPE on next agent's execute()
                if (result.isHardFailure()) {
                    log.error("Agent {} hard failure: {}", agent.name(), result.errorMessage());
                    return result; // Stop pipeline immediately
                }
                current = result.updatedContext();
                log.info("Agent {} completed successfully", agent.name());
            } catch (Exception e) {
                log.error("Agent {} failed: {}", agent.name(), e.getMessage(), e);
                return new PipelineResult(agent.name(), "Agent failed: " + e.getMessage());
            }
        }

        // All agents skipped → return error result instead of null
        if (result == null) {
            log.warn("All agents were disabled — pipeline produced no output");
            return new PipelineResult("Pipeline", "All agents disabled — no work was done");
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

    /** Run partial pipeline by agent name range (e.g. "Architect" to "Writer", "Auditor" to "Reviser")
     *  Resolves names to indexes — safer than raw index hardcoding. */
    public PipelineResult runPartialByName(PipelineContext context, String fromAgent, String toAgent) {
        int fromIndex = findAgentIndex(fromAgent);
        int toIndex = findAgentIndex(toAgent);
        if (fromIndex < 0 || toIndex < 0) {
            log.error("Unknown agent names: from={} to={}", fromAgent, toAgent);
            return new PipelineResult("Pipeline", "Unknown agent: " + fromAgent + " or " + toAgent);
        }
        return runPartial(context, fromIndex, toIndex);
    }

    /** Run partial pipeline by index range */
    public PipelineResult runPartial(PipelineContext context, int fromIndex, int toIndex) {
        PipelineContext current = context;
        PipelineResult result = null;

        for (int i = fromIndex; i <= toIndex && i < agents.size(); i++) {
            Agent agent = agents.get(i);
            log.info("=== Running agent: {} (partial pipeline {}-{}) ===", agent.name(), fromIndex, toIndex);
            try {
                result = agent.execute(current);
                if (result.isHardFailure()) {
                    log.error("Agent {} hard failure in partial pipeline", agent.name());
                    return result;
                }
                current = result.updatedContext();
            } catch (Exception e) {
                log.error("Agent {} failed", agent.name(), e);
                return new PipelineResult(agent.name(), "Agent failed: " + e.getMessage());
            }
        }

        return result;
    }

    /** Find agent index by name */
    private int findAgentIndex(String name) {
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /** Get agent by name */
    public Agent getAgent(String name) {
        return agents.stream()
            .filter(a -> a.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }
}
