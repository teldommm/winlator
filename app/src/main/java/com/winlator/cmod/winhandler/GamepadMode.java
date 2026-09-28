package com.winlator.cmod.winhandler;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;

/**
 * Which Windows gamepad APIs see the controllers, resolved once per launch from the container
 * (and the shortcut's overrides) — the three input toggles: Exclusive Input, Enable XInput,
 * Enable DInput.
 *
 * How each mode reaches Wine (Software\Wine\DirectInput\Joysticks, per gamepad name):
 *   BOTH   - no value: XInput and DInput both see the pad (Wine default).
 *   XINPUT - "disabled": DInput hides it. (Wine 11 master, commit d8b9b7c, also hides
 *            "disabled" pads from XInput — builds with that commit lose XInput in this mode.)
 *   DINPUT - "override": XInput skips it, DInput opens the full XInput-style interface.
 *   NONE   - no gamepad at all: the app never creates the fake evdev nodes, so no Wine version
 *            enumerates anything ("disabled" is written as well).
 */
public final class GamepadMode {
    public static final int BOTH = 0;
    public static final int XINPUT = 1;
    public static final int DINPUT = 2;
    public static final int NONE = 3;

    private GamepadMode() {}

    public static int fromSettings(boolean exclusive, int inputType) {
        if (!exclusive) return BOTH;
        boolean xinput = (inputType & WinHandler.FLAG_INPUT_TYPE_XINPUT) != 0;
        boolean dinput = (inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) != 0;
        if (xinput && dinput) return BOTH;
        if (xinput) return XINPUT;
        if (dinput) return DINPUT;
        return NONE;
    }

    /** Container values, overridden by the shortcut's own "inputType"/"exclusiveXInput" if set. */
    public static int resolve(Container container, Shortcut shortcut) {
        int inputType = container.getInputType();
        boolean exclusive = container.isExclusiveXInput();
        if (shortcut != null) {
            String type = shortcut.getExtra("inputType");
            if (!type.isEmpty()) {
                try {
                    inputType = Integer.parseInt(type);
                } catch (NumberFormatException ignored) {
                }
            }
            String extra = shortcut.getExtra("exclusiveXInput");
            if (!extra.isEmpty()) exclusive = extra.equals("1");
        }
        return fromSettings(exclusive, inputType);
    }

    /** Value for Software\Wine\DirectInput\Joysticks, or null to remove it. */
    public static String registryValue(int mode) {
        switch (mode) {
            case XINPUT:
            case NONE:
                return "disabled";
            case DINPUT:
                return "override";
            default:
                return null;
        }
    }
}
