package io.xrayac.core.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the configurable command templates.
 *
 * <p>The cases that matter are the ones where a mistake would be invisible on a live server. A newline
 * smuggled in through a substituted value would let a message field start a second console command; a
 * colour code inside an argument breaks the receiving plugin's argument parsing; and a misspelled
 * placeholder is dispatched verbatim, so the command appears to work while doing the wrong thing.
 */
@DisplayName("command templates")
class CommandTemplateTest {

    private static Map<String, String> values() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("player", "Steve");
        map.put("reason", "ore-vision cheating");
        map.put("strength", "STRONG");
        return map;
    }

    @Test
    @DisplayName("an absent command is not configured and renders nothing")
    void blankIsNotConfigured() {
        assertThat(CommandTemplate.blank().isConfigured()).isFalse();
        assertThat(new CommandTemplate(null).isConfigured()).isFalse();
        assertThat(new CommandTemplate("   ").isConfigured()).isFalse();
        assertThat(CommandTemplate.blank().render(values())).isEmpty();
    }

    @Test
    @DisplayName("known placeholders are substituted")
    void substitutesKnownPlaceholders() {
        CommandTemplate template = new CommandTemplate("kick %player% %reason%");
        assertThat(template.render(values())).isEqualTo("kick Steve ore-vision cheating");
    }

    @Test
    @DisplayName("an unknown placeholder is left verbatim and reported")
    void unknownPlaceholderIsReported() {
        CommandTemplate template = new CommandTemplate("ban %player% %usr%");
        assertThat(template.unknownPlaceholders()).containsExactly("usr");
        // Left in place rather than blanked: an administrator seeing "%usr%" in the log has a chance
        // to notice the typo, whereas an empty substitution would look like it had worked.
        assertThat(template.render(values())).isEqualTo("ban Steve %usr%");
    }

    @Test
    @DisplayName("a known placeholder set is complete")
    void knownPlaceholdersCoverTheDocumentedSet() {
        assertThat(CommandTemplate.KNOWN_PLACEHOLDERS)
                .contains("player", "uuid", "world", "reason", "strength", "confidence",
                        "score", "samples", "signals", "decibans", "snapshot-id", "action", "moderator");
    }

    @Test
    @DisplayName("colour codes in a substituted value are stripped")
    void stripsColourFromValues() {
        Map<String, String> map = values();
        map.put("reason", "\u00a7cSTRONG\u00a77 evidence");
        assertThat(new CommandTemplate("ban %player% %reason%").render(map))
                .isEqualTo("ban Steve STRONG evidence");
    }

    @Test
    @DisplayName("a newline in a value cannot start a second command")
    void flattensNewlinesInValues() {
        Map<String, String> map = values();
        map.put("reason", "cheating\nop Steve");
        String rendered = new CommandTemplate("ban %player% %reason%").render(map);
        assertThat(rendered).doesNotContain("\n");
        assertThat(rendered).isEqualTo("ban Steve cheating op Steve");
    }

    @Test
    @DisplayName("colour codes written by the administrator in the template survive")
    void keepsColourInTheTemplate() {
        // The asymmetry is deliberate: the administrator's own text is trusted, so a broadcast
        // command can still carry a colour, while the values the plugin fills in are sanitised.
        assertThat(new CommandTemplate("broadcast \u00a7cAlert for %player%").render(values()))
                .isEqualTo("broadcast \u00a7cAlert for Steve");
    }

    @Test
    @DisplayName("placeholders are listed without duplicates")
    void listsPlaceholdersOnce() {
        assertThat(new CommandTemplate("%player% and %player% and %reason%").placeholders())
                .containsExactly("player", "reason");
    }

    @Test
    @DisplayName("a multi-word reason survives as one substitution")
    void handlesMultiWordValues() {
        Map<String, String> map = values();
        map.put("reason", "X-Ray  /  ore-vision  cheating");
        // Runs of whitespace collapse, so a value with odd spacing does not shift the arguments.
        assertThat(new CommandTemplate("tban %player% 30d %reason%").render(map))
                .isEqualTo("tban Steve 30d X-Ray / ore-vision cheating");
    }
}
