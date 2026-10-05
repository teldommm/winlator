package com.winlator.cmod;

import android.app.Application;

import com.winlator.cmod.ui.toast.ForegroundActivityTracker;

public class WinlatorApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ForegroundActivityTracker.install(this);
    }
}
