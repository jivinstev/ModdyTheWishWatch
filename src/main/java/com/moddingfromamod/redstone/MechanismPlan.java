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
 */
public record MechanismPlan(String kind, List<RsCell> cells, List<Expect> expects, List<Control> controls,
                            List<String> replaced, List<String> notes, String why) {

    public boolean ok() {
        return why == null;
    }

    public static MechanismPlan refused(String kind, String why) {
        return new MechanismPlan(kind, List.of(), List.of(), List.of(), List.of(), List.of(), why);
    }
}
