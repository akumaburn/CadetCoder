package com.eonmux.cadetcoder.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One key this build does not recognise must not cost the user every other setting.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code config.json} was read by a plain Jackson {@code ObjectMapper}, which refuses a file
 * containing any property it cannot map. The whole read then failed, and the caller's answer to a
 * failed read is to start again from the shipped defaults -- so a single unfamiliar key silently
 * withdrew the user's model, provider, API keys, security settings and everything else at once. The
 * key did not have to be a typo: a file written by a newer build, or by an older one whose setting
 * has since been removed, has one, and downgrading or upgrading was enough to lose the lot.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>What the build understands is kept, whatever else the file contains, at the top level and
 * inside a section alike. The keys that were not understood are named rather than passed over,
 * because a typo the tool ignores without a word is a setting the user believes is in effect. A
 * file that is not JSON at all is still a failure: there is nothing in it to keep.</p>
 */
public class ConfigKeepsWhatItUnderstandsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ConfigFile.Loaded read(String json) throws IOException {
        Path file = folder.getRoot().toPath().resolve("config.json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return ConfigFile.read(file);
    }

    @Test
    public void aKeyThisBuildDoesNotKnowDoesNotDiscardTheSettingsAroundIt() throws Exception {
        ConfigFile.Loaded loaded = read("{\"baseDir\":\"/somewhere/of/my/own\","
                                        + "\"aSettingFromSomeOtherBuild\":true,"
                                        + "\"ai\":{\"model\":\"a-model-i-chose\"}}");

        assertThat(loaded.config().getBaseDir()).isEqualTo("/somewhere/of/my/own");
        assertThat(loaded.config().getAi().getModel()).isEqualTo("a-model-i-chose");
    }

    @Test
    public void anUnfamiliarKeyInsideASectionLeavesTheRestOfTheSectionAlone() throws Exception {
        ConfigFile.Loaded loaded = read("{\"context\":{\"maxFiles\":42,"
                                        + "\"somethingThatUsedToBeHere\":[\"a\",\"b\"],"
                                        + "\"maxLinesPerFile\":7}}");

        assertThat(loaded.config().getContext().getMaxFiles()).isEqualTo(42);
        assertThat(loaded.config().getContext().getMaxLinesPerFile()).isEqualTo(7);
    }

    @Test
    public void theKeysThatWereNotUnderstoodAreNamedWhereTheyWereFound() throws Exception {
        ConfigFile.Loaded loaded = read("{\"aSettingFromSomeOtherBuild\":true,"
                                        + "\"context\":{\"somethingThatUsedToBeHere\":1}}");

        assertThat(loaded.unrecognized())
                .as("a key the tool passes over without a word is a setting the user thinks is on")
                .containsExactlyInAnyOrder("aSettingFromSomeOtherBuild",
                                           "context.somethingThatUsedToBeHere");
    }

    @Test
    public void aFileThisBuildUnderstandsCompletelyHasNothingToReport() throws Exception {
        ConfigFile.Loaded loaded = read("{\"baseDir\":\"/somewhere\",\"context\":{\"maxFiles\":3}}");

        assertThat(loaded.unrecognized()).isEmpty();
    }

    @Test
    public void aFileThatIsNotJsonIsStillAFailure() throws Exception {
        Path file = folder.getRoot().toPath().resolve("config.json");
        Files.write(file, "this is not a configuration".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> ConfigFile.read(file))
                .as("there is nothing in it to keep, so the caller must hear about it")
                .isInstanceOf(IOException.class);
    }

    @Test
    public void whatIsWrittenBackIsReadBackTheSame() throws Exception {
        Configuration written = new Configuration();
        written.setBaseDir("/somewhere/of/my/own");
        written.getContext().setMaxFiles(17);

        Path file = folder.getRoot().toPath().resolve("config.json");
        ConfigFile.write(file, written);
        ConfigFile.Loaded loaded = ConfigFile.read(file);

        assertThat(loaded.config().getBaseDir()).isEqualTo("/somewhere/of/my/own");
        assertThat(loaded.config().getContext().getMaxFiles()).isEqualTo(17);
        assertThat(loaded.unrecognized()).isEmpty();
    }
}
