package com.moddingfromamod.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** the 1.21.5 component and text spellings, corrected wherever CommandDialect corrects. */
class ComponentSpellingTest {

    private static String fix(String s) {
        return ComponentSpelling.correct(s, new ArrayList<>());
    }

    @Test
    void dyedColorBecomesANumber() {
        assertEquals("give @s x[minecraft:dyed_color=46335] 1", fix("give @s x[minecraft:dyed_color={rgb:46335}] 1"));
    }

    @Test
    void enchantmentsLoseTheLevelsWrapper() {
        assertEquals("give @s x[minecraft:enchantments={\"minecraft:fortune\":5,\"minecraft:unbreaking\":3}] 1",
                fix("give @s x[minecraft:enchantments={levels:{\"minecraft:fortune\":5,\"minecraft:unbreaking\":3}}] 1"));
        assertEquals("give @s x[stored_enchantments={\"minecraft:mending\":1},minecraft:tooltip_display={hidden_components:[\"minecraft:stored_enchantments\"]}]",
                fix("give @s x[stored_enchantments={levels:{\"minecraft:mending\":1},show_in_tooltip:false}]"));
    }

    @Test
    void modifiersBecomeABareList() {
        assertEquals("give @s x[attribute_modifiers=[{type:\"armor\",id:\"a:b\",amount:0,operation:\"add_value\",slot:\"body\"}],"
                        + "minecraft:tooltip_display={hidden_components:[\"minecraft:attribute_modifiers\"]}]",
                fix("give @s x[attribute_modifiers={modifiers:[{type:\"armor\",id:\"a:b\",amount:0,operation:\"add_value\","
                        + "slot:\"body\"}],show_in_tooltip:false}]"));
    }

    @Test
    void quotedJsonTextIsUnquotedInComponentsAndNbt() {
        assertEquals("give @s x[minecraft:custom_name={\"text\":\"Slushie Machine\",\"bold\":true},"
                        + "minecraft:lore=[{\"text\":\"a\"},{\"text\":\"b, c\"}]] 1",
                fix("give @s x[minecraft:custom_name='{\"text\":\"Slushie Machine\",\"bold\":true}',"
                        + "minecraft:lore=['{\"text\":\"a\"}','{\"text\":\"b, c\"}']] 1"));
        assertEquals("summon zombie ~ ~ ~ {CustomName:{\"text\":\"Bob\"}}", fix("summon zombie ~ ~ ~ {CustomName:'{\"text\":\"Bob\"}'}"));
        assertEquals("setblock ~ ~ ~ oak_sign{front_text:{messages:[\"\",{\"text\":\"ice + sugar\",\"color\":\"dark_gray\"},\"\",\"\"]}}",
                fix("setblock ~ ~ ~ oak_sign{front_text:{messages:['\"\"','{\"text\":\"ice + sugar\",\"color\":\"dark_gray\"}','\"\"','\"\"']}}"));
    }

    @Test
    void theNewSpellingAndPlainQuotedTextAreLeftAlone() {
        String now = "give @s x[dyed_color=5,custom_name={\"text\":\"W\"},lore=[{\"text\":\"a\"}],attribute_modifiers=[],"
                + "enchantments={\"minecraft:sharpness\":2}] 1";
        assertEquals(now, fix(now));
        assertEquals("give @s x[custom_name='Wand'] 1", fix("give @s x[custom_name='Wand'] 1"));
        assertEquals("say hello", fix("say hello"));
    }

    @Test
    void itSaysWhatItChanged() {
        List<String> notes = new ArrayList<>();
        ComponentSpelling.correct("give @s x[dyed_color={rgb:1}]", notes);
        assertTrue(notes.stream().anyMatch(n -> n.contains("dyed_color")), notes.toString());
    }
}
