package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ARGS -> parameters -> CLI-argument round trip of {@link ActionBlockParser} for the whole
 * advertised command catalog.
 *
 * <p>{@link ParsedActionLockstepTest} already pins the second half (parameters -> CLI arguments). The
 * first half was broken for most commands: anything without an explicit arm in the positional mapping
 * fell into the numbered-parameter default ({@code arg0}, {@code arg1}, ...), which the dispatcher
 * does not read - so an {@code ls src -a} / {@code suggest tests X.java} / {@code todowrite add "..."}
 * action reached its command with an EMPTY argument list. These tests keep both halves in lockstep.</p>
 */
public class ActionBlockParserArgumentTest {

    private final ActionBlockParser parser = new ActionBlockParser();

    private String[] argsOf(ChatCommand.AIAction action) throws Exception {
        Field field = ChatCommand.AIAction.class.getDeclaredField("arguments");
        field.setAccessible(true);
        return (String[]) field.get(action);
    }

    /** Parses a single-line ACTION block and returns the parsed action. */
    private ParsedAction parse(String command, String argsLine) {
        String block = "ACTION_START\n"
                + "COMMAND: " + command + "\n"
                + "ARGS: " + argsLine + "\n"
                + "REASON: because the task needs it\n"
                + "ACTION_END";
        ParsedResponse response = parser.parse(block, new ParsingContext.Builder("do the work").build());
        assertThat(response.getActions()).hasSize(1);
        return response.getActions().get(0);
    }

    /** Parses a single-line ACTION block and returns the dispatched CLI arguments. */
    private List<String> argv(String command, String argsLine) throws Exception {
        return Arrays.asList(argsOf(parse(command, argsLine).toLegacyAction()));
    }

    @Test
    public void ls_mapsPathAndFlags() throws Exception {
        assertThat(argv("ls", "src -a")).containsExactly("src", "-a");
        assertThat(argv("ls", "src -R --max-depth=2"))
                .containsExactly("src", "-R", "--max-depth", "2");
        assertThat(argv("ls", "src --max-depth 2"))
                .containsExactly("src", "--max-depth", "2");
    }

    @Test
    public void suggest_mapsTypeThenPath() throws Exception {
        assertThat(argv("suggest", "tests src/Main.java")).containsExactly("tests", "src/Main.java");
    }

    @Test
    public void todowrite_mapsSubcommandContentAndFlags() throws Exception {
        assertThat(argv("todowrite", "add \"write unit tests\" -p high"))
                .containsExactly("add", "write unit tests", "-p", "high");
        assertThat(argv("todowrite", "update \"done\" -i 3 -s completed"))
                .containsExactly("update", "done", "-i", "3", "-s", "completed");
    }

    @Test
    public void commit_mapsFlagsAndMessage() throws Exception {
        assertThat(argv("commit", "-a \"fix: correct the timeout\""))
                .containsExactly("-a", "fix: correct the timeout");
        // An unquoted multi-word message is one message, not several dropped arguments.
        assertThat(argv("commit", "fix the timeout")).containsExactly("fix the timeout");
    }

    @Test
    public void webfetch_mapsUrlThenPrompt() throws Exception {
        assertThat(argv("webfetch", "https://example.com \"what does it say\""))
                .containsExactly("https://example.com", "what does it say");
        assertThat(argv("webfetch", "https://example.com -t 30"))
                .containsExactly("https://example.com", "-t", "30");
    }

    @Test
    public void websearch_mapsQueryAndResultCount() throws Exception {
        assertThat(argv("websearch", "\"java virtual threads\" -n 5"))
                .containsExactly("java virtual threads", "-n", "5");
    }

    @Test
    public void notebookread_mapsPathAndCell() throws Exception {
        assertThat(argv("notebookread", "analysis.ipynb --cell=3"))
                .containsExactly("analysis.ipynb", "--cell", "3");
    }

    @Test
    public void context_mapsSubcommand() throws Exception {
        assertThat(argv("context", "show")).containsExactly("show");
        assertThat(argv("context", "show -v")).containsExactly("show", "-v");
    }

    @Test
    public void edit_keepsTheWholeRequestNotJustItsSecondWord() throws Exception {
        // The tail used to be stored as args[1] under the key "limit", so everything from the third
        // token on was discarded before the request reached EditCommand.
        assertThat(argv("edit", "src/Main.java add a null check to login"))
                .containsExactly("src/Main.java", "add a null check to login");
    }

    @Test
    public void write_neverStoresContentAsALimit() {
        ParsedAction action = parse("write", "out.txt \"hello world\"");

        assertThat(action.getParameters()).containsEntry("file_path", "out.txt");
        assertThat(action.getParameters()).containsEntry("content", "hello world");
        assertThat(action.getParameters()).doesNotContainKey("limit");
    }

    @Test
    public void read_storesTheRangeAsNumbers() {
        ParsedAction action = parse("read", "Foo.java --limit=50 --offset=10");

        assertThat(action.getIntegerParameter("limit")).isEqualTo(50);
        assertThat(action.getIntegerParameter("offset")).isEqualTo(10);
        assertThat(action.getFilePath()).isEqualTo("Foo.java");
    }

    @Test
    public void read_stillHonoursTheBarePositionalLimitShorthand() {
        // Backwards compatibility with the previous "read <file> <limit>" mapping.
        assertThat(parse("read", "Foo.java 50").getIntegerParameter("limit")).isEqualTo(50);
    }

    @Test
    public void read_dropsAnUnknownFlagInsteadOfTreatingItAsThePath() {
        ParsedAction action = parse("read", "--bogus Foo.java");

        assertThat(action.getFilePath()).isEqualTo("Foo.java");
    }

    @Test
    public void keyValueArgumentFormStillWorks() {
        // The single-line "key=value, key=value" form must keep parsing as before.
        ParsedAction action = parse("grep", "pattern=TODO, path=src/");

        assertThat(action.getParameters()).containsEntry("pattern", "TODO");
        assertThat(action.getParameters()).containsEntry("path", "src/");
    }

    @Test
    public void bashArgumentsAreStillJoinedIntoOneCommandLine() throws Exception {
        assertThat(argv("bash", "ls -la target")).containsExactly("ls -la target");
    }
}
