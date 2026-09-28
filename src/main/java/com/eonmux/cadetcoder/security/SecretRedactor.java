package com.eonmux.cadetcoder.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credential-shaped text from anything about to be shown or stored.
 *
 * <h2>Why it exists separately from the places that use it</h2>
 *
 * <p>An API key can arrive in text that was never meant to carry one. Providers routinely echo the
 * offending key back in a 401 body; a request payload can quote a config file; a stack trace can
 * carry a header. Whether that text ends up on the terminal or in a session log on disk, the same
 * substrings have to go, which means the rule for recognising them has to live in one place rather
 * than beside whichever writer happened to be audited most recently.</p>
 *
 * <p>This is a net, not a guarantee. It recognises the shapes credentials actually take -- the key
 * prefixes of the providers {@code ProviderRegistry} ships connectors for, GitHub tokens, AWS
 * access key ids, bearer headers, and the {@code "api_key": "..."} form -- and leaves everything
 * else alone. Some providers issue opaque keys with no prefix at all (Azure, Mistral); for those
 * the only rule available is the name-based one, which is why {@link #redactHeader} and
 * {@link #redactArguments} exist beside shape matching. Text that must never be recorded should
 * not be handed to a logger in the first place.</p>
 */
public final class SecretRedactor {

    /** What replaces the secret part of a match. */
    private static final String MARKER = "***";

    /**
     * Credential shapes that must never reach a log line or the terminal.
     *
     * <p>One entry per key shape the shipped connectors use. {@code ProviderRegistry} is the list
     * of those connectors, and this list is only useful for as long as it keeps up with it: a
     * provider whose prefix is missing here is a provider whose key is written out in full.</p>
     */
    private static final Pattern[] SECRET_PATTERNS = {
            // openai, anthropic, deepseek, openrouter and the other sk- families
            Pattern.compile("(?i)\\b((?:sk|rk|pk)-)[A-Za-z0-9_\\-]{6,}"),
            // github, and the tokens github-copilot exchanges
            Pattern.compile("(?i)\\b(gh[pousr]_|github_pat_)[A-Za-z0-9_]{10,}"),
            // google (GEMINI_API_KEY / GOOGLE_GENERATIVE_AI_API_KEY)
            Pattern.compile("\\b(AIza)[0-9A-Za-z_\\-]{20,}"),
            // groq
            Pattern.compile("\\b(gsk_)[A-Za-z0-9]{20,}"),
            // xai
            Pattern.compile("(?i)\\b(xai-)[A-Za-z0-9]{20,}"),
            // perplexity
            Pattern.compile("(?i)\\b(pplx-)[A-Za-z0-9]{20,}"),
            // fireworks-ai
            Pattern.compile("\\b(fw_)[A-Za-z0-9]{20,}"),
            // commandcode: "user_" then 88 base62 characters. The prefix alone is far too ordinary
            // to key on -- "user_id" and "user_name" appear in most codebases -- so the length is
            // what separates a key from a field name, and no identifier runs to forty characters.
            Pattern.compile("\\b(user_)[A-Za-z0-9]{40,}"),
            // amazon-bedrock: AKIA is a long-lived access key id, ASIA a temporary one, which is
            // what AwsSigV4Signer.Credentials carries a session token for.
            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{12,}"),
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}"),
            Pattern.compile("(?i)(\"?(?:api[_-]?key|apikey|access[_-]?token|x-api-key|authorization)\"?\\s*[:=]\\s*\"?)"
                                    + "[A-Za-z0-9._\\-]{6,}")
    };

    /**
     * Headers whose VALUE is a credential whatever it looks like.
     *
     * <p>{@link #redact(String)} recognises credential shapes, which is the only thing available
     * when a secret is buried in prose. A header is different: the name says outright that the
     * value is an authenticator, so there is nothing to recognise and no reason to take the risk of
     * a shape that does not match. Session names and opaque cookie values carry no {@code sk-}
     * prefix and would otherwise be written to disk verbatim.</p>
     */
    private static final java.util.Set<String> CREDENTIAL_HEADERS = java.util.Set.of(
            "authorization", "proxy-authorization", "www-authenticate", "proxy-authenticate",
            "cookie", "set-cookie", "x-api-key", "api-key", "x-auth-token", "x-amz-security-token",
            "openai-organization", "x-goog-api-key");

    private SecretRedactor() {
    }

    /**
     * Commands whose arguments after the first are credentials.
     *
     * <p>{@code login} takes {@code <provider> <key>}; a user who types the key there has already
     * put it on the command line before the command can refuse it. {@code config} takes
     * {@code <setting> <value>}, and only some settings hold a credential -- but a line is masked
     * on the setting NAME, which is visible without knowing anything about the value.</p>
     */
    private static final java.util.Set<String> CREDENTIAL_SETTINGS =
            java.util.Set.of("ai.apikey", "ai.providerapikeys");

    /** What replaces a masked argument. */
    public static final String REDACTED = "***redacted***";

    /**
     * Returns a command's arguments as they may safely be recorded.
     *
     * @param command the command name
     * @param args    its arguments; may be {@code null}
     * @return a copy with credentials masked, or {@code args} when there is nothing to mask
     */
    public static String[] redactArguments(String command, String[] args) {
        if (args == null || args.length == 0) {
            return args;
        }
        boolean maskTail = masksArgumentTail(command, args);

        String[] copy = args.clone();
        // The tail is masked from index 1: the provider or setting name is what makes the line
        // readable in a log, and it is not itself the secret.
        for (int i = 0; i < copy.length; i++) {
            copy[i] = maskTail && i >= 1 ? REDACTED : redact(copy[i]);
        }
        return copy;
    }

    /** Whether this command's arguments after the first are credentials by definition. */
    private static boolean masksArgumentTail(String command, String[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        String name = command == null ? "" : command.trim().toLowerCase(java.util.Locale.ROOT);
        return "login".equals(name)
               || ("config".equals(name)
                   && CREDENTIAL_SETTINGS.contains(args[0].trim().toLowerCase(java.util.Locale.ROOT)));
    }

    /**
     * Returns a whole typed command line as it may safely be recorded.
     *
     * <p>The shell records what the user typed in three places that outlive the session -- the
     * session log, {@code history.log} and the saved transcript -- and none of them is restricted
     * to the owner, unlike {@code config.json}. So a key typed at the prompt ended up more widely
     * readable than the file it was being stored in.</p>
     *
     * @param line the line as typed; {@code null} and blank pass through
     * @return the line with credentials masked
     */
    public static String redactCommandLine(String line) {
        if (line == null || line.isBlank()) {
            return line;
        }
        String[] parts = line.trim().split("\\s+");
        String name = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
        String[] arguments = java.util.Arrays.copyOfRange(parts, 1, parts.length);

        // Only the name-based rules tokenize. Shape matching must see the WHOLE line, because the
        // shapes that matter span tokens -- "Bearer <key>" and "api_key: <key>" are two words, and
        // redacting each word on its own matches neither.
        if (!masksArgumentTail(name, arguments)) {
            return redact(line);
        }
        return parts[0] + (arguments.length == 0
                ? ""
                : " " + String.join(" ", redactArguments(name, arguments)));
    }

    /**
     * Redacts one HTTP header for logging.
     *
     * @param name  the header name, in any case
     * @param value the header value
     * @return the value with credentials removed, or the marker when the header name alone means
     *         the value is one
     */
    public static String redactHeader(String name, String value) {
        if (name != null && CREDENTIAL_HEADERS.contains(name.trim().toLowerCase(java.util.Locale.ROOT))) {
            return MARKER;
        }
        return redact(value);
    }

    /**
     * Replaces credential-shaped substrings with a marker, keeping the part that identifies what
     * was removed (the {@code sk-} prefix, the {@code "api_key":} label) so the text still reads.
     *
     * @param text any text about to be printed or written; {@code null} and empty pass through
     * @return the text with credentials replaced
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern pattern : SECRET_PATTERNS) {
            result = pattern.matcher(result).replaceAll(match -> {
                String prefix = match.groupCount() >= 1 && match.group(1) != null ? match.group(1) : "";
                return Matcher.quoteReplacement(prefix + MARKER);
            });
        }
        return result;
    }
}
