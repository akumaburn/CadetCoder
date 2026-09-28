package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.config.Configuration;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the ceiling on a reply's length and the ceiling on what a write may carry are one number.
 *
 * <p>They were two. A reply was refused unparsed past 50,000 characters while a write was allowed
 * the configured ten megabytes, so a model asked for a file larger than a fiftieth of a megabyte
 * had its whole reply rejected -- as a security threat, before any action in it was read. Raising
 * the configured limit did nothing, because the number that actually stopped the reply was written
 * into the validator. These tests hold the two together.</p>
 */
public class OneLimitBoundsAReplyAndItsPayloadTest {

    /** The largest content the shipped configuration allows one action to carry. */
    private static final long CONFIGURED_LIMIT = new Configuration.SecurityConfig().getMaxFileContentBytes();

    /** What the old ceiling was, and what no reply this size should be stopped by now. */
    private static final int ONCE_REFUSED = 50_000;

    private final SecurityValidator validator = new SecurityValidator();

    @Test
    public void aReplyCarryingAFileTheConfigurationAllowsIsNotRefusedForItsLength() {
        assertThat(validator.isResponseSafe(replyOf(ONCE_REFUSED * 2))).isTrue();
    }

    @Test
    public void aReplyCarryingTheLargestAllowedFileIsStillRead() {
        assertThat(validator.isResponseSafe(replyOf((int) CONFIGURED_LIMIT))).isTrue();
    }

    @Test
    public void aReplyLongerThanAnythingItCouldBeCarryingIsRefusedBeforeItIsParsed() {
        assertThat(validator.isResponseSafe(replyOf((int) CONFIGURED_LIMIT * 2))).isFalse();
    }

    /**
     * A reply of the given length that says nothing a validator objects to.
     *
     * <p>One repeated harmless character, so length is the only thing under test: text long enough
     * to matter here would otherwise stand a good chance of tripping an injection pattern.</p>
     */
    private static String replyOf(int characters) {
        return "x".repeat(characters);
    }
}
