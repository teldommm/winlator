package com.winlator.cmod.widget;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

// Haptic feedback for the on-screen controls (the sidebar's touchscreen "Vibration" switch).
//
// Replaces the old 50 ms one-shot at default amplitude, which on the linear motors of current
// phones feels like a buzz rather than a click. Two weights:
//   PRESS — a button press, a D-pad direction from rest, a swipe onto another button
//   TICK  — the D-pad rolling to another direction without lifting (lighter than a press)
// Sticks (fixed and dynamic) and trackpads don't vibrate at all: continuous input.
//
// Each weight is built once, best available first:
//   Android 11+ composition primitives (crispest, if the motor supports them)
//   → Android 10+ predefined effects (tuned by the OEM for its motor)
//   → a very short one-shot, with an explicit amplitude where the motor allows it.
//
// The Vibrator is called directly on purpose (not View.performHapticFeedback): the game has its
// own on/off switch, and routing through the system "touch feedback" setting would silently
// mute it for anyone who turned that off.
public final class TouchHaptics {
    public static final int NONE = -1;
    public static final int PRESS = 0;
    public static final int TICK = 1;

    // Ticks closer together than this are dropped, so a finger wobbling on a D-pad boundary
    // can't turn into a continuous rattle.
    private static final long MIN_TICK_INTERVAL_MS = 35;

    private final Vibrator vibrator;
    private final VibrationEffect pressEffect;
    private final VibrationEffect tickEffect;
    private long lastTickAt;

    public TouchHaptics(Context context) {
        Vibrator v = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) v = manager.getDefaultVibrator();
        }
        if (v == null) v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        vibrator = (v != null && v.hasVibrator()) ? v : null;

        if (vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pressEffect = build(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f, 1 /* EFFECT_CLICK */, 16, 190);
            tickEffect = build(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 2 /* EFFECT_TICK */, 8, 120);
        } else {
            pressEffect = tickEffect = null;
        }
    }

    public boolean isAvailable() {
        return vibrator != null;
    }

    public void play(int kind) {
        if (vibrator == null || kind == NONE) return;

        if (kind == TICK) {
            long now = SystemClock.uptimeMillis();
            if (now - lastTickAt < MIN_TICK_INTERVAL_MS) return;
            lastTickAt = now;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect effect = kind == TICK ? tickEffect : pressEffect;
            if (effect != null) vibrator.vibrate(effect);
        } else {
            vibrator.vibrate(kind == TICK ? 8 : 16);
        }
    }

    private VibrationEffect build(int primitive, float scale, int predefined, int fallbackMs, int fallbackAmplitude) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(primitive)) {
            return VibrationEffect.startComposition().addPrimitive(primitive, scale).compose();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // predefined: 1 = EFFECT_CLICK, 2 = EFFECT_TICK
            return VibrationEffect.createPredefined(predefined == 1 ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK);
        }
        int amplitude = vibrator.hasAmplitudeControl() ? fallbackAmplitude : VibrationEffect.DEFAULT_AMPLITUDE;
        return VibrationEffect.createOneShot(fallbackMs, amplitude);
    }
}
