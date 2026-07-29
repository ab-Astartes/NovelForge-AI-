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
    private com.novelforge.core.pipeline.ProgressListener progressListener;

    /** Set a progress listener to receive real-time pipeline events */
    public void setProgressListener(com.novelforge.core.pipeline.ProgressListener listener) {
        this.progressListener = listener;
    }

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
        int totalSteps = agents.size();
        int enabledCount = 0;

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

        for (int i = 0; i < agents.size(); i++) {
            Agent agent = agents.get(i);
            boolean enabled = toggles.getOrDefault(agent.name(), true);
            if (!enabled) {
                log.info("=== Skipping disabled agent: {} ===", agent.name());
                if (progressListener != null) progressListener.onAgentSkip(agent.name(), i, totalSteps);
                continue;
            }
            log.info("=== Running agent: {} ===", agent.name());
            if (progressListener != null) progressListener.onAgentStart(agent.name(), i, totalSteps);
            long startTime = System.currentTimeMillis();
            try {
                result = agent.execute(current);
                long elapsed = System.currentTimeMillis() - startTime;
                // 🟡-5 fix: Check for hard failure — updatedContext() returns null on error,
                // which would cause NPE on next agent's execute()
                if (result.isHardFailure()) {
                    log.error("Agent {} hard failure: {}", agent.name(), result.errorMessage());
                    if (progressListener != null) progressListener.onAgentFail(agent.name(), i, totalSteps, result.errorMessage());
                    return new PipelineResult(agent.name(), result.errorMessage(), current); // Pass checkpoint context
                }
                PipelineContext updatedCtx = result.updatedContext();
                if (updatedCtx != null) {
                    current = updatedCtx;
                } else {
                    log.warn("Agent {} returned null context — preserving previous context", agent.name());
                }
                log.info("Agent {} completed successfully", agent.name());
                enabledCount++;
                // Update checkpoint so we can resume from here if pipeline fails later
                current.updateCheckpoint(i, agent.name());
                String summary = String.format("%d chars, %d ms", result.generatedText() != null ? result.generatedText().length() : 0, elapsed);
                if (progressListener != null) progressListener.onAgentComplete(agent.name(), i, totalSteps, elapsed, summary);
            } catch (Exception e) {
                log.error("Agent {} failed: {}", agent.name(), e.getMessage(), e);
                if (progressListener != null) progressListener.onAgentFail(agent.name(), i, totalSteps, e.getMessage());
                return new PipelineResult(agent.name(), "Agent failed: " + e.getMessage(), current);
            }

            log.info("All agents skipped or completed");
        }

        // All agents skipped → return error result instead of null
        if (result == null) {
            log.warn("All agents were disabled — pipeline produced no output");
            return new PipelineResult("Pipeline", "All agents disabled — no work was done");
        }

        // Pipeline complete notification
        if (progressListener != null) {
            int chapters = current.getBook().getChapters().size();
            int words = 0;
            for (var ch : current.getBook().getChapters()) {
                String txt = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
                if (txt != null) words += com.novelforge.core.models.TextUtils.estimateChineseWordCount(txt);
            }
            double auditScore = current.getAuditResult() != null ? current.getAuditResult().getOverallScore() : 0;
            progressListener.onPipelineComplete(chapters, words, auditScore);
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
                PipelineContext updatedCtx = result.updatedContext();
                if (updatedCtx != null) {
                    current = updatedCtx;
                } else {
                    log.warn("Agent {} returned null context — preserving previous context", agent.name());
                }
                current.updateCheckpoint(i, agent.name());
            } catch (Exception e) {
                log.error("Agent {} failed", agent.name(), e);
                return new PipelineResult(agent.name(), "Agent failed: " + e.getMessage(), current);
            }
        }

        return result;
    }

    /** Resume pipeline from checkpoint — skip agents that already completed */
    public PipelineResult runFromCheckpoint(PipelineContext context) {
        int startFrom = context.getCheckpointAgentIndex() + 1;
        String checkpointName = context.getCheckpointAgentName();
        log.info("=== Resuming pipeline from agent index {} (after checkpoint: {}) ===", startFrom, checkpointName);
        if (startFrom >= agents.size()) {
            log.warn("Checkpoint already at the end — pipeline is complete");
            return new PipelineResult("Pipeline", "Already complete — checkpoint at end");
        }
        // Run from the next agent after checkpoint to end (full 9-step)
        return runFullFromIndex(context, startFrom);
    }

    /** Run full pipeline starting from a specific agent index */
    private PipelineResult runFullFromIndex(PipelineContext context, int startFrom) {
        PipelineContext current = context;
        PipelineResult result = null;
        PipelineConfig config = context.getConfig();
        int totalSteps = agents.size();

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

        for (int i = startFrom; i < agents.size(); i++) {
            Agent agent = agents.get(i);
            boolean enabled = toggles.getOrDefault(agent.name(), true);
            if (!enabled) {
                log.info("=== Skipping disabled agent: {} ===", agent.name());
                if (progressListener != null) progressListener.onAgentSkip(agent.name(), i, totalSteps);
                continue;
            }
            log.info("=== Running agent: {} (resumed) ===", agent.name());
            if (progressListener != null) progressListener.onAgentStart(agent.name(), i, totalSteps);
            long startTime = System.currentTimeMillis();
            try {
                result = agent.execute(current);
                long elapsed = System.currentTimeMillis() - startTime;
                if (result.isHardFailure()) {
                    log.error("Agent {} hard failure during resume", agent.name(), result.errorMessage());
                    if (progressListener != null) progressListener.onAgentFail(agent.name(), i, totalSteps, result.errorMessage());
                    return new PipelineResult(agent.name(), result.errorMessage(), current);
                }
                PipelineContext updatedCtx = result.updatedContext();
                if (updatedCtx != null) {
                    current = updatedCtx;
                } else {
                    log.warn("Agent {} returned null context — preserving previous context", agent.name());
                }
                current.updateCheckpoint(i, agent.name());
                String summary = String.format("%d chars, %d ms", result.generatedText() != null ? result.generatedText().length() : 0, elapsed);
                if (progressListener != null) progressListener.onAgentComplete(agent.name(), i, totalSteps, elapsed, summary);
            } catch (Exception e) {
                log.error("Agent {} failed during resume", agent.name(), e);
                if (progressListener != null) progressListener.onAgentFail(agent.name(), i, totalSteps, e.getMessage());
                return new PipelineResult(agent.name(), "Agent failed: " + e.getMessage(), current);
            }
        }

        // Pipeline complete notification (resume)
        if (progressListener != null) {
            int chapters = current.getBook().getChapters().size();
            int words = 0;
            for (var ch : current.getBook().getChapters()) {
                String txt = ch.getFinalText() != null ? ch.getFinalText() : ch.getDraftText();
                if (txt != null) words += com.novelforge.core.models.TextUtils.estimateChineseWordCount(txt);
            }
            double auditScore = current.getAuditResult() != null ? current.getAuditResult().getOverallScore() : 0;
            progressListener.onPipelineComplete(chapters, words, auditScore);
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
