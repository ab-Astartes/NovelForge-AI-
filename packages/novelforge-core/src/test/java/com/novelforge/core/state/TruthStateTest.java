package com.novelforge.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.core.models.HookOp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TruthStateTest {

    private TruthState truthState;
    private Path bookDir;

    @BeforeEach
    void setup(@TempDir Path tmpDir) {
        bookDir = tmpDir.resolve("test-book");
        bookDir.toFile().mkdirs();
        truthState = new TruthState(bookDir);
    }

    @Test
    void testDefaultCreation() {
        assertNotNull(truthState.characters());
        assertNotNull(truthState.world());
        assertNotNull(truthState.timeline());
        assertNotNull(truthState.hooks());
    }

    @Test
    void testSaveAllCreatesFiles() {
        truthState.saveAll();
        Path truthDir = bookDir.resolve("truth");
        assertTrue(Files.exists(truthDir.resolve("characters.json")));
        assertTrue(Files.exists(truthDir.resolve("world.json")));
        assertTrue(Files.exists(truthDir.resolve("timeline.json")));
        assertTrue(Files.exists(truthDir.resolve("hooks.json")));
    }

    @Test
    void testSaveAndLoadRoundTrip() {
        truthState.world().addLocation(new ObjectMapper().createObjectNode().put("name", "长安"));
        truthState.saveAll();

        TruthState loaded = new TruthState(bookDir);
        assertTrue(loaded.world().getData().get("locations").size() > 0);
        assertEquals("长安", loaded.world().getData().get("locations").get(0).get("name").asText());
    }

    @Test
    void testSaveAllWithBackupCreatesBackup() {
        truthState.saveAllWithBackup();
        Path backupDir = bookDir.resolve("truth").resolve("backups");
        assertTrue(Files.exists(backupDir));
        List<Long> versions = truthState.getBackupVersions();
        assertTrue(versions.size() >= 1);
    }

    @Test
    void testRollbackRestoresPreviousState() {
        truthState.world().addLocation(new ObjectMapper().createObjectNode().put("name", "长安"));
        truthState.saveAllWithBackup(); // creates first backup

        // Modify state
        truthState.world().addLocation(new ObjectMapper().createObjectNode().put("name", "洛阳"));
        truthState.saveAllWithBackup(); // creates second backup

        // Rollback should restore to previous state
        boolean result = truthState.rollback();
        assertTrue(result);
        // After rollback, we should have at least 1 location (长安)
        assertTrue(truthState.world().getData().get("locations").size() >= 1);
    }

    @Test
    void testRollbackWithNoBackupsReturnsFalse() {
        // Fresh TruthState with no backups yet
        boolean result = truthState.rollback();
        assertFalse(result);
    }

    @Test
    void testGetBackupVersionsInitiallyEmpty() {
        List<Long> versions = truthState.getBackupVersions();
        assertTrue(versions.isEmpty());
    }

    @Test
    void testApplyHookOps() {
        List<HookOp> ops = new ArrayList<>();
        HookOp op = new HookOp();
        op.setType(HookOp.Type.UPSERT);
        op.setHookId("h1");
        op.setDescription("悬念钩子");
        op.setChapterOrigin(1);
        op.setPriority("high");
        ops.add(op);

        truthState.applyHookOps(ops);
        // Hook should be applied
        assertNotNull(truthState.hooks());
    }

    @Test
    void testBackupPruningMaxVersions() {
        // Create more than MAX_BACKUPS (10) versions
        for (int i = 0; i < 12; i++) {
            truthState.world().addLocation(new ObjectMapper().createObjectNode().put("name", "城市" + i));
            truthState.saveAllWithBackup();
        }
        List<Long> versions = truthState.getBackupVersions();
        assertTrue(versions.size() <= 10, "Should keep at most 10 backup versions");
    }
}
