package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.mockito.MockedConstruction;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

public class GitIntegrationManagerTest {

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        // Reset singleton instance
        resetSingleton();
    }

    private void resetSingleton() {
        try {
            java.lang.reflect.Field instance = GitIntegrationManager.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            // Ignore
        }
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        resetSingleton();
    }

    @Test
    public void testGetInstance() {
        GitIntegrationManager instance1 = GitIntegrationManager.getInstance();
        GitIntegrationManager instance2 = GitIntegrationManager.getInstance();

        assertThat(instance1).isNotNull();
        assertThat(instance1).isSameAs(instance2);
    }

    @Test
    public void testIsAvailable_Success() throws Exception {
        try (MockedConstruction<GitIntegration> gitMock = mockConstruction(GitIntegration.class)) {
            GitIntegrationManager manager = GitIntegrationManager.getInstance();

            assertThat(manager.isAvailable()).isTrue();
            // No success message is output when initialization succeeds
            assertThat(outputCapture.getStderr()).isEmpty();
        }
    }

    @Test
    public void testIsAvailable_Failure() throws Exception {
        try (MockedConstruction<GitIntegration> gitMock = mockConstruction(GitIntegration.class,
                (mock, context) -> {
                    throw new IOException("Git repository not found");
                })) {

            GitIntegrationManager manager = GitIntegrationManager.getInstance();

            assertThat(manager.isAvailable()).isFalse();
            assertThat(outputCapture.getStderr()).contains("Git integration failed to initialize:");
        }
    }

    @Test
    public void testGetGitIntegration_Available() throws Exception {
        try (MockedConstruction<GitIntegration> gitMock = mockConstruction(GitIntegration.class)) {
            GitIntegrationManager manager = GitIntegrationManager.getInstance();

            GitIntegration git = manager.getGitIntegration();
            assertThat(git).isNotNull();
            assertThat(gitMock.constructed()).hasSize(1);
        }
    }

    @Test
    public void testGetGitIntegration_NotAvailable() throws Exception {
        try (MockedConstruction<GitIntegration> gitMock = mockConstruction(GitIntegration.class,
                (mock, context) -> {
                    throw new IOException("Git repository not found");
                })) {

            GitIntegrationManager manager = GitIntegrationManager.getInstance();

            GitIntegration git = manager.getGitIntegration();
            assertThat(git).isNull();
        }
    }

    @Test
    public void testLazyInitialization() throws Exception {
        // First access fails
        try (MockedConstruction<GitIntegration> gitMock = mockConstruction(GitIntegration.class,
                (mock, context) -> {
                    throw new IOException("Git repository not found");
                })) {

            GitIntegrationManager manager = GitIntegrationManager.getInstance();
            assertThat(manager.isAvailable()).isFalse();
        }

        // Reset singleton for second test
        resetSingleton();

        // Second access succeeds
        try (MockedConstruction<GitIntegration> gitMock = mockConstruction(GitIntegration.class)) {
            GitIntegrationManager manager = GitIntegrationManager.getInstance();
            assertThat(manager.isAvailable()).isTrue();
        }
    }
}