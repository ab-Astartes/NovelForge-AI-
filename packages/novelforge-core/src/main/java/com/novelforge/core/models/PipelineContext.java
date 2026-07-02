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
    private String currentChapterDraft;
    private String writerDraft;         // Writer's original output (preserved for comparison)
    private String observerOutput;     // Observer analysis (separate from chapter draft)
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
    public AuditResult getAuditResult() { return auditResult; }
    public PipelineConfig getConfig() { return config; }

    // --- Setters (pipeline agents update these progressively) ---
    public void setCurrentChapterDraft(String draft) { this.currentChapterDraft = draft; }
    public void setWriterDraft(String draft) { this.writerDraft = draft; }
    public void setObserverOutput(String output) { this.observerOutput = output; }
    public void setAuditResult(AuditResult result) { this.auditResult = result; }

    // --- Extra getters ---
    public String getWriterDraft() { return writerDraft; }
    public String getObserverOutput() { return observerOutput; }
}
