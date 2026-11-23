package com.moddingfromamod.compat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * <b>An item with a right-click in the air</b> (1.21.4 half).
 *
 * <p>{@code Item.use} changed its RETURN type (holder of the stack -> bare result), and a return type
 * is the one thing neither a rename nor a static helper can carry for an override (migrator W5b). So
 * the override lives here, per target, and the item answers one shared question: did the use do
 * anything? Both items hand back the stack they were holding, so the holder's payload was never
 * information and dropping it loses nothing.
 */
public abstract class UseItem extends Item {
    protected UseItem(Properties properties) {
        super(properties);
    }

    public enum Outcome { PASS, SUCCESS }

    protected abstract Outcome onUse(Level level, Player player, InteractionHand hand);

    @Override
    public final InteractionResult use(Level level, Player player, InteractionHand hand) {
        return onUse(level, player, hand) == Outcome.PASS
                ? InteractionResult.PASS
                : Interactions.success(level.isClientSide());
    }
}
