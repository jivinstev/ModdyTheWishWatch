package com.moddingfromamod.agent.tools;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.ItemCatalogue;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Does the pearl actually start a fight?</b>
 *
 * <p>The {@code aggro} effect is the answer to <i>"a pearl that makes a noob fight a mob"</i>, and
 * it is the one effect in the catalogue whose whole result is invisible: nothing moves, nothing is
 * placed, nothing takes meaningful damage. What changes is <b>who a creature is angry at</b>, and
 * that cannot be read off the world — which makes this exactly the kind of feature that ships
 * looking correct and does nothing.
 *
 * <h2>Why a command can do this at all</h2>
 * A function cannot set a mob's target: goal-based mobs take a Java {@code setTarget} and
 * brain-based ones take {@code ANGRY_AT}/{@code ATTACK_TARGET} memories, neither reachable from a
 * datapack. What IS reachable is {@code /damage <them> ... by <someone>}, which sets
 * {@code lastHurtByMob} — and {@code HurtByTargetGoal}, which nearly every hostile mob carries,
 * turns that into a real hunt on the next goal tick.
 *
 * <p>So this asserts <b>both halves separately</b>, because they fail for different reasons: the
 * mechanism (was the attribution recorded at all) and the outcome (did the mob actually go for it).
 * A test that only checked the second could not tell a broken selector from a slow goal.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class AggroBehaviourGameTest {

    /**
     * The `/` lines the rendered effect would run, in order — driven through the REAL
     * {@code FIGHTS} to selector mapping rather than a copy of it, so a change to the table is
     * exercised here and not only in {@code AggroFightsTest}.
     */
    private static List<String> effectCommands(String fights) {
        Map<String, String> args = new java.util.LinkedHashMap<>();
        args.put("FIGHTS", fights);
        String refusal = MakeItemTool.derive(
                ItemCatalogue.load().get("aggro"), "thrown", args);
        if (refusal != null) {
            throw new IllegalStateException("the tool refused '" + fights + "': " + refusal);
        }
        args.put("TARGET", ItemCatalogue.targetFor("thrown", "target", 3));
        String src = ItemCatalogue.load().render("aggro", "thrown", args,
                Map.of("FEATURE", "aggrotest", "GIVES", "", "GIVE_MESSAGE", "x",
                        "HEX", "#FFFFFF", "TRAIL", "minecraft:angry_villager"));
        return src.lines().map(String::strip)
                .filter(l -> l.startsWith("/tag ") || l.startsWith("/execute "))
                .map(l -> l.substring(1))
                .toList();
    }

    private static void run(GameTestHelper helper, List<String> commands, BlockPos at) {
        ServerLevel level = helper.getLevel();
        CommandSourceStack source = level.getServer().createCommandSourceStack()
                .withLevel(level).withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER)
                .withPosition(Vec3.atCenterOf(helper.absolutePos(at)));
        for (String c : commands) {
            level.getServer().getCommands().performPrefixedCommand(source, c);
        }
    }

    /**
     * <b>The whole wish, in one test.</b> A cow standing where the pearl lands ends up being
     * hunted by the zombie next to it — which is a fight that was not going to happen.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void whatItHitsEndsUpFightingTheOtherCreature(GameTestHelper helper) {
        BlockPos impact = new BlockPos(2, 2, 2);
        Cow noob = helper.spawn(EntityType.COW, impact);
        Zombie other = helper.spawn(EntityType.ZOMBIE, impact.offset(2, 0, 0));
        noob.setPersistenceRequired();
        other.setPersistenceRequired();

        run(helper, effectCommands("mob"), impact);

        // THE MECHANISM, readable at once: the damage was attributed to the other creature.
        helper.assertTrue(noob.getLastHurtByMob() == other,
                Component.literal("the pearl did not make the zombie the cow's attacker — it was "
                        + noob.getLastHurtByMob()));

        // THE OUTCOME, which needs a goal tick: the cow now runs from / goes for the zombie, and
        // the zombie has a live victim. Asserted late so a slow goal is not read as a broken one.
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(noob.isAlive(), Component.literal("the nudge killed the cow — 1.0 is meant to start a "
                    + "fight, not finish one"));
            helper.assertTrue(other.getTarget() == noob || noob.getLastHurtByMob() == other,
                    Component.literal("nothing is fighting anything: zombie target=" + other.getTarget()));
            helper.succeed();
        });
    }

    /**
     * The tag is not tidiness. Without it the "nearest creature" is the victim standing at the
     * impact point, and the pearl makes a mob fight ITSELF — which looks exactly like doing nothing.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void itNeverMakesACreatureFightItself(GameTestHelper helper) {
        BlockPos impact = new BlockPos(2, 2, 2);
        Cow alone = helper.spawn(EntityType.COW, impact);
        alone.setPersistenceRequired();

        run(helper, effectCommands("mob"), impact);

        helper.assertTrue(alone.getLastHurtByMob() != alone,
                Component.literal("the pearl made the cow its own attacker"));
        helper.assertTrue(alone.isAlive(), Component.literal("the cow died to a pearl with nobody to fight"));
        helper.succeed();
    }

    /**
     * And it leaves nothing behind. A tag that outlived the function would make the NEXT pearl pick
     * the wrong creature to exclude — a bug that only appears on the second use, which is the
     * hardest kind to be told about by a seven-year-old.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void theWorkingTagIsCleanedUp(GameTestHelper helper) {
        BlockPos impact = new BlockPos(2, 2, 2);
        Cow noob = helper.spawn(EntityType.COW, impact);
        helper.spawn(EntityType.ZOMBIE, impact.offset(2, 0, 0));

        run(helper, effectCommands("mob"), impact);

        helper.assertTrue(!noob.entityTags().contains("mfam_aggro_noob"),
                Component.literal("the working tag survived the function: " + noob.entityTags()));
        helper.succeed();
    }
}
