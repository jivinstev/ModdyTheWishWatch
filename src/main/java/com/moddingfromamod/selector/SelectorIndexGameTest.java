package com.moddingfromamod.selector;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * <b>an earlier issue's oracle in the server: every selector answered both ways, through real commands, while
 * the world is changed in every way that can leave an index stale.</b>
 *
 * <p>Runs in {@link SelectorIndex.Mode#VERIFY}, where the index answers into a scratch list beside
 * vanilla and any difference — which entities, or their ORDER — is a counted mismatch. Each step
 * mutates the world one way and then runs a battery of selector shapes (limits, sorts, type, nbt,
 * scores, negations, absent tags) through {@code execute if entity}. Passing means: zero mismatches,
 * and the index really answered (so a green run is not a run the index sat out). Every tag is
 * prefixed per test, so selectors never see another test's entities. Own batch: the mode and the
 * counters are static.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SelectorIndexGameTest {

    private static final BlockPos SPOT = new BlockPos(4, 1, 4);

    private record Run(GameTestHelper helper, String p, CommandSourceStack src, List<String> log) {

        int cmd(String c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            MinecraftServer s = helper.getLevel().getServer();
            return s.getCommands().getDispatcher().execute(
                    s.getCommands().getDispatcher().parse(c.replace("$", p), src));
        }

        int run(String c) {
            try {
                return cmd(c);
            } catch (Exception e) {
                // vanilla's `execute if entity` THROWS "Test failed" when nothing matches: that is 0
                if (e instanceof com.mojang.brigadier.exceptions.CommandSyntaxException
                        && String.valueOf(e.getMessage()).startsWith("Test failed")) {
                    return 0;
                }
                log.add(c.replace("$", p) + " -> " + e.getMessage());
                return -1;
            }
        }

        /** Every selector shape, counted both ways by the verify mode. */
        void battery(String step) {
            String[] sels = {
                    "@e[tag=$a]", "@e[tag=$a,tag=$b]", "@e[tag=$a,tag=!$b]", "@e[tag=$a,limit=1]",
                    "@e[tag=$a,limit=3]", "@e[tag=$a,sort=nearest,limit=2]", "@e[tag=$a,sort=furthest]",
                    "@e[tag=$a,sort=random,limit=2]", "@e[tag=$a,type=pig]", "@e[tag=$a,type=!pig]",
                    "@e[tag=$b,nbt={NoAI:1b}]", "@e[tag=$a,scores={$s=1..}]", "@e[tag=$nobody]",
                    "@e[tag=$a,name=Bob]", "@e[tag=$a,distance=..200]", "@e[tag=$a,tag=$b,tag=$c]",
                    "@e[tag=$c]", "@e[tag=$d]", "@e[tag=$rider]", "@e[tag=$far]", "@e[tag=$a,limit=1,sort=arbitrary]",
            };
            for (String sel : sels) {
                run("execute if entity " + sel);
                run("execute in minecraft:the_nether if entity " + sel);
            }
            run("execute as @e[tag=$a] run scoreboard players add @s $s 1");
            log.add("step " + step + " ok");
        }
    }

    private static Run start(GameTestHelper helper, String prefix) {
        ServerLevel level = helper.getLevel();
        Vec3 at = Vec3.atCenterOf(helper.absolutePos(SPOT));
        CommandSourceStack src = level.getServer().createCommandSourceStack().withLevel(level)
                .withPosition(at).withPermission(4).withSuppressedOutput();
        Run r = new Run(helper, prefix, src, new ArrayList<>());
        r.run("scoreboard objectives add $s dummy");
        return r;
    }

    @GameTest(template = "empty_test", batch = "selector_index", timeoutTicks = 1600)
    public static void everyWayToChangeTagsOrEntitiesKeepsTheIndexExact(GameTestHelper helper) {
        SelectorIndex.Mode was = SelectorIndex.mode();
        SelectorIndex.setModeForTest(SelectorIndex.Mode.VERIFY);
        long mis0 = SelectorIndex.MISMATCHES.get();
        long idx0 = SelectorIndex.INDEXED.get();
        Run r = start(helper, "gtsi" + Long.toHexString(System.nanoTime() & 0xffffff) + "_");
        try {
            for (int i = 0; i < 30; i++) {
                String tags = (i % 2 == 0 ? "\"$a\"," : "") + (i % 3 == 0 ? "\"$b\"," : "") + "\"$all\"";
                r.run("summon pig ~" + (i % 6) + " ~ ~" + (i / 6) + " {NoAI:1b,Tags:[" + tags + "]"
                        + (i == 4 ? ",CustomName:'\"Bob\"'" : "") + "}");
            }
            for (int i = 0; i < 20; i++) {
                r.run("summon armor_stand ~" + (i % 5) + " ~ ~-2 {NoGravity:1b}");
            }
            r.battery("summoned");
            r.run("tag @e[tag=$a,limit=5] remove $a");
            r.run("tag @e[tag=$b,limit=4] add $a");
            r.battery("/tag");
            r.run("execute as @e[tag=$a] run tag @s add $c");   // tags changing while a selector's answer is in use
            r.battery("tagged inside execute as");
            r.run("data merge entity @e[tag=$b,limit=1] {Tags:[\"$a\",\"$d\"]}");
            r.battery("/data merge (vanilla's load path)");
            // the bypass: code that mutates the live set directly, never through addTag
            Entity direct = null;
            for (Entity e : helper.getLevel().getEntities().getAll()) {
                if (e.getTags().contains(r.p() + "all") && !e.getTags().contains(r.p() + "a")) {
                    direct = e;
                    break;
                }
            }
            if (direct != null) {
                direct.getTags().add(r.p() + "a");
                direct.getTags().add(r.p() + "d");
                direct.getTags().removeIf(t -> t.equals(r.p() + "all"));
            }
            r.battery("getTags() mutated directly");
            r.run("kill @e[tag=$b,limit=3]");
            r.battery("killed");
            r.run("summon cow ~ ~ ~2 {NoAI:1b,Tags:[\"$a\",\"$b\"],Passengers:[{id:\"minecraft:chicken\",Tags:[\"$rider\",\"$a\"]}]}");
            r.battery("summoned with a passenger");
            r.run("ride @e[tag=$rider,limit=1] dismount");
            r.battery("dismounted");
            r.run("tag @e[tag=$a,limit=2] add $far");
            r.run("execute in minecraft:the_nether run forceload add 0 0");
            // GENERATE IT NOW. Chunk generation is wall-clock work on worker threads, and a GameTest
            // server ticks as fast as it can: a full Gate B (busy workers) once spent 1200 ticks of
            // waiting before the chunk existed. Loaded synchronously here, all that is left to wait
            // for is the ticket reaching it, which IS counted in ticks.
            ServerLevel nether0 = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            if (nether0 != null) {
                // and its NEIGHBOURS. A chunk ticks entities only once every chunk within 2 of it
                // is FULL, so loading (0,0) alone still left 24 chunks of wall-clock generation to wait
                // out in ticks — and a full tier (a batch at 1 tick/s, busy workers) ran out of them.
                for (int cx = -2; cx <= 2; cx++) {
                    for (int cz = -2; cz <= 2; cz++) {
                        nether0.getChunk(cx, cz);
                    }
                }
            }
            // a rigged creature: ~dozens of displays and proxies, built, recoloured, taken away
            com.moddingfromamod.vanilla.DisplayRig rig = com.moddingfromamod.vanilla.DisplayRig.spawn(
                    helper.getLevel(), Vec3.atBottomCenterOf(helper.absolutePos(SPOT)).add(0, 0, 3), 0f,
                    com.moddingfromamod.vanilla.RigFitGameTest.whale(), 1.0, r.p() + "rig",
                    com.moddingfromamod.vanilla.CarrierPool.Move.WALK);
            rig.base().addTag(r.p() + "a");
            r.battery("a rig built");
            rig.tint(0xFF6060);
            rig.discard();
            r.battery("the rig recoloured and removed");
        } catch (RuntimeException e) {
            SelectorIndex.setModeForTest(was);
            throw e;
        }
        // across ticks: chunk loading, a dimension change and back, entity ticking in between.
        // Wait for the forced nether chunk to TICK ENTITIES before sending anything there: a fixed
        // delay passed alone and failed under the full suite's load (and vanilla agreed: 0 there).
        whenNetherReady(helper, 0, () -> {
            r.run("execute in minecraft:the_nether run tp @e[tag=$far] 8 100 8");
            r.battery("moved to the nether");
            helper.runAfterDelay(20, () -> {
                int there = r.run("execute in minecraft:the_nether positioned 8 100 8 if entity @e[tag=$far,distance=..20]");
                if (there != 2) {
                    r.log().add("expected 2 entities in the nether, saw " + there);
                }
                r.battery("in the nether, a tick later");
                r.run("execute positioned " + (int) Vec3.atCenterOf(helper.absolutePos(SPOT)).x + " "
                        + helper.absolutePos(SPOT).getY() + " " + (int) Vec3.atCenterOf(helper.absolutePos(SPOT)).z
                        + " in minecraft:overworld run tp @e[tag=$far] ~ ~ ~");
                helper.runAfterDelay(20, () -> {
                    r.battery("back in the overworld");
                    r.run("execute in minecraft:the_nether run forceload remove 0 0");
                    SelectorIndex.setModeForTest(was);
                    long mismatches = SelectorIndex.MISMATCHES.get() - mis0;
                    long indexed = SelectorIndex.INDEXED.get() - idx0;
                    List<String> errors = r.log().stream().filter(l -> !l.startsWith("step ")).toList();
                    helper.assertTrue(errors.isEmpty(), Component.literal("commands failed: " + errors));
                    helper.assertTrue(mismatches == 0, Component.literal(mismatches + " selector answer(s) "
                            + "differed from vanilla's -- see 'selector index MISMATCH' in the log"));
                    helper.assertTrue(indexed > 300, Component.literal("the index answered only " + indexed
                            + " selectors: this run did not test it"));
                    helper.succeed();
                });
            });
        });
    }

    private static void whenNetherReady(GameTestHelper helper, int waited, Runnable then) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        if (nether != null && nether.isPositionEntityTicking(new BlockPos(8, 100, 8))) {
            then.run();
        } else if (waited >= 1200) {
            helper.fail(Component.literal("the forced nether chunk never started ticking entities"));
        } else {
            helper.runAfterDelay(5, () -> whenNetherReady(helper, waited + 5, then));
        }
    }

    @GameTest(template = "empty_test", batch = "selector_index", timeoutTicks = 100)
    public static void theIndexAnswersWhatVanillaWouldInOrdinaryMode(GameTestHelper helper) {
        // ON (the default): the answer that is USED is the index's. Count it against what is there.
        Run r = start(helper, "gtsj" + Long.toHexString(System.nanoTime() & 0xffffff) + "_");
        for (int i = 0; i < 12; i++) {
            r.run("summon pig ~" + i + " ~ ~ {NoAI:1b,Tags:[\"$a\"" + (i < 5 ? ",\"$b\"" : "") + "]}");
        }
        long idx0 = SelectorIndex.INDEXED.get();
        int a = r.run("execute if entity @e[tag=$a]");
        int ab = r.run("execute if entity @e[tag=$a,tag=$b]");
        int one = r.run("execute if entity @e[tag=$a,limit=1]");
        int none = r.run("execute if entity @e[tag=$nobody]");
        helper.assertTrue(a == 12 && ab == 5 && one == 1 && none == 0, Component.literal(
                "counts a=" + a + " ab=" + ab + " one=" + one + " none=" + none + " (want 12, 5, 1, 0)"));
        helper.assertTrue(SelectorIndex.mode() == SelectorIndex.Mode.VERIFY
                        || SelectorIndex.INDEXED.get() - idx0 >= 4,
                Component.literal("the index did not answer these"));
        helper.succeed();
    }
}
