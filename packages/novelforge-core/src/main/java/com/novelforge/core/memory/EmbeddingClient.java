package com.novelforge.core.memory;

import com.novelforge.core.llm.LlmException;

/**
 * EmbeddingClient — turns text into a fixed-dimension dense vector.
 *
 * Implementations may call a remote embedding endpoint (OpenAI-compatible
 * /v1/embeddings) or a local model. The contract is intentionally tiny so the
 * {@link MemoryStore} long-term memory layer can swap providers freely.
 */
public interface EmbeddingClient {

    /**
     * @return dense vector for the given text (provider-specific dimension).
     * @throws LlmException if the embedding endpoint is unreachable or returns an error.
     */
    float[] embed(String text) throws LlmException;

    /** True when this client is actually wired to a working endpoint. */
    default boolean isAvailable() { return true; }
}
