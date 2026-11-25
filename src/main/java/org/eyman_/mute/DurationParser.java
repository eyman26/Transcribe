package org.eyman_.mute;


import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {

    public static final Duration MAX = Duration.ofDays(365);
    private static final Pattern TOKEN = Pattern.compile("(\\d{1,9})([smhdw])");

    private DurationParser() {
    }

    public static Duration parse(String input) {
        String s = input.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Empty duration.");
        }
        Matcher m = TOKEN.matcher(s);
        Duration total = Duration.ZERO;
        int pos = 0;
        while (pos < s.length()) {
            m.region(pos, s.length());
            if (!m.lookingAt()) {
                throw new IllegalArgumentException("Invalid duration '" + input + "' - use e.g. 30s, 5m, 2h, 1d, 1h30m.");
            }
            long amount = Long.parseLong(m.group(1));
            total = total.plus(switch (m.group(2).charAt(0)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> Duration.ofDays(amount * 7);
            });
            pos = m.end();
        }
        if (total.isZero()) {
            throw new IllegalArgumentException("Duration must be greater than zero.");
        }
        if (total.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("Duration too long (max 365d). Omit it for a permanent mute.");
        }
        return total;
    }

    public static String format(Duration d) {
        long secs = Math.max(0, d.getSeconds());
        if (secs == 0) {
            return "0s";
        }
        StringBuilder sb = new StringBuilder();
        long days = secs / 86_400;
        secs %= 86_400;
        long hours = secs / 3_600;
        secs %= 3_600;
        long minutes = secs / 60;
        secs %= 60;
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (secs > 0) sb.append(secs).append("s");
        return sb.toString().strip();
    }
}
