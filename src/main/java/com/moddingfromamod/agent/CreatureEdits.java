package com.moddingfromamod.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WishRun;

/**
 * Where a tool reports that it changed a creature's SAVED DESIGN, so {@code finish_wish}
 * can tell him the edit is kept instead of "I can't save it for next time".
 *
 * <p>Three tools change a stored creature: a re-sculpt ({@code sculpt_creature} on an id that
 * exists), {@code modify_mob} when it writes to the design, and {@code paint_creature}. Only an
 * EDIT is recorded: a change to a creature this same wish made is part of its creation, and an
 * earlier granted wish having made it is what {@link McpProtocol#madeBefore} answers. A change that
 * made HIS OWN copy of someone else's creature is always recorded — the copy is his and it is
 * saved.
 *
 * <p>{@code modify_mob} decides on the SERVER thread, where the wish's run is not bound (it is per
 * worker thread). So it collects into a list this class lends the server-thread work for one call
 * ({@link #collecting}), and the tool records the list on the way out, on its own thread.
 */
public final class CreatureEdits {

    private CreatureEdits() {}

    /** One change, as the tool saw it. */
    public record Edit(String id, String baseId, String before, String after, boolean forked,
                       boolean reshaped) {
    }

    private static final ThreadLocal<List<Edit>> COLLECTING = new ThreadLocal<>();

    /** Run {@code work} with a list that {@link #collect} on this thread adds to. */
    public static <T> T collecting(List<Edit> into, Supplier<T> work) {
        COLLECTING.set(into);
        try {
            return work.get();
        } finally {
            COLLECTING.remove();
        }
    }

    /** From inside {@link #collecting}: note one change. Outside it (a taught button's replay), a no-op. */
    public static void collect(Edit e) {
        List<Edit> into = COLLECTING.get();
        if (into != null && e != null) {
            into.add(e);
        }
    }

    /** Record these on the wish this worker is granting. */
    public static void record(List<Edit> edits) {
        if (edits == null) {
            return;
        }
        for (Edit e : new ArrayList<>(edits)) {
            record(e);
        }
    }

    /** Record one change on the wish this worker is granting, if it is an edit. */
    public static void record(Edit e) {
        if (e == null) {
            return;
        }
        WishRun run = AgentContext.currentRun();
        if (run == null) {
            return;
        }
        if (!e.forked() && !McpProtocol.madeBefore(MadeKind.CREATURE, e.baseId())) {
            return;     // made by this wish: a creation, not an edit
        }
        run.creatureEdited(e.id(), e.before(), e.after(), e.forked(), e.reshaped());
    }
}
