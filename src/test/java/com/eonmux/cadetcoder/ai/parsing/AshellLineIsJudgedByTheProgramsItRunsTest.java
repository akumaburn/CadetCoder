package com.eonmux.cadetcoder.ai.parsing;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The screen on what the model proposes judges a shell line by the programs it runs, as the gate
 * that starts the process does.
 *
 * <h2>The defect</h2>
 *
 * <p>The screen matched every word of the line against the list of programs CadetCoder will not
 * run. {@code git rm --cached data.csv} names {@code rm} as a word, so the screen refused it,
 * although the gate that runs {@code bash} reads {@code git} as the program and allows the line.
 * In an unattended loop the model proposed that command, was refused, and the pass ended with the
 * file still tracked. The same held for {@code cat src/rm} and any other line where a listed name
 * appears as an argument.</p>
 *
 * <h2>What still stops a line</h2>
 *
 * <p>Every refusal the gate would make: a listed program however it is spelled or wrapped, a
 * protected path, a credential file. A line the gate only cannot read -- a script file, a program
 * built by expansion -- is not a violation here: the approval step at execution asks about it.</p>
 */
public class AshellLineIsJudgedByTheProgramsItRunsTest {

    private final SecurityValidator validator = new SecurityValidator();

    private SecurityValidator.ValidationResult screened(String line) {
        ParsedAction action = new ParsedAction.Builder("bash")
                .addParameter("command", line)
                .setReasoning("run it")
                .build();
        return validator.validateAction(action, new ParsingContext.Builder("do some work")
                .workingDirectory(System.getProperty("user.dir"))
                .build());
    }

    private boolean refused(String line) {
        return screened(line).getViolations().stream()
                .anyMatch(violation -> violation.startsWith(SecurityValidator.REFUSED_COMMAND));
    }

    @Test
    public void alistedNameThatIsOnlyAnArgumentDoesNotRefuseTheLine() {
        assertThat(refused("git rm --cached data.csv")).isFalse();
        assertThat(refused("git init")).isFalse();
        assertThat(refused("cat src/rm")).isFalse();
        assertThat(screened("git rm --cached data.csv").isValid()).isTrue();
    }

    @Test
    public void alistedProgramIsRefusedHoweverItIsWritten() {
        assertThat(refused("rm -rf /")).isTrue();
        assertThat(refused("/bin/rm target/out.txt")).isTrue();
        assertThat(refused("echo hi;rm -f a")).isTrue();
        assertThat(refused("env rm x")).isTrue();
        assertThat(refused("sudo systemctl restart nginx")).isTrue();
        assertThat(refused("bash -c 'rm -rf build'")).isTrue();
    }

    @Test
    public void therefusalSaysWhyInTheGatesWords() {
        assertThat(screened("git status && rm -rf build").getViolations())
                .anyMatch(violation -> violation.contains("'rm' is on the list of programs"));
    }

    @Test
    public void alineTheGateCannotReadIsLeftToTheApprovalStep() {
        SecurityValidator.ValidationResult result = screened("./deploy.sh");

        assertThat(refused("./deploy.sh")).isFalse();
        assertThat(result.getWarnings()).anyMatch(warning -> warning.contains("script file"));
    }
}
