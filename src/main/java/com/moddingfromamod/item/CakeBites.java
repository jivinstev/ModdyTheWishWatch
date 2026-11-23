package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Set;

/**
 * <b>A fork that eats a cake faster (part 2).</b> A cake is a BLOCK he eats from, so
 * {@code eat_speed} cannot reach it: with a thing carrying {@code cake_bites} in his hand, one click on
 * a real cake takes that many bites — each exactly vanilla's (2 hunger, 0.1 saturation, the eat
 * sound), stopping when he is full or the cake is gone. The rules are vanilla's; only the click count
 * is his.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class CakeBites {

    /** E1's label: the cake-fork row names {@code eat_speed} (syn-4-040). */
    public static final Set<String> PARTS = Set.of("eat_speed");

    private CakeBites() {
    }

    @SubscribeEvent
    public static void onClick(PlayerInteractEvent.RightClickBlock event) {
        Player p = event.getEntity();
        int n = bites(p.getItemInHand(event.getHand()));
        if (n < 2 || p.level().isClientSide() || !p.level().getBlockState(event.getPos()).is(Blocks.CAKE)) {
            return;
        }
        int ate = eat(p.level(), event.getPos(), p, n);
        if (ate > 0) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** Take up to {@code n} bites of the cake at {@code pos}; how many he took. */
    public static int eat(Level level, BlockPos pos, Player p, int n) {
        int ate = 0;
        for (int i = 0; i < n; i++) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.CAKE) || !p.canEat(false)) {
                break;
            }
            p.getFoodData().eat(2, 0.1F);
            int b = state.getValue(CakeBlock.BITES);
            level.gameEvent(p, GameEvent.EAT, pos);
            if (b < 6) {
                level.setBlock(pos, state.setValue(CakeBlock.BITES, b + 1), 3);
            } else {
                level.removeBlock(pos, false);
                level.gameEvent(p, GameEvent.BLOCK_DESTROY, pos);
            }
            ate++;
        }
        if (ate > 0) {
            level.playSound(null, pos, SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 0.5F, 1.2F);
        }
        return ate;
    }

    public static int bites(ItemStack stack) {
        CustomData data = stack == null || stack.isEmpty() ? null : stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0;
        }
        int n = data.copyTag().getIntOr(com.moddingfromamod.datapack.ItemProperties.CAKE_BITES_DATA, 0);
        return n >= 2 && n <= 7 ? n : 0;
    }
}
