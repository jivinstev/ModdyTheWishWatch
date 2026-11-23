package com.moddingfromamod.vanilla;

/**
 * How far a creature's DRAWN body turned while the rig was meant to hold it — the overhead-child GameTest's instrument
 *, Minecraft-free so its frame alignment is pinned by Gate A ({@code HeldTurnMeterTest}).
 *
 * <p><b>The drawn yaw a test reads is one tick old.</b> A server tick runs the levels (the carrier's AI and look),
 * then {@code GameTestTicker} (the test's {@code onEachTick}), then {@code ServerTickEvent.Post}, where
 * {@code VanillaRigAnimator} decides the drawn yaw from that tick's state. So the change a test sees between two reads
 * was decided on the PREVIOUS tick, and is judged against the previous tick's state. The carrier's own yaw is moved by
 * the level tick, before the test, so it is judged against this tick's.
 */
final class HeldTurnMeter {

    private float lastDrawn = Float.NaN;
    private float lastRaw = Float.NaN;
    private boolean heldBefore;
    private int counted;
    private float worst;
    private float total;
    private float raw;

    /**
     * One test tick.
     *
     * @param holdsNow  does this tick's state say the body should hold (standing still, looking steeply up)?
     * @param drawnSeen the body yaw the rig DREW, as read now: decided on the previous tick
     * @param rawSeen   the carrier's own body yaw, as read now
     */
    void tick(boolean holdsNow, float drawnSeen, float rawSeen) {
        if (heldBefore && !Float.isNaN(lastDrawn)) {
            float d = apart(drawnSeen, lastDrawn);
            worst = Math.max(worst, d);
            total += d;
            counted++;
        }
        if (holdsNow && !Float.isNaN(lastRaw)) {
            raw += apart(rawSeen, lastRaw);
        }
        heldBefore = holdsNow;
        lastDrawn = drawnSeen;
        lastRaw = rawSeen;
    }

    int counted() {
        return counted;
    }

    float worst() {
        return worst;
    }

    float total() {
        return total;
    }

    float raw() {
        return raw;
    }

    static float apart(float a, float b) {
        float d = (a - b) % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return Math.abs(d);
    }
}
