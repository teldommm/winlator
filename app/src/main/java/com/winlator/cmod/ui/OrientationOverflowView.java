package com.winlator.cmod.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.widget.CompoundButtonCompat;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.ui.theme.WinlatorLegacyTheme;

public final class OrientationOverflowView extends AppCompatImageButton {
    private final MainActivity activity;
    private int surface;
    private int onSurface;
    private int onSurfaceVariant;
    private int primary;
    private int outline;

    public OrientationOverflowView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        setImageResource(R.drawable.ui_ic_more);
        setContentDescription("More");
        setScaleType(ScaleType.CENTER_INSIDE);
        setPadding(dp(11), dp(11), dp(11), dp(11));
        setMinimumWidth(dp(48));
        setMinimumHeight(dp(48));
        TypedValue selectable = new TypedValue();
        if (getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, selectable, true)) {
            setBackgroundResource(selectable.resourceId);
        }
        refreshPalette();
        setColorFilter(onSurface);
        setOnClickListener(v -> showMenu());
    }

    private void showMenu() {
        refreshPalette();
        setColorFilter(onSurface);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(4));

        GradientDrawable background = new GradientDrawable();
        background.setColor(surface);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), outline);
        content.setBackground(background);

        PopupWindow popup = new PopupWindow(
                content,
                dp(310),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(10));
        popup.setClippingEnabled(true);

        content.addView(createRow(
                "Lock screen orientation",
                activity.isOrientationLocked(),
                false,
                v -> {
                    activity.toggleOrientationLock();
                    popup.dismiss();
                }
        ));
        content.addView(createRow(
                "Vertical mode",
                activity.isVerticalModeEnabled(),
                true,
                v -> {
                    activity.toggleVerticalMode();
                    popup.dismiss();
                }
        ));
        content.addView(createRow(
                "Horizontal mode",
                activity.isHorizontalModeEnabled(),
                true,
                v -> {
                    activity.toggleHorizontalMode();
                    popup.dismiss();
                }
        ));

        popup.showAsDropDown(this, -(dp(310) - getWidth()), 0);
    }

    private View createRow(String label, boolean checked, boolean radio, OnClickListener click) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), 0, dp(12), 0);
        row.setMinimumHeight(dp(56));
        row.setClickable(true);
        row.setFocusable(true);

        TypedValue selectable = new TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
            row.setBackgroundResource(selectable.resourceId);
        }

        TextView text = new TextView(getContext());
        text.setText(label);
        text.setTextColor(onSurface);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(0, dp(56), 1f));

        CompoundButton button = radio ? new RadioButton(getContext()) : new CheckBox(getContext());
        button.setChecked(checked);
        button.setClickable(false);
        button.setFocusable(false);
        int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] { }
        };
        CompoundButtonCompat.setButtonTintList(button,
                new ColorStateList(states, new int[] { primary, onSurfaceVariant }));
        row.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.setOnClickListener(click);
        return row;
    }

    private void refreshPalette() {
        surface = WinlatorLegacyTheme.surface(getContext());
        onSurface = WinlatorLegacyTheme.onSurface(getContext());
        onSurfaceVariant = WinlatorLegacyTheme.onSurfaceVariant(getContext());
        primary = WinlatorLegacyTheme.controlAccent(getContext());
        outline = WinlatorLegacyTheme.outlineVariant(getContext());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
