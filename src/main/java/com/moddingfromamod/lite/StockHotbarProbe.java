package com.moddingfromamod.lite;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchIcons;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.wish.WishHotbar;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.GameType;

/**
 * <b>The wish hotbar in a STOCK client </b>: the server half of {@code HOTBARWISH=on ./tools/stock-icons.sh}.
 *
 * <p>Three things he can make again, one per kind of picture a proxy wears (a vanilla cake, a dyed icon-library
 * sprite, a wished potion drawn as itself). For each the probe opens the thing's real page and writes
 * {@code page-N} with where the "Add to hotbar" tile is; the harness moves a stock client's pointer there,
 * photographs, and CLICKS, and the probe waits for the proxy to appear in his inventory ({@code added-N}). Then
 * {@code hud}: the harness photographs the hotbar, the tooltips and a use; a use runs the learned recipe, which
 * tags him, and the probe writes {@code fired-N}. Every click is the client's; the server only stages.
 *
 * <p>Recipes are learned in memory for the run and the shelf is the run's own ({@code playersDir} under the
 * probe's directory, set by the harness): a probe never writes a child's shelf (docs/WISH_STORES.md §9).
 */
final class StockHotbarProbe {

    /** {@code -Dmoddingfromamod.iconprobehotbar=true}: this arm. */
    static final String PROPERTY = ModdingFromAModMod.MODID + ".iconprobehotbar";

    private static final String[] NAMES = {"Birthday Cake", "Baby Dragon", "Bubblegum Potion"};
    private static final String[] RECIPES = {"hb2755_cake", "hb2755_dragon", "hb2755_potion"};
    private static final String TAG = "hb2755_fired_";
    /** Ticks to wait for the client's click before staging the proxy itself, so the run still reaches the rest. */
    private static final int CLICK_TIMEOUT = 1200;

    private static WatchPanel.Cell[] cells;
    private static int step;
    private static int ticks;
    private static final boolean[] fired = new boolean[3];
    private static boolean extras;

    private StockHotbarProbe() {
    }

    static boolean on() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Seed the three things and his inventory. */
    static void setup(ServerPlayer sp, BiConsumer<String, String> mark) {
        var server = ((net.minecraft.server.level.ServerLevel) sp.level()).getServer();
        com.moddingfromamod.safety.SafetyGrader.forTest(t -> com.moddingfromamod.safety.SafetyVerdict.fine(
                com.moddingfromamod.safety.SafetyVerdict.Provenance.HAIKU));
        List<String> learned = new ArrayList<>(com.moddingfromamod.wish.LearnedTemplates.savedJsonForTest());
        for (int i = 0; i < 3; i++) {
            learned.add("{\"id\":\"" + RECIPES[i] + "\",\"all\":[\"hb2755" + i + "\"],\"ownerId\":\""
                    + sp.getStringUUID() + "\",\"commands\":[\"tag @s add " + TAG + i
                    + "\",\"say hotbar wish fired: " + NAMES[i] + "\"]}");
        }
        com.moddingfromamod.wish.LearnedTemplates.restoreForTest(learned, server);

        ItemStack potion = new ItemStack(Items.POTION);
        potion.set(DataComponents.CUSTOM_NAME, Component.literal(NAMES[2]));
        potion.set(DataComponents.POTION_CONTENTS,
                new PotionContents(Optional.empty(), Optional.of(0xFF66CC), List.of(), Optional.empty()));
        com.moddingfromamod.item.WishItemCatalogue.remember(server, potion, sp.getStringUUID());
        String key = null;
        for (var e : com.moddingfromamod.item.WishItemCatalogue.all()) {
            if (e.name() != null && e.name().contains(NAMES[2])) {
                key = e.key();
            }
        }
        String[] icons = {"minecraft:cake", WatchIcons.sprite("baby_dragon", 0x55CC33),
                key == null ? "minecraft:barrier" : WatchIcons.STACK_PREFIX + key};
        cells = new WatchPanel.Cell[3];
        for (int i = 0; i < 3; i++) {
            cells[i] = new WatchPanel.Cell(0, WatchPanel.Family.CREATION, WatchPanel.Action.CARD,
                    NAMES[i], NAMES[i], RECIPES[i], NAMES[i], icons[i], List.of());
        }
        // Noon and peaceful: on a fresh flat world a night's mobs killed him before the first page (a rig fault).
        for (String cmd : List.of("time set noon", "gamerule advance_time false", "difficulty peaceful", "kill @e[type=!player]")) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
        }
        // A world kept from an earlier run still carries its tags, which would read as "made" before any use.
        for (int i = 0; i < 3; i++) {
            sp.removeTag(TAG + i);
        }
        sp.setGameMode(GameType.SURVIVAL);
        sp.getAbilities().invulnerable = true;
        sp.onUpdateAbilities();
        var inv = sp.getInventory();
        for (int i = 2; i < inv.getContainerSize(); i++) {
            inv.setItem(i, ItemStack.EMPTY);
        }
        // The history pictures in slots 7–9, so each proxy sits in one frame with its picture.
        for (int i = 0; i < 3; i++) {
            inv.setItem(6 + i, com.moddingfromamod.wish.WatchMenu.iconStack(icons[i]));
        }
        // Sky behind the hotbar: nothing drawn over it but the sky.
        sp.connection.teleport(sp.getX(), sp.getY(), sp.getZ(), 0f, -40f);
        inv.setSelectedSlot(8);
        sp.inventoryMenu.broadcastChanges();
        step = 0;
        ticks = 0;
        mark.accept("hotbar", "seeded: " + String.join(" | ", icons));
    }

    /** One server tick; true once everything has been staged and seen. */
    static boolean tick(ServerPlayer sp, BiConsumer<String, String> mark) {
        ticks++;
        if (step < 3) {
            WatchPanel.Cell cell = cells[step];
            if (ticks == 40) {
                com.moddingfromamod.wish.ThingPage.open(sp, cell, 0);
                if (!(sp.containerMenu instanceof ChestMenu chest)) {
                    mark.accept("page-" + step, "NOPAGE " + sp.containerMenu.getClass().getSimpleName());
                    return false;
                }
                int tile = -1;
                for (int i = 0; i < chest.getRowCount() * 9; i++) {
                    if (chest.getSlot(i).getItem().getHoverName().getString().equals(WishHotbar.ADD_LABEL)) {
                        tile = i;
                    }
                }
                mark.accept("page-" + step, tile + " " + chest.getRowCount() + " " + cell.label());
            }
            if (ticks > 40) {
                int at = slotOf(sp, cell.recipeId());
                if (at >= 0) {
                    ItemStack s = sp.getInventory().getItem(at);
                    mark.accept("added-" + step, at + " clicked by the client; badged=" + WishHotbar.badged(s)
                            + " glint=" + s.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) + " " + s.getComponentsPatch());
                    step++;
                    ticks = 0;
                } else if (ticks > 40 + CLICK_TIMEOUT) {
                    WishHotbar.add(sp, cell);
                    mark.accept("added-" + step, slotOf(sp, cell.recipeId()) + " STAGED: the client's click never came");
                    step++;
                    ticks = 0;
                }
            }
            return false;
        }
        if (step == 3) {
            sp.closeContainer();
            sp.connection.teleport(sp.getX(), sp.getY(), sp.getZ(), 0f, -40f);
            sp.getInventory().setSelectedSlot(8);
            sp.inventoryMenu.broadcastChanges();
            List<String> at = new ArrayList<>();
            for (WatchPanel.Cell c : cells) {
                at.add(c.label() + "=" + slotOf(sp, c.recipeId()));
            }
            mark.accept("hud", String.join(" ", at));
            step = 4;
            ticks = 0;
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (!fired[i] && sp.entityTags().contains(TAG + i)) {
                fired[i] = true;
                mark.accept("fired-" + i, NAMES[i] + " made, " + ticks + " ticks after hud; cooldown now "
                        + sp.getCooldowns().getCooldownPercent(sp.getInventory().getItem(Math.max(slotOf(sp, RECIPES[i]), 0)), 0f));
            }
        }
        // After the use: the hotbar holds vanilla pictures by RULE (blocks, a fence) and EXCEPTIONS (a chest, a
        // grass block, the potion), each beside nothing else, for one more photograph.
        if (fired[0] && !extras) {
            extras = true;
            var inv = sp.getInventory();
            String[][] more = {{"minecraft:stone", "Stone"}, {"minecraft:bricks", "Bricks"}, {"minecraft:oak_fence", "Fence"},
                    {"minecraft:chest", "Chest"}, {"minecraft:grass_block", "Grass"}, {"minecraft:white_bed", "Bed"}};
            List<String> said = new ArrayList<>();
            for (int k = 0; k < more.length; k++) {
                WatchPanel.Cell c = new WatchPanel.Cell(0, WatchPanel.Family.CREATION, WatchPanel.Action.CARD,
                        more[k][1], more[k][1], "hb2755_" + more[k][1], more[k][1], more[k][0], List.of());
                ItemStack proxy = WishHotbar.proxy(c, sp.getUUID());
                inv.setItem(2 + k, proxy);
                said.add((k + 3) + "=" + more[k][0] + (WishHotbar.badged(proxy) ? " badged" : "")
                        + (proxy.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) ? " glint" : ""));
            }
            inv.setSelectedSlot(0);
            sp.inventoryMenu.broadcastChanges();
            mark.accept("extras", String.join(" | ", said));
        }
        return ticks > 20 * 240;
    }

    private static int slotOf(ServerPlayer sp, String recipe) {
        var inv = sp.getInventory();
        for (int i = 0; i < 36; i++) {
            if (recipe.equals(WishHotbar.recipeOf(inv.getItem(i)))) {
                return i;
            }
        }
        return -1;
    }
}
