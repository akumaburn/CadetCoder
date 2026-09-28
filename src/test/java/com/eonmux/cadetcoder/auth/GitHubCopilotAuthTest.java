package com.eonmux.cadetcoder.auth;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

public class GitHubCopilotAuthTest {

    @Test
    public void parsesDeviceCodeResponse() throws Exception {
        String json = "{\"device_code\":\"DC123\",\"user_code\":\"ABCD-1234\","
                + "\"verification_uri\":\"https://github.com/login/device\","
                + "\"expires_in\":900,\"interval\":5}";
        GitHubCopilotAuth.DeviceCode dc = GitHubCopilotAuth.parseDeviceCode(json);
        assertThat(dc.deviceCode).isEqualTo("DC123");
        assertThat(dc.userCode).isEqualTo("ABCD-1234");
        assertThat(dc.verificationUri).isEqualTo("https://github.com/login/device");
        assertThat(dc.interval).isEqualTo(5);
        assertThat(dc.expiresIn).isEqualTo(900);
    }

    @Test
    public void parsesCopilotTokenResponse() throws Exception {
        String json = "{\"token\":\"tid=abc;exp=123\",\"expires_at\":1893456000}";
        GitHubCopilotAuth.CopilotToken token = GitHubCopilotAuth.parseCopilotToken(json);
        assertThat(token.token).isEqualTo("tid=abc;exp=123");
        assertThat(token.expiresAt).isEqualTo(1893456000L);
    }

    /**
     * The login borrows the client ID GitHub issued to its own editor integrations, and GitHub has
     * not approved this tool. The person is told so before anything is sent to GitHub.
     */
    @Test
    public void theDisclaimerIsShownBeforeGitHubIsContacted() throws Exception {
        GitHubCopilotAuth auth = spy(new GitHubCopilotAuth());
        doThrow(new IllegalStateException("no network in tests")).when(auth).requestDeviceCode();
        ByteArrayOutputStream shown = new ByteArrayOutputStream();

        assertThatThrownBy(() -> auth.login(new PrintStream(shown, true, StandardCharsets.UTF_8)))
                .hasMessage("no network in tests");

        assertThat(shown.toString(StandardCharsets.UTF_8))
                .contains("not affiliated with or endorsed by GitHub")
                .contains("GitHub's terms")
                .contains("at your own risk");
    }
}
