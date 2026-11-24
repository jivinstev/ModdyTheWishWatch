package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.SeededContainerLoot;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <b>A rod that catches something special.</b> On the REAL catch (NeoForge's
 * {@code ItemFishedEvent}, fired when he reels in a bite), a rod carrying {@code catches} rolls its
 * chance; on a hit the catch is his item instead of the fish. The event's drop list is read-only, so
 * the fish is cancelled and the new catch is flung from the hook to him exactly as vanilla flings a
 * fish (same motion), with the same experience — and the rod still wears as it would have.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class CustomCatch {

    /** E1's label: the three fishing-rod rows name {@code custom_catch}. */
    public static final Set<String> PARTS = Set.of("custom_catch");

    private CustomCatch() {
    }

    @SubscribeEvent
    public static void onFished(ItemFishedEvent event) {
        Player p = event.getEntity();
        FishingHook hook = event.getHookEntity();
        if (!(p.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack rod = p.getMainHandItem().is(Items.FISHING_ROD) ? p.getMainHandItem() : p.getOffhandItem();
        String[] c = setting(rod);
        if (c == null || level.random.nextInt(100) >= Integer.parseInt(c[1])) {
            return;
        }
        ItemStack caught = make(level, pick(level, c[0]));
        if (caught.isEmpty()) {
            return;
        }
        event.setCanceled(true);
        fling(level, hook, p, caught);
    }

    /** The {what, chance} this rod carries, or null. */
    public static String[] setting(ItemStack rod) {
        CustomData data = rod == null || rod.isEmpty() ? null : rod.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag all = data.copyTag();
        if (!all.contains(com.moddingfromamod.datapack.ItemProperties.CATCHES_DATA)) {
            return null;
        }
        CompoundTag t = all.getCompoundOrEmpty(com.moddingfromamod.datapack.ItemProperties.CATCHES_DATA);
        int chance = t.getIntOr("chance", 0);
        String what = t.getStringOr("what", "");
        return what.isEmpty() || chance < 1 || chance > 100 ? null : new String[]{what, String.valueOf(chance)};
    }

    static String pick(ServerLevel level, String what) {
        List<String> all = List.of(what.split(","));
        return all.get(level.random.nextInt(all.size()));
    }

    /** One catch, from our words or a checked id; empty when the id is not a real item. */
    public static ItemStack make(ServerLevel level, String what) {
        if ("treasure_chest".equals(what)) {
            ItemStack chest = new ItemStack(Items.CHEST);
            chest.set(DataComponents.CONTAINER_LOOT, new SeededContainerLoot(
                    ResourceKey.create(Registries.LOOT_TABLE,
                            Identifier.withDefaultNamespace("chests/buried_treasure")),
                    level.random.nextLong()));
            chest.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Treasure Chest"));
            return chest;
        }
        if ("enchanted_book".equals(what)) {
            return EnchantmentHelper.enchantItem(level.random, new ItemStack(Items.BOOK), 30,
                    level.registryAccess(), Optional.empty());
        }
        Identifier id = Identifier.tryParse(what);
        Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
        return item.map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** Vanilla's own fling (FishingHook.retrieve): the catch arcs from the hook to him. */
    static void fling(ServerLevel level, FishingHook hook, Player p, ItemStack caught) {
        ItemEntity e = new ItemEntity(level, hook.getX(), hook.getY(), hook.getZ(), caught);
        double dx = p.getX() - hook.getX();
        double dy = p.getY() - hook.getY();
        double dz = p.getZ() - hook.getZ();
        e.setDeltaMovement(dx * 0.1, dy * 0.1 + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1);
        level.addFreshEntity(e);
        level.addFreshEntity(new ExperienceOrb(level, p.getX(), p.getY() + 0.5, p.getZ() + 0.5,
                level.random.nextInt(6) + 1));
    }
}
