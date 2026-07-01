package com.novelforge.core.models;

/**
 * PipelineResult — output from a single agent or full pipeline run.
 * Carries the updated context, generated text, and any metadata.
 */
public class PipelineResult {

    private PipelineContext updatedContext;
    private String generatedText;
    private String agentName;
    private boolean success;
    private String errorMessage;

    public PipelineResult(PipelineContext context, String text, String agent) {
        this.updatedContext = context;
        this.generatedText = text;
        this.agentName = agent;
        this.success = true;
    }

    public PipelineResult(String agent, String error) {
        this.agentName = agent;
        this.success = false;
        this.errorMessage = error;
    }

    // --- Getters ---
    public PipelineContext updatedContext() { return updatedContext; }
    public String generatedText() { return generatedText; }
    public String agentName() { return agentName; }
    public boolean success() { return success; }
    public String errorMessage() { return errorMessage; }
}
