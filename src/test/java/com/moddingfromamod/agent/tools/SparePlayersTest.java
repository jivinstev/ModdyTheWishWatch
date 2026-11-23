package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import com.moddingfromamod.datapack.ItemCatalogue;
import org.junit.jupiter.api.Test;

/** spare_players keeps a ranged power's target, and its lightning, off every player. */
class SparePlayersTest {

    @Test
    void everyRangedTargetSelectorSkipsPlayers() {
        for (String shape : new String[] {"arrow", "on_throw_land", "on_shot"}) {
            String sel = SparePlayers.spare(ItemCatalogue.targetFor(shape, "target", 6));
            assertTrue(sel.contains("type=!minecraft:player"), shape + ": " + sel);
            assertTrue(sel.startsWith("@e["), sel);
        }
    }

    @Test
    void himselfIsLeftAlone() {
        assertEquals("@s", SparePlayers.spare(ItemCatalogue.targetFor("arrow", "self", 6)));
    }

    @Test
    void lightningHoldsOffWhileAPlayerIsNear() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("BOLTS", "    /summon minecraft:lightning_bolt ~ ~ ~\n    /summon minecraft:lightning_bolt ~ ~ ~");
        SparePlayers.apply(a);
        assertEquals("    /execute unless entity @a[distance=..4] run summon minecraft:lightning_bolt ~ ~ ~\n"
                + "    /execute unless entity @a[distance=..4] run summon minecraft:lightning_bolt ~ ~ ~", a.get("BOLTS"));
    }

    @Test
    void anAlreadySparedSelectorIsUnchanged() {
        String s = "@e[distance=..3.5,type=!minecraft:player]";
        assertEquals(s, SparePlayers.spare(s));
    }

    /**
     * A positive type is never a player, and the server refuses a second type= beside it (it then switches the whole
     * datapack off). Negated types combine, so a negated one still gets the player left out.
     */
    @Test
    void aSelectorThatNamesAPositiveTypeIsLeftAlone() {
        assertEquals("@e[type=minecraft:zombie,distance=..6]", SparePlayers.spare("@e[type=minecraft:zombie,distance=..6]"));
        assertEquals("@e[distance=..6,type=minecraft:zombie]", SparePlayers.spare("@e[distance=..6,type=minecraft:zombie]"));
        assertEquals("@e[distance=..6,type=#moddingfromamod:hostile]", SparePlayers.spare("@e[distance=..6,type=#moddingfromamod:hostile]"),
                "an entity-type TAG is a positive type too");
    }

    @Test
    void aNegatedTypeATagAndAPlainSelectorStillLeaveThePlayerOut() {
        assertEquals("@e[type=!minecraft:player,distance=..6,type=!minecraft:cow]",
                SparePlayers.spare("@e[distance=..6,type=!minecraft:cow]"));
        assertEquals("@e[type=!minecraft:player,tag=gt_target,distance=..6]", SparePlayers.spare("@e[tag=gt_target,distance=..6]"),
                "a tag= is not a type");
        assertEquals("@e[type=!minecraft:player]", SparePlayers.spare("@e"));
        assertEquals("@e[type=!minecraft:player,distance=..4]", SparePlayers.spare("@e[distance=..4]"));
    }
}
