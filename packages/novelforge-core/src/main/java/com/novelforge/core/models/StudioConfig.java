package com.novelforge.core.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelforge.core.pipeline.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * StudioConfig — full configuration for NovelForge Studio.
 * Includes global default API config, per-agent overrides, preset switching, and pipeline config.
 */
public class StudioConfig {

    private static final Logger log = LoggerFactory.getLogger(StudioConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentApiConfig globalDefault;
    private Map<String, AgentApiConfig> agentOverrides;  // Agent name -> per-agent config
    private String activePreset;                          // Currently active preset name
    private Map<String, PresetEntry> presets;             // Preset name -> preset config
    private PipelineConfig pipelineConfig;

    // Long-term memory (RAG) configuration
    private String embeddingBaseUrl = "";                 // OpenAI-compatible embeddings base URL
    private String embeddingApiKey = "";                  // embeddings API key
    private String embeddingModel = "text-embedding-3-small";
    private boolean memoryEnabled = true;                 // master switch for RAG recall
    private java.util.List<String> webhooks = new java.util.ArrayList<>();  // pipeline event webhook URLs

    /** Preset entry: contains its own globalDefault + agentOverrides */
    public static class PresetEntry {
        private AgentApiConfig globalDefault;
        private Map<String, AgentApiConfig> agentOverrides;
        private String description;

        public PresetEntry() {
            this.globalDefault = new AgentApiConfig();
            this.agentOverrides = new LinkedHashMap<>();
            this.description = "";
        }

        public PresetEntry(String description, AgentApiConfig globalDefault, Map<String, AgentApiConfig> agentOverrides) {
            this.description = description;
            this.globalDefault = globalDefault;
            this.agentOverrides = agentOverrides;
        }

        public AgentApiConfig getGlobalDefault() { return globalDefault; }
        public void setGlobalDefault(AgentApiConfig globalDefault) { this.globalDefault = globalDefault; }
        public Map<String, AgentApiConfig> getAgentOverrides() { return agentOverrides; }
        public void setAgentOverrides(Map<String, AgentApiConfig> agentOverrides) { this.agentOverrides = agentOverrides; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        /** Convert to JSON ObjectNode */
        public ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("description", description != null ? description : "");
            node.set("globalDefault", globalDefault.toJson());
            ObjectNode overridesNode = MAPPER.createObjectNode();
            for (Map.Entry<String, AgentApiConfig> entry : agentOverrides.entrySet()) {
                overridesNode.set(entry.getKey(), entry.getValue().toJson());
            }
            node.set("agentOverrides", overridesNode);
            return node;
        }

        /** Deserialize from JsonNode */
        public static PresetEntry fromJson(JsonNode node) {
            PresetEntry entry = new PresetEntry();
            if (node.has("description")) entry.setDescription(node.get("description").asText());
            if (node.has("globalDefault")) entry.setGlobalDefault(AgentApiConfig.fromJson(node.get("globalDefault")));
            if (node.has("agentOverrides")) {
                JsonNode ov = node.get("agentOverrides");
                ov.fields().forEachRemaining(field -> {
                    entry.agentOverrides.put(field.getKey(), AgentApiConfig.fromJson(field.getValue()));
                });
            }
            return entry;
        }
    }

    // Agent names used in the pipeline
    public static final String[] AGENT_NAMES = {
        "Architect", "Planner", "Composer", "Writer",
        "Observer", "Reflector", "Normalizer", "Auditor", "Reviser"
    };

    // Built-in model provider configs
    public static final java.util.Map<String, String[]> BUILTIN_PROVIDERS = java.util.Map.of(
        "openai",    new String[]{"OpenAI",   "https://api.openai.com/v1",     "gpt-4o"},
        "anthropic", new String[]{"Anthropic", "https://api.anthropic.com",     "claude-3-opus-20240229"},
        "deepseek",  new String[]{"DeepSeek",  "https://api.deepseek.com/v1",   "deepseek-chat"},
        "qwen",      new String[]{"通义千问",   "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-max"},
        "glm",       new String[]{"智谱GLM",   "https://open.bigmodel.cn/api/paas/v4", "glm-4"},
        "kimi",      new String[]{"Moonshot",  "https://api.moonshot.cn/v1",    "moonshot-v1-8k"},
        "minimax",   new String[]{"MiniMax",   "https://api.minimax.chat/v1",   "abab6.5s-chat"}
    );

    public StudioConfig() {
        this.globalDefault = new AgentApiConfig();
        this.agentOverrides = new LinkedHashMap<>();
        this.activePreset = "";
        this.presets = new LinkedHashMap<>();
        this.pipelineConfig = new PipelineConfig();
        this.webhooks = new java.util.ArrayList<>();
    }

    // --- Getters/Setters ---
    public AgentApiConfig getGlobalDefault() { return globalDefault; }
    public void setGlobalDefault(AgentApiConfig globalDefault) { this.globalDefault = globalDefault; }
    public Map<String, AgentApiConfig> getAgentOverrides() { return agentOverrides; }
    public void setAgentOverrides(Map<String, AgentApiConfig> agentOverrides) { this.agentOverrides = agentOverrides; }
    public String getActivePreset() { return activePreset; }
    public void setActivePreset(String activePreset) { this.activePreset = activePreset; }
    public Map<String, PresetEntry> getPresets() { return presets; }
    public void setPresets(Map<String, PresetEntry> presets) { this.presets = presets; }
    public PipelineConfig getPipelineConfig() { return pipelineConfig; }
    public void setPipelineConfig(PipelineConfig pipelineConfig) { this.pipelineConfig = pipelineConfig; }

    // --- Memory / Embedding config ---
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String v) { this.embeddingBaseUrl = v == null ? "" : v; }
    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public void setEmbeddingApiKey(String v) { this.embeddingApiKey = v == null ? "" : v; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String v) { this.embeddingModel = v == null || v.isEmpty() ? "text-embedding-3-small" : v; }
    public boolean isMemoryEnabled() { return memoryEnabled; }
    public void setMemoryEnabled(boolean v) { this.memoryEnabled = v; }
    public java.util.List<String> getWebhooks() { return webhooks; }
    public void setWebhooks(java.util.List<String> w) { this.webhooks = w == null ? new java.util.ArrayList<>() : w; }

    /** Get resolved API config for a specific agent (global default + agent override merge) */
    public AgentApiConfig getResolvedConfig(String agentName) {
        AgentApiConfig base = globalDefault.copy();
        AgentApiConfig override = agentOverrides.get(agentName);
        if (override != null) {
            // Merge: override fields replace base fields if non-empty
            if (override.getProvider() != null && !override.getProvider().isEmpty()) base.setProvider(override.getProvider());
            if (override.getModel() != null && !override.getModel().isEmpty()) base.setModel(override.getModel());
            if (override.getBaseUrl() != null && !override.getBaseUrl().isEmpty()) base.setBaseUrl(override.getBaseUrl());
            if (override.getApiKey() != null && !override.getApiKey().isEmpty()) base.setApiKey(override.getApiKey());
            if (override.getApiKeyEnv() != null && !override.getApiKeyEnv().isEmpty()) base.setApiKeyEnv(override.getApiKeyEnv());
        }
        return base;
    }

    /** Apply a preset: loads the preset's globalDefault and agentOverrides into this config */
    public void applyPreset(String presetName) {
        PresetEntry preset = presets.get(presetName);
        if (preset != null) {
            this.globalDefault = preset.getGlobalDefault().copy();
            this.agentOverrides = new LinkedHashMap<>();
            for (Map.Entry<String, AgentApiConfig> entry : preset.getAgentOverrides().entrySet()) {
                this.agentOverrides.put(entry.getKey(), entry.getValue().copy());
            }
            this.activePreset = presetName;
        }
    }

    /** Serialize to JSON ObjectNode (apiKey masked) */
    public ObjectNode toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("globalDefault", globalDefault.toJson());
        ObjectNode overridesNode = MAPPER.createObjectNode();
        for (Map.Entry<String, AgentApiConfig> entry : agentOverrides.entrySet()) {
            overridesNode.set(entry.getKey(), entry.getValue().toJson());
        }
        root.set("agentOverrides", overridesNode);
        root.put("activePreset", activePreset != null ? activePreset : "");
        // Presets
        ObjectNode presetsNode = MAPPER.createObjectNode();
        for (Map.Entry<String, PresetEntry> entry : presets.entrySet()) {
            presetsNode.set(entry.getKey(), entry.getValue().toJson());
        }
        root.set("presets", presetsNode);
        // Pipeline config
        ObjectNode pipelineNode = MAPPER.createObjectNode();
        pipelineNode.put("chapterWordsMin", pipelineConfig.getChapterWordsMin());
        pipelineNode.put("chapterWordsMax", pipelineConfig.getChapterWordsMax());
        pipelineNode.put("auditPassThreshold", pipelineConfig.getAuditPassThreshold());
        pipelineNode.put("maxRevisionPasses", pipelineConfig.getMaxRevisionPasses());
        pipelineNode.put("runArchitect", pipelineConfig.isRunArchitect());
        pipelineNode.put("runPlanner", pipelineConfig.isRunPlanner());
        pipelineNode.put("runComposer", pipelineConfig.isRunComposer());
        pipelineNode.put("runWriter", pipelineConfig.isRunWriter());
        pipelineNode.put("runObserver", pipelineConfig.isRunObserver());
        pipelineNode.put("runReflector", pipelineConfig.isRunReflector());
        pipelineNode.put("runNormalizer", pipelineConfig.isRunNormalizer());
        pipelineNode.put("runAuditor", pipelineConfig.isRunAuditor());
        pipelineNode.put("runReviser", pipelineConfig.isRunReviser());
        root.set("pipelineConfig", pipelineNode);
        // Memory / embedding / webhook config
        ObjectNode memoryNode = MAPPER.createObjectNode();
        memoryNode.put("enabled", memoryEnabled);
        memoryNode.put("embeddingBaseUrl", embeddingBaseUrl);
        memoryNode.put("embeddingApiKey", embeddingApiKey);
        memoryNode.put("embeddingModel", embeddingModel);
        root.set("memory", memoryNode);
        ArrayNode hooks = MAPPER.createArrayNode();
        for (String w : webhooks) hooks.add(w);
        root.set("webhooks", hooks);
        return root;
    }

    /** Deserialize from JsonNode */
    public static StudioConfig fromJson(JsonNode root) {
        StudioConfig config = new StudioConfig();
        if (root.has("globalDefault")) {
            config.setGlobalDefault(AgentApiConfig.fromJson(root.get("globalDefault")));
        }
        if (root.has("agentOverrides")) {
            JsonNode ov = root.get("agentOverrides");
            ov.fields().forEachRemaining(field -> {
                config.agentOverrides.put(field.getKey(), AgentApiConfig.fromJson(field.getValue()));
            });
        }
        if (root.has("activePreset")) {
            config.setActivePreset(root.get("activePreset").asText());
        }
        if (root.has("presets")) {
            JsonNode presetsNode = root.get("presets");
            presetsNode.fields().forEachRemaining(field -> {
                config.presets.put(field.getKey(), PresetEntry.fromJson(field.getValue()));
            });
        }
        if (root.has("pipelineConfig")) {
            JsonNode pc = root.get("pipelineConfig");
            PipelineConfig pipelineConfig = config.getPipelineConfig();
            if (pc.has("chapterWordsMin")) pipelineConfig.setChapterWordsMin(pc.get("chapterWordsMin").asInt());
            if (pc.has("chapterWordsMax")) pipelineConfig.setChapterWordsMax(pc.get("chapterWordsMax").asInt());
            if (pc.has("auditPassThreshold")) pipelineConfig.setAuditPassThreshold(pc.get("auditPassThreshold").asDouble());
            if (pc.has("maxRevisionPasses")) pipelineConfig.setMaxRevisionPasses(pc.get("maxRevisionPasses").asInt());
            if (pc.has("runArchitect")) pipelineConfig.setRunArchitect(pc.get("runArchitect").asBoolean());
            if (pc.has("runPlanner")) pipelineConfig.setRunPlanner(pc.get("runPlanner").asBoolean());
            if (pc.has("runComposer")) pipelineConfig.setRunComposer(pc.get("runComposer").asBoolean());
            if (pc.has("runWriter")) pipelineConfig.setRunWriter(pc.get("runWriter").asBoolean());
            if (pc.has("runObserver")) pipelineConfig.setRunObserver(pc.get("runObserver").asBoolean());
            if (pc.has("runReflector")) pipelineConfig.setRunReflector(pc.get("runReflector").asBoolean());
            if (pc.has("runNormalizer")) pipelineConfig.setRunNormalizer(pc.get("runNormalizer").asBoolean());
            if (pc.has("runAuditor")) pipelineConfig.setRunAuditor(pc.get("runAuditor").asBoolean());
            if (pc.has("runReviser")) pipelineConfig.setRunReviser(pc.get("runReviser").asBoolean());
        }
        if (root.has("memory")) {
            JsonNode mem = root.get("memory");
            if (mem.has("enabled")) config.setMemoryEnabled(mem.get("enabled").asBoolean());
            if (mem.has("embeddingBaseUrl")) config.setEmbeddingBaseUrl(mem.get("embeddingBaseUrl").asText());
            if (mem.has("embeddingApiKey")) config.setEmbeddingApiKey(mem.get("embeddingApiKey").asText());
            if (mem.has("embeddingModel")) config.setEmbeddingModel(mem.get("embeddingModel").asText());
        }
        if (root.has("webhooks") && root.get("webhooks").isArray()) {
            java.util.List<String> ws = new java.util.ArrayList<>();
            root.get("webhooks").forEach(n -> ws.add(n.asText()));
            config.setWebhooks(ws);
        }
        return config;
    }

    // --- Persistence ---
    private static final Path CONFIG_PATH = Paths.get(System.getProperty("user.home"), ".novelforge", "config.json");

    /** Save config to ~/.novelforge/config.json */
    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            ObjectNode root = toJson();
            // When saving to disk, store apiKeyEnv instead of apiKey for security
            // apiKey is already masked in toJson(), so we add apiKeyEnv field for resolution
            Files.writeString(CONFIG_PATH, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
            log.info("StudioConfig saved to {}", CONFIG_PATH);
        } catch (Exception e) {
            log.warn("Failed to save StudioConfig: {}", e.getMessage());
        }
    }

    /** Load config from ~/.novelforge/config.json, merging with defaults */
    public static StudioConfig load() {
        StudioConfig config = new StudioConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String jsonStr = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonNode root = MAPPER.readTree(jsonStr);
                config = fromJson(root);
                // Apply active preset if set
                if (config.getActivePreset() != null && !config.getActivePreset().isEmpty()) {
                    String presetName = config.getActivePreset();
                    PresetEntry preset = config.getPresets().get(presetName);
                    if (preset != null) {
                        config.applyPreset(presetName);
                    }
                }
                log.info("StudioConfig loaded from {}", CONFIG_PATH);
            } catch (Exception e) {
                log.warn("Failed to load StudioConfig from {}: {}. Using defaults.", CONFIG_PATH, e.getMessage());
            }
        }
        // Ensure built-in sample presets are always available
        ensureSamplePresets(config);
        return config;
    }

    /** Ensure sample presets are available (always add them if not present) */
    private static void ensureSamplePresets(StudioConfig config) {
        // "省钱模式": Writer & Auditor use gpt-4o-mini, rest default
        if (!config.presets.containsKey("economy")) {
            AgentApiConfig miniConfig = new AgentApiConfig("openai", "gpt-4o-mini", "https://api.openai.com/v1", "");
            Map<String, AgentApiConfig> ecoOverrides = new LinkedHashMap<>();
            ecoOverrides.put("Writer", new AgentApiConfig("openai", "gpt-4o-mini", "https://api.openai.com/v1", ""));
            ecoOverrides.put("Auditor", new AgentApiConfig("openai", "gpt-4o-mini", "https://api.openai.com/v1", ""));
            config.presets.put("economy", new PresetEntry("省钱模式：Writer/Auditor用gpt-4o-mini，其余默认", miniConfig, ecoOverrides));
        }
        // "高质量模式": Writer uses claude-3-opus, Auditor uses gpt-4o, rest default
        if (!config.presets.containsKey("quality")) {
            AgentApiConfig qualityDefault = new AgentApiConfig("anthropic", "claude-3-opus", "https://api.anthropic.com", "");
            Map<String, AgentApiConfig> qualityOverrides = new LinkedHashMap<>();
            qualityOverrides.put("Writer", new AgentApiConfig("anthropic", "claude-3-opus", "https://api.anthropic.com", ""));
            qualityOverrides.put("Auditor", new AgentApiConfig("openai", "gpt-4o", "https://api.openai.com/v1", ""));
            config.presets.put("quality", new PresetEntry("高质量模式：Writer用claude-3-opus，Auditor用gpt-4o", qualityDefault, qualityOverrides));
        }
        // "快速模式": all gpt-4o-mini, skip Observer and Reflector
        if (!config.presets.containsKey("fast")) {
            AgentApiConfig fastDefault = new AgentApiConfig("openai", "gpt-4o-mini", "https://api.openai.com/v1", "");
            Map<String, AgentApiConfig> fastOverrides = new LinkedHashMap<>();
            config.presets.put("fast", new PresetEntry("快速模式：全部用gpt-4o-mini，建议跳过Observer/Reflector", fastDefault, fastOverrides));
        }
    }

    /** Create sample presets JSON using ObjectMapper properly */
    public static String getSamplePresetsJsonString() {
        ObjectNode root = MAPPER.createObjectNode();

        // Economy preset
        AgentApiConfig ecoDefault = new AgentApiConfig("openai", "gpt-4o-mini", "https://api.openai.com/v1", "");
        Map<String, AgentApiConfig> ecoOverrides = new LinkedHashMap<>();
        ecoOverrides.put("Writer", new AgentApiConfig("openai", "gpt-4o-mini", "https://api.openai.com/v1", ""));
        ecoOverrides.put("Auditor", new AgentApiConfig("openai", "gpt-4o-mini", "https://api.openai.com/v1", ""));
        PresetEntry ecoPreset = new PresetEntry("省钱模式：Writer/Auditor用gpt-4o-mini，其余默认", ecoDefault, ecoOverrides);
        root.set("economy", ecoPreset.toJson());

        // Quality preset
        AgentApiConfig qualDefault = new AgentApiConfig("anthropic", "claude-3-opus", "https://api.anthropic.com", "");
        Map<String, AgentApiConfig> qualOverrides = new LinkedHashMap<>();
        qualOverrides.put("Writer", new AgentApiConfig("anthropic", "claude-3-opus", "https://api.anthropic.com", ""));
        qualOverrides.put("Auditor", new AgentApiConfig("openai", "gpt-4o", "https://api.openai.com/v1", ""));
        PresetEntry qualPreset = new PresetEntry("高质量模式：Writer用claude-3-opus，Auditor用gpt-4o", qualDefault, qualOverrides);
        root.set("quality", qualPreset.toJson());

        // Fast preset
        AgentApiConfig fastDefault = new AgentApiConfig("openai", "gpt-4o-mini", "https://api.openai.com/v1", "");
        Map<String, AgentApiConfig> fastOverrides = new LinkedHashMap<>();
        PresetEntry fastPreset = new PresetEntry("快速模式：全部用gpt-4o-mini，建议跳过Observer/Reflector", fastDefault, fastOverrides);
        root.set("fast", fastPreset.toJson());

        // Agent names list
        com.fasterxml.jackson.databind.node.ArrayNode agents = MAPPER.createArrayNode();
        for (String name : AGENT_NAMES) agents.add(name);
        root.set("agentNames", agents);

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }
}
