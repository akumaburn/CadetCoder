package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.ai.parsing.ParsedAction;
import com.eonmux.cadetcoder.ai.parsing.ParsingContext;
import com.eonmux.cadetcoder.commands.BashCommand;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What counts as reaching the network must be one list, not two that disagree.
 *
 * <p>{@code security.allowRemoteExecution} is a single setting, and two places decided what it
 * governs. {@code BashCommand} kept {@code ssh scp rsync curl wget ftp sftp telnet nc netcat};
 * {@code ai.parsing.SecurityValidator} kept {@code curl wget ssh scp ftp telnet nc netcat ping
 * nslookup dig}. So with remote execution switched off the model was refused for proposing
 * {@code ping} while the user could type it, and {@code rsync} and {@code sftp} were refused at the
 * prompt but reached the model's screen unflagged.</p>
 *
 * <p>The screen also matched without a left word boundary -- {@code (curl|...)\s} -- so any word
 * ENDING in a tool's name was network access: {@code npm run sync watch} was refused for the
 * {@code nc} inside {@code sync}.</p>
 */
public class OneNetworkCommandListTest {

    private com.eonmux.cadetcoder.ai.parsing.SecurityValidator screen;
    private BashCommand                                        shell;
    private TestOutputCapture                                  output;
    private Configuration.SecurityConfig                       securityConfig;
    private MockedStatic<ConfigManager>                        configMock;

    @Before
    public void setUp() {
        ConfigManager configManager = mock(ConfigManager.class);
        Configuration config        = mock(Configuration.class);
        securityConfig              = mock(Configuration.SecurityConfig.class);

        when(configManager.getConfig()).thenReturn(config);
        when(config.getSecurity()).thenReturn(securityConfig);
        when(securityConfig.getAllowedCommands()).thenReturn(new String[0]);
        when(securityConfig.isAllowOutsideProject()).thenReturn(true);
        when(securityConfig.isSandboxMode()).thenReturn(false);
        when(securityConfig.isReadOnlyMode()).thenReturn(false);
        when(securityConfig.isRequireConfirmation()).thenReturn(false);
        when(securityConfig.isAllowRemoteExecution()).thenReturn(false);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(configManager);

        // Built inside the mocked scope: the screen reads the security configuration once, in its
        // constructor.
        screen = new com.eonmux.cadetcoder.ai.parsing.SecurityValidator();
        shell  = new BashCommand();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        configMock.close();
    }

    /** Runs a command through the prompt's gate, with remote execution switched off. */
    private void shellGateRefuses(String command) {
        int exitCode = shell.execute(command.split(" "));

        assertThat(exitCode)
                .as("`%s` reaches the network and remote execution is off", command)
                .isEqualTo(1);
        assertThat(output.getAllOutput())
                .as("`%s` reaches the network and remote execution is off", command)
                .contains("Remote execution is disabled");
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
                     .anyMatch(violation -> violation.startsWith("Network access not allowed"));
    }

    /** What the model was refused for proposing, the user could type. */
    @Test
    public void theShellGateRefusesWhatTheModelScreenCallsNetworkAccess() {
        shellGateRefuses("ping --version");
        shellGateRefuses("nslookup --version");
        shellGateRefuses("dig --version");
    }

    /** And what the prompt refused, the model could propose unflagged. */
    @Test
    public void theModelScreenFlagsWhatTheShellGateRefuses() {
        assertThat(screenFlags("rsync -a /home/build/src/ backup.example.com:/srv/"))
                .as("rsync is refused at the prompt and must be flagged for the model too")
                .isTrue();
        assertThat(screenFlags("sftp deploy@example.com"))
                .as("sftp is refused at the prompt and must be flagged for the model too")
                .isTrue();
    }

    /**
     * The guarantee itself, driven off the list rather than off a copy of it.
     */
    @Test
    public void bothGatesHonourEveryNameOnTheOneList() {
        for (String tool : NetworkCommands.names()) {
            shellGateRefuses(tool + " --version");
            assertThat(screenFlags(tool + " --version"))
                    .as("`%s` is refused at the prompt and must be flagged for the model too", tool)
                    .isTrue();
        }
    }

    /** A word that merely ENDS in a tool's name is a different word. */
    @Test
    public void aWordThatMerelyContainsAToolNameIsNotNetworkAccess() {
        assertThat(screenFlags("npm run sync watch"))
                .as("the `nc` inside `sync` is not netcat")
                .isFalse();
        assertThat(screenFlags("javac Encode.java"))
                .as("the `nc` inside `javac`'s arguments is not netcat")
                .isFalse();
    }

    /**
     * No third copy of the list, wherever it is spelled.
     *
     * <p>The two that existed were found because they disagreed. A source scan is what makes the
     * next one visible before it has had time to drift.</p>
     */
    @Test
    public void noOtherSourceKeepsItsOwnListOfNetworkTools() throws java.io.IOException {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        java.nio.file.Path     sources   = java.nio.file.Paths.get("src", "main", "java");

        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(sources)) {
            for (java.nio.file.Path file
                    : (Iterable<java.nio.file.Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (file.getFileName().toString().equals("NetworkCommands.java")) {
                    continue;
                }
                String source = java.nio.file.Files.readString(file);
                for (String tool : java.util.List.of("netcat", "nslookup", "telnet")) {
                    if (source.contains("\"" + tool + "\"")) {
                        offenders.add(file + " names \"" + tool + "\" itself");
                    }
                }
            }
        }

        assertThat(offenders)
                .as("a second list of network tools cannot help but drift from the first")
                .isEmpty();
    }

    /** Switching the setting on is what the setting is for. */
    @Test
    public void networkAccessIsAllowedOnceTheSettingSaysSo() {
        when(securityConfig.isAllowRemoteExecution()).thenReturn(true);
        com.eonmux.cadetcoder.ai.parsing.SecurityValidator permissive =
                new com.eonmux.cadetcoder.ai.parsing.SecurityValidator();

        ParsedAction action = new ParsedAction.Builder("bash")
                .addParameter("command", "curl https://example.com/spec.json")
                .setReasoning("Fetch a spec")
                .build();

        assertThat(permissive.validateAction(action,
                        new ParsingContext.Builder("fetch the spec")
                                .workingDirectory(System.getProperty("user.dir"))
                                .build())
                             .getViolations())
                .noneMatch(violation -> violation.startsWith("Network access not allowed"));
    }
}
