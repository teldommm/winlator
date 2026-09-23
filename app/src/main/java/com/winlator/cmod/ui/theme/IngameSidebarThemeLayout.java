package com.winlator.cmod.ui.theme;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;

// Root of left_sidebar.xml. The sidebar itself is a single Compose composition
// (IngameSidebar.kt) that takes all of its colors from WinZTheme, so nothing under this
// root reads the overlay anymore. The overlay is still applied because it sets
// android:textColor*/colorAccent/colorControl* on XServerDisplayActivity's theme, which
// native widgets elsewhere in the activity inherit — dropping it is a separate decision.
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
