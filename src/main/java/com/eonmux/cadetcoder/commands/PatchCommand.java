package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.patch.PatchApply;
import com.eonmux.cadetcoder.patch.PatchKind;
import com.eonmux.cadetcoder.patch.PatchParse;
import com.eonmux.cadetcoder.patch.PatchedFile;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.util.AtomicFileWrite;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applying a unified diff to the files it names.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A model that knows what change it wants writes a unified diff without being asked, because
 * that is the format it has seen most of. Before this, the change had to be restated as a
 * SEARCH/REPLACE block or as a whole rewritten file, and a whole file rewritten from memory loses
 * whatever the model did not think to repeat.</p>
 *
 * <h2>Why every file is worked out before any file is written</h2>
 *
 * <p>A patch is one change. Writing the first two files and then finding the third does not fit
 * leaves the project in a state nobody wrote and nobody asked for, and the caller cannot tell from
 * the result which files went in. So every file is read and every hunk placed first; the writes
 * happen only once all of them have somewhere to go. That covers files the patch adds and files it
 * removes as well as files it edits.</p>
 *
 * <h2>Why there is no confirmation question</h2>
 *
 * <p>{@code edit} asks one because it worked the change out itself and the caller has not seen it.
 * A patch is the opposite: the caller wrote out every line, including the exact lines each file is
 * expected to hold, and the patch is refused unless the file still holds them. That is a stronger
 * check than {@code write} makes, and {@code --dry-run} is the preview. So nothing here is applied
 * that the caller did not already state in full.</p>
 *
 * <h2>Why a file named twice is worked out once</h2>
 *
 * <p>A patch may carry two header pairs for the same file, which is what several differs write for
 * a change with a rename or a mode line between its parts. Working each section out against the
 * file as it is on disk and then writing them in turn left only the last section's work: the first
 * section's changes were computed, reported and thrown away. Each section after the first is worked
 * out against what the sections before it decided the file holds, and the file is written once.</p>
 */
@CommandLine.Command (name = "patch", description = "Apply a unified diff to the files it names")
public class PatchCommand extends LoggingCommandSupport implements CommandRegistry.Command {

    /** Options that take a value, so {@code --flag=value} and {@code --flag value} both work. */
    private static final Set<String> VALUED = Set.of("-f", "--file");

    private final SecurityValidator security = new SecurityValidator();

    /**
     * Why the file being worked out cannot be changed.
     *
     * <p>Held in a field because {@link #worked} answers two things at once -- what the change
     * comes to, and why it cannot be made -- and only one of them can be a return value. Set
     * immediately before the {@code null} that reports it, and read immediately after.</p>
     */
    private String refusal;

    @Override
    public int execute(String[] args) {
        try {
            startCommandLogging("patch", args);
            Options options = Options.read(CommandOptions.expandInlineValues(args, VALUED));
            if (options.patchText == null && options.patchFile == null) {
                return refuse("No patch was given.");
            }
            if (!options.dryRun && ReadOnlyGuard.blocks("apply a patch")) {
                return finish(1);
            }

            String text = options.patchText != null ? options.patchText : fromFile(options.patchFile);
            if (text == null) {
                return finish(1);
            }

            List<PatchedFile> files;
            try {
                files = PatchParse.read(text);
            } catch (IllegalArgumentException malformed) {
                return refusedOutright(malformed.getMessage());
            }
            if (files.isEmpty()) {
                return refuse("That is no patch. A patch has a --- and +++ header pair and at "
                              + "least one @@ hunk.");
            }
            return applyAll(files, options.dryRun);
        } catch (RuntimeException unexpected) {
            logErrorQuietly("execute", "patch failed", unexpected);
            OutputFormatter.printError("Could not apply the patch: " + unexpected.getMessage());
            return finish(1);
        }
    }

    /**
     * Works out every file, then writes them.
     *
     * @param files  what the patch changes
     * @param dryRun whether to stop after working it out
     * @return the exit code
     */
    private int applyAll(List<PatchedFile> files, boolean dryRun) {
        Map<Path, Intended> intended = new LinkedHashMap<>();
        for (PatchedFile file : files) {
            Path   target = ProjectFile.at(file.path());
            String why    = ProjectFile.reasonNotToWrite(target, security);
            if (why != null) {
                return failed(file.path(), why);
            }
            // A hunk is context lines plus changes, and a model that has not read the file writes
            // those context lines from memory: the hunk does not fit, and the turn is spent on a
            // patch that changed nothing. A file the patch CREATES is exempt, there being nothing
            // to have read. See ReadBeforeEdit.
            if (file.kind() != PatchKind.CREATE) {
                String unread = ReadBeforeEdit.reasonNotToChange(target, "patch");
                if (unread != null) {
                    return failed(file.path(), unread);
                }
            }
            // Keyed by the resolved path, so a second section naming the same file continues from
            // what the first one left rather than from what is still on disk.
            Intended one = worked(file, target, intended.get(target));
            if (one == null) {
                return failed(file.path(), refusal);
            }
            intended.put(target, one);
        }
        List<Intended> inOrder = List.copyOf(intended.values());

        reportOffsets(inOrder);
        if (dryRun) {
            OutputFormatter.printSuccess("The patch would " + described(inOrder) + ".");
            return finish(0);
        }
        return carryOut(inOrder);
    }

    /**
     * Says where a hunk went in when it did not go in where the patch said.
     *
     * <p>A hunk located by its lines rather than by its number is a guess, and a good one, but the
     * caller wrote a line number and is entitled to know it was not the one used. {@code git apply}
     * reports the same thing the same way.</p>
     */
    private static void reportOffsets(List<Intended> intended) {
        for (Intended one : intended) {
            for (String offset : one.offsets()) {
                OutputFormatter.printWarning(one.name() + ": " + offset);
            }
        }
    }

    /**
     * What one file's change comes to, or {@code null} having recorded why it cannot happen.
     *
     * <p>Every kind is worked out here and nothing is written, because a patch is one change: a
     * creation that cannot happen has to stop the edit beside it.</p>
     *
     * @param sofar what an earlier section of the same patch already settled this file holds, or
     *              {@code null} when this is the first section to name it
     */
    private Intended worked(PatchedFile file, Path target, Intended sofar) {
        return switch (file.kind()) {
            case CREATE -> created(file, target, sofar);
            case DELETE -> removed(file, target, sofar);
            case MODIFY -> modified(file, target, sofar);
        };
    }

    /** A file the patch adds. */
    private Intended created(PatchedFile file, Path target, Intended sofar) {
        PatchApply.Result wholeFile = PatchApply.to("", file.hunks());
        if (!wholeFile.applied()) {
            return cannot(wholeFile.reason());
        }
        boolean standingThere = sofar == null ? Files.exists(target) : sofar.text() != null;
        if (standingThere) {
            return inTheWay(wholeFile.text(),
                            sofar == null ? currentTextOf(target) : sofar.text());
        }
        return new Intended(target, sofar != null && sofar.wasThere(), wholeFile.text(),
                            alsoReported(sofar, wholeFile.offsets()));
    }

    /** Why a file the patch adds cannot be added over whatever is standing in its place. */
    private Intended inTheWay(String wanted, String standing) {
        if (wanted.equals(standing)) {
            return cannot("It is already applied: the file is already exactly what the patch"
                          + " adds.");
        }
        return cannot("It already exists and holds something else. A patch that adds a file"
                      + " will not replace one.");
    }

    /** A file the patch removes. */
    private Intended removed(PatchedFile file, Path target, Intended sofar) {
        boolean gone = sofar == null ? !Files.exists(target) : sofar.text() == null;
        if (gone) {
            return cannot("It is already applied: the file is already gone.");
        }
        String current = sofar == null ? currentTextOf(target) : sofar.text();
        if (current == null) {
            return cannot("It could not be read, so there is no telling whether the patch fits.");
        }
        PatchApply.Result emptied = PatchApply.to(current, file.hunks());
        if (!emptied.applied()) {
            return cannot(emptied.reason());
        }
        if (!emptied.text().isEmpty()) {
            // The headers say the file goes and the hunks do not account for all of it. Deleting
            // anyway would discard lines nobody wrote a patch for.
            return cannot("The patch removes the file but does not account for all of its lines."
                          + " Nothing was deleted.");
        }
        return new Intended(target, sofar == null || sofar.wasThere(), null,
                            alsoReported(sofar, emptied.offsets()));
    }

    /** A file the patch edits. */
    private Intended modified(PatchedFile file, Path target, Intended sofar) {
        if (sofar == null && !Files.isRegularFile(target)) {
            return cannot("There is no such file to patch.");
        }
        if (sofar != null && sofar.text() == null) {
            return cannot("An earlier part of this patch removes the file, so there is nothing"
                          + " left here to edit.");
        }
        String current = sofar == null ? currentTextOf(target) : sofar.text();
        if (current == null) {
            return cannot("It could not be read.");
        }
        PatchApply.Result applied = PatchApply.to(current, file.hunks());
        if (!applied.applied()) {
            return cannot(applied.reason());
        }
        return new Intended(target, sofar == null || sofar.wasThere(), applied.text(),
                            alsoReported(sofar, applied.offsets()));
    }

    /** Everything said about where this file's hunks went, the earlier sections included. */
    private static List<String> alsoReported(Intended sofar, List<String> offsets) {
        if (sofar == null || sofar.offsets().isEmpty()) {
            return offsets;
        }
        List<String> all = new ArrayList<>(sofar.offsets());
        all.addAll(offsets);
        return List.copyOf(all);
    }

    /** Does every worked-out change, reporting each one. */
    private int carryOut(List<Intended> intended) {
        List<String> done = new ArrayList<>();
        for (Intended one : intended) {
            try {
                one.carryOut();
                done.add(one.name());
            } catch (IOException impossible) {
                // Past this point some files are already changed. Saying which, and which is not,
                // is the only honest answer left.
                OutputFormatter.printError("Could not " + one.verb() + " " + one.target() + ": "
                                           + impossible.getMessage());
                OutputFormatter.printWarning("Already done: " + String.join(", ", done));
                return finish(1);
            }
        }
        OutputFormatter.printSuccess("Patched " + String.join(", ", done) + ".");
        return finish(0);
    }

    /** What a dry run would do, in one phrase. */
    private static String described(List<Intended> intended) {
        List<String> each = new ArrayList<>();
        for (Intended one : intended) {
            each.add(one.verb() + " " + one.name());
        }
        return String.join(", ", each);
    }

    /**
     * One file's change, worked out and not yet made.
     *
     * <h2>Why the state is held rather than the kind of change</h2>
     *
     * <p>A patch may name the same file in more than one section, and each section after the first
     * has to start from what the ones before it left. Holding what the file will end up saying --
     * {@code null} when it ends up gone -- is what the next section reads, and keeps the writes at
     * one per file however many sections named it.</p>
     *
     * @param target   the file
     * @param wasThere whether the file was there before the patch, which is what decides whether
     *                 this reads as adding a file or as editing one
     * @param text     what it should say afterwards, or {@code null} when the patch removes it
     * @param offsets  where any of its hunks went in other than where the patch said
     */
    private record Intended(Path target, boolean wasThere, String text, List<String> offsets) {

        private Intended {
            offsets = List.copyOf(offsets);
        }

        /** Makes the change. */
        void carryOut() throws IOException {
            if (text == null) {
                Files.deleteIfExists(target);
                return;
            }
            Path holding = target.getParent();
            if (!wasThere && holding != null) {
                Files.createDirectories(holding);
            }
            AtomicFileWrite.writeString(target, text);
            // Having written it, the run knows what is in it; see ReadBeforeEdit.
            ReadBeforeEdit.sawContents(target);
        }

        /** What this does, as a verb a sentence can be built round. */
        String verb() {
            if (text == null) {
                return "remove";
            }
            return wasThere ? "patch" : "add";
        }

        /** The file, as a person would name it. */
        String name() {
            return target.getFileName() == null ? target.toString()
                                                : target.getFileName().toString();
        }
    }

    /** The text of a file the patch changes, or {@code null} when it cannot be read. */
    private String currentTextOf(Path target) {
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The patch itself, read out of a file. */
    private String fromFile(String given) {
        Path file  = ProjectFile.at(given);
        String why = ProjectFile.reasonNotToTouch(file, security);
        if (why != null) {
            OutputFormatter.printError(why);
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            OutputFormatter.printError("Could not read the patch from " + given + ": "
                                       + unreadable.getMessage());
            return null;
        }
    }

    /** Records why a file cannot be changed, for the caller to report. */
    private Intended cannot(String why) {
        refusal = why;
        return null;
    }

    private int failed(String path, String why) {
        return refusedOutright(path + ": " + why);
    }

    /** Reports a patch that will not be applied at all, rather than one file of it. */
    private int refusedOutright(String why) {
        OutputFormatter.printError(why);
        OutputFormatter.printWarning("Nothing was written. A patch goes in whole or not at all.");
        return finish(1);
    }

    private int refuse(String message) {
        OutputFormatter.printError(message);
        OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
        return finish(1);
    }

    private int finish(int exitCode) {
        completeCommandLogging(exitCode);
        return exitCode;
    }

    /** What one invocation asked for. */
    private static final class Options {

        private String  patchText;
        private String  patchFile;
        private boolean dryRun;

        static Options read(String[] args) {
            Options options = new Options();
            if (args == null) {
                return options;
            }
            for (int i = 0; i < args.length; i++) {
                String token = args[i];
                if (token == null) {
                    continue;
                }
                // A patch is recognised by holding more than one line, not by what it starts
                // with. Its first line is "--- a/path", which reads as an option to anything that
                // asks only whether an argument begins with a dash.
                if (token.indexOf('\n') >= 0) {
                    options.patchText = token;
                } else if ("--dry-run".equals(token) || "-n".equals(token)) {
                    options.dryRun = true;
                } else if (("-f".equals(token) || "--file".equals(token)) && i + 1 < args.length) {
                    options.patchFile = args[++i];
                } else if (!token.startsWith("-") && options.patchText == null) {
                    options.patchText = token;
                }
            }
            return options;
        }
    }

    @Override
    public String getUsage() {
        return "patch <diff> [options]\n"
             + "  The diff is one argument, in unified format, as `diff` and `git diff` print it.\n"
             + "  -f, --file <path>  Read the patch from a file instead\n"
             + "  -n, --dry-run      Say whether it would apply, without writing anything\n"
             + "  Every file is worked out before any is written, so a patch that does not fit\n"
             + "  changes nothing at all.";
    }
}
