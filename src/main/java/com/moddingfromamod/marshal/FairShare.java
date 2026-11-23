package com.moddingfromamod.marshal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Who is next when two children are waiting — D10</b>.
 *
 * <p>The decision, 2026-09-22: <i>10 workers · 3 concurrent per player · next-up by pickups
 * in the last ~10 min.</i> Before it the queue was a strict FIFO, and {@code TwoChildrenFairnessTest}
 * measured what that costs the second child: A asks for six things, B asks for one, and B's wish
 * started only after the <b>fourth</b> completion — about four minutes of watching nothing, because
 * the scheduler had no notion of whose wish it was.
 *
 * <p>The rule, in the order it is applied:
 *
 * <ol>
 *   <li><b>A child already at his cap is skipped</b>, not refused: his wish stays in the queue, in
 *       its place, and goes the moment one of his own finishes.</li>
 *   <li>Among the rest, the child with the <b>fewest pickups in the window</b> goes next — so a
 *       child who has just had three wishes picked up yields to one who has had none, and the
 *       advantage fades as the window slides rather than being remembered for ever.</li>
 *   <li><b>Ties go to queue order</b>, which is oldest first — and which is also why a wish a dead
 *       worker dropped keeps its place: {@code Marshal.workerDied} puts it back at the FRONT.</li>
 * </ol>
 *
 * <p>With one child every step collapses to today's FIFO except the cap, which is the point of the
 * cap: three is how many of one child's wishes may be in flight however quiet the server is.
 *
 * <p>Pure and Minecraft-free: the Marshal hands over the queue, the in-flight counts and the recent
 * pickups, and executes the answer. Both numbers are knobs — {@code queue.perChildConcurrent}
 * and {@code queue.fairWindowSeconds} — and fall back to the decision's values.
 */
public final class FairShare {

    /** D10's "3 concurrent per player". */
    public static final int DEFAULT_PER_CHILD = 3;

    /** D10's "the last ~10 min". */
    public static final int DEFAULT_WINDOW_SECONDS = 600;

    private FairShare() {
    }

    /**
     * {@code -Dmoddingfromamod.queue.perChildConcurrent=N}: the boot default of the cap, per WORLD
     * (D18). Each world is its own JVM with its own knobs, so the world manifest's
     * setting reaches only its own world through its launcher: one world can run 2
     * while another keeps D10's 3 by naming nothing.
     */
    public static final String PER_CHILD_PROPERTY = "moddingfromamod.queue.perChildConcurrent";

    /** How many of one child's wishes may be in flight at once: the knob, else the boot property, else D10's 3. */
    public static int perChild() {
        return Math.max(1, com.moddingfromamod.config.ModdySettings.intOr(
                "queue.perChildConcurrent", bootPerChild(System.getProperty(PER_CHILD_PROPERTY))));
    }

    /**
     * The boot default from the property's raw text, or {@link #DEFAULT_PER_CHILD}. A value that is not
     * a whole number from 1 to {@link WishSlots#MAX} is ignored rather than trusted — a typo must not
     * become "one child may hold every worker", nor "nobody's wish ever starts". Pure.
     */
    static int bootPerChild(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PER_CHILD;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return n >= 1 && n <= WishSlots.MAX ? n : DEFAULT_PER_CHILD;
        } catch (NumberFormatException e) {
            return DEFAULT_PER_CHILD;
        }
    }

    /** How far back a pickup still counts against a child. */
    public static long windowMs() {
        return Math.max(1, com.moddingfromamod.config.ModdySettings.intOr(
                "queue.fairWindowSeconds", DEFAULT_WINDOW_SECONDS)) * 1000L;
    }

    /**
     * Which waiting wish goes next.
     *
     * @param owners        the child behind each waiting wish, in queue order (oldest first);
     *                      {@code null} is a wish with no child (a console, a probe) and is its own
     *                      bucket rather than everyone's
     * @param inFlight      how many wishes each child has being granted right now
     * @param recentPickups how many of each child's wishes were picked up inside the window
     * @param perChild      the cap
     * @return the index into {@code owners} to hand out, or {@code -1} when every waiting wish
     *         belongs to a child already at his cap
     */
    public static int pick(List<UUID> owners, Map<UUID, Integer> inFlight,
                           Map<UUID, Integer> recentPickups, int perChild) {
        int best = -1;
        int bestPickups = Integer.MAX_VALUE;
        for (int i = 0; i < owners.size(); i++) {
            UUID who = owners.get(i);
            if (inFlight.getOrDefault(who, 0) >= perChild) {
                continue;
            }
            int p = recentPickups.getOrDefault(who, 0);
            if (p < bestPickups) {       // strictly fewer: a tie keeps the older wish
                best = i;
                bestPickups = p;
            }
        }
        return best;
    }
}
