package com.eonmux.cadetcoder.integration;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.Main;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.session.SessionManager;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for CadetCoder commands
 */
public class CommandIntegrationTest {

    @Rule
    public TemporaryFolder tempFolder = new ProjectFolder();

    private CommandRegistry       registry;
    private ByteArrayOutputStream outputStream;
    private PrintStream           originalOut;
    private PrintStream           originalErr;
    private String                originalUserHome;
    private String                originalBaseDir;

    @Before
    public void setUp() throws Exception {
        registry     = new CommandRegistry();
        outputStream = new ByteArrayOutputStream();
        originalOut  = System.out;
        originalErr  = System.err;
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(outputStream));

        // Set up temp directory as home
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempFolder.getRoot().getAbsolutePath());

        // Saved, so tearDown can put back exactly what was there. Reconstructing it is what went
        // wrong before: the value put back was "<user.home>/.config/cadet", which is not the
        // default -- Configuration uses "<user.home>/.cadet" -- so every later test in the fork
        // built its config tree somewhere the application never looks.
        originalBaseDir = com.eonmux.cadetcoder.config.Configuration.defaultBaseDir;
        com.eonmux.cadetcoder.config.Configuration.defaultBaseDir =
                tempFolder.getRoot().getAbsolutePath() + "/.cadet";

        // Reset singletons
        resetSingletons();
    }

    private void resetSingletons() throws Exception {
        // Reset ConfigManager
        java.lang.reflect.Field configInstance = ConfigManager.class.getDeclaredField("instance");
        configInstance.setAccessible(true);
        configInstance.set(null, null);

        // Reset SessionManager
        java.lang.reflect.Field sessionInstance = SessionManager.class.getDeclaredField("instance");
        sessionInstance.setAccessible(true);
        sessionInstance.set(null, null);

        // Reset ProjectContext
        java.lang.reflect.Field contextInstance =
                com.eonmux.cadetcoder.context.ProjectContext.class.getDeclaredField("instance");
        contextInstance.setAccessible(true);
        contextInstance.set(null, null);
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setProperty("user.home", originalUserHome);
        com.eonmux.cadetcoder.config.Configuration.defaultBaseDir = originalBaseDir;
    }

    @Test
    public void testReadWriteWorkflow() throws Exception {
        // Create a test file
        Path   testFile        = tempFolder.newFile("test.txt").toPath();
        String originalContent = "Original content\nLine 2\nLine 3";
        Files.writeString(testFile, originalContent);

        // Test read command
        int exitCode = registry.executeCommand("read", new String[] {testFile.toString()});
        assertThat(exitCode).isEqualTo(0);
        String output = outputStream.toString();
        assertThat(output).contains("Original content");
        assertThat(output).contains("Line 2");

        // Clear output
        outputStream.reset();

        // Test write command
        exitCode = registry.executeCommand("write", new String[] {testFile.toString(), "New content", "-f"});
        assertThat(exitCode).isEqualTo(0);

        // Verify file was updated
        String newContent = Files.readString(testFile);
        assertThat(newContent).isEqualTo("New content");
    }

    @Test
    public void testTodoWorkflow() throws Exception {
        // Add todos
        int exitCode = registry.executeCommand("todowrite", new String[] {"add", "First task"});
        assertThat(exitCode).isEqualTo(0);

        exitCode = registry.executeCommand("todowrite", new String[] {"add", "Second task", "-p", "high"});
        assertThat(exitCode).isEqualTo(0);

        // Clear output
        outputStream.reset();

        // Read todos
        exitCode = registry.executeCommand("todoread", new String[] {});
        assertThat(exitCode).isEqualTo(0);
        String output = outputStream.toString();
        assertThat(output).contains("First task");
        assertThat(output).contains("Second task");
        assertThat(output).contains("HIGH");
    }

    @Test
    public void testNotebookCommands() throws Exception {
        // Create a simple notebook
        String notebookJson = """
                              {
                                  "cells": [
                                      {
                                          "cell_type": "code",
                                          "id": "cell1",
                                          "source": ["print('Hello, World!')\\n"],
                                          "outputs": [],
                                          "execution_count": 1
                                      },
                                      {
                                          "cell_type": "markdown",
                                          "id": "cell2",
                                          "source": ["# Title\\n", "Some text\\n"]
                                      }
                                  ],
                                  "metadata": {
                                      "kernelspec": {
                                          "display_name": "Python 3",
                                          "language": "python"
                                      }
                                  }
                              }
                              """;

        Path notebookFile = tempFolder.newFile("test.ipynb").toPath();
        Files.writeString(notebookFile, notebookJson);

        // Test notebook read
        int exitCode = registry.executeCommand("notebookread", new String[] {notebookFile.toString()});
        assertThat(exitCode).isEqualTo(0);
        String output = outputStream.toString();
        assertThat(output).contains("print('Hello, World!')");
        assertThat(output).contains("# Title");
        assertThat(output).contains("Python 3");

        // Clear output
        outputStream.reset();

        // Test notebook edit
        exitCode = registry.executeCommand("notebookedit",
                new String[] {notebookFile.toString(), "cell1", "print('Updated!')"});
        assertThat(exitCode).isEqualTo(0);

        // Verify update
        String updatedContent = Files.readString(notebookFile);
        assertThat(updatedContent).contains("print('Updated!')");
    }

    @Test
    public void testSessionWorkflow() throws Exception {
        SessionManager sessionManager = SessionManager.getInstance();

        // Add some data to session
        sessionManager.addToConversationHistory("Test conversation");
        sessionManager.setTodoList(java.util.List.of(
                new com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem(
                        "1", "Finish the workflow",
                        com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem.Status.PENDING,
                        com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem.Priority.HIGH)));

        // Save session
        String sessionId = sessionManager.getCurrentSessionId();
        sessionManager.saveSessionToHistory();

        // Clear current session
        resetSingletons();

        // Load session by ID
        SessionManager newManager = SessionManager.getInstance();
        boolean        loaded     = newManager.loadSessionById(sessionId);
        assertThat(loaded).isTrue();

        // Verify data was restored
        assertThat(newManager.getConversationHistory()).contains("Test conversation");
        assertThat(newManager.getTodoList())
                .extracting(com.eonmux.cadetcoder.commands.TodoReadCommand.TodoItem::getContent)
                .contains("Finish the workflow");
    }

    @Test
    public void testGrepGlobCommands() throws Exception {
        // Create test files
        Path dir1  = tempFolder.newFolder("src").toPath();
        Path file1 = dir1.resolve("Test.java");
        Path file2 = dir1.resolve("Main.java");
        Path file3 = tempFolder.newFile("README.md").toPath();

        Files.writeString(file1, "public class Test {\n    public void testMethod() {}\n}");
        Files.writeString(file2, "public class Main {\n    public static void main(String[] args) {}\n}");
        Files.writeString(file3, "# README\nThis is a test project");

        // Change to temp directory
        System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

        // Test glob
        outputStream.reset();
        int exitCode = registry.executeCommand("glob", new String[] {"**/*.java"});
        assertThat(exitCode).isEqualTo(0);
        String output = outputStream.toString();
        assertThat(output).contains("Test.java");
        assertThat(output).contains("Main.java");
        assertThat(output).doesNotContain("README.md");

        // Test grep
        outputStream.reset();
        exitCode = registry.executeCommand("grep",
                new String[] {"public.*void", "-p", tempFolder.getRoot().getAbsolutePath()});
        assertThat(exitCode).isEqualTo(0);
        output = outputStream.toString();
        assertThat(output).contains("Test.java");
        assertThat(output).contains("testMethod");
    }

    @Test
    public void testContextCommand() throws Exception {
        // Save current directory and switch to temp directory
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());

        try {
            // Clean up any existing CADET.md file first
            Path cadetFile = Paths.get(System.getProperty("user.dir"), "CADET.md");
            Files.deleteIfExists(cadetFile);

            // Create context
            outputStream.reset();
            int exitCode = registry.executeCommand("context", new String[] {"create"});

            // Debug output if test fails
            if (exitCode != 0) {
                String output      = outputStream.toString();
                String errorOutput = output; // Both stdout and stderr go to outputStream
                System.err.println("Context create failed with exit code: " + exitCode);
                System.err.println("Output: " + output);
                System.err.println("Error Output: " + errorOutput);

                // Try to understand why it failed
                if (output.contains("Failed to create context file") ||
                    errorOutput.contains("Failed to create context file")) {
                    System.err.println("Failed to create CADET.md - check file permissions");
                }
            }

            assertThat(exitCode).isEqualTo(0);

            assertThat(cadetFile).exists();

            // Show context
            outputStream.reset();
            exitCode = registry.executeCommand("context", new String[] {"show"});
            assertThat(exitCode).isEqualTo(0);
            String output = outputStream.toString();
            assertThat(output).contains("Project Context");
            assertThat(output).contains("CADET.md");

            // Clean up after test
            Files.deleteIfExists(cadetFile);
        } finally {
            // Restore original directory
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    public void testMainWithFlags() throws Exception {
        // Test with --help
        Main                main = new Main();
        picocli.CommandLine cmd  = new picocli.CommandLine(main);

        outputStream.reset();
        int exitCode = cmd.execute("--help");
        assertThat(exitCode).isEqualTo(0);
        String output = outputStream.toString();
        assertThat(output).contains("AI-assisted coding tool");
        assertThat(output).contains("--ai-model");
        assertThat(output).contains("--continue");
        assertThat(output).contains("--resume");
    }

    @Test
    public void testBashCommandSecurity() throws Exception {
        // Test that dangerous commands are blocked in default config
        outputStream.reset();
        int exitCode = registry.executeCommand("bash", new String[] {"rm -rf /", "-f"});
        // Should fail due to security checks
        assertThat(exitCode).isNotEqualTo(0);
    }
}