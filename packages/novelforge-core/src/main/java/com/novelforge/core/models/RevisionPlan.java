package com.novelforge.core.models;

/**
 * RevisionPlan — describes the revision strategy selected by ReviserAgent.
 * Mode determines how aggressively to revise:
 * - POLISH: light touch, only minor wording improvements
 * - SPOT_FIX: targeted repairs on specific problematic paragraphs
 * - REWRITE: major overhaul, restructure scenes/paragraphs
 * - ANTI_DETECT: overlay anti-AIGC patterns (remove repetition, generic expressions)
 */
public class RevisionPlan {

    public enum Mode {
        POLISH,
        SPOT_FIX,
        REWRITE,
        ANTI_DETECT
    }

    private Mode mode;
    private String focusDescription;
    private java.util.List<String> targetDimensions;

    public RevisionPlan() {}

    public RevisionPlan(Mode mode, String focusDescription) {
        this.mode = mode;
        this.focusDescription = focusDescription;
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public String getFocusDescription() { return focusDescription; }
    public void setFocusDescription(String focusDescription) { this.focusDescription = focusDescription; }

    public java.util.List<String> getTargetDimensions() { return targetDimensions; }
    public void setTargetDimensions(java.util.List<String> targetDimensions) { this.targetDimensions = targetDimensions; }

    @Override
    public String toString() {
        return "RevisionPlan{mode=" + mode + ", focus=" + focusDescription + "}";
    }
}
