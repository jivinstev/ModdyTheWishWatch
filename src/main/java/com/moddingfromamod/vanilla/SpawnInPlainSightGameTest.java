package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A giant creature is not spawned around the child.</b>
 *
 * <p>an earlier issue's sea creature: {@code giant_sea_leaper}, scale <b>4.0</b>, spawned with
 * {@code wishmob spawn}, which stood every creature two blocks in front of the player whatever its
 * size. Production's own log put its parts <b>2.2 m</b> from the child. A model four times life size
 * centred two blocks away has the child's eyes INSIDE it, and from inside, the rig's boxes are not
 * drawn: <i>"I can't see the sea creature you made"</i>. The next wish moved it, and there it was.
 *
 * <p>The oracle is the creature's own drawn volume — its sculpt's extents times its scale, around
 * the carrier — against the child's eyes. Nothing tuned: engulfed or not.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SpawnInPlainSightGameTest {

    private static final double SCALE = 4.0;

    @GameTest(template = "empty_test", batch = "g983_plain_sight", timeoutTicks = 400)
    public static void aGiantCreatureIsNotSpawnedAroundTheChild(GameTestHelper helper) {
        String id = "gt_g983_giant";
        ServerLevel level = helper.getLevel();
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        plant(helper, id, parts);
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        try {
            WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level,
                    mock.position(), mock, "wishmob spawn " + id + " 2");
            List<LivingEntity> standing = carriers(level, id);
            helper.assertTrue(!standing.isEmpty(), Component.literal("nothing stood up: " + r.output()));

            var e = CreatureAnatomy.extents(RigGeometry.of(parts));
            double halfX = (e.maxX() - e.minX()) / 16.0 * SCALE / 2.0;
            double halfZ = (e.maxZ() - e.minZ()) / 16.0 * SCALE / 2.0;
            double height = (e.maxY() - e.minY()) / 16.0 * SCALE;
            double reach = Math.max(halfX, halfZ);
            Vec3 eye = mock.getEyePosition();
            for (LivingEntity c : standing) {
                double dx = eye.x - c.getX();
                double dz = eye.z - c.getZ();
                double flat = Math.sqrt(dx * dx + dz * dz);
                boolean withinHeight = eye.y >= c.getY() - 0.5 && eye.y <= c.getY() + height;
                helper.assertFalse(flat < reach && withinHeight, Component.literal("#983: a scale-" + SCALE
                        + " creature was spawned " + String.format("%.1f", flat) + " blocks from "
                        + "the child's eyes, and it reaches " + String.format("%.1f", reach)
                        + " blocks from its middle — his eyes are INSIDE it, where the rig is not "
                        + "drawn. It said: " + r.output()));
            }
            helper.succeed();
        } finally {
            for (LivingEntity c : carriers(level, id)) {
                DisplayRig.reap(level, c.getUUID());
                c.discard();
            }
            MockPlayerNetwork.remove(mock);
        }
    }

    private static void plant(GameTestHelper helper, String id, List<CreatureSculpt.Part> parts) {
        try {
            java.nio.file.Path spec = SpikeFixture.find(
                    SpikeFixture.CORPUS_DIR + "/" + SpikeFixture.SPIKOR + "/sculpt.json");
            helper.assertTrue(spec != null, Component.literal("the corpus sculpt is missing"));
            CreatureFiles.write(id, parts, java.nio.file.Files.readString(spec));
        } catch (Exception ex) {
            helper.fail(Component.literal("could not write the sculpt: " + ex));
        }
        com.moddingfromamod.testing.SpeciesForTest.put(helper, Species.fromJson(
                JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"Giant\",\"modelKey\":\""
                        + id + "\",\"health\":40,\"speed\":0.0,\"scale\":" + SCALE + "}")
                        .getAsJsonObject()));
    }

    private static List<LivingEntity> carriers(ServerLevel level, String id) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof LivingEntity l && e.isAlive()
                    && e.entityTags().contains(DisplayRig.RIG_TAG)
                    && e.entityTags().contains(WishMobTags.forSpecies(id))) {
                out.add(l);
            }
        }
        return out;
    }
}
