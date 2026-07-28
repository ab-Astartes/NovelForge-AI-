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

class WriterAgentTest {

    private WriterAgent agent;
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
                if (lastContent.contains("写") || lastContent.contains("创作")) {
                    return "这是模拟生成的章节文本。主角踏入了学院大门，心中充满期待。\n\n" +
                           "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。\n\n" +
                           "他深吸一口气，推开了教室的门。\n\n" +
                           "教授沉声说道：「欢迎来到这里。」\n\n" +
                           "夜色降临后，月光洒在院中。他问道：「这是什么地方？」导师答道：「这里是起点。」\n\n";
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

        this.agent = new WriterAgent();
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
        assertEquals("Writer", agent.name());
    }

    @Test
    void testModel() {
        assertNull(agent.model());
    }

    @Test
    void testTemperature() {
        assertEquals(0.7, agent.temperature());
        // Writer should have high temperature for creative generation
        assertTrue(agent.temperature() >= 0.5);
    }

    @Test
    void testExecuteWithComposerOutput() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setComposerOutput("组装好的写作上下文包：角色信息+世界观+Hook agenda");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(result.generatedText());
        assertNotNull(context.getCurrentChapterDraft());
        assertNotNull(context.getWriterDraft());
        // Writer draft and currentChapterDraft should be the same
        assertEquals(context.getCurrentChapterDraft(), context.getWriterDraft());
    }

    @Test
    void testExecuteFallbackChain() {
        // No composer output → fallback to plannerOutput
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setPlannerOutput("{\"agenda\": \"节奏计划\"}");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getCurrentChapterDraft());
    }

    @Test
    void testExecuteFallbackToArchitectOutput() {
        // No composer/planner → fallback to architectOutput
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setArchitectOutput("{\"outline\": \"章节大纲\"}");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getCurrentChapterDraft());
    }

    @Test
    void testExecuteFallbackToMinimalContext() {
        // No outputs at all — should use minimal context
        PipelineContext context = new PipelineContext(book, truthState, config);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getCurrentChapterDraft());
    }

    @Test
    void testExecuteLlmExceptionReturnsErrorResult() {
        LlmExceptionClient exceptionClient = new LlmExceptionClient("Writer API失败");
        ModelConfig exConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        ModelRouter exRouter = new ModelRouter(exConfig);
        exRouter.registerClient("mock@https://mock.local", exceptionClient);

        WriterAgent exAgent = new WriterAgent();
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
