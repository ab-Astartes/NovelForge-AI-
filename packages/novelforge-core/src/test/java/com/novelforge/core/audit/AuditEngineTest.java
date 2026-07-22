package com.novelforge.core.audit;

import com.novelforge.core.models.AuditResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditEngineTest {

    private final AuditEngine engine = new AuditEngine();

    @Test
    void testDimensionNamesCount() {
        String[] dims = AuditEngine.dimensionNames();
        assertEquals(33, dims.length);
    }

    @Test
    void testDimensionWeightKnownDimension() {
        // pacing.flow has weight 1.0
        assertEquals(1.0, AuditEngine.getDimensionWeight("pacing.flow"));
    }

    @Test
    void testDimensionWeightUnknownDimension() {
        // Unknown dimension returns default 0.7
        assertEquals(0.7, AuditEngine.getDimensionWeight("unknown.dim"));
    }

    @Test
    void testQuickAudit_shortText() {
        AuditResult result = engine.quickAudit("短文本");
        assertNotNull(result);
        // Short text → low scene balance score
        assertTrue(result.getOverallScore() > 0);
        assertNotNull(result.getDimensionScores());
        assertEquals(33, result.getDimensionScores().size());
    }

    @Test
    void testQuickAudit_richText() {
        // Create text with varied paragraphs, dialogue tags, no repetitive patterns
        String text = buildGoodChapterText();
        AuditResult result = engine.quickAudit(text);
        assertNotNull(result);
        assertTrue(result.getOverallScore() > 5.0); // should score decently
        // Check dimension scores exist
        assertTrue(result.getDimensionScores().containsKey("pacing.sceneLengthBalance"));
        assertTrue(result.getDimensionScores().containsKey("dialogue.tagVariety"));
        assertTrue(result.getDimensionScores().containsKey("antiAI.repetitivePatterns"));
    }

    @Test
    void testQuickAudit_repetitivePatterns() {
        // Text with many repetitive patterns
        String text = "然而他却不禁笑了起来。心中一动，淡淡地说了一句。嘴角微微上扬，目光微微一闪。" +
                      "深深地看了他一眼，不由自主地走了。仿佛梦境一般，犹如幻影似的。" +
                      "然而他却不禁叹息起来。心中一动，淡淡地说了一句。" +
                      "就这样结束了。于是他走了。然后回来了。当然不行。事实上是对的。" +
                      "总而言之好。毫无疑问错。显而易见。";
        AuditResult result = engine.quickAudit(text);
        // Repetitive + generic patterns should score low
        Double repScore = result.getDimensionScores().get("antiAI.repetitivePatterns");
        assertNotNull(repScore);
        assertTrue(repScore < 8.0, "Repetitive patterns should score low: " + repScore);

        Double genericScore = result.getDimensionScores().get("antiAI.genericExpressions");
        assertNotNull(genericScore);
        assertTrue(genericScore < 8.0, "Generic expressions should score low: " + genericScore);
    }

    @Test
    void testQuickAudit_noCriticalIssuesForGoodText() {
        String text = buildGoodChapterText();
        AuditResult result = engine.quickAudit(text);
        // Good text should not have many critical issues (score < 5.0)
        int criticalCount = result.getCriticalIssues() != null ? result.getCriticalIssues().size() : 0;
        assertTrue(criticalCount <= 5, "Good text shouldn't have many critical issues: " + criticalCount);
    }

    @Test
    void testQuickAudit_overallScoreIsFinite() {
        // Regression test for NaN/Infinity bug
        String text = "测试内容";
        AuditResult result = engine.quickAudit(text);
        assertTrue(Double.isFinite(result.getOverallScore()));
    }

    @Test
    void testDialogueTagVariety() {
        // Text with varied dialogue tags
        String text = "他说道一句话。她笑道另一句。老者叹道感慨。少年喊道兴奋。" +
                      "女子轻声低语。男子沉声告诫。师傅厉声训斥。孩童柔声请求。" +
                      "他问道疑惑。她答道明白。";
        AuditResult result = engine.quickAudit(text);
        Double tagScore = result.getDimensionScores().get("dialogue.tagVariety");
        assertNotNull(tagScore);
        assertTrue(tagScore >= 6.0, "Varied tags should score well: " + tagScore);
    }

    // --- Helper ---
    private String buildGoodChapterText() {
        StringBuilder sb = new StringBuilder();
        sb.append("夜色深沉，城墙上火光摇曳。\n\n");
        sb.append("守卫低声说道：「注意前方动静。」另一名守卫沉声回答：「已经确认，敌军不会今夜进攻。」\n\n");
        sb.append("长街尽头，一名老者叹道：「岁月不饶人啊。」身旁的少年笑道：「师父，您还硬朗得很呢！」\n\n");
        sb.append("城东集市热闹非凡。商贩喊道：「新鲜果品，一文钱一斤！」行人轻声议论着今日物价。\n\n");
        sb.append("月光下，剑客厉声道：「拔剑吧。」对手柔声回应：「何必呢，我们并无仇怨。」\n\n");
        sb.append("远山隐约传来笛声，悠扬婉转，仿佛在诉说一段被遗忘的往事。\n\n");
        sb.append("书房内灯火通明。学者问道：「这段史料出处何处？」同僚答道：「出自南朝旧档，经三次校勘无误。」\n\n");
        sb.append("秋风拂过落叶沙沙作响，像是大自然在低语告别。街道上行人匆匆，各怀心事。\n\n");
        sb.append("酒肆里喧嚣四起。醉客怒道：「这酒掺了水！」掌柜连忙赔笑：「客官息怒，换一壶好酒。」\n\n");
        sb.append("晨曦初现，雾气渐散。远方传来号角声，那是出发的信号。\n\n");
        return sb.toString();
    }
}
