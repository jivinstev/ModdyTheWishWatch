package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.AttackClipMigration;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptOrientation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * <b>Every attack is SEEN in front, as the screen receives it</b>.
 *
 * <p>The rig plays a swing over {@link VanillaRigAnimator#SWING_TICKS} ticks and writes a pose only every
 * {@link RigAnimator#DEFAULT_INTERVAL}, so a swing reaches a client as one or two poses, and which ones depends on the
 * phase the swing started in. A strike keyframe between those samples is never seen. The attack every creature had
 * ({@link AttackClipMigration#OLD}) was that case: on two phases of three every pose sent had the arm
 * BEHIND the body, and the owner saw "his arm seems to face backwards".
 *
 * <p>So, for every attack clip on an arm-like bone (the template and every starter, read as {@code ModelRepository}
 * reads it, migration included), on EVERY phase at least one pose sent must carry the limb's tip in front of where
 * it hangs by a quarter of its length. Forward is the rig's: a stored creature faces −Z
 * ({@link SculptOrientation#faceForward}) and the rig turns a bone by {@code R_x(+x)} ({@link RigTransform#rotation}),
 * so a positive x swings a hanging limb forward (the sign {@link RigLocomotion#pitch} spells too). The tip is the
 * bone's farthest corner from its pivot, turned about that pivot on top of its rest tilt, as the rig composes it.
 */
class ClipCadenceTest {

    /** A clip whose job is to hit something. */
    static final Pattern ATTACK = Pattern.compile("attack|strike|swing|punch|slam|smash|swipe|hit");

    /** A bone that strikes with its tip. A jaw's bite or a tail's swipe is not judged. */
    static final Pattern LIMB = Pattern.compile("(?i)arm|hand|fist|claw|paw|fore");

    /** How far in front of rest the tip must be seen, as a fraction of the limb's length. */
    static final double IN_FRONT = 0.25;

    /** A plain hanging arm, facing −Z: shoulder pivot at the origin, the box straight down. */
    static final List<CreatureSculpt.Part> ARM = List.of(new CreatureSculpt.Part("armR", null, new float[]{0, 0, 0},
            new float[]{-1, -12, -1}, new float[]{1, 0, 1}, 0xFFFFFF, CreatureSculpt.NO_ROTATION, 0f, null, 0));

    @Test
    void theTemplateIsSeenInFrontOnEveryPhase() {
        List<String> bad = check("template", ARM, CreatureFiles.animations());
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void theOldTemplateIsSeenOnlyBehindOnTwoPhases() {
        // The control: the clip the owner saw must fail, on exactly the two phases worked out by hand.
        JsonObject root = new JsonObject();
        JsonObject anims = new JsonObject();
        anims.add("animation.sculpted.attack", AttackClipMigration.OLD.deepCopy());
        root.add("animations", anims);
        BedrockAnimation old = BedrockAnimation.find(BedrockAnimation.parseAll(root), BedrockAnimation.Clip.ATTACK);
        assertEquals(List.of(0, 2), phasesNotSeenInFront(rig(ARM), "armR", old));
    }

    @Test
    void everyStarterAttackIsSeenInFrontOnEveryPhase() throws IOException {
        List<String> bad = new ArrayList<>();
        int judged = 0;
        try (Stream<Path> w = Files.walk(Path.of("src/main/resources"))) {
            for (Path anims : w.filter(p -> p.getFileName().toString().equals("animations.json")).sorted().toList()) {
                Path sculpt = anims.resolveSibling("sculpt.json");
                if (!Files.exists(sculpt)) {
                    continue;
                }
                JsonObject s = JsonParser.parseString(Files.readString(sculpt)).getAsJsonObject();
                if (!s.has("parts")) {
                    continue;
                }
                JsonObject root = JsonParser.parseString(Files.readString(anims)).getAsJsonObject();
                AttackClipMigration.migrate(root);   // as ModelRepository.animations reads it
                bad.addAll(check(anims.toString(), CreatureSculpt.parseParts(s.getAsJsonArray("parts")), root));
                judged++;
            }
        }
        assertTrue(judged >= 10, "only " + judged + " starters were found to judge");
        assertTrue(bad.isEmpty(), bad.size() + " attack(s) are not seen in front on some phase:\n"
                + String.join("\n", bad));
    }

    // ------------------------------------------------------------------------------------- the migration

    @Test
    void anExactOldTemplateIsMigratedToTheCurrentAttack() {
        JsonObject root = CreatureFiles.animations();
        root.getAsJsonObject("animations").add("animation.sculpted.attack", AttackClipMigration.OLD.deepCopy());
        JsonObject idle = root.getAsJsonObject("animations").getAsJsonObject("animation.sculpted.idle").deepCopy();
        assertTrue(AttackClipMigration.migrate(root));
        assertEquals(AttackClipMigration.current(),
                root.getAsJsonObject("animations").getAsJsonObject("animation.sculpted.attack"));
        assertEquals(idle, root.getAsJsonObject("animations").getAsJsonObject("animation.sculpted.idle"));
        assertFalse(AttackClipMigration.migrate(root), "a second read migrates nothing");
    }

    @Test
    void anAuthoredAttackIsLeftAlone() {
        // One keyframe different from the old template: somebody made it, so it is theirs.
        JsonObject tweaked = AttackClipMigration.OLD.deepCopy();
        tweaked.getAsJsonObject("bones").getAsJsonObject("armR").getAsJsonObject("rotation")
                .add("0.45", JsonParser.parseString("[40,0,0]"));
        JsonObject root = new JsonObject();
        JsonObject anims = new JsonObject();
        anims.add("animation.custom.attack", tweaked.deepCopy());
        root.add("animations", anims);
        assertFalse(AttackClipMigration.migrate(root));
        assertEquals(tweaked, anims.get("animation.custom.attack"));
    }

    @Test
    void theOldTemplateUnderAnyNameIsMigrated() {
        JsonObject root = new JsonObject();
        JsonObject anims = new JsonObject();
        anims.add("animation.dino_1a2b.attack", AttackClipMigration.OLD.deepCopy());
        anims.add("animation.dino_1a2b.walk", AttackClipMigration.OLD.deepCopy());   // not an attack: untouched
        root.add("animations", anims);
        assertTrue(AttackClipMigration.migrate(root));
        assertEquals(AttackClipMigration.current(), anims.get("animation.dino_1a2b.attack"));
        assertEquals(AttackClipMigration.OLD, anims.get("animation.dino_1a2b.walk"));
    }

    // ------------------------------------------------------------------------------------- the check

    /** The attack clips in {@code animations} not seen in front on some phase, for the creature drawn by {@code raw}. */
    static List<String> check(String who, List<CreatureSculpt.Part> raw, JsonObject animations) {
        List<CreatureSculpt.Part> rig = rig(raw);
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, BedrockAnimation> e : BedrockAnimation.parseAll(animations).entrySet()) {
            String name = e.getKey().substring(e.getKey().lastIndexOf('.') + 1).toLowerCase();
            if (!ATTACK.matcher(name).find()) {
                continue;
            }
            for (String bone : e.getValue().pose(0f).keySet()) {
                if (!LIMB.matcher(bone).find()) {
                    continue;
                }
                List<Integer> phases = phasesNotSeenInFront(rig, bone, e.getValue());
                if (!phases.isEmpty()) {
                    bad.add(who + " " + e.getKey() + " " + bone + ": never seen in front on phase(s) " + phases);
                }
            }
        }
        return bad;
    }

    /** As the live path draws it: faced forward (−Z), mirrored into the rig's space. */
    static List<CreatureSculpt.Part> rig(List<CreatureSculpt.Part> raw) {
        return RigFit.intoRigSpace(SculptOrientation.faceForward(raw));
    }

    /** The phases (0…interval−1) on which no pose the rig writes carries {@code bone}'s tip in front. */
    static List<Integer> phasesNotSeenInFront(List<CreatureSculpt.Part> rig, String bone, BedrockAnimation attack) {
        List<CreatureSculpt.Part> mine = rig.stream().filter(p -> p.name().equals(bone)).toList();
        List<Integer> out = new ArrayList<>();
        if (mine.isEmpty()) {
            return out;   // a clip may name a bone the creature does not have (CreatureFiles' javadoc)
        }
        CreatureSculpt.Part p = mine.get(0);
        float[] tip = farthestCorner(mine, p.pivot());
        double len = Math.sqrt(sq(tip[0] - p.pivot()[0]) + sq(tip[1] - p.pivot()[1]) + sq(tip[2] - p.pivot()[2]));
        double rest = forward(p, tip, new float[3]);
        int interval = RigAnimator.DEFAULT_INTERVAL;
        for (int phase = 0; phase < interval; phase++) {
            boolean front = false;
            for (int swing = 1; swing < VanillaRigAnimator.SWING_TICKS; swing++) {
                if ((swing + phase) % interval != 0) {
                    continue;   // RigAnimator.writesOn: this tick writes nothing
                }
                // AnimationState: an attack's progress (swing / SWING_TICKS) is a fraction of the clip's length
                float seconds = swing / (float) VanillaRigAnimator.SWING_TICKS * attack.length();
                BedrockAnimation.BonePose pose = attack.pose(seconds).get(bone);
                if (forward(p, tip, pose == null ? new float[3] : pose.rotation()) > rest + IN_FRONT * len) {
                    front = true;
                }
            }
            if (!front) {
                out.add(phase);
            }
        }
        return out;
    }

    /** How far toward the face (−Z) the tip is, with the pose composed on the rest tilt as the rig does. */
    static double forward(CreatureSculpt.Part p, float[] tip, float[] poseDeg) {
        Quaternionf own = RigTransform.rotation(poseDeg).mul(RigTransform.rotation(
                p.rotation() == null ? CreatureSculpt.NO_ROTATION : p.rotation()));
        return -new Vector3f(tip[0] - p.pivot()[0], tip[1] - p.pivot()[1], tip[2] - p.pivot()[2]).rotate(own).z;
    }

    static float[] farthestCorner(List<CreatureSculpt.Part> parts, float[] pivot) {
        float[] best = null;
        double far = -1;
        for (CreatureSculpt.Part p : parts) {
            for (int i = 0; i < 8; i++) {
                float[] c = {(i & 1) == 0 ? p.from()[0] : p.to()[0], (i & 2) == 0 ? p.from()[1] : p.to()[1],
                        (i & 4) == 0 ? p.from()[2] : p.to()[2]};
                double d = sq(c[0] - pivot[0]) + sq(c[1] - pivot[1]) + sq(c[2] - pivot[2]);
                if (d > far) {
                    far = d;
                    best = c;
                }
            }
        }
        return best;
    }

    private static double sq(double d) {
        return d * d;
    }
}
