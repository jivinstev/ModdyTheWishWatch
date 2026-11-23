package com.moddingfromamod.vanilla;

/**
 * <b>Which locomotion layer a creature is in, read off its carrier every tick, and eased</b>
 *.
 *
 * <p>The generator ({@link RigLocomotion}) draws a pose for any mix of fly, swim, jump, crouch,
 * sit, lie and sleep. This decides the mix, and it decides it from what the carrier IS doing —
 * {@code onGround()}, {@code isInWater()}, its velocity, whether it was told to stay — never from
 * a guess at its height. Every weight walks towards its target a step per tick, so nothing snaps:
 * a creature that lands eases out of its jump, one that is called stands up over a few ticks.
 *
 * <h2>The rules, each with the vanilla it copies</h2>
 * <ul>
 *   <li><b>FLY</b> while a flier is off the ground (a parrot/bee flaps whenever airborne). A
 *       creature that cannot fly flaps only once it has been FALLING a while — the chicken's rule —
 *       so a hop or a step down is not a flight.</li>
 *   <li><b>SWIM</b> while in water and either under it or off the bottom (the axolotl's split).
 *       Wading on the bottom is walking.</li>
 *   <li><b>JUMP</b> from a take-off — on the ground last tick, off it now, rising — and its clock
 *       is the jump itself: progress is {@code 0.5 − vy / 2vy₀}, 0 leaving the ground, ½ at the top,
 *       1 coming down, whatever the jump's height. Landing is a short crouch, eased out.</li>
 *   <li><b>HOP</b> (the {@code hops} power): a hopper does not walk. When it wants to move it
 *       gathers (a crouch), launches, lands, and goes again — the frog's anticipation, which a
 *       jump the creature did not plan cannot have.</li>
 *   <li><b>SIT</b> while told to stay and standing still; <b>LIE</b> after a long sit or,
 *       for a {@code naps} creature, a long idle; <b>SLEEP</b> at night for a {@code naps}
 *       creature. Hurt, called, or walking: it gets up.</li>
 * </ul>
 *
 * <p>Pure: the caller reads the carrier into a {@link Sense} and carries out the {@link Action}.
 */
public final class RigGait {

    /** What the carrier is doing this tick. */
    public record Sense(boolean onGround, boolean inWater, boolean underWater, boolean flies,
                        boolean winged, boolean hops, boolean naps, boolean staying,
                        boolean wantsToMove, boolean night, boolean hurt, boolean called,
                        double vx, double vy, double vz) {
    }

    /** What the caller must do to the carrier. */
    public enum Action {
        NONE,
        /** Stand still this tick — gathering for a hop, landing one, or asleep. */
        HOLD,
        /** Leave the ground now: the end of a hop's gather. */
        LAUNCH
    }

    // ---- timings, in ticks
    /** Rise into a flap: quick, a bird does not ease into the air. */
    static final int FLY_IN = 4;
    static final int SWIM_IN = 5;
    static final int JUMP_IN = 2;
    /** Sitting down takes most of a second: quick enough to answer "sit!", slow enough to see. */
    static final int SIT_IN = 16;
    static final int LIE_IN = 16;
    static final int SLEEP_IN = 24;
    static final int STAND_UP = 12;
    /** A hop's gather. Frog: ~0.2 s of crouch before the leap. */
    public static final int GATHER_TICKS = 4;
    /** The landing taken in the legs. */
    public static final int LAND_TICKS = 5;
    /** A beat between hops, so a hop is a hop and not a bounce. */
    static final int HOP_REST = 2;
    /** Airborne this long and falling before a non-flier spreads its wings. */
    static final int FALL_FLAP_TICKS = 6;
    /** Still this long before it sits (so a step back home is not a sit). */
    static final int SIT_AFTER = 10;
    /** Sitting this long, or idle this long for a napper, and it lies down. */
    public static final int LIE_AFTER = 600;
    /** Still this long at night and a napper is asleep. */
    static final int SLEEP_AFTER = 40;
    /** Hurt, it stays up this long. */
    static final int STARTLED = 100;
    /** Smallest upward speed that counts as a take-off rather than a step. */
    static final double TAKEOFF_VY = 0.15;

    private float fly;
    private float swim;
    private float sit;
    private float lie;
    private float sleep;
    private float crouch;
    private float jump;
    private float progress;
    private float pitch;
    private float moving;

    private boolean wasOnGround = true;
    private boolean jumping;
    private double takeoffVy;
    private int airborne;
    private int still;
    private int sitting;
    private int startled;
    private int landing;
    private int gather = -1;
    private int rest;

    /** One tick. */
    public Action tick(Sense s) {
        Action act = Action.NONE;
        double h = Math.hypot(s.vx(), s.vz());
        boolean ground = s.onGround();

        // ---- airborne / jump bookkeeping
        airborne = ground ? 0 : airborne + 1;
        boolean tookOff = wasOnGround && !ground && s.vy() > TAKEOFF_VY && !s.inWater()
                && !s.flies();
        if (tookOff) {
            jumping = true;
            takeoffVy = s.vy();
            progress = 0f;
        }
        if (jumping && !ground) {
            float p = (float) (0.5 - s.vy() / (2 * Math.max(0.05, takeoffVy)));
            progress = Math.max(progress, Math.max(0f, Math.min(1f, p)));
        }
        if (jumping && (ground || s.inWater())) {
            jumping = false;
            progress = 1f;
            landing = ground ? LAND_TICKS : 0;
        }

        // ---- still / startled
        boolean stillNow = !s.wantsToMove() && h < 0.02 && ground;
        still = stillNow ? still + 1 : 0;
        if (s.hurt()) {
            startled = STARTLED;
        } else if (startled > 0) {
            startled--;
        }

        // ---- the hop
        if (s.hops() && ground && !s.inWater() && sitTarget(s) == 0 && sleepTarget(s) == 0) {
            if (landing > 0) {
                act = Action.HOLD;
            } else if (gather >= 0) {
                gather++;
                act = Action.HOLD;
                if (gather >= GATHER_TICKS) {
                    gather = -1;
                    rest = HOP_REST;
                    act = Action.LAUNCH;
                }
            } else if (rest > 0) {
                rest--;
            } else if (s.wantsToMove()) {
                gather = 0;
                act = Action.HOLD;
            }
        } else {
            gather = -1;
        }

        // ---- targets
        float flyT = !ground && !s.inWater()
                && (s.flies() || (s.winged() && airborne >= FALL_FLAP_TICKS && s.vy() < -0.1))
                ? 1f : 0f;
        float swimT = s.inWater() && (s.underWater() || !ground) ? 1f : 0f;
        float sleepT = sleepTarget(s);
        float lieT = sleepT > 0 ? 0f : lieTarget(s);
        float sitT = sleepT > 0 || lieT > 0 ? 0f : sitTarget(s);
        // How long it has sat. Not reset by the lie-down it leads to, or the two would take turns.
        if (sitT > 0 && sit > 0.5f) {
            sitting++;
        } else if (sitT == 0 && lieT == 0) {
            sitting = 0;
        }
        float jumpT = jumping ? 1f : 0f;
        float crouchT = gather >= 0 || landing > 0 ? 1f : 0f;
        if (landing > 0) {
            landing--;
        }
        if (sleepT > 0 && !s.called()) {
            act = Action.HOLD;
        }

        // ---- ease
        fly = step(fly, flyT, FLY_IN, FLY_IN + 2);
        swim = step(swim, swimT, SWIM_IN, SWIM_IN);
        sit = step(sit, sitT, SIT_IN, STAND_UP);
        lie = step(lie, lieT, LIE_IN, STAND_UP);
        sleep = step(sleep, sleepT, SLEEP_IN, STAND_UP);
        jump = step(jump, jumpT, JUMP_IN, 3);
        crouch = step(crouch, crouchT, 2, LAND_TICKS);
        float pitchT = h + Math.abs(s.vy()) > 0.02
                ? (float) Math.toDegrees(Math.atan2(s.vy(), Math.max(h, 1e-4))) : 0f;
        pitch += (pitchT - pitch) * 0.25f;
        moving += ((float) Math.min(1.0, h / 0.1) - moving) * 0.25f;
        wasOnGround = ground;
        return act;
    }

    private float sitTarget(Sense s) {
        return s.staying() && startled == 0 && !s.inWater() && still >= SIT_AFTER ? 1f : 0f;
    }

    private float lieTarget(Sense s) {
        if (startled > 0 || s.inWater() || still < SIT_AFTER) {
            return 0f;
        }
        boolean longSit = s.staying() && sitting >= LIE_AFTER;
        boolean longIdle = s.naps() && still >= LIE_AFTER;
        return longSit || longIdle ? 1f : 0f;
    }

    private float sleepTarget(Sense s) {
        // Once asleep it stays asleep through its own AI's urge to wander (the caller holds it
        // still); only being hurt, called away, the water or the morning wake it.
        return s.naps() && s.night() && startled == 0 && !s.inWater() && !s.called()
                && (sleep > 0 || still >= SLEEP_AFTER) ? 1f : 0f;
    }

    private static float step(float w, float target, int inTicks, int outTicks) {
        if (target > w) {
            return Math.min(target, w + 1f / Math.max(1, inTicks));
        }
        return Math.max(target, w - 1f / Math.max(1, outTicks));
    }

    /** The weights the generator should draw. */
    public RigLocomotion.Weights weights() {
        return new RigLocomotion.Weights(fly, swim, sit, lie, sleep, crouch, jump, progress, pitch,
                moving);
    }

    /** True while the hop is driving the legs, so the walk clip should not. */
    public boolean hopping() {
        return gather >= 0 || jumping || landing > 0 || crouch > 0;
    }

    /** For a film's control line: the layer it is mostly in right now. */
    public String layer() {
        float[] ws = {fly, swim, sit, lie, sleep, jump, crouch};
        String[] names = {"FLY", "SWIM", "SIT", "LIE", "SLEEP", "JUMP", "CROUCH"};
        int best = -1;
        float bw = 0.05f;
        for (int i = 0; i < ws.length; i++) {
            if (ws[i] > bw) {
                bw = ws[i];
                best = i;
            }
        }
        return best < 0 ? "-" : names[best];
    }
}
