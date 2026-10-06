package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.StubbedProvider;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every request a chat sends begins with the whole of the request before it.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>A provider serves a prompt's leading tokens from its cache only when they are the same tokens it
 * saw before. The executor keeps an append-only transcript for exactly that reason, but the first
 * turn of a chat was built and sent by the chat step itself, and neither its prompt nor the model's
 * reply entered the transcript. The second request therefore opened with the result of the first
 * action, shared nothing with the first beyond the system prompt, and was billed in full. Measured
 * against Z.ai: the first follow-up of every chat cached only the system prompt.</p>
 *
 * <p>It also cost the model its own record. From the second turn on it was shown the action's result
 * but not the request as the user wrote it, nor the reply in which it chose that action.</p>
 */
public class EachChatTurnRepeatsTheTurnBeforeItTest {

    private static final String LIST_FILES = "ACTION_START\nCOMMAND: ls\nARGS: .\n"
                                             + "REASON: see what is there\nACTION_END";

    private TestOutputCapture output;

    @Before
    public void setUp() {
        SessionManager.getInstance().clearResumePoint();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        SessionManager.getInstance().clearResumePoint();
    }

    private static List<PromptData> askedDuring(String... replies) {
        try (StubbedProvider provider = StubbedProvider.answering(replies)) {
            assertThat(new ChatCommand().execute(new String[] {"list", "the", "files"}))
                    .isEqualTo(ExitCode.OK);
            return provider.asked();
        }
    }

    @Test
    public void eachRequestBeginsWithTheWholeOfTheOneBefore() {
        List<PromptData> asked = askedDuring(LIST_FILES, LIST_FILES.replace("ARGS: .", "ARGS: -l ."),
                                             "SUCCESS: listed");

        assertThat(asked).hasSizeGreaterThanOrEqualTo(3);
        for (int turn = 1; turn < asked.size(); turn++) {
            assertThat(asked.get(turn).getSystemPrompt())
                    .as("turn %d keeps the system prompt", turn + 1)
                    .isEqualTo(asked.get(0).getSystemPrompt());
            assertThat(asked.get(turn).getUserPrompt())
                    .as("turn %d begins with turn %d", turn + 1, turn)
                    .startsWith(asked.get(turn - 1).getUserPrompt());
        }
    }

    @Test
    public void theSecondTurnShowsTheRequestAndTheReplyThatChoseTheAction() {
        List<PromptData> asked = askedDuring(LIST_FILES, "SUCCESS: listed");

        assertThat(asked.get(1).getUserPrompt())
                .contains("User request: list the files")
                .contains("LLM: " + LIST_FILES)
                .contains("Command executed: ls");
    }
}
