package com.moddingfromamod.vanilla;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.RigProbe;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * <b>: a rig creature talks on command, stops, and turns to a point.</b>
 *
 * <p>The creature is a generic probe written into THIS world at the start of the test (a starter's
 * model plus a talk clip), so the test proves the commands, not any particular character.
 *
 * <p>Everything through the real commands an op (or the media timeline over RCON) types:
 * {@code /wishmob spawn talk_probe}, {@code /wishmob talk on|off <target>}, {@code /wishmob face <target>
 * <x y z>}. The talk assertions are on the ANIMATOR (it started the talk clip on the command's own
 * tick, and stopped), not only on the tag the command set, because "the flag is on the mob" and "the
 * rig is playing the talk clip" are different claims.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigTalkGameTest {

    static final String KEY = "talk_probe";

    /** A starter's shape and skin plus idle/walk/attack and a TALK clip, written into this world. */
    static void writeProbe() throws java.io.IOException {
        java.nio.file.Path dir = com.moddingfromamod.runtime.ModelRepository.dir(KEY);
        java.nio.file.Files.createDirectories(dir);
        // sculpt.json too: without it the creature is a STAND-IN rig, which by design plays no clips at all
        for (String f : new String[] {"geometry.json", "texture.png", "sculpt.json"}) {
            try (var in = RigTalkGameTest.class.getResourceAsStream("/starter-library/species/frog/" + f)) {
                java.nio.file.Files.copy(in, dir.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        com.google.gson.JsonObject anims;
        try (var in = RigTalkGameTest.class.getResourceAsStream("/starter-library/species/frog/animations.json")) {
            anims = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        anims.getAsJsonObject("animations").add("animation." + KEY + ".talk", com.google.gson.JsonParser.parseString(
                "{\"loop\":true,\"animation_length\":0.6,\"bones\":{\"head\":{\"rotation\":"
                        + "{\"0.0\":[0,0,0],\"0.3\":[12,0,0],\"0.6\":[0,0,0]}}}}"));
        java.nio.file.Files.writeString(dir.resolve("animations.json"), anims.toString());
        java.nio.file.Files.writeString(dir.resolve("species.json"), "{\"id\":\"" + KEY + "\",\"modelKey\":\"" + KEY
                + "\",\"name\":\"Talk Probe\",\"body\":\"humanoid\",\"texture\":\"zombie\",\"tint\":\"#FFFFFF\","
                + "\"health\":40.0,\"speed\":0.2,\"scale\":1.0,\"armor\":0.0,\"damage\":0.0,"
                + "\"knockbackResistance\":1.0,\"powers\":[\"friendly\",\"persistent\"],\"voice\":\"silent\"}");
    }

    private static void run(GameTestHelper helper, ServerPlayer op, String line) {
        CommandSourceStack src = op.createCommandSourceStack().withSuppressedOutput().withPermission(2);
        helper.getLevel().getServer().getCommands().performPrefixedCommand(src, line);
    }

    /**
     * His body faces the point, to within 2 degrees, from where he STANDS. Not a literal -90: when the
     * command lands he may be mid-stride (his life walks him about), and he coasts a few hundredths of
     * a block after the hold stops him, so "five blocks east of where he was" is a degree or three off
     * east from where he stops (measured: 9 runs in 40 were walking at the command, one 2.8 degrees).
     */
    private static void assertFacesPoint(GameTestHelper helper, Mob m, Vec3 point) {
        float want = RigFacing.yawTo(point.x - m.getX(), point.z - m.getZ());
        float off = Math.abs(RigHeadYaw.wrap(m.yBodyRot - want));
        helper.assertTrue(off < 2f, Component.literal("facing a point 5 blocks east, his body is at yaw "
                + m.yBodyRot + " (" + off + " degrees off " + want + ", the point from where he stands)"));
    }

    /**
     * Held: he faces the point and his own AI takes no step. As STATE, not position: a hold is "stand,
     * don't walk", not "immovable", and in a full run a leftover from the batch before (a slam's husk
     * and child one plot over) shoved him 0.07 of a block in one tick. Before the fix a stock carrier's
     * stroll started inside its AI step and moved it that tick (4 runs in 40); that stroll is visible
     * here, running, on the tick it starts. (And a goal begun before the hold must not linger: a horse
     * eating runs no AI tick to clean it up, so the hold stops it itself.)
     */
    private static void assertHeld(GameTestHelper helper, Mob m, Vec3 point) {
        assertFacesPoint(helper, m, point);
        var walking = m.goalSelector.getAvailableGoals().stream()
                .filter(w -> w.isRunning() && w.getFlags().contains(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE))
                .map(w -> w.getGoal().getClass().getSimpleName()).toList();
        helper.assertTrue(walking.isEmpty(), Component.literal("held, his own AI is walking him: " + walking));
    }

    @GameTest(template = "empty_test", batch = "rig_talk", timeoutTicks = 1200)
    public static void aRigTalksOnCommandAndTurnsToAPoint(GameTestHelper helper) {
        ServerPlayer op = MockPlayerNetwork.inLevel(helper);
        AtomicReference<Mob> probe = new AtomicReference<>();
        AtomicLong onTick = new AtomicLong(-1);
        Vec3[] point = new Vec3[1];
        try {
            writeProbe();
        } catch (java.io.IOException e) {
            throw new RuntimeException("could not write the talk probe: " + e, e);
        }
        run(helper, op, "wishmob spawn " + KEY);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    LivingEntity c = RigProbe.carrierFor(helper.getLevel(), KEY);
                    helper.assertTrue(c instanceof Mob m && VanillaRigAnimator.isDriving(m),
                            Component.literal("/wishmob spawn " + KEY + " put no driven rig in the world, so a stock "
                                    + "client sees nothing"));
                    probe.set((Mob) c);
                })
                .thenExecute(() -> {
                    Mob m = probe.get();
                    helper.assertTrue(VanillaRigAnimator.talkSince(m.getUUID()) < 0,
                            Component.literal("the probe was talking before anyone asked"));
                    // The probe's clips (written into this world above) must include the talk one, or
                    // "talk on" only idles.
                    var talk = com.moddingfromamod.runtime.BedrockAnimation.find(
                            com.moddingfromamod.runtime.ModelRepository.animations(KEY),
                            com.moddingfromamod.runtime.BedrockAnimation.Clip.TALK);
                    helper.assertTrue(talk != null && ("animation." + KEY + ".talk").equals(talk.name()),
                            Component.literal("the rig has no talk clip for " + KEY + ": " + talk));
                    onTick.set(helper.getLevel().getGameTime());
                    run(helper, op, "wishmob talk on " + m.getUUID());
                    helper.assertTrue(RigTalk.isTalking(m),
                            Component.literal("/wishmob talk on did not set the talking flag"));
                })
                .thenExecuteAfter(1, () -> {
                    // The RIG must be playing the talk clip (a stand-in rig would only idle on the flag).
                    String playing = VanillaRigAnimator.talkClipOf(probe.get().getUUID());
                    helper.assertTrue(("animation." + KEY + ".talk").equals(playing),
                            Component.literal("talk on, but the rig's talk clip is " + playing));
                    long since = VanillaRigAnimator.talkSince(probe.get().getUUID());
                    // Within one tick: a command over RCON runs before the tick's entities and the rig
                    // sees it on the same tick; a GameTest's sequence may run after the animator, so
                    // here the next tick is allowed too. Never later: that would be the pose schedule.
                    helper.assertTrue(since >= onTick.get() && since <= onTick.get() + 1,
                            Component.literal("the rig started talking at tick " + since + ", the command ran at "
                                    + onTick.get() + ": it must be within one tick"));
                })
                .thenExecuteAfter(20, () -> {
                    run(helper, op, "wishmob talk off " + probe.get().getUUID());
                    helper.assertTrue(!RigTalk.isTalking(probe.get()),
                            Component.literal("/wishmob talk off left the flag on"));
                })
                .thenExecuteAfter(1, () -> helper.assertTrue(
                        VanillaRigAnimator.talkSince(probe.get().getUUID()) < 0,
                        Component.literal("the rig kept talking after /wishmob talk off")))
                .thenExecute(() -> {
                    Mob m = probe.get();
                    // Five blocks to his east: he should end up facing +X (yaw -90).
                    point[0] = new Vec3(m.getX() + 5, m.getEyeY(), m.getZ());
                    run(helper, op, String.format(java.util.Locale.ROOT, "wishmob face %s %.3f %.3f %.3f",
                            m.getUUID(), point[0].x, point[0].y, point[0].z));
                    helper.assertTrue(RigFacing.holding(m.getUUID()),
                            Component.literal("/wishmob face set no hold"));
                })
                // facing it on tick 40 AND on each of the next 20 ticks. The carrier's own AI still
                // looks about every tick (ten degrees a step); a hold that loses to it between the
                // carrier's tick and the animator's showed here as "10.0 degrees off" 7 runs in 20.
                .thenExecuteAfter(40, () -> assertHeld(helper, probe.get(), point[0]))
                .thenExecuteFor(20, () -> assertHeld(helper, probe.get(), point[0]))
                .thenExecute(() -> {
                    Mob m = probe.get();
                    run(helper, op, "wishmob face " + m.getUUID() + " free");
                    helper.assertTrue(!RigFacing.holding(m.getUUID()),
                            Component.literal("/wishmob face … free kept the hold"));
                    m.discard();
                    MockPlayerNetwork.remove(op);
                })
                .thenSucceed();
    }
}
