package com.eonmux.cadetcoder.ai.parsing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's success tally counts what it handed back, not what it had part-way through.
 *
 * <p><b>The defect</b>: the counters were bumped before the fallback-only demotion rewrote the
 * result as a format error. A model answering in prose was counted as a successful parse and then
 * reported to the caller as unparseable -- so ten prose answers in a row left the engine claiming a
 * hundred per cent success rate having parsed nothing. The counters are never put back, so the claim
 * stood for the rest of the process.</p>
 */
class AtallyCountsWhatWasHandedBackTest {

    private ResponseParsingEngine engine;

    @BeforeEach
    void setUp() {
        System.setProperty("cadet.test.mode", "true");
        engine = new ResponseParsingEngine();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("cadet.test.mode");
    }

    private int tally(String field) throws Exception {
        Field held = ResponseParsingEngine.class.getDeclaredField(field);
        held.setAccessible(true);
        return (int) held.get(engine);
    }

    @Test
    void prosethatIsReportedAsUnparseableIsNotCountedAsParsed() throws Exception {
        ParsedResponse answered = engine.parseResponse(
                "I had a look at the file and I think the loop is fine as it stands.",
                "check the loop");

        assertThat(answered.isSuccessful())
                .as("this is what the caller is told")
                .isFalse();
        assertThat(tally("totalParses")).isEqualTo(1);
        assertThat(tally("successfulParses"))
                .as("and this is what the engine says about itself; they must agree")
                .isZero();
    }

    @Test
    void anActionTheCallerCanRunIsCounted() throws Exception {
        String block = "ACTION_START\n"
                       + "COMMAND: read\n"
                       + "ARGS: README.md\n"
                       + "REASON: the task needs it\n"
                       + "ACTION_END";

        ParsedResponse answered = engine.parseResponse(block, "read the readme");

        assertThat(answered.isSuccessful()).isTrue();
        assertThat(tally("successfulParses")).isEqualTo(1);
    }

    @Test
    void asuccessRateIsNeverClaimedForParsesThatFailed() throws Exception {
        for (int i = 0; i < 5; i++) {
            engine.parseResponse("Nothing structured here, just talking.", "do something");
        }

        assertThat(tally("totalParses")).isEqualTo(5);
        assertThat(tally("successfulParses")).isZero();
    }
}
