package com.eonmux.cadetcoder.security;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The programs that reach the network, and the one place that decides.
 *
 * <p>{@code security.allowRemoteExecution} is a single setting, and two places each kept their own
 * idea of what it governs. {@code BashCommand} -- the gate a command typed at the prompt passes
 * through -- named {@code ssh scp rsync curl wget ftp sftp telnet nc netcat};
 * {@link com.eonmux.cadetcoder.ai.parsing.SecurityValidator}, which screens what the model
 * proposes, named {@code curl wget ssh scp ftp telnet nc netcat ping nslookup dig}. Written apart,
 * they drifted apart: with the setting off, {@code ping}, {@code nslookup} and {@code dig} were
 * refused to the model and run for the user, while {@code rsync} and {@code sftp} were refused at
 * the prompt and reached the model's screen unflagged.</p>
 *
 * <h2>How a reference is recognised</h2>
 *
 * <p>Any whole-word occurrence anywhere in the command line, not just the first token. The screen
 * sees whatever the model wrote, pipelines and substitutions included, so the program that opens
 * the socket is not reliably the word at the front. Over-refusing an argument that happens to name
 * a tool is the cheap mistake here; the setting exists to be switched on.</p>
 *
 * <p>Whole-word is the part that was wrong before. The screen matched {@code (curl|...)\s}, with no
 * boundary on the left, so any word ENDING in a tool's name counted: {@code npm run sync watch} was
 * refused as network access for the {@code nc} inside {@code sync}.</p>
 */
public final class NetworkCommands {

    /**
     * Programs whose purpose is to talk to another host.
     *
     * <p>{@code git}, {@code npm}, {@code mvn} and the other build tools reach the network too and
     * are deliberately absent: they are how this tool does its ordinary work, and refusing them
     * would make the default configuration unusable rather than safer.</p>
     */
    private static final Set<String> NAMES = Set.of(
            // shells and copies over a remote link
            "ssh", "scp", "sftp", "rsync", "telnet",
            // fetching over HTTP and FTP
            "curl", "wget", "ftp",
            // raw sockets
            "nc", "netcat",
            // asking the network about itself
            "ping", "nslookup", "dig");

    /**
     * Every name, as one case-insensitive whole-word alternation.
     *
     * <p>Built from {@link #NAMES} so the pattern cannot fall behind the list. Sorted only to make
     * the compiled pattern deterministic; alternation order does not change what matches, because
     * every branch is anchored on both sides by a word boundary.</p>
     */
    private static final Pattern REFERENCE = Pattern.compile(
            "\\b(" + String.join("|", new TreeSet<>(NAMES)) + ")\\b",
            Pattern.CASE_INSENSITIVE);

    private NetworkCommands() {
    }

    /**
     * The names, for a caller that must state the rule rather than apply it.
     *
     * @return an immutable set of program names
     */
    public static Set<String> names() {
        return NAMES;
    }

    /**
     * Whether a command line names a program that reaches the network.
     *
     * @param commandLine the command as written; {@code null} and blank name nothing
     * @return {@code true} if any network program is named anywhere on the line
     */
    public static boolean isReferencedBy(String commandLine) {
        return commandLine != null && REFERENCE.matcher(commandLine).find();
    }
}
