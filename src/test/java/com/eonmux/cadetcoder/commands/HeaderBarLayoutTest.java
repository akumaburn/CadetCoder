package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the header bar's right-hand group says, idle and while a request is in flight. */
public class HeaderBarLayoutTest {

    private static final String SEPARATOR = "  ·  ";
    private static final String CONTEXT   = "gpt-5  ·  master";
    private static final String LIVE      = "⠋ Reading pom.xml  ·  4.2s";

    @Test
    public void idleTheBarCarriesTheModelAndBranchAlone() {
        assertThat(ShellHeaderBar.composeRight(CONTEXT, "", SEPARATOR)).isEqualTo(CONTEXT);
    }

    @Test
    public void whileWorkingTheIndicatorFollowsTheBranchRatherThanSittingAcrossTheBar() {
        // Split across the two ends, following a run meant reading both corners of one line at once.
        String right = ShellHeaderBar.composeRight(CONTEXT, LIVE, SEPARATOR);

        assertThat(right).startsWith(CONTEXT).endsWith(LIVE);
        assertThat(right.indexOf("master")).isLessThan(right.indexOf("4.2s"));
    }

    @Test
    public void withNoContextTheIndicatorDoesNotLeadWithADanglingSeparator() {
        assertThat(ShellHeaderBar.composeRight("", LIVE, SEPARATOR)).isEqualTo(LIVE);
    }

    @Test
    public void missingPiecesDoNotProduceTheStringNull() {
        assertThat(ShellHeaderBar.composeRight(null, null, SEPARATOR)).isEmpty();
        assertThat(ShellHeaderBar.composeRight(null, LIVE, SEPARATOR)).isEqualTo(LIVE);
    }

    @Test
    public void theWorkingDirectoryIsShownHomeRelative() {
        assertThat(ShellWidgets.shortPath("/home/dev/Projects/CadetCoder", "/home/dev", 80))
                .isEqualTo("~/Projects/CadetCoder");
        assertThat(ShellWidgets.shortPath("/opt/build/thing", "/home/dev", 80))
                .isEqualTo("/opt/build/thing");
    }

    @Test
    public void anOverlongPathIsElidedFromTheLeftSoTheProjectStaysReadable() {
        // Truncating the other way leaves every project under one source root looking identical,
        // which is the opposite of what a path in a title bar is for.
        String elided = ShellWidgets.shortPath(
                "/home/dev/very/deeply/nested/source/root/CadetCoder", "/home/dev", 20);

        assertThat(elided).hasSize(20);
        assertThat(elided).startsWith("\u2026");
        assertThat(elided).endsWith("CadetCoder");
    }

    @Test
    public void aMissingWorkingDirectoryYieldsNothingRatherThanTheWordNull() {
        assertThat(ShellWidgets.shortPath(null, "/home/dev", 20)).isEmpty();
        assertThat(ShellWidgets.shortPath("", "/home/dev", 20)).isEmpty();
        assertThat(ShellWidgets.shortPath("/a/b", null, 20)).isEqualTo("/a/b");
    }
}
