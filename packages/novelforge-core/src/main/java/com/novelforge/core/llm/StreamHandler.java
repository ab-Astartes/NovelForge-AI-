package com.novelforge.core.llm;

/**
 * Stream handler for real-time text delivery (fixes #29: extracted to separate file for cross-package use).
 * Used by LlmClient.chatCompleteStream() for SSE-based streaming responses.
 */
public interface StreamHandler {
    /** Called for each chunk of text received from the LLM */
    void onChunk(String chunk);
    /** Called when the full response is complete */
    void onComplete(String fullText);
    /** Called on error during streaming */
    void onError(Exception e);
}
