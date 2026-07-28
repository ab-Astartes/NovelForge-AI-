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
import com.novelforge.core.models.RevisionPlan;
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

class ReviserAgentTest {

    private ReviserAgent agent;
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
                if (lastContent.contains("修复") || lastContent.contains("修改") || lastContent.contains("润色")) {
                    return "修复后的章节文本。主角踏入学院大门，心中充满期待。" +
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

        this.agent = new ReviserAgent();
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
        assertEquals("Reviser", agent.name());
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
    void testExecuteNoRevisionNeededWhenAuditPasses() {
        // Audit passed with high score — no revision needed
        AuditResult auditResult = new AuditResult();
        auditResult.setOverallScore(8.0);
        auditResult.setPass(true);
        auditResult.setCriticalIssues(new ArrayList<>());
        auditResult.setWarnings(new ArrayList<>());

        String originalDraft = "原始章节文本";
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(originalDraft);
        context.setAuditResult(auditResult);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        // Should return the original draft without revision
        assertEquals(originalDraft, context.getCurrentChapterDraft());
    }

    @Test
    void testExecuteRevisionNeededWhenAuditFails() {
        // Audit failed — needs revision
        AuditResult auditResult = new AuditResult();
        auditResult.setOverallScore(5.5);
        auditResult.setPass(false);
        auditResult.setCriticalIssues(List.of("节奏混乱", "对话不自然"));
        auditResult.setWarnings(new ArrayList<>());

        String originalDraft = "有问题的章节文本。主角踏入了学院大门。" +
                               "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。" +
                               "他深吸一口气，推开了教室的门。教授沉声说道：「欢迎来到这里。」" +
                               "夜色降临后，月光洒在院中。他问道：「这是什么地方？」导师答道：「这里是起点。」";
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(originalDraft);
        context.setAuditResult(auditResult);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        // Should replace currentChapterDraft with revised version
        assertNotNull(context.getCurrentChapterDraft());
        // Revised draft should differ from original (mock returns different text)
        assertTrue(context.getCurrentChapterDraft().contains("修复"));
    }

    @Test
    void testExecuteRevisionModeSelectionPolish() {
        // Score >= 7.5 with no critical issues → POLISH mode
        AuditResult auditResult = new AuditResult();
        auditResult.setOverallScore(7.5);
        auditResult.setPass(false); // still needs some revision (warnings exist)
        auditResult.setCriticalIssues(new ArrayList<>());
        auditResult.setWarnings(List.of("节奏稍慢"));

        String originalDraft = "需要润色的章节文本。主角踏入了学院大门。" +
                               "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。" +
                               "他深吸一口气，推开了教室的门。教授沉声说道：「欢迎来到这里。」";
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(originalDraft);
        context.setAuditResult(auditResult);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getCurrentChapterDraft());
    }

    @Test
    void testExecuteRevisionModeSelectionRewrite() {
        // Score < 6.0 → REWRITE mode
        AuditResult auditResult = new AuditResult();
        auditResult.setOverallScore(4.0);
        auditResult.setPass(false);
        auditResult.setCriticalIssues(List.of("角色行为矛盾", "时间线冲突"));
        auditResult.setWarnings(List.of("对话过多"));

        String originalDraft = "需要重写的章节文本。主角踏入了学院大门。" +
                               "校园里古木参天。他推开了教室的门。" +
                               "教授说道：「欢迎。」夜色降临后。他问道：「这里？」";
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(originalDraft);
        context.setAuditResult(auditResult);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getCurrentChapterDraft());
    }

    @Test
    void testExecuteRevisionModeAntiDetect() {
        // Low anti-AI scores → ANTI_DETECT mode
        AuditResult auditResult = new AuditResult();
        auditResult.setOverallScore(6.5);
        auditResult.setPass(false);
        auditResult.setCriticalIssues(List.of("对话风格单一"));
        auditResult.setWarnings(new ArrayList<>());
        Map<String, Double> dimScores = new LinkedHashMap<>();
        dimScores.put("antiAI.repetitivePatterns", 3.0); // very low
        dimScores.put("antiAI.genericExpressions", 4.0); // very low
        dimScores.put("antiAI.overlyBalancedStructure", 3.5);
        auditResult.setDimensionScores(dimScores);

        String originalDraft = "需要反检测的章节文本。主角踏入了学院大门。" +
                               "然而他却不禁期待起来。仿佛梦境一般。" +
                               "不由自主地走入了校园。淡淡的说道：「好。」" +
                               "心中一动，目光微微一闪。深深的看了校园一眼。";
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(originalDraft);
        context.setAuditResult(auditResult);

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        assertNotNull(context.getCurrentChapterDraft());
    }

    @Test
    void testExecuteNoAuditResultSkipsRevision() {
        String originalDraft = "原始章节文本";
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(originalDraft);
        // auditResult is null

        PipelineResult result = agent.execute(context);

        assertTrue(result.success());
        // Should return original draft when no audit result
        assertEquals(originalDraft, context.getCurrentChapterDraft());
    }

    @Test
    void testExecuteLlmExceptionRecoveryWithDraft() {
        LlmExceptionClient exceptionClient = new LlmExceptionClient("Reviser API失败");
        ModelConfig exConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        ModelRouter exRouter = new ModelRouter(exConfig);
        exRouter.registerClient("mock@https://mock.local", exceptionClient);

        ReviserAgent exAgent = new ReviserAgent();
        exAgent.init(exRouter);

        AuditResult auditResult = new AuditResult();
        auditResult.setOverallScore(5.0);
        auditResult.setPass(false);
        auditResult.setCriticalIssues(List.of("节奏问题"));

        String originalDraft = "原始章节文本  有问题";
        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setCurrentChapterDraft(originalDraft);
        context.setAuditResult(auditResult);

        PipelineResult result = exAgent.execute(context);

        // ReviserAgent catches exceptions and applies lightRevise as fallback
        assertTrue(result.success());
        assertTrue(result.hasWarning());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Reviser exception"));
        // currentChapterDraft should still be set (light revised)
        assertNotNull(context.getCurrentChapterDraft());
    }

    @Test
    void testExecuteLlmExceptionNoDraftHardFailure() {
        LlmExceptionClient exceptionClient = new LlmExceptionClient("Reviser API失败");
        ModelConfig exConfig = new ModelConfig("mock", "mock-model", "https://mock.local", "mock-key");
        ModelRouter exRouter = new ModelRouter(exConfig);
        exRouter.registerClient("mock@https://mock.local", exceptionClient);

        ReviserAgent exAgent = new ReviserAgent();
        exAgent.init(exRouter);

        AuditResult auditResult = new AuditResult();
        auditResult.setOverallScore(5.0);
        auditResult.setPass(false);
        auditResult.setCriticalIssues(List.of("节奏问题"));

        PipelineContext context = new PipelineContext(book, truthState, config);
        context.setAuditResult(auditResult);
        // currentChapterDraft is null → no fallback possible

        PipelineResult result = exAgent.execute(context);

        assertFalse(result.success());
        assertTrue(result.isHardFailure());
        assertTrue(result.errorMessage().contains("Reviser exception"));
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
