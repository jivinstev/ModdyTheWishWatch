package com.moddingfromamod.event.catching;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;

/**
 * <b>A motion, and what it does to what it catches</b> (E3). Runs the motion's tick, then
 * finds what each {@link Catch} has caught and applies its effects, and runs the block effects. The
 * motion itself knows nothing about any of it — a wave is the same wave whether it carries you or not.
 */
public final class CatchingMotion implements EventMotion {

    private final EventMotion inner;
    private final List<Rule> rules = new ArrayList<>();
    private final List<BlockHits> blocks = new ArrayList<>();
    private int caught;

    private record Rule(Catch where, List<Effect> what) {
    }

    public CatchingMotion(EventMotion inner) {
        this.inner = inner;
    }

    public CatchingMotion on(Catch where, Effect... what) {
        rules.add(new Rule(where, List.of(what)));
        return this;
    }

    public CatchingMotion blocks(BlockHits hits) {
        blocks.add(hits);
        return this;
    }

    @Override
    public void stopped(net.minecraft.server.level.ServerLevel level) {
        inner.stopped(level);
    }

    /** The motion it wraps (for a test that asks the motion itself). */
    public EventMotion inner() {
        return inner;
    }

    /** How many catches it has made (one entity caught by one rule on one tick is one). */
    public int caught() {
        return caught;
    }

    @Override
    public boolean step(EventWriter w, int age) {
        boolean done = inner.step(w, age);
        for (Rule r : rules) {
            r.where().find(w, age, c -> {
                caught++;
                for (Effect e : r.what()) {
                    e.apply(w.level(), c, age);
                }
            });
        }
        for (BlockHits b : blocks) {
            b.hit(w, age);
        }
        return done;
    }
}
