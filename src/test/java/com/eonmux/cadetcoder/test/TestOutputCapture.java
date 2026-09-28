package com.eonmux.cadetcoder.test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * Utility class for capturing both stdout and stderr in tests.
 */
public class TestOutputCapture {
    private final ByteArrayOutputStream outputStream;
    private final ByteArrayOutputStream errorStream;
    private final PrintStream           originalOut;
    private final PrintStream           originalErr;

    public TestOutputCapture() {
        this.outputStream = new ByteArrayOutputStream();
        this.errorStream  = new ByteArrayOutputStream();
        this.originalOut  = System.out;
        this.originalErr  = System.err;
        startCapture();
    }

    public void startCapture() {
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(errorStream));
    }

    public String getStderr() {
        return errorStream.toString();
    }

    public String getAllOutput() {
        return outputStream.toString() + errorStream.toString();
    }

    public void reset() {
        outputStream.reset();
        errorStream.reset();
    }

    public String getOutput() {
        return getStdout();
    }

    public String getStdout() {
        return outputStream.toString();
    }

    public void restore() {
        stopCapture();
    }

    public void stopCapture() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }
}