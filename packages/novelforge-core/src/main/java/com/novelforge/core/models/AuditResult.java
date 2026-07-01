package com.novelforge.core.models;

import java.util.List;
import java.util.Map;

/**
 * AuditResult — 33-dimension quality check output.
 * Each dimension has a score (0-10) and optional issues list.
 */
public class AuditResult {

    private Map<String, Double> dimensionScores;  // dimension name → score
    private List<String> criticalIssues;           // must-fix before proceeding
    private List<String> warnings;                 // nice-to-fix
    private double overallScore;                   // weighted average
    private boolean pass;                          // true if no critical issues and overallScore >= threshold

    // --- Getters/Setters ---
    public Map<String, Double> getDimensionScores() { return dimensionScores; }
    public void setDimensionScores(Map<String, Double> s) { this.dimensionScores = s; }
    public List<String> getCriticalIssues() { return criticalIssues; }
    public void setCriticalIssues(List<String> i) { this.criticalIssues = i; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> w) { this.warnings = w; }
    public double getOverallScore() { return overallScore; }
    public void setOverallScore(double s) { this.overallScore = s; }
    public boolean isPass() { return pass; }
    public void setPass(boolean p) { this.pass = p; }
}
