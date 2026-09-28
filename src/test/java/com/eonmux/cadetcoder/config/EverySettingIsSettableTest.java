package com.eonmux.cadetcoder.config;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code cadet config <section>.<property> <value>} must change the setting it says it changed.
 *
 * <p>Six of the eight section handlers had no {@code default} branch, so an unrecognised property
 * fell out of the switch and the command still saved the file and printed
 * "Configuration updated: security.readOnlyMode = true". The value stayed false. Since {@code cadet
 * config} lists every property by serialising the whole {@link Configuration}, the user is actively
 * told those names are real -- and the worst affected section was security, where the failure is
 * someone believing they have hardened the tool.</p>
 *
 * <p>This is driven off the setters by reflection rather than a hand-written list, because a
 * hand-written list is the thing that drifted in the first place: a property added to a config
 * section is unreachable from the command line until somebody remembers to add a case for it, and
 * nothing said when they did not.</p>
 */
public class EverySettingIsSettableTest {

    /**
     * Section name as it is typed on the command line, and the getter that returns that section.
     *
     * <p>Read off {@link Configuration} rather than written out, for the reason in the class
     * comment: the written-out version had already lost {@code compaction}, so the section with the
     * fewest eyes on it was the one nothing checked.</p>
     */
    private static final Map<String, String> SECTIONS = discoverSections();

    private static Map<String, String> discoverSections() {
        Map<String, String> sections = new LinkedHashMap<>();
        for (Method getter : Configuration.class.getMethods()) {
            if (!getter.getName().startsWith("get") || getter.getParameterCount() != 0) {
                continue;
            }
            Class<?> returned = getter.getReturnType();
            if (returned.getEnclosingClass() != Configuration.class) {
                continue;
            }
            String name = Character.toLowerCase(getter.getName().charAt(3))
                          + getter.getName().substring(4);
            sections.put(name, getter.getName());
        }
        return sections;
    }

    /** Settings that are not inside any section, named exactly as they are typed. */
    private static List<String> discoverTopLevelSettings() {
        List<String> names = new ArrayList<>();
        for (Method setter : Configuration.class.getMethods()) {
            if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1) {
                continue;
            }
            if (setter.getParameterTypes()[0].getEnclosingClass() == Configuration.class) {
                continue;
            }
            names.add(Character.toLowerCase(setter.getName().charAt(3)) + setter.getName().substring(4));
        }
        return names;
    }

    /**
     * Properties with no {@code <section>.<property> <value>} spelling, and why.
     *
     * <p>Both are per-provider maps rather than scalars. {@code login} owns {@code providerApiKeys}
     * and deliberately refuses a key given as an argument, so routing one through {@code config}
     * would put a credential into the shell's history that {@code login} goes out of its way to
     * keep out of it.</p>
     */
    private static final java.util.Set<String> NOT_SCALAR =
            java.util.Set.of("ai.providerApiKeys", "ai.providerOptions", "ai.modelContextTokens");

    /**
     * Settings whose range rules out the generic sample, with one that is inside it.
     *
     * <p>The generic integer sample is "seven more than the default", which is a different value
     * for every setting and in range for nearly all of them. A setting that names three levels is
     * not one of those, and refusing 8 is the point rather than a defect; neither is a worker count,
     * whose ceiling is the provider's rate limit.</p>
     */
    private static final Map<String, String> IN_RANGE_SAMPLE =
            Map.of("ui.verbosityLevel", "2",
                   "performance.threads", "5",
                   // Two names, not free text: who answers when a command needs approval. The
                   // sample is the one the tool does not ship with, or setting it changes nothing
                   // and this test cannot tell that from a setting that was never applied.
                   "security.commandApproval", "auto");

    /** A value the given setter will accept, chosen so it differs from the shipped default. */
    private static String sampleFor(Class<?> parameterType, Object current) {
        if (parameterType == boolean.class) {
            return String.valueOf(!((Boolean) current));
        }
        if (parameterType == int.class) {
            return String.valueOf(((Integer) current) + 7);
        }
        if (parameterType == float.class || parameterType == double.class) {
            return "0.25";
        }
        if (parameterType == long.class) {
            return String.valueOf(((Long) current) + 7L);
        }
        if (parameterType == String[].class) {
            return "alpha,beta";
        }
        return "cadet-test-value";
    }

    private static Object read(Object section, String propertyName, Class<?> type) throws Exception {
        String suffix = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        for (String prefix : new String[] {"get", "is"}) {
            try {
                Method getter = section.getClass().getMethod(prefix + suffix);
                return getter.invoke(section);
            } catch (NoSuchMethodException keepLooking) {
                // try the other prefix
            }
        }
        throw new NoSuchMethodException("no getter for " + propertyName + " on " + section.getClass());
    }

    @Test
    public void everyPropertyWithASetterCanBeSetFromTheCommandLine() throws Exception {
        List<String> unreachable = new ArrayList<>();

        for (Map.Entry<String, String> entry : SECTIONS.entrySet()) {
            String section = entry.getKey();
            Configuration config = new Configuration();
            Object sectionObject = Configuration.class.getMethod(entry.getValue()).invoke(config);

            for (Method setter : sectionObject.getClass().getMethods()) {
                if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1) {
                    continue;
                }
                String property = Character.toLowerCase(setter.getName().charAt(3))
                                  + setter.getName().substring(4);
                Class<?> type = setter.getParameterTypes()[0];
                if (NOT_SCALAR.contains(section + "." + property)) {
                    continue;
                }

                // apiKey holds a credential; it is exercised elsewhere and not worth writing here.
                Object before = read(sectionObject, property, type);
                String sample = IN_RANGE_SAMPLE.getOrDefault(section + "." + property,
                                                             sampleFor(type, before));
                try {
                    ConfigOverrides.apply(config, section + "." + property, sample);
                } catch (IllegalArgumentException rejected) {
                    unreachable.add(section + "." + property + " (rejected: " + rejected.getMessage() + ")");
                    continue;
                }

                Object after = read(sectionObject, property, type);
                if (type == String[].class) {
                    assertThat((String[]) after).as(section + "." + property).containsExactly("alpha", "beta");
                } else if (String.valueOf(before).equals(String.valueOf(after))) {
                    unreachable.add(section + "." + property + " (accepted but unchanged)");
                }
            }
        }

        assertThat(new TreeSet<>(unreachable))
                .as("cadet config reports success for these and does not apply them, so a user who "
                    + "sets one believes a setting is in effect when it is not")
                .isEmpty();
    }

    @Test
    public void anUnknownPropertyIsRejectedRatherThanSilentlyAccepted() {
        Configuration config = new Configuration();

        for (String section : SECTIONS.keySet()) {
            assertThatThrownBy(() ->
                    ConfigOverrides.apply(config, section + ".notARealProperty", "x"))
                    .as("%s must reject an unknown property instead of saving and claiming success",
                        section)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    public void theRejectionNamesTheValidPropertiesSoTheUserCanRecover() {
        assertThatThrownBy(() ->
                ConfigOverrides.apply(new Configuration(), "security.readOnly", "true"))
                .hasMessageContaining("readOnlyMode");
    }

    /**
     * {@code Boolean.parseBoolean} maps every non-"true" string to false, so a plausible spelling
     * would have turned a protection OFF while reporting that it had been set.
     */
    @Test
    public void aBooleanSettingRefusesAValueThatIsNeitherTrueNorFalse() {
        assertThatThrownBy(() ->
                ConfigOverrides.apply(new Configuration(), "security.readOnlyMode", "yes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true or false");
    }

    @Test
    public void aNumericSettingRefusesTextRatherThanThrowingAParseError() {
        assertThatThrownBy(() ->
                ConfigOverrides.apply(new Configuration(), "ai.maxTokens", "lots"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");
    }

    /** Every section the configuration has must be one the command line can reach. */
    @Test
    public void noSectionIsUnreachable() {
        assertThat(SECTIONS.keySet())
                .as("a section discovered on Configuration but never exercised here is a section "
                    + "nothing checks")
                .isNotEmpty();
        for (String section : SECTIONS.keySet()) {
            assertThatThrownBy(() -> ConfigOverrides.apply(new Configuration(), section + ".x", "y"))
                    .as("%s must be a known section, rejecting only the unknown property", section)
                    .hasMessageContaining("x");
        }
    }

    /**
     * A setting that is not inside a section is still a setting.
     *
     * <p>{@code baseDir} is where the logs, the sessions, the indexes and the downloaded models
     * live. {@code cadet config} lists it under "paths" and {@code cadet config baseDir} prints its
     * value, so the user has been shown the name and told it is real -- and
     * {@link Configuration}'s own class comment names {@code cadet config baseDir <value>} as the
     * way to repair a corrupted one. That command did not exist: every key had to contain a dot,
     * so the only spelling the user could reach was rejected as malformed.</p>
     */
    @Test
    public void everyTopLevelSettingCanBeSetFromTheCommandLine() throws Exception {
        List<String> unreachable = new ArrayList<>();

        for (String property : discoverTopLevelSettings()) {
            Configuration config = new Configuration();
            String        sample = "/tmp/cadet-test-" + property;
            try {
                ConfigOverrides.apply(config, property, sample);
            } catch (IllegalArgumentException rejected) {
                unreachable.add(property + " (rejected: " + rejected.getMessage() + ")");
                continue;
            }
            if (!sample.equals(String.valueOf(read(config, property, String.class)))) {
                unreachable.add(property + " (accepted but unchanged)");
            }
        }

        assertThat(new TreeSet<>(unreachable))
                .as("cadet config prints these names and offers them back as things to set")
                .isEmpty();
    }

    @Test
    public void anUnknownTopLevelSettingIsRefusedAndTheMessageSaysWhatIsValid() {
        assertThatThrownBy(() -> ConfigOverrides.apply(new Configuration(), "notASetting", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseDir");
    }

    /** A blank value restores the default, which is the repair path the class comment names. */
    @Test
    public void aBlankBaseDirRestoresTheDefaultRatherThanBreakingTheNextRun() {
        Configuration config = new Configuration();
        ConfigOverrides.apply(config, "baseDir", "/tmp/cadet-somewhere-else");
        ConfigOverrides.apply(config, "baseDir", "  ");

        assertThat(config.getBaseDir()).isEqualTo(Configuration.defaultBaseDir);
    }
}
