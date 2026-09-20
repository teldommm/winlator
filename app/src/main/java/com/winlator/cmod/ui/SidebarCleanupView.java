package com.winlator.cmod.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

// Every id this view used to reach (BTSaveGraphicsPreset, SPHudStyle, LLModernHudOptions,
// CBHud*, SPInputControlsProfile, BTInputControlsSettings, SWEnableFSR, SPUpscalerMode,
// SPPostFXMode, SBSharpness) belonged to the legacy XML panels removed during the Compose
// port. Removing an id's only @+id/ declaration removes R.id.<name> itself — a
// findViewById(R.id.X) guarded by "if (view == null)" is still a compile error, not a
// harmless runtime null, so all of that code had to come out rather than stay dormant.
// tuneNeutralRail() (which used to repaint the rail with its own independent rounded
// background/border/elevation) is gone too now for a different reason: the rail and the
// content panel are one physical card as of left_sidebar.xml's IngameSidebarCard, so
// nothing here should be giving the rail its own separate rounding again.
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
        hideRenderingSectionLabels(getRootView());
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
}
