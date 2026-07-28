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

class ObserverAgentTest {

    private ObserverAgent agent;
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
                if (lastContent.contains("观察") || lastContent.contains("事实")) {
                    return "{\"characters\": [{\"name\": \"主角\", \"action\": \"入学\"}], " +
                           "\"worldEvents\": [{\"event\": \"开学典礼\"}]}";
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

        this.agent = new ObserverAgent();
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
        assertEquals("Observer", agent.name());
    }

    @Test
    void testModel() {
        assertNull(agent.model());
    }

    @Test
    void testTemperature() {
        assertEquals(0.5, agent.temperature());
    }

    @Test
    void testExecuteWithLongDraft() {
        // Draft with sufficient length (>= 100 chars) — should trigger LLM call
        String longDraft = "这是模拟生成的章节文本。主角踏入了学院大门，心中充满期待。" +
                           "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。" +
                           "他深吸一口气，推开了教室的门。教授沉声说道：「欢迎来到这里。」" +
                           "夜色降临后，月光洒在院中。他问道：「这是什么地方？」导师答道：「这里是起点。」";

        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(longDraft);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(result.generatedText());
        assertNotNull(context.getObserverOutput());
    }

    @Test
    void testExecuteSkipsShortDraft() {
        // Short draft (< 100 chars) — should skip observation with message
        String shortDraft = "太短的文本";

        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(shortDraft);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getObserverOutput());
        assertTrue(context.getObserverOutput().contains("观察跳过") || context.getObserverOutput().contains("过短"));
    }

    @Test
    void testExecuteNullDraft() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        // currentChapterDraft is null

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getObserverOutput());
        assertTrue(context.getObserverOutput().contains("观察跳过") || context.getObserverOutput().contains("过短"));
    }

    @Test
    void testExecuteEmptyDraft() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft("");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getObserverOutput());
        assertTrue(context.getObserverOutput().contains("观察跳过") || context.getObserverOutput().contains("过短"));
    }

    @Test
    void testExecuteLlmExceptionReturnsErrorResult() {
        LlmExceptionClient exceptionClient = new LlmExceptionClient("Observer API失败");
        ModelConfig exConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        ModelRouter exRouter = new ModelRouter(exConfig);
        exRouter.registerClient("mock@https://mock.local", exceptionClient);

        ObserverAgent exAgent = new ObserverAgent();
        exAgent.init(exRouter);

        // Need a long draft so Observer won't skip
        String longDraft = "这是模拟生成的章节文本。主角踏入了学院大门，心中充满期待。" +
                           "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。" +
                           "他深吸一口气，推开了教室的门。教授沉声说道：「欢迎来到这里。」" +
                           "夜色降临后，月光洒在院中。他问道：「这是什么地方？」导师答道：「这里是起点。」";

        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(longDraft);

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
