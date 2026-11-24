package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** step 1: a compiled datapack never runs a command CommandGuard's table DENIES. */
class DatapackCommandsTest {

    @Test
    void aDenyRowIsRefusedWhereverItHides() {
        for (String line : new String[]{"worldborder set 1", "/forceload add ~ ~", "tick freeze",
                "execute as @a at @s run difficulty peaceful", "$execute if score @s x matches 1 run reload",
                "minecraft:gamemode creative @s", "execute in minecraft:overworld run execute as @s run seed"}) {
            String why = DatapackCommands.refusalForLine(line);
            assertNotNull(why, line);
            assertTrue(why.contains("a wish never runs"), why);
        }
    }

    @Test
    void whatWishedPacksReallyRunIsUntouched() {
        // The LIMITED rows are run_command's, not a datapack's (an earlier issue's measurement): a fireball
        // removing itself, the compiler's own boilerplate, a breath that hurts whoever stands near.
        for (String line : new String[]{"kill @s", "gamerule max_command_sequence_length 1000000000",
                "execute as @a[distance=..4] run damage @s 4 minecraft:in_fire", "tellraw @a \"hi\"",
                "# a comment", "", "scoreboard players add @s t 1", "execute if entity @s[tag=x]",
                "particle minecraft:flame ~ ~ ~ 0 0 0 0 5"}) {
            assertNull(DatapackCommands.refusalForLine(line), line);
        }
    }

    /** Step 2: what wished packs really aim at players — measured over 178 of them. */
    @Test
    void aDatapackNeverChangesEveryPlayerAtOnceNorKillsAChild() {
        for (String line : new String[]{"give @a minecraft:cooked_beef 8", "effect give @a minecraft:levitation 5",
                "clear @a", "execute as @a run give @s minecraft:diamond", "tp @a ~ ~10 ~",
                "execute as @e at @s run effect give @s minecraft:poison 5", "kill @a[distance=..3]", "kill @p",
                "kill @e[distance=..5]", "spreadplayers 0 0 10 100 false @a", "advancement grant @r everything"}) {
            assertNotNull(DatapackCommands.refusalForLine(line), line);
        }
        for (String line : new String[]{"kill @s", "kill @e[tag=yegg]", "kill @e[type=minecraft:zombie,distance=..8]",
                "effect give @a[distance=..12] minecraft:resistance 3 4 true",
                "damage @a[distance=..3.0] 3 minecraft:on_fire", "give @a[limit=1,sort=nearest] minecraft:bread",
                "give @s minecraft:bread", "give @p minecraft:bread", "playsound minecraft:entity.player.levelup master @a",
                "tellraw @a \"Burgers!\"", "title @a title \"DINKO WINS!\"", "effect give @e[tag=wishmob_x] minecraft:speed 5",
                "execute as @e[tag=yegg] at @s run kill @s", "execute as @a[distance=..6] run give @s minecraft:apple",
                "execute as @a at @s run particle minecraft:heart ~ ~2 ~",
                // our own templates mark their targets with a score (impulse, grow — Gate B, 2026-10-17)
                "tp @e[scores={mfam_imp=1..}] ~ ~0.4 ~", "effect give @e[scores={mfam_imp=12}] minecraft:slow_falling 3",
                "attribute @e[scores={mfam_grow=0}] minecraft:scale base set 2", "kill @e[scores={mfam_t=0}]"}) {
            assertNull(DatapackCommands.refusalForLine(line), line);
        }
    }

    @Test
    void aZipIsReadFunctionByFunction(@TempDir Path dir) throws Exception {
        Path ok = pack(dir.resolve("ok.zip"), "say hello\nparticle minecraft:heart ~ ~1 ~ 0 0 0 0 3\n");
        assertNull(DatapackCommands.refusal(ok));
        Path bad = pack(dir.resolve("bad.zip"), "say hello\nexecute as @s run worldborder set 2\n");
        assertNotNull(DatapackCommands.refusal(bad));
        assertNull(DatapackCommands.refusal(dir.resolve("missing.zip")));
    }

    /** Where it RUNS: the real compiler refuses a wished pack that shrinks the world border. */
    @Test
    void theRealCompilerRefusesAPackThatRunsADenyRow(@TempDir Path dir) {
        org.junit.jupiter.api.Assumptions.assumeTrue(CbScriptCompiler.available(), "SKIPPED — no compiler here");
        CompileResult bad = CbScriptCompiler.compile(dir.resolve("bad"), "tiny_world", "a tiny world",
                "reset\n    /worldborder set 16\nend\n", dir.resolve("bad.zip"));
        assertTrue(!bad.ok() && bad.failureSummary().contains("a wish never runs"),
                "a datapack must not run what run_command may not: " + bad.failureSummary());
        assertTrue(!Files.exists(dir.resolve("bad.zip")), "nothing was saved");
        CompileResult good = CbScriptCompiler.compile(dir.resolve("good"), "hello", "a greeting",
                "reset\n    /tellraw @s \"hello\"\nend\n", dir.resolve("good.zip"));
        assertTrue(good.ok(), "the control compiles: " + good.failureSummary());
    }

    private static Path pack(Path zip, String function) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("pack.mcmeta"));
            z.write("{}".getBytes());
            z.putNextEntry(new ZipEntry("data/wish/function/tick.mcfunction"));
            z.write(function.getBytes());
        }
        return zip;
    }

    /** a datapack line that makes a command block, structure block or jigsaw is refused at compile. */
    @Test
    void aDatapackNeverMakesACommandBlock() {
        for (String line : new String[]{"setblock ~ ~1 ~ command_block{Command:\"op @a\",auto:1b}",
                "execute as @s at @s run fill ~ ~ ~ ~1 ~1 ~1 minecraft:repeating_command_block",
                "$setblock ~ ~ ~ minecraft:structure_block", "give @s jigsaw",
                "summon command_block_minecart ~ ~ ~", "data merge block ~ ~-1 ~ {Command:\"say x\"}"}) {
            String why = DatapackCommands.refusalForLine(line);
            assertNotNull(why, line);
            assertTrue(why.contains("#2466") && why.contains("This datapack makes"), why);
        }
        for (String line : new String[]{"setblock ~ ~1 ~ stone", "say command block", "give @s diamond"}) {
            assertNull(DatapackCommands.refusalForLine(line), line);
        }
    }

    @Test
    void aCompiledPackWithACommandBlockIsRefused(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("p.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("data/p/function/tick.mcfunction"));
            z.write("particle minecraft:heart ~ ~1 ~ 0 0 0 0 1\nsetblock ~ ~1 ~ command_block{auto:1b}\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.closeEntry();
        }
        String why = DatapackCommands.refusal(zip);
        assertNotNull(why);
        assertTrue(why.contains("command_block"), why);
    }
}
