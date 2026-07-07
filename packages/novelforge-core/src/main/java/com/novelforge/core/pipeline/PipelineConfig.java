package com.novelforge.core.pipeline;

/**
 * PipelineConfig — configuration for a pipeline run.
 * Controls which agents run, model overrides, length targets, etc.
 */
public class PipelineConfig {

    private boolean runArchitect;
    private boolean runPlanner;
    private boolean runComposer;
    private boolean runWriter;
    private boolean runObserver;
    private boolean runReflector;
    private boolean runNormalizer;
    private boolean runAuditor;
    private boolean runReviser;

    private int chapterWordsMin;       // minimum word count target
    private int chapterWordsMax;       // maximum word count target
    private double auditPassThreshold; // overall score threshold to pass audit
    private int maxRevisionPasses;     // how many revision attempts before proceeding anyway

    // Default: full pipeline, 2000-4000 words, 7.0 threshold, 1 revision pass
    public PipelineConfig() {
        this.runArchitect = true;
        this.runPlanner = true;
        this.runComposer = true;
        this.runWriter = true;
        this.runObserver = true;
        this.runReflector = true;
        this.runNormalizer = true;
        this.runAuditor = true;
        this.runReviser = true;
        this.chapterWordsMin = 2000;
        this.chapterWordsMax = 4000;
        this.auditPassThreshold = 7.0;
        this.maxRevisionPasses = 1;
    }

    // --- Getters/Setters ---
    public boolean isRunArchitect() { return runArchitect; }
    public void setRunArchitect(boolean v) { this.runArchitect = v; }
    public boolean isRunPlanner() { return runPlanner; }
    public void setRunPlanner(boolean v) { this.runPlanner = v; }
    public boolean isRunComposer() { return runComposer; }
    public void setRunComposer(boolean v) { this.runComposer = v; }
    public boolean isRunWriter() { return runWriter; }
    public void setRunWriter(boolean v) { this.runWriter = v; }
    public boolean isRunObserver() { return runObserver; }
    public void setRunObserver(boolean v) { this.runObserver = v; }
    public boolean isRunReflector() { return runReflector; }
    public void setRunReflector(boolean v) { this.runReflector = v; }
    public boolean isRunNormalizer() { return runNormalizer; }
    public void setRunNormalizer(boolean v) { this.runNormalizer = v; }
    public boolean isRunAuditor() { return runAuditor; }
    public void setRunAuditor(boolean v) { this.runAuditor = v; }
    public boolean isRunReviser() { return runReviser; }
    public void setRunReviser(boolean v) { this.runReviser = v; }
    public int getChapterWordsMin() { return chapterWordsMin; }
    public void setChapterWordsMin(int v) {
        if (v <= 0) v = 2000; // prevent invalid: fall back to default
        this.chapterWordsMin = v;
        // Auto-swap if min > max
        if (this.chapterWordsMin > this.chapterWordsMax) {
            int tmp = this.chapterWordsMin;
            this.chapterWordsMin = this.chapterWordsMax;
            this.chapterWordsMax = tmp;
        }
    }
    public int getChapterWordsMax() { return chapterWordsMax; }
    public void setChapterWordsMax(int v) {
        if (v <= 0) v = 4000; // prevent invalid: fall back to default
        this.chapterWordsMax = v;
        // Auto-swap if min > max
        if (this.chapterWordsMin > this.chapterWordsMax) {
            int tmp = this.chapterWordsMin;
            this.chapterWordsMin = this.chapterWordsMax;
            this.chapterWordsMax = tmp;
        }
    }
    public double getAuditPassThreshold() { return auditPassThreshold; }
    public void setAuditPassThreshold(double v) { this.auditPassThreshold = v; }
    public int getMaxRevisionPasses() { return maxRevisionPasses; }
    public void setMaxRevisionPasses(int v) { this.maxRevisionPasses = v; }
}
