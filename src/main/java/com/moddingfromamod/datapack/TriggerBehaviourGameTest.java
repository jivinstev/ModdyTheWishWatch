package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Does the template actually DO anything?</b>
 *
 * <p>The 144-cell matrix proves every combination <i>compiles</i>. Compilation was never the risk —
 * a silent no-op is. The player-only {@code on_hit} bug compiled perfectly, passed all 144 cells,
 * and would have done nothing whenever the victim was a creature; it was caught by a question,
 * not by a test.
 *
 * <p>This is the missing tier, and it belongs on a <b>server</b>, not in the client A/B. A real
 * dedicated server with real entities answers "did the world change" in seconds, where a live wish
 * costs minutes and drags in the agent, the model and the harness. Sparse on purpose: one
 * representative cell per trigger, because trigger is the axis that changes the program — effect
 * and target are already settled by the matrix.
 *
 * <p>Runs the template's own command lines through the real dispatcher rather than installing a
 * datapack: {@code reload_datapacks} costs ~5s and is historically noisy, and the semantics being
 * tested live in the command, not in the packaging.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class TriggerBehaviourGameTest {

    private static final String SOURCE_TAG = "behaviourmob";

    /** The command lines a template would run, in order: setup first, then the per-tick body. */
    private static List<String> commandsOf(String trigger, String command, int radius) {
        // Only the parameters THIS trigger declares. render() rejects an unknown one on purpose --
        // that guard exists so a misspelled parameter cannot silently fall back to a default -- so
        // a test that shovels every parameter at every template is testing its own sloppiness.
        EffectCatalogue catalogue = EffectCatalogue.load();
        EffectCatalogue.Effect entry = catalogue.get(trigger);
        Map<String, String> offered = Map.of(
                "TAG", SOURCE_TAG,
                "COMMAND", command,
                "RADIUS", String.valueOf(radius),
                "PERIOD_TICKS", "1");        // fire immediately, so one dispatch is one period
        Map<String, String> params = new java.util.LinkedHashMap<>();
        offered.forEach((k, v) -> {
            if (entry.params().contains(k)) {
                params.put(k, v);
            }
        });
        String src = catalogue.render(trigger, params);
        return src.lines()
                .map(String::strip)
                .filter(l -> l.startsWith("/"))
                .map(l -> l.substring(1))
                .toList();
    }

    private static void run(GameTestHelper helper, List<String> commands) {
        ServerLevel level = helper.getLevel();
        CommandSourceStack source = level.getServer().createCommandSourceStack()
                .withLevel(level).withSuppressedOutput().withPermission(4);
        for (String c : commands) {
            level.getServer().getCommands().performPrefixedCommand(source, c);
        }
    }

    /** A creature to hang the ability on, and a victim for it to land on. */
    private static Zombie sourceMob(GameTestHelper helper, BlockPos at) {
        Zombie z = helper.spawn(EntityType.ZOMBIE, at);
        z.addTag(SOURCE_TAG);
        z.setPersistenceRequired();
        return z;
    }

    private static boolean hasSlowness(LivingEntity e) {
        return e.hasEffect(MobEffects.SLOWNESS);
    }

    /**
     * <b>THE ONE THAT WOULD HAVE CAUGHT THE BUG.</b> A mob hurt next to the creature must receive
     * the effect — with no player involved anywhere.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void onHitLandsOnAMOBVictim(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 2, 2);
        sourceMob(helper, at);
        Cow victim = helper.spawn(EntityType.COW, at.offset(1, 0, 0));

        // Damage it so HurtTime is set THIS tick, which is what the template keys on.
        victim.hurt(helper.getLevel().damageSources().magic(), 1.0F);
        helper.assertTrue(victim.hurtTime > 0,
                Component.literal("the victim must actually be in its hurt window or this proves nothing; hurtTime="
                        + victim.hurtTime));

        run(helper, commandsOf("on_hit", "effect give @s minecraft:slowness 10 1", 4));

        helper.assertTrue(hasSlowness(victim),
                Component.literal("a COW hurt beside the creature received nothing. on_hit only works for players — "
                        + "which is exactly the bug that compiled cleanly and passed 144 matrix "
                        + "cells while doing nothing for every mob-versus-mob wish."));
        helper.succeed();
    }

    /**
     * <b>: the doubt that cost a wish, settled.</b>
     *
     * <p>On <i>"make the giant PUNCH me so hard i fly backwards"</i> the agent chose {@code on_hit}
     * three times, then switched to {@code proximity} while iterating, and its closing line said
     * why — it had reconceived the ability as <i>walk up and get smacked</i>. The reasoning is
     * defensible and was recorded as a fair engineering judgement: if a summoned giant has no AI
     * that attacks the child, {@code on_hit} never fires and the child sees nothing, ever.
     *
     * <p><b>The premise is false, and this test is the fact that answers it.</b> The template
     * selects its victim by {@code HurtTime} within {@code RADIUS}, excluding only the creature
     * itself. It never asks who dealt the damage. So the creature does not have to attack — it does
     * not have to be capable of attacking. Here it is spawned with <b>no AI at all</b>, which is
     * strictly worse than the vanilla giant the agent was worried about, and the trigger still
     * fires.
     *
     * <p>This also corrects the issue's own suggested wording, which proposed telling the agent
     * that {@code on_hit} means "the tagged creature damaging anything". That is narrower than what
     * the template does, and teaching it would recreate the same doubt from the other side — an
     * agent believing the creature must land the hit has every reason to fall back to
     * {@code proximity} exactly as this one did.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void onHitFiresEvenWhenTheCreatureCannotAttackAtAll(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 2, 2);
        Zombie inert = sourceMob(helper, at);
        inert.setNoAi(true);        // it cannot attack anything, ever
        Cow victim = helper.spawn(EntityType.COW, at.offset(1, 0, 0));

        // Hurt by something that is NOT the creature — the case the agent assumed was dead.
        victim.hurt(helper.getLevel().damageSources().magic(), 1.0F);
        helper.assertTrue(victim.hurtTime > 0, Component.literal("the victim must be in its hurt window"));
        helper.assertTrue(inert.isNoAi(), Component.literal("the creature must genuinely be unable to act"));

        run(helper, commandsOf("on_hit", "effect give @s minecraft:slowness 10 1", 4));

        helper.assertTrue(hasSlowness(victim),
                Component.literal("on_hit did not fire beside a creature that cannot attack — which would make the "
                        + "agent's #99 doubt correct and proximity the right fallback. It fires: "
                        + "the template keys on the VICTIM's HurtTime, never on who hit it."));
        helper.succeed();
    }

    /** And it must not fire for something that was never hurt. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void onHitDoesNotFireForAnUnhurtBystander(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 2, 2);
        sourceMob(helper, at);
        Cow bystander = helper.spawn(EntityType.COW, at.offset(1, 0, 0));

        run(helper, commandsOf("on_hit", "effect give @s minecraft:slowness 10 1", 4));

        helper.assertTrue(!hasSlowness(bystander),
                Component.literal("an UNHURT bystander got the effect, so on_hit is really proximity wearing its "
                        + "name — and the child would be told their creature's bite did something "
                        + "it did not"));
        helper.succeed();
    }

    /** The creature itself must not be its own victim. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void onHitDoesNotHitTheCreatureItself(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 2, 2);
        Zombie self = sourceMob(helper, at);
        self.hurt(helper.getLevel().damageSources().magic(), 1.0F);

        run(helper, commandsOf("on_hit", "effect give @s minecraft:slowness 10 1", 4));

        helper.assertTrue(!hasSlowness(self),
                Component.literal("the creature poisoned ITSELF — the tag=! exclusion is not working, and a wish for "
                        + "a venomous spider would produce a spider that poisons itself"));
        helper.succeed();
    }

    /** Proximity must reach a mob standing nearby, hurt or not. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void proximityLandsOnANearbyMob(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 2, 2);
        sourceMob(helper, at);
        Cow near = helper.spawn(EntityType.COW, at.offset(1, 0, 0));

        run(helper, commandsOf("proximity",
                "effect give @e[type=!player,distance=..3,tag=!" + SOURCE_TAG + "] "
                        + "minecraft:slowness 10 1", 3));

        helper.assertTrue(hasSlowness(near), Component.literal("a mob standing next to the creature got nothing"));
        helper.succeed();
    }

    /** …and must not reach one standing well outside its radius. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void proximityRespectsItsRadius(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        sourceMob(helper, at);
        Cow far = helper.spawn(EntityType.COW, at.offset(6, 0, 0));

        run(helper, commandsOf("proximity",
                "effect give @e[type=!player,distance=..2,tag=!" + SOURCE_TAG + "] "
                        + "minecraft:slowness 10 1", 2));

        helper.assertTrue(!hasSlowness(far),
                Component.literal("a mob six blocks away was affected by a two-block radius, so RADIUS is decorative "
                        + "and every 'only when you get close' promise is false"));
        helper.succeed();
    }

    /** Periodic must fire its command at least once. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void periodicFiresItsCommand(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 2, 2);
        sourceMob(helper, at);
        Cow near = helper.spawn(EntityType.COW, at.offset(1, 0, 0));

        // PERIOD_TICKS=1 and the scoreboard is stepped by the template's own lines, so a single
        // dispatch of setup+body is one whole period.
        run(helper, commandsOf("periodic",
                "effect give @e[type=!player,distance=..3,tag=!" + SOURCE_TAG + "] "
                        + "minecraft:slowness 10 1", 3));

        helper.assertTrue(hasSlowness(near),
                Component.literal("the periodic trigger never ran its command, so every 'every N seconds' wish would "
                        + "install cleanly and never happen"));
        helper.succeed();
    }
}
