package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.CommandApproval;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model may not change a file it has never looked at.
 *
 * <h2>The defect</h2>
 *
 * <p>An edit names the text to replace, and a model that has not read the file writes that text
 * from memory. The read was forty turns ago, or in another run, or never, so what it quotes is
 * close to what the file says without being equal to it: the match fails and the turn is spent on
 * an edit that changed nothing. The same guess against {@code write} does not fail at all -- it
 * succeeds, and replaces a file the model never saw with what it imagined was in it.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That an unread file is refused with a message naming the read to do first; that reading it, or
 * writing it, earns the right to change it; that a file which does not exist yet is not gated,
 * since creating one is not what the rule is about; and that a person at a terminal is not held to
 * any of it, because a fresh process has read nothing and the rule would refuse every one-shot
 * invocation there is.</p>
 */
public class AfileIsNotChangedFromMemoryTest {

    @Rule
    public TemporaryFolder folder = new ProjectFolder();

    private TestOutputCapture output;

    @Before
    public void startWithNothingRead() {
        ReadBeforeEdit.forgetEverything();
        // Approval is not the subject here. In auto mode it would be put to a model this test has
        // none of, and the edit would be declined for that reason instead of this one.
        System.setProperty(CommandApproval.PROPERTY, "manual");
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void forgetWhatWasRead() {
        output.stopCapture();
        ReadBeforeEdit.forgetEverything();
        System.clearProperty(CommandApproval.PROPERTY);
    }

    @Test
    public void anUnreadFileIsRefused() throws IOException {
        Path file = fileHolding("the original text");

        String reason = asAmodel(() -> ReadBeforeEdit.reasonNotToChange(file, "multiedit"));

        assertThat(reason).isNotNull();
        assertThat(reason).contains(file.getFileName().toString());
        assertThat(reason)
                .as("a refusal that does not say what to do next is repeated by the model")
                .contains("read " + file);
    }

    @Test
    public void afileThatWasReadMayBeChanged() throws IOException {
        Path file = fileHolding("the original text");

        ReadBeforeEdit.sawContents(file);

        assertThat(asAmodel(() -> ReadBeforeEdit.reasonNotToChange(file, "multiedit"))).isNull();
    }

    /**
     * A run that has just written a file knows what is in it as surely as one that has read it, and
     * refusing to edit a file the run itself created would be a rule about nothing.
     */
    @Test
    public void afileThisRunWroteCountsAsOneItHasRead() throws IOException {
        Path file = folder.getRoot().toPath().resolve("new.txt");
        assertThat(asAmodel(() -> ReadBeforeEdit.reasonNotToChange(file, "write")))
                .as("there is nothing to have read yet")
                .isNull();

        Files.writeString(file, "written by this run");
        ReadBeforeEdit.sawContents(file);

        assertThat(asAmodel(() -> ReadBeforeEdit.reasonNotToChange(file, "multiedit"))).isNull();
    }

    @Test
    public void afileThatDoesNotExistIsNotGated() {
        Path missing = folder.getRoot().toPath().resolve("not-there-yet.txt");

        assertThat(asAmodel(() -> ReadBeforeEdit.reasonNotToChange(missing, "write"))).isNull();
    }

    /**
     * A person typing the command has the file in front of them, and a fresh process has read
     * nothing, so holding one to this rule would refuse every one-shot invocation and every script.
     */
    @Test
    public void apersonAtAterminalIsNotHeldToIt() throws IOException {
        Path file = fileHolding("the original text");

        assertThat(ReadBeforeEdit.reasonNotToChange(file, "multiedit")).isNull();
    }

    @Test
    public void twoSpellingsOfOnePathAreOneFile() throws IOException {
        Path file = fileHolding("the original text");

        ReadBeforeEdit.sawContents(file);
        Path roundabout = file.getParent().resolve(".").resolve(file.getFileName());

        assertThat(asAmodel(() -> ReadBeforeEdit.reasonNotToChange(roundabout, "multiedit")))
                .isNull();
    }

    /** The rule reaches the command, not only the class that states it. */
    @Test
    public void multieditRefusesAfileItHasNotRead() throws IOException {
        Path file = fileHolding("alpha\nbeta\ngamma\n");

        int exitCode = asAmodel(() -> new MultiEditCommand().execute(new String[] {
                file.toString(),
                "EDIT_START\nOLD: beta\nNEW: delta\nREPLACE_ALL: false\nEDIT_END"}));

        assertThat(exitCode).isNotZero();
        assertThat(Files.readString(file))
                .as("nothing was changed")
                .isEqualTo("alpha\nbeta\ngamma\n");
        assertThat(output.getAllOutput()).contains("has not been read");
    }

    @Test
    public void multieditAppliesToAfileItHasRead() throws IOException {
        Path file = fileHolding("alpha\nbeta\ngamma\n");
        ReadBeforeEdit.sawContents(file);

        int exitCode = asAmodel(() -> new MultiEditCommand().execute(new String[] {
                file.toString(),
                "EDIT_START\nOLD: beta\nNEW: delta\nREPLACE_ALL: false\nEDIT_END"}));

        assertThat(exitCode).isZero();
        assertThat(Files.readString(file)).isEqualTo("alpha\ndelta\ngamma\n");
    }

    /** Overwriting a file nobody read is the one of these that succeeds rather than failing. */
    @Test
    public void writeRefusesToOverwriteAfileItHasNotRead() throws IOException {
        Path file = fileHolding("the contents nobody looked at");

        int exitCode = asAmodel(() -> new WriteCommand().execute(new String[] {
                file.toString(), "whatever the model imagined", "-f"}));

        assertThat(exitCode).isNotZero();
        assertThat(Files.readString(file)).isEqualTo("the contents nobody looked at");
    }

    @Test
    public void writeStillCreatesAfileThatIsNotThere() {
        Path file = folder.getRoot().toPath().resolve("brand-new.txt");

        int exitCode = asAmodel(() -> new WriteCommand().execute(new String[] {
                file.toString(), "the first thing written here"}));

        assertThat(exitCode).isZero();
        assertThat(file).exists();
    }

    @Test
    public void patchRefusesAfileItHasNotRead() throws IOException {
        Path file = fileHolding("alpha\nbeta\ngamma\n");
        String diff = "--- a/" + file + "\n+++ b/" + file + "\n@@ -1,3 +1,3 @@\n"
                      + " alpha\n-beta\n+delta\n gamma\n";

        int exitCode = asAmodel(() -> new PatchCommand().execute(new String[] {diff}));

        assertThat(exitCode).isNotZero();
        assertThat(Files.readString(file)).isEqualTo("alpha\nbeta\ngamma\n");
        assertThat(output.getAllOutput())
                .as("refused for the right reason, not because the diff was unreadable")
                .contains("has not been read");
    }

    /** A hunk that adds a file has nothing to have been read, so it is not gated. */
    @Test
    public void patchStillAddsAfileThatIsNotThere() throws IOException {
        Path file = folder.getRoot().toPath().resolve("added.txt");
        String diff = "--- /dev/null\n+++ b/" + file + "\n@@ -0,0 +1,1 @@\n+the first line\n";

        int exitCode = asAmodel(() -> new PatchCommand().execute(new String[] {diff}));

        assertThat(exitCode).isZero();
        assertThat(file).exists();
    }

    /** Reading a file is what earns the right to change it, so the read command has to say so. */
    @Test
    public void readingAfileEarnsTheRightToChangeIt() throws IOException {
        Path file = fileHolding("alpha\nbeta\n");

        assertThat(new ReadCommand().execute(new String[] {file.toString()})).isZero();

        assertThat(asAmodel(() -> ReadBeforeEdit.reasonNotToChange(file, "multiedit"))).isNull();
    }

    /** @return a file in the temporary folder holding this text */
    private Path fileHolding(String text) throws IOException {
        Path file = folder.newFile("subject.txt").toPath();
        Files.writeString(file, text);
        return file;
    }

    /**
     * Runs something as though a model had asked for it.
     *
     * @param work what to run
     * @param <T>  what it produces
     * @return what it produced
     */
    private static <T> T asAmodel(java.util.function.Supplier<T> work) {
        Object[] held = new Object[1];
        ModelDispatch.run("read", () -> {
            held[0] = work.get();
            return 0;
        });
        @SuppressWarnings ("unchecked")
        T produced = (T) held[0];
        return produced;
    }

    /** The same, for work that answers with an exit code. */
    private static int asAmodel(java.util.function.IntSupplier work) {
        return ModelDispatch.run("read", work);
    }
}
