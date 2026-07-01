package com.novelforge.core.models;

/**
 * Chapter — a single chapter in the book.
 * Holds draft text, intent, audit result, and revision history.
 */
public class Chapter {

    private int number;
    private String title;
    private String intent;      // chapter-XXXX.intent.md content
    private String contextJson; // composed context JSON
    private String draftText;   // raw generated text
    private String finalText;   // after normalization + revision
    private AuditResult auditResult;
    private RevisionPlan revisionPlan;
    private int wordCount;

    // --- Getters/Setters ---
    public int getNumber() { return number; }
    public void setNumber(int n) { this.number = n; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getIntent() { return intent; }
    public void setIntent(String i) { this.intent = i; }
    public String getContextJson() { return contextJson; }
    public void setContextJson(String c) { this.contextJson = c; }
    public String getDraftText() { return draftText; }
    public void setDraftText(String t) { this.draftText = t; }
    public String getFinalText() { return finalText; }
    public void setFinalText(String t) { this.finalText = t; }
    public AuditResult getAuditResult() { return auditResult; }
    public void setAuditResult(AuditResult a) { this.auditResult = a; }
    public RevisionPlan getRevisionPlan() { return revisionPlan; }
    public void setRevisionPlan(RevisionPlan r) { this.revisionPlan = r; }
    public int getWordCount() { return wordCount; }
    public void setWordCount(int w) { this.wordCount = w; }
}
