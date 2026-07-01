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

    /** Available LLM clients keyed by provider */
    private final Map<String, LlmClient> clients = new HashMap<>();

    public ModelRouter(ModelConfig globalDefault) {
        this.globalDefault = globalDefault;
    }

    /** Set model override for a specific agent */
    public void setAgentModel(String agentName, ModelConfig config) {
        agentModels.put(agentName, config);
    }

    /** Get the LLM client for an agent (fallback to global) */
    public LlmClient getClientForAgent(String agentName) {
        ModelConfig config = agentModels.getOrDefault(agentName, globalDefault);
        LlmClient client = clients.get(config.provider());
        if (client == null) {
            client = createClient(config);
            clients.put(config.provider(), client);
        }
        return client;
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
    public record ModelConfig(String provider, String model, String baseUrl, String apiKey) {}
}
