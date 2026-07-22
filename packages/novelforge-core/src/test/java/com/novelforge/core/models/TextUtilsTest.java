package com.novelforge.core.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextUtilsTest {

    @Test
    void testExtractJsonBlock_nullInput() {
        assertNull(TextUtils.extractJsonBlock(null));
    }

    @Test
    void testExtractJsonBlock_emptyInput() {
        assertNull(TextUtils.extractJsonBlock(""));
    }

    @Test
    void testExtractJsonBlock_plainJsonObject() {
        String text = "Here is the result: {\"name\": \"test\", \"value\": 42} and more text";
        String result = TextUtils.extractJsonBlock(text);
        assertNotNull(result);
        assertTrue(result.startsWith("{"));
        assertTrue(result.contains("\"name\""));
        assertTrue(result.endsWith("}"));
    }

    @Test
    void testExtractJsonBlock_jsonArray() {
        String text = "Results: [1, 2, 3] done";
        String result = TextUtils.extractJsonBlock(text);
        assertNotNull(result);
        assertEquals("[1, 2, 3]", result);
    }

    @Test
    void testExtractJsonBlock_codeBlock() {
        String text = "Output:\n```json\n{\"key\": \"val\"}\n```\nDone";
        String result = TextUtils.extractJsonBlock(text);
        assertNotNull(result);
        assertEquals("{\"key\": \"val\"}", result);
    }

    @Test
    void testExtractJsonBlock_nestedBrackets() {
        String text = "Result: {\"outer\": {\"inner\": [1, 2]}} end";
        String result = TextUtils.extractJsonBlock(text);
        assertNotNull(result);
        assertTrue(result.contains("\"outer\""));
        assertTrue(result.contains("\"inner\""));
    }

    @Test
    void testExtractJsonBlock_noJson() {
        assertNull(TextUtils.extractJsonBlock("Just plain text, no JSON here"));
    }

    @Test
    void testExtractJsonBlock_escapedQuotesInString() {
        String text = "{\"msg\": \"He said \\\"hello\\\"\"}";
        String result = TextUtils.extractJsonBlock(text);
        assertNotNull(result);
        assertTrue(result.contains("\\\"hello\\\""));
    }

    @Test
    void testExtractJsonBlock_bracketInString() {
        // Brackets inside JSON strings should not affect depth tracking
        String text = "{\"code\": \"{ x: 1 }\", \"ok\": true}";
        String result = TextUtils.extractJsonBlock(text);
        assertNotNull(result);
        assertTrue(result.contains("\"ok\": true"));
    }

    @Test
    void testEstimateChineseWordCount_null() {
        assertEquals(0, TextUtils.estimateChineseWordCount(null));
    }

    @Test
    void testEstimateChineseWordCount_empty() {
        assertEquals(0, TextUtils.estimateChineseWordCount(""));
    }

    @Test
    void testEstimateChineseWordCount_pureCJK() {
        // 10 CJK chars → 10 words
        assertEquals(10, TextUtils.estimateChineseWordCount("中文文字测试内容十字"));
    }

    @Test
    void testEstimateChineseWordCount_mixed() {
        // 5 CJK chars + 10 Latin → 5 + 10/5 = 7
        assertEquals(7, TextUtils.estimateChineseWordCount("中文字测试abcde12345"));
    }

    @Test
    void testTruncate_null() {
        assertEquals("（空）", TextUtils.truncate(null, 100));
    }

    @Test
    void testTruncate_shortEnough() {
        assertEquals("short", TextUtils.truncate("short", 100));
    }

    @Test
    void testTruncate_longText() {
        String longText = "A".repeat(500);
        String result = TextUtils.truncate(longText, 200);
        assertTrue(result.contains("...(已截断)"));
        assertTrue(result.length() <= 220); // truncated + suffix
    }

    @Test
    void testTruncate_breakAtNewline() {
        String text = "Line1\nLine2\n" + "X".repeat(300);
        String result = TextUtils.truncate(text, 50);
        // Should break at newline if within search range
        assertTrue(result.contains("...(已截断)") || result.length() <= 50);
    }

    @Test
    void testTruncate_breakAtSentence() {
        String text = "这是一句话。后面还有很长的内容" + "X".repeat(300);
        String result = TextUtils.truncate(text, 50);
        assertTrue(result.contains("...(已截断)") || result.length() <= 50);
    }

    @Test
    void testExtractJsonBlock_arrayBeforeObject() {
        // Array starts at index 0, object at index 5 — should pick array (lower index)
        String text = "[1,2] {\"a\":3}";
        String result = TextUtils.extractJsonBlock(text);
        assertNotNull(result);
        assertEquals("[1,2]", result);
    }

    @Test
    void testExtractJsonBlock_objectBeforeArray() {
        String text = "{\"a\":3} [1,2]";
        String result = TextUtils.extractJsonBlock(text);
        assertNotNull(result);
        assertEquals("{\"a\":3}", result);
    }
}
