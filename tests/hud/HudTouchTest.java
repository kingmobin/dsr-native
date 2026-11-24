package com.dsrnative.app;

import java.util.Random;

/** Host test of HudTouch: made-up multi-touch sequences against the default layout (no Android classes). */
public final class HudTouchTest {
    static int failures, checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL  " + what);
        }
    }

    static final int W = 2712, H = 1220;
    static long now = 1000;

    static HudTouch make(HudModel.Layout lay, boolean floating) {
        HudTouch t = new HudTouch();
        HudModel.Params p = new HudModel.Params();
        t.geo = HudModel.compute(lay, W, H, p);
        for (int i = 0; i < HudTouch.N; i++) t.hidden[i] = lay.get(HudModel.SPECS[i].group).hidden;
        t.stickHidden = lay.get("stick").hidden;
        t.camHidden = lay.get("cam").hidden;
        t.floating = floating;
        t.cameraRadius = 1.05f * t.geo.u;
        t.insetL = t.insetR = 0;
        return t;
    }

    static HudTouch make() {
        return make(new HudModel.Layout(), false);
    }

    static float cx(HudTouch t, int bit) { return t.geo.cx[HudModel.indexOfBit(bit)]; }
    static float cy(HudTouch t, int bit) { return t.geo.cy[HudModel.indexOfBit(bit)]; }

    static void down(HudTouch t, int id, float x, float y) { t.down(id, x, y, now += 5); }
    static void move(HudTouch t, int id, float x, float y) { t.move(id, x, y, now += 5); }

    static float[] sticks(HudTouch t) {
        float[] o = new float[4];
        t.sticks(o);
        return o;
    }

    static boolean near(float a, float b, float eps) { return Math.abs(a - b) <= eps; }

    static void scenarios() {
        HudTouch t = make();
        final float u = t.geo.u;

        // Move while attacking: stick finger + button finger, both work; lifting one leaves the other held.
        down(t, 0, t.geo.stickX, t.geo.stickY);
        move(t, 0, t.geo.stickX + t.geo.stickR, t.geo.stickY);
        down(t, 1, cx(t, HudModel.R), cy(t, HudModel.R));
        check(t.buttons() == HudModel.R, "R held with the stick active");
        check(near(sticks(t)[0], 1f, 1e-4f), "stick at full right while R is held");
        t.up(1);
        check(t.buttons() == 0 && near(sticks(t)[0], 1f, 1e-4f), "lifting the attack finger leaves the stick held");
        t.up(0);
        check(!t.anyHeld() && sticks(t)[0] == 0f, "everything released");

        // Hold several buttons independently.
        t = make();
        down(t, 3, cx(t, HudModel.A), cy(t, HudModel.A));
        down(t, 4, cx(t, HudModel.ZR), cy(t, HudModel.ZR));
        down(t, 5, cx(t, HudModel.L), cy(t, HudModel.L));
        check(t.buttons() == (HudModel.A | HudModel.ZR | HudModel.L), "three buttons held at once");
        t.up(4);
        check(t.buttons() == (HudModel.A | HudModel.L), "lifting ZR releases only ZR");
        t.up(3);
        t.up(5);
        check(t.buttons() == 0, "all released");

        // Two fingers on the same button: held until both lift.
        down(t, 0, cx(t, HudModel.B), cy(t, HudModel.B));
        down(t, 1, cx(t, HudModel.B) + 4, cy(t, HudModel.B));
        t.up(0);
        check(t.buttons() == HudModel.B, "B still held by the second finger");
        t.up(1);
        check(t.buttons() == 0, "B released after both");

        // Stick finger dragged far outside the base, over buttons and the camera area: stays a stick, presses nothing.
        t = make();
        down(t, 0, t.geo.stickX, t.geo.stickY);
        move(t, 0, cx(t, HudModel.A), cy(t, HudModel.A));
        check(t.buttons() == 0, "a stick finger dragged over A does not press it");
        float[] s = sticks(t);
        check(near((float) Math.hypot(s[0], s[1]), 1f, 1e-3f) && s[0] > 0, "stick at full tilt towards the finger, however far");
        t.up(0);

        // Camera finger dragged across buttons: stays the camera.
        t = make();
        float camx = (t.geo.camL + t.geo.camR) / 2, camy = t.geo.camT + 0.5f * u;
        down(t, 0, camx, camy);
        check(t.camPointer == 0, "a finger in the right half starts the camera");
        move(t, 0, cx(t, HudModel.Y), cy(t, HudModel.Y));
        move(t, 0, cx(t, HudModel.ZR), cy(t, HudModel.ZR));
        check(t.buttons() == 0, "a camera finger dragged over buttons presses nothing");
        t.up(0);
        check(t.camPointer == -1 && sticks(t)[2] == 0f, "camera released");

        // Move + camera + button together.
        down(t, 0, t.geo.stickX, t.geo.stickY);
        move(t, 0, t.geo.stickX, t.geo.stickY - t.geo.stickR);
        down(t, 1, camx, camy);
        move(t, 1, camx + 2 * u, camy);
        down(t, 2, cx(t, HudModel.A), cy(t, HudModel.A));
        s = sticks(t);
        check(s[1] > 0.99f && s[2] > 0.99f && t.buttons() == HudModel.A, "stick + camera + button all work together");
        t.up(1);
        s = sticks(t);
        check(s[2] == 0f && s[1] > 0.99f && t.buttons() == HudModel.A, "lifting the camera finger leaves the others");
        t.clear();
        check(!t.anyHeld() && t.buttons() == 0, "cancel releases everything");

        // Sliding: across the face buttons yes, onto another group's button no.
        t = make();
        down(t, 0, cx(t, HudModel.A), cy(t, HudModel.A));
        move(t, 0, cx(t, HudModel.B), cy(t, HudModel.B));
        check(t.buttons() == HudModel.B, "sliding from A to B moves the press (one button at a time)");
        move(t, 0, cx(t, HudModel.R), cy(t, HudModel.R));
        check(t.buttons() == 0, "sliding off the face buttons onto R releases and does not press R");
        move(t, 0, cx(t, HudModel.X), cy(t, HudModel.X));
        check(t.buttons() == HudModel.X, "and back onto the face buttons presses again");
        t.up(0);
        check(t.buttons() == 0, "released");

        // A finger on a button never becomes a stick or a camera.
        down(t, 0, cx(t, HudModel.ZL), cy(t, HudModel.ZL));
        move(t, 0, t.geo.stickX, t.geo.stickY);
        move(t, 0, camx, camy);
        check(t.stickPointer == -1 && t.camPointer == -1 && t.buttons() == 0, "a button finger leaving its button does not steer a stick");
        t.up(0);

        // A second finger cannot take over a held stick; it only starts what is under it.
        t = make();
        down(t, 0, t.geo.stickX, t.geo.stickY);
        down(t, 1, t.geo.stickX + 10, t.geo.stickY + 10);
        check(t.stickPointer == 0 && t.kind[1] == HudTouch.K_NONE, "a second finger on the stick does nothing");
        t.up(1);
        check(t.stickPointer == 0, "and lifting it does not release the stick");
        t.up(0);

        // Same pointer id coming down again without an up: nothing leaks.
        t = make();
        down(t, 0, cx(t, HudModel.A), cy(t, HudModel.A));
        down(t, 0, cx(t, HudModel.B), cy(t, HudModel.B));
        t.up(0);
        check(t.buttons() == 0 && !t.anyHeld(), "a repeated down for one finger does not leave a button stuck");

        // Out-of-range pointer ids are ignored, not crashed on.
        t.down(99, 10, 10, now);
        t.move(-1, 10, 10, now);
        t.up(1000);
        check(!t.anyHeld(), "bad pointer ids ignored");

        // Nothing under the finger: no effect (left side, outside the stick's reach).
        t = make();
        down(t, 0, t.geo.camL - 3 * u, 0.5f * u);
        check(!t.anyHeld() || t.kind[0] == HudTouch.K_NONE, "a finger on empty ground does nothing");
        t.up(0);
    }

    static void hiddenAndAreas() {
        // Hidden buttons stop receiving touches; the stick and camera area too.
        HudModel.Layout lay = HudModel.parse("@m;r:0,0,1,1,1;stick:0,0,1,1,1;cam:0,0,1,1,1");
        HudTouch t = make(lay, false);
        down(t, 0, cx(t, HudModel.R), cy(t, HudModel.R));
        check(t.buttons() == 0 && t.kind[0] != HudTouch.K_BUTTON, "a hidden button cannot be pressed");
        down(t, 1, t.geo.stickX, t.geo.stickY);
        check(t.stickPointer == -1, "a hidden stick cannot be grabbed");
        down(t, 2, (t.geo.camL + t.geo.camR) / 2, t.geo.camT + 40);
        check(t.camPointer == -1, "a hidden camera area does nothing");
        t.clear();
        down(t, 0, cx(t, HudModel.ZR), cy(t, HudModel.ZR));
        check(t.buttons() == HudModel.ZR, "the others still work");

        // A smaller camera area: a finger outside it does not steer the camera.
        lay = HudModel.parse("@m;cam:0,0,0.5");
        t = make(lay, false);
        down(t, 0, t.geo.camL - 5, t.geo.camT + 5);
        check(t.camPointer == -1, "outside a shrunken camera area: nothing");
        down(t, 1, (t.geo.camL + t.geo.camR) / 2, t.geo.camT + 10);
        check(t.camPointer == 1, "inside it: camera");

        // Floating stick: appears under the finger (no jump), goes home on release.
        t = make(new HudModel.Layout(), true);
        float fx = 2.0f * t.geo.u, fy = 0.4f * t.geo.h;
        down(t, 0, fx, fy);
        check(t.stickPointer == 0 && t.floatActive && sticks(t)[0] == 0f && sticks(t)[1] == 0f, "floating stick starts centred under the finger");
        check(near(t.stickBaseX(), fx, 0.01f) && near(t.stickBaseY(), fy, 0.01f), "its base is where the finger came down");
        move(t, 0, fx + t.geo.stickR, fy);
        check(near(sticks(t)[0], 1f, 1e-3f), "full tilt one radius away");
        t.up(0);
        check(!t.floatActive && near(t.stickBaseX(), t.geo.stickX, 0.01f), "the base goes home when the finger lifts");
        // A button wins over the floating zone.
        down(t, 0, cx(t, HudModel.L), cy(t, HudModel.L));
        check(t.buttons() == HudModel.L && t.stickPointer == -1, "a button in the floating zone is still a button");
        t.clear();
    }

    static void camera() {
        HudTouch t = make();
        float cx = (t.geo.camL + t.geo.camR) / 2, cy = t.geo.camT + t.geo.u;
        down(t, 0, cx, cy);
        move(t, 0, cx + t.cameraRadius, cy);
        check(near(sticks(t)[2], 1f, 1e-4f), "camera: a ring radius of drag = full tilt at speed 1");
        t.cameraSpeed = 2f;
        check(near(sticks(t)[2], 1f, 1e-4f), "speed 2: still capped at 1");
        move(t, 0, cx + t.cameraRadius, cy);
        t.cameraSpeed = 1f;
        move(t, 0, cx + 3 * t.cameraRadius, cy);
        check(near(sticks(t)[2], 1f, 1e-4f) && near(t.camBaseX, cx + 2 * t.cameraRadius, 1f), "the base follows a finger that goes past the ring");
        // Thumb at rest: the base slides under it and the camera eases to a stop.
        long t0 = now;
        boolean moved = false;
        float prev = Math.abs(sticks(t)[2]);
        boolean mono = true;
        for (int f = 1; f <= 60; f++) {
            moved |= t.recenter(0.016f, t0 + 16L * f + 100, true);
            float v = Math.abs(sticks(t)[2]);
            mono &= v <= prev + 1e-6f;
            prev = v;
        }
        check(moved && mono && prev < 0.05f, "a resting thumb eases the camera to zero without overshoot");
        // With recenter off the camera keeps turning.
        t.clear();
        down(t, 0, cx, cy);
        move(t, 0, cx + 0.5f * t.cameraRadius, cy);
        for (int f = 1; f <= 60; f++) t.recenter(0.016f, now + 16L * f + 100, false);
        check(near(sticks(t)[2], 0.5f, 1e-3f), "recenter off: the camera keeps its tilt");
        t.clear();
        // Camera dead zone.
        down(t, 0, cx, cy);
        move(t, 0, cx + 0.03f * t.cameraRadius, cy);
        check(sticks(t)[2] == 0f, "tiny camera movement is ignored");
        t.clear();
    }

    /** Random multi-touch: after every event the held buttons are exactly those under a button finger. */
    static void fuzz() {
        Random rnd = new Random(12345);
        String[] layouts = {"", "@s", "@m;face:1.0,-0.5,1.4;zr:-3,2,1;r:0,0,1,0.5,1", "@m;stick:3,-1,1.5;cam:-2,0,0.6"};
        int events = 0;
        for (String ls : layouts)
            for (int run = 0; run < 400; run++) {
                HudTouch t = make(HudModel.parse(ls), rnd.nextInt(4) == 0);
                boolean[] down = new boolean[10];
                for (int step = 0; step < 120; step++) {
                    int id = rnd.nextInt(10);
                    float x = rnd.nextFloat() * W, y = rnd.nextFloat() * H;
                    int r = rnd.nextInt(10);
                    if (r == 0) {
                        t.clear();
                        java.util.Arrays.fill(down, false);
                    } else if (!down[id] || r < 2) {
                        t.down(id, x, y, now += 3);
                        down[id] = true;
                    } else if (r < 7) {
                        t.move(id, x, y, now += 3);
                    } else {
                        t.up(id);
                        down[id] = false;
                    }
                    events++;
                    // Invariants.
                    int[] expect = new int[HudTouch.N];
                    boolean stick = false, cam = false;
                    for (int p = 0; p < 10; p++) {
                        if (t.kind[p] == HudTouch.K_BUTTON && t.on[p] >= 0) expect[t.on[p]]++;
                        if (t.kind[p] == HudTouch.K_STICK) stick |= t.stickPointer == p;
                        if (t.kind[p] == HudTouch.K_CAMERA) cam |= t.camPointer == p;
                        if (!down[p]) check(t.kind[p] == HudTouch.K_NONE && t.on[p] == -1, "lifted finger " + p + " holds nothing");
                        if (t.kind[p] != HudTouch.K_BUTTON) check(t.on[p] == -1, "only a button finger holds a button");
                    }
                    boolean ok = true;
                    for (int b = 0; b < HudTouch.N; b++) ok &= t.press[b] == expect[b] && t.press[b] >= 0;
                    if (!ok) {
                        check(false, "press counts match the fingers (layout '" + ls + "' run " + run + " step " + step + ")");
                        return;
                    }
                    check(t.stickPointer < 0 || stick, "stick finger exists");
                    check(t.camPointer < 0 || cam, "camera finger exists");
                    float[] o = new float[4];
                    t.sticks(o);
                    boolean bounded = true;
                    for (float v : o) bounded &= Math.abs(v) <= 1.0001f && !Float.isNaN(v);
                    check(bounded, "stick outputs stay within -1..1");
                    for (int b = 0; b < HudTouch.N && ls.contains("r:0,0,1,0.5,1"); b++)
                        if (t.hidden[b] && HudModel.SPECS[b].group.equals("r")) check(t.press[b] == 0, "a hidden button is never pressed");
                }
                for (int p = 0; p < 10; p++) if (down[p]) t.up(p);
                check(!t.anyHeld() && t.buttons() == 0, "after every finger lifts nothing is held");
                float[] o = new float[4];
                t.sticks(o);
                check(o[0] == 0 && o[1] == 0 && o[2] == 0 && o[3] == 0, "and both sticks are at rest");
            }
        System.out.println("fuzz: " + events + " random touch events");
    }

    public static void main(String[] args) {
        scenarios();
        hiddenAndAreas();
        camera();
        fuzz();
        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
