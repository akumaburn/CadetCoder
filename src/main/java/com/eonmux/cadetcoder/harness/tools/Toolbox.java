package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.belief.BeliefStoreException;
import com.eonmux.cadetcoder.harness.ledger.LedgerException;
import com.eonmux.cadetcoder.harness.model.ModelException;
import com.eonmux.cadetcoder.harness.model.ModelStoreException;
import com.eonmux.cadetcoder.harness.model.lang.ModelRuntimeException;
import com.eonmux.cadetcoder.harness.model.lang.ModelSyntaxException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The one way a deliberation reaches anything: a tool name, some arguments, and a string back.
 *
 * <h2>Why a mistake comes back as an answer and not as a failure</h2>
 *
 * <p>Every call here was written by a language model, so a mistyped argument, a version that does
 * not exist and a goal the model never defined are all ordinary events rather than exceptional
 * ones. Thrown, each of them ends a run that had everything it needed to recover; answered, each
 * costs one turn and tells the agent exactly what to write instead. What is deliberately not
 * caught is a spent budget -- the run is over and pretending otherwise would let it keep spending
 * -- and anything nobody anticipated, which is a defect in the harness and should be loud.</p>
 *
 * <h2>Why every call is written down</h2>
 *
 * <p>The transcript the agent sees is compacted as a run grows, so it is not a record of what the
 * run did. The log is: it survives compaction, it says what was refused as well as what ran, and it
 * is what a report at the end of a run is written from.</p>
 */
public final class Toolbox {

    /** What an answer starts with when the call was refused rather than run. */
    public static final String REFUSED = "refused: ";

    private final ToolSession    session;
    private final List<ToolCall> log = new ArrayList<>();

    /**
     * Builds a toolbox over one run.
     *
     * @param session the run
     */
    public Toolbox(ToolSession session) {
        if (session == null) {
            throw new IllegalArgumentException("a toolbox needs a run to work on");
        }
        this.session = session;
    }

    /** The run this toolbox works on. */
    public ToolSession session() {
        return session;
    }

    /** Every call this run has made, oldest first. */
    public List<ToolCall> log() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    /** The most recent call, or {@code null} when nothing has been called yet. */
    public ToolCall last() {
        return log.isEmpty() ? null : log.get(log.size() - 1);
    }

    /**
     * Where the world is, with everything standing about the run underneath it.
     *
     * <h2>Why the driver does not call {@code observe} for this</h2>
     *
     * <p>The driver has to put this in front of the agent twice over: once to start a run, and
     * again whenever it compacts the transcript out from under it. Neither is something the agent
     * asked for, so charging it a tool call would make the tally say the agent spent turns it never
     * spent, and would fill its own log with calls it did not make. This is the same text the
     * {@code observe} tool answers with; what differs is who is speaking.</p>
     *
     * @return the block the agent reads
     */
    public String observation() {
        return LedgerTools.observe(session);
    }

    /**
     * Where the run stands, without what can be seen.
     *
     * @return the standing facts, one to a line
     */
    public String status() {
        return StatusBlock.render(session);
    }

    /**
     * Runs one tool call.
     *
     * @param tool      what the agent asked for
     * @param arguments what it asked with
     * @return what to tell it, which may be a refusal
     * @throws com.eonmux.cadetcoder.harness.budget.BudgetExceededException if the run is over
     */
    public String dispatch(String tool, Map<String, Object> arguments) {
        session.budget().countToolCall();
        ToolSchema schema = ToolCatalog.of(tool);
        if (schema == null) {
            return refuse(tool, arguments, "there is no tool called " + tool + "; there is "
                                           + String.join(", ", ToolCatalog.names()));
        }
        try {
            return answer(tool, arguments, run(schema.name(), new ToolArgs(schema, arguments)));
        } catch (IllegalArgumentException | ModelException | ModelSyntaxException
                 | ModelRuntimeException | ModelStoreException | LedgerException
                 | BeliefStoreException | ToolException mistaken) {
            return refuse(tool, arguments, mistaken.getMessage());
        }
    }

    private String run(String tool, ToolArgs args) {
        return switch (tool) {
            case ToolName.OBSERVE -> LedgerTools.observe(session);
            case ToolName.LEDGER_TAIL -> LedgerTools.tail(session, args);
            case ToolName.LEDGER_GET -> LedgerTools.get(session, args);
            case ToolName.WRITE_MODEL -> ModelTools.write(session, args);
            case ToolName.CERTIFY -> ModelTools.certify(session, args);
            case ToolName.COVERAGE -> ModelTools.coverage(session, args);
            case ToolName.PLAN -> PlanTools.plan(session, args);
            case ToolName.SIMULATE -> PlanTools.simulate(session, args);
            case ToolName.DISCRIMINATE -> PlanTools.discriminate(session, args);
            case ToolName.FIT_PREDICATE -> PlanTools.fit(session, args);
            case ToolName.COMMIT -> ActTools.commit(session, args);
            case ToolName.RESET -> ActTools.reset(session, args);
            case ToolName.BELIEF_ADD -> BeliefTools.add(session, args);
            case ToolName.BELIEF_REFUTE -> BeliefTools.refute(session, args);
            case ToolName.BELIEF_QUESTION -> BeliefTools.question(session, args);
            case ToolName.BELIEF_RESOLVE -> BeliefTools.resolve(session, args);
            case ToolName.BELIEFS -> BeliefTools.all(session);
            case ToolName.NOTES_APPEND -> NoteTools.append(session, args);
            case ToolName.NOTES_READ -> NoteTools.read(session, args);
            case ToolName.MODELS -> ModelTools.list(session, args);
            case ToolName.MODEL_SOURCE -> ModelTools.source(session, args);
            case ToolName.BUDGET -> StatusBlock.budget(session);
            default -> throw new IllegalStateException("the catalog offers " + tool
                                                       + " and nothing here runs it");
        };
    }

    private String refuse(String tool, Map<String, Object> arguments, String reason) {
        return answer(tool, arguments, REFUSED + reason, true);
    }

    private String answer(String tool, Map<String, Object> arguments, String said) {
        return answer(tool, arguments, said, false);
    }

    private String answer(String tool, Map<String, Object> arguments, String said,
                          boolean refused) {
        log.add(new ToolCall(tool, arguments, said, refused));
        return said;
    }
}
