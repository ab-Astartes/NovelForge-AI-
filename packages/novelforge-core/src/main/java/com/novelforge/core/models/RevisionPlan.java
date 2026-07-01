package com.novelforge.core.models;

import java.util.List;

/**
 * RevisionPlan — targeted repair plan from Reviser agent.
 * Mode: polish | spot-fix | rewrite | anti-detect
 */
public class RevisionPlan {

    public enum Mode { POLISH, SPOT_FIX, REWRITE, ANTI_DETECT }

    private Mode mode;
    private List<String> targets;    // specific paragraphs/lines to revise
    private String instructions;     // revision instructions for LLM
    private List<String> unresolved; // issues that couldn't be fixed in this pass

    // --- Getters/Setters ---
    public Mode getMode() { return mode; }
    public void setMode(Mode m) { this.mode = m; }
    public List<String> getTargets() { return targets; }
    public void setTargets(List<String> t) { this.targets = t; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String i) { this.instructions = i; }
    public List<String> getUnresolved() { return unresolved; }
    public void setUnresolved(List<String> u) { this.unresolved = u; }
}
