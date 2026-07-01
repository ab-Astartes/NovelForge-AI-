package com.novelforge.core.state;

import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.HookOp;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;

/**
 * TruthState — the canonical source of all book state.
 * Manages characters.json, world.json, timeline.json, and hook state.
 * All reads/writes are immutable: state files are versioned snapshots.
 */
public class TruthState {

    private final Path bookDir;
    private final ObjectMapper mapper;

    // In-memory cached state (loaded from JSON files)
    private CharacterState characters;
    private WorldState world;
    private TimelineState timeline;
    private HookManager hooks;

    public TruthState(Path bookDir) {
        this.bookDir = bookDir;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        loadAll();
    }

    /** Load all state files from disk (creates defaults if missing) */
    public void loadAll() {
        Path truthDir = bookDir.resolve("truth");
        this.characters = new CharacterState(truthDir.resolve("characters.json"), mapper);
        this.world = new WorldState(truthDir.resolve("world.json"), mapper);
        this.timeline = new TimelineState(truthDir.resolve("timeline.json"), mapper);
        this.hooks = new HookManager(truthDir.resolve("hooks.json"), mapper);
    }

    /** Save all state files to disk */
    public void saveAll() {
        characters.save();
        world.save();
        timeline.save();
        hooks.save();
    }

    /** Apply hookOps from Reflector delta */
    public void applyHookOps(List<HookOp> ops) {
        hooks.applyOps(ops);
    }

    // --- Getters ---
    public CharacterState characters() { return characters; }
    public WorldState world() { return world; }
    public TimelineState timeline() { return timeline; }
    public HookManager hooks() { return hooks; }
}
