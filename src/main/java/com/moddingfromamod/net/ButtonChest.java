package com.moddingfromamod.net;

import net.minecraft.network.chat.Component;

/**
 * <b>A Moddy BUTTON CHEST</b> — a chest whose tiles DO something when clicked (the wish watch, the guide;
 * the Wish Crafter's chests next) rather than hold items. Open one with {@link #title} as its title.
 *
 * <p>Why it matters: on Bedrock's Pocket UI a chest tile cannot act on one tap, so a
 * Bedrock-bridge extension (ModdyButtonChests) shows a button chest to a Pocket player as a one-tap grid
 * of its tiles instead. It recognises one by this title's style INSERTION, which a Java client ignores in
 * a title — so Java players see exactly what they saw. A chest opened with a plain title is never touched.
 *
 * <p>Lay a button chest out so its SLOT ORDER reads well (controls first): a Pocket player sees the
 * tiles in slot order, at a width that depends on his screen ({@code WatchPanel.Grid.POCKET}).
 */
public final class ButtonChest {

    private ButtonChest() {
    }

    /** The insertion the extension looks for (ModdyButtonChests.MARKER — keep equal; ButtonChestTest). */
    public static final String MARKER = "moddy:button_chest";

    /**
     * A button chest's title: these words, marked — with the SERVER's layout ({@code moddy:button_chest/grid},
     * {@code /list}, {@code /off}; PlayerClient.chestLayout), which the extension follows. An extension older
     * than that matches only the bare marker and so sees no button chest: the server and its proxy ship together.
     */
    public static Component title(String text) {
        String marker = MARKER + "/" + PlayerClient.chestLayout(null);
        return Component.literal(text).withStyle(s -> s.withInsertion(marker));
    }

    /** The same, for the chest one player opens — his layout (PlayerClient.chestLayout(player)). */
    public static Component title(String text, net.minecraft.server.level.ServerPlayer player) {
        return title(text, player, null);
    }

    /** The same, with this page's own layout ({@code "list"}) unless his is {@code off}; null: his. */
    public static Component title(String text, net.minecraft.server.level.ServerPlayer player, String layout) {
        String his = PlayerClient.chestLayout(player);
        String marker = MARKER + "/" + (layout == null || "off".equals(his) ? his : layout);
        return Component.literal(text).withStyle(s -> s.withInsertion(marker));
    }
}
