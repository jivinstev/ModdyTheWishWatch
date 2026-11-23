package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.testing.AllPairs;
import com.moddingfromamod.ui.PickPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>"Turn things into…"'s two pages offer only what make_item makes</b>. The From page is a
 * multi-select, so its subsets are a product that grows with every group: by the rule (docs/GATE_PERFORMANCE.md)
 * this checks EACH GROUP ALONE with every To on every kind of item that carries the power, then an ALL-PAIRS sample of
 * (item, three From picks, To) — never every subset. Each check asks make_item's own reading
 * ({@code MakeItemTool.filledOffServer}, i.e. TurnIntoRecipe): an offered To it would refuse is a failure, and so is a To
 * the page hides that it would make (the page would be refusing for no reason).
 */
class BuilderTurnIntoTest {

    static final ItemCatalogue.Entry TURN_INTO = ItemCatalogue.load().get("turn_into");

    /** The kinds of item the power is on: held (aims), swung (the blocks round what it hits), thrown (where it lands). */
    static final List<String> SHAPES = List.of("held", "on_hit", "on_throw_land");

    /** make_item's refusal of this pick, or null when it makes it. */
    static String makeItemSays(String shape, List<BuilderTurnInto.From> from, BuilderTurnInto.To to) {
        try {
            MakeItemTool.filledOffServer(TURN_INTO, shape, BuilderTurnInto.params(shape, from, to, 2));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * Off-server, make_item reads every tag as a block tag too (TurnInto.ASSUME), so a group with no block form whose
     * members are item tags (raw food: #minecraft:meat) looks to it like blocks, and "raw food vanishes" reads as
     * makeable here while the live registry refuses it. Only the hidden-but-makeable direction is excused for that.
     */
    static boolean assumedBlocks(List<BuilderTurnInto.From> from) {
        return from.stream().anyMatch(g -> !g.block() && g.members().stream().anyMatch(m -> m.startsWith("#")));
    }

    /** Is the Builder's verdict make_item's? One line for each disagreement. */
    static void agree(String shape, List<BuilderTurnInto.From> from, BuilderTurnInto.To to, List<String> wrong) {
        String builder = BuilderTurnInto.whyNot(shape, from, to);
        String made = makeItemSays(shape, from, to);
        String which = shape + " " + from.stream().map(BuilderTurnInto.From::id).toList() + " -> " + to.id();
        if (builder == null && made != null) {
            wrong.add(which + ": offered, and make_item refuses it: " + made);
        } else if (builder != null && made == null && !assumedBlocks(from)) {
            wrong.add(which + ": hidden (" + builder + "), and make_item would make it");
        }
    }

    @Test
    void thePagesInTheirPinnedOrder() {
        assertEquals(List.of("any_block", "ores", "logs", "sand", "stone", "dirt_grass", "gravel", "clay", "raw_food",
                "plants", "wool", "netherrack", "ice_snow"), BuilderTurnInto.FROM.stream().map(BuilderTurnInto.From::id).toList());
        assertEquals(List.of("Any block", "Ores & raw metal", "Logs & wood", "Sand", "Stone & cobblestone", "Dirt & grass",
                "Gravel", "Clay", "Raw food", "Plants & flowers", "Wool", "Netherrack", "Ice & snow"),
                BuilderTurnInto.FROM.stream().map(BuilderTurnInto.From::label).toList());
        assertEquals(List.of("furnace", "gold", "iron", "copper", "diamond", "emerald", "coal", "glass", "stone",
                "cobblestone", "sand", "dirt", "gravel", "wool", "planks", "ice", "vanish"), BuilderTurnInto.toIds());
        assertEquals("What a furnace makes", BuilderTurnInto.to("furnace").label());
        assertEquals("Vanish", BuilderTurnInto.to("vanish").label());
        assertEquals("Point at a block, or tap the air for what's in your inventory.", BuilderTurnInto.lore("held"));
    }

    @Test
    void eachFromGroupAloneWithEveryToOnEveryKindOfItem() {
        // Per group: each group's own offer, on each shape. Linear in groups x targets, never in subsets.
        List<String> wrong = new ArrayList<>();
        for (String shape : SHAPES) {
            for (BuilderTurnInto.From g : BuilderTurnInto.FROM) {
                if (!BuilderTurnInto.offered(g, shape)) {
                    assertFalse(g.block(), g.id() + " is withheld on " + shape + " only when it has no block form");
                    continue;
                }
                long offers = BuilderTurnInto.TO.stream().filter(t -> BuilderTurnInto.whyNot(shape, List.of(g), t) == null).count();
                if (offers == 0) {
                    wrong.add(shape + " " + g.id() + ": on the From page and nothing on the To page");
                }
                BuilderTurnInto.TO.forEach(t -> agree(shape, List.of(g), t, wrong));
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " disagreements:\n" + String.join("\n", wrong));
    }

    @Test
    void anAllPairsSampleOfSeveralFromPicksAgreesWithMakeItem() {
        // Factors: shape, three From slots (a group or none), the To. Every pair of values meets in some row.
        List<BuilderTurnInto.From> groups = BuilderTurnInto.FROM;
        int n = groups.size();
        List<int[]> rows = AllPairs.rows(new int[] {SHAPES.size(), n, n + 1, n + 1, BuilderTurnInto.TO.size()}, 2216L);
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (int[] r : rows) {
            String shape = SHAPES.get(r[0]);
            List<BuilderTurnInto.From> from = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                if (r[i] < n && !from.contains(groups.get(r[i]))) {
                    from.add(groups.get(r[i]));
                }
            }
            if (from.stream().anyMatch(g -> !BuilderTurnInto.offered(g, shape))) {
                continue;   // not on this item's From page
            }
            BuilderTurnInto.To to = BuilderTurnInto.TO.get(r[4]);
            agree(shape, from, to, wrong);
            // The marked tile: the furnace exactly when every pick has something a furnace makes of it, and none is any
            // block.
            boolean allSmelt = from.stream().allMatch(BuilderTurnInto::smelts)
                    && from.stream().noneMatch(g -> g.id().equals(BuilderTurnInto.ANY_BLOCK));
            assertEquals(allSmelt, BuilderTurnInto.furnaceIsDefault(from), from + ": the furnace is the default iff all smelt, no any block");
            checked++;
        }
        assertTrue(checked > 100, "the sample checked " + checked + " picks");
        assertTrue(wrong.isEmpty(), wrong.size() + " disagreements:\n" + String.join("\n", wrong));
    }

    static List<String> ids(PickPage p) {
        return p.tiles().stream().map(PickPage.Tile::id).toList();
    }

    static BuilderFlow toTurnInto(String stock) {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("item");
        f.press(BuilderFlow.STOCK_PREFIX + stock);
        assertTrue(ids(f.page()).contains("itempower:" + BuilderTurnInto.POWER), stock + " offers Turn things into…");
        PickPage.Tile tile = f.page().tiles().stream().filter(t -> t.id().equals("itempower:" + BuilderTurnInto.POWER)).findFirst().orElseThrow();
        assertEquals("Turn things into…", tile.label());
        assertEquals(List.of(BuilderTurnInto.lore(BuilderItemPowers.stock(stock).shape())), tile.lore());
        f.press("itempower:" + BuilderTurnInto.POWER);
        assertEquals(BuilderFlow.Step.TURN_FROM, f.step());
        return f;
    }

    @Test
    void aWandWalksFromThenToThenTuneAndLowersToOneTurnInto() {
        BuilderFlow f = toTurnInto("wand");
        assertEquals(BuilderTurnInto.FROM_TITLE, f.page().title());
        List<String> fromPage = new ArrayList<>(BuilderTurnInto.FROM.stream().map(g -> BuilderTurnInto.FROM_PREFIX + g.id()).toList());
        fromPage.add(BuilderFind.TILE_ID);   //: then the 🔍 Find
        assertEquals(fromPage, ids(f.page()),
                "a wand aims, so every group is offered (raw food in his inventory too)");
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.TURN_FROM, f.step(), "Next with nothing picked is refused");
        f.press(BuilderTurnInto.FROM_PREFIX + "ores");
        f.press(BuilderTurnInto.FROM_PREFIX + "logs");
        f.press(BuilderTurnInto.FROM_PREFIX + "sand");
        f.press(BuilderTurnInto.FROM_PREFIX + "sand");   // a second tap takes it off
        assertEquals(List.of(BuilderTurnInto.FROM_PREFIX + "ores", BuilderTurnInto.FROM_PREFIX + "logs"),
                f.page().tiles().stream().filter(PickPage.Tile::selected).map(PickPage.Tile::id).toList());
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.TURN_TO, f.step());
        assertEquals(BuilderTurnInto.TO_TITLE, f.page().title());
        assertEquals(List.of(BuilderTurnInto.TO_PREFIX + "furnace"),
                f.page().tiles().stream().filter(PickPage.Tile::selected).map(PickPage.Tile::id).toList(),
                "ores and logs both smelt: the furnace is marked");
        assertTrue(ids(f.page()).contains(BuilderTurnInto.TO_PREFIX + "diamond"), "no rare-materials limit");
        f.press(BuilderTurnInto.TO_PREFIX + "gold");
        assertEquals(BuilderFlow.Step.TUNE, f.step());
        BuilderSpec spec = f.spec(Map.of("strength", "2"));
        assertEquals("ores,logs", spec.opt(BuilderTurnInto.FROM_SLOT));
        assertEquals("gold", spec.opt(BuilderTurnInto.TO_SLOT));
        assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty(), BuilderAllowlist.REVIEWED.problems(spec).toString());
        var make = BuilderLowering.lower(spec, "x_1", LibraryPresets.SHIPPED).stream()
                .filter(c -> c.tool().equals("make_item")).findFirst().orElseThrow();
        assertEquals("turn_into", make.args().get("effect").getAsString());
        var p = make.args().getAsJsonObject("params");
        assertEquals("gold", p.get("TO").getAsString());
        assertEquals("1", p.get("RADIUS").getAsString(), "strength 2 on a wand: a 3x3x3 patch");
        assertTrue(p.get("FROM").getAsString().contains("minecraft:raw_iron") && p.get("FROM").getAsString().contains("#minecraft:logs"));
        assertEquals("Midas Wand", BuilderLowering.label(spec));
    }

    @Test
    void aGroupThatDoesNotSmeltMarksNothingAndASwordHasNoRawFood() {
        BuilderFlow f = toTurnInto("wand");
        f.press(BuilderTurnInto.FROM_PREFIX + "wool");
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.TURN_TO, f.step());
        assertFalse(BuilderTurnInto.smelts(BuilderTurnInto.from("wool")), "wool has no furnace result");
        assertTrue(f.page().tiles().stream().noneMatch(PickPage.Tile::selected), "no default: he picks");

        BuilderFlow sword = toTurnInto("sword");
        assertFalse(ids(sword.page()).contains(BuilderTurnInto.FROM_PREFIX + "raw_food"), "a sword changes blocks");
        assertEquals(BuilderTurnInto.LORE_HIT, BuilderTurnInto.lore("on_hit"));
        assertNotNull(BuilderTurnInto.from("raw_food"));
    }

    /** Wool on the To page, white wool both ways: set into the world as a block, given as the item. */
    @Test
    void woolIsOnTheToPageWhiteWoolBothWays() {
        BuilderTurnInto.To wool = BuilderTurnInto.to("wool");
        assertNotNull(wool);
        assertEquals("Wool", wool.label());
        assertEquals("minecraft:white_wool", wool.blockId());
        assertEquals("minecraft:white_wool", wool.itemId());
        assertTrue(wool.words().containsAll(List.of("wool", "white wool", "fluffy")), wool.words().toString());
        assertEquals(null, BuilderTurnInto.whyNot("held", List.of(BuilderTurnInto.from("plants")), wool),
                "leaves and flowers into wool, on a wand");
        BuilderFlow f = toTurnInto("wand");
        f.press(BuilderTurnInto.FROM_PREFIX + "plants");
        f.press(PickPage.NEXT_ID);
        assertTrue(ids(f.page()).contains(BuilderTurnInto.TO_PREFIX + "wool"), ids(f.page()).toString());
        f.press(BuilderTurnInto.TO_PREFIX + "wool");
        BuilderSpec spec = f.spec(Map.of("strength", "2"));
        var p = BuilderLowering.lower(spec, "x_1", LibraryPresets.SHIPPED).stream()
                .filter(c -> c.tool().equals("make_item")).findFirst().orElseThrow().args().getAsJsonObject("params");
        assertEquals("minecraft:white_wool", p.get("TO").getAsString());
        assertEquals(null, makeItemSays("held", List.of(BuilderTurnInto.from("plants")), wool), "make_item makes it");
    }

    /**
     * "Any block" never makes the furnace the default — a furnace makes something of only a
     * few blocks, so it would silently ignore the rest. He picks a To. A specific group keeps the furnace default.
     */
    @Test
    void anyBlockMarksNoToAndSpecificGroupsKeepTheFurnace() {
        assertTrue(BuilderTurnInto.smelts(BuilderTurnInto.from("any_block")), "a furnace does make something of some blocks");
        assertFalse(BuilderTurnInto.furnaceIsDefault(List.of(BuilderTurnInto.from("any_block"))));
        assertFalse(BuilderTurnInto.furnaceIsDefault(List.of(BuilderTurnInto.from("any_block"), BuilderTurnInto.from("ores"))));
        assertTrue(BuilderTurnInto.furnaceIsDefault(List.of(BuilderTurnInto.from("ores"))), "ores keep the furnace");
        assertEquals("0", BuilderTurnInto.facts(BuilderTurnInto.from("any_block")).get("furnaceDefault"), "the Apprentice's fact too");
        assertEquals("1", BuilderTurnInto.facts(BuilderTurnInto.from("ores")).get("furnaceDefault"));
        assertEquals(BuilderTurnInto.FROM_SLOT + ":furnaceDefault", BuilderTurnInto.facts(BuilderTurnInto.to("furnace")).get("defaultWhen"));

        BuilderFlow f = toTurnInto("wand");
        f.press(BuilderTurnInto.FROM_PREFIX + "any_block");
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.TURN_TO, f.step());
        assertTrue(f.page().tiles().stream().noneMatch(PickPage.Tile::selected), "any block: no default, he picks");

        BuilderFlow g = toTurnInto("wand");
        g.press(BuilderTurnInto.FROM_PREFIX + "ores");
        g.press(PickPage.NEXT_ID);
        assertEquals(List.of(BuilderTurnInto.TO_PREFIX + "furnace"),
                g.page().tiles().stream().filter(PickPage.Tile::selected).map(PickPage.Tile::id).toList(), "ores: the furnace is marked");
    }

    /**
     * with any block and no To named, the Apprentice takes the next fitting To, which is gold (the Midas wand), never
     * the furnace.
     */
    @Test
    void theApprenticeGivesAnyBlockGoldWhenNoToIsNamed() {
        BuilderTree tree = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        Apprentice a = new Apprentice(tree, Apprentice.english());
        for (String wish : List.of("a magic wand that changes any block", "a block changing staff")) {
            Apprentice.Answer ans = a.match(wish);
            assertFalse(ans.offers().isEmpty(), wish + ": no tiles");
            Apprentice.Pick top = Apprentice.filled(tree, ans.offers().get(0));
            assertEquals("itempower:" + BuilderTurnInto.POWER, top.first("power"), wish + ": " + Apprentice.key(top));
            assertEquals(List.of(BuilderTurnInto.FROM_PREFIX + "any_block"), top.picks().get(BuilderTurnInto.FROM_SLOT), wish + ": " + Apprentice.key(top));
            assertEquals(BuilderTurnInto.TO_PREFIX + "gold", top.first(BuilderTurnInto.TO_SLOT), wish + ": " + Apprentice.key(top));
        }
    }

    /**
     * a change said in full is the power, and another power word ("frozen") is how it looks. "Frozen snowball
     * turn to cobbles white wool" was the freeze power with the change unread; it is the turn-into snowball, in ice.
     */
    @Test
    void aChangeSaidInFullWinsThePowerOverAnAdjective() {
        BuilderTree tree = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        Apprentice a = new Apprentice(tree, Apprentice.english());
        Apprentice.Pick top = Apprentice.filled(tree, a.match("frozen snowball turn to cobbles white wool").offers().get(0));
        assertEquals("itempower:" + BuilderTurnInto.POWER, top.first("power"), Apprentice.key(top));
        assertEquals(BuilderTurnInto.TO_PREFIX + "cobblestone", top.first(BuilderTurnInto.TO_SLOT), Apprentice.key(top));
        assertEquals(List.of(BuilderTurnInto.FROM_PREFIX + "wool"), top.picks().get(BuilderTurnInto.FROM_SLOT), Apprentice.key(top));
        assertEquals("ice", top.first("scheme"), "frozen is its look: " + Apprentice.key(top));
        Apprentice.Pick plain = Apprentice.filled(tree, a.match("a freezing sword").offers().get(0));
        assertEquals("itempower:freeze", plain.first("power"), "with no change said, the power word is the power");
    }
}
