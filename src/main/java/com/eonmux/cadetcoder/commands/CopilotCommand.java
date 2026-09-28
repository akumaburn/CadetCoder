package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.auth.GitHubCopilotAuth;

import java.util.Locale;

/**
 * Connects and disconnects GitHub Copilot through the OAuth device flow, so the
 * {@code github-copilot} connector can obtain a usable token.
 *
 * <pre>
 *   copilot login    run the device-authorization flow and store the token
 *   copilot status   report whether a valid Copilot token can be obtained
 *   copilot logout   remove the stored token from this machine
 * </pre>
 *
 * <h2>Why logout is here and not only under {@code login}</h2>
 *
 * <p>{@code login logout github-copilot} could already do this, and nobody who had just typed
 * {@code copilot login} would look for it there: the command that connected an account is the one
 * its owner comes back to for disconnecting it, and an unknown-action error is a poor answer to a
 * request to revoke a credential. Both spellings run the same
 * {@link ConnectorSupport#logOutOfCopilot()}, so neither can drift into removing less than the
 * other.</p>
 */
@picocli.CommandLine.Command (name = "copilot", description = "Connect or disconnect GitHub Copilot (OAuth device flow)")
public class CopilotCommand implements CommandRegistry.Command {

    @Override
    public int execute(String[] args) {
        String action = args != null && args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "login";
        if (!action.equals("status")) {
            Integer refused = ModelDispatch.refuseSetupChange("copilot " + action);
            if (refused != null) {
                return refused;
            }
        }
        switch (action) {
            case "login":
                return login();
            case "status":
                return status();
            case "logout":
            case "signout":
                return ConnectorSupport.logOutOfCopilot();
            default:
                OutputFormatter.printError("Unknown action: " + action);
                OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                return 1;
        }
    }

    private int login() {
        try {
            GitHubCopilotAuth.CopilotToken token = new GitHubCopilotAuth().login(System.out);
            OutputFormatter.printSuccess("GitHub Copilot connected. Token valid until epoch "
                                         + token.expiresAt + ".");
            OutputFormatter.printInfo("Use it with: --provider github-copilot --model <model>");
            return 0;
        } catch (Exception failure) {
            String reason = failure.getMessage() != null ? failure.getMessage()
                                                         : failure.getClass().getSimpleName();
            OutputFormatter.printError("Copilot login failed: " + reason);
            return 1;
        }
    }

    /**
     * Whether this machine is connected.
     *
     * <p>Gated on the locally stored OAuth token rather than on a refresh, so an offline or
     * transiently failing exchange does not report "not authenticated" for a user who is. Mirrors
     * {@code login status}.</p>
     */
    private int status() {
        if (new GitHubCopilotAuth().hasStoredOAuthToken()) {
            OutputFormatter.printSuccess("GitHub Copilot is authenticated.");
            return 0;
        }
        OutputFormatter.printWarning("GitHub Copilot is not authenticated. Run '"
                                     + CommandUsage.prefix() + "copilot login'.");
        return 1;
    }

    @Override
    public String getUsage() {
        return "copilot [login | status | logout]\n"
               + "  login   connect this machine through the OAuth device flow\n"
               + "  status  whether a token is stored here\n"
               + "  logout  remove it again; the same act as `login logout github-copilot`";
    }
}
