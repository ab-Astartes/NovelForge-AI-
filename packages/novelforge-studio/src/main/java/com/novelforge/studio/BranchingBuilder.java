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

        ArrayNode nArr = mapper.createArrayNode();
        for (Node n : nodes) nArr.add(n.toJson(mapper));
        ArrayNode eArr = mapper.createArrayNode();
        for (Edge e : edges) eArr.add(e.toJson(mapper));
        resp.set("nodes", nArr);
        resp.set("edges", eArr);
        resp.put("scaffolded", scaffolded);

        ObjectNode stats = computeStats(nodes, edges);
        resp.set("stats", stats);

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
        if (!VALID_TYPES.contains(node.type)) node.type = "scene";
        return node;
    }

    private static Edge parseEdge(JsonNode e) {
        Edge edge = new Edge();
        edge.from = e.path("from").asText("").trim();
        edge.to = e.path("to").asText("").trim();
        edge.choice = e.path("choice").asText("").trim();
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
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode o = mapper.createObjectNode();
            o.put("id", id);
            o.put("title", title);
            o.put("type", type);
            o.put("chapterRef", chapterRef);
            o.put("excerpt", excerpt);
            return o;
        }
    }

    private static final class Edge {
        String from = "";
        String to = "";
        String choice = "";
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode o = mapper.createObjectNode();
            o.put("from", from);
            o.put("to", to);
            o.put("choice", choice);
            return o;
        }
    }
}
