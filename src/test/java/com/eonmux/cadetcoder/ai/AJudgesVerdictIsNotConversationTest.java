package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What an internal judge says stays out of the session conversation.
 *
 * <p><b>The defect, from a real session</b>: with {@code security.commandApproval} on {@code auto},
 * the safety gate is a model call, and so is the stand-in that answers a command's yes-or-no
 * questions on the user's behalf. Both were answered through the ordinary completion path, which
 * files every response into the session conversation as a turn of the model -- so one real session
 * carried ten of those verdicts among sixty-one entries: "AI: ALLOW: Read-only git command...",
 * "AI: ANSWER: yes BECAUSE ...". Indistinguishable from things its own model had said, and a
 * resumed run was handed them as its own prior words.</p>
 *
 * <p><b>What is locked here</b>: that a judge's reply reaches its caller and does not reach the
 * conversation history; that the ordinary path still records, so the fix cannot silence the
 * conversation model itself; and that the suppression is thread-scoped, since a judge answering on
 * a worker's thread must not silence another thread's recording.</p>
 */
public class AJudgesVerdictIsNotConversationTest {

    @Before
    public void startFromAnEmptyConversation() throws Exception {
        // The conversation is session state one JVM wide, and other tests in this fork record
        // turns of their own -- and a reset singleton reads back the session file the last run
        // wrote. Reset the singleton AND the file, so what is asserted here is the delta of each
        // call from a conversation that has recorded nothing.
        java.lang.reflect.Field instance = SessionManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(
                System.getProperty("user.home"), ".cadet", "session.json"));
    }

    private static AIClient clientThatAnswers(String reply) throws Exception {
        AIClient client = mock(AIClient.class);
        when(client.complete(any(PromptData.class), anyMap())).thenReturn(reply);
        when(client.isAvailable()).thenReturn(true);
        Mockito.lenient().when(client.deliversImages(any(PromptData.class))).thenReturn(false);
        return client;
    }

    private static List<String> history() {
        return SessionManager.getInstance().getConversationHistory();
    }

    @Test
    public void aJudgesReplyIsAnsweredButNotRecorded() throws Exception {
        AIClient judge = clientThatAnswers("ALLOW: Read-only git command that lists history.");

        String reply = AIManager.getInstance().completeUnrecorded(
                judge, new PromptData("be the safety gate", "git log --oneline"));

        assertThat(reply).isEqualTo("ALLOW: Read-only git command that lists history.");
        assertThat(history()).as("the verdict is nobody's conversational turn").isEmpty();
    }

    @Test
    public void theOrdinaryPathStillRecordsTheConversationModel() throws Exception {
        AIClient model = clientThatAnswers("The model's actual answer.");

        AIManager.getInstance().completeMeasured(model, new PromptData("s", "u"));

        assertThat(history()).containsExactly("AI: The model's actual answer.");
    }

    @Test
    public void silencingOneThreadDoesNotSilenceAnother() throws Exception {
        AIClient judge = clientThatAnswers("ALLOW: fine.");
        AIClient model = clientThatAnswers("The model's actual answer.");

        Thread judgeThread = new Thread(() -> {
            try {
                AIManager.getInstance().completeUnrecorded(
                        judge, new PromptData("be the safety gate", "git status"));
            } catch (RuntimeException failedOnItsOwnThread) {
                // The judge's failure is the judge's; what this test watches is the recording.
            }
        });
        judgeThread.start();
        judgeThread.join();

        AIManager.getInstance().completeMeasured(model, new PromptData("s", "u"));

        assertThat(history())
                .as("a judge on its own thread silences only itself")
                .containsExactly("AI: The model's actual answer.");
    }
}
