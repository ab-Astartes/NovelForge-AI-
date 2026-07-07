package com.novelforge.core.llm;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModelRouter — maps each agent to its configured LLM model and provider.
 * Agents without explicit overrides fall back to global default.
 */
public class ModelRouter {

    /** Agent → (model, provider, baseUrl, apiKeyEnv) */
    private final ConcurrentHashMap<String, ModelConfig> agentModels = new ConcurrentHashMap<>();

    /** Global default config */
    private volatile ModelConfig globalDefault;

    /** Available LLM clients keyed by provider+baseUrl (unique per endpoint) */
    private final ConcurrentHashMap<String, LlmClient> clients = new ConcurrentHashMap<>();

    public ModelRouter(ModelConfig globalDefault) {
        this.globalDefault = globalDefault;
    }

    /** Set model override for a specific agent */
    public void setAgentModel(String agentName, ModelConfig config) {
        agentModels.put(agentName, config);
    }

    /** Register per-agent model override if not already set (fixes #24).
     *  Uses the global default config but with the specified model ID.
     *  This is called by AgentPipeline for agents that declare model() overrides. */
    public void registerAgentModelIfAbsent(String agentName, String modelId) {
        if (!agentModels.containsKey(agentName) && modelId != null && !modelId.equals(globalDefault.model())) {
            agentModels.put(agentName, new ModelConfig(globalDefault.provider(), modelId, globalDefault.baseUrl(), globalDefault.apiKey()));
        }
    }

    /** Get global default config (for cross-package access) */
    public ModelConfig getGlobalDefault() { return globalDefault; }

    /** Get the LLM client for an agent (fallback to global) */
    public LlmClient getClientForAgent(String agentName) {
        ModelConfig config = agentModels.getOrDefault(agentName, globalDefault);
        String clientKey = config.provider() + "@" + config.baseUrl();
        return clients.computeIfAbsent(clientKey, k -> createClient(config));
    }

    /** Get model ID for an agent */
    public String getModelForAgent(String agentName) {
        ModelConfig config = agentModels.getOrDefault(agentName, globalDefault);
        return config.model();
    }

    private LlmClient createClient(ModelConfig config) {
        switch (config.provider()) {
            case "openai":   return new OpenAiClient(config.baseUrl(), config.apiKey());
            case "anthropic": return new AnthropicClient(config.baseUrl(), config.apiKey());
            default:         return new OpenAiClient(config.baseUrl(), config.apiKey()); // custom = OpenAI-compatible
        }
    }

    /** Model config record */
    public record ModelConfig(String provider, String model, String baseUrl, String apiKey) {
        @Override
        public String toString() {
            return "ModelConfig{provider='" + provider + "', model='" + model + "', baseUrl='" + baseUrl + "', apiKey='" + maskKey(apiKey) + "'}";
        }
        private static String maskKey(String key) {
            if (key == null || key.length() < 8) return "***";
            return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
        }
    }
}
