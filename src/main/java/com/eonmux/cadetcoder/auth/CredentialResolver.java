package com.eonmux.cadetcoder.auth;

import java.util.List;
import java.util.Map;

/**
 * Resolves a provider's API credential the same way opencode does: an explicit
 * per-provider key from configuration wins, otherwise the first populated environment
 * variable declared for that provider (e.g. {@code ANTHROPIC_API_KEY}) is used.
 */
public final class CredentialResolver {

    private CredentialResolver() { }

    /**
     * @param providerId     the connector id (e.g. "anthropic")
     * @param envVars        environment variable names declared for the provider (catalog order)
     * @param configuredKeys map of provider id -&gt; api key from configuration (may be null)
     * @param legacyKey      a legacy config field value to fall back to (may be null/blank)
     * @return the resolved API key, or null if none is available
     */
    public static String resolveApiKey(String providerId,
                                       List<String> envVars,
                                       Map<String, String> configuredKeys,
                                       String legacyKey) {
        if (configuredKeys != null) {
            String configured = configuredKeys.get(providerId);
            if (notBlank(configured)) {
                return configured;
            }
        }
        if (notBlank(legacyKey)) {
            return legacyKey;
        }
        return firstEnv(envVars);
    }

    /** Returns the value of the first non-blank environment variable in the list, or null. */
    public static String firstEnv(List<String> envVars) {
        if (envVars == null) {
            return null;
        }
        for (String name : envVars) {
            String value = System.getenv(name);
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
