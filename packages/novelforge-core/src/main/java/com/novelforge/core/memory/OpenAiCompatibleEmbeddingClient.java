package com.novelforge.core.memory;

import com.novelforge.core.llm.LlmException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible embedding client (also works with DeepSeek, Qwen, vLLM,
 * Ollama /v1, etc.). Calls {@code POST {baseUrl}/embeddings}.
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean available = true;

    public OpenAiCompatibleEmbeddingClient(String baseUrl, String apiKey, String model) {
        String url = baseUrl == null ? "https://api.openai.com/v1" : baseUrl.replaceAll("/+$", "");
        url = url.replaceAll("(/v1)?/embeddings$", "");
        if (!url.endsWith("/v1")) url = url + "/v1";
        this.baseUrl = url;
        this.apiKey = apiKey;
        this.model = model == null || model.isEmpty() ? "text-embedding-3-small" : model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public float[] embed(String text) throws LlmException {
        if (!available) throw new LlmException("Embedding client marked unavailable");
        try {
            var body = mapper.createObjectNode();
            body.put("model", model);
            body.put("input", text == null ? "" : text);
            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(java.time.Duration.ofMinutes(2))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                available = false;
                throw new LlmException("Embedding API returned " + response.statusCode() + ": "
                        + truncate(response.body(), 200));
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.get("data");
            if (data == null || data.isEmpty()) {
                available = false;
                throw new LlmException("Embedding API returned no data");
            }
            JsonNode emb = data.get(0).get("embedding");
            if (emb == null || emb.isEmpty()) {
                available = false;
                throw new LlmException("Embedding API returned empty embedding");
            }
            List<Float> vec = new ArrayList<>();
            emb.forEach(n -> vec.add((float) n.asDouble()));
            float[] out = new float[vec.size()];
            for (int i = 0; i < out.length; i++) out[i] = vec.get(i);
            return out;
        } catch (LlmException e) {
            available = false;
            throw e;
        } catch (Exception e) {
            available = false;
            throw new LlmException("Embedding call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() { return available; }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n));
    }
}
