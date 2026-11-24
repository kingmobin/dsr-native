package com.dsrnative.app;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The touch HUD without any Android classes (so it runs in a host unit test, tests/hud): which elements there are, the
 * saved layout format (settings.txt `touch_layout`), where every element sits for a given screen, and the stick maths.
 * TouchControls draws it and feeds it touches.
 *
 * Layout string: `[@m|@s;]group:dx,dy[,size[,opacity[,hidden]]];...`
 *  - dx, dy: offset from the group's default spot, in layout units (a tenth of the screen's short side), so a layout
 *    keeps its place on other screen sizes and aspect ratios.
 *  - size: factor on the default size (0.5 to 2); opacity: factor on the overall opacity (0.1 to 1); hidden: 1 = hidden.
 *  - `@m` = offsets are from the mobile defaults, `@s` = from the original Switch-style defaults. A string with no
 *    marker is one saved before the marker existed, so it is from the Switch-style defaults. The empty string is
 *    "nothing customised": the mobile defaults.
 * Older builds read the first three numbers and skip the rest, so a newer layout is not lost by going back.
 */
final class HudModel {
    private HudModel() {}

    // nn::hid button bits (src/hle/input.h). Unchanged: the game's mapping does not depend on how the HUD looks.
    static final int A = 1, B = 1 << 1, X = 1 << 2, Y = 1 << 3, L3 = 1 << 4, R3 = 1 << 5, L = 1 << 6, R = 1 << 7,
            ZL = 1 << 8, ZR = 1 << 9, PLUS = 1 << 10, MINUS = 1 << 11, LEFT = 1 << 12, UP = 1 << 13, RIGHT = 1 << 14,
            DOWN = 1 << 15;

    /** Elements that can be moved, resized, faded and hidden. `cam` is the area where the camera stick starts. */
    static final String[] GROUPS = {"face", "dpad", "stick", "cam", "zl", "l", "zr", "r", "minus", "plus", "l3", "lock"};

    static final float SIZE_MIN = 0.5f, SIZE_MAX = 2.0f, SIZE_STEP = 0.1f;
    static final float ALPHA_MIN = 0.1f, ALPHA_STEP = 0.1f;

    static final int BASE_MOBILE = 0, BASE_SWITCH = 1;

    /** One button: what it presses, how it is drawn and which element it belongs to. */
    static final class Spec {
        final String label, group;
        final int bit;
        final boolean round;
        Spec(String label, int bit, boolean round, String group) {
            this.label = label;
            this.bit = bit;
            this.round = round;
            this.group = group;
        }
    }

    static final Spec[] SPECS = {
            new Spec("A", A, true, "face"), new Spec("B", B, true, "face"), new Spec("X", X, true, "face"),
            new Spec("Y", Y, true, "face"), new Spec("L", L, false, "l"), new Spec("ZL", ZL, false, "zl"),
            new Spec("R", R, false, "r"), new Spec("ZR", ZR, false, "zr"), new Spec("\u2212", MINUS, true, "minus"),
            new Spec("+", PLUS, true, "plus"), new Spec("\u25B2", UP, false, "dpad"),
            new Spec("\u25BC", DOWN, false, "dpad"), new Spec("\u25C0", LEFT, false, "dpad"),
            new Spec("\u25B6", RIGHT, false, "dpad"), new Spec("L3", L3, true, "l3"),
            new Spec("LOCK", R3, true, "lock")};

    static int indexOfBit(int bit) {
        for (int i = 0; i < SPECS.length; i++)
            if (SPECS[i].bit == bit) return i;
        return -1;
    }

    static boolean isGroup(String g) {
        for (String x : GROUPS)
            if (x.equals(g)) return true;
        return false;
    }

    /** - and + together open the settings menu, so they cannot both go (or either: the combination needs both). */
    static boolean canHide(String group) {
        return !"minus".equals(group) && !"plus".equals(group);
    }

    // ---- layout (what the player customised) ---------------------------------------------------------------------

    static final class Cfg {
        float dx, dy, size = 1f, alpha = 1f;
        boolean hidden;

        Cfg copy() {
            Cfg c = new Cfg();
            c.dx = dx;
            c.dy = dy;
            c.size = size;
            c.alpha = alpha;
            c.hidden = hidden;
            return c;
        }

        boolean isDefault() {
            return Math.abs(dx) < 0.005f && Math.abs(dy) < 0.005f && Math.abs(size - 1f) < 0.005f
                    && Math.abs(alpha - 1f) < 0.005f && !hidden;
        }
    }

    static final class Layout {
        int base = BASE_MOBILE;
        final Map<String, Cfg> cfg = new HashMap<>();

        /** The settings of a group (the stored one, or the defaults when it was never changed). */
        Cfg get(String g) {
            Cfg c = cfg.get(g);
            return c != null ? c : DEFAULT_CFG;
        }

        /** The settings of a group to change: created when missing. */
        Cfg edit(String g) {
            Cfg c = cfg.get(g);
            if (c == null) {
                c = new Cfg();
                cfg.put(g, c);
            }
            return c;
        }

        Layout copy() {
            Layout l = new Layout();
            l.base = base;
            for (Map.Entry<String, Cfg> e : cfg.entrySet()) l.cfg.put(e.getKey(), e.getValue().copy());
            return l;
        }

        boolean isDefault() {
            if (base != BASE_MOBILE) return false;
            for (Cfg c : cfg.values())
                if (!c.isDefault()) return false;
            return true;
        }
    }

    private static final Cfg DEFAULT_CFG = new Cfg();

    private static float clampSize(float s) {
        return Math.max(SIZE_MIN, Math.min(SIZE_MAX, s));
    }

    private static float clampAlpha(float a) {
        return Math.max(ALPHA_MIN, Math.min(1f, a));
    }

    /** settings.txt touch_layout -> Layout (unknown groups and bad numbers are skipped). */
    static Layout parse(String s) {
        Layout l = new Layout();
        if (s == null || s.trim().isEmpty()) return l;
        l.base = BASE_SWITCH;  // saved before the marker existed
        for (String part : s.split(";")) {
            part = part.trim();
            if (part.equals("@m")) {
                l.base = BASE_MOBILE;
                continue;
            }
            if (part.equals("@s")) {
                l.base = BASE_SWITCH;
                continue;
            }
            int c = part.indexOf(':');
            if (c <= 0) continue;
            String g = part.substring(0, c).trim().toLowerCase(Locale.ROOT);
            if (!isGroup(g)) continue;
            String[] n = part.substring(c + 1).split(",");
            if (n.length < 2) continue;
            try {
                Cfg k = new Cfg();
                k.dx = Float.parseFloat(n[0].trim());
                k.dy = Float.parseFloat(n[1].trim());
                if (n.length > 2) k.size = Float.parseFloat(n[2].trim());
                if (n.length > 3) k.alpha = Float.parseFloat(n[3].trim());
                if (n.length > 4) k.hidden = n[4].trim().equals("1");
                if (!Float.isFinite(k.dx) || !Float.isFinite(k.dy) || Math.abs(k.dx) >= 50 || Math.abs(k.dy) >= 50
                        || !Float.isFinite(k.size) || !Float.isFinite(k.alpha))
                    continue;
                k.size = clampSize(k.size);
                k.alpha = clampAlpha(k.alpha);
                if (!canHide(g)) k.hidden = false;
                l.cfg.put(g, k);
            } catch (NumberFormatException ignored) {
            }
        }
        return l;
    }

    static String format(Layout l) {
        StringBuilder b = new StringBuilder();
        for (String g : GROUPS) {
            Cfg o = l.cfg.get(g);
            if (o == null || o.isDefault()) continue;
            boolean sized = Math.abs(o.size - 1f) >= 0.005f, faded = Math.abs(o.alpha - 1f) >= 0.005f;
            if (b.length() > 0) b.append(';');
            b.append(String.format(Locale.ROOT, "%s:%.2f,%.2f", g, o.dx, o.dy));
            if (sized || faded || o.hidden) b.append(String.format(Locale.ROOT, ",%.2f", o.size));
            if (faded || o.hidden) b.append(String.format(Locale.ROOT, ",%.2f", o.alpha));
            if (o.hidden) b.append(",1");
        }
        if (l.base == BASE_MOBILE) return b.length() == 0 ? "" : "@m;" + b;
        return b.length() == 0 ? "@s" : "@s;" + b;
    }

    /** True when two saved layouts mean the same thing (so a saved preset can be shown as the current one). */
    static boolean same(String a, String b) {
        return format(parse(a)).equals(format(parse(b)));
    }

    // ---- built-in presets ---------------------------------------------------------------------------------------

    /** Preset id -> layout string, in the order they are listed. Names: Strings key "preset_" + id. */
    static Map<String, String> builtinPresets() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("mobile", "");
        m.put("twothumb", twoThumb());
        m.put("compact", compact());
        m.put("large", large());
        m.put("switch", "@s");
        return m;
    }

    private static void put(Layout l, String g, float dx, float dy, float size) {
        Cfg c = l.edit(g);
        c.dx = dx;
        c.dy = dy;
        c.size = size;
    }

    /**
     * Both thumbs do everything: the shoulder buttons come down to where a thumb can roll onto them, above the stick
     * (L, ZL) and the face buttons (R, ZR).
     */
    private static String twoThumb() {
        Layout l = new Layout();
        put(l, "l", 1.4f, 1.45f, 1f);
        put(l, "zl", 1.4f, 1.6f, 1f);
        put(l, "r", -1.1f, 1.15f, 1f);
        put(l, "zr", -1.1f, 1.3f, 1f);
        return format(l);
    }

    /** Smaller controls pushed into the corners: more of the game shows. */
    private static String compact() {
        Layout l = new Layout();
        put(l, "face", 0.5f, 0.4f, 0.8f);
        put(l, "stick", -0.5f, 0.4f, 0.8f);
        put(l, "dpad", -0.6f, 0.4f, 0.8f);
        put(l, "lock", 0.1f, 0.2f, 0.8f);
        put(l, "zl", -0.3f, -0.2f, 0.8f);
        put(l, "l", -0.3f, -0.2f, 0.8f);
        put(l, "zr", 0.3f, -0.2f, 0.8f);
        put(l, "r", 0.3f, -0.2f, 0.8f);
        put(l, "l3", -0.3f, 0.1f, 0.8f);
        return format(l);
    }

    /** Bigger buttons and sticks for easier thumbs. */
    private static String large() {
        Layout l = new Layout();
        put(l, "face", -0.3f, -0.4f, 1.3f);
        put(l, "stick", 0.3f, -0.4f, 1.25f);
        put(l, "dpad", 0f, 0f, 0.85f);
        put(l, "lock", -0.2f, -0.3f, 1f);
        put(l, "zl", 0.2f, -0.1f, 1.2f);
        put(l, "l", 0.2f, 0.1f, 1.2f);
        put(l, "zr", -0.2f, -0.1f, 1.2f);
        put(l, "r", -0.2f, 0.0f, 1.2f);
        put(l, "l3", 0f, 0f, 1f);
        return format(l);
    }

    // ---- geometry ------------------------------------------------------------------------------------------------

    /** Global appearance settings that change geometry (settings.txt touch_size, touch_stick_size, touch_spacing). */
    static final class Params {
        float buttonScale = 1f, stickScale = 1f, spacing = 1f;
        /** Safe area: display cutouts etc. (px). */
        float insetL, insetT, insetR, insetB;
    }

    /** Where everything is for one screen size. */
    static final class Geo {
        float u, w, h;
        final float[] cx = new float[SPECS.length], cy = new float[SPECS.length], bw = new float[SPECS.length],
                bh = new float[SPECS.length];
        float stickX, stickY, stickR;
        float camL, camT, camR, camB;
        /** Per group: default spot {x, y} and where it is {x, y} (px). */
        final Map<String, float[]> dflt = new HashMap<>(), placed = new HashMap<>();
        /** Per group: half extents of everything in it {hw, hh} (px). */
        final Map<String, float[]> half = new HashMap<>();
    }

    /** Keeps a group's centre far enough inside the safe area for all of it to show. */
    private static float[] clampIn(Geo g, Params p, float x, float y, float hw, float hh) {
        float l = p.insetL + hw, r = g.w - p.insetR - hw, t = p.insetT + hh, b = g.h - p.insetB - hh;
        if (r < l) x = (l + r) / 2f;
        else x = Math.max(l, Math.min(r, x));
        if (b < t) y = (t + b) / 2f;
        else y = Math.max(t, Math.min(b, y));
        return new float[] {x, y};
    }

    private static void at(Geo g, Params p, Layout lay, String group, float defX, float defY, float hw, float hh) {
        g.dflt.put(group, new float[] {defX, defY});
        Cfg c = lay.get(group);
        g.half.put(group, new float[] {hw, hh});
        g.placed.put(group, clampIn(g, p, defX + c.dx * g.u, defY + c.dy * g.u, hw, hh));
    }

    private static void place(Geo g, int bit, float cx, float cy, float w, float h) {
        int i = indexOfBit(bit);
        g.cx[i] = cx;
        g.cy[i] = cy;
        g.bw[i] = w;
        g.bh[i] = h;
    }

    /**
     * Places every element for a w x h screen. Edge-anchored defaults are measured from the safe area, so nothing
     * starts under a camera cutout. The same offsets work for every screen: positions and sizes are in layout units.
     */
    static Geo compute(Layout lay, float w, float h, Params p) {
        final Geo g = new Geo();
        g.w = w;
        g.h = h;
        final float cw = w - p.insetL - p.insetR, ch = h - p.insetT - p.insetB;
        final float u = Math.min(cw, ch) / 10f;  // layout unit
        g.u = u;
        final float x0 = p.insetL, y0 = p.insetT, x1 = w - p.insetR, y1 = h - p.insetB;
        final boolean sw = lay.base == BASE_SWITCH;
        final float bs = p.buttonScale, ss = p.stickScale, sp = p.spacing;

        // Face buttons: Switch layout (X top, A right, B bottom, Y left).
        float fs = lay.get("face").size * bs;
        float fd = (sw ? 1.45f : 1.5f) * u * fs * sp, fb = (sw ? 1.3f : 1.5f) * u * fs;
        at(g, p, lay, "face", sw ? x1 - 3.0f * u : x1 - 3.4f * u, sw ? y1 - 3.0f * u : y1 - 3.1f * u,
                fd + fb / 2, fd + fb / 2);
        float[] f = g.placed.get("face");
        place(g, A, f[0] + fd, f[1], fb, fb);
        place(g, B, f[0], f[1] + fd, fb, fb);
        place(g, X, f[0], f[1] - fd, fb, fb);
        place(g, Y, f[0] - fd, f[1], fb, fb);

        // LOCK (right stick click).
        float ls = lay.get("lock").size * bs, lk = (sw ? 1.3f : 1.4f) * u * ls;
        at(g, p, lay, "lock", sw ? x1 - 5.6f * u : x1 - 5.9f * u, sw ? y1 - 1.2f * u : y1 - 1.0f * u, lk / 2, lk / 2);
        float[] q = g.placed.get("lock");
        place(g, R3, q[0], q[1], lk, lk);

        // Shoulders: ZL / ZR in the top corners, L / R below them.
        float shw = (sw ? 2.4f : 2.8f) * u, shh = (sw ? 1.1f : 1.25f) * u;
        String[] sg = {"zl", "l", "zr", "r"};
        int[] sb = {ZL, L, ZR, R};
        float[] sx = {x0 + (sw ? 1.7f : 2.3f) * u, x0 + (sw ? 1.7f : 2.3f) * u, x1 - (sw ? 1.7f : 2.3f) * u,
                x1 - (sw ? 1.7f : 2.3f) * u};
        float[] sy = {y0 + (sw ? 1.0f : 0.95f) * u, y0 + (sw ? 2.5f : 2.55f) * u, y0 + (sw ? 1.0f : 0.95f) * u,
                y0 + (sw ? 2.5f : 2.55f) * u};
        for (int i = 0; i < 4; i++) {
            float k = lay.get(sg[i]).size * bs;
            at(g, p, lay, sg[i], sx[i], sy[i], shw * k / 2, shh * k / 2);
            float[] s = g.placed.get(sg[i]);
            place(g, sb[i], s[0], s[1], shw * k, shh * k);
        }

        // Minus / plus at the top centre.
        float ms = (sw ? 1.0f : 0.95f) * u;
        float mid = (x0 + x1) / 2f, mgap = (sw ? 1.3f : 1.2f) * u, my = y0 + (sw ? 0.9f : 0.8f) * u;
        float k = lay.get("minus").size * bs;
        at(g, p, lay, "minus", mid - mgap, my, ms * k / 2, ms * k / 2);
        float[] s = g.placed.get("minus");
        place(g, MINUS, s[0], s[1], ms * k, ms * k);
        k = lay.get("plus").size * bs;
        at(g, p, lay, "plus", mid + mgap, my, ms * k / 2, ms * k / 2);
        s = g.placed.get("plus");
        place(g, PLUS, s[0], s[1], ms * k, ms * k);

        // D-pad. Mobile: between the stick and LOCK, low, so the left thumb can reach it; its keys are big enough to hit.
        // On a narrower screen (4:3, or a cutout eating the width) its default size shrinks to fit the gap.
        // The default depends on the screen and the global style only, never on another element's own settings:
        // changing one element must not move another.
        float stickRight = x0 + 3.7f * u + 1.8f * u * ss, lockLeft = x1 - 5.9f * u - 0.7f * u * bs;
        float fit = sw ? 1f : Math.max(0.7f, Math.min(1f, (lockLeft - stickRight - 0.4f * u) / (3.1f * u)));
        float ds_ = lay.get("dpad").size * bs * fit, dstep = (sw ? 0.95f : 1.05f) * u * ds_ * sp,
                dkey = (sw ? 0.9f : 1.0f) * u * ds_;
        // Between the stick and LOCK, but never further than a left-thumb roll from the stick (wide screens).
        float dpadX = sw ? x0 + 6.9f * u : Math.min((stickRight + lockLeft) / 2f, stickRight + 2.45f * u);
        at(g, p, lay, "dpad", dpadX, y1 - (sw ? 2.0f : 1.9f) * u, dstep + dkey / 2, dstep + dkey / 2);
        s = g.placed.get("dpad");
        place(g, UP, s[0], s[1] - dstep, dkey, dkey);
        place(g, DOWN, s[0], s[1] + dstep, dkey, dkey);
        place(g, LEFT, s[0] - dstep, s[1], dkey, dkey);
        place(g, RIGHT, s[0] + dstep, s[1], dkey, dkey);

        // L3 (left stick click), under the stick.
        float l3s = lay.get("l3").size * bs, l3 = (sw ? 1.0f : 1.0f) * u * l3s;
        at(g, p, lay, "l3", sw ? x0 + 4.7f * u : x0 + 5.2f * u, y1 - (sw ? 0.9f : 0.8f) * u, l3 / 2, l3 / 2);
        s = g.placed.get("l3");
        place(g, L3, s[0], s[1], l3, l3);

        // Stick: where it rests, and its size.
        g.stickR = (sw ? 1.6f : 1.8f) * u * lay.get("stick").size * ss;
        at(g, p, lay, "stick", x0 + (sw ? 2.8f : 3.7f) * u, y1 - (sw ? 3.0f : 3.3f) * u, g.stickR, g.stickR);
        s = g.placed.get("stick");
        g.stickX = s[0];
        g.stickY = s[1];

        // Camera area: the right half by default (what the camera stick has always used); a finger on a button there
        // presses the button instead.
        float cs = lay.get("cam").size, chw = cw * 0.25f * cs, chh = ch * 0.5f * cs;
        at(g, p, lay, "cam", x0 + cw * 0.75f, y0 + ch * 0.5f, Math.min(chw, cw / 2), Math.min(chh, ch / 2));
        s = g.placed.get("cam");
        g.camL = Math.max(x0, s[0] - chw);
        g.camR = Math.min(x1, s[0] + chw);
        g.camT = Math.max(y0, s[1] - chh);
        g.camB = Math.min(y1, s[1] + chh);
        return g;
    }

    /** The bounding box of a group: {left, top, right, bottom}. */
    static float[] bounds(Geo g, String group) {
        if ("stick".equals(group)) return new float[] {g.stickX - g.stickR, g.stickY - g.stickR, g.stickX + g.stickR, g.stickY + g.stickR};
        if ("cam".equals(group)) return new float[] {g.camL, g.camT, g.camR, g.camB};
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i < SPECS.length; i++) {
            if (!SPECS[i].group.equals(group)) continue;
            l = Math.min(l, g.cx[i] - g.bw[i] / 2);
            r = Math.max(r, g.cx[i] + g.bw[i] / 2);
            t = Math.min(t, g.cy[i] - g.bh[i] / 2);
            b = Math.max(b, g.cy[i] + g.bh[i] / 2);
        }
        return new float[] {l, t, r, b};
    }

    // ---- stick maths -----------------------------------------------------------------------------------------------

    /**
     * Finger offset from the stick's centre (px, y down) -> axes (-1..1, y up). Radial dead zone with the rest rescaled,
     * so the output rises smoothly from 0 at the dead zone to 1 at the edge, with the direction kept exactly.
     */
    static void stickAxes(float dx, float dy, float radius, float deadzone, float[] out) {
        out[0] = out[1] = 0f;
        if (radius <= 0f) return;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 0f) return;
        float mag = Math.min(1f, len / radius);
        float dz = Math.max(0f, Math.min(0.5f, deadzone));
        if (mag <= dz) return;
        float scaled = (mag - dz) / (1f - dz);
        out[0] = dx / len * scaled;
        out[1] = -dy / len * scaled;
    }
}
