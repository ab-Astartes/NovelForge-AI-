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

class ComposerAgentTest {

    private ComposerAgent agent;
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
                if (lastContent.contains("组装") || lastContent.contains("上下文")) {
                    return "{\"contextPackage\": \"角色信息+世界观+Hook agenda+题材规则\", \"chapterIntent\": \"本章计划\"}";
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

        this.agent = new ComposerAgent();
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
        assertEquals("Composer", agent.name());
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
    void testExecuteWithPlannerOutput() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setPlannerOutput("{\"agenda\": \"节奏计划\", \"hooks\": [{\"id\": \"h1\"}]}");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(result.generatedText());
        assertNotNull(context.getComposerOutput());
    }

    @Test
    void testExecuteFallbackToArchitectOutput() {
        // No plannerOutput — should fall back to architectOutput
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setArchitectOutput("{\"outline\": \"章节大纲\"}");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getComposerOutput());
    }

    @Test
    void testExecuteFallbackToMinimalContext() {
        // No planner or architect output — should use minimal context
        PipelineContext context = new PipelineContext(book, truthState, config);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getComposerOutput());
    }

    @Test
    void testExecuteLlmExceptionReturnsErrorResult() {
        LlmExceptionClient exceptionClient = new LlmExceptionClient("Composer API失败");
        ModelConfig exConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        ModelRouter exRouter = new ModelRouter(exConfig);
        exRouter.registerClient("mock@https://mock.local", exceptionClient);

        ComposerAgent exAgent = new ComposerAgent();
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
