package com.moddingfromamod.event;

/**
 * <b>The block budget an event spends</b> (E1; D2). Pure.
 *
 * <p>Two meters, both hard: the cells the event may change IN ALL (what its journal may hold) and
 * the cells it may write IN ONE TICK. A new cell spends from both; rewriting a cell the event
 * already holds (a wave passing back over its own water) spends only from the tick, because the
 * journal does not grow. A refusal is final for that write, never an exception: an event that runs
 * out of budget simply stops growing, which is what "scale it down" means for a child's
 * "the biggest flood ever".
 */
public final class EventBudget {

    private final int total;
    private final int perTick;
    private int thisTick;

    public EventBudget(EventScale scale) {
        this(scale.totalBlocks, scale.perTick);
    }

    public EventBudget(int total, int perTick) {
        if (total <= 0 || perTick <= 0) {
            throw new IllegalArgumentException("a budget must be positive");
        }
        this.total = total;
        this.perTick = perTick;
    }

    /** Start a new tick's allowance. */
    public void newTick() {
        thisTick = 0;
    }

    /**
     * May the event write one more cell now? {@code held} is how many cells its journal already
     * holds and {@code isNew} whether this write would add one.
     */
    public boolean tryTake(int held, boolean isNew) {
        if (thisTick >= perTick) {
            return false;
        }
        if (isNew && held >= total) {
            return false;
        }
        thisTick++;
        return true;
    }

    public boolean tickSpent() {
        return thisTick >= perTick;
    }

    public int perTick() {
        return perTick;
    }

    public int total() {
        return total;
    }
}
