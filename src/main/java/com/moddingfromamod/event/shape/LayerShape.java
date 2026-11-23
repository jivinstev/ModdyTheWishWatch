package com.moddingfromamod.event.shape;

/**
 * <b>{@code layer}: something that rises, holds and drains</b> (E2). Pure.
 *
 * <p>A flood, a lake of lava, a slime tide, a snow drift. Ported from a mod repo's flood, whose
 * rules were paid for:
 * <ul>
 *   <li><b>A deeper layer is a wider one.</b> Water finds its level, and a hundred blocks of it in a
 *       small circle is a tower standing in a field.</li>
 *   <li><b>Every size takes about the same time to come in</b>, so watching it climb is the frightening
 *       part and waiting for it is not.</li>
 *   <li><b>It drains from the top, a few layers at a time</b>, visibly, rather than vanishing.</li>
 *   <li><b>The reach is a BUDGET, not a taste.</b> That flood at a 96-block reach wrote ~3M blocks and
 *       ran its test server out of memory; here the reach is whatever the event's total allows.</li>
 * </ul>
 */
public final class LayerShape {

    /** Ticks for any layer to finish rising, whatever its depth (~30 s). */
    public static final int RISE_TICKS = 600;
    /** Ticks it stands at full depth before draining. */
    public static final int HOLD_TICKS = 200;
    /** Ticks between one drained layer and the next (the original: five blocks every ten seconds). */
    public static final int DRAIN_EVERY = 40;
    public static final int MIN_REACH = 8;
    public static final int MAX_REACH = 72;

    public final int depth;
    public final int reach;

    public LayerShape(int depth, int reach) {
        if (depth < 1 || reach < MIN_REACH) {
            throw new IllegalArgumentException("a layer needs depth and some reach");
        }
        this.depth = depth;
        this.reach = reach;
    }

    /**
     * The shape a depth asks for, cut to what {@code totalBlocks} can pay for: the reach grows with the
     * depth, and shrinks if the disc times the depth would overspend.
     */
    public static LayerShape fit(int depth, int maxReach, int totalBlocks) {
        int d = Math.max(1, depth);
        int wanted = Math.max(MIN_REACH, Math.min(Math.min(MAX_REACH, maxReach), 12 + d * 3));
        int affordable = (int) Math.floor(Math.sqrt(totalBlocks / (Math.PI * d)));
        int reach = Math.max(MIN_REACH, Math.min(wanted, affordable));
        // If even the smallest disc overspends, give up depth rather than spill past the budget.
        while (d > 1 && Math.PI * reach * reach * d > totalBlocks) {
            d--;
        }
        return new LayerShape(d, reach);
    }

    /** Ticks between one layer and the next on the way up. */
    public int riseEvery() {
        return Math.max(2, RISE_TICKS / depth);
    }

    /** How many layers deep it should be at this age: up, hold, then down to zero. */
    public int layersAt(int age) {
        int risen = age / riseEvery() + 1;
        if (risen < depth) {
            return Math.max(1, risen);
        }
        int full = (depth - 1) * riseEvery() + HOLD_TICKS;
        if (age <= full) {
            return depth;
        }
        int drained = (age - full) / DRAIN_EVERY + 1;
        return Math.max(0, depth - drained);
    }

    /** Ticks from the first layer to nothing. */
    public int lifeTicks() {
        return (depth - 1) * riseEvery() + HOLD_TICKS + depth * DRAIN_EVERY;
    }

    /** Is the column {@code (dx, dz)} from the centre inside the layer's disc? */
    public boolean inside(int dx, int dz) {
        return dx * dx + dz * dz <= reach * reach;
    }

    /** Cells in one full layer. */
    public int cellsPerLayer() {
        return (int) Math.round(Math.PI * reach * reach);
    }
}
