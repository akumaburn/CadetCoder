package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.catalog.ModelsDevProvider;
import com.eonmux.cadetcoder.ai.providers.ProviderConnector;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Listing a provider never depends on a name that may not be there.
 *
 * <p><b>The defect</b>: the guard above proved at least ONE of the catalog entry and the local
 * connector was present -- not that the catalog entry was. Its {@code name} is deserialized straight
 * from models.dev and only the id is backfilled, so an entry with no name and no local connector
 * sent the listing into a null dereference: {@code models <id>} threw instead of listing.</p>
 */
class AproviderIsNamedByWhateverNameThereIsTest {

    private static String label(ModelsDevProvider provider, ProviderConnector connector,
                                String providerId) throws Exception {
        Method method = ModelsCommand.class.getDeclaredMethod(
                "label", ModelsDevProvider.class, ProviderConnector.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, provider, connector, providerId);
    }

    @Test
    void thecatalogsNameIsUsedWhenItHasOne() throws Exception {
        ModelsDevProvider provider = new ModelsDevProvider();
        provider.setName("Anthropic");

        assertThat(label(provider, null, "anthropic")).isEqualTo("Anthropic");
    }

    @Test
    void theconnectorsNameIsUsedWhenTheCatalogHasNone() throws Exception {
        ProviderConnector connector = mock(ProviderConnector.class);
        when(connector.getDisplayName()).thenReturn("Local Llama");

        assertThat(label(new ModelsDevProvider(), connector, "llama")).isEqualTo("Local Llama");
    }

    @Test
    void theIdIsTheNameOfLastResortRatherThanNothingAtAll() throws Exception {
        assertThat(label(new ModelsDevProvider(), null, "some-gateway"))
                .as("a catalog entry with no name and no connector is what used to throw")
                .isEqualTo("some-gateway");
        assertThat(label(null, null, "some-gateway")).isEqualTo("some-gateway");
    }
}
