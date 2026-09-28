package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * "The index had to be rebuilt" is said about the run that rebuilt it, once.
 *
 * <p><b>The defect</b>: the flag was set when a stale index was discarded and never put back. The
 * engine lives for the whole process, so every later {@code index} repeated "the existing index
 * could not be read and has been rebuilt from scratch" -- about a run that had read a perfectly good
 * index and discarded nothing.</p>
 */
class AnoticeIsGivenOnceAboutTheRunItDescribesTest {

    @TempDir
    Path directory;

    @AfterEach
    void tearDown() {
        ContextEngine.resetInstance();
    }

    private ContextEngine anEngine() throws Exception {
        ConfigManager                manager  = mock(ConfigManager.class);
        Configuration                config   = new Configuration();
        Configuration.IndexingConfig indexing = new Configuration.IndexingConfig();
        indexing.setEnabled(false);
        indexing.setIndexLocation(directory.resolve("index").toString());
        config.setIndexing(indexing);
        when(manager.getConfig()).thenReturn(config);

        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            return ContextEngine.getInstance();
        }
    }

    private static void sayItWasRebuilt(ContextEngine engine) throws Exception {
        Field flag = ContextEngine.class.getDeclaredField("indexRebuilt");
        flag.setAccessible(true);
        flag.set(engine, true);
    }

    @Test
    void theRunThatRebuiltItIsToldAndTheNextOneIsNot() throws Exception {
        ContextEngine engine = anEngine();
        sayItWasRebuilt(engine);

        assertThat(engine.wasIndexRebuilt())
                .as("this run discarded a stale index")
                .isTrue();
        assertThat(engine.wasIndexRebuilt())
                .as("the next one did not, and must not be told it did")
                .isFalse();
        assertThat(engine.wasIndexRebuilt()).isFalse();
    }

    @Test
    void arunThatRebuiltNothingIsToldNothing() throws Exception {
        assertThat(anEngine().wasIndexRebuilt()).isFalse();
    }
}
