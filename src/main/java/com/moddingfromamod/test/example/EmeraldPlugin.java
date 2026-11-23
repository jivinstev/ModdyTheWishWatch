package com.moddingfromamod.test.example;

import com.moddingfromamod.plugin.ModdyPlugin;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.wish.WishCharge;

/**
 * <b>How a plug-in registers {@link EmeraldWishCharge}</b> ({@code docs/EXTENDING.md}). A real plug-in is listed,
 * one class name per line, in {@code META-INF/services/com.moddingfromamod.plugin.ModdyPlugin}; this example is NOT
 * listed (it would charge every wish in every run), and its GameTest stands it in with {@code Plugins.replaceForTest}.
 */
public final class EmeraldPlugin implements ModdyPlugin {

    @Override
    public String name() {
        return "emerald-wishes";
    }

    @Override
    public void register(Plugins plugins) {
        plugins.add(WishCharge.class, new EmeraldWishCharge());
    }
}
