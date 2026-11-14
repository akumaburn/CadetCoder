package com.eonmux.cadetcoder.build;

import org.junit.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The facts a build is stamped with, and the rule that decides whether a release is an update.
 *
 * <p><b>The defect</b>: a jar could only say "1.0" for itself, so two jars of the same version
 * built months apart were indistinguishable, and there was nothing to hold a release against. The
 * comparison is therefore the whole feature: the release must carry work this build does not have
 * <em>and</em> have been published after this build existed. Either test alone says the wrong thing
 * -- a locally rebuilt release commit would "update" to itself, and a newer timestamp over an older
 * commit would downgrade work.</p>
 */
public class ABuildKnowsWhatItCameFromTest {

    private static final Instant BUILT     = Instant.parse("2026-11-14T07:00:00Z");
    private static final Instant PUBLISHED = Instant.parse("2026-11-14T08:00:00Z");

    private static Release release(String commit, Instant published) {
        return new Release("v1.1", commit, published,
                           "https://github.com/akumaburn/CadetCoder/releases/tag/v1.1",
                           "https://example.com/cadet-latest.jar", null);
    }

    /* ------------------------------------------------------------------ *
     * What a build says about itself                                      *
     * ------------------------------------------------------------------ */

    @Test
    public void aBuildNamesItsVersionCommitTimeAndOrigin() {
        BuildInfo build = new BuildInfo("1.0-SNAPSHOT",
                                        "7140268970f17360c15982614ea70ea6873137be",
                                        BUILT, "akumaburn/CadetCoder");

        assertThat(build.version()).isEqualTo("1.0-SNAPSHOT");
        assertThat(build.shortCommit()).isEqualTo("7140268");
        assertThat(build.time()).isEqualTo(BUILT);
        assertThat(build.repo()).isEqualTo("akumaburn/CadetCoder");
        assertThat(build.canBeChecked()).isTrue();
        assertThat(build.describe())
                .isEqualTo("CadetCoder 1.0-SNAPSHOT (build 7140268, 2026-11-14T07:00:00Z)");
    }

    @Test
    public void aBuildMadeOutsideGitSaysSoInsteadOfGuessing() {
        BuildInfo build = new BuildInfo(null, null, null, null);

        assertThat(build.commit()).isEqualTo("unknown");
        assertThat(build.shortCommit()).isEqualTo("unknown");
        assertThat(build.time()).isNull();
        assertThat(build.repo()).isNull();
        assertThat(build.canBeChecked()).isFalse();
        assertThat(build.describe()).contains("unknown");
    }

    /* ------------------------------------------------------------------ *
     * Whether a release is an update                                      *
     * ------------------------------------------------------------------ */

    @Test
    public void aReleaseCutFromLaterWorkAfterThisBuildIsAnUpdate() {
        assertThat(release("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", PUBLISHED)
                           .isNewerThan("7140268970f17360c15982614ea70ea6873137be", BUILT)).isTrue();
    }

    @Test
    public void theSameCommitIsNotAnUpdateWhateverItsTimestamp() {
        // A rebuild of the release commit is newer bytes of the same work.
        assertThat(release("7140268970f17360c15982614ea70ea6873137be",
                           PUBLISHED).isNewerThan("7140268970f17360c15982614ea70ea6873137be",
                                                  BUILT)).isFalse();
    }

    @Test
    public void theReleasesCommitShortenedToSevenIsStillTheSameWork() {
        // GitHub answers target_commitish as a full SHA; a local stamp may be abbreviated.
        assertThat(release("7140268970f17360c15982614ea70ea6873137be",
                           PUBLISHED).isNewerThan("7140268", BUILT)).isFalse();
    }

    @Test
    public void aReleasePublishedBeforeThisBuildWouldBeADowngrade() {
        Instant publishedEarlier = BUILT.minusSeconds(3600);
        assertThat(release("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                           publishedEarlier).isNewerThan(
                           "7140268970f17360c15982614ea70ea6873137be", BUILT)).isFalse();
    }

    @Test
    public void aReleaseThatDoesNotSayWhatItCameFromIsNeverAnUpdate() {
        assertThat(new Release("v1.1", null, PUBLISHED, null,
                               "https://example.com/cadet-latest.jar",
                               null).isNewerThan("7140268", BUILT)).isFalse();
        assertThat(release("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null)
                           .isNewerThan("7140268", BUILT)).isFalse();
    }

    @Test
    public void aBuildOfUnknownOriginIsNeverUpdated() {
        assertThat(release("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", PUBLISHED)
                           .isNewerThan("unknown", null)).isFalse();
    }
}
