package com.novelforge.core.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anthropic API client — supports Claude models.
 * Uses Anthropic's /v1/messages endpoint with different format from OpenAI.
 */
public class AnthropicClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public AnthropicClient(String baseUrl, String apiKey) {
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

    @Override public String provider() { return "anthropic"; }

    @Override
    public String complete(String prompt, String model, double temperature, int maxTokens) {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt)
        );
        return chatComplete(messages, model, temperature, maxTokens);
    }

    @Override
    public String chatComplete(List<Map<String, String>> messages, String model, double temperature, int maxTokens) {
        try {
            // Anthropic format: system is separate, messages only have user/assistant
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);

            // Extract system message if present
            StringBuilder systemPrompt = new StringBuilder();
            List<Map<String, String>> filtered = new ArrayList<>();
            for (Map<String, String> msg : messages) {
                if ("system".equals(msg.get("role"))) {
                    systemPrompt.append(msg.get("content")).append("\n");
                } else {
                    filtered.add(msg);
                }
            }
            if (systemPrompt.length() > 0) {
                body.put("system", systemPrompt.toString().trim());
            }

            ArrayNode msgsArr = body.putArray("messages");
            for (Map<String, String> msg : filtered) {
                ObjectNode msgNode = msgsArr.addObject();
                msgNode.put("role", msg.get("role"));
                msgNode.put("content", msg.get("content"));
            }

            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .build();

            log.debug("Anthropic request: model={}, temp={}, maxTokens={}", model, temperature, maxTokens);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Anthropic API error: status={}, body={}", response.statusCode(), truncate(response.body(), 500));
                throw new LlmException("Anthropic API returned " + response.statusCode() + ": " + truncate(response.body(), 200));
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode contentArr = root.get("content");
            if (contentArr == null || contentArr.isEmpty()) {
                throw new LlmException("No content in Anthropic response");
            }

            StringBuilder result = new StringBuilder();
            for (JsonNode block : contentArr) {
                if ("text".equals(block.get("type").asText())) {
                    result.append(block.get("text").asText());
                }
            }
            return result.toString();

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Anthropic call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void chatCompleteStream(List<Map<String, String>> messages, String model, double temperature,
                                   int maxTokens, StreamHandler handler) {
        // Anthropic streaming uses SSE with different event types (message_start, content_block_delta, message_stop)
        // TODO: Implement Anthropic SSE streaming
        String result = chatComplete(messages, model, temperature, maxTokens);
        handler.onComplete(result);
    }

    @Override
    public int estimateTokens(String text) {
        int cjkCount = (int) text.chars().filter(c ->
                (c >= 0x4E00 && c <= 0x9FFF) ||
                (c >= 0x3400 && c <= 0x4DBF) ||
                (c >= 0x20000 && c <= 0x2A6DF)
        ).count();
        int otherCount = text.length() - cjkCount;
        return cjkCount * 3 / 2 + otherCount / 4 + 1;
    }

    private String truncate(String s, int max) {
        return s == null ? "null" : s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
