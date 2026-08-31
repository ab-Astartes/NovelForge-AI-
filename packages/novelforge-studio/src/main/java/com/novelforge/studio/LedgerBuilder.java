package com.novelforge.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 资源与战力账本（零 LLM 成本）。
 *
 * <p>从 {@code chapters/*.md} 抽取资源收支（灵石/丹药/贡献点…）与人物境界进阶，
 * 计算余额流水并产出三类告警：凭空消耗（余额转负）、战力膨胀（单章跨阶 &gt; 2）、
 * 数值无上下文（数量出现但无收支动词）。支持合并 {@code truth/ledger.json} 手工账。</p>
 */
public final class LedgerBuilder {

    private LedgerBuilder() {}

    // ===================== 词表与模式 =====================

    /** 资源名 → 计量单位 */
    private static final Map<String, String> RESOURCES = new LinkedHashMap<>();
    static {
        RESOURCES.put("极品灵石", "块"); RESOURCES.put("上品灵石", "块"); RESOURCES.put("中品灵石", "块");
        RESOURCES.put("下品灵石", "块"); RESOURCES.put("灵石", "块"); RESOURCES.put("灵晶", "枚");
        RESOURCES.put("仙石", "块"); RESOURCES.put("魔石", "块"); RESOURCES.put("魂币", "枚");
        RESOURCES.put("金币", "枚"); RESOURCES.put("银两", "两"); RESOURCES.put("铜钱", "枚");
        RESOURCES.put("元宝", "锭"); RESOURCES.put("贡献点", "点"); RESOURCES.put("积分", "分");
        RESOURCES.put("声望", "点"); RESOURCES.put("丹药", "枚"); RESOURCES.put("符箓", "张");
        RESOURCES.put("灵草", "株"); RESOURCES.put("妖丹", "枚"); RESOURCES.put("魔核", "枚");
        RESOURCES.put("晶石", "块"); RESOURCES.put("寿元", "年"); RESOURCES.put("修为", "年");
    }

    /** 数量（阿拉伯数字、中文数字，或「几/数/若干/大量」等笼统量词→按 1 计） */
    private static final String NUM = "(?:\\d+(?:\\.\\d+)?|几|数|若干|大量|无数|一批|成堆|[一二三四五六七八九十百千万亿两]+)";
    /** 中文数字量词单位（块/枚/颗…） */
    private static final String QUANT = "(?:块|枚|颗|两|个|张|株|斤|锭|点|分|年|道|缕|丝|份|箱|袋|瓶)";
    /** 数量 + 资源名 */
    private static final Pattern AMOUNT_PAT = Pattern.compile(
            "(" + NUM + ")\\s*(?:" + QUANT + ")?\\s*(" + String.join("|", RESOURCES.keySet()) + ")");
    /** 收入动词（出现在数量前的窗口内） */
    private static final Set<String> GAIN_VERBS = setOf(
            "获得", "得到", "收入", "赚取", "奖励", "赏赐", "换取", "换来", "卖出", "拾得", "捡到",
            "夺得", "赢", "进账", "入账", "补足", "补齐", "赠予", "赠送", "补偿", "进献", "缴纳给",
            "收获", "领取", "发放", "积蓄", "存下", "攒下", "赏", "给", "赠与", "递给", "塞给");
    /** 支出动词 */
    private static final Set<String> LOSS_VERBS = setOf(
            "花费", "消耗", "耗去", "付出", "损失", "购买", "买下", "支付", "赔偿", "捐献", "缴纳",
            "散尽", "用掉", "耗尽", "挥霍", "抵押", "兑换掉", "扣除", "赔偿了", "输掉", "砸下",
            "花掉", "买", "付", "耗", "赔", "捐", "扣", "砸", "散", "耗损", "耗费", "吞掉");

    /** Set.of 不允许重复元素，词表较大时改用 HashSet 构造 */
    private static Set<String> setOf(String... words) {
        return new HashSet<>(java.util.Arrays.asList(words));
    }

    /** 境界阶梯（有序，索引即强度） */
    private static final List<String> REALM_ORDER = List.of(
            "炼气", "筑基", "结丹", "金丹", "元婴", "化神", "炼虚", "合体", "大乘", "渡劫", "真仙", "仙君", "仙帝");
    private static final Pattern REALM_PAT = Pattern.compile(
            "(" + String.join("|", REALM_ORDER) + ")\\s*([一二三四五六七八九十])?\\s*(?:层|重|段|阶|品)?");

    private static final int MAX_CHAPTERS = 400;
    private static final int CONTEXT_WINDOW = 14;   // 数量前文窗口（动词判定）

    /** 中文数字字面量（Map.of 上限 10 对，故手写） */
    private static final Map<Character, Integer> CN_DIGITS = new LinkedHashMap<>();
    static {
        char[] cs = {'零', '一', '二', '两', '三', '四', '五', '六', '七', '八', '九'};
        int[] vs = {0, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int i = 0; i < cs.length; i++) CN_DIGITS.put(cs[i], vs[i]);
    }

    // ===================== 主入口 =====================

    public static ObjectNode build(ObjectMapper mapper, Path bookDir) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("book", bookDir.getFileName() != null ? bookDir.getFileName().toString() : "");

        Map<String, Res> res = new LinkedHashMap<>();
        Map<String, List<PowerPoint>> power = new LinkedHashMap<>();
        ArrayNode warnings = mapper.createArrayNode();
        List<String> chapterNames = new ArrayList<>();

        // ---- 1. 已知人物（用于境界归属）----
        Set<String> persons = new HashSet<>();
        Path charsFile = bookDir.resolve("truth").resolve("characters.json");
        try {
            if (Files.exists(charsFile)) {
                JsonNode arr = mapper.readTree(Files.readAllBytes(charsFile));
                java.util.Iterator<JsonNode> it = null;
                if (arr.isArray()) it = arr.elements();
                else if (arr.isObject() && arr.path("characters").isArray()) it = arr.path("characters").elements();
                else if (arr.isObject()) { java.util.Iterator<String> ks = arr.fieldNames(); while (ks.hasNext()) persons.add(ks.next()); }
                if (it != null) while (it.hasNext()) { String n = it.next().path("name").asText("").trim(); if (!n.isEmpty()) persons.add(n); }
            }
        } catch (Exception ignore) {}

        // ---- 2. 正文扫描 ----
        Path chaptersDir = bookDir.resolve("chapters");
        int chapterIdx = 0;
        if (Files.isDirectory(chaptersDir)) {
            try (Stream<Path> stream = Files.list(chaptersDir)) {
                List<Path> files = stream
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .filter(p -> !p.getFileName().toString().contains(".draft."))
                        .sorted().limit(MAX_CHAPTERS).toList();
                for (Path f : files) {
                    chapterIdx++;
                    chapterNames.add("第" + chapterIdx + "章");
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    for (String rawLine : text.split("\n")) {
                        String line = rawLine.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        scanResources(res, line, chapterIdx, warnings);
                        scanPower(power, line, chapterIdx, persons);
                    }
                }
            } catch (Exception ignore) {}
        }

        // ---- 3. 合并手工账 truth/ledger.json ----
        Path manual = bookDir.resolve("truth").resolve("ledger.json");
        int manualCount = 0;
        try {
            if (Files.exists(manual)) {
                JsonNode m = mapper.readTree(Files.readAllBytes(manual));
                JsonNode entries = m.isArray() ? m : m.path("entries");
                if (entries.isArray()) {
                    for (JsonNode e : entries) {
                        String name = e.path("resource").asText(e.path("name").asText("")).trim();
                        if (name.isEmpty()) continue;
                        double delta = e.path("delta").asDouble(0);
                        int ch = e.path("chapter").asInt(0);
                        Res r = res.computeIfAbsent(name, k -> new Res(name, RESOURCES.getOrDefault(name, "")));
                        r.manual += delta;
                        if (ch > 0) r.history.add(new Entry(ch, delta, "（手工账）" + e.path("note").asText(""), true));
                        manualCount++;
                    }
                }
            }
        } catch (Exception ignore) {}

        // ---- 4. 余额结算 + 凭空消耗告警 ----
        ArrayNode resArr = mapper.createArrayNode();
        for (Res r : res.values()) {
            r.history.sort((a, b) -> Integer.compare(a.chapter, b.chapter));
            double bal = 0;
            for (Entry e : r.history) {
                bal += e.delta;
                e.balance = bal;
                if (bal < 0 && !e.manual) {
                    ObjectNode w = mapper.createObjectNode();
                    w.put("level", "error");
                    w.put("type", "negative");
                    w.put("chapter", e.chapter);
                    w.put("name", r.name);
                    w.put("message", "第" + e.chapter + "章「" + r.name + "」支出后余额为 " + fmt(bal)
                            + "，此前未见足够收入——疑似凭空支出");
                    warnings.add(w);
                }
            }
            Res sorted = r;
            ObjectNode o = mapper.createObjectNode();
            o.put("name", sorted.name);
            o.put("unit", sorted.unit);
            o.put("totalIn", sorted.totalIn);
            o.put("totalOut", sorted.totalOut);
            o.put("balance", sorted.totalIn - sorted.totalOut + sorted.manual);
            o.put("mentions", sorted.mentions);
            ArrayNode hist = mapper.createArrayNode();
            sorted.history.stream().limit(200).forEach(e -> {
                ObjectNode h = mapper.createObjectNode();
                h.put("chapter", e.chapter);
                h.put("delta", e.delta);
                h.put("balance", e.balance);
                h.put("context", e.context);
                h.put("manual", e.manual);
                hist.add(h);
            });
            o.set("history", hist);
            resArr.add(o);
        }

        // ---- 5. 境界时间线 + 战力膨胀告警 ----
        // 先按 (人物, 章节) 聚合：取该章出现次数最多的境界（同票数取更高阶），
        // 避免「同章最高阶」被单句假设/回忆污染（如 ch1 提一句筑基就把炼气主角抬阶）
        Map<String, Map<Integer, Map<String, Integer>>> votes = new LinkedHashMap<>();
        for (Map.Entry<String, List<PowerPoint>> en : power.entrySet()) {
            Map<Integer, Map<String, Integer>> byCh = votes.computeIfAbsent(en.getKey(), k -> new LinkedHashMap<>());
            for (PowerPoint p : en.getValue()) {
                byCh.computeIfAbsent(p.chapter, k -> new LinkedHashMap<>())
                    .merge(p.realm, 1, Integer::sum);
            }
        }
        Map<String, Map<Integer, String>> perChapterMax = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Integer, Map<String, Integer>>> en : votes.entrySet()) {
            Map<Integer, String> m = perChapterMax.computeIfAbsent(en.getKey(), k -> new LinkedHashMap<>());
            for (Map.Entry<Integer, Map<String, Integer>> ce : en.getValue().entrySet()) {
                String best = null; int bestCnt = -1;
                for (Map.Entry<String, Integer> re : ce.getValue().entrySet()) {
                    if (re.getValue() > bestCnt
                            || (re.getValue() == bestCnt && indexOfRealm(re.getKey()) > indexOfRealm(best))) {
                        best = re.getKey(); bestCnt = re.getValue();
                    }
                }
                if (best != null) m.put(ce.getKey(), best);
            }
        }
        ArrayNode powerArr = mapper.createArrayNode();
        for (Map.Entry<String, Map<Integer, String>> en : perChapterMax.entrySet()) {
            List<PowerPoint> tl = new ArrayList<>();
            en.getValue().forEach((ch, realm) -> tl.add(new PowerPoint(ch, realm)));
            tl.sort((a, b) -> Integer.compare(a.chapter, b.chapter));
            List<PowerPoint> dedup = new ArrayList<>();
            for (PowerPoint p : tl) {
                if (dedup.isEmpty() || !dedup.get(dedup.size() - 1).realm.equals(p.realm)) dedup.add(p);
            }
            for (int i = 1; i < dedup.size(); i++) {
                int jump = Math.abs(indexOfRealm(dedup.get(i).realm) - indexOfRealm(dedup.get(i - 1).realm));
                if (jump > 2) {
                    ObjectNode w = mapper.createObjectNode();
                    w.put("level", "warn");
                    w.put("type", "power-spike");
                    w.put("chapter", dedup.get(i).chapter);
                    w.put("name", en.getKey());
                    w.put("message", "「" + en.getKey() + "」第" + dedup.get(i - 1).chapter + "章为" + dedup.get(i - 1).realm
                            + "，第" + dedup.get(i).chapter + "章骤升至" + dedup.get(i).realm + "，跨 " + jump + " 阶，疑似战力膨胀");
                    warnings.add(w);
                } else if (indexOfRealm(dedup.get(i).realm) < indexOfRealm(dedup.get(i - 1).realm)) {
                    ObjectNode w = mapper.createObjectNode();
                    w.put("level", "info");
                    w.put("type", "power-drop");
                    w.put("chapter", dedup.get(i).chapter);
                    w.put("name", en.getKey());
                    w.put("message", "「" + en.getKey() + "」第" + dedup.get(i).chapter + "章为" + dedup.get(i).realm
                            + "，低于第" + dedup.get(i - 1).chapter + "章的" + dedup.get(i - 1).realm
                            + "——若为回忆/假设属正常，否则疑似笔误");
                    warnings.add(w);
                }
            }
            ObjectNode o = mapper.createObjectNode();
            o.put("character", en.getKey());
            ArrayNode tlArr = mapper.createArrayNode();
            for (PowerPoint p : dedup) {
                ObjectNode n = mapper.createObjectNode();
                n.put("chapter", p.chapter);
                n.put("realm", p.realm);
                n.put("index", indexOfRealm(p.realm));
                tlArr.add(n);
            }
            o.set("timeline", tlArr);
            o.put("current", dedup.isEmpty() ? "" : dedup.get(dedup.size() - 1).realm);
            powerArr.add(o);
        }

        resp.set("resources", resArr);
        resp.set("power", powerArr);
        resp.set("warnings", warnings);

        ObjectNode stats = mapper.createObjectNode();
        stats.put("resources", res.size());
        stats.put("entries", res.values().stream().mapToInt(r -> r.history.size()).sum());
        stats.put("chapters", chapterIdx);
        stats.put("warnings", warnings.size());
        stats.put("manualEntries", manualCount);
        stats.put("trackedCharacters", power.size());
        resp.set("stats", stats);
        return resp;
    }

    // ===================== 扫描 =====================

    private static void scanResources(Map<String, Res> res, String line, int chapter, ArrayNode warnings) {
        Matcher m = AMOUNT_PAT.matcher(line);
        while (m.find()) {
            String numStr = m.group(1);
            String name = m.group(2);
            Double v = parseNumber(numStr);
            if (v == null || v <= 0) continue;
            Res r = res.computeIfAbsent(name, k -> new Res(name, RESOURCES.getOrDefault(name, "")));
            r.mentions++;
            // 动词判定：取命中位置前 CONTEXT_WINDOW 字
            int start = Math.max(0, m.start() - CONTEXT_WINDOW);
            String before = line.substring(start, m.start());
            if (containsAny(before, GAIN_VERBS)) {
                r.totalIn += v;
                r.history.add(new Entry(chapter, v, clip(line, 70), false));
            } else if (containsAny(before, LOSS_VERBS)) {
                r.totalOut += v;
                r.history.add(new Entry(chapter, -v, clip(line, 70), false));
            } else {
                // 无收支动词：仅记录提及，不入账；频繁出现则提示
                r.ambiguous++;
                if (r.ambiguous == 3) addAmbiguousWarning(warnings, chapter, name);
            }
        }
    }

    private static void addAmbiguousWarning(ArrayNode warnings, int chapter, String name) {
        ObjectNode w = warnings.addObject();
        w.put("level", "info");
        w.put("type", "ambiguous");
        w.put("chapter", chapter);
        w.put("name", name);
        w.put("message", "「" + name + "」多次出现数量但无收支动词上下文，未计入流水（建议改为「获得/花费 N " + name + "」句式便于记账）");
    }

    private static void scanPower(Map<String, List<PowerPoint>> power, String line, int chapter, Set<String> persons) {
        Matcher m = REALM_PAT.matcher(line);
        while (m.find()) {
            String realm = m.group(1);
            String owner = "";
            // 归属判定：同一句中出现的已知人物
            for (String p : persons) {
                if (line.contains(p)) {
                    if (owner.isEmpty() || p.length() > owner.length()) owner = p;
                }
            }
            if (owner.isEmpty()) continue;   // 无主境界不入账，避免噪声
            power.computeIfAbsent(owner, k -> new ArrayList<>()).add(new PowerPoint(chapter, realm));
        }
    }

    // ===================== 数字与工具 =====================

    /** 解析阿拉伯数字或中文数字（支持 万/亿/千/百/十） */
    static Double parseNumber(String s) {
        if (s == null || s.isEmpty()) return null;
        String t = s.trim();
        if (t.matches("\\d+(\\.\\d+)?")) {
            try { return Double.parseDouble(t); } catch (Exception e) { return null; }
        }
        Map<Character, Integer> d = CN_DIGITS;
        // 形如 「三万」「五千」「一百二十」
        long total = 0, section = 0, cur = 0;
        boolean any = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (d.containsKey(c)) { cur = d.get(c); any = true; }
            else if (c == '十') { section += (cur == 0 ? 1 : cur) * 10; cur = 0; any = true; }
            else if (c == '百') { section += (cur == 0 ? 1 : cur) * 100; cur = 0; any = true; }
            else if (c == '千') { section += (cur == 0 ? 1 : cur) * 1000; cur = 0; any = true; }
            else if (c == '万') { total += (section + cur) * 10000L; section = 0; cur = 0; any = true; }
            else if (c == '亿') { total += (section + cur) * 100000000L; section = 0; cur = 0; any = true; }
            else return null;
        }
        if (!any) return null;
        return (double) (total + section + cur);
    }

    private static int indexOfRealm(String realm) {
        int i = REALM_ORDER.indexOf(realm);
        return i < 0 ? 0 : i;
    }

    private static boolean containsAny(String s, Set<String> words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.format(Locale.ROOT, "%.0f", v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static final class Res {
        final String name;
        final String unit;
        final List<Entry> history = new ArrayList<>();
        double totalIn, totalOut, manual;
        int mentions, ambiguous;
        Res(String name, String unit) { this.name = name; this.unit = unit; }
    }

    private static final class Entry {
        final int chapter;
        final double delta;
        final String context;
        final boolean manual;
        double balance;
        Entry(int chapter, double delta, String context, boolean manual) {
            this.chapter = chapter; this.delta = delta; this.context = context; this.manual = manual;
        }
    }

    private static final class PowerPoint {
        final int chapter;
        final String realm;
        PowerPoint(int chapter, String realm) { this.chapter = chapter; this.realm = realm; }
    }
}
