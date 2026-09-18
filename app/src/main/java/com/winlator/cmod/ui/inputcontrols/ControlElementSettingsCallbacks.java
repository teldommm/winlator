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
    void onColorSelected(int color);
    void onToggleSwitchChanged(boolean enabled);
    void onMouseMoveModeChanged(boolean enabled);
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
