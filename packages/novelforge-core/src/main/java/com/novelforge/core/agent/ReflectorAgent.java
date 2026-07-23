package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.HookOp;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.models.TextUtils;
import com.novelforge.core.prompt.PromptBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReflectorAgent — converts Observer's extraction into state patch operations.
 * Outputs hookOps (UPSERT/MENTION/RESOLVE/DEFER) and statePatches
 * (characterDelta, worldDelta, timelineDelta).
 */
public class ReflectorAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ReflectorAgent.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private ModelRouter router;
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Override public String name() { return "Reflector"; }
    @Override public String model() { return null; }
    @Override public double temperature() { return 0.3; }

    @Override public void init(ModelRouter router) { this.router = router; }

    @Override
    public PipelineResult execute(PipelineContext context) {
        try {
            log.info("Reflector: generating state patches for chapter {}", context.getBook().nextChapterNumber());

            String observerOutput = context.getObserverOutput();
            if (observerOutput == null || observerOutput.isEmpty()) {
                // Fallback: use chapter draft if observer was skipped
                observerOutput = context.getCurrentChapterDraft();
            }

            List<Map<String, String>> messages = promptBuilder.buildReflectorPrompt(
                    context.getBook(), context.getTruthState(), observerOutput, context.getConfig());

            LlmClient client = router.getClientForAgent(name());
            String modelId = router.getModelForAgent(name());

            String response = client.chatComplete(messages, modelId, temperature(), 1500);

            // Parse LLM response once and reuse the parsed tree
            String json = TextUtils.extractJsonBlock(response);
            com.fasterxml.jackson.databind.JsonNode root = null;
            if (json != null) {
                root = mapper.readTree(json);
            }

            // Parse hookOps + state patches + timeline events from single parsed JSON
            int chapterNum = context.getBook().nextChapterNumber();
            List<HookOp> hookOps = parseHookOps(root, chapterNum);
            context.getTruthState().applyHookOps(hookOps);

            // Apply character and world deltas from the response
            applyStateDeltas(root, context);

            // Add timeline events
            applyTimelineEvents(root, context);

            // Store reflector output in dedicated field
            context.setReflectorOutput(response);
            log.info("Reflector: {} hookOps applied, state patches generated ({})", hookOps.size(), response.length());

            return new PipelineResult(context, response, name());
        } catch (Exception e) {
            log.error("[{}] execute error: {}", name(), e.getMessage(), e);
            return new PipelineResult(name(), "Agent exception: " + e.getMessage());
        }
    }

    /** Parse hookOps from pre-parsed JSON tree */
    private List<HookOp> parseHookOps(com.fasterxml.jackson.databind.JsonNode root, int chapterNum) {
        List<HookOp> ops = new ArrayList<>();
        if (root == null) return ops;
        try {
            JsonNode hookOpsNode = root.get("hookOps");
            if (hookOpsNode == null || !hookOpsNode.isArray()) return ops;

            for (JsonNode opNode : hookOpsNode) {
                HookOp op = new HookOp();
                String typeStr = opNode.get("type").asText();
                // Safe type mapping with fallback (#7 fix)
                HookOp.Type type = switch(typeStr.toUpperCase().replace("-", "_")) {
                    case "UPSERT" -> HookOp.Type.UPSERT;
                    case "MENTION" -> HookOp.Type.MENTION;
                    case "RESOLVE" -> HookOp.Type.RESOLVE;
                    case "DEFER" -> HookOp.Type.DEFER;
                    default -> HookOp.Type.UPSERT; // fallback for unknown types
                };
                op.setType(type);
                op.setHookId(opNode.has("hookId") ? opNode.get("hookId").asText() : "hook-auto-" + ops.size());
                op.setDescription(opNode.has("description") ? opNode.get("description").asText() : "");
                op.setChapterOrigin(opNode.has("chapterOrigin") ? opNode.get("chapterOrigin").asInt() : chapterNum);
                op.setMentionCount(opNode.has("mentionCount") ? opNode.get("mentionCount").asInt() : 1);
                // Normalize priority: accept numeric (1-10→low-high) or string (high/medium/low)
                String priorityStr = opNode.has("priority") ? opNode.get("priority").asText() : "medium";
                op.setPriority(normalizePriority(priorityStr));
                ops.add(op);
            }
        } catch (Exception e) {
            log.warn("Failed to parse hookOps from Reflector output", e);
        }
        return ops;
    }

    /** Apply character deltas to TruthState from pre-parsed JSON tree */
    private void applyStateDeltas(com.fasterxml.jackson.databind.JsonNode root, PipelineContext context) {
        if (root == null) return;
        try {
            JsonNode statePatch = root.get("statePatch");
            if (statePatch == null) return;

            // Character delta
            JsonNode charDelta = statePatch.get("characterDelta");
            if (charDelta != null && charDelta.isArray()) {
                for (JsonNode ch : charDelta) {
                    String name = ch.has("name") ? ch.get("name").asText() : "unknown";
                    context.getTruthState().characters().upsertCharacter(name, ch);
                }
            }

            // World delta — add new locations/items/rules
            JsonNode worldDelta = statePatch.get("worldDelta");
            if (worldDelta != null) {
                JsonNode locations = worldDelta.get("locations");
                if (locations != null && locations.isArray()) {
                    for (JsonNode loc : locations) {
                        context.getTruthState().world().addLocation(loc);
                    }
                }
                JsonNode rules = worldDelta.get("rules");
                if (rules != null && rules.isArray()) {
                    for (JsonNode rule : rules) {
                        context.getTruthState().world().addRule(rule);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to apply state deltas from Reflector output", e);
        }
    }

    /** Add timeline events from pre-parsed JSON tree */
    private void applyTimelineEvents(com.fasterxml.jackson.databind.JsonNode root, PipelineContext context) {
        if (root == null) return;
        try {
            JsonNode statePatch = root.get("statePatch");
            if (statePatch == null) return;

            JsonNode timelineDelta = statePatch.get("timelineDelta");
            if (timelineDelta != null && timelineDelta.isArray()) {
                int chapterNum = context.getBook().nextChapterNumber();
                for (JsonNode event : timelineDelta) {
                    // Robust description extraction: try multiple field names, skip empty
                    String desc = extractEventDescription(event);
                    if (desc == null || desc.trim().isEmpty()) {
                        log.warn("Reflector: skipping timeline event with empty description: {}", event);
                        continue;
                    }
                    context.getTruthState().timeline().addEvent(chapterNum, desc.trim());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to apply timeline events from Reflector output", e);
        }
    }

    /** Normalize priority value: numeric 1-10 maps to low/medium/high, string values normalized. */
    private String normalizePriority(String p) {
        if (p == null || p.trim().isEmpty()) return "medium";
        try {
            int num = Integer.parseInt(p.trim());
            if (num <= 3) return "low";
            if (num <= 6) return "medium";
            return "high";
        } catch (NumberFormatException e) {
            // String value: normalize to standard labels
            String lower = p.trim().toLowerCase();
            if (lower.startsWith("h") || lower.equals("urgent") || lower.equals("critical")) return "high";
            if (lower.startsWith("l") || lower.equals("minor")) return "low";
            return "medium";
        }
    }

    /** Extract description from a timeline event JSON node.
     *  LLM output may use different field names: description, event, text, summary, or just a string value. */
    private String extractEventDescription(JsonNode event) {
        // Try known field names in order of preference
        for (String field : new String[]{"description", "event", "text", "summary"}) {
            if (event.has(field) && !event.get(field).isNull() && !event.get(field).asText().trim().isEmpty()) {
                return event.get(field).asText();
            }
        }
        // Fallback: if the node itself is a text value
        if (event.isTextual() && !event.asText().trim().isEmpty()) {
            return event.asText();
        }
        return null;
    }

    // extractJson moved to TextUtils.extractJsonBlock
}
