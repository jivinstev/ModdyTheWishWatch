package com.moddingfromamod.wish;

import java.util.Set;

/**
 * <b>Can this tool change his world? </b> A wish whose only successful calls were look-ups,
 * a plan and status lines has made nothing, whatever it then says. Read-only tools are the ones a
 * finished worker may still call ({@link com.moddingfromamod.marshal.CancelGate#allowedAfterFinish});
 * the rest of the exclusions only plan, narrate, or end the wish.
 */
public final class WorldChanging {

    private static final Set<String> NOT_A_CHANGE = Set.of(
            "plan_wish", "set_status", "notify_player", "conjure_at", "finish_building",
            "finish_wish", "await_wish");

    private WorldChanging() {
    }

    public static boolean tool(String name) {
        return name != null && !name.isBlank() && !NOT_A_CHANGE.contains(name)
                && !com.moddingfromamod.marshal.CancelGate.allowedAfterFinish(name);
    }
}
