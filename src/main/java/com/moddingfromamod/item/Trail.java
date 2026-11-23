package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Leaves something behind him.</b> While a thing carrying {@code trail} is in his
 * hand or on his body, every block he steps OFF gets the trail — if that spot is empty and the trail
 * can stay there (a flower only on soil, snow only on something solid). A fire trail keeps him fire
 * resistant while he has it, so the fire is behind him and never on him.
 *
 * <p>From the server tick over every player, as {@code StrongerWhen} does: a player's own tick
 * belongs to his connection, and a gate's mock player has none.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class Trail {

    /** E1's label: the fire-sword row names {@code trail} (syn-5-031). */
    public static final Set<String> PARTS = Set.of("trail");

    private static final List<Block> FLOWERS = List.of(Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID,
            Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP,
            Blocks.PINK_TULIP, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY);

    /** Where each player's feet were at the last look, so the block he LEFT is known. */
    private static final Map<UUID, BlockPos> LAST = new ConcurrentHashMap<>();

    private Trail() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            step(p);
        }
    }

    /** One look at him: the trail he carries, and whether he has left a block. */
    public static void step(ServerPlayer p) {
        String trail = carried(p);
        BlockPos now = p.blockPosition();
        BlockPos was = LAST.put(p.getUUID(), now);
        if (trail == null) {
            return;
        }
        if ("fire".equals(trail)) {
            p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 60, 0, true, false));
        }
        if (was != null && !was.equals(now) && p.level() == p.level()) {
            place(p.level(), was, trail);
        }
    }

    /** Put the trail at this spot if it is empty and the trail can stay there. */
    public static boolean place(ServerLevel level, BlockPos at, String trail) {
        if (!level.getBlockState(at).isAir()) {
            return false;
        }
        BlockState state = switch (trail) {
            case "fire" -> BaseFireBlock.getState(level, at);
            case "flowers" -> FLOWERS.get(level.random.nextInt(FLOWERS.size())).defaultBlockState();
            case "snow" -> Blocks.SNOW.defaultBlockState();
            case "light" -> Blocks.LIGHT.defaultBlockState();
            default -> null;
        };
        if (state == null || !state.canSurvive(level, at)) {
            return false;
        }
        return level.setBlock(at, state, Block.UPDATE_ALL);
    }

    /** The trail on anything in his hands or on his body, or null. */
    static String carried(ServerPlayer p) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            String t = trailOn(p.getItemBySlot(slot));
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    static String trailOn(ItemStack stack) {
        CustomData data = stack == null || stack.isEmpty() ? null : stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        String t = data.copyTag().getStringOr(com.moddingfromamod.datapack.ItemProperties.TRAIL_DATA, "");
        return com.moddingfromamod.datapack.ItemProperties.TRAILS.contains(t) ? t : null;
    }
}
