package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.Glyphs;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The banner: the first and often only instructions anyone reads.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>It was written straight into the transcript from inside the shell's constructor, and that
 * constructor takes over the process's output routing. So the one screen that tells a new user how
 * to run a command at all could not be asked a question without starting a terminal UI, and nothing
 * checked that it still named the keys and commands the README says it does.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the banner says how to reach help, how to run a command as opposed to talking to the
 * model, and how to connect a provider -- the three things a first run cannot proceed without; that
 * it names the working directory and the session, because those decide what every later command
 * acts on; and that on a terminal without Unicode it contains no character that terminal cannot
 * draw, which is the entire purpose of having two glyph sets.</p>
 */
public class WhatTheShellShowsBeforeAnybodyTypesTest {

    private static String banner(Glyphs glyphs) {
        return String.join("\n", ShellWelcome.banner("v1.0", "/home/me/work", "abc123", glyphs, null));
    }

    @Test
    public void itSaysHowToReachTheHelp() {
        assertThat(banner(Glyphs.ASCII)).contains("'/help'");
    }

    @Test
    public void itSaysHowToRunACommandRatherThanTalkToTheModel() {
        // The distinction the whole interface rests on, and the one thing a new user cannot guess.
        assertThat(banner(Glyphs.ASCII)).contains("Start with '/' to run a command");
    }

    @Test
    public void itSaysHowToConnectAProvider() {
        // Without one, every message to the model fails; this is the only place that says so before
        // the first failure.
        assertThat(banner(Glyphs.ASCII)).contains("'/login'");
    }

    @Test
    public void itNamesTheWorkingDirectoryAndTheSession() {
        String text = banner(Glyphs.ASCII);

        assertThat(text).contains("/home/me/work");
        assertThat(text).contains("abc123");
    }

    @Test
    public void itNamesTheVersionItIs() {
        assertThat(banner(Glyphs.ASCII)).contains("CadetCoder v1.0");
    }

    @Test
    public void aSessionThatHasNoNameIsStillDescribed() {
        // The shell resolves the absent id before it gets here; what must not appear is the word
        // "null" on the line that tells someone which session their work is going into.
        String text = String.join("\n",
                ShellWelcome.banner("v1.0", "/tmp", "new session", Glyphs.ASCII, null));

        assertThat(text).contains("Session: new session");
        assertThat(text).doesNotContain("null");
    }

    @Test
    public void onAnAsciiTerminalEveryCharacterIsAscii() {
        String text = banner(Glyphs.ASCII);

        assertThat(new String(text.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII))
                .as("the ASCII glyph set exists so that terminals without Unicode can draw this")
                .isEqualTo(text);
    }

    @Test
    public void theUnicodeBannerSaysTheSameThingsInNicerMarks() {
        String unicode = banner(Glyphs.UNICODE);

        assertThat(unicode).contains("'/help'");
        assertThat(unicode).contains("'/login'");
        assertThat(unicode).contains("/home/me/work");
    }

    @Test
    public void itNamesTheKeysThatAreNotOtherwiseDiscoverable() {
        // F1 opens the only list of the rest of them, and Tab is the only way to watch a worker
        // while it runs; neither appears anywhere else on screen.
        String text = banner(Glyphs.ASCII);

        assertThat(text).contains("F1");
        assertThat(text).contains("Tab");
        assertThat(text).contains("Ctrl+Q");
    }

    @Test
    public void itIsShortEnoughToLeaveTheFirstPromptOnScreen() {
        // Whatever else it says, it has to fit above the input line on an ordinary terminal, or the
        // instructions it carries have scrolled away by the time they could be followed.
        List<String> lines = ShellWelcome.banner("v1.0", "/home/me/work", "abc123", Glyphs.ASCII, null);

        assertThat(lines.size()).isLessThanOrEqualTo(20);
    }
}
