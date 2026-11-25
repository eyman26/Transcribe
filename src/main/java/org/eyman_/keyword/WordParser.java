package org.eyman_.keyword;


import java.util.Locale;
import java.util.regex.Pattern;

public final class WordParser {

    public static final int MAX_LENGTH = 32;

    private static final Pattern VALID_WORD = Pattern.compile("[a-z0-9']+");

    private WordParser() {
    }

    public static String normalize(String raw) {
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A keyword must not be empty.");
        }
        String n = normalize(raw);
        if (n.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Too long (max " + MAX_LENGTH + " characters).");
        }
        if (!VALID_WORD.matcher(n).matches()) {
            throw new IllegalArgumentException(
                    "A keyword is a single word using only letters a-z, digits and apostrophes: '" + n + "'.");
        }
        if (toId(n).isEmpty()) {
            throw new IllegalArgumentException("Must contain at least one letter or digit.");
        }
        return n;
    }

    public static String toId(String normalized) {
        return normalized.replace("'", "");
    }
}
