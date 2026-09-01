package com.novelforge.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 痕迹检测评分器（零 LLM 成本，纯统计指纹）。
 *
 * <p>与 {@code /api/deai/apply} 的关系：<b>apply 是「改」，score 是「测」</b>。
 * apply 负责把文本改写得更像人写，score 负责在改写前后给出可量化的 AI 概率分，
 * 让读者能判断「有没有改干净」，也能单独用于验收外来稿件。</p>
 *
 * <p>评分基于 7 个维度的统计指纹，加权得到 0~100 的 AI 可能性分数：</p>
 * <ol>
 *   <li>句长变异系数 —— 人写小说句长忽长忽短，AI 偏均匀</li>
 *   <li>AI 腔模式命中密度 —— 复用去AI配置里的 aiTellPatterns / bannedPhrases</li>
 *   <li>连接词密度 —— AI 偏爱「然而 / 因此 / 此外 / 值得注意的是」</li>
 *   <li>句式重复率 —— 相邻/同类句子开头雷同</li>
 *   <li>段落长度变异 —— AI 段落长度趋同</li>
 *   <li>句末标点多样性 —— AI 几乎只用句号</li>
 *   <li>情感均匀度 —— AI 把情绪摊平，人写有峰谷</li>
 * </ol>
 *
 * <p>注意：这是<b>启发式</b>评分，用于辅助判断与前后对比，不能作为唯一裁决依据。
 * 短文本（&lt; 200 字）样本不足，会标记为 lowConfidence。</p>
 */
public final class AiFingerprint {

    private AiFingerprint() {}

    // ===================== 词表 =====================

    /** 连接词/过渡词：AI 显著高发 */
    private static final Set<String> CONNECTIVES = setOf(
            "然而", "因此", "此外", "同时", "不仅", "而且", "尽管", "总之", "首先", "其次",
            "最后", "另外", "不过", "于是", "所以", "但是", "并且", "从而", "与此同时",
            "值得一提", "值得注意的是", "需要注意", "总而言之", "综上所述", "总的来说",
            "一方面", "另一方面", "在这种情况下", "正因如此", "由此可见", "换句话说",
            "事实上", "实际上", "显然", "毫无疑问", "不可否认", "众所周知");

    /** 内置 AI 腔模式（配置里没有时的兜底） */
    private static final Set<String> DEFAULT_TELLS = setOf(
            "值得注意的是", "总而言之", "综上所述", "不可否认", "众所周知", "毫无疑问",
            "在这个瞬间", "在这一刻", "仿佛整个世界", "仿佛整个", "时间仿佛静止",
            "心中五味杂陈", "百感交集", "如释重负", "豁然开朗", "若有所思",
            "意味深长", "深吸一口气", "微微一笑", "眼中闪过一丝", "嘴角勾起",
            "空气中弥漫着", "气氛凝重", "沉默片刻", "陷入了沉思",
            "不仅...而且", "既是...也是", "与其说...不如说",
            "这不仅仅是", "更像是一种", "仿佛在诉说着", "无声地诉说着",
            "命运的齿轮", "宿命", "注定", "冥冥之中", "仿佛命中注定");

    /** 高张力情绪词（与 TensionBuilder 同源，用于情感峰谷检测） */
    private static final Set<String> EMOTION_WORDS = setOf(
            "惊恐", "恐惧", "愤怒", "暴怒", "震怒", "绝望", "疯狂", "狰狞", "颤抖", "窒息",
            "心悸", "惊骇", "骇然", "大惊", "失色", "惊呼", "惨烈", "凄厉", "狂喜", "激动",
            "紧张", "急促", "慌乱", "惊疑", "凛然", "杀意", "寒意", "压迫", "沉重",
            "喜悦", "悲伤", "痛苦", "怨恨", "嫉妒", "懊悔", "羞愧", "震惊");

    private static final Pattern SENT_SPLIT = Pattern.compile("[。！？!?；;…]+|[\\n]+");
    private static final Pattern PARA_SPLIT = Pattern.compile("\\n\\s*\\n|\\n");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 低于此字数样本不足，结论仅供参考 */
    private static final int MIN_CONFIDENT_CHARS = 200;

    // ===================== 主入口 =====================

    /**
     * 对文本做 AI 痕迹评分。
     *
     * @param mapper     Jackson mapper
     * @param text       待检测文本
     * @param extraTells 额外的 AI 腔模式（来自去AI配置的 aiTellPatterns），可为 null
     * @param banned     额外的禁用短语（来自去AI配置的 bannedPhrases），可为 null
     * @return 评分结果
     */
    public static ObjectNode score(ObjectMapper mapper, String text,
                                   Set<String> extraTells, Set<String> banned) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);

        String body = stripMarkdown(text == null ? "" : text);
        int chars = countChinese(body);

        if (chars < 20) {
            resp.put("ok", false);
            resp.put("error", "文本过短（少于 20 个中文字），无法给出有意义的评分");
            return resp;
        }

        // —— 基础统计 ——
        List<String> sentences = splitSentences(body);
        List<String> paragraphs = splitParagraphs(body);

        double[] sentLens = sentences.stream().mapToDouble(AiFingerprint::countChineseAsDouble).toArray();
        double[] paraLens = paragraphs.stream().mapToDouble(AiFingerprint::countChineseAsDouble)
                .filter(v -> v > 0).toArray();

        double sentMean = mean(sentLens);
        double sentStd = std(sentLens, sentMean);
        double sentCV = sentMean > 0 ? sentStd / sentMean : 0;

        double paraMean = mean(paraLens);
        double paraStd = std(paraLens, paraMean);
        double paraCV = paraMean > 0 ? paraStd / paraMean : 0;

        // —— 命中检测 ——
        Set<String> tellSet = new HashSet<>(DEFAULT_TELLS);
        if (extraTells != null) tellSet.addAll(nonEmpty(extraTells));
        Map<String, Integer> tellHits = countPhrases(body, tellSet);

        Map<String, Integer> bannedHits = new HashMap<>();
        if (banned != null && !banned.isEmpty()) bannedHits = countPhrases(body, nonEmpty(banned));

        int tellCount = sum(tellHits);
        int bannedCount = sum(bannedHits);
        double tellDensity = per1000(tellCount + bannedCount, chars);

        double connCount = countHits(body, CONNECTIVES);
        double connDensity = per1000(connCount, chars);

        double repeatRate = openingRepeatRate(sentences);
        // 🟢 修复：标点熵必须在原文上算——切句时标点已被分割符吃掉，
        //    在切分后的文本上统计恒为 0，会把所有文本都误判成「标点单一」。
        double punctEntropy = punctuationEntropy(body);
        boolean punctMeasurable = punctuationTotal(body) >= 5;
        double emotionStd = emotionVolatility(paragraphs);
        // 🟢 修复：段落不足或全文几乎没有情绪词时，标准差恒为 0，
        //    此前会被当成「情绪被摊平=AI」打满分，属误判，故标记为不可测。
        boolean emotionMeasurable = paragraphs.size() >= 3 && totalEmotionHits(paragraphs) >= 3;

        // —— 逐维打分（不可测的维度权重记为 0，最后按可测权重归一化）——
        ArrayNode dims = mapper.createArrayNode();
        dims.add(dim(mapper, "sentCV", "句长变异", sentCV, fmt2(sentCV), 0.20,
                range(sentCV, 0.35, 0.85, true), sentences.size() >= 4,
                "人写的句子忽长忽短；AI 倾向于把每句都写得差不多长。"));
        dims.add(dim(mapper, "tells", "AI腔命中", tellDensity, fmt2(tellDensity) + " 处/千字", 0.22,
                range(tellDensity, 0.5, 6.0, false), true,
                "统计「值得注意的是 / 总而言之 / 仿佛整个…」这类高频 AI 腔。"));
        dims.add(dim(mapper, "connective", "连接词密度", connDensity, fmt2(connDensity) + " 处/千字", 0.18,
                range(connDensity, 1.0, 12.0, false), true,
                "AI 偏爱用「然而 / 因此 / 此外」串联，密度显著高于口语叙事。"));
        dims.add(dim(mapper, "repeat", "句式重复率", repeatRate, pct(repeatRate), 0.13,
                range(repeatRate, 0.05, 0.30, false), sentences.size() >= 4,
                "同一批句式反复使用（尤其句首雷同）是 AI 的典型特征。"));
        dims.add(dim(mapper, "paraCV", "段落长度变异", paraCV, fmt2(paraCV), 0.12,
                range(paraCV, 0.25, 0.75, true), paragraphs.size() >= 2,
                "AI 段落长度趋同；人写会有长短交错的呼吸感。"));
        dims.add(dim(mapper, "punct", "标点多样性", punctEntropy, fmt2(punctEntropy), 0.08,
                range(punctEntropy, 0.35, 0.85, true), punctMeasurable,
                "句末几乎只用句号、缺少 ！？…… 的变化。"));
        dims.add(dim(mapper, "emotion", "情感峰谷", emotionStd, fmt2(emotionStd), 0.07,
                range(emotionStd, 2.0, 12.0, true), emotionMeasurable,
                "AI 常把情绪摊平；人写段落间情绪起伏更大。"));

        // 按「可测维度」的权重归一化，避免不可测维度把总分拉偏
        double wsum = 0;
        double acc = 0;
        int measurableCount = 0;
        for (int i = 0; i < dims.size(); i++) {
            ObjectNode d = (ObjectNode) dims.get(i);
            if (!d.get("measurable").asBoolean()) continue;
            double w = d.get("weight").asDouble();
            wsum += w;
            acc += d.get("score").asDouble() * w;
            measurableCount++;
        }
        double total = wsum > 0 ? clamp(acc / wsum, 0, 100) : 0;

        String level = total >= 60 ? "high" : (total >= 32 ? "medium" : "low");
        String verdict = switch (level) {
            case "high" -> "AI 痕迹明显";
            case "medium" -> "存在可疑痕迹";
            default -> "整体自然";
        };

        resp.put("score", round1(total));
        resp.put("level", level);
        resp.put("verdict", verdict);
        resp.put("lowConfidence", chars < MIN_CONFIDENT_CHARS);
        resp.put("measurableDims", measurableCount);

        ObjectNode stats = mapper.createObjectNode();
        stats.put("chars", chars);
        stats.put("sentences", sentences.size());
        stats.put("paragraphs", paragraphs.size());
        stats.put("avgSentenceLen", round1(sentMean));
        stats.put("sentenceStd", round1(sentStd));
        stats.put("avgParagraphLen", round1(paraMean));
        resp.set("stats", stats);

        resp.set("dimensions", dims);

        // —— 命中明细 ——
        // 🟢 修复：同一个短语可能同时出现在禁词表与 AI 腔表，此前会重复列出两条。
        //    按短语去重，两者都命中时归为 banned（更强的信号）。
        Map<String, Integer> merged = new HashMap<>(tellHits);
        Map<String, String> types = new HashMap<>();
        tellHits.forEach((k, v) -> types.put(k, "aiTell"));
        for (Map.Entry<String, Integer> e : bannedHits.entrySet()) {
            merged.merge(e.getKey(), e.getValue(), Integer::sum);
            types.put(e.getKey(), "banned");
        }
        ArrayNode hits = mapper.createArrayNode();
        for (Map.Entry<String, Integer> e : merged.entrySet()) {
            ObjectNode o = mapper.createObjectNode();
            o.put("phrase", e.getKey());
            o.put("count", e.getValue());
            o.put("type", types.getOrDefault(e.getKey(), "aiTell"));
            hits.add(o);
        }
        sortHits(hits);
        resp.set("hits", hits);

        // —— 建议 ——
        ArrayNode advice = mapper.createArrayNode();
        for (int i = 0; i < dims.size(); i++) {
            ObjectNode d = (ObjectNode) dims.get(i);
            // 🟢 不可测维度不参与建议：它会因为样本不足而拿到虚高分，
            //    据此给建议等于凭空指点（例如单段文本提示「加大情绪落差」）
            if (!d.get("measurable").asBoolean()) continue;
            if (d.get("score").asDouble() >= 55) {
                advice.add(adviceFor(d.get("key").asText(), d.get("value").asDouble()));
            }
        }
        if (advice.isEmpty()) {
            advice.add("各项指纹均在正常区间，未发现明显 AI 痕迹。");
        }
        resp.set("advice", advice);

        return resp;
    }

    // ===================== 维度打分 =====================

    /**
     * 构造一个维度节点。
     *
     * @param measurable false 表示该维度在当前样本上无法测量（如段落数不足），
     *                   其权重会在总分归一化时被剔除，避免误判
     */
    private static ObjectNode dim(ObjectMapper mapper, String key, String label,
                                  double value, String display, double weight,
                                  double subScore, boolean measurable, String hint) {
        ObjectNode o = mapper.createObjectNode();
        o.put("key", key);
        o.put("label", label);
        o.put("value", round2(value));
        o.put("display", display);
        o.put("weight", measurable ? weight : 0.0);
        o.put("score", round1(subScore));
        o.put("measurable", measurable);
        o.put("verdict", !measurable ? "—" : (subScore >= 60 ? "高" : (subScore >= 32 ? "中" : "低")));
        o.put("hint", hint);
        return o;
    }

    /**
     * 把指标映射到 0~100 的 AI 倾向分。
     *
     * @param v      实际值
     * @param lo     该值下界（对应满分的另一侧）
     * @param hi     该值上界
     * @param invert true 表示值越小越像 AI（如变异系数）；false 表示值越大越像 AI
     */
    private static double range(double v, double lo, double hi, boolean invert) {
        double t;
        if (hi <= lo) return 0;
        if (v <= lo) t = 0;
        else if (v >= hi) t = 1;
        else t = (v - lo) / (hi - lo);
        return clamp((invert ? 1 - t : t) * 100, 0, 100);
    }

    /** 针对具体维度给出可执行的修改建议 */
    private static String adviceFor(String key, double value) {
        return switch (key) {
            case "sentCV" -> "刻意制造句长反差：在长描写后接一个短句（3~8 字），或用连续短句加速。当前变异系数仅 "
                    + fmt2(value) + "，建议拉到 0.6 以上。";
            case "tells" -> "集中清理 AI 腔短语：优先删掉下面「命中明细」里列出高频项，"
                    + "改用具体动作或感官描写替代抽象概括。";
            case "connective" -> "删掉多余的「然而 / 因此 / 此外」，让因果靠事件本身推进，而不是靠连接词说明。";
            case "repeat" -> "打散重复句式：把雷同的句首换成代词、动作或省略主语，避免排比式整齐。";
            case "paraCV" -> "调整段落切分：把部分长段拆开，或把几个短段合并，制造长短错落。";
            case "punct" -> "增加标点变化：适当使用感叹号、问号、破折号与省略号表达语气停顿。";
            case "emotion" -> "加大情绪落差：让平静段更平静、爆发段更猛烈，避免全程同一强度。";
            default -> "该维度偏离正常区间，建议人工复核。";
        };
    }

    // ===================== 统计工具 =====================

    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String s : SENT_SPLIT.split(text)) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static List<String> splitParagraphs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String s : PARA_SPLIT.split(text)) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 句首 2 字重复比例：衡量句式雷同 */
    private static double openingRepeatRate(List<String> sentences) {
        if (sentences.size() < 4) return 0;
        Map<String, Integer> openings = new HashMap<>();
        int counted = 0;
        for (String s : sentences) {
            if (s.length() < 2) continue;
            String head = s.substring(0, 2);
            openings.merge(head, 1, Integer::sum);
            counted++;
        }
        if (counted == 0) return 0;
        int dup = 0;
        for (int c : openings.values()) {
            if (c > 1) dup += (c - 1);
        }
        return (double) dup / counted;
    }

    /** 参与标点多样性统计的标点集合 */
    private static final char[] PUNCTS = {'。', '！', '？', '；', '…', '，', '、', '：', '"'};

    /**
     * 标点的归一化香农熵：越接近 1 越多样。
     *
     * <p>必须传入<b>原文</b>：句子切分会把 。！？ 当作分隔符丢掉，
     * 若基于切分后的文本统计，句末标点将永远为 0，导致该维度失效。</p>
     */
    private static double punctuationEntropy(String text) {
        if (text == null || text.isEmpty()) return 0;
        Map<Character, Integer> counts = new HashMap<>();
        int total = 0;
        for (char c : PUNCTS) {
            int n = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == c) n++;
            }
            if (n > 0) { counts.put(c, n); total += n; }
        }
        if (total == 0 || counts.size() <= 1) return 0;
        double h = 0;
        for (int n : counts.values()) {
            double p = (double) n / total;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return clamp(h / (Math.log(counts.size()) / Math.log(2)), 0, 1);
    }

    /** 原文中标点总数，用于判断该维度是否可测 */
    private static int punctuationTotal(String text) {
        if (text == null || text.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            for (char c : PUNCTS) {
                if (text.charAt(i) == c) { n++; break; }
            }
        }
        return n;
    }

    /** 全文情绪词命中总数，用于判断情感峰谷维度是否可测 */
    private static double totalEmotionHits(List<String> paragraphs) {
        double n = 0;
        for (String p : paragraphs) n += countHits(p, EMOTION_WORDS);
        return n;
    }

    /** 段落级情绪词密度的标准差：衡量情绪是否有峰谷 */
    private static double emotionVolatility(List<String> paragraphs) {
        if (paragraphs.size() < 3) return 0;
        double[] dens = new double[paragraphs.size()];
        for (int i = 0; i < paragraphs.size(); i++) {
            int c = countChinese(paragraphs.get(i));
            dens[i] = c > 0 ? countHits(paragraphs.get(i), EMOTION_WORDS) * 1000.0 / c : 0;
        }
        double m = mean(dens);
        return std(dens, m);
    }

    /** 统计一组短语在文本中的出现次数 */
    private static Map<String, Integer> countPhrases(String text, Set<String> phrases) {
        Map<String, Integer> hits = new HashMap<>();
        if (text == null || text.isEmpty()) return hits;
        for (String p : phrases) {
            if (p == null || p.isEmpty()) continue;
            int n = countOccurrences(text, p);
            if (n > 0) hits.put(p, n);
        }
        return hits;
    }

    /** 子串出现次数（支持 "a...b" 形式的通配：两端各至少间隔 0~12 字） */
    private static int countOccurrences(String text, String phrase) {
        int dots = phrase.indexOf("...");
        if (dots >= 0) {
            String a = phrase.substring(0, dots);
            String b = phrase.substring(dots + 3);
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                    java.util.regex.Pattern.quote(a) + ".{0,12}?" + java.util.regex.Pattern.quote(b));
            java.util.regex.Matcher m = pat.matcher(text);
            int n = 0;
            while (m.find()) n++;
            return n;
        }
        int n = 0, from = 0;
        while (true) {
            int i = text.indexOf(phrase, from);
            if (i < 0) break;
            n++;
            from = i + phrase.length();
        }
        return n;
    }

    private static double countHits(String text, Set<String> words) {
        if (text == null || text.isEmpty()) return 0;
        double n = 0;
        for (String w : words) {
            if (w == null || w.isEmpty()) continue;
            n += countOccurrences(text, w);
        }
        return n;
    }

    private static Set<String> nonEmpty(Set<String> src) {
        Set<String> out = new HashSet<>();
        for (String s : src) {
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    private static void sortHits(ArrayNode arr) {
        List<ObjectNode> list = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) list.add((ObjectNode) arr.get(i));
        list.sort((a, b) -> {
            int c = Integer.compare(b.get("count").asInt(), a.get("count").asInt());
            return c != 0 ? c : a.get("phrase").asText().compareTo(b.get("phrase").asText());
        });
        arr.removeAll();
        for (ObjectNode o : list) arr.add(o);
    }

    // ===================== 数值工具 =====================

    private static String stripMarkdown(String text) {
        if (text == null) return "";
        return text.replaceAll("^#{1,6}\\s*", "")
                .replaceAll("\\*\\*", "")
                .replaceAll("(?m)^>\\s*", "");
    }

    private static int countChinese(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        java.util.regex.Matcher m = CJK.matcher(s);
        while (m.find()) n++;
        return n;
    }

    private static double countChineseAsDouble(String s) {
        return countChinese(s);
    }

    private static double mean(double[] v) {
        if (v == null || v.length == 0) return 0;
        double s = 0;
        for (double x : v) s += x;
        return s / v.length;
    }

    private static double std(double[] v, double mean) {
        if (v == null || v.length < 2) return 0;
        double s = 0;
        for (double x : v) s += (x - mean) * (x - mean);
        return Math.sqrt(s / (v.length - 1));
    }

    private static int sum(Map<String, Integer> m) {
        int s = 0;
        for (int v : m.values()) s += v;
        return s;
    }

    private static double per1000(double count, int chars) {
        return chars > 0 ? count * 1000.0 / chars : 0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    private static String fmt2(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }

    private static String pct(double v) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", v * 100);
    }

    /** Set.of 不允许重复元素，词表统一用 HashSet，避免日后加词引发类初始化崩溃 */
    private static Set<String> setOf(String... words) {
        return new HashSet<>(Arrays.asList(words));
    }
}
