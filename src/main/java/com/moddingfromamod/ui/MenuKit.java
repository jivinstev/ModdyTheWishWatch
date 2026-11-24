package com.moddingfromamod.ui;

import com.mojang.serialization.JsonOps;
import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * <b>Opens Moddy's two menu forms</b> — the ONE place a menu reaches a client (docs/BEDROCK_COMPAT.md
 * "Menu mapping"). A new menu is a {@link PickPage} or a {@link TuneDialog}, opened here, and so gets
 * the Bedrock mapping without anybody having to know it:
 *
 * <table>
 *   <tr><th>Moddy form</th><th>Java</th><th>Bedrock (Geyser)</th></tr>
 *   <tr><td>{@link #pick} — choose one of many, or toggle tiles</td><td>a chest</td>
 *       <td>Pocket UI: a one-tap grid (button chest); Classic UI: the chest</td></tr>
 *   <tr><td>{@link #tune} — a few numbers, one dropdown, a name</td><td>a dialog</td>
 *       <td>a form; its one action is Submit</td></tr>
 * </table>
 *
 * {@code MenuMappingTest} fails any code that opens a dialog or a clickable chest any other way.
 */
public final class MenuKit {

    private MenuKit() {
    }

    /** The custom-data key on a picks-row item; the Geyser extension's ModdyButtonChests.PICKS_ROW. */
    public static final String PICKS_ROW = "moddy_picks_row";
    /**
     * The custom-data key on a tile that cannot be added now: grey, its reason its first lore line. The Geyser
     * extension's ModdyButtonChests.BLOCKED: a Pocket grid tile shows its name only, so there the reason is put under it.
     */
    public static final String BLOCKED = "moddy_blocked";

    /**
     * Open {@code page} for {@code p} as a button chest. {@code onPress} gets the press's meaning — a
     * tile id or {@link PickPage#BACK_ID}/{@link PickPage#NEXT_ID} — on the server thread; Close is
     * handled here. {@code menu} names the menu in the action log (step 1): every press, Close included,
     * is a {@code menu_press} record, so how each menu is used is a count.
     */
    public static void pick(ServerPlayer p, String menu, PickPage page, BiConsumer<ServerPlayer, String> onPress) {
        pick(p, menu, page, onPress, null);
    }

    /**
     * {@link #pick}, asking a Bedrock phone for one layout for THIS page: {@code "list"} is Bedrock's own full-width
     * button list, where a long button reads whole (the watch page). Null: the player's layout, as every
     * other page. A player whose layout is {@code off} (Java's chest) keeps the chest.
     */
    public static void pick(ServerPlayer p, String menu, PickPage page, BiConsumer<ServerPlayer, String> onPress,
                            String layout) {
        SimpleContainer c = new SimpleContainer(page.size());
        int row = page.picksRowSlot();
        for (var e : page.drawn().entrySet()) {
            int i = e.getKey();
            PickPage.Tile t = e.getValue();
            ItemStack s = icon(t.item(), t.shown());
            if (row >= 0 && i >= row && i < row + PickPage.COLS) {
                // the picks row, inside the chest. The Bedrock extension reads this mark and draws the row as
                // the form's "Chosen: …" line instead of as buttons (a Geyser form has no inventory to show it in).
                net.minecraft.nbt.CompoundTag mark = new net.minecraft.nbt.CompoundTag();
                mark.putBoolean(PICKS_ROW, true);
                s.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(mark));
            } else if (i < page.tiles().size() && page.blocked(page.tiles().get(i)) != null) {
                net.minecraft.nbt.CompoundTag mark = new net.minecraft.nbt.CompoundTag();
                mark.putBoolean(BLOCKED, true);
                s.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(mark));
            }
            if (!t.lore().isEmpty()) {
                s.set(DataComponents.LORE, new ItemLore(t.lore().stream()
                        .map(l -> (Component) Component.literal(l).withStyle(st -> st.withItalic(false))).toList()));
            }
            if (t.selected()) {
                s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            c.setItem(i, s);
        }
        if (page.back()) {
            c.setItem(page.backSlot(), icon(PickPage.BACK_ICON, "◀ Back"));
        }
        if (page.next()) {
            c.setItem(page.nextSlot(), icon("minecraft:lime_dye", page.nextLabel()));
        }
        c.setItem(page.closeSlot(), icon("minecraft:barrier", "✖ Close"));
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new PickMenu(id, inv, c, menu, page, onPress),
                com.moddingfromamod.net.ButtonChest.title(page.title(), p, layout)));   // his layout, or this page's
    }

    /** Open {@code dialog} for {@code p}, decoded by vanilla's own codec (a malformed one throws here). */
    public static void tune(ServerPlayer p, TuneDialog dialog) {
        // a player on another client (Bedrock, through the private plug-in) may get the dialog's symbols swapped
        // for ones his client draws; a Java player is sent the dialog exactly as it is (PlayerClient).
        com.google.gson.JsonElement shown = com.moddingfromamod.net.PlayerClient.dialog(p, dialog.json());
        p.openDialog(Holder.direct(Dialog.DIRECT_CODEC.parse(
                RegistryOps.create(JsonOps.INSTANCE, p.registryAccess()), shown).getOrThrow()));
    }

    /**
     * Open the text-first wish box with its buttons: JAVA only. A Bedrock form cannot carry a text field and
     * several buttons (Geyser makes them a dropdown), so a Bedrock player gets {@code WatchHome}'s button page and this
     * refuses him.
     */
    public static void wishBox(ServerPlayer p, WishBoxDialog dialog) {
        if (com.moddingfromamod.net.PlayerClient.isBedrock(p)) {
            throw new IllegalStateException("the wish box with buttons is Java's; a Bedrock player gets the button page");
        }
        p.openDialog(Holder.direct(decode(RegistryOps.create(JsonOps.INSTANCE, p.registryAccess()), dialog)));
    }

    public static Dialog decode(RegistryOps<com.google.gson.JsonElement> ops, WishBoxDialog dialog) {
        return Dialog.DIRECT_CODEC.parse(ops, dialog.json()).getOrThrow();
    }

    /** The dialog as vanilla reads it — what a GameTest checks. */
    public static Dialog decode(ServerPlayer p, TuneDialog dialog) {
        return decode(RegistryOps.create(JsonOps.INSTANCE, p.registryAccess()), dialog);
    }

    public static Dialog decode(RegistryOps<com.google.gson.JsonElement> ops, TuneDialog dialog) {
        return Dialog.DIRECT_CODEC.parse(ops, dialog.json()).getOrThrow();
    }

    static ItemStack icon(String id, String label) {
        if (PickPage.WATCH_ICON.equals(id) || id != null && id.startsWith("wish:")) {
            // Moddy's own pictures: the watch, or a sprite such as the back arrow, on the carrier with its
            // item_model — the same stacks the watch panel draws, so the Geyser extension names the same picture.
            ItemStack s = PickPage.WATCH_ICON.equals(id) ? com.moddingfromamod.wish.VanillaWatchItem.stack()
                    : com.moddingfromamod.wish.WatchMenu.iconStack(id);
            s.remove(DataComponents.LORE);
            s.set(DataComponents.CUSTOM_NAME, Component.literal(label).withStyle(st -> st.withItalic(false)));
            com.moddingfromamod.compat.ItemComponents.hideLine(s, DataComponents.ATTRIBUTE_MODIFIERS);
            return s;
        }
        var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.PAPER);
        ItemStack s = new ItemStack(item == Items.AIR ? Items.PAPER : item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(label).withStyle(st -> st.withItalic(false)));
        // A tile is a picture, not gear: without this a sword tile says "7 Attack Damage" and a dragon-head tile
        // "When on Head: -100% Waypoint Transmit Range" under the pick's own words (seen on film, S5).
        com.moddingfromamod.compat.ItemComponents.hideLine(s, DataComponents.ATTRIBUTE_MODIFIERS);
        return s;
    }

    /** A pick chest: every click on one of its slots is a press, never a move (as {@code WatchMenu}). */
    public static final class PickMenu extends ChestMenu {
        private final PickPage page;
        private final BiConsumer<ServerPlayer, String> onPress;

        private final String menu;

        PickMenu(int id, Inventory inv, SimpleContainer c, String menu, PickPage page,
                 BiConsumer<ServerPlayer, String> onPress) {
            super(type(page.rows()), id, inv, c, page.rows());
            this.menu = menu;
            this.page = page;
            this.onPress = onPress;
        }

        static MenuType<ChestMenu> type(int rows) {
            return switch (rows) {
                case 1 -> MenuType.GENERIC_9x1;
                case 2 -> MenuType.GENERIC_9x2;
                case 3 -> MenuType.GENERIC_9x3;
                case 4 -> MenuType.GENERIC_9x4;
                case 5 -> MenuType.GENERIC_9x5;
                default -> MenuType.GENERIC_9x6;
            };
        }

        public PickPage page() {
            return page;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public void clicked(int slot, int button, ClickType type, Player player) {
            if (slot < 0 || slot >= page.size()) {
                if (type != ClickType.QUICK_MOVE) {
                    super.clicked(slot, button, type, player);
                }
                return;
            }
            if (!(player instanceof ServerPlayer sp)) {
                return;
            }
            sp.containerMenu.sendAllDataToRemote();
            if (type == ClickType.QUICK_CRAFT) {
                return;
            }
            String what = page.at(slot);
            if (what == null) {
                return;
            }
            com.moddingfromamod.agent.ActionLog.menuPress(sp.getUUID().toString(), menu, null, null, -1, slot, what,
                    false);
            if (PickPage.CLOSE_ID.equals(what)) {
                sp.closeContainer();
                return;
            }
            onPress.accept(sp, what);
        }
    }
}
