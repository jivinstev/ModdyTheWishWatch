package com.moddingfromamod.wish;

import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.ItemSpecStore;
import com.moddingfromamod.item.WishItem;
import com.moddingfromamod.item.WishItemCatalogue;
import com.moddingfromamod.marshal.WatchRow;
import com.moddingfromamod.marshal.WishCardContract;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: an item card says what the item is and does — never "—" four times.</b>
 *
 * <p>Live, 2026-10-14: the Best Mining Pickaxe EVER (a netherite pickaxe, Efficiency V, Fortune III,
 * unbreakable), the Strongest Sword Ever and a tracker all opened a card reading ✋ — 💥 — 🎨 — 🔁 —,
 * because the card was built only from the item's design and found none: a {@code plain} design was
 * dropped at every world load, a behaviour key ({@code <feature>:track}) was looked up whole, and a
 * handed-over vanilla tool had no design at all. Driven through the real
 * {@link WatchSnapshot#creationsFor} over real catalogued stacks, because the card is built there.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ItemCardFromStackGameTest {

    private ItemCardFromStackGameTest() {
    }

    private static final String OWNER = "15581558-0000-4000-8000-000000001558";

    /** Its own batch: it swaps the item catalogue for an isolated one while it runs. */
    @GameTest(template = "empty_test", batch = "itemCardFromStack")
    public static void anItemCardIsReadOffItsStackAndItsPlainDesign(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var enchants = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemSpecStore.put(new ItemSpecStore.Spec("gate1558_sword_plain", "Gate1558 Sword", "sword",
                null, "plain", 1, List.of(), new JsonObject(), new com.google.gson.JsonArray(),
                new JsonObject(), new com.google.gson.JsonArray(), "minecraft:iron_sword"));
        ItemSpecStore.put(new ItemSpecStore.Spec("gate1558_tracker", "Gate1558 Tracker", "compass",
                "#ff0000", "held", 1, List.of("heal"), new JsonObject()));
        try {
            WishItemCatalogue.inIsolationForTest(() -> {
                // (a) the pickaxe: a real netherite pickaxe, enchanted and unbreakable, NO design.
                ItemStack pick = named(new ItemStack(Items.NETHERITE_PICKAXE), "Gate1558 Pickaxe",
                        WishItem.WISH_FEATURE, "gate1558_pick_plain");
                pick.enchant(holder(enchants, Enchantments.EFFICIENCY), 5);
                pick.enchant(holder(enchants, Enchantments.FORTUNE), 3);
                pick.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
                // (b) a PLAIN design on a real sword: its effect-less design must be found.
                ItemStack sword = named(new ItemStack(Items.IRON_SWORD), "Gate1558 Sword",
                        WishItem.WISH_FEATURE, "gate1558_sword_plain");
                sword.enchant(holder(enchants, Enchantments.SHARPNESS), 5);
                // (c) a behaviour key, `<design>:track`, whose design is its namespace.
                ItemStack tracker = named(new ItemStack(Items.COMPASS), "Gate1558 Tracker",
                        WishItem.ON_USE, "gate1558_tracker:track");
                for (ItemStack s : List.of(pick, sword, tracker)) {
                    WishItemCatalogue.remember(server, s, OWNER);
                }

                WatchRow.Creation pickCard = card(helper, "id:gate1558_pick_plain");
                WatchRow.Creation swordCard = card(helper, "id:gate1558_sword_plain");
                WatchRow.Creation trackerCard = card(helper, "id:gate1558_tracker:track");
                if (pickCard == null || swordCard == null || trackerCard == null) {
                    return;
                }
                for (WatchRow.Creation c : List.of(pickCard, swordCard, trackerCard)) {
                    long dashes = c.fourVitals().stream()
                            .filter(v -> v.endsWith(WishCardContract.ABSENT)).count();
                    helper.assertTrue(dashes < 4, Component.literal(c.id() + " still says — four times: " + c.vitals()));
                    helper.assertTrue(c.fourVitals().get(0).startsWith("✋")
                                    && c.fourVitals().get(1).startsWith("💥")
                                    && c.fourVitals().get(2).startsWith("🎨")
                                    && c.fourVitals().get(3).startsWith("🔁"),
                            Component.literal(c.id() + "'s vitals lost their icons: " + c.vitals()));
                }
                expect(helper, pickCard, 0, "✋ a netherite pickaxe");
                expect(helper, pickCard, 1, "mines super fast (level 5)");
                expect(helper, pickCard, 1, "more drops from ores (level 3)");
                expect(helper, pickCard, 1, "never breaks");
                expect(helper, pickCard, 3, "🔁 made once");
                helper.assertTrue(pickCard.powers().contains("3 hearts of damage"),
                        Component.literal("the pickaxe's attack damage is missing: " + pickCard.powers()));
                expect(helper, swordCard, 0, "✋ an iron sword");
                expect(helper, swordCard, 1, "hits extra hard (level 5)");
                helper.assertTrue(swordCard.powers().stream().anyMatch(p -> p.endsWith("of damage")),
                        Component.literal("the sword's attack damage is missing: " + swordCard.powers()));
                expect(helper, trackerCard, 0, "✋ a thing you hold and use");
                expect(helper, trackerCard, 2, "🎨 red");
                helper.assertTrue(!trackerCard.fourVitals().get(1).endsWith(WishCardContract.ABSENT),
                        Component.literal("the tracker's design was not found from its behaviour key: "
                                + trackerCard.vitals()));
            });
        } finally {
            ItemSpecStore.remove("gate1558_sword_plain");
            ItemSpecStore.remove("gate1558_tracker");
        }
        helper.succeed();
    }

    private static net.minecraft.core.Holder<Enchantment> holder(
            net.minecraft.core.HolderGetter<Enchantment> lookup, ResourceKey<Enchantment> key) {
        return lookup.getOrThrow(key);
    }

    private static ItemStack named(ItemStack s, String name, String tagKey, String tagValue) {
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        CompoundTag tag = new CompoundTag();
        tag.putString(tagKey, tagValue);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    private static WatchRow.Creation card(GameTestHelper helper, String id) {
        for (String row : WatchSnapshot.creationsFor(OWNER)) {
            WatchRow.Creation c = WatchRow.Creation.decode(row);
            if (c.id().equals(id)) {
                return c;
            }
        }
        helper.fail(Component.literal("no watch card for " + id));
        return null;
    }

    private static void expect(GameTestHelper helper, WatchRow.Creation c, int slot, String part) {
        String line = c.fourVitals().get(slot);
        helper.assertTrue(line.toLowerCase(java.util.Locale.ROOT)
                        .contains(part.toLowerCase(java.util.Locale.ROOT)),
                Component.literal(c.id() + " vital " + slot + " should say '" + part + "', says '" + line + "' "
                        + c.vitals()));
    }
}
