package com.eonmux.cadetcoder.ai.providers;

import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headers a gateway that routes by conversation is sent.
 *
 * <p><b>The defect</b>: OpenCode Go refused every completion with HTTP 400 {@code MissingSessionID}
 * -- "Request is missing x-opencode-session and cannot be routed efficiently". Its contract with a
 * client is three lines: send coding-agent traffic, name yourself in the user agent rather than
 * letting the HTTP library name you, and carry a stable session id per conversation. CadetCoder
 * already has the id -- it is the session -- and the connector was registered without either.</p>
 *
 * <p><b>What is locked here</b>: that a connector declaring a session header sends it with the
 * current session's id, that one which declares none sends none, that the id is the session's own
 * and stable across the requests of a run, and that a client user agent is named without
 * overwriting a connector that named its own.</p>
 */
public class AGatewayThatRoutesByConversationIsSentTheSessionTest {

    @Test
    public void aConnectorDeclaringTheHeaderSendsWithTheSessionsOwnId() {
        ProviderConnector connector = new ProviderConnector(
                "opencode-go", "OpenCode Go", java.util.List.of("OPENCODE_API_KEY"),
                "https://opencode.ai/zen/go/v1", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT, null).withSessionHeader("x-opencode-session");

        Map<String, String> headers = connector.headersFor(false);

        assertThat(headers.get("x-opencode-session"))
                .as("the id is the conversation's, which is what routing and prompt caching key on")
                .isEqualTo(SessionManager.getInstance().getCurrentSessionId())
                .isNotBlank();
    }

    @Test
    public void theSessionsIdIsStableAcrossTheRequestsOfARun() {
        ProviderConnector connector = new ProviderConnector(
                "opencode-go", "OpenCode Go", java.util.List.of("OPENCODE_API_KEY"),
                "https://opencode.ai/zen/go/v1", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT, null).withSessionHeader("x-opencode-session");

        String first  = connector.headersFor(false).get("x-opencode-session");
        String second = connector.headersFor(false).get("x-opencode-session");

        assertThat(first).isEqualTo(second);
    }

    @Test
    public void aConnectorThatDeclaresNoSessionHeaderSendsNone() {
        ProviderConnector zen = new ProviderConnector(
                "opencode", "OpenCode Zen", java.util.List.of("OPENCODE_API_KEY"),
                "https://opencode.ai/zen/v1", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT, null);

        assertThat(zen.getSessionHeaderName()).isNull();
        assertThat(zen.headersFor(false))
                .doesNotContainKey("x-opencode-session");
    }

    @Test
    public void theClientNamesItselfRatherThanTheHttpLibrary() {
        ProviderConnector connector = new ProviderConnector(
                "opencode-go", "OpenCode Go", java.util.List.of("OPENCODE_API_KEY"),
                "https://opencode.ai/zen/go/v1", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT, null).withSessionHeader("x-opencode-session");

        assertThat(connector.headersFor(false))
                .containsEntry("User-Agent", "CadetCoder/" 
                               + com.eonmux.cadetcoder.build.BuildInfo.current().version());
    }

    @Test
    public void aConnectorWithItsOwnUserAgentKeepsIt() {
        ProviderConnector copilot = new ProviderConnector(
                "github-copilot", "GitHub Copilot", java.util.List.of(),
                "https://api.githubcopilot.com", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT,
                java.util.Map.of("Editor-Version", "CadetCoder/1.0",
                                 "Copilot-Integration-Id", "vscode-chat",
                                 "User-Agent", "CopilotAgent/9.9"));

        assertThat(copilot.headersFor(false))
                .containsEntry("User-Agent", "CopilotAgent/9.9");
    }

    @Test
    public void theRegisteredGoConnectorDeclaresTheHeader() {
        ProviderConnector go = ProviderRegistry.getInstance().get("opencode-go");

        assertThat(go.getSessionHeaderName()).isEqualTo("x-opencode-session");
        assertThat(ProviderRegistry.getInstance().get("opencode").getSessionHeaderName())
                .as("Zen asked for no such header, and is sent none")
                .isNull();
    }
}
