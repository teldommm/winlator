package com.winlator.cmod.components;

/**
 * Result of an install started from a screen that only needs "what was installed, or why not":
 * exactly one of {@link #id} and {@link #error} is set.
 */
public final class InstallOutcome {
    /** Identifier of what was installed (runtime entry name, driver id, ...); null on failure. */
    public final String id;
    /** User-facing reason; null on success. */
    public final String error;

    private InstallOutcome(String id, String error) {
        this.id = id;
        this.error = error;
    }

    public static InstallOutcome ok(String id) {
        return new InstallOutcome(id, null);
    }

    public static InstallOutcome failed(String error) {
        return new InstallOutcome(null, error);
    }

    public boolean succeeded() {
        return id != null;
    }

    /** The specific reason when there is one, otherwise {@code fallback}. */
    public String failureText(String fallback) {
        return error != null && !error.trim().isEmpty() ? error : fallback;
    }
}
