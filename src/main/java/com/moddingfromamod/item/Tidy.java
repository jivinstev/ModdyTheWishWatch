package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <b>A bag that tidies — the half that touches the game.</b> The {@code sort_inventory}
 * and {@code drop_junk} effects tag him; at the end of the tick this sorts his MAIN inventory (slots
 * 9–35: his hotbar is where his sword is, and is never moved) or drops the junk from it at his feet.
 * A thing carrying {@code drops_junk} does the dropping by itself whenever his main inventory is full.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class Tidy {

    /** E1's label: the sorting-backpack row names {@code inventory_sort} (syn-4-034). */
    public static final Set<String> PARTS = Set.of("inventory_sort", "drop_junk");

    public static final String SORT_TAG = "mfam_sort_inventory";
    public static final String JUNK_TAG = "mfam_drop_junk";
    private static final int FIRST = Inventory.getSelectionSize();   // 9: the first slot past the hotbar
    private static final int END = 36;

    private Tidy() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        boolean look = event.getServer().getTickCount() % 20 == 0;
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            if (p.entityTags().contains(SORT_TAG)) {
                p.removeTag(SORT_TAG);
                sort(p);
            }
            if (p.entityTags().contains(JUNK_TAG)) {
                p.removeTag(JUNK_TAG);
                dropJunk(p);
            }
            if (look && carries(p, com.moddingfromamod.datapack.ItemProperties.DROPS_JUNK_DATA) && mainIsFull(p)) {
                dropJunk(p);
            }
            if (look && p.containerMenu == p.inventoryMenu && p.containerMenu.getCarried().isEmpty()
                    && carries(p, com.moddingfromamod.datapack.ItemProperties.AUTO_SORT_DATA)) {
                sort(p);   // only with no screen open and nothing on his cursor
            }
        }
    }

    /** Stack like things together and lay them out by group. */
    public static void sort(ServerPlayer p) {
        Inventory inv = p.getInventory();
        List<ItemStack> piles = new ArrayList<>();
        for (int i = FIRST; i < END; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            ItemStack left = s.copy();
            for (ItemStack pile : piles) {
                if (left.isEmpty()) {
                    break;
                }
                if (ItemStack.isSameItemSameComponents(pile, left) && pile.getCount() < pile.getMaxStackSize()) {
                    int move = Math.min(left.getCount(), pile.getMaxStackSize() - pile.getCount());
                    pile.grow(move);
                    left.shrink(move);
                }
            }
            if (!left.isEmpty()) {
                piles.add(left);
            }
            inv.setItem(i, ItemStack.EMPTY);
        }
        List<Integer> order = new ArrayList<>();
        List<TidyRule.Stack> keys = new ArrayList<>();
        for (int i = 0; i < piles.size(); i++) {
            order.add(i);
            keys.add(new TidyRule.Stack(BuiltInRegistries.ITEM.getKey(piles.get(i).getItem()).toString(),
                    group(piles.get(i)), piles.get(i).getCount()));
        }
        order.sort((a, b) -> TidyRule.ORDER.compare(keys.get(a), keys.get(b)));
        List<TidyRule.Stack> sorted = new ArrayList<>();
        for (int i : order) {
            sorted.add(keys.get(i));
        }
        int[] start = TidyRule.rowStarts(sorted);
        int[] next = start.clone();
        for (int i : order) {
            int g = keys.get(i).group().ordinal();
            inv.setItem(FIRST + next[g]++, piles.get(i));
        }
        inv.setChanged();
    }

    /** Drop the junk from his main inventory at his feet; how many stacks went. */
    public static int dropJunk(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = FIRST; i < END; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && TidyRule.isJunk(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())) {
                inv.setItem(i, ItemStack.EMPTY);
                p.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
                n++;
            }
        }
        inv.setChanged();
        return n;
    }

    static TidyRule.Group group(ItemStack s) {
        if (s.getItem() instanceof BlockItem) {
            return TidyRule.Group.BLOCKS;
        }
        if (s.has(DataComponents.FOOD)) {
            return TidyRule.Group.FOOD;
        }
        if (s.isDamageableItem()) {
            return TidyRule.Group.TOOLS;
        }
        return TidyRule.Group.OTHER;
    }

    static boolean mainIsFull(ServerPlayer p) {
        for (int i = FIRST; i < END; i++) {
            if (p.getInventory().getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Anything anywhere on him (a bag in his inventory counts) carrying this custom_data flag. */
    static boolean carries(ServerPlayer p, String flag) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            CustomData d = s.isEmpty() ? null : s.get(DataComponents.CUSTOM_DATA);
            if (d != null && d.copyTag().getBooleanOr(flag, false)) {
                return true;
            }
        }
        return false;
    }
}
