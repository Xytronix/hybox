package io.github.xytronix.hybox.core.notify.discord;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import io.github.xytronix.hybox.core.capture.IncidentNotifier;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;
import io.github.xytronix.hybox.core.json.JsonScanner;
import io.github.xytronix.hybox.core.json.JsonWriter;

/**
 * Sends incident notifications to a Discord webhook.
 */
public final class DiscordWebhookNotifier implements IncidentNotifier {
    static final Duration MAX_RETRY_WAIT = Duration.ofMinutes(1);
    private static final Duration DEFAULT_RETRY_WAIT = Duration.ofSeconds(1);
    private static final int MAX_LINE = 300;
    private static final int MAX_CONTENT = 1900;
    private static final int ANNOUNCED_LIMIT = 32;
    private static final Pattern DATA = Pattern.compile("\\bconst\\s+D\\s*=\\s*");
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern MARKDOWN = Pattern.compile("([\\\\*_~`|])");

    @FunctionalInterface
    public interface Delay {
        void schedule(Duration wait, Runnable task);
    }

    private final Clock clock;
    private final System.Logger logger;
    private final DiscordWebhookConfig config;
    private final WebhookTransport transport;
    private final Executor executor;
    private final Delay delay;
    private final Object lock = new Object();
    private final Set<String> announced = new LinkedHashSet<>();
    private Instant lastSentAt;
    private int loggedFailure;

    public DiscordWebhookNotifier(
        Clock clock,
        System.Logger logger,
        DiscordWebhookConfig config,
        WebhookTransport transport,
        Executor executor
    ) {
        this(clock, logger, config, transport, executor, (wait, task) -> CompletableFuture
            .delayedExecutor(wait.toMillis(), TimeUnit.MILLISECONDS, executor).execute(task));
    }

    public DiscordWebhookNotifier(
        Clock clock,
        System.Logger logger,
        DiscordWebhookConfig config,
        WebhookTransport transport,
        Executor executor,
        Delay delay
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    @Override
    public void onAccepted(IncidentMetadata meta) {
        if (config.webhookUrl().isBlank() || "MANUAL".equals(meta.trigger()) || !admit(meta.severity())) {
            return;
        }
        synchronized (lock) {
            announced.add(meta.id().value());
            if (announced.size() > ANNOUNCED_LIMIT) {
                Iterator<String> oldest = announced.iterator();
                oldest.next();
                oldest.remove();
            }
        }
        send(() -> acceptedContent(meta));
    }

    @Override
    public void onIncident(IncidentReport report, Path bundleZip) {
        if (config.webhookUrl().isBlank()) {
            return;
        }
        boolean followUp;
        synchronized (lock) {
            followUp = announced.remove(report.meta().id().value());
        }
        if (!followUp && !admit(report.meta().severity())) {
            return;
        }
        send(() -> savedContent(report.meta(), findings(bundleZip)));
    }

    @Override
    public void onFailed(IncidentMetadata meta) {
        if (config.webhookUrl().isBlank()) {
            return;
        }
        boolean followUp;
        synchronized (lock) {
            followUp = announced.remove(meta.id().value());
        }
        if (followUp) {
            send(() -> notSavedContent(meta));
        }
    }

    private boolean admit(Severity severity) {
        synchronized (lock) {
            Instant now = clock.instant();
            if (severity != Severity.CRITICAL && lastSentAt != null
                && now.isBefore(lastSentAt.plus(config.cooldown()))) {
                return false;
            }
            lastSentAt = now;
            return true;
        }
    }

    private void send(Supplier<String> content) {
        URI uri;
        try {
            uri = URI.create(config.webhookUrl());
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Invalid Discord webhook URL.", e);
            return;
        }
        executor.execute(() -> {
            String payload;
            try {
                payload = payload(content.get());
            } catch (IOException | RuntimeException e) {
                logger.log(System.Logger.Level.WARNING, "Failed to build Discord webhook payload.", e);
                return;
            }
            deliver(uri, payload, false);
        });
    }

    private void deliver(URI uri, String payload, boolean retried) {
        CompletableFuture<WebhookTransport.Response> sent;
        try {
            sent = Objects.requireNonNull(transport.post(uri, payload), "response");
        } catch (Exception e) {
            failed(-1, e);
            return;
        }
        sent.whenComplete((response, failure) -> {
            if (failure != null) {
                failed(-1, failure);
            } else {
                handle(uri, payload, retried, response);
            }
        });
    }

    private void handle(URI uri, String payload, boolean retried, WebhookTransport.Response response) {
        int status = response.status();
        if (status >= 200 && status < 300) {
            synchronized (lock) {
                loggedFailure = 0;
            }
            return;
        }
        Duration tooLong = null;
        if (status == 429 && !retried) {
            Duration wait = retryAfter(response);
            if (wait.compareTo(MAX_RETRY_WAIT) <= 0) {
                delay.schedule(wait, () -> deliver(uri, payload, true));
                return;
            }
            tooLong = wait;
        }
        failed(status, null, tooLong);
    }

    private void failed(int status, Throwable failure) {
        failed(status, failure, null);
    }

    private void failed(int status, Throwable failure, Duration tooLong) {
        synchronized (lock) {
            if (loggedFailure == status) {
                return;
            }
            loggedFailure = status;
        }
        logger.log(System.Logger.Level.WARNING, failureText(status, tooLong), failure);
    }

    static String failureText(int status, Duration tooLong) {
        String again = " Hybox logs this again only after a message goes through.";
        if (status < 0) {
            return "Could not reach the Discord webhook, so the alert was not delivered." + again;
        }
        if (status == 429 && tooLong != null) {
            return "Discord rate limited the webhook and asked Hybox to wait " + Math.round(tooLong.toMillis() / 1000.0)
                + " s, longer than 60 s, so the alert was dropped." + again;
        }
        if (status == 429) {
            return "Discord rate limited the webhook again after Hybox waited, so the alert was dropped." + again;
        }
        if (status == 401 || status == 403 || status == 404) {
            return "Discord refused the webhook with HTTP " + status + ". Check Discord.WebhookUrl, the webhook may "
                + "have been deleted." + again;
        }
        return "Discord answered the webhook with HTTP " + status + ", so the alert was not delivered." + again;
    }

    static Duration retryAfter(WebhookTransport.Response response) {
        String body = response.body();
        if (body != null) {
            Matcher seconds = RETRY_AFTER.matcher(body);
            if (seconds.find()) {
                return seconds(seconds.group(1));
            }
        }
        String header = response.retryAfterHeader();
        if (header != null && header.strip().matches("[0-9]+(?:\\.[0-9]+)?")) {
            return seconds(header.strip());
        }
        return DEFAULT_RETRY_WAIT;
    }

    private static Duration seconds(String value) {
        return Duration.ofMillis((long) Math.ceil(Double.parseDouble(value) * 1000));
    }

    String acceptedContent(IncidentMetadata meta) {
        List<String> lines = new ArrayList<>(2);
        lines.add(title(label(meta.severity()) + " incident: ") + line(meta.headline()));
        lines.add(where(meta) + " Hybox is saving the bundle now.");
        return content(lines);
    }

    String notSavedContent(IncidentMetadata meta) {
        List<String> lines = new ArrayList<>(2);
        lines.add(title(label(meta.severity()) + " incident not saved: ") + line(meta.headline()));
        lines.add(where(meta) + " Hybox could not save the bundle. See the server log.");
        return content(lines);
    }

    String savedContent(IncidentMetadata meta, List<String> findings) {
        List<String> lines = new ArrayList<>(4);
        lines.add(title(label(meta.severity()) + " incident saved: ") + line(meta.headline()));
        lines.add(where(meta));
        if (!findings.isEmpty() && !findings.get(0).isBlank()) {
            lines.add((findings.size() > 2 && "notable".equals(findings.get(2)) ? "Notable: " : "Cause: ")
                + line(findings.get(0)));
        }
        if (findings.size() > 1 && !findings.get(1).isBlank()) {
            lines.add("Next step: " + line(findings.get(1)));
        }
        return content(lines);
    }

    private String title(String text) {
        String server = config.serverName().strip();
        return server.isEmpty() ? "**" + text.strip() + "** " : "**" + escape(single(server)) + "** " + text;
    }

    private static String where(IncidentMetadata meta) {
        String world = meta.world();
        boolean hasWorld = world != null && !world.isBlank() && !world.equalsIgnoreCase("server");
        return (hasWorld ? "World " + line(world) + ", incident " : "Incident ") + meta.id().value() + ".";
    }

    private static String label(Severity severity) {
        String name = severity.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String content(List<String> lines) {
        String content = String.join("\n", lines);
        return content.length() <= MAX_CONTENT ? content : content.substring(0, MAX_CONTENT - 3) + "...";
    }

    private static String line(String text) {
        String single = single(text);
        return escape(single.length() <= MAX_LINE ? single : single.substring(0, MAX_LINE - 3) + "...");
    }

    private static String single(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String escape(String text) {
        return MARKDOWN.matcher(text).replaceAll("\\\\$1");
    }

    static List<String> findings(Path bundleZip) {
        if (bundleZip == null) {
            return List.of();
        }
        try (ZipFile zip = new ZipFile(bundleZip.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry("report.html");
            if (entry == null) {
                return List.of();
            }
            String html;
            try (var input = zip.getInputStream(entry)) {
                html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            Matcher data = DATA.matcher(html);
            if (!data.find()) {
                return List.of();
            }
            Object diagnosis = new JsonScanner(html, data.end()).object(Set.of("diagnosis")).get("diagnosis");
            if (!(diagnosis instanceof Map<?, ?> map)) {
                return List.of();
            }
            String lead = map.get("cause") instanceof Map<?, ?> cause ? text(cause) : first(map.get("why"));
            return List.of(lead, first(map.get("steps")), map.get("kind") instanceof String kind ? kind : "");
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    private static String first(Object entries) {
        return entries instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> entry ? text(entry) : "";
    }

    private static String text(Map<?, ?> entry) {
        return entry.get("text") instanceof String text ? text : "";
    }

    private String payload(String content) throws IOException {
        StringWriter writer = new StringWriter();
        JsonWriter json = new JsonWriter(writer);
        json.beginObject();
        json.name("content").value(content);
        if (!config.username().isBlank()) {
            json.name("username").value(config.username());
        }
        json.name("allowed_mentions").beginObject();
        json.name("parse").beginArray().endArray();
        json.endObject();
        json.endObject();
        return writer.toString();
    }
}
