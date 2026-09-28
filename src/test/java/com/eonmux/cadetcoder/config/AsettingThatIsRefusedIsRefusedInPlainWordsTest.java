package com.eonmux.cadetcoder.config;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A refused setting is told what is wrong with it once, in a sentence.
 *
 * <p><b>The defect</b>: {@code config} wrapped every refusal as "Invalid value for '&lt;key&gt;':"
 * and the refusals are already whole sentences, so a retired setting came back as "Invalid value
 * for 'performance.cacheSize': performance.cacheSize no longer exists: it is gone: there was no
 * cache to size." -- the key three times, three colons, and a category ("invalid value") that was
 * wrong, since the value is beside the point when the setting is gone. A misspelt property read the
 * same way.</p>
 */
public class AsettingThatIsRefusedIsRefusedInPlainWordsTest {

    private static Configuration fresh() {
        return new Configuration();
    }

    @Test
    public void aretiredSettingSaysWhatBecameOfItWithoutRepeatingItself() {
        assertThatThrownBy(() -> ConfigOverrides.apply(fresh(), "performance.cacheSize", "200"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("performance.cacheSize is no longer a setting")
                .hasMessageContaining("there was no cache to size")
                .hasMessageNotContaining("it is gone");
    }

    @Test
    public void everyRetirementReadsAsAsentenceWhicheverWayItIsReported() {
        for (String key : new String[]{"security.apiKeyEncryption", "performance.cacheSize",
                                       "performance.timeoutSeconds", "ui.outputFormat",
                                       "ai.uberModeChallenges", "ai.enableAdvancedFeatures"}) {
            String became = RetiredSettings.whatBecameOf(key);
            org.assertj.core.api.Assertions.assertThat(became)
                    .as(key + " is named in RetiredSettings")
                    .isNotNull();
            org.assertj.core.api.Assertions.assertThat(became)
                    .as(became + " is completed by both of its callers' leads, so it starts "
                        + "lower-case and carries no lead of its own")
                    .doesNotStartWith("It ")
                    .doesNotStartWith("it is gone");
        }
    }

    /**
     * A setting this tool wrote into the user's own file and later removed is not a typo, and
     * telling them it is invites them to correct a spelling that was never wrong.
     */
    @Test
    public void asettingThisToolUsedToWriteIsNotReportedAsAmisspelling() {
        assertThatThrownBy(
                () -> ConfigOverrides.apply(fresh(), "ai.uberModeChallenges", "2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ai.uberModeChallenges is no longer a setting")
                .hasMessageNotContaining("Unknown property");
    }

    @Test
    public void amisspeltPropertyIsNamedOnceAndTheRealOnesListed() {
        assertThatThrownBy(() -> ConfigOverrides.apply(fresh(), "ai.mdel", "gpt-4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown property ai.mdel")
                .hasMessageContaining("Valid ai properties");
    }
}
