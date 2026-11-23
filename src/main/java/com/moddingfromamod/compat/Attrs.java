package com.moddingfromamod.compat;

/**
 * <b>How this version spells a vanilla attribute id in TEXT</b> (1.21.4 half).
 *
 * <p>1.21.2 dropped the {@code generic.} prefix, so {@code minecraft:generic.armor} is
 * {@code minecraft:armor} here. Only for ids WE render into a command or a component; text the agent
 * or a template writes is corrected by {@code wish.CommandDialect} instead.
 */
public final class Attrs {
    private Attrs() {
    }

    /** A {@code generic.} attribute by its bare name, e.g. {@code generic("scale")}. */
    public static String generic(String bare) {
        return "minecraft:" + bare;
    }
}
