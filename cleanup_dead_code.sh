#!/data/data/com.termux/files/usr/bin/bash
#
# Winlator Bionic (com.winlite.bionic) — dead/orphaned code cleanup
#
# Deletes files confirmed to have ZERO references anywhere in the project:
#   - no Java/Kotlin R.layout / class usage
#   - no XML <include>, no styles.xml @layout entries, no tools:layout
#
# The preference_* layout family + res/xml/preferences*.xml are also included
# below (round 2): confirmed the whole AndroidX Preference apparatus is unused.
# That round also required an accompanying edit to values/styles.xml (removing
# the Preference.*/PreferenceFragment*/PreferenceThemeOverlay* style block) —
# drop in the full replacement styles.xml you already have alongside this script.
#
# Round 3: ShortcutSettingsDialog.java itself turned out to be dead too (the
# live per-shortcut editor is ui/shortcut/ShortcutEditorV2.kt, Compose). Its
# whole call chain — GraphicsDriverConfigDialog, DXVKConfigDialog,
# WineD3DConfigDialog, AddEnvVarDialog — is deleted below. RendererOptionsDialog
# is NOT deleted (kept, trimmed) because XServerDisplayActivity still calls its
# static toVkPresentMode() live — same for GraphicsDriverConfigDialog,
# DXVKConfigDialog and WineD3DConfigDialog, which each had live static config/
# env-var helpers alongside their dead dialog UI: drop in the trimmed
# replacement versions of those four files you already have, alongside this
# script — do NOT let this script delete them, it only deletes the confirmed
# fully-dead files below.
#
# NOT included here (left alone on purpose — confirmed still live):
#   - left_sidebar.xml / left_sidebar_original.xml / main_menu_storage_footer.xml —
#     these looked orphaned at first glance but are live via <include> in
#     xserver_display_activity.xml / main_activity.xml
#
# This script only DELETES files. It does not apply the separate content edits
# to MainActivity.java, RemoteDriverCatalog.java, values/styles.xml,
# GraphicsDriverConfigDialog.java, DXVKConfigDialog.java, WineD3DConfigDialog.java
# and RendererOptionsDialog.java — those are full replacement files, drop them
# in manually.
#
# Usage:
#   bash cleanup_dead_code.sh                     # uses default path below
#   bash cleanup_dead_code.sh /path/to/project     # or pass a custom path
#
# Safe to re-run: files already missing are just skipped, nothing else happens.

set -uo pipefail

BASE_DIR="${1:-/data/data/com.termux/files/home/winlator}"

if [ ! -d "$BASE_DIR" ]; then
    echo "ERROR: directory not found: $BASE_DIR"
    echo "Pass the correct path as an argument, e.g.:"
    echo "  bash $0 /data/data/com.termux/files/home/winlator"
    exit 1
fi

cd "$BASE_DIR" || exit 1

FILES=(
    # --- Dead fragments/dialogs: never attached to a FragmentManager / never shown ---
    "app/src/main/java/com/winlator/cmod/ContainerDetailFragment.java"
    "app/src/main/java/com/winlator/cmod/AdrenotoolsFragment.java"
    "app/src/main/java/com/winlator/cmod/ContentsFragment.java"
    "app/src/main/java/com/winlator/cmod/contentdialog/RepositoryManagerDialog.java"
    "app/src/main/java/com/winlator/cmod/contentdialog/DriverDownloadDialog.java"
    "app/src/main/java/com/winlator/cmod/contentdialog/ContentInfoDialog.java"
    "app/src/main/java/com/winlator/cmod/contentdialog/ContentUntrustedDialog.java"
    "app/src/main/java/com/winlator/cmod/contentdialog/StorageInfoDialog.java"

    # --- Layouts/drawable orphaned by the dead classes above ---
    "app/src/main/res/layout/container_detail_fragment.xml"
    "app/src/main/res/layout/adrenotools_fragment.xml"
    "app/src/main/res/layout/adrenotools_dashboard_item.xml"
    "app/src/main/res/layout/adrenotools_list_item.xml"
    "app/src/main/res/layout/contents_fragment.xml"
    "app/src/main/res/layout/content_info_dialog.xml"
    "app/src/main/res/layout/content_untrusted_dialog.xml"
    "app/src/main/res/layout/container_storage_info_dialog.xml"
    "app/src/main/res/layout/content_list_item.xml"
    "app/src/main/res/layout/content_file_list_item.xml"
    "app/src/main/res/layout/drive_list_item.xml"
    "app/src/main/res/drawable/adrenotools_card_background.xml"

    # --- Layouts orphaned by earlier Compose migrations / leftover redesigns ---
    "app/src/main/res/layout/activity_file_picker.xml"
    "app/src/main/res/layout/activity_terminal.xml"
    "app/src/main/res/layout/analog_stick_config_dialog.xml"
    "app/src/main/res/layout/box64_edit_preset_dialog.xml"
    "app/src/main/res/layout/box64_env_var_list_item.xml"
    "app/src/main/res/layout/checkbox_spinner.xml"
    "app/src/main/res/layout/container_selection_dialog.xml"
    "app/src/main/res/layout/external_controller_binding_list_item.xml"
    "app/src/main/res/layout/external_controller_bindings_activity.xml"
    "app/src/main/res/layout/external_controller_list_item.xml"
    "app/src/main/res/layout/extra_keys_config.xml"
    "app/src/main/res/layout/game_detail_fragment.xml"
    "app/src/main/res/layout/input_controls_fragment.xml"
    "app/src/main/res/layout/installed_wine_list_item.xml"
    "app/src/main/res/layout/main_menu_header.xml"
    "app/src/main/res/layout/proton_options_dialog.xml"
    "app/src/main/res/layout/screen_effect_dialog.xml"
    "app/src/main/res/layout/shortcut_properties_dialog.xml"
    "app/src/main/res/layout/wine_install_options_dialog.xml"

    # --- Round 2: dead AndroidX Preference apparatus (layouts + screen definitions) ---
    "app/src/main/res/layout/preference.xml"
    "app/src/main/res/layout/preference_category.xml"
    "app/src/main/res/layout/preference_category_material.xml"
    "app/src/main/res/layout/preference_dialog_edittext.xml"
    "app/src/main/res/layout/preference_dropdown.xml"
    "app/src/main/res/layout/preference_dropdown_material.xml"
    "app/src/main/res/layout/preference_information.xml"
    "app/src/main/res/layout/preference_information_material.xml"
    "app/src/main/res/layout/preference_list_fragment.xml"
    "app/src/main/res/layout/preference_material.xml"
    "app/src/main/res/layout/preference_recyclerview.xml"
    "app/src/main/res/layout/preference_widget_checkbox.xml"
    "app/src/main/res/layout/preference_widget_seekbar.xml"
    "app/src/main/res/layout/preference_widget_seekbar_material.xml"
    "app/src/main/res/layout/preference_widget_switch.xml"
    "app/src/main/res/layout/preference_widget_switch_compat.xml"
    "app/src/main/res/xml/preferences.xml"
    "app/src/main/res/xml/preferences_x11.xml"

    # --- Round 3: ShortcutSettingsDialog itself was dead (real editor is
    #     ui/shortcut/ShortcutEditorV2.kt, Compose) — deleting it and the two
    #     dialogs that had NO live static methods left over ---
    "app/src/main/java/com/winlator/cmod/contentdialog/ShortcutSettingsDialog.java"
    "app/src/main/java/com/winlator/cmod/contentdialog/AddEnvVarDialog.java"
    "app/src/main/res/layout/shortcut_settings_dialog.xml"
    "app/src/main/res/layout/wincomponent_list_item.xml"
    "app/src/main/res/layout/add_env_var_dialog.xml"
    "app/src/main/res/layout/dxvk_config_dialog.xml"
    "app/src/main/res/layout/graphics_driver_config_dialog.xml"
    "app/src/main/res/layout/renderer_options_dialog.xml"
    "app/src/main/res/layout/wined3d_config_dialog.xml"
    "app/src/main/res/layout/spinner_dropdown_item_amoled.xml"
    "app/src/main/res/layout/spinner_dropdown_item_amoled_compact.xml"
    "app/src/main/res/layout/spinner_item_amoled.xml"

    # --- Round 4: real gradle build caught this — ImagePickerView.java referenced
    #     R.id.* that only ever existed inside container_detail_fragment.xml
    #     (deleted in round 1). Root cause: I checked R.layout.X references before
    #     deleting layouts, but never checked R.id.X for ids declared only inside
    #     them — R.id is a global namespace, not scoped to one layout. Turned out
    #     ImagePickerView itself was dead too (only ever used from the same
    #     already-deleted cluster), so this completes that cleanup rather than
    #     restoring anything. Re-verified with an exhaustive sweep: every R.id.X
    #     and R.layout.X reference in the whole codebase now resolves against
    #     what's still on disk.
    "app/src/main/java/com/winlator/cmod/widget/ImagePickerView.java"
    "app/src/main/res/layout/image_picker_view.xml"
    "app/src/main/res/drawable-hdpi/icon_image_picker.png"

    # --- Round 5: renamed 4 trimmed utility classes to drop the misleading
    #     "Dialog" suffix (they no longer show any UI). Old filenames deleted
    #     here; new files (GraphicsDriverConfig.java, DXVKConfig.java,
    #     WineD3DConfig.java, RendererOptions.java) and the two files whose
    #     call sites changed (XServerDisplayActivity.java,
    #     contents/AdrenotoolsManager.java) are full replacement files —
    #     drop them in alongside running this script.
    "app/src/main/java/com/winlator/cmod/contentdialog/GraphicsDriverConfigDialog.java"
    "app/src/main/java/com/winlator/cmod/contentdialog/DXVKConfigDialog.java"
    "app/src/main/java/com/winlator/cmod/contentdialog/WineD3DConfigDialog.java"
    "app/src/main/java/com/winlator/cmod/contentdialog/RendererOptionsDialog.java"
)

removed=0
missing=0

for f in "${FILES[@]}"; do
    if [ -f "$f" ]; then
        rm -v -- "$f"
        removed=$((removed + 1))
    else
        echo "skip (already gone): $f"
        missing=$((missing + 1))
    fi
done

echo ""
echo "Done in: $BASE_DIR"
echo "Removed: $removed | already missing: $missing | total tracked: ${#FILES[@]}"
echo ""
echo "REMINDER — this script does NOT touch these nine (they need full content"
echo "replacement, not deletion; drop in the versions you already have):"
echo "  app/src/main/java/com/winlator/cmod/contents/RemoteDriverCatalog.java"
echo "  app/src/main/java/com/winlator/cmod/MainActivity.java"
echo "  app/src/main/java/com/winlator/cmod/XServerDisplayActivity.java"
echo "  app/src/main/java/com/winlator/cmod/container/Container.java"
echo "  app/src/main/java/com/winlator/cmod/contents/AdrenotoolsManager.java"
echo "  app/src/main/java/com/winlator/cmod/contentdialog/GraphicsDriverConfig.java  (new, was GraphicsDriverConfigDialog.java)"
echo "  app/src/main/java/com/winlator/cmod/contentdialog/DXVKConfig.java            (new, was DXVKConfigDialog.java)"
echo "  app/src/main/java/com/winlator/cmod/contentdialog/WineD3DConfig.java         (new, was WineD3DConfigDialog.java)"
echo "  app/src/main/java/com/winlator/cmod/contentdialog/RendererOptions.java       (new, was RendererOptionsDialog.java)"
echo "  app/src/main/res/values/styles.xml"
