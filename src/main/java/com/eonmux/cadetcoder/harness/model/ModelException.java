package com.eonmux.cadetcoder.harness.model;

/**
 * A model failed to do what a model must do.
 *
 * <p>Every one of these is a fact about the theory, never about the world. Certification records it
 * as a model error and never as evidence that reality misbehaved, which is the difference between
 * "the agent's beliefs are wrong" and "the environment is unreliable" -- two conclusions that lead
 * somewhere very different.</p>
 */
public class ModelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
