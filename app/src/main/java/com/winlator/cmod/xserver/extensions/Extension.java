package com.winlator.cmod.xserver.extensions;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

public interface Extension {
    String getName();

    byte getMajorOpcode();

    byte getFirstErrorId();

    byte getFirstEventId();

    void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError;

    /**
     * Called once when a client disconnects, after its resources (windows, pixmaps, ...) have been
     * freed. Extensions that keep per-client state drop it here. Called with all XServer locks
     * held, so implementations must not block.
     */
    default void onClientDisconnected(XClient client) {}
}
