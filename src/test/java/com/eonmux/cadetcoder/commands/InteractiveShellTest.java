package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.InputHandler;
import org.junit.*;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for InteractiveShell using the TamboUI TUI framework.
 * <p>
 * This focuses on class structure and interface implementation. Full TUI interaction
 * testing is avoided to prevent console corruption (and because the immediate-mode
 * runner requires a real terminal).
 */
public class InteractiveShellTest {

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        if (outputCapture != null) {
            outputCapture.restore();
        }
    }

    @Test
    public void testInteractiveShell_ClassStructure() {
        // Test that the class exists and implements the expected interface
        Class<?> shellClass = InteractiveShell.class;

        // The immediate-mode shell is a plain class (it no longer extends a TUI
        // application base class); it implements InputHandler.
        assertThat(InputHandler.class.isAssignableFrom(shellClass)).isTrue();
        assertThat(shellClass.getSuperclass()).isEqualTo(Object.class);
    }

    @Test
    public void testInteractiveShell_RequiredMethods() {
        Class<?> shellClass = InteractiveShell.class;

        Method[] methods     = shellClass.getDeclaredMethods();
        String[] methodNames = new String[methods.length];
        for (int i = 0; i < methods.length; i++) {
            methodNames[i] = methods[i].getName();
        }

        // Verify key methods exist (kept stable across the Jexer -> TamboUI migration)
        assertThat(methodNames).contains("appendOutputDirect");
        assertThat(methodNames).contains("stripAnsiCodes");
        assertThat(methodNames).contains("runShell");
        assertThat(methodNames).contains("requestQuit");
        assertThat(methodNames).contains("requestInterrupt");
    }

    @Test
    public void testInteractiveShell_ImplementsInputHandler() {
        Class<?> shellClass = InteractiveShell.class;

        // Verify the class declares the InputHandler interface
        assertThat(Arrays.asList(shellClass.getInterfaces())).contains(InputHandler.class);
    }

    @Test
    public void testInteractiveShell_NoConsoleCorruption() {
        // This test ensures we're not actually creating TUI instances
        // that could corrupt the console during testing.

        String initialOutput = outputCapture.getOutput();

        // Just verify class loading doesn't cause issues
        Class<?> shellClass = InteractiveShell.class;
        assertThat(shellClass).isNotNull();

        // Verify console state is unchanged
        String finalOutput = outputCapture.getOutput();
        assertThat(finalOutput).isEqualTo(initialOutput);
    }
}
