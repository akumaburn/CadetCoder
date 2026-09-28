package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model wrote is what the job runs.
 *
 * <h2>Why the command line is not split and re-joined</h2>
 *
 * <p>A model writes one line: {@code start grep -r "foo bar" .}. Split into tokens and joined back
 * with single spaces, that is {@code grep -r foo bar .} -- a different command, run without
 * complaint and reported as though it were the one asked for. {@code bash} avoids this by being
 * handed its whole command line as one argument; a job is the same arrangement one subcommand
 * deeper.</p>
 *
 * <h2>Why the screen is shown the same line</h2>
 *
 * <p>{@code ActionRun} screens a proposal before dispatching it. Shown the whole argument list, it
 * would take {@code start} for the program about to run and screen the wrong thing -- so the line
 * it refuses or allows must be the line that runs, which is what {@code startedCommand} answers.</p>
 */
public class AjobActionKeepsItsCommandLineIntactTest {

    @Test
    void aquotedArgumentSurvivesTheTripToTheJob() {
        assertThat(JobCommand.argvFor("start grep -r \"foo bar\" .", null))
                .containsExactly("start", "grep -r \"foo bar\" .");
    }

    @Test
    void thedescriptionIsAtokenAndTheCommandIsNot() {
        assertThat(JobCommand.argvFor("start -d \"the full suite\" mvn -o test", null))
                .containsExactly("start", "-d", "the full suite", "mvn -o test");
    }

    @Test
    void adescriptionNamedByAstructuredFrontEndIsUsedToo() {
        assertThat(JobCommand.argvFor("start mvn -o test", "the full suite"))
                .containsExactly("start", "-d", "the full suite", "mvn -o test");
    }

    @Test
    void thesubcommandsThatRunNothingAreOrdinaryTokens() {
        assertThat(JobCommand.argvFor("output j1 --lines 40", null))
                .containsExactly("output", "j1", "--lines", "40");
        assertThat(JobCommand.argvFor("list", null)).containsExactly("list");
        assertThat(JobCommand.argvFor("stop all", null)).containsExactly("stop", "all");
    }

    @Test
    void anEmptyLineAsksForNothing() {
        assertThat(JobCommand.argvFor(null, null)).isEmpty();
        assertThat(JobCommand.argvFor("   ", null)).isEmpty();
    }

    @Test
    void thescreenIsShownTheCommandAndNotTheSubcommand() {
        assertThat(JobCommand.startedCommand(new String[] {"start", "mvn -o test"}))
                .isEqualTo("mvn -o test");
        assertThat(JobCommand.startedCommand(new String[] {"start", "-d", "a build", "mvn -o test"}))
                .as("the description is this subcommand's own option, not part of the command")
                .isEqualTo("mvn -o test");
    }

    @Test
    void aninvocationThatStartsNothingIsNotAshellCommand() {
        assertThat(JobCommand.startedCommand(new String[] {"list"})).isNull();
        assertThat(JobCommand.startedCommand(new String[] {"output", "j1"})).isNull();
        assertThat(JobCommand.startedCommand(new String[] {"stop", "j1"})).isNull();
        assertThat(JobCommand.startedCommand(new String[0])).isNull();
        assertThat(JobCommand.startedCommand(null)).isNull();
    }

    @Test
    void whatFollowsTheCommandBelongsToTheCommand() {
        // A flag this subcommand also has is still the command's once the command has begun.
        assertThat(JobCommand.startedCommand(new String[] {"start", "tail", "-n", "5", "log.txt"}))
                .isEqualTo("tail -n 5 log.txt");
    }
}
