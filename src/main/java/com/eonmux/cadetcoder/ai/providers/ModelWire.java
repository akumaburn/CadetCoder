package com.eonmux.cadetcoder.ai.providers;

import java.util.Set;

/**
 * Which wire protocol a particular model answers on.
 *
 * <h2>Why a connector cannot always name one protocol</h2>
 *
 * <p>Almost every provider speaks one protocol for everything it offers, and for those this is a
 * constant. A gateway is different: it stands in front of several upstream vendors and can require
 * the vendor's own wire for the vendor's own models. Command Code does exactly that -- its Claude
 * models are served only from {@code /messages} in the Anthropic Messages shape, and everything else
 * only from {@code /chat/completions}, with each endpoint refusing the other's models outright.</p>
 *
 * <p>Treating such a gateway as single-protocol does not fail at startup or at login, where it could
 * be explained. It fails at the first completion, with a 400 naming a model the user just picked
 * from a list the tool itself printed.</p>
 */
public interface ModelWire {

    /**
     * @param model the model id about to be called, which may be {@code null} or empty when a
     *              caller is asking what the connector does by default
     * @return the protocol to speak for it
     */
    ConnectorProtocol forModel(String model);

    /**
     * Every protocol this wire can choose between, in the order a reader should meet them.
     *
     * <p>Asked by anything describing the connector rather than calling it. Answering it here keeps
     * the knowledge of which model ids mean what in the one class that has it: a caller working the
     * answer out by probing {@link #forModel} would need a sample id per vendor, which is the same
     * decision written a second time somewhere that cannot be kept up to date.</p>
     *
     * @return the protocols, one for all but a gateway
     */
    Set<ConnectorProtocol> protocols();

    /**
     * A wire for a provider that speaks one protocol for everything, which is nearly all of them.
     *
     * @param protocol the only protocol it speaks
     * @return a wire that answers {@code protocol} for every model
     */
    static ModelWire always(ConnectorProtocol protocol) {
        return new ModelWire() {
            @Override
            public ConnectorProtocol forModel(String model) {
                return protocol;
            }

            @Override
            public Set<ConnectorProtocol> protocols() {
                return Set.of(protocol);
            }
        };
    }
}
