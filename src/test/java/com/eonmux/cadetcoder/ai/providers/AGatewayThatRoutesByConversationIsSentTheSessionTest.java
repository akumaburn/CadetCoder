package com.eonmux.cadetcoder.ai.providers;

import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headers a gateway that routes by conversation is sent.
 *
 * <p><b>The defect</b>: OpenCode Go refused every completion with HTTP 400 {@code MissingSessionID}
 * -- "Request is missing x-opencode-session and cannot be routed efficiently". Its contract with a
 * client is: carry a stable session id per conversation, under the names its client sends them,
 * and name yourself in the user agent rather than letting the HTTP library name you. CadetCoder
 * already has the id -- it is the session -- and the connector was registered without either.</p>
 *
 * <p><b>What is locked here</b>: that a connector declaring session headers sends each of them
 * with the current session's id, that one which declares none sends none, that the id is the
 * session's own and stable across the requests of a run, and that a client user agent is named
 * without overwriting a connector that named its own.</p>
 */
public class AGatewayThatRoutesByConversationIsSentTheSessionTest {

    /** The four names OpenCode's own client sends the conversation id under. */
    private static final List<String> OPENCODE_SESSION_HEADERS = List.of(
            "x-opencode-session", "x-opencode-session-id",
            "x-session-affinity", "X-Session-Id");

    @Test
    public void aConnectorDeclaringTheHeadersSendsEachWithTheSessionsOwnId() {
        ProviderConnector connector = new ProviderConnector(
                "opencode-go", "OpenCode Go", List.of("OPENCODE_API_KEY"),
                "https://opencode.ai/inference/go/openai/v1", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT, null).withSessionHeaders(OPENCODE_SESSION_HEADERS);

        Map<String, String> headers = connector.headersFor(false);

        String id = SessionManager.getInstance().getCurrentSessionId();
        assertThat(id).as("the session has an id to send").isNotBlank();
        for (String headerName : OPENCODE_SESSION_HEADERS) {
            assertThat(headers.get(headerName))
                    .as(headerName + " carries the conversation's id, which is what routing and "
                        + "prompt caching key on")
                    .isEqualTo(id);
        }
    }

    @Test
    public void theSessionsIdIsStableAcrossTheRequestsOfARun() {
        ProviderConnector connector = new ProviderConnector(
                "opencode-go", "OpenCode Go", List.of("OPENCODE_API_KEY"),
                "https://opencode.ai/inference/go/openai/v1", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT, null).withSessionHeaders(OPENCODE_SESSION_HEADERS);

        String first  = connector.headersFor(false).get("x-opencode-session");
        String second = connector.headersFor(false).get("x-opencode-session");

        assertThat(first).isEqualTo(second);
    }

    @Test
    public void aConnectorThatDeclaresNoSessionHeadersSendsNone() {
        ProviderConnector anthropic = new ProviderConnector(
                "anthropic", "Anthropic", List.of("ANTHROPIC_API_KEY"),
                "https://api.anthropic.com/v1", AuthScheme.X_API_KEY,
                ConnectorProtocol.ANTHROPIC_MESSAGES, null);

        assertThat(anthropic.getSessionHeaderNames()).isEmpty();
        assertThat(anthropic.headersFor(false))
                .doesNotContainKeys(OPENCODE_SESSION_HEADERS.toArray(String[]::new));
    }

    @Test
    public void theClientNamesItselfRatherThanTheHttpLibrary() {
        ProviderConnector connector = new ProviderConnector(
                "opencode-go", "OpenCode Go", List.of("OPENCODE_API_KEY"),
                "https://opencode.ai/inference/go/openai/v1", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT, null);

        assertThat(connector.headersFor(false))
                .containsEntry("User-Agent", "CadetCoder/"
                               + com.eonmux.cadetcoder.build.BuildInfo.current().version());
    }

    @Test
    public void aConnectorWithItsOwnUserAgentKeepsIt() {
        ProviderConnector copilot = new ProviderConnector(
                "github-copilot", "GitHub Copilot", List.of(),
                "https://api.githubcopilot.com", AuthScheme.BEARER,
                ConnectorProtocol.OPENAI_CHAT,
                Map.of("Editor-Version", "CadetCoder/1.0",
                       "Copilot-Integration-Id", "vscode-chat",
                       "User-Agent", "CopilotAgent/9.9"));

        assertThat(copilot.headersFor(false))
                .containsEntry("User-Agent", "CopilotAgent/9.9");
    }

    @Test
    public void theRegisteredGatewaysDeclareTheHeaders() {
        ProviderRegistry registry = ProviderRegistry.getInstance();

        assertThat(registry.get("opencode-go").getSessionHeaderNames())
                .as("Go refuses a request that names no conversation")
                .containsExactlyElementsOf(OPENCODE_SESSION_HEADERS);
        assertThat(registry.get("opencode").getSessionHeaderNames())
                .as("Zen speaks the same client contract")
                .containsExactlyElementsOf(OPENCODE_SESSION_HEADERS);
        assertThat(registry.get("openai").getSessionHeaderNames())
                .as("OpenAI's own API asks for no session id, and is sent none")
                .isEmpty();
    }
}
