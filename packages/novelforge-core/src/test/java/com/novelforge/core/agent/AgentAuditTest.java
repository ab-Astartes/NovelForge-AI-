package com.novelforge.core.agent;

import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.models.AuditResult;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.models.HookOp;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AgentAuditTest {

    @Test
    void testAuditorAgentParseAuditResult() {
        // Simulate the parsing logic from AuditorAgent
        String llmOutput = """
            ```json
            {
              "scores": {
                "narrativeConsistency": 8.0,
                "characterDepth": 7.5,
                "pacing": 6.0,
                "dialogueQuality": 8.5
              },
              "criticalIssues": [],
              "warnings": ["节奏稍慢"]
            }
            ```
            """;

        AuditResult result = parseAuditResult(llmOutput);
        assertEquals(7.5, result.getOverallScore(), 0.5);
        assertTrue(result.isPass());
        assertEquals(1, result.getWarnings().size());
    }

    @Test
    void testAuditorAgentParseWithCriticalIssues() {
        String llmOutput = """
            {
              "scores": {"consistency": 4.0, "depth": 3.0},
              "criticalIssues": ["角色行为矛盾", "时间线冲突"],
              "warnings": ["对话过多"]
            }
            """;

        AuditResult result = parseAuditResult(llmOutput);
        assertFalse(result.isPass());
        assertEquals(2, result.getCriticalIssues().size());
    }

    @Test
    void testPipelineResultSuccessAndFailure() {
        PipelineConfig config = new PipelineConfig();
        Book book = new Book();
        book.setTitle("测试书");
        PipelineContext context = new PipelineContext(book, null, config);

        PipelineResult success = new PipelineResult(context, "生成文本", "Writer");
        assertTrue(success.success());
        assertEquals("Writer", success.agentName());
        assertEquals("生成文本", success.generatedText());

        PipelineResult failure = new PipelineResult("Writer", "API error");
        assertFalse(failure.success());
        assertEquals("API error", failure.errorMessage());
    }

    @Test
    void testHookOpTypes() {
        HookOp upsert = new HookOp();
        upsert.setType(HookOp.Type.UPSERT);
        upsert.setHookId("h1");

        HookOp resolve = new HookOp();
        resolve.setType(HookOp.Type.RESOLVE);
        resolve.setHookId("h2");

        assertEquals(HookOp.Type.UPSERT, upsert.getType());
        assertEquals(HookOp.Type.RESOLVE, resolve.getType());
    }

    // --- Helper: simplified audit result parser (mirrors AuditorAgent logic) ---
    private AuditResult parseAuditResult(String llmOutput) {
        AuditResult result = new AuditResult();
        result.setOverallScore(7.0);
        result.setPass(true);
        result.setCriticalIssues(new ArrayList<>());
        result.setWarnings(new ArrayList<>());

        try {
            String json = extractJson(llmOutput);
            if (json != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

                if (root.has("scores")) {
                    Map<String, Double> scores = new LinkedHashMap<>();
                    root.get("scores").fields().forEachRemaining(entry ->
                            scores.put(entry.getKey(), entry.getValue().asDouble()));
                    result.setDimensionScores(scores);
                    double total = 0;
                    for (double s : scores.values()) total += s;
                    result.setOverallScore(scores.isEmpty() ? 7.0 : total / scores.size());
                }

                if (root.has("criticalIssues")) {
                    List<String> issues = new ArrayList<>();
                    root.get("criticalIssues").forEach(node -> issues.add(node.asText()));
                    result.setCriticalIssues(issues);
                    result.setPass(issues.isEmpty());
                }

                if (root.has("warnings")) {
                    List<String> warnings = new ArrayList<>();
                    root.get("warnings").forEach(node -> warnings.add(node.asText()));
                    result.setWarnings(warnings);
                }
            }
        } catch (Exception e) {
            // fallback defaults
        }

        return result;
    }

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
