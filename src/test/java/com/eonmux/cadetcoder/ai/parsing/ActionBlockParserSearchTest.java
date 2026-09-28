package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code search} takes a free-text query; {@code grep} takes a pattern and a path.
 *
 * <p>The two shared one parser arm, so a search query was split on whitespace: the first word became
 * the pattern, the second became a {@code --path=}, and every word after that was dropped. Since the
 * whole point of {@code search} is to ask for code by describing it, the query is nearly always more
 * than two words, which made the command unusable from the agentic loop in its normal case. These
 * pin the parse -> {@link ParsedAction#toLegacyAction()} round trip against that.</p>
 */
public class ActionBlockParserSearchTest {

    private static ChatCommand.AIAction dispatched(String command, String argsLine) {
        String block = "ACTION_START\n" +
                       "COMMAND: " + command + "\n" +
                       "ARGS: " + argsLine + "\n" +
                       "REASON: locate the relevant code\n" +
                       "ACTION_END";

        ParsedResponse response = new ActionBlockParser()
                .parse(block, new ParsingContext.Builder("find the retry logic").build());

        assertThat(response.getActions()).isNotEmpty();
        return response.getActions().get(0).toLegacyAction();
    }

    private static List<String> argsOf(ChatCommand.AIAction action) throws Exception {
        Field f = ChatCommand.AIAction.class.getDeclaredField("arguments");
        f.setAccessible(true);
        return Arrays.asList((String[]) f.get(action));
    }

    private static String nameOf(ChatCommand.AIAction action) throws Exception {
        Field f = ChatCommand.AIAction.class.getDeclaredField("command");
        f.setAccessible(true);
        return (String) f.get(action);
    }

    @Test
    public void anUnquotedMultiWordQuerySurvivesWhole() throws Exception {
        ChatCommand.AIAction action = dispatched("search", "where the retry backoff is computed");

        assertThat(argsOf(action))
                .as("the query is one free-text argument, not a pattern plus a path plus dropped words")
                .containsExactly("where the retry backoff is computed");
    }

    @Test
    public void aQuotedMultiWordQuerySurvivesWhole() throws Exception {
        ChatCommand.AIAction action = dispatched("search", "\"where the retry backoff is computed\"");

        assertThat(argsOf(action)).containsExactly("where the retry backoff is computed");
    }

    @Test
    public void noWordOfTheQueryBecomesAPathFlag() throws Exception {
        ChatCommand.AIAction action = dispatched("search", "how tokens are estimated");

        assertThat(argsOf(action))
                .as("SearchCommand joins its arguments into the query, so a stray --path= would be "
                    + "searched for literally")
                .noneMatch(argument -> argument.startsWith("--"));
    }

    @Test
    public void searchDispatchesToSearchAndNotToGrep() throws Exception {
        assertThat(nameOf(dispatched("search", "how tokens are estimated"))).isEqualTo("search");
    }

    /** The other front-ends put the text under "pattern"; the dispatcher must accept either. */
    @Test
    public void aQueryArrivingUnderThePatternKeyIsStillEmitted() {
        ParsedAction action = new ParsedAction("search",
                java.util.Map.of("pattern", "where the retry backoff is computed"),
                "locate the relevant code");

        assertThat(action.toLegacyAction()).isNotNull();
    }

    /** grep keeps the pattern-plus-path scheme it actually needs. */
    @Test
    public void grepStillTakesAPatternAndAPath() throws Exception {
        List<String> args = argsOf(dispatched("grep", "\"class Main\" src/main/java"));

        assertThat(args.get(0)).isEqualTo("class Main");
        assertThat(args).contains("--path=src/main/java");
    }
}
