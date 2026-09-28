package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OutputRouter} after the TamboUI migration.
 *
 * <p>These tests deliberately avoid calling {@code startRouting()} with a real shell,
 * because that redirects the global {@code System.out}/{@code System.err} streams and
 * would interfere with the shared single-fork test JVM. Instead they verify the safe,
 * side-effect-free parts of the contract: the singleton, the default routing state, the
 * {@code requestExit()} fallback when no shell is registered, and that {@code startRouting()}
 * is a no-op (does not redirect streams) when no shell has been set.</p>
 */
public class OutputRouterTest {

    @Test
    public void testGetInstance_isSingleton() {
        OutputRouter a = OutputRouter.getInstance();
        OutputRouter b = OutputRouter.getInstance();
        assertThat(a).isNotNull();
        assertThat(b).isSameAs(a);
    }

    @Test
    public void testNotRoutingByDefault() {
        // No test in the suite constructs a real InteractiveShell, so no shell is
        // registered and routing must never be active.
        assertThat(OutputRouter.getInstance().isRouting()).isFalse();
    }

    @Test
    public void testRequestExit_noShell_returnsFalse() {
        // With no shell registered, requestExit() cannot deliver a quit signal.
        assertThat(OutputRouter.getInstance().requestExit()).isFalse();
    }

    @Test
    public void testStartRouting_withoutShell_isNoOp() {
        OutputRouter router = OutputRouter.getInstance();
        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;

        // Without a shell, startRouting() must not redirect the streams.
        router.startRouting();

        assertThat(router.isRouting()).isFalse();
        assertThat(System.out).isSameAs(originalOut);
        assertThat(System.err).isSameAs(originalErr);
    }
}
