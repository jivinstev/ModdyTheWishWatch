package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.Damage;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A creature's voice, on the shipped rig</b> (chunk 4). Every wished creature was silent:
 * its carrier is muted, and nothing played anything in its place. With a voice it is heard now and
 * then while it is about, when it is hurt, when it dies and as it walks; asked to be silent, or
 * given no voice at all (every creature made before this), it is as quiet as it always was.
 *
 * <p>What is asserted is what {@link RigVoice} PLAYED into the level. That a stock client HEARS it
 * is the audio rig's to prove ({@code tools/audio-proof.sh}, docs/AUDIO.md) — a sound sent is not a
 * sound heard, which is why it plays at a position rather than on the muted carrier.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class VoiceOnRigGameTest {

    private VoiceOnRigGameTest() {
    }

    private static String creature(GameTestHelper helper, String id, String voice) {
        String made = SpeciesOnRigGameTest.plant(helper, id,
                "{\"powers\":[\"friendly\",\"persistent\"],\"damage\":0,\"health\":40"
                        // null: no voice at all — the planted base (the starter pet dragon) has one.
                        + ",\"voice\":" + (voice == null ? "null" : voice) + "}");
        SpeciesOnRigGameTest.wish(helper, made);
        return made;
    }

    private static boolean heard(Mob c, String sound) {
        return RigVoice.heard(c.getUUID()).contains(sound);
    }

    /**
     * Its idle sound, then a blow, then the killing one: idle, hurt and death, each the borrowed
     * mob's own — and the killing blow is a death cry, not a hurt one.
     */
    @GameTest(template = "empty_test", batch = "voice_rig", timeoutTicks = 900)
    public static void itIsHeardIdleHurtAndDying(GameTestHelper helper) {
        String id = creature(helper, "gt_voice_r", "\"ravager\"");
        int[] stage = {0};
        long[] at = {0};
        helper.onEachTick(() -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c == null || !c.isAlive()) {
                return;
            }
            if (stage[0] == 0 && heard(c, "minecraft:entity.ravager.ambient")) {
                stage[0] = 1;
                Damage.hurt(c, helper.getLevel().damageSources().generic(), 2.0F);
                at[0] = helper.getTick();
            } else if (stage[0] == 1 && helper.getTick() > at[0] + 15) {
                helper.assertTrue(heard(c, "minecraft:entity.ravager.hurt"), Component.literal("hit, and no cry: " + RigVoice.heard(c.getUUID())));
                stage[0] = 2;
                java.util.UUID u = c.getUUID();
                c.kill(helper.getLevel());
                List<String> h = RigVoice.heard(u);
                helper.assertTrue(h.contains("minecraft:entity.ravager.death"), Component.literal("killed, and no death cry: " + h));
                helper.assertTrue(h.stream().filter("minecraft:entity.ravager.hurt"::equals).count() == 1,
                        Component.literal("the killing blow cried as a hurt too: " + h));
                helper.succeed();
            }
        });
        helper.runAfterDelay(880, () -> {
            if (stage[0] == 0) {
                Mob c = SpeciesOnRigGameTest.carrier(helper, id);
                helper.fail(Component.literal("it stood about for 44 seconds and never made its idle sound: "
                        + (c == null ? "no carrier" : RigVoice.heard(c.getUUID()))));
            }
        });
    }

    /** Walking, it is heard: its own footfall when the mob has one. */
    @GameTest(template = "empty_test", batch = "voice_steps", timeoutTicks = 300)
    public static void itsFootfallsAreHeardAsItWalks(GameTestHelper helper) {
        String id = creature(helper, "gt_voice_w", "\"wolf\"");
        walkAndCheck(helper, id, h -> h.contains("minecraft:entity.wolf.step"), "a wolf's footfall");
    }

    /** A mob with no step of its own (a cat) walks on the ground's own sound, as vanilla's do. */
    @GameTest(template = "empty_test", batch = "voice_steps_block", timeoutTicks = 300)
    public static void aVoiceWithNoFootfallWalksOnTheGround(GameTestHelper helper) {
        String id = creature(helper, "gt_voice_c", "\"cat\"");
        walkAndCheck(helper, id, h -> h.stream().anyMatch(s -> s.startsWith("minecraft:block.") && s.endsWith(".step")),
                "the ground's own footstep");
    }

    private static void walkAndCheck(GameTestHelper helper, String id,
                                     java.util.function.Predicate<List<String>> want, String what) {
        helper.onEachTick(() -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c != null && c.isAlive() && c.tickCount > 10 && c.onGround()) {
                Vec3 p = c.position();
                c.setPos(p.x + (helper.getTick() / 40 % 2 == 0 ? 0.2 : -0.2), p.y, p.z);   // to and fro
            }
        });
        helper.runAfterDelay(200, () -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            List<String> h = c == null ? List.of() : RigVoice.heard(c.getUUID());
            helper.assertTrue(want.test(h), Component.literal("it walked eight blocks and never made " + what + ": " + h));
            helper.succeed();
        });
    }

    /** "never makes a single sound": silent, and dying silent too. And no voice is as it always was. */
    @GameTest(template = "empty_test", batch = "voice_silent", timeoutTicks = 400)
    public static void silentAndUnvoicedCreaturesMakeNoSound(GameTestHelper helper) {
        String quiet = creature(helper, "gt_voice_s", "\"silent\"");
        String old = creature(helper, "gt_voice_n", null);
        java.util.Map<String, java.util.UUID> ids = new java.util.HashMap<>();
        helper.onEachTick(() -> {
            for (String id : List.of(quiet, old)) {
                Mob c = SpeciesOnRigGameTest.carrier(helper, id);
                if (c != null && c.isAlive()) {
                    ids.put(id, c.getUUID());
                    if (c.tickCount > 20) {
                        Vec3 p = c.position();
                        c.setPos(p.x + (helper.getTick() / 40 % 2 == 0 ? 0.2 : -0.2), p.y, p.z);
                    }
                    if (helper.getTick() == 300) {
                        Damage.hurt(c, helper.getLevel().damageSources().generic(), 2.0F);
                    }
                    if (helper.getTick() == 340) {
                        c.kill(helper.getLevel());
                    }
                }
            }
        });
        helper.runAfterDelay(360, () -> {
            helper.assertTrue(ids.size() == 2, Component.literal("a carrier never appeared: " + ids.keySet()));
            for (var e : ids.entrySet()) {
                helper.assertTrue(RigVoice.heard(e.getValue()).isEmpty(),
                        Component.literal(e.getKey() + " was walked, hit and killed, and made a sound: " + RigVoice.heard(e.getValue())));
            }
            helper.succeed();
        });
    }

    /** The tool refuses a voice the game does not have, by name, before it sculpts anything. */
    @GameTest(template = "empty_test", batch = "voice_tool", timeoutTicks = 100)
    public static void aVoiceTheGameDoesNotHaveIsRefused(GameTestHelper helper) {
        JsonObject a = new JsonObject();
        a.addProperty("id", "gt_voice_bad");
        a.addProperty("voice", "wolfy");
        var r = new com.moddingfromamod.agent.tools.SculptCreatureTool().call(a);
        helper.assertTrue(r.isError() && r.text().contains("wolf"), Component.literal("an unknown voice was not refused by name: " + r.text()));
        JsonObject b = new JsonObject();
        b.addProperty("id", "gt_voice_bad2");
        b.addProperty("voice", "wolf");
        JsonObject s = new JsonObject();
        // 1.21.5 split the wolf's sounds by variant and has no howl at all; the growl is the
        // misspelling-and-correction case now (entity.wolf_growl → entity.wolf.growl).
        s.addProperty("ambient", "minecraft:entity.wolf_growl");
        b.add("sounds", s);
        var r2 = new com.moddingfromamod.agent.tools.SculptCreatureTool().call(b);
        helper.assertTrue(r2.isError() && r2.text().contains("minecraft:entity.wolf.growl"),
                Component.literal("an underscore spelling of the growl was not corrected: " + r2.text()));
        helper.succeed();
    }
}
