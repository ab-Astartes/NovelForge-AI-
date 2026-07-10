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
            log.error("[{}] execute error: {}", name(), e.getMessage(), e);
            return new PipelineResult(name(), "Agent exception: " + e.getMessage());
        }
    }

    /** Parse LLM's JSON audit output into AuditResult object, then overlay objective checks.
     *  Overall score uses AuditEngine's 33-dimension weights (not simple average).
     *  Rule-engine dimensions override LLM scores for objective metrics. */
    private AuditResult parseAuditResult(String llmOutput, String chapterText) {
        AuditResult result = new AuditResult();
        result.setOverallScore(7.0);
        result.setPass(true);
        result.setCriticalIssues(new java.util.ArrayList<>());
        result.setWarnings(new java.util.ArrayList<>());

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

        // Overlay objective rule-engine checks
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
            // Fill missing dimensions with default 7.0
            for (String dim : AuditEngine.dimensionNames()) {
                scores.putIfAbsent(dim, 7.0);
            }
            // Calculate weighted overall score using AuditEngine's 33-dimension weights
            // (not simple average — each dimension has its own weight)
            double totalWeight = 0, totalScore = 0;
            for (String dim : AuditEngine.dimensionNames()) {
                double weight = AuditEngine.getDimensionWeight(dim);
                double score = scores.getOrDefault(dim, 7.0);
                totalScore += score * weight;
                totalWeight += weight;
            }
            result.setOverallScore(totalWeight > 0 ? totalScore / totalWeight : 7.0);
        } else if (objectiveCheck.getDimensionScores() != null) {
            result.setDimensionScores(objectiveCheck.getDimensionScores());
            result.setOverallScore(objectiveCheck.getOverallScore());
            result.setPass(objectiveCheck.isPass());
        }
        // Add objective issues/warnings
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
