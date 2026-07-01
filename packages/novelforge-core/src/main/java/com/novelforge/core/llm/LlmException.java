package com.novelforge.core.llm;

/**
 * LlmException — wraps all LLM API errors.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
