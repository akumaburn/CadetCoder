package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.context.ContextEngine;

/**
 * A testable version of AgentCommand that allows dependency injection.
 */
public class TestableAgentCommand extends AgentCommand {
    private AIManager     mockAIManager;
    private ConfigManager mockConfigManager;
    private ContextEngine mockContextEngine;

    public void setMockAIManager(AIManager aiManager) {
        this.mockAIManager = aiManager;
    }

    public void setMockConfigManager(ConfigManager configManager) {
        this.mockConfigManager = configManager;
    }

    public void setMockContextEngine(ContextEngine contextEngine) {
        this.mockContextEngine = contextEngine;
    }

    @Override
    protected AIManager getAIManager() {
        return mockAIManager != null ? mockAIManager : AIManager.getInstance();
    }

    @Override
    protected ConfigManager getConfigManager() {
        return mockConfigManager != null ? mockConfigManager : ConfigManager.getInstance();
    }

    @Override
    protected ContextEngine getContextEngine() {
        if (mockContextEngine != null) {
            return mockContextEngine;
        }
        try {
            return ContextEngine.getInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get ContextEngine", e);
        }
    }
}