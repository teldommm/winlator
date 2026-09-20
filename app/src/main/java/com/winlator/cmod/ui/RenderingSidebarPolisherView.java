package com.winlator.cmod.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

// Every id this used to style (LLStandardOptions, SWEnableFSR, SPUpscalerMode, SPPostFXMode,
// LBLSharpnessHeader) belonged to the legacy Graphics panel XML, now replaced by the Compose
// GraphicsSidebarPanel. An id with no remaining @+id/ declaration removes R.id.<name> itself,
// so findViewById(R.id.X) here would fail to compile, not just resolve to null at runtime —
// there's nothing left for this view to do. Left as an empty stub (rather than deleted,
// along with its still-live <com.winlator.cmod.ui.RenderingSidebarPolisherView> tag in
// left_sidebar.xml) so the inflater has a class to instantiate; it renders nothing and does
// nothing.
public class RenderingSidebarPolisherView extends View {
    public RenderingSidebarPolisherView(Context context) {
        super(context);
    }

    public RenderingSidebarPolisherView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RenderingSidebarPolisherView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
