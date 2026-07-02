package com.novelforge.core.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineConfigTest {

    @Test
    void testDefaultValues() {
        PipelineConfig config = new PipelineConfig();
        assertTrue(config.isRunArchitect());
        assertTrue(config.isRunPlanner());
        assertTrue(config.isRunComposer());
        assertTrue(config.isRunWriter());
        assertTrue(config.isRunObserver());
        assertTrue(config.isRunReflector());
        assertTrue(config.isRunNormalizer());
        assertTrue(config.isRunAuditor());
        assertTrue(config.isRunReviser());
        assertEquals(2000, config.getChapterWordsMin());
        assertEquals(4000, config.getChapterWordsMax());
        assertEquals(7.0, config.getAuditPassThreshold());
        assertEquals(1, config.getMaxRevisionPasses());
    }

    @Test
    void testToggleAgents() {
        PipelineConfig config = new PipelineConfig();
        config.setRunAuditor(false);
        config.setRunReviser(false);
        assertFalse(config.isRunAuditor());
        assertFalse(config.isRunReviser());
        assertTrue(config.isRunWriter()); // others remain on
    }

    @Test
    void testCustomWordTargets() {
        PipelineConfig config = new PipelineConfig();
        config.setChapterWordsMin(3000);
        config.setChapterWordsMax(6000);
        config.setAuditPassThreshold(8.5);
        config.setMaxRevisionPasses(2);
        assertEquals(3000, config.getChapterWordsMin());
        assertEquals(6000, config.getChapterWordsMax());
        assertEquals(8.5, config.getAuditPassThreshold());
        assertEquals(2, config.getMaxRevisionPasses());
    }
}
