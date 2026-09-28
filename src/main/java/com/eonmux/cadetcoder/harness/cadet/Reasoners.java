package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.ai.AIClient;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.ConnectorAIClient;
import com.eonmux.cadetcoder.harness.loop.Reasoner;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.OutputFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Who a run thinks with, weakest first.
 *
 * <h2>Why the tool does not choose the stronger model</h2>
 *
 * <p>A run that stops getting anywhere hands the transcript to the next reasoner in this list. Which
 * of two models is the stronger one is a fact about the account paying for them -- their prices, the
 * rate limits on them, and what the person actually wants spent on a task that has already stalled
 * -- so it is named in {@code ai.escalateTo} and never guessed. A tool that picked one would be
 * spending somebody's money on a judgement it has no way to make.</p>
 *
 * <h2>Why a hand-over that cannot happen is said out loud</h2>
 *
 * <p>A run with one reasoner and a run with two look identical from the outside until the plateau
 * arrives, and by then the escalation that was configured has silently not happened. Every way of
 * naming a model this tool cannot hand to -- no provider to run it on, a provider it does not know,
 * or the model already in use -- is reported when the run starts, while there is still something the
 * user can do about it.</p>
 */
public final class Reasoners {

    private Reasoners() {
    }

    /**
     * The reasoners this tool's configuration calls for.
     *
     * @return whichever model this tool is connected to, then whatever {@code ai.escalateTo} names
     */
    public static List<Reasoner> configured() {
        return from(ConfigManager.getInstance().getConfig().getAi(), AIManager.getInstance(),
                    OutputFormatter::printWarning);
    }

    /**
     * The same list, decided from a configuration and a manager the caller already has.
     *
     * @param ai     the AI configuration, or {@code null} when there is none
     * @param models what answers the reasoners' questions
     * @param report told why a configured hand-over is not in the list
     * @return the reasoners, weakest first, never empty
     */
    static List<Reasoner> from(Configuration.AiConfig ai, AIManager models, Consumer<String> report) {
        List<Reasoner> chain = new ArrayList<>();
        chain.add(ModelReasoner.active(models));

        String stronger = ai == null || ai.getEscalateTo() == null ? "" : ai.getEscalateTo().trim();
        if (stronger.isEmpty()) {
            return List.copyOf(chain);
        }
        if (stronger.equals(ai.getModel())) {
            report.accept("ai.escalateTo names " + stronger + ", which is the model already in use."
                          + " Handing a stalled run back to the model that stalled is not an"
                          + " escalation, so this run has one reasoner.");
            return List.copyOf(chain);
        }
        AIClient client = ConnectorAIClient.forModel(ai, stronger);
        if (client == null) {
            report.accept("ai.escalateTo names " + stronger + ", but ai.provider is "
                          + (ai.getProvider() == null || ai.getProvider().isBlank()
                             ? "not set" : "'" + ai.getProvider() + "', which is not a provider this"
                                           + " tool knows")
                          + ", so there is nowhere to ask it. This run cannot escalate.");
            return List.copyOf(chain);
        }
        chain.add(new ModelReasoner(client.getModelName(), models, client));
        return List.copyOf(chain);
    }
}
