package com.eonmux.cadetcoder.security;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The script a command line runs, when the line has a shape whose script is certain.
 *
 * <h2>Why</h2>
 *
 * <p>The screen reads the line and nothing else. For {@code python3 tool.py}, what the command does
 * is written in {@code tool.py}, and a judgement from the line alone can only guess. Whoever judges
 * the line can read the script instead.</p>
 *
 * <h2>Why only one shape</h2>
 *
 * <p>The script is shown as what will run, so it must be what runs. A shell line can change a file
 * before it runs it, run it from another directory, or run something else under the same name, in
 * more ways than a reader of the line can follow: {@code git checkout}, a brace expansion, a
 * {@code >&} redirection, a {@code cd} that fails, a program found first on {@code PATH}. So the
 * script is named only for a line of this shape, and for any other line nothing is named:</p>
 *
 * <pre>
 * [cd DIR &amp;&amp;] PROGRAM [options] SCRIPT [arguments] [&gt; FILE] [2&gt;&amp;1] [| READER ...]
 * </pre>
 *
 * <ul>
 *   <li>{@code PROGRAM} is {@code python}, {@code node}, {@code ruby} or {@code perl} and
 *       {@code SCRIPT} has one of its extensions, as in {@code python3 tool.py}; or a shell and any
 *       file, as in {@code bash build}; or the script is the program, run by its path, as in
 *       {@code ./build.sh}. Interpreters that load code named in a configuration file, such as
 *       {@code bun} with its {@code preload}, are not on the list.</li>
 *   <li>{@code DIR} is absolute or starts with {@code ./}, so {@code CDPATH} does not apply, and it
 *       is an existing directory, so the {@code cd} succeeds before {@code &&} runs the rest.</li>
 *   <li>{@code READER} is {@code head}, {@code tail}, {@code grep}, {@code wc} or {@code cat}, none
 *       of which writes a file.</li>
 *   <li>The line holds no expansion, pattern, substitution, {@code ~}, {@code ;}, {@code <},
 *       parenthesis, backslash or line break, and no {@code &} but the {@code &&} after {@code cd}
 *       and a redirection between numbered streams such as {@code 2>&1}.</li>
 *   <li>The script, the directory and every redirection name a path with no {@code ..} step.</li>
 *   <li>Each option before the script is on the program's short list of options that take no
 *       value, load no other code and change no directory, such as {@code python3 -u} and
 *       {@code bash -euo pipefail}. Any other option, such as {@code python3 -c} or
 *       {@code node -r}, names nothing.</li>
 *   <li>No redirection writes into the script, under any name.</li>
 *   <li>{@code PATH} holds only absolute directories, so a program in the project cannot stand in
 *       for the one its name says.</li>
 * </ul>
 */
public final class ProgramFiles {

    /**
     * What one interpreter accepts before its script.
     *
     * @param extensions the endings its scripts have; empty for a shell, which runs any file
     * @param options    the options allowed before the script, none of which takes a value, loads
     *                   other code or changes the directory
     */
    private record Interpreter(Set<String> extensions, Set<String> options) {
    }

    private static final Map<Pattern, Interpreter> INTERPRETERS = Map.of(
            Pattern.compile("python[0-9.]*"), new Interpreter(
                    Set.of(".py"), Set.of("-u", "-B", "-O", "-OO", "-I", "-E", "-s", "-S", "-q")),
            Pattern.compile("node|nodejs"), new Interpreter(
                    Set.of(".js", ".mjs", ".cjs"),
                    Set.of("--no-warnings", "--enable-source-maps", "--trace-warnings")),
            Pattern.compile("ruby"), new Interpreter(Set.of(".rb"), Set.of("-w", "-W0", "-d")),
            Pattern.compile("perl"), new Interpreter(Set.of(".pl"), Set.of("-w", "-W", "-X", "-T")),
            Pattern.compile("sh|bash|zsh|dash|ksh"), new Interpreter(Set.of(), Set.of()));

    /** Short options a shell may take before its script, alone or together, as in {@code -eu}. */
    private static final Pattern SHELL_OPTIONS = Pattern.compile("-[euxv]+");

    /** Short options that end in {@code o}, whose value is the next word, as in {@code -euo}. */
    private static final Pattern SHELL_OPTIONS_WITH_O = Pattern.compile("-[euxv]*o");

    /** The values a shell's {@code -o} may take before its script. */
    private static final Set<String> SHELL_O_VALUES = Set.of("pipefail", "errexit", "nounset", "xtrace");

    /** Programs a script's output may be piped into. None of them writes a file. */
    private static final Set<String> READERS = Set.of("head", "tail", "grep", "wc", "cat");

    /** Characters that make the shell read a line in a way this does not follow. */
    private static final Pattern UNFOLLOWED = Pattern.compile("[$`{}*?\\[\\]~;<()!#\\\\\\r\\n]");

    /** A redirection between numbered streams, such as {@code 2>&1}, whose {@code &} is not an operator. */
    private static final Pattern STREAM_TO_STREAM = Pattern.compile("(?<![0-9])[0-9]>&[0-9](?![0-9])");

    private ProgramFiles() {
    }

    /**
     * The script a command line runs, when its shape makes the script certain.
     *
     * @param command          the command line
     * @param workingDirectory the directory the command runs in
     * @return the script, absolute and normalized; empty for any other line
     */
    public static Optional<Path> scriptOf(String command, Path workingDirectory) {
        return scriptOf(command, workingDirectory, System.getenv("PATH"));
    }

    /**
     * {@link #scriptOf(String, Path)} with the search path given.
     *
     * @param command          the command line
     * @param workingDirectory the directory the command runs in
     * @param searchPath       the {@code PATH} the command runs with
     * @return the script, absolute and normalized; empty for any other line
     */
    static Optional<Path> scriptOf(String command, Path workingDirectory, String searchPath) {
        try {
            return Optional.ofNullable(find(command, workingDirectory, searchPath));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static Path find(String command, Path workingDirectory, String searchPath) {
        if (command == null || workingDirectory == null || !onlyAbsolute(searchPath)
            || UNFOLLOWED.matcher(command).find()) {
            return null;
        }
        String  operators = STREAM_TO_STREAM.matcher(command).replaceAll("");
        boolean cd        = operators.contains("&&");
        if (operators.replaceFirst("&&", "").contains("&") || command.contains("||")) {
            return null;
        }
        List<ShellCommandLine.Segment> segments = ShellCommandLine.parse(command).segments();
        if (segments.size() != 1 + countOf(command, '|') + (cd ? 1 : 0)) {
            return null;
        }
        Path here  = workingDirectory.toAbsolutePath().normalize();
        int  first = 0;
        if (cd) {
            if (command.indexOf('|') >= 0 && command.indexOf('|') < command.indexOf("&&")) {
                return null;
            }
            here  = afterCd(segments.get(0), here);
            first = 1;
            if (here == null) {
                return null;
            }
        }
        for (int i = first + 1; i < segments.size(); i++) {
            ShellCommandLine.Segment reader = segments.get(i);
            if (reader.isEmpty() || !READERS.contains(reader.tokens().get(0))
                || !reader.redirects().isEmpty()) {
                return null;
            }
        }
        Path script = scriptRunBy(segments.get(first), here);
        if (script == null) {
            return null;
        }
        for (ShellCommandLine.Redirect redirect : segments.get(first).redirects()) {
            if (!redirect.writes() || writesInto(redirect.target(), here, script)) {
                return null;
            }
        }
        return script;
    }

    /** The script one command runs, or {@code null} when the command is not of the one shape. */
    private static Path scriptRunBy(ShellCommandLine.Segment segment, Path here) {
        List<String> tokens = segment.tokens();
        if (tokens.isEmpty()) {
            return null;
        }
        String program = tokens.get(0);
        if (program.contains("/")) {
            return program.startsWith("/") || program.startsWith("./") ? file(program, here) : null;
        }
        Interpreter spec = interpreter(program);
        if (spec == null) {
            return null;
        }
        int at = spec.extensions().isEmpty() ? shellScriptIndex(tokens) : scriptIndex(tokens, spec);
        if (at < 0) {
            return null;
        }
        String name = tokens.get(at).toLowerCase(Locale.ROOT);
        if (!spec.extensions().isEmpty() && spec.extensions().stream().noneMatch(name::endsWith)) {
            return null;
        }
        return file(tokens.get(at), here);
    }

    private static Interpreter interpreter(String program) {
        String name = program.toLowerCase(Locale.ROOT);
        for (Map.Entry<Pattern, Interpreter> entry : INTERPRETERS.entrySet()) {
            if (entry.getKey().matcher(name).matches()) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Where an interpreter's script is named, or {@code -1} when an option is not on its list. */
    private static int scriptIndex(List<String> tokens, Interpreter spec) {
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!token.startsWith("-") && !token.startsWith("+")) {
                return i;
            }
            if (!spec.options().contains(token)) {
                return -1;
            }
        }
        return -1;
    }

    /** Where a shell's script is named, or {@code -1} when an option is not on its list. */
    private static int shellScriptIndex(List<String> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!token.startsWith("-") && !token.startsWith("+")) {
                return i;
            }
            if (SHELL_OPTIONS_WITH_O.matcher(token).matches()) {
                if (i + 1 >= tokens.size() || !SHELL_O_VALUES.contains(tokens.get(i + 1))) {
                    return -1;
                }
                i++;
            } else if (!SHELL_OPTIONS.matcher(token).matches()) {
                return -1;
            }
        }
        return -1;
    }

    /** The regular file a plain path names, or {@code null}. */
    private static Path file(String name, Path here) {
        Path path = plain(name, here);
        return path != null && Files.isRegularFile(path) ? path : null;
    }

    /** A path with no {@code ..} step, resolved from {@code here}, or {@code null}. */
    private static Path plain(String name, Path here) {
        if (name.isEmpty() || name.startsWith("-")) {
            return null;
        }
        Path given = Paths.get(name);
        for (Path step : given) {
            if (step.toString().equals("..")) {
                return null;
            }
        }
        return (given.isAbsolute() ? given : here.resolve(given)).toAbsolutePath().normalize();
    }

    /** The directory {@code cd DIR} moves to, when it certainly succeeds; otherwise {@code null}. */
    private static Path afterCd(ShellCommandLine.Segment segment, Path from) {
        List<String> tokens = segment.tokens();
        if (tokens.size() != 2 || !tokens.get(0).equals("cd") || !segment.redirects().isEmpty()) {
            return null;
        }
        String target = tokens.get(1);
        if (!target.startsWith("/") && !target.startsWith("./")) {
            return null; // bash searches CDPATH first
        }
        Path directory = plain(target, from);
        return directory != null && Files.isDirectory(directory) ? directory : null;
    }

    /** Whether a redirection writes into the script, through any link on the way. */
    private static boolean writesInto(String target, Path here, Path script) {
        Path path = plain(target, here);
        if (path == null || path.equals(script)) {
            return true;
        }
        try {
            // The same file under another name, through a link or a hard link, or in another case
            // on a file system that ignores case, is still the script.
            return Files.exists(path) && Files.isSameFile(path, script);
        } catch (IOException | RuntimeException unresolved) {
            return true;
        }
    }

    /** Whether each directory on the search path is absolute. */
    private static boolean onlyAbsolute(String searchPath) {
        if (searchPath == null) {
            return true;
        }
        for (String entry : searchPath.split(Pattern.quote(File.pathSeparator), -1)) {
            if (entry.isEmpty() || !Paths.get(entry).isAbsolute()) {
                return false;
            }
        }
        return true;
    }

    private static int countOf(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }
}
