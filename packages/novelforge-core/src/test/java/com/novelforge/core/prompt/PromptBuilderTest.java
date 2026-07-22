package com.novelforge.core.prompt;

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

class PromptBuilderTest {

    private PromptBuilder builder;
    private Book book;
    private TruthState truthState;
    private PipelineConfig config;

    @BeforeEach
    void setup(@TempDir Path tmpDir) {
        builder = new PromptBuilder();
        book = new Book();
        book.setTitle("测试小说");
        book.setGenre("xuanhuan");
        book.setAuthor("作者");
        book.setAuthorIntent("废材逆袭升级流");
        book.setOutline("第一章：觉醒\n第二章：初入宗门");

        truthState = new TruthState(tmpDir);
        config = new PipelineConfig();
    }

    @Test
    void testBuildArchitectPrompt() {
        List<Map<String, String>> msgs = builder.buildArchitectPrompt(book, truthState, config);
        assertEquals(2, msgs.size());
        assertEquals("system", msgs.get(0).get("role"));
        assertEquals("user", msgs.get(1).get("role"));
        assertTrue(msgs.get(1).get("content").contains("废材逆袭"));
        assertTrue(msgs.get(0).get("content").contains("架构师"));
    }

    @Test
    void testBuildArchitectIncrementalPrompt() {
        List<Map<String, String>> msgs = builder.buildArchitectIncrementalPrompt(book, truthState, config);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0).get("content").contains("不要重写"));
        assertTrue(msgs.get(1).get("content").contains("废材逆袭"));
    }

    @Test
    void testBuildPlannerPrompt() {
        String architectOutput = "{\"outline\": \"大纲内容\"}";
        List<Map<String, String>> msgs = builder.buildPlannerPrompt(book, truthState, architectOutput, config);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0).get("content").contains("规划师"));
        assertTrue(msgs.get(1).get("content").contains("大纲内容"));
    }

    @Test
    void testBuildComposerPrompt() {
        String plannerOutput = "规划输出";
        List<Map<String, String>> msgs = builder.buildComposerPrompt(book, truthState, plannerOutput, config);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0).get("content").contains("组装器"));
        assertTrue(msgs.get(1).get("content").contains("规划输出"));
    }

    @Test
    void testBuildWriterPrompt() {
        String composedContext = "组装好的上下文包";
        List<Map<String, String>> msgs = builder.buildWriterPrompt(book, truthState, composedContext, config);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0).get("content").contains("写手"));
        assertTrue(msgs.get(0).get("content").contains("2000")); // default word range min
        assertTrue(msgs.get(1).get("content").contains("上下文包"));
    }

    @Test
    void testBuildWriterPromptWithGenre() {
        book.setGenre("xuanhuan");
        String composedContext = "上下文";
        List<Map<String, String>> msgs = builder.buildWriterPrompt(book, truthState, composedContext, config);
        String system = msgs.get(0).get("content");
        // Should inject genre-specific rules
        assertTrue(system.contains("题材详细规则") || system.contains("玄幻") || system.contains("升级体系"));
    }

    @Test
    void testBuildObserverPrompt() {
        String draftText = "主角踏入学院大门，心中充满期待。";
        List<Map<String, String>> msgs = builder.buildObserverPrompt(book, truthState, draftText, config);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0).get("content").contains("观察员"));
        assertTrue(msgs.get(1).get("content").contains("主角踏入"));
    }

    @Test
    void testBuildReflectorPrompt() {
        String observerOutput = "{\"character_new\": [{\"name\": \"主角\"}]}";
        List<Map<String, String>> msgs = builder.buildReflectorPrompt(book, truthState, observerOutput, config);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0).get("content").contains("反射器"));
        assertTrue(msgs.get(1).get("content").contains("character_new"));
    }

    @Test
    void testBuildNormalizerPrompt() {
        String draftText = "短文本";
        List<Map<String, String>> msgs = builder.buildNormalizerPrompt(book, truthState, draftText, config);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0).get("content").contains("规范化器"));
        assertTrue(msgs.get(1).get("content").contains("短文本"));
    }

    @Test
    void testBuildAuditorPrompt() {
        String chapterText = "章节文本内容";
        List<Map<String, String>> msgs = builder.buildAuditorPrompt(book, truthState, chapterText, config);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0).get("content").contains("审计员"));
        assertTrue(msgs.get(0).get("content").contains("33"));
    }

    @Test
    void testEstimateMaxTokens() {
        // Default: 4000 words max → 4000 * 1.5 * 1.5 = 9000
        int tokens = builder.estimateMaxTokens(4000);
        assertEquals(9000, tokens);

        // Minimum: 1000 → should return at least 4000
        int minTokens = builder.estimateMaxTokens(1000);
        assertTrue(minTokens >= 4000);
    }

    @Test
    void testNullSafeAuthorIntent() {
        book.setAuthorIntent(null);
        List<Map<String, String>> msgs = builder.buildArchitectPrompt(book, truthState, config);
        assertTrue(msgs.get(1).get("content").contains("未指定"));
    }

    @Test
    void testComposerWithPreviousChapter() {
        Chapter prev = new Chapter();
        prev.setNumber(1);
        prev.setFinalText("上一章末尾内容，主角站在山巅望着远方。");
        book.getChapters().add(prev);

        String plannerOutput = "规划内容";
        List<Map<String, String>> msgs = builder.buildComposerPrompt(book, truthState, plannerOutput, config);
        assertTrue(msgs.get(1).get("content").contains("上一章末尾"));
    }

    @Test
    void testComposerWithoutPreviousChapter() {
        String plannerOutput = "规划内容";
        List<Map<String, String>> msgs = builder.buildComposerPrompt(book, truthState, plannerOutput, config);
        assertTrue(msgs.get(1).get("content").contains("第一章") || msgs.get(1).get("content").contains("无前文"));
    }
}
