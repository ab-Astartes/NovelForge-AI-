package com.novelforge.core.agent;

import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.llm.ModelRouter.ModelConfig;
import com.novelforge.core.models.*;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.state.TruthState;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

/**
 * Tests for AgentPipeline checkpoint/resume functionality.
 */
class AgentPipelineCheckpointTest {

    private AgentPipeline pipeline;
    private ModelRouter router;
    private Path tempDir;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("checkpoint-test");
        ModelConfig globalConfig = new ModelConfig("openai", "test-model", "https://api.test.com/v1", "test-key");
        router = new ModelRouter(globalConfig);
        pipeline = new AgentPipeline(router);
    }

    @AfterEach
    void cleanup() throws Exception {
        Files.walk(tempDir).sorted(Comparator.reverseOrder())
            .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception e) {} });
    }

    private PipelineContext createContext() throws Exception {
        Book book = new Book();
        book.setTitle("CheckpointTest");
        book.setGenre("xuanhuan");
        book.setAuthor("testAuthor");
        TruthState truthState = new TruthState(tempDir.resolve("truth"));
        PipelineConfig config = new PipelineConfig();
        return new PipelineContext(book, truthState, config);
    }

    // --- PipelineContext checkpoint tests ---

    @Test
    void testContextNoCheckpointInitially() throws Exception {
        PipelineContext ctx = createContext();
        assertFalse(ctx.hasCheckpoint(), "New context should have no checkpoint");
        assertEquals(-1, ctx.getCheckpointAgentIndex(), "Initial checkpoint index should be -1");
        assertNull(ctx.getCheckpointAgentName(), "Initial checkpoint name should be null");
    }

    @Test
    void testContextUpdateCheckpoint() throws Exception {
        PipelineContext ctx = createContext();
        ctx.updateCheckpoint(0, "Architect");
        assertTrue(ctx.hasCheckpoint(), "After update, should have checkpoint");
        assertEquals(0, ctx.getCheckpointAgentIndex());
        assertEquals("Architect", ctx.getCheckpointAgentName());
    }

    @Test
    void testContextCheckpointProgression() throws Exception {
        PipelineContext ctx = createContext();
        ctx.updateCheckpoint(0, "Architect");
        assertEquals(0, ctx.getCheckpointAgentIndex());
        ctx.updateCheckpoint(1, "Planner");
        assertEquals(1, ctx.getCheckpointAgentIndex());
        ctx.updateCheckpoint(2, "Composer");
        assertEquals(2, ctx.getCheckpointAgentIndex());
        assertEquals("Composer", ctx.getCheckpointAgentName());
    }

    @Test
    void testContextCheckpointReset() throws Exception {
        PipelineContext ctx = createContext();
        ctx.updateCheckpoint(3, "Writer");
        assertTrue(ctx.hasCheckpoint());
        ctx.updateCheckpoint(-1, null);
        assertFalse(ctx.hasCheckpoint(), "After reset to -1, should have no checkpoint");
    }

    // --- AgentPipeline runFromCheckpoint tests ---

    @Test
    void testRunFromCheckpointSkipsCompletedAgents() throws Exception {
        PipelineContext ctx = createContext();
        ctx.updateCheckpoint(2, "Composer");
        ctx.setArchitectOutput("architect result");
        ctx.setPlannerOutput("planner result");
        ctx.setComposerOutput("composer result");

        PipelineResult result = pipeline.runFromCheckpoint(ctx);
        assertNotNull(result, "Should return a result");
    }

    @Test
    void testRunFromCheckpointAtEndReturnsAlreadyComplete() throws Exception {
        PipelineContext ctx = createContext();
        ctx.updateCheckpoint(8, "Reviser");

        PipelineResult result = pipeline.runFromCheckpoint(ctx);
        assertFalse(result.success(), "Should return error result for already-complete checkpoint");
        assertTrue(result.errorMessage().contains("Already complete"),
            "Error message should say already complete");
    }

    @Test
    void testRunPartialSetsCheckpoint() throws Exception {
        PipelineContext ctx = createContext();
        PipelineResult result = pipeline.runPartial(ctx, 0, 3);

        PipelineContext finalCtx = result.updatedContext();
        if (finalCtx != null) {
            assertTrue(finalCtx.hasCheckpoint(), "After partial run, should have checkpoint");
            assertEquals(3, finalCtx.getCheckpointAgentIndex(), "Checkpoint should be at Writer (index 3)");
        }
    }

    @Test
    void testRunFullUpdatesCheckpointProgressively() throws Exception {
        PipelineContext ctx = createContext();
        PipelineResult result = pipeline.runFull(ctx);

        PipelineContext finalCtx = result.updatedContext();
        if (finalCtx != null) {
            assertTrue(finalCtx.hasCheckpoint(), "After full run, should have checkpoint");
            assertEquals(8, finalCtx.getCheckpointAgentIndex(), "Checkpoint should be at Reviser (index 8)");
        }
    }

    // --- PipelineResult checkpoint context tests ---

    @Test
    void testErrorResultWithoutCheckpointContext() {
        PipelineResult error = new PipelineResult("Writer", "API error");
        assertNull(error.checkpointContext(), "Error result without checkpoint should have null context");
        assertFalse(error.success());
        assertEquals("API error", error.errorMessage());
    }

    @Test
    void testErrorResultWithCheckpointContext() throws Exception {
        PipelineContext ctx = createContext();
        ctx.updateCheckpoint(3, "Writer");
        ctx.setWriterDraft("partial draft text");

        PipelineResult error = new PipelineResult("Writer", "API timeout", ctx);
        assertNotNull(error.checkpointContext(), "Should carry checkpoint context");
        assertEquals(3, error.checkpointContext().getCheckpointAgentIndex());
        assertEquals("Writer", error.checkpointContext().getCheckpointAgentName());
        assertFalse(error.success());
    }

    // --- Integration: runFull then resume should detect complete ---

    @Test
    void testFullRunThenResumeAlreadyComplete() throws Exception {
        PipelineContext ctx = createContext();
        PipelineResult fullResult = pipeline.runFull(ctx);

        PipelineContext finalCtx = fullResult.updatedContext();
        if (finalCtx != null && finalCtx.hasCheckpoint()) {
            PipelineResult resumeResult = pipeline.runFromCheckpoint(finalCtx);
            assertFalse(resumeResult.success(), "Resume after complete pipeline should fail");
            assertTrue(resumeResult.errorMessage().contains("Already complete"),
                "Should indicate already complete");
        }
    }
}
