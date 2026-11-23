package com.moddingfromamod.wish;

/** How a wish was made — useful for tuning the presets a kid actually uses. */
public enum WishSource {
    /** One of the big preset buttons on the watch. */
    PRESET,
    /** Free text typed into the watch. */
    TYPED,
    /** The /wish command (mostly for grown-ups and tests). */
    COMMAND,
    /** A Wish Builder make: picked from menus, lowered to tool calls, no model. */
    BUILDER;

    public static WishSource parse(String s) {
        for (WishSource v : values()) {
            if (v.name().equalsIgnoreCase(s)) {
                return v;
            }
        }
        return TYPED;
    }
}
