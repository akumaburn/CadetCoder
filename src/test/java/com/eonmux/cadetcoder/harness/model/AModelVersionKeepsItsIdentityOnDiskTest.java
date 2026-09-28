package com.eonmux.cadetcoder.harness.model;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A model version is named by its source and can never come to mean a different source.
 *
 * <p>The failure this locks out: an agent edits its model, keeps planning against the certificate the old model earned, and commits a plan nothing
 * ever certified. Naming a version by the digest of its text makes that impossible to express --
 * a certificate names a digest, and the digest names exactly one source forever.</p>
 *
 * <p>The second thing the store is for is noticing epicycles. A model that keeps growing while the
 * set of rules reality has actually tested stays where it was is being patched, not corrected, and
 * the store is the only place that can see that, because it is the only place that remembers what
 * the previous versions were.</p>
 */
public class AModelVersionKeepsItsIdentityOnDiskTest {

    @Rule
    public TemporaryFolder workspace = new TemporaryFolder();

    private static final String COUNTER = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.d}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 3; }
            """;

    private ModelRegistry registry() {
        return new ModelRegistry(workspace.getRoot().toPath());
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            value.put((String) pairs[i], pairs[i + 1]);
        }
        return value;
    }

    /** A model that says the same thing in more rules, for measuring complexity growth. */
    private static String counterWithGuards(int guards) {
        StringBuilder source = new StringBuilder("fn parse(obs) { return {\"pos\": obs.pos}; }\n");
        source.append("fn step(state, action) {\n");
        for (int d = 0; d < guards; d++) {
            source.append("  if (action.d == ").append(d)
                  .append(") { return {\"pos\": state.pos + ").append(d).append("}; }\n");
        }
        source.append("  return {\"pos\": state.pos + action.d};\n}\n");
        source.append("fn predict(state) { return {\"pos\": state.pos}; }\n");
        source.append("fn is_goal(state) { return state.pos >= 3; }\n");
        return source.toString();
    }

    /** A certificate as certification writes one: what matters here is the covered-arm count. */
    private static Map<String, Object> certificate(int covered) {
        return map("coverage", map("covered", covered));
    }

    @Test
    public void aSavedModelIsAddressedByTheDigestOfItsSource() {
        ModelRegistry registry = registry();

        WorldModel saved = registry.save(COUNTER, 0, "first try");

        assertThat(saved.digest()).isEqualTo(WorldModel.load(COUNTER).digest());
        assertThat(registry.digests()).containsExactly(saved.digest());
        assertThat(registry.source(saved.digest())).isEqualTo(COUNTER);
    }

    @Test
    public void savingTheSameSourceTwiceIsOneVersion() {
        ModelRegistry registry = registry();

        registry.save(COUNTER, 0, "first");
        registry.save(COUNTER, 9, "again");

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.record(registry.latest()).ledgerLength())
                .as("the version is the one that was already there, not a second copy of it")
                .isZero();
    }

    @Test
    public void aModelThatDoesNotFitTheContractIsNeverStored() {
        ModelRegistry registry = registry();

        assertThatThrownBy(() -> registry.save("fn parse(obs) { return obs; }", 0, ""))
                .isInstanceOf(ModelException.class);

        assertThat(registry.digests()).isEmpty();
        assertThat(registry.latest()).isNull();
    }

    @Test
    public void aVersionRemembersWhatItCameFromAndWhatItHadSeen() {
        ModelRegistry registry = registry();

        WorldModel  first  = registry.save(COUNTER, 4, "grounded");
        WorldModel  second = registry.save(counterWithGuards(2), 11, "handles d == 0");
        ModelRecord record = registry.record(second.digest());

        assertThat(record.parent()).isEqualTo(first.digest());
        assertThat(record.ledgerLength()).isEqualTo(11);
        assertThat(record.note()).isEqualTo("handles d == 0");
        assertThat(record.complexity().nodes()).isEqualTo(second.complexity().nodes());
        assertThat(registry.record(first.digest()).parent())
                .as("the first model came from nowhere")
                .isNull();
    }

    @Test
    public void aVersionCanBeNamedByAPrefixOfItsDigest() {
        ModelRegistry registry = registry();

        WorldModel saved = registry.save(COUNTER, 0, "");

        assertThat(registry.resolve(saved.digest().substring(0, 6))).isEqualTo(saved.digest());
    }

    @Test
    public void anAmbiguousPrefixIsRefusedRatherThanGuessed() {
        ModelRegistry registry = registry();
        String        shared   = saveTwoVersionsSharingADigitOfTheirDigest(registry);

        assertThatThrownBy(() -> registry.resolve(shared))
                .isInstanceOf(ModelStoreException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    public void anUnknownVersionIsRefused() {
        ModelRegistry registry = registry();
        registry.save(COUNTER, 0, "");

        assertThatThrownBy(() -> registry.resolve("nosuchmodel"))
                .isInstanceOf(ModelStoreException.class)
                .hasMessageContaining("nosuchmodel");
    }

    @Test
    public void theLatestVersionIsWhatAnUnnamedModelMeans() {
        ModelRegistry registry = registry();
        registry.save(COUNTER, 0, "");
        WorldModel second = registry.save(counterWithGuards(2), 1, "");

        assertThat(registry.latest()).isEqualTo(second.digest());
        assertThat(registry.resolve(null)).isEqualTo(second.digest());
        assertThat(registry.resolve("")).isEqualTo(second.digest());
        assertThat(registry.resolve("latest")).isEqualTo(second.digest());
    }

    @Test
    public void anEmptyStoreHasNoLatestVersionToName() {
        assertThatThrownBy(() -> registry().resolve("latest"))
                .isInstanceOf(ModelStoreException.class)
                .hasMessageContaining("no models");
    }

    @Test
    public void aRegistryReopenedFromDiskRemembersEveryVersion() {
        ModelRegistry first = registry();
        first.save(COUNTER, 3, "grounded");
        String second = first.save(counterWithGuards(2), 7, "guarded").digest();

        ModelRegistry reopened = registry();

        assertThat(reopened.digests()).isEqualTo(first.digests());
        assertThat(reopened.latest()).isEqualTo(second);
        assertThat(reopened.record(second).note()).isEqualTo("guarded");
        assertThat(reopened.record(second).ledgerLength()).isEqualTo(7);
    }

    @Test
    public void aStoredModelIsLoadedBackAsAModelThatRuns() {
        ModelRegistry registry = registry();
        registry.save(COUNTER, 0, "");

        WorldModel loaded = registry().load("latest");

        assertThat(loaded.isGoal(loaded.parse(map("pos", 5)))).isTrue();
    }

    /** Content addressing is only worth something if the store checks it. */
    @Test
    public void aSourceEditedBehindTheStoreIsRefusedRatherThanLoaded() throws IOException {
        ModelRegistry registry = registry();
        String        digest   = registry.save(COUNTER, 0, "").digest();
        Path          source   = registry.root().resolve(digest + ModelRegistry.SOURCE_SUFFIX);

        Files.writeString(source, counterWithGuards(2), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> registry.load(digest))
                .isInstanceOf(ModelStoreException.class)
                .hasMessageContaining(digest);
    }

    @Test
    public void aCertificateIsStoredBesideTheModelItCertifies() {
        ModelRegistry registry = registry();
        String        digest   = registry.save(COUNTER, 0, "").digest();

        registry.saveCertificate(digest, certificate(4));

        assertThat(registry().certificate(digest)).isEqualTo(certificate(4));
    }

    @Test
    public void aVersionNothingHasCertifiedHasNoCertificate() {
        ModelRegistry registry = registry();
        registry.save(COUNTER, 0, "");

        assertThat(registry.certificate("latest")).isNull();
    }

    @Test
    public void thereIsNoEpicycleWarningUntilThereIsSomethingToCompare() {
        ModelRegistry registry = registry();
        registry.saveCertificate(registry.save(COUNTER, 0, "").digest(), certificate(4));

        assertThat(registry.epicycleWarning()).isNull();
    }

    @Test
    public void aVersionNothingHasCertifiedCannotBeJudgedForEpicycles() {
        ModelRegistry registry = registry();
        registry.saveCertificate(registry.save(COUNTER, 0, "").digest(), certificate(4));
        registry.save(counterWithGuards(12), 1, "");

        assertThat(registry.epicycleWarning())
                .as("without a certificate there is no coverage to compare against")
                .isNull();
    }

    @Test
    public void complexityThatGrowsWithoutTestingMoreRulesIsNamedAsEpicycles() {
        ModelRegistry registry = registry();
        certify(registry, registry.save(COUNTER, 0, ""), 4);
        certify(registry, registry.save(counterWithGuards(6), 1, ""), 4);
        certify(registry, registry.save(counterWithGuards(14), 2, ""), 4);

        assertThat(registry.epicycleWarning())
                .contains("epicycle")
                .contains(String.valueOf(registry.record(registry.digests().get(0))
                                                 .complexity().nodes()));
    }

    @Test
    public void complexityThatBuysNewCoverageIsNotAnEpicycle() {
        ModelRegistry registry = registry();
        certify(registry, registry.save(COUNTER, 0, ""), 4);
        certify(registry, registry.save(counterWithGuards(6), 1, ""), 9);
        certify(registry, registry.save(counterWithGuards(14), 2, ""), 21);

        assertThat(registry.epicycleWarning()).isNull();
    }

    @Test
    public void aModelThatStaysTheSameSizeIsNotAnEpicycleEither() {
        ModelRegistry registry = registry();
        certify(registry, registry.save(COUNTER, 0, ""), 4);
        certify(registry, registry.save(COUNTER + "// a note\n", 1, ""), 4);
        certify(registry, registry.save(COUNTER + "// another note\n", 2, ""), 4);

        assertThat(registry.epicycleWarning()).isNull();
    }

    private static void certify(ModelRegistry registry, WorldModel model, int covered) {
        registry.saveCertificate(model.digest(), certificate(covered));
    }

    /**
     * Saves two versions whose digests start with the same character.
     *
     * <p>Trailing blank lines change a model's text without changing what it does, which is the
     * cheapest way to ask the store to tell two versions apart.</p>
     *
     * @return the one character both digests begin with
     */
    private static String saveTwoVersionsSharingADigitOfTheirDigest(ModelRegistry registry) {
        Map<String, String> byFirstCharacter = new LinkedHashMap<>();
        for (int blanks = 1; blanks < 200; blanks++) {
            String source = COUNTER + "\n".repeat(blanks);
            String first  = WorldModel.load(source).digest().substring(0, 1);
            String other  = byFirstCharacter.put(first, source);
            if (other != null) {
                registry.save(other, 0, "");
                registry.save(source, 0, "");
                return first;
            }
        }
        throw new IllegalStateException("two of two hundred digests must share a first character");
    }
}
