package com.eonmux.cadetcoder.security;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The redactor has to know the shapes of the keys the shipped connectors actually use.
 *
 * <p>{@code SecretRedactor} says it recognises "the common provider prefixes", and
 * {@code ProviderRegistry} says which providers those are. The two lists were written
 * independently and drifted: {@code sk-} and {@code ghp_} were covered, while Google
 * ({@code AIza}), Groq ({@code gsk_}), xAI ({@code xai-}) and the temporary AWS access key ids
 * ({@code ASIA}) that {@code AwsSigV4Signer.Credentials} exists to carry were not. Every log line,
 * session transcript and terminal echo went through a net with those five holes in it.</p>
 */
public class ProviderKeyShapesAreRecognisedTest {

    /**
     * @param provider the {@code ProviderRegistry} id whose key shape this is
     * @param key      a key of that shape, structurally valid and entirely invented
     */
    private void masks(String provider, String key) {
        assertThat(SecretRedactor.redact("the provider refused: " + key))
                .as("%s ships a connector, so its key shape must be recognised", provider)
                .doesNotContain(key);
    }

    @Test
    public void theKeyShapesOfTheShippedConnectorsAreAllRecognised() {
        masks("openai", "sk-proj-Ab12Cd34Ef56Gh78Ij90Kl12Mn34");
        masks("anthropic", "sk-ant-api03-Ab12Cd34Ef56Gh78Ij90");
        masks("google", "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q");
        masks("groq", "gsk_Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56");
        masks("xai", "xai-Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56");
        masks("commandcode", "user_5Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56Qr78St90Uv12Wx34Yz56Ab78Cd90Ef12Gh34Ij56Kl78Mn90");
        masks("github-copilot", "gho_Ab12Cd34Ef56Gh78Ij90Kl12");
        masks("amazon-bedrock", "AKIAIOSFODNN7EXAMPLE");
        masks("amazon-bedrock, temporary credentials", "ASIAIOSFODNN7EXAMPLE");
    }

    /** The masked line still has to say what was removed. */
    @Test
    public void whatWasRemovedIsStillIdentifiable() {
        assertThat(SecretRedactor.redact("key AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q rejected"))
                .contains("AIza")
                .contains("***");
    }

    @Test
    public void ordinaryProseIsLeftAlone() {
        String prose = "AIzawa reviewed the gsk file and the xai-team notes";
        assertThat(SecretRedactor.redact(prose)).isEqualTo(prose);
    }

    /**
     * The Command Code shape is the one that had to be told apart from ordinary identifiers rather
     * than from ordinary prose: "user_" is a field-name prefix in most codebases, and a pattern
     * that keyed on it alone would strike a column name out of every schema the tool ever read.
     */
    @Test
    public void identifiersThatBeginLikeACommandCodeKeyAreNotOne() {
        String code = "user_id, user_name, user_profile_settings_override = load(user_)";

        assertThat(SecretRedactor.redact(code)).isEqualTo(code);
    }
}
