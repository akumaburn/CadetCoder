package com.eonmux.cadetcoder.config;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A path setting that begins with {@code ~} has to mean the user's home directory.
 *
 * <h2>The defect</h2>
 *
 * <p>Java does not expand {@code ~}; a shell does. {@code Main} expanded it for {@code --base-dir}
 * and nowhere else, so {@code config baseDir ~/work} -- which needs no quoting at all in the
 * interactive shell, since this tool's own tokenizer is not a shell -- stored the two characters
 * verbatim. The directory tree was then created relative to the working directory, under a real
 * directory named {@code ~}, while the run reported "Created models directory: ~/work/models" as
 * though it had succeeded.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Expansion belongs to the setting, not to the one command-line flag that remembered to do it.
 * A path arrives from the flag, from {@code config}, and from somebody editing the file by hand,
 * and all three mean the same thing by {@code ~}.</p>
 */
public class APathSettingBeginningWithATildeMeansHomeTest {

    private static final String HOME = System.getProperty("user.home");

    private static Configuration set(String key, String value) {
        Configuration config = new Configuration();
        ConfigOverrides.apply(config, key, value);
        return config;
    }

    @Test
    public void theBaseDirectoryIsStoredAsHomeRatherThanAsALiteralTilde() {
        assertThat(set("baseDir", "~/work/cadet").getBaseDir()).isEqualTo(HOME + "/work/cadet");
    }

    @Test
    public void theLogFileAndTheIndexLocationMeanTheSameThingByIt() {
        assertThat(set("logging.logFile", "~/logs/cadet.log").getLogging().getLogFile())
                .isEqualTo(HOME + "/logs/cadet.log");
        assertThat(set("indexing.indexLocation", "~/idx").getIndexing().getIndexLocation())
                .isEqualTo(HOME + "/idx");
    }

    @Test
    public void aTildeOnItsOwnIsTheHomeDirectory() {
        assertThat(set("baseDir", "~").getBaseDir()).isEqualTo(HOME);
    }

    @Test
    public void aTildeThatIsNotThePathIsLeftWhereItIs() {
        // Only a leading "~/" (or a bare "~") is a home reference. A tilde anywhere else is an
        // ordinary character, and expanding one would rewrite a directory somebody really has.
        assertThat(set("baseDir", "/srv/back~ups").getBaseDir()).isEqualTo("/srv/back~ups");
        assertThat(set("baseDir", "~notauser/work").getBaseDir()).isEqualTo("~notauser/work");
    }

    @Test
    public void aTildeWrittenIntoTheFileByHandMeansHomeToo() {
        Configuration config = new Configuration();
        config.setBaseDir("~/edited-by-hand");

        assertThat(config.getBaseDir())
                .as("a setting is read from the file as often as it is typed")
                .isEqualTo(HOME + "/edited-by-hand");
    }
}
