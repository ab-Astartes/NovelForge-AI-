package com.novelforge.core.memory;

import com.novelforge.core.llm.LlmException;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.state.TruthState;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MemoryStore — the long-term memory layer that powers cross-chapter / cross-million-word
 * consistency (the feature webnovel-writer / AI-NovelGenerator are known for).
 *
 * It slices a book + its TruthState into {@link MemoryChunk}s, embeds them with an
 * {@link EmbeddingClient} when one is available, and retrieves the most relevant
 * fragments for a given writing query.
 *
 * <b>Graceful degradation:</b> when no embedding endpoint is configured (or it fails),
 * the store falls back to lexical retrieval (Chinese bigram + token overlap) so the
 * feature is always useful offline.
 */
public class MemoryStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CHUNK_SIZE = 700;
    private static final int CHUNK_OVERLAP = 120;
    private static final int MAX_CONTEXT_CHARS = 2200;

    private final Path bookDir;
    private final EmbeddingClient embedder;        // nullable → lexical-only mode
    private final List<MemoryChunk> chunks = new ArrayList<>();
    private volatile boolean vectorEnabled = false;
    private String embeddingModel = "";

    public MemoryStore(Path bookDir, EmbeddingClient embedder) {
        this.bookDir = bookDir;
        this.embedder = embedder;
    }

    public boolean isVectorEnabled() { return vectorEnabled; }
    public int size() { return chunks.size(); }
    public List<MemoryChunk> getChunks() { return Collections.unmodifiableList(chunks); }

    // ---------------------------------------------------------------- build

    /** Slice the book + truth state into chunks and (if possible) embed them. */
    public void rebuild(Book book, TruthState state) {
        chunks.clear();
        vectorEnabled = embedder != null && embedder.isAvailable();

        // 1) Existing chapters → fine-grained text slices (most important for recall)
        if (book.getChapters() != null) {
            int idx = 0;
            for (Chapter ch : book.getChapters()) {
                String text = ch.getFinalText() != null && !ch.getFinalText().isEmpty()
                        ? ch.getFinalText() : ch.getDraftText();
                if (text == null || text.isEmpty()) continue;
                String title = ch.getTitle() != null ? ch.getTitle() : "第" + ch.getNumber() + "章";
                for (String slice : sliceText(text)) {
                    MemoryChunk c = new MemoryChunk("ch-" + ch.getNumber() + "-" + (idx++), "chapter", slice);
                    c.chapterNumber = ch.getNumber();
                    c.source = title;
                    chunks.add(c);
                }
            }
        }

        // 2) TruthState files → one chunk per entity (read raw JSON directly, robust to API changes)
        Path truthDir = bookDir.resolve("truth");
        addJsonEntityChunks(truthDir.resolve("characters.json"), "character", "characters");
        addJsonEntityChunks(truthDir.resolve("world.json"), "world",
                List.of("locations", "items", "rules", "systems"));
        addJsonArrayChunks(truthDir.resolve("timeline.json"), "timeline", "events");
        addJsonArrayChunks(truthDir.resolve("hooks.json"), "hook", "hooks");

        // 3) Embed (if a working endpoint exists)
        if (vectorEnabled) {
            try {
                for (MemoryChunk c : chunks) {
                    c.vector = embedder.embed(c.text);
                }
                embeddingModel = "(remote)";
            } catch (LlmException e) {
                vectorEnabled = false;
                for (MemoryChunk c : chunks) c.vector = null;
            }
        }
        save();
    }

    private void addJsonEntityChunks(Path file, String scope, List<String> arrays) {
        JsonNode root = readJson(file);
        if (root == null) return;
        int i = 0;
        for (String arrName : arrays) {
            JsonNode arr = root.get(arrName);
            if (arr instanceof ArrayNode) {
                for (JsonNode item : arr) {
                    String text = summarize(item);
                    if (!text.isEmpty()) {
                        MemoryChunk c = new MemoryChunk(scope + "-" + (i++), scope, text);
                        c.source = item.has("name") ? item.get("name").asText()
                                : item.has("title") ? item.get("title").asText() : scope;
                        chunks.add(c);
                    }
                }
            }
        }
    }

    private void addJsonEntityChunks(Path file, String scope, String arrayName) {
        addJsonEntityChunks(file, scope, List.of(arrayName));
    }

    private void addJsonArrayChunks(Path file, String scope, String arrayName) {
        JsonNode root = readJson(file);
        if (root == null) return;
        JsonNode arr = root.get(arrayName);
        if (!(arr instanceof ArrayNode)) return;
        int i = 0;
        for (JsonNode item : arr) {
            String text = summarize(item);
            if (!text.isEmpty()) {
                MemoryChunk c = new MemoryChunk(scope + "-" + (i++), scope, text);
                c.source = scope;
                chunks.add(c);
            }
        }
    }

    private JsonNode readJson(Path file) {
        try {
            if (!Files.exists(file)) return null;
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String summarize(JsonNode node) {
        if (node == null) return "";
        if (node.isTextual()) return node.asText();
        // Flatten key fields into a readable string
        StringBuilder sb = new StringBuilder();
        if (node.isObject()) {
            node.fields().forEachRemaining(f -> {
                String v = flattenValue(f.getValue());
                if (!v.isEmpty()) sb.append(f.getKey()).append("：").append(v).append("；");
            });
        } else {
            sb.append(node.asText());
        }
        return sb.toString();
    }

    private static String flattenValue(JsonNode v) {
        if (v == null) return "";
        if (v.isTextual()) return v.asText();
        if (v.isNumber() || v.isBoolean()) return v.asText();
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder();
            v.forEach(n -> sb.append(flattenValue(n)).append("、"));
            return sb.toString().replaceAll("、$", "");
        }
        return summarize(v);
    }

    /** Split a long text into overlapping ~CHUNK_SIZE character slices. */
    private static List<String> sliceText(String text) {
        List<String> out = new ArrayList<>();
        if (text.length() <= CHUNK_SIZE) { out.add(text); return out; }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            out.add(text.substring(start, end));
            if (end == text.length()) break;
            start += (CHUNK_SIZE - CHUNK_OVERLAP);
        }
        return out;
    }

    // ---------------------------------------------------------------- retrieve

    /**
     * Retrieve the top-k relevant fragments for a query and format them as a prompt block.
     * Returns an empty string when there is nothing to recall.
     */
    public String retrieveContext(String query, int k, Set<String> scopes) {
        if (chunks.isEmpty() || query == null || query.isBlank()) return "";
        List<MemoryChunk> candidates = scopes == null || scopes.isEmpty() ? chunks
                : chunks.stream().filter(c -> scopes.contains(c.scope)).collect(Collectors.toList());
        if (candidates.isEmpty()) return "";

        List<Scored> scored = new ArrayList<>();
        if (vectorEnabled) {
            float[] qv;
            try { qv = embedder.embed(query); }
            catch (LlmException e) { qv = null; }
            if (qv != null) {
                for (MemoryChunk c : candidates) {
                    if (c.vector == null) continue;
                    scored.add(new Scored(c, cosine(qv, c.vector)));
                }
            }
        }
        // Lexical fallback (or supplement when no vectors)
        if (scored.isEmpty()) {
            Map<String, Integer> qTokens = tokenize(query);
            for (MemoryChunk c : candidates) {
                scored.add(new Scored(c, lexicalScore(qTokens, c.text)));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int take = Math.min(k, scored.size());

        StringBuilder sb = new StringBuilder();
        sb.append("## 长程记忆召回（与本章最相关的历史片段，用于保持人设/世界观/伏笔一致性）\n");
        int total = 0;
        for (int i = 0; i < take; i++) {
            MemoryChunk c = scored.get(i).chunk;
            String block = "> " + c.shortLabel() + "\n> " + c.text.replace("\n", "\n> ");
            if (total + block.length() > MAX_CONTEXT_CHARS) break;
            sb.append(block).append("\n\n");
            total += block.length();
        }
        return sb.toString();
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Chinese-aware lexical score: CJK bigram Jaccard + ASCII token overlap. */
    private static double lexicalScore(Map<String, Integer> qTokens, String text) {
        Map<String, Integer> tTokens = tokenize(text);
        if (qTokens.isEmpty() || tTokens.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(qTokens.keySet());
        inter.retainAll(tTokens.keySet());
        Set<String> union = new HashSet<>(qTokens.keySet());
        union.addAll(tTokens.keySet());
        if (union.isEmpty()) return 0;
        return (double) inter.size() / union.size();
    }

    private static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> tokens = new HashMap<>();
        if (text == null) return tokens;
        // ASCII words
        for (String w : text.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fff]+")) {
            if (w.length() >= 2) tokens.merge(w, 1, Integer::sum);
        }
        // CJK bigrams
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) cjk.append(c); else if (cjk.length() > 0) { emitBigrams(cjk.toString(), tokens); cjk.setLength(0); }
        }
        if (cjk.length() > 0) emitBigrams(cjk.toString(), tokens);
        return tokens;
    }

    private static void emitBigrams(String s, Map<String, Integer> tokens) {
        if (s.length() == 1) { tokens.merge(s, 1, Integer::sum); return; }
        for (int i = 0; i < s.length() - 1; i++) tokens.merge(s.substring(i, i + 2), 1, Integer::sum);
    }

    private static boolean isCjk(int c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x20000 && c <= 0x2A6DF) || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static class Scored {
        final MemoryChunk chunk;
        final double score;
        Scored(MemoryChunk c, double s) { chunk = c; score = s; }
    }

    // ---------------------------------------------------------------- persist

    public void save() {
        try {
            Path dir = bookDir.resolve("truth").resolve("memory");
            Files.createDirectories(dir);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("vectorEnabled", vectorEnabled);
            root.put("embeddingModel", embeddingModel);
            ArrayNode arr = root.putArray("chunks");
            for (MemoryChunk c : chunks) {
                ObjectNode o = arr.addObject();
                o.put("id", c.id);
                o.put("scope", c.scope);
                o.put("chapterNumber", c.chapterNumber);
                o.put("source", c.source);
                o.put("text", c.text);
                if (c.vector != null) {
                    ArrayNode v = o.putArray("vector");
                    for (float f : c.vector) v.add(f);
                }
            }
            Files.writeString(dir.resolve("vectors.json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Non-fatal: memory is a best-effort enhancement
        }
    }

    public void load() {
        try {
            Path file = bookDir.resolve("truth").resolve("memory").resolve("vectors.json");
            if (!Files.exists(file)) return;
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            vectorEnabled = root.path("vectorEnabled").asBoolean(false);
            embeddingModel = root.path("embeddingModel").asText("");
            JsonNode arr = root.get("chunks");
            if (arr instanceof ArrayNode) {
                for (JsonNode n : arr) {
                    MemoryChunk c = new MemoryChunk();
                    c.id = n.path("id").asText();
                    c.scope = n.path("scope").asText();
                    c.chapterNumber = n.path("chapterNumber").asInt(-1);
                    c.source = n.path("source").asText();
                    c.text = n.path("text").asText();
                    JsonNode v = n.get("vector");
                    if (v instanceof ArrayNode && !v.isEmpty()) {
                        c.vector = new float[v.size()];
                        for (int i = 0; i < v.size(); i++) c.vector[i] = (float) v.get(i).asDouble();
                    }
                    chunks.add(c);
                }
            }
        } catch (Exception e) {
            // Ignore load failures — rebuild on next write
        }
    }
}
