package com.eonmux.cadetcoder.harness.cadet;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An action is read twice before it happens -- once by the gate deciding whether it may, once by the
 * world doing it -- and the two readings have to agree.
 *
 * <h2>The defect these lock out</h2>
 *
 * <p>If the gate reads {@code {"command": "bash rm -rf src"}} as a command called
 * "bash rm -rf src" that it has never heard of, it classifies it as the safe middle and lets it
 * through; the world then splits the same string properly and deletes the tree. One reader, used by
 * both, is the only arrangement in which that cannot happen.</p>
 *
 * <h2>Why a malformed action is read rather than refused</h2>
 *
 * <p>These come from a language model, so they arrive in whatever shape it felt like. The canonical
 * shape has the name and the arguments apart; the shape a model reaches for most often is the whole
 * line in one string, and a single argument written as a bare string rather than a list of one is
 * the next most common. Reading those is not leniency, it is the difference between a run that works
 * and a run that spends its whole budget being corrected on punctuation. What is refused is only
 * what names nothing at all.</p>
 */
class AnActionIsReadTheSameWayEveryTimeTest {

    @Test
    void theCanonicalShapeKeepsTheNameAndTheArgumentsApart() {
        CommandInvocation read = CommandInvocation.from(action("read", List.of("pom.xml", "-n")));

        assertThat(read.name()).isEqualTo("read");
        assertThat(read.args()).containsExactly("pom.xml", "-n");
        assertThat(read.named()).isTrue();
    }

    @Test
    void aWholeLineInOneStringIsSplitTheWayAShellWouldSplitIt() {
        CommandInvocation read = CommandInvocation.from(action("read \"my notes.txt\" -n"));

        assertThat(read.name()).isEqualTo("read");
        assertThat(read.args()).containsExactly("my notes.txt", "-n");
    }

    @Test
    void oneArgumentWrittenAsAStringRatherThanAListOfOneIsStillOneArgument() {
        CommandInvocation read = CommandInvocation.from(action("read", "my notes.txt"));

        assertThat(read.name()).isEqualTo("read");
        assertThat(read.args()).containsExactly("my notes.txt");
    }

    @Test
    void argumentsThatAreNotTextArriveAsTheTextTheyWereWrittenAs() {
        CommandInvocation head = CommandInvocation.from(action("read", List.of("notes.txt", 40)));

        assertThat(head.args()).containsExactly("notes.txt", "40");
    }

    @Test
    void anActionNamingNothingNamesNothingRatherThanThrowing() {
        assertThat(CommandInvocation.from(null).named()).isFalse();
        assertThat(CommandInvocation.from("read pom.xml").named()).isFalse();
        assertThat(CommandInvocation.from(action("   ")).named()).isFalse();
        assertThat(CommandInvocation.from(new LinkedHashMap<String, Object>()).named()).isFalse();
        assertThat(CommandInvocation.from(action("   ")).args()).isEmpty();
    }

    @Test
    void theLineIsWhatWasAskedForWrittenBackOutInOnePiece() {
        assertThat(CommandInvocation.from(action("read", List.of("pom.xml"))).line())
                .isEqualTo("read pom.xml");
        assertThat(CommandInvocation.from(action("help")).line()).isEqualTo("help");
        assertThat(CommandInvocation.from(action("   ")).line()).isEmpty();
    }

    @Test
    void bothShapesOfTheSameRequestAreReadIntoTheSameInvocation() {
        CommandInvocation split = CommandInvocation.from(action("bash", List.of("rm", "-rf", "src")));
        CommandInvocation whole = CommandInvocation.from(action("bash rm -rf src"));

        assertThat(whole.name()).isEqualTo(split.name());
        assertThat(whole.args()).isEqualTo(split.args());
        assertThat(whole.line()).isEqualTo(split.line());
    }

    private static Map<String, Object> action(String command) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("command", command);
        return value;
    }

    private static Map<String, Object> action(String command, Object args) {
        Map<String, Object> value = action(command);
        value.put("args", args);
        return value;
    }
}
