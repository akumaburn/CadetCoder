package com.eonmux.cadetcoder.test;

import com.eonmux.cadetcoder.ai.AIClient;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Forces {@link AIManager} into a fully offline state so tests that exercise the chat fallback (an
 * unknown command routed to {@code ChatCommand}) never make a live network call.
 *
 * <p>Without this, such a test BLOCKS indefinitely whenever a real provider or a local model server is
 * actually reachable on the developer's machine — the surefire fork then dies on its
 * {@code forkedProcessTimeoutInSeconds} — even though the same test passes in an environment where
 * nothing is listening (the AI call just fails fast). The installed stub's active client returns a
 * canned response instantly and {@code AIManager} is pre-marked initialized so its connector-selection
 * path (which probes the live endpoint) is never reached.</p>
 */
public final class AiTestSupport {

    private AiTestSupport() {
    }

    /** Replaces the {@link AIManager} singleton with one whose active client is an offline stub. */
    public static void installOfflineStub() {
        try {
            setStatic("instance", null);

            AIManager manager = AIManager.getInstance();

            Field initialized = AIManager.class.getDeclaredField("initialized");
            initialized.setAccessible(true);
            initialized.setBoolean(manager, true); // bypass ensureInitialized() -> no connector probing

            Field activeClient = AIManager.class.getDeclaredField("activeClient");
            activeClient.setAccessible(true);
            activeClient.set(manager, new OfflineClient());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to install offline AIManager stub", e);
        }
    }

    /** Clears the singleton so the stub cannot leak into other test classes. */
    public static void reset() {
        try {
            setStatic("instance", null);
        } catch (ReflectiveOperationException ignored) {
            // Best-effort cleanup.
        }
    }

    private static void setStatic(String name, Object value) throws ReflectiveOperationException {
        Field field = AIManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** An {@link AIClient} that answers instantly and never touches the network. */
    private static final class OfflineClient implements AIClient {
        @Override
        public String complete(PromptData promptData, Map<String, Object> parameters) {
            return "Offline test stub response";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getModelName() {
            return "offline-test-stub";
        }
    }
}
