package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.wish.WishVerdict;

/** Test access to FinishWishTool's package-private verdict rules. */
public final class FinishWishToolAccess {
    private FinishWishToolAccess() {
    }

    public static boolean gaveNoVerdict(JsonObject args) {
        return FinishWishTool.gaveNoVerdict(args);
    }

    public static WishVerdict nothingChanged(WishVerdict v, boolean noVerdict, boolean nothing) {
        return FinishWishTool.nothingChanged(v, noVerdict, nothing);
    }
}
