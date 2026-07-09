package com.novelforge.core.models;

/**
 * PipelineResult — output from a single agent or full pipeline run.
 * Carries the updated context, generated text, and any metadata.
 *
 * Two distinct result types:
 * - Success: context + text + agentName, success=true
 * - Error: agentName + errorMessage, success=false, no context (pipeline should stop)
 *
 * The old "(context, text, agent, isError)" constructor was semantically confusing:
 * it stored the error message as generatedText and also set errorMessage.
 * Replaced with explicit success/error constructors.
 */
public class PipelineResult {

    private PipelineContext updatedContext;
    private String generatedText;
    private String agentName;
    private boolean success;
    private String errorMessage;

    /** Success result: agent completed, context updated */
    public PipelineResult(PipelineContext context, String text, String agent) {
        this.updatedContext = context;
        this.generatedText = text;
        this.agentName = agent;
        this.success = true;
        this.errorMessage = null;
    }

    /** Error result: agent failed, no context — pipeline should stop or skip */
    public PipelineResult(String agent, String error) {
        this.agentName = agent;
        this.success = false;
        this.errorMessage = error;
        this.generatedText = null;
        this.updatedContext = null;
    }

    /** Recovery result: agent hit a non-fatal error but still produced usable output.
     *  Context is preserved for downstream agents to continue. */
    public static PipelineResult recovery(PipelineContext context, String partialText, String agent, String warning) {
        PipelineResult r = new PipelineResult(context, partialText, agent);
        r.errorMessage = warning; // non-null but success=true indicates partial success
        return r;
    }

    // --- Getters ---
    public PipelineContext updatedContext() { return updatedContext; }
    public String generatedText() { return generatedText; }
    public String agentName() { return agentName; }
    public boolean success() { return success; }
    public String errorMessage() { return errorMessage; }
    /** Has non-fatal warning (success=true but something suboptimal happened) */
    public boolean hasWarning() { return success && errorMessage != null; }
}
