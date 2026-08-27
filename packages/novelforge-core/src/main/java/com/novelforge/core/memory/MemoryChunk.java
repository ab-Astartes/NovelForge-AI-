package com.novelforge.core.memory;

/**
 * A single retrievable fragment of a book's long-term memory.
 *
 * Scopes:
 *   - "chapter"  : text sliced from an already-written chapter
 *   - "character" : a character profile from characters.json
 *   - "world"     : a location / item / rule from world.json
 *   - "timeline"  : a timeline event
 *   - "hook"      : a hook / suspense thread from hooks.json
 *
 * The {@code vector} field is {@code transient} — it is materialised at runtime
 * and never serialised into the on-disk JSON (which stores raw floats instead).
 */
public class MemoryChunk {

    public String id;
    public String scope;
    public int chapterNumber = -1;          // -1 when not chapter-scoped
    public String source = "";              // human label, e.g. "第3章 风起"
    public String text;                     // the retrievable text
    public transient float[] vector;        // dense embedding (runtime only)

    public MemoryChunk() {}

    public MemoryChunk(String id, String scope, String text) {
        this.id = id;
        this.scope = scope;
        this.text = text;
    }

    public String shortLabel() {
        if (chapterNumber > 0) return "【第" + chapterNumber + "章】" + (source == null ? "" : source);
        return "【" + scope + "】" + (source == null ? "" : source);
    }
}
