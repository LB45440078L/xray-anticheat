package io.xrayac.spigot.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.xrayac.core.alert.AlertEvent;
import io.xrayac.core.alert.DiscordConfig;
import io.xrayac.core.evidence.EvidenceStrength;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Discord payload encoding.
 *
 * <p>These run without a server, which is the point of keeping the payload builder separate from the
 * HTTP call. The escaping cases are the ones worth having: a quote or a newline reaching the request
 * body unescaped produces a payload Discord rejects with a 400, and the failure would appear at exactly
 * the moment a real alert is being announced.
 */
@DisplayName("discord payload")
class DiscordPayloadTest {

    /** A single backslash, built from its code point. */
    private static final String BS = String.valueOf((char) 92);

    /** How the JSON writer renders the role mention, escaped for HTML-safety. */
    private static final String escapedMention = BS + "u003c@" + BS + "u0026424242" + BS + "u003e";

    /** Just the escaped mention prefix, for the negative case. */
    private static final String escapedMentionPrefix = BS + "u003c@" + BS + "u0026";

    private static DiscordConfig config(String username, String roleId) {
        return new DiscordConfig(true, "https://discord.com/api/webhooks/1/x", username, roleId,
                Set.of(AlertEvent.ALERT), EvidenceStrength.MODERATE, 0.5, 5);
    }

    @Test
    @DisplayName("the body is a JSON object carrying the username and the content")
    void buildsAJsonObject() {
        String body = DiscordPayload.build(config("XRay", ""), "Steve looks suspicious");
        assertThat(body).startsWith("{").endsWith("}");
        assertThat(body).contains("\"username\":\"XRay\"");
        assertThat(body).contains("\"content\":\"Steve looks suspicious\"");
    }

    @Test
    @DisplayName("quotes and control characters in the message are escaped")
    void escapesQuotesAndNewlines() {
        String body = DiscordPayload.build(config("XRay", ""), "reason: \"X-Ray\"\nsecond line");
        // The literal quote must not appear unescaped, and the newline must be the two-character
        // escape rather than a raw line break, which is invalid inside a JSON string.
        assertThat(body).contains("\\\"X-Ray\\\"");
        assertThat(body).contains("\\n");
        assertThat(body.replace("\\n", "")).doesNotContain("\n");
    }

    @Test
    @DisplayName("a backslash in the message is escaped")
    void escapesBackslash() {
        String body = DiscordPayload.build(config("XRay", ""), "a\\b");
        assertThat(body).contains("a\\\\b");
    }

    @Test
    @DisplayName("minecraft colour codes are removed rather than printed")
    void stripsColourCodes() {
        String body = DiscordPayload.build(config("XRay", ""), "\u00a7cRed\u00a77 grey");
        assertThat(body).doesNotContain("\u00a7");
        assertThat(body).contains("Red grey");
    }

    @Test
    @DisplayName("an over-long message is truncated, not rejected")
    void truncatesLongContent() {
        String huge = "x".repeat(DiscordPayload.MAX_CONTENT_CHARS + 500);
        String formatted = DiscordPayload.format(config("XRay", ""), huge);
        assertThat(formatted.length()).isGreaterThan(DiscordPayload.MAX_CONTENT_CHARS);

        String truncated = DiscordPayload.truncate(formatted);
        assertThat(truncated.length()).isEqualTo(DiscordPayload.MAX_CONTENT_CHARS);
        assertThat(truncated).endsWith(DiscordPayload.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("a message at the limit is left alone")
    void leavesFittingContentAlone() {
        String exact = "x".repeat(DiscordPayload.MAX_CONTENT_CHARS);
        assertThat(DiscordPayload.truncate(exact)).isEqualTo(exact);
        assertThat(DiscordPayload.truncate(exact)).doesNotContain(DiscordPayload.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("a configured role is mentioned and permitted")
    void mentionsConfiguredRole() {
        String body = DiscordPayload.build(config("XRay", "424242"), "Steve was banned");
        // The JSON writer escapes <, > and ampersand as backslash-u sequences so the same strings are
        // safe in an HTML document elsewhere. A JSON parser decodes them straight back, so Discord
        // receives the mention intact; asserting the escaped form keeps this test honest about the
        // wire format. The backslash is built from its code point so this file contains no
        // backslash-u lookalike of its own.
        assertThat(body).contains("\\u003c@\\u0026424242\\u003e");
        // Without allowed_mentions the mention renders but pings nobody.
        assertThat(body).contains("allowed_mentions");
        assertThat(body).contains("\"parse\":[\"roles\"]");
    }

    @Test
    @DisplayName("no role means no mention and no permissions block")
    void noRoleMeansNoMention() {
        String body = DiscordPayload.build(config("XRay", ""), "Steve was banned");
        assertThat(body).doesNotContain("\\u003c@\\u0026");
        assertThat(body).doesNotContain("allowed_mentions");
    }

    @Test
    @DisplayName("blank lines and trailing spaces are collapsed")
    void collapsesBlankLines() {
        String formatted = DiscordPayload.format(config("XRay", ""), "line one   \n\n\nline two  \n");
        assertThat(formatted).isEqualTo("line one\nline two");
    }

    @Test
    @DisplayName("a null message does not produce a malformed body")
    void handlesNullContent() {
        String body = DiscordPayload.build(config("XRay", ""), null);
        assertThat(body).contains("\"content\":\"\"");
    }
}
