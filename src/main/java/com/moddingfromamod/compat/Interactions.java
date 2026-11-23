package com.moddingfromamod.compat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * <b>Interaction results</b> (1.21.4 half).
 *
 * <p>1.21.2 folded {@code InteractionResultHolder} into {@code InteractionResult} and removed
 * {@code sidedSuccess}. Vanilla replaced {@code sidedSuccess(isClientSide)} with a bare {@code SUCCESS};
 * we do not, deliberately. 1.21.1's {@code sidedSuccess} was {@code clientSide ? SUCCESS : CONSUME},
 * and the watch depends on the server answering CONSUME (its own comment: a server that also reports
 * success closes the container it just opened). So {@link #success} keeps the per-side values
 * exactly. This is a judgement row, verified in a real client (the watch stays open), not by compile.
 */
public final class Interactions {
    private Interactions() {
    }

    /** 1.21.1's {@code InteractionResult.sidedSuccess(clientSide)}. */
    public static InteractionResult success(boolean clientSide) {
        return clientSide ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }

    /** Use an item and answer only the result (1.21.1 wrapped it in a holder with the stack). */
    public static InteractionResult use(Item item, Level level, Player player, InteractionHand hand) {
        return item.use(level, player, hand);
    }
}
