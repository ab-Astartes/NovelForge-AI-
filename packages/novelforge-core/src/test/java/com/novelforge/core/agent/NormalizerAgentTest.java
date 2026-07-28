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

class NormalizerAgentTest {

    private NormalizerAgent agent;
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
                if (lastContent.contains("润色") || lastContent.contains("长度")) {
                    return "润色后的文本——和原始文本基本一致，但更流畅。主角踏入了学院大门，心中充满期待。" +
                           "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。" +
                           "他深吸一口气，推开了教室的门。教授沉声说道：「欢迎来到这里。」" +
                           "夜色降临后，月光洒在院中。他问道：「这是什么地方？」导师答道：「这里是起点。」";
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

        this.agent = new NormalizerAgent();
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
        assertEquals("Normalizer", agent.name());
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
    void testExecuteWithLongDraft() {
        // Long draft — should trigger normalizer LLM call (draft exceeds target range with tolerance)
        // Default: min=2000, max=4000. Let's make a draft that's outside the range.
        // Actually with default 2000-4000 and 20% tolerance, most short mock drafts
        // will be "within range" and skip. So we need to either make draft very short
        // or very long, or adjust config. Let's use a short draft with strict config.
        config.setChapterWordsMin(500);
        config.setChapterWordsMax(1000);

        String shortDraft = "简短的草稿文本";
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(shortDraft);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        // After normalization, currentChapterDraft should be updated
        assertNotNull(context.getCurrentChapterDraft());
        assertNotNull(context.getNormalizerOutput());
    }

    @Test
    void testExecuteNullDraftReturnsEmptyChapter() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        // currentChapterDraft is null

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertEquals("（空章节）", result.generatedText());
    }

    @Test
    void testExecuteEmptyDraftReturnsEmptyChapter() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft("");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertEquals("（空章节）", result.generatedText());
    }

    @Test
    void testExecuteDraftWithinRangeSkipsNormalization() {
        // Draft within target range (±20% tolerance) — should skip normalization
        // Default config: 2000-4000 words. Let's create a draft that fits.
        // A 200-char Chinese text ≈ ~200 "words" per estimateChineseWordCount.
        // But we need it to be within 2000-4000 range with 20% tolerance
        // i.e., between 1600-4800. That's too much text for a mock.
        // Instead, adjust config to match our mock draft's length.
        config.setChapterWordsMin(100);
        config.setChapterWordsMax(300);

        String draft = "这是模拟生成的章节文本。主角踏入了学院大门，心中充满期待。" +
                       "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。" +
                       "他深吸一口气，推开了教室的门。教授沉声说道：「欢迎来到这里。」";
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(draft);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        // Within range — should return the original draft unchanged
        assertEquals(draft, context.getCurrentChapterDraft());
    }

    @Test
    void testExecuteLlmExceptionReturnsErrorResult() {
        LlmExceptionClient exceptionClient = new LlmExceptionClient("Normalizer API失败");
        ModelConfig exConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        ModelRouter exRouter = new ModelRouter(exConfig);
        exRouter.registerClient("mock@https://mock.local", exceptionClient);

        NormalizerAgent exAgent = new NormalizerAgent();
        exAgent.init(exRouter);

        // Use config that forces normalization (draft too short)
        PipelineConfig exConfig2 = new PipelineConfig();
        exConfig2.setChapterWordsMin(500);
        exConfig2.setChapterWordsMax(1000);

        PipelineContext context = new PipelineContext(book, truthState, exConfig2);
        context.setCurrentChapterDraft("简短草稿");

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
