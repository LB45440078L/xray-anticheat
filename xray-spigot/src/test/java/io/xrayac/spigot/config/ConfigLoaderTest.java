package io.xrayac.spigot.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.xrayac.core.alert.AlertEvent;
import io.xrayac.core.alert.DiscordConfig;
import io.xrayac.core.evidence.EvidenceStrength;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that the shipped {@code config.yml} actually loads, and loads into what it appears to say.
 *
 * <h2>Why this test exists</h2>
 * Every other test in this repository builds its settings in memory, which means nothing checked the
 * agreement between the YAML an administrator edits and the keys the loader reads. The failure mode is
 * silent in both directions: a key misspelled in the file is simply never read, so the value falls back
 * to a default and the administrator's change does nothing, while a key the loader reads and the file
 * omits is a documented option that does not exist. Both are cheap to assert and expensive to debug.
 *
 * <p>The strongest assertion here is that the shipped file produces no warnings at all. A released
 * configuration that warns on load is a released configuration with a mistake in it.
 */
@DisplayName("shipped configuration")
class ConfigLoaderTest {

    private static YamlConfiguration shipped(String resource) {
        try (InputStream in = ConfigLoaderTest.class.getResourceAsStream("/" + resource)) {
            assertThat(in).as("shipped resource %s on the classpath", resource).isNotNull();
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PluginSettings load() throws ConfigLoader.FatalConfigurationException {
        return new ConfigLoader().load(shipped("config.yml")).settings();
    }

    @Test
    @DisplayName("the shipped file loads cleanly, with no warnings")
    void shippedFileIsClean() throws Exception {
        ConfigLoader.LoadResult result = new ConfigLoader().load(shipped("config.yml"));
        assertThat(result.warnings()).as("warnings from the shipped config.yml").isEmpty();
        assertThat(result.settings()).isNotNull();
    }

    @Test
    @DisplayName("the declared config-version matches the build")
    void configVersionMatches() {
        // If these drift, an administrator's file is silently older or newer than the code that reads
        // it, and the startup warning fires for everybody.
        assertThat(shipped("config.yml").getInt("config-version")).isEqualTo(3);
        for (String file : List.of("messages.yml", "gui.yml", "database.yml")) {
            assertThat(shipped(file).getInt("config-version"))
                    .as("config-version in %s", file).isPositive();
        }
    }

    @Test
    @DisplayName("iron ships enabled with its own priors")
    void ironIsModelled() throws Exception {
        PluginSettings settings = load();
        var iron = settings.enabledOres().stream()
                .filter(ore -> ore.oreId().equals("iron"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("iron is not among the enabled ores"));

        assertThat(iron.hiddenDiscoveryRatePerThousandBlocks()).isEqualTo(12.0);
        assertThat(iron.oreInformedRateMultiplier()).isEqualTo(5.0);
        assertThat(iron.blockKeys())
                .containsExactlyInAnyOrder("minecraft:iron_ore", "minecraft:deepslate_iron_ore");
        // Both the stone and deepslate variants must map, or half of a player's iron is invisible.
        assertThat(settings.oreCatalog().oreIdFor("minecraft:deepslate_iron_ore")).contains("iron");
    }

    @Test
    @DisplayName("iron's prior is higher than diamond's, and its multiplier lower")
    void ironPriorIsDeliberate() throws Exception {
        PluginSettings settings = load();
        Map<String, io.xrayac.core.config.OreProfile> byId = settings.enabledOres().stream()
                .collect(java.util.stream.Collectors.toMap(io.xrayac.core.config.OreProfile::oreId, o -> o));

        io.xrayac.core.config.OreProfile iron = byId.get("iron");
        io.xrayac.core.config.OreProfile diamond = byId.get("diamond");
        // Iron is abundant: a legitimate miner finds buried iron far more often than diamond, so the
        // baseline has to be much higher or ordinary mining reads as suspicious.
        assertThat(iron.hiddenDiscoveryRatePerThousandBlocks())
                .isGreaterThan(diamond.hiddenDiscoveryRatePerThousandBlocks());
        assertThat(iron.oreInformedRateMultiplier())
                .isLessThan(diamond.oreInformedRateMultiplier());
    }

    @Test
    @DisplayName("the alerts section is read as written")
    void alertsSectionParses() throws Exception {
        PluginSettings.Alerts alerts = load().alerts();
        assertThat(alerts.throttleMinutes()).isEqualTo(5);
        assertThat(alerts.throttle()).hasMinutes(5);
        assertThat(alerts.discord().enabled()).isFalse();
        assertThat(alerts.discord().isUsable()).isFalse();
        assertThat(alerts.discord().minimumStrength()).isEqualTo(EvidenceStrength.MODERATE);
        assertThat(alerts.discord().minimumConfidence()).isEqualTo(0.5);
        assertThat(alerts.discord().events()).containsExactlyInAnyOrder(AlertEvent.values());
        assertThat(alerts.discord().username()).isEqualTo("XRay AntiCheat");
    }

    @Test
    @DisplayName("the shipped enforcement commands are empty, keeping the built-in behaviour")
    void enforcementCommandsShipEmpty() throws Exception {
        var commands = load().enforcementCommands();
        assertThat(commands.anyConfigured()).isFalse();
        assertThat(commands.logCommands()).isFalse();
        assertThat(commands.banTemplate(false).isConfigured()).isFalse();
        assertThat(commands.banTemplate(true).isConfigured()).isFalse();
    }

    @Test
    @DisplayName("an enabled webhook with a URL becomes usable")
    void discordEnabledParses() throws Exception {
        YamlConfiguration config = shipped("config.yml");
        config.set("alerts.discord.enabled", true);
        config.set("alerts.discord.webhook-url", "https://discord.com/api/webhooks/1/abc");
        config.set("alerts.discord.events", List.of("BAN", "BAN_WAVE"));
        config.set("alerts.discord.mention-role-id", "99");

        DiscordConfig discord = new ConfigLoader().load(config).settings().alerts().discord();
        assertThat(discord.isUsable()).isTrue();
        assertThat(discord.looksLikeDiscordWebhook()).isTrue();
        assertThat(discord.events()).containsExactlyInAnyOrder(AlertEvent.BAN, AlertEvent.BAN_WAVE);
        assertThat(discord.roleMention()).isEqualTo("<@&99>");
    }

    @Test
    @DisplayName("enabling the webhook without a URL warns and disables it, rather than failing the load")
    void discordWithoutUrlIsDisabledWithAWarning() throws Exception {
        YamlConfiguration config = shipped("config.yml");
        config.set("alerts.discord.enabled", true);
        // webhook-url deliberately left empty.
        ConfigLoader.LoadResult result = new ConfigLoader().load(config);
        assertThat(result.settings().alerts().discord().isUsable()).isFalse();
        assertThat(result.warnings())
                .anyMatch(warning -> warning.contains("alerts.discord") && warning.contains("webhook-url"));
    }

    @Test
    @DisplayName("a non-Discord URL is accepted but warned about")
    void nonDiscordUrlWarns() throws Exception {
        YamlConfiguration config = shipped("config.yml");
        config.set("alerts.discord.enabled", true);
        config.set("alerts.discord.webhook-url", "https://example.test/hook");
        ConfigLoader.LoadResult result = new ConfigLoader().load(config);
        assertThat(result.settings().alerts().discord().isUsable()).isTrue();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("does not look like a Discord"));
    }

    @Test
    @DisplayName("custom commands are read, and a bad placeholder is reported")
    void customCommandsParse() throws Exception {
        YamlConfiguration config = shipped("config.yml");
        config.set("enforcement.commands.ban", "networkban %player% 30d %reason%");
        config.set("enforcement.commands.kick", "kick %usr%");     // %usr% is not a placeholder
        config.set("enforcement.log-commands", true);

        ConfigLoader.LoadResult result = new ConfigLoader().load(config);
        var commands = result.settings().enforcementCommands();
        assertThat(commands.banTemplate(false).render(Map.of("player", "Steve", "reason", "cheating")))
                .isEqualTo("networkban Steve 30d cheating");
        assertThat(commands.logCommands()).isTrue();
        // The warning lists the bare names, without the surrounding percent signs.
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("cannot fill")
                && warning.contains("usr"));
    }

    @Test
    @DisplayName("an invalid event name warns and is ignored, leaving the rest working")
    void invalidEventNameIsReported() throws Exception {
        YamlConfiguration config = shipped("config.yml");
        config.set("alerts.discord.events", List.of("BAN", "NOT_AN_EVENT", "alert"));
        ConfigLoader.LoadResult result = new ConfigLoader().load(config);
        assertThat(result.settings().alerts().discord().events())
                .containsExactlyInAnyOrder(AlertEvent.BAN, AlertEvent.ALERT);
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("NOT_AN_EVENT"));
    }

    @Test
    @DisplayName("the dead keys stay gone")
    void removedKnobsAreAbsent() {
        // These two were parsed into the settings and then never read by anything, so they appeared to
        // be options and did nothing. Removing them was the fix; this guards against reintroducing one.
        YamlConfiguration config = shipped("config.yml");
        assertThat(config.contains("retention.ban-waves-days"))
                .as("retention.ban-waves-days is dead and must not come back").isFalse();
        assertThat(config.contains("tracking.retain-disconnected-players"))
                .as("tracking.retain-disconnected-players is dead and must not come back").isFalse();
    }

    @Test
    @DisplayName("no detection threshold is weakened by the shipped defaults")
    void detectionDefaultsAreConservative() throws Exception {
        PluginSettings settings = load();
        assertThat(settings.evidenceParameters().minimumSampleSize()).isGreaterThanOrEqualTo(10);
        assertThat(settings.evidenceParameters().minimumIndependentGroups()).isGreaterThanOrEqualTo(2);
        // Automatic enforcement must stay off in the shipped configuration.
        assertThat(settings.decisionPolicy().banWave().automaticBan()).isFalse();
        assertThat(settings.suspicionEnabled()).isTrue();
    }
}
