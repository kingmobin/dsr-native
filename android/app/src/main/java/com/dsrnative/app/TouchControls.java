package com.dsrnative.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.Map;

/**
 * On-screen controls drawn in a transparent panel window over the game: a mobile-style HUD for the Switch controller
 * the game expects. A stick at the bottom left moves; the right half of the screen (except the buttons) turns the
 * camera with a stick that appears under the thumb; the face buttons sit in an arc under the right thumb; the shoulder
 * buttons, d-pad and the rest are smaller and dimmer. What each button presses is fixed (HudModel.SPECS, the nn::hid
 * bits); only how it looks and where it sits changes.
 *
 * They hide when a game controller is connected or used, and come back with a double tap on the screen (or when the
 * last controller goes away). State goes to native code (nativeState), which merges it with the controller.
 *
 * Every element (HudModel.GROUPS) can be moved, resized, faded and hidden in the HUD editor (editLayout, from the
 * settings menu). The layout is saved in settings.txt `touch_layout` (format: see HudModel). Appearance settings
 * (button and stick size, spacing, dead zone, outlines, labels, floating stick) are `touch_*` keys too.
 */
public final class TouchControls extends View implements InputManager.InputDeviceListener {
    static native void nativeState(int buttons, float lx, float ly, float rx, float ry);
    static native void nativeVisible(boolean visible);

    private static TouchControls sView;
    /** settings.txt touch_camera_recenter: a thumb resting on the camera stick brings it back to the centre. */
    static volatile boolean sRecenter = true;
    /** What the player customised (settings.txt touch_layout). */
    private static volatile HudModel.Layout sLayout = new HudModel.Layout();

    /** Appearance settings (settings.txt touch_outline, touch_labels, touch_deadzone, touch_stick_float, touch_size, ...). */
    static final class Style {
        boolean outline = true, labels = true, floating;
        float deadzone = 0.08f, size = 1f, stickSize = 1f, spacing = 1f;
    }

    private static volatile Style sStyle = new Style();

    private static float num(String s, float def, float min, float max) {
        if (s == null) return def;
        try {
            float f = Float.parseFloat(s.trim());
            return Float.isFinite(f) ? Math.max(min, Math.min(max, f)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** The appearance settings from settings.txt (missing ones keep their defaults): applied to the controls at once. */
    static void setAppearance(Map<String, String> v) {
        Style s = new Style();
        s.outline = !"0".equals(v.get("touch_outline"));
        s.labels = !"0".equals(v.get("touch_labels"));
        s.floating = "1".equals(v.get("touch_stick_float"));
        s.deadzone = num(v.get("touch_deadzone"), 0.08f, 0f, 0.4f);
        s.size = num(v.get("touch_size"), 1f, 0.5f, 2f);
        s.stickSize = num(v.get("touch_stick_size"), 1f, 0.5f, 2f);
        s.spacing = num(v.get("touch_spacing"), 1f, 0.6f, 1.6f);
        sStyle = s;
        final TouchControls view = sView;
        if (view != null) view.post(view::relayout);
    }

    /** The saved layout (settings.txt touch_layout): applied to the controls at once. */
    static void setLayout(String s) {
        sLayout = HudModel.parse(s);
        final TouchControls v = sView;
        if (v != null) v.post(v::relayout);
    }

    /** The layout as saved (for the settings menu: is the current layout one that would be lost?). */
    static String currentLayoutString() {
        return HudModel.format(sLayout);
    }

    /** True while the player edits the HUD. */
    static volatile boolean sEditing;

    /**
     * Lets the player edit the HUD (shown for it even when the controls are off or hidden). Save writes the layout to
     * settings.txt; `done` runs on the UI thread when the editor closes (saved or not).
     */
    static void editLayout(final Activity activity, final Runnable done) {
        activity.runOnUiThread(() -> {
            TouchControls v = sView;
            boolean temp = v == null;
            if (temp) v = new TouchControls(activity, 1f, 1f);
            sEditing = true;
            v.startEdit(temp, done);
            if (temp) v.addTo(activity, 0);
        });
    }

    /** Called by native code once the game starts. */
    public static void attach(final Activity activity, final float opacity, final float cameraSpeed,
            final boolean enabled) {
        activity.runOnUiThread(() -> {
            if (sView != null || !enabled || sEditing) return;
            final TouchControls v = new TouchControls(activity, opacity, cameraSpeed);
            v.addTo(activity, 0);
        });
    }

    /**
     * Settings menu: turns the controls on or off and changes their opacity and camera stick speed, at once.
     */
    public static void applySettings(final Activity activity, final boolean enabled, final float opacity,
            final float cameraSpeed) {
        activity.runOnUiThread(() -> {
            if (!enabled) {
                if (sView != null) {
                    TouchControls v = sView;
                    sView = null;
                    v.releaseAll();
                    nativeVisible(false);
                    InputManager im = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
                    if (im != null) im.unregisterInputDeviceListener(v);
                    try {
                        activity.getWindowManager().removeView(v);
                    } catch (Exception e) {
                        android.util.Log.w("DSRNative", "touch controls: could not remove the overlay", e);
                    }
                }
                return;
            }
            if (sView == null) {
                if (sEditing) return;
                final TouchControls v = new TouchControls(activity, opacity, cameraSpeed);
                v.mShown = true;  // turned on by the player: shown even with a controller connected
                v.addTo(activity, 0);
                return;
            }
            sView.setAlpha(Math.max(0.1f, Math.min(1.0f, opacity)));
            sView.mCameraSpeed = Math.max(0.1f, Math.min(10f, cameraSpeed));
            sView.mT.cameraSpeed = sView.mCameraSpeed;
            sView.invalidate();
        });
    }

    /** The settings menu opened: everything held is let go (its releases go to the menu). */
    public static void releaseForMenu() {
        if (sView != null) {
            sView.releaseAll();
            sView.invalidate();
        }
    }

    /** Called by native code when a controller is used while the controls show. */
    public static void controllerUsed(final Activity activity) {
        activity.runOnUiThread(() -> {
            if (sView != null && !sView.mEditing) sView.setShown(false);
        });
    }

    private final Activity mActivity;
    private final float mDensity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mShown = true;

    private static final int N = HudModel.SPECS.length;
    private static final float SECONDARY = 0.8f;  // dpad, minus, plus, L3, LOCK are drawn dimmer than the main controls

    // Look: dark, warm, semi-transparent; pale gold when pressed.
    private static final int FILL_IDLE = 0x7014110D, FILL_DOWN = 0xD8E6D29A, RIM = 0xD0C9A55C, RIM_DOWN = 0xFFFFF0C8,
            TEXT_IDLE = 0xFFEDE3CC, TEXT_DOWN = 0xFF1A1610, GOLD = 0xFFC9A55C, PANEL_BG = 0xEC15120E;
    /** Face button letter colours, so the four stay apart at a glance (0 = the normal text colour). */
    private static final int[] ACCENT = new int[N];
    private static final boolean[] IS_DPAD = new boolean[N], IS_SECOND = new boolean[N];

    static {
        for (int i = 0; i < N; i++) {
            String g = HudModel.SPECS[i].group;
            IS_DPAD[i] = g.equals("dpad");
            IS_SECOND[i] = g.equals("dpad") || g.equals("minus") || g.equals("plus") || g.equals("l3") || g.equals("lock");
        }
        ACCENT[HudModel.indexOfBit(HudModel.A)] = 0xFFEE8A70;
        ACCENT[HudModel.indexOfBit(HudModel.B)] = 0xFFEBCB6A;
        ACCENT[HudModel.indexOfBit(HudModel.X)] = 0xFF72B2EC;
        ACCENT[HudModel.indexOfBit(HudModel.Y)] = 0xFF86CF93;
    }

    private HudModel.Geo mGeo;
    private final HudModel.Params mParams = new HudModel.Params();
    private float mU, mW, mH, mInsetL, mInsetT, mInsetR, mInsetB;
    // Per layout, cached so touch handling and drawing do no lookups.
    private final float[] mAlpha = new float[N];
    private float mStickAlpha = 1f, mCamAlpha = 1f;
    private final HudTouch mT = new HudTouch();  // what every finger is doing
    private final float[] mSticks = new float[4];

    /** A thumb resting on the camera stick slides its base under it, every display frame (Choreographer). */
    private long mRLastFrameNs;
    private final android.view.Choreographer.FrameCallback mRecenter = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameNs) {
            if (mT.camPointer < 0) return;
            float dt = mRLastFrameNs == 0 ? 0.016f : Math.min(0.05f, (frameNs - mRLastFrameNs) / 1e9f);
            mRLastFrameNs = frameNs;
            if (mT.recenter(dt, SystemClock.uptimeMillis(), sRecenter)) {
                sendState();
                invalidate();
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };
    private float mCameraSpeed;

    // Double tap (to show the controls again).
    private long mLastTapTime;
    private float mLastTapX, mLastTapY;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Path mPath = new Path();

    /** `argb` with its opacity multiplied by k (0..1). */
    private static int a(int argb, float k) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, k)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    private TouchControls(Activity activity, float opacity, float cameraSpeed) {
        super(activity);
        mActivity = activity;
        mCameraSpeed = Math.max(0.1f, Math.min(10f, cameraSpeed));
        mDensity = activity.getResources().getDisplayMetrics().density;
        setAlpha(Math.max(0.1f, Math.min(1.0f, opacity)));
        mStroke.setStyle(Paint.Style.STROKE);
        mThin.setStyle(Paint.Style.STROKE);
        mDash.setStyle(Paint.Style.STROKE);
        mDash.setPathEffect(new DashPathEffect(new float[] {7f * mDensity, 5f * mDensity}, 0f));
        mText.setColor(0xFFFFFFFF);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setFakeBoldText(true);
        for (int i = 0; i < 3; i++) mTool[i] = new RectF();
        for (int i = 0; i < 2; i++) {
            mSizeBtn[i] = new RectF();
            mAlphaBtn[i] = new RectF();
        }
        // A controller already connected (or built in, as on handhelds): start hidden.
        if (controllerConnected()) mShown = false;
        InputManager im = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        if (im != null) im.registerInputDeviceListener(this, mHandler);
    }

    private void addTo(final Activity activity, final int attempt) {
        final View decor = activity.getWindow().getDecorView();
        if (decor.getWindowToken() == null) {  // the game's window is not attached yet
            if (attempt < 50) mHandler.postDelayed(() -> addTo(activity, attempt + 1), 100);
            return;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.token = decor.getWindowToken();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try {
            activity.getWindowManager().addView(this, lp);
            if (mEditTemp) return;  // only for the layout editor
            sView = this;
            nativeVisible(mShown);
        } catch (Exception e) {
            android.util.Log.w("DSRNative", "touch controls: could not add the overlay", e);
        }
    }

    private boolean controllerConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                return true;
        }
        return false;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        InputDevice d = InputDevice.getDevice(deviceId);
        if (d == null) return;
        int s = d.getSources();
        if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
            setShown(false);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        // The last controller went away: the touch controls are the only way to play again.
        if (!controllerConnected()) setShown(true);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {}

    void setShown(boolean shown) {
        if (mEditing) {  // shown while the player edits; restored to `shown` after
            mSavedShown = shown;
            return;
        }
        if (mShown == shown) return;
        mShown = shown;
        releaseAll();
        nativeVisible(shown);
        invalidate();
    }

    /** Lets go of everything held, without telling the game (the caller sends the state). */
    private void clearPointers() {
        mT.clear();
    }

    private void releaseAll() {
        clearPointers();
        sendState();
    }

    // Android took the window away (the app went to the background, a dialog or the shade came over it): nothing can
    // still be held, whatever the touch events did or did not say.
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE) releaseAll();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        android.view.Choreographer.getInstance().removeFrameCallback(mRecenter);
        releaseAll();
    }

    // ---- layout ---------------------------------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        readInsets(getRootWindowInsets());
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        readInsets(insets);
        return super.onApplyWindowInsets(insets);
    }

    /** Display cutouts (a camera hole on a short edge): nothing starts under them. */
    private void readInsets(WindowInsets in) {
        float l = 0, t = 0, r = 0, b = 0;
        if (in != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.view.DisplayCutout dc = in.getDisplayCutout();
            if (dc != null) {
                l = dc.getSafeInsetLeft();
                t = dc.getSafeInsetTop();
                r = dc.getSafeInsetRight();
                b = dc.getSafeInsetBottom();
            }
        }
        if (l != mInsetL || t != mInsetT || r != mInsetR || b != mInsetB) {
            mInsetL = l;
            mInsetT = t;
            mInsetR = r;
            mInsetB = b;
            relayout();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layout(w, h);
    }

    private void relayout() {
        if (getWidth() > 0 && getHeight() > 0) layout(getWidth(), getHeight());
        invalidate();
    }

    private HudModel.Layout layoutNow() {
        return mEditing ? mEditLayout : sLayout;
    }

    private void layout(int w, int h) {
        mW = w;
        mH = h;
        final Style st = sStyle;
        mParams.buttonScale = st.size;
        mParams.stickScale = st.stickSize;
        mParams.spacing = st.spacing;
        mParams.insetL = mInsetL;
        mParams.insetT = mInsetT;
        mParams.insetR = mInsetR;
        mParams.insetB = mInsetB;
        final HudModel.Layout lay = layoutNow();
        mGeo = HudModel.compute(lay, w, h, mParams);
        mT.geo = mGeo;
        mT.floating = st.floating;
        mT.deadzone = st.deadzone;
        mT.cameraSpeed = mCameraSpeed;
        mT.insetL = mInsetL;
        mT.insetR = mInsetR;
        mU = mGeo.u;
        for (int i = 0; i < N; i++) {
            HudModel.Cfg c = lay.get(HudModel.SPECS[i].group);
            mAlpha[i] = c.alpha;
            mT.hidden[i] = c.hidden;
        }
        HudModel.Cfg c = lay.get("stick");
        mStickAlpha = c.alpha;
        mT.stickHidden = c.hidden;
        c = lay.get("cam");
        mCamAlpha = c.alpha;
        mT.camHidden = c.hidden;
        mT.cameraRadius = 1.05f * mU;
        mText.setTextSize(0.45f * mU);
        mStroke.setStrokeWidth(Math.max(2f, 0.05f * mU));
        mThin.setStrokeWidth(Math.max(1f, 0.02f * mU));
        mDash.setStrokeWidth(Math.max(1.5f, 0.03f * mU));
        layoutPanel();
    }

    // ---- HUD editor -----------------------------------------------------------------------------------------
    // Drag an element to move it; drag the corner handle (or pinch with a second finger) to resize it; the panel
    // changes size / opacity in steps, hides or shows it, and puts it back. Nothing here reaches the game: while the
    // editor is open every touch goes to editTouch, and the controls' own state was released when it opened.

    private boolean mEditing, mEditTemp, mSavedShown;
    private float mSavedAlpha;
    private Runnable mEditDone;
    private HudModel.Layout mEditLayout = new HudModel.Layout();
    private static final int D_NONE = 0, D_MOVE = 1, D_RESIZE = 2, D_PANEL = 3;
    private int mDragMode = D_NONE, mDragPointer = -1;
    private String mDragGroup, mSelGroup;
    private float mDragX, mDragY, mPivotX, mPivotY, mResize0, mSize0;
    private int mPinchPointer = -1;  // a second finger while an element is held: pinch to resize it
    private float mPinchDist, mPinchSize;
    private final RectF mPanel = new RectF(), mHead = new RectF(), mHideBtn = new RectF(), mOneBtn = new RectF();
    private final RectF[] mTool = new RectF[3];  // Save, Reset all, Cancel
    private final RectF[] mSizeBtn = new RectF[2], mAlphaBtn = new RectF[2];  // smaller / bigger, fainter / stronger
    private float mPanelDX, mPanelDY;  // the panel, moved by the player (it can be in the way)
    private long mCancelArmedAt, mResetArmedAt;  // a second tap within 2.5 s confirms
    private static final long CONFIRM_MS = 2500;

    private void layoutPanel() {
        final float u = mU, pw = 11.6f * u, ph = 5.5f * u;
        mPanelDX = Math.max(-(mW - pw) / 2f, Math.min((mW - pw) / 2f, mPanelDX));
        mPanelDY = Math.max(-(mH - ph) / 2f, Math.min((mH - ph) / 2f, mPanelDY));
        final float left = (mW - pw) / 2f + mPanelDX, top = (mH - ph) / 2f + mPanelDY;
        mPanel.set(left, top, left + pw, top + ph);
        mHead.set(left, top, left + pw, top + 0.95f * u);
        final float bh = 0.95f * u, gap = 0.3f * u;
        // Row 1: Save / Reset all / Cancel.
        final float tw = 3.6f * u, rowX = left + (pw - (3 * tw + 2 * gap)) / 2f, y1 = top + 1.95f * u;
        for (int i = 0; i < 3; i++) mTool[i].set(rowX + i * (tw + gap), y1, rowX + i * (tw + gap) + tw, y1 + bh);
        // Row 2: size and opacity of the selected element: [-] value [+].
        final float y2 = top + 3.15f * u, sq = 1.0f * u, vw = 3.0f * u, gw = sq + vw + sq, gx = left + (pw - (2 * gw + 0.4f * u)) / 2f;
        mSizeBtn[0].set(gx, y2, gx + sq, y2 + bh);
        mSizeBtn[1].set(gx + sq + vw, y2, gx + gw, y2 + bh);
        final float gx2 = gx + gw + 0.4f * u;
        mAlphaBtn[0].set(gx2, y2, gx2 + sq, y2 + bh);
        mAlphaBtn[1].set(gx2 + sq + vw, y2, gx2 + gw, y2 + bh);
        // Row 3: hide / show and put back.
        final float y3 = top + 4.35f * u, hw = 5.4f * u, hx = left + (pw - (2 * hw + gap)) / 2f;
        mHideBtn.set(hx, y3, hx + hw, y3 + bh);
        mOneBtn.set(hx + hw + gap, y3, hx + 2 * hw + gap, y3 + bh);
    }

    private static float distance(MotionEvent e, int a, int b) {
        float dx = e.getX(a) - e.getX(b), dy = e.getY(a) - e.getY(b);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void putOffset(String g, float dx, float dy) {
        HudModel.Cfg c = mEditLayout.edit(g);
        c.dx = dx;
        c.dy = dy;
    }

    private void putSize(String g, float size) {
        mEditLayout.edit(g).size = Math.max(HudModel.SIZE_MIN, Math.min(HudModel.SIZE_MAX, Math.round(size * 100f) / 100f));
        relayout();
    }

    private void putAlpha(String g, float alpha) {
        mEditLayout.edit(g).alpha = Math.max(HudModel.ALPHA_MIN, Math.min(1f, Math.round(alpha * 100f) / 100f));
        relayout();
    }

    private boolean dirty() {
        return !HudModel.format(mEditLayout).equals(HudModel.format(sLayout));
    }

    private void startEdit(boolean temp, Runnable done) {
        mEditTemp = temp;
        mEditDone = done;
        releaseAll();
        mEditing = true;
        mSavedShown = mShown;
        mShown = true;
        mSavedAlpha = getAlpha();
        setAlpha(Math.max(0.85f, mSavedAlpha));
        mEditLayout = sLayout.copy();
        mDragMode = D_NONE;
        mDragPointer = -1;
        mDragGroup = null;
        mSelGroup = null;
        mPinchPointer = -1;
        mCancelArmedAt = mResetArmedAt = 0;
        relayout();
    }

    private void endEdit(boolean save) {
        if (!mEditing) return;
        if (save) {
            sLayout = mEditLayout;
            if (!SettingsMenu.writeSetting("touch_layout", HudModel.format(mEditLayout)))
                android.util.Log.w("DSRNative", "touch controls: could not save the layout");
        }
        mEditing = false;
        sEditing = false;
        mDragMode = D_NONE;
        mDragPointer = -1;
        mDragGroup = null;
        setAlpha(mSavedAlpha);
        mShown = mSavedShown;
        if (mEditTemp) {
            InputManager im = (InputManager) mActivity.getSystemService(Context.INPUT_SERVICE);
            if (im != null) im.unregisterInputDeviceListener(this);
            try {
                mActivity.getWindowManager().removeView(this);
            } catch (Exception e) {
                android.util.Log.w("DSRNative", "touch controls: could not remove the editor", e);
            }
        } else {
            relayout();
        }
        Runnable done = mEditDone;
        mEditDone = null;
        if (done != null) done.run();
    }

    /** The element under a finger in the editor: a button (hidden ones too), the stick, or the camera area. */
    private String groupAt(float x, float y) {
        int b = mT.hit(x, y, true);
        if (b >= 0) return HudModel.SPECS[b].group;
        float dx = x - mGeo.stickX, dy = y - mGeo.stickY, r = mGeo.stickR * 1.1f;
        if (dx * dx + dy * dy <= r * r) return "stick";
        if (x >= mGeo.camL && x <= mGeo.camR && y >= mGeo.camT && y <= mGeo.camB) return "cam";
        return null;
    }

    /** The resize handle of an element: at the bottom right of it, kept on screen. */
    private float[] handlePos(String g) {
        float[] b = HudModel.bounds(mGeo, g);
        float m = 0.5f * mU;
        return new float[] {Math.min(b[2], mW - m), Math.min(b[3], mH - m)};
    }

    /** A finger came down on the editor panel. Returns true when the editor closed. */
    private boolean panelDown(int id, float x, float y) {
        final long now = SystemClock.uptimeMillis();
        if (mTool[0].contains(x, y)) {
            endEdit(true);
            return true;
        }
        if (mTool[1].contains(x, y)) {  // Reset all: tap twice
            if (now - mResetArmedAt < CONFIRM_MS) {
                mEditLayout = new HudModel.Layout();
                mSelGroup = null;
                mResetArmedAt = 0;
                relayout();
            } else {
                mResetArmedAt = now;
                postDelayed(this::invalidate, CONFIRM_MS + 50);
            }
            return false;
        }
        if (mTool[2].contains(x, y)) {  // Cancel: nothing changed closes at once, otherwise tap twice
            if (!dirty() || now - mCancelArmedAt < CONFIRM_MS) {
                endEdit(false);
                return true;
            }
            mCancelArmedAt = now;
            postDelayed(this::invalidate, CONFIRM_MS + 50);
            return false;
        }
        if (mSelGroup != null) {
            final HudModel.Cfg c = mEditLayout.get(mSelGroup);
            if (mSizeBtn[0].contains(x, y)) putSize(mSelGroup, c.size - HudModel.SIZE_STEP);
            else if (mSizeBtn[1].contains(x, y)) putSize(mSelGroup, c.size + HudModel.SIZE_STEP);
            else if (mAlphaBtn[0].contains(x, y)) putAlpha(mSelGroup, c.alpha - HudModel.ALPHA_STEP);
            else if (mAlphaBtn[1].contains(x, y)) putAlpha(mSelGroup, c.alpha + HudModel.ALPHA_STEP);
            else if (mHideBtn.contains(x, y)) {
                if (HudModel.canHide(mSelGroup)) {
                    mEditLayout.edit(mSelGroup).hidden = !c.hidden;
                    relayout();
                }
            } else if (mOneBtn.contains(x, y)) {  // this element back to its default spot, size, opacity and visibility
                mEditLayout.cfg.remove(mSelGroup);
                relayout();
            }
        }
        if (mHead.contains(x, y)) {  // the title bar moves the panel
            mDragMode = D_PANEL;
            mDragPointer = id;
            mDragX = x;
            mDragY = y;
        }
        return false;
    }

    private void editTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                float x = e.getX(i), y = e.getY(i);
                if (mDragPointer >= 0) {  // a second finger while an element is held: pinch resizes it
                    int d = e.findPointerIndex(mDragPointer);
                    if (mPinchPointer < 0 && mDragMode == D_MOVE && d >= 0 && mDragGroup != null) {
                        mPinchPointer = e.getPointerId(i);
                        mPinchDist = Math.max(1f, distance(e, d, i));
                        mPinchSize = mEditLayout.get(mDragGroup).size;
                    }
                    break;
                }
                if (mPanel.contains(x, y)) {
                    if (panelDown(e.getPointerId(i), x, y)) return;
                    break;
                }
                if (mSelGroup != null) {  // the corner handle of the selected element
                    float[] hp = handlePos(mSelGroup);
                    float dx = x - hp[0], dy = y - hp[1], r = 0.8f * mU;
                    if (dx * dx + dy * dy <= r * r) {
                        float[] b = HudModel.bounds(mGeo, mSelGroup);
                        mPivotX = (b[0] + b[2]) / 2f;
                        mPivotY = (b[1] + b[3]) / 2f;
                        mResize0 = Math.max(1f, (float) Math.hypot(x - mPivotX, y - mPivotY));
                        mSize0 = mEditLayout.get(mSelGroup).size;
                        mDragMode = D_RESIZE;
                        mDragPointer = e.getPointerId(i);
                        mDragGroup = mSelGroup;
                        break;
                    }
                }
                String g = groupAt(x, y);
                if (g != null) {
                    mDragMode = D_MOVE;
                    mDragPointer = e.getPointerId(i);
                    mDragGroup = g;
                    mSelGroup = g;
                    mDragX = x;
                    mDragY = y;
                } else {
                    mSelGroup = null;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mDragPointer < 0) break;
                int i = e.findPointerIndex(mDragPointer);
                if (i < 0) break;
                float x = e.getX(i), y = e.getY(i);
                if (mDragMode == D_PANEL) {
                    mPanelDX += x - mDragX;
                    mPanelDY += y - mDragY;
                    mDragX = x;
                    mDragY = y;
                    layoutPanel();
                    break;
                }
                if (mDragMode == D_RESIZE) {
                    putSize(mDragGroup, mSize0 * (float) Math.hypot(x - mPivotX, y - mPivotY) / mResize0);
                    break;
                }
                if (mPinchPointer >= 0) {  // pinching: resize, no move
                    int j = e.findPointerIndex(mPinchPointer);
                    if (j >= 0) putSize(mDragGroup, mPinchSize * distance(e, i, j) / mPinchDist);
                    mDragX = x;
                    mDragY = y;
                    break;
                }
                float[] def = mGeo.dflt.get(mDragGroup), cur = mGeo.placed.get(mDragGroup);
                if (def == null || cur == null || mU <= 0) break;
                // New spot = where it is + the finger's move; kept on screen by the layout.
                float nx = cur[0] + (x - mDragX), ny = cur[1] + (y - mDragY);
                putOffset(mDragGroup, (nx - def[0]) / mU, (ny - def[1]) / mU);
                mDragX = x;
                mDragY = y;
                relayout();
                float[] placed = mGeo.placed.get(mDragGroup);  // clamped: keep the offset where it stopped
                if (placed != null) putOffset(mDragGroup, (placed[0] - def[0]) / mU, (placed[1] - def[1]) / mU);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(e.getActionIndex());
                if (id == mPinchPointer) {
                    mPinchPointer = -1;
                    int i = e.findPointerIndex(mDragPointer);  // moving again from where the held finger is
                    if (i >= 0) {
                        mDragX = e.getX(i);
                        mDragY = e.getY(i);
                    }
                }
                if (id == mDragPointer) {
                    mDragMode = D_NONE;
                    mDragPointer = -1;
                    mDragGroup = null;
                    mPinchPointer = -1;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                mDragMode = D_NONE;
                mDragPointer = -1;
                mDragGroup = null;
                mPinchPointer = -1;
                break;
            default:
                break;
        }
        invalidate();
    }

    // ---- touch ----------------------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mEditing) {
            editTouch(e);
            return true;
        }
        final int action = e.getActionMasked();
        if (!mShown) {
            if (action == MotionEvent.ACTION_DOWN) checkDoubleTap(e.getX(), e.getY());
            return true;
        }
        if (mGeo == null) return true;
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                clearPointers();  // a new gesture starts with no finger down: nothing can still be held from before
                down(e.getPointerId(0), e.getX(0), e.getY(0));
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                down(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) mT.move(e.getPointerId(i), e.getX(i), e.getY(i), SystemClock.uptimeMillis());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                mT.up(e.getPointerId(i));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                clearPointers();
                break;
            default:
                break;
        }
        sendState();
        invalidate();
        return true;
    }

    private void checkDoubleTap(float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        float slop = 60 * mDensity;
        if (now - mLastTapTime < 350 && Math.abs(x - mLastTapX) < slop && Math.abs(y - mLastTapY) < slop) {
            mLastTapTime = 0;
            setShown(true);
        } else {
            mLastTapTime = now;
            mLastTapX = x;
            mLastTapY = y;
        }
    }

    private void down(int id, float x, float y) {
        mT.down(id, x, y, SystemClock.uptimeMillis());
        if (mT.camPointer == id) {  // the camera stick starts: the thumb-rest slide runs every frame while it is held
            mRLastFrameNs = 0;
            android.view.Choreographer.getInstance().removeFrameCallback(mRecenter);
            android.view.Choreographer.getInstance().postFrameCallback(mRecenter);
        }
    }

    private void sendState() {
        mT.sticks(mSticks);
        nativeState(mT.buttons(), mSticks[0], mSticks[1], mSticks[2], mSticks[3]);
    }

    // ---- drawing --------------------------------------------------------------------------------------------
    // Nothing is allocated while drawing a normal frame; the editor's overlays allocate a little (editing only).

    @Override
    protected void onDraw(Canvas c) {
        if (!mShown || mGeo == null) return;
        if (mEditing) {
            c.drawColor(0x50000000);
            drawCameraArea(c);
        }
        final Style st = sStyle;
        drawStick(c, st);
        drawCameraStick(c);
        for (int i = 0; i < N; i++) drawButton(c, i, st);
        if (mEditing) drawEditor(c);
    }

    private void drawStick(Canvas c, Style st) {
        if (mT.stickHidden && !mEditing) return;
        float k = mStickAlpha * (mT.stickHidden ? 0.35f : 1f);
        final boolean active = mT.stickPointer >= 0;
        if (st.floating && !active) k *= 0.6f;  // resting spot of a floating stick: a faint marker
        final float bx = mT.stickBaseX(), by = mT.stickBaseY(), r = mGeo.stickR;
        mFill.setColor(a(FILL_IDLE, k));
        c.drawCircle(bx, by, r, mFill);
        if (st.outline) {
            mStroke.setColor(a(RIM, k));
            c.drawCircle(bx, by, r, mStroke);
        }
        mThin.setColor(a(0x50FFFFFF, k));
        c.drawCircle(bx, by, r * 0.55f, mThin);
        float kx = bx, ky = by;
        if (active) {  // the thumb stays inside the ring
            float dx = mT.stickX - bx, dy = mT.stickY - by, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > r) {
                dx = dx / len * r;
                dy = dy / len * r;
            }
            kx = bx + dx;
            ky = by + dy;
        }
        mFill.setColor(a(active ? 0xC8E6D29A : 0x78D8C28C, k));
        c.drawCircle(kx, ky, r * 0.42f, mFill);
        if (st.outline) {
            mStroke.setColor(a(active ? RIM_DOWN : RIM, k));
            c.drawCircle(kx, ky, r * 0.42f, mStroke);
        }
    }

    /** Camera stick, only while touched, faint: 10% opaque on screen (whatever touch_opacity is). */
    private void drawCameraStick(Canvas c) {
        if (mT.camPointer < 0 || mEditing) return;
        final int al = Math.max(1, Math.min(255, Math.round(255 * 0.10f * mCamAlpha / Math.max(0.1f, getAlpha()))));
        mFill.setColor(al << 24);
        c.drawCircle(mT.camBaseX, mT.camBaseY, mT.cameraRadius, mFill);
        mStroke.setColor(0xFFFFFFFF);
        mStroke.setAlpha(al);
        c.drawCircle(mT.camBaseX, mT.camBaseY, mT.cameraRadius, mStroke);
        mStroke.setAlpha(0xFF);
        float dx = mT.camX - mT.camBaseX, dy = mT.camY - mT.camBaseY, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > mT.cameraRadius) {
            dx = dx / len * mT.cameraRadius;
            dy = dy / len * mT.cameraRadius;
        }
        mFill.setColor((al << 24) | 0xFFFFFF);
        c.drawCircle(mT.camBaseX + dx, mT.camBaseY + dy, mT.cameraRadius * 0.45f, mFill);
    }

    private void drawButton(Canvas c, int i, Style st) {
        if (mT.hidden[i] && !mEditing) return;
        final HudModel.Spec sp = HudModel.SPECS[i];
        final boolean on = mT.press[i] > 0;
        final float k = mAlpha[i] * (IS_SECOND[i] ? SECONDARY : 1f) * (mT.hidden[i] ? 0.35f : 1f);
        final float cx = mGeo.cx[i], cy = mGeo.cy[i], w = mGeo.bw[i], h = mGeo.bh[i], sh = on ? 0.94f : 1f;
        mFill.setColor(a(on ? FILL_DOWN : FILL_IDLE, k));
        mStroke.setColor(a(on ? RIM_DOWN : RIM, k));
        if (sp.round) {
            c.drawCircle(cx, cy, w / 2 * sh, mFill);
            if (st.outline) c.drawCircle(cx, cy, w / 2 * sh, mStroke);
        } else {
            mRect.set(cx - w / 2 * sh, cy - h / 2 * sh, cx + w / 2 * sh, cy + h / 2 * sh);
            float r = Math.min(w, h) * (IS_DPAD[i] ? 0.25f : 0.45f);
            c.drawRoundRect(mRect, r, r, mFill);
            if (st.outline) c.drawRoundRect(mRect, r, r, mStroke);
        }
        final int tc = on ? TEXT_DOWN : ACCENT[i] != 0 ? ACCENT[i] : TEXT_IDLE;
        if (IS_DPAD[i]) {  // an arrow (an icon, so it stays when labels are off)
            final float s = w * 0.22f;
            final float dx = sp.bit == HudModel.LEFT ? -1 : sp.bit == HudModel.RIGHT ? 1 : 0;
            final float dy = sp.bit == HudModel.UP ? -1 : sp.bit == HudModel.DOWN ? 1 : 0;
            mPath.reset();
            mPath.moveTo(cx + dx * s, cy + dy * s);
            mPath.lineTo(cx - dx * s * 0.6f - dy * s * 0.95f, cy - dy * s * 0.6f + dx * s * 0.95f);
            mPath.lineTo(cx - dx * s * 0.6f + dy * s * 0.95f, cy - dy * s * 0.6f - dx * s * 0.95f);
            mPath.close();
            mFill.setColor(a(tc, k));
            c.drawPath(mPath, mFill);
        } else if (st.labels) {
            float size = Math.min(0.45f * mU, Math.min(w, h) * 0.4f);
            if (sp.label.length() > 2) size *= 0.7f;
            mText.setTextSize(size);
            mText.setColor(a(tc, k));
            c.drawText(sp.label, cx, cy - (mText.descent() + mText.ascent()) / 2, mText);
        }
    }

    // -- editor overlays

    private static String groupName(String g) {
        return Strings.t("grp_" + g);
    }

    private void drawCameraArea(Canvas c) {
        mRect.set(mGeo.camL, mGeo.camT, mGeo.camR, mGeo.camB);
        mFill.setColor(mT.camHidden ? 0x10FFFFFF : 0x18FFFFFF);
        c.drawRect(mRect, mFill);
    }

    private void label(Canvas c, String s, float cx, float cy, float size, int color) {
        mText.setTextSize(size);
        mText.setColor(color);
        c.drawText(s, cx, cy - (mText.descent() + mText.ascent()) / 2, mText);
    }

    private void tool(Canvas c, RectF r, String text, boolean gold, boolean enabled) {
        final float u = mU, rr = 0.25f * u;
        mFill.setColor(gold ? (enabled ? GOLD : a(GOLD, 0.4f)) : (enabled ? 0xF0262320 : 0x90262320));
        c.drawRoundRect(r, rr, rr, mFill);
        mStroke.setColor(a(RIM, enabled ? 1f : 0.4f));
        c.drawRoundRect(r, rr, rr, mStroke);
        label(c, text, r.centerX(), r.centerY(), 0.4f * u, gold ? 0xFF151515 : enabled ? 0xFFFFFFFF : 0x80FFFFFF);
    }

    private void drawEditor(Canvas c) {
        final float u = mU;
        // Every element's outline; the selected one in gold with its resize handle.
        for (String g : HudModel.GROUPS) {
            final float[] b = HudModel.bounds(mGeo, g);
            if (b[2] <= b[0]) continue;
            final boolean sel = g.equals(mSelGroup);
            mRect.set(b[0] - 0.1f * u, b[1] - 0.1f * u, b[2] + 0.1f * u, b[3] + 0.1f * u);
            if (sel) {
                mFill.setColor(g.equals(mDragGroup) ? 0x38C9A55C : 0x20C9A55C);
                c.drawRoundRect(mRect, 0.2f * u, 0.2f * u, mFill);
                mStroke.setColor(GOLD);
                c.drawRoundRect(mRect, 0.2f * u, 0.2f * u, mStroke);
            } else {
                mDash.setColor(0x90FFFFFF);
                c.drawRoundRect(mRect, 0.2f * u, 0.2f * u, mDash);
            }
        }
        if (mSelGroup != null) {
            final float[] hp = handlePos(mSelGroup);
            mFill.setColor(GOLD);
            c.drawCircle(hp[0], hp[1], 0.38f * u, mFill);
            mStroke.setColor(0xFF151515);
            c.drawCircle(hp[0], hp[1], 0.38f * u, mStroke);
            final float d = 0.13f * u;  // a diagonal double arrow, drawn (a font may not have the glyph)
            c.drawLine(hp[0] - d, hp[1] + d, hp[0] + d, hp[1] - d, mStroke);
        }
        // Panel.
        final float rr = 0.3f * u;
        mFill.setColor(PANEL_BG);
        c.drawRoundRect(mPanel, rr, rr, mFill);
        mStroke.setColor(0xFF4A3D22);
        c.drawRoundRect(mPanel, rr, rr, mStroke);
        mFill.setColor(0xFF7A6A44);  // grip dots: the title bar moves the panel
        for (int i = 0; i < 3; i++) c.drawCircle(mHead.left + (0.45f + 0.28f * i) * u, mHead.centerY(), 0.07f * u, mFill);
        label(c, Strings.t("edit_help"), mHead.centerX(), mHead.centerY(), 0.34f * u, 0xFFBFB59C);
        final String name = mSelGroup == null ? Strings.t("size_pick") : groupName(mSelGroup);
        label(c, name, mPanel.centerX(), mPanel.top + 1.45f * u, 0.42f * u, GOLD);
        final long now = SystemClock.uptimeMillis();
        tool(c, mTool[0], Strings.t("save"), true, true);
        tool(c, mTool[1], now - mResetArmedAt < CONFIRM_MS ? Strings.t("ed_again") : Strings.t("ed_reset_all"), false, true);
        tool(c, mTool[2], now - mCancelArmedAt < CONFIRM_MS ? Strings.t("ed_discard") : Strings.t("cancel"), false, true);
        final boolean sel = mSelGroup != null;
        final HudModel.Cfg cfg = sel ? mEditLayout.get(mSelGroup) : null;
        tool(c, mSizeBtn[0], "\u2212", false, sel);
        tool(c, mSizeBtn[1], "+", false, sel);
        tool(c, mAlphaBtn[0], "\u2212", false, sel);
        tool(c, mAlphaBtn[1], "+", false, sel);
        label(c, sel ? Strings.t("size_of", Math.round(cfg.size * 100f)) : "\u2013",
                (mSizeBtn[0].right + mSizeBtn[1].left) / 2f, mSizeBtn[0].centerY(), 0.36f * u, sel ? 0xFFFFFFFF : 0x80FFFFFF);
        label(c, sel ? Strings.t("ed_opacity", Math.round(cfg.alpha * 100f)) : "\u2013",
                (mAlphaBtn[0].right + mAlphaBtn[1].left) / 2f, mAlphaBtn[0].centerY(), 0.36f * u, sel ? 0xFFFFFFFF : 0x80FFFFFF);
        final boolean canHide = sel && HudModel.canHide(mSelGroup);
        tool(c, mHideBtn, !sel ? Strings.t("ed_hide") : !canHide ? Strings.t("ed_needed") : cfg.hidden ? Strings.t("ed_show") : Strings.t("ed_hide"),
                false, canHide);
        tool(c, mOneBtn, Strings.t("ed_reset_one"), false, sel);
    }
}
