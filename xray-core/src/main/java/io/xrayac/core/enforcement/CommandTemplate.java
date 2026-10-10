package io.xrayac.core.enforcement;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A console command template an administrator has configured, with placeholders the plugin fills in.
 *
 * <h2>Why this is its own type</h2>
 * Running an administrator's text as a server command is the one place the plugin executes something
 * it did not write. Keeping the rendering here, in the platform-neutral core, means the substitution
 * and the sanitising are covered by the core's test suite rather than by a manual attempt on a live
 * server, and it keeps the Spigot layer down to a single dispatch call.
 *
 * <h2>Placeholders are substituted; values are sanitised</h2>
 * Placeholders are written {@code %like-this%}. The <i>values</i> substituted in are sanitised: the
 * section-sign colour codes this plugin's message files use are stripped, and control characters and
 * newlines are collapsed. A colour code inside a command argument is at best noise and at worst
 * breaks argument parsing, and a newline would let a message value inject a second command.
 *
 * <p>The template itself is <b>not</b> sanitised, so an administrator who wants a chat colour in a
 * {@code broadcast} command can still write one. That is a deliberate asymmetry: the administrator's
 * text is trusted, the plugin's generated values are not.
 *
 * @param raw the configured template, stripped; empty means "not configured"
 */
public record CommandTemplate(String raw) {

    /** Placeholders the plugin can substitute. Anything else is reported as a configuration warning. */
    public static final Set<String> KNOWN_PLACEHOLDERS = Set.of(
            "player", "uuid", "world", "reason", "strength", "confidence", "score",
            "samples", "signals", "decibans", "snapshot-id", "action", "moderator");

    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z][a-z0-9-]*)%");
    private static final Pattern COLOUR_CODE = Pattern.compile("(?i)\u00a7[0-9a-fk-orx]");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\t]]");

    public CommandTemplate {
        raw = raw == null ? "" : raw.strip();
    }

    /** The not-configured template, used as the fallback when a key is absent. */
    public static CommandTemplate blank() {
        return new CommandTemplate("");
    }

    /** Whether an administrator has actually configured a command here. */
    public boolean isConfigured() {
        return !raw.isEmpty();
    }

    /** The placeholder names this template refers to, in the order they appear. */
    public Set<String> placeholders() {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(raw);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /** The placeholders this template names that the plugin cannot fill, for configuration warnings. */
    public Set<String> unknownPlaceholders() {
        Set<String> unknown = new LinkedHashSet<>(placeholders());
        unknown.removeAll(KNOWN_PLACEHOLDERS);
        return unknown;
    }

    /**
     * Renders the command ready to dispatch.
     *
     * @param values the placeholder values, typically the same map used for the message templates
     * @return the command line, or an empty string if this template is not configured
     */
    public String render(Map<String, String> values) {
        if (!isConfigured()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = values.get(matcher.group(1));
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(replacement == null ? matcher.group(0) : sanitise(replacement)));
        }
        matcher.appendTail(out);
        return out.toString().strip();
    }

    /**
     * Makes a plugin-generated value safe to place inside a command line.
     *
     * <p>Colour codes go, because they are markup rather than content. Tabs, newlines and other
     * control characters go, because a newline in a substituted value would terminate this command
     * and begin another. Runs of whitespace collapse so that a value which was empty does not leave a
     * gap that shifts the command's arguments.
     */
    private static String sanitise(String value) {
        String cleaned = COLOUR_CODE.matcher(value).replaceAll("");
        cleaned = CONTROL_CHARS.matcher(cleaned).replaceAll(" ");
        return cleaned.replaceAll("\\s+", " ").strip();
    }
}
