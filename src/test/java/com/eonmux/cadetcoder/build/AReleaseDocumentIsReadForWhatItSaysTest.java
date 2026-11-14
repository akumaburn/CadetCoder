package com.eonmux.cadetcoder.build;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What the release document has to say before it is believed, and what a check keeps for next
 * time.
 *
 * <p><b>The defect</b>: the only source for "is there a newer build" is a JSON document the origin
 * repository answers with, and documents get thinner than promised -- a release with no jar asset,
 * a draft, or a truncated cache must end the question at "cannot tell" rather than at a download
 * address that points at nothing.</p>
 */
public class AReleaseDocumentIsReadForWhatItSaysTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private MockedStatic<ConfigManager> configMock;

    @Before
    public void setUp() throws Exception {
        ConfigManager mockConfigManager = mock(ConfigManager.class);
        Configuration mockConfig        = new Configuration();
        mockConfig.setBaseDir(tempFolder.getRoot().getAbsolutePath());
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);

        configMock = mockStatic(ConfigManager.class);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
    }

    @After
    public void tearDown() {
        if (configMock != null) {
            configMock.close();
        }
    }

    private static Release parsed(String json) throws Exception {
        return UpdateCheck.parse(MAPPER.readTree(json));
    }

    @Test
    public void aReleaseDocumentWithAJarAssetIsReadAsARelease() throws Exception {
        Release release = parsed("""
                {"tag_name":"v1.1","html_url":"https://github.com/akumaburn/CadetCoder/releases/tag/v1.1",
                 "target_commitish":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "published_at":"2026-11-14T08:00:00Z","draft":false,"prerelease":false,
                 "assets":[
                   {"name":"cadet-latest.jar",
                    "browser_download_url":"https://example.com/cadet-latest.jar"},
                   {"name":"cadet-latest.jar.sha256",
                    "browser_download_url":"https://example.com/cadet-latest.jar.sha256"}]}""");

        assertThat(release).isNotNull();
        assertThat(release.tag()).isEqualTo("v1.1");
        assertThat(release.commit()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(release.publishedAt()).isEqualTo(Instant.parse("2026-11-14T08:00:00Z"));
        assertThat(release.htmlUrl())
                .isEqualTo("https://github.com/akumaburn/CadetCoder/releases/tag/v1.1");
        assertThat(release.jarUrl()).isEqualTo("https://example.com/cadet-latest.jar");
        assertThat(release.sha256Url()).isEqualTo("https://example.com/cadet-latest.jar.sha256");
        assertThat(release.commitShort()).isEqualTo("aaaaaaa");
    }

    @Test
    public void aReleaseWithoutAJarAssetIsNotAReleaseThisFeatureCanInstall() throws Exception {
        assertThat(parsed("""
                {"tag_name":"v1.1","target_commitish":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "published_at":"2026-11-14T08:00:00Z","draft":false,
                 "assets":[{"name":"sources.zip","browser_download_url":"https://example.com/s.zip"}]}"""
        )).isNull();
    }

    @Test
    public void aDraftIsLeftAlone() throws Exception {
        assertThat(parsed("""
                {"tag_name":"v1.1","target_commitish":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "published_at":"2026-11-14T08:00:00Z","draft":true,
                 "assets":[{"name":"cadet-latest.jar",
                            "browser_download_url":"https://example.com/cadet-latest.jar"}]}"""
        )).isNull();
    }

    @Test
    public void aDocumentThatIsNotAResponseIsNotARelease() throws Exception {
        assertThat(parsed("{\"message\":\"Not Found\"}")).isNull();
        assertThat(parsed("[]")).isNull();
    }

    @Test
    public void anAnswerWithoutAPublishedTimeCannotBeCompared() throws Exception {
        Release release = parsed("""
                {"tag_name":"v1.1","target_commitish":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "assets":[{"name":"cadet-latest.jar",
                            "browser_download_url":"https://example.com/cadet-latest.jar"}]}""");

        assertThat(release).isNotNull();
        assertThat(release.canBeCompared()).isFalse();
    }

    /**
     * The one line a start-up says when the cache holds a newer release: what it is, and where on
     * the repository it lives.
     *
     * <p>The cache is fresh, so this says nothing to the network -- the point is what the notice
     * carries when the network already answered yesterday, which is the shape it has on the
     * overwhelming majority of starts.</p>
     */
    @Test
    public void theStartUpNoticeNamesTheReleaseAndPointsAtItsPage() {
        UpdateCheck.writeCache(new Release("v1.1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                           Instant.now().plusSeconds(3600),
                                           "https://github.com/akumaburn/CadetCoder/releases/tag/v1.1",
                                           "https://example.com/cadet-latest.jar", null));

        TestOutputCapture output = new TestOutputCapture();
        try {
            UpdateCheck.printNotice();
            assertThat(output.getAllOutput())
                    .contains("A newer build is available")
                    .contains("v1.1")
                    .contains("https://github.com/akumaburn/CadetCoder/releases/tag/v1.1")
                    .contains("update");
        } finally {
            output.restore();
        }
    }

    /**
     * A current cache is not news. The notice exists for the morning the release is ahead of the
     * build, and the same morning it is behind it says nothing at all.
     */
    @Test
    public void aCacheOfAReleaseThisBuildAlreadyContainsSaysNothing() {
        UpdateCheck.writeCache(new Release("v0.9", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                                           Instant.now().minusSeconds(3600),
                                           "https://github.com/akumaburn/CadetCoder/releases/tag/v0.9",
                                           "https://example.com/cadet-latest.jar", null));

        TestOutputCapture output = new TestOutputCapture();
        try {
            UpdateCheck.printNotice();
            assertThat(output.getAllOutput()).doesNotContain("newer build is available");
        } finally {
            output.restore();
        }
    }

    /* ------------------------------------------------------------------ *
     * What a check keeps for the next start-up                            *
     * ------------------------------------------------------------------ */

    @Test
    public void aCheckKeepsItsAnswerAndTheNextOneReadsItBack() throws Exception {
        assertThat(UpdateCheck.readCache()).as("no answer is kept yet").isNull();

        Release release = new Release("v1.1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                      Instant.parse("2026-11-14T08:00:00Z"),
                                      "https://github.com/akumaburn/CadetCoder/releases/tag/v1.1",
                                      "https://example.com/cadet-latest.jar",
                                      "https://example.com/cadet-latest.jar.sha256");
        UpdateCheck.writeCache(release);

        UpdateCheck.Cached cached = UpdateCheck.readCache();
        assertThat(cached).isNotNull();
        assertThat(cached.tag()).isEqualTo("v1.1");
        assertThat(cached.commit()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(cached.publishedAt()).isEqualTo(Instant.parse("2026-11-14T08:00:00Z"));
        assertThat(cached.jarUrl()).isEqualTo("https://example.com/cadet-latest.jar");
        assertThat(cached.checkedAt()).isNotNull();
        assertThat(cached.htmlUrl())
                .isEqualTo("https://github.com/akumaburn/CadetCoder/releases/tag/v1.1");

        Release readBack = cached.toRelease();
        assertThat(readBack).isNotNull();
        assertThat(readBack.isNewerThan("7140268", Instant.parse("2026-11-14T07:00:00Z"))).isTrue();

        // A half-written or foreign document is no answer, and no answer only means asking again.
        Path cache = tempFolder.getRoot().toPath().resolve("update-check.json");
        Files.writeString(cache, "{ truncated");
        assertThat(UpdateCheck.readCache()).isNull();
    }
}
