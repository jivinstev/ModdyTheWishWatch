package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.UseCooldown;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * <b>A wish on the hotbar: one click makes it again, no typing and no menu.</b>
 *
 * <p>A thing he can make again from its own recipe ({@link WatchPanel.Cell#canRemake}) gets a tile on its page,
 * "Add to hotbar". It puts a PROXY in a hotbar slot: an inert item (paper underneath) that only LOOKS like the
 * thing's picture and is named "✨ Wish: …", so a wish for a sword is never mistaken for the sword. Using it runs
 * exactly what the page's "Make another one" runs ({@link WishCommands#again}), so the price, the world's rules and
 * the safety layers are the ones every wish meets. Each proxy has its own vanilla item cooldown, which a stock
 * client draws as the sweep over the slot.
 *
 * <p>A proxy is his alone (another player using it is told so and nothing is made), it vanishes when dropped or
 * when he dies rather than lying in the world, and the same tile takes it off again.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WishHotbar {

    /**
     * Custom-data keys: the recipe a proxy makes, whose it is, and the icon string of the picture it wears. The icon
     * is there for the menus: a pick tile is drawn from an icon string, never from a stack, so without it the "Which
     * slot?" page could only draw a proxy as the paper it is (seen in a real client, docs/evidence-2755).
     */
    public static final String RECIPE = "mfam_hotbar";
    public static final String OWNER = "mfam_hotbar_owner";
    public static final String ICON = "mfam_hotbar_icon";

    /** The page's two tiles. */
    public static final String ADD_ID = "hotbar";
    public static final String REMOVE_ID = "unhotbar";
    public static final String ADD_LABEL = "🎯 Add to hotbar";
    public static final String REMOVE_LABEL = "🎯 Take off hotbar";

    /** Ticks a proxy rests after a use: long enough that one click is one wish. */
    public static final int REST_TICKS = 20;

    /** What a proxy copies from the thing's picture: how it LOOKS, never what it does. */
    private static final List<DataComponentType<?>> LOOKS = List.of(
            DataComponents.ITEM_MODEL, DataComponents.CUSTOM_MODEL_DATA, DataComponents.DYED_COLOR,
            DataComponents.PROFILE, DataComponents.POTION_CONTENTS, DataComponents.BANNER_PATTERNS,
            DataComponents.BASE_COLOR, DataComponents.TRIM, DataComponents.FIREWORK_EXPLOSION,
            DataComponents.MAP_COLOR);

    private WishHotbar() {
    }

    // ── the page's tile ───────────────────────────────────────────────────────────────────────────

    /** The tile a thing's page shows for {@code holder}, or null when this thing cannot go on a hotbar. */
    public static ThingPage.Verb verb(WatchPanel.Cell cell, ServerPlayer holder) {
        if (holder == null || !cell.canRemake()) {
            return null;
        }
        return has(holder, cell.recipeId())
                ? new ThingPage.Verb(REMOVE_ID, "minecraft:shears", REMOVE_LABEL, "It stays in My wishes", null, null)
                : new ThingPage.Verb(ADD_ID, "minecraft:item_frame", ADD_LABEL, "Then use it to make one, any time",
                        null, null);
    }

    /** A press on the page: true when it was one of the two hotbar tiles (and is handled). */
    public static boolean press(ServerPlayer sp, String id, WatchPanel.Cell cell) {
        if (ADD_ID.equals(id)) {
            add(sp, cell);
            return true;
        }
        if (REMOVE_ID.equals(id)) {
            sp.closeContainer();
            int n = remove(sp, cell.recipeId());
            sp.sendSystemMessage(Component.literal(n > 0 ? "🎯 Off your hotbar. It is still in My wishes."
                    : "🎯 It was not on your hotbar."));
            return true;
        }
        return false;
    }

    // ── the proxy ─────────────────────────────────────────────────────────────────────────────────

    /** The proxy for this thing, owned by {@code owner}. */
    public static ItemStack proxy(WatchPanel.Cell cell, UUID owner) {
        ItemStack s = new ItemStack(Items.PAPER);
        ItemStack picture = WatchMenu.iconStack(cell.icon());
        for (DataComponentType<?> t : LOOKS) {
            if (copy(picture, s, t)) {
                // A look is a picture, not a property: on paper a dye says "Dyed" and a potion's colour
                // "No Effects" under the wish's own words (seen in a real client, docs/evidence-2755).
                com.moddingfromamod.compat.ItemComponents.hideLine(s, t);
            }
        }
        s.set(DataComponents.CUSTOM_NAME, Component.literal(WishHotbarPlan.name(cell.label()))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withItalic(false)));
        s.set(DataComponents.LORE, new ItemLore(List.of(
                (Component) Component.literal("Use it and Moddy makes one.")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("A wish, not the thing itself.")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withItalic(false)))));
        // The glint is what tells a wish for an item from the item (until the watch overlay), but it washes a
        // dye-tinted picture out: the green dragon sprite drew pale lavender (docs/evidence-2755, 4-controls.png).
        if (!picture.has(DataComponents.DYED_COLOR)) {
            s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        s.set(DataComponents.MAX_STACK_SIZE, 1);
        s.set(DataComponents.USE_COOLDOWN, new UseCooldown(REST_TICKS / 20f, Optional.of(
                ResourceLocation.fromNamespaceAndPath(ModdingFromAModMod.MODID, WishHotbarPlan.group(cell.recipeId())))));
        CompoundTag tag = new CompoundTag();
        tag.putString(RECIPE, cell.recipeId());
        tag.putString(OWNER, owner.toString());
        tag.putString(ICON, cell.icon());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    private static <T> boolean copy(ItemStack from, ItemStack to, DataComponentType<T> type) {
        T v = from.get(type);
        if (v != null) {
            to.set(type, v);
            return true;
        }
        return false;
    }

    /** The recipe this stack makes, or null when it is not a proxy. */
    public static String recipeOf(ItemStack stack) {
        CompoundTag t = tagOf(stack);
        String r = t == null ? "" : t.getStringOr(RECIPE, "");
        return r.isEmpty() ? null : r;
    }

    /** The icon string of the picture this proxy wears, or null. */
    public static String iconOf(ItemStack stack) {
        CompoundTag t = tagOf(stack);
        String i = t == null ? "" : t.getStringOr(ICON, "");
        return i.isEmpty() ? null : i;
    }

    /** Whose proxy this is, or null. */
    public static String ownerOf(ItemStack stack) {
        CompoundTag t = tagOf(stack);
        return t == null ? null : t.getStringOr(OWNER, "");
    }

    private static CompoundTag tagOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.PAPER)) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null || !data.contains(RECIPE) ? null : data.copyTag();
    }

    /** Does he hold a proxy for this recipe anywhere in his inventory? */
    public static boolean has(ServerPlayer p, String recipeId) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (recipeId.equals(recipeOf(inv.getItem(i)))) {
                return true;
            }
        }
        return false;
    }

    /** Take every proxy for this recipe out of his inventory; how many went. */
    public static int remove(ServerPlayer p, String recipeId) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (recipeId.equals(recipeOf(inv.getItem(i)))) {
                inv.setItem(i, ItemStack.EMPTY);
                n++;
            }
        }
        return n;
    }

    // ── adding ────────────────────────────────────────────────────────────────────────────────────

    /** "Add to hotbar": the first empty slot, or ask which slot to give up. */
    public static void add(ServerPlayer sp, WatchPanel.Cell cell) {
        Inventory inv = sp.getInventory();
        List<Boolean> empty = new ArrayList<>();
        for (int i = 0; i < WishHotbarPlan.SLOTS; i++) {
            empty.add(inv.getItem(i).isEmpty());
        }
        WishHotbarPlan.Plan plan = WishHotbarPlan.add(empty, freeBagSlot(inv) >= 0);
        switch (plan.step()) {
            case PLACE -> {
                sp.closeContainer();
                place(sp, cell, plan.slot());
            }
            case ASK_WHICH -> MenuKit.pick(sp, "hotbar-slot", slotPage(inv),
                    (p, id) -> pickedSlot(p, id, cell));
            case NO_ROOM -> {
                sp.closeContainer();
                sp.sendSystemMessage(Component.literal("🎯 Your hotbar and your bag are full. Make some room first."));
            }
        }
    }

    /** The "which slot?" page: one tile per hotbar slot, showing what is there now. */
    static PickPage slotPage(Inventory inv) {
        List<PickPage.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < WishHotbarPlan.SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            tiles.add(new PickPage.Tile("slot" + i, tileItem(s),
                    "Slot " + (i + 1) + ": " + s.getHoverName().getString(),
                    List.of("It moves into your bag"), false));
        }
        return PickPage.of("🎯 Which slot?", tiles, true);
    }

    /**
     * What a tile can draw for this stack, as an icon string a tile accepts: Moddy's watch, a sprite on the carrier
     * as its {@code wish:} string (the Wish Bag is one), a proxy as the picture it wears, else the bare item.
     */
    static String tileItem(ItemStack s) {
        if (VanillaWatchItem.isWatch(s)) {
            return PickPage.WATCH_ICON;
        }
        String icon = iconOf(s);
        if (icon != null) {
            // a vanilla id or a sprite is already a tile's picture; a wished item's stack: key is drawn as its stack
            return icon.startsWith(com.moddingfromamod.marshal.WatchIcons.SPRITE_PREFIX)
                    || ResourceLocation.tryParse(icon) != null && !icon.startsWith(com.moddingfromamod.marshal.WatchIcons.STACK_PREFIX)
                    ? icon : tileItem(WatchMenu.iconStack(icon));
        }
        var cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd != null && BuiltInRegistries.ITEM.getKey(s.getItem()).toString()
                .equals(com.moddingfromamod.datapack.VanillaCarrier.ITEM_ID)) {
            int n = com.moddingfromamod.compat.ModelData.id(cmd);
            for (var sprite : com.moddingfromamod.item.IconSprites.bindings()) {
                if (sprite.customModelData() == n) {
                    var dye = s.get(DataComponents.DYED_COLOR);
                    return com.moddingfromamod.marshal.WatchIcons.sprite(sprite.name(), dye == null ? 0xFFFFFF : dye.rgb());
                }
            }
        }
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    static void pickedSlot(ServerPlayer sp, String id, WatchPanel.Cell cell) {
        sp.closeContainer();
        if (id == null || !id.startsWith("slot")) {
            return;   // Back or Close: nothing moves
        }
        int slot;
        try {
            slot = Integer.parseInt(id.substring(4));
        } catch (NumberFormatException e) {
            return;
        }
        Inventory inv = sp.getInventory();
        if (slot < 0 || slot >= WishHotbarPlan.SLOTS) {
            return;
        }
        ItemStack there = inv.getItem(slot);
        if (!there.isEmpty()) {
            int free = freeBagSlot(inv);
            if (free < 0) {
                sp.sendSystemMessage(Component.literal("🎯 Your bag is full now. Make some room first."));
                return;
            }
            inv.setItem(free, there);
            inv.setItem(slot, ItemStack.EMPTY);
        }
        place(sp, cell, slot);
    }

    private static void place(ServerPlayer sp, WatchPanel.Cell cell, int slot) {
        remove(sp, cell.recipeId());   // never two of one wish
        sp.getInventory().setItem(slot, proxy(cell, sp.getUUID()));
        sp.sendSystemMessage(Component.literal("🎯 On your hotbar, slot " + (slot + 1)
                + ". Hold it and use it to make one."));
    }

    /** An empty slot of the main inventory outside the hotbar, or -1. */
    private static int freeBagSlot(Inventory inv) {
        for (int i = WishHotbarPlan.SLOTS; i < 36 && i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    // ── using it ──────────────────────────────────────────────────────────────────────────────────

    /** What a use of a stack did. */
    public enum Used {
        /** Not a proxy: the click is someone else's to handle. */
        NOT_A_PROXY,
        /** A proxy still resting from its last use: nothing happens. */
        RESTING,
        /** Another player's proxy: he is told, nothing is made. */
        NOT_YOURS,
        /** The wish was handed to {@link WishCommands#again}; {@link Use#outcome} says what came of it. */
        WISHED
    }

    /** @param outcome the submit's answer for {@link Used#WISHED}, else null */
    public record Use(Used used, WishService.Outcome outcome) {
        /** Was the click spent on a proxy (so nothing else may use it)? */
        public boolean spent() {
            return used != Used.NOT_A_PROXY;
        }
    }

    /** Use the proxy: make the thing again, through the very path the page's "Make another one" takes. */
    public static Use use(ServerPlayer player, ItemStack stack) {
        String recipe = recipeOf(stack);
        if (recipe == null) {
            return new Use(Used.NOT_A_PROXY, null);
        }
        if (player.getCooldowns().isOnCooldown(stack)) {
            return new Use(Used.RESTING, null);
        }
        if (!player.getUUID().toString().equals(ownerOf(stack))) {
            player.sendSystemMessage(Component.literal("🎯 That wish belongs to someone else."));
            return new Use(Used.NOT_YOURS, null);
        }
        player.getCooldowns().addCooldown(stack, REST_TICKS);
        return new Use(Used.WISHED, WishCommands.again(player, recipe));
    }

    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && use(player, event.getItemStack()).spent()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        } else if (event.getLevel().isClientSide() && recipeOf(event.getItemStack()) != null) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** The same click aimed at a block: the wish is made, and the block is not used. */
    @SubscribeEvent
    public static void onUseOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && use(player, event.getItemStack()).spent()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** Dropped: it is gone, never an item lying in the world for someone else to pick up. */
    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        ItemEntity e = event.getEntity();
        if (recipeOf(e.getItem()) != null) {
            event.setCanceled(true);
            if (event.getPlayer() instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("🎯 Off your hotbar. It is still in My wishes."));
            }
        }
    }

    /** He died: his proxies do not drop. */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            event.getDrops().removeIf(d -> recipeOf(d.getItem()) != null);
        }
    }
}
