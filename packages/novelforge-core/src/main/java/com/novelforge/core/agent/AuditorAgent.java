package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.audit.AuditEngine;
import com.novelforge.core.models.AuditResult;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.models.TextUtils;
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
    private final AuditEngine auditEngine = new AuditEngine();

    @Override public String name() { return "Auditor"; }
    @Override public String model() { return null; }
    @Override public double temperature() { return 0.2; }

    @Override public void init(ModelRouter router) { this.router = router; }

    @Override
    public PipelineResult execute(PipelineContext context) {
        try {
            log.info("Auditor: running 33-dimension audit on chapter {}", context.getBook().nextChapterNumber());

            String chapterDraft = context.getCurrentChapterDraft();

            if (chapterDraft == null || chapterDraft.isEmpty()) {
                log.warn("Auditor: chapter draft is null/empty, skipping audit");
                AuditResult emptyResult = new AuditResult();
                emptyResult.setOverallScore(0);
                emptyResult.setPass(false);
                emptyResult.setCriticalIssues(java.util.List.of("无章节内容"));
                emptyResult.setWarnings(java.util.List.of());
                context.setAuditResult(emptyResult);
                return new PipelineResult(context, "（空章节，无法审计）", name());
            }

            List<Map<String, String>> messages = promptBuilder.buildAuditorPrompt(
                    context.getBook(), context.getTruthState(), chapterDraft, context.getConfig());

            LlmClient client = router.getClientForAgent(name());
            String modelId = router.getModelForAgent(name());

            String response = client.chatComplete(messages, modelId, temperature(), 3000);

            // Parse audit JSON into AuditResult
            AuditResult auditResult = parseAuditResult(response, chapterDraft);
            context.setAuditResult(auditResult);

            log.info("Auditor: overall score {}, {} critical issues, {} warnings",
                    String.format("%.1f", auditResult.getOverallScore()),
                    auditResult.getCriticalIssues() != null ? auditResult.getCriticalIssues().size() : 0,
                    auditResult.getWarnings() != null ? auditResult.getWarnings().size() : 0);

            return new PipelineResult(context, response, name());
        } catch (Exception e) {
            System.err.println("[Auditor] execute error: " + e.getMessage());
            e.printStackTrace();
            return new PipelineResult(context, "[Error] " + e.getMessage(), name(), true);
        }
    }

    /** Parse LLM's JSON audit output into AuditResult object, then overlay objective checks */
    private AuditResult parseAuditResult(String llmOutput, String chapterText) {
        // Parse LLM JSON audit output into AuditResult
        AuditResult result = new AuditResult();
        result.setOverallScore(7.0); // fallback if parsing fails
        result.setPass(true);
        result.setCriticalIssues(new java.util.ArrayList<>());
        result.setWarnings(new java.util.ArrayList<>());

        // Try to extract JSON from response
        try {
            String json = TextUtils.extractJsonBlock(llmOutput);
            if (json != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

                if (root.has("scores")) {
                    java.util.Map<String, Double> scores = new java.util.LinkedHashMap<>();
                    root.get("scores").fields().forEachRemaining(entry ->
                            scores.put(entry.getKey(), entry.getValue().asDouble()));
                    result.setDimensionScores(scores);

                    // Calculate overall score (simple average of LLM dimensions — rule-engine will be overlaid later)
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

        // Overlay objective rule-engine checks from AuditEngine
        AuditResult objectiveCheck = auditEngine.quickAudit(chapterText);
        Map<String, Double> scores = result.getDimensionScores();
        if (scores != null && !scores.isEmpty()) {
            // Override objective dimensions with rule-engine results (more reliable)
            if (objectiveCheck.getDimensionScores() != null) {
                for (String dim : new String[]{"pacing.sceneLengthBalance", "dialogue.tagVariety",
                        "antiAI.repetitivePatterns", "antiAI.genericExpressions",
                        "antiAI.overlyBalancedStructure"}) {
                    Double objScore = objectiveCheck.getDimensionScores().get(dim);
                    if (objScore != null) {
                        scores.put(dim, objScore);
                    }
                }
            }
            // Recalculate overall score with 60/40 weighting (fixes #8: LLM 60% + rule-engine 40%)
            double llmTotal = 0, ruleTotal = 0;
            int llmCount = 0, ruleCount = 0;
            for (var entry : scores.entrySet()) {
                String dim = entry.getKey();
                double s = entry.getValue();
                if (dim.startsWith("pacing.") || dim.startsWith("dialogue.") || dim.startsWith("world.") || dim.startsWith("outline.") || dim.startsWith("style.") || dim.startsWith("hook.")) {
                    llmTotal += s; llmCount++;
                } else {
                    ruleTotal += s; ruleCount++;
                }
            }
            double llmAvg = llmCount > 0 ? llmTotal / llmCount : 7.0;
            double ruleAvg = ruleCount > 0 ? ruleTotal / ruleCount : 7.0;
            result.setOverallScore(scores.isEmpty() ? 7.0 : (llmAvg * 0.6) + (ruleAvg * 0.4));
        } else if (objectiveCheck.getDimensionScores() != null) {
            // LLM parsing completely failed — use objective results as full fallback
            result.setDimensionScores(objectiveCheck.getDimensionScores());
            result.setOverallScore(objectiveCheck.getOverallScore());
            result.setPass(objectiveCheck.isPass());
        }
        // Add objective issues/warnings to LLM's list
        if (objectiveCheck.getCriticalIssues() != null) {
            result.getCriticalIssues().addAll(objectiveCheck.getCriticalIssues());
            if (!objectiveCheck.getCriticalIssues().isEmpty()) result.setPass(false);
        }
        if (objectiveCheck.getWarnings() != null) {
            result.getWarnings().addAll(objectiveCheck.getWarnings());
        }

        return result;
    }

    // extractJson moved to TextUtils.extractJsonBlock
}
