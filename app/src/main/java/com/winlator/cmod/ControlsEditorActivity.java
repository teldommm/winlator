package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.ui.ThemedAlertHost;
import com.winlator.cmod.ui.inputcontrols.ControlElementBindingRow;
import com.winlator.cmod.ui.inputcontrols.ControlElementIcon;
import com.winlator.cmod.ui.inputcontrols.ControlElementIconTint;
import com.winlator.cmod.ui.inputcontrols.ControlElementSettingsCallbacks;
import com.winlator.cmod.ui.inputcontrols.ControlElementSettingsComposeHost;
import com.winlator.cmod.ui.inputcontrols.ControlElementSettingsModel;
import com.winlator.cmod.ui.inputcontrols.ControlsEditorToolbarComposeHost;
import com.winlator.cmod.ui.inputcontrols.SchemeColorComposeDialog;

import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.widget.InputControlsView;

public class ControlsEditorActivity extends AppCompatActivity {
    private InputControlsView inputControlsView;
    private ControlsProfile profile;
    private ControlElement pendingIconElement = null;
    private int pendingBuiltinOverrideId = -1;
    private static final int PICK_ICON_REQUEST = 7001;
    private static final String BUILTIN_ICON_PREFIX = "builtin:";
    private static final String CUSTOM_ICON_PREFIX = "path:";

    private ComposeView elementSettingsView;

    private static File getCustomIconsDir() {
        return new File(Environment.getExternalStorageDirectory(), "winlator/custom_icons");
    }

    static File getBuiltinOverridePath(int iconId) {
        return new File(getCustomIconsDir(), "override_" + iconId + ".png");
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        setContentView(R.layout.controls_editor_activity);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setEditMode(true);
        inputControlsView.setOverlayOpacity(1.0f);

        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0)));
        if (profile == null) {
            Log.e("ControlsEditor", "Profile not found for id=" + getIntent().getIntExtra("profile_id", 0));
            Toast.makeText(this, "No profile selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        inputControlsView.setProfile(profile);

        FrameLayout container = findViewById(R.id.FLContainer);
        container.addView(inputControlsView, 0);

        container.addView(ControlsEditorToolbarComposeHost.create(
                this,
                profile.getName(),
                () -> {
                    if (!inputControlsView.addElement()) {
                        Toast.makeText(this, "No profile selected", Toast.LENGTH_SHORT).show();
                    }
                },
                () -> {
                    if (!inputControlsView.removeElement()) {
                        Toast.makeText(this, "No control element selected", Toast.LENGTH_SHORT).show();
                    }
                },
                () -> {
                    ControlElement selectedElement = inputControlsView.getSelectedElement();
                    if (selectedElement != null) {
                        showControlElementSettings();
                    } else {
                        Toast.makeText(this, "No control element selected", Toast.LENGTH_SHORT).show();
                    }
                },
                this::showSchemeColorPicker
        ));
    }

    @Override
    protected void onResume() {
        super.onResume();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            if (!prefs.getBoolean("mix_warning_shown_v4", false)) {
                prefs.edit().putBoolean("mix_warning_shown_v4", true).apply();
                ThemedAlertHost.info(
                        this,
                        "Gamepad + Mouse",
                        "Mixing gamepad and mouse bindings on the same controller may cause unexpected behavior in games. It is recommended to use only gamepad bindings or only mouse/keyboard bindings."
                );
            }
        }, 500);
    }

    private void showSchemeColorPicker() {
        ArrayList<Integer> colors = new ArrayList<>();
        for (int color : PALETTE_COLORS) colors.add(color);
        SchemeColorComposeDialog.show(this, colors, profile.getThemeColor(), color -> {
            profile.setThemeColor(color);
            profile.save();
            inputControlsView.invalidate();
        });
    }

    private void showControlElementSettings() {
        final ControlElement element = inputControlsView.getSelectedElement();
        if (element == null) return;

        ControlElementSettingsCallbacks callbacks = new ControlElementSettingsCallbacks() {
            @Override
            public void onTypeChanged(int index) {
                element.setType(ControlElement.Type.values()[index]);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onShapeChanged(int index) {
                element.setShape(ControlElement.Shape.values()[index]);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onRangeChanged(int index) {
                element.setRange(ControlElement.Range.values()[index]);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onOrientationChanged(boolean vertical) {
                element.setOrientation((byte) (vertical ? 1 : 0));
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onColumnsChanged(int columns) {
                element.setBindingCount(columns);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onScaleChanged(int percent) {
                element.setScale(percent / 100f);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onOpacityChanged(int percent) {
                element.setOpacity(percent / 100f);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onColorSelected(int color) {
                element.setCustomColor(color);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onToggleSwitchChanged(boolean enabled) {
                element.setToggleSwitch(enabled);
                profile.save();
                refreshElementSettings(element);
            }

            @Override
            public void onMouseMoveModeChanged(boolean enabled) {
                element.setMouseMoveMode(enabled);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onTextChanged(String text) {
                element.setText(text);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onIconSelected(String iconKey) {
                applyIconSelection(element, iconKey);
                refreshElementSettings(element);
            }

            @Override
            public void onIconLongPress(String iconKey) {
                if (iconKey.startsWith(BUILTIN_ICON_PREFIX)) {
                    pendingIconElement = element;
                    pendingBuiltinOverrideId = Byte.parseByte(iconKey.substring(BUILTIN_ICON_PREFIX.length()));
                    launchIconPicker();
                }
            }

            @Override
            public void onRemoveIcon() {
                element.setCustomIconPath(null);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onBrowseIcon() {
                pendingIconElement = element;
                pendingBuiltinOverrideId = -1;
                launchIconPicker();
            }

            @Override
            public void onBindingSourceTypeChanged(int bindingIndex, int sourceTypeIndex) {
                Binding[] values = bindingValuesForSourceType(sourceTypeIndex);
                element.setBindingAt(bindingIndex, values.length > 0 ? values[0] : Binding.NONE);
                profile.save();
                inputControlsView.invalidate();
                refreshElementSettings(element);
            }

            @Override
            public void onBindingValueChanged(int bindingIndex, int optionIndex) {
                int sourceTypeIndex = sourceTypeIndexForBinding(element, bindingIndex);
                Binding[] values = bindingValuesForSourceType(sourceTypeIndex);
                Binding binding = (optionIndex >= 0 && optionIndex < values.length) ? values[optionIndex] : Binding.NONE;
                if (binding != element.getBindingAt(bindingIndex)) {
                    element.setBindingAt(bindingIndex, binding);
                    profile.save();
                    inputControlsView.invalidate();
                }
                refreshElementSettings(element);
            }

            @Override
            public void onDone() {
                elementSettingsView = null;
            }
        };

        elementSettingsView = ControlElementSettingsComposeHost.create(this, buildElementSettingsModel(element), callbacks);
        ControlElementSettingsComposeHost.show(this, elementSettingsView);
    }

    private void refreshElementSettings(ControlElement element) {
        if (elementSettingsView != null) {
            ControlElementSettingsComposeHost.update(elementSettingsView, buildElementSettingsModel(element));
        }
    }

    private void launchIconPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_ICON_REQUEST);
    }

    private ControlElementSettingsModel buildElementSettingsModel(ControlElement element) {
        ControlElement.Type type = element.getType();
        boolean showShape = type == ControlElement.Type.BUTTON;
        boolean showRange = type == ControlElement.Type.RANGE_BUTTON;
        boolean showToggleSwitch = type == ControlElement.Type.BUTTON;
        boolean showTextAndIcon = type == ControlElement.Type.BUTTON;

        ArrayList<Integer> colorList = new ArrayList<>();
        for (int color : PALETTE_COLORS) colorList.add(color);

        String text = element.getText();

        return new ControlElementSettingsModel(
                type.ordinal(),
                Arrays.asList(ControlElement.Type.names()),
                showShape,
                element.getShape().ordinal(),
                Arrays.asList(ControlElement.Shape.names()),
                showRange,
                element.getRange().ordinal(),
                Arrays.asList(ControlElement.Range.names()),
                element.getOrientation() == 1,
                element.getBindingCount(),
                3,
                8,
                Math.round(element.getScale() * 100),
                Math.round(element.getOpacity() * 100),
                colorList,
                element.getCustomColor(),
                showToggleSwitch,
                element.isToggleSwitch(),
                element.isMouseMoveMode(),
                showTextAndIcon,
                text != null ? text : "",
                buildIconList(element),
                element.getCustomIconPath() != null,
                buildBindingRows(element)
        );
    }

    private List<ControlElementBindingRow> buildBindingRows(ControlElement element) {
        ArrayList<ControlElementBindingRow> rows = new ArrayList<>();
        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) {
            rows.add(buildBindingRow(element, 0, "Binding"));
            rows.add(buildBindingRow(element, 1, "Secondary Binding"));
        }
        else if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD) {
            rows.add(buildBindingRow(element, 0, "Up"));
            rows.add(buildBindingRow(element, 1, "Right"));
            rows.add(buildBindingRow(element, 2, "Down"));
            rows.add(buildBindingRow(element, 3, "Left"));
        }
        return rows;
    }

    private ControlElementBindingRow buildBindingRow(ControlElement element, int index, String label) {
        int sourceTypeIndex = sourceTypeIndexForBinding(element, index);
        String[] labels = bindingLabelsForSourceType(sourceTypeIndex);
        Binding[] values = bindingValuesForSourceType(sourceTypeIndex);
        int selectedIndex = Arrays.asList(values).indexOf(element.getBindingAt(index));
        if (selectedIndex < 0) selectedIndex = 0;
        return new ControlElementBindingRow(label, sourceTypeIndex, Arrays.asList(labels), selectedIndex);
    }

    private int sourceTypeIndexForBinding(ControlElement element, int bindingIndex) {
        Binding binding = element.getBindingAt(bindingIndex);
        if (binding.isKeyboard()) return 0;
        if (binding.isMouse()) return 1;
        return 2;
    }

    private Binding[] bindingValuesForSourceType(int sourceTypeIndex) {
        switch (sourceTypeIndex) {
            case 0: return Binding.keyboardBindingValues();
            case 1: return Binding.mouseBindingValues();
            default: return Binding.gamepadBindingValues();
        }
    }

    private String[] bindingLabelsForSourceType(int sourceTypeIndex) {
        switch (sourceTypeIndex) {
            case 0: return Binding.keyboardBindingLabels();
            case 1: return Binding.mouseBindingLabels();
            default: return Binding.gamepadBindingLabels();
        }
    }

    private void applyIconSelection(ControlElement element, String iconKey) {
        if (iconKey.startsWith(CUSTOM_ICON_PREFIX)) {
            element.setCustomIconPath(iconKey.substring(CUSTOM_ICON_PREFIX.length()));
            element.setIconId(0);
        }
        else if (iconKey.startsWith(BUILTIN_ICON_PREFIX)) {
            element.setCustomIconPath(null);
            element.setIconId(Byte.parseByte(iconKey.substring(BUILTIN_ICON_PREFIX.length())));
        }
        profile.save();
        inputControlsView.invalidate();
    }

    private List<ControlElementIcon> buildIconList(ControlElement element) {
        ArrayList<ControlElementIcon> icons = new ArrayList<>();
        String currentPath = element.getCustomIconPath();
        byte selectedId = element.getIconId();

        File iconsDir = getCustomIconsDir();
        if (iconsDir.exists()) {
            File[] files = iconsDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".png") && !name.startsWith("override_"));
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                for (File file : files) {
                    Bitmap bmp = loadAndScaleBitmap(file.getAbsolutePath());
                    if (bmp == null) continue;
                    String filePath = file.getAbsolutePath();
                    boolean selected = filePath.equals(currentPath);
                    ControlElementIconTint tint = isDarkBitmap(bmp) ? ControlElementIconTint.INVERT : ControlElementIconTint.NONE;
                    icons.add(new ControlElementIcon(CUSTOM_ICON_PREFIX + filePath, bmp, selected, tint, false, false));
                }
            }
        }

        byte[] iconIds = new byte[0];
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            iconIds = new byte[filenames.length];
            for (int i = 0; i < filenames.length; i++) {
                iconIds[i] = Byte.parseByte(FileUtils.getBasename(filenames[i]));
            }
        } catch (IOException e) {}

        Arrays.sort(iconIds);

        for (final byte id : iconIds) {
            File overrideFile = getBuiltinOverridePath(id);
            Bitmap bmp = null;
            if (overrideFile.exists()) {
                bmp = loadAndScaleBitmap(overrideFile.getAbsolutePath());
                if (bmp == null) Log.w("Icons", "Override exists but failed to decode: " + overrideFile.getName());
            }
            if (bmp == null) {
                try (InputStream is = getAssets().open("inputcontrols/icons/" + id + ".png")) {
                    bmp = BitmapFactory.decodeStream(is);
                    if (bmp == null) {
                        Log.w("Icons", "Built-in icon " + id + " failed to decode (empty or invalid PNG)");
                        continue;
                    }
                } catch (IOException e) {
                    Log.w("Icons", "Built-in icon " + id + " not found in assets: " + e.getMessage());
                    continue;
                }
            }

            boolean hasOverride = overrideFile.exists();
            boolean selected = id == selectedId && currentPath == null;
            icons.add(new ControlElementIcon(BUILTIN_ICON_PREFIX + id, bmp, selected, ControlElementIconTint.BLUE, hasOverride, true));
        }

        return icons;
    }

    private static final int[] PALETTE_COLORS = {
        0, 0xffffffff, 0xffdddddd, 0xffaaaaaa, 0xff777777, 0xff444444, 0xff222222, 0xff000000,
        0xffff3b30, 0xffff6961, 0xffff453a, 0xffcc0000, 0xff800000, 0xff4d0000, 0xffff8080, 0xffffb3b3,
        0xffff9500, 0xffff6000, 0xfffe9f0d, 0xffffcc00, 0xffffd60a, 0xffffea00, 0xffffb347, 0xffffdca5,
        0xff34c759, 0xff30d158, 0xff4cd964, 0xff00b050, 0xff2e8b57, 0xff006400, 0xffa8e6cf, 0xffd4edda,
        0xff5ac8fa, 0xff32ade6, 0xff00b4d8, 0xff0096c7, 0xff00758a, 0xff004c5a, 0xffb2ebf2, 0xffe0f7fa,
        0xff007aff, 0xff0a84ff, 0xff2184ff, 0xff1a56db, 0xff003f8f, 0xff001a66, 0xffbed6f8, 0xffdce9ff,
        0xffaf52de, 0xffbf5af2, 0xff9b59b6, 0xff6e3fa3, 0xff4a0e8f, 0xff2d0066, 0xffd7b4f3, 0xffede0f8,
        0xffff2d92, 0xffff375f, 0xffff6ab0, 0xffe91e8c, 0xffad1457, 0xff6a0032, 0xffffb3d9, 0xffffdcef,
    };

    private Bitmap loadAndScaleBitmap(String path) {
        final int MAX_DIM = 256;
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.w("Icons", "Cannot decode dimensions of " + path + " — file may be empty, corrupt, or not a supported image format");
                return null;
            }
            if (opts.outWidth <= MAX_DIM && opts.outHeight <= MAX_DIM) {
                bmp = BitmapFactory.decodeFile(path);
            } else {
                int sampleSize = 1;
                int w = opts.outWidth, h = opts.outHeight;
                while (w / 2 >= MAX_DIM || h / 2 >= MAX_DIM) { sampleSize *= 2; w /= 2; h /= 2; }
                opts = new BitmapFactory.Options();
                opts.inSampleSize = sampleSize;
                Bitmap sampled = BitmapFactory.decodeFile(path, opts);
                if (sampled == null) {
                    Log.w("Icons", "Failed to decode " + path + " even with sample size " + sampleSize);
                    return null;
                }
                float scale = Math.min((float) MAX_DIM / sampled.getWidth(), (float) MAX_DIM / sampled.getHeight());
                bmp = Bitmap.createScaledBitmap(sampled, (int)(sampled.getWidth() * scale), (int)(sampled.getHeight() * scale), true);
                if (!sampled.isRecycled()) sampled.recycle();
                Log.i("Icons", "Auto-scaled " + new File(path).getName() +
                    " from " + opts.outWidth + "x" + opts.outHeight + " to " + bmp.getWidth() + "x" + bmp.getHeight());
            }
        } catch (Exception e) {
            Log.e("Icons", "Exception loading " + path + ": " + e.getMessage());
        }
        if (bmp == null) Log.w("Icons", "Final decode returned null for " + path);
        return bmp;
    }

    private boolean isDarkBitmap(Bitmap bmp) {
        int step = Math.max(1, Math.min(bmp.getWidth(), bmp.getHeight()) / 16);
        long totalLuminance = 0;
        int count = 0;
        for (int y = 0; y < bmp.getHeight(); y += step) {
            for (int x = 0; x < bmp.getWidth(); x += step) {
                int pixel = bmp.getPixel(x, y);
                int alpha = (pixel >> 24) & 0xff;
                if (alpha > 32) {
                    int r = (pixel >> 16) & 0xff;
                    int g = (pixel >> 8) & 0xff;
                    int b = pixel & 0xff;
                    totalLuminance += (r * 299L + g * 587L + b * 114L) / 1000L;
                    count++;
                }
            }
        }
        return count > 0 && (totalLuminance / count) < 50;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_ICON_REQUEST && resultCode == Activity.RESULT_OK
                && data != null) {
            Uri uri = data.getData();
            try {
                File iconsDir = getCustomIconsDir();
                if (!iconsDir.exists()) iconsDir.mkdirs();

                File dest;
                if (pendingBuiltinOverrideId >= 0) {
                    dest = getBuiltinOverridePath(pendingBuiltinOverrideId);
                    pendingBuiltinOverrideId = -1;
                } else {
                    dest = new File(iconsDir, "icon_" + System.currentTimeMillis() + ".png");
                }

                try (InputStream in = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }

                Bitmap loaded = loadAndScaleBitmap(dest.getAbsolutePath());
                if (loaded == null) {
                    dest.delete();
                    Log.w("Icons", "Picked file is not a valid image or could not be decoded: " + uri);
                    Toast.makeText(this, "Unable to set icon", Toast.LENGTH_SHORT).show();
                    pendingIconElement = null;
                    return;
                }
                if (loaded.getWidth() != -1) {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                        loaded.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                }
                loaded.recycle();

                inputControlsView.invalidateIconCache();

                if (pendingIconElement != null) {
                    pendingIconElement.setCustomIconPath(dest.getAbsolutePath());
                    pendingIconElement.setIconId(0);
                    profile.save();
                    inputControlsView.invalidate();
                    refreshElementSettings(pendingIconElement);
                    pendingIconElement = null;
                }
            } catch (Exception e) {
                Log.e("Icons", "Error saving picked icon: " + e.getMessage());
                pendingIconElement = null;
                pendingBuiltinOverrideId = -1;
                Toast.makeText(this, "Unable to set icon", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Every way out (system back, the editor's own close/save) ends in finish(), so the return
    // motion is applied here: the reverse of the shared-axis enter used to open this screen.
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.shared_axis_pop_enter, R.anim.shared_axis_pop_exit);
    }
}
