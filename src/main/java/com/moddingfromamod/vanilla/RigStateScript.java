package com.moddingfromamod.vanilla;

/**
 * <b>A creature's pose on a fixed schedule, for a film</b> (the owner: "the house's AI must not decide whether
 * the thing happens"). {@code -Ptestprops=rigstate=napcycle} (or {@code swimcycle}) replaces the gait's weights for
 * every driven creature with a script keyed on its age, so a sleep, a wake, a swim stroke and a pitch happen on cue, on
 * the parts path and the native path alike (both draw from these weights). Off by default; never on a house. Pure.
 */
public final class RigStateScript {

    private RigStateScript() {
    }

    /** {@code napcycle}: one period, in ticks. */
    public static final int NAP_PERIOD = 240;
    /** {@code swimcycle}: one period, in ticks. */
    public static final int SWIM_PERIOD = 80;
    /** {@code swimcycle}: the travel pitch's swing, degrees each way. */
    public static final float SWIM_PITCH = 30f;

    /** The script's name from the property, or null (no script). */
    public static String named(String property) {
        if (property == null) {
            return null;
        }
        String s = property.trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("napcycle") || s.equals("swimcycle") || s.equals("showcase") || s.equals("liesleep") ? s : null;
    }

    /**
     * The weights at {@code tick} ticks into the script. {@code napcycle} (12 s): awake 1 s, lies down and sleeps over
     * 1.2 s, asleep 5 s, wakes over 1.2 s, awake 3.6 s. {@code swimcycle} (4 s): swimming and travelling throughout, the
     * travel pitch swinging +-30 degrees (nose up and down) on a sine.
     */
    public static RigLocomotion.Weights at(String name, long tick) {
        if ("napcycle".equals(name)) {
            long t = Math.floorMod(tick, NAP_PERIOD);
            float s;
            if (t < 20) {
                s = 0f;
            } else if (t < 44) {
                s = (t - 20) / 24f;
            } else if (t < 144) {
                s = 1f;
            } else if (t < 168) {
                s = 1f - (t - 144) / 24f;
            } else {
                s = 0f;
            }
            return new RigLocomotion.Weights(0, 0, 0, 0, s, 0, 0, 0, 0, 0);
        }
        if ("swimcycle".equals(name)) {
            float pitch = (float) (SWIM_PITCH * Math.sin(2 * Math.PI * Math.floorMod(tick, SWIM_PERIOD) / SWIM_PERIOD));
            return new RigLocomotion.Weights(0, 1, 0, 0, 0, 0, 0, 0, pitch, 1);
        }
        if ("showcase".equals(name)) {
            return showcase(Math.floorMod(tick, SHOWCASE_PERIOD));
        }
        if ("liesleep".equals(name)) {
            long t = Math.floorMod(tick, LIESLEEP_PERIOD);
            return new RigLocomotion.Weights(0, 0, 0, band(t, LS_LIE, LS_LIE_END, 24), band(t, LS_SLEEP, LS_SLEEP_END, 24),
                    0, 0, 0, 0, 0);
        }
        return null;
    }

    /**
     * {@code liesleep} (the owner's lie-and-sleep film): 18 s, awake 2 s, lies down over 1.2 s and stays 3.8 s,
     * up over 1.2 s, awake 2 s, then lies down to sleep the same way for 5.8 s, awake to the end. One native or parts arm
     * per period: the take flips the switch on the period's log line.
     */
    public static final int LIESLEEP_PERIOD = 360;
    /** When the lie and the sleep start and end, ticks into the period. */
    public static final int LS_LIE = 40, LS_LIE_END = 140, LS_SLEEP = 180, LS_SLEEP_END = 320;

    /**
     * {@code showcase} (the owner's comprehensive film): one creature held still runs every pose and look on
     * a 36 s loop, the same for both paths. 0-6 s: nothing scripted (the take's own commands: turn, look, attack, talk,
     * gesture, hurt); 6-8 crouch; 8-10 jump; 10-13 sit; 13-16 lie; 16-22 sleep (lies down, eyes shut, wakes); 22-24
     * poison; 24-26 frost; 26-28 wither; 28-30 whiten; 30-32 fullbright; 32-35 scale up and back.
     */
    public static final int SHOWCASE_PERIOD = 720;
    /** When each showcase beat starts, in ticks into the loop (the take plan and the artifact's clips read these). */
    public static final int SC_CROUCH = 120, SC_JUMP = 160, SC_SIT = 200, SC_LIE = 260, SC_SLEEP = 320, SC_POISON = 440,
            SC_FROST = 480, SC_WITHER = 520, SC_WHITEN = 560, SC_BRIGHT = 600, SC_SCALE = 640, SC_END = 700;

    private static RigLocomotion.Weights showcase(long t) {
        float crouch = band(t, SC_CROUCH, SC_JUMP, 10);
        float jump = t >= SC_JUMP && t < SC_SIT - 10 ? 1f : 0f;
        float jumpAt = jump > 0 ? Math.min(1f, (t - SC_JUMP) / 30f) : 0f;
        float sit = band(t, SC_SIT, SC_LIE, 12);
        float lie = band(t, SC_LIE, SC_SLEEP, 12);
        float sleep = band(t, SC_SLEEP, SC_POISON, 24);
        return new RigLocomotion.Weights(0, 0, sit, lie, sleep, crouch, jump, jumpAt, 0, 0);
    }

    /** 0 outside [from, to), ramping in and out over {@code ramp} ticks, 1 between. */
    static float band(long t, int from, int to, int ramp) {
        if (t < from || t >= to) {
            return 0f;
        }
        return Math.min(1f, Math.min((t - from) / (float) ramp, (to - t) / (float) ramp));
    }

    /** A scripted look: a whole-body tint (0xFFFFFF none), whiteness 0..1, fullbright, the body's scale. */
    public record Look(int tint, float white, boolean bright, float scale) {
        public static final Look NONE = new Look(RigTint.NONE, 0f, false, 1f);
    }

    /** The showcase's look at {@code tick} (other scripts: none). */
    public static Look look(String name, long tick) {
        if (!"showcase".equals(name)) {
            return null;
        }
        long t = Math.floorMod(tick, SHOWCASE_PERIOD);
        int tint = t >= SC_POISON && t < SC_FROST ? RigTint.POISON : t >= SC_FROST && t < SC_WITHER ? RigTint.FROST
                : t >= SC_WITHER && t < SC_WHITEN ? RigTint.WITHER : RigTint.NONE;
        float white = band(t, SC_WHITEN, SC_BRIGHT, 20);
        boolean bright = t >= SC_BRIGHT && t < SC_SCALE;
        float scale = 1f + 0.4f * band(t, SC_SCALE, SC_END, 30);
        return new Look(tint, white, bright, scale);
    }
}
