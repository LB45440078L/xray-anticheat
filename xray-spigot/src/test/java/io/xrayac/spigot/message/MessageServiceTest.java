package io.xrayac.spigot.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the message layer, including the integrity of the shipped {@code messages.yml}.
 *
 * <p>The file-level checks are the point: every placeholder in the console reporting has to be supplied
 * by the plugin, and a message that references one nobody provides prints the literal {@code %token%} to
 * the console, which reads as a broken plugin rather than a missing value.
 */
@DisplayName("message service")
class MessageServiceTest {

    /** Placeholders the plugin supplies for console reporting, from {@code consolePlaceholders()}. */
    private static final Set<String> CONSOLE_PLACEHOLDERS = Set.of(
            "prefix", "version", "dialect", "schema-version", "mode", "ores", "ore-count",
            "threads", "lookback", "error", "count");

    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z][a-z0-9-]*)%");

    private static YamlConfiguration yaml() throws IOException {
        try (InputStream in = MessageServiceTest.class.getResourceAsStream("/messages.yml")) {
            assertThat(in).as("messages.yml must be on the test classpath").isNotNull();
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static MessageService shipped() throws IOException {
        return new MessageService(yaml());
    }

    // ---------------------------------------------------------------------------------------
    // Colour handling
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("ampersand codes are translated to section signs")
    void translatesAmpersandCodes() {
        assertThat(MessageService.colour("&aGreen &lBold &8dim")).isEqualTo("§aGreen §lBold §8dim");
    }

    @Test
    @DisplayName("an ampersand not followed by a colour character is left alone")
    void leavesLiteralAmpersandsAlone() {
        // 'S' and a space are not colour characters, so ordinary text survives untouched.
        assertThat(MessageService.colour("Tom & Jerry")).isEqualTo("Tom & Jerry");
        assertThat(MessageService.colour("Marks & Spencer")).isEqualTo("Marks & Spencer");
    }

    @Test
    @DisplayName("an ampersand followed by a hex letter is a colour code, by convention")
    void ampersandBeforeHexLetterIsAColourCode() {
        // Pinned deliberately, because it looks like a bug and is not one: 'D' is a colour character, so
        // "R&D" is read as "R" plus a light-purple D. Every ampersand-based colour system behaves this
        // way, and a message needing a literal ampersand before A-F, K-O, R or a digit must not use it.
        assertThat(MessageService.colour("R&D")).isEqualTo("R§D");
    }

    @Test
    @DisplayName("hex colours become the section-sign form")
    void translatesHexColours() {
        assertThat(MessageService.colour("&#ff8800warn")).isEqualTo("§x§f§f§8§8§0§0warn");
    }

    @Test
    @DisplayName("stripping removes codes and leaves the text")
    void stripsColourCodes() {
        assertThat(MessageService.stripColour("§aGreen §lBold§r")).isEqualTo("Green Bold");
    }

    @Test
    @DisplayName("a missing key names itself rather than rendering blank")
    void missingKeyIsLoud() {
        MessageService messages = new MessageService(new YamlConfiguration());
        assertThat(messages.get("nothing.here")).isEqualTo("<missing: nothing.here>");
    }

    // ---------------------------------------------------------------------------------------
    // The shipped file
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the shipped banner renders and resolves every placeholder it uses")
    void shippedBannerRenders() throws IOException {
        MessageService messages = shipped();

        List<String> banner = new ConsoleReporter(messages).bannerLines(consolePlaceholders());

        assertThat(banner).isNotEmpty();
        assertThat(banner).allSatisfy(line -> assertThat(line).doesNotContain("<missing"));
        assertThat(banner).allSatisfy(line -> assertThat(line).doesNotContain("%"));
    }

    @Test
    @DisplayName("the banner art stays out of the block-drawing and box-drawing ranges")
    void bannerArtIsConsoleSafe() throws IOException {
        MessageService messages = shipped();

        // The stated reason the art is plain ASCII: those ranges are exactly what an older console font
        // cannot draw, and a banner that renders as mojibake is worse than no banner. A future edit that
        // pastes in fancier art would otherwise silently break those consoles.
        for (String line : messages.getList("lifecycle.banner", Map.of())) {
            for (int index = 0; index < line.length(); index++) {
                char character = line.charAt(index);
                assertThat(Character.UnicodeBlock.of(character))
                        .as("banner character U+%04X in %s", (int) character, line)
                        .isNotEqualTo(Character.UnicodeBlock.BLOCK_ELEMENTS)
                        .isNotEqualTo(Character.UnicodeBlock.BOX_DRAWING);
            }
        }
    }

    @Test
    @DisplayName("every placeholder in the lifecycle text is one the plugin actually supplies")
    void lifecyclePlaceholdersAreSupplied() throws IOException {
        ConfigurationSection lifecycle = yaml().getConfigurationSection("lifecycle");
        assertThat(lifecycle).as("messages.yml must define a lifecycle section").isNotNull();

        List<String> unknown = new ArrayList<>();
        for (String text : lifecycleTexts(lifecycle)) {
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                String token = matcher.group(1);
                if (!CONSOLE_PLACEHOLDERS.contains(token)) {
                    unknown.add(token + " in: " + text);
                }
            }
        }

        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName("the event wording does not still claim nothing is ever enforced")
    void readyMessageDoesNotClaimAlertOnly() throws IOException {
        // The shipped enforcement mode is BAN_WAVE, so a message hard-coding ALERT_ONLY would be wrong
        // the moment an operator looked at it next to their configuration.
        assertThat(shipped().get("lifecycle.ready")).doesNotContain("ALERT_ONLY");
    }

    /** Every string in a section and its nested lists, so nothing hides from the placeholder check. */
    private static List<String> lifecycleTexts(ConfigurationSection section) {
        List<String> texts = new ArrayList<>();
        for (String key : section.getKeys(true)) {
            Object value = section.get(key);
            if (value instanceof String text) {
                texts.add(text);
            } else if (value instanceof List<?> list) {
                for (Object entry : list) {
                    texts.add(String.valueOf(entry));
                }
            }
        }
        return texts;
    }

    private static Map<String, String> consolePlaceholders() {
        return Map.of(
                "version", "1.0.0",
                "dialect", "SQLITE",
                "schema-version", "1",
                "mode", "BAN_WAVE",
                "ores", "Diamond, Emerald",
                "ore-count", "2",
                "threads", "4 platform",
                "lookback", "90");
    }
}
