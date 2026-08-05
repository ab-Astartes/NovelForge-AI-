package com.novelforge.core.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * AgentApiConfig — per-agent or global API configuration.
 * Each agent can have its own provider, model, baseUrl, and apiKey.
 */
public class AgentApiConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String provider;    // "openai" | "anthropic" | "custom"
    private String model;       // "gpt-4o" | "claude-3-opus" etc.
    private String baseUrl;     // API endpoint
    private String apiKey;      // API key (masked when persisted)
    private String apiKeyEnv;   // Environment variable name (optional)

    public AgentApiConfig() {
        this.provider = "openai";
        this.model = "gpt-4o";
        this.baseUrl = "https://api.openai.com/v1";
        this.apiKey = "";
        this.apiKeyEnv = "";
    }

    public AgentApiConfig(String provider, String model, String baseUrl, String apiKey) {
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.apiKeyEnv = "";
    }

    // --- Getters/Setters ---
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiKeyEnv() { return apiKeyEnv; }
    public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }

    /** Mask apiKey for display/storage: show first 4 + last 4 chars */
    public String getMaskedApiKey() {
        if (apiKey == null || apiKey.isEmpty()) return "";
        if (apiKey.length() < 8) return "***";
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    /** Resolve apiKey: if apiKeyEnv is set and apiKey is empty, try env variable */
    public String resolveApiKey() {
        if (apiKey != null && !apiKey.isEmpty()) return apiKey;
        if (apiKeyEnv != null && !apiKeyEnv.isEmpty()) {
            String envVal = System.getenv(apiKeyEnv);
            if (envVal != null) return envVal;
        }
        return "";
    }

    /** Convert to ModelRouter.ModelConfig for pipeline use */
    public com.novelforge.core.llm.ModelRouter.ModelConfig toModelConfig() {
        return new com.novelforge.core.llm.ModelRouter.ModelConfig(
            provider != null ? provider : "openai",
            model != null ? model : "gpt-4o",
            baseUrl != null ? baseUrl : "https://api.openai.com/v1",
            resolveApiKey()
        );
    }

    /** Serialize to JSON ObjectNode (apiKey masked) */
    public ObjectNode toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("provider", provider);
        node.put("model", model);
        node.put("baseUrl", baseUrl);
        node.put("apiKey", getMaskedApiKey());
        node.put("apiKeyEnv", apiKeyEnv != null ? apiKeyEnv : "");
        return node;
    }

    /** Deserialize from JsonNode */
    public static AgentApiConfig fromJson(JsonNode node) {
        AgentApiConfig config = new AgentApiConfig();
        if (node.has("provider")) config.setProvider(node.get("provider").asText());
        if (node.has("model")) config.setModel(node.get("model").asText());
        if (node.has("baseUrl")) config.setBaseUrl(node.get("baseUrl").asText());
        // apiKey from JSON is already masked; keep it as-is for display
        if (node.has("apiKey")) config.setApiKey(node.get("apiKey").asText());
        if (node.has("apiKeyEnv")) config.setApiKeyEnv(node.get("apiKeyEnv").asText());
        return config;
    }

    /** Create a copy of this config */
    public AgentApiConfig copy() {
        AgentApiConfig c = new AgentApiConfig(provider, model, baseUrl, apiKey);
        c.apiKeyEnv = apiKeyEnv;
        return c;
    }

    @Override
    public String toString() {
        return "AgentApiConfig{provider='" + provider + "', model='" + model + "', baseUrl='" + baseUrl + "', apiKey='" + getMaskedApiKey() + "', apiKeyEnv='" + apiKeyEnv + "'}";
    }
}
