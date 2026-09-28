package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.commands.ChatCommand;
import com.eonmux.cadetcoder.commands.CommandCatalog;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat prompt is the CONTRACT the model is asked to follow, so every ACTION block it teaches must
 * be a block the parser actually accepts and dispatches. This test parses each example straight out of
 * the packaged {@code chat.md} and follows it through to the CLI arguments, so an example that drifts
 * away from the parser (or a parser change that invalidates an example) fails the build instead of
 * silently costing the agent a turn at runtime.
 *
 * <h2>Why the catalogue's examples are held to the same contract</h2>
 *
 * <p>The catalogue is the other half of what the model is shown, and its examples are what a model
 * copies. One of them read {@code job start mvn -o test -d "the full suite"}, with the description
 * written after the command -- but everything from the command onwards belongs to the command, so
 * the description was passed to Maven, which has no such option. An example nothing checks is a
 * instruction to get it wrong.</p>
 */
public class ChatPromptContractTest {

    private static final Pattern ACTION_BLOCK =
            Pattern.compile("ACTION_START\\n.*?ACTION_END", Pattern.DOTALL);

    private String promptText() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/prompts/default/content/chat.md")) {
            assertThat(in).as("packaged chat prompt").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String[] argsOf(ChatCommand.AIAction action) throws Exception {
        Field field = ChatCommand.AIAction.class.getDeclaredField("arguments");
        field.setAccessible(true);
        return (String[]) field.get(action);
    }

    @Test
    public void everyActionExampleInThePromptParsesIntoADispatchableAction() throws Exception {
        Set<String> registered = new CommandRegistry().getCommands().keySet();
        ParsingContext context = new ParsingContext.Builder("follow the prompt's example")
                .setAvailableCommands(registered)
                .build();

        List<String> blocks = new ArrayList<>();
        Matcher matcher = ACTION_BLOCK.matcher(promptText());
        while (matcher.find()) {
            blocks.add(matcher.group());
        }

        assertThat(blocks).as("the prompt must contain ACTION examples").isNotEmpty();

        for (String block : blocks) {
            // The format skeleton uses the literal placeholder "command_name"; it illustrates the
            // shape rather than a concrete call, so only its structure can be checked.
            boolean isSkeleton = block.contains("COMMAND: command_name");

            ParsedResponse response = new ActionBlockParser().parse(block, context);

            if (isSkeleton) {
                assertThat(response.isSuccessful()).as("parse of format skeleton:\n%s", block).isTrue();
                assertThat(response.getActions()).hasSize(1);
                continue;
            }

            assertThat(response.isSuccessful()).as("parse of prompt example:\n%s", block).isTrue();
            assertThat(response.getActions()).as("actions for prompt example:\n%s", block).hasSize(1);

            ParsedAction action = response.getActions().get(0);
            assertThat(action.getValidation())
                    .as("validation of prompt example:\n%s", block)
                    .isEqualTo(ParsedAction.ValidationResult.VALID);
            assertThat(registered)
                    .as("command of prompt example:\n%s", block)
                    .contains(action.getCommand());
            assertThat(argsOf(action.toLegacyAction()))
                    .as("dispatched arguments for prompt example:\n%s", block)
                    .isNotEmpty();
            assertThat(response.getConfidence())
                    .as("confidence of prompt example:\n%s", block)
                    .isGreaterThanOrEqualTo(0.7);
        }
    }

    /**
     * Every {@code Example:} the catalogue gives, read as the model would copy it.
     *
     * @param catalog the catalogue text
     * @return the examples, with a wrapped one joined back into a single line
     */
    private static List<String> examplesIn(String catalog) {
        List<String>  examples = new ArrayList<>();
        String[]      lines    = catalog.split("\n");
        for (int i = 0; i < lines.length; i++) {
            int at = lines[i].indexOf("Example: ");
            if (at < 0) {
                continue;
            }
            StringBuilder example = new StringBuilder(lines[i].substring(at + "Example: ".length()).trim());
            // The catalogue wraps at the width it is read at, so an example can run onto the next
            // line. An odd number of quotes is what says it did.
            while (example.chars().filter(c -> c == '"').count() % 2 == 1 && i + 1 < lines.length) {
                example.append(' ').append(lines[++i].trim());
            }
            examples.add(example.toString());
        }
        return examples;
    }

    /** One example, dispatched the way the chat loop dispatches what the model writes. */
    private ChatCommand.AIAction dispatch(String example, ParsingContext context) {
        int    space = example.indexOf(' ');
        String verb  = space < 0 ? example : example.substring(0, space);
        String args  = space < 0 ? "" : example.substring(space + 1);

        ParsedResponse response = new ActionBlockParser().parse(
                "ACTION_START\n"
                + "COMMAND: " + verb + "\n"
                + "ARGS: " + args + "\n"
                + "REASON: the catalogue says to write it this way\n"
                + "ACTION_END", context);

        assertThat(response.getActions()).as("parse of catalogue example: %s", example).hasSize(1);
        ParsedAction action = response.getActions().get(0);
        assertThat(action.getValidation())
                .as("validation of catalogue example: %s", example)
                .isEqualTo(ParsedAction.ValidationResult.VALID);
        return action.toLegacyAction();
    }

    @Test
    public void everyExampleTheCatalogueGivesDispatches() throws Exception {
        Set<String>    registered = new CommandRegistry().getCommands().keySet();
        ParsingContext context    = new ParsingContext.Builder("follow the catalogue's example")
                .setAvailableCommands(registered)
                .build();

        List<String> examples = examplesIn(CommandCatalog.coreCommands());
        examples.addAll(examplesIn(CommandCatalog.workerCommands()));
        assertThat(examples).as("the catalogue must give examples").isNotEmpty();

        for (String example : examples) {
            assertThat(argsOf(dispatch(example, context)))
                    .as("dispatched arguments for catalogue example: %s", example)
                    .isNotEmpty();
        }
    }

    /**
     * The one example whose argument order decides what runs.
     *
     * <p>{@code job start} reads its own options only in front of the command, because everything
     * from the command onwards is the command's. An example that put {@code -d} after it taught the
     * model to hand Maven an option Maven does not have.</p>
     */
    @Test
    public void ajobExampleKeepsItsDescriptionOutOfTheCommand() throws Exception {
        Set<String>    registered = new CommandRegistry().getCommands().keySet();
        ParsingContext context    = new ParsingContext.Builder("follow the catalogue's example")
                .setAvailableCommands(registered)
                .build();

        List<String> jobs = examplesIn(CommandCatalog.coreCommands()).stream()
                                                                    .filter(line -> line.startsWith("job start"))
                                                                    .toList();
        assertThat(jobs).as("the catalogue shows how to start a job").isNotEmpty();

        for (String example : jobs) {
            String[] argv = argsOf(dispatch(example, context));

            assertThat(argv).as("the description is read as one: %s", example).contains("-d");
            assertThat(argv[argv.length - 1])
                    .as("what is left is the command, and it is only the command: %s", example)
                    .doesNotContain("-d");
        }
    }

    @Test
    public void everyCatalogCommandIsRegistered() {
        Set<String> registered = new CommandRegistry().getCommands().keySet();

        Matcher matcher = Pattern.compile("(?m)^- ([a-z]+) ").matcher(CommandCatalog.coreCommands());
        List<String> advertised = new ArrayList<>();
        while (matcher.find()) {
            advertised.add(matcher.group(1));
        }

        assertThat(advertised).isNotEmpty();
        assertThat(registered).as("the catalog must only advertise commands that exist")
                .containsAll(advertised);
    }
}
