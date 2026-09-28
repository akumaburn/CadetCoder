package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.OutputFormatter;

public class GitIntegrationManager {
    private static GitIntegrationManager instance;
    private        GitIntegration        gitIntegration;

    private GitIntegrationManager() {
        try {
            gitIntegration = new GitIntegration();
        } catch (Exception e) {
            OutputFormatter.printError("Git integration failed to initialize: " + e.getMessage());
            gitIntegration = null;
        }
    }

    public static synchronized GitIntegrationManager getInstance() {
        if (instance == null) {
            instance = new GitIntegrationManager();
        }
        return instance;
    }

    public GitIntegration getGitIntegration() {
        return gitIntegration;
    }

    public boolean isAvailable() {
        return gitIntegration != null;
    }
}
