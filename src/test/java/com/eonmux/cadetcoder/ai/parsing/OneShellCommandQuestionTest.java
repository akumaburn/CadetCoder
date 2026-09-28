package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.CommandAliases;
import com.eonmux.cadetcoder.security.DangerousCommands;
import org.junit.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a proposed action will run is one question, and the screen must ask the dispatcher.
 *
 * <p>{@link CommandAliases} exists because a model that writes {@code execute} for "run a shell
 * command" had its action dispatched to a different command -- and its own javadoc names, as one of
 * the two reasons, that "the shell-command security validation (which keys on {@code bash}) was
 * silently skipped". The dispatch half was fixed. The screen half was not: {@code SecurityValidator}
 * still asked whether the verb was literally {@code bash}, {@code shell} or {@code execute}, while
 * {@code CommandAliases} also maps {@code run} and {@code exec} onto the shell runner. So
 * {@code COMMAND: run} reached {@code BashCommand} without the denylist, the network rule or the
 * injection check having looked at the line at all.</p>
 *
 * <p>Where the line lives was the other half. Both screens read the {@code command} parameter, which
 * is what the JSON and XML front ends produce. An {@code ACTION_START} block writes {@code ARGS:}
 * into {@code args}, and {@code ParsedAction} hands THAT to the shell runner whole -- so for the
 * front end the agentic loop actually uses, the screens saw an action with no command line.</p>
 *
 * <p>And {@code JSONSchemaParser} kept a third private denylist: four substring tests that knew
 * nothing of {@code dd}, {@code mkfs} or {@code shutdown}, and that called {@code git format-patch}
 * a security risk because its name contains {@code format}.</p>
 */
public class OneShellCommandQuestionTest {

    private final SecurityValidator validator = new SecurityValidator();

    private ParsingContext context() {
        return new ParsingContext.Builder("do some work")
                .workingDirectory(System.getProperty("user.dir"))
                .build();
    }

    private ParsedAction action(String verb, String parameterKey, Object value) {
        return new ParsedAction.Builder(verb)
                .addParameter(parameterKey, value)
                .setReasoning("run it")
                .build();
    }

    private boolean refuses(ParsedAction proposed) {
        return validator.validateAction(proposed, context()).getViolations().stream()
                .anyMatch(violation -> violation.startsWith(SecurityValidator.REFUSED_COMMAND));
    }

    /**
     * Every verb that reaches the shell runner is screened as one.
     *
     * <p>Driven off the alias table rather than a second list of verbs, because a second list is
     * exactly what drifted: the table gained {@code run} and {@code exec} and the screen did not
     * hear about it.</p>
     */
    @Test
    public void everyVerbThatDispatchesToTheShellRunnerIsScreenedAsOne() {
        for (String verb : CommandAliases.aliases()) {
            if (!"bash".equals(CommandAliases.canonicalize(verb))) {
                continue;
            }
            assertThat(refuses(action(verb, "command", "rm -rf /")))
                    .as("`%s` runs a shell command, so its line must be screened like bash's", verb)
                    .isTrue();
        }
        assertThat(refuses(action("bash", "command", "rm -rf /")))
                .as("the guard must not be vacuously satisfied by an empty alias set")
                .isTrue();
    }

    /** The shape the agentic loop's own front end produces. */
    @Test
    public void aCommandLineCarriedInTheArgsKeyIsScreened() {
        assertThat(refuses(action("bash", "args", "rm -rf /")))
                .as("ACTION_START writes ARGS: into `args`, and that line is what gets run")
                .isTrue();
        assertThat(refuses(action("run", "args", "dd if=/dev/zero of=/dev/sda")))
                .isTrue();
    }

    /** An ordinary command is still ordinary, whichever key and verb it arrives under. */
    @Test
    public void anOrdinaryCommandIsNotRefused() {
        assertThat(refuses(action("run", "args", "git status"))).isFalse();
        assertThat(refuses(action("bash", "command", "mvn -o -B test"))).isFalse();
    }

    /**
     * The string screened is the string dispatched.
     *
     * <p>Not "equivalent to" -- the same string, because the screen now asks the action the same
     * question the dispatcher asks it.</p>
     */
    @Test
    public void theLineScreenedIsTheLineDispatched() throws Exception {
        ParsedAction proposed = new ParsedAction.Builder("exec")
                .addParameter("command", Arrays.asList("grep", "-r", "TODO"))
                .addParameter("args", "src/main")
                .build();

        assertThat(proposed.shellCommandLine()).isEqualTo("grep -r TODO src/main");
        assertThat(dispatchedArguments(proposed))
                .as("BashCommand joins its argv with a space; one argument means one command line")
                .containsExactly("grep -r TODO src/main");
    }

    /** An action that runs nothing has no command line to screen. */
    @Test
    public void anActionThatIsNotAShellInvocationHasNoCommandLine() {
        assertThat(action("read", "file_path", "README.md").shellCommandLine()).isNull();
        assertThat(action("bash", "file_path", "README.md").shellCommandLine()).isNull();
    }

    /** The synonym groups are the alias table's, not a second copy of it. */
    @Test
    public void theOperationPredicatesFollowTheAliasTable() {
        assertThat(action("display", "file_path", "a.txt").isReadOperation())
                .as("`display` is a read to the dispatcher, so it is a read here")
                .isTrue();
        assertThat(action("change", "file_path", "a.txt").isModifyOperation()).isTrue();
        assertThat(action("exec", "command", "ls").isExecutionOperation()).isTrue();
        assertThat(action("read", "file_path", "a.txt").isExecutionOperation()).isFalse();
    }

    /** The argv the action is dispatched with; {@code AIAction} exposes its fields to its own package only. */
    private static String[] dispatchedArguments(ParsedAction proposed) throws Exception {
        java.lang.reflect.Field field =
                com.eonmux.cadetcoder.commands.ChatCommand.AIAction.class.getDeclaredField("arguments");
        field.setAccessible(true);
        return (String[]) field.get(proposed.toLegacyAction());
    }

    private ParsedAction.ValidationResult jsonVerdict(String verb, String commandLine) {
        // The validation the JSON strategy performs, reached the way the strategy reaches it.
        return new JSONSchemaParser().parse(
                "{\"action\":\"" + verb + "\",\"parameters\":{\"command\":\"" + commandLine + "\"}}",
                new ParsingContext.Builder("do some work").build())
                .getActions().get(0).getValidation();
    }

    /** The JSON strategy consults the shared denylist rather than four substrings of its own. */
    @Test
    public void theJsonStrategyUsesTheSharedDenylist() {
        assertThat(jsonVerdict("bash", "dd if=/dev/zero of=/dev/sda"))
                .as("dd is on the shared list; a private list of four substrings had never heard of it")
                .isEqualTo(ParsedAction.ValidationResult.SECURITY_RISK);
        assertThat(jsonVerdict("run", "shutdown -h now"))
                .as("`run` is the shell runner too")
                .isEqualTo(ParsedAction.ValidationResult.SECURITY_RISK);
    }

    /** And it stops calling ordinary work dangerous. */
    @Test
    public void theJsonStrategyNoLongerFlagsACommandForContainingTheWordFormat() {
        assertThat(jsonVerdict("bash", "git format-patch -1"))
                .as("`format` inside a subcommand name is not the `format` utility")
                .isNotEqualTo(ParsedAction.ValidationResult.SECURITY_RISK);
        assertThat(jsonVerdict("bash", "git add ."))
                .as("the `dd` in `add` is not dd")
                .isNotEqualTo(ParsedAction.ValidationResult.SECURITY_RISK);
    }

    /** The split that makes the denylist usable belongs to the class that owns the list. */
    @Test
    public void aRefusedVerbIsFoundWhereverOnTheLineItAppears() {
        assertThat(DangerousCommands.referencedBy("ls;rm -rf /")).containsExactly("rm");
        assertThat(DangerousCommands.referencedBy("git clean -df && /bin/rm x")).containsExactly("rm");
        assertThat(DangerousCommands.referencedBy("git add .")).isEmpty();
        assertThat(DangerousCommands.referencedBy("echo $result")).isEmpty();
        assertThat(DangerousCommands.isReferencedBy(null)).isFalse();
    }
}
