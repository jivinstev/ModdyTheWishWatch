package com.moddingfromamod.item;

/**
 * <b>A polished item glints briefly, then is exactly what it was</b>.
 * Minecraft-free: the decisions; {@link PolishGlintService} is the world half.
 *
 * <p>After the wish ends, a polish in place rewrites the stack he holds and tells him so in
 * one grey chat line; nothing on the item showed the change. Vanilla's enchantment glint shows on
 * every stock client with no pack, so a polished stack wears it for {@link #TICKS} ticks. The
 * override is STORED on the stack, so it must never stick: the stack carries a marker naming when
 * the glint ends and what the override was before, and whoever next sees an expired marker (the
 * sweep of his inventory, a container being opened, a login) puts the override back and removes
 * the marker, so the stack is byte for byte the polished design again.
 */
public final class PolishGlint {

    private PolishGlint() {
    }

    /** The {@code custom_data} key a glinting stack carries: {@code {until, was}}. */
    public static final String MARKER = "mfam_polish_glint";

    /** Two seconds: long enough to catch his eye, short enough to be a moment. */
    public static final int TICKS = 40;

    /** What the override was before the glint, as the marker stores it. */
    public static String was(Boolean current) {
        return current == null ? "absent" : current ? "on" : "off";
    }

    /** The override to put back: null removes it, as it was absent. */
    public static Boolean restore(String was) {
        if ("on".equals(was)) {
            return Boolean.TRUE;
        }
        if ("off".equals(was)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Has the glint run its time? */
    public static boolean expired(long until, long now) {
        return now >= until;
    }
}
