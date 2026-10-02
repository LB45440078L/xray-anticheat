package io.xrayac.paper.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Resolves user-facing text from {@code messages.yml}.
 *
 * <h2>Why no message is written in Java</h2>
 * Server owners localise, retheme and translate. A string compiled into a class cannot be changed
 * without a rebuild, and will silently persist through every upgrade. Keeping the text in a file means
 * an owner can change it in a text editor and reload, and it means this codebase contains no
 * presentation decisions to argue about.
 *
 * <h2>Failures are visible, not silent</h2>
 * A missing key returns a loud placeholder rather than an empty string. An empty message produces a
 * blank line in chat that nobody can trace back to its cause; {@code <missing: path>} names the exact
 * key that needs adding, which turns a support ticket into a one-line fix. An administrator
 * mid-rewrite of the file sees precisely which keys are still owed.
 */
public final class MessageService {

    /**
     * The live message configuration.
     *
     * <p>Deliberately mutable and volatile rather than final: a reload must take effect for every
     * component that already holds this service. Replacing the service object instead would leave
     * {@code AlertService}, {@code EnforcementService} and anything else constructed earlier still
     * quoting the previous file, so an administrator's corrected message would appear everywhere
     * except in the alerts they were trying to fix.
     */
    private volatile FileConfiguration messages;

    public MessageService(FileConfiguration messages) {
        this.messages = messages;
    }

    /** Replaces the message configuration in place, so every holder sees the new text. */
    public void reload(FileConfiguration messages) {
        this.messages = messages;
    }

    /** The configured prefix, prepended by messages that ask for it. */
    public String prefix() {
        return messages.getString("prefix", "");
    }

    /** Resolves a single message, substituting placeholders. */
    public String get(String path, Map<String, String> placeholders) {
        String raw = messages.getString(path);
        if (raw == null) {
            return "<missing: " + path + ">";
        }
        return substitute(raw, placeholders);
    }

    /** Resolves a message with no placeholders. */
    public String get(String path) {
        return get(path, Map.of());
    }

    /** Resolves a list of messages, for the multi-line reports. */
    public List<String> getList(String path, Map<String, String> placeholders) {
        List<String> raw = messages.getStringList(path);
        if (raw.isEmpty()) {
            // A single-string value is accepted as a one-line list, which is what an administrator
            // will naturally write when the default happens to be one line long.
            String single = messages.getString(path);
            if (single != null) {
                return List.of(substitute(single, placeholders));
            }
            return List.of("<missing: " + path + ">");
        }
        List<String> resolved = new ArrayList<>(raw.size());
        for (String line : raw) {
            resolved.add(substitute(line, placeholders));
        }
        return resolved;
    }

    /** Resolves a single message and sends it to a recipient. */
    public void send(CommandSender recipient, String path, Map<String, String> placeholders) {
        recipient.sendMessage(get(path, placeholders));
    }

    public void send(CommandSender recipient, String path) {
        recipient.sendMessage(get(path));
    }

    /** Resolves a list and sends every line. */
    public void sendList(CommandSender recipient, String path, Map<String, String> placeholders) {
        for (String line : getList(path, placeholders)) {
            recipient.sendMessage(line);
        }
    }

    /** Resolves a message, expanding the {@code %prefix%} placeholder to the configured prefix. */
    private String substitute(String template, Map<String, String> placeholders) {
        String result = template;
        if (result.contains("%prefix%")) {
            // Expanded once, up front, so that a message may reference the prefix in any position and
            // a prefix containing a placeholder-like token cannot cause a second pass.
            result = result.replace("%prefix%", prefix());
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    /** Formats a probability for display. */
    public static String formatProbability(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    /** Formats a duration in a compact human-readable form. */
    public static String formatDuration(java.time.Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return (hours / 24) + "d " + (hours % 24) + "h";
    }

    /** Ampersand colour codes, the form an administrator naturally types in YAML. */
    private static final java.util.regex.Pattern AMPERSAND_CODE =
            java.util.regex.Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    /** Ampersand hex colours, translated to the section-sign form the client understands. */
    private static final java.util.regex.Pattern AMPERSAND_HEX =
            java.util.regex.Pattern.compile("&#([0-9a-fA-F]{6})");

    /** A translated colour code, for stripping back to plain text. */
    private static final java.util.regex.Pattern SECTION_CODE =
            java.util.regex.Pattern.compile("§.");

    /**
     * Translates {@code &}-style colour codes to the form the server sends.
     *
     * <p>Only an ampersand followed by a colour character is translated, so text containing a literal
     * ampersand survives — which matters for messages quoting a command or a URL. Both the sixteen
     * legacy codes and {@code &#rrggbb} hex are recognised.
     */
    public static String colour(String text) {
        if (text == null) {
            return "";
        }
        String withHex = AMPERSAND_HEX.matcher(text).replaceAll(match -> hexSequence(match.group(1)));
        return AMPERSAND_CODE.matcher(withHex).replaceAll("§$1");
    }

    /**
     * Removes colour codes, for output destinations that cannot render them.
     *
     * <p>The console logger is the reason this exists: a console that will not translate section signs
     * would otherwise display them as raw glyphs in every line.
     */
    public static String stripColour(String text) {
        return text == null ? "" : SECTION_CODE.matcher(text).replaceAll("");
    }

    /** The section-sign expansion of a six-digit hex colour. */
    private static String hexSequence(String hex) {
        StringBuilder out = new StringBuilder("§x");
        for (int index = 0; index < hex.length(); index++) {
            out.append('§').append(hex.charAt(index));
        }
        return out.toString();
    }
}
