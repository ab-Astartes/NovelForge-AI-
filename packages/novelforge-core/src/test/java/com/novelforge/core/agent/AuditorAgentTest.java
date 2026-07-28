package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.LlmException;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.llm.ModelRouter.ModelConfig;
import com.novelforge.core.llm.StreamHandler;
import com.novelforge.core.models.AuditResult;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.PipelineContext;
import com.novelforge.core.models.PipelineResult;
import com.novelforge.core.pipeline.PipelineConfig;
import com.novelforge.core.state.TruthState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditorAgentTest {

    private AuditorAgent agent;
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
                if (lastContent.contains("审计") || lastContent.contains("评分")) {
                    return "{\"scores\": {\"pacing.flow\": 8.0, \"dialogue.naturalness\": 7.5, " +
                           "\"world.consistency\": 8.0, \"antiAI.repetitivePatterns\": 9.0, " +
                           "\"pacing.variation\": 7.0, \"pacing.tensionCurve\": 8.0, " +
                           "\"pacing.sceneLengthBalance\": 7.0, \"pacing.transitionSmoothness\": 7.0, " +
                           "\"dialogue.characterVoice\": 8.0, \"dialogue.subtext\": 7.0, " +
                           "\"dialogue.tagVariety\": 7.0, \"dialogue.actionBeats\": 7.0, " +
                           "\"world.detailLevel\": 7.0, \"world.sensoryImmersion\": 7.0, " +
                           "\"world.powerSystemLogic\": 7.0, \"world.settingFreshness\": 7.0, " +
                           "\"outline.chapterIntentMatch\": 8.0, \"outline.hookFulfillment\": 8.0, " +
                           "\"outline.progressionDirection\": 7.0, \"outline.characterArcAlignment\": 7.0, " +
                           "\"outline.plotTwistSetup\": 7.0, " +
                           "\"style.vocabularyConsistency\": 7.0, \"style.sentenceVariety\": 7.0, " +
                           "\"style.toneConsistency\": 7.0, \"style.genreVoice\": 7.0, " +
                           "\"style.descriptionBalance\": 7.0, " +
                           "\"hook.mustAdvanceHandled\": 8.0, \"hook.newHooksPlanted\": 7.0, " +
                           "\"hook.staleDebt\": 7.0, \"hook.burstDetection\": 7.0, " +
                           "\"hook.resolutionQuality\": 7.0, " +
                           "\"antiAI.genericExpressions\": 8.0, \"antiAI.overlyBalancedStructure\": 8.0}, " +
                           "\"criticalIssues\": [], \"warnings\": []}";
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

        this.agent = new AuditorAgent();
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
        assertEquals("Auditor", agent.name());
    }

    @Test
    void testModel() {
        assertNull(agent.model());
    }

    @Test
    void testTemperature() {
        assertEquals(0.2, agent.temperature());
        // Auditor should have low temperature for objective scoring
        assertTrue(agent.temperature() <= 0.3);
    }

    @Test
    void testExecuteWithChapterDraft() {
        String chapterDraft = "这是模拟生成的章节文本。主角踏入了学院大门，心中充满期待。" +
                              "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。" +
                              "他深吸一口气，推开了教室的门。" +
                              "教授沉声说道：「欢迎来到这里。」" +
                              "夜色降临后，月光洒在院中。他问道：「这是什么地方？」导师答道：「这里是起点。」";

        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(chapterDraft);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(result.generatedText());
        assertNotNull(context.getAuditResult());
        // AuditResult should have overall score > 0
        assertTrue(context.getAuditResult().getOverallScore() > 0);
    }

    @Test
    void testExecuteNullDraftCreatesZeroScoreResult() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        // currentChapterDraft is null

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getAuditResult());
        assertEquals(0, context.getAuditResult().getOverallScore());
        assertFalse(context.getAuditResult().isPass());
        assertNotNull(context.getAuditResult().getCriticalIssues());
        assertTrue(context.getAuditResult().getCriticalIssues().contains("无章节内容"));
    }

    @Test
    void testExecuteEmptyDraftCreatesZeroScoreResult() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft("");

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getAuditResult());
        assertEquals(0, context.getAuditResult().getOverallScore());
        assertFalse(context.getAuditResult().isPass());
    }

    @Test
    void testExecuteLlmExceptionReturnsErrorResult() {
        LlmExceptionClient exceptionClient = new LlmExceptionClient("Auditor API失败");
        ModelConfig exConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        ModelRouter exRouter = new ModelRouter(exConfig);
        exRouter.registerClient("mock@https://mock.local", exceptionClient);

        AuditorAgent exAgent = new AuditorAgent();
        exAgent.init(exRouter);

        String chapterDraft = "这是模拟生成的章节文本。主角踏入了学院大门，心中充满期待。" +
                              "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。" +
                              "他深吸一口气，推开了教室的门。教授沉声说道：「欢迎来到这里。」" +
                              "夜色降临后，月光洒在院中。他问道：「这是什么地方？」";

        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(chapterDraft);

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
