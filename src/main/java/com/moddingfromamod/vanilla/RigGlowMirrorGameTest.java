package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.mob.EntityHandles;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: glowing belongs on the rig, and never on the invisible carrier.</b>
 *
 * <p>Every source here writes the CARRIER, the way the shipped paths do, and the rig is driven by
 * the real {@link VanillaRigAnimator} tick — nothing calls {@link DisplayRig#setGlowing} by hand,
 * because a test that did would be re-proving scoreboard row S6 (the displays CAN glow) and not
 * this issue (nothing made them). Two facts are asserted for each source:
 *
 * <ul>
 *   <li>every display carries {@code Glowing}, in the carrier's team colour when it has one;</li>
 *   <li>the carrier's shared flag byte, as a client would be SENT it, has bit 6 clear — the bit
 *       {@code LivingEntityRenderer} turns into a {@code RenderType.outline} of the invisible mob,
 *       which is the horse-shaped outline that was filmed.</li>
 * </ul>
 *
 * <p>And that turning it off clears the displays, which is half of what a mirror is.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class RigGlowMirrorGameTest {

    private static final BlockPos SPOT = new BlockPos(2, 2, 2);

    private static DisplayRig drivenSpikor(GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        double scale = SpikeFixture.scale(SpikeFixture.SPIKOR);
        var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(),
                Vec3.atBottomCenterOf(helper.absolutePos(SPOT)), 0f, parts, scale,
                CreatureAnatomy.widthBlocks(extents) * scale,
                CreatureAnatomy.heightBlocks(extents) * scale, "spikor");
        VanillaRigAnimator.drive(rig, RigFit.intoRigSpace(parts), Map.of());
        return rig;
    }

    /**
     * Bit 6 of the shared flags as the client would be told it — read from the synced values
     * themselves ({@code getNonDefaultValues}, the payload of a fresh tracking packet), because
     * {@code getSharedFlag} is protected and the server's {@code isCurrentlyGlowing()} deliberately
     * still answers from the tag.
     */
    static boolean carrierOutlineSent(Entity e) {
        List<SynchedEntityData.DataValue<?>> values = e.getEntityData().getNonDefaultValues();
        if (values == null) {
            return false;
        }
        for (var v : values) {
            if (v.id() == 0 && v.value() instanceof Byte b) {   // DATA_SHARED_FLAGS_ID is id 0
                return (b & (1 << 6)) != 0;
            }
        }
        return false;
    }

    private static int override(Entity display) {
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(display);
        return tag.contains("glow_color_override") ? tag.getIntOr("glow_color_override", 0) : -1;
    }

    /** Every display glows (or none does) in {@code rgb}; the carrier sends no outline either way. */
    private static void assertMirrored(GameTestHelper helper, DisplayRig rig, boolean on, int rgb,
                                       String source) {
        Mob base = rig.base();
        helper.assertFalse(carrierOutlineSent(base), Component.literal(source + ": the invisible carrier must not "
                + "be told to draw an outline -- that is the horse-shaped outline of #775"));
        for (Entity d : rig.displays()) {
            helper.assertTrue(d.hasGlowingTag() == on, Component.literal(source + ": every display should "
                    + (on ? "" : "stop ") + "glow; one of " + rig.displays().size() + " did not"));
            if (on) {
                helper.assertTrue(override(d) == rgb, Component.literal(source + ": outline colour should be "
                        + Integer.toHexString(rgb) + ", was " + Integer.toHexString(override(d))));
            }
        }
    }

    @GameTest(template = "empty_test", timeoutTicks = 100)
    public static void theGlowingTagIsMirroredAndTheCarrierDrawsNoOutline(GameTestHelper helper) {
        DisplayRig rig = drivenSpikor(helper);
        helper.startSequence()
                .thenExecuteAfter(2, () -> rig.base().setGlowingTag(true))
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(rig.base().hasGlowingTag(),
                            Component.literal("the tag stays the source of truth -- only the synced flag is off"));
                    assertMirrored(helper, rig, true, -1, "setGlowingTag");
                })
                .thenExecuteAfter(1, () -> rig.base().setGlowingTag(false))
                .thenExecuteAfter(2, () -> assertMirrored(helper, rig, false, -1, "tag off"))
                .thenSucceed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 100)
    public static void theGlowingEffectIsMirrored(GameTestHelper helper) {
        DisplayRig rig = drivenSpikor(helper);
        helper.startSequence()
                .thenExecuteAfter(2, () -> rig.base().addEffect(
                        new MobEffectInstance(MobEffects.GLOWING, 600, 0)))
                // The effect reaches the flag only through tickEffects -> updateGlowingStatus, on
                // the carrier's own tick, so this waits for one.
                .thenExecuteAfter(3, () -> assertMirrored(helper, rig, true, -1, "GLOWING effect"))
                .thenExecuteAfter(1, () -> rig.base().removeEffect(MobEffects.GLOWING))
                .thenExecuteAfter(3, () -> assertMirrored(helper, rig, false, -1, "effect off"))
                .thenSucceed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 100)
    public static void aTeamColourBecomesTheOutlineColour(GameTestHelper helper) {
        DisplayRig rig = drivenSpikor(helper);
        var scoreboard = helper.getLevel().getScoreboard();
        String name = "glowtest_" + rig.base().getUUID().toString().substring(0, 8);
        PlayerTeam team = scoreboard.addPlayerTeam(name);
        team.setColor(java.util.Optional.of(net.minecraft.world.scores.TeamColor.RED));
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    scoreboard.addPlayerToTeam(rig.base().getScoreboardName(), team);
                    rig.base().setGlowingTag(true);
                })
                .thenExecuteAfter(2, () -> assertMirrored(helper, rig, true,
                        net.minecraft.world.scores.TeamColor.RED.rgb(), "red team"))
                // A colour change with no glow change is still a change.
                .thenExecuteAfter(1, () -> team.setColor(java.util.Optional.of(net.minecraft.world.scores.TeamColor.BLUE)))
                .thenExecuteAfter(2, () -> assertMirrored(helper, rig, true,
                        net.minecraft.world.scores.TeamColor.BLUE.rgb(), "team recoloured"))
                .thenExecuteAfter(1, () -> rig.base().setGlowingTag(false))
                .thenExecuteAfter(2, () -> assertMirrored(helper, rig, false, -1, "tag off"))
                .thenExecuteAfter(1, () -> scoreboard.removePlayerTeam(team))
                .thenSucceed();
    }

    /** The shipped writer, end to end: modify_mob addressing the creature by its handle. */
    /**
     * A batch of its own: its handle names the carrier by the carrier's TYPE, and in the default
     * batch {@code ModifyMobGameTest} runs {@code StaleHandles.forgetEvery(COW)} concurrently —
     * on 1.21.1 this Spikor rides a cow, and its handle vanished between the two calls
     * ("I don't know the handle @cow2", the gate, first failure in 196 runs).
     */
    @GameTest(template = "empty_test", batch = "rig_glow_modify", timeoutTicks = 300)
    public static void modifyMobGlowIsMirrored(GameTestHelper helper) {
        DisplayRig rig = drivenSpikor(helper);
        String handle = EntityHandles.global().remember(rig.base().getUUID(),
                EntityType.getKey(rig.base().getType()).toString(), "glowing spikor");
        AtomicReference<ToolResult> on = new AtomicReference<>();
        AtomicReference<ToolResult> off = new AtomicReference<>();
        offThread(() -> on.set(new ModifyMobTool().call(JsonParser.parseString(
                "{\"scope\":\"one\",\"target\":\"" + handle + "\",\"glow\":\"pink\"}")
                .getAsJsonObject())));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(on.get() != null, Component.literal("waiting for modify_mob"));
                    helper.assertFalse(on.get().isError(), Component.literal("modify_mob: " + on.get().text()));
                    assertMirrored(helper, rig, true, net.minecraft.world.scores.TeamColor.LIGHT_PURPLE.rgb(),
                            "modify_mob glow pink");
                })
                .thenExecute(() -> offThread(() -> off.set(new ModifyMobTool().call(
                        JsonParser.parseString("{\"scope\":\"one\",\"target\":\"" + handle
                                + "\",\"glow\":\"none\"}").getAsJsonObject()))))
                .thenWaitUntil(() -> {
                    helper.assertTrue(off.get() != null, Component.literal("waiting for modify_mob off"));
                    helper.assertFalse(off.get().isError(), Component.literal("modify_mob off: " + off.get().text()));
                    assertMirrored(helper, rig, false, -1, "modify_mob glow none");
                })
                .thenSucceed();
    }

    private static void offThread(Runnable work) {
        Thread t = new Thread(work, "rig-glow-gametest");
        t.setDaemon(true);
        t.start();
    }
}
