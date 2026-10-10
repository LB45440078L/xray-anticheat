package io.xrayac.spigot.alert;

import io.xrayac.core.alert.AlertEvent;
import io.xrayac.core.alert.DiscordConfig;
import io.xrayac.core.evidence.EvidenceStrength;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts alerts to a Discord channel webhook, off the server thread.
 *
 * <h2>Why nothing here blocks</h2>
 * An HTTP request is a network round trip measured in hundreds of milliseconds, and Discord is
 * routinely slow to accept a post that it then rate-limits. Doing that on the Minecraft server thread
 * would stall the tick loop for every player on the server, so every send is handed to the plugin's
 * existing worker pool. Nothing in this class may be called in a way that depends on the result, which
 * is also why the methods return void: a notification is best-effort by design, and a failure to
 * announce a ban must never prevent the ban.
 *
 * <h2>Failure handling</h2>
 * A webhook URL is the only thing needed to post to a channel, so the messages here include the status
 * code but never the URL. Rate limiting is reported distinctly, because it is the one failure an
 * administrator can act on by moving the notifications to a quieter channel or raising the alert
 * throttle. Redirects are not followed: a redirect could send the webhook credential to a host the
 * administrator never chose.
 */
public final class DiscordNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordNotifier.class);

    private final DiscordConfig config;
    private final Consumer<Runnable> backgroundTask;
    private final HttpClient http;

    public DiscordNotifier(DiscordConfig config, Consumer<Runnable> backgroundTask) {
        this.config = config;
        this.backgroundTask = backgroundTask;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                // Never follow a redirect: it would forward the webhook URL to another host.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Whether anything will be sent at all, so callers can skip building messages. */
    public boolean isActive() {
        return config.isUsable();
    }

    /**
     * Offers an event to the channel, if the configuration wants it.
     *
     * @param event      what happened
     * @param strength   the evidence band behind it
     * @param confidence how much the sample supports that band
     * @param content    the message, in the plugin's usual colour-coded form
     */
    public void notify(AlertEvent event, EvidenceStrength strength, double confidence, String content) {
        if (!config.accepts(event, strength, confidence)) {
            return;
        }
        send(event, content);
    }

    /**
     * Offers an event to the channel, gated only by which events are enabled.
     *
     * <p>For messages that summarise rather than assess, such as a completed ban wave. There is no
     * evidence band to compare against a threshold, and applying one anyway would silently drop the
     * summary of an event the administrator explicitly asked to be told about.
     */
    public void notifyIfEnabled(AlertEvent event, String content) {
        if (!config.isUsable() || event == null || !config.events().contains(event)) {
            return;
        }
        send(event, content);
    }

    private void send(AlertEvent event, String content) {
        String body;
        try {
            body = DiscordPayload.build(config, content);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not build the Discord payload for a {} event: {}", event, e.toString());
            return;
        }
        backgroundTask.accept(() -> post(event, body));
    }

    private void post(AlertEvent event, String body) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(config.webhookUrl()))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "XRayAntiCheat (admin webhook)")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            LOGGER.warn("The configured Discord webhook URL is not a usable URI, so the {} "
                    + "notification was not sent: {}", event, e.toString());
            return;
        }

        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 429) {
                LOGGER.warn("Discord rate-limited the {} notification (HTTP 429). Consider raising "
                        + "alerts.throttle-minutes or using a quieter channel.", event);
            } else if (status / 100 != 2) {
                LOGGER.warn("Discord rejected the {} notification with HTTP {}. The webhook may have "
                        + "been deleted or the URL mistyped.", event, status);
            }
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it: something else may care that we were
            // interrupted, even though this notification is being abandoned.
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Could not reach Discord for the {} notification: {}", event, e.toString());
        }
    }
}
