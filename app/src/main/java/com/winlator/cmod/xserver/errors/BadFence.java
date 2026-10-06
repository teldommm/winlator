package com.winlator.cmod.xserver.errors;

import com.winlator.cmod.xserver.extensions.SyncExtension;

public class BadFence extends XRequestError {
    public BadFence(int id) {
        super(SyncExtension.FIRST_ERROR_ID + 2, id);
    }
}
