package com.novelforge.core.models;

/**
 * WritingProgress — tracks writing progress statistics.
 */
public class WritingProgress {

    private int totalChapters;
    private int totalWords;
    private int auditedChapters;
    private int passedChapters;
    private int averageWordsPerChapter;

    // --- Getters/Setters ---
    public int getTotalChapters() { return totalChapters; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }
    public int getTotalWords() { return totalWords; }
    public void setTotalWords(int totalWords) { this.totalWords = totalWords; }
    public int getAuditedChapters() { return auditedChapters; }
    public void setAuditedChapters(int auditedChapters) { this.auditedChapters = auditedChapters; }
    public int getPassedChapters() { return passedChapters; }
    public void setPassedChapters(int passedChapters) { this.passedChapters = passedChapters; }
    public int getAverageWordsPerChapter() { return averageWordsPerChapter; }
    public void setAverageWordsPerChapter(int averageWordsPerChapter) { this.averageWordsPerChapter = averageWordsPerChapter; }

    @Override
    public String toString() {
        return String.format("Progress: %d chapters, %d words, %d audited, %d passed, avg %d words/chapter",
                totalChapters, totalWords, auditedChapters, passedChapters, averageWordsPerChapter);
    }
}
