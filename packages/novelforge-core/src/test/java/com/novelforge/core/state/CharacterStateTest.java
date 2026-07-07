package com.novelforge.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterStateTest {

    @TempDir
    Path tempDir;

    @Test
    void testLoadCreatesDefaultState() {
        Path file = tempDir.resolve("characters.json");
        CharacterState state = new CharacterState(file, new ObjectMapper());
        // After removing auto-save, constructor no longer creates the file;
        // verify the state object is correctly initialized instead.
        assertNotNull(state.getSummary());
        assertFalse(file.toFile().exists()); // file should NOT be auto-created
    }

    @Test
    void testUpsertNewCharacter() {
        Path file = tempDir.resolve("characters.json");
        ObjectMapper mapper = new ObjectMapper();
        CharacterState state = new CharacterState(file, mapper);

        ObjectNode delta = mapper.createObjectNode();
        delta.put("name", "张三");
        delta.put("role", "主角");
        delta.put("powerLevel", "1级");

        state.upsertCharacter("张三", delta);
        state.save();

        // Reload and verify
        CharacterState loaded = new CharacterState(file, mapper);
        assertNotNull(loaded.getCharacter("张三"));
        assertEquals("主角", loaded.getCharacter("张三").get("role").asText());
    }

    @Test
    void testUpsertUpdateExisting() {
        Path file = tempDir.resolve("characters.json");
        ObjectMapper mapper = new ObjectMapper();
        CharacterState state = new CharacterState(file, mapper);

        ObjectNode delta1 = mapper.createObjectNode();
        delta1.put("name", "李四");
        delta1.put("role", "配角");
        state.upsertCharacter("李四", delta1);

        ObjectNode delta2 = mapper.createObjectNode();
        delta2.put("name", "李四");
        delta2.put("role", "反派");
        delta2.put("powerLevel", "5级");
        state.upsertCharacter("李四", delta2);
        state.save();

        CharacterState loaded = new CharacterState(file, mapper);
        assertEquals("反派", loaded.getCharacter("李四").get("role").asText());
        assertEquals("5级", loaded.getCharacter("李四").get("powerLevel").asText());
    }

    @Test
    void testGetSummary() {
        Path file = tempDir.resolve("characters.json");
        ObjectMapper mapper = new ObjectMapper();
        CharacterState state = new CharacterState(file, mapper);

        ObjectNode ch1 = mapper.createObjectNode();
        ch1.put("name", "王五");
        ch1.put("role", "导师");
        state.upsertCharacter("王五", ch1);

        String summary = state.getSummary();
        assertTrue(summary.contains("王五"));
        assertTrue(summary.contains("导师"));
    }
}
