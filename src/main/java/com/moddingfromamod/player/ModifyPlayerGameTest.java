package com.moddingfromamod.player;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.ModifyPlayerTool;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * The player-effects verb, headless at last —.
 *
 * <h2>Why this works where the first attempt did not</h2>
 *
 * <p>The withdrawn version installed a mock into the server's ONE shared player list via
 * {@code MockPlayerNetwork.inLevel}. Six tests in a batch then each changed somebody else's player,
 * and every FAILING test leaked its own (teardown sat in {@code succeedWhen}, which only runs on
 * success) — taking four correct, unrelated tests down with it. That is an earlier issue's failure mode,
 * committed while fixing.
 *
 * <p>This one uses {@link GameTestHelper#makeMockPlayer}, which does <b>not</b> call
 * {@code placeNewPlayer}: the subject is plot-local and invisible to every other test. Nothing is
 * shared, so nothing can leak, and there is no teardown to forget. The tool's application path was
 * widened to {@code LivingEntity} to make that possible — nothing in it ever needed a
 * {@code ServerPlayer}.
 *
 * <p><b>What is deliberately NOT here:</b> which player the tool resolves as "the child". That is
 * one line, is meaningless without a real singleplayer world, and belongs to the Gate-D probe.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ModifyPlayerGameTest {

    private static void apply(Player subject, String power, PlayerPowers.Level level) {
        ModifyPlayerTool.applyOne(subject, PlayerPowers.find(power), false, -1, level,
                new ArrayList<>());
    }

    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "modifyplayer")
    public void aSizePowerReallyResizesAndCanBeUndone(GameTestHelper helper) {
        Player subject = helper.makeMockPlayer(GameType.SURVIVAL);
        double before = subject.getAttributeValue(Attributes.SCALE);

        apply(subject, "giant", PlayerPowers.Level.NORMAL);
        double giant = subject.getAttributeValue(Attributes.SCALE);
        helper.assertTrue(giant > before, Component.literal("asking to be giant left him at " + giant));

        // A power with no way out is a trap — and "make me normal size" is a wish the agent invented
        // for itself, unprompted, in the very first measured run.
        apply(subject, "normal_size", PlayerPowers.Level.NORMAL);
        helper.assertTrue(Math.abs(subject.getAttributeValue(Attributes.SCALE) - 1.0) < 0.001,
                Component.literal("he could not get back to his own size"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "modifyplayer")
    public void aLevelMakesItStrongerInThePowersOwnDirection(GameTestHelper helper) {
        Player big = helper.makeMockPlayer(GameType.SURVIVAL);
        apply(big, "giant", PlayerPowers.Level.NORMAL);
        double plain = big.getAttributeValue(Attributes.SCALE);
        apply(big, "giant", PlayerPowers.Level.SUPER);
        helper.assertTrue(big.getAttributeValue(Attributes.SCALE) > plain,
                Component.literal("'super giant' was no bigger than plain giant"));

        // ...and SMALLER for a power that points the other way, which a plain multiplier gets wrong.
        Player small = helper.makeMockPlayer(GameType.SURVIVAL);
        apply(small, "tiny", PlayerPowers.Level.NORMAL);
        double tiny = small.getAttributeValue(Attributes.SCALE);
        apply(small, "tiny", PlayerPowers.Level.SUPER);
        helper.assertTrue(small.getAttributeValue(Attributes.SCALE) < tiny,
                Component.literal("'super tiny' made him bigger"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "modifyplayer")
    public void anEffectPowerLastsForeverAndComesOffAgain(GameTestHelper helper) {
        Player subject = helper.makeMockPlayer(GameType.SURVIVAL);
        apply(subject, "fast", PlayerPowers.Level.NORMAL);

        var effect = subject.getActiveEffects().stream().findFirst().orElse(null);
        helper.assertTrue(effect != null, Component.literal("'fast' gave him no effect at all"));
        // A child asking to be fast does not mean "for thirty seconds", and a power that quietly
        // expires reads as the wish having broken rather than as time passing.
        helper.assertTrue(effect.isInfiniteDuration(),
                Component.literal("the power runs out after " + effect.getDuration() + " ticks"));

        ModifyPlayerTool.applyOne(subject, PlayerPowers.find("fast"), true, -1,
                PlayerPowers.Level.NORMAL, new ArrayList<>());
        helper.assertTrue(subject.getActiveEffects().isEmpty(), Component.literal("the power could not be taken off"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "modifyplayer")
    public void aBigJumpLandsSoftly(GameTestHelper helper) {
        // Being hurt by your own wish is the thing to avoid: a jump high enough to be fun is high
        // enough to hurt on the way down, so slow falling rides with it.
        Player subject = helper.makeMockPlayer(GameType.SURVIVAL);
        apply(subject, "jump", PlayerPowers.Level.NORMAL);
        List<String> got = subject.getActiveEffects().stream()
                .map(e -> String.valueOf(net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
                        .getKey(e.getEffect().value())))
                .toList();
        helper.assertTrue(got.stream().anyMatch(id -> id.endsWith("jump_boost")), Component.literal("no jump: " + got));
        helper.assertTrue(got.stream().anyMatch(id -> id.endsWith("slow_falling")),
                Component.literal("he can jump high and will hurt himself landing: " + got));
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "modifyplayer")
    public void neverDieMeansHurtButNotKilled(GameTestHelper helper) {
        // His words: "let me get hurt but NEVER die". BOTH halves are asserted, because answering it
        // with invincibility would remove the half he actually asked for.
        Player subject = helper.makeMockPlayer(GameType.SURVIVAL);
        apply(subject, "never_die", PlayerPowers.Level.NORMAL);
        helper.assertTrue(NeverDie.isOn(subject), Component.literal("the never-die flag was not set"));

        subject.setHealth(subject.getMaxHealth());
        subject.setInvulnerableTime(0);
        subject.hurt(subject.damageSources().generic(), 6.0f);
        helper.assertTrue(subject.getHealth() < subject.getMaxHealth(),
                Component.literal("he was not hurt at all — that is invincibility, not what he asked for"));

        subject.setInvulnerableTime(0);
        subject.hurt(subject.damageSources().generic(), 1000.0f);
        helper.assertTrue(subject.isAlive(), Component.literal("a killing blow still killed him"));
        helper.assertTrue(subject.getHealth() > 0, Component.literal("he is alive with no health"));
        helper.succeed();
    }
}
