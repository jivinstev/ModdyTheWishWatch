package com.moddingfromamod.compat;

/** The Minecraft version THIS build targets (1.21.11), for tools outside the JVM that must know. */
public final class Target {
    private Target() {
    }

    /** Handed to the CBScript compiler as {@code CBSCRIPT_MC}, which flips its attribute-id correction. */
    public static final String MINECRAFT = "1.21.11";
}
