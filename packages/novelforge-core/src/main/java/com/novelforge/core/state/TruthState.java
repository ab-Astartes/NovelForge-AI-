package com.novelforge.core.state;

import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
import com.novelforge.core.models.HookOp;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * TruthState — the canonical source of all book state.
 * Manages characters.json, world.json, timeline.json, and hook state.
 * All reads/writes are immutable: state files are versioned snapshots.
 * Supports incremental backup with rollback to recent versions.
 */
public class TruthState {

    private final Path bookDir;
    private final ObjectMapper mapper;
    private static final int MAX_BACKUPS = 10;

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
    public synchronized void loadAll() {
        Path truthDir = bookDir.resolve("truth");
        this.characters = new CharacterState(truthDir.resolve("characters.json"), mapper);
        this.world = new WorldState(truthDir.resolve("world.json"), mapper);
        this.timeline = new TimelineState(truthDir.resolve("timeline.json"), mapper);
        this.hooks = new HookManager(truthDir.resolve("hooks.json"), mapper);
    }

    /** Save all state files to disk */
    public synchronized void saveAll() {
        characters.save();
        world.save();
        timeline.save();
        hooks.save();
    }

    /** Save all state files and create a backup snapshot */
    public synchronized void saveAllWithBackup() {
        // First backup current state before saving
        createBackup();
        // Then save new state
        saveAll();
    }

    /** Create a backup of current state files */
    private void createBackup() {
        Path truthDir = bookDir.resolve("truth");
        Path backupDir = truthDir.resolve("backups");
        try {
            Files.createDirectories(backupDir);
            // Generate version timestamp
            String version = "v" + System.currentTimeMillis();
            Path versionDir = backupDir.resolve(version);
            Files.createDirectories(versionDir);
            // Copy each state file
            String[] stateFiles = {"characters.json", "world.json", "timeline.json", "hooks.json"};
            for (String fname : stateFiles) {
                Path src = truthDir.resolve(fname);
                if (Files.exists(src)) {
                    Files.copy(src, versionDir.resolve(fname), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // Prune old backups (keep only MAX_BACKUPS most recent)
            pruneBackups(backupDir);
        } catch (Exception e) {
            // Backup failure should not block save — log and continue
            System.err.println("[TruthState] backup failed: " + e.getMessage());
        }
    }

    /** Remove old backups, keeping only the most recent MAX_BACKUPS */
    private void pruneBackups(Path backupDir) {
        try (Stream<Path> dirs = Files.list(backupDir)
                .filter(Files::isDirectory)
                .sorted(Comparator.comparingLong((Path p) -> {
                    String name = p.getFileName().toString();
                    // Extract timestamp from "v<timestamp>"
                    try { return Long.parseLong(name.substring(1)); }
                    catch (NumberFormatException e) { return 0L; }
                }).reversed())) {
            long count = 0;
            for (Path dir : dirs.toArray(Path[]::new)) {
                count++;
                if (count > MAX_BACKUPS) {
                    // Delete old backup directory
                    try (Stream<Path> files = Files.walk(dir)) {
                        files.sorted(Comparator.reverseOrder()).forEach(f -> {
                            try { Files.delete(f); } catch (Exception ignored) {}
                        });
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /** Rollback to the most recent backup version */
    public synchronized boolean rollback() {
        Path truthDir = bookDir.resolve("truth");
        Path backupDir = truthDir.resolve("backups");
        if (!Files.exists(backupDir)) return false;
        try {
            // Find the most recent backup
            Path latest = null;
            long latestTime = 0;
            try (Stream<Path> dirs = Files.list(backupDir).filter(Files::isDirectory)) {
                for (Path dir : dirs.toArray(Path[]::new)) {
                    String name = dir.getFileName().toString();
                    try {
                        long ts = Long.parseLong(name.substring(1));
                        if (ts > latestTime) { latestTime = ts; latest = dir; }
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (latest == null) return false;
            // Copy backup files back to truth dir
            String[] stateFiles = {"characters.json", "world.json", "timeline.json", "hooks.json"};
            for (String fname : stateFiles) {
                Path src = latest.resolve(fname);
                if (Files.exists(src)) {
                    Files.copy(src, truthDir.resolve(fname), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // Reload state from rolled-back files
            loadAll();
            return true;
        } catch (Exception e) {
            System.err.println("[TruthState] rollback failed: " + e.getMessage());
            return false;
        }
    }

    /** Get list of available backup versions (timestamps) */
    public synchronized List<Long> getBackupVersions() {
        Path backupDir = bookDir.resolve("truth").resolve("backups");
        if (!Files.exists(backupDir)) return List.of();
        try (Stream<Path> dirs = Files.list(backupDir).filter(Files::isDirectory)) {
            return dirs.map(p -> {
                String name = p.getFileName().toString();
                try { return Long.parseLong(name.substring(1)); }
                catch (NumberFormatException e) { return 0L; }
            }).filter(ts -> ts > 0).sorted(Comparator.reverseOrder()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Rollback to a specific backup version by timestamp */
    public synchronized boolean rollbackTo(long timestamp) {
        Path truthDir = bookDir.resolve("truth");
        Path versionDir = truthDir.resolve("backups").resolve("v" + timestamp);
        if (!Files.exists(versionDir)) return false;
        try {
            String[] stateFiles = {"characters.json", "world.json", "timeline.json", "hooks.json"};
            for (String fname : stateFiles) {
                Path src = versionDir.resolve(fname);
                if (Files.exists(src)) {
                    Files.copy(src, truthDir.resolve(fname), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            loadAll();
            return true;
        } catch (Exception e) {
            System.err.println("[TruthState] rollbackTo(" + timestamp + ") failed: " + e.getMessage());
            return false;
        }
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
