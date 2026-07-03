package com.novelforge.core.models;

import com.novelforge.core.state.TruthState;
import com.novelforge.core.pipeline.PipelineConfig;

/**
 * PipelineContext — carries book state, truth state, config, and accumulated results
 * through the agent pipeline. Each agent reads from and writes to this context.
 */
public class PipelineContext {

    private Book book;
    private TruthState truthState;
    private String currentChapterDraft;    // Writer's chapter text (the actual chapter content)
    private String writerDraft;            // Writer's original output (preserved for comparison)
    private String architectOutput;        // Architect outline + chapter plan
    private String plannerOutput;          // Planner hook agenda + rhythm plan
    private String composerOutput;         // Composer assembled context package
    private String observerOutput;         // Observer fact extraction
    private String reflectorOutput;        // Reflector state patch operations
    private String normalizerOutput;       // Normalizer length-adjusted text
    private AuditResult auditResult;
    private PipelineConfig config;

    public PipelineContext(Book book, TruthState truthState, PipelineConfig config) {
        this.book = book;
        this.truthState = truthState;
        this.config = config;
    }

    // --- Getters ---
    public Book getBook() { return book; }
    public TruthState getTruthState() { return truthState; }
    public String getCurrentChapterDraft() { return currentChapterDraft; }
    public String getWriterDraft() { return writerDraft; }
    public String getArchitectOutput() { return architectOutput; }
    public String getPlannerOutput() { return plannerOutput; }
    public String getComposerOutput() { return composerOutput; }
    public String getObserverOutput() { return observerOutput; }
    public String getReflectorOutput() { return reflectorOutput; }
    public String getNormalizerOutput() { return normalizerOutput; }
    public AuditResult getAuditResult() { return auditResult; }
    public PipelineConfig getConfig() { return config; }

    // --- Setters (each agent writes its own dedicated field) ---
    public void setCurrentChapterDraft(String draft) { this.currentChapterDraft = draft; }
    public void setWriterDraft(String draft) { this.writerDraft = draft; }
    public void setArchitectOutput(String output) { this.architectOutput = output; }
    public void setPlannerOutput(String output) { this.plannerOutput = output; }
    public void setComposerOutput(String output) { this.composerOutput = output; }
    public void setObserverOutput(String output) { this.observerOutput = output; }
    public void setReflectorOutput(String output) { this.reflectorOutput = output; }
    public void setNormalizerOutput(String output) { this.normalizerOutput = output; }
    public void setAuditResult(AuditResult result) { this.auditResult = result; }
}
