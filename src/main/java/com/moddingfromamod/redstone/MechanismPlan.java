package com.moddingfromamod.redstone;

import java.util.List;

/**
 * A complete mechanism ready to place, or the reason it cannot be built.
 *
 * @param cells    every block to place, in a safe order (pockets cleared first, supports and casing, then parts,
 *                 then wire and torches, controls last)
 * @param expects  what must hold at rest and when fired
 * @param controls the controls a player uses
 * @param replaced built blocks (not natural ground) the plan replaces, for the report (the S1 footprint)
 * @param notes    what to tell the agent
 * @param why      null when the plan is good; else what stopped it
 * @param feeds    for a mechanism driven by the redstone engine ({@link RedstoneScript}): the cells where the engine
 *                 puts a redstone block while the mechanism is on (air at rest): one port of a door's module, or every
 *                 device's own; empty for a wired one
 */
public record MechanismPlan(String kind, List<RsCell> cells, List<Expect> expects, List<Control> controls,
                            List<String> replaced, List<String> notes, String why, List<RsPos> feeds) {

    public MechanismPlan(String kind, List<RsCell> cells, List<Expect> expects, List<Control> controls,
                         List<String> replaced, List<String> notes, String why) {
        this(kind, cells, expects, controls, replaced, notes, why, List.of());
    }

    public MechanismPlan {
        feeds = feeds == null ? List.of() : List.copyOf(feeds);
    }

    /** True when its switches are read by the engine rather than wired. */
    public boolean linked() {
        return !feeds.isEmpty();
    }

    /** The first feed (null for a wired mechanism). */
    public RsPos feed() {
        return feeds.isEmpty() ? null : feeds.get(0);
    }

    public boolean ok() {
        return why == null;
    }

    public static MechanismPlan refused(String kind, String why) {
        return new MechanismPlan(kind, List.of(), List.of(), List.of(), List.of(), List.of(), why);
    }
}
