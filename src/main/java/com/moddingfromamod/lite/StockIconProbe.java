package com.moddingfromamod.lite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.item.WishIcons;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.wish.VanillaWatchItem;
import com.moddingfromamod.wish.WatchMenu;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Gate C: put the icons in front of a STOCK client's eyes and hold them there.</b>
 *
 * <p>The unit gate proves the pack's bytes carry every override; {@code ItemPackGameTest} proves a
 * client with no mod is really told where those bytes are. Neither can answer the only question a
 * child asks, which is whether the thing in his hand looks like a wand or like a horse armour —
 * and <b>this whole class of defect is invisible to anything without eyes</b>, which is precisely
 * how it survived a green board for the life of the vanilla-lite path. It was found by looking at
 * a frame.
 *
 * <p>So this stages the two places Moddy appears, in order, and holds each long enough for a
 * screenshot to be unambiguous:
 *
 * <ol>
 *   <li><b>the hotbar</b> — Moddy in the first slot with eight distinct wish icons beside him, so
 *       one frame answers both "does Moddy render" and "do the wished things render", and a pack
 *       that arrived but is numbered wrong shows up as eight identical pictures rather than as
 *       nothing;</li>
 *   <li><b>the wish panel</b> — {@code WatchMenu}, whose COMPOSE cell renders
 *       {@code VanillaWatchItem.stack()} as a slot. That cell is the button for "wish for
 *       something new" inside the menu that exists to be usable without the mod, so it is the one
 *       that matters most and the one no server-side assertion can see.</li>
 * </ol>
 *
 * <p><b>It writes a marker per stage rather than running to a clock.</b> A harness that shoots on a
 * fixed schedule and a server that acts on another is two clocks that agree until the day a resource
 * reload runs long — and then the contact sheet is of an empty hotbar with every check green.
 * {@code tools/stock-icons.sh} waits for each marker and shoots after it.
 *
 * <p>Registered ONLY when a signal directory names it, the same gating as {@code SightProbe} and
 * {@code HeadlessWishProbe} and for the same reason: it rewrites a player's hotbar and opens a
 * screen on him, which is not something to leave one unset system property away from a child's game.
 */
public final class StockIconProbe {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-icon-probe");

    /** {@code -Dmoddingfromamod.iconprobe=/tmp/icons} — the directory the markers go in. */
    public static final String PROPERTY = ModdingFromAModMod.MODID + ".iconprobe";

    /**
     * How long each stage is held, in ticks — 45 s at 20 tps.
     *
     * <p>Generous on purpose. A stock client applying the item pack pays a resource reload measured
     * at ~6.7 s during which it renders nothing at all, and it may still be answering the
     * pack dialogue when the stage begins. A stage shorter than that photographs the reload.
     */
    private static final int STAGE_TICKS = 900;

    /** Ticks to wait after the join before touching anything — the reload window, doubled. */
    private static final int SETTLE_TICKS = 400;

    private static ServerPlayer subject;
    private static int ticks;
    private static int stage;

    private StockIconProbe() {
    }

    public static boolean active() {
        return !dir().isEmpty();
    }

    private static String dir() {
        return System.getProperty(PROPERTY, "").trim();
    }

    private static void mark(String name, String body) {
        try {
            Path d = Path.of(dir());
            Files.createDirectories(d);
            Files.writeString(d.resolve(name), body + "\n");
            LOG.info("STAGE {}: {}", name, body);
        } catch (Exception e) {
            LOG.error("could not write the {} marker, so the harness will wait for ever", name, e);
        }
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !PlayerKind.isChild(player.getUUID())) {
            return;
        }
        if (subject != null) {
            return;
        }
        subject = player;
        ticks = 0;
        stage = 0;
        LOG.info("staging the icon shoot for '{}' (carrier route {})",
                player.getGameProfile().name(), VanillaCarrier.enabled() ? "on" : "OFF");
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerPlayer player = subject;
        if (player == null || player.hasDisconnected()) {
            return;
        }
        ticks++;
        switch (stage) {
            case 0 -> {
                if (ticks >= SETTLE_TICKS) {
                    fillHotbar(player);
                    stage = 1;
                    ticks = 0;
                }
            }
            case 1 -> {
                // the wish hotbar arm stages its own pages and waits for the client's clicks.
                if (StockHotbarProbe.on()) {
                    if (StockHotbarProbe.tick(player, StockIconProbe::mark)) {
                        mark("done", "the hotbar arm held");
                        stage = 3;
                    }
                    return;
                }
                // The overlay shoot PAGES the hotbar (every overlay on a vanilla base,
                // nine at a time) and never opens the panel, so no page is ever covered.
                if (Boolean.getBoolean(LIBRARY_PROPERTY)) {
                    // Held as long as a full stage: the harness opens the inventory AFTER the
                    // hotbar shots, so a short page would be gone before it is photographed.
                    if (ticks >= STAGE_TICKS) {
                        ticks = 0;
                        if (!libraryPage(player, ++page)) {
                            mark("done", "every library page held");
                            stage = 3;
                        }
                    }
                    return;
                }
                if (Boolean.getBoolean(PINNED_PROPERTY)) {
                    if (ticks >= PAGE_TICKS) {
                        mark("done", "the pinned page held");
                        stage = 3;
                    }
                    return;
                }
                if (Boolean.getBoolean(OVERLAYS_PROPERTY)) {
                    if (ticks >= PAGE_TICKS) {
                        ticks = 0;
                        if (!overlayPage(player, ++page)) {
                            mark("done", "every overlay page held");
                            stage = 3;
                        }
                    }
                    return;
                }
                if (ticks >= STAGE_TICKS) {
                    if (Boolean.getBoolean(CREATIONS_PROPERTY)) {
                        seedCreations(player);
                    }
                    boolean opened = WatchMenu.open(player);
                    mark("panel", opened
                            ? "the wish panel is open -- the COMPOSE cell is Moddy"
                            : "REFUSED -- WatchMenu.open said no, so there is nothing to photograph");
                    stage = 2;
                    ticks = 0;
                }
            }
            case 2 -> {
                if (ticks >= STAGE_TICKS) {
                    mark("done", "both stages held");
                    stage = 3;
                }
            }
            default -> {
                // held; nothing further to stage
            }
        }
    }

    /**
     * Moddy in slot 0, eight distinct wish icons beside him.
     *
     * <p>Minted with exactly the components a real wished thing carries — the carrier item,
     * {@code custom_model_data} and {@code dyed_color} — because those three are the whole of what
     * the pack has to turn into a picture. Anything richer would be testing the give path rather
     * than the artwork.
     */
    private static void fillHotbar(ServerPlayer player) {
        if (StockHotbarProbe.on()) {
            StockHotbarProbe.setup(player, StockIconProbe::mark);
            return;
        }
        if (Boolean.getBoolean(PAIRS_PROPERTY)) {
            fillPairs(player);
            return;
        }
        if (Boolean.getBoolean(WORN_PROPERTY)) {
            dress(player);
            return;
        }
        if (Boolean.getBoolean(LIBRARY_PROPERTY)) {
            page = 0;
            libraryPage(player, 0);
            mark("hotbar", "library page 0");
            return;
        }
        if (Boolean.getBoolean(OVERLAYS_PROPERTY)) {
            fillOverlays(player);
            return;
        }
        if (Boolean.getBoolean(PINNED_PROPERTY)) {
            fillPinned(player);
            return;
        }
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(VanillaCarrier.itemId()));
        List<String> shown = new ArrayList<>();
        player.getInventory().setItem(0, VanillaWatchItem.stack());
        shown.add("0=Moddy@" + VanillaCarrier.WATCH_MODEL_DATA);
        List<WishIcons.Icon> icons = WishIcons.all();
        for (int slot = 1; slot <= 8 && slot - 1 < icons.size(); slot++) {
            WishIcons.Icon icon = icons.get(slot - 1);
            ItemStack s = new ItemStack(item);
            s.set(DataComponents.CUSTOM_MODEL_DATA, com.moddingfromamod.compat.ModelData.of(icon.id()));
            // A real colour rather than white: layer 0 is the tinted one, so an untinted icon and
            // a missing override look far more alike than they should.
            com.moddingfromamod.compat.ItemComponents.dyeHidden(s, TINTS[slot - 1]);
            s.set(DataComponents.CUSTOM_NAME, Component.literal(icon.name()));
            player.getInventory().setItem(slot, s);
            shown.add(slot + "=" + icon.name() + "@" + icon.id());
        }
        player.getInventory().setSelectedSlot(0);
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
        mark("hotbar", String.join(" ", shown));
    }

    /** {@code -Dmoddingfromamod.iconprobelibrary=true}: an earlier issue's photograph — the drawn library. */
    public static final String LIBRARY_PROPERTY = ModdingFromAModMod.MODID + ".iconprobelibrary";

    /** Second colours used on the pages: every other two-colour icon gets one, the rest derive it. */
    private static final int[] ACCENTS = {0xFFE14D, 0x2E7D32, 0xFFFFFF, 0x1565C0, 0xFF8FB1, 0x111111};

    /**
     * <b>: page {@code n} of the drawn library, 36 icons across the hotbar and the main
     * inventory</b>, each minted through the PRODUCTION give line ({@code MakeItemTool.itemArg})
     * in a real colour, so the shot is of what a wish hands over. A two-colour icon gets an
     * explicit accent on even positions and the DERIVED one on odd, so both paths are on film. A
     * fixed icon ({@code *}) is given a loud colour on purpose: it must come out in its own.
     * Returns false when the library has run out.
     */
    private static boolean libraryPage(ServerPlayer player, int n) {
        List<com.moddingfromamod.item.IconSprites.LibraryIcon> icons = com.moddingfromamod.item.IconSprites.drawn();
        int from = n * 36;
        if (from >= icons.size()) {
            return false;
        }
        var server = ((net.minecraft.server.level.ServerLevel) player.level()).getServer();
        List<String> shown = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            int i = from + slot;
            if (i >= icons.size()) {
                player.getInventory().setItem(slot, ItemStack.EMPTY);
                continue;
            }
            var icon = icons.get(i);
            Integer accent = icon.accent() && i % 2 == 0 ? ACCENTS[(i / 2) % ACCENTS.length] : null;
            String arg = com.moddingfromamod.agent.tools.MakeItemTool.itemArg(
                    new com.moddingfromamod.agent.tools.MakeItemTool.Variant(icon.name(),
                            TINTS[i % TINTS.length], null, accent),
                    icon.number(), "A hint.", null, null,
                    com.moddingfromamod.item.WishItem.WISH_FEATURE + ":\"probe_" + icon.name() + "\"",
                    null, null);
            ItemStack s;
            try {
                var parsed = new net.minecraft.commands.arguments.item.ItemParser(server.registryAccess())
                        .parse(new com.mojang.brigadier.StringReader(arg));
                s = new ItemStack(parsed.item(), 1, parsed.components());
            } catch (Exception e) {
                LOG.error("the library give line did not parse: {}", arg, e);
                s = ItemStack.EMPTY;
            }
            player.getInventory().setItem(slot, s);
            shown.add(slot + "=" + icon.name() + (icon.tints() ? "" : "*") + (accent != null ? "+a" : ""));
        }
        player.getInventory().setSelectedSlot(0);
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
        mark("lib-" + n, String.join(" ", shown));
        return true;
    }

    /** {@code -Dmoddingfromamod.iconprobeoverlays=true}: the photograph — icons wearing what they do. */
    public static final String OVERLAYS_PROPERTY = ModdingFromAModMod.MODID + ".iconprobeoverlays";

    /**
     * <b>Every overlay in the library, on a real wished item, on a stock client.</b>
     *
     * <p>Every stack goes through the PRODUCTION give line ({@code MakeItemTool.itemArg} with its
     * overlay) and the game's own item parser, so the photograph is of what a wish hands over, not
     * of a stack built to look right. Hotbar: slot 0 the CONTROL — a plain purple wand, no overlay
     * — then eight real pairings an effect produces (lightning wand, fire sword, ice orb…). The
     * main inventory holds all nineteen overlays on one wand, in the library's order, so the
     * inventory shot is the whole library at once. A slot identical to the control means the
     * overlay never arrived; a magenta one means the composite named a model the pack lacks.
     */
    private static void fillOverlays(ServerPlayer player) {
        var server = ((net.minecraft.server.level.ServerLevel) player.level()).getServer();
        String[][] hotbar = {
                {"wand", null, "Plain Wand (control)"}, {"wand", "lightning", "Lightning Wand"},
                {"sword", "ignite", "Fire Sword"}, {"orb", "freeze", "Ice Orb"},
                {"food_round", "heal", "Healing Pie"}, {"coin", "rich", "Lucky Coin"},
                {"shield", "protect", "Guard Shield"}, {"bomb", "explode", "Boom Bomb"},
                {"key", "teleport", "Warp Key"}};
        List<String> shown = new ArrayList<>();
        for (int slot = 0; slot < hotbar.length; slot++) {
            String[] h = hotbar[slot];
            String overlay = h[1] == null ? null
                    : com.moddingfromamod.item.IconOverlays.forEffects(List.of(h[1]));
            player.getInventory().setItem(slot, overlaid(server, h[0], overlay, h[2], TINTS[slot % TINTS.length]));
            shown.add(slot + "=" + h[2] + "/" + overlay);
        }
        List<String> names = com.moddingfromamod.item.IconOverlays.NAMES;
        for (int i = 0; i < names.size(); i++) {
            player.getInventory().setItem(9 + i,
                    overlaid(server, "wand", names.get(i), "wand + " + names.get(i), 0x9966FF));
        }
        shown.add("9..=" + String.join(",", names));
        player.getInventory().setSelectedSlot(0);
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
        page = 0;
        mark("hotbar", String.join(" ", shown));
        mark("page-0", String.join(" ", shown));
    }

    /** {@code -Dmoddingfromamod.iconprobepinned=true}: an earlier issue's photograph — Moddy and the bag, animating. */
    public static final String PINNED_PROPERTY = ModdingFromAModMod.MODID + ".iconprobepinned";

    /**
     * <b>: the two things every child always holds, and nothing else</b> — Moddy in slot 1, the
     * Wish Bag in slot 2, the rest of the hotbar empty and the selection parked on slot 9, so the two
     * cells hold only their own artwork. Both are frame strips with a {@code .png.mcmeta}; two shots of
     * this one page that differ inside those two cells are the proof each animates on that client.
     * Minted by the production stacks ({@code VanillaWatchItem.stack}, {@code WishBagItem.stack}).
     */
    private static void fillPinned(ServerPlayer player) {
        player.getInventory().setItem(com.moddingfromamod.wish.WatchPin.HOME, VanillaWatchItem.stack());
        player.getInventory().setItem(com.moddingfromamod.wish.WatchPin.BAG_HOME,
                com.moddingfromamod.bag.WishBagItem.stack());
        for (int slot = 2; slot < 9; slot++) {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        player.getInventory().setSelectedSlot(8);
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
        page = 0;
        String shown = "0=Moddy@" + VanillaCarrier.WATCH_MODEL_DATA + " 1=WishBag@" + VanillaCarrier.WISH_BAG_MODEL_DATA;
        mark("hotbar", shown);
        mark("page-0", shown);
    }

    /** Ticks each overlay page is held — long enough for two photographs a harness takes. */
    private static final int PAGE_TICKS = 400;

    private static int page;

    /** Icons with a VANILLA base, one per overlay, so every page shows the look a wish now has. */
    private static final String[] PAGE_ICONS = {"sword", "wand", "orb", "food_round", "key", "axe",
            "coin", "bomb", "helmet", "bottle", "rocket", "bucket", "bow", "hammer", "lamp",
            "snowball", "spear", "vehicle", "bag"};

    /**
     * <b>Page {@code n} ≥ 1: nine overlays of the library, each on its own vanilla-based icon</b>
     * — slot 8 of the last page is a plain wish with the backing and nothing else. Returns false
     * when there are no pages left.
     */
    private static boolean overlayPage(ServerPlayer player, int n) {
        List<String> names = com.moddingfromamod.item.IconOverlays.NAMES;
        int from = (n - 1) * 9;
        if (from > names.size()) {
            return false;
        }
        var server = ((net.minecraft.server.level.ServerLevel) player.level()).getServer();
        List<String> shown = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            int i = from + slot;
            ItemStack s;
            if (i < names.size()) {
                s = overlaid(server, PAGE_ICONS[i % PAGE_ICONS.length], names.get(i),
                        PAGE_ICONS[i % PAGE_ICONS.length] + " + " + names.get(i), TINTS[i % TINTS.length]);
                shown.add(slot + "=" + names.get(i));
            } else if (i == names.size()) {
                s = overlaid(server, "sword", null, "sword, backing only", 0x9966FF);
                shown.add(slot + "=backing-only");
            } else {
                s = ItemStack.EMPTY;
            }
            player.getInventory().setItem(slot, s);
        }
        player.getInventory().setSelectedSlot(8);
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
        mark("page-" + n, String.join(" ", shown));
        return true;
    }

    private static ItemStack overlaid(net.minecraft.server.MinecraftServer server, String icon,
                                      String overlay, String name, int rgb) {
        String arg = com.moddingfromamod.agent.tools.MakeItemTool.itemArg(
                new com.moddingfromamod.agent.tools.MakeItemTool.Variant(name, rgb),
                com.moddingfromamod.item.IconSprites.numberFor(icon), "A hint.", null, null,
                com.moddingfromamod.item.WishItem.WISH_FEATURE + ":\"probe_" + icon + "\"", null, overlay);
        try {
            var parsed = new net.minecraft.commands.arguments.item.ItemParser(server.registryAccess())
                    .parse(new com.mojang.brigadier.StringReader(arg));
            return new ItemStack(parsed.item(), 1, parsed.components());
        } catch (Exception e) {
            LOG.error("the overlaid give line did not parse: {}", arg, e);
            return ItemStack.EMPTY;
        }
    }

    /** {@code -Dmoddingfromamod.iconprobepairs=true}: the item-model hotbar, old and new side by side. */
    public static final String PAIRS_PROPERTY = ModdingFromAModMod.MODID + ".iconprobepairs";

    /**
     * <b>The photograph: each population NEW (names its own {@code item_model}) beside OLD (the
     * number only, exactly what a 1.21.1 jar minted and the data fixer upgraded).</b>
     *
     * <p>Slots 0/1 Moddy, 2/3 a cup, 4/5 a wand, 6/7 a rig atom, and slot 8 the CONTROL — a carrier
     * with no number, which is a leather horse armour on every client. Each pair must be two
     * identical pictures and neither may be the control: a pair that differs means the name and
     * the number draw different things; a pair that matches slot 8 means neither path arrived.
     */
    private static void fillPairs(ServerPlayer player) {
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(VanillaCarrier.itemId()));
        List<String> shown = new ArrayList<>();
        ItemStack moddy = VanillaWatchItem.stack();
        player.getInventory().setItem(0, moddy);
        player.getInventory().setItem(1, com.moddingfromamod.compat.ModelData.unnamed(moddy));
        shown.add("0=Moddy/new 1=Moddy/old");
        int[] numbers = {
                com.moddingfromamod.item.IconSprites.numberFor("cup"),
                com.moddingfromamod.item.IconSprites.numberFor("wand"),
                VanillaCarrier.ATOM_MODEL_DATA };
        String[] names = {"cup", "wand", "rig_atom"};
        for (int i = 0; i < numbers.length; i++) {
            ItemStack s = new ItemStack(item);
            com.moddingfromamod.compat.ModelData.stamp(s, numbers[i]);
            com.moddingfromamod.compat.ItemComponents.dyeHidden(s, TINTS[i * 2]);
            s.set(DataComponents.CUSTOM_NAME, Component.literal(names[i] + " (new)"));
            ItemStack old = com.moddingfromamod.compat.ModelData.unnamed(s);
            old.set(DataComponents.CUSTOM_NAME, Component.literal(names[i] + " (old)"));
            player.getInventory().setItem(2 + i * 2, s);
            player.getInventory().setItem(3 + i * 2, old);
            shown.add((2 + i * 2) + "=" + names[i] + "/new@" + numbers[i]
                    + " (" + com.moddingfromamod.compat.ModelData.itemModelOf(s) + ") "
                    + (3 + i * 2) + "=" + names[i] + "/old");
        }
        ItemStack control = new ItemStack(item);
        control.set(DataComponents.CUSTOM_NAME, Component.literal("control: no number"));
        player.getInventory().setItem(8, control);
        shown.add("8=control");
        player.getInventory().setSelectedSlot(8);
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
        mark("hotbar", String.join(" ", shown));
    }

    /** {@code -Dmoddingfromamod.iconprobecreations=true}: the photograph — the panel's own row. */
    public static final String CREATIONS_PROPERTY = ModdingFromAModMod.MODID + ".iconprobecreations";

    /**
     * <b>Stage a reported screenshot — a panel with things he has MADE on it.</b>
     *
     * <p>Six creatures in six colours, a stone-brick castle, an oak treehouse and a wished wand,
     * put into the registries IN MEMORY ONLY ({@code replaceAll} / {@code putInMemoryForProbe}) —
     * a probe never writes a child's shelf (docs/WISH_STORES.md §9). Everything downstream is the
     * shipped path: {@code WatchSnapshot} → {@code WatchPanel} → {@code WatchMenu} → a stock client.
     * Before creation icons this row was six amethyst shards, two identical brick blocks and a diamond.
     */
    private static void seedCreations(ServerPlayer player) {
        String[][] creatures = {
                {"probe_spikor", "Spikor", "#2E8B57"}, {"probe_bloop", "Bloop of Lord", "#7A4BC2"},
                {"probe_chucko", "Chucko", "#E23B3B"}, {"probe_sunny", "Sunny", "#F2C94C"},
                {"probe_frosty", "Frosty", "#3BC2E2"}, {"probe_shadow", "Shadow Cat", "#222233"}};
        // KEEP what is already there — the starter library's SCULPTED dragon among it. A panel of
        // only hand-tinted probe creatures hid that a sculpted species' own tint is white.
        java.util.List<com.moddingfromamod.species.Species> species =
                new ArrayList<>(com.moddingfromamod.species.SpeciesRegistry.all());
        for (String[] c : creatures) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("id", c[0]);
            o.addProperty("name", c[1]);
            o.addProperty("tint", c[2]);
            species.add(com.moddingfromamod.species.Species.fromJson(o));
        }
        com.moddingfromamod.species.SpeciesRegistry.replaceAll(species);
        com.moddingfromamod.builder.BuildRegistry.putInMemoryForProbe(
                new com.moddingfromamod.builder.BuildPlan("probe_castle", "Stone Castle", List.of(
                        new com.moddingfromamod.builder.BuildShape(
                                com.moddingfromamod.builder.BuildShape.Kind.BOX, 0, 0, 0, 12, 8, 12,
                                "minecraft:stone_bricks", true),
                        new com.moddingfromamod.builder.BuildShape(
                                com.moddingfromamod.builder.BuildShape.Kind.BOX, 4, 0, 4, 3, 3, 1,
                                "minecraft:oak_planks", false))));
        com.moddingfromamod.builder.BuildRegistry.putInMemoryForProbe(
                new com.moddingfromamod.builder.BuildPlan("probe_treehouse", "Treehouse", List.of(
                        new com.moddingfromamod.builder.BuildShape(
                                com.moddingfromamod.builder.BuildShape.Kind.BOX, 0, 0, 0, 6, 6, 6,
                                "minecraft:oak_planks", true))));
        var server = ((net.minecraft.server.level.ServerLevel) player.level()).getServer();
        String arg = com.moddingfromamod.agent.tools.MakeItemTool.itemArg(
                new com.moddingfromamod.agent.tools.MakeItemTool.Variant("Lightning Wand", 0x9966FF),
                com.moddingfromamod.item.IconSprites.numberFor("wand"), "Zap!", null, null,
                com.moddingfromamod.item.WishItem.WISH_FEATURE + ":\"probe_wand\"");
        try {
            var parsed = new net.minecraft.commands.arguments.item.ItemParser(server.registryAccess())
                    .parse(new com.mojang.brigadier.StringReader(arg));
            ItemStack wand = new ItemStack(parsed.item(), 1, parsed.components());
            String json = ItemStack.CODEC.encodeStart(net.minecraft.resources.RegistryOps.create(
                    com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess()), wand)
                    .getOrThrow().toString();
            com.moddingfromamod.item.WishItemCatalogue.replaceAll(List.of(
                    new com.moddingfromamod.item.WishItemCatalogue.Entry("probe_wand", "Lightning Wand",
                            json)));
        } catch (Exception e) {
            LOG.error("could not stage the probe's wand", e);
        }
        mark("creations", "seeded 6 creatures, 2 buildings, 1 item (memory only)");
    }

    /** {@code -Dmoddingfromamod.iconprobeworn=true}: the photograph — wished armour ON him. */
    public static final String WORN_PROPERTY = ModdingFromAModMod.MODID + ".iconprobeworn";

    /**
     * <b>Dress the child in wished armour, so a camera can see it on the BODY.</b>
     *
     * <p>Three pieces, each through the production give line ({@code MakeItemTool.itemArg} with the
     * shape) and put on with a real {@code item replace} — nothing hand-built: bright red boots and
     * a cyan helmet made from scratch (dyed leather on him), and a chestplate built on
     * {@code minecraft:golden_chestplate} (gold on him). The same three are also in the hotbar, so
     * one frame shows what they look like in the hand.
     */
    private static void dress(ServerPlayer player) {
        var server = ((net.minecraft.server.level.ServerLevel) player.level()).getServer();
        var source = server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput();
        String who = player.getGameProfile().name();
        String[][] pieces = {
                {"worn_feet", "Red Jump Boots", "E23B3B", null, "armor.feet", "0"},
                {"worn_head", "Cyan Helmet", "3BE2E2", null, "armor.head", "1"},
                {"worn_chest", "Gold Chest", "FFD700", "minecraft:golden_chestplate", "armor.chest", "2"},
        };
        List<String> shown = new ArrayList<>();
        for (String[] w : pieces) {
            String arg = com.moddingfromamod.agent.tools.MakeItemTool.itemArg(
                    new com.moddingfromamod.agent.tools.MakeItemTool.Variant(w[1],
                            Integer.parseInt(w[2], 16)),
                    com.moddingfromamod.item.IconSprites.numberFor("helmet"), "Put it on!", null, w[3],
                    com.moddingfromamod.item.WishItem.WISH_FEATURE + ":\"probe_" + w[4].replace('.', '_')
                            + "\"," + com.moddingfromamod.item.WishItem.WORN + ":\"" + w[0].substring(5) + "\"",
                    w[0]);
            server.getCommands().performPrefixedCommand(source,
                    "item replace entity " + who + " " + w[4] + " with " + arg);
            server.getCommands().performPrefixedCommand(source,
                    "item replace entity " + who + " hotbar." + w[5] + " with " + arg);
            shown.add(w[4] + "=" + w[1] + " (" + com.moddingfromamod.compat.Wearables.describe(
                    player.getItemBySlot(slotOf(w[4]))) + ")");
        }
        player.getInventory().setSelectedSlot(8);
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
        mark("hotbar", String.join(" ", shown));
    }

    private static net.minecraft.world.entity.EquipmentSlot slotOf(String armorSlot) {
        return switch (armorSlot) {
            case "armor.feet" -> net.minecraft.world.entity.EquipmentSlot.FEET;
            case "armor.head" -> net.minecraft.world.entity.EquipmentSlot.HEAD;
            case "armor.chest" -> net.minecraft.world.entity.EquipmentSlot.CHEST;
            default -> net.minecraft.world.entity.EquipmentSlot.LEGS;
        };
    }

    /** Eight colours no two of which read the same in a 16px sprite. */
    private static final int[] TINTS = {
            0xE23B3B, 0xE2963B, 0xE2E23B, 0x3BE23B,
            0x3BE2E2, 0x3B6BE2, 0x9B3BE2, 0xE23B9B };
}
