package com.novelforge.core.pipeline;

/**
 * PipelineConfig — configuration for a pipeline run.
 * Controls which agents run, model overrides, length targets, etc.
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfig.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper SHARED_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

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

    /** Hot-reload config from a JSON file (fixes #28: configuration hot-update).
     *  Reads the file and updates all fields, preserving any that are missing in the JSON. */
    public void reloadFromJson(java.nio.file.Path configFile) {
        if (configFile == null || !java.nio.file.Files.exists(configFile)) return;
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                SHARED_MAPPER.readTree(java.nio.file.Files.newInputStream(configFile));
            if (root.has("chapterWordsMin")) this.chapterWordsMin = root.get("chapterWordsMin").asInt();
            if (root.has("chapterWordsMax")) this.chapterWordsMax = root.get("chapterWordsMax").asInt();
            if (root.has("auditPassThreshold")) this.auditPassThreshold = root.get("auditPassThreshold").asDouble();
            if (root.has("maxRevisionPasses")) this.maxRevisionPasses = root.get("maxRevisionPasses").asInt();
            if (root.has("runArchitect")) this.runArchitect = root.get("runArchitect").asBoolean();
            if (root.has("runPlanner")) this.runPlanner = root.get("runPlanner").asBoolean();
            if (root.has("runComposer")) this.runComposer = root.get("runComposer").asBoolean();
            if (root.has("runWriter")) this.runWriter = root.get("runWriter").asBoolean();
            if (root.has("runObserver")) this.runObserver = root.get("runObserver").asBoolean();
            if (root.has("runReflector")) this.runReflector = root.get("runReflector").asBoolean();
            if (root.has("runNormalizer")) this.runNormalizer = root.get("runNormalizer").asBoolean();
            if (root.has("runAuditor")) this.runAuditor = root.get("runAuditor").asBoolean();
            if (root.has("runReviser")) this.runReviser = root.get("runReviser").asBoolean();
        } catch (Exception e) {
            // Log diagnostic info instead of silently swallowing (tech debt fix)
            log.error("Failed to reload pipeline config from {}: {}. Old config remains valid.",
                    configFile, e.getMessage(), e);
        }
    }
}
