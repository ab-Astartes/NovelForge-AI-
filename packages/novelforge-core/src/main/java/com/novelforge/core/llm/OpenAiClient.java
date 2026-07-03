package com.novelforge.core.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI API client — supports GPT-4o, GPT-4, GPT-3.5, and any OpenAI-compatible endpoint.
 * This is the primary LLM client; custom providers (e.g. DeepSeek, Qwen) use OpenAI-compatible format.
 */
public class OpenAiClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenAiClient(String baseUrl, String apiKey) {
        // Normalize: strip trailing slash, ensure /v1 path
        String url = baseUrl.replaceAll("/+$", "");
        if (!url.endsWith("/v1")) {
            url = url + (url.contains("/v1/") ? "" : "/v1");
        }
        this.baseUrl = url;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override public String provider() { return "openai"; }

    @Override
    public String complete(String prompt, String model, double temperature, int maxTokens) {
        // Build simple user message
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt)
        );
        return chatComplete(messages, model, temperature, maxTokens);
    }

    @Override
    public String chatComplete(List<Map<String, String>> messages, String model, double temperature, int maxTokens) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);

            ArrayNode msgsArr = body.putArray("messages");
            for (Map<String, String> msg : messages) {
                ObjectNode msgNode = msgsArr.addObject();
                msgNode.put("role", msg.get("role"));
                msgNode.put("content", msg.get("content"));
            }

            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .build();

            log.debug("LLM request: model={}, temp={}, maxTokens={}, msgCount={}", model, temperature, maxTokens, messages.size());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("LLM API error: status={}, body={}", response.statusCode(), truncate(response.body(), 500));
                throw new LlmException("API returned " + response.statusCode() + ": " + truncate(response.body(), 200));
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode choices = root.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new LlmException("No choices in response");
            }

            String content = choices.get(0).get("message").get("content").asText();
            log.debug("LLM response length: {} chars", content.length());
            return content;

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new LlmException("LLM call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void chatCompleteStream(List<Map<String, String>> messages, String model, double temperature,
                                   int maxTokens, StreamHandler handler) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            body.put("stream", true);

            ArrayNode msgsArr = body.putArray("messages");
            for (Map<String, String> msg : messages) {
                ObjectNode msgNode = msgsArr.addObject();
                msgNode.put("role", msg.get("role"));
                msgNode.put("content", msg.get("content"));
            }

            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(java.time.Duration.ofMinutes(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                handler.onError(new LlmException("API returned " + response.statusCode()));
                return;
            }

            // Parse SSE lines
            StringBuilder fullText = new StringBuilder();
            for (String line : response.body().split("\n")) {
                if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                    String data = line.substring(6).trim();
                    if (data.isEmpty()) continue;
                    JsonNode chunk = mapper.readTree(data);
                    JsonNode delta = chunk.at("/choices/0/delta/content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String text = delta.asText();
                        fullText.append(text);
                        handler.onChunk(text);
                    }
                }
            }
            handler.onComplete(fullText.toString());

        } catch (Exception e) {
            handler.onError(e);
        }
    }

    @Override
    public int estimateTokens(String text) {
        // Chinese: ~1.5 tokens per char; English: ~0.25 tokens per char
        int cjkCount = (int) text.chars().filter(c ->
                (c >= 0x4E00 && c <= 0x9FFF) ||
                (c >= 0x3400 && c <= 0x4DBF) ||
                (c >= 0x20000 && c <= 0x2A6DF)
        ).count();
        int otherCount = text.length() - cjkCount;
        return cjkCount * 3 / 2 + otherCount / 4 + 1;
    }

    /** Truncate for logging */
    private String truncate(String s, int max) {
        return s == null ? "null" : s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
