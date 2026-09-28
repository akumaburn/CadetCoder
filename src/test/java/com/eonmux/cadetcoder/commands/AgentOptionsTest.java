package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading an {@code agent} argument vector. */
public class AgentOptionsTest {

    @Test
    public void aVectorWithNoOptionsIsAllTask() {
        AgentOptions options = AgentOptions.parse(new String[] {"tidy", "the", "imports"});

        assertThat(options.taskDescription()).isEqualTo("tidy the imports");
        assertThat(options.maxSteps()).isEqualTo(AgentOptions.UNLIMITED);
        assertThat(options.timeoutSeconds()).isEqualTo(AgentOptions.UNLIMITED);
        assertThat(options.verbose()).isFalse();
        assertThat(options.preconfirmed()).isFalse();
        assertThat(options.warnings()).isEmpty();
    }

    @Test
    public void anAbsentVectorIsAnInvocationThatAsksForNothing() {
        AgentOptions options = AgentOptions.parse(null);

        assertThat(options.task()).isEmpty();
        assertThat(options.taskDescription()).isEmpty();
        assertThat(options.warnings()).isEmpty();
    }

    /** An option is an option wherever it is written, because the task is free text either way. */
    @Test
    public void optionsAreRecognizedAnywhereInTheVector() {
        AgentOptions options = AgentOptions.parse(
                new String[] {"-v", "tidy", "--max-steps", "9", "the", "imports", "-y", "-t", "45"});

        assertThat(options.taskDescription()).isEqualTo("tidy the imports");
        assertThat(options.maxSteps()).isEqualTo(9);
        assertThat(options.timeoutSeconds()).isEqualTo(45);
        assertThat(options.verbose()).isTrue();
        assertThat(options.preconfirmed()).isTrue();
    }

    @Test
    public void bothSpellingsOfAValueAreAccepted() {
        assertThat(AgentOptions.parse(new String[] {"--timeout=45", "t"}).timeoutSeconds()).isEqualTo(45);
        assertThat(AgentOptions.parse(new String[] {"-m=9", "t"}).maxSteps()).isEqualTo(9);
    }

    /**
     * A budget that cannot be read leaves the run unbudgeted, and says so.
     *
     * <p>The task is still runnable, and refusing to run it would answer a typo with nothing.</p>
     */
    @Test
    public void aValueThatIsNotAPositiveNumberIsReportedAndTheBudgetStaysUnset() {
        AgentOptions notANumber = AgentOptions.parse(new String[] {"-m", "soon", "t"});
        assertThat(notANumber.maxSteps()).isEqualTo(AgentOptions.UNLIMITED);
        assertThat(notANumber.taskDescription()).isEqualTo("t");
        assertThat(notANumber.warnings()).containsExactly("Invalid value for -m: 'soon'. Ignoring.");

        AgentOptions zero = AgentOptions.parse(new String[] {"--timeout", "0", "t"});
        assertThat(zero.timeoutSeconds()).isEqualTo(AgentOptions.UNLIMITED);
        assertThat(zero.warnings()).containsExactly("--timeout must be positive; ignoring '0'.");
    }

    @Test
    public void aFlagGivenWithoutItsValueIsReported() {
        AgentOptions options = AgentOptions.parse(new String[] {"tidy", "-t"});

        assertThat(options.timeoutSeconds()).isEqualTo(AgentOptions.UNLIMITED);
        assertThat(options.taskDescription()).isEqualTo("tidy");
        assertThat(options.warnings()).containsExactly("Option -t requires a value. Ignoring.");
    }

    @Test
    public void theTaskArrayIsTheCallersOwnCopy() {
        AgentOptions options = AgentOptions.parse(new String[] {"tidy", "imports"});

        options.task()[0] = "rm";

        assertThat(options.task()).containsExactly("tidy", "imports");
    }
}
