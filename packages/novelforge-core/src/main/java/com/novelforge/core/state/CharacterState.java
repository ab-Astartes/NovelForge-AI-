package com.novelforge.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CharacterState — manages character facts (names, attributes, relationships, arcs).
 * Reads/writes characters.json. Provides summary for prompt building.
 */
public class CharacterState {

    private static final Logger log = LoggerFactory.getLogger(CharacterState.class);

    private final Path filePath;
    private final ObjectMapper mapper;
    private ObjectNode data;

    public CharacterState(Path filePath, ObjectMapper mapper) {
        this.filePath = filePath;
        this.mapper = mapper;
        load();
    }

    public void load() {
        try {
            if (Files.exists(filePath)) {
                JsonNode root = mapper.readTree(Files.newInputStream(filePath));
                if (root.isObject()) {
                    this.data = (ObjectNode) root;
                } else {
                    this.data = mapper.createObjectNode();
                }
            } else {
                this.data = mapper.createObjectNode();
                this.data.putArray("characters");
                // Don't auto-save on load — only save when explicitly requested
            }
        } catch (Exception e) {
            log.warn("Failed to load characters.json, starting fresh", e);
            this.data = mapper.createObjectNode();
            this.data.putArray("characters");
        }
    }

    public void save() {
        try {
            Files.createDirectories(filePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(Files.newOutputStream(filePath), data);
            log.debug("characters.json saved");
        } catch (Exception e) {
            log.error("Failed to save characters.json", e);
            throw new RuntimeException("Failed to save characters.json: " + e.getMessage(), e);
        }
    }

    public JsonNode getCharacter(String name) {
        JsonNode chars = data.get("characters");
        if (chars == null || !chars.isArray()) return null;
        for (JsonNode c : chars) {
            if (name.equals(c.get("name").asText())) return c;
        }
        return null;
    }

    public void upsertCharacter(String name, JsonNode delta) {
        JsonNode chars = data.get("characters");
        if (chars == null || !chars.isArray()) {
            chars = data.putArray("characters");
        }
        // Find existing or add new
        for (int i = 0; i < chars.size(); i++) {
            JsonNode c = chars.get(i);
            if (name.equals(c.get("name").asText())) {
                // Merge delta into existing
                ((ObjectNode) chars.get(i)).setAll((ObjectNode) delta);
                return;
            }
        }
        // Add new character
        ((com.fasterxml.jackson.databind.node.ArrayNode) chars).add(delta);
    }

    /** Get human-readable summary for prompt context */
    public String getSummary() {
        JsonNode chars = data.get("characters");
        if (chars == null || chars.isEmpty()) return "（暂无角色数据）";

        StringBuilder sb = new StringBuilder();
        for (JsonNode c : chars) {
            sb.append("- ").append(c.get("name").asText());
            if (c.has("role")) sb.append(" (").append(c.get("role").asText()).append(")");
            if (c.has("powerLevel")) sb.append(" | 等级: ").append(c.get("powerLevel").asText());
            if (c.has("location")) sb.append(" | 位置: ").append(c.get("location").asText());
            sb.append("\n");
        }
        return sb.toString();
    }
}
