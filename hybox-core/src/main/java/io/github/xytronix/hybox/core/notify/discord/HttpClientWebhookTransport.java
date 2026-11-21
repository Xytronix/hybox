package io.github.xytronix.hybox.core.notify.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Async HTTP transport for Discord webhook delivery.
 */
public final class HttpClientWebhookTransport implements WebhookTransport {
    private final HttpClient client;
    private final Duration requestTimeout;

    public HttpClientWebhookTransport(Duration requestTimeout) {
        this(HttpClient.newHttpClient(), requestTimeout);
    }

    public HttpClientWebhookTransport(HttpClient client, Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public CompletableFuture<Response> post(URI uri, String jsonPayload) {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
            .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> new Response(response.statusCode(),
                response.headers().firstValue("Retry-After").orElse(null), response.body()));
    }
}
