package com.winlator.cmod.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;

// Every id this view used to reach (BTSaveGraphicsPreset, SPHudStyle, LLModernHudOptions,
// CBHud*, SPInputControlsProfile, BTInputControlsSettings, SWEnableFSR, SPUpscalerMode,
// SPPostFXMode, SBSharpness) belonged to the legacy XML panels removed during the Compose
// port. Removing an id's only @+id/ declaration removes R.id.<name> itself — a
// findViewById(R.id.X) guarded by "if (view == null)" is still a compile error, not a
// harmless runtime null, so all of that code had to come out rather than stay dormant.
// The rail is the one target here that's still real, so tuneNeutralRail() (and the
// section-label sweep, which matches by view tag rather than id) are all that's left.
public class SidebarCleanupView extends View {
    public SidebarCleanupView(Context context) {
        super(context);
    }

    public SidebarCleanupView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public SidebarCleanupView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::polishUi);
        postDelayed(this::polishUi, 80);
        postDelayed(this::polishUi, 650);
    }

    private void polishUi() {
        View root = getRootView();
        hideRenderingSectionLabels(root);
        tuneNeutralRail(root);
    }

    private void tuneNeutralRail(View root) {
        String theme = PreferenceManager.getDefaultSharedPreferences(
                getContext().getApplicationContext()).getString("winlator_ui_theme", "black");
        if (!"black".equals(theme) && !"amoled".equals(theme)) return;

        View rail = root.findViewById(R.id.IngameSidebarRail);
        if (rail == null) return;

        int surface = resolveColor(R.attr.ingameSidebarSurface,
                "amoled".equals(theme) ? 0xFF050505 : 0xFF121216);
        int edge = resolveColor(R.attr.ingameSidebarSurfaceVariant,
                "amoled".equals(theme) ? 0xFF0D0D0D : 0xFF1A1A20);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(surface);
        background.setStroke(dp(1), edge);
        background.setCornerRadius(dp(22));
        rail.setBackground(background);
        rail.setElevation(dp(8));
        rail.setTranslationZ(dp(2));
    }

    private int resolveColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) return value.data;
        return fallback;
    }

    private void hideRenderingSectionLabels(View view) {
        Object tag = view.getTag();
        if (tag instanceof String) {
            String value = (String) tag;
            if ("winz-section-PERFORMANCE".equals(value)
                    || "winz-section-IMAGE QUALITY".equals(value)
                    || "winz-section-PRESETS".equals(value)) {
                view.setVisibility(GONE);
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                hideRenderingSectionLabels(group.getChildAt(i));
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
