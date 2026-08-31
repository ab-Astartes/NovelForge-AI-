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
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 设定集 / 术语表自动抽取（零 LLM 成本）。
 *
 * <p>从 {@code chapters/*.md} 正文 + {@code truth/characters.json} + {@code truth/world.json}
 * 抽取六类设定实体：人物、功法典籍、法宝兵器、地名、境界体系、术语。
 * 输出词频、首现章节、例句与三类一致性告警（高频未登记 / 疑似异写 / 低频孤词）。</p>
 */
public final class GlossaryBuilder {

    private GlossaryBuilder() {}

    // ===================== 词表与模式 =====================

    /** 功法/武技后缀 */
    private static final Pattern SKILL_PAT = Pattern.compile(
            "[\\u4e00-\\u9fa5]{1,6}(?:诀|经|大法|心法|剑法|刀法|枪法|掌法|拳法|指法|腿法|身法|步法|阵法|秘术|神通|宝典|真解|图录|功法|武技|法典)");
    /** 《》典籍名（最高置信） */
    private static final Pattern BOOK_PAT = Pattern.compile("《([\\u4e00-\\u9fa5A-Za-z0-9]{2,12})》");
    /** 法宝/兵器后缀 */
    private static final Pattern ITEM_PAT = Pattern.compile(
            "[\\u4e00-\\u9fa5]{1,5}(?:剑|刀|枪|戟|弓|鞭|锏|斧|锤|塔|印|炉|鼎|镜|珠|环|钟|幡|旗|符箓|符|丹|甲|袍|履|戒|镯|瓶|葫芦|扇|琴|舟|车|盘|尺|针|网|索)");
    /** 地名 */
    private static final Pattern PLACE_PAT = Pattern.compile(
            "[\\u4e00-\\u9fa5]{2,4}(?:镇|城|村|山|谷|郡|州|府|域|界|海|渊|殿|宫|阁|楼|林|原|泽|岛)");
    /** 境界体系（含层级） */
    private static final String REALM_WORDS = "炼气|筑基|结丹|金丹|元婴|化神|炼虚|合体|大乘|渡劫|真仙|仙君|仙帝|仙境|"
            + "武者|武师|大武师|武灵|武王|武皇|武宗|武尊|武圣|武帝|"
            + "斗者|斗师|大斗师|斗灵|斗王|斗皇|斗宗|斗尊|斗圣|斗帝";
    private static final Pattern REALM_PAT = Pattern.compile("(" + REALM_WORDS + ")(?:\\s*[一二三四五六七八九十]?\\s*(?:层|重|段|阶|品))?");
    /** 疑似人物（说话动词前） */
    private static final Pattern PERSON_PAT = Pattern.compile("([\\u4e00-\\u9fa5]{2,4})(?:说道|问道|答道|喊道|笑道|冷哼|沉声道|轻声道|喃喃)");
    /** 定义句（术语释义抽取） */
    private static final Pattern DEF_PAT = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,8})(?:，)?(?:是|即|指的是|被称为|谓之|称作|名为)([^。！？\\n]{4,60})");
    /** 常用泛词黑名单（避免「长剑」「飞剑」之类误报） */
    private static final Set<String> STOP = Set.of(
            "长剑", "短剑", "宝剑", "飞剑", "利剑", "长刀", "大刀", "宝刀", "单刀", "长枪", "铁枪",
            "一剑", "一掌", "一拳", "一刀", "一枪", "手掌", "拳头", "脚步", "身影", "目光",
            "小镇", "山洞", "山谷", "城中", "城外", "山下", "山上", "家里", "房间", "大门",
            "什么", "怎么", "如此", "一个", "这些", "那些", "自己", "他们", "我们", "你们",
            "时间", "地方", "东西", "问题", "机会", "办法", "力量", "声音", "身体", "心中",
            "天地", "世界", "大陆", "海域", "森林", "草原", "沙漠", "沼泽", "岛屿");

    /** 类型标签 */
    private static final Map<String, String> TYPE_LABEL = new LinkedHashMap<>();
    static {
        TYPE_LABEL.put("person", "人物");
        TYPE_LABEL.put("skill", "功法典籍");
        TYPE_LABEL.put("item", "法宝兵器");
        TYPE_LABEL.put("place", "地名");
        TYPE_LABEL.put("realm", "境界体系");
        TYPE_LABEL.put("term", "术语");
    }

    /** 类型优先级：同一实体被多模式命中时取优先级高的（人物 > 功法 > 法宝 > 地名 > 境界 > 术语） */
    private static final Map<String, Integer> TYPE_PRIORITY = Map.of(
            "person", 6, "skill", 5, "item", 4, "place", 3, "realm", 2, "term", 1);

    /** 虚词/代词/介词/常用动词：以这些字开头，或出现在 3 字以上候选词内部的一律不是实体
     *  （排除「那些符」「他已经」「声音在脑海」「老者沉声道」之类误报） */
    private static final Set<Character> FUNC_WORD = Set.of(
            '这', '那', '其', '某', '每', '各', '诸', '些', '几', '多', '无', '满', '全', '整', '所',
            '什', '怎', '此', '之', '的', '了', '是', '有', '和', '与', '就', '都', '也', '还', '很',
            '太', '最', '不', '没', '别', '再', '又', '才', '更', '他', '她', '它', '我', '你', '您',
            '谁', '何', '如', '若', '但', '而', '且', '将', '把', '被', '让', '从', '对', '给', '向',
            '在', '按', '照', '依', '据', '随', '沿', '往', '朝', '替', '同', '跟', '及', '或', '为',
            '于', '至', '到', '过', '得', '地', '会', '能', '可', '要', '说', '道', '者', '声', '已');

    /** 数词前缀：仅对非功法/人物类生效——「九转混沌诀」「三皇剑」是合法专名，而「一块灵石」不是 */
    private static final Set<Character> NUM_PREFIX = Set.of(
            '一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '百', '千', '万');

    /** 常见姓氏：用于「姓氏 + 2 字且以地名词结尾」的姓名识别（如「萧远山」） */
    private static final Set<Character> SURNAMES = Set.of(
            '萧', '李', '王', '张', '刘', '陈', '杨', '赵', '黄', '周', '吴', '徐', '孙', '马', '朱',
            '胡', '郭', '何', '高', '林', '罗', '郑', '梁', '谢', '宋', '唐', '许', '韩', '冯', '邓',
            '曹', '彭', '曾', '肖', '田', '董', '袁', '潘', '于', '蒋', '蔡', '余', '杜', '叶', '程',
            '苏', '魏', '吕', '丁', '任', '沈', '姚', '卢', '姜', '崔', '钟', '谭', '陆', '汪', '范',
            '金', '石', '廖', '贾', '夏', '韦', '方', '白', '邹', '孟', '熊', '秦', '邱', '江', '尹',
            '薛', '段', '雷', '侯', '龙', '史', '陶', '黎', '贺', '顾', '毛', '郝', '龚', '邵', '钱',
            '严', '武', '戴', '莫', '孔', '向', '汤', '柳', '楚', '慕', '轩');

    private static final int MAX_CHAPTERS = 400;

    // ===================== 主入口 =====================

    public static ObjectNode build(ObjectMapper mapper, Path bookDir) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("book", bookDir.getFileName() != null ? bookDir.getFileName().toString() : "");

        // ---- 1. 已登记设定（characters.json / world.json）----
        Set<String> knownPersons = new HashSet<>();
        Set<String> knownAll = new HashSet<>();
        Path truthDir = bookDir.resolve("truth");
        Path charsFile = truthDir.resolve("characters.json");
        try {
            if (Files.exists(charsFile)) {
                JsonNode arr = mapper.readTree(Files.readAllBytes(charsFile));
                java.util.Iterator<JsonNode> it = null;
                if (arr.isArray()) it = arr.elements();
                else if (arr.isObject() && arr.path("characters").isArray()) it = arr.path("characters").elements();
                else if (arr.isObject()) { java.util.Iterator<String> ks = arr.fieldNames(); while (ks.hasNext()) knownPersons.add(ks.next()); }
                if (it != null) while (it.hasNext()) { String n = it.next().path("name").asText("").trim(); if (!n.isEmpty()) knownPersons.add(n); }
            }
        } catch (Exception ignore) {}
        knownAll.addAll(knownPersons);

        Path worldFile = truthDir.resolve("world.json");
        try {
            if (Files.exists(worldFile)) {
                JsonNode w = mapper.readTree(Files.readAllBytes(worldFile));
                if (w.isObject()) {
                    for (String f : new String[]{"locations", "places", "factions", "terms", "glossary"}) {
                        JsonNode n = w.path(f);
                        if (n.isArray()) for (JsonNode e : n) {
                            String nm = e.isObject() ? e.path("name").asText("").trim() : e.asText("").trim();
                            if (!nm.isEmpty()) knownAll.add(nm);
                        }
                    }
                    String raw = w.toString();
                    Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]{2,12})\"").matcher(raw);
                    while (m.find()) knownAll.add(m.group(1));
                }
            }
        } catch (Exception ignore) {}

        // ---- 2. 正文扫描 ----
        Map<String, Term> terms = new LinkedHashMap<>();
        List<String> chapterNames = new ArrayList<>();
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
                    chapterNames.add(chapterTitle(f, chapterIdx));
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    for (String line : text.split("\n")) {
                        String ln = line.trim();
                        if (ln.isEmpty() || ln.startsWith("#")) continue;
                        collect(terms, BOOK_PAT, ln, "skill", chapterIdx, 1);
                        collect(terms, SKILL_PAT, ln, "skill", chapterIdx, 0);
                        collect(terms, ITEM_PAT, ln, "item", chapterIdx, 0);
                        collect(terms, PLACE_PAT, ln, "place", chapterIdx, 0);
                        collect(terms, REALM_PAT, ln, "realm", chapterIdx, 0);
                        collect(terms, PERSON_PAT, ln, "person", chapterIdx, 0);
                        collectDefs(terms, ln, chapterIdx);
                    }
                }
            } catch (Exception ignore) {}
        }

        // ---- 3. 已知人物补登并强制归为人物（避免「萧远山」被地名模式抢注）----
        for (String p : knownPersons) {
            Term t = terms.computeIfAbsent(p, k -> new Term(p, "person"));
            t.type = "person";
        }

        // ---- 4. 过滤与排序 ----
        List<Term> list = new ArrayList<>();
        for (Term t : terms.values()) {
            if (t.name.length() < 2 && !"realm".equals(t.type)) continue;
            if (STOP.contains(t.name)) continue;
            if (t.count < 2 && !knownAll.contains(t.name) && !"realm".equals(t.type)) continue;
            t.defined = knownAll.contains(t.name) || !t.def.isEmpty();
            list.add(t);
        }
        list.sort((a, b) -> b.count != a.count ? Integer.compare(b.count, a.count) : a.name.compareTo(b.name));

        // ---- 5. 告警（境界体系属通用设定，不参与「未登记/异写/孤词」三类告警，避免刷屏）----
        ArrayNode warnings = mapper.createArrayNode();
        // 5.1 高频未登记
        for (Term t : list) {
            if ("realm".equals(t.type)) continue;
            if (t.count >= 3 && !knownAll.contains(t.name) && t.def.isEmpty()) {
                ObjectNode w = mapper.createObjectNode();
                w.put("level", "warn");
                w.put("type", "undefined");
                w.put("name", t.name);
                w.put("message", "「" + t.name + "」出现 " + t.count + " 次但未在角色表/世界观登记，建议补入设定集");
                warnings.add(w);
            }
        }
        // 5.2 疑似异写（同长、仅差一字、共用尾字）
        List<Term> cands = list.stream().filter(t -> !"realm".equals(t.type) && t.name.length() >= 2 && t.count >= 2).toList();
        for (int i = 0; i < cands.size() && warnings.size() < 60; i++) {
            for (int j = i + 1; j < cands.size() && warnings.size() < 60; j++) {
                Term a = cands.get(i), b = cands.get(j);
                if (a.type.equals(b.type) && isNearVariant(a.name, b.name)) {
                    ObjectNode w = mapper.createObjectNode();
                    w.put("level", "warn");
                    w.put("type", "variant");
                    w.put("name", a.name + " / " + b.name);
                    w.put("message", "「" + a.name + "」与「" + b.name + "」仅一字之差，疑似同一设定的异写（出现 " + a.count + " / " + b.count + " 次）");
                    warnings.add(w);
                }
            }
        }
        // 5.3 称谓/修饰粘连：长实体尾部包含高频短实体（如「长老萧远山」含「萧远山」）
        List<Term> shortOnes = list.stream().filter(t -> t.name.length() >= 2 && t.count >= 3).toList();
        for (Term t : list) {
            if (t.name.length() < 3 || t.count >= 3) continue;
            for (Term s : shortOnes) {
                if (s.name.equals(t.name)) continue;
                if (t.name.endsWith(s.name)) {
                    ObjectNode w = mapper.createObjectNode();
                    w.put("level", "info");
                    w.put("type", "sticky");
                    w.put("name", t.name);
                    w.put("message", "「" + t.name + "」疑似是「" + s.name + "」加了称谓/修饰前缀（出现 " + s.count + " 次），建议统一写法");
                    warnings.add(w);
                    break;
                }
            }
        }
        // 5.4 低频孤词（仅出现 2 次且长度 ≥3，可能是笔误）
        for (Term t : list) {
            if ("realm".equals(t.type)) continue;
            if (t.count == 2 && t.name.length() >= 3 && !knownAll.contains(t.name) && t.def.isEmpty()) {
                ObjectNode w = mapper.createObjectNode();
                w.put("level", "info");
                w.put("type", "orphan");
                w.put("name", t.name);
                w.put("message", "「" + t.name + "」仅出现 2 次，确认是否为一次性名词或笔误");
                warnings.add(w);
            }
        }

        // ---- 6. 输出 ----
        ArrayNode arr = mapper.createArrayNode();
        for (Term t : list) {
            ObjectNode o = mapper.createObjectNode();
            o.put("name", t.name);
            o.put("type", t.type);
            o.put("typeLabel", TYPE_LABEL.getOrDefault(t.type, t.type));
            o.put("count", t.count);
            o.put("firstChapter", t.firstChapter);
            o.put("defined", t.defined);
            if (!t.def.isEmpty()) o.put("definition", clip(t.def, 80));
            ArrayNode ex = mapper.createArrayNode();
            t.examples.stream().limit(3).forEach(ex::add);
            o.set("examples", ex);
            arr.add(o);
        }
        resp.set("terms", arr);
        resp.set("warnings", warnings);

        ObjectNode stats = mapper.createObjectNode();
        stats.put("total", list.size());
        stats.put("chapters", chapterIdx);
        stats.put("warnings", warnings.size());
        stats.put("known", knownAll.size());
        ObjectNode byType = mapper.createObjectNode();
        for (String k : TYPE_LABEL.keySet()) byType.put(k, list.stream().filter(t -> t.type.equals(k)).count());
        stats.set("byType", byType);
        resp.set("stats", stats);
        return resp;
    }

    // ===================== 辅助 =====================

    private static void collect(Map<String, Term> terms, Pattern pat, String line, String type, int chapter, int group) {
        Matcher m = pat.matcher(line);
        while (m.find()) {
            String name = (group > 0 ? m.group(group) : m.group()).trim();
            if (name.isEmpty() || name.length() > 12) continue;
            if (STOP.contains(name)) continue;
            // 姓氏规则：「萧远山」这类 3 字、姓氏开头、以地名词结尾的应判为人物
            String effType = (name.length() == 3 && SURNAMES.contains(name.charAt(0)) && "place".equals(type))
                    ? "person" : type;
            if (isNoise(name, effType)) continue;
            Term t = terms.computeIfAbsent(name, k -> new Term(name, effType));
            // 类型升级：后命中的模式优先级更高时更正（如「萧远山」先被地名命中、后被说话动词命中）
            Integer cur = TYPE_PRIORITY.get(t.type), nw = TYPE_PRIORITY.get(effType);
            if (nw != null && (cur == null || nw > cur)) t.type = type;
            t.count++;
            if (t.firstChapter == 0) t.firstChapter = chapter;
            if (t.examples.size() < 3) {
                String ex = clip(line, 90);
                if (t.examples.stream().noneMatch(e -> e.equals(ex))) t.examples.add(ex);
            }
        }
    }

    /** 噪声判定：虚词开头 / 3 字以上内部含虚词 / 数词开头（限非功法人物） */
    private static boolean isNoise(String name, String type) {
        if (name.isEmpty()) return true;
        char c0 = name.charAt(0);
        if (FUNC_WORD.contains(c0)) return true;
        if (NUM_PREFIX.contains(c0) && !"skill".equals(type) && !"person".equals(type)) return true;
        if (name.length() >= 3) {
            for (int i = 1; i < name.length(); i++) if (FUNC_WORD.contains(name.charAt(i))) return true;
        }
        return false;
    }

    /** 抽取「X，是/即/指的是…」形式的释义 */
    private static void collectDefs(Map<String, Term> terms, String line, int chapter) {
        Matcher m = DEF_PAT.matcher(line);
        while (m.find()) {
            String name = m.group(1).trim();
            if (name.length() < 2 || STOP.contains(name)) continue;
            if (isNoise(name, "term")) continue;
            Term t = terms.computeIfAbsent(name, k -> new Term(name, "term"));
            if (t.def.isEmpty()) t.def = m.group(2).trim();
            t.count++;
            if (t.firstChapter == 0) t.firstChapter = chapter;
        }
    }

    /** 同长、仅差一字、共用尾字 → 疑似异写 */
    private static boolean isNearVariant(String a, String b) {
        if (a.equals(b) || a.length() != b.length() || a.length() < 2) return false;
        if (a.charAt(a.length() - 1) != b.charAt(b.length() - 1)) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) if (a.charAt(i) != b.charAt(i)) diff++;
        return diff == 1;
    }

    private static String chapterTitle(Path f, int idx) {
        String n = f.getFileName().toString();
        n = n.replace(".md", "");
        return "第" + idx + "章 " + n;
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static final class Term {
        final String name;
        String type;   // 可被高优先级模式更正
        final List<String> examples = new ArrayList<>();
        int count;
        int firstChapter;
        boolean defined;
        String def = "";
        Term(String name, String type) { this.name = name; this.type = type; }
    }
}
