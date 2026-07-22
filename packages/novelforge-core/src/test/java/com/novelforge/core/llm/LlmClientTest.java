package com.novelforge.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientTest {

    @Test
    void testEstimateTokensNullSafe() {
        LlmClient client = new StubLlmClient();
        assertEquals(0, client.estimateTokens(null));
        assertEquals(0, client.estimateTokens(""));
    }

    @Test
    void testEstimateTokensChinese() {
        LlmClient client = new StubLlmClient();
        // "测试" = 2 CJK chars → 2*3/2 + 0/4 + 1 = 4
        assertEquals(4, client.estimateTokens("测试"));
    }

    @Test
    void testEstimateTokensMixed() {
        LlmClient client = new StubLlmClient();
        // "Hello 世界" = 8 chars total, 2 CJK + 6 other
        // tokens = 2*3/2 + 6/4 + 1 = 3 + 1 + 1 = 5
        assertEquals(5, client.estimateTokens("Hello 世界"));
    }

    @Test
    void testEstimateTokensPureEnglish() {
        LlmClient client = new StubLlmClient();
        // "Hello" = 5 chars, 0 CJK → 0 + 5/4 + 1 = 1+1 = 2
        assertEquals(2, client.estimateTokens("Hello"));
    }

    @Test
    void testLlmExceptionMessage() {
        LlmException ex = new LlmException("API error 500");
        assertEquals("API error 500", ex.getMessage());
    }

    @Test
    void testLlmExceptionWithCause() {
        LlmException ex = new LlmException("failed", new RuntimeException("root cause"));
        assertEquals("failed", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void testStubLlmClientComplete() {
        LlmClient client = new StubLlmClient();
        String result = client.complete("prompt", "model", 0.7, 1000);
        assertEquals("stub-response", result);
    }

    @Test
    void testStubLlmClientChatComplete() {
        LlmClient client = new StubLlmClient();
        List<Map<String, String>> messages = List.of(
            Map.of("role", "user", "content", "hello")
        );
        String result = client.chatComplete(messages, "model", 0.7, 1000);
        assertEquals("stub-chat-response", result);
    }

    @Test
    void testStreamHandlerOnError() {
        StringBuilder log = new StringBuilder();
        StreamHandler handler = new StreamHandler() {
            @Override public void onChunk(String chunk) { log.append("C:").append(chunk); }
            @Override public void onComplete(String fullText) { log.append("D:").append(fullText); }
            @Override public void onError(Exception e) { log.append("E:").append(e.getMessage()); }
        };

        handler.onChunk("hello");
        handler.onComplete("hello world");
        assertEquals("C:helloD:hello world", log.toString());
    }

    @Test
    void testStreamHandlerErrorPath() {
        StringBuilder log = new StringBuilder();
        StreamHandler handler = new StreamHandler() {
            @Override public void onChunk(String chunk) { log.append("C:").append(chunk); }
            @Override public void onComplete(String fullText) { log.append("D:").append(fullText); }
            @Override public void onError(Exception e) { log.append("E:").append(e.getMessage()); }
        };

        handler.onError(new LlmException("timeout"));
        assertEquals("E:timeout", log.toString());
    }

    /** Simple stub implementation for unit testing */
    private static class StubLlmClient implements LlmClient {
        @Override public String provider() { return "stub"; }
        @Override public String complete(String prompt, String model, double temperature, int maxTokens) {
            return "stub-response";
        }
        @Override public String chatComplete(List<Map<String, String>> messages, String model, double temperature, int maxTokens) {
            return "stub-chat-response";
        }
        @Override public void chatCompleteStream(List<Map<String, String>> messages, String model, double temperature,
                                                  int maxTokens, StreamHandler handler) {
            handler.onChunk("chunk1");
            handler.onComplete("full-stream-response");
        }
    }
}
