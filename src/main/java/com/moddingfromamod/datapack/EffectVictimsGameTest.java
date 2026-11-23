package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Husk;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Who a creature's effect lands on, in the world</b> (chunk 2, {@link EffectVictims}).
 *
 * <p>The baseline: a fire breath burned the child, a bite meant for "the monsters" poisoned
 * him, a knock-back for the monsters threw him 33 blocks. Each template here runs its own command
 * lines through the real dispatcher (as {@code TriggerBehaviourGameTest} does) with a cow, a
 * monster and a stand-in child all in reach, so what is asserted is what a server does with the
 * selector — including that a positive {@code type=#…} tag parses at all, which no text check can
 * say.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class EffectVictimsGameTest {

    private static final String SOURCE_TAG = "victimsource";
    private static final String BATCH = "effect_victims";

    private record Cast(Mob source, Cow cow, Husk monster, ServerPlayer kid) {
    }

    /** A source facing east (+x), and a cow, a husk and the child two blocks in front of it. */
    private static Cast cast(GameTestHelper helper, String name) {
        MockPlayerNetwork.clearStaleMocks(helper);
        BlockPos at = new BlockPos(2, 2, 4);
        Cow source = helper.spawn(EntityType.COW, at);
        source.addTag(SOURCE_TAG);
        source.setNoAi(true);
        source.setYRot(-90f);
        source.setYHeadRot(-90f);
        source.setYBodyRot(-90f);
        Cow cow = helper.spawn(EntityType.COW, at.offset(2, 0, -1));
        cow.setNoAi(true);
        Husk husk = helper.spawn(EntityType.HUSK, at.offset(2, 0, 1));
        husk.setNoAi(true);
        ServerPlayer kid = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, name));
        BlockPos k = helper.absolutePos(at.offset(3, 0, 0));
        kid.teleportTo(k.getX() + 0.5, k.getY(), k.getZ() + 0.5);
        return new Cast(source, cow, husk, kid);
    }

    private static List<String> lines(String effect, Map<String, String> params) {
        EffectCatalogue cat = EffectCatalogue.load();
        Map<String, String> p = new LinkedHashMap<>(params);
        p.put("TAG", SOURCE_TAG);
        for (String d : cat.get(effect).derived()) {
            if (!d.equals("VICTIMS")) {
                p.putIfAbsent(d, "40");
            }
        }
        return cat.render(effect, p).lines().map(String::strip).filter(l -> l.startsWith("/"))
                .map(l -> l.substring(1)).toList();
    }

    private static void run(GameTestHelper helper, List<String> commands) {
        ServerLevel level = helper.getLevel();
        CommandSourceStack src = level.getServer().createCommandSourceStack().withLevel(level)
                .withSuppressedOutput().withPermission(4);
        for (String c : commands) {
            level.getServer().getCommands().performPrefixedCommand(src, c);
        }
    }

    private static boolean slowed(LivingEntity e) {
        return e.hasEffect(MobEffects.SLOWNESS);
    }

    private static void expect(GameTestHelper helper, String what, Cast c, boolean cow, boolean monster,
                               boolean kid) {
        String got = "cow=" + slowed(c.cow()) + " monster=" + slowed(c.monster()) + " child=" + slowed(c.kid());
        helper.assertTrue(slowed(c.cow()) == cow && slowed(c.monster()) == monster && slowed(c.kid()) == kid,
                Component.literal(what + ": wanted cow=" + cow + " monster=" + monster + " child=" + kid + ", got " + got));
        helper.assertTrue(!slowed(c.source()), Component.literal(what + ": the creature caught its own effect"));
    }

    private static final String SLOW = "effect give @s minecraft:slowness 10 1";

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void anAuraWithNoTargetNeverCatchesTheChild(GameTestHelper helper) {
        Cast c = cast(helper, "mfam-victims-aura");
        run(helper, lines("inflict_nearby", Map.of("EFFECT", "minecraft:slowness", "RADIUS", "5")));
        expect(helper, "inflict_nearby, no TARGET", c, true, true, false);
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void anAuraForHimCatchesOnlyHim(GameTestHelper helper) {
        Cast c = cast(helper, "mfam-victims-forhim");
        run(helper, lines("inflict_nearby", Map.of("EFFECT", "minecraft:slowness", "RADIUS", "5",
                "TARGET", "players")));
        expect(helper, "inflict_nearby, TARGET=players", c, false, false, true);
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void anAuraForTheMonstersCatchesOnlyMonsters(GameTestHelper helper) {
        Cast c = cast(helper, "mfam-victims-monsters");
        run(helper, lines("inflict_nearby", Map.of("EFFECT", "minecraft:slowness", "RADIUS", "5",
                "TARGET", "monsters")));
        expect(helper, "inflict_nearby, TARGET=monsters", c, false, true, false);
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aBiteWithNoTargetNeverLandsOnTheChild(GameTestHelper helper) {
        Cast c = cast(helper, "mfam-victims-bite");
        var magic = helper.getLevel().damageSources().magic();
        c.cow().hurt(magic, 1.0F);
        c.monster().hurt(magic, 1.0F);
        // A player who just joined is invulnerable for a few seconds, so his punch never lands;
        // the template reads only HurtTime, so the window is set as a hit would set it.
        c.kid().hurtTime = 10;
        helper.assertTrue(c.kid().hurtTime > 0 && c.cow().hurtTime > 0, Component.literal("everyone must be in the hurt window"));
        run(helper, lines("on_hit", Map.of("COMMAND", SLOW, "RADIUS", "5")));
        expect(helper, "on_hit, no TARGET", c, true, true, false);
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aBreathWithNoTargetNeverBurnsTheChild(GameTestHelper helper) {
        Cast c = cast(helper, "mfam-victims-breath");
        run(helper, lines("breath_cone", Map.of("COMMAND", SLOW)));
        expect(helper, "breath_cone, no TARGET", c, true, true, false);
        helper.succeed();
    }
}
