package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.PromptBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That {@code edit} loads the files its request names, and not one file it was told to favour.
 *
 * <p>{@code EditCommand} used to hold {@code if (editReq.contains("README.md"))}, and that was the
 * whole of it: README.md was read into the prompt whole, and a request naming any other file was
 * left to an index search that matches on file contents rather than on file names. The model was
 * then asked to rewrite a file it had not been shown. This test class holds the wiring that
 * replaced it -- every name gets the same treatment, and a request that names nothing loads
 * nothing.</p>
 */
public class EditShowsTheModelTheFilesTheRequestNamesTest {

    @Rule
    public TemporaryFolder project = new TemporaryFolder();

    private final EditCommand edit = new EditCommand();

    @Test
    public void aFileTheRequestNamesIsInThePromptTheModelIsSent() throws IOException {
        file("CONTRIBUTING.md", "Run the tests before opening a pull request.");
        String request = "soften the tone of CONTRIBUTING.md";

        assertThat(promptFor(request)).contains("CONTRIBUTING.md")
                                      .contains("Run the tests before opening a pull request.");
    }

    @Test
    public void readmeGetsNoTreatmentTheOtherFilesDoNotGet() throws IOException {
        file("README.md", "README says this.");
        file("CONTRIBUTING.md", "CONTRIBUTING says this.");

        assertThat(promptFor("say the same thing in README.md and CONTRIBUTING.md"))
                .contains("README says this.")
                .contains("CONTRIBUTING says this.");
    }

    @Test
    public void aFileTheRequestDoesNotNameIsLeftToTheContextSearch() throws IOException {
        file("README.md", "README says this.");

        assertThat(promptFor("make the action parser reject an empty ARGS block"))
                .doesNotContain("README says this.");
    }

    private String promptFor(String request) {
        PromptBuilder promptBuilder = new PromptBuilder("edit", "system", "reminder");
        edit.loadContextFiles(request, promptBuilder, project.getRoot().toPath());
        return promptBuilder.buildUserPrompt(request);
    }

    private void file(String name, String content) throws IOException {
        Files.writeString(project.getRoot().toPath().resolve(name), content);
    }
}
