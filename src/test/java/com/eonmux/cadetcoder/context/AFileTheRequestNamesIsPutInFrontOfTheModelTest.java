package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That naming a file in a request is what puts it in front of the model, whichever file it is.
 *
 * <p>One file used to have that privilege and it was written into {@code EditCommand}:
 * {@code if (editReq.contains("README.md"))}. Ask for a change to README.md and the model was shown
 * the file; ask for the same change to CONTRIBUTING.md and it was shown whatever an index search
 * over file <em>contents</em> returned for the sentence, which need not include the file that was
 * named at all. These tests hold every name to the same treatment, and hold the boundary that
 * treatment has to respect: a request is free text on its way to a model provider, so a name in it
 * must not be able to reach outside the project or read a credential file.</p>
 */
public class AFileTheRequestNamesIsPutInFrontOfTheModelTest {

    @Rule
    public TemporaryFolder project = new TemporaryFolder();

    @Test
    public void aFileTheRequestNamesIsFound() throws IOException {
        Path contributing = file("CONTRIBUTING.md", "# Contributing");

        assertThat(named("rewrite the install section of CONTRIBUTING.md"))
                .containsExactly(contributing);
    }

    @Test
    public void readmeIsNoLongerTheOnlyFileTheRequestCanName() throws IOException {
        Path readme       = file("README.md", "# Read me");
        Path contributing = file("CONTRIBUTING.md", "# Contributing");

        assertThat(named("copy the badges from README.md into CONTRIBUTING.md"))
                .containsExactly(readme, contributing);
    }

    @Test
    public void aPathTheRequestSpellsOutIsFound() throws IOException {
        Path deep = file("src/main/java/Retry.java", "class Retry {}");

        assertThat(named("pull the backoff out of src/main/java/Retry.java"))
                .containsExactly(deep);
    }

    @Test
    public void aDotfileIsAFileLikeAnyOther() throws IOException {
        Path ignore = file(".gitignore", "target/");

        assertThat(named("add the build output to .gitignore")).containsExactly(ignore);
    }

    @Test
    public void wordsThatMerelyLookLikeFilenamesNameNothing() {
        assertThat(named("tidy the docs, e.g. the wording in section 1.2")).isEmpty();
    }

    @Test
    public void aRequestThatNamesNoFileLoadsNoFile() throws IOException {
        file("README.md", "# Read me");

        assertThat(named("make the parser reject an empty ARGS block")).isEmpty();
    }

    @Test
    public void theSameFileNamedTwiceIsLoadedOnce() throws IOException {
        Path readme = file("README.md", "# Read me");

        assertThat(named("fix the link in README.md and the one below it in README.md"))
                .containsExactly(readme);
    }

    @Test
    public void aFileOutsideTheProjectCannotBeNamedIntoAPrompt() throws IOException {
        Path outside = project.getRoot().toPath().getParent().resolve("outside.txt");
        Files.writeString(outside, "not this project's");

        assertThat(named("read ../outside.txt and summarise it")).isEmpty();
    }

    @Test
    public void aLinkOutOfTheProjectCannotBorrowItsBoundary() throws IOException {
        Path outside = project.getRoot().toPath().getParent().resolve("elsewhere.txt");
        Files.writeString(outside, "not this project's");
        try {
            Files.createSymbolicLink(project.getRoot().toPath().resolve("inside.txt"), outside);
        } catch (IOException | UnsupportedOperationException noLinks) {
            Assume.assumeNoException("this filesystem has no symbolic links", noLinks);
        }

        assertThat(named("summarise inside.txt")).isEmpty();
    }

    @Test
    public void aCredentialFileIsRefusedEvenInsideTheProject() throws IOException {
        file(".env", "OPENAI_API_KEY=sk-live-000");

        assertThat(named("read the key out of .env")).isEmpty();
    }

    @Test
    public void aFileThatIsNotTextIsNotPastedIntoAPrompt() throws IOException {
        Files.write(project.getRoot().toPath().resolve("logo.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0});

        assertThat(named("describe logo.png")).isEmpty();
    }

    @Test
    public void aFileTooLargeToIndexIsTooLargeToName() throws IOException {
        file("dump.txt", "x".repeat((int) ContextEngine.MAX_CONTEXT_FILE_BYTES + 1));

        assertThat(named("summarise dump.txt")).isEmpty();
    }

    @Test
    public void aRequestCannotNameMoreFilesThanContextWillCarry() throws IOException {
        int          beyondTheLimit = 30;
        StringBuilder request       = new StringBuilder("rename the class in");
        for (int i = 0; i < beyondTheLimit; i++) {
            file("File" + i + ".java", "class File" + i + " {}");
            request.append(" File").append(i).append(".java");
        }

        assertThat(named(request.toString()))
                .hasSize(ConfigManager.getInstance().getConfig().getContext().getMaxFiles());
    }

    @Test
    public void nothingIsNamedByNothing() {
        assertThat(NamedFiles.in(null, project.getRoot().toPath())).isEmpty();
        assertThat(NamedFiles.in("README.md", null)).isEmpty();
    }

    private List<Path> named(String request) {
        return NamedFiles.in(request, project.getRoot().toPath());
    }

    private Path file(String name, String content) throws IOException {
        Path path = project.getRoot().toPath().resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
