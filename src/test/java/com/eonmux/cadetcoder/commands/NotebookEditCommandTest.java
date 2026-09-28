package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.config.Configuration.SecurityConfig;
import com.eonmux.cadetcoder.session.SessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class NotebookEditCommandTest {

    private final ByteArrayOutputStream        outputStream = new ByteArrayOutputStream();
    private final ByteArrayOutputStream        errorStream  = new ByteArrayOutputStream();
    private final PrintStream                  originalOut  = System.out;
    private final PrintStream                  originalErr  = System.err;
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private       NotebookEditCommand          command;
    private       MockedStatic<ConfigManager>  mockedConfigManager;
    private       MockedStatic<SessionManager> mockedSessionManager;
    private       ConfigManager                mockConfigManager;
    private       SessionManager               mockSessionManager;
    private       Configuration                mockConfig;
    private       SecurityConfig               mockSecurityConfig;
    private       Configuration.UiConfig mockUiConfig;
    private final ObjectMapper           mapper = new ObjectMapper();
    private       Path                   tempDir;

    @Before
    public void setUp() {
        command = new NotebookEditCommand();
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(errorStream));

        // Set up temp directory
        try {
            tempDir = tempFolder.getRoot().toPath();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Mock ConfigManager
        mockConfigManager  = mock(ConfigManager.class);
        mockConfig         = mock(Configuration.class);
        mockSecurityConfig = mock(SecurityConfig.class);
        mockUiConfig       = mock(Configuration.UiConfig.class);

        mockedConfigManager = mockStatic(ConfigManager.class);
        mockedConfigManager.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);
        when(mockConfig.getSecurity()).thenReturn(mockSecurityConfig);
        when(mockConfig.getUi()).thenReturn(mockUiConfig);
        when(mockSecurityConfig.isReadOnlyMode()).thenReturn(false);
        // Mirror the shipped default. Left unstubbed, Mockito answers false, which is a stricter
        // policy than the product has and would reject every notebook these tests write to a
        // temporary directory.
        when(mockSecurityConfig.isAllowOutsideProject()).thenReturn(true);
        when(mockUiConfig.getVerbosityLevel()).thenReturn(1);

        // Mock SessionManager
        mockSessionManager   = mock(SessionManager.class);
        mockedSessionManager = mockStatic(SessionManager.class);
        mockedSessionManager.when(SessionManager::getInstance).thenReturn(mockSessionManager);

        // Reset output capture after initialization
        outputStream.reset();
        errorStream.reset();
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        if (mockedConfigManager != null) {
            mockedConfigManager.close();
        }
        if (mockedSessionManager != null) {
            mockedSessionManager.close();
        }
    }

    @Test
    public void testEditNotebookInsufficientArgs() {
        // Act
        int result = command.execute(new String[] {"notebook.ipynb"});

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream.toString();
        // The inline usage string is now rendered from getUsage(), so there is one source of truth
        // instead of a hand-written copy that could drift from the real option list.
        assertTrue(output.contains("Usage: "
                + CommandUsage.render(command.getUsage()).split("\n")[0]));
    }

    @Test
    public void testEditNotebookReadOnlyMode() {
        // Arrange
        when(mockSecurityConfig.isReadOnlyMode()).thenReturn(true);

        // Act
        int result = command.execute(new String[] {"notebook.ipynb", "cell-1", "new content"});

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Cannot edit notebook: read-only mode is enabled"));
    }

    @Test
    public void testEditNotebookOutsideTheWorkingDirectoryIsRefused() throws Exception {
        // The notebook is real and well-formed; the only thing wrong with it is where it lives.
        Path outside = tempDir.resolve("outside.ipynb");
        Files.writeString(outside, "{\"cells\": [], \"metadata\": {}, "
                                   + "\"nbformat\": 4, \"nbformat_minor\": 5}");

        Path   project        = Files.createDirectories(tempDir.resolve("project"));
        Path   isolatedTmp    = Files.createDirectories(project.resolve("tmp"));
        String originalDir    = System.getProperty("user.dir");
        String originalTmpDir = System.getProperty("java.io.tmpdir");
        try {
            // Moved off the real temp directory as well, so the allowance for it cannot be what
            // decides this case.
            System.setProperty("user.dir", project.toString());
            System.setProperty("java.io.tmpdir", isolatedTmp.toString());

            int result = command.execute(new String[] {outside.toString(), "cell-1", "new content"});

            assertEquals(1, result);
            String output = outputStream.toString() + errorStream;
            assertTrue("Expected a containment refusal, got: " + output,
                       output.contains("outside working directory"));
        } finally {
            System.setProperty("user.dir", originalDir);
            System.setProperty("java.io.tmpdir", originalTmpDir);
        }
    }

    @Test
    public void testEditNonExistentNotebook() {
        // Act
        int result = command.execute(new String[] {"nonexistent.ipynb", "cell-1", "new content"});

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("File not found: nonexistent.ipynb") || output.contains("Notebook not found"));
    }

    @Test
    public void testReplaceCellSuccess() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["old code\\n"],
                                             "execution_count": 1,
                                             "outputs": []
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {
                notebookPath.toString(), "cell-1", "new", "code", "content"
        });

        // Assert
        assertEquals(0, result);
        verify(mockSessionManager).saveState();

        String output = outputStream.toString();
        assertTrue(output.contains("Cell cell-1 updated successfully"));

        // Verify file was updated
        String   updatedContent = Files.readString(notebookPath);
        JsonNode updated        = mapper.readTree(updatedContent);
        String   newSource      = updated.get("cells").get(0).get("source").get(0).asText();
        assertTrue(newSource.contains("new code content"));
    }

    @Test
    public void testReplaceCellNotFound() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["code\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {
                notebookPath.toString(), "missing-cell", "new content"
        });

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Cell not found: missing-cell"));
    }

    @Test
    public void testInsertCellSuccess() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["existing code\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {
                notebookPath.toString(), "cell-1", "new cell content",
                "-m", "insert", "-t", "code"
        });

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("New cell inserted successfully"));

        // Verify file was updated
        String   updatedContent = Files.readString(notebookPath);
        JsonNode updated        = mapper.readTree(updatedContent);
        assertEquals(2, updated.get("cells").size());
    }

    @Test
    public void testInsertCellAtBeginning() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["existing code\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {
                notebookPath.toString(), "null", "new first cell",
                "-m", "insert", "-t", "markdown"
        });

        // Assert
        assertEquals(0, result);

        // Verify file was updated
        String   updatedContent = Files.readString(notebookPath);
        JsonNode updated        = mapper.readTree(updatedContent);
        assertEquals(2, updated.get("cells").size());
        assertEquals("markdown", updated.get("cells").get(0).get("cell_type").asText());
    }

    @Test
    public void testInsertCellMissingType() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": []
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {
                notebookPath.toString(), "null", "content", "-m", "insert"
        });

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Cell type is required for insert mode"));
    }

    @Test
    public void testDeleteCellSuccess() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["code to delete\\n"]
                                         },
                                         {
                                             "cell_type": "code",
                                             "id": "cell-2",
                                             "source": ["keep this\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {
                notebookPath.toString(), "cell-1", "-m", "delete"
        });

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("Cell cell-1 deleted successfully"));

        // Verify file was updated
        String   updatedContent = Files.readString(notebookPath);
        JsonNode updated        = mapper.readTree(updatedContent);
        assertEquals(1, updated.get("cells").size());
        assertEquals("cell-2", updated.get("cells").get(0).get("id").asText());
    }

    @Test
    public void testUpdateCellType() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["# Markdown content\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {
                notebookPath.toString(), "cell-1", "# Updated markdown",
                "-t", "markdown"
        });

        // Assert
        assertEquals(0, result);

        // Verify file was updated
        String   updatedContent = Files.readString(notebookPath);
        JsonNode updated        = mapper.readTree(updatedContent);
        assertEquals("markdown", updated.get("cells").get(0).get("cell_type").asText());
    }

    @Test
    public void testInteractiveModeNonInteractiveFailsFast() throws Exception {
        // In the non-interactive test harness (no console, TUI not routing),
        // interactive mode must not block on stdin; it must fail fast with a
        // clear error (notebook-3).
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["old\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Even with stdin populated, the command should not consume it because
        // canPrompt() is false (no console attached in the test JVM).
        String userInput = "line 1\nline 2\nEOF\n";
        InputStream originalIn = System.in;
        System.setIn(new ByteArrayInputStream(userInput.getBytes()));
        try {
            int result = command.execute(new String[] {
                    notebookPath.toString(), "cell-1", "-i"
            });

            assertEquals(1, result);
            String output = outputStream.toString() + errorStream;
            assertTrue(output.contains("Interactive mode is not available in non-interactive sessions"));

            // The original cell must be untouched.
            String   updatedContent = Files.readString(notebookPath);
            JsonNode updated        = mapper.readTree(updatedContent);
            assertEquals("old\n", updated.get("cells").get(0).get("source").get(0).asText());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    public void testUnknownEditMode() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": []
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {
                notebookPath.toString(), "cell-1", "content", "-m", "invalid"
        });

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Unknown edit mode: invalid"));
    }

    @Test
    public void testEmptyCellsArray() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "metadata": {}
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {
                notebookPath.toString(), "cell-1", "content"
        });

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("No cells found in notebook"));
    }

    @Test
    public void testCallMethodWithParameters() throws Exception {
        // This tests the picocli integration
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "test-cell",
                                             "source": ["test\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Create new instance with parameters set
        NotebookEditCommand cmdWithParams = new NotebookEditCommand();
        // Use reflection to set private fields
        var pathField = NotebookEditCommand.class.getDeclaredField("notebookPath");
        pathField.setAccessible(true);
        pathField.set(cmdWithParams, notebookPath.toString());

        var cellField = NotebookEditCommand.class.getDeclaredField("cellId");
        cellField.setAccessible(true);
        cellField.set(cmdWithParams, "test-cell");

        var sourceField = NotebookEditCommand.class.getDeclaredField("newSourceParts");
        sourceField.setAccessible(true);
        sourceField.set(cmdWithParams, new String[] {"updated", "content"});

        // Act
        Integer result = cmdWithParams.call();

        // Assert
        assertEquals(0, (int) result);
    }

    @Test
    public void testEditNonIpynbFileRejected() throws Exception {
        // notebook-4: editing a non-.ipynb file must be refused so notebook JSON
        // is never written over an arbitrary resolved file.
        Path target = tempDir.resolve("notes.txt");
        Files.writeString(target, "plain text content");

        int result = command.execute(new String[] {
                target.toString(), "cell-1", "new content"
        });

        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("not a Jupyter notebook"));
        // File must be untouched (no JSON written).
        assertEquals("plain text content", Files.readString(target));
    }

    @Test
    public void testInvalidCellTypeRejectedOnReplace() throws Exception {
        // notebook-7: an invalid cell type must never be written verbatim.
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["code\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        int result = command.execute(new String[] {
                notebookPath.toString(), "cell-1", "content", "-t", "bogus"
        });

        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Invalid cell type: bogus"));
        // The original cell type must remain unchanged.
        JsonNode updated = mapper.readTree(Files.readString(notebookPath));
        assertEquals("code", updated.get("cells").get(0).get("cell_type").asText());
    }

    @Test
    public void testInvalidCellTypeRejectedOnInsert() throws Exception {
        // notebook-7: insert path must also validate the cell type.
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["code\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        int result = command.execute(new String[] {
                notebookPath.toString(), "cell-1", "content", "-m", "insert", "-t", "table"
        });

        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Invalid cell type: table"));
        // No cell was added.
        JsonNode updated = mapper.readTree(Files.readString(notebookPath));
        assertEquals(1, updated.get("cells").size());
    }

    @Test
    public void testRawCellTypeAccepted() throws Exception {
        // notebook-7: "raw" is a valid Jupyter cell type and must be accepted.
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["code\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        int result = command.execute(new String[] {
                notebookPath.toString(), "cell-1", "raw content", "-t", "raw"
        });

        assertEquals(0, result);
        JsonNode updated = mapper.readTree(Files.readString(notebookPath));
        assertEquals("raw", updated.get("cells").get(0).get("cell_type").asText());
    }

    @Test
    public void testAtomicWriteLeavesNoTempFile() throws Exception {
        // notebook-5: the atomic write must produce a valid notebook and not
        // leave a stray temp file behind in the directory.
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["old\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        int result = command.execute(new String[] {
                notebookPath.toString(), "cell-1", "fresh content"
        });

        assertEquals(0, result);
        // Valid notebook JSON persisted.
        JsonNode updated = mapper.readTree(Files.readString(notebookPath));
        assertTrue(updated.get("cells").get(0).get("source").get(0).asText().contains("fresh content"));
        // No leftover *.tmp file in the directory.
        try (java.util.stream.Stream<Path> entries = Files.list(tempDir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    public void testCallRoutesThroughExecuteValidation() throws Exception {
        // notebook-2: call() must route through execute() so its arg validation
        // runs. With no parameters set, the reconstructed args are missing both
        // the path and cell id, hitting the insufficient-args check rather than
        // diverging into editNotebook with nulls.
        NotebookEditCommand cmd = new NotebookEditCommand();

        Integer result = cmd.call();

        assertEquals(1, (int) result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Usage: "
                + CommandUsage.render(cmd.getUsage()).split("\n")[0]));
    }

    @Test
    public void testCallRejectsNonIpynbType() throws Exception {
        // notebook-2 + notebook-7: invalid cell type provided via the picocli
        // path is validated because call() shares execute()'s flow.
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["code\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        NotebookEditCommand cmd = new NotebookEditCommand();
        var pathField = NotebookEditCommand.class.getDeclaredField("notebookPath");
        pathField.setAccessible(true);
        pathField.set(cmd, notebookPath.toString());
        var cellField = NotebookEditCommand.class.getDeclaredField("cellId");
        cellField.setAccessible(true);
        cellField.set(cmd, "cell-1");
        var typeField = NotebookEditCommand.class.getDeclaredField("cellType");
        typeField.setAccessible(true);
        typeField.set(cmd, "weird");

        Integer result = cmd.call();

        assertEquals(1, (int) result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Invalid cell type: weird"));
    }

    @Test
    public void testGetDescription() {
        assertEquals("Edit Jupyter notebook cells", command.getDescription());
    }

    @Test
    public void testGetUsage() {
        String usage = command.getUsage();
        // Options on their own lines, like grep/ls/glob: on one line this was 130 columns.
        assertTrue(usage.startsWith("notebookedit <notebook_path> <cell_id> [new_source] [options]"));
        assertTrue(usage.contains("-t, --type <code|markdown>"));
        assertTrue(usage.contains("-m, --mode <replace|insert|delete>"));
        assertTrue(usage.contains("-i, --interactive"));
    }
}