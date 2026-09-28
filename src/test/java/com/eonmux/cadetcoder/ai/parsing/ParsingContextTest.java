package com.eonmux.cadetcoder.ai.parsing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A builder that accepts a setting and then discards it is worse than one that never offered the
 * setting at all: the caller has no way to tell. These tests hold every value the builder accepts
 * to the context it produces.
 */
class ParsingContextTest {

    @Test
    void theBuilderCarriesEveryParsingPreferenceItWasGiven() {
        ParsingContext context = new ParsingContext.Builder("do the work")
                .strictMode(true)
                .maxRetries(7)
                .confidenceThreshold(0.95)
                .build();

        assertThat(context.isStrictMode()).isTrue();
        assertThat(context.getMaxRetries()).isEqualTo(7);
        assertThat(context.getConfidenceThreshold()).isEqualTo(0.95);
    }

    @Test
    void theBuilderCarriesEveryCollectionItWasGiven() {
        ParsingContext context = new ParsingContext.Builder("do the work")
                .workingDirectory("/tmp/project")
                .addSessionState("key", "value")
                .setRecentCommands(List.of("read", "grep"))
                .setAvailableCommands(Set.of("read", "write"))
                .addEnvironmentVariable("LANG", "en_GB")
                .build();

        assertThat(context.getUserRequest()).isEqualTo("do the work");
        assertThat(context.getWorkingDirectory()).isEqualTo("/tmp/project");
        assertThat(context.getSessionState()).containsEntry("key", "value");
        assertThat(context.getRecentCommands()).containsExactly("read", "grep");
        assertThat(context.getAvailableCommands()).containsExactlyInAnyOrder("read", "write");
        assertThat(context.getEnvironmentVariables()).containsEntry("LANG", "en_GB");
    }

    @Test
    void anUnconfiguredBuilderMatchesTheDirectConstructor() {
        ParsingContext built  = new ParsingContext.Builder("request").build();
        ParsingContext direct = new ParsingContext("request", System.getProperty("user.dir"));

        assertThat(built.isStrictMode()).isEqualTo(direct.isStrictMode());
        assertThat(built.getMaxRetries()).isEqualTo(direct.getMaxRetries());
        assertThat(built.getConfidenceThreshold()).isEqualTo(direct.getConfidenceThreshold());
        assertThat(built.getWorkingDirectory()).isEqualTo(direct.getWorkingDirectory());
    }
}
