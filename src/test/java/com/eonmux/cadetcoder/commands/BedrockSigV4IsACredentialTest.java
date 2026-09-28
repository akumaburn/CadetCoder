package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.ai.providers.ProviderRegistry;
import com.eonmux.cadetcoder.auth.AwsSigV4Signer;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

/**
 * Signature V4 is how Amazon Bedrock is normally authenticated, so it counts as being logged in.
 *
 * <p>The connector's credential list held one name, {@code AWS_BEARER_TOKEN_BEDROCK}, and
 * {@code ConnectorSupport} asked that list and nothing else. The backend, meanwhile, calls
 * {@link AwsSigV4Signer.Credentials#fromEnvironment()} and works perfectly well with the
 * {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY} pair that {@code README.md} tells the
 * user to export. So a working Bedrock setup was listed by {@code models providers} as having no
 * credential, and everything gated on that refused to use it.</p>
 *
 * <p>The pair is asked for as a pair, through the one method that already defines what "AWS
 * credentials are present" means. Adding the two names to the connector's {@code env} list would
 * have reported success on either half alone -- and half of a SigV4 credential signs nothing.</p>
 */
public class BedrockSigV4IsACredentialTest {

    private final ProviderConnector bedrock = ProviderRegistry.getInstance().get("amazon-bedrock");

    private Configuration.AiConfig ai;

    @Before
    public void setUp() {
        ai = new Configuration().getAi();
        Assume.assumeTrue("a real Bedrock bearer token in the environment would mask the question",
                          System.getenv("AWS_BEARER_TOKEN_BEDROCK") == null);
    }

    private MockedStatic<AwsSigV4Signer.Credentials> withEnvironmentCredentials() {
        MockedStatic<AwsSigV4Signer.Credentials> creds =
                mockStatic(AwsSigV4Signer.Credentials.class);
        creds.when(AwsSigV4Signer.Credentials::fromEnvironment)
             .thenReturn(new AwsSigV4Signer.Credentials("AKIAEXAMPLEEXAMPLE", "secret", null));
        return creds;
    }

    private MockedStatic<AwsSigV4Signer.Credentials> withNoEnvironmentCredentials() {
        MockedStatic<AwsSigV4Signer.Credentials> creds =
                mockStatic(AwsSigV4Signer.Credentials.class);
        creds.when(AwsSigV4Signer.Credentials::fromEnvironment).thenReturn(null);
        return creds;
    }

    @Test
    public void anAwsKeyPairInTheEnvironmentIsACredential() {
        try (MockedStatic<AwsSigV4Signer.Credentials> ignored = withEnvironmentCredentials()) {
            assertThat(ConnectorSupport.hasCredential(bedrock, ai))
                    .as("the backend signs with these; the setup README documents is a working one")
                    .isTrue();
        }
    }

    @Test
    public void theStatusSaysWhereTheCredentialCameFrom() {
        try (MockedStatic<AwsSigV4Signer.Credentials> ignored = withEnvironmentCredentials()) {
            assertThat(ConnectorSupport.credentialStatus(bedrock, ai))
                    .as("'no credential' beside a working setup sends the user looking for a "
                        + "problem that is not there")
                    .contains("AWS_ACCESS_KEY_ID");
        }
    }

    @Test
    public void bedrockWithNothingExportedIsStillReportedAsUnconfigured() {
        try (MockedStatic<AwsSigV4Signer.Credentials> ignored = withNoEnvironmentCredentials()) {
            assertThat(ConnectorSupport.hasCredential(bedrock, ai)).isFalse();
            assertThat(ConnectorSupport.credentialStatus(bedrock, ai)).isEqualTo("no credential");
        }
    }

    /** Only Bedrock signs; the change must not make every other connector look configured. */
    @Test
    public void noOtherConnectorIsAffectedByAwsCredentials() {
        try (MockedStatic<AwsSigV4Signer.Credentials> ignored = withEnvironmentCredentials()) {
            for (ProviderConnector connector : ProviderRegistry.getInstance().all()) {
                if (connector.getAuthScheme() == com.eonmux.cadetcoder.ai.providers.AuthScheme.AWS_SIGV4
                    || connector.getAuthScheme() == com.eonmux.cadetcoder.ai.providers.AuthScheme.NONE
                    || "github-copilot".equals(connector.getId())) {
                    continue;
                }
                boolean fromEnvironment = connector.getEnv().stream()
                        .anyMatch(name -> System.getenv(name) != null && !System.getenv(name).isBlank());
                assertThat(ConnectorSupport.hasCredential(connector, ai))
                        .as("%s does not sign with AWS credentials", connector.getId())
                        .isEqualTo(fromEnvironment);
            }
        }
    }
}
