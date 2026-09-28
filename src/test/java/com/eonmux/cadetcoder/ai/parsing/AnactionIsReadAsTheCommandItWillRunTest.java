package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An action block is read by the command it will be dispatched to.
 *
 * <h2>Why the verb has to be canonical on both sides</h2>
 *
 * <p>Arguments are read into the parameters one command expects, and written back out as the argv
 * another command accepts. The reader used to be given the verb the model wrote and the writer the
 * verb it resolves to, so for every synonym the two were different commands: the reader fell
 * through to its numbered-argument default and the writer looked for parameters that were never
 * set. {@code list src/main/java -R} then dispatched as {@code ls} with no arguments at all --
 * listing the wrong directory, reporting success, and telling the model what it asked for.</p>
 *
 * <h2>Why a command line is not read as words</h2>
 *
 * <p>{@code bash} and {@code job} run what they are handed. Tokenising a line and joining it back
 * with single spaces loses the quoting, so {@code grep -r "foo bar" .} becomes a search for
 * {@code foo} in the files {@code bar} and {@code .} -- run without complaint, and screened as the
 * command it became rather than the one that was asked for.</p>
 *
 * <h2>Why a payload that reads as a label is refused</h2>
 *
 * <p>An inline {@code ARGS:} payload ends at the next label line, so a file whose content holds a
 * line such as {@code COMMAND: read} ended there too. The rest was read as the block's own fields,
 * the file was written truncated, and the action reported success. Such a block is refused, with a
 * message saying to send the payload between {@code ARGS_BEGIN} and {@code ARGS_END} -- which
 * carries it whole.</p>
 */
public class AnactionIsReadAsTheCommandItWillRunTest {

    private final ActionBlockParser parser = new ActionBlockParser();

    /** No command list, so availability is not what is under test here. */
    private ParsingContext ctx() {
        return new ParsingContext.Builder("do the thing").build();
    }

    private ParsedResponse parse(String response) {
        return parser.parse(response, ctx());
    }

    /** A context that knows which commands exist, which is what a real run hands the parser. */
    private ParsedResponse parseAgainstTheRealCommands(String response) {
        ParsingContext context = new ParsingContext.Builder("run the suite")
                .setAvailableCommands(
                        new com.eonmux.cadetcoder.CommandRegistry().getCommands().keySet())
                .build();
        return parser.parse(response, context);
    }

    private ChatCommand.AIAction dispatched(String command, String args) {
        String response = "ACTION_START\n"
                          + "COMMAND: " + command + "\n"
                          + "ARGS: " + args + "\n"
                          + "REASON: because the work needs it\n"
                          + "ACTION_END";
        ParsedResponse result = parser.parse(response, ctx());
        assertThat(result.getActions()).as("the block should parse").isNotEmpty();
        return result.getActions().get(0).toLegacyAction();
    }

    @Test
    public void asynonymKeepsTheArgumentsItWasGiven() throws Exception {
        ChatCommand.AIAction action = dispatched("list", "src/main/java -R");

        assertThat(commandOf(action)).isEqualTo("ls");
        assertThat(argsOf(action)).contains("src/main/java");
    }

    @Test
    public void asynonymForReadingAfileStillNamesTheFile() throws Exception {
        ChatCommand.AIAction action = dispatched("cat", "pom.xml");

        assertThat(commandOf(action)).isEqualTo("read");
        assertThat(argsOf(action)).containsExactly("pom.xml");
    }

    @Test
    public void asynonymForWritingAfileStillCarriesThePath() throws Exception {
        ChatCommand.AIAction action = dispatched("create", "notes.txt\nthe body");

        assertThat(commandOf(action)).isEqualTo("write");
        assertThat(argsOf(action)).as("the path and the content both survive").hasSize(2);
        assertThat(argsOf(action)[0]).isEqualTo("notes.txt");
    }

    @Test
    public void aquotedShellArgumentSurvivesBeingRead() throws Exception {
        ChatCommand.AIAction action = dispatched("bash", "grep -r \"foo bar\" .");

        assertThat(commandOf(action)).isEqualTo("bash");
        assertThat(argsOf(action)).hasSize(1);
        assertThat(argsOf(action)[0])
                .as("the quotes are what make this one argument rather than two")
                .isEqualTo("grep -r \"foo bar\" .");
    }

    @Test
    public void asynonymForTheShellIsReadTheSameWay() throws Exception {
        ChatCommand.AIAction action = dispatched("run", "echo \"two words\"");

        assertThat(commandOf(action)).isEqualTo("bash");
        assertThat(argsOf(action)).containsExactly("echo \"two words\"");
    }

    @Test
    public void ajobStartsWithTheCommandItWasGiven() throws Exception {
        ChatCommand.AIAction action = dispatched("job", "start mvn -o test -d \"the full suite\"");

        assertThat(commandOf(action)).isEqualTo("job");
        assertThat(argsOf(action)).startsWith("start");
        assertThat(String.join(" ", argsOf(action)))
                .as("the command it is to run is one argument, not several")
                .contains("mvn -o test");
    }

    @Test
    public void asynonymForAjobIsReadTheSameWay() throws Exception {
        ChatCommand.AIAction action = dispatched("jobs", "list");

        assertThat(commandOf(action)).isEqualTo("job");
        assertThat(argsOf(action)).containsExactly("list");
    }

    @Test
    public void grepsContextFlagIsNotReadAsThePathToSearch() throws Exception {
        ChatCommand.AIAction action = dispatched("grep", "\"TODO\" --include=*.java -C 3");

        assertThat(commandOf(action)).isEqualTo("grep");
        String line = String.join(" ", argsOf(action));
        assertThat(line).contains("TODO").contains("--include=*.java");
        assertThat(line)
                .as("a number of context lines is not a directory to search")
                .doesNotContain("--path=3");
    }

    @Test
    public void apayloadCutShortByAlabelIsRefusedRatherThanWrittenTruncated() {
        ParsedResponse result = parse("ACTION_START\n"
                                      + "COMMAND: write\n"
                                      + "ARGS: notes.md The format is simple.\n"
                                      + "COMMAND: the verb to run\n"
                                      + "REASON: because the notes are needed\n"
                                      + "ACTION_END");

        assertThat(result.getActions()).hasSize(1);
        ParsedAction refused = result.getActions().get(0);
        assertThat(refused.isValid()).isFalse();
        assertThat(refused.getValidation())
                .isEqualTo(ParsedAction.ValidationResult.INVALID_PARAMETERS);
        assertThat(refused.getValidationMessage()).contains("ARGS_BEGIN");
    }

    @Test
    public void adelimitedPayloadKeepsAlineThatReadsAsAlabel() throws Exception {
        ChatCommand.AIAction action = parse("ACTION_START\n"
                                            + "COMMAND: write\n"
                                            + "ARGS_BEGIN\n"
                                            + "notes.md The format is simple.\n"
                                            + "COMMAND: the verb to run\n"
                                            + "ARGS_END\n"
                                            + "REASON: because the notes are needed\n"
                                            + "ACTION_END")
                .getActions().get(0).toLegacyAction();

        assertThat(commandOf(action)).isEqualTo("write");
        assertThat(String.join(" ", argsOf(action)))
                .as("the payload is the file's content, labels and all")
                .contains("COMMAND: the verb to run");
    }

    @Test
    public void adiffReachesThePatchCommandAsOneArgument() throws Exception {
        ChatCommand.AIAction action = parse("ACTION_START\n"
                                            + "COMMAND: patch\n"
                                            + "ARGS_BEGIN\n"
                                            + "--- a/notes.txt\n"
                                            + "+++ b/notes.txt\n"
                                            + "@@ -1,1 +1,1 @@\n"
                                            + "-old\n"
                                            + "+new\n"
                                            + "ARGS_END\n"
                                            + "REASON: because the line is wrong\n"
                                            + "ACTION_END")
                .getActions().get(0).toLegacyAction();

        assertThat(commandOf(action)).isEqualTo("patch");
        assertThat(argsOf(action)[0])
                .as("a diff is one argument, not a list of words")
                .contains("--- a/notes.txt")
                .contains("+new");
    }

    @Test
    public void aprosePayloadWithAcommaIsNotReadAsPairs() throws Exception {
        ChatCommand.AIAction action = dispatched("commit", "message=fix: nulls, and empties");

        assertThat(commandOf(action)).isEqualTo("commit");
        assertThat(String.join(" ", argsOf(action)))
                .as("everything after the comma is part of the message")
                .contains("and empties");
    }

    @Test
    public void bundledShortFlagsAreBothRead() throws Exception {
        ChatCommand.AIAction action = dispatched("ls", "-la src");

        assertThat(commandOf(action)).isEqualTo("ls");
        String line = String.join(" ", argsOf(action));
        assertThat(line).contains("src");
        assertThat(argsOf(action))
                .as("both letters of the bundle are flags in their own right")
                .contains("-a", "-l");
    }

    @Test
    public void apatternThatBeginsWithAdashIsStillSearchedFor() throws Exception {
        ChatCommand.AIAction action = dispatched("grep", "-- -Xmx src");

        assertThat(commandOf(action)).isEqualTo("grep");
        String[] args = argsOf(action);
        assertThat(args).contains("-Xmx");
        assertThat(args[args.length - 2])
                .as("the pattern is written after the end-of-flags marker")
                .isEqualTo("--");
        assertThat(args[args.length - 1]).isEqualTo("-Xmx");
    }

    /**
     * The verb line may carry the subcommand, because that is how the catalogue reads.
     *
     * <p>{@code job start} is the name the model is taught: the catalogue says "use `job start`",
     * and every example of it is two words. Written on the COMMAND line, the whole of it was looked
     * up as one command name, found to be no command at all, and the block was refused as a format
     * error -- twice, and then the run ended. The verb is the first word; what follows it belongs
     * with the arguments.</p>
     */
    @Test
    public void averbLineThatCarriesItsSubcommandIsStillThatCommand() throws Exception {
        ParsedResponse result = parseAgainstTheRealCommands(
                "ACTION_START\n"
                + "COMMAND: job start\n"
                + "ARGS: mvn -o test\n"
                + "REASON: the suite takes longer than a step\n"
                + "ACTION_END");

        assertThat(result.getActions()).hasSize(1);
        ParsedAction parsed = result.getActions().get(0);
        assertThat(parsed.getValidation())
                .as("`job start` names a command that exists")
                .isEqualTo(ParsedAction.ValidationResult.VALID);

        ChatCommand.AIAction action = parsed.toLegacyAction();
        assertThat(commandOf(action)).isEqualTo("job");
        assertThat(argsOf(action)).containsExactly("start", "mvn -o test");
    }

    @Test
    public void whatFollowsTheVerbIsNotLostWhenTheArgumentsAreEmpty() throws Exception {
        ParsedResponse result = parseAgainstTheRealCommands(
                "ACTION_START\n"
                + "COMMAND: job list\n"
                + "ARGS:\n"
                + "REASON: see what is still running\n"
                + "ACTION_END");

        assertThat(result.getActions()).hasSize(1);
        ChatCommand.AIAction action = result.getActions().get(0).toLegacyAction();
        assertThat(commandOf(action)).isEqualTo("job");
        assertThat(argsOf(action)).containsExactly("list");
    }

    @Test
    public void ajobsDescriptionDoesNotEndUpInsideTheCommandItRuns() throws Exception {
        ChatCommand.AIAction action =
                dispatched("job", "start -d \"the full suite\" mvn -o test");

        assertThat(argsOf(action)).containsExactly("start", "-d", "the full suite", "mvn -o test");
    }

    private String commandOf(ChatCommand.AIAction action) throws ReflectiveOperationException {
        Field field = ChatCommand.AIAction.class.getDeclaredField("command");
        field.setAccessible(true);
        return (String) field.get(action);
    }

    private String[] argsOf(ChatCommand.AIAction action) throws ReflectiveOperationException {
        Field field = ChatCommand.AIAction.class.getDeclaredField("arguments");
        field.setAccessible(true);
        return (String[]) field.get(action);
    }
}
