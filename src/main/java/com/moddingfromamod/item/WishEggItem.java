package com.moddingfromamod.item;

import java.util.List;

import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * A spawn egg for a wished-for creature.
 *
 * <p><b>One</b> egg item is registered, not one per creature — registries freeze at load, so a
 * genuinely new {@code Item} can never appear at runtime, exactly as with entity types. The creature
 * it hatches is <em>data</em> on the stack, which is why a creature invented thirty seconds ago can
 * have a working egg in the hotbar with no restart and no resource reload.
 *
 * <p>Everything that makes an egg look like <i>its</i> creature is derived per stack: the name comes
 * from the species, and the two tint layers from {@link com.moddingfromamod.runtime.EggColours}
 * (bound client-side), so the shared vanilla spawn-egg template renders a differently-coloured egg
 * for every creature.
 */
public class WishEggItem extends Item {

    /** The tag key inside the stack's custom data holding which creature this egg hatches. */
    public static final String SPECIES_TAG = "WishSpecies";

    public WishEggItem(Properties properties) {
        super(properties);
    }

    /** Build an egg for a species. */
    public static ItemStack of(Item eggItem, String speciesId) {
        ItemStack stack = new ItemStack(eggItem);
        setSpeciesId(stack, speciesId);
        return stack;
    }

    public static void setSpeciesId(ItemStack stack, String speciesId) {
        CompoundTag tag = new CompoundTag();
        tag.putString(SPECIES_TAG, speciesId == null ? "" : speciesId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** Which creature does this egg hatch? Empty when it isn't a configured egg. */
    public static String speciesId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return "";
        }
        return data.copyTag().getStringOr(SPECIES_TAG, "");
    }

    /** The species this egg hatches, or null if its definition is missing. */
    public static Species species(ItemStack stack) {
        String id = speciesId(stack);
        return id.isEmpty() ? null : SpeciesRegistry.get(id);
    }

    @Override
    public Component getName(ItemStack stack) {
        Species s = species(stack);
        if (s == null) {
            return super.getName(stack);
        }
        // "Baby Dragon Spawn Egg" — reads like a vanilla egg, which is the point.
        return Component.literal(s.name() + " Spawn Egg");
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        Species s = species(stack);
        if (s == null) {
            String id = speciesId(stack);
            tooltip.accept(Component.literal(id.isEmpty()
                            ? "Use it to bring a wish creature into the world"
                            : "This creature (" + id + ") isn't in this world")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.accept(Component.literal((int) s.health() + " health · "
                + (int) s.damage() + " damage").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();
        Species s = species(stack);
        if (s == null) {
            return InteractionResult.FAIL;
        }
        BlockPos clicked = context.getClickedPos();
        Direction face = context.getClickedFace();
        // Spawn on top of the clicked face, like a vanilla spawn egg.
        BlockPos spawnAt = level.getBlockState(clicked).getCollisionShape(level, clicked).isEmpty()
                ? clicked : clicked.relative(face);

        // The one spawn path: the rig is built here and its carrier handed back, so an
        // egg that stood nothing up keeps the egg rather than eating it for nothing.
        var carrier = com.moddingfromamod.vanilla.CreatureSpawn.spawn(serverLevel, s.id(),
                spawnAt.getX() + 0.5, spawnAt.getY(), spawnAt.getZ() + 0.5,
                context.getHorizontalDirection().toYRot(), EntitySpawnReason.SPAWN_ITEM_USE, false);
        if (carrier == null) {
            return InteractionResult.FAIL;
        }
        if (context.getPlayer() != null) {
            // An egg's creature is the child's who used it.
            com.moddingfromamod.mob.Creatures.stampSpawner(carrier,
                    context.getPlayer().getUUID().toString());
        }

        if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
