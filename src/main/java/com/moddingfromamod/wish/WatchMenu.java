package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.marshal.WatchIcons;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.marshal.WatchRow;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// ── TRANSITIONAL — watch spike. Not production. See docs/VANILLA_WATCH_SPIKE_RESULTS.md

/**
 * <b>The watch as a vanilla container menu — the panel a client with no mod can still press.</b>
 *
 * <p>{@code WishScreen} is mod client code and a vanilla client will never have it. A
 * {@link MenuType#GENERIC_9x6} chest, on the other hand, is plain vanilla protocol: the server sends
 * {@code ClientboundOpenScreenPacket} with a built-in menu id and the client draws it with no idea
 * that a mod is involved. So the panel is a chest whose slots are <b>presses</b>, not items.
 *
 * <p>The layout is {@link WatchPanel}, which is Minecraft-free and unit-tested. This class is only
 * the part that genuinely needs a game: turning a cell into an {@link ItemStack} and turning a click
 * back into a cell.
 *
 * <h2>Everything a chest menu already means, and what is done about it</h2>
 *
 * <p>This is §3.B.10's checklist, in code. A borrowed vanilla thing brings its own meaning, and
 * every piece of it is either matched or neutralised:
 *
 * <ul>
 *   <li><b>The items in it are real items.</b> A left-click picks one up and carries it out. Every
 *       click whose target is a panel slot is therefore consumed as a press and never passed to
 *       {@code super}, so nothing is ever picked up.</li>
 *   <li><b>Shift-click is a different door.</b> {@code QUICK_MOVE} does not go through the pickup
 *       path at all — it calls {@link #quickMoveStack}, which returns empty here.</li>
 *   <li><b>Middle-click clones, in creative.</b> {@code CLONE} would hand a creative child an
 *       amethyst shard called "Make it rain chickens!". Refused — and the child this is built for
 *       plays creative, so this is the likely one rather than the exotic one.</li>
 *   <li><b>Q throws.</b> {@code THROW} on a panel slot would drop a fake button into the world.
 *       Refused.</li>
 *   <li><b>Number keys swap.</b> {@code SWAP} exchanges a hotbar slot with the hovered one, which
 *       is a two-way loss: his item goes in, the button comes out. Refused.</li>
 *   <li><b>Dragging distributes.</b> {@code QUICK_CRAFT} spreads a carried stack across slots. The
 *       carried stack is always empty here, so it cannot reach a panel slot — but it is refused
 *       outright rather than argued about, because "cannot happen" is how the item spike's two
 *       worst bugs were justified.</li>
 *   <li><b>Closing drops the carried item</b>, which is correct and is left alone: it is his own
 *       item, picked up from his own inventory, and vanilla returning it is the right behaviour.
 *       The panel's own container is never emptied into the world because nothing can ever be put
 *       in it.</li>
 *   <li><b>His own inventory is part of the menu and stays fully usable</b> — refusing there would
 *       break his inventory for as long as the panel is open.</li>
 *   <li><b>An empty slot cannot be pressed</b>, so every cell holds a real stack of one.</li>
 *   <li><b>The title is an arbitrary Component</b>, which is vanilla, so it is matched.</li>
 * </ul>
 *
 * <h2>The one thing a refusal owes the client</h2>
 *
 * <p>A client predicts a click locally and then reconciles against the server. Refusing without
 * saying so leaves it drawing a picture the server does not agree with — the item appears to follow
 * the cursor until something else forces a resync. Every refusal therefore ends in
 * {@code sendAllDataToRemote()}, which is the same repair vanilla makes after a rejected click.
 */
public final class WatchMenu extends ChestMenu {

    /** The icons. Deliberately harmless: if a refusal ever leaked, the child gets a dye. */
    private static final net.minecraft.world.item.Item PRESET_ICON = Items.AMETHYST_SHARD;
    private static final net.minecraft.world.item.Item HAPPENING_ICON = Items.CLOCK;
    private static final net.minecraft.world.item.Item CREATION_ICON = Items.BOOK;
    private static final net.minecraft.world.item.Item REJECT_ICON = Items.DYE.red();
    private static final net.minecraft.world.item.Item CLOSE_ICON = Items.DYE.gray();

    /** The page on screen now. Not final: paging RE-LAYS this same menu. */
    private WatchPanel.Layout layout;
    /** the text-first watch's LIBRARY ("📚 My wishes"): his things and the ready-made wishes, no actions. */
    private boolean library;
    /** this chest is the guide page "📖 What do I do now?" opened from the text-first watch. */
    private boolean fromHome;

    private WatchMenu(int containerId, Inventory playerInventory, Container container,
                      WatchPanel.Layout layout) {
        super(MenuType.GENERIC_9x6, containerId, playerInventory, container, WatchPanel.ROWS);
        this.layout = layout;
    }

    /**
     * <b>Open the panel for this child.</b> Server thread only.
     *
     * <p>Reads exactly the same {@link WatchSnapshot#forPlayer} the mod screen is fed from, so the
     * panel and the screen cannot show different worlds. The rows arrive encoded because that is
     * how they travel to a modded client; decoding them here rather than growing a second accessor
     * keeps one source of truth (§3.B.8).
     */
    public static boolean open(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        // WAKE THE POOL, FIRST (step 4). Opening the panel is the earliest sign a
        // child is about to wish, and every door to it comes through here — a stock client's
        // right-click, a modded client's, and a bare `/wish`. If the pool was stopped during a
        // quiet spell, the ~10-13 s boot now runs while he reads the panel or types. Lock-free
        // and off the server thread; a no-op when the pool is up.
        com.moddingfromamod.agent.AgentSession.intent(player.getUUID(), "watch");
        WatchPanel.Layout plan = planFor(player);
        SimpleContainer container = new SimpleContainer(WatchPanel.SIZE);
        fill(container, plan);
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new WatchMenu(id, inv, container, plan),
                com.moddingfromamod.net.ButtonChest.title(title(player), player)));   // a BUTTON chest
        com.moddingfromamod.agent.ActionLog.menuOpen(player.getUUID().toString(), "watch");   // step 1
        return true;
    }

    /**
     * <b>Open the wish LIBRARY</b>: the text-first watch's "📚 My wishes". The same chest as
     * the panel, holding only his things and the ready-made wishes (and Close and the page turns); every action lives
     * on the wish box (Java) or the watch's button page (Bedrock), {@link WatchHome}.
     */
    public static boolean openLibrary(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        com.moddingfromamod.agent.AgentSession.intent(player.getUUID(), "watch");
        WatchPanel.Layout plan = libraryFor(player, 0);
        SimpleContainer container = new SimpleContainer(WatchPanel.SIZE);
        fill(container, plan);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            WatchMenu m = new WatchMenu(id, inv, container, plan);
            m.library = true;
            return m;
        }, com.moddingfromamod.net.ButtonChest.title(LIBRARY_TITLE, player)));
        com.moddingfromamod.agent.ActionLog.menuOpen(player.getUUID().toString(), "library");
        return true;
    }

    /** "📖 What do I do now?" from the text-first watch: the panel's chest, opened on its guide page. */
    public static boolean openGuide(ServerPlayer player) {
        if (!open(player)) {
            return false;
        }
        if (player.containerMenu instanceof WatchMenu w) {
            w.fromHome = true;   // its "◀ Back to my wishes" returns to the text-first watch
            w.showGuide(player);
        }
        return true;
    }

    /** The library's title. */
    public static final String LIBRARY_TITLE = "📚 My wishes";

    /** The actions the library leaves out: each is a button on the wish box or the watch's button page. */
    static final java.util.Set<WatchPanel.Action> NOT_IN_LIBRARY = java.util.EnumSet.of(WatchPanel.Action.COMPOSE,
            WatchPanel.Action.BUILDER, WatchPanel.Action.STOP, WatchPanel.Action.REJECT, WatchPanel.Action.GUIDE,
            WatchPanel.Action.GO, WatchPanel.Action.BACK_HOME);

    /** One page of his library: no happening row, no way-back tile, no Builder slot, no action cells. */
    public static WatchPanel.Layout libraryFor(ServerPlayer player, int page) {
        var snapshot = WatchSnapshot.forPlayer(player);
        WatchPanel.Layout l = magicianMarked(player, planFor(player.getUUID().toString(), List.of(), snapshot.creations(),
                true, page, com.moddingfromamod.net.PlayerClient.grid(player), true));
        List<WatchPanel.Cell> cells = new ArrayList<>();
        for (WatchPanel.Cell c : l.cells()) {
            if (!NOT_IN_LIBRARY.contains(c.action())) {
                cells.add(c);
            }
        }
        return new WatchPanel.Layout(cells, l.omittedPresets(), l.omittedHappening(), l.omittedCreations(),
                l.page(), l.pageCount());
    }

    /** The panel's own title, where there is no wish bank. */
    public static final String TITLE = "✨ Moddy ✨";

    /**
     * The chest's title: {@link #TITLE}, or on a world with a wish bank the balance too —
     * {@code ✨ Moddy · ✨ 7/10}, so the count is in front of him every time he opens the watch.
     */
    public static String title(ServerPlayer player) {
        return WishCharge.get().panelTitle(TITLE, player.getUUID());
    }

    /** Every slot of the chest set to one page's cells, and every other slot emptied. */
    private static void fill(Container container, WatchPanel.Layout plan) {
        for (int i = 0; i < WatchPanel.SIZE; i++) {
            container.setItem(i, ItemStack.EMPTY);
        }
        for (WatchPanel.Cell cell : plan.cells()) {
            container.setItem(cell.slot(), iconFor(cell));
        }
    }

    /**
     * <b>Turn to another page of THIS menu</b> — the same chest, re-laid, so a stock client
     * sees its items change and nothing else: no new screen, no new container id, and his cursor
     * stays where it was. Public so a gate can drive it without a click.
     */
    public void showPage(ServerPlayer player, int page) {
        if (fromHome && WatchHome.on()) {
            // with the text-first watch the only chest that is not the library is the guide page, and its
            // "◀ Back to my wishes" goes back to the watch he opened it from, never to the old mixed chest.
            player.closeContainer();
            WatchHome.open(player);
            return;
        }
        WatchPanel.Layout next = library ? libraryFor(player, page) : planFor(player, page);
        fill(getContainer(), next);
        this.layout = next;
        broadcastChanges();
        sendAllDataToRemote();
    }

    /** The page currently shown. */
    /**
     * <b>"📖 What do I do now?"</b>: re-lay THIS chest as the guide page
     * ({@link com.moddingfromamod.marshal.GuidePanel}) — the same container, so a stock client sees its
     * items change and nothing else, and "◀ Back to my wishes" is an ordinary page turn to page one.
     */
    public void showGuide(ServerPlayer player) {
        WatchPanel.Layout next = guideFor(player);
        fill(getContainer(), next);
        this.layout = next;
        broadcastChanges();
        sendAllDataToRemote();
    }

    /** The guide page for this player, on this world. Server thread. */
    public static WatchPanel.Layout guideFor(ServerPlayer player) {
        var world = com.moddingfromamod.world.WorldFacts.current();
        var answer = com.moddingfromamod.world.GoalGuide.next(player, world.guide());
        int wishPrice = WishCharge.get().wishPrice();
        List<WatchPanel.PresetRow> starters = new ArrayList<>();
        if (!answer.tree()) {
            for (WatchPanel.PresetRow pr : presetRows(player.getUUID().toString(), List.of(), java.util.Set.of())) {
                if (pr.starter()) {
                    starters.add(pr);
                }
            }
        }
        // laid out for the chest his client draws (Bedrock's Pocket UI: 7 wide)
        WatchPanel.Layout page = com.moddingfromamod.marshal.GuidePanel.lay(
                new com.moddingfromamod.marshal.GuidePanel.View(answer.tree(), answer.goal(),
                        // a Crafter's help is an Apprentice make, whose page says its own (half) price
                        CrafterService.isCrafter(player) ? 0 : wishPrice,
                        world.guide().hasBook(), world.guide().book().title(),
                        starters));
        // a Magician picks who answers his typed wishes, where the Apprentice exists (the Builder is on).
        if (!CrafterService.isCrafter(player) && com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(player.getUUID()).enabled()) {
            boolean apprentice = ApprenticeService.prefersApprentice(player);
            page = com.moddingfromamod.marshal.GuidePanel.withMode(page,
                    com.moddingfromamod.wishbuilder.ApprenticeWords.modeLabel(apprentice),
                    com.moddingfromamod.wishbuilder.ApprenticeWords.modeLore(apprentice));
        }
        // laid out for the chest his client draws (Bedrock's Pocket UI: 7 wide)
        return com.moddingfromamod.marshal.GuidePanel.project(page, com.moddingfromamod.net.PlayerClient.grid(player));
    }

    public WatchPanel.Layout layout() {
        return layout;
    }

    /** The layout this child would get right now — extracted so a gate can assert on it. */
    public static WatchPanel.Layout planFor(ServerPlayer player) {
        return planFor(player, 0);
    }

    /** One page of the layout this child would get right now. */
    public static WatchPanel.Layout planFor(ServerPlayer player, int page) {
        var snapshot = WatchSnapshot.forPlayer(player);
        List<WatchRow.Happening> happening = new ArrayList<>();
        for (String row : snapshot.happening()) {
            happening.add(WatchRow.Happening.decode(row));
        }
        return magicianMarked(player, forRole(player, planFor(player.getUUID().toString(), happening, snapshot.creations(), true,
                page, com.moddingfromamod.net.PlayerClient.grid(player))));
    }

    /** the line a Magician-made thing's tile carries under its ✪. */
    public static final String MAGICIAN_LORE = "✪ Made by the Magician: the Builder can change some of it.";

    /**
     * on a world whose Builder is on, each of his things the MAGICIAN made (not a Builder make he
     * still keeps) ends its name with ✪ and says so in its first line, so he knows before he opens it that the Builder
     * changes only part of it. At the END of the name: a Pocket grid drops a leading symbol, and shows no lore.
     */
    static WatchPanel.Layout magicianMarked(ServerPlayer player, WatchPanel.Layout l) {
        if (!com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(player.getUUID()).enabled()) {
            return l;
        }
        String owner = player.getUUID().toString();
        List<WatchPanel.Cell> cells = new ArrayList<>();
        boolean any = false;
        for (WatchPanel.Cell c : l.cells()) {
            if (c.family() == WatchPanel.Family.CREATION && !c.label().contains("  ✨") && !c.label().endsWith(
                    com.moddingfromamod.wishbuilder.MagicianCreature.MARK)
                    && com.moddingfromamod.wishbuilder.BuilderSpecStore.idFor(owner, c.arg(), c.label()) == null) {
                List<String> lore = new ArrayList<>();
                lore.add(MAGICIAN_LORE);
                lore.addAll(c.lore());
                cells.add(new WatchPanel.Cell(c.slot(), c.family(), c.action(),
                        c.label() + " " + com.moddingfromamod.wishbuilder.MagicianCreature.MARK, c.arg(), c.recipeId(), c.seed(),
                        c.icon(), lore));
                any = true;
            } else {
                cells.add(c);
            }
        }
        return !any ? l : new WatchPanel.Layout(cells, l.omittedPresets(), l.omittedHappening(), l.omittedCreations(),
                l.page(), l.pageCount());
    }

    /**
     * <b>HIS OWN things, as his watch lays them out — by id, online or not</b> (2026-10-12): the
     * account page's history is this, so it cannot show a thing his watch does not, nor draw it
     * with another icon or another card. No starters (they are everyone's, not his) and nothing
     * happening (a page is not live).
     */
    public static WatchPanel.Layout hisOwnFor(String viewerId, int page) {
        return planFor(viewerId, List.of(), WatchSnapshot.creationsFor(viewerId), false, page);
    }

    /**
     * every tile on this child's watch, by the id a hide names — a creation's {@code arg},
     * a ✨ button's {@code recipeId} — and its family. Every page, starters included. Server thread.
     */
    public static java.util.Map<String, WatchPanel.Family> tilesFor(String viewerId) {
        java.util.Map<String, WatchPanel.Family> out = new java.util.LinkedHashMap<>();
        List<String> creations = WatchSnapshot.creationsFor(viewerId);
        WatchPanel.Layout first = planFor(viewerId, List.of(), creations, true, 0);
        for (int page = 0; page < first.pageCount(); page++) {
            WatchPanel.Layout l = page == 0 ? first : planFor(viewerId, List.of(), creations, true, page);
            for (WatchPanel.Cell c : l.cells()) {
                String id = tileIdOf(c);
                if (!id.isEmpty()) {
                    out.putIfAbsent(id, c.family());
                }
            }
        }
        return out;
    }

    /** the id a hide of this tile names, or "" for a cell that is not one of his tiles. */
    static String tileIdOf(WatchPanel.Cell c) {
        return switch (c.family()) {
            case CREATION -> c.arg();
            case PRESET -> c.recipeId();
            default -> "";
        };
    }

    /** What {@link #hide} did. */
    public enum Hid { HIDDEN, FORGOT, ABSENT, REFUSED }

    /**
     * <b>Take one tile off this child's watch</b> — the child's own [🙈 Hide from my watch]
     * and the account page's "Remove from my watch" ({@code /moddyop hide}). Re-checked here: only a
     * tile his watch really shows can be hidden. A ✨ button HE owns is forgotten
     * ({@link LearnedTemplates#forgetOwned}, as the account page's delete always did); anything else —
     * a creation, a part, a starter, a button nobody owns — goes on his shelf's hidden list. One
     * already hidden is {@link Hid#HIDDEN} again, so every server can run the same line.
     */
    public static Hid hide(net.minecraft.server.MinecraftServer server, String viewerId, String tileId) {
        String who = com.moddingfromamod.marshal.ArtifactOwner.normalise(viewerId);
        if (!com.moddingfromamod.store.PlayerShelf.isShelvable(who) || !WatchHides.isTileId(tileId)) {
            return Hid.REFUSED;
        }
        if (WatchHides.hiddenFor(who).contains(tileId)) {
            return Hid.HIDDEN;
        }
        WatchPanel.Family family = tilesFor(who).get(tileId);
        if (family == null) {
            return Hid.ABSENT;
        }
        if (family == WatchPanel.Family.PRESET
                && LearnedTemplates.forgetOwned(server, who, tileId) == LearnedTemplates.Forgot.FORGOT) {
            return Hid.FORGOT;
        }
        try {
            WatchHides.hide(com.moddingfromamod.runtime.RuntimeConfig.playersDir(), who, tileId,
                    System.currentTimeMillis());
        } catch (java.io.IOException e) {
            return Hid.REFUSED;
        }
        return WatchHides.hiddenFor(who).contains(tileId) ? Hid.HIDDEN : Hid.REFUSED;
    }

    /** The child's own [🙈 Hide from my watch]: {@link #hide}, and what Moddy says back. */
    public static Hid hideFor(ServerPlayer player, String tileId) {
        Hid r = hide(player.level().getServer(), player.getStringUUID(), tileId);
        String said = switch (r) {
            case HIDDEN, FORGOT -> "🙈 Gone from your watch! Wish for it again any time and it comes back.";
            case ABSENT -> "🤔 I can't find that on your watch.";
            case REFUSED -> "😕 I couldn't take that off your watch just now.";
        };
        player.sendSystemMessage(Component.literal(said));
        return r;
    }

    private static WatchPanel.Layout planFor(String viewerId, List<WatchRow.Happening> happening,
                                             List<String> creationRows, boolean starters, int page) {
        return planFor(viewerId, happening, creationRows, starters, page, WatchPanel.Grid.JAVA);
    }

    private static WatchPanel.Layout planFor(String viewerId, List<WatchRow.Happening> happening,
                                             List<String> creationRows, boolean starters, int page,
                                             WatchPanel.Grid grid) {
        return planFor(viewerId, happening, creationRows, starters, page, grid, false);
    }

    private static WatchPanel.Layout planFor(String viewerId, List<WatchRow.Happening> happening,
                                             List<String> creationRows, boolean starters, int page,
                                             WatchPanel.Grid grid, boolean library) {
        List<WatchRow.Creation> creations = new ArrayList<>();
        for (String row : creationRows) {
            creations.add(WatchRow.Creation.decode(row));
        }
        List<WatchPanel.PresetRow> presets = new ArrayList<>();
        // ONE TILE PER THING HE MADE: a taught recipe whose own commands
        // replay a creation on this panel lives on that creation's tile (its card's [Make another
        // one] runs it — WatchSnapshot joins them by the same rule), so it is not a second button.
        List<String> onPanel = new ArrayList<>();
        for (WatchRow.Creation c : creations) {
            onPanel.add(c.id());
        }
        // a creation he HID still owns its recipe — joined, so it does not come back as an
        // orphan ✨ button for the thing he took away.
        onPanel.addAll(WatchHides.hiddenFor(viewerId));
        java.util.Set<String> joined = com.moddingfromamod.marshal.TaughtJoin.joined(onPanel,
                com.moddingfromamod.marshal.TaughtJoin.visibleTo(viewerId,
                        LearnedTemplates.taught()));   // per child: never a brother's recipe
        // HIS OWN PANEL: a brother's taught wishes are not his. ONE POOL: his
        // ORPHAN taught wishes, newest first, then the starters in the library's order.
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (WatchRow.Creation c : creations) {
            names.put(com.moddingfromamod.marshal.TaughtJoin.key(c.id()), c.name());
        }
        for (WatchPanel.PresetRow pr : presetRows(viewerId, LearnedTemplates.asPresets(), joined,
                names)) {
            if (starters || !pr.starter()) {
                presets.add(pr);
            }
        }
        // ↩ Take me back leads the tiles while "Go see it" has somewhere to take him back to.
        boolean wayBack;
        try {
            wayBack = viewerId != null && !viewerId.isBlank()
                    && WishGo.canGoBack(java.util.UUID.fromString(viewerId), System.currentTimeMillis());
        } catch (IllegalArgumentException notAUuid) {
            wayBack = false;
        }
        java.util.UUID viewer;
        try {
            viewer = viewerId == null || viewerId.isBlank() ? null : java.util.UUID.fromString(viewerId);
        } catch (IllegalArgumentException notAUuid) {
            viewer = null;
        }
        return WatchPanel.lay(presets, happening, creations, page, grid, wayBack && !library,
                !library && com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(viewer).enabled());
    }

    /**
     * A WISH CRAFTER's panel (step 5). His "✍ Wish for something new!" is the same tile in the same
     * slot as a Magician's and opens the same wish box; only its second line differs, because what answers him is
     * the Apprentice ({@link WishRoute}). Same slot, action and icon, so nothing a stock or Bedrock client maps
     * changes. Who answers is decided again on the server at submit: the lore is a courtesy.
     */
    static WatchPanel.Layout forRole(ServerPlayer player, WatchPanel.Layout l) {
        if (!CrafterService.isCrafter(player)) {
            return l;
        }
        List<WatchPanel.Cell> cells = new ArrayList<>();
        for (WatchPanel.Cell c : l.cells()) {
            cells.add(c.action() == WatchPanel.Action.COMPOSE
                    ? new WatchPanel.Cell(c.slot(), c.family(), c.action(), c.label(), "", "", "",
                            c.icon(), Crafters.COMPOSE_LORE)
                    : c);
        }
        return new WatchPanel.Layout(cells, l.omittedPresets(), l.omittedHappening(), l.omittedCreations(),
                l.page(), l.pageCount());
    }

    /**
     * The preset buttons a child with this id sees, icons resolved — the ONE place they are
     * built, so the snapshot an operator's dashboard draws ({@link WatchPanelSnapshot}) is built by the
     * same code as the panel a child opens, and the tower cannot show a watch no child sees.
     */
    public static List<WatchPanel.PresetRow> presetRows(String viewerId,
                                                        List<WishCatalog.Preset> learned,
                                                        java.util.Set<String> joined) {
        return presetRows(viewerId, learned, joined, java.util.Map.of());
    }

    /**
     * @param names his creations' names by part key, so a WISH button can say what it makes
     *; a part with no tile is named from its id
     */
    public static List<WatchPanel.PresetRow> presetRows(String viewerId,
                                                        List<WishCatalog.Preset> learned,
                                                        java.util.Set<String> joined,
                                                        java.util.Map<String, String> names) {
        java.util.Map<String, com.moddingfromamod.marshal.TaughtJoin.Taught> taughtById =
                new java.util.HashMap<>();
        for (var t : LearnedTemplates.taught()) {
            taughtById.put(t.id(), t);
        }
        List<WatchPanel.PresetRow> presets = new ArrayList<>();
        var library = com.moddingfromamod.library.WishLibrary.asPresets();
        java.util.Set<WishCatalog.Preset> starters =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        starters.addAll(library);
        // a button he took off his watch (a starter, one nobody owns) is not laid for him.
        java.util.Set<String> hidden = WatchHides.hiddenFor(viewerId);
        for (var p : WishPresets.forPanel(viewerId, library, learned, joined)) {
            if (hidden.contains(p.templateId())) {
                continue;
            }
            // A button with no icon of its own shows what its recipe MAKES.
            // A STORED amethyst is the old "no idea" answer written down, not a choice —
            // re-assessed from the recipe as if it were blank (WatchIcons.isUnset).
            String icon = com.moddingfromamod.marshal.WatchIcons.isUnset(p.icon())
                    ? PresetIcons.forRecipe(p.templateId(), p.wishText()) : p.icon();
            // a starter is laid after all of his own things; his own carries its time.
            WatchPanel.PresetRow row = starters.contains(p)
                    ? new WatchPanel.PresetRow(p.label(), p.wishText(), p.templateId(), icon)
                    : WatchPanel.PresetRow.his(p.label(), p.wishText(), p.templateId(), icon,
                            LearnedTemplates.timesOf(p.templateId()).recency());
            var taught = taughtById.get(p.templateId());
            if (!starters.contains(p) && com.moddingfromamod.marshal.TaughtJoin.isWish(taught)) {
                // the WISH entry says what it makes — its parts are tiles of their own.
                List<String> parts = new ArrayList<>();
                for (String part : com.moddingfromamod.marshal.TaughtJoin.partsOf(taught)) {
                    parts.add(names.getOrDefault(part, part.replaceAll("~.*$", "")
                            .replace('_', ' ')));
                }
                row = row.withParts(parts);
            }
            presets.add(row);
        }
        return presets;
    }

    /**
     * A cell as a stack.
     *
     * <p>{@code custom_name} and {@code lore} are DATA COMPONENTS, so they render on a vanilla
     * client exactly as they do on a modded one — the same property the item spike leaned on when a
     * carrier's own name turned out to travel for free. Italics are switched off because vanilla
     * italicises a renamed item, and a whole panel in italics reads as an error state.
     */
    /**
     * <b>The break seam, and it breaks the THING UNDER TEST rather than the instrument.</b>
     *
     * <p>{@code labels} strips every panel item's name. Nothing is missing, every slot is the right
     * item, every one is still pressable, and the panel renders perfectly — it is simply six
     * identical amethyst shards, which is a panel a child cannot read. That is the {@code shift}
     * shape the items spike used to prove its own oracle: a "did anything get drawn" check passes
     * on it, and only an assertion about what a child can actually tell apart goes red.
     *
     * <p>Inert unless {@code -Dmoddingfromamod.watchbreak} is set.
     */
    private static boolean breakingLabels() {
        return "labels".equalsIgnoreCase(
                System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".watchbreak",
                        "").trim());
    }

    /**
     * <b>PER CELL, not per family.</b>
     *
     * <p>This used to pick one icon per {@code Family}, which is how the shipped panel came out as
     * six identical amethyst shards over six identical books. A row of identical icons is not a
     * menu -- a child scanning it is reading nothing and has to hover every cell in turn to find
     * out what any of them is. The mapping is {@link WatchIcons}, kept pure so which item stands
     * for a wish is a decision with unit tests rather than a literal buried here.
     */
    private static ItemStack iconFor(WatchPanel.Cell cell) {
        // MODDY'S OWN ARTWORK ON THE ONE BUTTON THAT IS MODDY. Dad: "the wish watch itself is a
        // very cool icon" -- and it is already in the jar and already in the generated pack, so
        // using a name tag for "make a wish" was leaving the brand on the floor.
        //
        // It costs nothing on a client WITHOUT the pack either, which is the reason this is safe
        // where a whole panel of WishIcons would not be (see WatchIcons' class note). A packless
        // client draws the base carrier -- leather horse armour -- but DYED_COLOR, the label and
        // the glint all travel as components, so the cell is still the only WHITE shimmering thing
        // in a row of grey and brown. One cell borrowing the pack degrades to "distinct but plain";
        // forty-five cells borrowing it degrade to "all identical", which is the failure the item
        // spike measured.
        //
        // ⚠ This used to say "bright blue", and it was wrong in the same family as the premise
        // found next door: VanillaWatchItem.TINT is 0xFFFFFF, so the packless cell is white,
        // not blue. Corrected rather than tidied, because the argument survives the correction and
        // the number is the thing a reader would otherwise trust -- white is still a colour no
        // dungeon horse armour is (vanilla's untinted default is brown). And the pack
        // is really pushed, so the packless case is a declined dialogue rather than the default.
        if (cell.action() == WatchPanel.Action.COMPOSE && VanillaCarrier.enabled()) {
            ItemStack moddy = VanillaWatchItem.stack();
            moddy.remove(DataComponents.CUSTOM_NAME);
            moddy.remove(DataComponents.LORE);
            return label(moddy, cell);
        }
        ItemStack stack = iconStack(cell.icon());
        return label(stack, cell);
    }

    /**
     * <b>An icon string to the stack a slot shows</b> — the one place the three
     * {@link com.moddingfromamod.marshal.WatchIcons} spellings are turned into items:
     * {@code stack:<key>} is the wished item itself, rebuilt from his catalogue;
     * {@code wish:<sprite>#RRGGBB} is an icon-library sprite on the carrier, dyed; anything else is
     * a vanilla id. Every miss falls back to {@link #resolve}'s item rather than to an empty slot.
     */
    public static ItemStack iconStack(String icon) {
        Face face = face(icon);
        if (face.item() != null) {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            ItemStack held = server == null ? ItemStack.EMPTY
                    : com.moddingfromamod.item.WishItemCatalogue.stack(face.item(), server.registryAccess());
            if (!held.isEmpty()) {
                held.setCount(1);
                // The catalogue's copy was minted when it was made — an item
                // older than overlays gets the one its design calls for, as it is drawn.
                com.moddingfromamod.item.OverlayBackfill.apply(held);
                return held;
            }
            return new ItemStack(resolve(com.moddingfromamod.marshal.WatchIcons.forKind(
                    com.moddingfromamod.marshal.MadeKind.ITEM)));
        }
        var sprite = face.sprite();
        if (sprite != null) {
            ItemStack s = new ItemStack(resolve(VanillaCarrier.ITEM_ID));
            com.moddingfromamod.compat.ModelData.stamp(s,
                    com.moddingfromamod.item.IconSprites.numberFor(sprite.name()));
            com.moddingfromamod.compat.ItemComponents.dyeHidden(s, sprite.rgb());
            // Not armour and not a horse's: nothing a panel icon should ever say in a tooltip.
            // ⚠ NON-EMPTY, the same trick as VanillaWatchItem's watch_inert: NeoForge replaces an
            // EMPTY component with the carrier's own +3 armour, and false hides the line.
            s.set(DataComponents.ATTRIBUTE_MODIFIERS,
                    new net.minecraft.world.item.component.ItemAttributeModifiers(java.util.List.of(
                            new net.minecraft.world.item.component.ItemAttributeModifiers.Entry(
                                    net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,
                                    new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                                            net.minecraft.resources.Identifier
                                                    .fromNamespaceAndPath(
                                                            com.moddingfromamod.ModdingFromAModMod.MODID,
                                                            "panel_icon_inert"),
                                            0.0D,
                                            net.minecraft.world.entity.ai.attributes
                                                    .AttributeModifier.Operation.ADD_VALUE),
                                    net.minecraft.world.entity.EquipmentSlotGroup.BODY))));
            com.moddingfromamod.compat.ItemComponents.hideLine(s, DataComponents.ATTRIBUTE_MODIFIERS);
            return s;
        }
        return new ItemStack(resolve(face.vanilla()));
    }

    /**
     * <b>What an icon string DRAWS, decided once</b> (2026-10-12): one of his wished items, one of
     * our dyed sprites, or a vanilla item. {@link #iconStack} turns it into the stack on his watch;
     * {@link #faceJson} into the data the account page draws — so the two cannot pick different
     * pictures for one tile.
     */
    record Face(String vanilla, com.moddingfromamod.marshal.WatchIcons.Sprite sprite,
                com.moddingfromamod.item.WishItemCatalogue.Entry item) {
    }

    static Face face(String icon) {
        String key = com.moddingfromamod.marshal.WatchIcons.stackKeyOf(icon);
        if (key != null) {
            for (var e : com.moddingfromamod.item.WishItemCatalogue.all()) {
                if (e.key().equals(key)) {
                    return new Face(null, null, e);
                }
            }
            return new Face(com.moddingfromamod.marshal.WatchIcons.forKind(
                    com.moddingfromamod.marshal.MadeKind.ITEM), null, null);
        }
        var sprite = com.moddingfromamod.marshal.WatchIcons.spriteOf(icon);
        if (sprite != null && com.moddingfromamod.item.IconSprites.byName(sprite.name()) != null
                && VanillaCarrier.enabled()) {
            return new Face(null, sprite, null);
        }
        if (sprite != null) {
            return new Face(com.moddingfromamod.marshal.WatchIcons.forKind(
                    com.moddingfromamod.marshal.WatchIcons.SPECIES), null, null);
        }
        return new Face(icon, null, null);
    }

    /**
     * A {@link Face} as the account page reads it: {@code {"item": "<vanilla id>"}}, or
     * {@code {"made": "item"|"wish", "look": {"sprite", "color", "effects"}}} — the shape the
     * shared icon library ({@code tools/wishgrid}) composes.
     */
    /**
     * What a STACK draws, as the page reads it: one of our icon pictures (its bound number → the
     * sprite's name, its dye, the overlay it wears) or else the vanilla item it is.
     */
    static com.google.gson.JsonObject stackJson(ItemStack held) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        var cmd = held.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd != null) {
            int n = com.moddingfromamod.compat.ModelData.id(cmd);
            for (var sp : com.moddingfromamod.item.IconSprites.bindings()) {
                if (sp.customModelData() == n) {
                    com.google.gson.JsonObject look = new com.google.gson.JsonObject();
                    look.addProperty("sprite", sp.name());
                    var dye = held.get(DataComponents.DYED_COLOR);
                    if (dye != null) {
                        look.addProperty("color", String.format("#%06x", dye.rgb() & 0xFFFFFF));
                    }
                    String overlay = com.moddingfromamod.compat.ModelData.overlayOf(cmd);
                    if (overlay == null) {
                        overlay = com.moddingfromamod.item.OverlayBackfill.overlayFor(held);
                    }
                    if (overlay != null) {
                        o.addProperty("overlay", overlay);
                    }
                    o.addProperty("made", "item");
                    o.add("look", look);
                    return o;
                }
            }
        }
        o.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        return o;
    }

    static com.google.gson.JsonObject faceJson(Face face) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        if (face.item() != null) {
            String k = face.item().key();
            var spec = com.moddingfromamod.item.ItemSpecStore.get(k.startsWith("id:") ? k.substring(3) : k);
            if (spec == null || spec.icon() == null || spec.icon().isBlank()) {
                // No design to read (an item made before designs were kept, a special item like
                // the watch): the watch draws the STACK itself, so the page is told what the
                // stack draws — the same answer, read off the same thing.
                var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                ItemStack held = server == null ? ItemStack.EMPTY
                        : com.moddingfromamod.item.WishItemCatalogue.stack(face.item(), server.registryAccess());
                if (!held.isEmpty()) {
                    return stackJson(held);
                }
                o.addProperty("item", spec != null && spec.baseItem() != null && !spec.baseItem().isBlank()
                        ? spec.baseItem() : com.moddingfromamod.marshal.WatchIcons.forKind(
                                com.moddingfromamod.marshal.MadeKind.ITEM));
                return o;
            }
            com.google.gson.JsonObject look = new com.google.gson.JsonObject();
            look.addProperty("sprite", spec.icon());
            if (spec.colour() != null) {
                look.addProperty("color", spec.colour());
            }
            com.google.gson.JsonArray effects = new com.google.gson.JsonArray();
            spec.effects().forEach(effects::add);
            look.add("effects", effects);
            o.addProperty("made", "item");
            o.add("look", look);
            return o;
        }
        if (face.sprite() != null) {
            com.google.gson.JsonObject look = new com.google.gson.JsonObject();
            look.addProperty("sprite", face.sprite().name());
            look.addProperty("color", String.format("#%06x", face.sprite().rgb() & 0xFFFFFF));
            o.addProperty("made", "wish");
            o.add("look", look);
            return o;
        }
        o.addProperty("item", face.vanilla());
        return o;
    }

    /** The name and tooltip a child reads — shared, so the Moddy cell cannot drift from the rest. */
    private static ItemStack label(ItemStack stack, WatchPanel.Cell cell) {
        if (!breakingLabels()) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(cell.label())
                    .setStyle(Style.EMPTY.withColor(colourFor(cell)).withItalic(false)));
        }
        // on a world with a wish bank, a press that wishes says its price BEFORE he presses
        // ("Uses 1 ✨", the bar's own words); with no wish bank it is "" and nothing is added.
        String price = cell.action() == WatchPanel.Action.WISH
                ? WishCharge.get().loreLine(againKind(cell))
                : "";
        if (!cell.lore().isEmpty() || !price.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : cell.lore()) {
                lines.add(Component.literal(line)
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)));
            }
            if (!price.isEmpty()) {
                lines.add(Component.literal(price)
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withItalic(false)));
            }
            stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lines));
        }
        return stack;
    }

    /**
     * An id to an item, falling back rather than crashing.
     *
     * <p>{@code BuiltInRegistries.ITEM.get} answers AIR for an unknown id, and an AIR stack in a
     * container slot is an EMPTY slot -- so one typo in the mapping would not throw, it would
     * silently delete a row of the panel. The fallback is what makes that impossible;
     * {@code everyIconIsARealItem} is what makes it never fire.
     */
    private static net.minecraft.world.item.Item resolve(String id) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getValue(net.minecraft.resources.Identifier.parse(id));
        return item == net.minecraft.world.item.Items.AIR ? PRESET_ICON : item;
    }

    private static ChatFormatting colourFor(WatchPanel.Cell cell) {
        return switch (cell.family()) {
            case PRESET -> ChatFormatting.AQUA;
            case HAPPENING -> ChatFormatting.LIGHT_PURPLE;
            case CREATION -> ChatFormatting.YELLOW;
            case GUIDE -> ChatFormatting.GOLD;
            case CONTROL -> switch (cell.action()) {
                case REJECT -> ChatFormatting.RED;
                // GREEN, and the only green on the panel: this is the one cell that starts
                // something rather than ending it.
                case COMPOSE -> ChatFormatting.GREEN;
                case BUILDER -> ChatFormatting.GOLD;
                default -> ChatFormatting.GRAY;
            };
        };
    }

    /** Nothing ever leaves the panel by shift-click. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput type, Player player) {
        boolean panelSlot = slotId >= 0 && slotId < WatchPanel.SIZE;
        // A drag can only reach a panel slot with something on the cursor, and nothing can ever get
        // onto the cursor from here — but refuse it anyway rather than rely on that reasoning.
        if (type == ContainerInput.QUICK_CRAFT) {
            refuse(player);
            return;
        }
        if (!panelSlot) {
            // His own inventory. Left alone, except that shift-clicking out of it would try to push
            // an item INTO the panel — quickMoveStack already refuses that, from both directions.
            super.clicked(slotId, button, type, player);
            return;
        }
        // the BUTTON matters now — left makes a past wish again, right opens its card. A
        // stock client sends both as PICKUP; every other verb presses as a left click always did.
        press(slotId, com.moddingfromamod.marshal.WatchClick.secondary(type == ContainerInput.PICKUP, button),
                player);
    }

    /** Turn a click on a panel slot into the thing the cell says it is, and never a slot move. */
    private void press(int slotId, boolean secondary, Player player) {
        refuse(player);
        WatchPanel.Cell cell = layout.at(slotId);
        if (cell == null || !(player instanceof ServerPlayer sp)) {
            return;
        }
        com.moddingfromamod.agent.ActionLog.menuPress(sp.getUUID().toString(), "watch", cell.action().name(),
                cell.family().name(), layout.page(), slotId,
                com.moddingfromamod.marshal.WatchClick.logged(cell), secondary);   // step 1
        if (com.moddingfromamod.marshal.WatchClick.decide(cell, secondary)
                == com.moddingfromamod.marshal.WatchClick.Press.CARD) {
            ThingPage.open(sp, cell, layout.page());
            return;
        }
        switch (cell.action()) {
            case WISH -> {
                sp.closeContainer();
                // SOURCE and RECIPE ID both, which is the whole reason this is not "/wish <label>".
                // A preset re-derived from its own label loses the design it names and is routed as
                // if it were free text -- see WishService.submit.
                WishService.submit(sp, cell.arg(), WishSource.PRESET, cell.recipeId());
            }
            case GO -> {
                sp.closeContainer();
                WishGo.go(sp, cell.arg());
            }
            case CARD -> ThingPage.open(sp, cell, layout.page());
            case STOP -> {
                sp.closeContainer();
                WishCancel.stop(sp, "", com.moddingfromamod.marshal.CancelMode.STOP, null);
            }
            case BACK_HOME -> {
                sp.closeContainer();
                WishGo.back(sp);
            }
            case REJECT -> {
                sp.closeContainer();
                WishService.reject(sp, "");
            }
            case BUILDER -> com.moddingfromamod.wishbuilder.BuilderMenus.open(sp);   //: replaces the panel
            // The wish box: a dialog with a text box (D2). It closes the chest itself. The SAME box for
            // everyone: a Crafter's words go to the Apprentice, a Magician's to the agent (WishRoute).
            case COMPOSE -> WishCompose.open(sp);
            // the Magician's "Typed wishes: Magician ✪ / Apprentice ✨" toggle, on the 📖 page. Re-laid in place.
            case WISH_MODE -> {
                ApprenticeService.toggle(sp);
                showGuide(sp);
            }
            case CLOSE -> sp.closeContainer();
            case GUIDE -> showGuide(sp);
            case GOALS -> GuidePages.openGoals(sp, 0);
            case LINKS -> {
                sp.closeContainer();
                GuidePages.sayLinks(sp);
            }
            // The help wish is the ONLY press here that costs, and it costs nothing itself: the wish
            // box opens with the words in it and the wish goes the normal way when he confirms it.
            // the same help words for everyone. A Crafter's wish box goes to the Apprentice: Builder
            // makes, half a wish, no AI; a Magician's to whichever magic his toggle says.
            case HELP_WISH -> WishCompose.open(sp, cell.arg());
            case HOW_TO -> {
                sp.closeContainer();
                com.moddingfromamod.world.JoinIntroService.help(sp);
            }
            case GUIDE_BOOK -> {
                sp.closeContainer();
                com.moddingfromamod.world.GuideBookService.command(sp);
            }
            case PAGE -> {
                // NOT closeContainer + open: the same chest is re-laid, so a stock client
                // keeps its screen and the cursor stays under his finger.
                int page;
                try {
                    page = Integer.parseInt(cell.arg());
                } catch (NumberFormatException e) {
                    page = 0;
                }
                showPage(sp, page);
            }
            case NONE -> { }
        }
    }

    /**
     * which "again" a recipe press is — {@code <wish>@<part>} remakes one PART, anything else
     * the WHOLE wish. Both cost one wish on a world with a wish bank; the kind is what the preview is asked for.
     */
    static WishCharge.Again againKind(WatchPanel.Cell cell) {
        return WishCharge.Again.ofRecipe(
                com.moddingfromamod.marshal.TaughtJoin.splitPart(cell.recipeId()) != null);
    }

    /** Put the client's picture back where the server's is. */
    private void refuse(Player player) {
        if (player instanceof ServerPlayer sp) {
            sp.containerMenu.sendAllDataToRemote();
        }
    }
}
