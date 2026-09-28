package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.ai.AIClient;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.Completion;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.harness.loop.Reasoner;
import com.eonmux.cadetcoder.harness.loop.Reply;
import com.eonmux.cadetcoder.harness.loop.Transcript;
import com.eonmux.cadetcoder.harness.loop.Turn;
import com.eonmux.cadetcoder.harness.loop.TurnKind;

/**
 * The harness's thinking, done by whichever model CadetCoder is connected to.
 *
 * <h2>Why the conversation is flattened, and what that costs</h2>
 *
 * <p>A run is a conversation -- the driver says where the world is, the agent answers, tools answer
 * back -- and the request this tool sends carries one system prompt and one user prompt. So the
 * whole conversation is rendered into the user prompt, each turn under the name of whoever said it.
 * The attribution is not decoration: an agent that reads its own last reply as an instruction acts
 * on its own guess as though the world had confirmed it, and that is precisely the failure the
 * ledger exists to make impossible.</p>
 *
 * <h2>Why nothing is retried here</h2>
 *
 * <p>{@link AIManager} already applies the backend's retry policy and offers the person at the
 * terminal one more attempt after it. A second policy here would multiply the delay before a
 * revoked key is reported, and a backend failure is not a fact about the world the agent is
 * exploring -- writing one into the ledger as though it were would corrupt the only record the run
 * has.</p>
 */
public final class ModelReasoner implements Reasoner {

    /** What a run calls a reasoner whose backend has not been chosen yet. */
    public static final String UNCHOSEN = "the configured model";

    private static final String BREAK = System.lineSeparator() + System.lineSeparator();

    private final String    name;
    private final AIManager models;
    private final AIClient  model;

    /**
     * A reasoner backed by whichever model this tool is connected to at the time each question is
     * asked.
     *
     * @param name   what to call this reasoner in a log or an escalation notice
     * @param models what answers its questions
     */
    public ModelReasoner(String name, AIManager models) {
        this(name, models, null);
    }

    /**
     * @param name   what to call this reasoner in a log or an escalation notice
     * @param models what answers its questions
     * @param model  the one to ask, or {@code null} for whichever this tool is connected to
     */
    public ModelReasoner(String name, AIManager models, AIClient model) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a reasoner has to be callable something");
        }
        if (models == null) {
            throw new IllegalArgumentException("a reasoner needs something to do its thinking");
        }
        this.name   = name;
        this.models = models;
        this.model  = model;
    }

    /**
     * A reasoner backed by whichever model this tool is currently connected to.
     *
     * <p>Named after the active client so an escalation notice says which model was asked, and
     * {@link #UNCHOSEN} when selection has not happened yet -- the provider is chosen lazily, and
     * refusing to build a reasoner here would report an absent provider in a second place, in
     * different words from the one that already reports it actionably at the first request.</p>
     *
     * @return the reasoner
     */
    public static ModelReasoner active() {
        return active(AIManager.getInstance());
    }

    /**
     * The same reasoner, thinking through a manager the caller already has.
     *
     * @param models what answers its questions
     * @return the reasoner
     */
    public static ModelReasoner active(AIManager models) {
        AIClient client = models.getActiveClient();
        return new ModelReasoner(client == null ? UNCHOSEN : client.getModelName(), models);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Reply think(String system, Transcript transcript) {
        if (transcript == null || transcript.size() == 0) {
            throw new IllegalArgumentException("there has to be something for the model to answer");
        }
        Completion said =
                models.completeMeasured(model, new PromptData(system, render(transcript)));
        return new Reply(said.text(), said.tokensIn(), said.tokensOut());
    }

    /**
     * The conversation as one prompt, each turn under the name of whoever said it.
     *
     * @param transcript everything said so far, oldest first
     * @return what to send
     */
    static String render(Transcript transcript) {
        StringBuilder out = new StringBuilder();
        for (Turn turn : transcript.turns()) {
            if (out.length() > 0) {
                out.append(BREAK);
            }
            out.append(speaker(turn.kind())).append(System.lineSeparator()).append(turn.text());
        }
        return out.toString();
    }

    /**
     * What one kind of turn is called in the flattened conversation.
     *
     * <p>Second person for the agent's own turns, because they are read back to it: "YOU" is what
     * makes its earlier reply legible as something it said rather than something it was told.</p>
     */
    private static String speaker(TurnKind kind) {
        switch (kind) {
            case AGENT:
                return "YOU SAID";
            case ANSWER:
                return "THE TOOLS ANSWERED";
            case HARNESS:
            default:
                return "THE HARNESS SAID";
        }
    }
}
