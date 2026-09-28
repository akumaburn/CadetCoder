package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code job} action with a command on each line reaches the command as one job per line.
 *
 * <h2>The defect</h2>
 *
 * <p>The only way to start two jobs was two actions, one per step, so independent experiments ran
 * one after another. The catalog now shows the several-line form; this checks that the form the
 * model is shown survives the parser with every line, and every line's description, intact.</p>
 */
class AjobActionWithSeveralLinesStartsSeveralJobsTest {

    private final ActionBlockParser parser = new ActionBlockParser();

    private List<String> argv(String block) throws Exception {
        ParsedResponse response = parser.parse(block, new ParsingContext.Builder("run both").build());
        assertThat(response.getActions()).hasSize(1);
        ChatCommand.AIAction action = response.getActions().get(0).toLegacyAction();
        Field field = ChatCommand.AIAction.class.getDeclaredField("arguments");
        field.setAccessible(true);
        return Arrays.asList((String[]) field.get(action));
    }

    @Test
    void eachLineBecomesOneJobWithItsOwnDescription() throws Exception {
        List<String> argv = argv("ACTION_START\n"
                                 + "COMMAND: job\n"
                                 + "ARGS_BEGIN\n"
                                 + "start\n"
                                 + "-d \"fit with cap 60\" python fit.py --cap 60\n"
                                 + "-d \"fit with cap 120\" python fit.py --cap 120\n"
                                 + "ARGS_END\n"
                                 + "REASON: the two fits do not depend on each other\n"
                                 + "ACTION_END");

        assertThat(argv).containsExactly("start-each",
                                         "-d \"fit with cap 60\" python fit.py --cap 60",
                                         "-d \"fit with cap 120\" python fit.py --cap 120");
    }

    @Test
    void jobStartWrittenInFrontOfEachLineIsUnderstood() throws Exception {
        List<String> argv = argv("ACTION_START\n"
                                 + "COMMAND: job\n"
                                 + "ARGS_BEGIN\n"
                                 + "start\n"
                                 + "job start mvn -o -pl a test\n"
                                 + "job start mvn -o -pl b test\n"
                                 + "ARGS_END\n"
                                 + "REASON: separate modules\n"
                                 + "ACTION_END");

        assertThat(argv).containsExactly("start-each", "mvn -o -pl a test", "mvn -o -pl b test");
    }

    @Test
    void startWrittenOnTheCommandLineAndAgainInTheArgumentsIsOneStart() throws Exception {
        // The form a model used in a real run: the catalog's two-word name on the COMMAND line, and
        // the several-line form, which begins with `start`, under it. Joined, the second `start`
        // became a job of its own, which the safety check refused.
        List<String> argv = argv("ACTION_START\n"
                                 + "COMMAND: job start\n"
                                 + "ARGS_BEGIN\n"
                                 + "start\n"
                                 + "-d \"cand1\" python fit.py --cap 60\n"
                                 + "-d \"cand2\" python fit.py --cap 120\n"
                                 + "ARGS_END\n"
                                 + "REASON: two candidates\n"
                                 + "ACTION_END");

        assertThat(argv).containsExactly("start-each",
                                         "-d \"cand1\" python fit.py --cap 60",
                                         "-d \"cand2\" python fit.py --cap 120");
    }

    @Test
    void startWrittenTwiceOnOneLineIsOneStart() throws Exception {
        List<String> argv = argv("ACTION_START\n"
                                 + "COMMAND: job start\n"
                                 + "ARGS: start mvn -o test\n"
                                 + "REASON: the suite\n"
                                 + "ACTION_END");

        assertThat(argv).containsExactly("start", "mvn -o test");
    }

    @Test
    void acommandOnTheLineAfterStartIsStillOneJob() throws Exception {
        List<String> argv = argv("ACTION_START\n"
                                 + "COMMAND: job\n"
                                 + "ARGS_BEGIN\n"
                                 + "start\n"
                                 + "mvn -o test\n"
                                 + "ARGS_END\n"
                                 + "REASON: the suite\n"
                                 + "ACTION_END");

        assertThat(argv).containsExactly("start", "mvn -o test");
    }
}
