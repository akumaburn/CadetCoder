package com.eonmux.cadetcoder.commands;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Test for the improved SUCCESS detection mechanism
 */
public class SuccessDetectionTest {

    private IterativeExecutor executor;
    private TestIterativeCommand testCommand;
    
    @Before
    public void setUp() {
        executor = new IterativeExecutor();
        testCommand = new TestIterativeCommand();
    }
    
    @Test
    public void testSuccessFormatDetection() {
        testCommand.setResponse("SUCCESS: The AIManager class is a singleton that manages AI clients and handles fallback between local and API clients.");
        
        int result = executor.execute(testCommand, new String[]{"test"});
        
        assertEquals("SUCCESS format should return exit code 0", 0, result);
    }
    
    @Test
    public void testLegacyResponseFormatCompatibility() {
        testCommand.setResponse("Response: This is a legacy response format");
        
        int result = executor.execute(testCommand, new String[]{"test"});
        
        assertEquals("Legacy Response format should return exit code 0", 0, result);
    }
    
    @Test
    public void testErrorDetection() {
        // Finding 21: errors are signalled by a structured leading ERROR: sentinel (or an explicit
        // failure flag), not by substring-scanning output for the word "Error".
        testCommand.setResponse("ERROR: Something went wrong during execution");

        int result = executor.execute(testCommand, new String[]{"test"});

        assertEquals("ERROR: sentinel should return exit code 1", 1, result);
    }

    @Test
    public void testExplicitFailureReturnsErrorCode() {
        // Finding 21: an explicit StepResult.failure is authoritative regardless of output wording.
        testCommand.setExplicitResult(IterativeCommand.StepResult.failure(
                "Operation cancelled by user", new HashMap<>()));

        int result = executor.execute(testCommand, new String[]{"test"});

        assertEquals("Explicit failure should return exit code 1", 1, result);
    }

    @Test
    public void testBenignOutputWithFailureWordsIsNotAnError() {
        // Finding 21: plain output that merely contains words like "failed"/"cancelled" but carries
        // neither the ERROR: sentinel nor an explicit error flag must NOT be misclassified as a
        // failure. The previous keyword scan produced false failures on benign command output.
        testCommand.setResponse("The build log mentions a previously cancelled job that failed earlier.");

        int result = executor.execute(testCommand, new String[]{"test"});

        assertEquals("Benign output with failure-like words should return exit code 0", 0, result);
    }
    
    @Test
    public void testSuccessWithMultilineContent() {
        testCommand.setResponse("SUCCESS: The caching system uses Spring Cache with Redis as the backend. Here's how it works:\n\n" +
                                "1. CacheConfiguration.java sets up Redis connection and cache managers\n" +
                                "2. Methods annotated with @Cacheable automatically cache return values\n" +
                                "3. Cache keys are generated from method parameters using SpEL expressions");
        
        int result = executor.execute(testCommand, new String[]{"test"});
        
        assertEquals("Multiline SUCCESS format should return exit code 0", 0, result);
    }
    
    @Test
    public void testSuccessWithLeadingWhitespace() {
        testCommand.setResponse("   SUCCESS: Response with leading whitespace");
        
        int result = executor.execute(testCommand, new String[]{"test"});
        
        assertEquals("SUCCESS with leading whitespace should return exit code 0", 0, result);
    }
    
    @Test
    public void testNonSuccessResponse() {
        testCommand.setResponse("Just a regular response without SUCCESS prefix");
        
        int result = executor.execute(testCommand, new String[]{"test"});
        
        assertEquals("Regular response should return exit code 0 (legacy compatibility)", 0, result);
    }
    
    /**
     * Test implementation of IterativeCommand
     */
    private static class TestIterativeCommand implements IterativeCommand {
        private String response = "SUCCESS: Test completed";
        private StepResult explicitResult;

        public void setResponse(String response) {
            this.response = response;
            this.explicitResult = null;
        }

        /** Forces the step to return a fully-specified StepResult (explicit error flag set). */
        public void setExplicitResult(StepResult explicitResult) {
            this.explicitResult = explicitResult;
        }

        @Override
        public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
            if (explicitResult != null) {
                return explicitResult;
            }
            return new StepResult(true, response, context, null);
        }
        
        @Override
        public String getInitialPrompt(String[] args) {
            return null;
        }
        
        @Override
        public boolean supportsIterativeExecution(String[] args) {
            return true;
        }
        
        @Override
        public int execute(String[] args) {
            return 0;
        }
        
        @Override
        public String getDescription() {
            return "Test command for SUCCESS detection";
        }
        
        @Override
        public String getUsage() {
            return "test";
        }
    }
}