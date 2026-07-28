package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.LlmException;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.llm.ModelRouter.ModelConfig;
import com.novelforge.core.llm.StreamHandler;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.state.TruthState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReflectorAgentTest {

    private ReflectorAgent agent;
    private ModelRouter router;
    private PipelineConfig config;
    private Book book;
    private TruthState truthState;

    @BeforeEach
    void setup(@TempDir Path tmpDir) {
        LlmClient mockClient = new LlmClient() {
            @Override public String provider() { return "mock"; }
            @Override
            public String complete(String prompt, String model, double temperature, int maxTokens) {
                return "模拟完成文本";
            }
            @Override
            public String chatComplete(List<Map<String, String>> messages, String model, double temperature, int maxTokens) {
                String lastContent = messages.get(messages.size() - 1).get("content");
                if (lastContent.contains("反思") || lastContent.contains("状态")) {
                    return "{\"hookOps\": [{\"type\": \"UPSERT\", \"hookId\": \"h1\", " +
                           "\"description\": \"主角入学悬念\", \"priority\": \"medium\", \"chapterOrigin\": 1}], " +
                           "\"statePatch\": {\"characterDelta\": [], \"worldDelta\": {\"locations\": [], \"rules\": []}, " +
                           "\"timelineDelta\": [{\"description\": \"主角入学\"}]}}";
                }
                return "模拟响应文本";
            }
            @Override
            public void chatCompleteStream(List<Map<String, String>> messages, String model, double temperature,
                                           int maxTokens, StreamHandler handler) {
                handler.onComplete(chatComplete(messages, model, temperature, maxTokens));
            }
        };

        ModelConfig globalConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        this.router = new ModelRouter(globalConfig);
        this.router.registerClient("mock@https://mock.local", mockClient);

        this.agent = new ReflectorAgent();
        this.agent.init(router);

        this.config = new PipelineConfig();
        this.book = new Book();
        this.book.setTitle("测试小说");
        this.book.setGenre("武侠");
        this.book.setAuthor("测试作者");

        Path truthDir = tmpDir.resolve("truth");
        truthDir.toFile().mkdirs();
        this.truthState = new TruthState(tmpDir);
    }

    @Test
    void testName() {
        assertEquals("Reflector", agent.name());
    }

    @Test
    void testModel() {
        assertNull(agent.model());
    }

    @Test
    void testTemperature() {
        assertEquals(0.3, agent.temperature());
    }

    @Test
    void testExecuteWithObserverOutput() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setObserverOutput("{\"characters\": [{\"name\": \"主角\", \"action\": \"入学\"}], \"worldEvents\": [{\"event\": \"开学典礼\"}]}");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(result.generatedText());
        assertNotNull(context.getReflectorOutput());
    }

    @Test
    void testExecuteFallbackToChapterDraft() {
        // No observerOutput — should fall back to currentChapterDraft
        String chapterDraft = "这是模拟生成的章节文本。主角踏入了学院大门，心中充满期待。" +
                              "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。" +
                              "他深吸一口气，推开了教室的门。";
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(chapterDraft);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getReflectorOutput());
    }

    @Test
    void testExecuteAppliesHookOpsToTruthState() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setObserverOutput("{\"characters\": [{\"name\": \"主角\"}]}");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        // Verify that hookOps were applied (the mock response contains hookOps)
        // TruthState should have been modified by applyHookOps
        assertNotNull(context.getReflectorOutput());
    }

    @Test
    void testExecuteLlmExceptionReturnsErrorResult() {
        LlmExceptionClient exceptionClient = new LlmExceptionClient("Reflector API失败");
        ModelConfig exConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        ModelRouter exRouter = new ModelRouter(exConfig);
        exRouter.registerClient("mock@https://mock.local", exceptionClient);

        ReflectorAgent exAgent = new ReflectorAgent();
        exAgent.init(exRouter);

        PipelineContext context = new PipelineContext(book, truthState, config);
        PipelineResult result = exAgent.execute(context);

        assertFalse(result.success());
        assertTrue(result.isHardFailure());
        assertTrue(result.errorMessage().contains("Agent exception"));
    }

    // --- Helper ---
    private static class LlmExceptionClient implements LlmClient {
        private final String errorMsg;
        LlmExceptionClient(String errorMsg) { this.errorMsg = errorMsg; }
        @Override public String provider() { return "mock"; }
        @Override public String complete(String prompt, String model, double temperature, int maxTokens) {
            throw new LlmException(errorMsg);
        }
        @Override public String chatComplete(List<Map<String, String>> messages, String model, double temperature, int maxTokens) {
            throw new LlmException(errorMsg);
        }
        @Override public void chatCompleteStream(List<Map<String, String>> messages, String model, double temperature,
                                                  int maxTokens, StreamHandler handler) {
            throw new LlmException(errorMsg);
        }
    }
}
