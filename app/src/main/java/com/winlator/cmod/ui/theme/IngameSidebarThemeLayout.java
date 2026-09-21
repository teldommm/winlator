package com.winlator.cmod.ui.theme;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;

// Applies the ingameSidebar* theme-overlay attrs (see ingame_sidebar_themes.xml) so
// sidebar_panel_bg (the outer floating-card background/outline) resolves per the user's
// chosen Winlator theme. Used to also retint an entire legacy native sidebar tree
// (normalizeLegacyTree/wrapLegacySpinnerAdapters/isLegacyBlue) — removed once that tree
// was replaced by the Compose sidebar rail + panels (see SidebarRailPanel.kt and the
// sidebar Compose port), since nothing native remains under this root to retint.
public class IngameSidebarThemeLayout extends FrameLayout {

    public IngameSidebarThemeLayout(Context context) {
        super(context);
        applyChosenTheme(context);
    }

    public IngameSidebarThemeLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        applyChosenTheme(context);
    }

    public IngameSidebarThemeLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        applyChosenTheme(context);
    }

    private static void applyChosenTheme(Context context) {
        String theme = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .getString("winlator_ui_theme", "black");
        int overlay;
        if ("white".equals(theme)) overlay = R.style.IngameSidebarTheme_White;
        else if ("amoled".equals(theme)) overlay = R.style.IngameSidebarTheme_Amoled;
        else overlay = R.style.IngameSidebarTheme_Black;
        context.getTheme().applyStyle(overlay, true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setBackgroundColor(Color.TRANSPARENT);
    }
}
