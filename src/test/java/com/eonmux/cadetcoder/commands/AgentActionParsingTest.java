package com.eonmux.cadetcoder.commands;

import org.junit.Test;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent loop must be able to run the commands it is offered.
 *
 * <p>Its catalog advertises {@code write <filepath> <content>} and {@code multiedit <filepath>
 * <edits>}, where the edits are {@code EDIT_START}/{@code EDIT_END} blocks spanning several lines.
 * Its parser read arguments from a single {@code ARGS:} line and stopped at the next label, and its
 * prompt documented no other spelling -- so a payload with a newline in it was truncated to its
 * first line, or lost entirely. Chat has understood {@code ARGS_BEGIN}/{@code ARGS_END} for exactly
 * this reason; the agent never got it, and the two loops silently differed in what they could do.</p>
 */
public class AgentActionParsingTest {

    /** What the agent would make of one reply. Warnings are not what these tests assert on. */
    private static AgentAction parse(String response) {
        return AgentActions.from(response, message -> { });
    }

    private static String commandOf(AgentAction action) {
        return action.command;
    }

    private static String[] argsOf(AgentAction action) {
        return action.args;
    }

    @Test
    public void aMultiLineWritePayloadSurvives() throws Exception {
        String response = "ACTION_START\n"
                          + "COMMAND: write\n"
                          + "ARGS_BEGIN\n"
                          + "notes/Example.java\n"
                          + "public class Example {\n"
                          + "    // REASON: this line looks like a label and must not end the payload\n"
                          + "    void run() {}\n"
                          + "}\n"
                          + "ARGS_END\n"
                          + "REASON: create the file\n"
                          + "ACTION_END";

        AgentAction action = parse(response);

        assertThat(action).as("a write block must parse at all").isNotNull();
        assertThat(commandOf(action)).isEqualTo("write");
        assertThat(argsOf(action))
                .as("write takes the path and the content as two arguments")
                .hasSize(2);
        assertThat(argsOf(action)[0]).isEqualTo("notes/Example.java");
        assertThat(argsOf(action)[1])
                .as("the whole body, not just its first line")
                .contains("public class Example {")
                .contains("void run() {}");
    }

    @Test
    public void aMultieditPayloadSurvives() throws Exception {
        String response = "ACTION_START\n"
                          + "COMMAND: multiedit\n"
                          + "ARGS_BEGIN\n"
                          + "src/Main.java\n"
                          + "EDIT_START\n"
                          + "OLD: int x = 1;\n"
                          + "NEW: int x = 2;\n"
                          + "EDIT_END\n"
                          + "ARGS_END\n"
                          + "REASON: bump the value\n"
                          + "ACTION_END";

        AgentAction action = parse(response);

        assertThat(action).isNotNull();
        assertThat(commandOf(action)).isEqualTo("multiedit");
        assertThat(String.join(" ", argsOf(action)))
                .as("the edit blocks are the whole point of the command")
                .contains("EDIT_START")
                .contains("OLD: int x = 1;")
                .contains("NEW: int x = 2;");
    }

    // ---- everything the agent could already do must keep working ----

    @Test
    public void anInlineArgsLineStillWorks() throws Exception {
        AgentAction action = parse("COMMAND: read ARGS: src/Main.java");

        assertThat(commandOf(action)).isEqualTo("read");
        assertThat(argsOf(action)).contains("src/Main.java");
    }

    @Test
    public void anArgsLineBelowTheCommandStillWorks() throws Exception {
        AgentAction action = parse("ACTION_START\n"
                              + "COMMAND: grep\n"
                              + "ARGS: \"class Main\"\n"
                              + "REASON: find it\n"
                              + "ACTION_END");

        assertThat(commandOf(action)).isEqualTo("grep");
        assertThat(argsOf(action)[0]).isEqualTo("class Main");
    }

    @Test
    public void aBareCommandWithNoBlockMarkersStillWorks() throws Exception {
        // Models routinely drop the ACTION_START/ACTION_END markers. The shared block parser
        // requires them, so this tolerance has to survive alongside it.
        AgentAction action = parse("COMMAND: ls\nARGS: src\n");

        assertThat(commandOf(action)).isEqualTo("ls");
        assertThat(argsOf(action)).contains("src");
    }

    @Test
    public void aCompletionSignalIsStillRecognised() throws Exception {
        assertThat(commandOf(parse("TASK COMPLETE: added the tests"))).isEqualTo("complete");
        assertThat(commandOf(parse("TASK FAILED: could not build"))).isEqualTo("complete");
        assertThat(commandOf(parse("complete all done"))).isEqualTo("complete");
    }

    @Test
    public void proseIsNeverDispatchedAsACommand() throws Exception {
        assertThat(parse("Sure, I can help you with that."))
                .as("the first word of a sentence must not become a command")
                .isNull();
        assertThat(parse(null)).isNull();
    }
}
