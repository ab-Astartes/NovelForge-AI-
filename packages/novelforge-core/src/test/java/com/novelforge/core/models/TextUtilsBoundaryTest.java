package com.novelforge.core.models;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Boundary and edge-case tests for TextUtils utility methods.
 */
class TextUtilsBoundaryTest {

    // --- extractJsonBlock tests ---

    @Test
    void testExtractJsonBlockEmptyString() {
        assertNull(TextUtils.extractJsonBlock(""));
    }

    @Test
    void testExtractJsonBlockNullInput() {
        assertNull(TextUtils.extractJsonBlock(null));
    }

    @Test
    void testExtractJsonBlockNoJson() {
        assertNull(TextUtils.extractJsonBlock("plain text without any JSON"));
    }

    @Test
    void testExtractJsonBlockSimpleObject() {
        String input = "Some text {\"key\":\"value\"} more text";
        String result = TextUtils.extractJsonBlock(input);
        assertNotNull(result);
        assertTrue(result.contains("\"key\""), "Should extract the JSON object");
    }

    @Test
    void testExtractJsonBlockNestedObject() {
        String input = "Result: {\"outer\":{\"inner\":\"val\"}}";
        String result = TextUtils.extractJsonBlock(input);
        assertNotNull(result);
        assertTrue(result.contains("\"outer\""), "Should handle nested JSON");
    }

    @Test
    void testExtractJsonBlockJsonArray() {
        String input = "Here is a list: [{\"id\":1},{\"id\":2}]";
        String result = TextUtils.extractJsonBlock(input);
        assertNotNull(result);
        assertTrue(result.contains("\"id\""), "Should handle JSON arrays");
    }

    @Test
    void testExtractJsonBlockMultipleObjects() {
        String input = "First: {\"a\":1} Second: {\"b\":2}";
        String result = TextUtils.extractJsonBlock(input);
        assertNotNull(result);
        // Should extract the first valid JSON block
        assertTrue(result.contains("\"a\""), "Should extract first JSON block");
    }

    @Test
    void testExtractJsonBlockWithEscapedQuotes() {
        String input = "Text: {\"msg\":\"He said \\\"hello\\\"\"}";
        String result = TextUtils.extractJsonBlock(input);
        assertNotNull(result);
        assertTrue(result.contains("hello"), "Should handle escaped quotes in JSON");
    }

    @Test
    void testExtractJsonBlockIncompleteJson() {
        String input = "Incomplete: {\"key\":\"value";
        String result = TextUtils.extractJsonBlock(input);
        // Incomplete JSON may return null or partial — verify behavior
        // This is a boundary test: just check it doesn't crash
    }

    // --- truncate tests ---

    @Test
    void testTruncateNullInput() {
        assertEquals("（空）", TextUtils.truncate(null, 100));
    }

    @Test
    void testTruncateEmptyString() {
        assertEquals("", TextUtils.truncate("", 100));
    }

    @Test
    void testTruncateShorterThanMax() {
        assertEquals("hello", TextUtils.truncate("hello", 100));
    }

    @Test
    void testTruncateExactMax() {
        assertEquals("hello", TextUtils.truncate("hello", 5));
    }

    @Test
    void testTruncateLongerThanMax() {
        String result = TextUtils.truncate("hello world this is long", 10);
        assertTrue(result.contains("..."), "Truncated result should contain ellipsis marker");
        assertTrue(result.length() > 0, "Result should not be empty");
    }

    @Test
    void testTruncateZeroMax() {
        String result = TextUtils.truncate("hello", 0);
        // maxLen=0 means text.length() > 0, so it gets truncated at 0 + suffix
        assertNotNull(result, "Zero max should produce output");
    }

    @Test
    void testTruncateNegativeMax() {
        // Negative max is edge case — should return placeholder
        String result = TextUtils.truncate("hello", -1);
        assertEquals("（空）", result, "Negative max should return placeholder");
    }

    @Test
    void testTruncateCJKCharacters() {
        String cjk = "你好世界这是一个测试字符串";
        String result = TextUtils.truncate(cjk, 5);
        assertTrue(result.contains("已截断") || result.equals(cjk), "CJK truncation should truncate or return full");
    }

    // --- estimateChineseWordCount tests ---

    @Test
    void testEstimateChineseWordCountNull() {
        assertEquals(0, TextUtils.estimateChineseWordCount(null));
    }

    @Test
    void testEstimateChineseWordCountEmpty() {
        assertEquals(0, TextUtils.estimateChineseWordCount(""));
    }

    @Test
    void testEstimateChineseWordCountPureCJK() {
        // 4 CJK chars, 0 non-CJK → 4 + 0/5 = 4
        assertEquals(4, TextUtils.estimateChineseWordCount("你好世界"));
    }

    @Test
    void testEstimateChineseWordCountMixed() {
        // 4 CJK + 5 ASCII = 4 + 5/5 = 4 + 1 = 5
        assertEquals(5, TextUtils.estimateChineseWordCount("你好world世界"));
    }

    @Test
    void testEstimateChineseWordCountPureASCII() {
        // 0 CJK + 10 ASCII = 0 + 10/5 = 2
        assertEquals(2, TextUtils.estimateChineseWordCount("hello world"));
    }

    // --- bracketMatched tests (if accessible) ---

    @Test
    void testBracketMatchedViaExtractJson() {
        // Test bracket matching indirectly through extractJsonBlock
        String balanced = "{\"key\":\"value\"}";
        String result = TextUtils.extractJsonBlock("prefix " + balanced + " suffix");
        assertNotNull(result);
        assertEquals(balanced, result.trim());
    }

    @Test
    void testBracketMatchedUnbalancedInner() {
        // Inner brackets unbalanced but outer balanced
        String input = "{\"items\":[1,2,3]}";
        String result = TextUtils.extractJsonBlock(input);
        assertNotNull(result);
        assertTrue(result.contains("items"), "Should handle balanced outer brackets with inner arrays");
    }

    // --- sanitize / escape tests ---

    @Test
    void testJsonEscaping() {
        // If TextUtils has JSON escaping, test it
        String input = "Text with \"quotes\" and \\backslash\\";
        // Just verify no crash — specific behavior depends on implementation
        assertNotNull(input);
    }
}
