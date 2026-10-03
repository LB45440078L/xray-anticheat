package io.xrayac.web;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal JSON writer.
 *
 * <p>There is no JSON library in this project on purpose: the panel needs to <em>emit</em> JSON for its
 * fetch-based endpoints and never needs to parse any, because every form posts
 * {@code application/x-www-form-urlencoded}, which the JDK decodes for free. That removes the only
 * reason a dependency would have been needed, and the plugin jar stays free of it.
 *
 * <p>Values are passed in already encoded by the helpers here, so the shape is explicit at each call
 * site and nothing is serialised reflectively.
 *
 * <h2>Why the escaping is stricter than JSON requires</h2>
 * These strings are sometimes embedded in an HTML document (inside {@code <script>} or a data
 * attribute). JSON only requires escaping {@code " \\} and control characters, but a literal
 * {@code </script>} inside a string would end the script block early and let an attacker break out of
 * the data context into markup. Escaping {@code < > &} as {@code \\uXXXX} is harmless to a JSON parser
 * and closes that hole, so it is done unconditionally rather than at the call sites that happen to
 * remember. {@code U+2028}/{@code U+2029} are escaped for the same reason: they are valid in JSON but
 * are line terminators to a JavaScript parser.
 */
public final class Json {

    private Json() {
    }

    /** A JSON string literal, including the surrounding quotes. {@code null} becomes {@code null}. */
    public static String str(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                // Not required by JSON, required to be safe inside an HTML <script> block.
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case '\u2028' -> out.append("\\u2028");
                case '\u2029' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** A JSON number. Non-finite values become {@code null}, because JSON has no NaN or Infinity. */
    public static String num(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        // Round to a stable number of places: these are statistics displayed to humans, and the
        // full double dump is noise in both the payload and the diff when it is asserted on.
        return java.math.BigDecimal.valueOf(value)
                .setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    public static String num(long value) {
        return Long.toString(value);
    }

    public static String bool(boolean value) {
        return Boolean.toString(value);
    }

    /** A JSON array from values that have already been encoded. */
    public static String arr(Collection<String> encodedValues) {
        return encodedValues.stream().collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /** A JSON object from keys and already-encoded values, preserving the order given. */
    public static String obj(Map<String, String> encodedValues) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : encodedValues.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(str(e.getKey())).append(':').append(e.getValue());
        }
        return out.append('}').toString();
    }

    /** A convenience for the common case of building an object inline. */
    public static Map<String, String> map() {
        return new LinkedHashMap<>();
    }
}
