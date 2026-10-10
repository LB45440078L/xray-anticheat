package io.xrayac.core.alert;

import io.xrayac.core.evidence.EvidenceStrength;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Configuration for forwarding alerts to a Discord channel webhook.
 *
 * <h2>Why a webhook rather than a bot</h2>
 * A webhook is a single URL that accepts an HTTP POST. It needs no bot account, no gateway connection,
 * no library, and no process to keep alive, which means this feature adds nothing to the plugin's
 * dependency footprint: the JDK's own HTTP client is enough. The cost is that it is one-way, which is
 * exactly the requirement — notify a channel when moderators are not online.
 *
 * <h2>Failing closed</h2>
 * The configuration refuses to be enabled without an HTTPS URL. A webhook URL is a credential: anyone
 * holding it can post to the channel, and over plain HTTP it would also be readable in transit. Rather
 * than warn and continue, {@link #enabled} being true with an unusable URL is a configuration error
 * that the loader turns into a disabled feature with a warning, so a mistake here fails visibly at
 * startup instead of silently posting nowhere.
 *
 * @param enabled           whether anything is forwarded at all
 * @param webhookUrl        the channel webhook, which must be HTTPS to be used
 * @param username          the display name the message appears under
 * @param mentionRoleId     an optional role id to ping, without the {@code <@&...>} wrapper
 * @param events            which events are forwarded
 * @param minimumStrength   the weakest evidence worth forwarding
 * @param minimumConfidence the least trustworthy assessment worth forwarding, in {@code [0,1]}
 * @param timeoutSeconds    how long to wait for Discord before giving up, in {@code [1,60]}
 */
public record DiscordConfig(
        boolean enabled,
        String webhookUrl,
        String username,
        String mentionRoleId,
        Set<AlertEvent> events,
        EvidenceStrength minimumStrength,
        double minimumConfidence,
        int timeoutSeconds) {

    /** The display name used when the configuration does not name one. */
    public static final String DEFAULT_USERNAME = "XRay AntiCheat";

    /** The shortest and longest sensible HTTP timeouts, in seconds. */
    private static final int MIN_TIMEOUT = 1;
    private static final int MAX_TIMEOUT = 60;

    public DiscordConfig {
        webhookUrl = webhookUrl == null ? "" : webhookUrl.strip();
        username = username == null || username.isBlank() ? DEFAULT_USERNAME : username.strip();
        mentionRoleId = mentionRoleId == null ? "" : mentionRoleId.strip();
        events = events == null || events.isEmpty() ? Set.copyOf(EnumSet.allOf(AlertEvent.class))
                : Set.copyOf(events);
        minimumStrength = minimumStrength == null ? EvidenceStrength.MODERATE : minimumStrength;

        if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0.0 || minimumConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "alerts.discord.minimum-confidence must lie in [0,1], was " + minimumConfidence);
        }
        if (timeoutSeconds < MIN_TIMEOUT || timeoutSeconds > MAX_TIMEOUT) {
            throw new IllegalArgumentException("alerts.discord.timeout-seconds must lie in ["
                    + MIN_TIMEOUT + ", " + MAX_TIMEOUT + "], was " + timeoutSeconds);
        }
        if (enabled) {
            if (webhookUrl.isBlank()) {
                // Enabling this without a URL is the single most likely mistake: the administrator
                // turned the feature on and expected the plugin to find the channel itself.
                throw new IllegalArgumentException(
                        "alerts.discord.enabled is true but webhook-url is empty");
            }
            if (!webhookUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
                throw new IllegalArgumentException(
                        "alerts.discord.webhook-url must begin with https:// so the webhook is not"
                                + " transmitted in clear text");
            }
        }
    }

    /** The shipped default: present in the file, switched off, forwarding nothing. */
    public static DiscordConfig disabled() {
        return new DiscordConfig(false, "", DEFAULT_USERNAME, "",
                Set.copyOf(EnumSet.allOf(AlertEvent.class)), EvidenceStrength.MODERATE, 0.5, 5);
    }

    /** Whether a usable URL is configured, and so whether posting is worth attempting. */
    public boolean isUsable() {
        return enabled && !webhookUrl.isBlank();
    }

    /**
     * Whether this event should be forwarded.
     *
     * <p>Both gates must open, and they are the same two the decision layer uses: the evidence band and
     * the confidence. Forwarding on the band alone would push a single dramatic-looking signal with no
     * corroboration into a channel where nobody can check it.
     */
    public boolean accepts(AlertEvent event, EvidenceStrength strength, double confidence) {
        if (!isUsable() || event == null || !events.contains(event)) {
            return false;
        }
        if (strength == null || strength.ordinal() < minimumStrength.ordinal()) {
            return false;
        }
        return confidence >= minimumConfidence;
    }

    /** Whether a role should be pinged alongside the message. */
    public boolean mentionsRole() {
        return !mentionRoleId.isBlank();
    }

    /** The Discord role mention, in the form Discord expects it. */
    public String roleMention() {
        return mentionsRole() ? "<@&" + mentionRoleId + ">" : "";
    }

    /** Whether the URL has the shape of a Discord webhook, for a configuration warning. */
    public boolean looksLikeDiscordWebhook() {
        String lower = webhookUrl.toLowerCase(Locale.ROOT);
        return lower.contains("discord.com/api/webhooks/") || lower.contains("discordapp.com/api/webhooks/");
    }
}
