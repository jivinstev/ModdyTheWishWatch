package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** a sure change on a Magician item is an in-place make_item edit, or nothing (it goes on to the Magician). */
class MagicianItemTest {

    private static final MagicianItem.Thing ZOOMER = new MagicianItem.Thing("zoomer", "Zoomer", "");
    private static final MagicianItem.Thing SWORD = new MagicianItem.Thing("golden_sword_x", "Golden Sword", "minecraft:iron_sword");

    private static JsonObject made(List<BuilderLowering.Call> calls) {
        assertNotNull(calls);
        assertEquals(List.of("plan_wish", "make_item", "finish_building"), calls.stream().map(BuilderLowering.Call::tool).toList());
        return calls.get(1).args();
    }

    @Test
    @DisplayName("its colours: an edit of the same item (start_from), never a new one")
    void coloursEditInPlace() {
        JsonObject a = made(MagicianItem.calls(ZOOMER, List.of(BuilderEdit.Op.set("scheme", "gold"))));
        assertEquals("zoomer", a.get("start_from").getAsString());
        Schemes.Scheme gold = Schemes.byId("gold");
        assertEquals(gold.main(), a.get("colour").getAsString());
        assertEquals(gold.accent(), a.get("accent").getAsString());
        assertFalse(a.has("as_new"), "an edit replaces the design: nothing new is handed over");
        assertFalse(a.has("count"));
        assertFalse(a.has("name"), "its name is inherited, never re-sent");
        assertFalse(a.has("effect"), "its power is inherited: the Magician wrote it");
    }

    @Test
    @DisplayName("gold on a real iron tool is that tool in gold; any other colour is drawn tinted")
    void realBaseItem() {
        JsonObject g = made(MagicianItem.calls(SWORD, List.of(BuilderEdit.Op.set("scheme", "gold"))));
        assertEquals("minecraft:golden_sword", g.get("base_item").getAsString());
        JsonObject r = made(MagicianItem.calls(SWORD, List.of(BuilderEdit.Op.set("scheme", "ocean"))));
        assertTrue(r.get("tint").getAsBoolean());
        assertFalse(r.has("base_item"));
    }

    @Test
    @DisplayName("its look: the library icon in the look's own colour")
    void look() {
        BuilderLooks.Look any = BuilderLooks.groups().get(0).looks().get(0);
        JsonObject a = made(MagicianItem.calls(ZOOMER, List.of(BuilderEdit.Op.set(BuilderLooks.SLOT, any.id()))));
        assertEquals(any.id(), a.get("icon").getAsString());
        assertEquals(String.format("#%06X", BuilderLooks.tileColour(any.id())), a.get("colour").getAsString());
    }

    @Test
    @DisplayName("its power, strength, a removal or an unknown colour: nothing here, the Magician's")
    void theRestIsTheMagicians() {
        assertNull(MagicianItem.calls(SWORD, List.of(BuilderEdit.Op.step("strength", 1))));
        assertNull(MagicianItem.calls(SWORD, List.of(BuilderEdit.Op.set("power", "power:lightning"))));
        assertNull(MagicianItem.calls(SWORD, List.of(BuilderEdit.Op.set("scheme", "gold"), BuilderEdit.Op.step("strength", 1))),
                "never half of it");
        assertNull(MagicianItem.calls(SWORD, List.of(BuilderEdit.Op.remove("scheme", "gold"))));
        assertNull(MagicianItem.calls(SWORD, List.of(BuilderEdit.Op.set("scheme", "no_such_colours"))));
        assertNull(MagicianItem.calls(SWORD, List.of()));
    }
}
