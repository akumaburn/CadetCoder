package com.eonmux.cadetcoder.security;

import java.util.Locale;
import java.util.Set;

/**
 * The verbs CadetCoder will not run, and the one place that decides.
 *
 * <p>Two classes named {@code SecurityValidator} each kept a private copy of this list.
 * {@link com.eonmux.cadetcoder.ai.parsing.SecurityValidator} screens what the model proposes;
 * {@link SecurityValidator} is the gate {@code bash} passes through before a process is started.
 * They were written apart and drifted apart: the screen knew {@code chmod}, {@code chown},
 * {@code halt}, {@code init}, {@code useradd}, {@code userdel}, {@code iptables}, {@code ufw},
 * {@code mount}, {@code umount} and {@code fsck}, the gate knew {@code killall}, {@code pkill} and
 * the {@code mkfs.ext4} family, and neither knew the other's. A verb was therefore too dangerous
 * for the model to suggest and safe enough for the user to run, or the reverse, depending only on
 * which file had been edited more recently.</p>
 *
 * <h2>What belongs here</h2>
 *
 * <p>A verb whose accidental use cannot be undone from inside this tool. The gate refuses the verb
 * outright rather than judging its arguments -- it splits on whitespace and does not parse shell
 * grammar, so it cannot tell {@code chmod +x build.sh} from {@code chmod -R 777 /} with any
 * confidence, and guessing wrong in that direction is the expensive one. That is the same bargain
 * already struck for {@code rm}, which is refused here whatever it is pointed at. The escape hatch
 * is the user's own shell, which this tool never stands in front of.</p>
 */
public final class DangerousCommands {

    /**
     * Verbs refused by name.
     *
     * <p>Grouped by what they destroy, and complete within each group: a list that names
     * {@code useradd} and {@code userdel} but not {@code usermod}, or {@code shutdown} and
     * {@code halt} but not {@code poweroff}, is a list of what one author happened to recall
     * rather than a rule.</p>
     */
    private static final Set<String> NAMES = Set.of(
            // files and filesystems
            "rm", "del", "delete", "format", "fdisk", "dd", "mkfs", "fsck", "mount", "umount",
            // the machine
            "shutdown", "reboot", "halt", "poweroff", "init",
            // running processes
            "killall", "pkill", "systemctl", "service",
            // identity and privilege
            "sudo", "su", "passwd", "useradd", "userdel", "usermod", "chmod", "chown",
            // the network's front door
            "iptables", "nft", "ufw", "firewall", "firewall-cmd");

    /**
     * {@code mkfs} names one program per filesystem.
     *
     * <p>The gate used to enumerate {@code mkfs.ext4}, {@code mkfs.ext3}, {@code mkfs.xfs} and
     * {@code mkfs.btrfs}, which is a list of the filesystems whoever wrote it thought of;
     * {@code mkfs.vfat} formats a disk exactly as thoroughly. The family is the rule.</p>
     */
    private static final String MAKE_FILESYSTEM_PREFIX = "mkfs.";

    private DangerousCommands() {
    }

    /**
     * Reduces a command token to the lower-cased basename of the program it names, dropping any
     * directory prefix and surrounding quotes. This is what closes the obvious bypass: {@code rm},
     * {@code /bin/rm}, {@code /usr/bin/rm} and {@code "rm"} all reduce to {@code rm}.
     *
     * <p>Both separators are honoured, so a Windows-style path
     * ({@code C:\Windows\System32\format.com}) reduces the same way on any platform.</p>
     *
     * @param token a token from a command line; {@code null} reduces to the empty string
     * @return the lower-cased basename, possibly empty when the token names no program
     */
    public static String baseName(String token) {
        if (token == null) {
            return "";
        }
        String candidate = token.replace("\"", "").replace("'", "");
        int    separator = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (separator >= 0) {
            candidate = candidate.substring(separator + 1);
        }
        return candidate.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a token names a program this tool refuses to run.
     *
     * @param token a token from a command line, path-qualified or not
     * @return {@code true} when the program it names is on the list
     */
    public static boolean isDangerous(String token) {
        String name = baseName(token);
        return NAMES.contains(name) || name.startsWith(MAKE_FILESYSTEM_PREFIX);
    }

    /**
     * The refused verbs a whole command line names, in the order they appear.
     *
     * <p>Applying {@link #isDangerous} to a command line means splitting it first, and the split has
     * to be the same one every time: whitespace and the shell operators that end one program and
     * start another, so {@code ls; rm -rf /} is two programs rather than one token that matches
     * nothing. Two callers had written that split out -- and a third screened the line with
     * {@code contains}, which is wrong in both directions at once: it misses {@code ls;rm -rf /},
     * and it flags {@code git format-patch}, {@code git add .} (the {@code dd} in "add") and
     * {@code echo $result} (the {@code su} in "result"). The split belongs beside the list it is
     * a way of consulting.</p>
     *
     * @param commandLine the command as written; {@code null} and blank name nothing
     * @return the refused programs the line names, reduced to their basenames; empty when none
     */
    public static java.util.List<String> referencedBy(String commandLine) {
        if (commandLine == null) {
            return java.util.List.of();
        }
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String raw : commandLine.split(TOKEN_SEPARATORS)) {
            String name = baseName(raw);
            if (!name.isEmpty() && isDangerous(name)) {
                found.add(name);
            }
        }
        return found;
    }

    /**
     * Whether a command line names any refused program.
     *
     * @param commandLine the command as written; {@code null} and blank name nothing
     * @return {@code true} when at least one refused program is named anywhere on the line
     */
    public static boolean isReferencedBy(String commandLine) {
        return !referencedBy(commandLine).isEmpty();
    }

    /**
     * What ends one program on a command line and starts another: whitespace, and the shell
     * operators that separate commands rather than belonging to one.
     */
    private static final String TOKEN_SEPARATORS = "[\\s;|&()<>=]+";

    /**
     * The refused verbs, for callers that need to state the rule rather than apply it.
     *
     * @return an immutable view of the names; the {@code mkfs.} family is matched by
     *         {@link #isDangerous} and is not enumerated here
     */
    public static Set<String> names() {
        return NAMES;
    }
}
