package com.dsrnative.app;

import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Host test of HudModel (no Android classes). Prints one line per check and exits non-zero on any failure. */
public final class HudModelTest {
    static int failures, checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL  " + what);
        }
    }

    static boolean near(float a, float b, float eps) {
        return Math.abs(a - b) <= eps;
    }

    /** The parser of builds before sizes/opacity/hidden existed (TouchControls.parseLayout), to check old builds still read new files. */
    static Map<String, float[]> oldParse(String s) {
        Map<String, float[]> m = new java.util.HashMap<>();
        if (s == null) return m;
        for (String part : s.split(";")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            String g = part.substring(0, c).trim().toLowerCase(Locale.ROOT);
            boolean known = false;
            for (String x : new String[] {"face", "dpad", "stick", "zl", "l", "zr", "r", "minus", "plus", "l3", "lock"})
                known |= x.equals(g);
            if (!known) continue;
            String[] n = part.substring(c + 1).split(",");
            if (n.length < 2) continue;
            try {
                float dx = Float.parseFloat(n[0].trim()), dy = Float.parseFloat(n[1].trim());
                float size = n.length > 2 ? Float.parseFloat(n[2].trim()) : 1f;
                m.put(g, new float[] {dx, dy, Math.max(0.5f, Math.min(2f, size))});
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    static final int[][] SCREENS = {{2712, 1220}, {2400, 1080}, {1920, 1080}, {2560, 1600}, {1280, 720}, {2800, 1260}};

    static HudModel.Params params(float l, float t, float r, float b) {
        HudModel.Params p = new HudModel.Params();
        p.insetL = l;
        p.insetT = t;
        p.insetR = r;
        p.insetB = b;
        return p;
    }

    static boolean overlap(float[] a, float[] b, float gap) {
        return a[0] < b[2] + gap && b[0] < a[2] + gap && a[1] < b[3] + gap && b[1] < a[3] + gap;
    }

    static void testFormat() {
        check(HudModel.parse("").isDefault(), "empty layout = defaults");
        check(HudModel.parse(null).base == HudModel.BASE_MOBILE, "null layout = mobile base");
        check(HudModel.format(new HudModel.Layout()).isEmpty(), "default layout formats to empty");

        // A layout saved by the build before this one: no marker -> Switch-style defaults, numbers kept.
        HudModel.Layout old = HudModel.parse("face:1.20,-0.50,1.30;zl:0.10,0.20;bogus:1,1;stick:x,y");
        check(old.base == HudModel.BASE_SWITCH, "legacy layout (no marker) keeps the Switch-style base");
        check(near(old.get("face").dx, 1.2f, 1e-4f) && near(old.get("face").dy, -0.5f, 1e-4f)
                && near(old.get("face").size, 1.3f, 1e-4f), "legacy face offset and size read");
        check(near(old.get("zl").size, 1f, 1e-6f) && near(old.get("zl").alpha, 1f, 1e-6f) && !old.get("zl").hidden,
                "legacy entry without size = size 1, opaque, shown");
        check(!old.cfg.containsKey("bogus") && !old.cfg.containsKey("stick"), "unknown groups and bad numbers skipped");
        check(HudModel.format(old).startsWith("@s;"), "legacy layout is rewritten with an explicit @s marker");
        check(HudModel.same("face:1.2,-0.5,1.3", "@s;face:1.20,-0.50,1.30"), "same() ignores formatting and the marker");

        // Round trip, including opacity and hidden.
        Random rnd = new Random(7);
        for (int i = 0; i < 300; i++) {
            HudModel.Layout l = new HudModel.Layout();
            l.base = rnd.nextBoolean() ? HudModel.BASE_MOBILE : HudModel.BASE_SWITCH;
            for (String g : HudModel.GROUPS) {
                if (rnd.nextInt(3) == 0) continue;
                HudModel.Cfg c = l.edit(g);
                c.dx = Math.round((rnd.nextFloat() * 6 - 3) * 100) / 100f;
                c.dy = Math.round((rnd.nextFloat() * 6 - 3) * 100) / 100f;
                c.size = Math.round((0.5f + rnd.nextFloat() * 1.5f) * 10) / 10f;
                c.alpha = Math.round((0.1f + rnd.nextFloat() * 0.9f) * 10) / 10f;
                c.hidden = HudModel.canHide(g) && rnd.nextInt(4) == 0;
            }
            String s = HudModel.format(l);
            HudModel.Layout r = HudModel.parse(s);
            boolean ok = r.base == l.base || (l.base == HudModel.BASE_MOBILE && s.isEmpty());
            for (String g : HudModel.GROUPS) {
                HudModel.Cfg a = l.get(g), b = r.get(g);
                ok &= near(a.dx, b.dx, 0.006f) && near(a.dy, b.dy, 0.006f) && near(a.size, b.size, 0.006f)
                        && near(a.alpha, b.alpha, 0.006f) && a.hidden == b.hidden;
            }
            check(ok, "round trip: " + s);
            if (!ok) break;
            check(HudModel.format(r).equals(s), "format is stable: " + s);
        }

        // Builds from before this one still read a new layout (first three numbers; the marker part is skipped).
        HudModel.Layout nl = new HudModel.Layout();
        HudModel.Cfg c = nl.edit("r");
        c.dx = 1f;
        c.dy = 2f;
        c.size = 1.5f;
        c.alpha = 0.4f;
        c.hidden = true;
        Map<String, float[]> o = oldParse(HudModel.format(nl));
        check(o.containsKey("r") && near(o.get("r")[0], 1f, 1e-4f) && near(o.get("r")[2], 1.5f, 1e-4f),
                "an older build reads a new layout");

        // Limits.
        HudModel.Layout lim = HudModel.parse("@m;face:0,0,9,5,0;l:0,0,0.01;minus:0,0,1,1,1;l3:99,0");
        check(near(lim.get("face").size, 2f, 1e-6f) && near(lim.get("face").alpha, 1f, 1e-6f), "size and opacity clamped");
        check(near(lim.get("l").size, 0.5f, 1e-6f), "size has a floor");
        check(!lim.get("minus").hidden, "minus cannot be hidden (needed to open the menu)");
        check(!lim.cfg.containsKey("l3"), "absurd offsets skipped");
    }

    static void testPresets() {
        Map<String, String> presets = HudModel.builtinPresets();
        check(presets.size() == 5, "five built-in presets");
        check(presets.get("mobile").isEmpty() && presets.get("switch").equals("@s"), "mobile = defaults, switch = classic");
        for (Map.Entry<String, String> e : presets.entrySet())
            check(HudModel.format(HudModel.parse(e.getValue())).equals(e.getValue()), "preset " + e.getKey() + " is canonical");
        check(!HudModel.same(presets.get("compact"), presets.get("large")), "compact and large differ");
    }

    /** Every element of every preset is fully on screen and elements of different groups do not touch. */
    static void testGeometry() {
        float[][] insets = {{0, 0, 0, 0}, {120, 0, 0, 0}, {0, 0, 110, 0}, {90, 30, 90, 30}};
        int bad = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, String> pe : HudModel.builtinPresets().entrySet()) {
            HudModel.Layout lay = HudModel.parse(pe.getValue());
            for (int[] sc : SCREENS) {
                for (float[] in : insets) {
                    HudModel.Params p = params(in[0], in[1], in[2], in[3]);
                    HudModel.Geo g = HudModel.compute(lay, sc[0], sc[1], p);
                    String where = pe.getKey() + " " + sc[0] + "x" + sc[1] + " insets " + java.util.Arrays.toString(in);
                    for (String grp : HudModel.GROUPS) {
                        float[] b = HudModel.bounds(g, grp);
                        boolean in_ = b[0] >= p.insetL - 0.5f && b[1] >= p.insetT - 0.5f && b[2] <= sc[0] - p.insetR + 0.5f
                                && b[3] <= sc[1] - p.insetB + 0.5f;
                        if (!in_) {
                            bad++;
                            if (seen.add("off " + pe.getKey() + grp)) System.out.printf(Locale.ROOT, "FAIL  off screen: %s group %s %s%n", where, grp, java.util.Arrays.toString(b));
                        }
                    }
                    // Overlaps: button against button of another group, and the stick circle against buttons
                    // (the camera area covers the right half on purpose, and a group's own buttons sit together).
                    for (int i = 0; i < HudModel.SPECS.length; i++) {
                        float[] bi = {g.cx[i] - g.bw[i] / 2, g.cy[i] - g.bh[i] / 2, g.cx[i] + g.bw[i] / 2, g.cy[i] + g.bh[i] / 2};
                        for (int j = i + 1; j < HudModel.SPECS.length; j++) {
                            if (HudModel.SPECS[i].group.equals(HudModel.SPECS[j].group)) continue;
                            float[] bj = {g.cx[j] - g.bw[j] / 2, g.cy[j] - g.bh[j] / 2, g.cx[j] + g.bw[j] / 2, g.cy[j] + g.bh[j] / 2};
                            if (overlap(bi, bj, 0f)) {
                                bad++;
                                if (seen.add(pe.getKey() + HudModel.SPECS[i].group + HudModel.SPECS[j].group)) System.out.println("FAIL  overlap: " + where + " " + HudModel.SPECS[i].group + "/" + HudModel.SPECS[j].group);
                            }
                        }
                        // Stick circle vs the button's nearest point.
                        float nx = Math.max(bi[0], Math.min(g.stickX, bi[2])), ny = Math.max(bi[1], Math.min(g.stickY, bi[3]));
                        if (Math.hypot(nx - g.stickX, ny - g.stickY) < g.stickR) {
                            bad++;
                            if (seen.add(pe.getKey() + "stick" + HudModel.SPECS[i].group)) System.out.println("FAIL  overlap: " + where + " stick/" + HudModel.SPECS[i].group);
                        }
                    }
                }
            }
        }
        check(bad == 0, "presets: all on screen, no overlaps (" + bad + " problems)");
    }

    static void testPortability() {
        // Same layout, two screens of the same aspect ratio: everything in the same place in layout units.
        HudModel.Layout lay = HudModel.parse(HudModel.builtinPresets().get("twothumb"));
        HudModel.Geo a = HudModel.compute(lay, 1920, 1080, params(0, 0, 0, 0)), b = HudModel.compute(lay, 2400, 1350, params(0, 0, 0, 0));
        boolean ok = true;
        for (int i = 0; i < HudModel.SPECS.length; i++)
            ok &= near(a.cx[i] / a.u, b.cx[i] / b.u, 0.01f) && near(a.cy[i] / a.u, b.cy[i] / b.u, 0.01f)
                    && near(a.bw[i] / a.u, b.bw[i] / b.u, 0.01f);
        check(ok, "layout is the same in layout units on screens of one aspect ratio");

        // Original Switch-style layout is untouched (positions from before this change, 1920x1080, u = 108).
        HudModel.Geo s = HudModel.compute(HudModel.parse("@s"), 1920, 1080, params(0, 0, 0, 0));
        float u = 108f;
        int ia = HudModel.indexOfBit(HudModel.A), ib = HudModel.indexOfBit(HudModel.B), izl = HudModel.indexOfBit(HudModel.ZL),
                iup = HudModel.indexOfBit(HudModel.UP);
        check(near(s.cx[ia], 1920 - 3f * u + 1.45f * u, 0.01f) && near(s.cy[ia], 1080 - 3f * u, 0.01f) && near(s.bw[ia], 1.3f * u, 0.01f),
                "Switch-style A button where it always was");
        check(near(s.cx[ib], 1920 - 3f * u, 0.01f) && near(s.cy[ib], 1080 - 3f * u + 1.45f * u, 0.01f), "Switch-style B where it was");
        check(near(s.cx[izl], 1.7f * u, 0.01f) && near(s.cy[izl], 1.0f * u, 0.01f) && near(s.bw[izl], 2.4f * u, 0.01f), "Switch-style ZL where it was");
        check(near(s.cx[iup], 6.9f * u, 0.01f) && near(s.cy[iup], 1080 - 2.0f * u - 0.95f * u, 0.01f), "Switch-style d-pad where it was");
        check(near(s.stickX, 2.8f * u, 0.01f) && near(s.stickY, 1080 - 3f * u, 0.01f) && near(s.stickR, 1.6f * u, 0.01f), "Switch-style stick where it was");

        // Mappings never change: every bit appears once, and the Switch bit values are the ones in src/hle/input.h.
        int all = 0;
        for (HudModel.Spec sp : HudModel.SPECS) {
            check((all & sp.bit) == 0, "bit used once: " + sp.label);
            all |= sp.bit;
        }
        check(all == 0xFFFF, "all 16 Switch buttons are there");
        check(HudModel.A == 1 && HudModel.B == 2 && HudModel.X == 4 && HudModel.Y == 8 && HudModel.L3 == 16 && HudModel.R3 == 32
                && HudModel.L == 64 && HudModel.R == 128 && HudModel.ZL == 256 && HudModel.ZR == 512 && HudModel.PLUS == 1024
                && HudModel.MINUS == 2048 && HudModel.LEFT == 4096 && HudModel.UP == 8192 && HudModel.RIGHT == 16384
                && HudModel.DOWN == 32768, "button bit values unchanged");

        // Size, spacing and a moved element change only what they should.
        HudModel.Layout big = HudModel.parse("@m;face:0,0,2");
        HudModel.Geo d0 = HudModel.compute(new HudModel.Layout(), 2712, 1220, params(0, 0, 0, 0)), d1 = HudModel.compute(big, 2712, 1220, params(0, 0, 0, 0));
        check(d1.bw[ia] > d0.bw[ia] * 1.9f && near(d1.bw[izl], d0.bw[izl], 0.01f), "resizing the face buttons leaves the others alone");
        check(near(d1.stickR, d0.stickR, 0.01f), "... and the stick");
        HudModel.Params wide = params(0, 0, 0, 0);
        wide.spacing = 1.4f;
        HudModel.Geo d2 = HudModel.compute(new HudModel.Layout(), 2712, 1220, wide);
        check(near(d2.bw[ia], d0.bw[ia], 0.01f) && Math.abs(d2.cx[ia] - d2.cx[HudModel.indexOfBit(HudModel.Y)])
                > Math.abs(d0.cx[ia] - d0.cx[HudModel.indexOfBit(HudModel.Y)]) * 1.3f, "spacing moves buttons apart, same size");
    }

    /** Resizing, moving, fading or hiding one element must not move or resize any other. */
    static void testIndependence() {
        for (int base = 0; base < 2; base++)
            for (String changed : HudModel.GROUPS) {
                HudModel.Layout ref = new HudModel.Layout(), mod = new HudModel.Layout();
                ref.base = mod.base = base;
                HudModel.Cfg c = mod.edit(changed);
                c.size = 1.7f;
                c.dx = 0.6f;
                c.dy = -0.4f;
                c.alpha = 0.3f;
                c.hidden = HudModel.canHide(changed);
                HudModel.Geo a = HudModel.compute(ref, 2712, 1220, params(0, 0, 0, 0)), b = HudModel.compute(mod, 2712, 1220, params(0, 0, 0, 0));
                boolean ok = true;
                for (String g : HudModel.GROUPS) {
                    if (g.equals(changed)) continue;
                    float[] x = HudModel.bounds(a, g), y = HudModel.bounds(b, g);
                    for (int i = 0; i < 4; i++) ok &= near(x[i], y[i], 0.001f);
                }
                check(ok, "changing '" + changed + "' leaves every other element alone (base " + base + ")");
            }
    }

    static void testStick() {
        float[] o = new float[2];
        HudModel.stickAxes(0, 0, 100, 0.08f, o);
        check(o[0] == 0 && o[1] == 0, "centre = 0");
        HudModel.stickAxes(5, 0, 100, 0.08f, o);
        check(o[0] == 0 && o[1] == 0, "inside the dead zone = 0");
        HudModel.stickAxes(100, 0, 100, 0.08f, o);
        check(near(o[0], 1f, 1e-5f) && near(o[1], 0f, 1e-5f), "full right at the edge");
        HudModel.stickAxes(500, 0, 100, 0.08f, o);
        check(near(o[0], 1f, 1e-5f), "past the edge stays at full tilt (finger outside the base)");
        HudModel.stickAxes(0, -100, 100, 0.08f, o);
        check(near(o[1], 1f, 1e-5f), "up on screen = +y");
        HudModel.stickAxes(70.7f, 70.7f, 100, 0.0f, o);
        check(near(o[0], 0.707f, 0.01f) && near(o[1], -0.707f, 0.01f), "diagonal keeps its angle");
        float prev = -1;
        boolean mono = true, cont = true;
        for (float d = 0; d <= 100; d += 0.5f) {
            HudModel.stickAxes(d, 0, 100, 0.12f, o);
            mono &= o[0] >= prev - 1e-6f;
            if (prev >= 0 && o[0] - prev > 0.02f) cont = false;
            prev = o[0];
        }
        check(mono && cont, "output rises smoothly with no jump at the dead zone");
        // Direction is exact at any magnitude (no axis snapping).
        boolean dir = true;
        for (float m = 20; m <= 100; m += 10) {
            float dx = (float) (m * Math.cos(0.6)), dy = (float) (-m * Math.sin(0.6));
            HudModel.stickAxes(dx, dy, 100, 0.08f, o);
            dir &= near((float) Math.atan2(o[1], o[0]), 0.6f, 0.001f);
        }
        check(dir, "direction preserved at every magnitude");
    }

    public static void main(String[] args) {
        testFormat();
        testPresets();
        testGeometry();
        testPortability();
        testIndependence();
        testStick();
        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
