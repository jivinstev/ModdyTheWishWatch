package com.moddingfromamod.wish;

/** Lets a test in another package clear {@link WishOrigin}'s statics. */
public final class WishOriginTestAccess {
    private WishOriginTestAccess() {
    }

    public static void reset() {
        WishOrigin.resetForTest();
    }
}
