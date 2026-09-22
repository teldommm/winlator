package com.winlator.cmod;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.box64.Box64EditPresetDialog;
import com.winlator.cmod.box64.Box64Preset;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.LosslessDll;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.fexcore.FEXCoreEditPresetDialog;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.xenvironment.ImageFsInstaller;
import com.winlator.cmod.ui.ThemedAlertHost;
import com.winlator.cmod.ui.settings.SettingChoice;
import com.winlator.cmod.ui.settings.SettingsCallbacks;
import com.winlator.cmod.ui.settings.SettingsComposeHost;
import com.winlator.cmod.ui.settings.SettingsModel;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

public class SettingsFragment extends Fragment {
    public static final String DEFAULT_WINE_DEBUG_CHANNELS = "warn,err,fixme";
    public static final String DEFAULT_WINLATOR_PATH = Environment.getExternalStorageDirectory().getPath() + "/Winlator";
    public static final String DEFAULT_SHORTCUT_EXPORT_PATH = DEFAULT_WINLATOR_PATH + "/Shortcuts";
    private Callback<Uri> installSoundFontCallback;
    private PreloaderDialog preloaderDialog;
    private SharedPreferences preferences;
    private ComposeView composeView;

    private static final int REQUEST_CODE_WINLATOR_PATH = 1002;
    private static final int REQUEST_CODE_SHORTCUT_EXPORT_PATH = 1003;
    private static final int REQUEST_CODE_INSTALL_SOUNDFONT = 1001;
    private static final int REQUEST_CODE_IMPORT_BOX64_PRESET = 1004;
    private static final int REQUEST_CODE_IMPORT_FEXCORE_PRESET = 1005;
    private static final int REQUEST_CODE_IMPORT_LOSSLESS_DLL = 1006;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.settings);
    }



    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).setDetailMode(false);
        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(R.string.settings);
        refreshCompose();
    }

    @Override
    public void onPause() {
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).setDetailMode(false);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        composeView = null;
        super.onDestroyView();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final Context context = requireContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        composeView = SettingsComposeHost.create(context, buildComposeModel(), createComposeCallbacks());
        return composeView;
    }

    private SettingsModel buildComposeModel() {
        Context context = requireContext();
        ArrayList<SettingChoice> box64Choices = new ArrayList<>();
        for (Box64Preset preset : Box64PresetManager.getPresets("box64", context)) {
            box64Choices.add(new SettingChoice(preset.id, preset.name));
        }
        ArrayList<SettingChoice> fexChoices = new ArrayList<>();
        for (FEXCorePreset preset : FEXCorePresetManager.getPresets(context)) {
            fexChoices.add(new SettingChoice(preset.id, preset.name));
        }

        ArrayList<SettingChoice> soundFontChoices = new ArrayList<>();
        soundFontChoices.add(new SettingChoice(MidiManager.DEFAULT_SF2_FILE, MidiManager.DEFAULT_SF2_FILE));
        File[] soundFontFiles = MidiManager.getSoundFontDir(context).listFiles();
        if (soundFontFiles != null) {
            Arrays.sort(soundFontFiles, (left, right) ->
                    left.getName().compareToIgnoreCase(right.getName()));
            for (File file : soundFontFiles) {
                if (file.isFile()) soundFontChoices.add(new SettingChoice(file.getName(), file.getName()));
            }
        }

        String winlatorPath = resolveStoredPath("winlator_path_uri", DEFAULT_WINLATOR_PATH);
        String shortcutPath = resolveStoredPath("shortcuts_export_path_uri", DEFAULT_SHORTCUT_EXPORT_PATH);

        ArrayList<String> wineDebugOptions = new ArrayList<>();
        try {
            JSONArray channels = new JSONArray(FileUtils.readString(requireContext(), "wine_debug_channels.json"));
            for (int i = 0; i < channels.length(); i++) {
                String channel = channels.optString(i, "").trim();
                if (!channel.isEmpty()) wineDebugOptions.add(channel);
            }
        } catch (JSONException ignored) {}
        if (wineDebugOptions.isEmpty()) {
            wineDebugOptions.addAll(Arrays.asList(DEFAULT_WINE_DEBUG_CHANNELS.split(",")));
        }

        return new SettingsModel(
                box64Choices,
                preferences.getString("box64_preset", Box64Preset.COMPATIBILITY),
                fexChoices,
                preferences.getString("fexcore_preset", FEXCorePreset.COMPATIBILITY),
                soundFontChoices,
                LosslessDll.isGlobalDllAvailable(context),
                winlatorPath,
                shortcutPath,
                Math.round(preferences.getFloat("cursor_speed", 1.0f) * 100.0f),
                preferences.getBoolean("cursor_lock", true),
                preferences.getBoolean("xinput_toggle", false),
                preferences.getBoolean("use_dri3", true),
                preferences.getBoolean("high_refresh_rate_mode", false),
                preferences.getBoolean("enable_file_provider", true),
                preferences.getBoolean("open_with_android_browser", false),
                preferences.getBoolean("share_android_clipboard", false),
                preferences.getBoolean("pause_resume_wine", true),
                preferences.getBoolean("remove_loading_bar_when_booting_games", false),
                preferences.getBoolean("enable_wine_debug", false),
                preferences.getString("wine_debug_channels", DEFAULT_WINE_DEBUG_CHANNELS),
                preferences.getBoolean("enable_box64_logs", false),
                preferences.getBoolean("enable_custom_api_key", false),
                preferences.getString("custom_api_key", ""),
                preferences.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES),
                wineDebugOptions
        );
    }

    private String resolveStoredPath(String key, String fallback) {
        String stored = preferences.getString(key, null);
        if (stored == null) return fallback;
        Uri uri = Uri.parse(stored);
        String path = FileUtils.getFilePathFromUri(requireContext(), uri);
        return path != null ? path : stored;
    }

    private SettingsCallbacks createComposeCallbacks() {
        return new SettingsCallbacks() {
            @Override
            public void onOpenComponents() {
                getParentFragmentManager().beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                        .addToBackStack(null)
                        .replace(R.id.FLFragmentContainer, ComponentManagerFragment.newInstance())
                        .commit();
            }

            @Override
            public void onOpenContainers() {
                getParentFragmentManager().beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                        .addToBackStack(null)
                        .replace(R.id.FLFragmentContainer, new com.winlator.cmod.ui.settings.ContainersSettingsFragment())
                        .commit();
            }

            @Override
            public void onBox64PresetSelected(@NonNull String id) {
                preferences.edit().putString("box64_preset", id).apply();
                refreshCompose();
            }

            @Override
            public void onFexPresetSelected(@NonNull String id) {
                preferences.edit().putString("fexcore_preset", id).apply();
                refreshCompose();
            }

            @Override
            public void onInstallSoundFont() {
                installSoundFontCallback = uri -> {
                    PreloaderDialog dialog = new PreloaderDialog(requireActivity());
                    dialog.showOnUiThread(R.string.installing_content);
                    MidiManager.installSF2File(requireContext(), uri, new MidiManager.OnSoundFontInstalledCallback() {
                        @Override
                        public void onSuccess() {
                            dialog.closeOnUiThread();
                            requireActivity().runOnUiThread(() -> {
                                ThemedAlertHost.info((AppCompatActivity) requireActivity(), "SoundFont Installed", "Soundfont installed successfully!");
                                refreshCompose();
                            });
                        }

                        @Override
                        public void onFailed(int reason) {
                            dialog.closeOnUiThread();
                            String message = switch (reason) {
                                case MidiManager.ERROR_BADFORMAT -> "Bad SoundFont format.";
                                case MidiManager.ERROR_EXIST -> "Soundfont you want to install already exists.";
                                default -> "Soundfont installation failed.";
                            };
                            requireActivity().runOnUiThread(() ->
                                    ThemedAlertHost.info((AppCompatActivity) requireActivity(), "SoundFont Install Failed", message));
                        }
                    });
                };
                openFile(REQUEST_CODE_INSTALL_SOUNDFONT);
            }

            @Override
            public void onRemoveSoundFont(@NonNull String name) {
                if (MidiManager.DEFAULT_SF2_FILE.equals(name)) {
                    Toast.makeText(requireContext(), R.string.cannot_remove_default_sound_font, Toast.LENGTH_SHORT).show();
                    return;
                }
                ThemedAlertHost.confirm(
                        (AppCompatActivity) requireActivity(),
                        "Remove SoundFont?",
                        "Do you want to remove this soundfont?",
                        "Remove",
                        () -> {
                            if (MidiManager.removeSF2File(requireContext(), name)) {
                                Toast.makeText(requireContext(), R.string.sound_font_removed_success, Toast.LENGTH_SHORT).show();
                                refreshCompose();
                            } else {
                                Toast.makeText(requireContext(), R.string.sound_font_removed_failed, Toast.LENGTH_SHORT).show();
                            }
                        },
                        true
                );
            }

            @Override
            public void onImportLosslessDll() {
                openFile(REQUEST_CODE_IMPORT_LOSSLESS_DLL);
            }

            @Override
            public void onChooseWinlatorPath() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(intent, REQUEST_CODE_WINLATOR_PATH);
            }

            @Override
            public void onChooseShortcutPath() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(intent, REQUEST_CODE_SHORTCUT_EXPORT_PATH);
            }

            @Override
            public void onBooleanChanged(@NonNull String key, boolean value) {
                SharedPreferences.Editor editor = preferences.edit().putBoolean(key, value);
                if ("enable_custom_api_key".equals(key) && !value) editor.remove("custom_api_key");
                editor.apply();
                if ("enable_file_provider".equals(key)) {
                    Toast.makeText(requireContext(), "This option will take effect at the next startup.", Toast.LENGTH_SHORT).show();
                }
                refreshCompose();
            }

            @Override
            public void onCursorSpeedChanged(int percent) {
                int clamped = Math.max(10, Math.min(200, percent));
                preferences.edit().putFloat("cursor_speed", clamped / 100.0f).apply();
                refreshCompose();
            }

            @Override
            public void onCustomApiKeyChanged(@NonNull String value) {
                preferences.edit().putString("custom_api_key", value.trim()).apply();
                refreshCompose();
            }

            @Override
            public void onContentsUrlChanged(@NonNull String value) {
                String normalized = value.trim();
                if (normalized.isEmpty()) normalized = ContentsManager.REMOTE_PROFILES;
                preferences.edit().putString("downloadable_contents_url", normalized).apply();
                refreshCompose();
            }

            @Override
            public void onWineDebugChannelsChanged(@NonNull String value) {
                String normalized = value.trim().replace(" ", "");
                if (normalized.isEmpty()) normalized = DEFAULT_WINE_DEBUG_CHANNELS;
                preferences.edit().putString("wine_debug_channels", normalized).apply();
                refreshCompose();
            }

            @Override
            public void onReinstallImageFs() {
                ImageFsInstaller.installFromAssets((MainActivity) requireActivity(), null);
            }

            @Override
            public void onPresetAction(@NonNull String kind, @NonNull String id, @NonNull String action) {
                Context context = requireContext();
                boolean box64 = "box64".equals(kind);

                switch (action) {
                    case "add":
                        if (box64) {
                            Box64EditPresetDialog dialog = new Box64EditPresetDialog(context, "box64", null);
                            dialog.setOnConfirmCallback(SettingsFragment.this::refreshCompose);
                            dialog.show();
                        } else {
                            FEXCoreEditPresetDialog dialog = new FEXCoreEditPresetDialog(context, null);
                            dialog.setOnConfirmCallback(SettingsFragment.this::refreshCompose);
                            dialog.show();
                        }
                        break;
                    case "edit":
                        if (box64) {
                            Box64EditPresetDialog dialog = new Box64EditPresetDialog(context, "box64", id);
                            dialog.setOnConfirmCallback(SettingsFragment.this::refreshCompose);
                            dialog.show();
                        } else {
                            FEXCoreEditPresetDialog dialog = new FEXCoreEditPresetDialog(context, id);
                            dialog.setOnConfirmCallback(SettingsFragment.this::refreshCompose);
                            dialog.show();
                        }
                        break;
                    case "duplicate":
                        ThemedAlertHost.confirm(
                                (AppCompatActivity) requireActivity(),
                                "Clone Preset",
                                "Do you want to duplicate this preset?",
                                "Clone",
                                () -> {
                                    if (box64) Box64PresetManager.duplicatePreset("box64", context, id);
                                    else FEXCorePresetManager.duplicatePreset(context, id);
                                    refreshCompose();
                                }
                        );
                        break;
                    case "remove":
                        boolean custom = box64
                                ? id.startsWith(Box64Preset.CUSTOM)
                                : id.startsWith(FEXCorePreset.CUSTOM);
                        if (!custom) {
                            Toast.makeText(context, "You cannot remove this preset", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ThemedAlertHost.confirm(
                                (AppCompatActivity) requireActivity(),
                                "Remove Preset",
                                "Do you want to remove this preset?",
                                "Remove",
                                () -> {
                                    if (box64) {
                                        Box64PresetManager.removePreset("box64", context, id);
                                        if (id.equals(preferences.getString("box64_preset", ""))) {
                                            preferences.edit().putString("box64_preset", Box64Preset.COMPATIBILITY).apply();
                                        }
                                    } else {
                                        FEXCorePresetManager.removePreset(context, id);
                                        if (id.equals(preferences.getString("fexcore_preset", ""))) {
                                            preferences.edit().putString("fexcore_preset", FEXCorePreset.COMPATIBILITY).apply();
                                        }
                                    }
                                    refreshCompose();
                                    Toast.makeText(context, "Preset removed", Toast.LENGTH_SHORT).show();
                                },
                                true
                        );
                        break;
                    case "import":
                        openFile(box64 ? REQUEST_CODE_IMPORT_BOX64_PRESET : REQUEST_CODE_IMPORT_FEXCORE_PRESET);
                        break;
                    case "export":
                        boolean exportable = box64
                                ? id.startsWith(Box64Preset.CUSTOM)
                                : id.startsWith(FEXCorePreset.CUSTOM);
                        if (!exportable) {
                            Toast.makeText(context, "Cannot export this preset", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (box64) Box64PresetManager.exportPreset("box64", context, id);
                        else FEXCorePresetManager.exportPreset(context, id);
                        break;
                }
            }
        };
    }

    private void refreshCompose() {
        if (composeView != null && preferences != null && isAdded()) {
            SettingsComposeHost.update(composeView, buildComposeModel());
        }
    }

    private void openFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        // Start activity for result based on the provided request code
        getActivity().startActivityFromFragment(this, intent, requestCode);
    }

    public static void resetEmulatorsVersion(AppCompatActivity activity) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove("current_box64_version");
        editor.remove("current_wowbox64_version");
        editor.remove("current_fexcore_version");
        editor.apply();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();

            if (uri != null) {

                SharedPreferences.Editor editor = preferences.edit();

                switch (requestCode) {

                    case REQUEST_CODE_WINLATOR_PATH:
                        // Save the selected URI as a string in SharedPreferences
                        editor.putString("winlator_path_uri", uri.toString());
                        editor.apply();

                        // Take persistable URI permission
                        try {
                            // Take persistable URI permission with explicit flags
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );
                        } catch (SecurityException e) {
                            Toast.makeText(getContext(), "Unable to take persistable permissions: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }

                        refreshCompose();
                        break;

                    case REQUEST_CODE_SHORTCUT_EXPORT_PATH:
                        editor.putString("shortcuts_export_path_uri", uri.toString());
                        editor.apply();

                        // Take persistable URI permission
                        try {
                            // Take persistable URI permission with explicit flags
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );
                        } catch (SecurityException e) {
                            Toast.makeText(getContext(), "Unable to take persistable permissions: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }

                        refreshCompose();
                        break;

                    // Case for installing a SoundFont
                    case REQUEST_CODE_INSTALL_SOUNDFONT:
                        if (installSoundFontCallback != null) {
                            try {
                                installSoundFontCallback.call(uri);
                            } catch (Exception e) {
                                Toast.makeText(getContext(), "Unable to install soundfont", Toast.LENGTH_SHORT).show();
                            } finally {
                                installSoundFontCallback = null;
                            }
                        }
                        break;

                    case REQUEST_CODE_IMPORT_LOSSLESS_DLL:
                        if (LosslessDll.importGlobalLosslessDll(requireContext(), uri)) {
                            Toast.makeText(requireContext(), "Lossless.dll imported", Toast.LENGTH_SHORT).show();
                            refreshCompose();
                        } else {
                            Toast.makeText(requireContext(), "Unable to import Lossless.dll", Toast.LENGTH_SHORT).show();
                        }
                        break;

                    case REQUEST_CODE_IMPORT_BOX64_PRESET:
                        try {
                            InputStream is = requireActivity().getContentResolver().openInputStream(uri);
                            Box64PresetManager.importPreset("box64", requireContext(), is);
                            refreshCompose();
                        } catch (FileNotFoundException e) {
                        }
                        break;
                    case REQUEST_CODE_IMPORT_FEXCORE_PRESET:
                        try {
                            InputStream is = requireActivity().getContentResolver().openInputStream(uri);
                            FEXCorePresetManager.importPreset(requireContext(), is);
                            refreshCompose();
                        } catch (FileNotFoundException e) {
                        }
                        break;
                        // Add future cases here for other request codes...
                    default:
                        break;
                }
            }
        }
    }

    private void moveFiles(File sourceDir, File targetDir) throws IOException {
        File[] files = sourceDir.listFiles();
        if (files != null) {
            for (File file : files) {
                File targetFile = new File(targetDir, file.getName());
                if (file.isDirectory()) {
                    if (!targetFile.exists()) {
                        targetFile.mkdirs();
                    }
                    moveFiles(file, targetFile); // Recursively move directory contents
                } else {
                    if (!file.renameTo(targetFile)) {
                        throw new IOException("Failed to move file: " + file.getAbsolutePath());
                    }
                }
            }
        }
        // Clear the temporary directory after moving
        FileUtils.clear(sourceDir);
    }
}

