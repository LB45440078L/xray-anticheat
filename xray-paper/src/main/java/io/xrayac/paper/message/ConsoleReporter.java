package io.xrayac.paper.message;

import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the plugin's lifecycle reporting to the server console, in colour, from {@code messages.yml}.
 *
 * <h2>Why the console is addressed as a sender</h2>
 * The Minecraft console is a {@link CommandSender}, and sending a message to it is what lets the server
 * translate section-sign colour codes into whatever its terminal actually understands — ANSI on a
 * modern terminal, or nothing at all on a console that cannot render them. Logging to SLF4J instead
 * would print the raw codes as glyphs, because a logging framework has no idea that {@code §} means
 * anything.
 *
 * <h2>Why it still falls back to the logger</h2>
 * The console sender only exists once the server is up. Class-loading this class in a context without a
 * running server — a unit test, or the loader probing the plugin — would throw, and losing a startup
 * line entirely is worse than losing its colour. Every write therefore falls back to a colour-stripped
 * log line, so the message is always delivered exactly once.
 */
public final class ConsoleReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsoleReporter.class);

    private final MessageService messages;

    public ConsoleReporter(MessageService messages) {
        this.messages = messages;
    }

    /**
     * Prints the configured banner.
     *
     * <p>The art lives in {@code messages.yml} rather than in a constant so an owner can replace it, and
     * the shipped one is deliberately plain ASCII: the block characters that make fancier banners look
     * good are exactly the ones an older console font cannot draw, and a banner that renders as
     * mojibake on someone's console is worse than no banner.
     */
    public void banner(Map<String, String> placeholders) {
        for (String line : messages.getList("lifecycle.banner", placeholders)) {
            write(line);
        }
    }

    /** Prints one configured line, coloured. */
    public void line(String path, Map<String, String> placeholders) {
        write(messages.get(path, placeholders));
    }

    /** Prints every line of a configured list, coloured. */
    public void lines(String path, Map<String, String> placeholders) {
        for (String line : messages.getList(path, placeholders)) {
            write(line);
        }
    }

    /** Sends one line to the console, or to the logger when there is no console to send to. */
    private void write(String raw) {
        String coloured = MessageService.colour(raw);
        try {
            CommandSender console = Bukkit.getConsoleSender();
            if (console != null) {
                console.sendMessage(coloured);
                return;
            }
        } catch (IllegalStateException | UnsupportedOperationException e) {
            // No server is running yet; fall through to the logger rather than dropping the line.
        }
        LOGGER.info("{}", MessageService.stripColour(coloured));
    }

    /** The configured banner as plain lines, for tests and for callers that need the text only. */
    public List<String> bannerLines(Map<String, String> placeholders) {
        return messages.getList("lifecycle.banner", placeholders).stream()
                .map(MessageService::colour)
                .toList();
    }
}
