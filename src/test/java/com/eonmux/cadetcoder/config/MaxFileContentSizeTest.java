package com.eonmux.cadetcoder.config;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write-size limit, in the unit its callers compare against.
 *
 * <p>Two callers each did {@code megabytes * 1024 * 1024} and one of them did it in {@code int}
 * arithmetic. That overflows at 2048: raising the limit to 2048MB produced {@code
 * Integer.MIN_VALUE}, so {@code content.length() > limit} was true for everything and the model
 * could no longer write any file at all -- while the message told the user the limit was 2048MB.
 * A configured value of zero or less had the same effect through a different route.</p>
 */
public class MaxFileContentSizeTest {

    private static long bytesFor(int megabytes) {
        Configuration.SecurityConfig security = new Configuration.SecurityConfig();
        security.setMaxFileContentSize(megabytes);
        return security.getMaxFileContentBytes();
    }

    @Test
    public void theLimitIsAlwaysPositiveSoItCannotRejectEveryWrite() {
        for (int megabytes : new int[] {1, 10, 1024, 2048, 4096, 999_999, Integer.MAX_VALUE}) {
            assertThat(bytesFor(megabytes))
                    .as("%dMB must not overflow into a negative limit", megabytes)
                    .isPositive();
        }
    }

    @Test
    public void raisingTheLimitRaisesIt() {
        assertThat(bytesFor(2048))
                .as("the int computation gave Integer.MIN_VALUE here")
                .isEqualTo(2048L * 1024 * 1024);
        assertThat(bytesFor(4096))
                .as("the int computation gave exactly zero here")
                .isEqualTo(4096L * 1024 * 1024);
    }

    @Test
    public void aMeaninglessLimitFallsBackToTheShippedDefault() {
        long shipped = (long) Configuration.SecurityConfig.DEFAULT_MAX_FILE_CONTENT_MB * 1024 * 1024;

        assertThat(bytesFor(0)).isEqualTo(shipped);
        assertThat(bytesFor(-1))
                .as("a negative limit must not become 'refuse everything', and must not become "
                    + "'no limit' either")
                .isEqualTo(shipped);
    }

    @Test
    public void theDefaultIsTenMegabytes() {
        assertThat(new Configuration.SecurityConfig().getMaxFileContentBytes())
                .isEqualTo(10L * 1024 * 1024);
    }
}
