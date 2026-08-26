package com.acme.toolplatform.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure unit test: no Spring context, no database. Runs in milliseconds.
 * This is the "fast lane" the pipeline runs on every single push.
 */
class SemanticVersionTest {

    @ParameterizedTest
    @CsvSource({
        "1.0,   1, 0, 0",
        "1.2,   1, 2, 0",
        "1.2.3, 1, 2, 3",
        "2.0,   2, 0, 0",
        "10.20.30, 10, 20, 30"
    })
    @DisplayName("parses MAJOR.MINOR[.PATCH]")
    void parsesValidVersions(String raw, int major, int minor, int patch) {
        SemanticVersion v = SemanticVersion.parse(raw);
        assertThat(v.major()).isEqualTo(major);
        assertThat(v.minor()).isEqualTo(minor);
        assertThat(v.patch()).isEqualTo(patch);
    }

    @Test
    @DisplayName("keeps the raw string so '1.0' does not become '1.0.0'")
    void preservesRawForm() {
        assertThat(SemanticVersion.parse("1.0").raw()).isEqualTo("1.0");
        assertThat(SemanticVersion.parse("1.0").toString()).isEqualTo("1.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "1", "1.x", "v1.2", "1.2.3.4", "-1.0", "1.0-SNAPSHOT", "", "  "})
    @DisplayName("rejects anything that is not a concrete numeric version")
    void rejectsInvalidVersions(String raw) {
        assertThat(SemanticVersion.isValid(raw)).isFalse();
        assertThatThrownBy(() -> SemanticVersion.parse(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("orders numerically, not lexicographically: 1.10 is newer than 1.9")
    void ordersNumerically() {
        SemanticVersion v1_9 = SemanticVersion.parse("1.9");
        SemanticVersion v1_10 = SemanticVersion.parse("1.10");

        // The bug this test exists to prevent:
        assertThat("1.10".compareTo("1.9")).isNegative();   // string compare says 1.10 is OLDER
        assertThat(v1_10).isGreaterThan(v1_9);              // semantic compare gets it right
    }

    @Test
    void treatsMissingPatchAsZero() {
        assertThat(SemanticVersion.parse("1.2")).isEqualByComparingTo(SemanticVersion.parse("1.2.0"));
    }
}
