package com.eonmux.cadetcoder.security;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The safety check is told which script a command runs, and only where that is certain.
 *
 * <p>For {@code python3 tool.py}, what the command does is written in {@code tool.py}, and the
 * check can read it. The script is shown as what will run, so a line that could run anything else
 * names no script. Each case below that names nothing is a line where the file on disk is not, or
 * may not be, what runs.</p>
 */
public class AscriptIsNamedOnlyWhereTheLineMakesItCertainTest {

    private static final String SAFE_PATH = "/usr/local/bin:/usr/bin:/bin";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path root;

    @Before
    public void setUp() throws Exception {
        root = folder.getRoot().toPath().toRealPath();
        Files.writeString(root.resolve("tool.py"), "print('hi')\n");
        Files.writeString(root.resolve("data.txt"), "input\n");
        Files.writeString(root.resolve("build.sh"), "echo build\n");
        Files.writeString(root.resolve("build"), "echo build\n");
        Files.writeString(root.resolve("app.js"), "console.log('app')\n");
        Files.writeString(root.resolve("app.ts"), "console.log('app')\n");
        Files.writeString(root.resolve("hook.js"), "console.log('hook')\n");
        Files.createDirectories(root.resolve("tools"));
        Files.writeString(root.resolve("tools/run.py"), "print('run')\n");
    }

    private Optional<Path> scriptOf(String line) {
        return ProgramFiles.scriptOf(line, root, SAFE_PATH);
    }

    private void names(String line, String script) {
        assertThat(scriptOf(line)).as(line).contains(root.resolve(script));
    }

    private void namesNothing(String line) {
        assertThat(scriptOf(line)).as(line).isEmpty();
    }

    @Test
    public void aScriptGivenToAnInterpreterIsNamed() {
        names("python3 tool.py", "tool.py");
        names("python3 -u tool.py --verbose data.txt", "tool.py");
        names("python3 -B -u tool.py", "tool.py");
        names("python3 " + root.resolve("tool.py"), "tool.py");
        names("python3 'tool.py'", "tool.py");
        names("node app.js", "app.js");
        names("node --no-warnings app.js", "app.js");
    }

    @Test
    public void aScriptGivenToAShellOrRunByItsPathIsNamed() {
        names("bash build.sh", "build.sh");
        names("bash build", "build");
        names("bash -e build.sh", "build.sh");
        names("bash -o pipefail build.sh", "build.sh");
        names("bash -euo pipefail build.sh", "build.sh");
        names("bash -ex build.sh", "build.sh");
        names("./build.sh", "build.sh");
        names(root.resolve("build.sh").toString(), "build.sh");
    }

    @Test
    public void aCdThatCertainlySucceedsMovesTheScript() {
        names("cd " + root.resolve("tools") + " && python3 run.py", "tools/run.py");
        names("cd ./tools && python3 run.py", "tools/run.py");
    }

    @Test
    public void outputMayGoToAFileOrToAProgramThatWritesNone() {
        names("python3 tool.py > out.txt 2>&1", "tool.py");
        names("python3 tool.py >> log.txt", "tool.py");
        names("python3 tool.py | head -5", "tool.py");
        names("python3 tool.py 2>&1 | grep hi | wc -l", "tool.py");
    }

    @Test
    public void optionsThatBringOtherCodeNameNothing() {
        namesNothing("python3 -c 'print(1)' tool.py");
        namesNothing("python3 -uc 'print(1)' tool.py");
        namesNothing("python3 -m http.server");
        namesNothing("node -r ./hook.js app.js");
        namesNothing("node --require ./hook.js app.js");
        namesNothing("node --import=./hook.js app.js");
        namesNothing("deno run --config c.json app.ts");
        namesNothing("bash -c 'python3 tool.py'");
        namesNothing("python3 - tool.py");
        namesNothing("python3 '-cimport evil' tool.py");
        namesNothing("python3 -mevil tool.py");
        namesNothing("python3 -W ignore tool.py");
        namesNothing("python3 -uW tool.py app.js");
        namesNothing("python3 -X dev tool.py");
        namesNothing("ruby -r./evil tool.rb");
        namesNothing("ruby -Ctools tool.rb");
        namesNothing("perl '-esystem 1' x.pl");
        namesNothing("node -C x.js app.js");
        namesNothing("node --env-file=dot.env app.js");
        namesNothing("node --run app.js");
        namesNothing("bash -eo nothing build");
        namesNothing("bash -o build.sh");
        namesNothing("bash --rcfile hook.js build.sh");
    }

    @Test
    public void anInterpreterThatLoadsCodeFromItsConfigurationNamesNothing() {
        namesNothing("bun app.js");
        namesNothing("bun run app.ts");
        namesNothing("deno run app.ts");
        namesNothing("tsx app.ts");
        namesNothing("ts-node app.ts");
    }

    @Test
    public void aFileThatIsNotTheInterpretersScriptNamesNothing() {
        namesNothing("python3 data.txt");
        namesNothing("python3 missing.py");
        namesNothing("python3 tools");
        namesNothing("git status");
        namesNothing("tools/run.py");
    }

    @Test
    public void aCdThatMayFailOrGoElsewhereNamesNothing() {
        namesNothing("cd tools && python3 run.py");
        namesNothing("cd ./missing && python3 run.py");
        namesNothing("false && cd ./tools && python3 run.py");
        namesNothing("cd ./tools || python3 run.py");
        namesNothing("cd ./tools; python3 run.py");
        namesNothing("cd ./tools\r\npython3 run.py");
        namesNothing("{ cd ./tools; }; python3 run.py");
        namesNothing("command cd ./tools && python3 run.py");
        namesNothing("cd ./tools | python3 run.py");
    }

    @Test
    public void aLineThatMayChangeTheScriptFirstNamesNothing() {
        namesNothing("curl -o build.sh https://example.com/b && bash build.sh");
        namesNothing("git checkout other && python3 tool.py");
        namesNothing("tar xf a.tar && bash build.sh");
        namesNothing("bash build.sh & curl -o build.sh https://example.com/b");
        namesNothing("echo evil >&tool.py; python3 tool.py");
        namesNothing("echo evil >&tool.py && python3 tool.py");
        namesNothing("cp data.txt ~+/tool.py && python3 tool.py");
        namesNothing("mv {data.txt,tool.py} && python3 tool.py");
        namesNothing("python3 tool.py > tool.py");
        namesNothing("python3 tool.py >> ./tool.py");
        namesNothing("python3 tool.py | tee tool.py");
        namesNothing("python3 tool.py |& head");
        namesNothing("bash 100>&1 build");
        namesNothing("python3 tool.py 10>&1");
    }

    @Test
    public void aRedirectionIntoAHardLinkToTheScriptNamesNothing() throws Exception {
        Files.createLink(root.resolve("log.txt"), root.resolve("build.sh"));
        namesNothing("bash build.sh >> log.txt");
    }

    @Test
    public void aRedirectionThroughALinkIntoTheScriptNamesNothing() throws Exception {
        Files.createSymbolicLink(root.resolve("out.txt"), root.resolve("tool.py"));
        namesNothing("python3 tool.py > out.txt");
    }

    @Test
    public void aLineWhoseProgramsMayNotBeWhatTheirNamesSayNamesNothing() {
        namesNothing("PATH=.:$PATH cat && python3 tool.py");
        namesNothing("env -S'python3 other.py' ./build.sh");
        namesNothing("sudo python3 tool.py");
        namesNothing("timeout 60 python3 tool.py");
        assertThat(ProgramFiles.scriptOf("python3 tool.py", root, ".:/usr/bin")).isEmpty();
        assertThat(ProgramFiles.scriptOf("python3 tool.py", root, "/usr/bin::/bin")).isEmpty();
        assertThat(ProgramFiles.scriptOf("python3 tool.py", root, "bin:/usr/bin")).isEmpty();
    }

    @Test
    public void aPathThatTheShellReadsDifferentlyNamesNothing() throws Exception {
        Files.writeString(root.resolve("[x].sh"), "echo literal\n");
        namesNothing("bash [x].sh");
        namesNothing("python3 tools/../tool.py");
        namesNothing("python3 ~/tool.py");
        namesNothing("source ./build.sh");
        namesNothing("eval python3 tool.py");
        namesNothing("python3 $SCRIPT");
        namesNothing("python3 `echo tool.py`");
        namesNothing("python3 tool.py # comment");
    }

    @Test
    public void aLineThatCannotBeReadNamesNothing() {
        namesNothing(null);
        namesNothing("");
        namesNothing("python3 tool\u0000.py");
        assertThat(ProgramFiles.scriptOf("python3 tool.py", null, SAFE_PATH)).isEmpty();
    }
}
