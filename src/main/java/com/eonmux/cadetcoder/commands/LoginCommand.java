package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.providers.AuthScheme;
import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.auth.GitHubCopilotAuth;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.ui.UnifiedOutput;

import java.util.Locale;

/**
 * Easy provider authentication ("login") for the interactive shell and the CLI.
 *
 * <pre>
 *   login                  interactive: pick a provider, then authenticate it
 *   login &lt;provider&gt;        authenticate a specific connector (prompts for an API key,
 *                          or runs the GitHub Copilot device flow)
 *   login status           show the auth status of every connector (keys are never shown)
 *   login logout &lt;prov&gt;     remove the saved API key (or Copilot token) for a provider
 * </pre>
 *
 * <p>API keys are <em>not</em> written to the command history and are stored per provider in
 * the configuration. The key is <em>not</em> shown as it is typed: in the shell it is read on the
 * input line, which draws one mark per character, and outside it with
 * {@link java.io.Console#readPassword(String, Object...)}. With neither, as when input is
 * redirected, the key is <em>never</em> read: the user is directed to export the provider
 * environment variable instead. After a successful login the user is offered the chance to make
 * the provider active and choose a model.</p>
 */
@picocli.CommandLine.Command (name = "login", description = "Connect an AI provider (API key or device flow)")
public class LoginCommand implements CommandRegistry.Command {

    @Override
    public int execute(String[] args) {
        String action = (args.length > 0 && args[0] != null)
                ? args[0].trim().toLowerCase(Locale.ROOT) : "";
        if (!action.equals("status") && !action.equals("list")) {
            Integer refused = ModelDispatch.refuseSetupChange(("login " + action).trim());
            if (refused != null) {
                return refused;
            }
        }

        switch (action) {
            case "":
                return interactiveLogin();
            case "status":
            case "list":
                return status();
            case "logout":
            case "signout":
                return logout(args.length > 1 ? args[1] : null);
            default:
                if (args.length > 1) {
                    OutputFormatter.printWarning("Extra arguments ignored. For security, the API key is "
                            + "requested at a prompt (it is never read from the command line or history).");
                }
                return loginProvider(action);
        }
    }

    // ---------------------------------------------------------------------
    // Login flows
    // ---------------------------------------------------------------------

    private int interactiveLogin() {
        ProviderConnector c = ConnectorSupport.promptForProvider("Login - choose a provider");
        if (c == null) {
            OutputFormatter.printInfo("Login cancelled.");
            return 0;
        }
        return authenticate(c);
    }

    private int loginProvider(String providerId) {
        ProviderConnector c = ConnectorSupport.connector(providerId);
        if (c == null) {
            OutputFormatter.printError("Unknown provider: " + providerId);
            OutputFormatter.printInfo("Run '" + CommandUsage.prefix() + "login' with no arguments to choose from the list, "
                    + "or '" + CommandUsage.prefix() + "models providers' to see every connector.");
            return 1;
        }
        return authenticate(c);
    }

    private int authenticate(ProviderConnector c) {
        if (ConnectorSupport.COPILOT_ID.equals(c.getId())) {
            return copilotLogin(c);
        }
        if (c.getAuthScheme() == AuthScheme.NONE) {
            OutputFormatter.printSuccess(c.getDisplayName() + " needs no API key.");
            return offerActivate(c);
        }
        return apiKeyLogin(c);
    }

    private int apiKeyLogin(ProviderConnector c) {
        OutputFormatter.printHeader("Authenticate " + c.getDisplayName());
        String environmentHint = ConnectorSupport.environmentHint(c);
        if (!environmentHint.isEmpty()) {
            OutputFormatter.printInfo("Tip: you can also export "
                    + environmentHint + " instead of saving a key here.");
        }
        if (!canReadSecretWithoutEcho()) {
            printNoEchoUnavailable(c);
            return 1;
        }
        String key = readSecret("Paste the API key for " + c.getId() + " (blank to cancel): ");
        if (key == null || key.trim().isEmpty()) {
            OutputFormatter.printInfo("Login cancelled - no key entered.");
            return 0;
        }

        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        ai.getProviderApiKeys().put(c.getId(), key.trim());
        // The confirmation is gated on the write actually succeeding. It used to discard
        // saveConfig()'s result and report success either way, so a key that never reached disk was
        // announced as saved -- and the next run would silently have no credential.
        if (!ConfigManager.getInstance().saveConfig()) {
            OutputFormatter.printError("Could not save the API key for " + c.getId()
                    + "; the configuration file was not written.");
            return 1;
        }
        AIManager.getInstance().resetActiveClient();
        OutputFormatter.printSuccess("Saved API key for " + c.getId()
                + " (" + ConnectorSupport.maskKey(key) + ").");

        return offerActivate(c);
    }

    /**
     * Whether a secret can be read without echoing it back to the user: on the shell's input line,
     * which draws one mark per character, or on a real {@link java.io.Console}.
     */
    private boolean canReadSecretWithoutEcho() {
        return OutputRouter.getInstance().canReadSecret();
    }

    /**
     * Reads a secret credential without echoing it. The key value is never printed or logged.
     *
     * @return the key, or {@code null} or empty when none was entered
     */
    private String readSecret(String prompt) {
        return OutputRouter.getInstance().getSecretInput(prompt);
    }

    /**
     * Explains why the key cannot be entered safely on the current (no-console) path and points
     * the user at secure alternatives. Never reads or prints a key value.
     */
    private void printNoEchoUnavailable(ProviderConnector c) {
        OutputFormatter.printWarning("Cannot read the API key here without showing it on screen, "
                + "so it will not be requested on this prompt for security.");
        String environmentHint = ConnectorSupport.environmentHint(c);
        if (!environmentHint.isEmpty()) {
            OutputFormatter.printInfo("Export " + environmentHint
                    + " before starting, then run 'login " + c.getId() + "' again.");
        }
        OutputFormatter.printInfo("Alternatively, run the 'login " + c.getId()
                + "' command in a terminal, where the key can be typed without being shown.");
    }

    /**
     * Renders a token expiry for a person to read.
     *
     * <p>The raw {@code expiresAt} is a Unix epoch second count. Printed as-is it told the user
     * nothing they could act on.</p>
     *
     * @param epochSeconds the expiry, in seconds since the epoch
     * @return a local date-time, or the raw value if it cannot be interpreted
     */
    private static String formatExpiry(long epochSeconds) {
        try {
            return java.time.LocalDateTime
                    .ofInstant(java.time.Instant.ofEpochSecond(epochSeconds),
                               java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (RuntimeException e) {
            return String.valueOf(epochSeconds);
        }
    }

    private int copilotLogin(ProviderConnector c) {
        OutputFormatter.printHeader("GitHub Copilot login");
        try {
            GitHubCopilotAuth.CopilotToken token = new GitHubCopilotAuth().login(System.out);
            OutputFormatter.printSuccess("GitHub Copilot connected (token valid until "
                    + formatExpiry(token.expiresAt) + ").");
            AIManager.getInstance().resetActiveClient();
            return offerActivate(c);
        } catch (Exception e) {
            OutputFormatter.printError("Copilot login failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Offers to make the connector the active provider and pick a model. Declining leaves
     * the saved credential in place without changing the active provider.
     */
    private int offerActivate(ProviderConnector c) {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        if (c.getId().equals(ai.getProvider())
            && ai.getModel() != null && !ai.getModel().isBlank()) {
            // Already the provider in use, with a model: there is nothing to switch to.
            OutputFormatter.printInfo(c.getId() + " is the active provider, with model "
                    + ai.getModel() + ". Run '" + CommandUsage.prefix()
                    + "models select' to pick another model.");
            return 0;
        }
        boolean activate = OutputRouter.getInstance()
                .getConfirmation("Use " + c.getId() + " as the active provider and pick a model now?");
        if (!activate) {
            OutputFormatter.printInfo("Credential saved. Run '" + CommandUsage.prefix() + "models use "
                    + c.getId() + " <model>' or '" + CommandUsage.prefix()
                    + "models select' when you want to switch.");
            return 0;
        }
        ServerAddress.Answer where = ConnectorSupport.promptForAddress(c);
        if (where.cancelled()) {
            OutputFormatter.printInfo("Provider not changed.");
            return 1;
        }
        String model = ConnectorSupport.promptForModel(c, where);
        ConnectorSupport.applyActiveModel(c.getId(), model, where);
        return 0;
    }

    // ---------------------------------------------------------------------
    // Status / logout
    // ---------------------------------------------------------------------

    private int status() {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        String active = ai.getProvider();
        OutputFormatter.printHeader("Provider login status");
        boolean anyActive = false;
        for (ProviderConnector c : ConnectorSupport.connectors()) {
            boolean isActive = c.getId().equals(active);
            anyActive |= isActive;
            UnifiedOutput.printf("  %s %-22s %-26s %s%n",
                    isActive ? "*" : " ", c.getId(), c.getDisplayName(),
                    ConnectorSupport.credentialStatus(c, ai));
        }
        String prefix = CommandUsage.prefix();
        // The legend is for a marker that is only on the list when a provider is active. Printed
        // unconditionally, it explained a '*' that appeared nowhere -- on a first run, which is
        // exactly when someone reads this, no provider is set.
        if (anyActive) {
            OutputFormatter.printInfo("'*' marks the active provider.");
        } else {
            OutputFormatter.printInfo("No provider is active yet.");
        }
        OutputFormatter.printInfo("Run '" + prefix + "login <provider>' to authenticate, or '"
                + prefix + "models use <provider> <model>' to switch models.");
        return 0;
    }

    private int logout(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            OutputFormatter.printError("Which provider should I log out of?");
            OutputFormatter.printInfo("Usage: " + CommandUsage.prefix()
                    + "login logout <provider>");
            return 1;
        }
        String id = providerId.trim().toLowerCase(Locale.ROOT);

        if (ConnectorSupport.COPILOT_ID.equals(id)) {
            return ConnectorSupport.logOutOfCopilot();
        }

        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        String removed = ai.getProviderApiKeys().remove(id);
        if (removed == null) {
            OutputFormatter.printInfo("No saved API key for " + id
                    + " (environment variables, if any, are left untouched).");
            return 0;
        }
        // The key is out of the map but not yet out of the file. If the write fails, put it back:
        // a process that believes it is logged out while config.json still holds the credential is
        // worse than the failure itself, because the user is told the key is gone and the next run
        // reads it straight back. This is the removal half of the save path above, which was
        // already corrected for the same reason.
        if (!ConfigManager.getInstance().saveConfig()) {
            ai.getProviderApiKeys().put(id, removed);
            OutputFormatter.printError("Could not remove the API key for " + id
                    + "; the configuration file was not written and the key is still saved.");
            return 1;
        }
        AIManager.getInstance().resetActiveClient();
        OutputFormatter.printSuccess("Removed saved API key for " + id + ".");
        return 0;
    }


    @Override
    public String getUsage() {
        return "login [<provider> | status | logout <provider>]";
    }
}
