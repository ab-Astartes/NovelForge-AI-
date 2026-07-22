package com.novelforge.core.llm;

import com.novelforge.core.llm.ModelRouter.ModelConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRouterTest {

    @Test
    void testGlobalDefault() {
        ModelConfig config = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "sk-test-key-12345678");
        ModelRouter router = new ModelRouter(config);

        assertEquals("openai", router.getGlobalDefault().provider());
        assertEquals("gpt-4o", router.getGlobalDefault().model());
    }

    @Test
    void testModelConfigMaskApiKey() {
        ModelConfig config = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "sk-test-key-12345678");
        String masked = config.toString();
        assertTrue(masked.contains("sk-t...5678"));
        assertFalse(masked.contains("sk-test-key-12345678"));
    }

    @Test
    void testModelConfigMaskShortKey() {
        ModelConfig config = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "abc");
        String masked = config.toString();
        assertTrue(masked.contains("***"));
        assertFalse(masked.contains("abc"));
    }

    @Test
    void testModelConfigMaskNullKey() {
        ModelConfig config = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", null);
        String masked = config.toString();
        assertTrue(masked.contains("***"));
    }

    @Test
    void testSetAgentModelOverride() {
        ModelConfig global = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "sk-key");
        ModelRouter router = new ModelRouter(global);

        ModelConfig override = new ModelConfig("anthropic", "claude-3", "https://api.anthropic.com", "ak-key");
        router.setAgentModel("Auditor", override);

        assertEquals("claude-3", router.getModelForAgent("Auditor"));
        assertEquals("gpt-4o", router.getModelForAgent("Writer")); // falls back to global
    }

    @Test
    void testRegisterAgentModelIfAbsent() {
        ModelConfig global = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "sk-key");
        ModelRouter router = new ModelRouter(global);

        // Register override for Auditor (different model)
        router.registerAgentModelIfAbsent("Auditor", "claude-3");
        assertEquals("claude-3", router.getModelForAgent("Auditor"));

        // Same model as global → should NOT register
        router.registerAgentModelIfAbsent("Writer", "gpt-4o");
        assertEquals("gpt-4o", router.getModelForAgent("Writer")); // still global default
    }

    @Test
    void testRegisterAgentModelIfAbsentDoesNotOverrideExisting() {
        ModelConfig global = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "sk-key");
        ModelRouter router = new ModelRouter(global);

        router.setAgentModel("Auditor", new ModelConfig("anthropic", "claude-3", "https://api.anthropic.com", "ak-key"));
        // Try to register different model — should not override existing
        router.registerAgentModelIfAbsent("Auditor", "deepseek-v3");
        assertEquals("claude-3", router.getModelForAgent("Auditor")); // still claude-3
    }

    @Test
    void testGetClientForAgentGlobalFallback() {
        ModelConfig global = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "sk-key");
        ModelRouter router = new ModelRouter(global);

        LlmClient client = router.getClientForAgent("Writer");
        assertNotNull(client);
        assertEquals("openai", client.provider());
    }

    @Test
    void testGetClientForAgentCustomProvider() {
        ModelConfig global = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "sk-key");
        ModelRouter router = new ModelRouter(global);

        router.setAgentModel("Auditor", new ModelConfig("anthropic", "claude-3", "https://api.anthropic.com", "ak-key"));
        LlmClient client = router.getClientForAgent("Auditor");
        assertEquals("anthropic", client.provider());
    }

    @Test
    void testModelConfigRecordEquality() {
        ModelConfig c1 = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "sk-key");
        ModelConfig c2 = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "sk-key");
        assertEquals(c1, c2);
    }

    @Test
    void testModelConfigRecordFields() {
        ModelConfig config = new ModelConfig("openai", "gpt-4o", "https://api.openai.com", "sk-key");
        assertEquals("openai", config.provider());
        assertEquals("gpt-4o", config.model());
        assertEquals("https://api.openai.com", config.baseUrl());
        assertEquals("sk-key", config.apiKey());
    }
}
