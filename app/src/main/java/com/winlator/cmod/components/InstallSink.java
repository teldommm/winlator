package com.winlator.cmod.components;

/** Receives progress while a component installs. Called from the worker thread. */
public interface InstallSink {
    /** {@code percent} is 0..100, or -1 when the amount of work is unknown. */
    void progress(String label, int percent);
}
