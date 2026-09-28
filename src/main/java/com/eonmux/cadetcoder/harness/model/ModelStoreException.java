package com.eonmux.cadetcoder.harness.model;

/**
 * The store could not do what was asked of it.
 *
 * <p>Deliberately not a {@link ModelException}: nothing here says anything about whether a model is
 * any good. A name that matches no version, an index that will not read back, a source that no
 * longer hashes to the name it is filed under -- these are facts about the store, and an agent that
 * confused one of them for "my theory is wrong" would revise a model that was never at fault.</p>
 */
public class ModelStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModelStoreException(String message) {
        super(message);
    }

    public ModelStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
