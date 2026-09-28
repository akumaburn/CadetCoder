package com.eonmux.cadetcoder.ai.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ModelCatalogTest {

    /** Validates the bundled snapshot parses into the POJOs (deterministic, no network). */
    @Test
    public void bundledSnapshotParsesIntoCatalog() throws Exception {
        String json;
        try (InputStream in = ModelCatalog.class.getResourceAsStream(ModelCatalog.BUNDLED_SNAPSHOT)) {
            assertThat(in).as("bundled snapshot resource present").isNotNull();
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, ModelsDevProvider> catalog =
                new ObjectMapper().readValue(json, new TypeReference<LinkedHashMap<String, ModelsDevProvider>>() {});

        assertThat(catalog).hasSizeGreaterThan(50);
        assertThat(catalog).containsKeys("anthropic", "openai", "openrouter", "google");

        ModelsDevProvider anthropic = catalog.get("anthropic");
        assertThat(anthropic.getName()).isEqualTo("Anthropic");
        assertThat(anthropic.getEnv()).contains("ANTHROPIC_API_KEY");
        assertThat(anthropic.getModels()).isNotEmpty();

        ModelsDevModel any = anthropic.getModels().values().iterator().next();
        assertThat(any.getId()).isNotBlank();
        assertThat(any.getLimit()).isNotNull();
        assertThat(any.getLimit().getContext()).isGreaterThan(0);
    }

    /** The service always yields a non-empty catalog (fetch or snapshot fallback). */
    @Test
    public void serviceLoadsNonEmptyCatalogWithKnownProviders() {
        ModelCatalog.resetInstance();
        ModelCatalog catalog = ModelCatalog.getInstance();
        Map<String, ModelsDevProvider> providers = catalog.getProviders();

        assertThat(providers).isNotEmpty();
        assertThat(catalog.getProvider("anthropic")).isNotNull();

        ModelsDevModel model = catalog.findModel("anthropic",
                catalog.getProvider("anthropic").getModels().keySet().iterator().next());
        assertThat(model).isNotNull();
    }
}
