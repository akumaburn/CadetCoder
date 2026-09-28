package com.eonmux.cadetcoder.ui;

/**
 * Interface for handling user input in different contexts
 */
public interface InputHandler {
    /**
     * Get user input with a prompt
     */
    String getUserInput(String prompt);

    /**
     * Get confirmation from user
     */
    boolean getConfirmation(String message);

    /**
     * Show a message to the user
     */
    void showMessage(String message);
}