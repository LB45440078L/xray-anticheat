package io.xrayac.core.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How a ban picks its template, and how unknown placeholders are aggregated across templates. */
@DisplayName("configurable enforcement commands")
class EnforcementCommandsTest {

    private static final Map<String, String> VALUES = Map.of("player", "Steve", "reason", "cheating");

    @Test
    @DisplayName("the default configures nothing and falls back to the built-in actions")
    void defaultsConfigureNothing() {
        EnforcementCommands none = EnforcementCommands.none();
        assertThat(none.anyConfigured()).isFalse();
        assertThat(none.banTemplate(false).isConfigured()).isFalse();
        assertThat(none.renderBan(false, VALUES)).isEmpty();
    }

    @Test
    @DisplayName("a wave uses its own command when one is set")
    void wavePrefersItsOwnCommand() {
        EnforcementCommands commands = commands("networkban %player%", "waveban %player%");
        assertThat(commands.banTemplate(true).render(VALUES)).isEqualTo("waveban Steve");
        assertThat(commands.banTemplate(false).render(VALUES)).isEqualTo("networkban Steve");
    }

    @Test
    @DisplayName("a wave falls back to the ban command when none of its own is set")
    void waveFallsBackToBan() {
        EnforcementCommands commands = commands("networkban %player%", "");
        assertThat(commands.banTemplate(true).render(VALUES)).isEqualTo("networkban Steve");
    }

    @Test
    @DisplayName("a wave with no commands at all still resolves to nothing")
    void waveWithNothingConfigured() {
        EnforcementCommands commands = commands("", "");
        assertThat(commands.banTemplate(true).isConfigured()).isFalse();
        assertThat(commands.renderBan(true, VALUES)).isEmpty();
    }

    @Test
    @DisplayName("unknown placeholders are collected across every template")
    void aggregatesUnknownPlaceholders() {
        EnforcementCommands commands = new EnforcementCommands(
                new CommandTemplate("kick %player%"),
                new CommandTemplate("ban %usr%"),
                new CommandTemplate("waveban %ip%"),
                new CommandTemplate("log %whatever%"),
                false);
        assertThat(commands.unknownPlaceholders()).containsExactlyInAnyOrder("usr", "ip", "whatever");
    }

    @Test
    @DisplayName("null templates are tolerated rather than throwing")
    void toleratesNullTemplates() {
        EnforcementCommands commands = new EnforcementCommands(null, null, null, null, false);
        assertThat(commands.anyConfigured()).isFalse();
        assertThat(commands.unknownPlaceholders()).isEmpty();
    }

    private static EnforcementCommands commands(String ban, String wave) {
        return new EnforcementCommands(CommandTemplate.blank(), new CommandTemplate(ban),
                new CommandTemplate(wave), CommandTemplate.blank(), false);
    }
}
