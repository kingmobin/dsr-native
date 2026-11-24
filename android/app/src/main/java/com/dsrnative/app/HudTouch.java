package com.dsrnative.app;

/**
 * What every finger on the HUD is doing (no Android classes, so tests/hud can drive it with made-up touches).
 *
 * A finger keeps one job from the moment it comes down until it lifts: it holds a button (and may slide to another
 * button of the same group), drives the left stick, or drives the camera stick. It never changes job, so a finger on
 * the camera area cannot press a button it drags over, a stick finger cannot press a button, and lifting one finger
 * only ever releases what that finger held. Pointer ids are Android's (0..15 are tracked).
 */
final class HudTouch {
    static final int K_BUTTON = 0, K_STICK = 1, K_CAMERA = 2, K_NONE = 3;
    static final int N = HudModel.SPECS.length;
    static final int MAX_POINTERS = 16;

    // Set by the view after each layout / settings change.
    HudModel.Geo geo;
    final boolean[] hidden = new boolean[N];
    boolean stickHidden, camHidden, floating;
    float deadzone = 0.08f;
    float cameraSpeed = 1f;
    float insetL, insetR;
    float cameraRadius;  // the camera stick's ring (px)

    // Per finger.
    final int[] kind = new int[MAX_POINTERS];
    final int[] on = new int[MAX_POINTERS];  // button held (index into HudModel.SPECS) or -1
    final String[] group = new String[MAX_POINTERS];  // group of the button the finger started on

    // Per button: fingers on it.
    final int[] press = new int[N];

    // Left stick. Floating: its base is where the finger came down.
    int stickPointer = -1;
    float stickX, stickY, floatX, floatY;
    boolean floatActive;

    // Camera stick: its base follows a finger that goes past the ring.
    int camPointer = -1;
    float camBaseX, camBaseY, camX, camY;
    long camLastMove;

    private final float[] mAxes = new float[2];

    HudTouch() {
        clear();
    }

    /** Lets go of everything. */
    void clear() {
        for (int i = 0; i < MAX_POINTERS; i++) {
            kind[i] = K_NONE;
            on[i] = -1;
            group[i] = null;
        }
        for (int i = 0; i < N; i++) press[i] = 0;
        stickPointer = -1;
        camPointer = -1;
        floatActive = false;
    }

    boolean anyHeld() {
        for (int i = 0; i < MAX_POINTERS; i++)
            if (kind[i] != K_NONE) return true;
        return false;
    }

    float stickBaseX() {
        return floatActive ? floatX : geo.stickX;
    }

    float stickBaseY() {
        return floatActive ? floatY : geo.stickY;
    }

    /** The button under a point (index into HudModel.SPECS), or -1. Hidden ones only count when asked to. */
    int hit(float x, float y, boolean includeHidden) {
        final float slop = 0.15f * geo.u;
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            if (!includeHidden && hidden[i]) continue;
            final float dx = x - geo.cx[i], dy = y - geo.cy[i], bw = geo.bw[i], bh = geo.bh[i];
            boolean in;
            if (HudModel.SPECS[i].round) {
                float r = bw / 2 + slop;
                in = dx * dx + dy * dy <= r * r;
            } else {
                in = Math.abs(dx) <= bw / 2 + slop && Math.abs(dy) <= bh / 2 + slop;
            }
            if (in) {
                float dist = dx * dx + dy * dy;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }
        }
        return best;
    }

    /** Where a finger takes hold of the stick: on or near it, or (floating) anywhere on the left side. */
    private boolean stickGrab(float x, float y) {
        if (floating) return x < insetL + (geo.w - insetL - insetR) * 0.5f;
        float dx = x - geo.stickX, dy = y - geo.stickY, r = geo.stickR * 1.5f;
        return dx * dx + dy * dy <= r * r;
    }

    boolean inCamera(float x, float y) {
        return x >= geo.camL && x <= geo.camR && y >= geo.camT && y <= geo.camB;
    }

    void down(int id, float x, float y, long nowMs) {
        if (id < 0 || id >= MAX_POINTERS) return;
        if (kind[id] != K_NONE) up(id);  // a finger id coming down again: whatever it held was let go first
        int b = hit(x, y, false);
        if (b >= 0) {
            kind[id] = K_BUTTON;
            on[id] = b;
            group[id] = HudModel.SPECS[b].group;
            press[b]++;
        } else if (stickPointer < 0 && !stickHidden && stickGrab(x, y)) {
            kind[id] = K_STICK;
            stickPointer = id;
            stickX = x;
            stickY = y;
            if (floating) {  // the stick appears under the finger
                floatActive = true;
                floatX = x;
                floatY = y;
            }
        } else if (camPointer < 0 && !camHidden && inCamera(x, y)) {
            kind[id] = K_CAMERA;
            camPointer = id;
            camBaseX = camX = x;
            camBaseY = camY = y;
            camLastMove = nowMs;
        } else {
            kind[id] = K_NONE;
        }
    }

    void move(int id, float x, float y, long nowMs) {
        if (id < 0 || id >= MAX_POINTERS) return;
        switch (kind[id]) {
            case K_BUTTON: {  // slide between the buttons of the same group (across the face buttons, the d-pad)
                int b = hit(x, y, false);
                if (b >= 0 && !HudModel.SPECS[b].group.equals(group[id])) b = -1;  // never onto another group's button
                if (b != on[id]) {
                    if (on[id] >= 0) press[on[id]]--;
                    on[id] = b;
                    if (b >= 0) press[b]++;
                }
                break;
            }
            case K_STICK:
                stickX = x;
                stickY = y;
                break;
            case K_CAMERA: {
                if (x != camX || y != camY) camLastMove = nowMs;
                camX = x;
                camY = y;
                float dx = x - camBaseX, dy = y - camBaseY, len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > cameraRadius) {  // the base follows the finger
                    camBaseX = x - dx / len * cameraRadius;
                    camBaseY = y - dy / len * cameraRadius;
                }
                break;
            }
            default:
                break;
        }
    }

    void up(int id) {
        if (id < 0 || id >= MAX_POINTERS) return;
        if (kind[id] == K_BUTTON && on[id] >= 0) press[on[id]]--;
        if (kind[id] == K_STICK) {
            stickPointer = -1;
            floatActive = false;
        }
        if (kind[id] == K_CAMERA) camPointer = -1;
        kind[id] = K_NONE;
        on[id] = -1;
        group[id] = null;
    }

    /** The nn::hid button bits held now. */
    int buttons() {
        int bits = 0;
        for (int i = 0; i < N; i++)
            if (press[i] > 0) bits |= HudModel.SPECS[i].bit;
        return bits;
    }

    /** Both sticks: out = {lx, ly, rx, ry}, each -1..1, y up. */
    void sticks(float[] out) {
        out[0] = out[1] = out[2] = out[3] = 0f;
        if (stickPointer >= 0 && geo != null) {
            HudModel.stickAxes(stickX - stickBaseX(), stickY - stickBaseY(), geo.stickR, deadzone, mAxes);
            out[0] = mAxes[0];
            out[1] = mAxes[1];
        }
        if (camPointer >= 0) {
            // Full tilt at 1 / speed of the ring's radius (speed 1: at the edge).
            float reach = cameraRadius / cameraSpeed;
            float rx = Math.max(-1f, Math.min(1f, (camX - camBaseX) / reach));
            float ry = Math.max(-1f, Math.min(1f, -(camY - camBaseY) / reach));
            if (Math.abs(rx) < 0.06f && Math.abs(ry) < 0.06f) rx = ry = 0;
            out[2] = rx;
            out[3] = ry;
        }
    }

    // A thumb resting on the camera stick: the base slides under the thumb, so the camera eases to a stop instead of
    // turning on and on. The slide fades in over the first moments of stillness (no jolt when it starts).
    static final float REST_START_S = 0.012f, REST_RAMP_S = 0.045f;  // stillness before / to full slide
    static final float RECENTER_TIME_S = 0.06f;  // time constant of the slide at full strength

    /** One display frame of the slide (dt seconds). Returns true when the base moved. */
    boolean recenter(float dt, long nowMs, boolean enabled) {
        if (camPointer < 0) return false;
        float still = (nowMs - camLastMove) / 1000f;
        float ramp = enabled ? Math.max(0f, Math.min(1f, (still - REST_START_S) / REST_RAMP_S)) : 0f;
        ramp = ramp * ramp * (3f - 2f * ramp);  // smoothstep
        if (ramp > 0f && (camX != camBaseX || camY != camBaseY)) {
            float k = 1f - (float) Math.exp(-dt * ramp / RECENTER_TIME_S);
            camBaseX += (camX - camBaseX) * k;
            camBaseY += (camY - camBaseY) * k;
            float dx = camX - camBaseX, dy = camY - camBaseY;
            if (dx * dx + dy * dy < 0.25f) {
                camBaseX = camX;
                camBaseY = camY;
            }
            return true;
        }
        return false;
    }
}
