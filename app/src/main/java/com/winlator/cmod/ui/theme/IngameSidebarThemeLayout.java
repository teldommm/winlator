package com.winlator.cmod.ui.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;

public class IngameSidebarThemeLayout extends FrameLayout {
    private int background;
    private int surfaceVariant;
    private int onSurface;
    private int onSurfaceVariant;
    private int primary;
    private int primaryContainer;

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
        readPalette();
        setBackgroundColor(Color.TRANSPARENT);

        ViewGroup legacyRoot = findViewById(R.id.LegacySidebarRoot);
        if (legacyRoot != null) {
            // legacyRoot itself used to be one big opaque rectangle spanning the whole
            // sidebar width (the "solid slab" behind both the rail and the content), and
            // later reserved dp(64) of left padding for the rail to float on top of it.
            // Rail and content are now two columns of one shared card (see left_sidebar.xml
            // / IngameSidebarCard) rather than overlapping layers, so legacyRoot needs
            // neither: it's left fully transparent with no reserved padding.
            legacyRoot.setBackgroundColor(Color.TRANSPARENT);
            if (legacyRoot.getChildCount() >= 3) {
                legacyRoot.getChildAt(0).setVisibility(View.GONE);
                legacyRoot.getChildAt(1).setVisibility(View.GONE);
            }
        }

        // replaceLegacyFpsLimiter(), applyCompactPremiumLayout(), forceKnownLegacyIconTints()
        // and fitMetricText() used to run here too — all of them only ever reached ids
        // belonging to the legacy Screen/Input/HUD/Graphics/TaskManager panels, which are
        // now Compose (see the sidebar Compose port). An id with no remaining @+id/
        // declaration removes R.id.<name> itself, so those findViewById(R.id.X) calls
        // would fail to compile, not just resolve to null at runtime, once every panel's
        // XML was gone — hence removed rather than left dormant.
        normalizeLegacyTree(this);

        post(() -> normalizeLegacyTree(this));
        postDelayed(() -> {
            normalizeLegacyTree(this);
            wrapLegacySpinnerAdapters(this);
        }, 500);
    }

    private void readPalette() {
        background = resolveColor(R.attr.ingameSidebarBackground, Color.BLACK);
        surfaceVariant = resolveColor(R.attr.ingameSidebarSurfaceVariant, Color.rgb(28, 29, 35));
        onSurface = resolveColor(R.attr.ingameSidebarOnSurface, Color.WHITE);
        onSurfaceVariant = resolveColor(R.attr.ingameSidebarOnSurfaceVariant, Color.LTGRAY);
        primary = resolveColor(R.attr.ingameSidebarPrimary, Color.WHITE);
        primaryContainer = resolveColor(R.attr.ingameSidebarPrimaryContainer, surfaceVariant);
    }

    private int resolveColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) return value.data;
        return fallback;
    }

    private void normalizeLegacyTree(View view) {
        Drawable drawable = view.getBackground();
        if (drawable instanceof ColorDrawable) {
            int color = ((ColorDrawable) drawable).getColor();
            if (color == Color.BLACK || color == Color.rgb(3, 8, 13)) {
                view.setBackgroundColor(background);
            } else if (color == Color.rgb(14, 34, 49) || color == Color.rgb(15, 45, 66)) {
                view.setBackgroundColor(surfaceVariant);
            }
        }

        if (view instanceof TextView) {
            TextView text = (TextView) view;
            int current = text.getCurrentTextColor();
            if (current == Color.WHITE || current == Color.rgb(238, 247, 255)) {
                text.setTextColor(onSurface);
            } else if (isLegacyBlue(current)) {
                text.setTextColor(primary);
            } else if (current == Color.rgb(221, 246, 255)) {
                text.setTextColor(onSurfaceVariant);
            }
        }

        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            ColorStateList tint = ImageViewCompat.getImageTintList(image);
            if (tint != null) {
                int current = tint.getDefaultColor();
                if (isLegacyBlue(current)) {
                    ImageViewCompat.setImageTintList(image, ColorStateList.valueOf(primary));
                } else if (current == Color.rgb(221, 246, 255)
                        || current == Color.rgb(238, 247, 255)) {
                    ImageViewCompat.setImageTintList(image, ColorStateList.valueOf(onSurface));
                }
            }
        }

        if (view instanceof Switch) {
            Switch toggle = (Switch) view;
            int[][] states = new int[][] {
                    new int[] { android.R.attr.state_checked },
                    new int[] { }
            };
            toggle.setThumbTintList(new ColorStateList(states,
                    new int[] { primary, onSurfaceVariant }));
            toggle.setTrackTintList(new ColorStateList(states,
                    new int[] { primaryContainer, surfaceVariant }));
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                normalizeLegacyTree(group.getChildAt(i));
            }
        }
    }

    private void wrapLegacySpinnerAdapters(View view) {
        if (view instanceof Spinner) {
            Spinner spinner = (Spinner) view;
            SpinnerAdapter adapter = spinner.getAdapter();
            if (adapter != null && !(adapter instanceof ThemeSpinnerAdapter)) {
                int selected = spinner.getSelectedItemPosition();
                spinner.setAdapter(new ThemeSpinnerAdapter(adapter));
                if (selected >= 0 && selected < spinner.getCount()) spinner.setSelection(selected, false);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) wrapLegacySpinnerAdapters(group.getChildAt(i));
        }
    }

    private View themeSpinnerView(View view, boolean dropdown) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(onSurface);
            text.setSingleLine(true);
            text.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            if (dropdown) {
                text.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                text.setBackgroundColor(surfaceVariant);
            }
        } else {
            normalizeLegacyTree(view);
            if (dropdown && view.getBackground() instanceof ColorDrawable) {
                view.setBackgroundColor(surfaceVariant);
            }
        }
        return view;
    }

    private final class ThemeSpinnerAdapter implements SpinnerAdapter {
        private final SpinnerAdapter delegate;

        private ThemeSpinnerAdapter(SpinnerAdapter delegate) {
            this.delegate = delegate;
        }

        @Override public int getCount() { return delegate.getCount(); }
        @Override public Object getItem(int position) { return delegate.getItem(position); }
        @Override public long getItemId(int position) { return delegate.getItemId(position); }
        @Override public boolean hasStableIds() { return delegate.hasStableIds(); }
        @Override public int getItemViewType(int position) { return delegate.getItemViewType(position); }
        @Override public int getViewTypeCount() { return delegate.getViewTypeCount(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public void registerDataSetObserver(DataSetObserver observer) { delegate.registerDataSetObserver(observer); }
        @Override public void unregisterDataSetObserver(DataSetObserver observer) { delegate.unregisterDataSetObserver(observer); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return themeSpinnerView(delegate.getView(position, convertView, parent), false);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return themeSpinnerView(delegate.getDropDownView(position, convertView, parent), true);
        }
    }

    private boolean isLegacyBlue(int color) {
        return color == Color.rgb(0, 85, 255)
                || color == Color.rgb(0, 102, 255)
                || color == Color.rgb(2, 136, 209)
                || color == Color.rgb(64, 196, 255)
                || color == Color.rgb(143, 216, 255)
                || color == Color.rgb(130, 184, 255);
    }

}
