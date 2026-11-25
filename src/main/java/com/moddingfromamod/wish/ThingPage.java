package com.moddingfromamod.wish;

import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>One of his things, and what he can do with it</b> — the page a press on a thing's tile opens,
 * on every edition (2026-11-10, a Bedrock design decision, D1 "cards").
 *
 * <p>It replaced a row of clickable chat links ({@code WatchMenu.verbRow}). Those were the right tool
 * on Java 1.21.1 and the wrong one everywhere else: a Bedrock phone turned each link line into a pop-up
 * form, and a chat line scrolls away. A {@link PickPage} opened by {@link MenuKit} is a chest on Java
 * and a one-tap grid on a phone, so both editions get the SAME page with no fork (docs/BEDROCK_COMPAT.md
 * → "Menu mapping").
 *
 * <p>Each verb runs the very command its chat link ran (a permission-0 {@code /moddy} branch), so the
 * price, the recipe check and the give rules cannot drift between the page and the command a child can
 * still type. "Wish about this" is the one verb that is not a command: it opens the wish box with the
 * words already in it.
 */
public final class ThingPage {

    private ThingPage() {
    }

    /** "Wish about this": the Magician's mark only where he would answer the wish ({@link MagicianDoor#offered}, #78). */
    public static final String WISH_ABOUT_MAGICIAN = "✪ Wish about this";
    public static final String WISH_ABOUT = "✨ Wish about this";

    /** The Builder verb's lore while the change is the free one ({@code FreeEdit}). */
    public static final String FREE_CHANGE_LORE = "Free until you wish again";

    /**
     * One thing he can do. {@code command} is what a press runs; {@code prefill}, when not null, means a
     * press opens the wish box with these words instead. Both null: the page handles the press itself
     * (the hotbar tiles, {@link WishHotbar}).
     */
    public record Verb(String id, String icon, String label, String lore, String command, String prefill) {
        /** What a test reads: the label and what pressing it does. */
        public String describe() {
            return label + " → " + (prefill != null ? "wish: " + prefill : command != null ? command : "(" + id + ")");
        }
    }

    /** Every verb, in the order the page shows them, as a string a test can search. */
    public static String describe(List<Verb> verbs) {
        return String.join(" | ", verbs.stream().map(Verb::describe).toList());
    }

    /**
     * <b>The verbs this thing offers</b>. Two of them appear CONDITIONALLY, and a verb that fails to
     * appear is the quietest defect on this surface: nothing errors and the child never learns it exists
     *. {@code GiveAgainGameTest}, {@code WatchHideGameTest} and {@code VanillaWatchGameTest} drive
     * this on a real server with the real catalogue — where it runs.
     */
    public static List<Verb> verbs(WatchPanel.Cell cell, UUID owner) {
        return verbs(cell, owner, null);
    }

    /** {@link #verbs(WatchPanel.Cell, UUID)} for a player who is here: adds what needs his inventory (the hotbar tile). */
    public static List<Verb> verbs(WatchPanel.Cell cell, UUID owner, ServerPlayer holder) {
        List<Verb> out = new ArrayList<>();
        if (cell.canRemake()) {
            // NO MATCHING AT ALL — the one path that replays a design by name (/moddy again).
            out.add(new Verb("again", "minecraft:nether_star",
                    "✨ Make another one" + WishCharge.get().preview(WatchMenu.againKind(cell)),
                    "Moddy makes it again, just the same", ChildCommands.again(cell.recipeId()), null));
        }
        out.add(new Verb("go", "minecraft:spyglass", "🔍 Go see it", "Moddy takes you there",
                ChildCommands.go(""), null));
        // SEED, not the display name: the recipe's own terms are what separate his things.
        // ✪ is the Magician's mark: only where he would answer the wish (#78); else the Apprentice's ✨
        boolean magician = holder != null ? MagicianDoor.offered(holder) : MagicianDoor.on(owner);
        out.add(new Verb("wish", "minecraft:writable_book", magician ? WISH_ABOUT_MAGICIAN : WISH_ABOUT, "Change it with a wish",
                null, "I want my " + seedOf(cell) + " to "));
        // 🎁 GIVE ME ONE: a copy of one that exists, a DIFFERENT verb from making another — the
        // labels have different shapes on purpose. Only when the catalogue can serve it.
        if (com.moddingfromamod.item.WishItemCatalogue.resolve(cell.arg()).size() == 1) {
            out.add(new Verb("give", "minecraft:chest",
                    "🎁 Give me one" + WishCharge.get().preview(WishCharge.Again.GIVE_ONE),
                    "Another one of it, in your bag", ChildCommands.give(cell.arg()), null));
        }
        // ✦ CHANGE IT IN THE BUILDER: only his own Builder makes, only where the Builder is on.
        String builderId = owner == null || cell.family() != WatchPanel.Family.CREATION
                || !com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(owner).enabled() ? null
                : com.moddingfromamod.wishbuilder.BuilderSpecStore.idFor(owner.toString(), cell.arg(), cell.label());
        if (builderId != null) {
            // The last Apprentice make's thing is free to change once, until his next wish.
            boolean free = com.moddingfromamod.wishbuilder.FreeEdit.isFree(owner, builderId);
            out.add(new Verb("builder", "minecraft:crafting_table", "✦ Change it in the Builder",
                    free ? FREE_CHANGE_LORE : "Every pick you made, ready to change",
                    com.moddingfromamod.wishbuilder.BuilderMenus.editCommand(builderId), null));
        }
        // ✦ CHANGE IT IN THE BUILDER, on a creature the MAGICIAN made (slice 1): the Builder changes what it can (its
        // size, the Builder's powers, its temper) and everything else stays as the Magician made it. His own creature only.
        String magicianId = builderId != null || owner == null || cell.family() != WatchPanel.Family.CREATION
                || !com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(owner).enabled() ? null
                : com.moddingfromamod.wishbuilder.BuilderMenus.magicianThingId(owner, cell.arg());   // a creature or a building
        if (magicianId != null) {
            out.add(new Verb("builder", "minecraft:crafting_table",
                    com.moddingfromamod.wishbuilder.MagicianCreature.VERB_LABEL,   // short: one Pocket line
                    com.moddingfromamod.wish.WatchMenu.MAGICIAN_LORE,
                    com.moddingfromamod.wishbuilder.BuilderMenus.magicianCommand(magicianId), null));
        }
        // 🎯 ADD TO HOTBAR: one click makes it again. Only a thing with a recipe of its own.
        Verb hotbar = WishHotbar.verb(cell, holder);
        if (hotbar != null) {
            out.add(hotbar);
        }
        // 🙈 HIDE: declutter, not a delete. Last, so it is never the one a child hits first.
        String tile = WatchMenu.tileIdOf(cell);
        if (WatchHides.isTileId(tile)) {
            out.add(new Verb("hide", "minecraft:gray_dye", "🙈 Hide from my watch",
                    "Wish for it again and it comes back", ChildCommands.hide(tile), null));
        }
        return out;
    }

    /** The page itself — what a gate asserts on. */
    public static PickPage page(WatchPanel.Cell cell, List<Verb> verbs) {
        List<PickPage.Tile> tiles = new ArrayList<>();
        for (Verb v : verbs) {
            tiles.add(new PickPage.Tile(v.id(), v.icon(), v.label(), List.of(v.lore()), false));
        }
        String name = cell.label().replace("  ✨", "").strip();
        return PickPage.of("🧸 " + name, tiles, true);
    }

    /** Open it; ◀ Back returns to the watch on the page he came from. */
    public static void open(ServerPlayer sp, WatchPanel.Cell cell, int watchPage) {
        List<Verb> verbs = verbs(cell, sp.getUUID(), sp);
        MenuKit.pick(sp, "thing", page(cell, verbs), (p, id) -> {
            if (!WishHotbar.press(p, id, cell)) {
                press(p, id, verbs, watchPage);
            }
        });
    }

    static void press(ServerPlayer sp, String id, List<Verb> verbs, int watchPage) {
        if (PickPage.BACK_ID.equals(id)) {
            // with the text-first watch a thing's page is reached from the library, so Back goes there.
            if ((WatchHome.on() ? WatchMenu.openLibrary(sp) : WatchMenu.open(sp)) && sp.containerMenu instanceof WatchMenu w) {
                w.showPage(sp, watchPage);
            }
            return;
        }
        for (Verb v : verbs) {
            if (!v.id().equals(id)) {
                continue;
            }
            if (v.prefill() != null) {
                WishCompose.open(sp, v.prefill());
                return;
            }
            sp.closeContainer();
            sp.getServer().getCommands().performPrefixedCommand(sp.createCommandSourceStack(), v.command());
            return;
        }
    }

    private static String seedOf(WatchPanel.Cell cell) {
        return cell.seed().isBlank() ? cell.label().replace(" " + com.moddingfromamod.wishbuilder.MagicianCreature.MARK, "").strip()
                : cell.seed();
    }
}
