package com.eonmux.cadetcoder.ai.catalog;

import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.ai.providers.ProviderRegistry;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A provider's model list is remembered for each key.
 *
 * <h2>What is locked here</h2>
 *
 * <p>A list is asked for once for each key, because the list is what that key may call. A view of
 * every connector reuses a recent failure, so it does not wait again for each endpoint that is
 * down. A view of the one connector the user names asks again after a failure, because the server
 * may be running by then.</p>
 */
public class AmodelListIsAskedForOncePerKeyTest {

    private static final String LISTED = "{\"object\":\"list\",\"data\":[{\"id\":\"glm-5.1\"}]}";

    private final ProviderConnector connector = ProviderRegistry.getInstance().get("zai-coding-plan");
    private final AtomicInteger     asked     = new AtomicInteger();

    private HttpServer          server;
    private volatile int        status = 200;
    private Map<String, String> options;

    @BeforeEach
    public void setUp() throws IOException {
        ProviderModelListing.resetForTesting();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/models", exchange -> {
            asked.incrementAndGet();
            byte[] body = (status == 200 ? LISTED : "{\"error\":{\"code\":\"1000\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        options = Map.of("baseURL", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    public void tearDown() {
        server.stop(0);
        ProviderModelListing.resetForTesting();
    }

    @Test
    public void aListIsAskedForOnce() {
        assertThat(ProviderModelListing.fetch(connector, "key-a", options)).hasSize(1);
        assertThat(ProviderModelListing.fetch(connector, "key-a", options)).hasSize(1);

        assertThat(asked).hasValue(1);
    }

    @Test
    public void aViewOfEveryConnectorDoesNotAskAgainStraightAfterARefusal() {
        status = 401;

        assertThat(ProviderModelListing.fetchForEveryConnector(connector, "key-a", options))
                .isEmpty();
        assertThat(ProviderModelListing.fetchForEveryConnector(connector, "key-a", options))
                .isEmpty();

        assertThat(asked).hasValue(1);
    }

    @Test
    public void aConnectorTheUserNamesIsAskedAgainAfterAFailure() {
        status = 401;
        ProviderModelListing.fetchForEveryConnector(connector, "key-a", options);
        status = 200;

        assertThat(ProviderModelListing.fetch(connector, "key-a", options)).hasSize(1);
        assertThat(asked).hasValue(2);
    }

    @Test
    public void anotherKeyIsAskedForItsOwnList() {
        ProviderModelListing.fetch(connector, "key-a", options);
        ProviderModelListing.fetch(connector, "key-b", options);

        assertThat(asked).as("the list is what one key may call").hasValue(2);
    }

    @Test
    public void aRefusalOfOneKeyDoesNotHideTheListOfAnother() {
        status = 401;
        ProviderModelListing.fetch(connector, "key-a", options);
        status = 200;

        assertThat(ProviderModelListing.fetch(connector, "key-b", options)).hasSize(1);
    }
}
