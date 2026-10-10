package io.xrayac.core.enforcement;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The administrator-configurable ways of carrying out enforcement.
 *
 * <h2>What this exists for</h2>
 * A server that already runs a punishment plugin — a network-wide ban system, a temporary-ban manager,
 * a Discord bridge — does not want this plugin writing to the vanilla ban list behind that plugin's
 * back. It wants the decision and the evidence, and its own command run with them. Each action
 * therefore accepts a command template, and when one is configured the plugin runs it <i>instead of</i>
 * its built-in action.
 *
 * <h2>What is never skipped</h2>
 * The plugin still disconnects the player, because that is what the action's name promises, and it
 * still writes its own audit row, because the reason a player was removed must remain answerable even
 * when the punishment itself is recorded somewhere else. A custom command changes <i>how</i> the
 * player is punished, never whether the decision is recorded.
 *
 * @param kick      how to kick, or blank for the plugin's own kick
 * @param ban       how to ban, or blank for the plugin's own ban
 * @param banWave   how a ban wave removes a player; falls back to {@code ban} when blank
 * @param alert     run whenever an alert is raised, in addition to the chat alert; blank for none
 * @param logCommands whether to log each dispatched command, for administrators auditing their own templates
 */
public record EnforcementCommands(
        CommandTemplate kick,
        CommandTemplate ban,
        CommandTemplate banWave,
        CommandTemplate alert,
        boolean logCommands) {

    public EnforcementCommands {
        kick = kick == null ? CommandTemplate.blank() : kick;
        ban = ban == null ? CommandTemplate.blank() : ban;
        banWave = banWave == null ? CommandTemplate.blank() : banWave;
        alert = alert == null ? CommandTemplate.blank() : alert;
    }

    /** The shipped default: no custom commands, so every action uses the built-in behaviour. */
    public static EnforcementCommands none() {
        return new EnforcementCommands(CommandTemplate.blank(), CommandTemplate.blank(),
                CommandTemplate.blank(), CommandTemplate.blank(), false);
    }

    /**
     * The template to use when banning, which depends on the context.
     *
     * <p>A wave falls back to the single-ban command when no wave-specific one is set, because the two
     * differ only in how they are scheduled and an administrator who has configured one ban command has
     * almost certainly configured the one they want used for both.
     */
    public CommandTemplate banTemplate(boolean wave) {
        if (wave && banWave.isConfigured()) {
            return banWave;
        }
        return ban;
    }

    /** Whether any command at all is configured, so the loader can skip the placeholder check. */
    public boolean anyConfigured() {
        return kick.isConfigured() || ban.isConfigured() || banWave.isConfigured() || alert.isConfigured();
    }

    /** Every placeholder named across all templates that the plugin cannot fill. */
    public Set<String> unknownPlaceholders() {
        Set<String> unknown = new LinkedHashSet<>();
        unknown.addAll(kick.unknownPlaceholders());
        unknown.addAll(ban.unknownPlaceholders());
        unknown.addAll(banWave.unknownPlaceholders());
        unknown.addAll(alert.unknownPlaceholders());
        return unknown;
    }

    /**
     * Renders whichever template applies, or an empty string when none does.
     *
     * @param wave         whether this ban is part of a ban wave
     * @param placeholders the values to substitute
     */
    public String renderBan(boolean wave, Map<String, String> placeholders) {
        return banTemplate(wave).render(placeholders);
    }
}
