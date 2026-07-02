package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.HookOp;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
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

        // Parse response into HookOp list + state patches, then apply
        List<HookOp> hookOps = parseHookOps(response);
        context.getTruthState().applyHookOps(hookOps);

        // Apply character and world deltas from the response
        applyStateDeltas(response, context);

        // Add timeline events
        applyTimelineEvents(response, context);

        log.info("Reflector: {} hookOps applied, state patches generated ({})", hookOps.size(), response.length());

        return new PipelineResult(context, response, name());
    }

    /** Parse hookOps from LLM JSON response */
    private List<HookOp> parseHookOps(String llmOutput) {
        List<HookOp> ops = new ArrayList<>();
        try {
            String json = extractJson(llmOutput);
            if (json == null) return ops;

            JsonNode root = mapper.readTree(json);
            JsonNode hookOpsNode = root.get("hookOps");
            if (hookOpsNode == null || !hookOpsNode.isArray()) return ops;

            for (JsonNode opNode : hookOpsNode) {
                HookOp op = new HookOp();
                String typeStr = opNode.get("type").asText();
                op.setType(HookOp.Type.valueOf(typeStr.toUpperCase().replace("-", "_")));
                op.setHookId(opNode.has("hookId") ? opNode.get("hookId").asText() : "hook-auto-" + ops.size());
                op.setDescription(opNode.has("description") ? opNode.get("description").asText() : "");
                op.setChapterOrigin(opNode.has("chapterOrigin") ? opNode.get("chapterOrigin").asInt() : 0);
                op.setMentionCount(opNode.has("mentionCount") ? opNode.get("mentionCount").asInt() : 1);
                op.setPriority(opNode.has("priority") ? opNode.get("priority").asText() : "medium");
                ops.add(op);
            }
        } catch (Exception e) {
            log.warn("Failed to parse hookOps from Reflector output", e);
        }
        return ops;
    }

    /** Apply character deltas to TruthState */
    private void applyStateDeltas(String llmOutput, PipelineContext context) {
        try {
            String json = extractJson(llmOutput);
            if (json == null) return;

            JsonNode root = mapper.readTree(json);
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

    /** Add timeline events from Reflector output */
    private void applyTimelineEvents(String llmOutput, PipelineContext context) {
        try {
            String json = extractJson(llmOutput);
            if (json == null) return;

            JsonNode root = mapper.readTree(json);
            JsonNode statePatch = root.get("statePatch");
            if (statePatch == null) return;

            JsonNode timelineDelta = statePatch.get("timelineDelta");
            if (timelineDelta != null && timelineDelta.isArray()) {
                int chapterNum = context.getBook().nextChapterNumber();
                for (JsonNode event : timelineDelta) {
                    String desc = event.has("description") ? event.get("description").asText() : event.asText();
                    context.getTruthState().timeline().addEvent(chapterNum, desc);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to apply timeline events from Reflector output", e);
        }
    }

    /** Extract JSON block from LLM output */
    private String extractJson(String text) {
        int start = text.indexOf("```json");
        if (start >= 0) {
            int contentStart = text.indexOf('\n', start) + 1;
            int end = text.indexOf("```", contentStart);
            if (end > contentStart) return text.substring(contentStart, end).trim();
        }
        int jsonStart = text.indexOf('{');
        int jsonEnd = text.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) return text.substring(jsonStart, jsonEnd + 1);
        return null;
    }
}
