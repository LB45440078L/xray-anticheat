package io.xrayac.core.alert;

/**
 * The kinds of event that can be forwarded to an external notification channel.
 *
 * <p>These are the events a moderator would want to hear about while away from the game: an alert
 * raised, and each of the three ways a player can actually be removed. The order is deliberate, from
 * least to most consequential, so that a configuration which lists a minimum severity reads naturally.
 */
public enum AlertEvent {

    /** Evidence crossed the alert threshold. Nothing happened to the player. */
    ALERT,

    /** A player was disconnected, automatically or by a moderator. */
    KICK,

    /** A single player was banned. */
    BAN,

    /** A ban wave removed one or more players. */
    BAN_WAVE;

    /** The configuration key this event is written as. */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Parses a configuration value, returning null when it names no known event. */
    public static AlertEvent fromKey(String value) {
        if (value == null) {
            return null;
        }
        String normalised = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (AlertEvent event : values()) {
            if (event.name().equals(normalised)) {
                return event;
            }
        }
        return null;
    }
}
