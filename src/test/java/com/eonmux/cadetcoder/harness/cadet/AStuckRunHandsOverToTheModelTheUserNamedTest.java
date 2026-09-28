package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.harness.loop.Reasoner;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A run that stops getting anywhere has to be able to hand over to something stronger.
 *
 * <h2>The defect</h2>
 *
 * <p>The harness has always been able to escalate: {@code Budget.plateau()} says when several
 * rounds of thinking have certified nothing new and fixed no mismatch, the driver hands the
 * transcript to the next reasoner with a note saying what it has walked into, and the record counts
 * the hand-offs. CadetCoder supplied exactly one reasoner, and the driver's guard is
 * {@code reasonerAt + 1 < reasoners.size()}, so none of that could ever fire. A whole designed path
 * -- and the plateau signal the status block prints -- was dead in the product.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Which model is stronger is not a judgement this tool may make on somebody's behalf, so the
 * chain is exactly what was configured: one model, or that one and then the one named. Every way of
 * naming a model that cannot actually be handed to is reported rather than dropped -- a run that
 * silently kept one reasoner would look identical to a run that had been given two, and the
 * difference is whether the escalation the user paid for exists.</p>
 */
class AStuckRunHandsOverToTheModelTheUserNamedTest {

    private final List<String> reported = new ArrayList<>();

    /** A configuration naming a provider, the model in use, and whatever to escalate to. */
    private static Configuration.AiConfig ai(String provider, String model, String escalateTo) {
        Configuration.AiConfig ai = new Configuration.AiConfig();
        ai.setProvider(provider);
        ai.setModel(model);
        ai.setEscalateTo(escalateTo);
        return ai;
    }

    private List<Reasoner> chainFor(Configuration.AiConfig ai) {
        return Reasoners.from(ai, mock(AIManager.class), reported::add);
    }

    @Test
    void aRunWithNobodyStrongerNamedThinksWithOneModel() {
        assertThat(chainFor(ai("openai", "gpt-4o-mini", ""))).hasSize(1);
        assertThat(reported).isEmpty();
    }

    @Test
    void theStrongerModelIsAskedSecondSoTheWeakerOneGetsFirstGo() {
        List<Reasoner> chain = chainFor(ai("openai", "gpt-4o-mini", "gpt-4o"));

        assertThat(chain).hasSize(2);
        assertThat(chain.get(1).name())
                .as("an escalation notice has to say which model took over")
                .contains("gpt-4o")
                .contains("openai");
        assertThat(reported).isEmpty();
    }

    @Test
    void escalatingToTheModelAlreadyInUseIsNotAnEscalationAndIsSaidSo() {
        assertThat(chainFor(ai("openai", "gpt-4o", "gpt-4o"))).hasSize(1);
        assertThat(reported).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                            .contains("gpt-4o")
                            .contains("ai.escalateTo");
    }

    @Test
    void aStrongerModelNamedWithNoProviderToRunItOnIsReportedRatherThanIgnored() {
        assertThat(chainFor(ai("", "gpt-4o-mini", "gpt-4o"))).hasSize(1);
        assertThat(reported).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                            .contains("ai.escalateTo")
                            .contains("ai.provider");
    }

    @Test
    void aStrongerModelNamedOnAProviderThisToolDoesNotKnowIsReportedRatherThanIgnored() {
        assertThat(chainFor(ai("a-provider-that-does-not-exist", "small", "large"))).hasSize(1);
        assertThat(reported).hasSize(1);
    }

    @Test
    void whitespaceWhereAModelNameWouldGoIsNoModelAtAll() {
        assertThat(chainFor(ai("openai", "gpt-4o-mini", "   "))).hasSize(1);
        assertThat(reported).isEmpty();
    }

    @Test
    void aRunWithNoConfigurationAtAllStillHasSomebodyToThinkWith() {
        assertThat(Reasoners.from(null, mock(AIManager.class), reported::add)).hasSize(1);
        assertThat(reported).isEmpty();
    }
}
