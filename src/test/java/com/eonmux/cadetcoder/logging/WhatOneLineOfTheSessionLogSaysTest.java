package com.eonmux.cadetcoder.logging;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one line each entry occupies in the readable session log.
 *
 * <h2>What is locked here</h2>
 *
 * <p>The rendering was a branch per kind of entry, each differing from its neighbours by a word,
 * and nothing checked what any of them produced. It is now one sentence applied to a table of
 * kinds, which is only an improvement if the lines it produces are the lines that were produced
 * before: the log is read by people who know its shape, and by anything that greps it.</p>
 *
 * <p>Locked here: the name each kind is written under, whether the line is about the message or
 * about where it came from, that a recorded value appears only when it was recorded, that a
 * measurement carries its unit, that a long line of command output is shown at both ends, and that
 * a failure is named on the line whatever kind of entry carried it.</p>
 */
public class WhatOneLineOfTheSessionLogSaysTest {

    private static Map<String, String> recorded(String... keysAndValues) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int at = 0; at < keysAndValues.length; at += 2) {
            metadata.put(keysAndValues[at], keysAndValues[at + 1]);
        }
        return metadata;
    }

    private static String lineFor(LogEntryType type, String source, String message,
                                  Map<String, String> metadata, Throwable failure) {
        return SessionLogLine.from(
                SessionLogEntry.of(1, type, source, message, metadata, failure));
    }

    private static String lineFor(LogEntryType type, String message) {
        return lineFor(type, "Test", message, null, null);
    }

    @Test
    public void theKindOfEntryComesFirst() {
        assertThat(lineFor(LogEntryType.USER_INPUT, "index")).isEqualTo("USER_INPUT: index");
    }

    @Test
    public void someKindsAreWrittenUnderAShorterName() {
        assertThat(lineFor(LogEntryType.SESSION_EVENT, "Session started"))
                .isEqualTo("SESSION: Session started");
        assertThat(lineFor(LogEntryType.FILE_OPERATION, "write"))
                .startsWith("FILE_OP: ");
        assertThat(lineFor(LogEntryType.COMMAND_OUTPUT, "done"))
                .isEqualTo("OUTPUT: done");
    }

    @Test
    public void whatWasRecordedAlongsideTheEntryFollowsIt() {
        String line = lineFor(LogEntryType.COMMAND_START, "Test", "edit",
                              recorded("args", "--dry-run"), null);

        assertThat(line).isEqualTo("COMMAND_START: edit | Args: --dry-run");
    }

    @Test
    public void whatWasNotRecordedIsNotShown() {
        String line = lineFor(LogEntryType.COMMAND_END, "Test", "edit",
                              recorded("exitCode", "0"), null);

        assertThat(line)
                .isEqualTo("COMMAND_END: edit | Exit Code: 0")
                .doesNotContain("Duration");
    }

    @Test
    public void aMeasurementCarriesWhatItIsMeasuredIn() {
        assertThat(lineFor(LogEntryType.PERFORMANCE, "Test", "index",
                           recorded("duration", "1200"), null))
                .isEqualTo("PERFORMANCE: index | Duration: 1200ms");

        assertThat(lineFor(LogEntryType.LLM_REQUEST, "Test", "request to /chat",
                           recorded("payloadSize", "4096"), null))
                .isEqualTo("LLM_REQUEST: request to /chat | Payload Size: 4096 bytes");
    }

    @Test
    public void everythingRecordedIsShownInTheOrderItIsDeclared() {
        String line = lineFor(LogEntryType.LLM_RESPONSE, "Test", "response",
                              recorded("responseSize", "20", "duration", "5", "model", "a-model"),
                              null);

        assertThat(line)
                .isEqualTo("LLM_RESPONSE: response | Model: a-model | Duration: 5ms "
                           + "| Response Size: 20 bytes");
    }

    @Test
    public void anAiRequestIsWrittenAboutWhereItCameFromRatherThanWhatItSaid() {
        String line = lineFor(LogEntryType.AI_REQUEST, "AIManager", "AI request sent: hello",
                              recorded("model", "a-model"), null);

        assertThat(line).isEqualTo("AI_REQUEST: AIManager | Model: a-model");
    }

    @Test
    public void aLineOfOutputShortEnoughToReadIsLeftAlone() {
        String output = "x".repeat(200);

        assertThat(lineFor(LogEntryType.COMMAND_OUTPUT, output)).isEqualTo("OUTPUT: " + output);
    }

    @Test
    public void aVeryLongLineOfOutputIsShownAtBothEnds() {
        String output = "s".repeat(100) + "m".repeat(60) + "e".repeat(100);

        String line = lineFor(LogEntryType.COMMAND_OUTPUT, output);

        assertThat(line).isEqualTo("OUTPUT: " + "s".repeat(100) + " ... [60 chars] ... "
                                   + "e".repeat(100));
    }

    @Test
    public void aFailureIsNamedOnTheLine() {
        String line = lineFor(LogEntryType.ERROR, "Test", "could not read the file",
                              null, new IllegalStateException("no such file"));

        assertThat(line)
                .isEqualTo("ERROR: could not read the file "
                           + "| Exception: IllegalStateException: no such file");
    }

    @Test
    public void aFailureOnAnEntryThatIsNotAnErrorIsNamedToo() {
        String line = lineFor(LogEntryType.FILE_OPERATION, "Test", "write",
                              null, new IllegalStateException("read-only"));

        assertThat(line)
                .as("the JSON record kept it, so a readable line without it made the two disagree")
                .contains("| Exception: IllegalStateException: read-only");
    }

    @Test
    public void everyKindOfEntryIsWrittenUnderANameOfItsOwn() {
        for (LogEntryType type : LogEntryType.values()) {
            assertThat(lineFor(type, "something happened"))
                    .as("%s", type)
                    .matches("[A-Z_]+: .+");
        }
    }
}
