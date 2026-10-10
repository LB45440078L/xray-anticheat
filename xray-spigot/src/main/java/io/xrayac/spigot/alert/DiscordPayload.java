package io.xrayac.spigot.alert;

import io.xrayac.core.alert.DiscordConfig;
import io.xrayac.spigot.message.MessageService;
import io.xrayac.web.Json;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON body a Discord webhook accepts.
 *
 * <h2>Why the payload is built here rather than inline</h2>
 * Three things about this are easy to get wrong and are worth testing on their own: the JSON escaping,
 * which the existing {@link Json} writer already does correctly; the conversion of the plugin's
 * Minecraft colour markup into something Discord will not print as literal section signs; and the
 * 2000-character ceiling on a webhook's content field, which Discord enforces with a 400 rather than by
 * truncating. A payload builder with no network and no server in it can be checked by a unit test.
 *
 * <p>Deliberately not done here: parsing or validating the response. That belongs to
 * {@link DiscordNotifier}, which owns the HTTP conversation.
 */
public final class DiscordPayload {

    /** Discord's documented limit on the {@code content} field. */
    static final int MAX_CONTENT_CHARS = 2000;

    /** What is appended when a message has to be shortened, so a moderator knows it was. */
    static final String TRUNCATION_MARKER = " [...]";

    private DiscordPayload() {
    }

    /**
     * Builds the request body.
     *
     * @param config  the webhook configuration, supplying the display name and any role ping
     * @param content the message text, in the plugin's usual colour-coded form
     * @return a JSON object ready to POST
     */
    public static String build(DiscordConfig config, String content) {
        Map<String, String> fields = Json.map();
        fields.put("username", Json.str(config.username()));
        fields.put("content", Json.str(format(config, content)));
        if (config.mentionsRole()) {
            // Discord honours a role ping from a webhook only when the request also permits it, so
            // without this the mention renders as inert text and nobody is notified.
            fields.put("allowed_mentions", allowedRoleMentions());
        }
        return Json.obj(fields);
    }

    /**
     * Renders the message for Discord: colour markup removed, trailing whitespace dropped, blank lines
     * collapsed, and the role mention prepended when one is configured.
     *
     * <p>Colour codes are stripped rather than translated because Discord's markdown is unrelated to
     * Minecraft's, and a raw section sign in a channel reads as a rendering error. The result is plain
     * text that keeps its structure through indentation and line breaks.
     */
    static String format(DiscordConfig config, String content) {
        String plain = MessageService.stripColour(
                MessageService.colour(content == null ? "" : content));

        StringBuilder out = new StringBuilder(plain.length());
        for (String line : plain.split("\n", -1)) {
            String trimmed = stripTrailing(line);
            // Collapse runs of blank lines: the message files use them for readability in a console,
            // where they are noise in a chat channel.
            if (trimmed.isEmpty() && (out.isEmpty() || out.charAt(out.length() - 1) == '\n')) {
                continue;
            }
            out.append(trimmed).append('\n');
        }
        String text = stripTrailing(out.toString()).strip();

        if (config.mentionsRole()) {
            return config.roleMention() + "\n" + text;
        }
        return text;
    }

    /**
     * Shortens a message to Discord's limit.
     *
     * <p>Truncating is better than letting Discord reject the whole request, which would lose the
     * notification entirely at the moment it matters most: a long evidence explanation accompanies the
     * strongest cases.
     */
    static String truncate(String text) {
        if (text.length() <= MAX_CONTENT_CHARS) {
            return text;
        }
        int keep = MAX_CONTENT_CHARS - TRUNCATION_MARKER.length();
        return text.substring(0, keep) + TRUNCATION_MARKER;
    }

    private static String allowedRoleMentions() {
        Map<String, String> outer = Json.map();
        outer.put("parse", Json.arr(List.of(Json.str("roles"))));
        return Json.obj(outer);
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
