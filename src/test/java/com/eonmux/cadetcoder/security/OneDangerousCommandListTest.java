package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.ai.parsing.ParsedAction;
import com.eonmux.cadetcoder.ai.parsing.ParsingContext;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The verbs CadetCoder refuses must be one list, not two that disagree.
 *
 * <p>Two classes named {@code SecurityValidator} each declared their own set of dangerous commands.
 * {@code ai.parsing.SecurityValidator} screens what the MODEL proposes and flags a violation;
 * {@code security.SecurityValidator} is the gate {@code bash} consults before starting a process,
 * and it refuses outright. The screen knew about {@code chmod}, {@code chown}, {@code halt},
 * {@code init}, {@code useradd}, {@code userdel}, {@code iptables}, {@code ufw}, {@code mount},
 * {@code umount} and {@code fsck}; the gate did not. So the tool declared a command too dangerous
 * for the model to suggest and then ran it when the user typed it -- {@code /bash "chmod -R 777 /"},
 * {@code /bash halt} and {@code /bash "iptables -F"} all executed.</p>
 *
 * <p>The drift ran the other way too: the gate refused {@code killall}, {@code pkill} and the
 * {@code mkfs.ext4} family, which the screen let through unflagged.</p>
 *
 * <p>Neither list is a policy on its own. There is one answer to "is this verb one this tool
 * runs?", so these tests hold both gates to it.</p>
 */
public class OneDangerousCommandListTest {

    private SecurityValidator                   gate;
    private ConfigManager                       mockConfigManager;
    private Configuration                       mockConfig;
    private Configuration.SecurityConfig        mockSecurityConfig;
    private MockedStatic<ConfigManager>         configMock;

    private final com.eonmux.cadetcoder.ai.parsing.SecurityValidator screen =
            new com.eonmux.cadetcoder.ai.parsing.SecurityValidator();

    @Before
    public void setUp() {
        mockConfigManager  = mock(ConfigManager.class);
        mockConfig         = mock(Configuration.class);
        mockSecurityConfig = mock(Configuration.SecurityConfig.class);

        when(mockConfigManager.getConfig()).thenReturn(mockConfig);
        when(mockConfig.getSecurity()).thenReturn(mockSecurityConfig);
        when(mockSecurityConfig.isAllowOutsideProject()).thenReturn(false);
        when(mockSecurityConfig.isSandboxMode()).thenReturn(false);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

        gate = new SecurityValidator();
    }

    @After
    public void tearDown() {
        configMock.close();
    }

    private void gateRefuses(String command) {
        assertThat(gate.isCommandExecutionAllowed(command))
                .as("`%s` is a command this tool must not run", command)
                .isFalse();
    }

    private void gateAllows(String command) {
        assertThat(gate.isCommandExecutionAllowed(command))
                .as("`%s` is ordinary work and must keep running", command)
                .isTrue();
    }

    private boolean screenFlags(String command) {
        ParsedAction action = new ParsedAction.Builder("bash")
                .addParameter("command", command)
                .setReasoning("Run a command")
                .build();
        return screen.validateAction(action,
                        new ParsingContext.Builder("do some work")
                                .workingDirectory(System.getProperty("user.dir"))
                                .build())
                     .getViolations().stream()
                     .anyMatch(violation -> violation.startsWith(
                             com.eonmux.cadetcoder.ai.parsing.SecurityValidator.REFUSED_COMMAND));
    }

    private void screenFlags(String command, String why) {
        assertThat(screenFlags(command)).as(why, command).isTrue();
    }

    /** What the model was forbidden to propose, the user could type. */
    @Test
    public void theShellGateRefusesWhatTheModelScreenCallsDangerous() {
        gateRefuses("chmod -R 777 /home/build/app");
        gateRefuses("chown -R nobody /home/build/app");
        gateRefuses("halt");
        gateRefuses("init 0");
        gateRefuses("iptables -F");
        gateRefuses("ufw disable");
        gateRefuses("mount /home/build/image /home/build/mnt");
        gateRefuses("umount /home/build/mnt");
        gateRefuses("fsck -y");
        gateRefuses("useradd attacker");
        gateRefuses("userdel builder");
    }

    /** And what the gate refused, the model could propose unflagged. */
    @Test
    public void theModelScreenFlagsWhatTheShellGateRefuses() {
        screenFlags("killall -9 java", "%s ends processes the gate already refuses to end");
        screenFlags("pkill firefox", "%s ends processes the gate already refuses to end");
        screenFlags("mkfs.ext4 /dev/sda", "%s is the very command the gate names in full");
    }

    /**
     * A denylist that enumerates {@code mkfs.ext4}, {@code mkfs.ext3}, {@code mkfs.xfs} and
     * {@code mkfs.btrfs} is a list of the filesystems whoever wrote it happened to think of.
     * {@code mkfs.vfat} formats a disk exactly as thoroughly.
     */
    @Test
    public void theWholeMakeFilesystemFamilyIsRefusedNotJustTheEnumeratedMembers() {
        gateRefuses("mkfs.vfat /home/build/image");
        gateRefuses("mkfs.f2fs /home/build/image");
        assertThat(screenFlags("mkfs.vfat /home/build/image"))
                .as("the screen must know the same family")
                .isTrue();
    }

    /**
     * The guarantee itself, stated once and driven off the list rather than off a copy of it.
     *
     * <p>The two gates disagreed because each was written against its own transcription. Reading
     * both from {@link DangerousCommands} is what makes them the same rule; this asserts that they
     * still are, for every name, so a verb added for one gate cannot go missing from the other.</p>
     */
    @Test
    public void bothGatesHonourEveryNameOnTheOneList() {
        for (String verb : DangerousCommands.names()) {
            gateRefuses(verb);
            assertThat(screenFlags(verb + " something"))
                    .as("`%s` is refused by the shell gate and must be flagged for the model too", verb)
                    .isTrue();
        }
    }

    /**
     * The gate identifies the PROGRAM, not every word on the line, and that has to stay true:
     * {@code init} is a verb of {@code git} and {@code npm} long before it is a system command.
     */
    @Test
    public void ordinaryWorkStillRuns() {
        gateAllows("git status");
        gateAllows("git init");
        gateAllows("npm run build");
        gateAllows("mvn -o -B test");
        gateAllows("ls -la");
        gateAllows("echo hello");
        gateAllows("/usr/bin/git log --oneline");
    }
}
