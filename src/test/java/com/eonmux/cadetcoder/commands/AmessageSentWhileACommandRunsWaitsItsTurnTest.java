package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.InterruptSignal;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lines sent from the shell while a command runs wait in a queue, in the order sent.
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the conversation the shell started takes the messages waiting at the front of the queue;
 * that it stops at the first line that has to wait for the command to end, so nothing is answered
 * out of order; that a conversation opened inside it, on another thread, or after a stop was asked
 * takes nothing; and that what is not taken is still there, in order, when the command ends.</p>
 */
class AmessageSentWhileACommandRunsWaitsItsTurnTest {

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        QueuedMessages.clear();
        InterruptSignal.clear();
        output = new TestOutputCapture();
        output.startCapture();
        QueuedMessages.ownedBy(Thread.currentThread());
    }

    @AfterEach
    void tearDown() {
        output.stopCapture();
        QueuedMessages.clear();
        InterruptSignal.clear();
    }

    private static QueuedMessages.Line message(String text) {
        return new QueuedMessages.Line(text, text, text, text);
    }

    private static QueuedMessages.Line command(String text) {
        return new QueuedMessages.Line(text, text, text, null);
    }

    @Test
    void theConversationTakesTheMessagesAtTheFrontInOrder() {
        QueuedMessages.add(message("also check the tests"));
        QueuedMessages.add(message("and the docs"));

        try (QueuedMessages.Conversation conversation = QueuedMessages.open()) {
            assertThat(conversation.take()).containsExactly("also check the tests", "and the docs");
            assertThat(conversation.take()).as("each is taken once").isEmpty();
        }
        assertThat(output.getAllOutput())
                .contains("Added to the conversation: also check the tests");
    }

    @Test
    void aLineThatMustWaitHoldsBackEverythingAfterIt() {
        QueuedMessages.add(message("first"));
        QueuedMessages.add(command("/ls"));
        QueuedMessages.add(message("after the listing"));

        try (QueuedMessages.Conversation conversation = QueuedMessages.open()) {
            assertThat(conversation.take()).containsExactly("first");
            conversation.delivered();
        }
        assertThat(QueuedMessages.next().typed()).isEqualTo("/ls");
        assertThat(QueuedMessages.next().typed()).isEqualTo("after the listing");
        assertThat(QueuedMessages.next()).isNull();
    }

    @Test
    void aConversationOpenedInsideTheFirstTakesNothing() {
        QueuedMessages.add(message("for the outer one"));

        try (QueuedMessages.Conversation outer = QueuedMessages.open();
             QueuedMessages.Conversation inner = QueuedMessages.open()) {
            assertThat(inner.take()).isEmpty();
            assertThat(outer.take()).containsExactly("for the outer one");
        }
    }

    @Test
    void aConversationOnAnotherThreadTakesNothing() throws Exception {
        QueuedMessages.add(message("for the shell's command"));
        AtomicReference<List<String>> taken = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try (QueuedMessages.Conversation conversation = QueuedMessages.open()) {
                taken.set(conversation.take());
            }
        });
        worker.start();
        worker.join();

        assertThat(taken.get()).isEmpty();
        assertThat(QueuedMessages.size()).isEqualTo(1);
    }

    @Test
    void nothingIsTakenOnceAStopIsAsked() {
        QueuedMessages.add(message("too late"));
        InterruptSignal.request();

        try (QueuedMessages.Conversation conversation = QueuedMessages.open()) {
            assertThat(conversation.take()).isEmpty();
        }
        assertThat(QueuedMessages.takeAll()).extracting(QueuedMessages.Line::typed)
                                            .containsExactly("too late");
    }

    @Test
    void aClosedConversationLetsTheNextOneTake() {
        QueuedMessages.add(message("for the second run"));
        try (QueuedMessages.Conversation first = QueuedMessages.open()) {
            assertThat(first).isNotSameAs(QueuedMessages.Conversation.NONE);
        }

        try (QueuedMessages.Conversation second = QueuedMessages.open()) {
            assertThat(second.take()).containsExactly("for the second run");
        }
    }

    /**
     * A message the model never read stays part of the run it joined: its transcript, its resume
     * point and the session hold it. Run again on its own as well, it would reach the model twice.
     */
    @Test
    void aMessageTheModelNeverReadIsReportedAndNotRunAgain() {
        QueuedMessages.add(message("also check the tests"));
        try (QueuedMessages.Conversation conversation = QueuedMessages.open()) {
            assertThat(conversation.take()).containsExactly("also check the tests");
            QueuedMessages.add(command("/ls"));
        }

        assertThat(QueuedMessages.takeAll()).extracting(QueuedMessages.Line::typed)
                                            .containsExactly("/ls");
        assertThat(output.getAllOutput())
                .contains("The run ended before the model read: also check the tests");
    }

    @Test
    void aMessageTheModelReceivedIsNotSentAgain() {
        QueuedMessages.add(message("also check the tests"));
        try (QueuedMessages.Conversation conversation = QueuedMessages.open()) {
            conversation.take();
            conversation.delivered();
        }

        assertThat(QueuedMessages.size()).isZero();
    }

    /**
     * The registry runs a command that can be interrupted on a thread of its own, so the shell's
     * command thread is never the one a chat or an agent run converses on.
     */
    @Test
    void aConversationTheRegistryRunsOnAThreadOfItsOwnStillTakes() {
        QueuedMessages.add(message("for the chat"));
        AtomicReference<List<String>> taken = new AtomicReference<>();
        CommandRegistry registry = new CommandRegistry();
        registry.register("probe", new CommandRegistry.InterruptibleCommand() {
            @Override
            public void setInterruptionContext(
                    CommandRegistry.InterruptionContext context) {
            }

            @Override
            public int execute(String[] args) {
                try (QueuedMessages.Conversation conversation = QueuedMessages.open()) {
                    taken.set(conversation.take());
                }
                return 0;
            }

            @Override
            public String getUsage() {
                return "probe";
            }
        });

        assertThat(registry.executeCommand("probe", new String[0], false)).isZero();

        assertThat(taken.get()).containsExactly("for the chat");
    }
}
