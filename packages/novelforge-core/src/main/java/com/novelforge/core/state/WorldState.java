package com.novelforge.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WorldState — manages world-building facts (locations, rules, items, systems).
 * Reads/writes world.json.
 */
public class WorldState {

    private static final Logger log = LoggerFactory.getLogger(WorldState.class);

    private final Path filePath;
    private final ObjectMapper mapper;
    private volatile ObjectNode data;  // fixes #29: volatile for thread-safe reads

    public WorldState(Path filePath, ObjectMapper mapper) {
        this.filePath = filePath;
        this.mapper = mapper;
        load();
    }

    public synchronized void load() {  // fixes #29
        try {
            if (Files.exists(filePath)) {
                JsonNode root = mapper.readTree(Files.newInputStream(filePath));
                this.data = root.isObject() ? (ObjectNode) root : mapper.createObjectNode();
            } else {
                this.data = mapper.createObjectNode();
                this.data.putArray("locations");
                this.data.putArray("items");
                this.data.putArray("rules");
                this.data.putArray("systems");
                // Don't auto-save on load
            }
        } catch (Exception e) {
            log.warn("Failed to load world.json, starting fresh", e);
            this.data = mapper.createObjectNode();
            this.data.putArray("locations");
            this.data.putArray("items");
            this.data.putArray("rules");
            this.data.putArray("systems");
        }
    }

    public synchronized void save() {  // fixes #29
        try {
            Files.createDirectories(filePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(Files.newOutputStream(filePath), data);
        } catch (Exception e) {
            log.error("Failed to save world.json", e);
            throw new RuntimeException("Failed to save world.json: " + e.getMessage(), e);
        }
    }

    /** Get raw ObjectNode for mutation (fixes #29: synchronized + fixed duplicate javadoc) */
    public synchronized ObjectNode getData() { return data; }

    /** Add a location entry (fixes #29: synchronized) */
    public synchronized void addLocation(JsonNode loc) {
        JsonNode arr = data.get("locations");
        if (arr != null && arr.isArray()) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) arr).add(loc);
        }
    }

    /** Add a rule entry (fixes #29: synchronized) */
    public synchronized void addRule(JsonNode rule) {
        JsonNode arr = data.get("rules");
        if (arr != null && arr.isArray()) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) arr).add(rule);
        }
    }

    public synchronized String getSummary() {  // fixes #29
        StringBuilder sb = new StringBuilder();
        JsonNode locations = data.get("locations");
        if (locations != null && !locations.isEmpty()) {
            sb.append("地点: ");
            for (JsonNode l : locations) {
                String locName = l.has("name") ? l.get("name").asText() : l.asText();
                sb.append(locName).append(", ");
            }
            if (sb.length() > 0) sb.setLength(sb.length() - 2);
            sb.append("\n");
        }
        JsonNode rules = data.get("rules");
        if (rules != null && !rules.isEmpty()) {
            sb.append("规则: ");
            for (JsonNode r : rules) {
                // rules can be strings or objects — handle both
                if (r.isTextual()) sb.append(r.asText());
                else if (r.isObject()) sb.append(r.toString());
                else sb.append(r.asText());
                sb.append("; ");
            }
            sb.append("\n");
        }
        if (sb.isEmpty()) return "（暂无世界观数据）";
        return sb.toString();
    }
}
