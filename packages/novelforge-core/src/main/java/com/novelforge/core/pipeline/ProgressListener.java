package com.novelforge.core.pipeline;

/**
 * ProgressListener — receives real-time progress events from the Agent pipeline.
 * Used by StudioServer SSE to push live updates to the frontend.
 */
public interface ProgressListener {

    /** Called when an agent starts execution */
    void onAgentStart(String agentName, int stepIndex, int totalSteps);

    /** Called when an agent completes successfully */
    void onAgentComplete(String agentName, int stepIndex, int totalSteps, long elapsedMs, String summary);

    /** Called when an agent is skipped (disabled) */
    void onAgentSkip(String agentName, int stepIndex, int totalSteps);

    /** Called when an agent fails */
    void onAgentFail(String agentName, int stepIndex, int totalSteps, String error);

    /** Called when the pipeline finishes (all agents done) */
    void onPipelineComplete(int totalChapters, int totalWords, double auditScore);

    /** Called when the pipeline fails overall */
    void onPipelineFail(String error);
}
