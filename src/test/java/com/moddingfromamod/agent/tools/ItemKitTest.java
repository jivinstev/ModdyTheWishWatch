package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.RegistryExists;
import org.junit.jupiter.api.Test;

/** a thing's supplies are its KIT — how `make_item` reads one and keeps it on the design. */
class ItemKitTest {

    @Test
    void aKitBecomesOneGiveLinePerThing() {
        String lines = MakeItemTool.kitLines("minecraft:melon 4, minecraft:cocoa_beans x8, "
                + "minecraft:sugar", RegistryExists.ALWAYS);
        assertEquals(3, lines.split("\n").length - 1, lines);
        assertTrue(lines.contains("minecraft:melon 4"), lines);
        assertTrue(lines.contains("minecraft:cocoa_beans 8"), lines);
        assertTrue(lines.contains("minecraft:sugar 1"), "no count is one: " + lines);
    }

    @Test
    void aKitIsBoundedAndEmptyIsNothing() {
        assertTrue(MakeItemTool.kitLines("minecraft:melon 65", RegistryExists.ALWAYS)
                .startsWith("!"), "more than a stack is refused");
        assertTrue(MakeItemTool.kitLines("a 1, b 1, c 1, d 1, e 1, f 1, g 1", RegistryExists.ALWAYS)
                .startsWith("!"), "a starter kit, not a shop");
        assertEquals("", MakeItemTool.kitLines("  ", RegistryExists.ALWAYS));
    }

    @Test
    void theKitIsKeptOnTheDesignSoAnEditKeepsIt() {
        JsonObject args = JsonParser.parseString(
                "{\"name\":\"Bar\",\"kit\":\"minecraft:melon 4\",\"params\":{\"X\":\"1\"}}")
                .getAsJsonObject();
        JsonObject folded = MakeItemTool.foldKit(args);
        assertTrue(!folded.has("kit"));
        assertEquals("minecraft:melon 4", folded.getAsJsonObject("params")
                .getAsJsonObject(MakeItemTool.KIT_KEY).get("items").getAsString());
        assertEquals("1", folded.getAsJsonObject("params").get("X").getAsString(),
                "the effect's own parameters are untouched");
        JsonObject off = MakeItemTool.foldKit(JsonParser.parseString("{\"kit\":\"none\"}")
                .getAsJsonObject());
        assertTrue(off.getAsJsonObject("params").get(MakeItemTool.KIT_KEY).isJsonNull(),
                "\"none\" takes it off at the merge, like any parameter sent as null");
    }
}
