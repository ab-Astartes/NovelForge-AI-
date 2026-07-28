package com.novelforge.core.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WritingProgressTest {

    @Test
    void testDefaultCreation() {
        WritingProgress progress = new WritingProgress();
        assertEquals(0, progress.getTotalChapters());
        assertEquals(0, progress.getTotalWords());
        assertEquals(0, progress.getAverageWordsPerChapter());
        assertEquals(0.0, progress.getAverageAuditScore(), 0.001);
        assertNotNull(progress.getChapterProgresses());
        assertTrue(progress.getChapterProgresses().isEmpty());
    }

    @Test
    void testComputeFromChapters() {
        WritingProgress progress = new WritingProgress();

        WritingProgress.ChapterProgress cp1 = new WritingProgress.ChapterProgress(1, "启程", 3000);
        cp1.setAudited(true);
        cp1.setPassed(true);
        cp1.setAuditScore(7.5);
        cp1.setPipelineTimeMs(5000);

        WritingProgress.ChapterProgress cp2 = new WritingProgress.ChapterProgress(2, "交锋", 2500);
        cp2.setAudited(true);
        cp2.setPassed(false);
        cp2.setAuditScore(6.2);
        cp2.setPipelineTimeMs(4500);

        progress.getChapterProgresses().add(cp1);
        progress.getChapterProgresses().add(cp2);
        progress.computeFromChapters();

        assertEquals(2, progress.getTotalChapters());
        assertEquals(5500, progress.getTotalWords());
        assertEquals(2750, progress.getAverageWordsPerChapter());
        assertEquals(2, progress.getAuditedChapters());
        assertEquals(1, progress.getPassedChapters());
        assertEquals(6.85, progress.getAverageAuditScore(), 0.01);
        assertEquals(9500, progress.getTotalPipelineTimeMs());
    }

    @Test
    void testComputeFromEmptyChapters() {
        WritingProgress progress = new WritingProgress();
        progress.computeFromChapters();
        // Empty chapters → no change (default 0s)
        assertEquals(0, progress.getTotalChapters());
        assertEquals(0, progress.getTotalWords());
    }

    @Test
    void testChapterProgressFields() {
        WritingProgress.ChapterProgress cp = new WritingProgress.ChapterProgress(3, "高潮", 4000);
        assertEquals(3, cp.getChapterNumber());
        assertEquals("高潮", cp.getChapterTitle());
        assertEquals(4000, cp.getWordCount());
        assertFalse(cp.isAudited());
        assertFalse(cp.isPassed());
        assertNotNull(cp.getAgentTimings());
    }

    @Test
    void testAgentTimingFields() {
        WritingProgress.AgentTiming timing = new WritingProgress.AgentTiming("Writer", 3200, 4000);
        assertEquals("Writer", timing.getAgentName());
        assertEquals(3200, timing.getDurationMs());
        assertEquals(4000, timing.getOutputChars());
    }

    @Test
    void testToStringFormat() {
        WritingProgress progress = new WritingProgress();
        progress.setTotalChapters(5);
        progress.setTotalWords(15000);
        progress.setAuditedChapters(5);
        progress.setPassedChapters(4);
        progress.setAverageWordsPerChapter(3000);
        progress.setAverageAuditScore(7.2);
        String str = progress.toString();
        assertTrue(str.contains("5 chapters"));
        assertTrue(str.contains("15000 words"));
        assertTrue(str.contains("7.2"));
    }

    @Test
    void testSettersAndGetters() {
        WritingProgress progress = new WritingProgress();
        progress.setTotalChapters(10);
        progress.setTotalWords(50000);
        progress.setAuditedChapters(8);
        progress.setPassedChapters(7);
        progress.setAverageWordsPerChapter(5000);
        progress.setAverageAuditScore(7.8);
        progress.setTotalPipelineTimeMs(120000);

        assertEquals(10, progress.getTotalChapters());
        assertEquals(50000, progress.getTotalWords());
        assertEquals(8, progress.getAuditedChapters());
        assertEquals(7, progress.getPassedChapters());
        assertEquals(5000, progress.getAverageWordsPerChapter());
        assertEquals(7.8, progress.getAverageAuditScore(), 0.001);
        assertEquals(120000, progress.getTotalPipelineTimeMs());
    }
}
