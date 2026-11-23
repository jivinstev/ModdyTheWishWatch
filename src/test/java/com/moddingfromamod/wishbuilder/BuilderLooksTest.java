package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.ui.PickPage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>A made-up item's look, from the icon library</b>: every drawn icon is offered behind "🎨 More looks…" with
 * 3+ words, picks the item it goes on, and lowers to the same {@code make_item} icon the Magician passes; with no power
 * it is a plain keepsake drawn as its look (a crown to wear needs). {@code BuilderLooksGameTest} makes one.
 */
class BuilderLooksTest {

    @Test
    void everyDrawnIconIsALookWithThreeWordsAnIconMakeItemKnowsAndAStockItem() {
        List<BuilderLooks.Look> all = BuilderLooks.all();
        assertEquals(com.moddingfromamod.item.IconSprites.drawn().size(), all.size(), "every drawn icon is offered");
        List<String> wrong = new ArrayList<>();
        for (BuilderLooks.Look l : all) {
            if (l.words().size() < 3) {
                wrong.add(l.id() + " has " + l.words());
            }
            if (!ItemCatalogue.ICONS.containsKey(l.id())) {
                wrong.add(l.id() + " is not an icon make_item takes");
            }
            if (BuilderItemPowers.stock(l.stock()) == null) {
                wrong.add(l.id() + " goes on no stock item: " + l.stock());
            }
        }
        assertEquals(List.of(), wrong);
        for (BuilderLooks.Group g : BuilderLooks.groups()) {
            assertTrue(g.looks().size() < PickPage.MAX_TILES, g.id() + " fits one page");
        }
        assertEquals("helmet", BuilderLooks.look("crown").stock());
        assertEquals("snack", BuilderLooks.look("cupcake").stock());
        assertEquals("drink", BuilderLooks.look("smoothie").stock());
        assertEquals("chestplate", BuilderLooks.look("cape").stock());
        assertEquals("wand", BuilderLooks.look("teddy_bear").stock());
    }

    static BuilderFlow toLook(String look) {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("item");
        assertTrue(BuilderTurnIntoTest.ids(f.page()).contains(BuilderLooks.MORE_ID), "What is it? has More looks…");
        f.press(BuilderLooks.MORE_ID);
        assertEquals(BuilderFlow.Step.ITEM_LOOK_GROUP, f.step());
        BuilderLooks.Look l = BuilderLooks.look(look);
        f.press(BuilderLooks.GROUP_PREFIX + l.group());
        assertEquals(BuilderFlow.Step.ITEM_LOOK, f.step());
        f.press(BuilderLooks.PREFIX + look);
        assertEquals(BuilderFlow.Step.ITEM_POWER, f.step());
        return f;
    }

    static JsonObject makeItem(BuilderFlow f) {
        BuilderSpec spec = f.spec(f.values(""));
        for (BuilderLowering.Call c : BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED)) {
            if (c.tool().equals("make_item")) {
                return c.args();
            }
        }
        throw new AssertionError("no make_item call for " + spec);
    }

    @Test
    void aCrownForFunIsAWornLeatherHelmetDrawnAsTheCrown() {
        BuilderFlow f = toLook("crown");
        assertTrue(BuilderTurnIntoTest.ids(f.page()).contains("itempower:" + BuilderLooks.SHOW), "Just for fun is offered");
        f.press("itempower:" + BuilderLooks.SHOW);
        assertEquals(BuilderFlow.Step.TUNE, f.step());
        JsonObject a = makeItem(f);
        assertEquals("crown", a.get("icon").getAsString());
        assertEquals(ItemCatalogue.PLAIN, a.get("shape").getAsString());
        assertEquals("minecraft:leather_helmet", a.get("base_item").getAsString(), "#2330: a crown he WEARS");
        assertTrue(a.get("look").getAsBoolean(), "#2330: and drawn as the crown, not as the leather helmet");
        assertFalse(a.has("effect"), "a plain item has no effect");
        assertEquals("Crown", a.get("name").getAsString());
        assertEquals("#FFD54A", a.get("colour").getAsString(), "#2337: a gold crown, in its look's own colour");
    }

    @Test
    void aWizardHatThatMakesMeFastIsAWornSpeedHelmetWithTheWizardHatIcon() {
        BuilderFlow f = toLook("wizard_hat");
        f.press("itempower:speed");
        JsonObject a = makeItem(f);
        assertEquals("wizard_hat", a.get("icon").getAsString());
        assertEquals("worn_head", a.get("shape").getAsString());
        assertEquals("speed", a.get("effect").getAsString());
        assertEquals("Speedy Wizard Hat", a.get("name").getAsString());
        // Back walks back through the looks, and a stock item of its own drops the look
        f = toLook("wizard_hat");
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.ITEM_LOOK, f.step());
        f.press(PickPage.BACK_ID);
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.SHAPE, f.step());
        f.press(BuilderFlow.STOCK_PREFIX + "sword");
        f.press("itempower:lightning");
        JsonObject s = makeItem(f);
        assertEquals("sword", s.get("icon").getAsString(), "a stock item of its own wears its own icon");
    }

    @Test
    void anEditKeepsTheLook() {
        BuilderFlow f = toLook("cupcake");
        f.press("itempower:" + BuilderLooks.SHOW);
        BuilderSpec spec = f.spec(f.values(""));
        assertEquals("cupcake", spec.opt(BuilderLooks.SLOT));
        BuilderFlow e = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "x", spec);
        assertNotNull(e);
        assertEquals("cupcake", makeItem(walkToTune(e)).get("icon").getAsString());
    }

    /** Walk an editing flow to its tune step the way the editor does: the same press on every page. */
    static BuilderFlow walkToTune(BuilderFlow f) {
        for (int i = 0; i < 12 && f.step() != BuilderFlow.Step.TUNE; i++) {
            String keep = f.keepPress();
            f.press(keep == null ? PickPage.NEXT_ID : keep);
        }
        return f;
    }

    @Test
    void aCupcakeIsACookieHeEatsDrawnAsTheCupcakeWithOrWithoutAPower() {
        BuilderFlow f = toLook("cupcake");
        f.press("itempower:" + BuilderLooks.SHOW);
        JsonObject a = makeItem(f);
        assertEquals("minecraft:cookie", a.get("base_item").getAsString());
        assertTrue(a.get("look").getAsBoolean());
        BuilderFlow g = toLook("cupcake");
        g.press("itempower:speed");
        JsonObject b = makeItem(g);
        assertEquals("cupcake", b.get("icon").getAsString());
        assertEquals("on_consume", b.get("shape").getAsString());
        assertTrue(b.has("look") && b.get("look").getAsBoolean(), "#2330: a snack with a power is drawn as its look too");
        // A keepsake held in the hand needs no real base: the carrier wears the look by itself
        BuilderFlow t = toLook("teddy_bear");
        t.press("itempower:" + BuilderLooks.SHOW);
        assertFalse(makeItem(t).has("base_item"));
    }

    /** every look's tile is its own drawn icon, and a tinted one is drawn in a colour (not its grey base). */
    @Test
    void everyLookTileIsItsOwnIconInAColour() {
        List<String> wrong = new ArrayList<>();
        for (var icon : com.moddingfromamod.item.IconSprites.drawn()) {
            BuilderLooks.Look l = BuilderLooks.look(icon.name());
            var sprite = com.moddingfromamod.marshal.WatchIcons.spriteOf(l.tile());
            if (sprite == null || !sprite.name().equals(icon.name())) {
                wrong.add(icon.name() + " tile " + l.tile());
            }
            if (icon.tints() && !BuilderLooks.loadColours().containsKey(icon.name())) {
                wrong.add(icon.name() + " is tinted and has no tile colour (items/icon-tile-colours.json)");
            }
        }
        assertEquals(List.of(), wrong);
        assertEquals("wish:crown#FFD54A", BuilderLooks.look("crown").tile());
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("item");
        f.press(BuilderLooks.MORE_ID);
        f.press(BuilderLooks.GROUP_PREFIX + BuilderLooks.look("cupcake").group());
        assertTrue(f.page().tiles().stream().anyMatch(t -> t.id().equals("look:cupcake") && t.item().startsWith("wish:cupcake#")),
                "the cupcake tile is the cupcake");
    }

    /**
     * every look, with two of its powers, lowers to a make_item the real-item hint lets through. A
     * "Cookie Jar" and a "Fishing Rod" look were refused ("'Cookie Jar' is a cookie…") and the tile failed in front of
     * the player; the Builder has chosen the base, so a name naming a real item is a look ({@code only_looks_like}).
     */
    @Test
    void noLookIsRefusedForANameThatIsARealItem() {
        List<String> refused = new ArrayList<>();
        int made = 0;
        for (BuilderLooks.Look l : BuilderLooks.all()) {
            List<String> powers = new ArrayList<>();
            for (String id : BuilderTurnIntoTest.ids(toLook(l.id()).page())) {
                if (id.startsWith("itempower:")) {
                    powers.add(id);
                }
            }
            assertFalse(powers.isEmpty(), l.id() + " offers a power");
            // The base comes from the look's stock and the name is "<power word> <look>": the first power and one
            // more cover both shapes of name; every power for every look cost 11 s of Gate A.
            List<String> two = new ArrayList<>(List.of(powers.get(0)));
            powers.stream().filter(p -> !p.equals(powers.get(0))).findFirst().ifPresent(two::add);
            for (String power : two) {
                BuilderFlow f = toLook(l.id());
                f.press(power);
                if (f.step() != BuilderFlow.Step.TUNE) {
                    continue;   // a power with a page of its own (what it turns into): its own tests walk it
                }
                JsonObject a = makeItem(f);
                made++;
                String base = a.has("base_item") ? a.get("base_item").getAsString() : null;
                boolean only = a.has("only_looks_like") && a.get("only_looks_like").getAsBoolean();
                String shape = a.has("shape") ? a.get("shape").getAsString() : null;
                com.moddingfromamod.item.RealItemHint.of(a.get("name").getAsString(), base, false, only, shape)
                        .ifPresent(r -> refused.add(l.id() + " + " + power + " -> '" + a.get("name").getAsString()
                                + "' is a " + r.word()));
            }
        }
        assertTrue(made > BuilderLooks.all().size(), "walked every look's powers: " + made);
        assertEquals(List.of(), refused);
        JsonObject jar = makeItem(pressed(toLook("cookie_jar"), "itempower:" + BuilderLooks.SHOW));
        assertEquals("Cookie Jar", jar.get("name").getAsString());
        assertTrue(jar.get("only_looks_like").getAsBoolean(), "a cookie jar only looks like a cookie");
        JsonObject rod = makeItem(pressed(toLook("fishing_rod"), "itempower:" + BuilderLooks.SHOW));
        assertTrue(rod.get("only_looks_like").getAsBoolean(), "a fishing rod look is tapped, like every wand look");
        JsonObject cupcake = makeItem(pressed(toLook("cupcake"), "itempower:" + BuilderLooks.SHOW));
        assertFalse(cupcake.has("only_looks_like"), "a look built on the real thing needs no pass");
    }

    private static BuilderFlow pressed(BuilderFlow f, String id) {
        f.press(id);
        return f;
    }
}
