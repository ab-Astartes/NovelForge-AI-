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

class PlannerAgentTest {

    private PlannerAgent agent;
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
                if (lastContent.contains("节奏") || lastContent.contains("钩子")) {
                    return "{\"agenda\": \"节奏计划\", \"hooks\": [{\"id\": \"h1\", \"type\": \"suspense\"}]}";
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

        this.agent = new PlannerAgent();
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
        assertEquals("Planner", agent.name());
    }

    @Test
    void testModel() {
        assertNull(agent.model());
    }

    @Test
    void testTemperature() {
        assertEquals(0.4, agent.temperature());
    }

    @Test
    void testExecuteWithArchitectOutput() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setArchitectOutput("{\"outline\": \"章节大纲\", \"chapterPlan\": \"本章计划\"}");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(result.generatedText());
        assertNotNull(context.getPlannerOutput());
        assertTrue(context.getPlannerOutput().contains("节奏") || context.getPlannerOutput().contains("钩子"));
    }

    @Test
    void testExecuteFallbackToBookOutline() {
        // No architectOutput — should fall back to book outline
        book.setOutline("已有的大纲内容");
        PipelineContext context = new PipelineContext(book, truthState, config);
        // architectOutput is null

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getPlannerOutput());
    }

    @Test
    void testExecuteNullArchitectOutputAndNoOutline() {
        // No architectOutput and no book outline — planner should still call LLM with empty string
        PipelineContext context = new PipelineContext(book, truthState, config);

        PipelineResult result = agent.execute(context);

        // Planner doesn't have try/catch wrapping the main logic, so it will call LLM anyway
        // If LLM succeeds, result should be success
        assertTrue(result.success());
        assertNotNull(context.getPlannerOutput());
    }

    @Test
    void testExecuteLlmException() {
        LlmExceptionClient exceptionClient = new LlmExceptionClient("Planner API失败");
        ModelConfig exConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        ModelRouter exRouter = new ModelRouter(exConfig);
        exRouter.registerClient("mock@https://mock.local", exceptionClient);

        PlannerAgent exAgent = new PlannerAgent();
        exAgent.init(exRouter);

        PipelineContext context = new PipelineContext(book, truthState, config);

        // Planner doesn't have try/catch, so LlmException propagates
        assertThrows(LlmException.class, () -> exAgent.execute(context));
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
