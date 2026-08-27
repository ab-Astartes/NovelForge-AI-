package com.novelforge.core.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebhookNotifier — fire-and-forget HTTP POST notifications when a pipeline
 * completes or fails. Mirrors InkOS's daemon webhook behaviour.
 *
 * Failures are intentionally swallowed so a bad webhook URL can never block or
 * corrupt the writing pipeline.
 */
public final class WebhookNotifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "webhook-notifier");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient;

    public WebhookNotifier() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Notify all configured URLs asynchronously. */
    public void notifyAll(List<String> urls, String event, ObjectNode payload) {
        if (urls == null || urls.isEmpty()) return;
        for (String url : urls) {
            if (url == null || url.isBlank()) continue;
            POOL.submit(() -> postOne(url, event, payload));
        }
    }

    private void postOne(String url, String event, ObjectNode payload) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("event", event);
            body.put("timestamp", System.currentTimeMillis());
            body.set("data", payload);
            String json = MAPPER.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "NovelForge-Studio")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                // Non-fatal: log at most
                System.err.println("[Webhook] POST " + url + " returned " + resp.statusCode());
            }
        } catch (Exception e) {
            // Swallowed on purpose — webhooks must never break writing.
            System.err.println("[Webhook] failed to notify " + url + ": " + e.getMessage());
        }
    }
}
