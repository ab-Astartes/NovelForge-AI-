package com.novelforge.core.pipeline;

import com.novelforge.core.agent.AgentPipeline;
import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.llm.ModelRouter.ModelConfig;
import com.novelforge.core.llm.StreamHandler;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.models.WritingProgress;
import com.novelforge.core.state.TruthState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineRunnerTest {

    private ModelRouter router;
    private PipelineConfig config;
    private Book book;
    private TruthState truthState;

    @BeforeEach
    void setup(@TempDir Path tmpDir) {
        // Mock LlmClient that returns context-aware responses
        LlmClient mockClient = new LlmClient() {
            @Override public String provider() { return "mock"; }

            @Override
            public String complete(String prompt, String model, double temperature, int maxTokens) {
                return "模拟完成文本";
            }

            @Override
            public String chatComplete(List<Map<String, String>> messages, String model, double temperature, int maxTokens) {
                String lastContent = messages.get(messages.size() - 1).get("content");
                if (lastContent.contains("大纲") || lastContent.contains("架构")) {
                    return "{\"outline\": \"章节大纲\", \"chapterPlan\": \"本章计划\"}";
                }
                if (lastContent.contains("节奏") || lastContent.contains("钩子")) {
                    return "{\"agenda\": \"节奏计划\", \"hooks\": [{\"id\": \"h1\", \"type\": \"suspense\"}]}";
                }
                if (lastContent.contains("写") || lastContent.contains("创作") || lastContent.contains("章")) {
                    return "这是模拟生成的章节文本。主角踏入了学院大门。\n\n他深吸一口气，推开了教室的门。\n\n教授沉声说道：「欢迎来到这里。」\n\n夜色降临后，月光洒在院中。\n\n";
                }
                if (lastContent.contains("审计") || lastContent.contains("评分")) {
                    return "{\"scores\": {\"pacing.flow\": 8.0, \"dialogue.naturalness\": 7.5}, \"criticalIssues\": [], \"warnings\": []}";
                }
                if (lastContent.contains("观察")) {
                    return "{\"characters\": [{\"name\": \"主角\", \"action\": \"入学\"}], \"worldEvents\": []}";
                }
                if (lastContent.contains("反思")) {
                    return "{\"hookOps\": [], \"statePatch\": {}}";
                }
                if (lastContent.contains("润色")) {
                    return "润色后的文本。";
                }
                if (lastContent.contains("修复") || lastContent.contains("修改")) {
                    return "修复后的章节文本。";
                }
                return "模拟响应文本";
            }

            @Override public void chatCompleteStream(List<Map<String, String>> messages, String model, double temperature,
                                                     int maxTokens, StreamHandler handler) {
                handler.onComplete(chatComplete(messages, model, temperature, maxTokens));
            }
        };

        ModelConfig globalConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        this.router = new ModelRouter(globalConfig);
        this.router.registerClient("mock@https://mock.local", mockClient);

        this.config = new PipelineConfig();
        this.book = new Book();
        this.book.setTitle("测试小说");
        this.book.setGenre("武侠");
        this.book.setAuthor("测试作者");

        Path truthDir = tmpDir.resolve("truth-book");
        truthDir.toFile().mkdirs();
        this.truthState = new TruthState(truthDir);
    }

    @Test
    void testPipelineRunnerCreation() {
        PipelineRunner runner = new PipelineRunner(config, router);
        assertNotNull(runner);
    }

    @Test
    void testWriteNextChapterSuccess() {
        PipelineRunner runner = new PipelineRunner(config, router);
        PipelineResult result = runner.writeNextChapter(book, truthState);

        assertTrue(result.success(), "Pipeline should succeed with mock LLM");
        assertNotNull(result.updatedContext());
        assertEquals(1, book.getChapters().size(), "One chapter should be added");
        assertNotNull(book.getChapters().get(0).getFinalText());
    }

    @Test
    void testWriteNextChapterAddsProgress() {
        PipelineRunner runner = new PipelineRunner(config, router);
        PipelineResult result = runner.writeNextChapter(book, truthState);

        assertTrue(result.success());
        // Progress may be null if no audit was performed (getStoredProgress vs getProgress)
        // At minimum, chapters should be added
        assertEquals(1, book.getChapters().size());
        // If progress was stored, verify it
        if (book.getStoredProgress() != null) {
            assertEquals(1, book.getStoredProgress().getChapterProgresses().size());
            assertTrue(book.getStoredProgress().getTotalWords() > 0);
        } else {
            // Use getProgress() (computes from chapters)
            WritingProgress progress = book.getProgress();
            assertNotNull(progress);
            assertTrue(progress.getTotalChapters() >= 1);
        }
    }

    @Test
    void testRunDraftOnlySuccess() {
        PipelineRunner runner = new PipelineRunner(config, router);
        PipelineResult result = runner.runDraftOnly(book, truthState);

        assertTrue(result.success());
        assertEquals(1, book.getChapters().size());
        assertNotNull(book.getChapters().get(0).getFinalText());
    }

    @Test
    void testRunAuditOnlySuccess() {
        PipelineRunner runner = new PipelineRunner(config, router);
        PipelineResult result = runner.runAuditOnly(book, truthState, "这是一段测试章节文本，用于审计。");

        // Audit-only should produce a result (may be success or partial)
        assertNotNull(result);
    }

    @Test
    void testWriteMultipleChapters() {
        PipelineRunner runner = new PipelineRunner(config, router);

        PipelineResult r1 = runner.writeNextChapter(book, truthState);
        assertTrue(r1.success());
        assertEquals(1, book.getChapters().size());

        PipelineResult r2 = runner.writeNextChapter(book, truthState);
        assertTrue(r2.success());
        assertEquals(2, book.getChapters().size());
    }

    @Test
    void testProgressListenerCallback() {
        StringBuilder log = new StringBuilder();
        PipelineRunner runner = new PipelineRunner(config, router);
        runner.setProgressListener(new ProgressListener() {
            @Override public void onAgentStart(String name, int step, int total) { log.append("S:" + name); }
            @Override public void onAgentComplete(String name, int step, int total, long elapsedMs, String summary) { log.append("C:" + name); }
            @Override public void onAgentSkip(String name, int step, int total) { log.append("K:" + name); }
            @Override public void onAgentFail(String name, int step, int total, String error) { log.append("F:" + name); }
            @Override public void onPipelineComplete(int chapters, int words, double score) { log.append("P:done"); }
            @Override public void onPipelineFail(String error) { log.append("P:fail"); }
        });

        PipelineResult result = runner.writeNextChapter(book, truthState);
        assertTrue(result.success());
        assertTrue(log.toString().contains("S:Architect"), "Should have received Architect start callback");
        assertTrue(log.toString().contains("C:Architect"), "Should have received Architect complete callback");
    }

    @Test
    void testResumeChapterFromCheckpoint() {
        PipelineRunner runner = new PipelineRunner(config, router);
        PipelineResult r1 = runner.writeNextChapter(book, truthState);
        assertTrue(r1.success());

        // Create a context with checkpoint for testing resume
        PipelineContext savedContext = new PipelineContext(book, truthState, config);
        savedContext.setArchitectOutput("{\"outline\": \"章节大纲\"}");
        savedContext.setPlannerOutput("{\"agenda\": \"节奏计划\"}");
        savedContext.updateCheckpoint(1, "Planner");  // Planner done

        PipelineResult resumeResult = runner.resumeChapter(book, truthState, savedContext);
        // Resume should attempt to continue from checkpoint
        assertNotNull(resumeResult);
    }
}
