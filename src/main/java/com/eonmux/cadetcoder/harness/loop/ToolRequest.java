package com.eonmux.cadetcoder.harness.loop;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tool call, as it was read out of an agent's reply.
 *
 * <h2>Why nothing here is checked</h2>
 *
 * <p>A request says what the agent wrote, not whether it makes sense. Which tools exist, which
 * arguments they take and what has to be a whole number are all settled by
 * {@link com.eonmux.cadetcoder.harness.tools.ToolCatalog} and
 * {@link com.eonmux.cadetcoder.harness.tools.ToolArgs}, which answer a mistake with a refusal the
 * agent can act on. A reader that made its own rulings would refuse things twice, in two voices,
 * and one of them would fall behind the catalog.</p>
 *
 * @param tool      what the agent called
 * @param arguments what it called with, in the order it wrote them
 */
public record ToolRequest(String tool, Map<String, Object> arguments) {

    public ToolRequest {
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    @Override
    public String toString() {
        return tool + arguments.keySet();
    }
}
