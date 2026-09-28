package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConnectorSupport} pure helpers (masking + choice resolution).
 * Interactive prompts and persistence are exercised via {@link LoginCommandTest} and
 * {@link ModelsCommandTest}.
 */
public class ConnectorSupportTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() throws Exception {
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();

        System.setProperty("user.home", tempFolder.getRoot().getAbsolutePath());
        Configuration.defaultBaseDir = tempFolder.getRoot().getAbsolutePath() + "/.config/cadet";

        java.lang.reflect.Field configInstance =
                com.eonmux.cadetcoder.config.ConfigManager.class.getDeclaredField("instance");
        configInstance.setAccessible(true);
        configInstance.set(null, null);
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void maskKey_handlesNullAndBlank() {
        assertThat(ConnectorSupport.maskKey(null)).isEqualTo("(none)");
        assertThat(ConnectorSupport.maskKey("")).isEqualTo("(none)");
        assertThat(ConnectorSupport.maskKey("   ")).isEqualTo("(none)");
    }

    @Test
    public void maskKey_shortKeyFullyMasked() {
        assertThat(ConnectorSupport.maskKey("short")).isEqualTo("****");
        assertThat(ConnectorSupport.maskKey("12345678")).isEqualTo("****");
    }

    @Test
    public void maskKey_longKeyRevealsPrefixAndSuffixOnly() {
        String masked = ConnectorSupport.maskKey("sk-abcdefghijklmnop");
        assertThat(masked).startsWith("sk-a");
        assertThat(masked).endsWith("mnop");
        assertThat(masked).contains("...");
        assertThat(masked).doesNotContain("efghijkl");
    }

    @Test
    public void connectors_includeWellKnownProviders() {
        List<ProviderConnector> all = ConnectorSupport.connectors();
        assertThat(all).isNotEmpty();
        assertThat(all).extracting(ProviderConnector::getId)
                .contains("anthropic", "openai", "github-copilot", "local");
    }

    @Test
    public void resolveProviderChoice_byNumber() {
        List<ProviderConnector> all = ConnectorSupport.connectors();
        ProviderConnector first = ConnectorSupport.resolveProviderChoice(all, "1");
        assertThat(first).isNotNull();
        assertThat(first.getId()).isEqualTo(all.get(0).getId());
    }

    @Test
    public void resolveProviderChoice_byId() {
        List<ProviderConnector> all = ConnectorSupport.connectors();
        ProviderConnector byId = ConnectorSupport.resolveProviderChoice(all, "anthropic");
        assertThat(byId).isNotNull();
        assertThat(byId.getId()).isEqualTo("anthropic");
    }

    @Test
    public void resolveProviderChoice_blankReturnsNull() {
        List<ProviderConnector> all = ConnectorSupport.connectors();
        assertThat(ConnectorSupport.resolveProviderChoice(all, "")).isNull();
        assertThat(ConnectorSupport.resolveProviderChoice(all, "   ")).isNull();
        assertThat(ConnectorSupport.resolveProviderChoice(all, null)).isNull();
    }

    @Test
    public void resolveProviderChoice_outOfRangeReturnsNull() {
        List<ProviderConnector> all = ConnectorSupport.connectors();
        assertThat(ConnectorSupport.resolveProviderChoice(all, "0")).isNull();
        assertThat(ConnectorSupport.resolveProviderChoice(all, "9999")).isNull();
    }

    @Test
    public void resolveProviderChoice_unknownIdReturnsNull() {
        List<ProviderConnector> all = ConnectorSupport.connectors();
        assertThat(ConnectorSupport.resolveProviderChoice(all, "not-a-provider")).isNull();
    }
}
