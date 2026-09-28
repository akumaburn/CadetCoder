package com.eonmux.cadetcoder.commands;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class NotebookReadCommandTest {

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errorStream  = new ByteArrayOutputStream();
    private final PrintStream           originalOut  = System.out;
    private final PrintStream           originalErr  = System.err;
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private       NotebookReadCommand   command;
    private Path tempDir;

    @Before
    public void setUp() {
        command = new NotebookReadCommand();
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(errorStream));

        // Set up temp directory
        try {
            tempDir = tempFolder.getRoot().toPath();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    public void testReadNotebookNoArgs() {
        // Act
        int result = command.execute(new String[0]);

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("No notebook path provided"));
    }

    @Test
    public void testReadNonExistentNotebook() {
        // Act
        int result = command.execute(new String[] {"nonexistent.ipynb"});

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("File not found: nonexistent.ipynb") || output.contains("Notebook not found"));
    }

    @Test
    public void testReadValidNotebook() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["print('Hello, World!')\\n"],
                                             "execution_count": 1,
                                             "outputs": [
                                                 {
                                                     "output_type": "stream",
                                                     "text": ["Hello, World!\\n"]
                                                 }
                                             ]
                                         },
                                         {
                                             "cell_type": "markdown",
                                             "id": "cell-2",
                                             "source": ["# Test Markdown\\n", "This is a test.\\n"]
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
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {notebookPath.toString()});

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("Notebook: test.ipynb"));
        assertTrue(output.contains("Kernel: Python 3 (python)"));
        assertTrue(output.contains("Cell 1 [cell-1] - code"));
        assertTrue(output.contains("print('Hello, World!')"));
        assertTrue(output.contains("Hello, World!"));
        assertTrue(output.contains("Cell 2 [cell-2] - markdown"));
        assertTrue(output.contains("# Test Markdown"));
        assertTrue(output.contains("Total cells: 2"));
    }

    @Test
    public void testReadSpecificCell() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["x = 1\\n"]
                                         },
                                         {
                                             "cell_type": "code",
                                             "id": "target-cell",
                                             "source": ["y = 2\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {notebookPath.toString(), "-c", "target-cell"});

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("target-cell"));
        assertTrue(output.contains("y = 2"));
        assertFalse(output.contains("x = 1"));
        assertFalse(output.contains("Total cells"));
    }

    @Test
    public void testReadNonExistentCell() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["x = 1\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {notebookPath.toString(), "--cell", "missing-cell"});

        // Assert
        assertEquals(1, result); // The command should fail when cell is not found
        String output = outputStream.toString();
        assertFalse(output.contains("cell-1")); // Should not display any cells
    }

    @Test
    public void testReadNotebookWithErrorOutput() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["1/0\\n"],
                                             "outputs": [
                                                 {
                                                     "output_type": "error",
                                                     "ename": "ZeroDivisionError",
                                                     "evalue": "division by zero"
                                                 }
                                             ]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {notebookPath.toString()});

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("Error:"));
        assertTrue(output.contains("ZeroDivisionError"));
        assertTrue(output.contains("division by zero"));
    }

    @Test
    public void testReadNotebookWithDisplayData() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": [
                                         {
                                             "cell_type": "code",
                                             "id": "cell-1",
                                             "source": ["display(df)\\n"],
                                             "outputs": [
                                                 {
                                                     "output_type": "display_data",
                                                     "data": {
                                                         "text/plain": ["DataFrame output"],
                                                         "text/html": ["<table>...</table>"],
                                                         "image/png": "base64data"
                                                     }
                                                 }
                                             ]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {notebookPath.toString()});

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("DataFrame output"));
    }

    @Test
    public void testReadNotebookEmptyCells() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("test.ipynb");
        String notebookContent = """
                                 {
                                     "cells": []
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Act
        int result = command.execute(new String[] {notebookPath.toString()});

        // Assert
        assertEquals(0, result);
        String output = outputStream.toString();
        assertTrue(output.contains("Total cells: 0")); // When cells array is empty, it still counts cells
    }

    @Test
    public void testReadInvalidJsonNotebook() throws Exception {
        // Arrange
        Path notebookPath = tempDir.resolve("invalid.ipynb");
        Files.writeString(notebookPath, "invalid json content");

        // Act
        int result = command.execute(new String[] {notebookPath.toString()});

        // Assert
        assertEquals(1, result);
        String output = outputStream.toString() + errorStream;
        assertTrue(output.contains("Error reading notebook"));
    }

    @Test
    public void testReadNonNotebookFile() throws Exception {
        // Arrange
        Path textFile = tempDir.resolve("test.txt");
        Files.writeString(textFile, "Not a notebook");

        // Act
        int result = command.execute(new String[] {textFile.toString()});

        // Assert
        String output = outputStream.toString();
        assertTrue(output.contains("File does not appear to be a Jupyter notebook"));
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
                                             "source": ["test code\\n"]
                                         }
                                     ]
                                 }
                                 """;
        Files.writeString(notebookPath, notebookContent);

        // Create new instance with parameters set
        NotebookReadCommand cmdWithParams = new NotebookReadCommand();
        // Use reflection to set private fields (in real scenario, picocli would do this)
        var pathField = NotebookReadCommand.class.getDeclaredField("notebookPath");
        pathField.setAccessible(true);
        pathField.set(cmdWithParams, notebookPath.toString());

        var cellField = NotebookReadCommand.class.getDeclaredField("cellId");
        cellField.setAccessible(true);
        cellField.set(cmdWithParams, "test-cell");

        // Act
        Integer result = cmdWithParams.call();

        // Assert
        assertEquals(0, (int) result);
    }

    @Test
    public void testGetDescription() {
        assertEquals("Read Jupyter notebook contents", command.getDescription());
    }

    @Test
    public void testGetUsage() {
        assertEquals("notebookread <notebook_path> [-c|--cell <cell_id>]", command.getUsage());
    }
}