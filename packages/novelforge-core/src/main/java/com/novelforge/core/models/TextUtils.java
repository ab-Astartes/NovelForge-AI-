package com.novelforge.core.models;

/**
 * TextUtils — shared utility methods for text processing.
 * Replaces duplicated extractJson and estimateWordCount implementations.
 */
public final class TextUtils {

    private TextUtils() {} // no instances

    /**
     * Extract the first complete JSON object from text using bracket matching.
     * Handles nested JSON correctly (unlike indexOf('{') + lastIndexOf('}')).
     * Also handles ```json code blocks.
     */
    public static String extractJsonBlock(String text) {
        if (text == null || text.isEmpty()) return null;

        // Try ```json code block first
        int codeStart = text.indexOf("```json");
        if (codeStart >= 0) {
            int contentStart = text.indexOf('\n', codeStart) + 1;
            int codeEnd = text.indexOf("```", contentStart);
            if (codeEnd > contentStart) {
                String block = text.substring(contentStart, codeEnd).trim();
                // Validate it's a complete JSON object
                if (block.startsWith("{") && bracketMatched(block)) return block;
            }
        }

        // Find the first '{' and use bracket matching to find its matching '}'
        int jsonStart = text.indexOf('{');
        if (jsonStart < 0) return null;

        int depth = 0;
        int jsonEnd = -1;
        boolean inString = false;
        char prev = '\0';

        for (int i = jsonStart; i < text.length(); i++) {
            char c = text.charAt(i);

            // Handle string escaping: only count escapes inside strings
            if (inString) {
                if (prev == '\\') {
                    prev = c; // skip escaped char
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                prev = c;
                continue;
            }

            // Not inside a string
            if (c == '"') {
                inString = true;
                prev = c;
                continue;
            }

            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    jsonEnd = i;
                    break; // found the matching closing brace
                }
            }
            prev = c;
        }

        if (jsonEnd >= 0) return text.substring(jsonStart, jsonEnd + 1);
        return null;
    }

    /** Check if a string has balanced curly braces (quick validation) */
    private static boolean bracketMatched(String s) {
        int depth = 0;
        boolean inString = false;
        char prev = '\0';
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (prev == '\\') { prev = c; continue; }
                if (c == '"') inString = false;
                prev = c;
                continue;
            }
            if (c == '"') { inString = true; prev = c; continue; }
            if (c == '{') depth++;
            else if (c == '}') depth--;
            prev = c;
        }
        return depth == 0;
    }

    /**
     * Estimate Chinese word count (CJK characters count as 1 word each,
     * non-CJK characters count as 1/5 word each).
     */
    public static int estimateChineseWordCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) ||    // CJK Unified
                (c >= 0x3400 && c <= 0x4DBF) ||    // Extension A
                (c >= 0x20000 && c <= 0x2A6DF) ||   // Extension B
                (c >= 0x3000 && c <= 0x303F) ||     // CJK Symbols
                (c >= 0xFF00 && c <= 0xFFEF)) {     // Halfwidth/Fullwidth
                cjk++;
            }
        }
        return cjk + (text.length() - cjk) / 5;
    }

    /**
     * Smart truncate — tries to break at newline or sentence boundary.
     * Falls back to hard cut only if no good boundary exists nearby.
     */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "（空）";
        if (text.length() <= maxLen) return text;

        // Try newline within last 20% of maxLen
        int searchStart = (int) (maxLen * 0.8);
        for (int i = maxLen; i > searchStart && i < text.length(); i--) {
            if (text.charAt(i) == '\n') return text.substring(0, i) + "\n...(已截断)";
        }

        // Try sentence boundary (. ! ? within last 10%)
        searchStart = (int) (maxLen * 0.9);
        for (int i = maxLen; i > searchStart && i < text.length(); i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？') {
                return text.substring(0, i + 1) + "\n...(已截断)";
            }
        }

        return text.substring(0, maxLen) + "...(已截断)";
    }
}
