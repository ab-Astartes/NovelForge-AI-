package com.novelforge.core.models;

import java.util.ArrayList;
import java.util.List;

/**
 * WritingProgress — tracks writing progress statistics per chapter and per agent.
 */
public class WritingProgress {

    private int totalChapters;
    private int totalWords;
    private int auditedChapters;
    private int passedChapters;
    private int averageWordsPerChapter;
    private double averageAuditScore;
    private long totalPipelineTimeMs;

    private final List<ChapterProgress> chapterProgresses = new ArrayList<>();

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
    public double getAverageAuditScore() { return averageAuditScore; }
    public void setAverageAuditScore(double averageAuditScore) { this.averageAuditScore = averageAuditScore; }
    public long getTotalPipelineTimeMs() { return totalPipelineTimeMs; }
    public void setTotalPipelineTimeMs(long totalPipelineTimeMs) { this.totalPipelineTimeMs = totalPipelineTimeMs; }
    public List<ChapterProgress> getChapterProgresses() { return chapterProgresses; }

    /** Compute aggregate stats from chapter progresses */
    public void computeFromChapters() {
        if (chapterProgresses.isEmpty()) return;
        totalChapters = chapterProgresses.size();
        totalWords = chapterProgresses.stream().mapToInt(ChapterProgress::getWordCount).sum();
        averageWordsPerChapter = totalWords / totalChapters;
        auditedChapters = (int) chapterProgresses.stream().filter(ChapterProgress::isAudited).count();
        passedChapters = (int) chapterProgresses.stream().filter(ChapterProgress::isPassed).count();
        averageAuditScore = chapterProgresses.stream()
                .filter(ChapterProgress::isAudited)
                .mapToDouble(ChapterProgress::getAuditScore)
                .average().orElse(0.0);
        totalPipelineTimeMs = chapterProgresses.stream().mapToLong(ChapterProgress::getPipelineTimeMs).sum();
    }

    @Override
    public String toString() {
        return String.format("Progress: %d chapters, %d words, %d audited, %d passed, avg %d words/chapter, avg score %.1f",
                totalChapters, totalWords, auditedChapters, passedChapters, averageWordsPerChapter, averageAuditScore);
    }

    /**
     * Per-chapter progress: word count, audit score, agent timings, pass/fail status.
     */
    public static class ChapterProgress {
        private int chapterNumber;
        private String chapterTitle;
        private int wordCount;
        private boolean audited;
        private boolean passed;
        private double auditScore;
        private long pipelineTimeMs;
        private final List<AgentTiming> agentTimings = new ArrayList<>();

        public ChapterProgress() {}
        public ChapterProgress(int chapterNumber, String chapterTitle, int wordCount) {
            this.chapterNumber = chapterNumber;
            this.chapterTitle = chapterTitle;
            this.wordCount = wordCount;
        }

        // --- Getters/Setters ---
        public int getChapterNumber() { return chapterNumber; }
        public void setChapterNumber(int chapterNumber) { this.chapterNumber = chapterNumber; }
        public String getChapterTitle() { return chapterTitle; }
        public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }
        public int getWordCount() { return wordCount; }
        public void setWordCount(int wordCount) { this.wordCount = wordCount; }
        public boolean isAudited() { return audited; }
        public void setAudited(boolean audited) { this.audited = audited; }
        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }
        public double getAuditScore() { return auditScore; }
        public void setAuditScore(double auditScore) { this.auditScore = auditScore; }
        public long getPipelineTimeMs() { return pipelineTimeMs; }
        public void setPipelineTimeMs(long pipelineTimeMs) { this.pipelineTimeMs = pipelineTimeMs; }
        public List<AgentTiming> getAgentTimings() { return agentTimings; }
    }

    /**
     * Per-agent timing: agent name, duration in ms, output char count.
     */
    public static class AgentTiming {
        private String agentName;
        private long durationMs;
        private int outputChars;

        public AgentTiming() {}
        public AgentTiming(String agentName, long durationMs, int outputChars) {
            this.agentName = agentName;
            this.durationMs = durationMs;
            this.outputChars = outputChars;
        }

        public String getAgentName() { return agentName; }
        public void setAgentName(String agentName) { this.agentName = agentName; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
        public int getOutputChars() { return outputChars; }
        public void setOutputChars(int outputChars) { this.outputChars = outputChars; }
    }
}
