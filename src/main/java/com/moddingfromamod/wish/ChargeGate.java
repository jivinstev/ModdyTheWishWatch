package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The core's one way to ask this world's charge whether a wish may start</b> ({@link WishCharge#refuses}), and to
 * say its answer. Every place a wish starts asks through here, so the distress rule, the text sweep and the fallback
 * line ({@link ChargeRefusal}) are the same at each. Server thread.
 */
public final class ChargeGate {

    private ChargeGate() {
    }

    /** What this world's charge says about {@code ask}, decided: whether it stops, and the line he then reads. */
    public static ChargeRefusal ask(WishAsk ask, ChargeRefusal.Where where) {
        return ChargeRefusal.decide(ask, WishCharge.get().refuses(ask), where, line -> WishFeedback.sweeps(line, ""));
    }

    /** Say a refusal's line, once; nothing when it has none (the charge said its own words). */
    public static void say(ServerPlayer player, ChargeRefusal refusal) {
        if (player != null && refusal.refused() && !refusal.line().isEmpty()) {
            player.sendSystemMessage(Component.literal(refusal.line()));
        }
    }
}
