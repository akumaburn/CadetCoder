package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The registry builds one instance of each command and hands it every invocation for the life of
 * the session, so anything a run writes to a field is still there for the next one.
 *
 * <p>{@code grep}, {@code ls} and {@code glob} each answered that with a {@code resetState()} that
 * put every field back by hand before parsing. That is a second copy of the defaults the field
 * declarations already state, and the copy drifted: {@code grep}'s list of credential files the
 * denylist refused was added later and never added to the reset, so a search that met a {@code .env}
 * announced it again on the next search, and again on the one after that, naming files the later
 * searches had never looked at.</p>
 *
 * <p>The defaults are written once, where the fields are declared, and each invocation gets an
 * instance that has never run anything.</p>
 */
public class CommandsDoNotRememberTheirLastRunTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private TestOutputCapture     outputCapture;
    private MockedStatic<ConfigManager> configMock;

    @Before
    public void setUp() {
        Configuration config = new Configuration();
        config.getUi().setColorEnabled(false);
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);
        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(manager);

        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
        configMock.close();
    }

    /** A directory holding a credential file the denylist refuses, and a match beside it. */
    private File directoryWithASecret() throws IOException {
        File dir = tempFolder.newFolder("with-secret");
        Files.writeString(new File(dir, ".env").toPath(), "API_KEY=needle" + System.lineSeparator());
        Files.writeString(new File(dir, "notes.txt").toPath(), "needle" + System.lineSeparator());
        return dir;
    }

    /** A directory holding nothing but ordinary content. */
    private File directoryWithoutOne() throws IOException {
        File dir = tempFolder.newFolder("plain");
        Files.writeString(new File(dir, "notes.txt").toPath(), "needle" + System.lineSeparator());
        return dir;
    }

    private static String[] search(File dir) {
        return new String[] {"--path", dir.getAbsolutePath(), "needle"};
    }

    @Test
    public void grepDoesNotAnnounceCredentialFilesFromAnEarlierSearch() throws IOException {
        GrepCommand grep = new GrepCommand();

        grep.execute(search(directoryWithASecret()));
        assertThat(outputCapture.getAllOutput())
                .as("this search really did meet one, and says so")
                .contains("credential file");

        outputCapture.reset();
        grep.execute(search(directoryWithoutOne()));

        assertThat(outputCapture.getAllOutput())
                .as("the second search never looked at a credential file")
                .doesNotContain("credential file");
    }

    /**
     * The general form, which does not depend on knowing which field leaked.
     *
     * <p>A command that keeps nothing from its last run gives the same answer whether or not it has
     * run before, so the reused instance's output is the fresh instance's output. Stated that way
     * the check covers every field there is, including the ones added after it was written.</p>
     */
    @Test
    public void aReusedInstanceSearchesExactlyAsAFreshOneDoes() throws IOException {
        File first  = directoryWithASecret();
        File second = directoryWithoutOne();

        GrepCommand reused = new GrepCommand();
        reused.execute(search(first));
        outputCapture.reset();
        reused.execute(search(second));
        String afterAnEarlierRun = outputCapture.getAllOutput();

        outputCapture.reset();
        new GrepCommand().execute(search(second));
        String onItsOwn = outputCapture.getAllOutput();

        assertThat(afterAnEarlierRun)
                .as("what a command printed last time must not change what it prints this time")
                .isEqualTo(onItsOwn);
    }

    @Test
    public void lsAndGlobAreFreshTheSameWay() throws IOException {
        File dir = directoryWithoutOne();

        LSCommand ls = new LSCommand();
        ls.execute(new String[] {"--all", dir.getAbsolutePath()});
        outputCapture.reset();
        ls.execute(new String[] {dir.getAbsolutePath()});
        String lsReused = outputCapture.getAllOutput();

        outputCapture.reset();
        new LSCommand().execute(new String[] {dir.getAbsolutePath()});
        assertThat(lsReused)
                .as("the --all of the earlier listing must not survive into this one")
                .isEqualTo(outputCapture.getAllOutput());

        GlobCommand glob = new GlobCommand();
        glob.execute(new String[] {"--path", dir.getAbsolutePath(), "--limit", "1", "*.txt"});
        outputCapture.reset();
        glob.execute(new String[] {"--path", dir.getAbsolutePath(), "*.txt"});
        String globReused = outputCapture.getAllOutput();

        outputCapture.reset();
        new GlobCommand().execute(new String[] {"--path", dir.getAbsolutePath(), "*.txt"});
        assertThat(globReused)
                .as("the --limit of the earlier glob must not survive into this one")
                .isEqualTo(outputCapture.getAllOutput());
    }

    @Test
    public void readIsFreshTheSameWay() throws IOException {
        File file = tempFolder.newFile("chapter.txt");
        Files.writeString(file.toPath(), String.join(System.lineSeparator(),
                "one", "two", "three", "four") + System.lineSeparator());

        ReadCommand read = new ReadCommand();
        read.execute(new String[] {file.getAbsolutePath(), "--limit", "1"});
        outputCapture.reset();
        read.execute(new String[] {file.getAbsolutePath()});
        String reused = outputCapture.getAllOutput();

        outputCapture.reset();
        new ReadCommand().execute(new String[] {file.getAbsolutePath()});

        assertThat(reused)
                .as("the --limit of the earlier read must not survive into this one")
                .isEqualTo(outputCapture.getAllOutput());
    }

    @Test
    public void themeIsFreshTheSameWay() {
        ThemeCommand theme = new ThemeCommand();
        theme.execute(new String[] {"list", "--verbose"});
        outputCapture.reset();
        theme.execute(new String[] {"list"});
        String reused = outputCapture.getAllOutput();

        outputCapture.reset();
        new ThemeCommand().execute(new String[] {"list"});

        assertThat(reused)
                .as("the --verbose of the earlier listing must not survive into this one")
                .isEqualTo(outputCapture.getAllOutput());
    }

    /**
     * One statement of the defaults, and it is the field declarations.
     *
     * <p>A {@code resetState()} is the second statement of them, and the one that goes out of date:
     * a field added to the command is reset only if somebody remembers, and nothing says when they
     * do not.</p>
     */
    @Test
    public void noCommandRestatesItsDefaultsInAResetMethod() throws IOException {
        try (Stream<Path> sources = Files.walk(Paths.get("src", "main", "java"))) {
            List<Path> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(CommandsDoNotRememberTheirLastRunTest::declaresAReset)
                    .collect(Collectors.toList());

            assertThat(offenders)
                    .as("a per-invocation instance has nothing to reset")
                    .isEmpty();
        }
    }

    private static boolean declaresAReset(Path source) {
        return read(source).contains("void resetState()");
    }

    /**
     * The rule itself, rather than one of the shapes it has been broken in.
     *
     * <p>Looking for {@code resetState()} finds only commands that named the method that. {@code push}
     * and {@code undo} wrote the same reset inline at the top of {@code execute} and {@code theme}
     * wrote it in one called {@code parseArguments} -- three more copies of the defaults, on the
     * command where the copy that drifts hard-resets the working tree.</p>
     *
     * <p>So this asks what the reset was for. A command either hands the invocation to an instance
     * that has never run one, in which case nothing it writes can outlive the run, or it writes none
     * of its option fields at all. Anything else is a run that can see what the last one typed.</p>
     */
    @Test
    public void noCommandWritesItsOptionFieldsWhileRunning() throws IOException {
        try (Stream<Path> sources = Files.walk(Paths.get("src", "main", "java"))) {
            List<String> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(CommandsDoNotRememberTheirLastRunTest::whatARunCanLeaveBehind)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            assertThat(offenders)
                    .as("a run gets its own instance, or it writes none of its options")
                    .isEmpty();
        }
    }

    private static final Pattern OPTION_ANNOTATION = Pattern.compile("@(?:Option|Parameters)\\s*\\(");

    /** The field an {@code @Option} or {@code @Parameters} annotation is attached to. */
    private static final Pattern ANNOTATED_FIELD = Pattern.compile(
            "\\s*(?:@\\w+\\s*(?:\\([^)]*\\))?\\s*)*(?:public|private|protected)?\\s*"
            + "(?:static\\s+|final\\s+|transient\\s+)*[\\w.$]+(?:<[^;=]*>)?(?:\\[\\])?\\s+(\\w+)\\s*[;=]");

    /** A method declaration, up to and including the parenthesis its parameter list opens with. */
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "(?:public|private|protected)\\s+(?:static\\s+|final\\s+|synchronized\\s+)*"
            + "[\\w.$<>\\[\\], ]+?\\s+(\\w+)\\s*\\(");

    /** A name being called, so the walk can follow {@code execute} into the methods it uses. */
    private static final Pattern CALL = Pattern.compile("(?<![\\w.])(\\w+)\\s*\\(");

    /** What is between a parameter list and the body: a {@code throws} clause, or nothing. */
    private static final Pattern THROWS_CLAUSE = Pattern.compile("\\s*(?:throws\\s+[\\w.,\\s]+)?\\s*");

    /**
     * @param source a main source file
     * @return what a run of this command leaves set for the next one, or {@code null} when it is not
     *         a registry command, has no options, or leaves nothing
     */
    private static String whatARunCanLeaveBehind(Path source) {
        String text = read(source);
        String type = source.getFileName().toString().replace(".java", "");
        if (!declaresARegistryCommand(text, type)) {
            return null;
        }

        Set<String>               options = optionFields(text);
        Map<String, List<String>> bodies  = methodBodies(text);
        List<String>              entry   = bodies.get("execute");
        if (options.isEmpty() || entry == null) {
            return null;
        }

        // A run handed an instance that has never run anything cannot leave anything for the next
        // one, whatever it writes to it.
        if (entry.stream().anyMatch(body -> constructs(body, type))) {
            return null;
        }

        Set<String>   written = new TreeSet<>();
        Set<String>   visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(entry);
        while (!pending.isEmpty()) {
            String body = pending.pop();
            for (String option : options) {
                if (assignsTo(body, option)) {
                    written.add(option);
                }
            }
            Matcher calls = CALL.matcher(body);
            while (calls.find()) {
                List<String> called = bodies.get(calls.group(1));
                if (called != null && visited.add(calls.group(1))) {
                    pending.addAll(called);
                }
            }
        }
        return written.isEmpty() ? null : type + " leaves " + written + " set for the next run";
    }

    private static boolean declaresARegistryCommand(String text, String type) {
        Matcher header = Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:final\\s+|abstract\\s+)*class\\s+"
                                         + Pattern.quote(type) + "\\b[^{]*\\{").matcher(text);
        return header.find() && header.group().contains("CommandRegistry.Command");
    }

    private static boolean constructs(String body, String type) {
        return Pattern.compile("\\bnew\\s+" + Pattern.quote(type) + "\\s*\\(").matcher(body).find();
    }

    /**
     * Whether the body assigns to the field, rather than declaring a local that happens to share its
     * name -- which {@code todowrite} does, with a {@code Status status} beside its {@code --status}.
     */
    private static boolean assignsTo(String body, String field) {
        return Pattern.compile("(?m)(?:^[ \\t]*|[;{}]\\s*|\\)\\s*)(?:this\\.)?" + Pattern.quote(field)
                               + "\\s*(?:=(?!=)|\\+\\+|--|\\+=)").matcher(body).find();
    }

    private static Set<String> optionFields(String text) {
        Set<String> names       = new LinkedHashSet<>();
        Matcher     annotations = OPTION_ANNOTATION.matcher(text);
        while (annotations.find()) {
            int closed = matching(text, annotations.end() - 1, '(', ')');
            if (closed < 0) {
                continue;
            }
            Matcher field = ANNOTATED_FIELD.matcher(
                    text.substring(closed + 1, Math.min(text.length(), closed + 400)));
            if (field.lookingAt()) {
                names.add(field.group(1));
            }
        }
        return names;
    }

    private static Map<String, List<String>> methodBodies(String text) {
        Map<String, List<String>> bodies       = new HashMap<>();
        Matcher                   declarations = METHOD_DECLARATION.matcher(text);
        while (declarations.find()) {
            int parameters = matching(text, declarations.end() - 1, '(', ')');
            if (parameters < 0) {
                continue;
            }
            int opening = text.indexOf('{', parameters);
            // An abstract or interface declaration ends at a semicolon and has no body to walk.
            if (opening < 0 || !THROWS_CLAUSE.matcher(text.substring(parameters + 1, opening)).matches()) {
                continue;
            }
            int closing = matching(text, opening, '{', '}');
            if (closing < 0) {
                continue;
            }
            bodies.computeIfAbsent(declarations.group(1), name -> new ArrayList<>())
                    .add(text.substring(opening, closing + 1));
        }
        return bodies;
    }

    /** The index of the delimiter closing the one at {@code start}, or -1 when it is unbalanced. */
    private static int matching(String text, int start, char open, char close) {
        int depth = 0;
        for (int i = Math.max(start, 0); i < text.length(); i++) {
            if (text.charAt(i) == open) {
                depth++;
            } else if (text.charAt(i) == close && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + source, e);
        }
    }
}
