package com.winlator.cmod.ui.inputcontrols;

// Every setter here applies immediately (mirrors how every control in this panel already
// behaved before the port: each change calls profile.save() + inputControlsView.invalidate()
// right away). The two exceptions in the old PopupWindow version -- custom text and icon
// selection, which only committed on popup dismiss -- are made live too, for consistency with
// everything else here; ControlsEditorActivity applies them the same way as any other field.
public interface ControlElementSettingsCallbacks {
    void onTypeChanged(int index);
    void onShapeChanged(int index);
    void onRangeChanged(int index);
    void onOrientationChanged(boolean vertical);
    void onColumnsChanged(int columns);
    void onScaleChanged(int percent);
    void onOpacityChanged(int percent);

    // Live, unsaved updates while a slider is being dragged (the panel no longer covers the
    // canvas, so the element can follow the thumb). The matching on...Changed call on release
    // commits and saves.
    void onScalePreview(int percent);
    void onOpacityPreview(int percent);
    void onColorSelected(int color);
    void onToggleSwitchChanged(boolean enabled);
    void onMouseMoveModeChanged(boolean enabled);
    void onSwipeableChanged(boolean enabled);
    void onDynamicStickChanged(boolean enabled);
    // Zone side in percent of the stick's diameter (150..500); preview = live, unsaved.
    void onZoneScalePreview(int percent);
    void onZoneScaleChanged(int percent);
    // 0..100; committed on slider release only (nothing to preview in the editor).
    void onFollowSpeedChanged(int percent);
    void onTextChanged(String text);

    // iconKey is either "builtin:<id>" or "path:<absolutePath>", as produced by
    // ControlElementSettingsModel's icon list -- see ControlElementSettingsComposeHost.kt.
    void onIconSelected(String iconKey);
    void onIconLongPress(String iconKey);
    void onRemoveIcon();
    void onBrowseIcon();

    void onBindingSourceTypeChanged(int bindingIndex, int sourceTypeIndex);
    void onBindingValueChanged(int bindingIndex, int optionIndex);

    void onDone();
}
