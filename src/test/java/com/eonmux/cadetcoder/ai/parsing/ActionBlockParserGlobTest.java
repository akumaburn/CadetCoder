package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Argument parsing for {@code glob} in {@link ActionBlockParser}.
 *
 * <p>The same defect class {@link ActionBlockParserGrepTest} covers for grep: a token that is
 * plainly a flag must never be folded into the search-path positional. {@code glob} took its second
 * argument as the path whatever it was, so {@code glob "**&#47;*.java" -p} became
 * {@code --path=-p}, which {@code GlobCommand}'s parser rejects — failing the command and burning a
 * turn on a listing the model had asked for correctly.</p>
 */
public class ActionBlockParserGlobTest {

    /** Parses a glob ACTION block with the given ARGS line and returns the emitted legacy CLI args. */
    private List<String> emittedArgs(String argsLine) throws Exception {
        String block = "ACTION_START\n" +
                       "COMMAND: glob\n" +
                       "ARGS: " + argsLine + "\n" +
                       "REASON: find the source files\n" +
                       "ACTION_END";

        ParsedResponse response = new ActionBlockParser()
                .parse(block, new ParsingContext.Builder("review the code").build());

        assertThat(response.getActions()).isNotEmpty();
        ChatCommand.AIAction legacy = response.getActions().get(0).toLegacyAction();

        Field f = ChatCommand.AIAction.class.getDeclaredField("arguments");
        f.setAccessible(true);
        return Arrays.asList((String[]) f.get(legacy));
    }

    @Test
    public void aPatternOnItsOwnIsPassedThrough() throws Exception {
        assertThat(emittedArgs("\"**/*.java\"")).containsExactly("**/*.java");
    }

    @Test
    public void aBarePathPositionalBecomesThePathFlag() throws Exception {
        // GlobCommand accepts the base path only via -p/--path; a second positional is rejected.
        assertThat(emittedArgs("\"**/*.java\" src/main/java"))
                .containsExactly("**/*.java", "--path=src/main/java");
    }

    @Test
    public void aShortFlagIsNotMistakenForThePath() throws Exception {
        List<String> args = emittedArgs("\"**/*.java\" -p");

        assertThat(args).doesNotContain("--path=-p");
        assertThat(args).first().isEqualTo("**/*.java");
    }

    @Test
    public void thePathFlagIsHonouredInBothItsForms() throws Exception {
        assertThat(emittedArgs("\"**/*.java\" --path=src/main/java"))
                .containsExactly("**/*.java", "--path=src/main/java");
        assertThat(emittedArgs("\"**/*.java\" --path src/main/java"))
                .containsExactly("**/*.java", "--path=src/main/java");
        assertThat(emittedArgs("\"**/*.java\" -p src/main/java"))
                .containsExactly("**/*.java", "--path=src/main/java");
    }

    @Test
    public void aValueFlagDoesNotSwallowAnotherFlagAsItsValue() throws Exception {
        // "--path -p" is a malformed pair. Taking "-p" as the path produces "--path=-p", which fails
        // the command; dropping the valueless flag leaves a glob that still does what was asked.
        List<String> args = emittedArgs("\"**/*.java\" --path -p");

        assertThat(args).doesNotContain("--path=-p");
        assertThat(args).first().isEqualTo("**/*.java");
    }

    @Test
    public void globsOwnFlagsSurviveInsteadOfBeingReadAsAPath() throws Exception {
        List<String> args = emittedArgs("\"**/*.java\" -l 50");

        assertThat(args).doesNotContain("--path=-l");
        assertThat(args).contains("**/*.java");
        // The limit the model asked for must not be silently dropped either.
        assertThat(String.join(" ", args)).contains("50");
    }

    @Test
    public void booleanFlagsAreNotReadAsAPath() throws Exception {
        List<String> args = emittedArgs("\"**/*.java\" --full-path");

        assertThat(args).doesNotContain("--path=--full-path");
        assertThat(args).contains("**/*.java");
    }

    @Test
    public void maxDepthIsCarriedThrough() throws Exception {
        List<String> args = emittedArgs("\"**/*.java\" --max-depth 3");

        assertThat(args).doesNotContain("--path=--max-depth");
        assertThat(String.join(" ", args)).contains("3");
    }

    @Test
    public void aValueFlagOnAnyCommandDoesNotSwallowTheFollowingFlag() throws Exception {
        // The same guard in the shared flag scanner the other commands use. In "ls src --sort -l",
        // "--sort" must take no value rather than storing "-l" as the sort key -- while "-l", which
        // is a real ls flag, is still passed through as one.
        String block = "ACTION_START\n"
                       + "COMMAND: ls\n"
                       + "ARGS: src --sort -l\n"
                       + "REASON: list the sources\n"
                       + "ACTION_END";

        ParsedResponse response = new ActionBlockParser()
                .parse(block, new ParsingContext.Builder("look around").build());
        ChatCommand.AIAction legacy = response.getActions().get(0).toLegacyAction();

        Field f = ChatCommand.AIAction.class.getDeclaredField("arguments");
        f.setAccessible(true);
        List<String> args = Arrays.asList((String[]) f.get(legacy));

        assertThat(String.join(" ", args))
                .as("--sort must not have been given a flag as its value")
                .doesNotContain("sort");
        assertThat(args).contains("src", "-l");
    }
}
