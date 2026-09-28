package com.eonmux.cadetcoder.net;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pool settings that have to be in place before the first HttpClient is built. */
public class HttpClientTuningTest {

    private String original;

    @Before
    public void setUp() {
        original = System.getProperty(HttpClientTuning.KEEP_ALIVE_PROPERTY);
        System.clearProperty(HttpClientTuning.KEEP_ALIVE_PROPERTY);
    }

    @After
    public void tearDown() {
        if (original == null) {
            System.clearProperty(HttpClientTuning.KEEP_ALIVE_PROPERTY);
        } else {
            System.setProperty(HttpClientTuning.KEEP_ALIVE_PROPERTY, original);
        }
    }

    @Test
    public void anIdleConnectionIsDroppedBeforeTheFarEndDropsIt() {
        HttpClientTuning.apply();

        // The JDK's 1200s default outlives every load balancer that sits in front of a model API,
        // so the pool hands back sockets the far end closed minutes ago.
        int seconds = Integer.parseInt(System.getProperty(HttpClientTuning.KEEP_ALIVE_PROPERTY));
        assertThat(seconds).isLessThan(60);
        assertThat(seconds).as("still long enough to be worth pooling at all").isGreaterThan(5);
    }

    @Test
    public void anExplicitSettingIsLeftAlone() {
        System.setProperty(HttpClientTuning.KEEP_ALIVE_PROPERTY, "300");

        HttpClientTuning.apply();

        assertThat(System.getProperty(HttpClientTuning.KEEP_ALIVE_PROPERTY)).isEqualTo("300");
    }

    @Test
    public void applyingTwiceChangesNothing() {
        HttpClientTuning.apply();
        String after = System.getProperty(HttpClientTuning.KEEP_ALIVE_PROPERTY);

        HttpClientTuning.apply();

        assertThat(System.getProperty(HttpClientTuning.KEEP_ALIVE_PROPERTY)).isEqualTo(after);
    }

    @Test
    public void theJdkIsNotAlsoRetryingBehindTheRetryLoop() {
        HttpClientTuning.apply();

        // Two retry layers would multiply the configured budget unpredictably and would ignore
        // Retry-After on the inner one. AbstractLLMBackend owns retrying.
        assertThat(System.getProperty("jdk.httpclient.enableAllMethodRetry")).isNull();
    }
}
