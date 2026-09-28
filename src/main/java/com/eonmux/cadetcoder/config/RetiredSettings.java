package com.eonmux.cadetcoder.config;

import java.util.Map;

/**
 * Settings that used to exist, and what happened to them.
 *
 * <h2>Why a removed setting is not simply unknown</h2>
 *
 * <p>An unrecognised key is almost always a spelling mistake, and is reported as one. A key that
 * this tool wrote into the user's own file and later removed is a different thing entirely: the user
 * did not mistype it, and telling them their file contains a setting "this build does not know"
 * invites them to correct a spelling that was never wrong. Named here, they are told what became of
 * it -- which for a setting that claimed a security property it never had is the thing they most
 * need to know.</p>
 */
final class RetiredSettings {

    private static final Map<String, String> GONE = Map.of(
            "security.apiKeyEncryption",
            "nothing ever encrypted anything, so it described a protection that was not there. "
            + "Saved keys are held in a file only its owner can read",

            "performance.cacheSize",
            "there was no cache to size. What this tool caches is the model catalog, which is one "
            + "small file with an age rather than a budget",

            "performance.timeoutSeconds",
            "nothing ever timed out by it. How long to wait for a model to answer is "
            + "'ai.completionTimeoutSeconds', and that one is read",

            "ui.outputFormat",
            "every command has always printed text, and setting it to anything else changed "
            + "nothing. Use 'ui.verbosityLevel' to say how much of it you want",

            "ai.uberModeChallenges",
            "uber mode no longer counts down. A run is asked its closing questions until it has "
            + "answered all of them in a row without doing any further work, and doing more work "
            + "starts them again, so there is no number to set",

            "ai.enableAdvancedFeatures",
            "it named a tool-calling path that was never wired to anything: no request body ever "
            + "differed, and its only reader printed that it was enabled. Tools are described to "
            + "the model in the prompt and read back out of the reply, which is how every provider "
            + "here works and needs no setting");

    private RetiredSettings() {
    }

    /**
     * Why a setting is not here any more.
     *
     * <p>The reason alone, with no lead of its own: it is completed by whichever caller is
     * reporting it ("... still has X, and <i>this</i>", "X is no longer a setting: <i>this</i>"),
     * and a lead written into it here stuttered against both of them.</p>
     *
     * @param key a setting as it is written in the file, e.g. {@code security.apiKeyEncryption}
     * @return what became of it, or {@code null} if this build never had it
     */
    static String whatBecameOf(String key) {
        return GONE.get(key);
    }
}
