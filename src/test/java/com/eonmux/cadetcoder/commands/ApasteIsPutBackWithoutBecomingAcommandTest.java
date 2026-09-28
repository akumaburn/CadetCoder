package com.eonmux.cadetcoder.commands;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Putting a paste back must not turn a question into a command.
 *
 * <h2>The defect</h2>
 *
 * <p>A dropped or pasted file is held as a marker and the marker stands for the file's absolute
 * path. The line that runs is the line with each marker put back, and the router reads a leading
 * slash as meaning "this is a command" -- so a marker at the start of a line wrote a slash nobody
 * typed. Dropping a screenshot at an empty prompt and asking a question about it, which is the
 * whole point of dropping one, was reported as:</p>
 *
 * <pre>
 *   &gt; [#1: image pasted-image-.png] transcribe this for me.
 *   Unknown command: tmp/pasted-image-.png
 *   Command [tmp/pasted-image-.png] failed with exit code 1
 * </pre>
 *
 * <p>The picture was attached to a turn that never happened. The slash that names a command is one
 * the person typed, so the decision is taken from the line as typed and the line with the markers
 * put back is what is handed on.</p>
 */
public class ApasteIsPutBackWithoutBecomingAcommandTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final ShellPastes pastes = new ShellPastes();

    private static final Set<String> COMMANDS = Set.of("read", "ls", "chat", "write");

    /** Routes a typed line the way the shell does, with its markers put back. */
    private InputRouter.Routed route(String typed) {
        return InputRouter.route(typed, pastes.expand(typed), COMMANDS,
                                 InputRouter.Mode.CONVERSATION);
    }

    private Path png(String name) throws IOException {
        byte[] bytes = new byte[40];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0,
                         bytes, 0, 8);
        Path file = folder.newFile(name).toPath();
        Files.write(file, bytes);
        return file;
    }

    @Test
    public void aquestionAboutAdroppedPictureGoesToTheModel() throws IOException {
        Path   file   = png("pasted-image-.png");
        String marker = pastes.insertionFor("'" + file + "' ");

        InputRouter.Routed routed = route(marker + " transcribe this for me.");

        assertThat(routed.isChat())
                .as("the person typed a question, not a command named after the file")
                .isTrue();
        assertThat(routed.getText())
                .isEqualTo(file.toAbsolutePath() + " transcribe this for me.");
    }

    /** The same line with nothing after the marker is still a message, not a command. */
    @Test
    public void adroppedFileOnItsOwnIsStillAmessage() throws IOException {
        Path file = png("shot.png");

        InputRouter.Routed routed = route(pastes.insertionFor("'" + file + "' "));

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getText()).isEqualTo(file.toAbsolutePath().toString());
    }

    /** A command the person did name keeps its name, and the path reaches it as an argument. */
    @Test
    public void acommandNamedBeforeAmarkerStillRunsWithThePathAsItsArgument() throws IOException {
        Path   file   = png("shot.png");
        String marker = pastes.insertionFor("'" + file + "' ");

        InputRouter.Routed routed = route("/read " + marker);

        assertThat(routed.isCommand()).isTrue();
        assertThat(routed.getName()).isEqualTo("read");
        assertThat(routed.getArgs()).containsExactly(file.toAbsolutePath().toString());
    }

    /** A line with no marker in it is routed exactly as it was before. */
    @Test
    public void alineWithNothingToPutBackIsUnchanged() {
        assertThat(route("/ls src").isCommand()).isTrue();
        assertThat(route("/ls src").getArgs()).containsExactly("src");
        assertThat(route("what does this do?").isChat()).isTrue();
        assertThat(route("read the design doc and summarise it").getShadowedCommand())
                .isEqualTo("read");
    }

    /** The escape still means a message that begins with a slash. */
    @Test
    public void theEscapeStillWorksBesideAmarker() throws IOException {
        Path   file   = png("shot.png");
        String marker = pastes.insertionFor("'" + file + "' ");

        InputRouter.Routed routed = route("//ls is a command " + marker);

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getText())
                .as("one slash is taken off, and the path is put back")
                .isEqualTo("/ls is a command " + file.toAbsolutePath());
    }

    /**
     * A text paste can begin with a slash too, and a block of output pasted at an empty prompt is
     * no more a command than a dropped file is.
     */
    @Test
    public void apastedBlockThatBeginsWithAslashIsNotAcommand() {
        String marker = pastes.keep("/usr/bin/env\nsecond line\nthird line");

        InputRouter.Routed routed = route(marker + " what is this?");

        assertThat(routed.isChat()).isTrue();
        assertThat(routed.getText()).startsWith("/usr/bin/env\nsecond line");
    }
}
