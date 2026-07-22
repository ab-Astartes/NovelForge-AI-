package com.novelforge.core.agent;

import com.novelforge.core.llm.LlmClient;
import com.novelforge.core.llm.StreamHandler;
import com.novelforge.core.llm.ModelRouter;
import com.novelforge.core.llm.ModelRouter.ModelConfig;
import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;
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

class AgentPipelineTest {

    private ModelRouter router;
    private PipelineConfig config;
    private Book book;
    private TruthState truthState;

    @BeforeEach
    void setup(@TempDir Path tmpDir) {
        // Create mock LlmClient that returns fixed responses
        LlmClient mockClient = new LlmClient() {
            @Override public String provider() { return "mock"; }

            @Override
            public String complete(String prompt, String model, double temperature, int maxTokens) {
                return "模拟完成文本";
            }

            @Override
            public String chatComplete(List<Map<String, String>> messages, String model, double temperature, int maxTokens) {
                // Return JSON-like responses based on the last message role
                String lastRole = messages.get(messages.size() - 1).get("role");
                if (lastRole.equals("user")) {
                    String lastContent = messages.get(messages.size() - 1).get("content");
                    if (lastContent.contains("大纲") || lastContent.contains("架构")) {
                        return "{\"outline\": \"章节大纲\", \"chapterPlan\": \"本章计划：主角入学\"}";
                    }
                    if (lastContent.contains("节奏") || lastContent.contains("钩子")) {
                        return "{\"agenda\": \"节奏计划\", \"hooks\": [{\"id\": \"h1\", \"type\": \"suspense\"}]}";
                    }
                    if (lastContent.contains("写") || lastContent.contains("创作")) {
                        return "这是模拟生成的章节文本。主角踏入了学院大门，心中充满期待。\n\n" +
                               "校园里古木参天，石径蜿蜒。几名学子正低声讨论着今日的课程。\n\n" +
                               "他深吸一口气，推开了教室的门。\n\n" +
                               "教授沉声说道：「欢迎来到这里。」\n\n" +
                               "夜色降临后，月光洒在院中。他问道：「这是什么地方？」导师答道：「这里是起点。」\n\n";
                    }
                    if (lastContent.contains("审计") || lastContent.contains("评分")) {
                        return "{\"scores\": {\"pacing.flow\": 8.0, \"dialogue.naturalness\": 7.5, " +
                               "\"world.consistency\": 8.0, \"antiAI.repetitivePatterns\": 9.0}, " +
                               "\"criticalIssues\": [], \"warnings\": []}";
                    }
                    if (lastContent.contains("观察") || lastContent.contains("事实")) {
                        return "{\"characters\": [{\"name\": \"主角\", \"action\": \"入学\"}], " +
                               "\"worldEvents\": [{\"event\": \"开学典礼\"}]}";
                    }
                    if (lastContent.contains("反思") || lastContent.contains("状态")) {
                        return "{\"hookOps\": [{\"type\": \"UPSERT\", \"hookId\": \"h1\", " +
                               "\"status\": \"PLANTED\"}], \"statePatch\": {\"characters\": []}}";
                    }
                    if (lastContent.contains("润色") || lastContent.contains("长度")) {
                        return "润色后的文本——和原始文本基本一致，但更流畅。";
                    }
                    if (lastContent.contains("修复") || lastContent.contains("修改")) {
                        return "修复后的章节文本。主角踏入学院大门。";
                    }
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
        // Inject mock client directly
        this.router.getClientForAgent("Architect"); // triggers createClient — override with mock
        // We can't easily replace the created client, so we use a custom ModelRouter approach
        // Instead, let's create a test-friendly ModelRouter subclass

        this.config = new PipelineConfig();
        this.book = new Book();
        this.book.setTitle("测试小说");
        this.book.setGenre("武侠");
        this.book.setAuthor("测试作者");

        // Create truth state directory
        Path truthDir = tmpDir.resolve("truth");
        truthDir.toFile().mkdirs();
        this.truthState = new TruthState(tmpDir);
    }

    @Test
    void testPipelineResultSuccess() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        PipelineResult result = new PipelineResult(context, "测试文本", "Architect");
        assertTrue(result.success());
        assertFalse(result.isHardFailure());
        assertEquals("测试文本", result.generatedText());
        assertNotNull(result.updatedContext());
    }

    @Test
    void testPipelineResultFailure() {
        PipelineResult result = new PipelineResult("Writer", "API 调用失败");
        assertFalse(result.success());
        assertTrue(result.isHardFailure());
        assertEquals("API 调用失败", result.errorMessage());
        assertNull(result.updatedContext());
    }

    @Test
    void testPipelineResultRecovery() {
        PipelineContext context = new PipelineContext(book, truthState, config);
        PipelineResult result = PipelineResult.recovery(context, "部分文本", "Reviser", "轻量修复");
        assertTrue(result.success());
        assertTrue(result.hasWarning());
        assertEquals("轻量修复", result.errorMessage());
        assertNotNull(result.updatedContext());
    }

    @Test
    void testBookNextChapterNumber() {
        assertEquals(1, book.nextChapterNumber());

        Chapter ch1 = new Chapter();
        ch1.setNumber(1);
        ch1.setDraftText("第一章内容");
        book.getChapters().add(ch1);

        assertEquals(2, book.nextChapterNumber());
    }

    @Test
    void testAgentNamesAndOrder() {
        AgentPipeline pipeline = new AgentPipeline(router);
        String[] expectedNames = {"Architect", "Planner", "Composer", "Writer",
                                  "Observer", "Reflector", "Normalizer", "Auditor", "Reviser"};
        for (String name : expectedNames) {
            Agent agent = pipeline.getAgent(name);
            assertNotNull(agent, "Agent '" + name + "' should exist in pipeline");
            assertEquals(name, agent.name());
        }
    }

    @Test
    void testPipelineConfigClonePreservesToggles() {
        config.setRunArchitect(true);
        config.setRunPlanner(false);
        config.setRunWriter(true);
        config.setChapterWordsMin(3000);
        config.setChapterWordsMax(5000);

        PipelineConfig cloned = config.clone();
        assertTrue(cloned.isRunArchitect());
        assertFalse(cloned.isRunPlanner());
        assertTrue(cloned.isRunWriter());
        assertEquals(3000, cloned.getChapterWordsMin());
        assertEquals(5000, cloned.getChapterWordsMax());
    }

    @Test
    void testPipelineConfigCloneIsIndependent() {
        config.setRunArchitect(true);
        PipelineConfig cloned = config.clone();

        cloned.setRunArchitect(false);
        assertTrue(config.isRunArchitect(), "Original should not be affected by clone mutation");
    }

    @Test
    void testPipelineConfigAutoSwapMinMax() {
        config.setChapterWordsMin(5000);
        config.setChapterWordsMax(2000);
        // Should auto-swap so min < max
        assertTrue(config.getChapterWordsMin() <= config.getChapterWordsMax());
    }

    @Test
    void testBookBasicFields() {
        assertEquals("测试小说", book.getTitle());
        assertEquals("武侠", book.getGenre());
        assertEquals("测试作者", book.getAuthor());
    }

    @Test
    void testChapterBasicFields() {
        Chapter ch = new Chapter();
        ch.setNumber(3);
        ch.setDraftText("草稿文本");
        ch.setFinalText("最终文本");

        assertEquals(3, ch.getNumber());
        assertEquals("草稿文本", ch.getDraftText());
        assertEquals("最终文本", ch.getFinalText());
    }

    @Test
    void testAgentTemperatureRanges() {
        // Creative agents should have higher temperatures
        Agent[] agents = new Agent[] {
            new ArchitectAgent(), new PlannerAgent(), new ComposerAgent(),
            new WriterAgent(), new ObserverAgent(), new ReflectorAgent(),
            new NormalizerAgent(), new AuditorAgent(), new ReviserAgent()
        };

        for (Agent a : agents) {
            double temp = a.temperature();
            assertTrue(temp >= 0.0 && temp <= 1.0,
                a.name() + " temperature should be between 0 and 1: " + temp);
        }

        // Auditor should be low temp (objective scoring)
        assertTrue(new AuditorAgent().temperature() <= 0.3,
            "Auditor temperature should be low for objective scoring");

        // Writer should be high temp (creative generation)
        assertTrue(new WriterAgent().temperature() >= 0.5,
            "Writer temperature should be high for creative generation");
    }
}
