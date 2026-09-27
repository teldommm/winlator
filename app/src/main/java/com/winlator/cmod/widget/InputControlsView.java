package com.winlator.cmod.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.ExternalControllerBinding;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.MotionAccumulator;
import com.winlator.cmod.ui.inputcontrols.EditorCanvasColors;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Timer;
import java.util.TimerTask;

public class InputControlsView extends View {
    public static final float DEFAULT_OVERLAY_OPACITY = 0.85f;
    private static final byte MOUSE_WHEEL_DELTA = 120;
    private static final boolean AUTO_HIDE_CONTROLS = false;
    private boolean editMode = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final ColorFilter colorFilter = new PorterDuffColorFilter(0xff2184ff, PorterDuff.Mode.SRC_IN);
    private final Point cursor = new Point();
    private boolean readyToDraw = false;
    private boolean moveCursor = false;
    private int snappingSize;
    private float offsetX;
    private float offsetY;
    private ControlElement selectedElement;

    // ---------- Editor (edit mode only) ----------
    // Listener the ControlsEditorActivity uses to keep its floating toolbar/settings panel in
    // sync with what happens on the canvas.
    public interface EditorListener {
        void onSelectionChanged(ControlElement element);
        // Fired after a drag has fully settled (position is final and saved), and after
        // add/duplicate/undo — anything that changes where elements sit.
        void onElementsChanged();
        void onUndoAvailabilityChanged(boolean available);
        // True while an element is being dragged or pinch-scaled on the canvas (the floating
        // toolbar/panel dim themselves so what's underneath stays visible).
        void onElementGestureChanged(boolean active);
    }

    private static final long SETTLE_DURATION_MS = 120;
    // Fraction of one grid cell within which an axis sticks to a grid line while dragging.
    private static final float GRID_MAGNET = 0.3f;
    // Fraction of one grid cell within which an axis sticks to an alignment guide (screen
    // centre, another element's centre/edges). Larger than the grid magnet on purpose: guides
    // are rare and meaningful, grid lines are everywhere.
    private static final float GUIDE_MAGNET = 0.6f;

    private EditorListener editorListener;

    // Touchscreen control haptics (see TouchHaptics). The switch is re-read at the start of
    // every touch event; the default matches what the in-game sidebar shows (off).
    private TouchHaptics touchHaptics;
    private boolean touchHapticsEnabled = false;
    private EditorCanvasColors canvasColors;
    private int touchSlop;
    private int activeEditPointerId = -1;
    private float downTouchX, downTouchY;
    private boolean draggingElement = false;
    private int dragStartX, dragStartY;
    // Live (unsnapped-to-int) crosshair position; `cursor` keeps the settled grid node that
    // addElement() uses.
    private float cursorX, cursorY;
    // Alignment guide currently being snapped to, per axis (NaN = none). Drawn as accent lines.
    private float guideLineX = Float.NaN, guideLineY = Float.NaN;
    private ValueAnimator elementSettleAnimator;
    private ValueAnimator cursorAnimator;
    private Runnable undoAction;

    // Pinch-to-scale of the selected element (second finger down while it is held).
    private static final float MIN_ELEMENT_SCALE = 0.5f;
    private static final float MAX_ELEMENT_SCALE = 2.0f;
    private boolean pinching = false;
    // After a pinch the rest of the gesture is ignored until every finger is up, so the
    // remaining finger doesn't start dragging the element it was just scaling.
    private boolean gestureConsumed = false;
    private int pinchPointerId = -1;
    private float pinchStartDistance;
    private float pinchStartScale;
    private boolean elementGestureActive = false;
    private ControlsProfile profile;
    private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
    private TouchpadView touchpadView;
    private XServer xServer;
    private final android.util.SparseArray<Bitmap> icons = new android.util.SparseArray<>();
    private Timer mouseMoveTimer;
    private final PointF mouseMoveOffset = new PointF();
    // Full deflection at 100% Stick Mouse Speed = 10 px per 60 Hz tick (600 px/s), as before.
    private static final float STICK_MOUSE_PIXELS_PER_TICK = 10f;
    private boolean showTouchscreenControls = true;
    private int activeTouchPointerCount = 0;

    private Handler timeoutHandler; // Reference to the activity's timeout handler
    private Runnable hideControlsRunnable; // Runnable to hide the controls

    private SharedPreferences preferences;

    private ControlElement stickElement;

    private boolean focusOnStick = false; // A flag to determine if we are focusing on the stick

    public boolean isFocusedOnStick() {
        return focusOnStick;
    }

    public void setFocusOnStick(boolean focus) {
        this.focusOnStick = focus;
        invalidate(); // Redraw the view with the new focus setting
    }



    @SuppressLint("ResourceType")
    public InputControlsView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus(); // Add this line to request focus
        setBackgroundColor(0x00000000);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        preferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());
    }

    @SuppressLint("ResourceType")
    public InputControlsView(Context context, Handler timeoutHandler, Runnable hideControlsRunnable) {
        super(context);
        this.timeoutHandler = timeoutHandler; // Store the reference to timeout handler
        this.hideControlsRunnable = hideControlsRunnable; // Store the reference to the hide controls runnable
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus(); // Add this line to request focus
        setBackgroundColor(0x00000000);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        preferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());
    }

    public InputControlsView(Context context, boolean focusOnStick) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus(); // Add this line to request focus
        setBackgroundColor(0x00000000);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));

        // If focusOnStick is true, adjust the layout params to match the stick element size
        if (focusOnStick) {
            setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        preferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());
    }


    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        if (editMode) {
            canvasColors = EditorCanvasColors.resolve(getContext());
            touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        }
    }

    public void setEditorListener(EditorListener listener) {
        this.editorListener = listener;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
    }

    public float getOverlayOpacity() {
        return overlayOpacity;
    }

    public void invalidateElement(Rect rect) {
        if (rect == null) {
            invalidate();
            return;
        }
        invalidate(rect.left, rect.top, rect.right, rect.bottom);
    }

    public int getSnappingSize() {
        return snappingSize;
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width, height;

        if (stickElement != null && isFocusedOnStick()) {
            // If focusing on the stick, set width and height to the stick's bounding box size
            Rect boundingBox = stickElement.getBoundingBox();
            width = boundingBox.width();
            height = boundingBox.height();
        } else {
            // Default behavior for full screen
            width = getWidth();
            height = getHeight();
        }

        if (width == 0 || height == 0) {
            readyToDraw = false;
            return;
        }

        snappingSize = width / 100;
        readyToDraw = true;

        if (editMode) {
            drawGrid(canvas);
            drawCursor(canvas);
        }

        if (stickElement != null) {
            // Draw only the stick element if focus mode is active
            stickElement.draw(canvas);
        }

        if (profile != null && showTouchscreenControls && !isFocusedOnStick()) {
            if (!profile.isElementsLoaded()) profile.loadElements(this);
            if (editMode) drawDynamicZones(canvas);

            for (ControlElement element : profile.getElements()) {
                element.draw(canvas);
            }
        }

        if (editMode) drawGuides(canvas);

        super.onDraw(canvas);
    }


    public void resetStickPosition() {
        if (stickElement != null) {
            Rect boundingBox = stickElement.getBoundingBox();
            float centerX = boundingBox.centerX();
            float centerY = boundingBox.centerY();

            stickElement.setCurrentPosition(centerX, centerY); // Reset to the center of the bounding box
            invalidate(); // Redraw the stick in the centered position
        }
    }



    public void initializeStickElement(float x, float y, float scale) {
        stickElement = new ControlElement(this);
        stickElement.setType(ControlElement.Type.STICK); // Set type to STICK
        stickElement.setX((int) x);
        stickElement.setY((int) y);
        stickElement.setScale(scale);
        invalidate(); // Force the view to redraw with the stick
    }


    public void updateStickPosition(float x, float y) {
        if (stickElement != null) {
            stickElement.getCurrentPosition().x = x;  // Update the thumbstick's position
            stickElement.getCurrentPosition().y = y;  // Update the thumbstick's position
            invalidate(); // Redraw the view
        }
    }


    public ControlElement getStickElement() {
        return stickElement;
    }

    private void drawGrid(Canvas canvas) {
        EditorCanvasColors colors = canvasColors != null ? canvasColors : EditorCanvasColors.resolve(getContext());
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        canvas.drawColor(colors.background);

        paint.setAntiAlias(false);
        paint.setColor(colors.gridLine);

        int width = getMaxWidth();
        int height = getMaxHeight();

        for (int i = 0; i <= width; i += snappingSize) canvas.drawLine(i, 0, i, height, paint);
        for (int i = 0; i <= height; i += snappingSize) canvas.drawLine(0, i, width, i, paint);

        float cx = Mathf.roundTo(width * 0.5f, snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, snappingSize);
        paint.setColor(colors.gridCenter);

        for (int i = 0; i < height; i += snappingSize * 2) canvas.drawLine(cx, i, cx, i + snappingSize, paint);
        for (int i = 0; i < width; i += snappingSize * 2) canvas.drawLine(i, cy, i + snappingSize, cy, paint);

        paint.setAntiAlias(true);
    }

    private void drawCursor(Canvas canvas) {
        EditorCanvasColors colors = canvasColors != null ? canvasColors : EditorCanvasColors.resolve(getContext());
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(colors.cursor);

        paint.setAntiAlias(false);
        canvas.drawLine(0, cursorY, getMaxWidth(), cursorY, paint);
        canvas.drawLine(cursorX, 0, cursorX, getMaxHeight(), paint);

        paint.setAntiAlias(true);
    }

    // Editor only: the zone of every dynamic stick as a dashed square (stronger when selected),
    // under the controls. Never drawn in game.
    private final DashPathEffect zoneDash = new DashPathEffect(new float[]{12f, 10f}, 0f);

    private void drawDynamicZones(Canvas canvas) {
        if (profile == null) return;
        EditorCanvasColors colors = canvasColors != null ? canvasColors : EditorCanvasColors.resolve(getContext());
        for (ControlElement element : profile.getElements()) {
            if (!element.isDynamicStick()) continue;
            RectF zone = element.getDynamicZone();
            boolean selected = element == selectedElement;

            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((colors.guide & 0x00FFFFFF) | ((selected ? 0x22 : 0x10) << 24));
            canvas.drawRect(zone, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, snappingSize * 0.1f));
            paint.setColor((colors.guide & 0x00FFFFFF) | ((selected ? 0xCC : 0x66) << 24));
            paint.setPathEffect(zoneDash);
            canvas.drawRect(zone, paint);
        }
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawGuides(Canvas canvas) {
        if (Float.isNaN(guideLineX) && Float.isNaN(guideLineY)) return;
        EditorCanvasColors colors = canvasColors != null ? canvasColors : EditorCanvasColors.resolve(getContext());
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(Math.max(2f, snappingSize * 0.125f));
        paint.setColor(colors.guide);
        if (!Float.isNaN(guideLineX)) canvas.drawLine(guideLineX, 0, guideLineX, getHeight(), paint);
        if (!Float.isNaN(guideLineY)) canvas.drawLine(0, guideLineY, getWidth(), guideLineY, paint);
    }

    public synchronized boolean addElement() {
        if (editMode && profile != null) {
            finishPendingAnimations();
            ControlElement element = new ControlElement(this);
            element.setX(cursor.x);
            element.setY(cursor.y);
            profile.addElement(element);
            profile.save();
            selectElement(element);
            setUndo(() -> removeWithoutUndo(element));
            notifyElementsChanged();
            return true;
        }
        else return false;
    }

    public synchronized boolean removeElement() {
        if (editMode && selectedElement != null && profile != null) {
            finishPendingAnimations();
            final ControlElement removed = selectedElement;
            final int index = profile.getElements().indexOf(removed);
            profile.removeElement(removed);
            selectElement(null);
            profile.save();
            invalidate();
            setUndo(() -> {
                profile.addElementAt(index, removed);
                profile.save();
                selectElement(removed);
                notifyElementsChanged();
            });
            return true;
        }
        else return false;
    }

    // Copy of the selected element (same JSON round-trip the profile loader uses, so every
    // field comes along), dropped a few grid cells down-right of the original and selected.
    public synchronized boolean duplicateElement() {
        if (!editMode || selectedElement == null || profile == null || snappingSize <= 0) return false;
        finishPendingAnimations();
        ControlElement copy = ControlsProfile.elementFromJSON(selectedElement.toJSONObject(), this);
        if (copy == null) return false;
        int step = snappingSize * 4;
        copy.setX(clampInt(selectedElement.getX() + step, 0, getMaxWidth()));
        copy.setY(clampInt(selectedElement.getY() + step, 0, getMaxHeight()));
        profile.addElement(copy);
        profile.save();
        selectElement(copy);
        setUndo(() -> removeWithoutUndo(copy));
        notifyElementsChanged();
        return true;
    }

    public synchronized boolean undo() {
        if (!editMode || undoAction == null) return false;
        finishPendingAnimations();
        Runnable action = undoAction;
        setUndo(null);
        action.run();
        invalidate();
        return true;
    }

    public boolean canUndo() {
        return undoAction != null;
    }

    // Records the element's current state as the one-step undo, for edits made outside the
    // canvas (the settings panel). Undo swaps in a fresh copy built from the snapshot — the same
    // JSON round-trip the profile loader uses — at the same list index, with the exact position.
    public synchronized void recordElementSnapshot(final ControlElement element) {
        if (element == null || profile == null) return;
        final JSONObject snapshot = element.toJSONObject();
        if (snapshot == null) return;
        final int x = element.getX();
        final int y = element.getY();
        setUndo(() -> {
            int index = profile.getElements().indexOf(element);
            if (index < 0) return;
            ControlElement restored = ControlsProfile.elementFromJSON(snapshot, this);
            if (restored == null) return;
            restored.setX(x);
            restored.setY(y);
            profile.removeElement(element);
            profile.addElementAt(index, restored);
            profile.save();
            selectElement(restored);
            notifyElementsChanged();
        });
    }

    private void removeWithoutUndo(ControlElement element) {
        profile.removeElement(element);
        if (selectedElement == element) selectElement(null);
        profile.save();
        notifyElementsChanged();
    }

    private void setUndo(Runnable action) {
        boolean before = undoAction != null;
        undoAction = action;
        if (editorListener != null && before != (action != null)) {
            editorListener.onUndoAvailabilityChanged(action != null);
        }
    }

    private void notifyElementsChanged() {
        if (editorListener != null) editorListener.onElementsChanged();
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public ControlElement getSelectedElement() {
        return selectedElement;
    }

    private synchronized void deselectAllElements() {
        selectedElement = null;
        if (profile != null) {
            for (ControlElement element : profile.getElements()) element.setSelected(false);
        }
    }

    private void selectElement(ControlElement element) {
        ControlElement previous = selectedElement;
        deselectAllElements();
        if (element != null) {
            selectedElement = element;
            selectedElement.setSelected(true);
        }
        invalidate();
        if (editorListener != null && previous != element) editorListener.onSelectionChanged(element);
    }

    public synchronized ControlsProfile getProfile() {
        return profile;
    }

    public synchronized void setProfile(ControlsProfile profile) {
        ControlElement previousSelection = selectedElement;
        if (editMode) {
            // Switching profiles inside the editor: land any glide on the old profile first,
            // and drop the undo step — it points at the old profile's elements.
            finishPendingAnimations();
            setUndo(null);
            guideLineX = Float.NaN;
            guideLineY = Float.NaN;
        }
        if (profile != null) {
            this.profile = profile;
            deselectAllElements();
        }
        else this.profile = null;
        selectedElement = null;
        if (editMode) {
            invalidate();
            if (previousSelection != null && editorListener != null) editorListener.onSelectionChanged(null);
        }
    }

    public boolean isShowTouchscreenControls() {
        return showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        this.showTouchscreenControls = showTouchscreenControls;
    }

    public int getPrimaryColor() {
        // Kept for compatibility with ControlElement; visual style now derives from secondary blue.
        return Color.argb((int)(overlayOpacity * 255), 255, 255, 255);
    }

    public int getSecondaryColor() {
        // Winlator-like electric blue used by the app UI. Alpha is handled per primitive.
        return Color.argb(255, 33, 132, 255);
    }

    private synchronized ControlElement intersectElement(float x, float y) {
        if (profile != null) {
            for (ControlElement element : profile.getElements()) {
                if (element.containsPoint(x, y)) return element;
            }
        }
        return null;
    }

    public Paint getPaint() {
        return paint;
    }

    public Path getPath() {
        return path;
    }

    public ColorFilter getColorFilter() {
        return colorFilter;
    }

    public TouchpadView getTouchpadView() {
        return touchpadView;
    }

    public void setTouchpadView(TouchpadView touchpadView) {
        this.touchpadView = touchpadView;
    }

    public XServer getXServer() {
        return xServer;
    }

    public void setXServer(XServer xServer) {
        this.xServer = xServer;
        createMouseMoveTimer();
    }

    public int getMaxWidth() {
        return (int)Mathf.roundTo(getWidth(), snappingSize);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mouseMoveTimer != null)
            mouseMoveTimer.cancel();
        super.onDetachedFromWindow();
    }

    public int getMaxHeight() {
        return (int)Mathf.roundTo(getHeight(), snappingSize);
    }

    private void createMouseMoveTimer() {
        WinHandler winHandler = xServer.getWinHandler();
        if (mouseMoveTimer == null && profile != null) {
            mouseMoveTimer = new Timer();
            mouseMoveTimer.schedule(new TimerTask() {
                // Only touched from this timer thread.
                private final MotionAccumulator motion = new MotionAccumulator();

                @Override
                public void run() {
                    float offsetX = mouseMoveOffset.x;
                    float offsetY = mouseMoveOffset.y;
                    if (offsetX == 0 && offsetY == 0) {
                        motion.reset();
                        return;
                    }
                    // Stick Mouse Speed is read every tick (it used to be frozen when the timer
                    // was first created, so switching profiles kept the old speed), and the
                    // remainder is carried instead of truncated, which used to swallow small
                    // deflections entirely.
                    ControlsProfile activeProfile = profile;
                    float speed = activeProfile != null ? activeProfile.getCursorSpeed() : 1.0f;
                    motion.add(offsetX * speed * STICK_MOUSE_PIXELS_PER_TICK, offsetY * speed * STICK_MOUSE_PIXELS_PER_TICK);
                    int dx = motion.x();
                    int dy = motion.y();
                    if (dx == 0 && dy == 0) return;
                    if (xServer.isRelativeMouseMovement())
                        winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                    else
                        xServer.injectPointerMoveDelta(dx, dy);
                }
            }, 0, 1000 / 60); // 60 FPS
        }
    }

    private void processJoystickInput(ExternalController controller) {
        final int[] axes = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        };
        final float[] values = {
                controller.state.thumbLX, controller.state.thumbLY,
                controller.state.thumbRX, controller.state.thumbRY,
                controller.state.getDPadX(), controller.state.getDPadY()
        };

        for (int i = 0; i < axes.length; i++) {
            float value = values[i];
            if (Math.abs(value) > ControlElement.STICK_DEAD_ZONE) {
                byte sign = Mathf.sign(value);
                int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
                ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
                if (controllerBinding != null) {
                    handleInputEvent(controller, controllerBinding.getBinding(), true, value, false);
                }
            } else {
                // Handle releasing the bindings when the axis returns to deadzone
                for (byte sign = -1; sign <= 1; sign += 2) {
                    int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
                    ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
                    if (controllerBinding != null) {
                        handleInputEvent(controller, controllerBinding.getBinding(), false, value, false);
                    }
                }
            }
        }

        // Handle Analog Triggers (L2/R2)
        // We use the binding for the digital button (e.g. KEYCODE_BUTTON_L2) to determing where to map the analog value
        processTriggerInput(controller, controller.state.triggerL, KeyEvent.KEYCODE_BUTTON_L2, false);
        processTriggerInput(controller, controller.state.triggerR, KeyEvent.KEYCODE_BUTTON_R2, false);

        // Send the updated state once after processing all axes
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (winHandler != null) {
            winHandler.sendGamepadState(controller);
        }
    }

    private void processTriggerInput(ExternalController controller, float value, int keyCode, boolean sendUpdate) {
        ExternalControllerBinding binding = controller.getControllerBinding(keyCode);
        if (binding != null) {
            boolean isPressed = value > ControlElement.STICK_DEAD_ZONE; // Use deadzone or simple > 0
            if (isPressed) {
                handleInputEvent(controller, binding.getBinding(), true, value, sendUpdate);
            } else {
                handleInputEvent(controller, binding.getBinding(), false, 0, sendUpdate);
            }
        }
    }




    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        Log.d("InputControlsView", "dispatchGenericMotionEvent called. Source: " + event.getSource());
        return super.dispatchGenericMotionEvent(event);
    }


    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {

        Log.d("InputControlsView", "Motion event received. Source: " + event.getSource());
        Log.d("InputControlsView", "Device ID: " + event.getDeviceId());
        Log.d("InputControlsView", "Profile is " + (profile != null ? "set" : "null"));


        if (!editMode && profile != null) {
            // Retrieve the associated controller for this event
            ExternalController controller = profile.getController(event.getDeviceId());

            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                // Process L2 and R2 button bindings
                ExternalControllerBinding controllerBinding;

                // L2 button
                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2);
                if (controllerBinding != null) {
                    handleInputEvent(controller, controllerBinding.getBinding(), controller.state.isPressed(ExternalController.IDX_BUTTON_L2));
                }

                // R2 button
                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2);
                if (controllerBinding != null) {
                    handleInputEvent(controller, controllerBinding.getBinding(), controller.state.isPressed(ExternalController.IDX_BUTTON_R2));
                }

                Log.d("InputEvent", "Event source: " + event.getSource());
                Log.d("InputEvent", "Device ID: " + event.getDeviceId());
                Log.d("InputEvent", "Action: " + event.getAction());

                // Process joystick inputs for mouse movement and other bindings
                processJoystickInput(controller);

                // Return true to indicate the motion event was handled
                return true;
            }
        }

        // Pass the event to the super method if not handled
        return super.onGenericMotionEvent(event);
    }


    @Override
    public boolean onTouchEvent(MotionEvent event) {

        boolean hapticsEnabled = preferences.getBoolean("touchscreen_haptics_enabled", false);
        touchHapticsEnabled = hapticsEnabled && !editMode;

        // Do not let the auto-hide runnable hide controls while a finger is still down.
        // This fixes controls disappearing under load or while holding a stick/button.
        updateTouchscreenTimeout(event);

        if (editMode && readyToDraw) {
            handleEditTouch(event);
        }

        if (!editMode && profile != null) {
            int actionIndex = event.getActionIndex();
            int pointerId = event.getPointerId(actionIndex);
            int actionMasked = event.getActionMasked();
            boolean handled = false;

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);

                    touchpadView.setPointerButtonLeftEnabled(true);
                    boolean hapticPlayed = false;
                    for (ControlElement element : profile.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) {
                            handled = true;
                            // One pulse per touch even if overlapping elements both took it.
                            // D-pads report their own (per direction) from ControlElement.
                            if (!hapticPlayed) {
                                int kind = element.getTouchDownHaptic();
                                if (kind != TouchHaptics.NONE) {
                                    playTouchHaptic(kind);
                                    hapticPlayed = true;
                                }
                            }
                        }
                        if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON) {
                            touchpadView.setPointerButtonLeftEnabled(false);
                        }
                    }
                    // Second pass: only a touch nobody took by their own bounds may spawn a
                    // dynamic stick from its zone — buttons inside a zone keep priority.
                    if (!handled) {
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleDynamicZoneTouchDown(pointerId, x, y)) {
                                handled = true;
                                // No haptic: sticks are silent by design.
                                break;
                            }
                        }
                    }
                    if (!handled) forwardToTouchpad(event);
                    else syncCapturedPointers();
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    // The touchpad gets the whole event at most once (it used to receive it once
                    // per unhandled pointer, so e.g. a two-finger scroll was processed twice).
                    boolean anyUnhandled = false;
                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);
                        int pid = event.getPointerId(i);

                        boolean pointerHandled = trySwipe(pid, x, y);
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(pid, x, y)) pointerHandled = true;
                        }
                        if (!pointerHandled) anyUnhandled = true;
                    }
                    if (anyUnhandled) forwardToTouchpad(event);
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    for (ControlElement element : profile.getElements()) if (element.handleTouchUp(pointerId)) handled = true;
                    // A cancel always reaches the touchpad too, so it can drop its fingers/buttons.
                    if (!handled || actionMasked == MotionEvent.ACTION_CANCEL) forwardToTouchpad(event);
                    else syncCapturedPointers();
                    break;
            }
        }
        return true;
    }





    private void updateTouchscreenTimeout(MotionEvent event) {
        if (timeoutHandler == null || hideControlsRunnable == null) return;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeTouchPointerCount = 1;
                timeoutHandler.removeCallbacks(hideControlsRunnable);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                activeTouchPointerCount = event.getPointerCount();
                timeoutHandler.removeCallbacks(hideControlsRunnable);
                break;
            case MotionEvent.ACTION_MOVE:
                if (activeTouchPointerCount > 0) {
                    timeoutHandler.removeCallbacks(hideControlsRunnable);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                activeTouchPointerCount = Math.max(0, event.getPointerCount() - 1);
                if (activeTouchPointerCount > 0) {
                    timeoutHandler.removeCallbacks(hideControlsRunnable);
                }
                else {
                    scheduleTouchscreenTimeout();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeTouchPointerCount = 0;
                scheduleTouchscreenTimeout();
                break;
        }
    }

    private void scheduleTouchscreenTimeout() {
        if (!AUTO_HIDE_CONTROLS) {
            if (timeoutHandler != null && hideControlsRunnable != null) {
                timeoutHandler.removeCallbacks(hideControlsRunnable);
            }
            return;
        }
        if (timeoutHandler == null || hideControlsRunnable == null) return;
        timeoutHandler.removeCallbacks(hideControlsRunnable);
        timeoutHandler.postDelayed(hideControlsRunnable, 5000);
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (profile != null && event.getRepeatCount() == 0) {
            ExternalController controller = profile.getController(event.getDeviceId());
            
            if (controller != null) {
                ExternalControllerBinding controllerBinding = controller.getControllerBinding(event.getKeyCode());
                
                if (controllerBinding != null) {
                    int action = event.getAction();

                    if (action == KeyEvent.ACTION_DOWN) {
                        handleInputEvent(controller, controllerBinding.getBinding(), true);
                    }
                    else if (action == KeyEvent.ACTION_UP) {
                        handleInputEvent(controller, controllerBinding.getBinding(), false);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) {
        handleInputEvent(null, binding, isActionDown, 0);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown) {
        handleInputEvent(controller, binding, isActionDown, 0);
    }

    /**
     * Handle stick input with proper 2D axis management.
     * Use this for analog sticks to avoid per-direction axis conflicts.
     */
    public void handleStickInput(Binding firstBinding, float deltaX, float deltaY) {
        if (!firstBinding.isGamepad()) return;
        
        GamepadState state = profile.getGamepadState();
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        
        // Determine which stick this is based on the first binding
        boolean isLeftStick = firstBinding == Binding.GAMEPAD_LEFT_THUMB_UP || 
                             firstBinding == Binding.GAMEPAD_LEFT_THUMB_DOWN ||
                             firstBinding == Binding.GAMEPAD_LEFT_THUMB_LEFT ||
                             firstBinding == Binding.GAMEPAD_LEFT_THUMB_RIGHT;
        
        if (isLeftStick) {
            state.thumbLX = deltaX;
            state.thumbLY = deltaY;
        } else {
            state.thumbRX = deltaX;
            state.thumbRY = deltaY;
        }
        
        if (winHandler != null) {
            winHandler.sendGamepadState();
        }
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(null, binding, isActionDown, offset);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(controller, binding, isActionDown, offset, true);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown, float offset, boolean sendUpdate) {
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (binding.isGamepad()) {
            GamepadState state = (controller != null) ? controller.remappedState : profile.getGamepadState();

            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= ExternalController.IDX_BUTTON_R2) {
                if (buttonIdx == ExternalController.IDX_BUTTON_L2)
                    state.triggerL = isActionDown ? (offset != 0 ? offset : 1.0f) : 0f;
                else if (buttonIdx == ExternalController.IDX_BUTTON_R2)
                    state.triggerR = isActionDown ? (offset != 0 ? offset : 1.0f) : 0f;
                else
                    state.setPressed(buttonIdx, isActionDown);
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                state.thumbLY = isActionDown ? (binding == Binding.GAMEPAD_LEFT_THUMB_UP ? -val : val) : 0;
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                state.thumbLX = isActionDown ? (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT ? -val : val) : 0;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                state.thumbRY = isActionDown ? (binding == Binding.GAMEPAD_RIGHT_THUMB_UP ? -val : val) : 0;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                state.thumbRX = isActionDown ? (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT ? -val : val) : 0;
            }
            else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT ||
                     binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT) {
                state.dpad[binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal()] = isActionDown;
            }

            if (winHandler != null && sendUpdate) {
                if (controller != null)
                    winHandler.sendGamepadState(controller);
                else
                    winHandler.sendGamepadState();
            }
        }
        else {
            if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                mouseMoveOffset.x = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_LEFT ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                mouseMoveOffset.y = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_UP ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else {
                Pointer.Button pointerButton = binding.getPointerButton();
                if (isActionDown) {
                    if (pointerButton != null) {
                        if (xServer.isRelativeMouseMovement()) {
                            int wheelDelta = pointerButton == Pointer.Button.BUTTON_SCROLL_UP ? MOUSE_WHEEL_DELTA : (pointerButton == Pointer.Button.BUTTON_SCROLL_DOWN ? -MOUSE_WHEEL_DELTA : 0);
                            winHandler.mouseEvent(MouseEventFlags.getFlagFor(pointerButton, true), 0, 0, wheelDelta);
                        } else {
                            xServer.injectPointerButtonPress(pointerButton);
                        }
                    }
                    else xServer.injectKeyPress(binding.keycode);
                }
                else {
                    if (pointerButton != null) {
                        if (xServer.isRelativeMouseMovement()) {
                            winHandler.mouseEvent(MouseEventFlags.getFlagFor(pointerButton, false), 0, 0, 0);
                        } else {
                            xServer.injectPointerButtonRelease(pointerButton);
                        }
                    }
                    else xServer.injectKeyRelease(binding.keycode);
                }
            }
        }
    }


    public void invalidateIconCache() {
        icons.clear();
    }

    public Bitmap getIcon(byte id) {
        if (id < 0) return null;
        Bitmap cached = icons.get(id);
        if (cached == null) {
            File overrideFile = new File(
                android.os.Environment.getExternalStorageDirectory(),
                "winlator/custom_icons/override_" + id + ".png"
            );
            if (overrideFile.exists()) {
                cached = BitmapFactory.decodeFile(overrideFile.getAbsolutePath());
                if (cached != null) {
                    android.util.Log.i("Icons", "Using custom override for built-in icon " + id);
                    icons.put(id, cached);
                    return cached;
                }
            }
            Context context = getContext();
            try (InputStream is = context.getAssets().open("inputcontrols/icons/"+id+".png")) {
                cached = BitmapFactory.decodeStream(is);
                if (cached != null) icons.put(id, cached);
                else android.util.Log.w("Icons", "Built-in icon " + id + " decoded as null");
            }
            catch (IOException e) {
                android.util.Log.w("Icons", "Built-in icon " + id + " not in assets: " + e.getMessage());
            }
        }
        return cached;
    }
    // A finger holding a swipe-enabled control that has slid off it onto another swipe-enabled,
    // currently free control: release the first and press the second, without lifting. Both
    // ends must have Swipeable on (off by default), so existing layouts behave exactly as before.
    // Tells the touchpad which pointers are held by on-screen controls, so those fingers never
    // count as trackpad/touchscreen fingers (e.g. a finger on a stick becoming "the" touch).
    private final java.util.HashSet<Integer> capturedPointerIds = new java.util.HashSet<>();

    private void syncCapturedPointers() {
        if (touchpadView == null) return;
        capturedPointerIds.clear();
        if (profile != null) {
            for (ControlElement element : profile.getElements()) {
                int id = element.getCurrentPointerId();
                if (id != -1) capturedPointerIds.add(id);
            }
        }
        touchpadView.setPointerIdsToIgnore(capturedPointerIds);
    }

    private void forwardToTouchpad(MotionEvent event) {
        syncCapturedPointers();
        touchpadView.onTouchEvent(event);
    }

    private boolean trySwipe(int pointerId, float x, float y) {
        ControlElement source = null;
        for (ControlElement element : profile.getElements()) {
            if (element.isCapturing(pointerId)) {
                source = element;
                break;
            }
        }
        if (source == null || !source.isSwipeEnabled() || source.containsPoint(x, y)) return false;

        for (ControlElement target : profile.getElements()) {
            if (target == source || !target.isSwipeEnabled() || target.isCapturingAnyPointer() || !target.containsPoint(x, y)) continue;
            source.handleTouchUp(pointerId);
            if (target.handleTouchDown(pointerId, x, y)) {
                playTouchHaptic(target.getTouchDownHaptic());
            }
            return true;
        }
        return false;
    }

    // Called by the touch code here and by ControlElement (D-pad direction changes). No-op in
    // the editor and when the sidebar's touchscreen vibration is off.
    public void playTouchHaptic(int kind) {
        if (!touchHapticsEnabled || kind == TouchHaptics.NONE) return;
        if (touchHaptics == null) touchHaptics = new TouchHaptics(getContext());
        touchHaptics.play(kind);
    }

    // ======================= Edit-mode touch: drag, magnets, crosshair =======================

    private void handleEditTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                finishPendingAnimations();
                gestureConsumed = false;
                pinching = false;
                activeEditPointerId = event.getPointerId(0);
                float x = event.getX();
                float y = event.getY();
                downTouchX = x;
                downTouchY = y;
                draggingElement = false;

                ControlElement element = intersectElement(x, y);
                moveCursor = element == null;
                if (element != null) {
                    offsetX = x - element.getX();
                    offsetY = y - element.getY();
                    dragStartX = element.getX();
                    dragStartY = element.getY();
                }
                else {
                    // Crosshair glides to the tap point instead of teleporting there.
                    animateCursorTo(x, y, null);
                }
                selectElement(element);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (gestureConsumed || pinching || moveCursor || selectedElement == null) break;
                if (draggingElement) {
                    // Land the drag first (glide jumps to its end, position is saved).
                    draggingElement = false;
                    settleElement(selectedElement);
                    finishPendingAnimations();
                }
                int newIndex = event.getActionIndex();
                int firstIndex = event.findPointerIndex(activeEditPointerId);
                if (firstIndex < 0) break;
                float distance = (float) Math.hypot(event.getX(newIndex) - event.getX(firstIndex), event.getY(newIndex) - event.getY(firstIndex));
                if (distance < touchSlop) break;
                recordElementSnapshot(selectedElement);
                pinching = true;
                pinchPointerId = event.getPointerId(newIndex);
                pinchStartDistance = distance;
                pinchStartScale = selectedElement.getScale();
                setElementGestureActive(true);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (gestureConsumed) break;
                if (pinching) {
                    updatePinch(event);
                    break;
                }
                int index = event.findPointerIndex(activeEditPointerId);
                if (index < 0) break;
                float x = event.getX(index);
                float y = event.getY(index);

                if (selectedElement != null && !moveCursor) {
                    if (!draggingElement) {
                        if (Math.hypot(x - downTouchX, y - downTouchY) < touchSlop) break;
                        // Re-anchor at the moment the drag starts so the element doesn't jump
                        // by the slop distance.
                        draggingElement = true;
                        offsetX = x - selectedElement.getX();
                        offsetY = y - selectedElement.getY();
                        setElementGestureActive(true);
                    }
                    dragElementTo(x - offsetX, y - offsetY);
                }
                else if (moveCursor) {
                    if (cursorAnimator != null) cursorAnimator.cancel();
                    cursorX = x;
                    cursorY = y;
                    invalidate();
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int liftedId = event.getPointerId(event.getActionIndex());
                if (pinching && (liftedId == pinchPointerId || liftedId == activeEditPointerId)) {
                    endPinch();
                    break;
                }
                if (gestureConsumed) break;
                // A second finger lifting doesn't end the gesture; the first one lifting does.
                if (liftedId == activeEditPointerId) endEditGesture(event, event.getActionIndex());
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (pinching) endPinch();
                if (gestureConsumed) {
                    gestureConsumed = false;
                    activeEditPointerId = -1;
                    break;
                }
                int index = event.findPointerIndex(activeEditPointerId);
                endEditGesture(event, Math.max(index, 0));
                break;
            }
        }
    }

    private void endEditGesture(MotionEvent event, int pointerIndex) {
        if (activeEditPointerId == -1) return;
        activeEditPointerId = -1;

        if (selectedElement != null && draggingElement) {
            draggingElement = false;
            setElementGestureActive(false);
            settleElement(selectedElement);
        }
        else if (moveCursor) {
            float x = event.getX(pointerIndex);
            float y = event.getY(pointerIndex);
            float targetX = Mathf.roundTo(x, snappingSize);
            float targetY = Mathf.roundTo(y, snappingSize);
            cursor.set((int) targetX, (int) targetY);
            animateCursorTo(targetX, targetY, null);
        }
        invalidate();
    }

    private void updatePinch(MotionEvent event) {
        ControlElement element = selectedElement;
        int a = event.findPointerIndex(activeEditPointerId);
        int b = event.findPointerIndex(pinchPointerId);
        if (element == null || a < 0 || b < 0 || pinchStartDistance <= 0) return;
        float distance = (float) Math.hypot(event.getX(b) - event.getX(a), event.getY(b) - event.getY(a));
        float scale = pinchStartScale * distance / pinchStartDistance;
        // 5% steps, same granularity the settings slider effectively lands on.
        scale = Math.round(scale * 20f) / 20f;
        scale = Math.max(MIN_ELEMENT_SCALE, Math.min(MAX_ELEMENT_SCALE, scale));
        if (scale != element.getScale()) {
            element.setScale(scale);
            invalidate();
        }
    }

    private void endPinch() {
        pinching = false;
        pinchPointerId = -1;
        gestureConsumed = true;
        draggingElement = false;
        setElementGestureActive(false);
        if (profile != null) profile.save();
        invalidate();
        notifyElementsChanged();
    }

    private void setElementGestureActive(boolean active) {
        if (elementGestureActive == active) return;
        elementGestureActive = active;
        if (editorListener != null) editorListener.onElementGestureChanged(active);
    }

    private void dragElementTo(float rawX, float rawY) {
        ControlElement element = selectedElement;
        if (element == null || snappingSize <= 0) return;

        Rect box = element.getBoundingBox();
        float halfW = box.width() * 0.5f;
        float halfH = box.height() * 0.5f;

        float prevGuideX = guideLineX, prevGuideY = guideLineY;
        float[] snappedX = snapAxis(element, rawX, halfW, true);
        float[] snappedY = snapAxis(element, rawY, halfH, false);
        guideLineX = snappedX[1];
        guideLineY = snappedY[1];

        element.setX(Math.round(snappedX[0]));
        element.setY(Math.round(snappedY[0]));

        // Tick when a guide is newly acquired (not on grid lines — they're every cell and
        // would turn into a constant buzz).
        boolean newGuide = (!Float.isNaN(guideLineX) && guideLineX != prevGuideX)
                || (!Float.isNaN(guideLineY) && guideLineY != prevGuideY);
        if (newGuide) snapHaptic();

        invalidate();
    }

    // Returns {position, guideLine (NaN if the axis is on the grid or free)}.
    // Guides win over grid lines; either only applies within its magnet radius, otherwise the
    // axis follows the finger freely.
    private float[] snapAxis(ControlElement dragged, float value, float half, boolean horizontal) {
        float bestDistance = snappingSize * GUIDE_MAGNET;
        float bestPosition = Float.NaN;
        float bestLine = Float.NaN;

        float screenCenter = Mathf.roundTo((horizontal ? getMaxWidth() : getMaxHeight()) * 0.5f, snappingSize);
        float d = Math.abs(value - screenCenter);
        if (d <= bestDistance) {
            bestDistance = d;
            bestPosition = screenCenter;
            bestLine = screenCenter;
        }

        // Screen edges, one grid cell in: my start edge to the leading margin, my end edge to
        // the trailing one.
        float extent = horizontal ? getMaxWidth() : getMaxHeight();
        float edgeMargin = snappingSize;
        d = Math.abs((value - half) - edgeMargin);
        if (d < bestDistance) { bestDistance = d; bestPosition = edgeMargin + half; bestLine = edgeMargin; }
        d = Math.abs((value + half) - (extent - edgeMargin));
        if (d < bestDistance) { bestDistance = d; bestPosition = extent - edgeMargin - half; bestLine = extent - edgeMargin; }

        if (profile != null) {
            for (ControlElement other : profile.getElements()) {
                if (other == dragged) continue;
                Rect box = other.getBoundingBox();
                float center = horizontal ? other.getX() : other.getY();
                float start = horizontal ? box.left : box.top;
                float end = horizontal ? box.right : box.bottom;

                // centre ↔ centre
                d = Math.abs(value - center);
                if (d < bestDistance) { bestDistance = d; bestPosition = center; bestLine = center; }
                // my start edge ↔ their start edge
                d = Math.abs((value - half) - start);
                if (d < bestDistance) { bestDistance = d; bestPosition = start + half; bestLine = start; }
                // my end edge ↔ their end edge
                d = Math.abs((value + half) - end);
                if (d < bestDistance) { bestDistance = d; bestPosition = end - half; bestLine = end; }
            }
        }

        if (!Float.isNaN(bestPosition)) return new float[]{bestPosition, bestLine};

        float grid = Mathf.roundTo(value, snappingSize);
        if (Math.abs(value - grid) <= snappingSize * GRID_MAGNET) return new float[]{grid, Float.NaN};
        return new float[]{value, Float.NaN};
    }

    // On release: an axis held by a guide keeps it, a free axis glides to the nearest grid
    // line. The profile is saved (and undo recorded) only once the glide has finished.
    private void settleElement(final ControlElement element) {
        final float fromX = element.getX();
        final float fromY = element.getY();
        final float toX = Float.isNaN(guideLineX) ? Mathf.roundTo(fromX, snappingSize) : fromX;
        final float toY = Float.isNaN(guideLineY) ? Mathf.roundTo(fromY, snappingSize) : fromY;
        final int startX = dragStartX, startY = dragStartY;

        elementSettleAnimator = ValueAnimator.ofFloat(0f, 1f);
        elementSettleAnimator.setDuration(SETTLE_DURATION_MS);
        elementSettleAnimator.setInterpolator(new DecelerateInterpolator());
        elementSettleAnimator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            element.setX(Math.round(fromX + (toX - fromX) * t));
            element.setY(Math.round(fromY + (toY - fromY) * t));
            invalidate();
        });
        elementSettleAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean done = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                finish();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                finish();
            }

            private void finish() {
                if (done) return;
                done = true;
                element.setX(Math.round(toX));
                element.setY(Math.round(toY));
                guideLineX = Float.NaN;
                guideLineY = Float.NaN;
                elementSettleAnimator = null;
                if (profile != null) profile.save();
                if (element.getX() != startX || element.getY() != startY) {
                    setUndo(() -> {
                        element.setX(startX);
                        element.setY(startY);
                        profile.save();
                        selectElement(element);
                        notifyElementsChanged();
                    });
                }
                invalidate();
                notifyElementsChanged();
            }
        });
        elementSettleAnimator.start();
    }

    private void animateCursorTo(final float toX, final float toY, Runnable onEnd) {
        if (cursorAnimator != null) cursorAnimator.cancel();
        final float fromX = cursorX;
        final float fromY = cursorY;
        cursorAnimator = ValueAnimator.ofFloat(0f, 1f);
        cursorAnimator.setDuration(SETTLE_DURATION_MS);
        cursorAnimator.setInterpolator(new DecelerateInterpolator());
        cursorAnimator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            cursorX = fromX + (toX - fromX) * t;
            cursorY = fromY + (toY - fromY) * t;
            invalidate();
        });
        cursorAnimator.start();
    }

    // Jumps any running glide to its end state (saving the settled element) before a new
    // gesture or an add/remove/duplicate/undo acts on the canvas.
    private void finishPendingAnimations() {
        if (elementSettleAnimator != null) elementSettleAnimator.end();
        if (cursorAnimator != null) cursorAnimator.end();
    }

    private void snapHaptic() {
        int constant = Build.VERSION.SDK_INT >= 34
                ? HapticFeedbackConstants.SEGMENT_TICK
                : HapticFeedbackConstants.CLOCK_TICK;
        performHapticFeedback(constant);
    }
}
