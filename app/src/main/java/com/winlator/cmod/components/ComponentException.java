package com.winlator.cmod.components;

/** A component operation failed; the message is written for the user (it ends up in a toast). */
public class ComponentException extends Exception {
    public ComponentException(String userMessage) {
        super(userMessage);
    }

    public ComponentException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
