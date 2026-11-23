package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * a wish never makes a command block, structure block, jigsaw or command-block minecart — on any world,
 * and whatever {@code enable-command-block} says. Both sides: every shape that names one is refused through
 * {@link CommandGuard} (the check every command path asks), and ordinary blocks, chests, signs, text that merely
 * mentions the word, and {@code place jigsaw} (the structure generator) are untouched.
 */
class ForbiddenBlocksTest {

    static final String[] REFUSED = {
            "setblock ~ ~1 ~ command_block{Command:\"say hi\",auto:1b}",
            "setblock ~ ~1 ~ minecraft:command_block",
            "setblock ~ ~1 ~ minecraft:repeating_command_block[facing=up]{auto:1b}",
            "setblock 10 64 10 chain_command_block",
            "fill ~ ~ ~ ~2 ~2 ~2 minecraft:command_block",
            "fill ~ ~ ~ ~2 ~2 ~2 structure_block",
            "fill ~ ~ ~ ~2 ~2 ~2 minecraft:jigsaw",
            "clone ~ ~ ~ ~2 ~2 ~2 ~5 ~ ~5 filtered minecraft:command_block",
            "give @s command_block",
            "give @s minecraft:chain_command_block 3",
            "give @s minecraft:structure_block",
            "give @s jigsaw",
            "item replace entity @s weapon.mainhand with command_block",
            "item replace block ~ ~ ~ container.0 with minecraft:repeating_command_block",
            "summon command_block_minecart ~ ~1 ~",
            "summon minecraft:command_block_minecart ~ ~ ~ {Command:\"op @a\"}",
            "summon falling_block ~ ~2 ~ {BlockState:{Name:\"minecraft:command_block\"}}",
            "data merge block ~ ~-1 ~ {Command:\"op @a\"}",
            "data modify block 1 2 3 Command set value \"op @a\"",
            "data merge block ~ ~ ~ {auto:1b}",
            "execute as @s at @s run setblock ~ ~1 ~ command_block",
            "execute positioned ~ ~ ~ run execute run give @s command_block",
            "setblock ~ ~ ~ minecraft:test_instance_block",
    };

    static final String[] KEPT = {
            "setblock ~ ~1 ~ stone",
            "fill ~ ~ ~ ~4 ~4 ~4 oak_planks hollow",
            "setblock ~ ~1 ~ chest{Items:[{Slot:0b,id:\"minecraft:apple\",count:3}]}",
            "setblock ~ ~1 ~ oak_sign{front_text:{messages:[\"hi\",\"\",\"\",\"\"]}}",
            "give @s diamond 5",
            "say I love jigsaw puzzles and command blocks",
            "tellraw @s {\"text\":\"a structure_block is a thing\"}",
            "title @s title \"command_block\"",
            "execute as @s run say jigsaw",
            "place jigsaw minecraft:village/plains/town_centers minecraft:bottom 7 ~ ~ ~",
            "place structure minecraft:village_plains",
            "data merge block ~ ~ ~ {CustomName:\"chest\"}",
            "data merge entity @e[tag=pet,limit=1] {auto:1b}",
            "setblock ~ ~ ~ mymod:command_block",
            "summon minecart ~ ~ ~",
    };

    @Test
    void everyShapeThatMakesOneIsRefused() {
        for (String c : REFUSED) {
            assertNotNull(ForbiddenBlocks.inCommand(c) == null ? deep(c) : "x", "should see a forbidden block: " + c);
            assertFalse(CommandGuard.isAllowed(c), "CommandGuard should REFUSE: " + c);
            String why = CommandGuard.whyRefused(c);
            assertTrue(why != null && why.contains("#2466"), c + " → " + why);
        }
    }

    @Test
    void ordinaryBlocksTextAndTheJigsawGeneratorAreUntouched() {
        for (String c : KEPT) {
            assertNull(ForbiddenBlocks.inCommand(c), "should NOT see a forbidden block: " + c);
            assertTrue(CommandGuard.isAllowed(c), "CommandGuard should still allow: " + c);
        }
    }

    @Test
    void idsAreRecognisedWithOrWithoutTheirNamespaceAndState() {
        for (String id : new String[]{"command_block", "minecraft:command_block", "MINECRAFT:JIGSAW",
                "minecraft:repeating_command_block[facing=up]", "structure_block", "command_block_minecart",
                "chain_command_block{auto:1b}"}) {
            assertTrue(ForbiddenBlocks.isForbiddenId(id), id);
        }
        for (String id : new String[]{"stone", "minecraft:chest", "mymod:command_block", "minecraft:structure_void",
                "minecraft:command_block_stairs", null, ""}) {
            assertFalse(ForbiddenBlocks.isForbiddenId(id), String.valueOf(id));
        }
    }

    @Test
    void theRefusalNamesWhatAndWhyAndTheFix() {
        String why = ForbiddenBlocks.refusal("command_block");
        assertTrue(why.contains("command_block") && why.contains("on any world") && why.contains("Nothing was placed"),
                why);
        assertEquals("command_block", ForbiddenBlocks.inCommand("data merge block ~ ~ ~ {Command:\"x\"}"));
    }

    @Test
    void makeItemsBaseItemCannotBeOne() {
        var bad = com.moddingfromamod.datapack.ItemBase.refusals("minecraft:command_block");
        assertTrue(bad.size() == 1 && bad.get(0).contains("#2466"), bad.toString());
        assertTrue(com.moddingfromamod.datapack.ItemBase.refusals("minecraft:golden_axe").isEmpty());
    }

    /**
     * "A tool's own internals" (an earlier issue's checklist): no catalogue template, effect template or bundled function
     * this mod writes into a datapack names one of these, so the refusal can never break one of our own packs.
     */
    @Test
    void noneOfOurOwnTemplatesNeedsOne() throws java.io.IOException {
        java.nio.file.Path res = java.nio.file.Path.of("src/main/resources");
        java.util.List<String> hits = new java.util.ArrayList<>();
        int files;
        try (var walk = java.nio.file.Files.walk(res)) {
            var list = walk.filter(p -> p.toString().endsWith(".cbs") || p.toString().endsWith(".mcfunction")
                    || p.toString().endsWith(".cbscript")).toList();
            files = list.size();
            for (var p : list) {
                for (String line : java.nio.file.Files.readAllLines(p)) {
                    String l = line.strip();
                    if (l.startsWith("/")) {
                        l = l.substring(1);
                    }
                    if (ForbiddenBlocks.inCommand(l) != null || (l.contains(" run ") && deep(l) != null)) {
                        hits.add(p + ": " + line);
                    }
                }
            }
        }
        assertTrue(files > 20, "found only " + files + " templates — is the path right?");
        assertTrue(hits.isEmpty(), String.join("\n", hits));
    }

    /** The forbidden id in the innermost {@code run} tail (an execute head is read on its own). */
    private static String deep(String c) {
        int run = c.indexOf(" run ");
        return run < 0 ? null : (ForbiddenBlocks.inCommand(c.substring(run + 5)) != null ? "x"
                : deep(c.substring(run + 5)));
    }
}
