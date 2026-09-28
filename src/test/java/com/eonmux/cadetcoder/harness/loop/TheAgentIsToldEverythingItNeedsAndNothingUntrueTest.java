package com.eonmux.cadetcoder.harness.loop;

import com.eonmux.cadetcoder.harness.env.Corridor;
import com.eonmux.cadetcoder.harness.model.ModelContract;
import com.eonmux.cadetcoder.harness.model.lang.LanguageGuide;
import com.eonmux.cadetcoder.harness.tools.ToolCatalog;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt is the only place the agent learns what it may do, so anything untrue in it is a
 * failure the agent cannot recover from by observing harder.
 *
 * <p>A tool that exists but is not named is a capability nobody uses; a tool that is named but does
 * not exist is a turn spent on a refusal; a contract stated one way and enforced another is a model
 * refused for a rule nobody was told. Each test here is one of those, and each is answered by
 * deriving the prompt from what the harness actually does rather than restating it.</p>
 */
public class TheAgentIsToldEverythingItNeedsAndNothingUntrueTest {

    /** A name in backticks, which is how the prompt's own prose points at a tool. */
    private static final Pattern QUOTED = Pattern.compile("`([a-z_]+)`");

    private static String prompt() {
        return SystemPrompt.render(new Corridor());
    }

    @Test
    public void everyToolTheHarnessCanRunIsNamedInThePrompt() {
        String prompt = prompt();

        assertThat(ToolCatalog.names()).allSatisfy(tool ->
                assertThat(prompt).as("the prompt never mentions %s", tool).contains(tool));
    }

    /**
     * The reference this was built from named its tools in prose. A tool renamed in the catalog and
     * not in the prose sends the agent to a tool that no longer exists, and the harness answers with
     * a refusal instead of an observation.
     */
    @Test
    public void everyToolThePromptTellsTheAgentToUseIsOneTheToolboxHas() {
        List<String>  quoted = new ArrayList<>();
        Matcher       found  = QUOTED.matcher(SystemPrompt.preamble());
        while (found.find()) {
            quoted.add(found.group(1));
        }

        assertThat(quoted).isNotEmpty();
        assertThat(quoted).allSatisfy(name ->
                assertThat(ToolCatalog.of(name)).as("the prompt names %s as a tool", name)
                        .isNotNull());
    }

    @Test
    public void theContractThePromptStatesIsTheOneTheHarnessEnforces() {
        assertThat(prompt()).contains(ModelContract.render());
    }

    @Test
    public void theLanguageThePromptTeachesIsTheOneAModelIsRunIn() {
        assertThat(prompt()).contains(LanguageGuide.render());
    }

    @Test
    public void theCallFormatThePromptShowsIsTheOneTheHarnessReads() {
        assertThat(prompt()).contains(CallFormat.describe());
    }

    /**
     * The harness must never describe the world itself. Every sentence about the environment is one
     * the environment wrote, because a driver that explains the mechanics turns certification into a
     * tautology.
     */
    @Test
    public void theEnvironmentSpeaksForItself() {
        Corridor corridor = new Corridor();

        assertThat(SystemPrompt.render(corridor)).contains(corridor.describe());
    }

    /**
     * A model is a program in the harness's own language. The reference this was built from asked
     * for Python, and a prompt that still does produces models that are refused at load.
     */
    @Test
    public void noModelIsEverAskedForInSomeOtherLanguage() {
        assertThat(SystemPrompt.preamble()).doesNotContain("def ");
        assertThat(SystemPrompt.preamble().toLowerCase()).doesNotContain("python");
    }

    @Test
    public void theAgentIsToldHowToSayItHasFinishedAndHowToSayItIsStuck() {
        assertThat(SystemPrompt.preamble()).contains(SystemPrompt.DONE);
        assertThat(SystemPrompt.preamble()).contains(SystemPrompt.STUCK);
    }

    @Test
    public void aPromptCannotBeBuiltWithoutAWorldToDescribe() {
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> SystemPrompt.render(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
