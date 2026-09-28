package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.harness.cadet.RecordedRun;
import com.eonmux.cadetcoder.harness.cadet.RunRecord;
import com.eonmux.cadetcoder.harness.cadet.RunRecordException;
import com.eonmux.cadetcoder.harness.cadet.RunSummary;
import com.eonmux.cadetcoder.harness.ledger.LedgerException;
import com.eonmux.cadetcoder.harness.ledger.LedgerStats;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.model.ModelRecord;
import com.eonmux.cadetcoder.harness.model.ModelStoreException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Opens the records that {@code agent} runs leave behind.
 *
 * <h2>What this is for</h2>
 *
 * <p>A run under the harness writes down everything it established: what really happened, in a hash
 * chain that cannot be quietly edited; every version of the theory it held about the project and the
 * certificate each one earned; and whatever it wrote down for itself. All of it went into
 * {@code .cadet/runs} and nothing in the tool ever opened it again, so the evidence the harness
 * exists to produce could be reached only by someone who knew the layout. Evidence nobody can read
 * is not evidence.</p>
 *
 * <h2>Why it only reads</h2>
 *
 * <p>There is no {@code runs delete} and no {@code runs clean}. A record is the only account of what
 * an agent did to somebody's repository, and the value of an append-only chain is exactly that
 * nothing which can read it can also revise it. Records are ordinary directories under the project;
 * removing them is a job for the tools people already use to remove directories, where the decision
 * is unmistakably theirs.</p>
 */
@picocli.CommandLine.Command (name = "runs", description = "Read what past agent runs recorded")
public class RunsCommand implements CommandRegistry.Command {

    /** How wide a run's name column is: {@code yyyyMMdd-HHmmss} and a few characters of suffix. */
    private static final int NAME_WIDTH = 24;

    /** How wide the column that says how a run ended is. */
    private static final int STATE_WIDTH = 11;

    /** How much of one transition is shown before the rest is left for the ledger file. */
    private static final int TRANSITION_WIDTH = 200;

    /** How many transitions {@code runs ledger} prints when it is not told. */
    private static final int DEFAULT_TRANSITIONS = 20;

    /** How much of a run's notes is printed before the reader is sent to the file. */
    private static final int NOTES_LINES = 40;

    @Override
    public int execute(String[] args) {
        String asked = args == null || args.length == 0 ? "list"
                                                        : args[0].trim().toLowerCase(Locale.ROOT);
        try {
            return switch (asked) {
                case "list"   -> list();
                case "show"   -> show(argument(args, 1));
                case "ledger" -> ledger(argument(args, 1), argument(args, 2));
                default       -> unknown(asked);
            };
        } catch (RunRecordException | LedgerException | ModelStoreException failure) {
            OutputFormatter.printError(failure.getMessage());
            return 1;
        }
    }

    /**
     * The project whose runs are being read.
     *
     * <p>The directory this process was started in, which is what every other command in this tool
     * means by "the project". Overridable so a test can point one at a project it made.</p>
     */
    protected Path project() {
        return Paths.get(System.getProperty("user.dir"));
    }

    /** Every run this project has a record of, newest first. */
    private int list() {
        List<RecordedRun> found = RecordedRun.under(project());
        if (found.isEmpty()) {
            OutputFormatter.printInfo("No runs recorded yet. "
                                      + CommandUsage.render("agent \"<task>\"") + " records one.");
            return 0;
        }
        OutputFormatter.printHeader("Runs");
        for (RecordedRun run : found) {
            OutputFormatter.printInfo(String.format("  %-" + NAME_WIDTH + "s %-" + STATE_WIDTH
                                                    + "s %s", run.name(), state(run), about(run)));
        }
        OutputFormatter.printInfo(CommandUsage.render("runs show <name>") + " opens one; "
                                  + CommandUsage.render("runs ledger <name>")
                                  + " prints what really happened.");
        return 0;
    }

    /** One run in full: what it was asked for, what it came to, and what it established. */
    private int show(String reference) {
        Optional<RecordedRun> found = RecordedRun.named(project(), reference);
        if (found.isEmpty()) {
            return noSuchRun(reference);
        }
        RecordedRun run   = found.get();
        boolean     whole = true;
        OutputFormatter.printHeader("Run " + run.name());
        Optional<RunSummary> summary = run.summary();
        if (summary.isPresent()) {
            printIndented(summary.get().render());
        } else {
            OutputFormatter.printWarning("  " + run.problem()
                                                   .orElse("this record says nothing about itself"));
            whole = false;
        }
        whole &= showEvidence(run);
        whole &= showModels(run);
        showNotes(run);
        OutputFormatter.printInfo("  record: " + run.record().directory());
        return whole ? 0 : 1;
    }

    /**
     * What really happened, in the few numbers it comes to.
     *
     * <p>Reported rather than thrown, so that a run whose ledger was damaged still shows what it was
     * asked to do and where the rest of its record is. The failure is what the exit code is for.</p>
     *
     * @return whether it could be read
     */
    private boolean showEvidence(RecordedRun run) {
        try {
            Optional<LedgerStats> evidence = run.evidence();
            OutputFormatter.printInfo("  " + evidence.map(LedgerStats::render)
                                                     .orElse("ledger: nothing was recorded"));
            return true;
        } catch (LedgerException failure) {
            OutputFormatter.printError("  " + failure.getMessage());
            return false;
        }
    }

    /** @return whether the models this run wrote could be read */
    private boolean showModels(RecordedRun run) {
        try {
            List<ModelRecord> models = run.models();
            if (models.isEmpty()) {
                OutputFormatter.printInfo("  models: none were written");
                return true;
            }
            OutputFormatter.printInfo("  models: " + models.size()
                                      + (models.size() == 1 ? " version" : " versions"));
            for (ModelRecord model : models) {
                OutputFormatter.printInfo("    " + model.render());
            }
            return true;
        } catch (ModelStoreException failure) {
            OutputFormatter.printError("  " + failure.getMessage());
            return false;
        }
    }

    /** What the agent wrote down for itself, up to the point where a terminal stops being useful. */
    private void showNotes(RecordedRun run) {
        Optional<String> notes = run.notes();
        if (notes.isEmpty() || notes.get().isBlank()) {
            return;
        }
        String[] lines = notes.get().split("\n", -1);
        OutputFormatter.printSubheader("Notes");
        for (int at = 0; at < Math.min(lines.length, NOTES_LINES); at++) {
            OutputFormatter.println(lines[at]);
        }
        if (lines.length > NOTES_LINES) {
            OutputFormatter.printInfo("... " + (lines.length - NOTES_LINES) + " more lines in "
                                      + run.record().notes());
        }
    }

    /** The last few things that really happened, as the chain recorded them. */
    private int ledger(String reference, String howMany) {
        Optional<RecordedRun> found = RecordedRun.named(project(), reference);
        if (found.isEmpty()) {
            return noSuchRun(reference);
        }
        int count = DEFAULT_TRANSITIONS;
        if (howMany != null) {
            try {
                count = Math.max(1, Integer.parseInt(howMany.trim()));
            } catch (NumberFormatException notANumber) {
                OutputFormatter.printError("Not a number of transitions: " + howMany);
                return 1;
            }
        }
        RecordedRun           run      = found.get();
        Optional<LedgerStats> evidence = run.evidence();
        if (evidence.isEmpty()) {
            OutputFormatter.printInfo("This run recorded nothing: there is no ledger in "
                                      + run.record().directory());
            return 0;
        }
        List<Transition> tail = run.lastTransitions(count);
        OutputFormatter.printHeader("Ledger of " + run.name() + "  (last " + tail.size() + " of "
                                    + evidence.get().transitions() + ")");
        for (Transition transition : tail) {
            OutputFormatter.printInfo("  " + oneLine(transition.toString(), TRANSITION_WIDTH));
        }
        OutputFormatter.printInfo("  in full: " + run.record().ledger());
        return 0;
    }

    private int noSuchRun(String reference) {
        String wanted = reference == null || reference.isBlank() ? RecordedRun.LATEST
                                                                 : reference.strip();
        OutputFormatter.printError("There is no run called " + wanted + " under "
                                   + project().resolve(RunRecord.RUNS));
        OutputFormatter.printInfo(CommandUsage.render("runs") + " lists the ones there are.");
        return 1;
    }

    private int unknown(String asked) {
        OutputFormatter.printError("There is no `" + CommandUsage.prefix() + "runs " + asked + "`.");
        OutputFormatter.printInfo("Usage:\n" + CommandUsage.render(getUsage()));
        return 1;
    }

    /** How a run ended, in the width of a column. */
    private static String state(RecordedRun run) {
        if (run.problem().isPresent()) {
            return "unreadable";
        }
        return run.summary().map(summary -> summary.over() ? summary.status() : "unfinished")
                            .orElse("no summary");
    }

    /** What a run was about, or what is wrong with the record if it will not say. */
    private static String about(RecordedRun run) {
        return run.problem().orElseGet(
                () -> run.summary().map(summary -> flat(summary.task()))
                                   .orElse("this record says nothing about itself"));
    }

    private static void printIndented(String block) {
        for (String line : block.split("\n", -1)) {
            OutputFormatter.printInfo("  " + line);
        }
    }

    /** @return the text on one line, whole */
    private static String flat(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String oneLine(String text, int width) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= width ? flat : flat.substring(0, width - 3) + "...";
    }

    private static String argument(String[] args, int index) {
        return args != null && args.length > index ? args[index] : null;
    }

    @Override
    public String getUsage() {
        return "runs                          every run this project recorded, newest first\n"
               + "runs show [<name>]            one run in full; the newest when unnamed\n"
               + "runs ledger [<name>] [<n>]    the last n things that really happened\n"
               + "  A name is any unambiguous beginning of one, or `latest`.\n"
               + "  Records are written by `agent`, kept with the project and never changed\n"
               + "  afterwards; `runs show` names the directory one is in.";
    }
}
