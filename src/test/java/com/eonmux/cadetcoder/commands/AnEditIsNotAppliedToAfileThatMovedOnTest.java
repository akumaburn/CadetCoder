package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An edit worked out against one text is not written over a different one.
 *
 * <p><b>The defect</b>: multiedit read the file, worked out the whole new contents, then asked for
 * confirmation -- and wrote the result whenever the answer came back. Anything saved to that file
 * while the question stood was replaced without a word, because the text being written was computed
 * before it existed.</p>
 */
class AnEditIsNotAppliedToAfileThatMovedOnTest {

    @TempDir
    Path directory;

    @Test
    void afileStillHoldingWhatWasReadMayBeWritten() throws IOException {
        Path file = Files.writeString(directory.resolve("subject.txt"), "as it was\n");

        assertThat(UnchangedSince.reasonNotToWrite(file, "as it was\n")).isNull();
    }

    @Test
    void afileWrittenToWhileTheQuestionStoodIsRefused() throws IOException {
        Path file = Files.writeString(directory.resolve("subject.txt"), "as it was\n");

        Files.writeString(file, "somebody else's work\n");

        assertThat(UnchangedSince.reasonNotToWrite(file, "as it was\n"))
                .contains("changed on disk")
                .contains("Nothing was written");
    }

    @Test
    void afileThatCanNoLongerBeReadIsRefusedRatherThanOverwritten() throws IOException {
        Path file = directory.resolve("subject.txt");

        assertThat(UnchangedSince.reasonNotToWrite(file, "as it was\n"))
                .as("it was readable when the edit was worked out; now it is not")
                .contains("could not be read again");
    }

    @Test
    void nothingToCompareAgainstIsNothingToRefuse() {
        assertThat(UnchangedSince.reasonNotToWrite(null, "text")).isNull();
        assertThat(UnchangedSince.reasonNotToWrite(directory.resolve("x"), null)).isNull();
    }
}
