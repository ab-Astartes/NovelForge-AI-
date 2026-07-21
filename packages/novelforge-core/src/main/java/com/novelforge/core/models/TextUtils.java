package com.novelforge.core.models;

/**
 * TextUtils — shared utility methods for text processing.
 * Replaces duplicated extractJson and estimateWordCount implementations.
 */
public final class TextUtils {

    private TextUtils() {} // no instances

    /**
     * Extract the first complete JSON object or array from text using bracket matching.
     * Handles nested JSON correctly (unlike indexOf('{') + lastIndexOf('}')).
     * Also handles ```json code blocks.
     * Supports both JSON objects ({...}) and arrays ([...]) (🟡-10).
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
                // Validate it's a complete JSON object or array
                if ((block.startsWith("{") || block.startsWith("[")) && bracketMatched(block)) return block;
            }
        }

        // Find the first '{' or '[' and use bracket matching to find its matching closer
        int objStart = text.indexOf('{');
        int arrStart = text.indexOf('[');
        int jsonStart;
        char openChar, closeChar;

        if (objStart < 0 && arrStart < 0) return null;
        if (objStart < 0) { jsonStart = arrStart; openChar = '['; closeChar = ']'; }
        else if (arrStart < 0) { jsonStart = objStart; openChar = '{'; closeChar = '}'; }
        else { jsonStart = Math.min(objStart, arrStart); openChar = jsonStart == objStart ? '{' : '['; closeChar = jsonStart == objStart ? '}' : ']'; }

        int depth = 0;
        int jsonEnd = -1;
        boolean inString = false;
        boolean escaping = false;  // true when previous char was unescaped backslash

        for (int i = jsonStart; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inString) {
                if (escaping) {
                    escaping = false;  // current char is escaped — skip it
                    continue;
                }
                if (c == '\\') {
                    escaping = true;   // next char will be escaped
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            // Not inside a string
            if (c == '"') {
                inString = true;
                continue;
            }

            // Track both bracket types for nesting inside arrays/objects
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    jsonEnd = i;
                    break; // found the matching closing bracket
                }
            }
        }

        if (jsonEnd >= 0) return text.substring(jsonStart, jsonEnd + 1);
        return null;
    }

    /** Check if a string has balanced brackets (both {} and []) */
    private static boolean bracketMatched(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaping) { escaping = false; continue; }
                if (c == '\\') { escaping = true; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
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
