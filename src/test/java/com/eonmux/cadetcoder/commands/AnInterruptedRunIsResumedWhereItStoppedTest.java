package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.harness.cadet.RunCutShort;
import com.eonmux.cadetcoder.harness.cadet.RunOutcome;
import com.eonmux.cadetcoder.harness.cadet.RunRecord;
import com.eonmux.cadetcoder.harness.cadet.RunRequest;
import com.eonmux.cadetcoder.harness.loop.RunResult;
import com.eonmux.cadetcoder.harness.loop.RunStatus;
import com.eonmux.cadetcoder.net.LLMException;
import com.eonmux.cadetcoder.resume.ResumeBriefing;
import com.eonmux.cadetcoder.session.ResumePoint;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.StubbedProvider;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run the user interrupts can be carried on with {@code resume}.
 *
 * <p>Typing "continue" starts a new run that knows nothing of the one before it, and a loop or an
 * uber-mode run holds more than a request: which pass it was on, what the pass had done, and how
 * many of uber mode's closing questions the work had passed. The interrupt saves that as the
 * session's resume point, and {@code resume} starts the same run again from it.</p>
 */
public class AnInterruptedRunIsResumedWhereItStoppedTest {

    private static final String LIST_FILES = "ACTION_START\nCOMMAND: ls\nARGS: .\n"
                                             + "REASON: see what is there\nACTION_END";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

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
        InterruptSignal.clear();
        SessionManager.getInstance().clearResumePoint();
    }

    private static ResumePoint saved() {
        return SessionManager.getInstance().getResumePoint().orElseThrow(
                () -> new AssertionError("the interrupt saved no resume point"));
    }

    private static List<String> userPrompts(StubbedProvider provider) {
        return provider.asked().stream().map(PromptData::getUserPrompt).toList();
    }

    @Test
    public void aChatSavesItsRequestAndWhatItDidWhenItIsInterrupted() {
        try (StubbedProvider ignored = StubbedProvider.interruptedAt(2, LIST_FILES)) {
            assertThat(new ChatCommand().execute(new String[] {"tidy the notes"}))
                    .isEqualTo(ExitCode.INTERRUPTED);
        }

        ResumePoint point = saved();
        assertThat(point.kind()).isEqualTo(ResumePoint.CHAT);
        assertThat(point.chat().request()).isEqualTo("tidy the notes");
        assertThat(String.join("\n", point.chat().transcript())).contains("Command executed: ls");
        assertThat(point.project()).isEqualTo(System.getProperty("user.dir"));
    }

    @Test
    public void aResumedChatIsShownTheRequestAndTheWorkBeforeTheInterrupt() {
        try (StubbedProvider ignored = StubbedProvider.interruptedAt(2, LIST_FILES)) {
            new ChatCommand().execute(new String[] {"tidy the notes"});
        }
        InterruptSignal.clear();

        try (StubbedProvider resumed = StubbedProvider.answering("SUCCESS: the notes are tidy")) {
            assertThat(new ResumeCommand().execute(new String[0])).isEqualTo(ExitCode.OK);

            String first = userPrompts(resumed).get(0);
            assertThat(first).contains("tidy the notes")
                             .contains("Command executed: ls")
                             .contains("The user interrupted")
                             .as("an action the interrupt stopped may be run again")
                             .contains(ResumeBriefing.STOPPED_IS_NOT_REFUSED);
            assertThat(userPrompts(resumed)).allMatch(
                    prompt -> prompt.contains(ResumeBriefing.STOPPED_IS_NOT_REFUSED));
        }
        assertThat(SessionManager.getInstance().getResumePoint())
                .as("a run that was carried on to its end leaves nothing to resume")
                .isEmpty();
    }

    @Test
    public void aResumedChatKeepsTheUberModeQuestionsItHadPassed() {
        com.eonmux.cadetcoder.config.Configuration.AiConfig ai =
                com.eonmux.cadetcoder.config.ConfigManager.getInstance().getConfig().getAi();
        boolean wasOn = ai.isUberMode();
        ai.setUberMode(true);
        try {
            int passed = UberMode.questionCount() - 1;
            SessionManager.getInstance().setResumePoint(ResumePoint.chat(
                    List.of("tidy the notes"),
                    new ResumePoint.Chat("tidy the notes", List.of("System: did some work"),
                                         passed)));

            try (StubbedProvider ignored = StubbedProvider.answering("SUCCESS: done")) {
                assertThat(new ResumeCommand().execute(new String[0])).isEqualTo(ExitCode.OK);
            }

            int last = UberMode.questionCount();
            assertThat(output.getOutput())
                    .contains("(" + last + " of " + last + ")")
                    .doesNotContain("(1 of " + last + ")");
        } finally {
            ai.setUberMode(wasOn);
        }
    }

    @Test
    public void aRunInterruptedAgainReplacesTheResumePoint() {
        try (StubbedProvider ignored = StubbedProvider.interruptedAt(2, LIST_FILES)) {
            new ChatCommand().execute(new String[] {"tidy the notes"});
        }
        InterruptSignal.clear();

        try (StubbedProvider ignored = StubbedProvider.interruptedAt(1, LIST_FILES)) {
            assertThat(new ResumeCommand().execute(new String[0])).isEqualTo(ExitCode.INTERRUPTED);
        }

        assertThat(saved().chat().request()).isEqualTo("tidy the notes");
        assertThat(String.join("\n", saved().chat().transcript()))
                .as("the work of the first attempt is still there for the next resume")
                .contains("Command executed: ls");
    }

    @Test
    public void aLoopCarriesOnFromTheInterruptedPassWithThePassesLeft() {
        // Pass 1 finishes. Pass 2 runs one action and is interrupted at its next request.
        try (StubbedProvider ignored = StubbedProvider.interruptedAt(3, "SUCCESS: pass one done",
                                                                     LIST_FILES)) {
            assertThat(new LoopCommand().execute(new String[] {"--times=3", "tidy", "up"}))
                    .isEqualTo(ExitCode.INTERRUPTED);
        }
        ResumePoint point = saved();
        assertThat(point.kind()).isEqualTo(ResumePoint.LOOP);
        assertThat(point.loop().pass()).isEqualTo(2);
        assertThat(point.loop().times()).isEqualTo(3);
        assertThat(point.loop().interruptedPass()).isNotNull();
        assertThat(point.loop().iterations())
                .as("the iteration the interrupted pass ran is counted")
                .isEqualTo(1);
        InterruptSignal.clear();

        try (StubbedProvider resumed = StubbedProvider.answering("SUCCESS: finished pass two",
                                                                 "SUCCESS: pass three done")) {
            assertThat(new ResumeCommand().execute(new String[0])).isEqualTo(ExitCode.OK);

            List<String> prompts = userPrompts(resumed);
            assertThat(prompts).hasSize(2);
            assertThat(prompts.get(0)).contains("pass 2 of 3").contains("Command executed: ls");
            assertThat(prompts.get(1)).contains("pass 3 of 3").contains("finished pass two");
            assertThat(prompts).noneMatch(prompt -> prompt.contains("pass 1 of 3"));
        }
    }

    @Test
    public void aChatCutOffFromTheModelPartWaySavesWhatItDid() {
        try (StubbedProvider ignored = StubbedProvider.refusingAt(2, LIST_FILES)) {
            assertThat(new ChatCommand().execute(new String[] {"tidy the notes"}))
                    .isEqualTo(ExitCode.UNREACHABLE);
        }

        assertThat(String.join("\n", saved().chat().transcript())).contains("Command executed: ls");
    }

    @Test
    public void aChatCutOffBeforeItDidAnythingLeavesTheEarlierPointAlone() {
        SessionManager.getInstance().setResumePoint(ResumePoint.loop(
                ResumePoint.LOOP, List.of("--times=2", "tidy", "up"),
                new ResumePoint.Loop(2, 2, 3, null, null)));

        try (StubbedProvider ignored = StubbedProvider.refusingAt(1, "never asked for")) {
            assertThat(new ChatCommand().execute(new String[] {"something else"}))
                    .isEqualTo(ExitCode.UNREACHABLE);
        }

        assertThat(saved().kind()).isEqualTo(ResumePoint.LOOP);
    }

    @Test
    public void aResumedLoopCutOffFromTheModelSavesThePassItWasOn() {
        SessionManager.getInstance().setResumePoint(ResumePoint.loop(
                ResumePoint.LOOP, List.of("--times=3", "tidy", "up"),
                new ResumePoint.Loop(2, 3, 4, "pass one said this", null)));

        try (StubbedProvider ignored = StubbedProvider.refusingAt(2, LIST_FILES)) {
            assertThat(new ResumeCommand().execute(new String[0])).isEqualTo(ExitCode.UNREACHABLE);
        }

        ResumePoint point = saved();
        assertThat(point.loop().pass()).isEqualTo(2);
        assertThat(point.loop().interruptedPass()).isNotNull();
        assertThat(String.join("\n", point.loop().interruptedPass().transcript()))
                .contains("Command executed: ls");
    }

    @Test
    public void secretsInTheRunAreNotSaved() {
        String key = "sk-live0123456789abcdef";
        try (StubbedProvider ignored = StubbedProvider.interruptedAt(2, LIST_FILES)) {
            new ChatCommand().execute(new String[] {"deploy with the key " + key});
        }

        ResumePoint point = saved();
        assertThat(point.chat().request()).doesNotContain(key);
        assertThat(point.chat().transcript()).noneMatch(entry -> entry.contains(key));
        assertThat(point.arguments()).noneMatch(argument -> argument.contains(key));
    }

    @Test
    public void aLoopInterruptedBetweenPassesStartsTheNextPass() {
        SessionManager.getInstance().setResumePoint(ResumePoint.loop(
                ResumePoint.LOOPFRESH, List.of("--times=2", "tidy", "up"),
                new ResumePoint.Loop(2, 2, 5, "pass one said this", null)));

        try (StubbedProvider resumed = StubbedProvider.answering("SUCCESS: pass two done")) {
            assertThat(new ResumeCommand().execute(new String[0])).isEqualTo(ExitCode.OK);

            List<String> prompts = userPrompts(resumed);
            assertThat(prompts).hasSize(1);
            assertThat(prompts.get(0)).contains("pass 2 of 2")
                                      .as("loopfresh tells a pass nothing of the last")
                                      .doesNotContain("pass one said this");
        }
    }

    @Test
    public void anAgentIsResumedWithWhatItsRecordSaysItHadDone() throws Exception {
        Path record = folder.newFolder("20260101-000000-abc").toPath();
        Files.writeString(new RunRecord(record).notes(), "The bug is in Parser.readHeader.\n");
        RunOutcome calledOff = new RunOutcome(
                new RunResult(RunStatus.STOPPED, 2, 1, 3, 0, "stopped by the user"),
                new RunRecord(record));

        assertThat(agentAnswering(new AtomicReference<>(), calledOff)
                           .execute(new String[] {"-y", "-m", "9", "fix the parser"}))
                .isEqualTo(ExitCode.INTERRUPTED);
        ResumePoint point = saved();
        assertThat(point.kind()).isEqualTo(ResumePoint.AGENT);
        assertThat(point.agent().record()).isEqualTo(record.toString());

        AtomicReference<RunRequest> asked = new AtomicReference<>();
        RunOutcome finished = new RunOutcome(
                new RunResult(RunStatus.DONE, 1, 1, 2, 0, "fixed"), new RunRecord(record));
        int exit = new ResumeCommand(ChatCommand::new, () -> agentAnswering(asked, finished),
                                     WorkersCommand::new).execute(new String[0]);

        assertThat(exit).isEqualTo(ExitCode.OK);
        assertThat(asked.get().task()).startsWith("fix the parser")
                                      .contains("The bug is in Parser.readHeader.")
                                      .contains("The user interrupted")
                                      .contains(ResumeBriefing.STOPPED_IS_NOT_REFUSED);
        assertThat(asked.get().limits().maxDeliberations())
                .as("the options the run was started with still apply")
                .isEqualTo(9);
    }

    @Test
    public void anAgentInterruptedWhileTheModelAnsweredKeepsItsRecord() throws Exception {
        Path record = folder.newFolder("20260101-000000-cut").toPath();
        AgentCommand agent = new AgentCommand() {
            @Override
            protected RunOutcome underTheHarness(RunRequest request, boolean verbose) {
                InterruptSignal.request();
                throw new RunCutShort(new RunRecord(record),
                                      LLMException.stopped(null, "stub", null, null));
            }
        };

        assertThat(agent.execute(new String[] {"-y", "fix the parser"}))
                .isEqualTo(ExitCode.INTERRUPTED);

        assertThat(saved().agent().record()).isEqualTo(record.toString());
    }

    @Test
    public void anAgentInterruptedAgainKeepsWhatTheFirstRunDid() throws Exception {
        Path first = folder.newFolder("20260101-000000-one").toPath();
        Files.writeString(new RunRecord(first).notes(), "The bug is in Parser.readHeader.\n");
        Path second = folder.newFolder("20260101-000100-two").toPath();
        Files.writeString(new RunRecord(second).notes(), "The fix needs a test for empty headers.\n");
        SessionManager.getInstance().setResumePoint(ResumePoint.agent(
                List.of("-y", "fix the parser"),
                new ResumePoint.Agent(first.toString(), List.of(), null, 0)));

        RunOutcome calledOff = new RunOutcome(
                new RunResult(RunStatus.STOPPED, 1, 1, 2, 0, "stopped by the user"),
                new RunRecord(second));
        assertThat(new ResumeCommand(ChatCommand::new,
                                     () -> agentAnswering(new AtomicReference<>(), calledOff),
                                     WorkersCommand::new).execute(new String[0]))
                .isEqualTo(ExitCode.INTERRUPTED);

        AtomicReference<RunRequest> asked = new AtomicReference<>();
        RunOutcome finished = new RunOutcome(
                new RunResult(RunStatus.DONE, 1, 1, 2, 0, "fixed"), new RunRecord(second));
        new ResumeCommand(ChatCommand::new, () -> agentAnswering(asked, finished),
                          WorkersCommand::new).execute(new String[0]);

        assertThat(asked.get().task()).contains("The bug is in Parser.readHeader.")
                                      .contains("The fix needs a test for empty headers.");
    }

    @Test
    public void aResumedClassicAgentKeepsTheUberModeQuestionsItHadPassed() {
        com.eonmux.cadetcoder.config.Configuration.AiConfig ai =
                com.eonmux.cadetcoder.config.ConfigManager.getInstance().getConfig().getAi();
        boolean wasOn = ai.isUberMode();
        ai.setUberMode(true);
        try {
            SessionManager.getInstance().setResumePoint(ResumePoint.agent(
                    List.of("-y", "--classic", "tidy the notes"),
                    new ResumePoint.Agent(null, List.of("ls . -> exit 0: notes.md"), null,
                                          UberMode.questionCount() - 1)));

            try (StubbedProvider ignored = StubbedProvider.answering("TASK COMPLETE: done")) {
                new ResumeCommand().execute(new String[0]);
            }

            int last = UberMode.questionCount();
            assertThat(output.getOutput())
                    .contains("(" + last + " of " + last + ")")
                    .doesNotContain("(1 of " + last + ")");
        } finally {
            ai.setUberMode(wasOn);
        }
    }

    @Test
    public void thereIsNothingToResumeUntilARunIsInterrupted() {
        assertThat(new ResumeCommand().execute(new String[0])).isEqualTo(1);
        assertThat(output.getOutput()).contains("Nothing to resume");
    }

    @Test
    public void aRunFromAnotherProjectIsNotResumedHere() {
        ResumePoint elsewhere = new ResumePoint(ResumePoint.CHAT, "/somewhere/else",
                System.currentTimeMillis(), ResumePoint.THIS_PROCESS, List.of("tidy"),
                new ResumePoint.Chat("tidy", List.of(), 0), null, null, null, null, null);
        SessionManager.getInstance().setResumePoint(elsewhere);

        assertThat(new ResumeCommand().execute(new String[0])).isEqualTo(1);
        assertThat(output.getAllOutput()).contains("/somewhere/else");
        assertThat(SessionManager.getInstance().getResumePoint())
                .as("it is kept for when CadetCoder runs in that project")
                .isPresent();
    }

    @Test
    public void showSaysWhatWouldBeResumedAndDiscardForgetsIt() {
        SessionManager.getInstance().setResumePoint(ResumePoint.chat(List.of("tidy the notes"),
                new ResumePoint.Chat("tidy the notes", List.of(), 0)));

        assertThat(new ResumeCommand().execute(new String[] {"show"})).isEqualTo(ExitCode.OK);
        assertThat(output.getOutput()).contains("tidy the notes");
        assertThat(SessionManager.getInstance().getResumePoint()).isPresent();

        assertThat(new ResumeCommand().execute(new String[] {"discard"})).isEqualTo(ExitCode.OK);
        assertThat(SessionManager.getInstance().getResumePoint()).isEmpty();
    }

    @Test
    public void aModelCannotResumeARun() {
        assertThat(ModelDispatch.startsALoop("resume")).isTrue();
    }

    private static AgentCommand agentAnswering(AtomicReference<RunRequest> asked,
                                               RunOutcome answer) {
        return new AgentCommand() {
            @Override
            protected RunOutcome underTheHarness(RunRequest request, boolean verbose) {
                asked.set(request);
                return answer;
            }
        };
    }
}
