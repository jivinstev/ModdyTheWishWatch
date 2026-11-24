package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.carrier;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.plant;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.wish;

import java.util.concurrent.atomic.AtomicBoolean;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.CompileResult;
import com.moddingfromamod.datapack.DatapackForge;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a creature is made immune to its own abilities, mechanically.</b>
 *
 * <p>Production, 2026-09-25: <i>"make spikor! he breathes fire"</i> got a datapack that put fire
 * at Spikor's own feet, and the next wish was <i>"lol spikor catches himself on fire"</i>. Each
 * test compiles a datapack of that SHAPE through the real {@code DatapackForge.compile}, the one
 * place every datapack passes, where {@code SelfHarmGuard} runs. Nobody sets {@code fireproof} by
 * hand. Each was red on {@code main}: the species stayed as sculpted, and the fire burned it.
 *
 * <p>The datapack is compiled, not loaded: a live reload mid-batch re-runs every other pack's load
 * functions and breaks concurrent tests ({@code DatapackGameTest}'s header). So the test runs the
 * datapack's own command itself, the way its clock would, at the creature's feet.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SelfHarmGameTest {

    private static final String BATCH = "t6c5_selfharm";

    /** The production datapack's shape, aimed at {@code id}'s creature. */
    static String fireBreath(String id) {
        String tag = WishMobTags.forSpecies(id);
        return "clock tick\n"
                + "    /execute as @e[tag=" + tag + "] at @s run particle minecraft:flame ^ ^1 ^1 0.2 0.2 0.2 0.02 5\n"
                + "    /execute as @e[tag=" + tag + "] at @s run setblock ~ ~ ~ minecraft:fire keep\n"
                + "end\n";
    }

    private static void compile(GameTestHelper helper, String featureId, String source) {
        helper.assertTrue(CbScriptCompiler.available(),
                Component.literal("no CBScript compiler here, so the compile path this test is about cannot run"));
        CompileResult r = DatapackForge.compile(featureId, "#943 test", source);
        try {
            helper.assertTrue(r.ok(), Component.literal("the datapack did not compile: " + r.failureSummary()));
        } finally {
            // The guard learned what it needs at compile time. A pack left in the store is a rule
            // "running in his world" to every later test in this world: four of them once pushed
            // SurroundingsGameTest's staged rule out of its top-ten list, because "t6c5_" sorts
            // after "sur_rule_".
            forget(featureId);
        }
    }

    private static void forget(String featureId) {
        java.nio.file.Path dir = com.moddingfromamod.datapack.DatapackStore.featureDir(featureId);
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(p);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not remove the test's datapack " + featureId, e);
        }
    }

    private static void run(GameTestHelper helper, String command) {
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), command);
    }

    // ── the production bug: fire breath, and the creature does not burn ─────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aCreatureGivenFireBreathDoesNotBurn(GameTestHelper helper) {
        String id = plant(helper, "t6c5_spikor", "{base:'wild', powers:['friendly']}");
        helper.assertTrue(!SpeciesRegistry.get(id).has(Species.Power.FIREPROOF),
                Component.literal("the premise: nobody made it fireproof"));
        wish(helper, id);
        String tag = WishMobTags.forSpecies(id);
        AtomicBoolean compiled = new AtomicBoolean();
        long[] burning = {0};
        long[] inFire = {0};
        float[] lowest = {Float.MAX_VALUE};
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c == null) {
                return;
            }
            if (!compiled.getAndSet(true)) {
                compile(helper, "t6c5_spikor_fire", fireBreath(id));
            }
            if (helper.getTick() % 5 == 0) {
                run(helper, "execute as @e[tag=" + tag + "] at @s run setblock ~ ~ ~ minecraft:fire keep");
            }
            if (RigImmunities.shownOnFire(c)) {
                burning[0]++;
            }
            if (helper.getLevel().getBlockState(c.blockPosition()).is(Blocks.FIRE)) {
                inFire[0]++;
            }
            lowest[0] = Math.min(lowest[0], c.getHealth() / c.getMaxHealth());
        });
        helper.runAfterDelay(400, () -> {
            Mob c = carrier(helper, id);
            // What the child sees first, then why.
            helper.assertTrue(inFire[0] > 20, Component.literal("the premise: it stood in its own fire ("
                    + inFire[0] + " ticks in a fire block)"));
            helper.assertTrue(lowest[0] >= 1.0f, Component.literal(String.format(
                    "it burned: its health fell to %.0f%% of its maximum", lowest[0] * 100)));
            // Unharmed is not enough: a creature shown in flames reads as burning (the film,
            // 2026-09-27: 40/40 health inside a column of fire). A blaze is never shown on fire.
            helper.assertTrue(burning[0] <= inFire[0] / 10, Component.literal("it is unharmed but SHOWN ON FIRE for "
                    + burning[0] + " of the " + inFire[0] + " ticks it stood in fire"));
            helper.assertTrue(c != null, Component.literal("the creature is gone"));
            helper.assertTrue(SpeciesRegistry.get(id).has(Species.Power.FIREPROOF),
                    Component.literal("the fire-breath datapack compiled and the creature was NOT made fireproof"));
            helper.succeed();
        });
    }

    // ── it stays immune: a re-sculpt, and a datapack written before the creature ─────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aReSculptKeepsTheImmunityAndADatapackWrittenFirstStillCounts(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String first = plant(helper, "t6c5_resculpt", "{base:'wild', powers:['friendly']}");
        compile(helper, "t6c5_resculpt_fire", fireBreath(first));
        helper.assertTrue(SpeciesRegistry.get(first).has(Species.Power.FIREPROOF), Component.literal("not made fireproof"));
        // "make him bigger": the species is rebuilt from the agent's own arguments, which never
        // mention fireproof.
        Species again = SpeciesRegistry.get(first).with(2.0, null, null, null, null, null);
        Species plain = new Species(again.id(), again.name(), again.body(), again.texture(),
                again.tint(), again.scale(), again.health(), again.speed(), again.damage(),
                again.armor(), again.knockbackResistance(),
                java.util.EnumSet.of(Species.Power.FRIENDLY), again.modelKey(), again.base(),
                again.abilities(), again.abilityPacks(), again.ownerId());
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, plain);
        helper.assertTrue(SpeciesRegistry.get(first).has(Species.Power.FIREPROOF),
                Component.literal("a re-sculpt dropped the immunity its fire breath needs"));

        // The datapack first, the creature after: the agent is told to write powers before it
        // spawns, so this is the order a careful grant takes.
        compile(helper, "t6c5_later_fire", fireBreath("t6c5_later"));
        String later = plant(helper, "t6c5_later", "{base:'wild', powers:['friendly']}");
        helper.assertTrue(SpeciesRegistry.get(later).has(Species.Power.FIREPROOF),
                Component.literal("a creature sculpted after its fire-breath datapack was not made fireproof"));
        helper.succeed();
    }

    // ── the other hazards: blast, lightning, poison ─────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void blastLightningAndPoisonCostItNothingEither(GameTestHelper helper) {
        String id = plant(helper, "t6c5_storm", "{base:'wild', powers:['friendly']}");
        String tag = WishMobTags.forSpecies(id);
        compile(helper, "t6c5_storm_powers", "clock tick\n"
                + "    /execute as @e[tag=" + tag + "] at @s run summon minecraft:tnt ~ ~ ~ {fuse:80}\n"
                + "    /execute as @e[tag=" + tag + "] at @s run summon minecraft:lightning_bolt ~ ~ ~\n"
                + "    /effect give @e[tag=" + tag + "] minecraft:poison 5 1\n"
                + "end\n");
        Species sp = SpeciesRegistry.get(id);
        helper.assertTrue(sp.has(Species.Power.BLASTPROOF) && sp.has(Species.Power.LIGHTNINGPROOF)
                && sp.has(Species.Power.POISONPROOF), Component.literal("not made immune: " + sp.powers()));
        wish(helper, id);
        AtomicBoolean hit = new AtomicBoolean();
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            if (!hit.getAndSet(true)) {
                // The harm itself, as those commands would deal it: typed damage, not a real blast,
                // so no block of the plot and no neighbouring test is touched.
                run(helper, "damage @e[tag=" + tag + ",limit=1] 6 minecraft:explosion");
                run(helper, "damage @e[tag=" + tag + ",limit=1] 5 minecraft:lightning_bolt");
                run(helper, "effect give @e[tag=" + tag + "] minecraft:poison 5 1");
            }
            helper.assertTrue(c.getHealth() >= c.getMaxHealth(), Component.literal(String.format(
                    "hurt by its own blast/lightning: %.1f of %.1f", c.getHealth(), c.getMaxHealth())));
            helper.assertTrue(!c.hasEffect(MobEffects.POISON), Component.literal("its own poison took hold of it"));
        });
    }
}
