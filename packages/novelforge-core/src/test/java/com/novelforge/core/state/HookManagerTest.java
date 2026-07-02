package com.novelforge.core.state;

import com.novelforge.core.models.HookOp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HookManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void testUpsertHook() {
        Path file = tempDir.resolve("hooks.json");
        ObjectMapper mapper = new ObjectMapper();
        HookManager hooks = new HookManager(file, mapper);

        List<HookOp> ops = new ArrayList<>();
        HookOp op = new HookOp();
        op.setType(HookOp.Type.UPSERT);
        op.setHookId("hook-1");
        op.setDescription("主角获宝");
        op.setPriority("high");
        op.setMentionCount(1);
        op.setChapterOrigin(1);
        ops.add(op);

        hooks.applyOps(ops);
        hooks.save();

        // Reload and verify
        HookManager loaded = new HookManager(file, mapper);
        String summary = loaded.getSummary();
        assertTrue(summary.contains("hook-1"));
        assertTrue(summary.contains("主角获宝"));
    }

    @Test
    void testMentionIncrement() {
        Path file = tempDir.resolve("hooks.json");
        ObjectMapper mapper = new ObjectMapper();
        HookManager hooks = new HookManager(file, mapper);

        // First upsert
        List<HookOp> insertOps = new ArrayList<>();
        HookOp insert = new HookOp();
        insert.setType(HookOp.Type.UPSERT);
        insert.setHookId("hook-2");
        insert.setDescription("秘境入口");
        insert.setPriority("medium");
        insert.setMentionCount(1);
        insert.setChapterOrigin(2);
        insertOps.add(insert);
        hooks.applyOps(insertOps);

        // Then mention
        List<HookOp> mentionOps = new ArrayList<>();
        HookOp mention = new HookOp();
        mention.setType(HookOp.Type.MENTION);
        mention.setHookId("hook-2");
        mentionOps.add(mention);
        hooks.applyOps(mentionOps);

        String summary = hooks.getSummary();
        assertTrue(summary.contains("hook-2"));
    }

    @Test
    void testResolveHook() {
        Path file = tempDir.resolve("hooks.json");
        ObjectMapper mapper = new ObjectMapper();
        HookManager hooks = new HookManager(file, mapper);

        List<HookOp> insertOps = new ArrayList<>();
        HookOp insert = new HookOp();
        insert.setType(HookOp.Type.UPSERT);
        insert.setHookId("hook-3");
        insert.setDescription("悬念A");
        insert.setPriority("low");
        insert.setMentionCount(1);
        insert.setChapterOrigin(3);
        insertOps.add(insert);
        hooks.applyOps(insertOps);

        // Resolve: removes from active hooks
        List<HookOp> resolveOps = new ArrayList<>();
        HookOp resolve = new HookOp();
        resolve.setType(HookOp.Type.RESOLVE);
        resolve.setHookId("hook-3");
        resolveOps.add(resolve);
        hooks.applyOps(resolveOps);

        String summary = hooks.getSummary();
        assertTrue(summary.contains("活跃悬念: 0"));
    }
}
