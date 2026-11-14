package com.eonmux.cadetcoder.build;

import java.time.Instant;

/**
 * The latest release a repository offers, as far as the update check can know it.
 *
 * @param tag         the release's tag, as {@code v1.0}
 * @param commit      the commit the release was cut from, as the API's {@code target_commitish}
 * @param publishedAt when the release was published
 * @param htmlUrl     the release's page on the repository, which the notice points at
 * @param jarUrl      where the release's jar is downloaded from
 * @param sha256Url   where the release's checksum is downloaded from, or {@code null} when the
 *                    release ships none
 */
public record Release(String tag, String commit, Instant publishedAt, String htmlUrl,
                      String jarUrl, String sha256Url) {

    /** @return whether the release carries the facts a comparison needs */
    public boolean canBeCompared() {
        return commit != null && !commit.isBlank() && publishedAt != null;
    }

    /** @return the commit shortened the way git names branches and releases, or {@code unknown} */
    public String commitShort() {
        if (commit == null || commit.isBlank()) {
            return "unknown";
        }
        String trimmed = commit.trim();
        return trimmed.substring(0, Math.min(7, trimmed.length()));
    }

    /**
     * Whether this release is newer than the given build.
     *
     * <h2>Why both the commit and the time have to agree</h2>
     *
     * <p>A different commit alone is not enough: the build on this machine may be newer than the
     * release -- built locally from a commit the release has not caught up with -- and talking that
     * machine into "updating" to an older release would be a downgrade. A newer published time
     * alone is not enough either: rebuilding the same commit produces a newer build of the same
     * code, and that is not an update either. Both together say what the question actually is:
     * the release was cut from work this build does not contain, after this build existed.</p>
     *
     * @param localCommit the commit of the build asking, full or abbreviated
     * @param localTime   when that build was made
     * @return whether installing this release would move the build forward
     */
    public boolean isNewerThan(String localCommit, Instant localTime) {
        if (localCommit == null || localTime == null || !canBeCompared()) {
            return false;
        }
        String mine = localCommit.trim();
        boolean differentWork = !commit.trim().equalsIgnoreCase(mine)
                && !commit.trim().toLowerCase().startsWith(mine.toLowerCase());
        boolean builtBeforeTheRelease = publishedAt.isAfter(localTime);
        return differentWork && builtBeforeTheRelease;
    }
}
