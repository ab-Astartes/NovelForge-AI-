package com.novelforge.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AiFingerprint 评分器单测。
 *
 * <p>核心目标：验证评分器具备方向性判别力——AI 腔明显的文本得分应显著高于
 * 人写风格的文本，且输出结构完整、边界安全。</p>
 */
class AiFingerprintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 典型 AI 腔：连接词密集、句式工整、总结升华、句长均匀 */
    private static final String AI_LIKE = """
            值得注意的是，这场战斗不仅仅是力量的较量，更像是命运的安排。
            总而言之，他心中五味杂陈，百感交集。
            因此，他深吸一口气，缓缓闭上了双眼。
            与此同时，空气中弥漫着浓重的血腥气息，气氛凝重到了极点。
            毫无疑问，这是一场不可避免的对决，也是宿命的必然结果。
            众所周知，真正的强者不仅需要强大的实力，而且需要坚定的意志。
            在这个瞬间，时间仿佛静止了，仿佛整个世界都在注视着他。
            总而言之，无论结果如何，他都将义无反顾地走下去。
            此外，他的眼神中闪过一丝决然，仿佛在诉说着某种无声的誓言。
            由此可见，命运的齿轮已经开始转动，一切都已注定。
            事实上，他早已做好了准备，实际上他从未退缩过。
            总的来说，这不仅仅是生死的搏杀，更像是一种信念的碰撞。
            """;

    /** 人写风格：句长反差大、短句冲击、具体动作、少连接词 */
    private static final String HUMAN_LIKE = """
            刀来了。
            他偏头，刀锋擦着耳根过去，削断几缕头发，钉进身后的柱子，嗡嗡直颤。
            没时间想。他一矮身，肩膀顶进对方怀里，两人一起撞翻了桌子，碗碟碎了一地。
            "你疯了？"
            疯？也许吧。他只觉得手心全是汗，握不住刀柄，就反手把刀背磕在对方膝弯上。
            骨头发出的声音让他自己都牙酸。
            那人跪下去，又撑着地要起来。他退了半步，喘。
            屋外雨大，压住了别的所有声音。他听见自己的心跳，一下，一下，撞得肋骨发麻。
            血从袖口往下滴，在手背上洇开一小片。他这才觉出疼。
            死了？没有。指头动了动。
            他一屁股坐在碎瓷片上，笑出声，笑得胸口发闷，最后变成咳。
            算了。他抹了把脸，把刀插回鞘里，站起来，腿软。
            """;

    @Test
    @DisplayName("AI 腔文本得分应显著高于人写文本")
    void aiTextScoresHigherThanHumanText() {
        double aiScore = scoreOf(AI_LIKE);
        double humanScore = scoreOf(HUMAN_LIKE);
        assertTrue(aiScore > humanScore,
                "AI 腔文本得分(" + aiScore + ")应高于人写文本(" + humanScore + ")");
        assertTrue(aiScore - humanScore >= 20,
                "分差应至少 20 分，实际 " + String.format("%.1f", aiScore - humanScore));
    }

    @Test
    @DisplayName("AI 腔文本应被判定为 high 或 medium")
    void aiTextIsFlagged() {
        JsonNode r = AiFingerprint.score(MAPPER, AI_LIKE, null, null);
        String level = r.path("level").asText();
        assertTrue("high".equals(level) || "medium".equals(level),
                "AI 腔文本等级应为 high/medium，实际 " + level);
        assertFalse(r.path("hits").isEmpty(), "应命中具体 AI 腔短语");
    }

    @Test
    @DisplayName("人写文本应被判定为 low 或 medium，且不为 high")
    void humanTextIsNotFlaggedHigh() {
        JsonNode r = AiFingerprint.score(MAPPER, HUMAN_LIKE, null, null);
        assertNotEquals("high", r.path("level").asText(),
                "人写文本不应被判为 high，实际得分 " + r.path("score").asDouble());
    }

    @Test
    @DisplayName("输出结构完整：score/level/dimensions/advice 齐全，7 个维度")
    void outputStructureIsComplete() {
        JsonNode r = AiFingerprint.score(MAPPER, AI_LIKE, null, null);
        assertTrue(r.path("ok").asBoolean(), "ok 应为 true");
        assertTrue(r.path("score").isNumber(), "score 应为数值");
        assertFalse(r.path("level").asText().isEmpty(), "level 不应为空");
        assertEquals(7, r.path("dimensions").size(), "应有 7 个评分维度");
        for (JsonNode d : r.path("dimensions")) {
            assertTrue(d.has("key"), "维度缺少 key");
            assertTrue(d.has("label"), "维度缺少 label");
            assertTrue(d.has("score"), "维度缺少 score");
            assertTrue(d.has("weight"), "维度缺少 weight");
        }
        assertFalse(r.path("advice").isEmpty(), "应给出至少一条建议");
        assertTrue(r.path("stats").path("chars").asInt() > 0, "stats.chars 应大于 0");
    }

    @Test
    @DisplayName("分数恒定落在 0~100 区间")
    void scoreIsAlwaysInRange() {
        for (String t : new String[]{AI_LIKE, HUMAN_LIKE, "短", "。。。。。", ""}) {
            JsonNode r = AiFingerprint.score(MAPPER, t, null, null);
            if (!r.path("ok").asBoolean()) continue;   // 过短文本会被拒绝
            double s = r.path("score").asDouble();
            assertTrue(s >= 0 && s <= 100, "分数越界: " + s);
        }
    }

    @Test
    @DisplayName("过短文本应被拒绝而非崩溃")
    void tooShortTextIsRejected() {
        JsonNode r = AiFingerprint.score(MAPPER, "你好", null, null);
        assertFalse(r.path("ok").asBoolean(), "过短文本应返回 ok=false");
        assertFalse(r.path("error").asText().isEmpty(), "应给出错误说明");

        // null 与纯空白同样应安全返回
        assertFalse(AiFingerprint.score(MAPPER, null, null, null).path("ok").asBoolean());
        assertFalse(AiFingerprint.score(MAPPER, "   \n\n  ", null, null).path("ok").asBoolean());
    }

    @Test
    @DisplayName("额外传入禁用词应能提升命中与得分")
    void extraBannedPhrasesIncreaseHits() {
        String text = "这是一段普通的叙述文字，用来测试自定义禁用词是否生效。"
                + "他在街角站了一会儿，看着来往的人群，心里说不上是什么滋味。"
                + "风从巷口灌进来，吹得招牌吱呀作响，他紧了紧衣领，继续往前走。"
                + "远处传来叫卖声，混着马车的铃铛响，热闹得有些不真实。";
        JsonNode before = AiFingerprint.score(MAPPER, text, null, null);
        JsonNode after = AiFingerprint.score(MAPPER, text, null, Set.of("普通的叙述", "说不上是什么滋味"));
        int hitsBefore = before.path("hits").size();
        int hitsAfter = after.path("hits").size();
        assertTrue(hitsAfter > hitsBefore,
                "传入自定义禁用词后命中数应增加: " + hitsBefore + " -> " + hitsAfter);
    }

    @Test
    @DisplayName("命中明细不应出现重复短语（同一词同时属于禁词表与AI腔表时只列一条）")
    void hitsAreDeduplicated() {
        // 「总而言之」既是内置 AI 腔，又在下面作为自定义禁词传入
        JsonNode r = AiFingerprint.score(MAPPER, AI_LIKE, Set.of("总而言之"), Set.of("总而言之"));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode h : r.path("hits")) {
            String phrase = h.path("phrase").asText();
            assertTrue(seen.add(phrase), "命中明细出现重复短语: " + phrase);
        }
        assertTrue(seen.contains("总而言之"), "应命中「总而言之」");
    }

    @Test
    @DisplayName("标点多样性应基于原文统计，不能恒为 0")
    void punctuationEntropyUsesOriginalText() {
        String varied = "他冲了出去！门在身后重重关上？不，没人追来。"
                + "雨、风、泥水，全糊在脸上；他抹了一把，继续跑……";
        JsonNode r = AiFingerprint.score(MAPPER, varied, null, null);
        JsonNode punct = findDim(r, "punct");
        assertTrue(punct.path("value").asDouble() > 0,
                "含 ！？；…… 的原文标点熵应大于 0，实际 " + punct.path("value").asDouble());
    }

    @Test
    @DisplayName("不可测维度应被排除（weight=0 且 verdict 为 —），不参与总分")
    void unmeasurableDimensionsAreExcluded() {
        // 单段、无情绪词的文本：段落变异与情感峰谷都不可测
        String single = "他推开门，屋里的灯还亮着。桌上摆着两只碗，一双筷子，"
                + "汤已经凉了，浮着一层油花。窗纸被风吹得鼓起来，又落回去。"
                + "他站了一会儿，把门带上，转身走进雨里。";
        JsonNode r = AiFingerprint.score(MAPPER, single, null, null);
        JsonNode para = findDim(r, "paraCV");
        assertEquals(0.0, para.path("weight").asDouble(), "单段文本段落变异应不可测");
        assertEquals("—", para.path("verdict").asText(), "不可测维度 verdict 应为 —");

        JsonNode emo = findDim(r, "emotion");
        assertEquals(0.0, emo.path("weight").asDouble(), "无情绪词时情感峰谷应不可测");

        int measurable = 0;
        for (JsonNode d : r.path("dimensions")) {
            if (d.path("measurable").asBoolean()) measurable++;
        }
        assertEquals(measurable, r.path("measurableDims").asInt(),
                "measurableDims 应与实际可测维度数一致");
        assertTrue(measurable < 7, "本样本应有维度被判为不可测");
    }

    @Test
    @DisplayName("不可测维度不应产生对应的改进建议")
    void unmeasurableDimensionsProduceNoAdvice() {
        // 单段、无情绪词：paraCV 与 emotion 都不可测，不应建议「调整段落/加大情绪落差」
        String single = "他推开门，屋里的灯还亮着。桌上摆着两只碗，一双筷子，"
                + "汤已经凉了，浮着一层油花。窗纸被风吹得鼓起来，又落回去。"
                + "他站了一会儿，把门带上，转身走进雨里。";
        JsonNode r = AiFingerprint.score(MAPPER, single, null, null);
        for (JsonNode a : r.path("advice")) {
            String text = a.asText();
            assertFalse(text.contains("段落切分"), "段落维度不可测，不应给出段落建议：" + text);
            assertFalse(text.contains("情绪落差"), "情绪维度不可测，不应给出情绪建议：" + text);
        }
    }

    private static JsonNode findDim(JsonNode r, String key) {
        for (JsonNode d : r.path("dimensions")) {
            if (key.equals(d.path("key").asText())) return d;
        }
        fail("未找到维度 " + key);
        return null;
    }

    private static double scoreOf(String text) {
        return AiFingerprint.score(MAPPER, text, null, null).path("score").asDouble();
    }
}
