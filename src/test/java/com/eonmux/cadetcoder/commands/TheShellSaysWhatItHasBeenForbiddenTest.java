package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.ui.Glyphs;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run that has been restricted says so before the first refusal, not after it.
 *
 * <p><b>The defect</b>: {@code --read-only}, {@code security.sandboxMode} and
 * {@code security.allowOutsideProject=false} each change what the tool will agree to do, and the
 * shell showed no sign of any of them. A session started read-only looked exactly like an ordinary
 * one until a write failed -- and since the flag can come from the config file rather than the
 * command line, the user need not have set it in this run to be living under it.</p>
 */
public class TheShellSaysWhatItHasBeenForbiddenTest {

    private static Configuration.SecurityConfig unrestricted() {
        Configuration.SecurityConfig security = new Configuration.SecurityConfig();
        security.setReadOnlyMode(false);
        security.setSandboxMode(false);
        security.setAllowOutsideProject(true);
        return security;
    }

    private static String bannerUnder(Configuration.SecurityConfig security, Glyphs glyphs) {
        return String.join("\n",
                ShellWelcome.banner("v1.0", "/home/me/work", "abc123", glyphs, security));
    }

    @Test
    public void anUnrestrictedRunSaysNothingAboutRestrictions() {
        assertThat(bannerUnder(unrestricted(), Glyphs.ASCII))
                .as("a line that is always there is a line nobody reads")
                .doesNotContain("Restricted");
    }

    @Test
    public void areadOnlyRunSaysSoBeforeTheFirstWriteIsRefused() {
        Configuration.SecurityConfig security = unrestricted();
        security.setReadOnlyMode(true);

        String text = bannerUnder(security, Glyphs.ASCII);

        assertThat(text).contains("Restricted");
        assertThat(text).contains("read-only");
    }

    @Test
    public void sandboxModeIsNamedToo() {
        Configuration.SecurityConfig security = unrestricted();
        security.setSandboxMode(true);

        assertThat(bannerUnder(security, Glyphs.ASCII)).contains("sandbox");
    }

    @Test
    public void theShippedBoundaryOnWhereFilesMayBeTouchedIsNotALine() {
        // Files are confined to the project by default, so naming it would put the line in front
        // of every session.
        assertThat(bannerUnder(new Configuration.SecurityConfig(), Glyphs.ASCII))
                .doesNotContain("Restricted")
                .doesNotContain("this directory");
    }

    @Test
    public void severalAtOnceAreAllNamedOnOneLine() {
        Configuration.SecurityConfig security = unrestricted();
        security.setReadOnlyMode(true);
        security.setSandboxMode(true);
        security.setAllowOutsideProject(false);

        String text = bannerUnder(security, Glyphs.ASCII);

        assertThat(text).contains("read-only").contains("sandbox");
        assertThat(text.lines().filter(line -> line.contains("Restricted")).count())
                .as("one line, however many of them are on")
                .isEqualTo(1);
    }

    @Test
    public void amissingSecurityConfigIsNotArestrictionNorAcrash() {
        assertThat(String.join("\n",
                ShellWelcome.banner("v1.0", "/tmp", "abc123", Glyphs.ASCII, null)))
                .doesNotContain("Restricted");
    }

    @Test
    public void theRestrictedLineIsDrawableOnAnAsciiTerminal() {
        Configuration.SecurityConfig security = unrestricted();
        security.setReadOnlyMode(true);
        String text = bannerUnder(security, Glyphs.ASCII);

        assertThat(new String(text.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII))
                .isEqualTo(text);
    }
}
