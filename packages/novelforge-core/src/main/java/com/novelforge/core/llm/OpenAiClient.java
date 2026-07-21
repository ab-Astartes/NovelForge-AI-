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
import com.novelforge.core.models.TextUtils;
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
        // Security: warn if baseUrl is not HTTPS (fixes #6)
        if (baseUrl != null && !baseUrl.startsWith("https://")) {
            log.warn("[WARNING] baseUrl is not HTTPS — API key will be transmitted in plaintext: {}", baseUrl);
        }
        // Normalize: strip trailing slash and /chat/completions, ensure /v1
        String url = baseUrl.replaceAll("/+$", "");
        // Strip known API path suffixes so we don't double-append
        url = url.replaceAll("(/v1)?/chat/completions$", "");
        // Now ensure /v1 suffix
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
        return withRetry(() -> chatCompleteOnce(messages, model, temperature, maxTokens), 4);  // 1 initial + 3 retries
    }

    /** Single LLM call attempt (no retry) */
    private String chatCompleteOnce(List<Map<String, String>> messages, String model, double temperature, int maxTokens) {
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
                log.error("LLM API error: status={}, body={}", response.statusCode(), TextUtils.truncate(response.body(), 500));
                throw new LlmException("API returned " + response.statusCode() + ": " + TextUtils.truncate(response.body(), 200));
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode choices = root.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new LlmException("No choices in response");
            }

            String content = choices.get(0).get("message").get("content").asText();
            if (content == null || content.trim().isEmpty()) {
                log.warn("[OpenAiClient] LLM returned empty response");
                return "[LLM returned empty response - please retry]";
            }
            log.debug("LLM response length: {} chars", content.length());
            return content;

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new LlmException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /** Retry wrapper: retry on 429/5xx, exponential backoff */
    private String withRetry(java.util.function.Supplier<String> action, int maxAttempts) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {  // maxAttempts = total tries (1 initial + retries)
            try {
                return action.get();
            } catch (LlmException e) {
                boolean retryable = e.getMessage() != null &&
                    (e.getMessage().contains("429") || e.getMessage().contains("500") ||
                     e.getMessage().contains("502") || e.getMessage().contains("503") ||
                     e.getMessage().contains("timeout") || e.getMessage().contains("connect"));
                if (!retryable || attempt >= maxAttempts - 1) throw e;
                long delayMs = (long) Math.pow(2, attempt) * 1000;  // 1s, 2s, 4s
                log.warn("LLM call failed (attempt {}), retrying in {}ms: {}", attempt + 1, delayMs, e.getMessage());
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw new LlmException("Max retries exhausted");
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

            // Real SSE streaming: InputStream line-by-line, invoke onChunk per delta
            // try-with-resources ensures InputStream closure on all paths
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                try (java.io.InputStream errStream = response.body()) {
                    String errorBody = new java.io.BufferedReader(
                            new java.io.InputStreamReader(errStream)).lines()
                            .collect(java.util.stream.Collectors.joining("\n"));
                    handler.onError(new LlmException(
                            "API returned " + response.statusCode() + ": " + TextUtils.truncate(errorBody, 500)));
                }
                return;
            }

            StringBuilder fullText = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                        String data = line.substring(6).trim();
                        if (data.isEmpty()) continue;
                        try {
                            JsonNode chunk = mapper.readTree(data);
                            JsonNode delta = chunk.at("/choices/0/delta/content");
                            if (!delta.isMissingNode() && !delta.isNull()) {
                                String text = delta.asText();
                                fullText.append(text);
                                handler.onChunk(text);
                            }
                        } catch (Exception parseEx) {
                            // Skip malformed SSE chunks — AnthropicClient does the same
                            log.warn("Skipping malformed SSE chunk: {}", TextUtils.truncate(data, 200));
                        }
                    }
                }
            } // reader + InputStream auto-closed

            String streamResult = fullText.toString();
            if (streamResult == null || streamResult.trim().isEmpty()) {
                log.warn("[OpenAiClient] LLM streaming returned empty response");
                handler.onComplete("[LLM returned empty response - please retry]");
            } else {
                handler.onComplete(streamResult);
            }

        } catch (LlmException e) {
            handler.onError(e);
        } catch (Exception e) {
            handler.onError(new LlmException("OpenAI streaming failed: " + e.getMessage(), e));
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
}
