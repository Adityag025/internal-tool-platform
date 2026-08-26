package com.acme.toolplatform.domain;

import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed MAJOR.MINOR[.PATCH] version.
 *
 * Why parse at all instead of storing the raw string?
 * Because string ordering is wrong for versions: lexicographically
 * "1.10" &lt; "1.9", but numerically 1.10 is NEWER. Any feature that needs
 * ordering (latest, ranges, "is this a downgrade?") needs numeric parts.
 *
 * We keep {@code raw} as the caller typed it so that "1.0" round-trips as
 * "1.0" and not as "1.0.0" - the version string is part of the artifact's
 * identity/coordinates and must not be silently rewritten.
 */
public record SemanticVersion(int major, int minor, int patch, String raw)
        implements Comparable<SemanticVersion> {

    private static final Pattern PATTERN = Pattern.compile("^(\\d{1,6})\\.(\\d{1,6})(?:\\.(\\d{1,6}))?$");

    private static final Comparator<SemanticVersion> ORDER =
            Comparator.comparingInt(SemanticVersion::major)
                      .thenComparingInt(SemanticVersion::minor)
                      .thenComparingInt(SemanticVersion::patch);

    /**
     * @throws IllegalArgumentException if the text is not MAJOR.MINOR[.PATCH]
     */
    public static SemanticVersion parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        Matcher m = PATTERN.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "version '" + text + "' is not valid; expected MAJOR.MINOR[.PATCH], e.g. 1.2 or 1.2.3");
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return new SemanticVersion(major, minor, patch, text.trim());
    }

    public static boolean isValid(String text) {
        return text != null && PATTERN.matcher(text.trim()).matches();
    }

    @Override
    public int compareTo(SemanticVersion other) {
        return ORDER.compare(this, Objects.requireNonNull(other));
    }

    @Override
    public String toString() {
        return raw;
    }
}
