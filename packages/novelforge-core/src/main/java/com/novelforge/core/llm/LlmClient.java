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

    /** Token estimation for context budget */
    int estimateTokens(String text);
}

/** Stream handler for real-time text delivery */
interface StreamHandler {
    void onChunk(String chunk);
    void onComplete(String fullText);
    void onError(Exception e);
}
