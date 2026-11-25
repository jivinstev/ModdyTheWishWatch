package com.moddingfromamod.world;

import java.util.Map;
import java.util.TreeMap;

/**
 * <b>What a world says about one make</b> ({@link WorldMakes#view}):
 * <ul>
 *   <li>{@link State#ALLOWED}: made as it is;</li>
 *   <li>{@link State#HIDDEN}: not offered here at all (absent from menus and pages);</li>
 *   <li>{@link State#LOCKED}: shown but not made yet, with the world's own {@code words} ("Unlocks after you build a
 *       lighthouse"), which the core sweeps as it sweeps a chat line, saying a fallback line in place of words that do
 *       not pass;</li>
 *   <li>{@link State#FIT}: made, but within {@code limits}: DATA, a map from a {@code MakeFacts.NUMBERS} key to the most
 *       this world allows of it. The core fits the make to them (a smaller size, fewer at once, a gentler strength) and
 *       says so in its own words; a make it cannot fit is locked in the core's own words. A key the core does not know
 *       is ignored, and a limit on a number the make does not carry does nothing.</li>
 * </ul>
 * A record, so a later answer is a new state or field, never a new signature.
 */
public record MakeView(State state, String words, Map<String, Double> limits) {

    public enum State { ALLOWED, HIDDEN, LOCKED, FIT }

    public static final MakeView ALLOWED = new MakeView(State.ALLOWED, "", Map.of());
    public static final MakeView HIDDEN = new MakeView(State.HIDDEN, "", Map.of());

    public MakeView {
        state = state == null ? State.ALLOWED : state;
        words = words == null ? "" : words.strip();
        limits = WorldLimits.clean(limits);
    }

    /** Locked, with what the player reads ({@code ""}: the core's own line). */
    public static MakeView locked(String words) {
        return new MakeView(State.LOCKED, words, Map.of());
    }

    /** Made within these limits (a {@code MakeFacts.NUMBERS} key → its most). */
    public static MakeView fit(Map<String, Double> limits) {
        return new MakeView(State.FIT, "", limits == null ? Map.of() : new TreeMap<>(limits));
    }

    public boolean allowed() {
        return state == State.ALLOWED;
    }
}
