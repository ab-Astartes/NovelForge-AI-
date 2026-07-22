package com.novelforge.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TimelineStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testDefaultCreation(@TempDir Path tmpDir) {
        TimelineState state = new TimelineState(tmpDir.resolve("timeline.json"), mapper);
        // Fresh timeline has no events
        String recent = state.getRecentEvents(10);
        assertEquals("", recent);
    }

    @Test
    void testAddEvent(@TempDir Path tmpDir) {
        TimelineState state = new TimelineState(tmpDir.resolve("timeline.json"), mapper);
        state.addEvent(1, "主角入学");
        state.addEvent(2, "发现秘密");

        String recent = state.getRecentEvents(10);
        assertTrue(recent.contains("主角入学"));
        assertTrue(recent.contains("发现秘密"));
    }

    @Test
    void testSaveAndLoadRoundTrip(@TempDir Path tmpDir) {
        Path tlFile = tmpDir.resolve("timeline.json");
        TimelineState original = new TimelineState(tlFile, mapper);
        original.addEvent(0, "世界介绍");
        original.save();

        assertTrue(java.nio.file.Files.exists(tlFile));

        TimelineState loaded = new TimelineState(tlFile, mapper);
        String recent = loaded.getRecentEvents(10);
        assertTrue(recent.contains("世界介绍"));
    }

    @Test
    void testGetRecentEventsLastN(@TempDir Path tmpDir) {
        TimelineState state = new TimelineState(tmpDir.resolve("timeline.json"), mapper);
        for (int i = 1; i <= 10; i++) {
            state.addEvent(i, "事件" + i);
        }

        String recent = state.getRecentEvents(3);
        assertTrue(recent.contains("事件8"));
        assertTrue(recent.contains("事件9"));
        assertTrue(recent.contains("事件10"));
        assertFalse(recent.contains("事件7"));
    }

    @Test
    void testGetRecentEventsEmpty(@TempDir Path tmpDir) {
        TimelineState state = new TimelineState(tmpDir.resolve("timeline.json"), mapper);
        String recent = state.getRecentEvents(5);
        assertEquals("", recent);
    }

    @Test
    void testConcurrentAccess(@TempDir Path tmpDir) throws Exception {
        TimelineState state = new TimelineState(tmpDir.resolve("timeline.json"), mapper);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);

        for (int i = 0; i < 20; i++) {
            final int idx = i;
            pool.submit(() -> state.addEvent(idx + 1, "事件" + idx));
        }
        pool.shutdown();
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        // All 20 events should be present
        String recent = state.getRecentEvents(20);
        assertTrue(recent.contains("事件19"));
        assertTrue(recent.contains("事件0"));
    }
}
