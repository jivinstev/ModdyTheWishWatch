package com.moddingfromamod.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.ARGB;

/**
 * <b>A pixel read that means the same thing on every target</b> (1.21.4 half).
 *
 * <p>Every reader here was written against 1.21.1's {@code NativeImage.getPixelRGBA}, which
 * returns the raw memory word: <b>ABGR</b>, whatever the name says (the client tests say so in
 * as many words, and it is how the camera learned to find red). 1.21.4 made that raw read private
 * ({@code getPixelABGR}) and gave the public name to {@code getPixel}, which converts to
 * <b>ARGB</b>: its body is {@code ARGB.fromABGR(getPixelABGR(x, y))}, read with javap.
 *
 * <p>So a rename would compile on both targets and silently swap red and blue in every photograph
 * a test inspects (the migrator's section V34: a name that changed because the VALUE changed).
 * This answers the question the callers actually ask, the ABGR word, on each target;
 * its 1.21.1 half went with that build (branch parked/create-trains-mc1.21.1).
 */
public final class Pixels {
    private Pixels() {
    }

    /** The pixel at (x, y) packed ABGR (0xAABBGGRR), exactly as 1.21.1's getPixelRGBA returned it. */
    public static int abgr(NativeImage img, int x, int y) {
        return ARGB.toABGR(img.getPixel(x, y));
    }
}
