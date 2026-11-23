package com.moddingfromamod.compat;

/**
 * Whether this Minecraft has a real bundle a child can use. 1.21.2 released it; on 1.21.1
 * it is behind the experimental "bundle" feature flag, so a give of one fails — see the mc21 half.
 */
public final class Bundles {
    private Bundles() {
    }

    public static final boolean SUPPORTED = true;
}
