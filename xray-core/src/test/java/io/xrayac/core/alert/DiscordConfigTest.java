package io.xrayac.core.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.xrayac.core.evidence.EvidenceStrength;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Discord webhook configuration.
 *
 * <p>The validation cases carry the weight here. A webhook URL is a credential, so a plain-HTTP
 * address is refused outright rather than warned about, and an administrator who switches the feature
 * on but forgets the URL gets an error at startup instead of a channel that silently stays empty.
 */
@DisplayName("discord webhook configuration")
class DiscordConfigTest {

    private static DiscordConfig enabled(String url) {
        return new DiscordConfig(true, url, "XRay", "", Set.of(AlertEvent.ALERT), EvidenceStrength.MODERATE,
                0.5, 5);
    }

    @Test
    @DisplayName("the shipped default forwards nothing")
    void defaultIsDisabled() {
        DiscordConfig off = DiscordConfig.disabled();
        assertThat(off.enabled()).isFalse();
        assertThat(off.isUsable()).isFalse();
        assertThat(off.accepts(AlertEvent.ALERT, EvidenceStrength.VERY_STRONG, 0.99)).isFalse();
    }

    @Test
    @DisplayName("enabling without a URL is rejected")
    void enabledWithoutUrlIsRejected() {
        assertThatThrownBy(() -> enabled(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("webhook-url is empty");
    }

    @Test
    @DisplayName("a plain-HTTP URL is rejected, because the webhook is a credential")
    void plainHttpIsRejected() {
        assertThatThrownBy(() -> enabled("http://discord.com/api/webhooks/1/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://");
    }

    @Test
    @DisplayName("an HTTPS URL is accepted and recognised")
    void httpsIsAccepted() {
        DiscordConfig config = enabled("https://discord.com/api/webhooks/123/abc");
        assertThat(config.isUsable()).isTrue();
        assertThat(config.looksLikeDiscordWebhook()).isTrue();
    }

    @Test
    @DisplayName("a URL that is not a Discord webhook is still usable but not recognised")
    void nonDiscordUrlIsUsableButFlagged() {
        DiscordConfig config = enabled("https://example.com/hook");
        assertThat(config.isUsable()).isTrue();
        assertThat(config.looksLikeDiscordWebhook()).isFalse();
    }

    @Test
    @DisplayName("confidence outside [0,1] is rejected")
    void confidenceMustBeAProbability() {
        assertThatThrownBy(() -> new DiscordConfig(true, "https://x.test/h", "n", "",
                Set.of(AlertEvent.ALERT), EvidenceStrength.WEAK, 1.5, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum-confidence");
        assertThatThrownBy(() -> new DiscordConfig(true, "https://x.test/h", "n", "",
                Set.of(AlertEvent.ALERT), EvidenceStrength.WEAK, Double.NaN, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the timeout is bounded")
    void timeoutIsBounded() {
        assertThatThrownBy(() -> new DiscordConfig(true, "https://x.test/h", "n", "",
                Set.of(AlertEvent.ALERT), EvidenceStrength.WEAK, 0.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiscordConfig(true, "https://x.test/h", "n", "",
                Set.of(AlertEvent.ALERT), EvidenceStrength.WEAK, 0.5, 600))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an empty event list means every event rather than none")
    void emptyEventListMeansAll() {
        // The alternative reading — enable the feature, select nothing, receive nothing — is a
        // configuration that looks correct and does not work, so it is not permitted.
        DiscordConfig config = new DiscordConfig(true, "https://discord.com/api/webhooks/1/x", "n", "",
                Set.of(), EvidenceStrength.MODERATE, 0.5, 5);
        assertThat(config.events()).containsExactlyInAnyOrder(AlertEvent.values());
    }

    @Test
    @DisplayName("an event that is not selected is not forwarded")
    void eventFilterIsApplied() {
        DiscordConfig onlyBans = new DiscordConfig(true, "https://discord.com/api/webhooks/1/x", "n", "",
                Set.of(AlertEvent.BAN), EvidenceStrength.MODERATE, 0.5, 5);
        assertThat(onlyBans.accepts(AlertEvent.BAN, EvidenceStrength.VERY_STRONG, 0.9)).isTrue();
        assertThat(onlyBans.accepts(AlertEvent.ALERT, EvidenceStrength.VERY_STRONG, 0.9)).isFalse();
    }

    @Test
    @DisplayName("both the evidence band and the confidence gate must open")
    void bothGatesMustOpen() {
        DiscordConfig config = new DiscordConfig(true, "https://discord.com/api/webhooks/1/x", "n", "",
                Set.of(AlertEvent.ALERT), EvidenceStrength.STRONG, 0.75, 5);
        assertThat(config.accepts(AlertEvent.ALERT, EvidenceStrength.STRONG, 0.75)).isTrue();
        assertThat(config.accepts(AlertEvent.ALERT, EvidenceStrength.MODERATE, 0.99)).isFalse();
        assertThat(config.accepts(AlertEvent.ALERT, EvidenceStrength.VERY_STRONG, 0.60)).isFalse();
        assertThat(config.accepts(AlertEvent.ALERT, null, 0.99)).isFalse();
    }

    @Test
    @DisplayName("a role mention is built in the form Discord expects")
    void roleMentionFormat() {
        DiscordConfig config = new DiscordConfig(true, "https://discord.com/api/webhooks/1/x", "n", "42",
                Set.of(AlertEvent.ALERT), EvidenceStrength.MODERATE, 0.5, 5);
        assertThat(config.mentionsRole()).isTrue();
        assertThat(config.roleMention()).isEqualTo("<@&42>");

        DiscordConfig noRole = enabled("https://discord.com/api/webhooks/1/x");
        assertThat(noRole.mentionsRole()).isFalse();
        assertThat(noRole.roleMention()).isEmpty();
    }

    @Test
    @DisplayName("a blank username becomes the shipped default")
    void blankUsernameFallsBack() {
        DiscordConfig config = new DiscordConfig(true, "https://discord.com/api/webhooks/1/x", "  ", "",
                Set.of(AlertEvent.ALERT), EvidenceStrength.MODERATE, 0.5, 5);
        assertThat(config.username()).isEqualTo(DiscordConfig.DEFAULT_USERNAME);
    }

    @Test
    @DisplayName("event keys parse case-insensitively and reject nonsense")
    void parsesEventKeys() {
        assertThat(AlertEvent.fromKey("ban_wave")).isEqualTo(AlertEvent.BAN_WAVE);
        assertThat(AlertEvent.fromKey("BAN-WAVE")).isEqualTo(AlertEvent.BAN_WAVE);
        assertThat(AlertEvent.fromKey("alerts")).isNull();
        assertThat(AlertEvent.fromKey(null)).isNull();
    }
}
