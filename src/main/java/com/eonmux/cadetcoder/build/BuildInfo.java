package com.eonmux.cadetcoder.build;

import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/**
 * What this build is, and when and where it was made.
 *
 * <h2>Where the answers come from</h2>
 *
 * <p>{@code build.properties}, written by the build itself into the jar: the commit the build came
 * from, the time it was built, and the GitHub repository that build's checkout calls its origin.
 * A jar built outside a git checkout carries {@code unknown} for what it cannot answer, and every
 * reader here treats that as the honest answer it is: an update check against a build of unknown
 * commit reports that it cannot tell, rather than guessing.</p>
 *
 * <p>Read once. The answers cannot change while the process runs, and the file sits behind every
 * classpath lookup a command makes otherwise.</p>
 */
public final class BuildInfo {

    private static final BuildInfo CURRENT = read();

    private final String version;
    private final String commit;
    private final Instant time;
    private final String repo;

    /** Visible for tests: the real one is read from the classpath, once. */
    BuildInfo(String version, String commit, Instant time, String repo) {
        this.version = blankToUnknown(version);
        this.commit  = blankToUnknown(commit);
        this.time    = time;
        this.repo    = blankToNull(repo);
    }

    /** @return the build this process is running from */
    public static BuildInfo current() {
        return CURRENT;
    }

    /** @return the program version the build declares, or {@code unknown} */
    public String version() {
        return version;
    }

    /**
     * @return the full commit the build came from, or {@code unknown} when it was built outside a
     *         git checkout
     */
    public String commit() {
        return commit;
    }

    /** @return the commit shortened the way git names branches and releases, or {@code unknown} */
    public String shortCommit() {
        return "unknown".equals(commit) ? commit : commit.substring(0, Math.min(7, commit.length()));
    }

    /** @return when the build was made, or {@code null} when the build does not say */
    public Instant time() {
        return time;
    }

    /**
     * @return the GitHub repository this build's checkout calls its origin, as
     *         {@code owner/repository}, or {@code null} when the origin is not one
     */
    public String repo() {
        return repo;
    }

    /** @return whether this build knows enough for an update check to compare it against anything */
    public boolean canBeChecked() {
        return !"unknown".equals(commit) && time != null && repo != null;
    }

    /**
     * @return one line naming this build, for {@code --version} and the update check's own report
     */
    public String describe() {
        String stamp = time == null ? "unknown time" : time.toString();
        return "CadetCoder " + version + " (build " + shortCommit() + ", " + stamp + ")";
    }

    private static BuildInfo read() {
        Properties props = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception e) {
            // Absent or unreadable is answered the same way as never written: a build of unknown
            // origin. Nothing here is allowed to cost the program its start-up.
        }
        return new BuildInfo(props.getProperty("build.version"),
                             props.getProperty("build.commit"),
                             instant(props.getProperty("build.time")),
                             props.getProperty("build.repo"));
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException notAClockReading) {
            return null;
        }
    }

    private static String blankToUnknown(String text) {
        return text == null || text.isBlank() ? "unknown" : text.trim();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
