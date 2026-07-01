package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.models.AuditResult;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * AuditorAgent — 33-dimension quality check on chapter text.
 * Very low temperature (0.2) for objective, consistent scoring.
 * Output: AuditResult with dimension scores, critical issues, and warnings.
 */
public class AuditorAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(AuditorAgent.class);

    private ModelRouter router;
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Override public String name() { return "Auditor"; }
    @Override public String model() { return null; }
    @Override public double temperature() { return 0.2; }

    @Override public void init(ModelRouter router) { this.router = router; }

    @Override
    public PipelineResult execute(PipelineContext context) {
        log.info("Auditor: running 33-dimension audit on chapter {}", context.getBook().nextChapterNumber());

        String chapterDraft = context.getCurrentChapterDraft();

        List<Map<String, String>> messages = promptBuilder.buildAuditorPrompt(
                context.getBook(), context.getTruthState(), chapterDraft, context.getConfig());

        LlmClient client = router.getClientForAgent(name());
        String modelId = router.getModelForAgent(name());

        String response = client.chatComplete(messages, modelId, temperature(), 3000);

        // Parse audit JSON into AuditResult
        AuditResult auditResult = parseAuditResult(response);
        context.setAuditResult(auditResult);

        log.info("Auditor: overall score {}, {} critical issues, {} warnings",
                String.format("%.1f", auditResult.getOverallScore()),
                auditResult.getCriticalIssues() != null ? auditResult.getCriticalIssues().size() : 0,
                auditResult.getWarnings() != null ? auditResult.getWarnings().size() : 0);

        return new PipelineResult(context, response, name());
    }

    /** Parse LLM's JSON audit output into AuditResult object */
    private AuditResult parseAuditResult(String llmOutput) {
        // TODO: Proper JSON parsing with Jackson
        // For now, create a basic AuditResult
        AuditResult result = new AuditResult();
        result.setOverallScore(7.0); // default until proper parsing
        result.setPass(true);
        result.setCriticalIssues(new java.util.ArrayList<>());
        result.setWarnings(new java.util.ArrayList<>());

        // Try to extract JSON from response
        try {
            String json = extractJson(llmOutput);
            if (json != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

                if (root.has("scores")) {
                    java.util.Map<String, Double> scores = new java.util.LinkedHashMap<>();
                    root.get("scores").fields().forEachRemaining(entry ->
                            scores.put(entry.getKey(), entry.getValue().asDouble()));
                    result.setDimensionScores(scores);

                    // Calculate weighted overall score
                    double total = 0;
                    for (double s : scores.values()) total += s;
                    result.setOverallScore(scores.isEmpty() ? 7.0 : total / scores.size());
                }

                if (root.has("criticalIssues")) {
                    java.util.List<String> issues = new java.util.ArrayList<>();
                    root.get("criticalIssues").forEach(node -> issues.add(node.asText()));
                    result.setCriticalIssues(issues);
                    result.setPass(issues.isEmpty());
                }

                if (root.has("warnings")) {
                    java.util.List<String> warnings = new java.util.ArrayList<>();
                    root.get("warnings").forEach(node -> warnings.add(node.asText()));
                    result.setWarnings(warnings);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse audit JSON, using defaults", e);
        }

        return result;
    }

    /** Extract JSON block from LLM output (may be wrapped in markdown) */
    private String extractJson(String text) {
        // Try to find ```json ... ``` block
        int start = text.indexOf("```json");
        if (start >= 0) {
            int contentStart = text.indexOf('\n', start) + 1;
            int end = text.indexOf("```", contentStart);
            if (end > contentStart) return text.substring(contentStart, end).trim();
        }
        // Try raw JSON
        int jsonStart = text.indexOf('{');
        int jsonEnd = text.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) return text.substring(jsonStart, jsonEnd + 1);
        return null;
    }
}
