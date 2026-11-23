package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * which values ride through the CBScript compiler as text (a slot) and which must stay in the skeleton (a
 * literal), and that a skeleton always fills back to the render it stands for. The compiler's own rewrites each have a
 * case: a selector with arguments is rebuilt, {@code $} is a macro, {@code nbt=}/attribute ids/old component spellings
 * are respelt, a single-quoted name or lore line is unquoted, a {@code /command} line is stripped at its ends.
 */
class ReadyMadeSkeletonTest {

    private static boolean ok(String name, String value) {
        return ReadyMadeSkeleton.accepts(ReadyMadeSkeleton.kindOf(name), value);
    }

    @Test
    void plainValuesAreSlotsAndRewrittenOnesAreNot() {
        assertTrue(ok("TAG", "spikor_2"));
        assertFalse(ok("TAG", "Spikor"), "a tag is lowercase");
        assertTrue(ok("RADIUS", "4.5"));
        assertTrue(ok("PARTICLE", "minecraft:flame"));
        assertFalse(ok("BLOCK", "minecraft:snow[layers=2]"), "block states are not a resource id");
        assertTrue(ok("COMMAND", "effect give @s minecraft:speed 3 1"));
        assertTrue(ok("COMMAND", "data modify entity @s Fire set value 100s"));
        assertFalse(ok("COMMAND", "damage @s[type=#moddingfromamod:hostile] 2"), "a selector with arguments is rebuilt");
        assertFalse(ok("COMMAND", "say $(x)"), "a $ is a macro");
        assertFalse(ok("COMMAND", "execute if entity @s[nbt={OnGround:1b}] run say hi"));
        assertFalse(ok("COMMAND", "attribute @s scale base set 2"), "attribute ids are respelt");
        assertFalse(ok("COMMAND", "effect give @s speed "), "a /command line is stripped at its ends");
        assertTrue(ok("NAME", "Lightning Sword 2"));
        assertFalse(ok("NAME", "Bob's Sword"), "a single quote is unquoted inside lore and names");
        assertFalse(ok("NAME", "zq sneaky"), "a value with a sentinel's letters could be put back as something else");
        assertFalse(ok("VICTIMS", ",type=!minecraft:player"), "a selector clause is never a slot");
        assertTrue(ok("HEX", "#FF8800"));
    }

    @Test
    void commandLinesAreABlockOnlyWhenTheCompilerCopiesThemThrough() {
        assertTrue(ok("GIVES", "    /give @s minecraft:cookie[minecraft:item_model='wishpack:icon/x',"
                + "minecraft:lore=[{\"text\":\"Its yours\"}]] 1"));
        assertFalse(ok("GIVES", "    /give @s minecraft:stick[minecraft:custom_name='{\"text\":\"x\"}'] 1"));
        assertFalse(ok("GIVES", "    /give @s minecraft:stick[minecraft:lore=['{\"text\":\"x\"}']] 1"));
        assertFalse(ok("GIVES", "    /give @s minecraft:stick[minecraft:dyed_color={rgb:255}] 1"));
        assertFalse(ok("GIVES", "    /give @a[distance=..3] minecraft:stick 1"));
        assertFalse(ok("GIVES", "    give @s minecraft:stick 1"), "every line is a /command");
    }

    @Test
    void aSkeletonFillsBackToItsRenderAndBlocksBecomeCompiledLines() {
        String template = "reset\n${GIVES}\nend\nclock tick\n    /execute as @e[tag=${TAG}] run ${COMMAND}\nend\n";
        Map<String, String> v = new LinkedHashMap<>();
        v.put("TAG", "spikor");
        v.put("COMMAND", "effect give @s minecraft:speed 3 1");
        v.put("GIVES", "    /give @s minecraft:stick 1\n    /give @s minecraft:apple 2");
        ReadyMadeSkeleton.Composer c = new ReadyMadeSkeleton.Composer().scan(template, v, "");
        ReadyMadeSkeleton.Skeleton sk = c.skeleton(c.render(template, v, "", null));
        assertTrue(sk.source().contains("\n    /zqgivesqz\n"), sk.source());
        assertTrue(sk.source().contains("@e[tag=zqtagqz] run zqcommandqz"), sk.source());
        String filled = template.replace("${TAG}", v.get("TAG")).replace("${COMMAND}", v.get("COMMAND"))
                .replace("${GIVES}", v.get("GIVES"));
        assertEquals(filled, sk.fill());
        assertEquals("give @s minecraft:stick 1\ngive @s minecraft:apple 2", sk.outputValues().get("zqgivesqz"));
        assertEquals(Set.of("zqgivesqz"), sk.blocks());
    }

    @Test
    void identityValuesLeaveALiteralButNeverRightAfterType() {
        String template = "/execute as ${VICTIMS} run say ${TAG}\n";
        Map<String, String> v = Map.of("TAG", "zombie_king", "LOOK", "minecraft:zombie",
                "VICTIMS", "@e[tag=zombie_king,type=minecraft:zombie,nbt={Item:{id:\"minecraft:zombie\"}}]");
        ReadyMadeSkeleton.Composer c = new ReadyMadeSkeleton.Composer().scan(template, v, "")
                .slot("", "LOOK", v.get("LOOK"));
        ReadyMadeSkeleton.Skeleton sk = c.skeleton(c.render(template, v, "", null));
        assertTrue(sk.source().contains("tag=zqtagqz,type=minecraft:zombie,nbt={Item:{id:\"zqlookqz\"}}"),
                "the tag and the look are taken out, but a type= keeps its value: " + sk.source());
        assertEquals(template.replace("${VICTIMS}", v.get("VICTIMS")).replace("${TAG}", "zombie_king"), sk.fill());
    }
}
