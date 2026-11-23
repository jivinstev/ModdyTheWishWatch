package com.moddingfromamod.vanilla;

import java.util.Collection;

/**
 * <b>How often a rig atom's POSITION is sent: on the rig's own beat, every {@link #MOVE_INTERVAL} ticks, the same
 * ticks for every atom</b>.
 *
 * <p>{@link RigDriver#follow} moves every display of a walking creature every tick, and vanilla's tracker sends an
 * {@code item_display} every tick it moved ({@code EntityType.ITEM_DISPLAY.updateInterval(1)}). So a walking
 * 40-atom creature cost 40 move packets a tick to every viewer, while the invisible mob it follows (the carrier,
 * {@code updateInterval(3)}) cost one packet every third tick. Each display already eases each move over
 * {@link DisplayRig#MOTION_TICKS} ticks, which is 3 for exactly this reason (it is how the client eases a mob), so a
 * move every third tick draws the same continuous walk at a third of the packets. On Bedrock, where each move is a
 * packet the phone handles per atom, that third is the difference between a client that keeps up and one that does
 * not (480 atoms near a phone, 1.6 MB/s, menus never drawn).
 *
 * <p>The beat is the WORLD's game time, not each display's own tracker counter: atoms tracked on different ticks
 * (a creature walking back into range, a reshape adding parts) would otherwise send on different ticks and the
 * body would tear apart by a tick's walk. The beat is the tracker tick that sends the animator's pose write
 * ({@link #TRACKER_LAG}), so a pose and its move go out together. A display whose data changed off the beat (a glow,
 * a light, a new item, a pose off the animator's schedule) has that DATA sent at once, as vanilla does, but never a
 * move: every atom of a body takes its moves on the same ticks ({@link #send}).
 *
 * <p>{@code -Dmoddingfromamod.rigMoveInterval=1} is vanilla's every-tick sending, the A/B arm.
 */
public final class RigCadence {

    private RigCadence() {
    }

    /** Ticks between a rig atom's position updates, and between its pose writes ({@link RigAnimator#DEFAULT_INTERVAL}). */
    public static final int DEFAULT_MOVE_INTERVAL = 3;

    /**
     * <b>Every ease is the gap to the next write plus ONE tick.</b> An ease exactly as long as the gap arrives
     * just as the next write lands, so a packet a little late (a home connection's 50 +- 15 ms) leaves the part
     * stopped until it comes and then lurching to catch up: measured, 2.5-8.7% of a lagged walk's frames frozen with a
     * 3-tick ease on a 3-tick cadence, 0-0.6% with 4. It holds for every channel a rig eases: the move
     * ({@link DisplayRig#MOTION_TICKS}), every pose ({@link #poseEase}), and the death flop, whose 1-tick ease in the
     * death film froze it for whole ticks under lag and caught up at double speed. One number, so no channel can be
     * given a gap-long ease again. {@code -Dmoddingfromamod.rigeaseslack=0} is the old behaviour, the A/B arm.
     */
    public static final int EASE_SLACK = slack(System.getProperty("moddingfromamod.rigeaseslack"));

    static int slack(String prop) {
        if (prop == null || prop.isBlank()) {
            return 1;
        }
        try {
            return Math.max(0, Math.min(5, Integer.parseInt(prop.trim())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** How long the client should ease a pose written every {@code interval} ticks: the gap plus {@link #EASE_SLACK}. */
    public static int poseEase(int interval) {
        return Math.max(1, interval) + EASE_SLACK;
    }

    /**
     * <b>A look change must not jump a pose.</b> A client rebuilds a display's ease whenever its light, its
     * item (a tint, a hurt flash, the shimmer) or its shadow changes, starting from where the part is drawn but keeping
     * the OLD start tick ({@code Display.tick}: {@code createInterpolatedRenderState(renderState, lastProgress)} with
     * no new start): a part halfway through its ease leaps a quarter of the way at once. So when such a change goes out
     * while an ease is running and no new pose rides with it, the tracker re-arms the ease for the ticks it has left
     * ({@link #remaining}): the client restarts from where it draws the part, aimed at the same pose, due at the same
     * tick, at the same speed. {@code -Dmoddingfromamod.rigrearm=off} is the A/B arm.
     */
    public static final boolean REARM = !"off".equals(System.getProperty("moddingfromamod.rigrearm", "on"));

    /** Ticks left of an ease due to end at tracker tick {@code easeEnd}, seen at tracker tick {@code now}; 0 = none running. */
    public static int remaining(long easeEnd, long now) {
        return easeEnd > now ? (int) Math.min(easeEnd - now, 100) : 0;
    }

    /** The interval in force: the A/B property, or {@link #DEFAULT_MOVE_INTERVAL}. Read once. */
    public static final int MOVE_INTERVAL = interval(System.getProperty("moddingfromamod.rigMoveInterval"));

    static int interval(String prop) {
        if (prop == null || prop.isBlank()) {
            return DEFAULT_MOVE_INTERVAL;
        }
        try {
            return Math.max(1, Math.min(20, Integer.parseInt(prop.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_MOVE_INTERVAL;
        }
    }

    /** Is this entity's tag set a rig atom's (a display carrying its creature's owner tag)? */
    public static boolean isRigAtom(Collection<String> tags) {
        for (String t : tags) {
            if (t.startsWith(DisplayRig.RIG_OWNER_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>The tracker runs a tick BEHIND the rig.</b> {@code ServerLevel.tick} advances game time, then runs the entity
     * tracker ({@code ServerChunkCache.tick → ChunkMap.tick → ServerEntity.sendChanges}), and only after every level has
     * ticked does {@code ServerTickEvent.Post} run {@link VanillaRigAnimator}. So what the rig writes at game time
     * {@code t} (its follow, and a pose on {@link RigAnimator#writesOn} ticks) is SENT by the tracker at {@code t + 1}.
     * Measured on the client: a pose written on the animator's beat arrived a tick after a beat at game time
     * {@code ≡ 0}.
     */
    public static final int TRACKER_LAG = 1;

    /** What the tracker does with a rig atom this tick. */
    public enum Send {
        /** vanilla's whole update: its move, and its data if any changed */
        ALL,
        /** only the changed data (a pose, a glow); the move waits for the beat, so every atom keeps one schedule */
        DATA_ONLY,
        /** nothing this tick */
        HOLD
    }

    /**
     * <b>One position schedule for every atom of a body, on the tick that carries the pose.</b>
     *
     * <p>The first version sent a whole update whenever an atom's data was dirty. A posed atom's data is dirty
     * on every pose write, so the posed atoms (legs, feet, head) took an extra move on the pose's tick while the unposed
     * ones (the body) moved only on the beat: two schedules eased over the same three ticks, and the posed parts were
     * drawn up to a third of a block ahead of the body (a biped's feet coming off its legs). Now an atom's MOVE goes out
     * only on the beat, whatever its data did; changed data off the beat goes out alone. And the beat is the tracker
     * tick that sends the animator's pose write ({@link #TRACKER_LAG}), so a pose and the move from the same rig tick
     * reach the client together, as they do when everything is sent every tick.
     */
    public static Send send(long trackerGameTime, boolean dataDirty, int interval) {
        if (SABOTAGE_2630) {
            return send2630(trackerGameTime, dataDirty, interval);
        }
        if (interval <= 1 || onBeat(trackerGameTime, interval)) {
            return Send.ALL;
        }
        return dataDirty ? Send.DATA_ONLY : Send.HOLD;
    }

    /**
     * {@code -Dmoddingfromamod.rigcadencesabotage=2630}: an earlier issue's removed behaviour (the beat at game time {@code ≡ 0},
     * and a dirty atom sent whole off it), kept ONLY so the drift check ({@code tools/rig-drift.py}) can be watched
     * going red on the same commit. Never set in production.
     */
    public static final boolean SABOTAGE_2630 =
            "2630".equals(System.getProperty("moddingfromamod.rigcadencesabotage", ""));

    static Send send2630(long trackerGameTime, boolean dataDirty, int interval) {
        return interval <= 1 || dataDirty || Math.floorMod(trackerGameTime, interval) == 0 ? Send.ALL : Send.HOLD;
    }

    /** Is this tracker tick the one that carries the rig's animator beat? */
    public static boolean onBeat(long trackerGameTime, int interval) {
        return RigAnimator.writesOn(Math.floorMod(trackerGameTime - TRACKER_LAG, (long) Math.max(1, interval)), interval);
    }
}
