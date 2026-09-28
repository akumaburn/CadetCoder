package com.eonmux.cadetcoder.security;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which files CadetCoder refuses to read out loud.
 *
 * <p>Two gaps, both found by asking what the tool itself treats as a secret elsewhere.
 * {@code ~/.cadet/copilot-auth.json} holds a GitHub OAuth token and {@code GitHubCopilotAuth}
 * chmods it owner-only for exactly that reason -- yet only its sibling {@code config.json} was on
 * the denylist, so {@code read} printed the token. {@code ~/.git-credentials} stores passwords in
 * cleartext by design and was not listed at all.</p>
 *
 * <p>{@code .ssh/} and {@code .gnupg/} were a third kind of gap: {@code ReadCommand} carried that
 * rule privately, so {@code read} refused them and the credential check {@code bash} consults did
 * not know about them. A denylist that depends on which command you go through is one rule
 * pretending to be two.</p>
 *
 * <p>The same question asked once more found two more. {@code CommitCommand} keeps its own list of
 * what holds a secret, and warns before staging {@code .env}, {@code .key}, {@code .p12},
 * {@code .pfx} and {@code .keystore} files -- none of which this denylist knew about, so
 * {@code read} printed the project's API keys and database passwords, and sent them to the model
 * provider, while {@code commit} was warning about the very same file. {@code .pem} was listed on
 * its own, which meant one private key was refused as PEM and printed as PKCS#12.</p>
 */
public class CredentialFileDenylistTest {

    private final SecurityValidator validator = new SecurityValidator();

    private void refuses(String path) {
        assertThat(validator.isSensitiveCredentialFile(path))
                .as("%s holds credentials and must never be read or echoed", path)
                .isTrue();
    }

    private void allows(String path) {
        assertThat(validator.isSensitiveCredentialFile(path))
                .as("%s is ordinary project content and must stay readable", path)
                .isFalse();
    }

    @Test
    public void cadetsOwnStoredTokensAreRefused() {
        refuses("/home/someone/.cadet/copilot-auth.json");
        refuses("/home/someone/.cadet/config.json");
    }

    @Test
    public void wellKnownCredentialFilesAreRefused() {
        refuses("/home/someone/.git-credentials");
        refuses("/home/someone/.pgpass");
        refuses("/home/someone/.netrc");
        refuses("/home/someone/.aws/credentials");
        refuses("/home/someone/certs/server.pem");
    }

    /** The rule ReadCommand kept to itself. */
    @Test
    public void anythingUnderAKeyDirectoryIsRefused() {
        refuses("/home/someone/.ssh/id_rsa");
        refuses("/home/someone/.ssh/id_rsa_backup");
        refuses("/home/someone/.ssh/config");
        refuses("/home/someone/.gnupg/secring.gpg");
    }

    @Test
    public void theSameAnswerIsGivenToTheShellRunner() {
        assertThat(validator.commandReferencesCredentialFile(
                "cat /home/someone/.cadet/copilot-auth.json")).isTrue();
        assertThat(validator.commandReferencesCredentialFile(
                "cat /home/someone/.git-credentials")).isTrue();
        assertThat(validator.commandReferencesCredentialFile(
                "cat /home/someone/.ssh/id_rsa_backup"))
                .as("read refused this; bash must refuse it for the same reason")
                .isTrue();
    }

    /** Where a project actually keeps its API keys and database passwords. */
    @Test
    public void environmentFilesAreRefused() {
        refuses("/home/someone/project/.env");
        refuses("/home/someone/project/.env.local");
        refuses("/home/someone/project/.env.production");
        refuses("/home/someone/project/deploy/prod.env");
    }

    /**
     * The committed placeholder is not the secret.
     *
     * <p>{@code .env.example} and its siblings hold variable NAMES without values and are checked
     * in on purpose; refusing them would block ordinary work and protect nothing.</p>
     */
    @Test
    public void anEnvironmentTemplateIsStillReadable() {
        allows("/home/someone/project/.env.example");
        allows("/home/someone/project/.env.sample");
        allows("/home/someone/project/.env.template");
        allows("/home/someone/project/.env.dist");
    }

    /** A private key is a private key whichever container it is encoded in. */
    @Test
    public void keyMaterialIsRefusedWhateverContainerItIsIn() {
        refuses("/home/someone/project/certs/server.key");
        refuses("/home/someone/project/certs/client.p12");
        refuses("/home/someone/project/certs/client.pfx");
        refuses("/home/someone/project/certs/app.keystore");
        refuses("/home/someone/project/certs/app.jks");
    }

    @Test
    public void theShellRunnerRefusesThemToo() {
        assertThat(validator.commandReferencesCredentialFile("cat /home/someone/project/.env"))
                .as("read refused this; bash must refuse it for the same reason")
                .isTrue();
        assertThat(validator.commandReferencesCredentialFile(
                "openssl pkcs12 -in /home/someone/project/certs/client.p12")).isTrue();
        assertThat(validator.commandReferencesCredentialFile(
                "cat /home/someone/project/.env.example"))
                .as("the template holds no values and must stay usable")
                .isFalse();
    }

    @Test
    public void ordinaryProjectFilesAreStillReadable() {
        allows("/home/someone/project/src/main/java/Main.java");
        allows("/home/someone/project/config.json");
        allows("/home/someone/project/README.md");
        allows("/home/someone/project/.cadet-notes/plan.md");
        allows("/home/someone/project/.envrc");
        allows("/home/someone/project/src/environment.ts");
        allows("/home/someone/project/scripts/env");
    }

    /**
     * A directory whose NAME merely ends in a protected one is a different directory.
     *
     * <p>The same string-prefix mistake was already found once in this class's containment check,
     * where {@code /w/project-secrets} counted as inside {@code /w/project}.</p>
     */
    @Test
    public void aDirectoryThatMerelyLooksLikeAKeyDirectoryIsNotRefused() {
        allows("/home/someone/project/notssh/notes.txt");
        allows("/home/someone/project/my.ssh.docs/readme.md");
    }
}
