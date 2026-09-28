package com.eonmux.cadetcoder.harness.loop;

import com.eonmux.cadetcoder.harness.Json;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * There is no tool channel underneath this harness, so a call is text and has to be read as text.
 *
 * <p>The agent's whole reply arrives as one string. Reading it wrongly is not a parsing detail: an
 * argument that loses its braces is a model that will not compile, a call silently dropped is a
 * deliberation that repeats itself until the budget is gone, and a truncated reply that reads as
 * "no calls" is a loop that spins forever. Each test here is one of those failures.</p>
 */
public class ACallIsReadFromTheTextAnAgentWroteTest {

    private static ToolRequest onlyCall(String reply) {
        CallReading reading = CallFormat.read(reply);
        assertThat(reading.complaints()).isEmpty();
        assertThat(reading.calls()).hasSize(1);
        return reading.calls().get(0);
    }

    @Test
    public void aCallWithNoArgumentsIsReadFromTheTextAroundIt() {
        ToolRequest call = onlyCall("""
                I should look before I act.

                <call tool="observe"></call>
                """);

        assertThat(call.tool()).isEqualTo("observe");
        assertThat(call.arguments()).isEmpty();
    }

    @Test
    public void aCallThatClosesItselfIsStillACall() {
        assertThat(onlyCall("<call tool=\"observe\"/>").tool()).isEqualTo("observe");
    }

    @Test
    public void everythingOutsideACallIsTheAgentsOwnThinkingAndIsLeftAlone() {
        CallReading reading = CallFormat.read("""
                The corridor seems to run east. <call> is what I write to act.
                No call here.
                """);

        assertThat(reading.calls()).isEmpty();
        assertThat(reading.complaints()).isEmpty();
    }

    @Test
    public void anArgumentIsReadAsTheKindItsToolDeclares() {
        ToolRequest call = onlyCall("""
                <call tool="simulate">
                <arg name="model">latest</arg>
                <arg name="actions">[{"move": 1}, {"move": -1}]</arg>
                </call>
                """);

        assertThat(call.arguments().get("model")).isEqualTo("latest");
        assertThat(call.arguments().get("actions")).isInstanceOf(List.class);
        assertThat(Json.canonical(call.arguments().get("actions")))
                .isEqualTo("[{\"move\":1},{\"move\":-1}]");
    }

    /**
     * A list the agent wrote wrongly used to be dropped or coerced. Passing it on is what makes the
     * toolbox refuse it by name, which is the one answer that tells the agent what to write instead.
     */
    @Test
    public void aListArgumentThatIsNotJsonIsPassedOnRatherThanGuessedAt() {
        ToolRequest call = onlyCall("""
                <call tool="simulate">
                <arg name="actions">move east then north</arg>
                </call>
                """);

        assertThat(call.arguments().get("actions")).isEqualTo("move east then north");
    }

    /**
     * A model is a program: braces, quotes and newlines all the way down. This is the reason the
     * format is not JSON -- a whole model program escaped into a JSON string is what an LLM gets
     * wrong, and what it gets wrong here costs a whole deliberation.
     */
    @Test
    public void anArgumentKeepsEveryBraceAndQuoteOfAModelItCarries() {
        String source = """
                fn parse(obs) {
                    return {"pos": obs.pos};
                }""";

        ToolRequest call = onlyCall("<call tool=\"write_model\">\n<arg name=\"source\">\n"
                                    + source + "\n</arg>\n<arg name=\"note\">first</arg>\n</call>");

        assertThat(call.arguments().get("source")).isEqualTo(source);
        assertThat(call.arguments().get("note")).isEqualTo("first");
    }

    @Test
    public void severalCallsInOneReplyAreReadInTheOrderTheyWereWritten() {
        CallReading reading = CallFormat.read("""
                <call tool="observe"/>
                <call tool="beliefs"/>
                <call tool="budget"/>
                """);

        assertThat(reading.calls()).extracting(ToolRequest::tool)
                .containsExactly("observe", "beliefs", "budget");
    }

    /**
     * A reply cut off mid-call is the commonest failure of a text protocol. Read as "no calls" it
     * looks exactly like an agent that ended its turn, and the harness nudges it forever.
     */
    @Test
    public void aCallThatIsNeverClosedIsComplainedAboutRatherThanDropped() {
        CallReading reading = CallFormat.read("""
                <call tool="write_model">
                <arg name="source">fn parse(obs) { return obs; }
                """);

        assertThat(reading.calls()).isEmpty();
        assertThat(reading.complaints()).hasSize(1);
        assertThat(reading.complaints().get(0)).contains("</call>");
    }

    @Test
    public void anArgumentThatIsNeverClosedIsComplainedAboutRatherThanDropped() {
        CallReading reading = CallFormat.read("""
                <call tool="notes_append">
                <arg name="text">the door was locked
                </call>
                """);

        assertThat(reading.calls()).isEmpty();
        assertThat(reading.complaints()).hasSize(1);
        assertThat(reading.complaints().get(0)).contains("</arg>");
    }

    @Test
    public void aCallThatNamesNoToolIsComplainedAbout() {
        CallReading reading = CallFormat.read("<call>\n<arg name=\"n\">3</arg>\n</call>");

        assertThat(reading.calls()).isEmpty();
        assertThat(reading.complaints().get(0)).contains("tool=");
    }

    /**
     * An invented tool and an invented argument both belong to the toolbox to refuse: it names what
     * exists, and this reader has no business deciding what is valid.
     */
    @Test
    public void aToolThisHarnessHasNeverHeardOfIsPassedOnForTheToolboxToName() {
        ToolRequest call = onlyCall("""
                <call tool="teleport">
                <arg name="where">the end</arg>
                </call>
                """);

        assertThat(call.tool()).isEqualTo("teleport");
        assertThat(call.arguments().get("where")).isEqualTo("the end");
    }

    /**
     * Taking the last of two would write a model from the wrong half of the reply, and taking the
     * first would ignore the correction the agent wrote second.
     */
    @Test
    public void anArgumentGivenTwiceIsComplainedAboutRatherThanQuietlyTakingOne() {
        CallReading reading = CallFormat.read("""
                <call tool="write_model">
                <arg name="source">fn parse(obs) { return obs; }</arg>
                <arg name="source">fn parse(obs) { return obs.pos; }</arg>
                </call>
                """);

        assertThat(reading.calls()).isEmpty();
        assertThat(reading.complaints()).hasSize(1);
        assertThat(reading.complaints().get(0)).contains("source").contains("twice");
    }

    /** The example in the prompt is read by the same reader the agent's replies are. */
    @Test
    public void theExampleTheAgentIsShownIsOneThisReaderCanRead() {
        CallReading reading = CallFormat.read(CallFormat.describe());

        assertThat(reading.complaints()).isEmpty();
        assertThat(reading.calls()).isNotEmpty();
        assertThat(reading.calls()).allSatisfy(call ->
                assertThat(com.eonmux.cadetcoder.harness.tools.ToolCatalog.of(call.tool()))
                        .as("the example calls %s, which is not a tool", call.tool())
                        .isNotNull());
    }
}
