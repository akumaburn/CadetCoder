package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for grep argument parsing in {@link ActionBlockParser}.
 *
 * <p>A model that emits a valid ACTION_START block with grep flags (e.g. {@code --line-number}) must
 * have those flags recognized as flags, never folded into the search-path positional. The reported
 * failure translated a bare {@code --line-number} into {@code --path=--line-number}, which failed the
 * whole grep and burned iterations. These tests pin the full parse -> {@link ParsedAction#toLegacyAction()}
 * round trip so the emitted CLI args match what {@code GrepCommand} accepts.</p>
 */
public class ActionBlockParserGrepTest {

    /** Parses a grep ACTION block with the given ARGS line and returns the emitted legacy CLI args. */
    private List<String> emittedArgs(String argsLine) throws Exception {
        String block = "ACTION_START\n" +
                       "COMMAND: grep\n" +
                       "ARGS: " + argsLine + "\n" +
                       "REASON: locate something in the source\n" +
                       "ACTION_END";

        ParsedResponse response = new ActionBlockParser()
                .parse(block, new ParsingContext.Builder("find the entry point").build());

        assertThat(response.getActions()).isNotEmpty();
        ChatCommand.AIAction legacy = response.getActions().get(0).toLegacyAction();

        Field f = ChatCommand.AIAction.class.getDeclaredField("arguments");
        f.setAccessible(true);
        return Arrays.asList((String[]) f.get(legacy));
    }

    @Test
    public void lineNumberFlagIsNotAssignedAsPath() throws Exception {
        List<String> args = emittedArgs("\"class.Main\\|public.*main\" --include=.java --line-number");

        // The pattern is the first positional; the flags are emitted as flags, and NOTHING is turned
        // into a bogus "--path=--line-number".
        assertThat(args.get(0)).isEqualTo("class.Main\\|public.*main");
        assertThat(args).contains("--include=.java", "--line-number");
        assertThat(args).as("a bare --line-number must never become a search path")
                .noneMatch(a -> a.startsWith("--path="));
    }

    @Test
    public void shortFlagsAreRecognized() throws Exception {
        List<String> args = emittedArgs("\"TODO\" -i -n");

        assertThat(args.get(0)).isEqualTo("TODO");
        assertThat(args).contains("--ignore-case", "--line-number");
        assertThat(args).noneMatch(a -> a.startsWith("--path="));
    }

    @Test
    public void excludeAndExplicitPathAreParsed() throws Exception {
        List<String> args = emittedArgs("\"foo\" src --exclude=*Test.java");

        assertThat(args.get(0)).isEqualTo("foo");
        assertThat(args).contains("--path=src", "--exclude=*Test.java");
    }

    @Test
    public void unknownFlagIsDroppedNotTreatedAsPath() throws Exception {
        List<String> args = emittedArgs("\"foo\" --bogus-flag");

        assertThat(args.get(0)).isEqualTo("foo");
        assertThat(args).as("an unrecognized flag must be dropped, not corrupt the search path")
                .noneMatch(a -> a.startsWith("--path="));
    }
}
