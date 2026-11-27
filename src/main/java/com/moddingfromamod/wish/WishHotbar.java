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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.UseCooldown;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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
 * <p>A proxy is his alone (another player using it is told so and nothing is made), it vanishes when dropped rather
 * than lying in the world, it is kept when he is knocked out and back in the same slot when he respawns,
 * and the same tile takes it off again.
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

    /**
     * Ticks a proxy rests after a use: long enough that one click is one wish. A world's charge can name a longer (or
     * shorter) rest per wish ({@link WishCharge#restLeft}), and the sweep then shows that.
     */
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
        // The Moddy badge says "a wish, not the thing". With it the glint goes: it only lightened the
        // picture (and washed a dyed sprite out to pale lavender, docs/evidence-2755, 4-controls.png). A picture
        // the badge cannot draw keeps the old marker.
        if (!badge(s) && !picture.has(DataComponents.DYED_COLOR)) {
            s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        s.set(DataComponents.MAX_STACK_SIZE, 1);
        s.set(DataComponents.USE_COOLDOWN, new UseCooldown(REST_TICKS / 20f, Optional.of(group(cell.recipeId()))));
        CompoundTag tag = new CompoundTag();
        tag.putString(RECIPE, cell.recipeId());
        tag.putString(OWNER, owner.toString());
        tag.putString(ICON, cell.icon());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    /**
     * <b>Put the Moddy badge on a proxy</b>, through the item pack every client already has (no reload,
     * {@link com.moddingfromamod.pack.HotbarBadge}): an icon of ours gets the badge flag beside the number,
     * overlay and accent it already carries; a vanilla picture is drawn by the hotbar definition, which reads its
     * id from the string. False, and the stack untouched, for a picture neither can draw.
     */
    static boolean badge(ItemStack s) {
        Identifier model = s.get(DataComponents.ITEM_MODEL);
        if (model == null) {
            return false;
        }
        CustomModelData cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
        if (com.moddingfromamod.pack.VanillaItemPack.NAMESPACE.equals(model.getNamespace())
                && model.getPath().startsWith(com.moddingfromamod.pack.NativeItemModels.DIR + "/")) {
            var sprite = com.moddingfromamod.item.IconSprites.byName(
                    model.getPath().substring(com.moddingfromamod.pack.NativeItemModels.DIR.length() + 1));
            if (sprite == null || !sprite.model().startsWith("wish/")) {
                return false;   // only a wish's own pictures are composed (pack.VanillaItemPack.nodes)
            }
            CustomModelData was = cmd == null ? new CustomModelData(List.of(), List.of(), List.of(), List.of()) : cmd;
            s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(was.floats(),
                    flagged(was.flags()), was.strings(), was.colors()));
            return true;
        }
        if (com.moddingfromamod.pack.HotbarBadge.drawsVanilla(model.toString())) {
            s.set(DataComponents.ITEM_MODEL, Identifier.parse(com.moddingfromamod.pack.HotbarBadge.ITEM_MODEL));
            s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(true),
                    List.of(model.toString()), List.of()));
            return true;
        }
        return false;
    }

    private static List<Boolean> flagged(List<Boolean> flags) {
        List<Boolean> out = new ArrayList<>(flags);
        while (out.size() <= com.moddingfromamod.pack.HotbarBadge.FLAG_INDEX) {
            out.add(false);
        }
        out.set(com.moddingfromamod.pack.HotbarBadge.FLAG_INDEX, true);
        return out;
    }

    /** Does this proxy wear the Moddy badge? */
    public static boolean badged(ItemStack s) {
        CustomModelData cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
        return cmd != null && Boolean.TRUE.equals(cmd.getBoolean(com.moddingfromamod.pack.HotbarBadge.FLAG_INDEX));
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

    /**
     * A kept make (#70, {@code WorldMakes.landing}), put on his hotbar as a wish: the first empty hotbar slot, else his
     * Wish Bag (never a slot he has to give up: nothing is asked of him). The same proxy "Add to hotbar" makes, so it
     * fires and is aimed like any hotbar wish. Returns the hotbar slot, {@code -1} for the Wish Bag, or {@code -2} when
     * neither had room (he is told). Server thread.
     */
    public static int addKept(ServerPlayer sp, String recipeId, String name, String icon) {
        WatchPanel.Cell cell = new WatchPanel.Cell(0, WatchPanel.Family.CREATION, WatchPanel.Action.WISH, name, "",
                recipeId, "", icon, List.of());
        remove(sp, recipeId);   // never two of one wish
        takeFromBag(sp, recipeId);   // the bag's copy too: the one handed over now is where he is told it is
        Inventory inv = sp.getInventory();
        for (int i = 0; i < WishHotbarPlan.SLOTS; i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, proxy(cell, sp.getUUID()));
                showRests(sp);
                return i;
            }
        }
        int in = com.moddingfromamod.bag.WishBagService.add(sp.getServer(), sp.getUUID(), proxy(cell, sp.getUUID()));
        if (in > 0) {
            return -1;
        }
        sp.sendSystemMessage(Component.literal("🎯 Your hotbar and your Wish Bag are full. Make some room first."));
        return -2;
    }

    /** "Add to hotbar": the first empty slot, or ask which slot to give up. */
    public static void add(ServerPlayer sp, WatchPanel.Cell cell) {
        Inventory inv = sp.getInventory();
        List<Boolean> empty = new ArrayList<>();
        for (int i = 0; i < WishHotbarPlan.SLOTS; i++) {
            empty.add(inv.getItem(i).isEmpty());
        }
        WishHotbarPlan.Plan plan = WishHotbarPlan.add(empty, freeBagSlot(inv) >= 0, sp.getServer() != null);
        if (plan.step() == WishHotbarPlan.Step.WISH_BAG) {
            // A full hotbar (#189): his Wish Bag, never a question he has to answer and never nowhere. Only a bag that
            // cannot take it falls back to asking which slot to give up. The same on every client: it is all here.
            sp.closeContainer();
            boolean already = inBag(sp, cell.recipeId());
            if (already || com.moddingfromamod.bag.WishBagService.add(sp.getServer(), sp.getUUID(),
                    proxy(cell, sp.getUUID())) > 0) {
                sp.sendSystemMessage(Component.literal(WishHotbarPlan.inWishBag(already)));
                return;
            }
            plan = WishHotbarPlan.add(empty, freeBagSlot(inv) >= 0, false);
        }
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
                    || Identifier.tryParse(icon) != null && !icon.startsWith(com.moddingfromamod.marshal.WatchIcons.STACK_PREFIX)
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
        takeFromBag(sp, cell.recipeId());
        sp.getInventory().setItem(slot, proxy(cell, sp.getUUID()));
        showRests(sp);   // a wish still resting in his world shows the time left, whether or not it was off a while
        sp.sendSystemMessage(Component.literal("🎯 On your hotbar, slot " + (slot + 1)
                + ". Hold it and use it to make one."));
    }

    /** Is a proxy for this recipe in his Wish Bag? */
    public static boolean inBag(ServerPlayer sp, String recipeId) {
        if (sp.getServer() == null || recipeId == null) {
            return false;
        }
        for (ItemStack e : com.moddingfromamod.bag.WishBagService.contents(sp.getServer(), sp.getUUID())) {
            if (recipeId.equals(recipeOf(e))) {
                return true;
            }
        }
        return false;
    }

    /** Take every proxy for this recipe out of his Wish Bag; how many went. */
    static int takeFromBag(ServerPlayer sp, String recipeId) {
        if (sp.getServer() == null || recipeId == null) {
            return 0;
        }
        int n = 0;
        for (ItemStack e : com.moddingfromamod.bag.WishBagService.contents(sp.getServer(), sp.getUUID())) {
            if (recipeId.equals(recipeOf(e))) {
                n += com.moddingfromamod.bag.WishBagService.take(sp.getServer(), sp.getUUID(), e.copy(), e.getCount());
            }
        }
        return n;
    }

    /**
     * <b>Every new wish is also a hotbar wish in his Wish Bag</b> (#pins): a thing he wished for that can be made
     * again ({@code recipeId}: a Builder make's "Make another one", a Magician's taught recipe) is put in his bag as
     * the same proxy "Add to hotbar" makes, so it is always there to fire again and he never has to find the menu to
     * learn that pinning exists. Nothing when he already holds one anywhere (hotbar, inventory or bag), when the bag
     * does not apply to him (a camera, a test's mock) or is full. He is told once, in one line. Server thread.
     *
     * @return true when one was put in his bag
     */
    public static boolean pinToBag(net.minecraft.server.MinecraftServer server, UUID who, String recipeId, String name,
                                   String icon) {
        ServerPlayer sp = server == null || who == null ? null : server.getPlayerList().getPlayer(who);
        if (sp == null || recipeId == null || recipeId.isBlank() || !WatchPinService.bagApplies(sp)
                || has(sp, recipeId) || inBag(sp, recipeId)) {
            return false;
        }
        WatchPanel.Cell cell = new WatchPanel.Cell(0, WatchPanel.Family.CREATION, WatchPanel.Action.WISH, name, "",
                recipeId, "", icon == null ? "" : icon, List.of());
        if (com.moddingfromamod.bag.WishBagService.add(server, who, proxy(cell, who)) <= 0) {
            return false;
        }
        // its name is a make's or a taught recipe's label, as the watch shows it; the line is swept as any he reads,
        // and one that does not pass is said without the name
        String line = WishHotbarPlan.pinnedInBag(name);
        sp.sendSystemMessage(Component.literal(WishFeedback.sweeps(line, "") ? line : WishHotbarPlan.pinnedInBag("")));
        return true;
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
        player.getCooldowns().addCooldown(stack, REST_TICKS);   // at once: a second click inside the fire is no wish
        WishService.Outcome outcome = WishCommands.again(player, recipe);
        // The world's rest, asked AFTER the fire so it answers for what just happened: the rest a fire it allowed
        // started, the time left on one it refused because the wish is still resting (never a fresh rest), none for
        // one it refused for another reason. A charge that does not say keeps the one second above.
        int ticks = WishHotbarPlan.shownRest(true, restLeft(player, recipe), REST_TICKS);
        if (ticks != REST_TICKS) {
            setRest(player, recipe, ticks);
        }
        return new Use(Used.WISHED, outcome);
    }

    // ── resting ───────────────────────────────────────────────────────────────────────────────────

    /** The world's charge's {@link WishCharge#restLeft} for this wish, or -1 when it does not say (or throws). */
    static int restLeft(ServerPlayer player, String recipe) {
        try {
            return WishCharge.get().restLeft(WishAsk.replay(player.getUUID(), recipe));
        } catch (RuntimeException e) {
            return -1;   // a world's fault never stops his hotbar: the core's own rest stands
        }
    }

    /** The cooldown group of this wish's proxy (one per recipe, shared with nothing else). */
    static Identifier group(String recipe) {
        return Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, WishHotbarPlan.group(recipe));
    }

    /** Set this wish's sweep to {@code ticks} from now ({@code 0} clears it); the server sends it to his client. */
    private static void setRest(ServerPlayer player, String recipe, int ticks) {
        if (ticks > 0) {
            player.getCooldowns().addCooldown(group(recipe), ticks);
        } else if (ticks == 0) {
            player.getCooldowns().removeCooldown(group(recipe));
        }
    }

    /**
     * A proxy was put into his inventory (or his client was made anew): put each one's sweep back at the rest the
     * world's charge says is left, so taking it off and adding it again neither dodges a rest nor loses it. A charge
     * that does not say leaves the cooldown as the game kept it. How many sweeps were set.
     */
    static int showRests(ServerPlayer player) {
        Inventory inv = player.getInventory();
        java.util.Set<String> done = new java.util.HashSet<>();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            String recipe = recipeOf(inv.getItem(i));
            if (recipe == null || !done.add(recipe)) {
                continue;
            }
            int ticks = WishHotbarPlan.shownRest(false, restLeft(player, recipe), REST_TICKS);
            if (ticks > 0) {
                setRest(player, recipe, ticks);
                n++;
            }
        }
        return n;
    }

    /** Logged in: a new body, and the game keeps no cooldown across a logout. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            showRests(sp);
        }
    }

    /**
     * Respawned (after a knock-out, or out of the End): a new body with no cooldowns, and a client player made anew,
     * so this is after the respawn reached his client (the Clone, where his wishes come back, is before it).
     */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            showRests(sp);
        }
    }

    /** Another dimension: the server keeps his cooldowns, but his client's player is made anew without them. */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            showRests(sp);
        }
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

    // ── knocked out ───────────────────────────────────────────────────────────────────────

    /**
     * His wishes kept through a death, under the tag NeoForge copies onto the new body: a list of
     * {@code {slot, stack}}. Written at the death, read and cleared at the respawn's Clone, so it survives him logging
     * out (or the server restarting) before he presses Respawn, exactly as {@code player/SurviveDeath}'s effects do.
     */
    static final String KEPT = "mfam_hotbar_kept";

    /**
     * He was knocked out: take his wishes off before anything drops or is kept, and write down where each was. So
     * they never lie in the world, and with {@code keepInventory} on, the kept inventory holds none for the respawn to
     * double. LOWEST and not for a cancelled death (a death prevented is not a death).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            keep(sp);
        }
    }

    /** The respawn's new body: his wishes back in their slots. */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.isWasDeath() && event.getEntity() instanceof ServerPlayer sp) {
            giveBack(sp);
        }
    }

    /** Take every proxy off and write it down with its slot; how many. */
    static int keep(ServerPlayer dying) {
        Inventory inv = dying.getInventory();
        var ops = RegistryOps.create(NbtOps.INSTANCE, dying.registryAccess());
        CompoundTag persisted = dying.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        ListTag kept = persisted.getListOrEmpty(KEPT).copy();   // a death before an earlier one was given back
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (recipeOf(s) == null) {
                continue;
            }
            var encoded = ItemStack.CODEC.encodeStart(ops, s).result();
            if (encoded.isPresent()) {
                CompoundTag one = new CompoundTag();
                one.putInt("slot", i);
                one.put("stack", encoded.get());
                kept.add(one);
            }
            inv.setItem(i, ItemStack.EMPTY);   // unwritable or not, it never drops: My wishes still has it
        }
        if (kept.isEmpty()) {
            persisted.remove(KEPT);
        } else {
            persisted.put(KEPT, kept);
        }
        dying.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        return kept.size();
    }

    /** Has a death been written down and not yet given back? */
    static boolean keeping(Player p) {
        return p.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG).contains(KEPT);
    }

    /** Give back what {@link #keep} wrote down, once: its own slot, else the first free one; how many went back. */
    static int giveBack(ServerPlayer reborn) {
        CompoundTag persisted = reborn.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        if (!persisted.contains(KEPT)) {
            return 0;
        }
        ListTag kept = persisted.getListOrEmpty(KEPT);
        persisted.remove(KEPT);
        reborn.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        var ops = RegistryOps.create(NbtOps.INSTANCE, reborn.registryAccess());
        Inventory inv = reborn.getInventory();
        List<ItemStack> stacks = new ArrayList<>();
        List<Integer> wanted = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            CompoundTag one = kept.getCompoundOrEmpty(i);
            ItemStack s = ItemStack.CODEC.parse(ops, one.getCompoundOrEmpty("stack")).result().orElse(ItemStack.EMPTY);
            String recipe = recipeOf(s);
            if (recipe == null || has(reborn, recipe)
                    || stacks.stream().anyMatch(o -> recipe.equals(recipeOf(o)))) {
                continue;   // never two of one wish
            }
            stacks.add(s);
            wanted.add(one.getIntOr("slot", -1));
        }
        List<Boolean> empty = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            empty.add(inv.getItem(i).isEmpty());
        }
        List<Integer> to = WishHotbarPlan.restore(empty, wanted);
        int given = 0;
        for (int i = 0; i < stacks.size(); i++) {
            int slot = to.get(i);
            String name = stacks.get(i).getHoverName().getString();
            if (slot < 0) {
                reborn.sendSystemMessage(Component.literal("🎯 No room for " + name
                        + " on your hotbar. It is still in My wishes."));
                continue;
            }
            inv.setItem(slot, stacks.get(i));
            given++;
            if (slot != wanted.get(i)) {
                reborn.sendSystemMessage(Component.literal("🎯 " + name + " is in slot " + (slot + 1)
                        + " now: its own slot was taken."));
            }
        }
        return given;
    }

    /** A safety net: should a proxy ever reach his death drops, it is gone rather than lying in the world. */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            event.getDrops().removeIf(d -> recipeOf(d.getItem()) != null);
        }
    }
}
