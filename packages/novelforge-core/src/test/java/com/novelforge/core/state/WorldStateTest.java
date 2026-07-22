package com.novelforge.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testDefaultCreation(@TempDir Path tmpDir) {
        WorldState state = new WorldState(tmpDir.resolve("world.json"), mapper);
        assertNotNull(state.getData());
        assertTrue(state.getData().has("locations"));
        assertTrue(state.getData().get("locations").isArray());
    }

    @Test
    void testAddLocation(@TempDir Path tmpDir) {
        WorldState state = new WorldState(tmpDir.resolve("world.json"), mapper);
        ObjectNode loc = mapper.createObjectNode();
        loc.put("name", "京城");
        state.addLocation(loc);

        assertEquals(1, state.getData().get("locations").size());
        assertEquals("京城", state.getData().get("locations").get(0).get("name").asText());
    }

    @Test
    void testAddRule(@TempDir Path tmpDir) {
        WorldState state = new WorldState(tmpDir.resolve("world.json"), mapper);
        ObjectNode rule = mapper.createObjectNode();
        rule.put("name", "魔法体系");
        rule.put("description", "元素之力");
        state.addRule(rule);

        assertEquals(1, state.getData().get("rules").size());
        assertEquals("魔法体系", state.getData().get("rules").get(0).get("name").asText());
    }

    @Test
    void testSaveAndLoadRoundTrip(@TempDir Path tmpDir) {
        Path worldFile = tmpDir.resolve("world.json");
        WorldState original = new WorldState(worldFile, mapper);

        ObjectNode loc = mapper.createObjectNode();
        loc.put("name", "长安");
        original.addLocation(loc);
        original.save();

        assertTrue(java.nio.file.Files.exists(worldFile));

        WorldState loaded = new WorldState(worldFile, mapper);
        assertEquals(1, loaded.getData().get("locations").size());
        assertEquals("长安", loaded.getData().get("locations").get(0).get("name").asText());
    }

    @Test
    void testGetSummary(@TempDir Path tmpDir) {
        WorldState state = new WorldState(tmpDir.resolve("world.json"), mapper);
        ObjectNode loc = mapper.createObjectNode();
        loc.put("name", "汴京");
        state.addLocation(loc);

        String summary = state.getSummary();
        assertTrue(summary.contains("汴京"));
    }

    @Test
    void testGetSummaryEmpty(@TempDir Path tmpDir) {
        WorldState state = new WorldState(tmpDir.resolve("world.json"), mapper);
        String summary = state.getSummary();
        assertTrue(summary.contains("暂无"));
    }

    @Test
    void testConcurrentAccess(@TempDir Path tmpDir) throws Exception {
        WorldState state = new WorldState(tmpDir.resolve("world.json"), mapper);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);

        for (int i = 0; i < 20; i++) {
            final int idx = i;
            pool.submit(() -> {
                ObjectNode loc = mapper.createObjectNode();
                loc.put("name", "地点" + idx);
                state.addLocation(loc);
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(20, state.getData().get("locations").size());
    }
}
