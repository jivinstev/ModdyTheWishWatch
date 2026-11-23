package com.moddingfromamod.datapack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the side door, {@link DatapackItemGives}, on hand-written packs. The same check over the real packs an
 * archived pass made, where every flag must be caught and every pack that worked must pass, is
 * {@code DatapackItemGivesArchiveTest}, beside the archive.
 */
class DatapackItemGivesTest {

    @Test
    void aMadeTridentIsFlaggedAndTheRealOneIsNot() {
        String made = "/give @s moddingfromamod:wish_item[minecraft:custom_name='{\"text\":\"Storm Trident\"}'] 1";
        assertEquals("trident", DatapackItemGives.scan(made).get(0).real().word());
        assertTrue(DatapackItemGives.scan(
                "/give @s minecraft:trident[minecraft:custom_name='{\"text\":\"Storm Trident\"}'] 1").isEmpty());
    }

    @Test
    void aPearlDressedAsOneOrThatTeleportsIsAnEnderPearl() {
        assertEquals("ender pearl", DatapackItemGives.scan(
                "give @p carrot_on_a_stick[item_name='\"Home Orb\"',item_model=\"minecraft:ender_pearl\"]")
                .get(0).real().word());
        String teleports = "give @p carrot_on_a_stick[item_name='\"Home Pearl\"']\ntp @s 0 64 0";
        assertEquals("ender pearl", DatapackItemGives.scan(teleports).get(0).real().word());
        assertTrue(DatapackItemGives.scan("give @p carrot_on_a_stick[item_name='\"Pearl Necklace\"']")
                .isEmpty(), "a pearl that does not teleport is jewellery");
    }

    @Test
    void anEffectGiveIsNotAnItem() {
        assertTrue(DatapackItemGives.scan("effect give @s minecraft:speed 30 1 true").isEmpty());
        assertTrue(DatapackItemGives.scan("execute as @a run effect give @s minecraft:speed 3").isEmpty());
    }

}
