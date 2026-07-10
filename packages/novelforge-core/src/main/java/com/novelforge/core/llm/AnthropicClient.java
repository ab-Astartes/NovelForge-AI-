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
import com.novelforge.core.models.TextUtils;
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
        // Security: warn if baseUrl is not HTTPS (fixes #6)
        if (baseUrl != null && !baseUrl.startsWith("https://")) {
            log.warn("[WARNING] baseUrl is not HTTPS — API key will be transmitted in plaintext: {}", baseUrl);
        }
        String url = baseUrl.replaceAll("/+$", "");
        // Anthropic API path is /v1/messages, not /v1/chat/completions
        url = url.replaceAll("(/v1)?/messages$", "");
        if (!url.endsWith("/v1")) {
            url = url + "/v1";
        }
        this.baseUrl = url;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
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
        return withRetry(() -> chatCompleteOnce(messages, model, temperature, maxTokens), 3);
    }

    /** Single Anthropic API call (no retry) */
    private String chatCompleteOnce(List<Map<String, String>> messages, String model, double temperature, int maxTokens) {
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
                log.error("Anthropic API error: status={}, body={}", response.statusCode(), TextUtils.truncate(response.body(), 500));
                throw new LlmException("Anthropic API returned " + response.statusCode() + ": " + TextUtils.truncate(response.body(), 200));
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
            String contentStr = result.toString();
            if (contentStr == null || contentStr.trim().isEmpty()) {
                log.warn("[AnthropicClient] LLM returned empty response");
                return "[LLM returned empty response - please retry]";
            }
            return contentStr;

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Anthropic call failed: " + e.getMessage(), e);
        }
    }

    /** Retry wrapper: retry on 429/5xx/timeout, exponential backoff */
    private String withRetry(java.util.function.Supplier<String> action, int maxRetries) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.get();
            } catch (LlmException e) {
                boolean retryable = e.getMessage() != null &&
                    (e.getMessage().contains("429") || e.getMessage().contains("500") ||
                     e.getMessage().contains("502") || e.getMessage().contains("503") ||
                     e.getMessage().contains("overload") || e.getMessage().contains("timeout") ||
                     e.getMessage().contains("connect"));
                if (!retryable || attempt == maxRetries) throw e;
                long delayMs = (long) Math.pow(2, attempt) * 1000;
                log.warn("Anthropic call failed (attempt {}), retrying in {}ms: {}", attempt + 1, delayMs, e.getMessage());
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { throw e; }
            }
        }
        throw new LlmException("Max retries exhausted");
    }

    @Override
    public void chatCompleteStream(List<Map<String, String>> messages, String model, double temperature,
                                   int maxTokens, StreamHandler handler) {
        try {
            // Build Anthropic SSE request body
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            body.put("stream", true);

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
                    .timeout(java.time.Duration.ofMinutes(10))
                    .build();

            // Real SSE streaming: read InputStream line-by-line, invoke handler.onChunk() per delta
            // InputStream wrapped in try-with-resources to ensure closure on all paths (fixes 🔴-4)
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                try (java.io.InputStream errStream = response.body()) {
                    String errorBody = new java.io.BufferedReader(
                            new java.io.InputStreamReader(errStream)).lines()
                            .collect(java.util.stream.Collectors.joining("\n"));
                    handler.onError(new LlmException(
                            "Anthropic API returned " + response.statusCode() + ": " + TextUtils.truncate(errorBody, 500)));
                }
                return;
            }

            StringBuilder fullText = new StringBuilder();
            // try-with-resources ensures both BufferedReader and underlying InputStream are closed
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if (data.equals("[DONE]")) continue;
                        try {
                            JsonNode event = mapper.readTree(data);
                            String eventType = event.has("type") ? event.get("type").asText() : "";
                            switch (eventType) {
                                case "content_block_delta" -> {
                                    JsonNode delta = event.get("delta");
                                    if (delta != null && "text_delta".equals(delta.get("type").asText())) {
                                        String text = delta.get("text").asText();
                                        fullText.append(text);
                                        handler.onChunk(text);
                                    }
                                }
                                case "message_stop" -> {
                                    // Stream complete
                                }
                            }
                        } catch (Exception ignored) {
                            // Malformed SSE line, skip
                        }
                    }
                }
            } // reader + InputStream auto-closed here

            String streamResult = fullText.toString();
            if (streamResult == null || streamResult.trim().isEmpty()) {
                log.warn("[AnthropicClient] LLM streaming returned empty response");
                handler.onComplete("[LLM returned empty response - please retry]");
            } else {
                handler.onComplete(streamResult);
            }

        } catch (LlmException e) {
            handler.onError(e);
        } catch (Exception e) {
            handler.onError(new LlmException("Anthropic streaming failed: " + e.getMessage(), e));
        }
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

}

