package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.security.WritePathPolicy;
import org.junit.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code edit}'s first argument is a REQUEST, not a path.
 *
 * <p>{@code EditCommand} joins everything it is handed into one sentence ({@code String.join(" ",
 * requestParts)}) and asks the model for SEARCH/REPLACE blocks; its usage line is {@code edit
 * "<edit request>"}. It never opens argv[0]. Yet the dispatcher listed {@code edit} at path index 0,
 * so every edit request was put through the same gate that broke grep -- and that gate rejects any
 * string containing {@code ".."}, which an ordinary instruction ("raise the retry ceiling from 3..5",
 * "drop the leading ../ from the include") routinely contains. The edit was refused with "Access to
 * file path not allowed" before {@code EditCommand} ever ran.</p>
 *
 * <p>Nothing is lost by removing it: the files an edit actually writes are policed by
 * {@link WritePathPolicy}, which {@code EditCommand.validatePath} consults for every one of them.</p>
 */
public class EditRequestIsNotAPathTest {

    private static int pathArgumentIndex(String command) {
        return PathArgument.indexFor(command, new ChatCommand());
    }

    @Test
    public void anEditRequestIsNotRunThroughThePathGate() {
        assertThat(pathArgumentIndex("edit"))
                .as("edit's argv is prose; validating it as a path refuses ordinary instructions")
                .isEqualTo(-1);
    }

    /** Why it matters: the gate's first question already says no to an ordinary sentence. */
    @Test
    public void theGateWouldRefuseAnOrdinaryEditRequest() {
        SecurityValidator validator = new SecurityValidator();

        assertThat(validator.isFileAccessAllowed("raise the retry ceiling from 3..5"))
                .as("the traversal pattern matches any '..', so this instruction was refused outright")
                .isFalse();
        assertThat(validator.isFileAccessAllowed("drop the leading ../ from the include path"))
                .isFalse();
    }

    /** And what still protects the files an edit writes. */
    @Test
    public void theFilesAnEditWritesAreStillPoliced() {
        assertThat(WritePathPolicy.decide(Paths.get("/etc/passwd")).isAllowed())
                .as("EditCommand.validatePath consults this for every file it writes")
                .isFalse();
        assertThat(WritePathPolicy.decide(Paths.get("../outside-the-project.txt")).isAllowed())
                .isFalse();
    }

    /** The commands whose first argument genuinely is a path must keep their gate. */
    @Test
    public void commandsWhoseFirstArgumentReallyIsAPathKeepTheirGate() {
        assertThat(pathArgumentIndex("read")).isEqualTo(0);
        assertThat(pathArgumentIndex("write")).isEqualTo(0);
        assertThat(pathArgumentIndex("ls")).isEqualTo(0);
        // A structured edit that names the text to replace is dispatched as multiedit, whose argv[0]
        // IS the file. That is the path check the edit arm keeps.
        assertThat(pathArgumentIndex("multiedit")).isEqualTo(0);
        assertThat(pathArgumentIndex("suggest")).isEqualTo(1);
    }

    /** Removing a gate must not remove the declaration: the map stays exhaustive. */
    @Test
    public void everyRegisteredCommandStillDeclaresWhichArgumentIsAPath() {
        assertThat(PathArgument.mappedCommands())
                .as("a command missing here is silently left unchecked")
                .containsAll(new CommandRegistry().getCommands().keySet())
                .as("edit must still be declared, as having no path argument")
                .contains("edit");
    }
}
