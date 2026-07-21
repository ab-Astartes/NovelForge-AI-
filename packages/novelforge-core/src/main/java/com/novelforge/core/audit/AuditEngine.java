package com.novelforge.core.audit;

import com.novelforge.core.models.AuditResult;
import com.novelforge.core.models.TextUtils;
import com.novelforge.core.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * AuditEngine — performs 33-dimension quality checks on chapter text.
 * Two modes:
 * 1. Full audit: LLM-based subjective scoring + rules engine objective checks
 * 2. Quick audit: rules-only, no LLM call (fast, deterministic)
 */
public class AuditEngine {

    private static final Logger log = LoggerFactory.getLogger(AuditEngine.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // 33 audit dimensions (name → weight) — unmodifiable after static init (🟢-3)
    private static final Map<String, Double> DIMENSIONS;

    static {
        LinkedHashMap<String, Double> dims = new LinkedHashMap<>();
        // Pacing (5)
        dims.put("pacing.flow", 1.0);
        dims.put("pacing.variation", 0.8);
        dims.put("pacing.tensionCurve", 1.0);
        dims.put("pacing.sceneLengthBalance", 0.7);
        dims.put("pacing.transitionSmoothness", 0.7);
        // Dialogue (5)
        dims.put("dialogue.naturalness", 1.0);
        dims.put("dialogue.characterVoice", 1.0);
        dims.put("dialogue.subtext", 0.8);
        dims.put("dialogue.tagVariety", 0.6);
        dims.put("dialogue.actionBeats", 0.7);
        // World-building (5)
        dims.put("world.consistency", 1.0);
        dims.put("world.detailLevel", 0.8);
        dims.put("world.sensoryImmersion", 0.8);
        dims.put("world.powerSystemLogic", 0.9);
        dims.put("world.settingFreshness", 0.7);
        // Outline adherence (5)
        dims.put("outline.chapterIntentMatch", 1.0);
        dims.put("outline.hookFulfillment", 1.0);
        dims.put("outline.progressionDirection", 0.9);
        dims.put("outline.characterArcAlignment", 0.8);
        dims.put("outline.plotTwistSetup", 0.7);
        // Style consistency (5)
        dims.put("style.vocabularyConsistency", 0.7);
        dims.put("style.sentenceVariety", 0.7);
        dims.put("style.toneConsistency", 0.8);
        dims.put("style.genreVoice", 0.8);
        dims.put("style.descriptionBalance", 0.7);
        // Hook health (5)
        dims.put("hook.mustAdvanceHandled", 1.0);
        dims.put("hook.newHooksPlanted", 0.8);
        dims.put("hook.staleDebt", 1.0);
        dims.put("hook.burstDetection", 0.9);
        dims.put("hook.resolutionQuality", 0.9);
        // Anti-AI detection (3)
        dims.put("antiAI.repetitivePatterns", 1.0);
        dims.put("antiAI.genericExpressions", 0.9);
        dims.put("antiAI.overlyBalancedStructure", 0.8);
        DIMENSIONS = Collections.unmodifiableMap(dims);
    }

    // Repetitive phrase patterns for anti-AI detection
    private static final Pattern[] REPETITIVE_PATTERNS = {
        Pattern.compile("然而.{0,10}却"),
        Pattern.compile("不禁.{0,15}起来"),
        Pattern.compile("心中一动"),
        Pattern.compile("淡淡地说"),
        Pattern.compile("嘴角微微上扬"),
        Pattern.compile("目光微微一闪"),
        Pattern.compile("深深地看了"),
        Pattern.compile("不由自主地"),
        Pattern.compile("仿佛.{0,10}一般"),
        Pattern.compile("犹如.{0,10}似的")
    };

    // Generic expression patterns
    private static final Pattern[] GENERIC_PATTERNS = {
        Pattern.compile("就这样"),
        Pattern.compile("于是"),
        Pattern.compile("然后"),
        Pattern.compile("当然"),
        Pattern.compile("事实上"),
        Pattern.compile("总而言之"),
        Pattern.compile("毫无疑问"),
        Pattern.compile("显而易见")
    };

    /**
     * Run full 33-dimension audit using LLM for subjective dimensions.
     * LLM call + rules engine for objective ones.
     */
    public AuditResult audit(String chapterText, String chapterIntent, String genreRules, LlmClient client, String modelId) {
        // 1. Run LLM-based subjective scoring
        Map<String, Double> scores = new LinkedHashMap<>();

        // Use LLM for subjective dimensions
        String llmPrompt = buildAuditPrompt(chapterText, chapterIntent, genreRules);
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", "你是质量审计引擎，对文本进行客观评分。"),
            Map.of("role", "user", "content", llmPrompt)
        );

        try {
            String llmResponse = client.chatComplete(messages, modelId, 0.2, 2000);
            String json = TextUtils.extractJsonBlock(llmResponse);
            if (json != null) {
                JsonNode root = mapper.readTree(json);
                JsonNode scoresNode = root.get("scores");
                if (scoresNode != null) {
                    scoresNode.fields().forEachRemaining(e -> {
                        if (DIMENSIONS.containsKey(e.getKey())) {
                            double val = e.getValue().asDouble();
                            // Guard against NaN/Infinity from malformed LLM output
                            if (!Double.isFinite(val)) val = 7.0;
                            scores.put(e.getKey(), Math.min(10.0, Math.max(0.0, val)));
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("LLM audit call failed, falling back to rules-only", e);
        }

        // 2. Run objective rules checks (override or fill missing)
        Map<String, Double> objectiveScores = runObjectiveChecks(chapterText);
        objectiveScores.forEach((k, v) -> {
            if (!scores.containsKey(k)) scores.put(k, v);
        });

        // 3. Fill any remaining dimensions with default 7.0
        for (String dim : DIMENSIONS.keySet()) {
            scores.putIfAbsent(dim, 7.0);
        }

        // 4. Compute weighted overall score
        double totalWeight = 0;
        double totalScore = 0;
        for (Map.Entry<String, Double> e : DIMENSIONS.entrySet()) {
            double weight = e.getValue();
            double score = scores.getOrDefault(e.getKey(), 7.0);
            if (!Double.isFinite(score)) score = 7.0;
            totalScore += score * weight;
            totalWeight += weight;
        }
        double overallScore = totalWeight > 0 ? totalScore / totalWeight : 7.0;
        if (!Double.isFinite(overallScore)) overallScore = 7.0;

        // 5. Flag critical issues (score < 5.0)
        List<String> criticalIssues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (e.getValue() < 5.0) {
                criticalIssues.add(e.getKey() + " 评分过低: " + String.format("%.1f", e.getValue()));
            } else if (e.getValue() < 6.5) {
                warnings.add(e.getKey() + " 评分偏低: " + String.format("%.1f", e.getValue()));
            }
        }

        AuditResult result = new AuditResult();
        result.setDimensionScores(scores);
        result.setOverallScore(overallScore);
        result.setCriticalIssues(criticalIssues);
        result.setWarnings(warnings);
        result.setPass(criticalIssues.isEmpty() && overallScore >= 7.0);
        // 🔴-6 fix: guard NaN/Infinity

        log.info("AuditEngine: overall={}/10, critical={}, warnings={}",
                String.format("%.1f", overallScore), criticalIssues.size(), warnings.size());

        return result;
    }

    /**
     * Quick structural audit — no LLM, fast deterministic checks.
     * Only checks objective dimensions: scene balance, dialogue tags, repetitive patterns.
     */
    public AuditResult quickAudit(String chapterText) {
        Map<String, Double> scores = runObjectiveChecks(chapterText);

        // Fill remaining with default 7.0 (can't check without LLM)
        for (String dim : DIMENSIONS.keySet()) {
            scores.putIfAbsent(dim, 7.0);
        }

        // Weighted overall
        double totalWeight = 0, totalScore = 0;
        for (Map.Entry<String, Double> e : DIMENSIONS.entrySet()) {
            double s = scores.getOrDefault(e.getKey(), 7.0);
            if (!Double.isFinite(s)) s = 7.0;
            totalScore += s * e.getValue();
            totalWeight += e.getValue();
        }

        List<String> criticalIssues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (e.getValue() < 5.0) criticalIssues.add(e.getKey() + ": " + String.format("%.1f", e.getValue()));
            else if (e.getValue() < 6.5) warnings.add(e.getKey() + ": " + String.format("%.1f", e.getValue()));
        }

        AuditResult result = new AuditResult();
        result.setDimensionScores(scores);
        double oScore = totalWeight > 0 ? totalScore / totalWeight : 7.0;
        if (!Double.isFinite(oScore)) oScore = 7.0;
        result.setOverallScore(oScore);
        result.setCriticalIssues(criticalIssues);
        result.setWarnings(warnings);
        result.setPass(criticalIssues.isEmpty() && oScore >= 7.0);  // 🔴-6 + 🟡-3: add overallScore check
        return result;
    }

    /** Run objective (rule-based, no LLM) checks */
    private Map<String, Double> runObjectiveChecks(String text) {
        Map<String, Double> scores = new LinkedHashMap<>();

        // --- Pacing checks ---
        // Scene length balance: check paragraph length variation
        String[] paragraphs = text.split("\n+");
        if (paragraphs.length > 3) {
            double avgLen = Arrays.stream(paragraphs).mapToDouble(p -> p.length()).average().orElse(100);
            double variance = Arrays.stream(paragraphs).mapToDouble(p -> Math.abs(p.length() - avgLen)).average().orElse(50);
            // Good variance = 0.3-0.5 of avg length
            double balanceScore = Math.min(10, Math.max(0, 5 + (variance / avgLen - 0.3) * 10));
            scores.put("pacing.sceneLengthBalance", balanceScore);
        } else {
            scores.put("pacing.sceneLengthBalance", 5.0); // too few paragraphs
        }

        // --- Dialogue checks ---
        // Tag variety: check how many different dialogue tags are used
        Set<String> tags = new HashSet<>();
        Matcher tagMatcher = Pattern.compile("(说道|道|喊道|笑道|冷哼|低声|怒道|轻声|吼道|叹道|问道|答道|沉声|厉声|柔声)").matcher(text);
        while (tagMatcher.find()) tags.add(tagMatcher.group(1));
        scores.put("dialogue.tagVariety", Math.min(10, tags.size() * 1.5));

        // --- Anti-AI checks ---
        // Repetitive patterns
        int repetitiveHits = 0;
        for (Pattern p : REPETITIVE_PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) repetitiveHits++;
        }
        int textLen = text.length();
        // Allow ~1 per 2000 chars
        double repScore = Math.max(0, 10 - (repetitiveHits / Math.max(1, textLen / 2000)) * 3);
        scores.put("antiAI.repetitivePatterns", repScore);

        // Generic expressions
        int genericHits = 0;
        for (Pattern p : GENERIC_PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) genericHits++;
        }
        double genericScore = Math.max(0, 10 - (genericHits / Math.max(1, textLen / 3000)) * 2);
        scores.put("antiAI.genericExpressions", genericScore);

        // Overly balanced structure: check if paragraph lengths are too uniform
        if (paragraphs.length > 5) {
            double stdDev = calculateStdDev(paragraphs);
            double balancedScore = stdDev < 20 ? 4.0 : (stdDev < 40 ? 7.0 : 9.0);
            scores.put("antiAI.overlyBalancedStructure", balancedScore);
        }

        return scores;
    }

    /** Build LLM audit prompt */
    private String buildAuditPrompt(String text, String intent, String genreRules) {
        return String.format("""
            请对以下章节文本进行质量评分。每个维度0-10分。
            
            章节意图: %s
            题材规则: %s
            
            章节文本 (前6000字):
            %s
            
            输出JSON: { "scores": { "dimension.name": score }, "criticalIssues": [...], "warnings": [...] }
            """,
                intent != null ? intent : "（未指定）",
                genreRules != null ? genreRules : "（未指定）",
                text.length() > 6000 ? text.substring(0, 6000) : text
        );
    }

    /** Calculate standard deviation of paragraph lengths */
    private double calculateStdDev(String[] paragraphs) {
        double mean = Arrays.stream(paragraphs).mapToDouble(p -> p.length()).average().orElse(0);
        double variance = Arrays.stream(paragraphs).mapToDouble(p -> Math.pow(p.length() - mean, 2)).average().orElse(0);
        return Math.sqrt(variance);
    }

    // extractJson moved to TextUtils.extractJsonBlock

    /** Get dimension names */
    public static String[] dimensionNames() {
        return DIMENSIONS.keySet().toArray(new String[0]);
    }

    /** Get weight for a specific dimension */
    public static double getDimensionWeight(String dim) {
        return DIMENSIONS.getOrDefault(dim, 0.7); // default weight for unknown dimensions
    }
}
