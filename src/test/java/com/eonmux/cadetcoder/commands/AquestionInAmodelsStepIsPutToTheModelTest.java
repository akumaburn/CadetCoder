package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.CommandApproval;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.OutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * In auto mode, a command's question during a model's step is answered by the model, separately.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code multiedit} asked the user to confirm its edits from inside a model's step, where nobody
 * sees the question. The log then said "No one to ask: declining confirmation (safe default)", "No
 * one can be asked from a worker; using default input 'no'" and "Applying without confirmation", in
 * that order, and applied the edit. With {@code security.commandApproval} at {@code auto} the model
 * answers such a question in a request of its own, as it already does for shell commands, and the
 * edit is applied or not by that answer.</p>
 *
 * <h2>Why a malformed edit block is refused and not guessed at</h2>
 *
 * <p>An edit whose EDIT_START block did not parse was handed to a second model call to work out
 * what was meant. That call was not given the file name, answered "The user wants to apply changes
 * to a file, but hasn't specified which file", and its reply -- token counts first -- became the
 * error the model read. The command now says what is wrong with the block.</p>
 */
class AquestionInAmodelsStepIsPutToTheModelTest {

    @TempDir
    Path folder;

    private String originalWorkingDir;

    private TestOutputCapture       output;
    private MockedStatic<AIManager> managers;
    private AIManager               model;
    private Path                    file;

    @BeforeEach
    void setUp() throws Exception {
        // The folder is the project, as it is when a user works on their own code.
        originalWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", folder.toAbsolutePath().toString());
        ReadBeforeEdit.forgetEverything();
        model    = mock(AIManager.class);
        managers = mockStatic(AIManager.class);
        managers.when(AIManager::getInstance).thenReturn(model);
        file = folder.resolve("guide.md");
        Files.writeString(file, "# Guide\n\nvalue = 30\n");
        ReadBeforeEdit.sawContents(file);
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalWorkingDir);
        output.stopCapture();
        managers.close();
        System.clearProperty(CommandApproval.PROPERTY);
        ReadBeforeEdit.forgetEverything();
    }

    private int editByTheModel() {
        return ModelDispatch.run("multiedit", () -> new MultiEditCommand().execute(new String[] {
                file.toString(),
                "EDIT_START\nOLD: value = 30\nNEW: value = 45\nREPLACE_ALL: false\nEDIT_END"}));
    }

    @Test
    void inAutoModeTheEditIsAppliedWhenTheModelAgrees() throws Exception {
        System.setProperty(CommandApproval.PROPERTY, "auto");
        when(model.complete(any(PromptData.class)))
                .thenReturn("ANSWER: yes\nBECAUSE: the preview changes one value as asked.");

        assertThat(editByTheModel()).isZero();

        assertThat(Files.readString(file)).contains("value = 45");
        assertThat(output.getAllOutput())
                .contains("Answered by the model")
                .doesNotContain("Applying without confirmation")
                .doesNotContain("declining confirmation");
    }

    @Test
    void themodelIsShownTheWholeChangeItIsAskedToApprove() throws Exception {
        System.setProperty(CommandApproval.PROPERTY, "auto");
        String longLine = "value = 30 // " + "a comment that runs well past fifty characters ".repeat(3);
        Files.writeString(file, "# Guide\n\n" + longLine + "\n");
        ReadBeforeEdit.sawContents(file);
        when(model.complete(any(PromptData.class)))
                .thenReturn("ANSWER: yes\nBECAUSE: the change is shown whole.");

        int exit = ModelDispatch.run("multiedit", () -> new MultiEditCommand().execute(new String[] {
                file.toString(),
                "EDIT_START\nOLD: " + longLine + "\nNEW: value = 45\nREPLACE_ALL: false\nEDIT_END"}));

        assertThat(exit).isZero();
        ArgumentCaptor<PromptData> asked = ArgumentCaptor.forClass(PromptData.class);
        verify(model).complete(asked.capture());
        assertThat(asked.getValue().getUserPrompt())
                .contains("-" + longLine)
                .contains("+value = 45")
                .doesNotContain("...");
    }

    @Test
    void inAutoModeTheEditIsNotAppliedWhenTheModelRefuses() throws Exception {
        System.setProperty(CommandApproval.PROPERTY, "auto");
        when(model.complete(any(PromptData.class)))
                .thenReturn("ANSWER: no\nBECAUSE: the change is not what was asked for.");

        assertThat(editByTheModel()).isNotZero();

        assertThat(Files.readString(file)).contains("value = 30");
        assertThat(output.getAllOutput())
                .contains("the change is not what was asked for")
                .doesNotContain("Applying without confirmation");
    }

    @Test
    void anAnswerThatCannotBeReadDeclines() throws Exception {
        System.setProperty(CommandApproval.PROPERTY, "auto");
        when(model.complete(any(PromptData.class))).thenReturn("Sure, looks fine to me!");

        assertThat(editByTheModel()).isNotZero();

        assertThat(Files.readString(file)).contains("value = 30");
    }

    @Test
    void inManualModeTheModelIsNotAsked() throws Exception {
        System.setProperty(CommandApproval.PROPERTY, "manual");

        editByTheModel();

        verify(model, never()).complete(any(PromptData.class));
    }

    @Test
    void inAutoModeAnOverwriteIsPutToTheModelWithWhatItReplaces() throws Exception {
        System.setProperty(CommandApproval.PROPERTY, "auto");
        when(model.complete(any(PromptData.class)))
                .thenReturn("ANSWER: yes\nBECAUSE: the file was read and is being rewritten.");
        int[] exit = new int[1];

        List<String> transcript = OutputCapture.collect(() -> exit[0] = ModelDispatch.run(
                "write", () -> new WriteCommand().execute(new String[] {file.toString(), "new text"})));

        assertThat(exit[0]).isZero();
        assertThat(Files.readString(file)).isEqualTo("new text");
        assertThat(transcript).anySatisfy(line -> assertThat(line).contains("Answered by the model"));
        ArgumentCaptor<PromptData> asked = ArgumentCaptor.forClass(PromptData.class);
        verify(model).complete(asked.capture());
        assertThat(asked.getValue().getUserPrompt()).contains(file.toString());
    }

    @Test
    void inManualModeAnOverwriteNobodyCanSeeIsDeclined() throws Exception {
        System.setProperty(CommandApproval.PROPERTY, "manual");
        int[] exit = new int[1];

        OutputCapture.collect(() -> exit[0] = ModelDispatch.run(
                "write", () -> new WriteCommand().execute(new String[] {file.toString(), "new text"})));

        assertThat(exit[0]).isNotZero();
        assertThat(Files.readString(file)).contains("value = 30");
        verify(model, never()).complete(any(PromptData.class));
    }

    @Test
    void anEditThatCannotApplyIsRefusedBeforeTheModelIsAsked() throws Exception {
        System.setProperty(CommandApproval.PROPERTY, "auto");

        int exit = ModelDispatch.run("multiedit", () -> new MultiEditCommand().execute(new String[] {
                file.toString(),
                "EDIT_START\nOLD: value = 31\nNEW: value = 45\nREPLACE_ALL: false\nEDIT_END"}));

        assertThat(exit).isNotZero();
        verify(model, never()).complete(any(PromptData.class));
        assertThat(output.getAllOutput()).contains("No edits were applied");
    }

    @Test
    void amalformedEditBlockIsRefusedWithWhatIsWrongWithIt() throws Exception {
        int exit = ModelDispatch.run("multiedit", () -> new MultiEditCommand().execute(
                new String[] {file.toString(), "EDIT_START", "OLD:", "value", "=", "30", "NEW:",
                              "value", "=", "45", "EDIT_END"}));

        assertThat(exit).isNotZero();
        verify(model, never()).complete(any(PromptData.class));
        verify(model, never()).complete(any(PromptData.class), any());
        assertThat(output.getAllOutput()).contains("own line").contains("ARGS_BEGIN");
        assertThat(Files.readString(file)).contains("value = 30");
    }
}
