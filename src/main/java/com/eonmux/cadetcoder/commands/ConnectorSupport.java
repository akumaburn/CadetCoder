package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.ContextWindow;
import com.eonmux.cadetcoder.ai.catalog.ModelCatalog;
import com.eonmux.cadetcoder.ai.catalog.ModelsDevModel;
import com.eonmux.cadetcoder.ai.catalog.ModelsDevProvider;
import com.eonmux.cadetcoder.ai.catalog.ProviderModelListing;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.ai.providers.ProviderRegistry;
import com.eonmux.cadetcoder.auth.AwsSigV4Signer;
import com.eonmux.cadetcoder.auth.CredentialResolver;
import com.eonmux.cadetcoder.auth.GitHubCopilotAuth;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.ui.UnifiedOutput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared, side-effect-light helpers for the connector/model commands ({@link LoginCommand}
 * and {@link ModelsCommand}): listing connectors, reporting credential status without
 * leaking secrets, interactively choosing a provider/model through the TUI prompt line, and
 * persisting the active provider+model.
 *
 * <p>This is a plain utility class (not a {@link com.eonmux.cadetcoder.CommandRegistry.Command}),
 * so the reflective command registry never registers or instantiates it.</p>
 */
final class ConnectorSupport {

    /** Connector id for GitHub Copilot (authenticated via the OAuth device flow, not a saved key). */
    static final String COPILOT_ID = "github-copilot";

    /** The provider option that holds where a connector's server is. */
    static final String BASE_URL_OPTION = "baseURL";

    private ConnectorSupport() {
    }

    // ---------------------------------------------------------------------
    // Connector lookup
    // ---------------------------------------------------------------------

    static List<ProviderConnector> connectors() {
        return new ArrayList<>(ProviderRegistry.getInstance().all());
    }

    static ProviderConnector connector(String id) {
        if (id == null) {
            return null;
        }
        return ProviderRegistry.getInstance().get(id.trim().toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------------
    // Credential status (never returns secret material)
    // ---------------------------------------------------------------------

    /**
     * The AWS variables a Signature V4 connector is authenticated with, beyond its own key.
     *
     * <p>They are kept out of the connector's {@code env} list on purpose. That list is read as a
     * set of alternatives -- any one populated variable is a credential -- and half of a SigV4
     * credential signs nothing, so listing the pair there would report a connector as configured
     * when it could not make a single request.</p>
     */
    private static final List<String> SIGV4_ENV =
            List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY");

    /** A human-readable, secret-free credential status for a connector. */
    static String credentialStatus(ProviderConnector c, Configuration.AiConfig ai) {
        if (COPILOT_ID.equals(c.getId())) {
            return new GitHubCopilotAuth().hasStoredOAuthToken()
                    ? "authenticated (device flow)" : "not logged in";
        }
        if (c.getAuthScheme() == AuthScheme.NONE) {
            return "no key required";
        }
        if (savedKey(c, ai) != null) {
            return "configured (saved key)";
        }
        for (String name : c.getEnv()) {
            String v = System.getenv(name);
            if (v != null && !v.isBlank()) {
                return "configured (env " + name + ")";
            }
        }
        if (hasSignatureCredentials(c)) {
            return "configured (env " + String.join(" + ", SIGV4_ENV) + ")";
        }
        return "no credential";
    }

    /** Whether a usable credential can be resolved for the connector (saved, env, or Copilot login). */
    static boolean hasCredential(ProviderConnector c, Configuration.AiConfig ai) {
        if (COPILOT_ID.equals(c.getId())) {
            return new GitHubCopilotAuth().hasStoredOAuthToken();
        }
        if (c.getAuthScheme() == AuthScheme.NONE) {
            return true;
        }
        if (savedKey(c, ai) != null) {
            return true;
        }
        return CredentialResolver.firstEnv(c.getEnv()) != null || hasSignatureCredentials(c);
    }

    /**
     * Whether a Signature V4 connector can sign with what is in the environment.
     *
     * <p>Asked through {@link AwsSigV4Signer.Credentials#fromEnvironment()} because that is already
     * the definition of "AWS credentials are present" -- it is what {@code createBackend} calls to
     * build the backend, and it requires the access key and the secret together. Answering the
     * question anywhere else would let the two definitions drift, which is how a working Bedrock
     * setup came to be listed as having no credential: the connector declares one variable,
     * {@code AWS_BEARER_TOKEN_BEDROCK}, and this was the only list consulted.</p>
     */
    private static boolean hasSignatureCredentials(ProviderConnector c) {
        return c.getAuthScheme() == AuthScheme.AWS_SIGV4
               && AwsSigV4Signer.Credentials.fromEnvironment() != null;
    }

    /**
     * Every environment variable that authenticates a connector, for listing to the user.
     *
     * <p>Display only. {@link #hasCredential} must not be derived from this, for the reason
     * {@link #SIGV4_ENV} records.</p>
     */
    static List<String> credentialEnv(ProviderConnector c) {
        if (c.getAuthScheme() != AuthScheme.AWS_SIGV4) {
            return c.getEnv();
        }
        List<String> names = new ArrayList<>(c.getEnv());
        names.addAll(SIGV4_ENV);
        return names;
    }

    /**
     * How to supply a connector's credential through the environment, phrased for a person.
     *
     * <p>Not a plain join of {@link #credentialEnv}: the SigV4 variables are needed together while
     * the rest are alternatives, and "export A or B or C" would be an instruction that does not
     * work. Empty when the connector reads no environment variable at all.</p>
     */
    static String environmentHint(ProviderConnector c) {
        List<String> alternatives = new ArrayList<>(c.getEnv());
        if (c.getAuthScheme() == AuthScheme.AWS_SIGV4) {
            alternatives.add(String.join(" together with ", SIGV4_ENV));
        }
        return String.join(" or ", alternatives);
    }

    private static String savedKey(ProviderConnector c, Configuration.AiConfig ai) {
        if (ai == null || ai.getProviderApiKeys() == null) {
            return null;
        }
        String k = ai.getProviderApiKeys().get(c.getId());
        return (k != null && !k.isBlank()) ? k : null;
    }

    /**
     * Minimum key length before any characters are revealed. Below this, the key is fully
     * masked so the visible prefix/suffix can never expose a large fraction of a short secret
     * (a 9-12 char key would otherwise reveal 8 of its characters).
     */
    private static final int MASK_REVEAL_THRESHOLD = 16;

    /** Masks an API key for display, revealing only a short prefix/suffix. */
    static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "(none)";
        }
        String k = key.trim();
        if (k.length() < MASK_REVEAL_THRESHOLD) {
            return "****";
        }
        return k.substring(0, 4) + "..." + k.substring(k.length() - 4);
    }

    // ---------------------------------------------------------------------
    // Interactive selection (uses the TUI prompt line; falls back to the console)
    // ---------------------------------------------------------------------

    /** Lists connectors and prompts the user to choose one. Returns null if cancelled/invalid. */
    static ProviderConnector promptForProvider(String title) {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        List<ProviderConnector> all = connectors();
        OutputFormatter.printHeader(title);
        for (int i = 0; i < all.size(); i++) {
            ProviderConnector c = all.get(i);
            UnifiedOutput.printf("  %2d. %-22s %-26s [%s]%n",
                    i + 1, c.getId(), c.getDisplayName(), credentialStatus(c, ai));
        }
        String choice = OutputRouter.getInstance()
                .getUserInput("Select a provider (number or id, blank to cancel): ");
        return resolveProviderChoice(all, choice);
    }

    /** Resolves a typed provider choice (1-based number or id) against the given list. */
    static ProviderConnector resolveProviderChoice(List<ProviderConnector> all, String choice) {
        if (choice == null) {
            return null;
        }
        String t = choice.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            int idx = Integer.parseInt(t);
            if (idx >= 1 && idx <= all.size()) {
                return all.get(idx - 1);
            }
            OutputFormatter.printError("Number out of range: " + idx);
            return null;
        } catch (NumberFormatException notANumber) {
            ProviderConnector byId = connector(t);
            if (byId == null) {
                OutputFormatter.printError("Unknown provider: " + t);
            }
            return byId;
        }
    }

    /**
     * Lists the connector's catalog models and prompts the user to choose one. When the
     * catalog has no models for the provider, prompts for a free-form model id. Returns the
     * chosen model id, or null if cancelled.
     */
    /**
     * Every model the connector offers, from whichever source can say.
     *
     * <p>The catalog is preferred where it has an answer, because it carries pricing, context
     * limits and capability flags that a listing from the provider does not. Where it has none --
     * a gateway newer than the catalog, a local server, an endpoint inside a company -- the
     * provider is asked directly, rather than the user being asked to remember model ids.</p>
     *
     * @param c the connector
     * @return the models, in catalog or provider order; empty when neither source could say
     */
    /**
     * Every model this connector offers.
     *
     * <h2>Why asking this for all twenty-three connectors is cheap</h2>
     *
     * <p>Only a connector the catalogue does not carry costs a request, and the catalogue carries
     * all but two of them. The answer is then remembered for the rest of the process, so the four
     * callers that want the same list within one {@code models} command -- the count in the table,
     * the table of one connector's models, a search, and the interactive picker -- pay for it
     * once.</p>
     *
     * @param c the connector
     * @return its models, empty when neither the catalogue nor the provider would say
     */
    static List<ModelsDevModel> models(ProviderConnector c) {
        return models(c, options(c.getId()));
    }

    /**
     * Every model this connector offers, asked of the server at the given options' address.
     *
     * @param c       the connector
     * @param options the options a listing goes by, which may name an address not yet saved
     * @return its models, empty when neither the catalogue nor the provider would say
     */
    private static List<ModelsDevModel> models(ProviderConnector c, Map<String, String> options) {
        ModelsDevProvider cat = ModelCatalog.getInstance().getProvider(c.getId());
        if (cat != null && !cat.getModels().isEmpty()) {
            return new ArrayList<>(cat.getModels().values());
        }
        return new ArrayList<>(ProviderModelListing.fetch(c, resolvedKey(c), options));
    }

    /**
     * Whether this connector publishes its own model list rather than appearing in the catalogue.
     *
     * @param c the connector
     * @return whether {@code models <id>} would ask the provider itself
     */
    static boolean listsItsOwnModels(ProviderConnector c) {
        ModelsDevProvider cat = ModelCatalog.getInstance().getProvider(c.getId());
        return (cat == null || cat.getModels().isEmpty()) && ProviderModelListing.supports(c);
    }

    /** The credential a listing request carries, resolved by the rules a completion uses. */
    private static String resolvedKey(ProviderConnector c) {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        return CredentialResolver.resolveApiKey(c.getId(), c.getEnv(), ai.getProviderApiKeys(), null);
    }

    /** The configured per-provider options, so a listing goes where a completion would go. */
    private static Map<String, String> options(String providerId) {
        Map<String, Map<String, String>> all =
                ConfigManager.getInstance().getConfig().getAi().getProviderOptions();
        return all == null ? null : all.get(providerId);
    }

    /** The configured options, with the address from an answer in place of the saved one. */
    private static Map<String, String> optionsAt(String providerId, ServerAddress.Answer where) {
        Map<String, String> saved = options(providerId);
        if (where.baseUrl() == null) {
            return saved;
        }
        Map<String, String> moved = saved == null ? new LinkedHashMap<>() : new LinkedHashMap<>(saved);
        moved.put(BASE_URL_OPTION, where.baseUrl());
        return moved;
    }

    /**
     * Asks where the connector's server runs, when it may run on any machine.
     *
     * <p>A local OpenAI-compatible server is as often on another machine on the network as on this
     * one; see {@link ServerAddress}. The question shows the address in use, and Enter keeps it.</p>
     *
     * @param c the connector about to be used
     * @return the answer; {@link ServerAddress.Answer#KEEP} for a connector that is not asked
     */
    static ServerAddress.Answer promptForAddress(ProviderConnector c) {
        if (!ServerAddress.asksWhere(c)) {
            return ServerAddress.Answer.KEEP;
        }
        String inUse = ServerAddress.inUse(c, options(c.getId()));
        String typed = OutputRouter.getInstance().getUserInput(
                "Where does the " + c.getId() + " server run? host:port or URL (Enter keeps "
                + inUse + "): ");
        try {
            String address = ServerAddress.read(typed, inUse);
            return address.equals(inUse) ? ServerAddress.Answer.KEEP
                                         : ServerAddress.Answer.at(address);
        } catch (IllegalArgumentException unreadable) {
            OutputFormatter.printError(unreadable.getMessage());
            return ServerAddress.Answer.CANCELLED;
        }
    }

    static String promptForModel(ProviderConnector c) {
        return promptForModel(c, ServerAddress.Answer.KEEP);
    }

    /**
     * Lists the connector's models and asks which to use.
     *
     * @param c     the connector
     * @param where where its server runs, from {@link #promptForAddress}
     * @return the model id chosen, or {@code null} when none was
     */
    static String promptForModel(ProviderConnector c, ServerAddress.Answer where) {
        List<ModelsDevModel> models = models(c, optionsAt(c.getId(), where));

        if (models.isEmpty()) {
            if (ServerAddress.asksWhere(c)) {
                OutputFormatter.printInfo("No model list came back from "
                        + ServerAddress.inUse(c, optionsAt(c.getId(), where))
                        + "; type the model id if the server is not running yet.");
            }
            String m = OutputRouter.getInstance()
                    .getUserInput("Enter the model id for " + c.getId() + " (blank to cancel): ");
            return (m == null || m.trim().isEmpty()) ? null : m.trim();
        }

        // Every model is listed: the list is what the user chooses from, and one cut at forty left
        // the rest choosable only by someone who already knew their ids.
        OutputFormatter.printHeader(c.getDisplayName() + " models (" + models.size() + ")");
        for (int i = 0; i < models.size(); i++) {
            ModelsDevModel m = models.get(i);
            UnifiedOutput.printf("  %3d. %-40s %s%n",
                    i + 1, m.getId(), m.getName() != null ? m.getName() : "");
        }

        String choice = OutputRouter.getInstance().getUserInput(
                "Select a model (number, or type any model id; blank to cancel): ");
        if (choice == null || choice.trim().isEmpty()) {
            return null;
        }
        String t = choice.trim();
        try {
            int idx = Integer.parseInt(t);
            if (idx >= 1 && idx <= models.size()) {
                return models.get(idx - 1).getId();
            }
            OutputFormatter.printError("Number out of range: " + idx);
            return null;
        } catch (NumberFormatException notANumber) {
            return t; // a model id, used as given: a server may serve models it does not list
        }
    }

    // ---------------------------------------------------------------------
    // Apply / persist the active model
    // ---------------------------------------------------------------------

    /**
     * Persists the active provider (and model, when given), saves the configuration, and
     * resets the cached AI client so the change takes effect on the next request.
     *
     * <p>The success confirmation is gated on the save result: if persistence fails, an error
     * is reported and no "active model set" message is printed, so the user is never told the
     * change stuck when it did not. The confirmation deliberately omits the on-disk config path
     * to avoid disclosing it on every write.</p>
     *
     * @return {@code true} if the configuration was persisted successfully, {@code false} if the
     *         save failed (in which case the change was not durably applied).
     */
    static boolean applyActiveModel(String providerId, String model) {
        return applyActiveModel(providerId, model, ServerAddress.Answer.KEEP);
    }

    /**
     * Persists the active provider and model, and the address of its server when one was given.
     *
     * @param providerId the connector
     * @param model      the model, or {@code null} to keep the configured one
     * @param where      where the server runs, from {@link #promptForAddress}
     * @return whether the configuration was saved
     */
    static boolean applyActiveModel(String providerId, String model, ServerAddress.Answer where) {
        return applyActiveModel(providerId, model, where, null);
    }

    /**
     * Persists the active provider and model, where its server runs, and its input window.
     *
     * @param providerId the connector
     * @param model      the model, or {@code null} to keep the configured one
     * @param where      where the server runs, from {@link #promptForAddress}
     * @param window     the model's input window in tokens, or {@code null} to leave it as it is
     * @return whether the configuration was saved
     */
    static boolean applyActiveModel(String providerId, String model, ServerAddress.Answer where,
                                    Integer window) {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        ai.setProvider(providerId);
        List<String> chosen = new ArrayList<>(List.of("ai.provider"));
        boolean hasModel = model != null && !model.isBlank();
        if (hasModel) {
            ai.setModel(model);
            chosen.add("ai.model");
        }
        if (where.baseUrl() != null) {
            ai.getProviderOptions().computeIfAbsent(providerId, id -> new LinkedHashMap<>())
              .put(BASE_URL_OPTION, where.baseUrl());
            chosen.add("ai.providerOptions." + providerId + "." + BASE_URL_OPTION);
        }
        if (window != null) {
            ai.getModelContextTokens().put(ContextWindow.keyFor(providerId, ai.getModel()), window);
            chosen.add("ai.modelContextTokens");
        }
        boolean saved = ConfigManager.getInstance().saveChoice(chosen.toArray(new String[0]));
        if (!saved) {
            OutputFormatter.printError("Could not save the configuration; "
                    + "the active model was not persisted.");
            return false;
        }
        AIManager.getInstance().resetActiveClient();
        ProviderConnector c = connector(providerId);
        OutputFormatter.printSuccess("Active model set to " + providerId
                + (hasModel ? "/" + model : "")
                + (ServerAddress.asksWhere(c) ? " at " + ServerAddress.inUse(c, options(providerId))
                                              : "")
                + (window != null ? String.format(", taking %,d input tokens", window) : ""));
        return true;
    }

    /**
     * Disconnects GitHub Copilot, whichever command was asked to do it.
     *
     * <p>{@code login logout github-copilot} and {@code copilot logout} are the same act, and a
     * token removed by one of them has to be removed by the other. Written twice, the second copy is
     * the one that eventually stops calling {@link AIManager#resetActiveClient()} and leaves a
     * process authenticating with a credential the user has been told is gone.</p>
     *
     * @return the exit code: 0 whether or not there was anything stored, 1 when a stored token could
     *         not be removed
     */
    static int logOutOfCopilot() {
        boolean removed;
        try {
            removed = new GitHubCopilotAuth().clearStoredToken();
        } catch (java.io.IOException failure) {
            // Distinguished from "nothing stored" on purpose: the token is still on disk and still
            // authenticates this machine, so reporting a clean logout would leave the user believing
            // a live credential had been revoked.
            OutputFormatter.printError("Could not remove the stored GitHub Copilot credentials: "
                                       + failure.getMessage() + " - this machine is still logged in.");
            return 1;
        }
        OutputFormatter.printInfo(removed ? "Removed stored GitHub Copilot credentials."
                                          : "No stored GitHub Copilot credentials to remove.");
        AIManager.getInstance().resetActiveClient();
        return 0;
    }
}
