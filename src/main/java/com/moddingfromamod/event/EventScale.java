package com.moddingfromamod.event;

/**
 * <b>How big an event may be</b> (E1; D2 in docs/EVENTS_PLAN.md). Pure.
 *
 * <p>A budget, not a taste: An earlier mod's flood at a 96-block reach wrote ~3M blocks and ran its test server
 * out of memory. Every event declares a scale, and the director holds it to BOTH numbers: the total
 * cells it may change (the journal's size) and the cells it may write in one tick (so the server keeps
 * its 20 ticks a second — the builder's own rate is 8000).
 */
public enum EventScale {
    LITTLE(8_000, 2_000, 16),
    MEDIUM(40_000, 4_000, 32),
    BIG(120_000, 6_000, 48),
    GIGANTIC(250_000, 8_000, 64);

    /** Cells the event may change in all. */
    public final int totalBlocks;
    /** Cells it may write (or give back) in one tick. */
    public final int perTick;
    /** How far from its centre anything may happen. */
    public final int radius;

    EventScale(int totalBlocks, int perTick, int radius) {
        this.totalBlocks = totalBlocks;
        this.perTick = perTick;
        this.radius = radius;
    }

    /** The scale a child's word asks for; null for a word this does not know. */
    public static EventScale parse(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "little", "small", "tiny" -> LITTLE;
            case "medium", "normal" -> MEDIUM;
            case "big", "large" -> BIG;
            case "gigantic", "huge", "giant", "mega" -> GIGANTIC;
            default -> null;
        };
    }
}
