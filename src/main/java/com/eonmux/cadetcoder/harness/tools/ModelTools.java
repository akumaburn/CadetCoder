package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.certify.Certificate;
import com.eonmux.cadetcoder.harness.model.ModelRegistry;
import com.eonmux.cadetcoder.harness.model.WorldModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Writing a theory down, and finding out what the ledger does to it.
 *
 * <h2>Why writing and replaying are one tool</h2>
 *
 * <p>A model that has been saved but not replayed is a model the agent will reach for, because it is
 * the latest one and nothing said otherwise. Certifying on the way in means there is no moment at
 * which a version exists without a verdict, and the answer that comes back is the verdict rather
 * than a receipt -- so the turn that writes a theory is also the turn that learns it is wrong.</p>
 */
final class ModelTools {

    /** How much of a model's source an answer shows before it stops being worth reading. */
    private static final int SOURCE_SHOWN = 8_000;

    private ModelTools() {
    }

    /** Saves a version and replays the whole ledger through it. */
    static String write(ToolSession session, ToolArgs args) {
        String     source = args.text("source");
        String     note   = args.text("note", "");
        WorldModel model  = session.registry().save(source, session.ledger().size(), note);

        Certificate  certificate = session.certify(model, false);
        List<String> lines       = new ArrayList<>();
        lines.add(session.registry().record(model.digest()).render());
        lines.add(certificate.render());
        lines.add(certificate.coverage().render());
        String epicycles = session.registry().epicycleWarning();
        if (epicycles != null) {
            lines.add("epicycles: " + epicycles);
        }
        return String.join(System.lineSeparator(), lines);
    }

    /** Replays the ledger through a version again. */
    static String certify(ToolSession session, ToolArgs args) {
        WorldModel  model       = session.model(args.text("model", ModelRegistry.LATEST));
        Certificate certificate = session.certify(model, args.flag("strict_grounding", false));
        return certificate.render() + System.lineSeparator() + certificate.coverage().render();
    }

    /** Which of a version's rules reality has never exercised. */
    static String coverage(ToolSession session, ToolArgs args) {
        WorldModel  model       = session.model(args.text("model", ModelRegistry.LATEST));
        Certificate certificate = session.certified(model, false);
        return certificate.summary() + System.lineSeparator() + certificate.coverage().render();
    }

    /** Every version ever written. */
    static String list(ToolSession session, ToolArgs args) {
        ModelRegistry registry = session.registry();
        if (registry.size() == 0) {
            return "no models have been written yet";
        }
        List<String> lines = new ArrayList<>();
        for (String digest : registry.digests()) {
            lines.add(registry.record(digest).render() + "  [" + verdict(session, digest) + "]");
        }
        String epicycles = registry.epicycleWarning();
        if (epicycles != null) {
            lines.add("epicycles: " + epicycles);
        }
        return String.join(System.lineSeparator(), lines);
    }

    /** A version as it was written. */
    static String source(ToolSession session, ToolArgs args) {
        String reference = args.text("model", ModelRegistry.LATEST);
        return Compact.clipped(session.registry().source(reference), SOURCE_SHOWN);
    }

    /**
     * What is known about a version, preferring the standing certificate to the filed one.
     *
     * <p>The copy on disk is evidence about a ledger that may since have moved, so it is reported as
     * what it is rather than as the answer to whether this version can be acted on.</p>
     */
    private static String verdict(ToolSession session, String digest) {
        Certificate standing = session.standing(digest);
        if (standing != null) {
            return standing.green() ? "green, current" : "RED, current";
        }
        Object filed = session.registry().certificate(digest);
        if (filed == null) {
            return "never certified";
        }
        return (Json.truthy(Json.at(filed, "green")) ? "green" : "RED") + ", stale";
    }
}
