package io.github.xytronix.hybox.core.notify.discord;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Transport hook for webhook delivery.
 */
@FunctionalInterface
public interface WebhookTransport {
    CompletableFuture<Response> post(URI uri, String jsonPayload) throws Exception;

    record Response(int status, String retryAfterHeader, String body) {
    }
}
