package com.novelforge.core.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM client interface — abstracts OpenAI, Anthropic, and custom providers.
 */
public interface LlmClient {

    /** Provider name (openai, anthropic, custom) */
    String provider();

    /** Single completion call */
    String complete(String prompt, String model, double temperature, int maxTokens);

    /** Chat completion with system + user messages */
    String chatComplete(List<Map<String, String>> messages, String model, double temperature, int maxTokens);

    /** Streaming completion (for long text generation) */
    void chatCompleteStream(List<Map<String, String>> messages, String model, double temperature,
                           int maxTokens, StreamHandler handler);

    /** Token estimation for context budget (fixes #29: null-safe) */
    default int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjkCount = (int) text.chars().filter(c ->
                (c >= 0x4E00 && c <= 0x9FFF) ||
                (c >= 0x3400 && c <= 0x4DBF) ||
                (c >= 0x20000 && c <= 0x2A6DF)
        ).count();
        int otherCount = text.length() - cjkCount;
        return cjkCount * 3 / 2 + otherCount / 4 + 1;
    }
}
