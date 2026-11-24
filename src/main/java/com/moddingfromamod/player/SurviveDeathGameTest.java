package com.moddingfromamod.player;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.ModifyPlayerTool;
import com.moddingfromamod.testing.MockPlayerNetwork;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A forever power comes back after a REAL death and respawn</b> ({@link SurviveDeath}).
 *
 * <p>Not a simulated clone: the player dies and goes through {@code PlayerList.respawn}, which
 * builds the new body with vanilla's own {@code restoreFrom} and fires NeoForge's
 * {@code PlayerEvent.Clone} from inside it. So this proves three things a unit test cannot: that the
 * handler is on the right bus (a wrong bus compiles and does nothing), that the old body still holds
 * its effects when the event fires, and that attributes need no help.
 *
 * <p>It needs a real {@code ServerPlayer} in the player list, so it uses
 * {@link MockPlayerNetwork#inLevel} and sits in the {@code mockplayer} batch with the other tests
 * that do. Respawning replaces the player object, and the mock's automatic removal knows only the
 * OLD one, so the new one is removed here, in a {@code finally}: the test is synchronous, so nothing
 * needs to be held across ticks.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SurviveDeathGameTest {

    @GameTest(template = "empty_test", batch = "mockplayer", timeoutTicks = 100)
    public static void aForeverPowerComesBackAfterARealDeath(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-forever");
        ServerPlayer reborn = null;
        try {
            // modify_player's path: "make me fast", no seconds, so forever.
            ModifyPlayerTool.applyOne(child, PlayerPowers.find("fast"), false, PlayerPowers.INFINITE,
                    PlayerPowers.Level.NORMAL, new ArrayList<>());
            // run_command's path: `effect give @s dolphins_grace infinite 0 true`.
            child.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, PlayerPowers.INFINITE,
                    0, false, false, true));
            // A thirty-second effect. It must still END on death, as vanilla has it.
            child.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600));
            // An attribute power: "make me giant". Vanilla carries base values by itself.
            ModifyPlayerTool.applyOne(child, PlayerPowers.find("giant"), false, PlayerPowers.INFINITE,
                    PlayerPowers.Level.NORMAL, new ArrayList<>());
            double giant = child.getAttributeValue(Attributes.SCALE);
            helper.assertTrue(child.getEffect(MobEffects.SPEED) != null,
                    Component.literal("'fast' was never granted, so its absence after death would prove nothing"));

            child.die(child.damageSources().generic());
            reborn = helper.getLevel().getServer().getPlayerList().respawn(child, false, Entity.RemovalReason.KILLED);

            helper.assertTrue(reborn != child, Component.literal("respawn returned the same body, so nothing was tested"));
            // The trap this class is built around: respawn removes the old body with KILLED, which
            // CLEARS its effects before the Clone event fires. If the old body still held them, a
            // Clone-only copy would work and this test would not be testing the design.
            helper.assertTrue(child.getActiveEffects().isEmpty(),
                    Component.literal("the old body still holds its effects after respawn, so vanilla changed and the "
                            + "LivingDeathEvent stash may no longer be needed: " + child.getActiveEffects()));
            MobEffectInstance speed = reborn.getEffect(MobEffects.SPEED);
            helper.assertTrue(speed != null && speed.isInfiniteDuration(),
                    Component.literal("'fast' was granted forever and was gone after he died: " + speed));
            helper.assertTrue(speed.getAmplifier() == PlayerPowers.find("fast").buffs().get(0).level(),
                    Component.literal("the power came back at a different level: " + speed.getAmplifier()));
            MobEffectInstance swim = reborn.getEffect(MobEffects.DOLPHINS_GRACE);
            helper.assertTrue(swim != null && swim.isInfiniteDuration(),
                    Component.literal("an infinite effect given with a command was gone after he died: " + swim));
            helper.assertTrue(reborn.getEffect(MobEffects.NIGHT_VISION) == null,
                    Component.literal("a thirty-second effect survived his death, which vanilla would not do"));
            helper.assertTrue(Math.abs(reborn.getAttributeValue(Attributes.SCALE) - giant) < 0.001,
                    Component.literal("'giant' did not survive his death: " + reborn.getAttributeValue(Attributes.SCALE)
                            + " (was " + giant + ")"));
            helper.assertTrue(!SurviveDeath.remembering(reborn),
                    Component.literal("the stash was given back and kept, so the NEXT death would restore it twice"));
            helper.succeed();
        } finally {
            if (reborn != null && reborn != child) {
                MockPlayerNetwork.remove(reborn);
            }
        }
    }

    /**
     * A death {@link NeverDie} prevents is not a death. It is cancelled before the LOWEST listener,
     * so nothing is written down, and a later real death cannot restore stale effects from it.
     * Plot-local: no respawn is needed to ask this.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "modifyplayer")
    public static void aDeathNeverDiePreventsWritesNothingDown(GameTestHelper helper) {
        var subject = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ModifyPlayerTool.applyOne(subject, PlayerPowers.find("never_die"), false, PlayerPowers.INFINITE,
                PlayerPowers.Level.NORMAL, new ArrayList<>());
        ModifyPlayerTool.applyOne(subject, PlayerPowers.find("fast"), false, PlayerPowers.INFINITE,
                PlayerPowers.Level.NORMAL, new ArrayList<>());
        subject.invulnerableTime = 0;
        subject.hurt(subject.damageSources().generic(), 1000.0f);
        helper.assertTrue(subject.isAlive(), Component.literal("never_die did not hold, so this asks nothing"));
        helper.assertTrue(!SurviveDeath.remembering(subject),
                Component.literal("a death that never happened left effects written down for the next real one"));
        helper.succeed();
    }

    /**
     * the attribute powers. Granted forever, they must come back after a real death (vanilla
     * copies attribute base values on respawn, and saturation rides the effect stash), and they must
     * DO what they say: a long fall does no harm, a knockback does not move him. Asserting only the
     * attribute values would pass a power wired to the wrong attribute.
     */
    @GameTest(template = "empty_test", batch = "mockplayer", timeoutTicks = 100)
    public static void attributePowersComeBackAndWork(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-attrpowers");
        ServerPlayer reborn = null;
        try {
            for (String power : java.util.List.of("no_fall_damage", "no_knockback", "never_hungry")) {
                ModifyPlayerTool.applyOne(child, PlayerPowers.find(power), false, PlayerPowers.INFINITE,
                        PlayerPowers.Level.NORMAL, new ArrayList<>());
            }
            helper.assertTrue(child.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER) == 0.0,
                    Component.literal("no_fall_damage did not set the fall-damage multiplier"));

            child.die(child.damageSources().generic());
            reborn = helper.getLevel().getServer().getPlayerList().respawn(child, false, Entity.RemovalReason.KILLED);
            helper.assertTrue(reborn != child, Component.literal("respawn returned the same body, so nothing was tested"));

            helper.assertTrue(reborn.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER) == 0.0,
                    Component.literal("no_fall_damage did not survive his death: "
                            + reborn.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER)));
            helper.assertTrue(reborn.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) == 1.0,
                    Component.literal("no_knockback did not survive his death"));
            MobEffectInstance full = reborn.getEffect(MobEffects.SATURATION);
            helper.assertTrue(full != null && full.isInfiniteDuration(),
                    Component.literal("never_hungry was granted forever and was gone after he died: " + full));

            // What the powers DO, on the body he has now.
            reborn.setHealth(reborn.getMaxHealth());
            float before = reborn.getHealth();
            reborn.causeFallDamage(30f, 1f, reborn.damageSources().fall());
            helper.assertTrue(reborn.getHealth() == before,
                    Component.literal("a 30-block fall hurt him with no_fall_damage: " + before + " -> " + reborn.getHealth()));
            reborn.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            reborn.knockback(1.5, 1.0, 0.0);
            helper.assertTrue(reborn.getDeltaMovement().horizontalDistance() < 1e-6,
                    Component.literal("a knockback moved him with no_knockback: " + reborn.getDeltaMovement()));

            // And taking a power away gives vanilla back.
            ModifyPlayerTool.applyOne(reborn, PlayerPowers.find("no_fall_damage"), true,
                    PlayerPowers.INFINITE, PlayerPowers.Level.NORMAL, new ArrayList<>());
            helper.assertTrue(reborn.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER) == 1.0,
                    Component.literal("removing no_fall_damage did not restore vanilla's fall damage"));
            helper.succeed();
        } finally {
            if (reborn != null && reborn != child) {
                MockPlayerNetwork.remove(reborn);
            }
        }
    }
}
