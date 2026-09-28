package com.eonmux.cadetcoder;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project says what it is licensed under, and so does everything it ships inside its jar.
 *
 * <h2>The defect</h2>
 *
 * <p>The README claimed "licensed under the MIT License - see the LICENSE file" and there was no
 * LICENSE file, so the project's terms were a sentence pointing at nothing. It is Apache 2.0 now,
 * with the text in {@code LICENSE}.</p>
 *
 * <p>The second half is the one that goes stale on its own. {@code mvn package} shades every
 * dependency into one jar, so distributing that jar distributes their code, and several of their
 * licences require their notices to travel with it. A dependency added later is bundled the moment
 * it is declared, and nothing would have said that {@code NOTICE} had stopped describing what was
 * in the jar. This walks the POM, so it does.</p>
 */
class LicensingTravelsWithTheJarTest {

    private static final Path POM     = Path.of("pom.xml");
    private static final Path LICENSE = Path.of("LICENSE");
    private static final Path NOTICE  = Path.of("NOTICE");

    /** A licence file named in NOTICE, as opposed to the directory being mentioned in prose. */
    private static final java.util.regex.Pattern NAMED_LICENCE =
            java.util.regex.Pattern.compile("licenses/[A-Za-z0-9._-]+\\.txt");

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }

    /**
     * Every dependency that ends up in the shaded jar, by artifact id.
     *
     * <p>Test-scoped ones are left out: they are not in the jar, so nothing about them is being
     * distributed.</p>
     */
    private static List<String> bundledArtifacts() throws IOException {
        String pom   = read(POM);
        int    from  = pom.indexOf("<dependencies>");
        int    to    = pom.indexOf("</dependencies>", from);
        assertThat(from).as("the POM declares dependencies").isGreaterThan(-1);

        List<String> bundled = new ArrayList<>();
        for (String block : pom.substring(from, to).split("<dependency>")) {
            if (block.contains("<scope>test</scope>") || !block.contains("<artifactId>")) {
                continue;
            }
            int idFrom = block.indexOf("<artifactId>") + "<artifactId>".length();
            int idTo   = block.indexOf("</artifactId>", idFrom);
            bundled.add(block.substring(idFrom, idTo).trim());
        }
        return bundled;
    }

    @Test
    void theProjectSaysWhatItIsLicensedUnder() throws IOException {
        assertThat(Files.exists(LICENSE)).as("LICENSE is the file every claim points at").isTrue();

        String licence = read(LICENSE);
        assertThat(licence).contains("Apache License");
        assertThat(licence).contains("Version 2.0, January 2004");
        assertThat(licence).contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION");
        assertThat(licence)
                .as("a licence with no copyright holder names nobody as the licensor")
                .containsPattern("Copyright \\d{4} \\S");
    }

    @Test
    void thepomDeclaresTheSameLicenceTheFileDoes() throws IOException {
        assertThat(read(POM)).contains("<name>Apache License, Version 2.0</name>");
    }

    @Test
    void everyBundledDependencyIsNamedInTheNotice() throws IOException {
        String notice = read(NOTICE).toLowerCase(Locale.ROOT);

        java.util.Set<String> unnamed = new TreeSet<>();
        for (String artifact : bundledArtifacts()) {
            if (!notice.contains(artifact.toLowerCase(Locale.ROOT))) {
                unnamed.add(artifact);
            }
        }

        assertThat(unnamed)
                .as("shaded into the jar and absent from NOTICE: distributing the jar distributes "
                    + "this code, so its licence has to travel with it")
                .isEmpty();
    }

    @Test
    void everyLicenceTheNoticePointsAtIsThere() throws IOException {
        String notice = read(NOTICE);

        java.util.Set<String> missing = new TreeSet<>();
        java.util.regex.Matcher named = NAMED_LICENCE.matcher(notice);
        while (named.find()) {
            if (!Files.exists(Path.of(named.group()))) {
                missing.add(named.group());
            }
        }

        assertThat(missing).as("NOTICE names a licence file that is not in the repository").isEmpty();
    }

    @Test
    void everyLicenceFileIsPointedAtAndSaysSomething() throws IOException {
        String notice = read(NOTICE);

        try (Stream<Path> files = Files.list(Path.of("licenses"))) {
            for (Path licence : files.toList()) {
                assertThat(Files.size(licence)).as(licence + " is empty").isGreaterThan(100L);
                assertThat(notice)
                        .as(licence + " is in the repository but NOTICE does not point at it")
                        .contains("licenses/" + licence.getFileName());
            }
        }
    }

    @Test
    void thebuildPutsTheLicenceAndTheNoticeInsideTheJar() throws IOException {
        String pom = read(POM);

        assertThat(pom).contains("<targetPath>META-INF</targetPath>");
        assertThat(pom).contains("<targetPath>META-INF/licenses</targetPath>");
        assertThat(pom)
                .as("the shade plugin discards dependency LICENSE files, this one included, so "
                    + "ours has to be put back")
                .contains("IncludeResourceTransformer");
        assertThat(pom).contains("ApacheNoticeResourceTransformer");
    }
}
