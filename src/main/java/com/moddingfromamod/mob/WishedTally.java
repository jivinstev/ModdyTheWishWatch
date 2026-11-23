package com.moddingfromamod.mob;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <b>How many wished creatures a level holds, re-derived from the level every tick</b>.
 * Minecraft-free; {@code ops.WishMobLimit} supplies the census and the clock.
 *
 * <p>The count used to be a running total, +1 on {@code EntityJoinLevelEvent} and -1 on
 * {@code EntityLeaveLevelEvent}. Those two events are not a pair, so the total drifted for good:
 * a join fires before the entity manager has accepted the entity (a duplicate UUID is refused
 * after it), an entity that joins a hidden chunk section is never tracked and so never "leaves",
 * and a leave fires when a tracked entity's chunk merely goes hidden, with no join when it comes
 * back. In a long Gate B run the total climbed to the 400 ceiling with one wished mob standing,
 * and every wished summon after that was refused.
 *
 * <p>So the number is never carried from one tick to the next. The first question in a tick takes
 * a CENSUS of what the level tracks right now; joins counted later in that same tick are added on
 * top (a datapack that summons 450 creepers in one function call must meet the ceiling at 400, not
 * at 400 plus whatever joined since the census), and the next tick starts over from a new census.
 * Whatever a join, a leave or a missing event got wrong is gone one tick later: the count is
 * correct by construction rather than by every path remembering to give its slot back.
 *
 * <p>A join seen BEFORE this tick's census is not added: the census is taken after it and sees the
 * entity itself if the level tracks it.
 */
public final class WishedTally<K extends Enum<K>> {

    private final Class<K> kinds;
    private long tick = Long.MIN_VALUE;
    private boolean taken;
    private EnumMap<K, Integer> census;
    private final EnumMap<K, Integer> since;

    public WishedTally(Class<K> kinds) {
        this.kinds = kinds;
        this.census = new EnumMap<>(kinds);
        this.since = new EnumMap<>(kinds);
    }

    /**
     * Creatures of kind {@code k} right now: this tick's census (taken now, once, if it has not
     * been) plus what was counted joining since it.
     */
    public int live(K k, long now, Supplier<Map<K, Integer>> takeCensus) {
        roll(now);
        if (!taken) {
            census = new EnumMap<>(kinds);
            census.putAll(takeCensus.get());
            taken = true;
        }
        return census.getOrDefault(k, 0) + since.getOrDefault(k, 0);
    }

    /** One creature of kind {@code k} was counted joining. Before this tick's census it is the census's to see. */
    public void joined(K k, long now) {
        roll(now);
        if (taken) {
            since.merge(k, 1, Integer::sum);
        }
    }

    private void roll(long now) {
        if (now != tick) {
            tick = now;
            taken = false;
            since.clear();
        }
    }
}
