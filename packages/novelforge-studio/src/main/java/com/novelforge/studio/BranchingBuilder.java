package com.novelforge.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 分支剧情 / 互动小说 —— 可视化剧情树编辑器后端（零 LLM 成本）。
 *
 * <p>读取用户编排的 {@code truth/branching.json}（节点 nodes + 选择支 edges）；若文件不存在，
 * 则按章节自动生成骨架（每章一个节点，首章为起点，含「结局」标记的章记为结局）。
 * 计算剧情树统计，并对结构问题告警：死胡同 / 不可达 / 孤立节点 / 缺少结局 / 多起点 / 环路。</p>
 */
public final class BranchingBuilder {

    private BranchingBuilder() {}

    // ===================== 主入口 =====================

    public static ObjectNode build(ObjectMapper mapper, Path bookDir) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("book", bookDir.getFileName() != null ? bookDir.getFileName().toString() : "");

        Path truth = bookDir.resolve("truth");
        Path file = truth.resolve("branching.json");

        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        boolean scaffolded = true;

        if (Files.exists(file)) {
            try {
                JsonNode root = mapper.readTree(Files.readAllBytes(file));
                JsonNode ns = root.path("nodes");
                if (ns.isArray()) for (JsonNode n : ns) nodes.add(parseNode(n));
                JsonNode es = root.path("edges");
                if (es.isArray()) for (JsonNode e : es) edges.add(parseEdge(e));
                scaffolded = false;
            } catch (Exception ignore) {
                // 解析失败 → 回退到章节骨架
            }
        }
        if (scaffolded) {
            scaffoldFromChapters(mapper, bookDir, nodes, edges);
        }

        ensureIds(nodes);
        normalizeEdges(nodes, edges);
        enrichBodies(bookDir, nodes);

        ArrayNode nArr = mapper.createArrayNode();
        for (Node n : nodes) nArr.add(n.toJson(mapper));
        ArrayNode eArr = mapper.createArrayNode();
        for (Edge e : edges) eArr.add(e.toJson(mapper));
        resp.set("nodes", nArr);
        resp.set("edges", eArr);
        resp.put("scaffolded", scaffolded);

        ObjectNode stats = computeStats(nodes, edges);
        resp.set("stats", stats);

        ObjectNode outline = analyzeOutline(bookDir, nodes, edges, mapper);
        resp.set("outline", outline);

        ArrayNode warnings = mapper.createArrayNode();
        validate(nodes, edges, warnings, mapper);
        resp.set("warnings", warnings);
        stats.put("warnings", warnings.size());

        return resp;
    }

    // ===================== 解析 =====================

    private static Node parseNode(JsonNode n) {
        Node node = new Node();
        node.id = n.path("id").asText("").trim();
        node.title = n.path("title").asText("").trim();
        node.type = n.path("type").asText("scene").trim();
        node.chapterRef = n.path("chapterRef").asInt(0);
        node.excerpt = n.path("excerpt").asText("").trim();
        node.volume = n.path("volume").asText("").trim();
        JsonNode st = n.path("state");
        if (st.isObject()) node.state = (ObjectNode) st;
        if (!VALID_TYPES.contains(node.type)) node.type = "scene";
        return node;
    }

    private static Edge parseEdge(JsonNode e) {
        Edge edge = new Edge();
        edge.from = e.path("from").asText("").trim();
        edge.to = e.path("to").asText("").trim();
        edge.choice = e.path("choice").asText("").trim();
        JsonNode req = e.path("requires");
        if (req.isObject()) edge.requires = (ObjectNode) req;
        JsonNode set = e.path("sets");
        if (set.isObject()) edge.sets = (ObjectNode) set;
        return edge;
    }

    private static final Set<String> VALID_TYPES = new HashSet<>(java.util.Arrays.asList("start", "scene", "ending"));

    /** 无 id 的节点按 n1/n2 顺序补 id；保证引用稳定 */
    private static void ensureIds(List<Node> nodes) {
        int i = 1;
        Set<String> used = new HashSet<>();
        for (Node n : nodes) {
            if (n.id == null || n.id.isEmpty() || used.contains(n.id)) {
                while (true) {
                    String cand = "n" + i++;
                    if (!used.contains(cand)) { n.id = cand; break; }
                }
            }
            used.add(n.id);
        }
    }

    /** 丢弃指向不存在节点的边，避免悬空引用 */
    private static void normalizeEdges(List<Node> nodes, List<Edge> edges) {
        Set<String> ids = new HashSet<>();
        for (Node n : nodes) ids.add(n.id);
        edges.removeIf(e -> !ids.contains(e.from) || !ids.contains(e.to) || e.from.equals(e.to));
    }

    // ===================== 章节骨架 =====================

    private static void scaffoldFromChapters(ObjectMapper mapper, Path bookDir, List<Node> nodes, List<Edge> edges) {
        Path chaptersDir = bookDir.resolve("chapters");
        int idx = 0;
        List<String> titles = new ArrayList<>();
        if (Files.isDirectory(chaptersDir)) {
            try (Stream<Path> stream = Files.list(chaptersDir)) {
                List<Path> files = stream
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .filter(p -> !p.getFileName().toString().contains(".draft."))
                        .sorted().limit(400).toList();
                for (Path f : files) {
                    idx++;
                    String text;
                    try { text = Files.readString(f, StandardCharsets.UTF_8); } catch (Exception e) { text = ""; }
                    String title = "第" + idx + "章";
                    String heading = firstHeading(text);
                    if (heading != null) title = "第" + idx + "章 · " + heading;
                    String type = "scene";
                    if (idx == 1) type = "start";
                    if (text.contains("【结局】") || text.contains("【ENDING】")
                            || text.contains("结局篇") || text.contains("最终章")) type = "ending";
                    Node n = new Node();
                    n.id = "n" + idx;
                    n.title = title;
                    n.type = type;
                    n.chapterRef = idx;
                    n.excerpt = clip(stripMarkdown(text).replaceAll("\\s+", " "), 90);
                    nodes.add(n);
                    titles.add(title);
                }
            } catch (Exception ignore) {}
        }
    }

    private static String firstHeading(String text) {
        if (text == null) return null;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.startsWith("#")) return clip(line.replaceAll("^#+\uFEFF?\\s*", ""), 24);
            if (!line.isEmpty() && line.length() <= 30 && !line.contains("。")) return clip(line, 24);
        }
        return null;
    }

    private static String stripMarkdown(String text) {
        return text.replaceAll("^#{1,6}\\s+", "")
                   .replaceAll("\\*\\*|__|\\*|_|`", "")
                   .replaceAll("^>\\s?", "")
                   .replaceAll("^---+$", "");
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    // ===================== 章节正文内联 =====================

    /** 为节点附加其关联章节的正文（不写入 branching.json，仅用于预览 / 导出互动阅读器） */
    private static void enrichBodies(Path bookDir, List<Node> nodes) {
        for (Node n : nodes) {
            String b = chapterBodyByRef(bookDir, n.chapterRef);
            n.body = b == null ? "" : b;
        }
    }

    /** 按 1-based 章节序号读取对应 chapters/chapter-NNN.md 正文 */
    private static String chapterBodyByRef(Path bookDir, int ref) {
        if (ref <= 0) return "";
        Path chaptersDir = bookDir.resolve("chapters");
        if (!Files.isDirectory(chaptersDir)) return "";
        try (Stream<Path> stream = Files.list(chaptersDir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().contains(".draft."))
                    .sorted().limit(400).toList();
            if (ref - 1 >= 0 && ref - 1 < files.size()) {
                String text = Files.readString(files.get(ref - 1), StandardCharsets.UTF_8);
                return extractChapterBody(text);
            }
        } catch (Exception ignore) {}
        return "";
    }

    /** 抽取章节正文：跳过首行标题，去除 Markdown 标记，保留段落换行 */
    private static String extractChapterBody(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (ln.startsWith("#")) { start = i + 1; break; }
            if (!ln.isEmpty()) { start = i + 1; break; }
        }
        if (start < 0) start = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (ln.isEmpty()) { sb.append("\n"); continue; }
            sb.append(stripMarkdown(ln).replaceAll("\\s+", " ").trim()).append("\n");
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    // ===================== 统计 =====================

    private static ObjectNode computeStats(List<Node> nodes, List<Edge> edges) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode stats = mapper.createObjectNode();
        int start = 0, ending = 0, scene = 0;
        for (Node n : nodes) {
            if ("start".equals(n.type)) start++;
            else if ("ending".equals(n.type)) ending++;
            else scene++;
        }
        stats.put("nodes", nodes.size());
        stats.put("edges", edges.size());
        stats.put("startCount", start);
        stats.put("endingCount", ending);
        stats.put("sceneCount", scene);

        // 可达性与纵深（从起点 BFS 分层）
        Set<String> starts = new HashSet<>();
        for (Node n : nodes) if ("start".equals(n.type)) starts.add(n.id);
        if (starts.isEmpty() && !nodes.isEmpty()) starts.add(nodes.get(0).id);
        Map<String, List<String>> adj = adjacency(nodes, edges);
        Map<String, Integer> depth = new LinkedHashMap<>();
        for (String s : starts) depth.put(s, 0);
        java.util.Queue<String> q = new java.util.ArrayDeque<>(starts);
        while (!q.isEmpty()) {
            String cur = q.poll();
            int d = depth.get(cur);
            for (String nx : adj.getOrDefault(cur, new ArrayList<>())) {
                if (!depth.containsKey(nx)) { depth.put(nx, d + 1); q.add(nx); }
            }
        }
        int reachable = depth.size();
        int maxDepth = 0;
        for (int d : depth.values()) maxDepth = Math.max(maxDepth, d);
        stats.put("reachable", reachable);
        stats.put("unreachable", nodes.size() - reachable);
        stats.put("depth", reachable == 0 ? 0 : maxDepth);

        // —— 树统计增强：最短路到结局 / 最长链 / 分支宽度 ——
        Set<String> endings = new HashSet<>();
        for (Node n : nodes) if ("ending".equals(n.type)) endings.add(n.id);
        stats.put("shortestToEnding", computeShortestToEnding(starts, adj, endings));
        stats.put("longestChain", computeLongestChain(nodes, adj, endings));

        int maxW = 0;
        Map<Integer, Integer> wd = new LinkedHashMap<>();
        for (Node n : nodes) {
            int od = adj.getOrDefault(n.id, new ArrayList<>()).size();
            maxW = Math.max(maxW, od);
            wd.put(od, wd.getOrDefault(od, 0) + 1);
        }
        stats.put("maxBranchWidth", maxW);
        int startBranch = 0;
        for (String s : starts) startBranch = Math.max(startBranch, adj.getOrDefault(s, new ArrayList<>()).size());
        stats.put("startBranch", startBranch);
        ArrayNode wdArr = mapper.createArrayNode();
        wd.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            ObjectNode o = mapper.createObjectNode();
            o.put("outdeg", e.getKey());
            o.put("count", e.getValue());
            wdArr.add(o);
        });
        stats.set("widthDist", wdArr);
        return stats;
    }

    private static Map<String, List<String>> adjacency(List<Node> nodes, List<Edge> edges) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (Node n : nodes) adj.put(n.id, new ArrayList<>());
        for (Edge e : edges) {
            if (adj.containsKey(e.from)) adj.get(e.from).add(e.to);
        }
        return adj;
    }

    /** 最短路到结局（BFS，按边数计）；无可达结局返回 -1 */
    private static int computeShortestToEnding(Set<String> starts, Map<String, List<String>> adj, Set<String> endings) {
        if (endings.isEmpty() || starts.isEmpty()) return -1;
        Map<String, Integer> dist = new LinkedHashMap<>();
        for (String s : starts) dist.put(s, 0);
        java.util.Queue<String> q = new java.util.ArrayDeque<>(starts);
        while (!q.isEmpty()) {
            String cur = q.poll();
            int d = dist.get(cur);
            if (endings.contains(cur)) return d;
            for (String nx : adj.getOrDefault(cur, new ArrayList<>())) {
                if (!dist.containsKey(nx)) { dist.put(nx, d + 1); q.add(nx); }
            }
        }
        return -1;
    }

    /** 最长链（最长简单路径，忽略回边；按边数计） */
    private static int computeLongestChain(List<Node> nodes, Map<String, List<String>> adj, Set<String> endings) {
        Map<String, Integer> memo = new LinkedHashMap<>();
        Set<String> inStack = new HashSet<>();
        int best = 0;
        for (Node n : nodes) best = Math.max(best, longestFrom(n.id, adj, endings, memo, inStack));
        return best;
    }

    private static int longestFrom(String u, Map<String, List<String>> adj, Set<String> endings,
                                   Map<String, Integer> memo, Set<String> inStack) {
        if (memo.containsKey(u)) return memo.get(u);
        if (inStack.contains(u)) return 0; // 回边，不延伸
        inStack.add(u);
        int best;
        List<String> nxt = adj.getOrDefault(u, new ArrayList<>());
        if (nxt.isEmpty() || endings.contains(u)) {
            best = 0;
        } else {
            best = 0;
            for (String v : nxt) best = Math.max(best, 1 + longestFrom(v, adj, endings, memo, inStack));
        }
        inStack.remove(u);
        memo.put(u, best);
        return best;
    }

    // ===================== 大纲联动校验 =====================

    /** 解析 outline.md：检测「卷」归属、章节区间、关键抉择点，并校验剧情树覆盖度 */
    private static ObjectNode analyzeOutline(Path bookDir, List<Node> nodes, List<Edge> edges, ObjectMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        out.put("present", false);
        ArrayNode gaps = mapper.createArrayNode();
        ArrayNode volumes = mapper.createArrayNode();
        ObjectNode volumeMap = mapper.createObjectNode();

        Path ol = bookDir.resolve("outline.md");
        if (!Files.exists(ol)) { out.set("gaps", gaps); out.set("volumes", volumes); out.set("volumeMap", volumeMap); return out; }
        out.put("present", true);
        String text;
        try { text = Files.readString(ol, StandardCharsets.UTF_8); } catch (Exception e) { text = ""; }

        // 卷归属 + 章节区间
        Map<Integer, String> chapterVolume = new LinkedHashMap<>();
        List<String> volList = new ArrayList<>();
        String curVol = "";
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String vol = detectVolume(line);
            if (vol != null) { curVol = vol; if (!volList.contains(vol)) volList.add(vol); }
            for (Integer ch : detectChapters(line)) chapterVolume.put(ch, curVol);
        }
        volList.forEach(volumes::add);

        // 节点 -> 卷
        for (Node n : nodes) {
            String v = "";
            if (n.volume != null && !n.volume.isEmpty()) v = n.volume;
            else if (n.chapterRef > 0 && chapterVolume.containsKey(n.chapterRef)) v = chapterVolume.get(n.chapterRef);
            if (!v.isEmpty()) volumeMap.put(n.id, v);
        }

        // 抉择点覆盖度
        List<String> points = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (isDecisionLine(line)) {
                String name = extractDecisionName(line);
                if (name != null && name.length() >= 2) points.add(name);
            }
        }
        List<String> corpus = new ArrayList<>();
        nodes.forEach(n -> { corpus.add(n.title); corpus.add(n.excerpt); });
        edges.forEach(e -> corpus.add(e.choice));
        for (String p : points) {
            if (!coveredBy(p, corpus)) {
                ObjectNode g = mapper.createObjectNode();
                g.put("point", p);
                g.put("hint", "大纲标记的抉择点「" + p + "」在剧情树中未找到对应节点/选择支，建议补充分支覆盖。");
                gaps.add(g);
            }
        }
        out.set("gaps", gaps);
        out.set("volumeMap", volumeMap);
        return out;
    }

    private static String detectVolume(String line) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?i)(第\\s*[一二三四五六七八九十百千0-9]+\\s*卷|卷\\s*[一二三四五六七八九十百千0-9]+|vol\\.?\\s*\\d+|part\\.?\\s*\\d+|第\\s*[一二三四五六七八九十]+\\s*部|book\\s*\\d+)");
        java.util.regex.Matcher m = p.matcher(line);
        if (m.find()) return m.group(1).replaceAll("\\s+", "");
        return null;
    }

    private static List<Integer> detectChapters(String line) {
        List<Integer> out = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?i)第?\\s*(\\d+)\\s*(?:[-~–—至]\\s*(\\d+))?\\s*章");
        java.util.regex.Matcher m = p.matcher(line);
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            if (m.group(2) != null) {
                int b = Integer.parseInt(m.group(2));
                for (int i = a; i <= b; i++) out.add(i);
            } else out.add(a);
        }
        return out;
    }

    private static boolean isDecisionLine(String line) {
        return line.matches("(?i).*(抉择|分支|选择|分歧|分歧点|关键节点|岔路|岔道|拐点|decision|branch|choice|diverge).*");
    }

    private static String extractDecisionName(String line) {
        String s = line.replaceAll("^#{1,6}\\s*", "")
                       .replaceAll("^[-*+]\\s*", "")
                       .replaceAll("^\\d+[.、)\\]]\s*", "")
                       .replaceAll("^第\\s*\\d+\\s*[章回节]\\s*", "")
                       .replaceAll("(?i)(关键抉择|关键决策|抉择|分支|选择|分歧|分歧点|关键节点|岔路|岔道|拐点|decision|branch|choice|diverge)", "")
                       .replaceAll("^[:：、.\\s]+", "")
                       .replaceAll("[：:、，。.\\s]+$", "")
                       .trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean coveredBy(String point, List<String> corpus) {
        String[] candidates = { point, stripYesNo(point) };
        for (String p : candidates) {
            if (p == null || p.length() < 2) continue;
            for (String c : corpus) if (c != null && c.contains(p)) return true;
        }
        return false;
    }

    private static String stripYesNo(String s) {
        return s.replaceAll("^(是否要?|要不要|可否|能否|该不该|应不应该)", "").trim();
    }

    // ===================== 校验告警 =====================

    private static void validate(List<Node> nodes, List<Edge> edges, ArrayNode warnings, ObjectMapper mapper) {
        if (nodes.isEmpty()) {
            addWarn(warnings, mapper, "warn", "empty", "", "尚未定义任何剧情节点，请在右侧编辑区「新增节点」或点击「从章节生成骨架」。");
            return;
        }
        Map<String, List<String>> adj = adjacency(nodes, edges);
        Map<String, Integer> indeg = new LinkedHashMap<>(), outdeg = new LinkedHashMap<>();
        for (Node n : nodes) { indeg.put(n.id, 0); outdeg.put(n.id, 0); }
        for (Edge e : edges) {
            if (outdeg.containsKey(e.from)) outdeg.put(e.from, outdeg.get(e.from) + 1);
            if (indeg.containsKey(e.to)) indeg.put(e.to, indeg.get(e.to) + 1);
        }

        // 起点集合（可达性基准）
        Set<String> starts = new HashSet<>();
        for (Node n : nodes) if ("start".equals(n.type)) starts.add(n.id);
        if (starts.isEmpty()) starts.add(nodes.get(0).id);

        // 可达集合
        Set<String> reachable = new HashSet<>(starts);
        java.util.Queue<String> q = new java.util.ArrayDeque<>(starts);
        while (!q.isEmpty()) {
            String cur = q.poll();
            for (String nx : adj.getOrDefault(cur, new ArrayList<>())) {
                if (reachable.add(nx)) q.add(nx);
            }
        }

        int startCount = starts.size();
        int endingCount = 0;
        for (Node n : nodes) {
            if ("ending".equals(n.type)) endingCount++;
            int od = outdeg.getOrDefault(n.id, 0);
            int idg = indeg.getOrDefault(n.id, 0);
            if (!"ending".equals(n.type) && od == 0) {
                addWarn(warnings, mapper, "error", "deadend", n.id,
                        "节点「" + nodeLabel(n) + "」是死胡同：无出边且非结局，读者将无路可走。请补一条选择支或改为结局。");
            }
            if (idg == 0 && od == 0) {
                addWarn(warnings, mapper, "warn", "isolated", n.id,
                        "节点「" + nodeLabel(n) + "」是孤立节点：既无入边也无出边，建议接入主线或被删除。");
            }
            if (!reachable.contains(n.id)) {
                addWarn(warnings, mapper, "warn", "unreachable", n.id,
                        "节点「" + nodeLabel(n) + "」从起点不可达：当前剧情树无法到达该节点，请补接入边。");
            }
        }
        if (endingCount == 0) {
            addWarn(warnings, mapper, "error", "noending", "",
                    "剧情树缺少结局节点（type=ending），读者永远无法通关。请至少设置一个结局。");
        }
        if (startCount > 1) {
            addWarn(warnings, mapper, "info", "multistart", "",
                    "检测到 " + startCount + " 个起点，互动小说通常只有一个入口；多起点将被视为并行开局。");
        }
        if (hasCycle(nodes, edges)) {
            addWarn(warnings, mapper, "info", "cycle", "",
                    "剧情树检测到环路（分支会回到已走过的节点）。循环结构通常合理，但请确认非笔误。");
        }
    }

    private static boolean hasCycle(List<Node> nodes, List<Edge> edges) {
        Map<String, List<String>> adj = adjacency(nodes, edges);
        Set<String> visited = new HashSet<>(), inStack = new HashSet<>();
        for (Node n : nodes) {
            if (!visited.contains(n.id)) {
                if (dfsCycle(n.id, adj, visited, inStack)) return true;
            }
        }
        return false;
    }

    private static boolean dfsCycle(String u, Map<String, List<String>> adj, Set<String> visited, Set<String> inStack) {
        visited.add(u); inStack.add(u);
        for (String v : adj.getOrDefault(u, new ArrayList<>())) {
            if (!visited.contains(v)) {
                if (dfsCycle(v, adj, visited, inStack)) return true;
            } else if (inStack.contains(v)) {
                return true;
            }
        }
        inStack.remove(u);
        return false;
    }

    private static String nodeLabel(Node n) {
        return n.title != null && !n.title.isEmpty() ? n.title : (n.id == null ? "?" : n.id);
    }

    private static void addWarn(ArrayNode warnings, ObjectMapper mapper, String level, String type, String node, String message) {
        ObjectNode w = mapper.createObjectNode();
        w.put("level", level);
        w.put("type", type);
        w.put("node", node == null ? "" : node);
        w.put("message", message);
        warnings.add(w);
    }

    // ===================== 数据模型 =====================

    private static final class Node {
        String id = "";
        String title = "";
        String type = "scene";
        int chapterRef = 0;
        String excerpt = "";
        String body = "";
        String volume = "";
        JsonNode state = null;
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode o = mapper.createObjectNode();
            o.put("id", id);
            o.put("title", title);
            o.put("type", type);
            o.put("chapterRef", chapterRef);
            o.put("excerpt", excerpt);
            o.put("body", body);
            o.put("volume", volume);
            if (state != null && state.isObject()) o.set("state", state);
            return o;
        }
    }

    private static final class Edge {
        String from = "";
        String to = "";
        String choice = "";
        JsonNode requires = null;
        JsonNode sets = null;
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode o = mapper.createObjectNode();
            o.put("from", from);
            o.put("to", to);
            o.put("choice", choice);
            if (requires != null && requires.isObject()) o.set("requires", requires);
            if (sets != null && sets.isObject()) o.set("sets", sets);
            return o;
        }
    }
}
