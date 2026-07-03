package com.novelforge.core.state;

import com.novelforge.core.models.HookOp;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HookManager — manages narrative hooks (promises to the reader).
 * Tracks mustAdvance (must be pushed forward), eligibleResolve (can be resolved),
 * staleDebt (overdue hooks), and new hooks planted.
 * Reads/writes hooks.json.
 */
public class HookManager {

    private static final Logger log = LoggerFactory.getLogger(HookManager.class);

    private final Path filePath;
    private final ObjectMapper mapper;
    private ObjectNode data;

    public HookManager(Path filePath, ObjectMapper mapper) {
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
                this.data.putArray("hooks");
                this.data.putArray("mustAdvance");
                this.data.putArray("staleDebt");
                save();
            }
        } catch (Exception e) {
            log.warn("Failed to load hooks.json, starting fresh", e);
            this.data = mapper.createObjectNode();
            this.data.putArray("hooks");
            this.data.putArray("mustAdvance");
            this.data.putArray("staleDebt");
        }
    }

    public void save() {
        try {
            Files.createDirectories(filePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(Files.newOutputStream(filePath), data);
        } catch (Exception e) {
            log.error("Failed to save hooks.json", e);
        }
    }

    /** Apply HookOps from Reflector */
    public void applyOps(List<HookOp> ops) {
        ArrayNode hooks = data.has("hooks") ? (ArrayNode) data.get("hooks") : data.putArray("hooks");

        for (HookOp op : ops) {
            switch (op.getType()) {
                case UPSERT -> {
                    // Find existing or add new
                    boolean found = false;
                    for (int i = 0; i < hooks.size(); i++) {
                        if (hooks.get(i).get("id").asText().equals(op.getHookId())) {
                            ((ObjectNode) hooks.get(i)).put("description", op.getDescription());
                            ((ObjectNode) hooks.get(i)).put("priority", op.getPriority());
                            ((ObjectNode) hooks.get(i)).put("mentionCount", op.getMentionCount());
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        ObjectNode newHook = mapper.createObjectNode();
                        newHook.put("id", op.getHookId());
                        newHook.put("description", op.getDescription());
                        newHook.put("chapterOrigin", op.getChapterOrigin());
                        newHook.put("mentionCount", op.getMentionCount());
                        newHook.put("priority", op.getPriority());
                        hooks.add(newHook);
                    }
                }
                case MENTION -> {
                    for (int i = 0; i < hooks.size(); i++) {
                        if (hooks.get(i).get("id").asText().equals(op.getHookId())) {
                            ((ObjectNode) hooks.get(i)).put("mentionCount",
                                    hooks.get(i).get("mentionCount").asInt() + 1);
                            break;
                        }
                    }
                }
                case RESOLVE -> {
                    // Remove from active hooks
                    for (int i = 0; i < hooks.size(); i++) {
                        if (hooks.get(i).get("id").asText().equals(op.getHookId())) {
                            hooks.remove(i);
                            break;
                        }
                    }
                }
                case DEFER -> {
                    // Mark as stale (move to staleDebt)
                    for (int i = 0; i < hooks.size(); i++) {
                        if (hooks.get(i).get("id").asText().equals(op.getHookId())) {
                            ArrayNode stale = (ArrayNode) data.get("staleDebt");
                            stale.add(hooks.get(i));
                            hooks.remove(i);
                            break;
                        }
                    }
                }
            }
        }
        save();
    }

    /** Get mustAdvance hook list summary */
    public String getMustAdvanceSummary() {
        JsonNode mustAdvance = data.get("mustAdvance");
        if (mustAdvance == null || mustAdvance.isEmpty()) return "（无必须推进的悬念）";
        StringBuilder sb = new StringBuilder();
        for (JsonNode h : mustAdvance) {
            String id = h.has("id") ? h.get("id").asText() : "unknown";
            String desc = h.has("description") ? h.get("description").asText() : "";
            sb.append("- ").append(id)
              .append(": ").append(desc).append("\n");
        }
        return sb.toString();
    }

    /** Get full hook state summary */
    public String getSummary() {
        JsonNode hooks = data.get("hooks");
        JsonNode stale = data.get("staleDebt");
        StringBuilder sb = new StringBuilder();
        sb.append("活跃悬念: ").append(hooks != null ? hooks.size() : 0).append(" 个\n");
        sb.append("过期悬念: ").append(stale != null ? stale.size() : 0).append(" 个\n");
        if (hooks != null && !hooks.isEmpty()) {
            for (JsonNode h : hooks) {
                String id = h.has("id") ? h.get("id").asText() : "unknown";
                String priority = h.has("priority") ? h.get("priority").asText() : "medium";
                String desc = h.has("description") ? h.get("description").asText() : "";
                sb.append("  - ").append(id)
                  .append(" (优先级: ").append(priority).append(")")
                  .append(": ").append(desc).append("\n");
            }
        }
        return sb.toString();
    }
}
