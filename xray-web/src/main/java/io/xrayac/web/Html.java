package io.xrayac.web;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * HTML escaping and the small amount of formatting the pages need.
 *
 * <p>Every value that reaches a page goes through {@link #esc(String)}. Player names, world keys,
 * ore ids, moderator notes and audit text are all player- or staff-authored strings that end up in
 * markup, so escaping is applied at the point of writing rather than trusted upstream.
 */
public final class Html {

    /** Formats instants for display in UTC, which is unambiguous for an audit trail. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"));

    private Html() {
    }

    /** Escapes text for use in element content or a quoted attribute. */
    public static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** An instant as a UTC timestamp, or a dash when absent. */
    public static String time(Instant instant) {
        return instant == null ? "&mdash;" : TIMESTAMP.format(instant);
    }

    /** A UUID as text, or a dash when absent. */
    public static String uuid(java.util.UUID id) {
        return id == null ? "&mdash;" : id.toString();
    }

    /**
     * A coarse "how long ago" label.
     *
     * <p>Deliberately coarse: the exact timestamp is shown next to it, and a precise relative time
     * would be a second source of truth that can disagree with it.
     */
    public static String ago(Instant instant) {
        if (instant == null) {
            return "&mdash;";
        }
        Duration age = Duration.between(instant, Instant.now());
        if (age.isNegative()) {
            return "just now";
        }
        long minutes = age.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = age.toHours();
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = age.toDays();
        if (days < 30) {
            return days + "d ago";
        }
        return (days / 30) + "mo ago";
    }

    /**
     * Renders a 0..1 score as a percentage with a fixed number of places.
     *
     * <p>Non-finite values render as a dash rather than {@code NaN%}: an undefined statistic is not
     * zero, and printing it as one would be a claim the engine never made.
     */
    public static String percent(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "&mdash;";
        }
        return String.format("%.1f%%", value * 100.0);
    }

    /** A numeric value with fixed places, or a dash when it is not a number. */
    public static String decimal(double value, int places) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "&mdash;";
        }
        return String.format("%." + places + "f", value);
    }
}
