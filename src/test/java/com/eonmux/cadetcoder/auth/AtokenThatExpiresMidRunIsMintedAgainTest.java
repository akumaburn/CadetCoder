package com.eonmux.cadetcoder.auth;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A credential that expires during a run is re-read, not remembered.
 *
 * <p><b>The defect</b>: a GitHub Copilot token lives about twenty-five minutes. It was exchanged
 * once, when the AI client was constructed, and the client is cached until the model or the login
 * changes -- so every request for the rest of the session carried the same token, long after it had
 * expired. The failure is a 401, which {@code RetryPolicy} rightly declines to retry, so a run
 * longer than the token's life could not recover inside the process. The expiry was parsed the
 * whole time and read only in order to print it.</p>
 *
 * <p>These tests hold the rule that replaced it: a token is reused while it is good for at least
 * the refresh margin, and treated as spent before it actually expires.</p>
 */
public class AtokenThatExpiresMidRunIsMintedAgainTest {

    private static final long NOW = 1_800_000_000L;

    /** A base directory with no stored login in it, so nothing on this machine is consulted. */
    @Rule
    public TemporaryFolder noLogin = new TemporaryFolder();

    private static GitHubCopilotAuth.CopilotToken expiringAt(long epochSeconds) throws Exception {
        // The only constructor is package-private to this package, which is where this test lives,
        // but it is reached through the parser so the test also covers the shape GitHub sends.
        return GitHubCopilotAuth.parseCopilotToken(
                "{\"token\":\"tok_abc\",\"expires_at\":" + epochSeconds + "}");
    }

    @Test
    public void aFreshTokenIsUsable() throws Exception {
        assertThat(GitHubCopilotAuth.isUsableAt(expiringAt(NOW + 1_500), NOW)).isTrue();
    }

    @Test
    public void anExpiredTokenIsNotUsable() throws Exception {
        assertThat(GitHubCopilotAuth.isUsableAt(expiringAt(NOW - 1), NOW)).isFalse();
    }

    @Test
    public void aTokenIsSpentBeforeItExpires() throws Exception {
        // The margin exists so a token handed out is good for the whole of the call it is handed
        // out for: one that expires in a minute must not start a completion that takes two.
        long insideTheMargin = NOW + GitHubCopilotAuth.REFRESH_MARGIN_SECONDS - 1;
        assertThat(GitHubCopilotAuth.isUsableAt(expiringAt(insideTheMargin), NOW)).isFalse();

        long outsideTheMargin = NOW + GitHubCopilotAuth.REFRESH_MARGIN_SECONDS + 1;
        assertThat(GitHubCopilotAuth.isUsableAt(expiringAt(outsideTheMargin), NOW)).isTrue();
    }

    @Test
    public void nothingIsNotUsable() {
        assertThat(GitHubCopilotAuth.isUsableAt(null, NOW)).isFalse();
    }

    @Test
    public void aResponseWithoutAnExpiryIsStillGivenOne() throws Exception {
        // GitHub always sends expires_at; a token with none would otherwise be treated as expiring
        // at epoch zero and re-minted before every single request.
        GitHubCopilotAuth.CopilotToken token =
                GitHubCopilotAuth.parseCopilotToken("{\"token\":\"tok_abc\"}");

        assertThat(token.expiresAt).isGreaterThan(System.currentTimeMillis() / 1000L);
        assertThat(GitHubCopilotAuth.isUsableAt(token, System.currentTimeMillis() / 1000L)).isTrue();
    }

    @Test
    public void withNoStoredLoginThereIsNoTokenAndNoExchangeAttempt() {
        // Reached from the AI client's constructor, which runs on every invocation, so it has to
        // answer without a network call when nobody has connected Copilot.
        Configuration config = new Configuration();
        config.setBaseDir(noLogin.getRoot().getAbsolutePath());
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);

        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            assertThat(new GitHubCopilotAuth().getValidCopilotTokenAt(NOW)).isNull();
        }
    }
}
