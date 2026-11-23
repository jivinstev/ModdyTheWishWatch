package com.moddingfromamod.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>One staged event</b> (E1): who asked for it, where it is, how big it may be, what it has
 * changed so far, and where it is in its life. Everything but the motion is saved with the world.
 */
public final class StagedEvent {

    final String id;
    final ResourceKey<Level> dimension;
    final String owner;
    final EventScale scale;
    final EventGuard.Policy policy;
    /** true: the world is given back when the motion ends (a wave); false: kept until undo (a crater). */
    final boolean restoreAtEnd;
    final EventJournal<BlockState> journal;
    EventPhase phase;
    int age;
    /** Null after a reload: an event whose motion is gone can only be rolled back. */
    transient EventMotion motion;
    /** How many writes the guards and the budget refused, for the tool's reply and the tests. */
    int refused;
    /** Entities it put into the world; saved, because a chicken shower is undone by it alone. */
    int spawned;
    /**
     * The box of every cell it has written this run — where to look for what it caught. Not saved:
     * after a reload the motion is gone and nothing is being caught.
     */
    transient int bx0 = Integer.MAX_VALUE, by0, bz0, bx1, by1, bz1;
    /**
     * what every creature it brings IS from the moment it arrives ({@code modify_mob}'s JSON),
     * applied in {@link EventWriter#spawn}. Not saved: after a reload the motion is gone, and nothing
     * more is spawned.
     */
    transient String traits = "";

    /** Set what each creature it spawns is given (its design's {@code traits}). */
    public StagedEvent traits(String t) {
        traits = t == null ? "" : t;
        return this;
    }

    public String traits() {
        return traits;
    }

    /** Where it was aimed and when it started: what the wish camera films. Not saved. */
    transient net.minecraft.core.BlockPos focus;
    transient long focusedAtMs;

    /** Record where this event happens — its design's centre — for the camera. */
    public void focus(net.minecraft.core.BlockPos at) {
        focus = at;
        focusedAtMs = System.currentTimeMillis();
    }

    /** Where it happens, or null for an event staged before this build or with no centre. */
    public net.minecraft.core.BlockPos focus() {
        return focus;
    }

    public long focusedAtMs() {
        return focusedAtMs;
    }

    /** Where its action goes over the camera's clip: what the framer fits. Not saved. */
    transient com.moddingfromamod.runtime.EventReach planned;
    /**
     * Held at age 0 until this wall-clock time: the camera is on its way, so the first visible
     * tick waits for it rather than playing to nobody. 0 = not held. Not saved — a reload plays on.
     */
    transient long heldUntilMs;

    public StagedEvent planned(com.moddingfromamod.runtime.EventReach reach) {
        planned = reach;
        return this;
    }

    public com.moddingfromamod.runtime.EventReach planned() {
        return planned;
    }

    /** Hold the start until {@code untilMs} (only ever before it has played a tick). */
    public void holdUntil(long untilMs) {
        if (age == 0) {
            heldUntilMs = untilMs;
        }
    }

    /** Let it start at {@code atMs}, if that is sooner than it was held to. */
    public void releaseAt(long atMs) {
        if (heldUntilMs > 0) {
            heldUntilMs = Math.min(heldUntilMs, atMs);
        }
    }

    public boolean held(long nowMs) {
        return heldUntilMs > nowMs && age == 0;
    }

    /** The box of every cell it has written so far {x0,y0,z0,x1,y1,z1}, or null when it wrote none. */
    public int[] written() {
        return bx0 == Integer.MAX_VALUE ? null : new int[] {bx0, by0, bz0, bx1, by1, bz1};
    }

    void reach(int x, int y, int z) {
        if (bx0 == Integer.MAX_VALUE) {
            bx0 = bx1 = x;
            by0 = by1 = y;
            bz0 = bz1 = z;
            return;
        }
        bx0 = Math.min(bx0, x);
        by0 = Math.min(by0, y);
        bz0 = Math.min(bz0, z);
        bx1 = Math.max(bx1, x);
        by1 = Math.max(by1, y);
        bz1 = Math.max(bz1, z);
    }

    StagedEvent(String id, ResourceKey<Level> dimension, String owner, EventScale scale,
                EventGuard.Policy policy, boolean restoreAtEnd, EventJournal<BlockState> journal,
                EventPhase phase, int age) {
        this.id = id;
        this.dimension = dimension;
        this.owner = owner == null ? "" : owner;
        this.scale = scale;
        this.policy = policy;
        this.restoreAtEnd = restoreAtEnd;
        this.journal = journal;
        this.phase = phase;
        this.age = age;
    }

    public String id() {
        return id;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public String owner() {
        return owner;
    }

    public EventScale scale() {
        return scale;
    }

    public EventPhase phase() {
        return phase;
    }

    public int age() {
        return age;
    }

    public int changed() {
        return journal.size();
    }

    public int refused() {
        return refused;
    }

    public int spawned() {
        return spawned;
    }

    public boolean restoreAtEnd() {
        return restoreAtEnd;
    }
}
