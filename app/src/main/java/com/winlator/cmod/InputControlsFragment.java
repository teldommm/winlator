package com.winlator.cmod;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.HttpUtils;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.ui.ThemedAlertHost;
import com.winlator.cmod.ui.inputcontrols.InputControllerItem;
import com.winlator.cmod.ui.inputcontrols.InputControlsCallbacks;
import com.winlator.cmod.ui.inputcontrols.InputControlsComposeHost;
import com.winlator.cmod.ui.inputcontrols.InputControlsModel;
import com.winlator.cmod.ui.inputcontrols.InputProfileItem;
import com.winlator.cmod.widget.InputControlsView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class InputControlsFragment extends Fragment {
    private static final String INPUT_CONTROLS_URL =
            "https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/%s";
    private static final String ARG_SELECTED_PROFILE_ID = "selected_profile_id";

    private InputControlsManager manager;
    private ControlsProfile currentProfile;
    private Callback<ControlsProfile> importProfileCallback;
    private SharedPreferences preferences;
    private ComposeView composeView;
    private ArrayList<ExternalController> visibleControllers = new ArrayList<>();
    private int selectedProfileId;

    public InputControlsFragment() {
    }

    public static InputControlsFragment newInstance(int selectedProfileId) {
        InputControlsFragment fragment = new InputControlsFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SELECTED_PROFILE_ID, selectedProfileId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        manager = new InputControlsManager(requireContext());
        selectedProfileId = getArguments() != null
                ? getArguments().getInt(ARG_SELECTED_PROFILE_ID, 0)
                : 0;
        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        Context context = requireContext();
        currentProfile = selectedProfileId > 0 ? manager.getProfile(selectedProfileId) : null;
        composeView = InputControlsComposeHost.create(context, buildModel(), createCallbacks());
        return composeView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(R.string.input_controls);
    }

    @Override
    public void onStart() {
        super.onStart();
        refreshCompose();
    }

    @Override
    public void onDestroyView() {
        composeView = null;
        super.onDestroyView();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != MainActivity.OPEN_FILE_REQUEST_CODE
                || resultCode != Activity.RESULT_OK
                || data == null
                || data.getData() == null) return;

        try {
            ControlsProfile importedProfile = manager.importProfile(
                    new JSONObject(FileUtils.readString(requireContext(), data.getData()))
            );
            if (importProfileCallback != null) importProfileCallback.call(importedProfile);
        }
        catch (Exception e) {
            Toast.makeText(requireContext(), "Unable to import profile", Toast.LENGTH_SHORT).show();
        }
        finally {
            importProfileCallback = null;
        }
    }

    private InputControlsCallbacks createCallbacks() {
        return new InputControlsCallbacks() {
            @Override
            public void onProfileSelected(int profileId) {
                currentProfile = profileId > 0 ? manager.getProfile(profileId) : null;
                refreshCompose();
            }

            @Override
            public void onOpacityChanged(int percent) {
                int snapped = Math.max(0, Math.min(100, Math.round(percent / 5.0f) * 5));
                preferences.edit().putFloat("overlay_opacity", snapped / 100.0f).apply();
                refreshCompose();
            }

            @Override
            public void onAddProfile() {
                ThemedAlertHost.prompt(activity(), "Profile Name", "", "Add", name -> {
                    String trimmed = name.trim();
                    if (trimmed.isEmpty()) return;
                    currentProfile = manager.createProfile(trimmed);
                    refreshCompose();
                });
            }

            @Override
            public void onEditProfile() {
                if (currentProfile == null) {
                    showNoProfileToast();
                    return;
                }
                ThemedAlertHost.prompt(
                        activity(),
                        "Profile Name",
                        currentProfile.getName(),
                        "Save",
                        name -> {
                            String trimmed = name.trim();
                            if (trimmed.isEmpty()) return;
                            currentProfile.setName(trimmed);
                            currentProfile.save();
                            refreshCompose();
                        }
                );
            }

            @Override
            public void onDuplicateProfile() {
                if (currentProfile == null) {
                    showNoProfileToast();
                    return;
                }
                ThemedAlertHost.confirm(
                        activity(),
                        "Duplicate Profile?",
                        "Do you want to duplicate this profile?",
                        "Duplicate",
                        () -> {
                            currentProfile = manager.duplicateProfile(currentProfile);
                            refreshCompose();
                        }
                );
            }

            @Override
            public void onRemoveProfile() {
                if (currentProfile == null) {
                    showNoProfileToast();
                    return;
                }
                ThemedAlertHost.confirm(
                        activity(),
                        "Remove Profile?",
                        "Do you want to remove this profile?",
                        "Remove",
                        () -> {
                            manager.removeProfile(currentProfile);
                            currentProfile = null;
                            refreshCompose();
                        },
                        true
                );
            }

            @Override
            public void onImportProfile() {
                showImportOptions();
            }

            @Override
            public void onExportProfile() {
                exportCurrentProfile();
            }

            @Override
            public void onOpenEditor() {
                openControlsEditor();
            }

            @Override
            public void onOpenController(int index) {
                openController(index);
            }

            @Override
            public void onRemoveController(int index) {
                removeController(index);
            }
        };
    }

    private InputControlsModel buildModel() {
        ArrayList<InputProfileItem> profileItems = new ArrayList<>();
        for (ControlsProfile profile : manager.getProfiles()) {
            profileItems.add(new InputProfileItem(profile.id, profile.getName()));
        }

        visibleControllers = collectVisibleControllers();
        ArrayList<InputControllerItem> controllerItems = new ArrayList<>();
        for (int index = 0; index < visibleControllers.size(); index++) {
            ExternalController controller = visibleControllers.get(index);
            controllerItems.add(new InputControllerItem(
                    index,
                    controller.getName(),
                    controller.getControllerBindingCount(),
                    controller.isConnected()
            ));
        }

        int opacity = Math.round(preferences.getFloat(
                "overlay_opacity",
                InputControlsView.DEFAULT_OVERLAY_OPACITY
        ) * 100.0f);
        return new InputControlsModel(
                profileItems,
                currentProfile != null ? currentProfile.id : 0,
                opacity,
                controllerItems
        );
    }

    private ArrayList<ExternalController> collectVisibleControllers() {
        ArrayList<ExternalController> controllers = currentProfile != null
                ? currentProfile.loadControllers()
                : new ArrayList<>();
        for (ExternalController connected : ExternalController.getControllers()) {
            if (!controllers.contains(connected)) controllers.add(connected);
        }
        return controllers;
    }

    private void refreshCompose() {
        if (composeView != null && manager != null && preferences != null) {
            InputControlsComposeHost.update(composeView, buildModel());
        }
    }

    private void showNoProfileToast() {
        Toast.makeText(requireContext(), "No profile selected", Toast.LENGTH_SHORT).show();
    }

    private AppCompatActivity activity() {
        return (AppCompatActivity) requireActivity();
    }

    private void showImportOptions() {
        ThemedAlertHost.actions(activity(), "Import Profile", Arrays.asList(
                new ThemedAlertHost.ActionItem("Open Local Profile", this::openProfileFile),
                new ThemedAlertHost.ActionItem("Download Profiles", this::downloadProfileList)
        ));
    }

    private void openProfileFile() {
        importProfileCallback = importedProfile -> {
            currentProfile = importedProfile;
            refreshCompose();
        };
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        requireActivity().startActivityFromFragment(
                this,
                intent,
                MainActivity.OPEN_FILE_REQUEST_CODE
        );
    }

    private void exportCurrentProfile() {
        if (currentProfile == null) {
            showNoProfileToast();
            return;
        }
        File exportedFile = manager.exportProfile(currentProfile);
        if (exportedFile != null) {
            Toast.makeText(
                    requireContext(),
                    "Profile exported to " + exportedFile.getPath(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void openControlsEditor() {
        if (currentProfile == null) {
            showNoProfileToast();
            return;
        }
        Intent intent = new Intent(requireContext(), ControlsEditorActivity.class);
        intent.putExtra("profile_id", currentProfile.id);
        startActivity(intent);
        requireActivity().overridePendingTransition(R.anim.slide_in_up, R.anim.slide_out_down);
    }

    private void openController(int index) {
        if (currentProfile == null) {
            showNoProfileToast();
            return;
        }
        if (index < 0 || index >= visibleControllers.size()) return;
        ExternalController controller = visibleControllers.get(index);
        Intent intent = new Intent(requireContext(), ExternalControllerBindingsActivity.class);
        intent.putExtra("profile_id", currentProfile.id);
        intent.putExtra("controller_id", controller.getId());
        startActivity(intent);
        requireActivity().overridePendingTransition(R.anim.slide_in_up, R.anim.slide_out_down);
    }

    private void removeController(int index) {
        if (currentProfile == null || index < 0 || index >= visibleControllers.size()) return;
        ExternalController controller = visibleControllers.get(index);
        ThemedAlertHost.confirm(
                activity(),
                "Remove Controller?",
                "Do you want to remove this controller?",
                "Remove",
                () -> {
                    currentProfile.removeController(controller);
                    currentProfile.save();
                    refreshCompose();
                },
                true
        );
    }

    private void downloadSelectedProfiles(String[] items, List<Integer> positions) {
        MainActivity activity = (MainActivity) requireActivity();
        activity.preloaderDialog.show(R.string.downloading_file);
        currentProfile = null;
        AtomicInteger processedItemCount = new AtomicInteger();

        for (int position : positions) {
            HttpUtils.download(String.format(INPUT_CONTROLS_URL, items[position]), content -> {
                try {
                    if (content != null) manager.importProfile(new JSONObject(content));
                }
                catch (JSONException ignored) {
                }
                if (processedItemCount.incrementAndGet() == positions.size()) {
                    activity.runOnUiThread(() -> {
                        activity.preloaderDialog.close();
                        refreshCompose();
                    });
                }
            });
        }
    }

    private void downloadProfileList() {
        MainActivity activity = (MainActivity) requireActivity();
        activity.preloaderDialog.show(R.string.loading);
        HttpUtils.download(String.format(INPUT_CONTROLS_URL, "index.txt"), content ->
                activity.runOnUiThread(() -> {
                    activity.preloaderDialog.close();
                    if (content == null) {
                        Toast.makeText(activity, "Unable to load profile list", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] items = content.split("\\n");
                    ThemedAlertHost.multiChoice(
                            activity,
                            "Import Profile",
                            Arrays.asList(items),
                            "Download",
                            positions -> {
                                if (!positions.isEmpty()) {
                                    ThemedAlertHost.confirm(
                                            activity,
                                            "Download Profiles?",
                                            "Do you want to download the selected profiles?",
                                            "Download",
                                            () -> downloadSelectedProfiles(items, positions)
                                    );
                                }
                            }
                    );
                })
        );
    }
}
