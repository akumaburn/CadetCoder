package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the three commands that rewrite a file make of the answer to "shall I apply this?".
 *
 * <p><b>The defect</b>: the answer was read by asking, in order, whether it contained "yes", then
 * "apply", then "no". The word "apply" is in the question, so it is in half the answers to it -- and
 * "no, don't apply that" contains it. The approving branch was tested first, so a refusal phrased
 * the way people actually refuse rewrote the file, and the branch that would have cancelled was
 * never reached. A destructive step has to read every way of refusing before any way of agreeing.</p>
 *
 * <p><b>What is locked here</b>: that a refusal cancels however it is phrased, including one that
 * quotes the question back; that an approval still applies; that an answer that is neither is asked
 * again rather than guessed at; and that the question itself is addressed to the person, since an
 * unrecognised confirmation is put to the model, which would then be approving a rewrite of
 * somebody's file on its own say-so.</p>
 */
public class ArefusalIsNeverReadAsConsentTest {

    private static final String BEFORE = "the original text";

    @Rule
    public TemporaryFolder folder = new ProjectFolder();

    @Before
    public void withSomebodyAtTheTerminal() {
        System.setProperty("cadet.interactive", "true");
    }

    @After
    public void forgetThat() {
        System.clearProperty("cadet.interactive");
    }

    @Test
    public void arefusalThatQuotesTheQuestionStillRefuses() throws Exception {
        assertThat(whatHappensWhenTheAnswerIs("no, don't apply that"))
                .isEqualTo(BEFORE);
    }

    @Test
    public void aplainRefusalRefuses() throws Exception {
        assertThat(whatHappensWhenTheAnswerIs("no")).isEqualTo(BEFORE);
    }

    @Test
    public void anApprovalStillApplies() throws Exception {
        assertThat(whatHappensWhenTheAnswerIs("yes")).isEqualTo("the edited text");
    }

    @Test
    public void anAnswerToSomeOtherQuestionChangesNothingAndIsAskedAgain() throws Exception {
        Path               file   = aFileSaying(BEFORE);
        IterativeCommand.StepResult result = answering(file, "what does it do at the moment?");

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(BEFORE);
        assertThat(result.getNextPrompt()).contains("'yes'");
    }

    /**
     * The question is the user's to answer.
     *
     * <p>Left undeclared it is read by phrasing, and the phrasing test does not recognise
     * {@code (yes/no/modify)} -- so the one confirmation in this tool that authorises rewriting a
     * file was the one put to the model.</p>
     */
    @Test
    public void thequestionIsPutToThePersonAndNotToTheModel() throws Exception {
        Path file = aFileSaying(BEFORE);
        TestOutputCapture quiet = new TestOutputCapture();
        IterativeCommand.StepResult asked;
        try {
            asked = preview(file);
        } finally {
            quiet.restore();
        }

        assertThat(asked.getNextPrompt()).contains("apply these edits");
        assertThat(new UserAsk(true).requiresUserInput(asked))
                .as("a confirmation for a rewrite is never answered by the model")
                .isTrue();
    }

    /** The file's contents after the preview has been answered with {@code reply}. */
    private String whatHappensWhenTheAnswerIs(String reply) throws Exception {
        Path file = aFileSaying(BEFORE);
        answering(file, reply);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Runs the preview step, then answers it. */
    private IterativeCommand.StepResult answering(Path file, String reply) throws Exception {
        TestOutputCapture quiet = new TestOutputCapture();
        try {
            IterativeCommand.StepResult asked = preview(file);
            return new MultiEditCommand().executeStep(argsFor(file), asked.getContext(), reply);
        } finally {
            quiet.restore();
        }
    }

    /** The run at the point where the edits have been worked out and are waiting to be approved. */
    private IterativeCommand.StepResult preview(Path file) {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "prepare_edits");
        context.put("args", argsFor(file));
        context.put("filePath", file.toString());
        return new MultiEditCommand().executeStep(argsFor(file), context, null);
    }

    private static String[] argsFor(Path file) {
        return new String[] {file.toString(),
                             "EDIT_START\nOLD: the original text\nNEW: the edited text\n"
                             + "REPLACE_ALL: false\nEDIT_END"};
    }

    private Path aFileSaying(String text) throws IOException {
        Path file = folder.newFile("Widget.txt").toPath();
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
