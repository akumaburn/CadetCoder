package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.util.TextFiles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The walk itself: every file in scope, read and matched, with a record of what could not be.
 *
 * <h2>Why nothing here ends the walk</h2>
 *
 * <p>A search answers a question about a project, and a project always has something in it that
 * cannot be read -- a root-owned directory, a mount that hiccuped, a file in some encoding from
 * 1998. Each of those used to unwind the entire walk and discard every match found before it, so
 * the person got an I/O error where they had asked a question. Every failure here is recorded
 * against the path it happened to and the walk continues; {@link GrepReport} says at the end what
 * was missed, so an omission is never silent either.</p>
 *
 * <p>The same goes for what the walk chose not to look at: a subtree pruned for being hidden or
 * excluded, and a file the security rules refused, are each recorded and reported rather than
 * dropped. The claim above is only worth anything if it holds for the omissions this class decides
 * on as well as the ones the file system forces on it.</p>
 */
final class GrepSearch {

    private final GrepScope           scope;
    private final GrepMatching        matching;
    private final GrepContext         context;
    private final BooleanSupplier     interrupted;
    private final LoggingCommandSupport log;
    private final SecurityValidator   securityValidator = new SecurityValidator();

    private final List<Path> lossilyDecodedFiles    = new ArrayList<>();
    private final List<Path> unsearchableFiles      = new ArrayList<>();
    private final List<Path> unsearchableDirs       = new ArrayList<>();
    private final List<Path> prunedDirs             = new ArrayList<>();
    private final List<Path> refusedCredentialFiles = new ArrayList<>();
    private final List<Path> refusedUnsafePaths     = new ArrayList<>();

    /** Set the moment anything here gives up on the user's behalf; see {@link #wasCutShort()}. */
    private boolean cutShort;

    /**
     * @param scope       where to look
     * @param matching    what counts as a match
     * @param context     how many lines either side of a match to keep
     * @param interrupted whether the person at the terminal has asked for this to stop
     * @param log         where the walk narrates itself
     */
    GrepSearch(GrepScope scope, GrepMatching matching, GrepContext context,
               BooleanSupplier interrupted, LoggingCommandSupport log) {
        this.scope       = scope;
        this.matching    = matching;
        this.context     = context == null ? GrepContext.NONE : context;
        this.interrupted = interrupted;
        this.log         = log;
    }

    /** Files whose content is not valid UTF-8 and was re-read with replacement characters. */
    List<Path> lossilyDecodedFiles() {
        return lossilyDecodedFiles;
    }

    /** Files in scope that could not be searched at all. */
    List<Path> unsearchableFiles() {
        return unsearchableFiles;
    }

    /** Directories in scope that could not be listed, or not listed to the end. */
    List<Path> unsearchableDirs() {
        return unsearchableDirs;
    }

    /**
     * Directories the walk did not descend into because they are hidden or excluded.
     *
     * <p>Not a failure, which is why it is kept apart from the two lists above -- but an omission
     * all the same, and the one a caller is least likely to guess at, since nothing about
     * {@code --include "**}{@code /*.yml"} suggests that {@code .github} was never entered.</p>
     */
    List<Path> prunedDirectories() {
        return prunedDirs;
    }

    /** Files skipped because they hold credentials, whatever the globs said. */
    List<Path> refusedCredentialFiles() {
        return refusedCredentialFiles;
    }

    /** Files skipped because what they resolve to is not somewhere this tool reads from. */
    List<Path> refusedUnsafePaths() {
        return refusedUnsafePaths;
    }

    /**
     * Every file in scope that matches, and where in it.
     *
     * @param regexPattern what to look for
     * @return the matches, by file, in path order
     * @throws IOException if the walk itself cannot start
     */
    Map<Path, List<GrepMatch>> matches(Pattern regexPattern) throws IOException {
        log.logStep("Starting file tree walk", String.format("Start path: %s", scope.start()));
        Map<Path, List<GrepMatch>> results = new TreeMap<>();

        Tally tally = new Tally();
        Files.walkFileTree(scope.start(), EnumSet.noneOf(FileVisitOption.class), scope.maxDepth(),
                           visitor(regexPattern, results, tally));

        log.logDataProcessing("file_tree", "directories visited", tally.visitedDirs, 0);
        log.logDataProcessing("file_tree", "directories skipped", tally.skippedDirs, 0);
        log.logDataProcessing("file_tree", "files visited", tally.visitedFiles, 0);
        log.logDataProcessing("file_tree", "files processed", tally.processedFiles, 0);
        log.logDataProcessing("file_tree", "interruption checks", tally.interruptionChecks, 0);
        return results;
    }

    /** The visitor that does the walking, so {@link #matches} stays readable. */
    private SimpleFileVisitor<Path> visitor(Pattern regexPattern,
                                            Map<Path, List<GrepMatch>> results, Tally tally) {
        return new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                tally.visitedDirs++;
                // Asked every ten directories: often enough that a large tree stops promptly,
                // seldom enough that the check is not itself the cost of the walk.
                if (tally.visitedDirs % 10 == 0 && stopping(tally, "Directory traversal",
                                                            tally.visitedDirs + " directories")) {
                    return FileVisitResult.TERMINATE;
                }
                if (pruned(dir)) {
                    tally.skippedDirs++;
                    prunedDirs.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                tally.visitedFiles++;
                if (tally.visitedFiles % 50 == 0 && stopping(tally, "File traversal",
                                                             tally.visitedFiles + " files")) {
                    return FileVisitResult.TERMINATE;
                }
                if (inScope(file)) {
                    tally.processedFiles++;
                    searchOne(file, regexPattern, results);
                }
                return FileVisitResult.CONTINUE;
            }

            /**
             * Records an entry the walk could not open, and keeps walking.
             *
             * <p>{@link SimpleFileVisitor} rethrows what it is handed here, so one unreadable
             * subdirectory unwound the whole walk and discarded every match found before it.</p>
             */
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.logWarning("File tree walk", String.format("Could not open %s - %s", file, exc));
                if (Files.isDirectory(file)) {
                    unsearchableDirs.add(file);
                } else {
                    unsearchableFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            /** Records a directory whose listing failed part-way through, and keeps walking. */
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    log.logWarning("File tree walk",
                                   String.format("Could not finish reading %s - %s", dir, exc));
                    unsearchableDirs.add(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        };
    }

    /** Whether the person at the terminal has asked for this to stop, said once where it happened. */
    private boolean stopping(Tally tally, String stage, String reached) {
        tally.interruptionChecks++;
        if (!interrupted.getAsBoolean()) {
            return false;
        }
        cutShort = true;
        log.logStep(stage, "Interrupted by user after visiting " + reached);
        OutputFormatter.printWarning("Operation interrupted by user");
        return true;
    }

    /**
     * Whether the walk was stopped before it had seen everything.
     *
     * <p>The result of a search that ended early is a different thing from the result of one that
     * finished, and nothing used to carry the difference out of here: a walk terminated part-way
     * returned its partial map, and the caller reported "Found 37 matches in 12 files" -- or, when
     * the stop landed before the first match, "No matches found" -- for a search that had not
     * looked. Both were reported as success.</p>
     *
     * @return whether what was returned is all there was
     */
    boolean wasCutShort() {
        return cutShort;
    }

    /**
     * Whether a subdirectory is skipped.
     *
     * <p>Never the explicitly-provided start directory, even when it is hidden -- {@code grep foo -p
     * .github} names the directory it wants -- so only hidden and excluded SUBdirectories go.</p>
     */
    private boolean pruned(Path dir) {
        if (dir.equals(scope.start())) {
            return false;
        }
        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
        return com.eonmux.cadetcoder.util.ProjectTreeWalk.isPrunedName(name)
               && !namedByInclude(name);
    }

    /**
     * Whether the {@code --include} glob spells this directory's name out as a path segment.
     *
     * <h2>Why naming a directory lifts the pruning of it</h2>
     *
     * <p>Pruning is there so an ordinary search does not wade through {@code .git}, {@code target}
     * and {@code node_modules} on the way to the project's own files. It was never meant to
     * overrule a caller who asks for one of them by name, and {@code --include ".github/**"} is a
     * caller asking. A wildcard is not: {@code **}{@code /*.yml} says nothing about which
     * directories are wanted, so it still leaves the hidden ones alone -- and the skip is reported
     * either way, which is what tells the caller the directory is there to be named.</p>
     */
    private boolean namedByInclude(String name) {
        if (scope.includeGlob() == null || name.isEmpty()) {
            return false;
        }
        for (String segment : scope.includeGlob().split("[/\\\\]")) {
            if (segment.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Searches one file, degrading per file rather than per walk. */
    private void searchOne(Path file, Pattern regexPattern, Map<Path, List<GrepMatch>> results) {
        try {
            long              started = System.currentTimeMillis();
            List<GrepMatch>   found   = inFile(file, regexPattern);
            long              took    = System.currentTimeMillis() - started;

            if (!found.isEmpty()) {
                results.put(file, found);
            }
            // Only the files that were slow or unusually productive; the rest would drown the log.
            if (took > 10 || found.size() > 100) {
                log.logPerformance(String.format("File search: %s", file.getFileName()), took);
                if (found.size() > 100) {
                    log.logDataProcessing("file_search",
                                          String.format("matches in %s", file.getFileName()),
                                          found.size(), took);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A decoding failure surfaces as an UncheckedIOException raised from inside the stream's
            // terminal operation, so catching IOException alone let it escape the whole walk and
            // discard every match found so far.
            log.logWarning("File read error", String.format("Error reading file: %s - %s", file, e));
            unsearchableFiles.add(file);
        }
    }

    /**
     * Whether this file is one the caller asked about.
     *
     * <p>The globs are matched against the path relative to the search root so that a recursive
     * glob like {@code **}{@code /*.java} behaves as expected while a bare {@code *.java} still
     * matches a file directly under the root.</p>
     */
    private boolean inScope(Path file) {
        if (Files.isDirectory(file)) {
            return false;
        }
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false;
        }
        Path target = matchTarget(file, fileName);

        if (scope.include() != null && !scope.include().matches(target)) {
            return false;
        }
        if (scope.exclude() != null && scope.exclude().matches(target)) {
            return false;
        }
        // Asked last, so a file the caller's own globs had already ruled out is not reported as a
        // refusal it never came close to. Asked of the resolved path, because that is the file
        // whose bytes are about to be read.
        String resolved = resolved(file).toString();
        if (securityValidator.isSensitiveCredentialFile(resolved)) {
            refusedCredentialFiles.add(file);
            return false;
        }
        // The rule `read`, `write` and `ls` go through, and the only one that knows about protected
        // system locations and the project boundary. It was never asked here at all, so a search
        // was the one way into this tool that would open /etc/passwd and print it line by line.
        if (!securityValidator.isFileAccessAllowed(resolved)) {
            refusedUnsafePaths.add(file);
            return false;
        }
        // Recorded, not dropped. This used to be the first thing asked and it returned false in
        // silence, so a file in scope that could not be opened simply did not appear in the results
        // -- and an omission the user cannot see reads exactly like the pattern not being there.
        if (!Files.isReadable(file)) {
            unsearchableFiles.add(file);
            return false;
        }
        return true;
    }

    /**
     * The file with every symbolic link on the way to it followed.
     *
     * <h2>Why the security rules are asked about this and not about the path the walk holds</h2>
     *
     * <p>A symbolic link is a name in one place and content in another. The walk does not follow
     * links when it descends, so the path it hands out is the link's own, but {@code Files.lines}
     * follows one when it reads. Asked about the name, the denylist saw an ordinary file under the
     * search root and said yes to whatever the link pointed at.</p>
     *
     * @param file the path the walk reached
     * @return the real path, or the absolute path when the real one cannot be taken
     */
    private Path resolved(Path file) {
        try {
            return file.toRealPath();
        } catch (IOException e) {
            // A link with no target, or a path that moved mid-walk. The absolute form is the more
            // cautious of the two answers available, since it is what the caller's globs matched.
            log.logWarning("File access", String.format("Could not resolve %s - %s", file, e));
            return file.toAbsolutePath().normalize();
        }
    }

    /** The file relative to the search root when possible, otherwise the bare file name. */
    private Path matchTarget(Path file, Path fileName) {
        try {
            Path absolute = file.toAbsolutePath();
            if (absolute.startsWith(scope.start())) {
                Path relative = scope.start().relativize(absolute);
                if (relative.getNameCount() > 0 && !relative.toString().isEmpty()) {
                    return relative;
                }
            }
        } catch (IllegalArgumentException e) {
            // Different roots; fall through to the file-name fallback below.
            log.logWarning("Glob matching",
                           "Unable to relativize " + file + " against " + scope.start());
        }
        return fileName;
    }

    /**
     * Every matching line in one file.
     *
     * <p>Binary content is skipped, but a file that could not be READ is not skipped quietly: the
     * failure goes up to the visitor, which records it for the summary line. Swallowing it here made
     * the file vanish from the results and from the count of what was missed.</p>
     */
    private List<GrepMatch> inFile(Path file, Pattern regexPattern) throws IOException {
        if (!TextFiles.isTextFile(file)) {
            return new ArrayList<>();
        }
        // Strict UTF-8 first: unchanged fast path for the overwhelming majority of files.
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return collect(file, regexPattern, lines);
        } catch (UncheckedIOException e) {
            if (!decodingFailed(e.getCause())) {
                // A genuine I/O failure, not an encoding problem: let the per-file handler record it
                // rather than pretending the file was searched.
                throw e;
            }
            // isTextFile() has already sniffed out true binaries, so this is a text file in some
            // non-UTF-8 encoding. Re-read it with the malformed bytes replaced by U+FFFD so its
            // matches are still found instead of being lost.
            log.logWarning("File decoding",
                           String.format("Non-UTF-8 content in %s, re-reading with replacement "
                                         + "characters: %s", file, e.getCause()));
            List<GrepMatch> found = lossily(file, regexPattern);
            lossilyDecodedFiles.add(file);
            return found;
        }
    }

    /** Whether a cause is a character-decoding failure rather than a genuine I/O failure. */
    private static boolean decodingFailed(Throwable cause) {
        return cause instanceof MalformedInputException
               || cause instanceof UnmappableCharacterException;
    }

    /**
     * Re-reads a file that is not valid UTF-8, substituting U+FFFD for every undecodable byte so the
     * file can still be searched end to end. Only the affected characters are lost; every line is
     * still offered to the pattern.
     */
    private List<GrepMatch> lossily(Path file, Pattern regexPattern) throws IOException {
        CharsetDecoder decoder = TextFiles.lossyUtf8Decoder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder));
             Stream<String> lines = reader.lines()) {
            return collect(file, regexPattern, lines);
        }
    }

    /**
     * Collects the matching lines from an already-open line stream. Shared by the strict UTF-8 read
     * and the replacement-character re-read so both apply identical matching rules.
     */
    private List<GrepMatch> collect(Path file, Pattern regexPattern, Stream<String> lines) {
        GrepLines     kept       = new GrepLines(wantedContext());
        AtomicInteger lineNumber = new AtomicInteger(0);
        AtomicInteger processed  = new AtomicInteger(0);

        // takeWhile so that an interruption genuinely short-circuits the stream instead of letting
        // every remaining line be fed through the lambda. Once the predicate returns false the
        // stream stops feeding lines; the matches collected so far are still returned below.
        AtomicBoolean stoppedHere = new AtomicBoolean();
        lines.takeWhile(line -> {
            if (processed.incrementAndGet() % 100 == 0 && interrupted.getAsBoolean()) {
                // The line this predicate is rejecting was counted on the way in and is not read,
                // so the count is put back before it is quoted.
                processed.decrementAndGet();
                stoppedHere.set(true);
                cutShort = true;
                log.logStep("File content processing", "Interrupted by user after processing "
                                                       + processed.get() + " lines in "
                                                       + file.getFileName());
                return false;
            }
            return true;
        }).forEach(line -> {
            lineNumber.incrementAndGet();
            Matcher matcher  = regexPattern.matcher(line);
            boolean hasMatch = matcher.find();
            matcher.reset();

            if (hasMatch == matching.invertMatch()) {
                kept.unmatched(lineNumber.get(), line);
                return;
            }
            GrepMatch match = new GrepMatch(lineNumber.get(), line);
            if (matching.highlights()) {
                while (matcher.find()) {
                    match.addMatchPosition(matcher.start(), matcher.end());
                }
            }
            kept.matched(match);
        });

        // Said only when THIS file was actually cut short. The sticky interrupt flag was asked
        // instead, and the in-stream check only samples every hundredth line, so a 40-line file
        // read completely and correctly was announced as "interrupted after processing 40 lines".
        List<GrepMatch> collected = kept.collected();
        if (stoppedHere.get()) {
            long found = collected.stream().filter(one -> one.matched).count();
            OutputFormatter.printWarning("File search interrupted by user after processing "
                                         + processed.get()
                                         + (processed.get() == 1 ? " line with " : " lines with ")
                                         + found
                                         + (found == 1 ? " match" : " matches"));
        }
        return collected;
    }

    /**
     * How much of the surroundings this run keeps.
     *
     * <p>Nothing, for a run that shows no lines. A count and a file listing report one number and
     * one name, so lines kept for them would be read, held and thrown away. Asked of whether lines
     * are shown rather than of whether they are highlighted, because an inverted match shows lines
     * without highlighting them and had its {@code -A}, {@code -B} and {@code -C} discarded
     * here.</p>
     */
    private GrepContext wantedContext() {
        return matching.showsLines() ? context : GrepContext.NONE;
    }

    /** What the walk saw, for the debug log. Mutable because a file visitor cannot return it. */
    private static final class Tally {
        private int visitedDirs;
        private int skippedDirs;
        private int visitedFiles;
        private int processedFiles;
        private int interruptionChecks;
    }
}
