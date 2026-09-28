package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.ContextWindow;
import com.eonmux.cadetcoder.ai.catalog.ModelCatalog;
import com.eonmux.cadetcoder.ai.catalog.ModelsDevModel;
import com.eonmux.cadetcoder.ai.catalog.ModelsDevProvider;
import com.eonmux.cadetcoder.ai.providers.DataRetentionControl.Promise;
import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.ai.providers.ProviderRegistry;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.ui.UnifiedOutput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lists the connectors and the models.dev catalog &mdash; the opencode-style provider and
 * model database now available in CadetCoder &mdash; and configures the active model.
 *
 * <pre>
 *   models                          summary of the catalog + connectors
 *   models providers                list the built-in connectors
 *   models &lt;providerId&gt;              list the models offered by a catalog provider
 *   models find &lt;text&gt;               search models across all providers by id/name
 *   models current                  show the active provider + model
 *   models use &lt;provider&gt; [model] [--context=&lt;tokens&gt;]
 *                                   set the active provider (and model, and its window)
 *   models select                   interactively choose a provider and model
 *   models context [&lt;tokens&gt;|clear]  show, set or clear the active model's input window
 * </pre>
 *
 * <h2>Why a window can be given by hand</h2>
 *
 * <p>Every prompt is measured against the model's input window. The catalog publishes it for the
 * models it knows; a model on a local server or a private deployment is not among them and was
 * assumed to take {@value ContextWindow#DEFAULT_TOKENS} tokens. The window is recorded for the
 * provider and model it belongs to, so it stops applying when another model is chosen.</p>
 */
@picocli.CommandLine.Command (name = "models", description = "List AI providers/models and configure the active model")
public class ModelsCommand implements CommandRegistry.Command {

    /** Stands in the Models column for a connector that would have answered but did not. */
    private static final String UNASKED = "-";

    /** The option of {@code models use} that gives the model's input window. */
    private static final String CONTEXT_OPTION = "--context=";

    /** The word that removes a window given by hand. */
    private static final String CLEAR = "clear";

    /** How often an unreadable window is asked for again before the assumption is kept. */
    private static final int WINDOW_ATTEMPTS = 3;

    /** The subcommands that switch or save the active model, which only the user may do. */
    private static final java.util.Set<String> CHANGES_THE_ACTIVE_MODEL =
            java.util.Set.of("use", "set", "select", "pick");

    /** The subcommands that show the model's input window alone and set it when given a value. */
    private static final java.util.Set<String> SETS_THE_WINDOW = java.util.Set.of("context", "window");

    @Override
    public int execute(String[] args) {
        String verb = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (CHANGES_THE_ACTIVE_MODEL.contains(verb) || (SETS_THE_WINDOW.contains(verb) && args.length > 1)) {
            Integer refused = ModelDispatch.refuseSetupChange("models " + args[0]);
            if (refused != null) {
                return refused;
            }
        }
        ModelCatalog catalog = ModelCatalog.getInstance();
        ProviderRegistry registry = ProviderRegistry.getInstance();

        if (args.length == 0) {
            return printSummary(catalog, registry);
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if ("providers".equals(first) || "connectors".equals(first) || "--connectors".equals(first)) {
            return printConnectors(registry);
        }
        // "find" is the CLI verb; "search" is accepted as an alias for the interactive shell
        // (in the picocli CLI path the bare word "search" is intercepted by the top-level
        // search command, so "models find <text>" is the portable form).
        if ("find".equals(first) || "search".equals(first)) {
            if (args.length < 2) {
                OutputFormatter.printError("Which text should I search the model names for?");
                OutputFormatter.printInfo("Usage: " + CommandUsage.prefix() + "models find <text>");
                return 1;
            }
            return search(catalog, registry, args[1]);
        }
        if ("current".equals(first) || "active".equals(first)) {
            return printCurrent();
        }
        if ("use".equals(first) || "set".equals(first)) {
            return useModel(args);
        }
        if ("select".equals(first) || "pick".equals(first)) {
            return selectInteractive();
        }
        if ("context".equals(first) || "window".equals(first)) {
            return context(args);
        }
        return printProviderModels(catalog, first);
    }

    /** Shows the currently configured connector provider and model. */
    private int printCurrent() {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        OutputFormatter.printHeader("Active model");
        String provider = ai.getProvider();
        if (provider == null || provider.isBlank()) {
            OutputFormatter.printInfo("No connector provider is configured "
                    + "(falling back to the legacy local/API client).");
            OutputFormatter.printInfo("Model: " + ai.getModel());
            OutputFormatter.printInfo("Run '" + CommandUsage.prefix() + "models select' or '"
                    + CommandUsage.prefix() + "login' to choose a provider.");
            return 0;
        }
        ProviderConnector c = ConnectorSupport.connector(provider);
        UnifiedOutput.printf("  Provider: %s%s%n", provider,
                c != null ? " (" + c.getDisplayName() + ")" : " (unknown connector)");
        UnifiedOutput.printf("  Model:    %s%n", ai.getModel());
        printWindow();
        if (c != null) {
            UnifiedOutput.printf("  Auth:     %s%n", ConnectorSupport.credentialStatus(c, ai));
        }
        return 0;
    }

    /**
     * {@code models use <provider> [model] [--context=<tokens>]} - sets the active provider,
     * prompting for a model if omitted.
     */
    private int useModel(String[] args) {
        List<String> words  = new ArrayList<>();
        Integer      window = null;
        for (String arg : args) {
            if (!arg.toLowerCase(Locale.ROOT).startsWith(CONTEXT_OPTION)) {
                words.add(arg);
                continue;
            }
            int tokens = windowOf(arg.substring(CONTEXT_OPTION.length()));
            if (tokens <= 0) {
                refuseWindow(arg.substring(CONTEXT_OPTION.length()));
                return 1;
            }
            window = tokens;
        }
        if (words.size() < 2) {
            OutputFormatter.printError("Which provider should I use?");
            OutputFormatter.printInfo("Usage: " + CommandUsage.prefix()
                    + "models use <provider> [model] [" + CONTEXT_OPTION + "<tokens>]");
            return 1;
        }
        ProviderConnector c = ConnectorSupport.connector(words.get(1));
        if (c == null) {
            OutputFormatter.printError("Unknown provider: " + words.get(1));
            OutputFormatter.printInfo("Run '" + CommandUsage.prefix() + "models providers' to see connectors.");
            return 1;
        }
        if (words.size() >= 3) {
            warnIfNoCredential(c);
            return ConnectorSupport.applyActiveModel(c.getId(), words.get(2),
                                                     ServerAddress.Answer.KEEP, window) ? 0 : 1;
        }
        if (window != null) {
            OutputFormatter.printError("A window belongs to one model; name the model too.");
            OutputFormatter.printInfo("Usage: " + CommandUsage.prefix()
                    + "models use <provider> <model> " + CONTEXT_OPTION + "<tokens>");
            return 1;
        }
        return chooseModelOf(c);
    }

    /**
     * {@code models context [<tokens>|clear]} - shows, sets or clears the window of the model in use.
     *
     * @param args the arguments, the first being {@code context}
     * @return the exit code
     */
    private int context(String[] args) {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        if (ai.getProvider() == null || ai.getProvider().isBlank()
            || ai.getModel() == null || ai.getModel().isBlank()) {
            OutputFormatter.printError("No model is active, so there is no window to give.");
            OutputFormatter.printInfo("Choose one first with '" + CommandUsage.prefix()
                                      + "models select'.");
            return 1;
        }
        String key = ContextWindow.keyFor(ai.getProvider(), ai.getModel());
        if (args.length < 2) {
            printWindow();
            OutputFormatter.printInfo("Give it with '" + CommandUsage.prefix()
                    + "models context <tokens>', or remove it with '" + CommandUsage.prefix()
                    + "models context " + CLEAR + "'.");
            return 0;
        }
        String given = args[1];
        if (CLEAR.equalsIgnoreCase(given)) {
            if (ai.getModelContextTokens().remove(key) == null) {
                OutputFormatter.printInfo("No window was given for " + key + "; nothing changed.");
                printWindow();
                return 0;
            }
            return saveWindow("The window given for " + key + " is removed.");
        }
        int tokens = windowOf(given);
        if (tokens <= 0) {
            refuseWindow(given);
            return 1;
        }
        ai.getModelContextTokens().put(key, tokens);
        return saveWindow(String.format("%s takes %,d input tokens.", key, tokens));
    }

    /** Saves a change to the windows given by hand, and says what is in force now. */
    private int saveWindow(String done) {
        if (!ConfigManager.getInstance().saveChoice("ai.modelContextTokens")) {
            OutputFormatter.printError("Could not save the configuration; the window applies "
                                       + "to this run only.");
            return 1;
        }
        OutputFormatter.printSuccess(done);
        printWindow();
        return 0;
    }

    private static void printWindow() {
        ContextWindow.Window window = ContextWindow.current();
        UnifiedOutput.printf("  Window:   %,d input tokens (%s)%n", window.tokens(), window.source());
    }

    /**
     * Reads a window as typed.
     *
     * @param typed the text, which may group its digits with commas or underscores
     * @return the number of tokens, or {@code -1} when the text is not a positive whole number
     */
    static int windowOf(String typed) {
        if (typed == null) {
            return -1;
        }
        String digits = typed.trim().replace(",", "").replace("_", "");
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        try {
            int tokens = Integer.parseInt(digits);
            return tokens > 0 ? tokens : -1;
        } catch (NumberFormatException tooLarge) {
            return -1;
        }
    }

    private static void refuseWindow(String typed) {
        OutputFormatter.printError("'" + typed + "' is not a window: give the number of input "
                                   + "tokens, for example 131072.");
    }

    /**
     * Asks where the connector's server runs when that is the user's to say, then which model to
     * use, and makes the choice active.
     *
     * @param c the connector chosen
     * @return the exit code
     */
    private int chooseModelOf(ProviderConnector c) {
        ServerAddress.Answer where = ConnectorSupport.promptForAddress(c);
        if (where.cancelled()) {
            OutputFormatter.printInfo("Provider not changed.");
            return 1;
        }
        String model = ConnectorSupport.promptForModel(c, where);
        if (model == null) {
            OutputFormatter.printInfo("No model selected; provider not changed.");
            return 0;
        }
        Integer window = askWindowOf(c.getId(), model);
        warnIfNoCredential(c);
        return ConnectorSupport.applyActiveModel(c.getId(), model, where, window) ? 0 : 1;
    }

    /**
     * Asks the window of a model the catalog does not describe, when none was given for it before.
     *
     * <p>Without an answer the window is assumed, and every prompt is cut to fit the assumption.
     * The person choosing a model on their own server is the one who knows what it was started
     * with.</p>
     *
     * @param providerId the connector chosen
     * @param model      the model chosen
     * @return the window given, or {@code null} to keep what is published, given or assumed
     */
    private static Integer askWindowOf(String providerId, String model) {
        String key = ContextWindow.keyFor(providerId, model);
        if (ConfigManager.getInstance().getConfig().getAi().getModelContextTokens().containsKey(key)
            || ContextWindow.isPublished(providerId, model)) {
            return null;
        }
        String question = String.format("No context window is published for %s. How many input "
                                        + "tokens does it take? (Enter assumes %,d): ",
                                        key, ContextWindow.DEFAULT_TOKENS);
        for (int attempt = 0; attempt < WINDOW_ATTEMPTS; attempt++) {
            String typed = OutputRouter.getInstance().getUserInput(question);
            if (typed == null || typed.isBlank()) {
                return null;
            }
            int tokens = windowOf(typed);
            if (tokens > 0) {
                return tokens;
            }
            refuseWindow(typed.trim());
        }
        OutputFormatter.printInfo("The window stays assumed; give it later with '"
                                  + CommandUsage.prefix() + "models context <tokens>'.");
        return null;
    }

    /** Fully interactive provider + model picker. */
    private int selectInteractive() {
        ProviderConnector c = ConnectorSupport.promptForProvider("Select a provider");
        if (c == null) {
            OutputFormatter.printInfo("Selection cancelled.");
            return 0;
        }
        return chooseModelOf(c);
    }

    private void warnIfNoCredential(ProviderConnector c) {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        if (!ConnectorSupport.hasCredential(c, ai)) {
            OutputFormatter.printWarning("No credential found for " + c.getId()
                    + ". Run '" + CommandUsage.prefix() + "login " + c.getId()
                    + "' to authenticate before chatting.");
        }
    }

    private int printSummary(ModelCatalog catalog, ProviderRegistry registry) {
        Map<String, ModelsDevProvider> providers = catalog.getProviders();
        int totalModels = providers.values().stream().mapToInt(p -> p.getModels().size()).sum();

        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        OutputFormatter.printHeader("Model catalog (models.dev)");
        OutputFormatter.printInfo(providers.size() + " providers, " + totalModels + " models available.");
        if (ai.getProvider() != null && !ai.getProvider().isBlank()) {
            OutputFormatter.printInfo("Active: " + ai.getProvider() + "/" + ai.getModel());
        }

        OutputFormatter.printHeader("Connectors (" + registry.all().size() + ")");
        // Rendered as a table so the columns are measured from the content. The fixed %-22s/%-26s
        // widths these listings used overflowed on every id or display name longer than the guess,
        // pushing that row's later columns out of line, and padded shorter rows with trailing spaces.
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Connector", "Name", "Models"});
        boolean anyUnasked = false;
        for (ProviderConnector c : registry.all()) {
            String count = modelCount(c);
            anyUnasked |= UNASKED.equals(count);
            rows.add(new String[] {c.getId(), c.getDisplayName(), count});
        }
        OutputFormatter.printTable(rows, true);
        if (anyUnasked) {
            OutputFormatter.printInfo("\"" + UNASKED + "\" means the connector publishes its own "
                    + "model list rather than appearing in the catalogue, and did not answer: it "
                    + "may be unreachable, or need a credential. Run '" + CommandUsage.prefix()
                    + "models <connector>' to see what it says.");
        }
        printHowToChoose();
        return 0;
    }

    /**
     * How many models a connector offers, for the table.
     *
     * <h2>Why this is not the catalogue's count</h2>
     *
     * <p>models.dev does not list every provider a user can reach, and a gateway is exactly the kind
     * it misses. The column read the catalogue alone, so Command Code -- seventy-one models, every
     * one of them printed by {@code models commandcode} -- appeared in this table as {@code 0}. A
     * user looking at the row for their own active provider was told it served nothing. A connector
     * that would have answered but could not be reached is printed as a dash rather than as a zero,
     * because the two mean opposite things.</p>
     *
     * @param c the connector
     * @return the count, or {@link #UNASKED} when it could not be had cheaply
     */
    private static String modelCount(ProviderConnector c) {
        int count = ConnectorSupport.models(c).size();
        if (count > 0) {
            return String.valueOf(count);
        }
        return ConnectorSupport.listsItsOwnModels(c) ? UNASKED : "0";
    }

    /**
     * The two commands that change which model answers.
     *
     * <h2>Why this is a section rather than a sentence</h2>
     *
     * <p>Six commands were listed across two run-on lines, in the order they appear in the source
     * rather than the order anyone uses them, and none of them said which one actually selects a
     * model. Choosing one is two steps: see what a connector offers, then name one. Those two are
     * numbered here and the rest is listed after them.</p>
     */
    private void printHowToChoose() {
        String p = CommandUsage.prefix();
        OutputFormatter.printHeader("Choosing a model");
        UnifiedOutput.printf("  1. %smodels <connector>%n", p);
        UnifiedOutput.printf("     lists the models that connector offers%n");
        UnifiedOutput.printf("  2. %smodels use <connector> <model>%n", p);
        UnifiedOutput.printf("     makes one of them the model that answers%n");
        OutputFormatter.printInfo("Or '" + p + "models select' to do both, one prompt at a time.");
        OutputFormatter.printInfo("Also: '" + p + "models find <text>' searches every model, '" + p
                + "models current' shows the active one, '" + p
                + "models context <tokens>' gives its input window, '" + p
                + "login <connector>' saves a credential.");
    }

    /**
     * The connector table.
     *
     * <h2>Why retention is a column</h2>
     *
     * <p>{@code ai.zeroDataRetention} is on unless it is turned off, and it can only be honoured by
     * a provider that offers a way to refuse retention. Without somewhere saying which providers
     * those are, a setting that reads as on everywhere would in fact be doing nothing on most of
     * this table -- a privacy guarantee believed rather than held, which is the worst version of
     * one. The column says where it bites.</p>
     *
     * <p>It distinguishes two strengths for the same reason. A provider that will not retain the
     * request and a provider that will merely not store it where the account can read it back are
     * not offering the same thing, and printing one word for both would tell the reader they had the
     * stronger of the two everywhere they had the weaker.</p>
     */
    private int printConnectors(ProviderRegistry registry) {
        OutputFormatter.printHeader("Connectors");
        boolean asked = ConfigManager.getInstance().getConfig().getAi().isZeroDataRetention();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Connector", "Name", "Protocol", "Auth", "Retention", "Environment"});
        for (ProviderConnector c : registry.all()) {
            rows.add(new String[] {
                    c.getId(),
                    c.getDisplayName(),
                    c.protocolLabel(),
                    String.valueOf(c.getAuthScheme()),
                    retentionLabel(c, asked),
                    ConnectorSupport.credentialEnv(c).isEmpty()
                            ? "-" : String.join(",", ConnectorSupport.credentialEnv(c))});
        }
        OutputFormatter.printTable(rows, true);
        if (!asked) {
            OutputFormatter.printInfo("Zero data retention is turned off ("
                    + CommandUsage.prefix() + "config ai.zeroDataRetention true to ask for it).");
        } else if (anyNoStorage(registry)) {
            OutputFormatter.printInfo("\"no storage\" is weaker than \"zero retention\": the request"
                    + " is kept out of stored history, but operational logs the provider exposes no"
                    + " request-level control over are not covered. OpenAI arranges true zero"
                    + " retention per organisation, not per request.");
        }
        return 0;
    }

    /** Whether the table has a row the legend above needs to explain. */
    private static boolean anyNoStorage(ProviderRegistry registry) {
        return registry.all().stream()
                .anyMatch(c -> c.retentionControl().promise() == Promise.NO_STORAGE);
    }

    /**
     * What this connector does about retention, given the setting in force.
     *
     * @param c     the connector
     * @param asked whether the user has asked for zero retention
     * @return a phrase for the table
     */
    static String retentionLabel(ProviderConnector c, boolean asked) {
        Promise promise = c.retentionControl().promise();
        if (promise == Promise.NONE) {
            return "provider default";
        }
        if (!asked) {
            return "provider default (off)";
        }
        return promise == Promise.ZERO_RETENTION ? "zero retention" : "no storage";
    }

    /**
     * Lists one provider's models.
     *
     * <p>A provider is worth listing when either source knows it: the catalog, or a registered
     * connector. Requiring the catalog alone made every connector it does not carry look like a
     * misspelling -- "Unknown provider: commandcode" for a provider the tool could reach, was
     * authenticated against, and would happily have run a completion on.</p>
     */
    /**
     * What to call a provider, from whichever of the two sources can say.
     *
     * @param provider   the catalog entry, or {@code null} if the catalog does not carry it
     * @param connector  the local connector, or {@code null} if none implements it
     * @param providerId the id, which is always known
     * @return the best name available, never {@code null}
     */
    private static String label(ModelsDevProvider provider, ProviderConnector connector,
                                String providerId) {
        if (provider != null && provider.getName() != null) {
            return provider.getName();
        }
        return connector != null ? connector.getDisplayName() : providerId;
    }

    private int printProviderModels(ModelCatalog catalog, String providerId) {
        ModelsDevProvider provider  = catalog.getProvider(providerId);
        ProviderConnector connector = ConnectorSupport.connector(providerId);
        if (provider == null && connector == null) {
            OutputFormatter.printError("Unknown provider: " + providerId);
            OutputFormatter.printInfo("Run '" + CommandUsage.prefix() + "models providers' to see "
                    + "connectors, or '" + CommandUsage.prefix() + "models' for the catalog summary.");
            return 1;
        }

        // The guard above is an OR, so it proves at least one of the two is present -- not that
        // this one is. A catalog entry with no "name" (the field is deserialized straight from
        // models.dev and only the id is backfilled) and no local connector sent this straight into
        // a null dereference, so `models <id>` threw instead of listing.
        String label = label(provider, connector, providerId);
        List<ModelsDevModel> models = (connector != null)
                ? ConnectorSupport.models(connector)
                : new ArrayList<>(provider.getModels().values());

        if (models.isEmpty()) {
            OutputFormatter.printHeader(label + " (no models)");
            OutputFormatter.printInfo("Neither the catalog nor the provider named a model. A "
                    + "provider that authenticates its model list cannot be asked without a key: "
                    + "run '" + CommandUsage.prefix() + "login " + providerId + "' first.");
            return 0;
        }

        OutputFormatter.printHeader(label + " (" + models.size()
                                    + (models.size() == 1 ? " model)" : " models)"));
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Model", "Name", "Context"});
        for (ModelsDevModel m : models) {
            rows.add(new String[] {
                    m.getId(),
                    m.getName() != null ? m.getName() : "",
                    (contextLabel(m) + statusLabel(m)).trim()});
        }
        OutputFormatter.printTable(rows, true);
        // A table of seventy-one ids ended here, and nothing on screen said what to type next.
        OutputFormatter.printInfo("Use one of these with: " + CommandUsage.prefix()
                                  + "models use " + providerId + " <model>");
        return 0;
    }

    /**
     * Searches every model the tool can reach.
     *
     * <h2>Why the catalogue is not the whole search</h2>
     *
     * <p>A connector that publishes its own model list is not in the catalogue, so searching the
     * catalogue alone searched everything except the providers the user had configured.
     * {@code models find sonnet} returned three Claude models from gateways the user had never
     * heard of and none from the one they were authenticated against.</p>
     */
    private int search(ModelCatalog catalog, ProviderRegistry registry, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Provider", "Model", "Context"});
        for (ProviderConnector c : registry.all()) {
            if (ConnectorSupport.listsItsOwnModels(c)) {
                collectMatches(c.getId(), ConnectorSupport.models(c), q, rows);
            }
        }
        for (ModelsDevProvider provider : catalog.getProviders().values()) {
            collectMatches(provider.getId(), provider.getModels().values(), q, rows);
        }
        int matches = rows.size() - 1;

        OutputFormatter.printHeader("Models matching '" + query + "'");
        if (matches == 0) {
            OutputFormatter.printInfo("No models matched.");
            return 0;
        }
        OutputFormatter.printTable(rows, true);
        OutputFormatter.printInfo(matches + (matches == 1 ? " model matched." : " models matched.")
                                  + " Use one with: " + CommandUsage.prefix()
                                  + "models use <provider> <model>");
        return 0;
    }

    /**
     * Adds every model whose id or name contains the query.
     *
     * @param providerId the provider the models belong to
     * @param models     that provider's models
     * @param query      the search text, already lower-cased
     * @param rows       the table being built, appended to in place
     */
    private void collectMatches(String providerId, Collection<ModelsDevModel> models,
                                String query, List<String[]> rows) {
        for (ModelsDevModel m : models) {
            String id   = m.getId() != null ? m.getId().toLowerCase(Locale.ROOT) : "";
            String name = m.getName() != null ? m.getName().toLowerCase(Locale.ROOT) : "";
            if (id.contains(query) || name.contains(query)) {
                rows.add(new String[] {providerId, m.getId(), contextLabel(m).trim()});
            }
        }
    }

    private String contextLabel(ModelsDevModel m) {
        if (m.getLimit() != null && m.getLimit().getContext() > 0) {
            long ctx = m.getLimit().getContext();
            return "ctx=" + (ctx >= 1000 ? (ctx / 1000) + "k" : String.valueOf(ctx));
        }
        return "";
    }

    private String statusLabel(ModelsDevModel m) {
        return (m.getStatus() != null && !m.getStatus().isBlank()) ? " [" + m.getStatus() + "]" : "";
    }


    @Override
    public String getUsage() {
        return "models                                  the catalog and the connectors\n"
             + "models providers                        every connector, with its protocol\n"
             + "models <provider>                       the models one provider offers\n"
             + "models find <text>                      search every model by id or name\n"
             + "models current                          the active provider, model and window\n"
             + "models use <provider> [model] [--context=<tokens>]\n"
             + "                                        make a model the one that answers\n"
             + "models select                           choose a provider and model by prompts\n"
             + "models context [<tokens>|clear]         show, give or remove the active window";
    }
}
