package com.eonmux.cadetcoder.security;

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
 * A command the gate cannot read does not stop it from reading the rest of the line.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code screenCommand} returned the first verdict that was not "allowed", and an "unclear"
 * verdict is one. In {@code ./build.sh && rm -rf work} the script file is unclear, so {@code rm}
 * was never looked at. The line went to the approval step as "it runs a script file"; with
 * {@code commandApproval auto} the model settled that question, and with {@code --force} nothing
 * did, so {@code rm} ran. An outright refusal anywhere on the line must win over "cannot tell".</p>
 */
public class AcommandTheGateCannotReadDoesNotHideTheNextTest {

    private MockedStatic<ConfigManager> configMock;
    private SecurityValidator           gate;

    @Before
    public void setUp() {
        ConfigManager                manager  = mock(ConfigManager.class);
        Configuration                config   = mock(Configuration.class);
        Configuration.SecurityConfig security = mock(Configuration.SecurityConfig.class);
        when(manager.getConfig()).thenReturn(config);
        when(config.getSecurity()).thenReturn(security);
        when(security.isSandboxMode()).thenReturn(false);
        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(manager);
        gate = new SecurityValidator();
    }

    @After
    public void tearDown() {
        configMock.close();
    }

    private void refused(String line) {
        SecurityValidator.CommandScreening verdict = gate.screenCommand(line);
        assertThat(verdict.allowed()).as("`%s` runs rm", line).isFalse();
        assertThat(verdict.reconsiderable())
                .as("`%s` is refused outright, not left for someone to approve", line)
                .isFalse();
    }

    @Test
    public void alistedProgramAfterAscriptFileIsRefused() {
        refused("./build.sh && rm -rf work");
        refused("./build.sh; rm -rf work");
    }

    @Test
    public void alistedProgramAfterAprogramBuiltByExpansionIsRefused() {
        refused("\"$EDITOR\" notes.txt; rm -rf work");
    }

    @Test
    public void aprotectedPathHandedToAscriptFileIsRefused() {
        SecurityValidator.CommandScreening verdict = gate.screenCommand("./x.sh /etc/shadow");

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reconsiderable()).isFalse();
    }

    @Test
    public void alineWhoseOnlyDoubtIsTheScriptIsStillLeftForApproval() {
        SecurityValidator.CommandScreening verdict = gate.screenCommand("./build.sh && git status");

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reconsiderable()).isTrue();
        assertThat(verdict.reason()).contains("script file");
    }
}
