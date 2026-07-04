package com.novelforge.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TimelineState — tracks chronological events across chapters.
 * Reads/writes timeline.json.
 */
public class TimelineState {

    private static final Logger log = LoggerFactory.getLogger(TimelineState.class);

    private final Path filePath;
    private final ObjectMapper mapper;
    private ObjectNode data;

    public TimelineState(Path filePath, ObjectMapper mapper) {
        this.filePath = filePath;
        this.mapper = mapper;
        load();
    }

    public void load() {
        try {
            if (Files.exists(filePath)) {
                JsonNode root = mapper.readTree(Files.newInputStream(filePath));
                this.data = root.isObject() ? (ObjectNode) root : mapper.createObjectNode();
            } else {
                this.data = mapper.createObjectNode();
                this.data.putArray("events");
                save();
            }
        } catch (Exception e) {
            log.warn("Failed to load timeline.json, starting fresh", e);
            this.data = mapper.createObjectNode();
            this.data.putArray("events");
        }
    }

    public void save() {
        try {
            Files.createDirectories(filePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(Files.newOutputStream(filePath), data);
        } catch (Exception e) {
            log.error("Failed to save timeline.json", e);
        }
    }

    public void addEvent(int chapter, String description) {
        JsonNode events = data.get("events");
        if (events == null || !events.isArray()) {
            events = data.putArray("events");
        }
        ObjectNode event = mapper.createObjectNode();
        event.put("chapter", chapter);
        event.put("description", description);
        ((com.fasterxml.jackson.databind.node.ArrayNode) events).add(event);
    }

    /** Get recent events for context */
    public String getRecentEvents(int lastN) {
        JsonNode events = data.get("events");
        if (events == null || events.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, events.size() - lastN);
        for (int i = start; i < events.size(); i++) {
            JsonNode e = events.get(i);
            sb.append("第").append(e.has("chapter") ? e.get("chapter").asInt() : i + 1).append("章: ")
              .append(e.has("description") ? e.get("description").asText() : "(无描述)").append("\n");
        }
        return sb.toString();
    }
}
