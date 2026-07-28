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

class ArchitectAgentTest {

    private ArchitectAgent agent;
    private ModelRouter router;
    private PipelineConfig config;
    private Book book;
    private TruthState truthState;
    private LlmClient mockClient;

    @BeforeEach
    void setup(@TempDir Path tmpDir) {
        mockClient = new LlmClient() {
            @Override public String provider() { return "mock"; }
            @Override
            public String complete(String prompt, String model, double temperature, int maxTokens) {
                return "模拟完成文本";
            }
            @Override
            public String chatComplete(List<Map<String, String>> messages, String model, double temperature, int maxTokens) {
                String lastContent = messages.get(messages.size() - 1).get("content");
                if (lastContent.contains("大纲") || lastContent.contains("架构")) {
                    return "{\"outline\": \"章节大纲\", \"chapterPlan\": \"本章计划：主角入学\"}";
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

        this.agent = new ArchitectAgent();
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
        assertEquals("Architect", agent.name());
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
    void testExecuteFirstCallSetsBookOutline() {
        // First call: no existing outline, should use buildArchitectPrompt and set book outline
        assertNull(book.getOutline());

        PipelineContext context = new PipelineContext(book, truthState, config);
        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(result.generatedText());
        assertNotNull(context.getArchitectOutput());
        // First call should set the book outline
        assertNotNull(book.getOutline());
        assertEquals(context.getArchitectOutput(), book.getOutline());
    }

    @Test
    void testExecuteIncrementalCallOnlySetsArchitectOutput() {
        // Set existing outline — triggers incremental prompt
        book.setOutline("已有的大纲内容");

        PipelineContext context = new PipelineContext(book, truthState, config);
        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getArchitectOutput());
        // Incremental call should NOT overwrite book outline
        assertEquals("已有的大纲内容", book.getOutline());
    }

    @Test
    void testExecuteLlmExceptionReturnsErrorResult() {
        LlmExceptionClient exceptionClient = new LlmExceptionClient("API调用失败");
        ModelConfig exConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        ModelRouter exRouter = new ModelRouter(exConfig);
        exRouter.registerClient("mock@https://mock.local", exceptionClient);

        ArchitectAgent exAgent = new ArchitectAgent();
        exAgent.init(exRouter);

        PipelineContext context = new PipelineContext(book, truthState, config);
        PipelineResult result = exAgent.execute(context);

        assertFalse(result.success());
        assertTrue(result.isHardFailure());
        assertTrue(result.errorMessage().contains("Agent exception"));
    }

    // --- Helper: LlmClient that throws LlmException ---
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
