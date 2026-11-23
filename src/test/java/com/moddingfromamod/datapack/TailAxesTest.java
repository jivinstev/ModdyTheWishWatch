package com.moddingfromamod.datapack;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** YIELD, RECIPIENT and PAYLOAD — every rule of the three that is true without Minecraft. */
class TailAxesTest {

    private static JsonArray arr(String s) {
        return JsonParser.parseString(s).getAsJsonArray();
    }

    private static JsonObject obj(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static final JsonObject SLICES = JsonParser.parseString(
            "{\"name\":\"Pizza Slice\",\"icon\":\"food_round\",\"colour\":\"yellow\",\"count\":8}")
            .getAsJsonObject();

    // ── RECIPIENT ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a selector is built here, never taken from what the agent wrote")
    void aRecipientCannotCarryCommandText() {
        // The whole reason this is a regex and a builder rather than a passthrough. Each of these
        // would end the selector and start something else if it reached the give line.
        for (String evil : List.of("@e[type=pig,nbt={}]", "minecraft:pig] /kill @a", "@a",
                "minecraft:pig,limit=99", "Minecraft:Pig", "pig")) {
            assertFalse(ItemRecipient.refusals(evil).isEmpty(),
                    "'" + evil + "' must be refused");
        }
        assertTrue(ItemRecipient.refusals("minecraft:piglin_brute").isEmpty());
        assertTrue(ItemRecipient.refusals(null).isEmpty(), "absent means he gets it himself");
        assertTrue(ItemRecipient.refusals("me").isEmpty());
    }

    @Test
    @DisplayName("one creature gets it, not every creature of that kind")
    void onlyOneCreatureIsArmed() {
        String sel = ItemRecipient.selector("minecraft:piglin_brute");
        assertTrue(sel.contains("limit=1"), sel);
        assertTrue(sel.contains("sort=nearest"), sel);
        assertTrue(sel.contains("type=minecraft:piglin_brute"), sel);
        assertEquals("@s", ItemRecipient.selector(null), "the default is still the child");
        assertEquals("@s", ItemRecipient.selector("me"));
    }

    @Test
    @DisplayName("he is told when the thing lands in someone else's hands")
    void heIsToldWhenItGoesElsewhere() {
        // Without this the wish looks like it did nothing: the give lands on a creature several
        // blocks away and his own hands stay empty.
        String msg = ItemRecipient.message("minecraft:piglin_brute", "Gold Axe");
        assertTrue(msg != null && msg.contains("piglin brute") && msg.contains("Gold Axe"), msg);
        assertNull(ItemRecipient.message("me", "Gold Axe"),
                "the normal case already has its own message");
    }

    // ── YIELD ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("only a shape with a moment of use can yield")
    void onlyAShapeWithAMomentOfUseCanYield() {
        for (String shape : ItemCatalogue.SHAPES) {
            boolean refused = !ItemYield.refusals(SLICES, shape, false).isEmpty();
            assertEquals(!ItemYield.SHAPES.contains(shape), refused,
                    shape + " is on the wrong side of the yield rule");
        }
    }

    @Test
    @DisplayName("a yield and a family are refused together, not silently made imprecise")
    void aYieldAndAFamilyAreRefusedTogether() {
        // The clear matches the behaviour tag, which every member of a family shares — so using one
        // member would consume whichever came to hand.
        assertFalse(ItemYield.refusals(SLICES, "held", true).isEmpty());
        assertTrue(ItemYield.refusals(SLICES, "held", false).isEmpty());
    }

    @Test
    @DisplayName("what he ends up with has to be recognisable, so every field is required")
    void everyFieldOfAYieldIsRequired() {
        for (String drop : List.of("name", "icon", "colour", "count")) {
            JsonObject y = SLICES.deepCopy();
            y.remove(drop);
            assertFalse(ItemYield.refusals(y, "held", false).isEmpty(),
                    "a yield with no " + drop + " must be refused");
        }
        JsonObject badIcon = SLICES.deepCopy();
        badIcon.addProperty("icon", "not_an_icon_at_all");
        assertFalse(ItemYield.refusals(badIcon, "held", false).isEmpty(),
                "an icon that does not exist ships the missing-texture cube");
    }

    @Test
    @DisplayName("the clear names the ONE item, through its behaviour tag")
    void theClearNamesOneItem() {
        String p = ItemYield.clearPredicate("held", "pizza_food", "WishOnUse:\"pizza_food:act\"",
                ItemBase.WISH_ITEM);
        assertTrue(p.contains("pizza_food"), p);
        // A partial match, and it has to be: `=` would demand the whole custom_data object be
        // equal, which fails the moment anything else is written into it.
        assertTrue(p.contains("~"), "the component match must be partial: " + p);
    }

    // ── PAYLOAD ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a part is a thing, and cannot be command text")
    void aPartCannotCarryCommandText() {
        for (String evil : List.of("\"}]", "a{b}", "x,y=z", "]/kill @a")) {
            assertFalse(ItemParts.refusals(arr("[\"" + evil.replace("\"", "\\\"") + "\"]")).isEmpty(),
                    "'" + evil + "' must be refused");
        }
        assertTrue(ItemParts.refusals(arr("[\"sprinkles\",\"chocolate sauce\"]")).isEmpty());
        assertTrue(ItemParts.refusals(null).isEmpty());
    }

    @Test
    @DisplayName("too many parts is refused rather than trimmed")
    void tooManyPartsIsRefused() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i <= ItemParts.MAX; i++) {
            b.append(i > 0 ? "," : "").append("\"part").append(i).append('"');
        }
        assertFalse(ItemParts.refusals(arr(b.append(']').toString())).isEmpty());
    }

    @Test
    @DisplayName("the parts are written the way he would say them")
    void thePartsReadLikeASentence() {
        // This line is the only place the parts exist for him, so it has to read.
        assertEquals("With sprinkles", ItemParts.loreLine(arr("[\"sprinkles\"]")));
        assertEquals("With sprinkles and chocolate sauce",
                ItemParts.loreLine(arr("[\"sprinkles\",\"chocolate sauce\"]")));
        assertEquals("With sprinkles, chocolate sauce and a flake",
                ItemParts.loreLine(arr("[\"sprinkles\",\"chocolate sauce\",\"a flake\"]")));
        assertNull(ItemParts.loreLine(arr("[]")), "no parts means no extra line at all");
    }

    // ── BASE ITEM ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a base item cannot carry command text, and absent still means our own item")
    void aBaseItemIsAnIdAndNothingElse() {
        for (String evil : List.of("minecraft:stone] /kill @a", "Minecraft:Stone", "stone",
                "minecraft:stone[foo=1]", "minecraft:stone,minecraft:dirt")) {
            assertFalse(ItemBase.refusals(evil).isEmpty(), "'" + evil + "' must be refused");
        }
        assertTrue(ItemBase.refusals("minecraft:golden_axe").isEmpty());
        assertTrue(ItemBase.refusals(null).isEmpty());
        // "OUR OWN ITEM", asked route-independently. ItemBase.id is the one place that resolves
        // what our own item currently IS, so pinning the literal here would pin the ROUTE and
        // would have to be edited every time the default moves — while asserting nothing extra.
        // VanillaCarrierTest is what pins which id each route actually yields.
        assertEquals(VanillaCarrier.itemId(), ItemBase.id(null));
        assertEquals(VanillaCarrier.itemId(), ItemBase.id(""));
        assertEquals("minecraft:golden_axe", ItemBase.id("minecraft:golden_axe"));
        assertTrue(ItemBase.isWishItem(null));
        assertFalse(ItemBase.isWishItem("minecraft:golden_axe"));
    }

    @Test
    @DisplayName("unbreakable is a property like any other, and asking for 0 writes nothing")
    void unbreakableIsJustAProperty() {
        // It was written up as a fork between a max_damage component and handing out a real item.
        // Neither: it is one property that works wherever there IS durability to protect, and what
        // was missing was the ability to name WHICH item.
        assertTrue(ItemProperties.render(obj("{\"unbreakable\":\"1\"}")).contains("unbreakable"));
        assertEquals("", ItemProperties.render(obj("{\"unbreakable\":\"0\"}")),
                "not asking for it must write nothing, not a falsey component");
        assertFalse(ItemProperties.refusals(obj("{\"unbreakable\":\"2\"}")).isEmpty());
        // And it must not leave a dangling comma when it renders nothing beside something that does.
        String both = ItemProperties.render(obj("{\"unbreakable\":\"0\",\"max_stack_size\":\"1\"}"));
        assertFalse(both.contains(",,"), both);
        assertTrue(both.startsWith(",minecraft:max_stack_size"), both);
    }

    @Test
    @DisplayName("a yield names the item the give handed out, whatever that was")
    void theYieldClearFollowsTheBaseItem() {
        // A predicate saying wish_item while the give handed out a golden axe matches nothing, so
        // the axe is never consumed and he keeps both — silently, since a clear that removes
        // nothing is not an error.
        String p = ItemYield.clearPredicate("held", "x", "WishOnUse:\"x:act\"",
                "minecraft:golden_axe");
        assertTrue(p.startsWith("minecraft:golden_axe["), p);
        assertTrue(ItemYield.clearPredicate("held", "x", "WishOnUse:\"x:act\"", null)
                .startsWith(ItemBase.id(null) + "["));
    }

    @Test
    @DisplayName("an unknown yield field is not silently ignored")
    void theCorpusFormIsTheShippedForm() {
        // The pre-registration wrote `{consumes_self:true}`, which names nothing he ends up with.
        assertFalse(ItemYield.refusals(obj("{\"consumes_self\":true}"), "held", false).isEmpty(),
                "a yield that does not say what he gets must be refused");
    }
}
