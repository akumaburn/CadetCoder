package com.eonmux.cadetcoder.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A setting the tool accepts is a setting the tool acts on.
 *
 * <h2>The defect</h2>
 *
 * <p>Ten settings could be written -- through {@code cadet config}, through the configuration file,
 * and listed back by {@code cadet config} as though they were real -- while nothing anywhere read
 * them. {@code config performance.threads 8} answered "Configuration updated" and changed nothing;
 * {@code config git.includeCommitHistory true} promised the model would be told about the history
 * and told it nothing. A setting that does nothing is worse than a missing one, because the user
 * believes they have configured something.</p>
 *
 * <p>This walks {@code src/main/java} rather than trusting a list, so a getter added tomorrow and
 * never read fails here rather than reaching a user. A setting genuinely read nowhere must be
 * retired through {@link RetiredSettings} and taken out of {@link Configuration}; the only entries
 * below are the ones whose absence from the source is itself the correct answer.</p>
 */
class EverySettingTheToolOffersDoesSomethingTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /** The one file that may not count as a reader: a class reading its own fields proves nothing. */
    private static final String DECLARING_FILE = "Configuration.java";

    /**
     * Getters no production code calls by name, and why that is right.
     *
     * <p>The bar is that the SETTING does something, not that this particular method is called. A
     * setting exposed through a second, derived accessor is read; one exposed through nothing at
     * all is not.</p>
     */
    private static final Map<String, String> READ_BY_ANOTHER_NAME = Map.of(
            "getMaxFileContentSize",
            "read as getMaxFileContentBytes(), which is the unit every caller compares against");

    private static String sourcesOfEverythingButConfiguration() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                                  .filter(p -> !p.getFileName().toString().equals(DECLARING_FILE))
                                  .toList()) {
                all.append(Files.readString(file)).append('\n');
            }
        }
        return all.toString();
    }

    private static List<Method> gettersOf(Class<?> type) {
        List<Method> getters = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            boolean named = method.getName().startsWith("get") || method.getName().startsWith("is");
            if (named && Modifier.isPublic(method.getModifiers()) && method.getParameterCount() == 0) {
                getters.add(method);
            }
        }
        return getters;
    }

    private static List<Class<?>> theSections() {
        List<Class<?>> sections = new ArrayList<>();
        sections.add(Configuration.class);
        for (Class<?> nested : Configuration.class.getDeclaredClasses()) {
            if (Modifier.isPublic(nested.getModifiers())) {
                sections.add(nested);
            }
        }
        return sections;
    }

    @Test
    void nosettingCanBeWrittenThatNothingEverReads() throws IOException {
        String production = sourcesOfEverythingButConfiguration();

        Set<String> unread = new TreeSet<>();
        for (Class<?> section : theSections()) {
            for (Method getter : gettersOf(section)) {
                String call = getter.getName() + "()";
                if (READ_BY_ANOTHER_NAME.containsKey(getter.getName())) {
                    continue;
                }
                if (!production.contains(call)) {
                    unread.add(section.getSimpleName() + "." + call);
                }
            }
        }

        assertThat(unread)
                .as("settable but never read: either implement it or retire it through "
                    + "RetiredSettings -- do not leave it accepting values it ignores")
                .isEmpty();
    }

    /** The exceptions list stays honest: an entry naming a getter that is gone is dead weight. */
    @Test
    void everyNamedExceptionIsStillAgetterThatExists() {
        Set<String> declared = new TreeSet<>();
        for (Class<?> section : theSections()) {
            gettersOf(section).forEach(getter -> declared.add(getter.getName()));
        }

        assertThat(declared).containsAll(READ_BY_ANOTHER_NAME.keySet());
    }
}
