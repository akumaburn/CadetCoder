package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Security validator for file access and command execution.
 *
 * <p><b>Scope.</b> These checks are defence-in-depth against an LLM's mistakes -- a model that
 * proposes {@code rm -rf /} or {@code cat /etc/shadow} because it misread the task -- and a
 * confirmation aid for the user reviewing what was proposed. They are NOT a sandbox. A command
 * line is read by {@link ShellCommandLine}, which recovers its structure but performs no expansion,
 * so anyone deliberately trying to evade them can (variables, here-documents, an interpreter
 * reading from stdin, a wrapper script). What cannot be read is reported as
 * {@link CommandScreening#unclear} rather than guessed at. Treat a pass as "nothing obviously
 * destructive was spotted", never as a guarantee that the command is harmless; real isolation has
 * to come from the process/OS the tool runs under.</p>
 */
public class SecurityValidator {

    /**
     * Commands that merely decorate ANOTHER command ({@code env FOO=1 rm -rf /},
     * {@code xargs rm}, {@code time rm}). The token that follows them is what actually
     * runs, so the denylist has to be re-applied to it instead of stopping at the wrapper.
     */
    private static final Set<String> COMMAND_PREFIX_RUNNERS = new HashSet<>(Arrays.asList(
            "env", "sudo", "doas", "nohup", "xargs", "time", "timeout", "nice", "ionice",
            "stdbuf", "setsid", "command", "chroot", "unbuffer"
                                                                                         ));

    /**
     * Options of a prefix runner that take a value of their own.
     *
     * <p>The walk past a wrapper skips its options, and an option whose value is a separate token
     * leaves that value where the program name is expected. {@code xargs -n 1 rm} stopped the walk
     * at {@code 1}, which names no program, so {@code rm} was never screened -- while
     * {@code xargs -n1 rm} was refused. One spelling of one command cannot be the difference.</p>
     */
    private static final Set<String> PREFIX_RUNNER_VALUE_FLAGS =
            Set.of("-u", "-g", "-p", "-C", "-n", "-P", "-I", "-i", "-o", "-e", "-d", "-s", "-L",
                   "-k", "--user", "--group", "--max-args", "--max-procs", "--replace",
                   "--delimiter", "--signal", "--kill-after", "--input", "--output", "--error");

    /**
     * Programs that run a command line of their own.
     *
     * <p>Screened by reading what they were told to run, because the wrapper says nothing: the
     * whole of {@code bash -c "rm -rf /"} is one token as far as the outer line is concerned.</p>
     */
    private static final Set<String> SHELL_RUNNERS =
            Set.of("sh", "bash", "zsh", "dash", "ksh", "fish");

    /** Builtins that run text as a command; the text is screened as the command line it becomes. */
    private static final Set<String> EVALUATORS = Set.of("eval", "source", ".");

    /**
     * Device files that are not files.
     *
     * <p>{@code /dev} is a protected location, and {@code > /dev/null} is how half the shell
     * commands anyone writes discard output. These three carry nothing and store nothing, so the
     * protection has nothing to protect there.</p>
     */
    private static final Set<String> HARMLESS_DEVICES =
            Set.of("/dev/null", "/dev/stdout", "/dev/stderr");

    /**
     * What sandbox mode permits when the user has not said.
     *
     * <p>Named, because it is the default for {@code security.allowedCommands} rather than the
     * answer: a user who sets that list is narrowing what the sandbox allows, and for a long time
     * their list was read by something else entirely while this one went on deciding.</p>
     */
    static final Set<String> DEFAULT_SANDBOX_COMMANDS = Set.of(
            "echo", "pwd", "cd", "ls", "dir", "cat", "type", "grep", "find",
            "git", "mvn", "npm", "yarn", "python", "java", "javac", "node");

    private static final Set<String> DANGEROUS_PATHS = new HashSet<>(Arrays.asList(
            "/etc", "/boot", "/proc", "/sys", "/dev", "/root",
            "C:\\Windows", "C:\\System32", "C:\\Program Files"
                                                                                  ));

    /**
     * Well-known credential / secret file names that must never be read,
     * written, or echoed regardless of project-containment policy. Matched
     * against the NORMALIZED resolved path (relative form), not a raw substring,
     * so an in-project file that merely contains one of these tokens in its
     * directory name is not falsely blocked.
     */
    private static final Set<String> CREDENTIAL_RELATIVE_PATHS = new HashSet<>(Arrays.asList(
            ".m2/settings.xml", ".m2/settings-security.xml",
            ".aws/credentials", ".aws/config",
            ".docker/config.json", ".kube/config",
            // CadetCoder's own config file: it stores ai.apiKey / ai.providerApiKeys, which
            // ConfigCommand deliberately redacts when printing. Matched under its ".cadet"
            // parent rather than as a bare "config.json" so ordinary project config files
            // stay readable.
            ".cadet/config.json",
            // The GitHub OAuth token. GitHubCopilotAuth writes this file owner-only precisely
            // because it is a credential; only its sibling config.json was listed here, so `read`
            // printed the token in full.
            ".cadet/copilot-auth.json"
                                                                                            ));

    /**
     * Directories whose entire contents are key material.
     *
     * <p>Matched on a whole path COMPONENT, so {@code notssh/} and {@code my.ssh.docs/} are
     * unaffected -- the same string-prefix mistake this class already had to fix in its
     * project-containment check.</p>
     *
     * <p>{@code ReadCommand} carried this rule privately, which meant {@code read} refused these
     * files and {@code commandReferencesCredentialFile} -- the check {@code bash} consults -- did
     * not know about them. A denylist that depends on which command you go through is one rule
     * pretending to be two.</p>
     */
    private static final Set<String> CREDENTIAL_DIRECTORIES =
            new HashSet<>(Arrays.asList(".ssh", ".gnupg"));

    /** Bare file names that always denote credentials wherever they appear. */
    private static final Set<String> CREDENTIAL_FILE_NAMES = new HashSet<>(Arrays.asList(
            ".netrc", ".npmrc", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
            // Cleartext by design: git stores "https://user:password@host" lines here.
            ".git-credentials", ".pgpass"
                                                                                        ));

    /**
     * Extensions that hold key material, whatever container it is encoded in.
     *
     * <p>{@code .pem} was listed on its own, so the same private key was refused as PEM and
     * printed as PKCS#12. The rest are the containers {@code CommitCommand} already warns about
     * before staging, which is the tool saying in its own words that they hold a secret.</p>
     */
    private static final Set<String> KEY_MATERIAL_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".pem", ".key", ".p12", ".pfx", ".keystore", ".jks"
                                                                                          ));

    /** The {@code .env} extension, as a whole extension. */
    private static final String ENVIRONMENT_FILE_EXTENSION = ".env";

    /**
     * The {@code .env.<name>} variants that are templates rather than secrets.
     *
     * <p>{@code .env.example} and its siblings hold variable NAMES without values and are checked
     * into the repository on purpose. Refusing them would block ordinary work and protect
     * nothing.</p>
     */
    private static final Set<String> ENVIRONMENT_TEMPLATE_VARIANTS = new HashSet<>(Arrays.asList(
            "example", "sample", "template", "dist", "defaults"
                                                                                                ));

    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile("(\\.\\.)|(\\.\\.[\\\\/])");

    private final Configuration config;
    private final Path          projectRoot;

    public SecurityValidator() {
        this.config      = ConfigManager.getInstance().getConfig();
        this.projectRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * Validates if a file path is safe to access
     *
     * @param filePath The file path to validate
     * @return true if the path is safe, false otherwise
     */
    public boolean isFileAccessAllowed(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }

        // Check for path traversal attempts
        if (PATH_TRAVERSAL_PATTERN.matcher(filePath).find()) {
            return false;
        }

        try {
            // Resolved against the project root the containment check compares with, rather than
            // against the directory the process started in, so both sides name the same place.
            Path   given         = Paths.get(filePath);
            Path   path          = (given.isAbsolute() ? given : projectRoot.resolve(given)).normalize();
            String canonicalPath = path.toString();

            // Check if path is within project boundaries
            if (!config.getSecurity().isAllowOutsideProject()) {
                if (!path.startsWith(projectRoot)) {
                    return false;
                }
            }

            // Check against dangerous paths
            if (isDangerousSystemPath(canonicalPath)) {
                return false;
            }

            // Deny well-known credential / secret files regardless of the
            // project-containment policy so secrets are never read, written, or
            // echoed. Matches the normalized resolved path (relative form), not
            // a raw substring.
            if (isSensitiveCredentialFile(path)) {
                return false;
            }

            // Check file extensions if in sandbox mode
            if (config.getSecurity().isSandboxMode()) {
                String fileName = path.getFileName().toString().toLowerCase();
                return !fileName.endsWith(".exe") && !fileName.endsWith(".sh") &&
                       !fileName.endsWith(".bat") && !fileName.endsWith(".cmd") &&
                       !fileName.endsWith(".dll") && !fileName.endsWith(".so");
            }

            return true;
        } catch (Exception e) {
            // If we can't resolve the path, it's not safe
            return false;
        }
    }

    /**
     * Whether a resolved path points into a protected system location ({@code /etc},
     * {@code /boot}, {@code C:\Windows}, ...).
     *
     * <p>Extracted from {@link #isFileAccessAllowed(String)} so command validation can apply
     * the SAME test to command arguments: before this, {@code read /etc/shadow} was denied
     * while {@code bash "cat /etc/shadow"} was not. The prefix semantics (and the
     * separator normalization) are exactly the ones the file check has always used.</p>
     *
     * @param canonicalPath the path to test, normally already absolute and normalized
     * @return true if the path lies under a protected system location
     */
    private static boolean isDangerousSystemPath(String canonicalPath) {
        if (canonicalPath == null || canonicalPath.isEmpty()) {
            return false;
        }
        // Handle both forward and backward slashes
        String normalizedCanonicalPath = canonicalPath.replace('\\', File.separatorChar);
        for (String dangerousPath : DANGEROUS_PATHS) {
            String normalizedDangerousPath = dangerousPath.replace('\\', File.separatorChar);
            if (normalizedCanonicalPath.startsWith(normalizedDangerousPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether a path points at a well-known credential / secret file
     * (e.g. ~/.m2/settings.xml, ~/.aws/credentials, SSH private keys, .env, key
     * material in any container) that must never be read, written, or echoed.
     *
     * <p>The match is performed on the NORMALIZED resolved path: the basename is
     * checked against a set of well-known credential file names, a relative
     * suffix (e.g. ".m2/settings.xml") is matched against the path tail, and any
     * key-material or dotenv file is denied. A raw substring is intentionally NOT
     * used so that an in-project file whose directory merely contains a token is
     * not blocked.
     *
     * @param filePath the path to inspect (raw or resolved)
     * @return true if the path is a sensitive credential file
     */
    public boolean isSensitiveCredentialFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }
        try {
            Path path = Paths.get(filePath).toAbsolutePath().normalize();
            return isSensitiveCredentialFile(path);
        } catch (Exception e) {
            // If the path cannot be resolved, fall back to a conservative check
            // on the raw, slash-normalized string tail.
            return matchesCredentialTokens(filePath.replace('\\', '/'));
        }
    }

    /**
     * Normalized-path variant of {@link #isSensitiveCredentialFile(String)}.
     *
     * @param path an already-normalized absolute path
     * @return true if the path is a sensitive credential file
     */
    public boolean isSensitiveCredentialFile(Path path) {
        if (path == null) {
            return false;
        }
        return matchesCredentialTokens(path.toString().replace('\\', '/')) || isTheNamedConfigFile(path);
    }

    /**
     * Whether a path is the settings file {@code --config} named.
     *
     * <p>That file holds the same API keys as {@code ~/.cadet/config.json}, and it can have any
     * name, so the suffix in {@link #CREDENTIAL_RELATIVE_PATHS} does not find it.</p>
     */
    private static boolean isTheNamedConfigFile(Path path) {
        Path named = ConfigManager.namedConfigFile();
        return named != null
               && path.toAbsolutePath().normalize().equals(named.toAbsolutePath().normalize());
    }

    /**
     * Matches a forward-slash-normalized path string against the credential
     * denylist: bare credential file names, well-known relative suffixes, key
     * material in any container, and dotenv files that hold values.
     */
    private static boolean matchesCredentialTokens(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        int    lastSlash = normalized.lastIndexOf('/');
        String fileName  = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;

        String lowerFileName = fileName.toLowerCase(java.util.Locale.ROOT);

        // Key material anywhere, in any container.
        for (String extension : KEY_MATERIAL_EXTENSIONS) {
            if (lowerFileName.endsWith(extension)) {
                return true;
            }
        }

        // Where a project keeps its API keys and database passwords.
        if (isEnvironmentFile(lowerFileName)) {
            return true;
        }

        // Bare credential file names (e.g. .netrc, id_rsa).
        if (CREDENTIAL_FILE_NAMES.contains(fileName)) {
            return true;
        }

        // Anything under a key directory, matched per component so a directory that merely ends
        // in ".ssh" is a different directory.
        for (String component : normalized.split("/")) {
            if (CREDENTIAL_DIRECTORIES.contains(component)) {
                return true;
            }
        }

        // Well-known relative suffixes (e.g. ".m2/settings.xml"). Require a path
        // boundary so "anything.m2/settings.xml" does not match unintentionally.
        for (String rel : CREDENTIAL_RELATIVE_PATHS) {
            if (normalized.equals(rel) || normalized.endsWith("/" + rel)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Whether a file name is a dotenv file that holds values.
     *
     * <p>Matches {@code .env}, {@code prod.env} and the per-environment variants
     * {@code .env.local} / {@code .env.production}, and not the committed templates. The
     * {@code .env} part must be a whole extension, so {@code .envrc} (a direnv script),
     * {@code environment.ts} and a file simply called {@code env} are untouched.</p>
     *
     * @param lowerFileName the base file name, already lower-cased
     * @return true when the file is a dotenv file holding real values
     */
    private static boolean isEnvironmentFile(String lowerFileName) {
        int marker = lowerFileName.indexOf(ENVIRONMENT_FILE_EXTENSION);
        if (marker < 0) {
            return false;
        }
        String rest = lowerFileName.substring(marker + ENVIRONMENT_FILE_EXTENSION.length());
        if (!rest.isEmpty() && !rest.startsWith(".")) {
            return false;
        }
        String variant = rest.isEmpty() ? "" : rest.substring(1);
        return !ENVIRONMENT_TEMPLATE_VARIANTS.contains(variant);
    }

    /**
     * Whether a shell command references a well-known credential / secret file
     * (e.g. ~/.m2/settings.xml, ~/.aws/credentials, an SSH private key, a *.pem
     * file). The shell runner ({@code bash}) consults this so a command cannot read
     * or echo secrets that {@link #isFileAccessAllowed(String)} already blocks for the
     * file commands — closing the gap where {@code cat ~/.m2/settings.xml} succeeded
     * via bash while {@code read ~/.m2/settings.xml} was denied.
     *
     * <p>This is best-effort defense-in-depth, not a full shell parser: the command is
     * split on whitespace, each token is unquoted and {@code '~'}-expanded, then matched
     * against the credential denylist (both as a resolved path and as a raw,
     * slash-normalized tail). Constructs that hide the target (globs, command
     * substitution, variable expansion) are out of scope — but the bash runner already
     * rejects command substitution and shell metacharacters upstream, so the practical
     * evasion surface is small.</p>
     *
     * @param command the shell command line to inspect
     * @return true if any token references a protected credential file
     */
    public boolean commandReferencesCredentialFile(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String home = System.getProperty("user.home");
        for (String token : command.trim().split("\\s+")) {
            // Drop quote characters AND escaping backslashes the shell strips before it opens
            // the path, so the candidate reflects what the shell actually reads. Without this,
            // whitespace-split stray quotes (bash -c "cat ~/.m2/settings.xml") or an escape
            // (cat ~/.m2/\settings.xml) would defeat the match.
            String candidate = token.replace("\"", "").replace("'", "").replace("\\", "");
            if (candidate.isEmpty()) {
                continue;
            }
            if (home != null && (candidate.equals("~") || candidate.startsWith("~/"))) {
                candidate = home + candidate.substring(1);
            }
            String normalized = candidate.replace('\\', '/');
            // Resolved-path and raw basename/suffix match on the literal token.
            if (isSensitiveCredentialFile(candidate) || matchesCredentialTokens(normalized)) {
                return true;
            }
            // A shell glob (* or ?) can expand to a credential file the literal match misses
            // (cat ~/.m2/s*.xml, cat ~/.ssh/id_rs?). These metacharacters are not blocked
            // upstream, so test whether the glob could match a protected file.
            if ((normalized.indexOf('*') >= 0 || normalized.indexOf('?') >= 0)
                    && globCouldMatchCredential(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a token containing shell glob wildcards ({@code *}/{@code ?}) could expand to a
     * protected credential file: its basename glob matches a bare credential file name
     * ({@code id_rsa} family, {@code .netrc}, ...), is a {@code *.pem} read, or matches the
     * secret file of a credential directory the token points into (e.g. {@code .m2/s*.xml}).
     * Scoped so ordinary globs over non-secret directories (e.g. {@code *.java},
     * {@code .m2/repository/foo*.jar}) are not falsely blocked.
     */
    private static boolean globCouldMatchCredential(String normalizedPath) {
        int    slash = normalizedPath.lastIndexOf('/');
        String dir   = slash >= 0 ? normalizedPath.substring(0, slash) : "";
        String base  = slash >= 0 ? normalizedPath.substring(slash + 1) : normalizedPath;

        // A globbed PEM key read (e.g. *.pem, server*.pem). Lower-cased for consistency with
        // the literal-token PEM check.
        if (base.toLowerCase().endsWith(".pem")) {
            return true;
        }
        // Glob over a bare credential file name that is sensitive anywhere. Anchored so a bare
        // wildcard (e.g. `ls *`, `cat src/*`, `grep foo *`) is NOT mistaken for a credential
        // reference and wrongly blocked.
        for (String name : CREDENTIAL_FILE_NAMES) {
            if (anchoredGlobMatch(base, name)) {
                return true;
            }
        }
        // Glob over the secret file of a credential directory the token points into. The
        // directory segment may ITSELF be globbed (e.g. ~/.m*/settings.xml -> ~/.m2/...), so
        // match it with a glob-aware, literal-anchored comparison rather than a plain compare.
        // The file part stays unanchored: a bare wildcard IS blocked here because it only
        // applies once the directory is confirmed to be a credential directory.
        String dirLast = dir.substring(dir.lastIndexOf('/') + 1);
        for (String rel : CREDENTIAL_RELATIVE_PATHS) {
            int    s       = rel.lastIndexOf('/');
            String relDir  = s >= 0 ? rel.substring(0, s) : "";
            String relFile = s >= 0 ? rel.substring(s + 1) : rel;
            if (anchoredGlobMatch(dirLast, relDir) && globMatches(base, relFile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code segment} matches {@code target} either literally or as a shell glob that
     * shares a NON-EMPTY literal prefix with {@code target}. The literal-prefix anchor stops a
     * bare wildcard-only segment (e.g. a lone {@code *}) from matching every target, which would
     * over-block ordinary commands. Used for both the bare credential-name match and the
     * credential-directory match.
     */
    private static boolean anchoredGlobMatch(String segment, String target) {
        if (segment.equals(target)) {
            return true;
        }
        int wildcard = -1;
        for (int k = 0; k < segment.length(); k++) {
            char c = segment.charAt(k);
            if (c == '*' || c == '?') {
                wildcard = k;
                break;
            }
        }
        if (wildcard <= 0) {
            // No wildcard (and not literally equal), or a leading wildcard with no literal anchor.
            return false;
        }
        return target.startsWith(segment.substring(0, wildcard)) && globMatches(segment, target);
    }

    /** Matches a shell glob (only {@code *}/{@code ?} expected here) against a literal name. */
    private static boolean globMatches(String globPattern, String literal) {
        try {
            return java.nio.file.FileSystems.getDefault()
                    .getPathMatcher("glob:" + globPattern).matches(java.nio.file.Paths.get(literal));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates if a command is safe to execute
     *
     * @param command The command to validate
     * @return true if the command is safe, false otherwise
     */
    public boolean isCommandExecutionAllowed(String command) {
        return isCommandExecutionAllowed(command, null);
    }

    /**
     * Validates if a command is safe to execute.
     *
     * @param command      The command to validate
     * @param errorMessage StringBuilder to receive detailed error message
     * @return true if the command is safe, false otherwise
     */
    public boolean isCommandExecutionAllowed(String command, StringBuilder errorMessage) {
        CommandScreening screening = screenCommand(command);
        if (!screening.allowed() && errorMessage != null) {
            errorMessage.append(screening.reason());
        }
        return screening.allowed();
    }

    /**
     * What a screen made of a command line, and whether anything may reconsider it.
     *
     * <p>The distinction is the whole point. A line that names {@code rm} is refused because of
     * what it does, and no second opinion changes that. A line whose program is built out of a
     * variable is refused because this checker cannot SEE what it does, which is a different
     * statement and one that something with more context -- a person at the terminal, or the model
     * in {@code auto} approval -- is entitled to settle. Collapsing the two into a boolean is what
     * made every refusal final and unexplained.</p>
     *
     * @param allowed        whether the line may run
     * @param reason         why not, in words meant for whoever has to act on it; empty when allowed
     * @param reconsiderable whether the refusal is "cannot tell" rather than "will not"
     * @param personOnly     whether only the person at the terminal may reconsider it, never the
     *                       model and never a {@code --force} given in advance
     */
    public record CommandScreening(boolean allowed, String reason, boolean reconsiderable,
                                   boolean personOnly) {

        /** @return a line that passed every screen */
        public static CommandScreening ok() {
            return new CommandScreening(true, "", false, false);
        }

        /** @return a refusal nothing may overturn */
        public static CommandScreening refused(String reason) {
            return new CommandScreening(false, reason, false, false);
        }

        /** @return a refusal that rests on what could not be determined, not on what was found */
        public static CommandScreening unclear(String reason) {
            return new CommandScreening(false, reason, true, false);
        }

        /**
         * @return a line that may run only once the person at the terminal has said yes to it, as a
         *         push that can discard commits on the remote may
         */
        public static CommandScreening forThePerson(String reason) {
            return new CommandScreening(false, reason, true, true);
        }
    }

    /**
     * Screens a shell command line, and says why when it will not do.
     *
     * <p>The line is taken apart by {@link ShellCommandLine} and every command it names is screened
     * in turn -- the ones an operator separates, the ones a substitution nests, and the one a
     * {@code sh -c} string carries. Each is put through the {@link DangerousCommands} denylist
     * (applied to the BASENAME, and through prefix runners such as {@code env}/{@code xargs}, so
     * {@code /bin/rm} and {@code env rm} are refused just like {@code rm}), the
     * {@link #DANGEROUS_PATHS} check on absolute-path arguments and redirection targets, the
     * credential denylist, and the sandbox allowlist.</p>
     *
     * <p>What it deliberately no longer does is refuse a line for containing a character. A pipe, a
     * redirection, a glob and a {@code $} inside an {@code awk} script are how ordinary shell work
     * is written; refusing them refused the work while stopping nothing, because the programs a
     * line runs are what matter and those are now read directly.</p>
     *
     * <p>This is defence-in-depth against a mistaken command, not an escape-proof boundary -- see
     * the class javadoc. Ordinary work must keep working, so nothing is refused on suspicion alone:
     * what cannot be read is reported as {@link CommandScreening#unclear} for someone else to
     * settle.</p>
     *
     * @param command the command line to screen
     * @return the verdict, with its reason
     */
    public CommandScreening screenCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return CommandScreening.refused("no command was given");
        }
        ShellCommandLine line = ShellCommandLine.parse(command);
        if (line.segments().isEmpty()) {
            return CommandScreening.refused("the line names no program to run");
        }
        if (!config.getSecurity().isAllowRemoteExecution()
            && NetworkCommands.isReferencedBy(command)) {
            return CommandScreening.refused(
                    "Remote execution is disabled: this command reaches the network "
                    + "(security.allowRemoteExecution is off)");
        }
        if (commandReferencesCredentialFile(command)) {
            // The path is left out on purpose: naming it would print the location of a secret into
            // the transcript, the session log and the model's next prompt.
            return CommandScreening.refused("it names a protected credential file");
        }
        // A command this cannot read does not end the reading: `./build.sh && rm -rf work` has to
        // be refused for its rm, not handed to the approval step as a question about a script.
        CommandScreening doubt = CommandScreening.ok();
        for (ShellCommandLine.Segment segment : line.allSegments()) {
            CommandScreening verdict = screenSegment(segment);
            if (isRefusal(verdict)) {
                return verdict;
            }
            doubt = firstDoubt(doubt, verdict);
        }
        if (!doubt.allowed()) {
            return doubt;
        }
        if (line.isBackground()) {
            // Nothing that waits on this command can see a backgrounded child finish, stop it, or
            // collect what it printed -- so the timeout and the interrupt both stop applying.
            return CommandScreening.unclear("it puts a command into the background with '&'");
        }
        return CommandScreening.ok();
    }

    /**
     * Screens one command of a line: its program, its arguments and its redirections.
     *
     * @param segment the command to screen
     * @return the verdict for this command alone
     */
    private CommandScreening screenSegment(ShellCommandLine.Segment segment) {
        CommandScreening redirected = screenRedirects(segment);
        if (isRefusal(redirected) || segment.isEmpty()) {
            return redirected;
        }
        CommandScreening program = screenProgram(segment);
        if (isRefusal(program)) {
            return program;
        }
        CommandScreening arguments = screenArguments(segment.tokens());
        if (!arguments.allowed()) {
            return arguments;
        }
        return firstDoubt(redirected, program);
    }

    /**
     * Screens the program a command runs, walking past the runners that only decorate another
     * command ({@code env FOO=1 xargs ...}), so the program that actually runs is the one screened.
     *
     * @param segment a command with at least one token
     * @return refused for a listed program, unclear for one this cannot read, otherwise ok
     */
    private CommandScreening screenProgram(ShellCommandLine.Segment segment) {
        List<String> tokens = segment.tokens();
        int          index  = 0;
        while (index < tokens.size()) {
            if (segment.isHidden(index)) {
                return CommandScreening.unclear(
                        "the program it runs is built by expansion, so it cannot be read from the "
                        + "line");
            }
            // A shell runs `FOO=1 rm -rf x` by setting FOO and then running rm, and so does this:
            // read as a program name, `FOO=1` is on no denylist and decorates nothing, so the walk
            // stopped there and rm was never looked at.
            if (isEnvironmentAssignment(tokens.get(index))) {
                index++;
                continue;
            }
            String candidate = commandBaseName(tokens.get(index));
            if (candidate.isEmpty()) {
                // Nothing left to identify (e.g. a bare "/"): stop rather than guess.
                break;
            }
            if (DangerousCommands.isDangerous(candidate)) {
                return CommandScreening.refused(
                        "'" + candidate + "' is on the list of programs CadetCoder will not run");
            }
            if (isScriptFile(candidate)) {
                return CommandScreening.unclear(
                        "'" + tokens.get(index) + "' runs a script file, whose contents this check "
                        + "cannot read");
            }
            if (candidate.equals("git")) {
                String rewrite = GitPushes.whatItCanDiscard(segment, index);
                return rewrite == null ? CommandScreening.ok() : CommandScreening.forThePerson(rewrite);
            }
            if (SHELL_RUNNERS.contains(candidate)) {
                return screenNestedShell(segment, index);
            }
            if (EVALUATORS.contains(candidate)) {
                return screenEvaluated(segment, index);
            }
            if (!COMMAND_PREFIX_RUNNERS.contains(candidate)) {
                break;
            }
            index = pastTheRunnersOptions(tokens, index + 1);
        }
        return CommandScreening.ok();
    }

    /**
     * Screens a command's arguments against the protected locations and the sandbox allowlist.
     *
     * @param tokens the command's words, at least one
     * @return refused, or ok
     */
    private CommandScreening screenArguments(List<String> tokens) {
        // Deny arguments that point into protected system locations, using the same check the file
        // commands apply, so "cat /etc/shadow" is refused for the shell exactly as
        // "read /etc/shadow" already is.
        for (String token : tokens) {
            if (isProtectedSystemPathArgument(token)) {
                return CommandScreening.refused(
                        "'" + token + "' points into a protected system location");
            }
        }

        // In sandbox mode, only allow whitelisted programs. The allowlist is matched against the
        // program as written: it is an opt-in "only these invocations" mode, so nothing it used to
        // refuse becomes allowed here.
        if (config.getSecurity().isSandboxMode()) {
            String program = tokens.get(0).toLowerCase();
            if (!sandboxAllows(config.getSecurity(), program)) {
                return CommandScreening.refused(
                        "sandbox mode is on and '" + program + "' is not on security.allowedCommands");
            }
        }
        return CommandScreening.ok();
    }

    /** Whether a verdict refuses outright, as opposed to allowing or not being able to tell. */
    private static boolean isRefusal(CommandScreening verdict) {
        return !verdict.allowed() && !verdict.reconsiderable();
    }

    /**
     * The first of two verdicts that is not "allowed", so the reason reported is the earliest one.
     *
     * @param earlier the verdict so far
     * @param later   the next verdict; neither is a refusal
     * @return {@code earlier} when it already holds a doubt, otherwise {@code later}
     */
    private static CommandScreening firstDoubt(CommandScreening earlier, CommandScreening later) {
        // A doubt only the person may settle outranks one anybody may: reported second, it would
        // be put to whoever settles the first, which under auto approval is the model.
        if (later.personOnly() && !earlier.personOnly()) {
            return later;
        }
        return earlier.allowed() ? later : earlier;
    }

    /**
     * Screens the files a command redirects to or from.
     *
     * @param segment the command whose redirections to screen
     * @return the verdict
     */
    private CommandScreening screenRedirects(ShellCommandLine.Segment segment) {
        CommandScreening doubt = CommandScreening.ok();
        for (ShellCommandLine.Redirect redirect : segment.redirects()) {
            String target = redirect.target();
            if (target.isEmpty() || HARMLESS_DEVICES.contains(target)) {
                continue;
            }
            if (target.indexOf('$') >= 0) {
                doubt = firstDoubt(doubt, CommandScreening.unclear(
                        "the file it redirects to is named by an expansion, so it cannot be read "
                        + "from the line"));
                continue;
            }
            String opened = asTheShellOpens(target);
            if (opened == null) {
                return CommandScreening.refused(
                        "it redirects to '" + target + "', whose '~' form the check does not resolve");
            }
            if (isProtectedSystemPathArgument(opened)) {
                return CommandScreening.refused(
                        "it redirects to '" + target + "', inside a protected system location");
            }
            if (redirect.writes() && !writeIsAllowedTo(opened)) {
                return CommandScreening.refused(
                        "it writes to '" + target + "', which the file policy does not allow");
            }
        }
        return doubt;
    }

    /**
     * Screens the command line a {@code sh -c} carries, which is the one that actually runs.
     *
     * @param segment the segment naming the shell
     * @param at      where in its tokens the shell itself is
     * @return the verdict for what the shell was told to run
     */
    private CommandScreening screenNestedShell(ShellCommandLine.Segment segment, int at) {
        List<String> tokens  = segment.tokens();
        String       operand = null;
        for (int i = at + 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!carriesTheDashC(token)) {
                if (operand == null && !token.startsWith("-")) {
                    operand = token;
                }
                continue;
            }
            if (i + 1 >= tokens.size()) {
                return CommandScreening.unclear("it starts a shell with nothing to run");
            }
            if (segment.isHidden(i + 1)) {
                return CommandScreening.unclear(
                        "the command it hands to a shell is built by expansion");
            }
            return screenCommand(tokens.get(i + 1));
        }
        if (operand != null) {
            // A shell invoked to run a file. The file is judged as it would be judged if it were
            // run directly, so how a script is spelled is not what decides whether anybody is
            // asked about it -- `bash deploy.sh` and `./deploy.sh` get the same answer. A file
            // this check has no rule against is ordinary work: its contents are no more readable
            // than a Makefile's, and running one is what `make` and `python` do too.
            if (isScriptFile(commandBaseName(operand))) {
                return CommandScreening.unclear(
                        "'" + operand + "' runs a script file, whose contents this check cannot "
                        + "read");
            }
            return CommandScreening.ok();
        }
        // A shell with nothing to run takes its commands from somewhere this line does not say:
        // `echo rm -rf x | bash` is the readable version, and the half that says what runs was
        // screened as an `echo`.
        return CommandScreening.unclear(
                "it starts a shell that reads its commands from somewhere this check cannot see");
    }

    /**
     * Whether a shell option token says the next token is a command line.
     *
     * <p>Short options combine, so {@code -lc}, {@code -ec} and {@code -cx} all mean {@code -c} and
     * none of them is the two characters {@code -c}. A check that compared for equality read
     * {@code bash -lc "rm -rf x"} as a shell with no command at all and allowed it outright.</p>
     *
     * @param token one token after the shell's name
     * @return whether the token after it is what the shell will run
     */
    private static boolean carriesTheDashC(String token) {
        if (token.length() < 2 || token.charAt(0) != '-' || token.startsWith("--")) {
            return false;
        }
        return token.indexOf('c', 1) >= 0 && token.indexOf('=') < 0;
    }

    /**
     * Screens what an {@code eval} is about to run.
     *
     * @param segment the segment naming the evaluator
     * @param at      where in its tokens the evaluator itself is
     * @return the verdict for the text being evaluated
     */
    private CommandScreening screenEvaluated(ShellCommandLine.Segment segment, int at) {
        List<String> tokens = segment.tokens();
        StringBuilder evaluated = new StringBuilder();
        for (int i = at + 1; i < tokens.size(); i++) {
            if (segment.isHidden(i)) {
                return CommandScreening.unclear(
                        "the text it evaluates is built by expansion, so it cannot be read from the "
                        + "line");
            }
            if (evaluated.length() > 0) {
                evaluated.append(' ');
            }
            evaluated.append(tokens.get(i));
        }
        if (evaluated.length() == 0) {
            return CommandScreening.ok();
        }
        return screenCommand(evaluated.toString());
    }

    /** Whether a program token names a script or binary file run directly rather than a program. */
    private static boolean isScriptFile(String candidate) {
        return candidate.endsWith(".sh") || candidate.endsWith(".bat")
               || candidate.endsWith(".cmd") || candidate.endsWith(".exe");
    }

    /**
     * Whether one command token is an absolute path into a protected system location.
     *
     * <p>Option tokens are skipped, except for the value of a {@code --flag=/path} form.</p>
     *
     * @param token the token as the shell would read it, quotes already removed
     * @return whether it points somewhere protected
     */
    private static boolean isProtectedSystemPathArgument(String token) {
        if (token.isEmpty() || HARMLESS_DEVICES.contains(token)) {
            return false;
        }
        if (!token.startsWith("-") && isDangerousPathToken(token)) {
            return true;
        }
        int equals = token.indexOf('=');
        return equals >= 0 && isDangerousPathToken(token.substring(equals + 1));
    }

    /**
     * Reduces a command token to the lower-cased basename of the program it names.
     *
     * <p>The reduction lives beside the denylist it feeds, in {@link DangerousCommands}, so that a
     * token is identified the same way here and in the screen the model's proposals go through.</p>
     *
     * @param token a whitespace-delimited token from the command line
     * @return the lower-cased basename, possibly empty when the token names no program
     */
    private static String commandBaseName(String token) {
        return DangerousCommands.baseName(token);
    }

    /**
     * The file a redirect target names once the shell expands a leading {@code ~}.
     *
     * <p>Read as a plain path, {@code ~/.bashrc} is a folder called {@code ~} inside the project,
     * and the shell writes to the home directory instead.</p>
     *
     * @param target the target as written on the line
     * @return the path the shell opens, or {@code null} for any other {@code ~} form, such as
     *         {@code ~name} or {@code ~+}, which this does not resolve
     */
    private static String asTheShellOpens(String target) {
        if (!target.startsWith("~")) {
            return target;
        }
        String home = System.getProperty("user.home");
        if (home == null || !(target.equals("~") || target.startsWith("~/"))) {
            return null;
        }
        return home + target.substring(1);
    }

    /**
     * Whether a shell redirect may write where it says.
     *
     * <h2>Why the file commands' policy decides this</h2>
     *
     * <p>{@code write ~/.bashrc} is refused by {@link WritePathPolicy}, which allows the working
     * directory and the system temporary directory and nothing else, whatever
     * {@code security.allowOutsideProject} says. {@code bash "echo ... &gt;&gt; ~/.bashrc"} used to be
     * screened by the looser file-access check alone and was allowed on the shipped defaults. One
     * act had two policies, and the weaker one was reached through the tool that runs arbitrary
     * programs.</p>
     *
     * @param target the file the redirect names
     * @return whether writing there is within the policy every other writer follows
     */
    private static boolean writeIsAllowedTo(String target) {
        try {
            return WritePathPolicy.decide(Paths.get(target)) == WritePathPolicy.Decision.ALLOWED;
        } catch (RuntimeException unreadable) {
            // A path this cannot even parse is not one to allow a write to.
            return false;
        }
    }

    /**
     * Walks past what a prefix runner was given, to where the program it runs is named.
     *
     * <p>Its options are skipped, and so is the value of an option that takes one as a separate
     * token. So is a bare number, which no program is called and every one of these runners takes
     * as an operand -- {@code timeout 60 rm -rf x} names the program two tokens along, and a walk
     * that stopped at {@code 60} screened nothing at all.</p>
     *
     * @param tokens the segment's tokens
     * @param from   the index just after the runner
     * @return where the program it runs should be named
     */
    private static int pastTheRunnersOptions(List<String> tokens, int from) {
        int index = from;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (isEnvironmentAssignment(token)) {
                index++;
            } else if (token.startsWith("-")) {
                boolean takesAvalue = PREFIX_RUNNER_VALUE_FLAGS.contains(token);
                index++;
                if (takesAvalue && index < tokens.size()) {
                    index++;
                }
            } else if (isBareOperand(token)) {
                index++;
            } else {
                return index;
            }
        }
        return index;
    }

    /** Whether a token is a number, with or without a unit, and so names no program. */
    private static boolean isBareOperand(String token) {
        String value = token.endsWith("s") || token.endsWith("m") || token.endsWith("h")
                       || token.endsWith("d")
                       ? token.substring(0, token.length() - 1)
                       : token;
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i)) && value.charAt(i) != '.') {
                return false;
            }
        }
        return true;
    }

    /** Whether a token is a shell {@code NAME=VALUE} environment assignment (as accepted by {@code env}). */
    private static boolean isEnvironmentAssignment(String token) {
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
        for (int i = 0; i < equals; i++) {
            char c = token.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return Character.isLetter(token.charAt(0)) || token.charAt(0) == '_';
    }

    /** Whether a single command token is an absolute path inside a protected system location. */
    private static boolean isDangerousPathToken(String token) {
        if (!isAbsolutePathToken(token)) {
            return false;
        }
        if (isDangerousSystemPath(token)) {
            return true;
        }
        try {
            return isDangerousSystemPath(Paths.get(token).normalize().toString());
        } catch (Exception e) {
            // Unparseable token: the raw check above has already been applied, so treat the
            // normalized variant as simply unavailable rather than failing the whole command.
            return false;
        }
    }

    /** Whether a token looks like an absolute path on either POSIX ({@code /x}) or Windows ({@code C:\x}). */
    private static boolean isAbsolutePathToken(String token) {
        if (token.startsWith("/") || token.startsWith("\\")) {
            return true;
        }
        return token.length() >= 3 && Character.isLetter(token.charAt(0)) && token.charAt(1) == ':' &&
               (token.charAt(2) == '/' || token.charAt(2) == '\\');
    }

    /**
     * Checks if a path is within the project directory
     *
     * @param filePath The file path to check
     * @return true if within project, false otherwise
     */
    public boolean isWithinProject(String filePath) {
        String canonical = getCanonicalPath(filePath);
        if (canonical == null) {
            return false;
        }
        // Compared as paths, not as text: a String prefix test admits a sibling directory whose
        // name merely begins with the project's own ("/w/project" would contain "/w/project-old"),
        // because it never requires a separator after the root.
        return Paths.get(canonical).normalize().startsWith(projectRoot);
    }

    /**
     * Gets the resolved canonical path
     *
     * @param filePath The file path to resolve
     * @return The canonical path or null if invalid
     */
    public String getCanonicalPath(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (path.isAbsolute()) {
                return path.toAbsolutePath().normalize().toString();
            } else {
                return projectRoot.resolve(path).normalize().toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether sandbox mode lets this program run.
     *
     * @param security    the security settings in force
     * @param baseCommand the program name, already reduced to its first token
     * @return whether it is on the list the user set, or on the shipped one when they set none
     */
    static boolean sandboxAllows(Configuration.SecurityConfig security, String baseCommand) {
        String[] configured = security == null ? null : security.getAllowedCommands();
        if (configured == null || configured.length == 0) {
            return DEFAULT_SANDBOX_COMMANDS.contains(baseCommand);
        }
        for (String allowed : configured) {
            if (allowed != null && allowed.trim().equalsIgnoreCase(baseCommand)) {
                return true;
            }
        }
        return false;
    }
}
