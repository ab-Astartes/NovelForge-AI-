package com.novelforge.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 情绪张力曲线与章节节拍器（零 LLM 成本）。
 *
 * <p>按章计算张力分（句长节奏 / 对话占比 / 动作冲突词密度 / 感叹问号密度 / 转折词密度 /
 * 情绪极性 / 段落长度），生成全书张力曲线，并按「起承转合」给出每章节拍目标带，
 * 对「流水账」「高潮疲劳」「节奏断崖」「偏离节拍」四类节奏问题告警。</p>
 */
public final class TensionBuilder {

    private TensionBuilder() {}

    // ===================== 词表 =====================

    /** 动作/冲突词（紧张度高） */
    private static final Set<String> ACTION_WORDS = setOf(
            "扑", "斩", "砍", "劈", "轰", "爆", "震", "撞", "撕", "吼", "喝", "杀", "死", "血",
            "战", "斗", "崩", "裂", "闪", "逃", "追", "攻", "挡", "退", "击", "刺", "踢", "砸",
            "捏", "掐", "绞", "燃", "焚", "冰封", "雷", "风暴", "剑气", "掌风", "拳风", "刀光",
            "危机", "危险", "致命", "绝境", "拼命", "搏命", "生死", "搏杀", "厮杀", "激战",
            "颤抖", "冷汗", "窒息", "惨叫", "嘶吼", "咆哮", "怒喝", "暴喝", "疾驰", "狂奔");

    /** 转折/突发词（节奏推进） */
    private static final Set<String> TURN_WORDS = setOf(
            "突然", "猛然", "骤然", "忽然", "陡然", "瞬间", "刹那", "顷刻", "霎时", "猝不及防",
            "没想到", "谁知", "竟", "竟然", "却", "然而", "但是", "不料", "岂料", "就在此时",
            "说时迟", "下一刻", "紧接着", "就在这时", "千钧一发", "危机关头");

    /** 高张力情绪词 */
    private static final Set<String> HIGH_EMOTION = setOf(
            "惊恐", "恐惧", "愤怒", "暴怒", "震怒", "绝望", "疯狂", "狰狞", "颤抖", "窒息",
            "心悸", "惊骇", "骇然", "大惊", "失色", "惊呼", "惨烈", "凄厉", "狂喜", "激动",
            "紧张", "急促", "慌乱", "惊疑", "凛然", "杀意", "寒意", "压迫", "沉重", "窒息感");

    /** 低张力/平静词 */
    private static final Set<String> CALM_WORDS = setOf(
            "平静", "安详", "宁静", "悠闲", "缓缓", "慢慢", "轻轻", "微笑", "温和", "柔和",
            "惬意", "闲适", "舒展", "放松", "松了口气", "安心", "静谧", "祥和", "淡然", "从容");

    /** 句子切分（中文句末标点 + 换行） */
    private static final Pattern SENT_SPLIT = Pattern.compile("[。！？!?；;…]+|[\\n]+");
    /** 对话（中文引号包裹） */
    private static final Pattern DIALOG_PAT = Pattern.compile("[\"“\"『』][^\"“\"『』]{1,200}[\"“\"『』]");
    /** 段落（非空行） */
    private static final Pattern PARA_SPLIT = Pattern.compile("\\n\\s*\\n");

    private static final int MAX_CHAPTERS = 400;

    // ===================== 主入口 =====================

    public static ObjectNode build(ObjectMapper mapper, Path bookDir) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("book", bookDir.getFileName() != null ? bookDir.getFileName().toString() : "");

        List<ChapterMetric> metrics = new ArrayList<>();
        Path chaptersDir = bookDir.resolve("chapters");
        int idx = 0;
        if (Files.isDirectory(chaptersDir)) {
            try (Stream<Path> stream = Files.list(chaptersDir)) {
                List<Path> files = stream
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .filter(p -> !p.getFileName().toString().contains(".draft."))
                        .sorted().limit(MAX_CHAPTERS).toList();
                for (Path f : files) {
                    idx++;
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    ChapterMetric m = analyze(idx, stripMarkdown(text));
                    if (m.words > 0) metrics.add(m);
                }
            } catch (Exception ignore) {}
        }

        int n = metrics.size();
        // ---- 节拍目标带：起承转合（前 25% 铺垫 / 中段爬升 / 后 20% 高潮 / 结尾回落）----
        for (ChapterMetric m : metrics) {
            m.target = beatTarget(m.chapter, n);
            m.deviation = m.score - m.target;
        }

        // ---- 输出曲线 ----
        ArrayNode curve = mapper.createArrayNode();
        for (ChapterMetric m : metrics) {
            ObjectNode o = mapper.createObjectNode();
            o.put("chapter", m.chapter);
            o.put("score", round1(m.score));
            o.put("target", round1(m.target));
            o.put("deviation", round1(m.deviation));
            o.put("words", m.words);
            o.put("sentences", m.sentences);
            o.put("dialogRatio", round1(m.dialogRatio));
            o.put("avgSentenceLen", round1(m.avgSentenceLen));
            o.put("sentenceVar", round1(m.sentenceVar));
            o.put("actionDensity", round1(m.actionDensity));
            o.put("turnDensity", round1(m.turnDensity));
            o.put("exclamDensity", round1(m.exclamDensity));
            o.put("emotion", round1(m.emotion));
            o.put("paragraphs", m.paragraphs);
            if (m.peak != null) o.put("peak", m.peak);
            ArrayNode ex = mapper.createArrayNode();
            m.excerpts.forEach(ex::add);
            o.set("excerpts", ex);
            curve.add(o);
        }

        // ---- 告警 ----
        ArrayNode warnings = mapper.createArrayNode();
        if (n >= 3) {
            // 1. 流水账：连续 ≥3 章低于 35
            addRunWarning(mapper, warnings, metrics, 3, m -> m.score < 35,
                    "flat", "warn", chs -> "第 " + chs + " 章连续张力偏低（< 35），节奏平淡如流水账，建议插入冲突、悬念或反转");
            // 2. 高潮疲劳：连续 ≥3 章高于 65（实测带对话的战斗章峰值约 66-70，持续贴顶即疲劳）
            addRunWarning(mapper, warnings, metrics, 3, m -> m.score > 65,
                    "fatigue", "warn", chs -> "第 " + chs + " 章持续高张力（> 65），读者会疲劳，建议安排缓冲段落与情绪回落");
            // 3. 节奏断崖：相邻章落差 > 40
            for (int i = 1; i < metrics.size(); i++) {
                double d = metrics.get(i).score - metrics.get(i - 1).score;
                if (Math.abs(d) > 40) {
                    ObjectNode w = mapper.createObjectNode();
                    w.put("level", "warn");
                    w.put("type", "cliff");
                    w.put("chapter", metrics.get(i).chapter);
                    w.put("message", "第" + metrics.get(i - 1).chapter + "章（" + round1(metrics.get(i - 1).score)
                            + "）到第" + metrics.get(i).chapter + "章（" + round1(metrics.get(i).score) + "）落差 "
                            + round1(Math.abs(d)) + "，" + (d < 0 ? "高潮后断崖式下跌，建议用余波/反应段过渡" : "骤然拔高缺乏铺垫，建议前置铺垫"));
                    warnings.add(w);
                }
            }
            // 4. 偏离节拍：与目标带偏差 > 25
            for (ChapterMetric m : metrics) {
                if (Math.abs(m.deviation) > 25) {
                    ObjectNode w = mapper.createObjectNode();
                    w.put("level", "info");
                    w.put("type", "offbeat");
                    w.put("chapter", m.chapter);
                    w.put("message", "第" + m.chapter + "章张力 " + round1(m.score) + "，节拍目标约 " + round1(m.target)
                            + "（偏差 " + round1(m.deviation) + "）——" + (m.deviation < 0 ? "该推进时偏温吞" : "该铺垫时过早发力"));
                    warnings.add(w);
                }
            }
        }

        resp.set("curve", curve);
        resp.set("warnings", warnings);

        // ---- 统计 ----
        ObjectNode stats = mapper.createObjectNode();
        stats.put("chapters", n);
        if (!metrics.isEmpty()) {
            List<ChapterMetric> sorted = new ArrayList<>(metrics);
            sorted.sort(Comparator.comparingDouble(m -> m.score));
            stats.put("avg", round1(metrics.stream().mapToDouble(m -> m.score).average().orElse(0)));
            stats.put("min", round1(sorted.get(0).score));
            stats.put("minChapter", sorted.get(0).chapter);
            stats.put("max", round1(sorted.get(sorted.size() - 1).score));
            stats.put("maxChapter", sorted.get(sorted.size() - 1).chapter);
            long high = metrics.stream().filter(m -> m.score >= 70).count();
            stats.put("highCount", high);
            stats.put("highRatio", round1(n == 0 ? 0 : high * 100.0 / n));
        } else {
            stats.put("avg", 0); stats.put("min", 0); stats.put("minChapter", 0);
            stats.put("max", 0); stats.put("maxChapter", 0);
            stats.put("highCount", 0); stats.put("highRatio", 0);
        }
        stats.put("warnings", warnings.size());
        resp.set("stats", stats);
        return resp;
    }

    // ===================== 单章分析 =====================

    private static ChapterMetric analyze(int chapter, String text) {
        ChapterMetric m = new ChapterMetric(chapter);
        if (text == null || text.isBlank()) return m;

        m.words = countChinese(text);
        // 句子
        List<String> sents = new ArrayList<>();
        for (String s : SENT_SPLIT.split(text)) {
            String t = s.trim();
            if (t.length() >= 2) sents.add(t);
        }
        m.sentences = sents.size();
        if (!sents.isEmpty()) {
            double sum = 0;
            for (String s : sents) sum += s.length();
            double avg = sum / sents.size();
            m.avgSentenceLen = avg;
            double varr = 0;
            for (String s : sents) varr += (s.length() - avg) * (s.length() - avg);
            m.sentenceVar = Math.sqrt(varr / sents.size());
        }
        // 对话占比
        int dialogChars = 0;
        Matcher dm = DIALOG_PAT.matcher(text);
        while (dm.find()) dialogChars += dm.group().length();
        m.dialogRatio = m.words == 0 ? 0 : Math.min(100, dialogChars * 100.0 / m.words);
        // 段落
        int paras = 0;
        for (String p : PARA_SPLIT.split(text)) if (p.trim().length() >= 4) paras++;
        m.paragraphs = Math.max(1, paras);
        // 密度（每千字）
        double k = Math.max(0.5, m.words / 1000.0);
        m.actionDensity = countHits(text, ACTION_WORDS) / k;
        m.turnDensity = countHits(text, TURN_WORDS) / k;
        m.exclamDensity = (countChar(text, '！') + countChar(text, '!')
                + countChar(text, '？') + countChar(text, '?')) / k;

        // 情绪极性：-100（极平静）~ +100（极激烈）
        double hi = countHits(text, HIGH_EMOTION), lo = countHits(text, CALM_WORDS);
        double emoTotal = hi + lo;
        m.emotion = emoTotal == 0 ? 0 : (hi - lo) / emoTotal * 100;

        // 张力分：多因子加权（0~100）
        // 系数按真实网文语料标定：典型战斗章（动作 ~14、转折 ~5、感叹 ~14/千字）落在 50-60，
        // 爆点章可上探 80+，纯铺垫章落在 15-30，避免全线饱和
        double score = 18                                            // 基线
                + Math.min(18, Math.max(0, m.actionDensity - 5) * 0.85)  // 动作冲突
                + Math.min(12, Math.max(0, m.turnDensity - 1) * 1.4)     // 转折推进
                + Math.min(10, Math.max(0, m.exclamDensity - 4) * 0.55)  // 感叹/疑问
                + Math.min(8, Math.max(0, m.emotion) / 12)               // 高张力情绪
                - Math.min(8, Math.max(0, -m.emotion) / 12)              // 平静情绪
                + Math.min(6, m.dialogRatio / 16)                        // 对话推进节奏
                + Math.min(6, Math.max(0, 24 - m.avgSentenceLen) * 0.55);// 短句紧凑度
        // 句长方差：忽长忽短 = 有节奏起伏
        score += Math.min(5, m.sentenceVar * 0.3);
        m.score = Math.max(0, Math.min(100, score));

        // 峰值片段：动作/转折词最密集的段落
        String[] blocks = PARA_SPLIT.split(text);
        String peak = null;
        int best = -1;
        for (String b : blocks) {
            if (b.trim().length() < 10) continue;
            int h = countHits(b, ACTION_WORDS) * 2 + countHits(b, TURN_WORDS) * 2 + countHits(b, HIGH_EMOTION);
            if (h > best) { best = h; peak = b.trim(); }
        }
        if (peak != null) m.peak = clip(peak, 90);
        // 代表性片段：前两段
        int shown = 0;
        for (String b : blocks) {
            if (b.trim().length() < 10) continue;
            m.excerpts.add(clip(b.trim(), 80));
            if (++shown >= 2) break;
        }
        return m;
    }

    /** 起承转合目标带：前 25% 铺垫 32→46，中段爬升 46→72，后段高潮 72→78，末章收束回落至 58
     *  （峰值压在 78 以内，与张力分理论上限 ~83 保持可达） */
    private static double beatTarget(int chapter, int total) {
        if (total <= 1) return 55;
        double p = (chapter - 1) * 1.0 / (total - 1);   // 0~1
        if (p <= 0.25) return 32 + (p / 0.25) * 14;          // 起：32 → 46
        if (p <= 0.70) return 46 + ((p - 0.25) / 0.45) * 26; // 承转：46 → 72
        if (p <= 0.92) return 72 + ((p - 0.70) / 0.22) * 6;  // 高潮：72 → 78
        return 78 - ((p - 0.92) / 0.08) * 20;                // 收束：78 → 58
    }

    // ===================== 工具 =====================

    private static void addRunWarning(ObjectMapper mapper, ArrayNode warnings, List<ChapterMetric> metrics,
                                      int minRun, java.util.function.Predicate<ChapterMetric> cond,
                                      String type, String level,
                                      java.util.function.Function<String, String> msgFn) {
        List<ChapterMetric> run = new ArrayList<>();
        for (int i = 0; i <= metrics.size(); i++) {
            boolean hit = i < metrics.size() && cond.test(metrics.get(i));
            if (hit) run.add(metrics.get(i));
            if (!hit || i == metrics.size()) {
                if (run.size() >= minRun) {
                    String chs = run.size() == 1
                            ? String.valueOf(run.get(0).chapter)
                            : run.get(0).chapter + "-" + run.get(run.size() - 1).chapter;
                    ObjectNode w = mapper.createObjectNode();
                    w.put("level", level);
                    w.put("type", type);
                    w.put("chapter", run.get(0).chapter);
                    w.put("message", msgFn.apply(chs));
                    warnings.add(w);
                }
                run.clear();
            }
        }
    }

    private static String stripMarkdown(String text) {
        return text.replaceAll("^#{1,6}\\s+", "")
                   .replaceAll("\\*\\*|__|\\*|_|`", "")
                   .replaceAll("^>\\s?", "")
                   .replaceAll("^---+$", "");
    }

    private static int countChinese(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fa5) n++;
        }
        return n;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static int countHits(String text, Set<String> words) {
        int hits = 0;
        for (String w : words) {
            int from = 0;
            while (true) {
                int i = text.indexOf(w, from);
                if (i < 0) break;
                hits++;
                from = i + w.length();
            }
        }
        return hits;
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /**
     * 章节节奏剖面（供去AI「按章节节奏」自适应使用）。
     * 直接复用单章分析逻辑，返回可用于调节去AI强度/侧重点的指标与节奏分类。
     */
    public static ObjectNode rhythmProfile(ObjectMapper mapper, String text) {
        ChapterMetric m = analyze(1, stripMarkdown(text == null ? "" : text));
        ObjectNode o = mapper.createObjectNode();
        o.put("avgSentenceLen", round1(m.avgSentenceLen));
        o.put("sentenceVar", round1(m.sentenceVar));
        o.put("dialogRatio", round1(m.dialogRatio));
        o.put("actionDensity", round1(m.actionDensity));
        o.put("turnDensity", round1(m.turnDensity));
        o.put("emotion", round1(m.emotion));
        o.put("score", round1(m.score));
        // 节奏分类：综合张力分 + 对话占比
        String rhythm;
        if (m.score >= 60) rhythm = "climax";          // 高潮：冲突密集
        else if (m.score >= 42) rhythm = "rising";     // 推进：张力上行
        else if (m.dialogRatio >= 40) rhythm = "dialogue"; // 对话驱动：平缓但有戏
        else rhythm = "calm";                          // 铺垫：低张力
        o.put("rhythm", rhythm);
        return o;
    }

    /** 不同节奏对去AI强度的调节系数（clamp 到 0~1） */
    public static double rhythmStrengthFactor(String rhythm) {
        return switch (rhythm == null ? "" : rhythm) {
            case "climax" -> 1.15;   // 高潮章：AI 腔更刺眼，更激进
            case "rising" -> 1.0;
            case "dialogue" -> 0.9;
            case "calm" -> 0.82;     // 铺垫章：保留文气，轻处理
            default -> 1.0;
        };
    }

    /** 不同节奏对应的去AI侧重点（附加到改写准则） */
    public static String rhythmGuidance(String rhythm) {
        return switch (rhythm == null ? "" : rhythm) {
            case "climax" -> "本章为高潮/冲突密集段：重点剥除总结性升华与工整对仗，保留动作与感官冲击，避免把紧张节奏改平。";
            case "rising" -> "本章为推进段：去除铺垫处的空泛过渡与解释性旁白，让节奏更利落。";
            case "dialogue" -> "本章以对话驱动：去除对话中的说明性套话与过度修辞，让对白更自然口语化。";
            case "calm" -> "本章为铺垫/抒情段：仅去除明显 AI 腔，保留文气与细腻描写，不要过度改写。";
            default -> "";
        };
    }

    /** Set.of 不允许重复元素，词表较大时改用 HashSet */
    private static Set<String> setOf(String... words) {
        return new java.util.HashSet<>(java.util.Arrays.asList(words));
    }

    private static final class ChapterMetric {
        final int chapter;
        final List<String> excerpts = new ArrayList<>();
        int words, sentences, paragraphs;
        double avgSentenceLen, sentenceVar, dialogRatio;
        double actionDensity, turnDensity, exclamDensity, emotion;
        double score, target, deviation;
        String peak;
        ChapterMetric(int chapter) { this.chapter = chapter; }
    }
}
