package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;

import java.util.Arrays;
import java.util.List;

import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertError;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertHeader;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertNotice;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertSubheader;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertSuccess;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertWarning;
import static org.assertj.core.api.Assertions.assertThat;

public class OutputFormatterTest {

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        // Clear color cache to ensure consistent state
        OutputFormatter.clearColorCache();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        // Clear color cache
        OutputFormatter.clearColorCache();
    }

    @Test
    public void testPrintSuccess() {
        OutputFormatter.printSuccess("Test success message");

        // Asserted by classification, not by the literal marker: the console picks its markers from
        // Glyphs.system(), so the exact characters depend on the terminal encoding.
        assertSuccess(outputCapture.getOutput(), "Test success message");
    }

    @Test
    public void testPrintError() {
        OutputFormatter.printError("Test error message");

        String output = outputCapture.getStderr(); // Error goes to stderr
        assertError(output, "Test error message");

        // One user-facing copy. This used to be asserted as doesNotContain("✗"), because the second
        // and third copies came from CadetLogger.error()'s own console echo, which is marked "✗".
        // That marker is now the formatter's own error marker too, so its absence no longer proves
        // anything -- the count does, and it proves it whichever marker is in use.
        assertThat(countOccurrences(outputCapture.getAllOutput(), "Test error message")).isEqualTo(1);
    }

    @Test
    public void testPrintWarning() {
        OutputFormatter.printWarning("Test warning message");

        assertWarning(outputCapture.getOutput(), "Test warning message");
    }

    @Test
    public void testPrintInfo() {
        OutputFormatter.printInfo("Test info message");

        assertNotice(outputCapture.getOutput(), "Test info message");
    }

    @Test
    public void testPrintHeader() {
        OutputFormatter.printHeader("Test Header");

        assertHeader(outputCapture.getOutput(), "Test Header");
    }

    @Test
    public void testPrintSubheader() {
        OutputFormatter.printSubheader("Test Subheader");

        assertSubheader(outputCapture.getOutput(), "Test Subheader");
    }

    @Test
    public void testPrintCodeBlock() {
        String code = "public class Test {\n    // Code here\n}";
        OutputFormatter.printCodeBlock(code);

        String output = outputCapture.getOutput();
        assertThat(output).contains("public class Test");
        assertThat(output).contains("// Code here");
    }

    @Test
    public void testPrintTable() {
        List<String[]> rows = Arrays.asList(
                new String[] {"Name", "Value", "Description"},
                new String[] {"key1", "value1", "First item"},
                new String[] {"key2", "value2", "Second item"}
                                           );

        OutputFormatter.printTable(rows, true);

        String output = outputCapture.getOutput();
        assertThat(output).contains("Name");
        assertThat(output).contains("Value");
        assertThat(output).contains("key1");
        assertThat(output).contains("value1");
    }

    @Test
    public void testColorDisabled() {
        OutputFormatter.disableColor();
        OutputFormatter.clearColorCache();
        OutputFormatter.printSuccess("No color message");

        String output = outputCapture.getOutput();
        assertThat(output).contains("No color message");
    }

    @Test
    public void testEnableVerbose() {
        OutputFormatter.enableVerbose();
        // Just test that it doesn't throw
        assertThat(true).isTrue();
    }


    @Test
    public void testMultipleOutputTypes() {
        // Test that multiple output types work together
        OutputFormatter.printHeader("Test Results");
        OutputFormatter.printSuccess("3 tests passed");
        OutputFormatter.printError("1 test failed");
        OutputFormatter.printWarning("2 tests skipped");
        OutputFormatter.printInfo("Total: 6 tests");

        String output = outputCapture.getOutput();
        String stderr = outputCapture.getStderr();
        assertThat(output).contains("Test Results");
        assertThat(output).contains("3 tests passed");
        assertThat(stderr).contains("1 test failed"); // Error goes to stderr
        assertThat(output).contains("2 tests skipped");
        assertThat(output).contains("Total: 6 tests");
    }

    /** Number of non-overlapping occurrences of {@code needle} in {@code haystack}. */
    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}