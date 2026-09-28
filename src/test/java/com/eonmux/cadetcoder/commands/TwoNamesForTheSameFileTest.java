package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a path a model asked for is the one it has already seen, said differently.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>This decides whether a file the model named is quietly replaced by a different one. Get it too
 * eager and an edit lands in the wrong file -- the implementation instead of the interface, the test
 * instead of the class -- with nothing in the output to say a substitution happened. Get it too shy
 * and the model's near-miss becomes "file not found" for a file that is right there. It is a
 * staged comparison with four separate ways to say yes and no test of any of them.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>A shared extension is a precondition, not a detail: without it a substitution swaps a language
 * for another. Beyond that, the stages are equality, containment, a shared affix, and only then a
 * distance -- writing this down is what showed that a fifth stage for the Java pairings could never
 * answer, since every pair it named had already been accepted by the affix rule above it. And a name
 * is found at the end of a path written either way, with a line number dropped and a Windows drive
 * letter left alone.</p>
 */
public class TwoNamesForTheSameFileTest {

    private final FilenameSimilarity names = new FilenameSimilarity(new ChatCommand());

    // ------------------------------------------------------------------ finding the name

    @Test
    public void theNameIsTheLastSegmentOfAPath() {
        assertThat(names.baseFilename("src/main/java/Foo.java")).isEqualTo("Foo.java");
        assertThat(names.baseFilename("src\\main\\java\\Foo.java")).isEqualTo("Foo.java");
        assertThat(names.baseFilename("Foo.java")).isEqualTo("Foo.java");
    }

    @Test
    public void aTrailingLineNumberIsNotPartOfTheName() {
        assertThat(names.baseFilename("src/Foo.java:42")).isEqualTo("Foo.java");
    }

    @Test
    public void aWindowsDriveLetterIsNotATrailingLineNumber() {
        assertThat(names.baseFilename("C:/work/Foo.java")).isEqualTo("Foo.java");
    }

    @Test
    public void aPathEndingInASeparatorHasNoNameOfItsOwn() {
        assertThat(names.baseFilename("src/main/")).isEqualTo("src/main/");
    }

    @Test
    public void nothingIsNotAName() {
        assertThat(names.baseFilename(null)).isNull();
        assertThat(names.baseFilename("")).isNull();
    }

    // ------------------------------------------------------------------ comparing two names

    @Test
    public void aNameIsTheSameAsItself() {
        assertThat(names.areSimilar("Foo.java", "Foo.java")).isTrue();
    }

    @Test
    public void nothingIsSimilarToNothing() {
        assertThat(names.areSimilar(null, "Foo.java")).isFalse();
        assertThat(names.areSimilar("Foo.java", null)).isFalse();
        assertThat(names.areSimilar("", "Foo.java")).isFalse();
        assertThat(names.areSimilar("Foo.java", "")).isFalse();
    }

    @Test
    public void aNameContainedInTheOtherIsTheSameThing() {
        assertThat(names.areSimilar("UserServiceImpl.java", "UserService.java")).isTrue();
    }

    @Test
    public void aDifferentExtensionIsADifferentFile() {
        assertThat(names.areSimilar("Config.java", "Config.kt"))
                .as("substituting one language's file for another's is never the near miss meant")
                .isFalse();
        assertThat(names.areSimilar("build.gradle", "build.xml")).isFalse();
    }

    @Test
    public void anExtensionIsComparedWithoutRegardToCase() {
        assertThat(names.areSimilar("Report.JAVA", "ReportBuilder.java")).isTrue();
    }

    @Test
    public void aNameWithNoExtensionIsNotMatchedByDistance() {
        assertThat(names.areSimilar("Makefile", "Makefyle")).isFalse();
    }

    @Test
    public void namesThatBeginAlikeAreTheSameThing() {
        assertThat(names.areSimilar("AccountReader.java", "AccountWriter.java")).isTrue();
    }

    @Test
    public void namesThatEndAlikeAreTheSameThing() {
        assertThat(names.areSimilar("HttpHandler.java", "QueueHandler.java")).isTrue();
    }

    @Test
    public void aTestIsPairedWithTheClassItTests() {
        assertThat(names.areSimilar("Ledger.java", "LedgerTest.java")).isTrue();
        assertThat(names.areSimilar("LedgerTest.java", "Ledger.java")).isTrue();
    }

    @Test
    public void anInterfaceIsPairedWithItsImplementation() {
        assertThat(names.areSimilar("Ledger.java", "LedgerImpl.java")).isTrue();
        assertThat(names.areSimilar("Ledger.java", "ILedger.java")).isTrue();
    }

    @Test
    public void theConventionalPairingsAreTheAffixRuleAndNotARuleOfTheirOwn() {
        // Each of the three shares three characters at one end with the name it pairs with, which
        // is why the stage that used to test for them separately could never answer.
        assertThat(names.areSimilar("orders.sql", "ordersTest.sql"))
                .as("nothing about the pairing is particular to Java")
                .isTrue();
    }

    @Test
    public void twoUnrelatedNamesAreTwoFiles() {
        assertThat(names.areSimilar("Ledger.java", "Zebra.java")).isFalse();
        assertThat(names.areSimilar("pom.xml", "settings.gradle")).isFalse();
    }

    @Test
    public void aNearlyIdenticalNameIsMatchedByDistance() {
        assertThat(names.areSimilar("Zebra.java", "Zebrra.java")).isTrue();
    }

    @Test
    public void twoVeryLongNamesAreStillCompared() {
        String left  = "A".repeat(80) + "Widget.java";
        String right = "A".repeat(80) + "Gadget.java";

        assertThat(names.areSimilar(left, right))
                .as("past the length where a distance matrix is worth taking, shared characters "
                    + "still have to answer the question")
                .isTrue();
    }
}
