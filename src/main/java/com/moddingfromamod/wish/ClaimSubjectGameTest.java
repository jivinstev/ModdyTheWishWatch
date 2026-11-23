package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.vanilla.CreatureSpawn;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.HitProxies;
import com.moddingfromamod.vanilla.SpikeFixture;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A claim check counts the creature it names, through the one resolver.</b>
 *
 * <p>the {@code ClaimWorld.matches} decides what a {@code creature_count} / {@code follows} /
 * {@code stays_healthy} check counts, and it carried a stop-gap matcher of its own. A hit proxy is a
 * real mob wearing no wishmob tag, so a check naming the proxy's TYPE counted it — measured on a Muse
 * retry that checked {@code llama} and counted the rig's invisible hit proxy, "which says nothing
 * about the creature". The resolver never answers with a rig's internals.
 *
 * <p>Kept: {@code pickleball_player} still finds species {@code pickleball_player_a} and
 * "Pickleball Player Red" (a false refusal, since fixed), and a mob TYPE still means that type
 * exactly, so a count of zombies is not a count of zombie villagers.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ClaimSubjectGameTest {

    private static final String CASE = com.moddingfromamod.vanilla.SpikeFixture.real("tralalelo-armA");

    @GameTest(template = "empty_test", batch = "t6g1b_claims", timeoutTicks = 300)
    public static void aClaimCountsTheCreatureAndNeverItsMachinery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String id = "gt_pickleball_player_a";
        plant(helper, id, "Pickleball Player Red");
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
        LivingEntity carrier = CreatureSpawn.spawn(level, id, at.x, at.y, at.z, 0f,
                EntitySpawnReason.COMMAND, false);
        List<Mob> proxies = new ArrayList<>();
        LivingEntity real = null;
        try {
            helper.assertTrue(carrier != null, Component.literal("the creature did not stand up"));
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof Mob m && HitProxies.isProxy(e)
                        && e.getTags().contains(HitProxies.PROXY_OWNER_PREFIX + carrier.getUUID())) {
                    proxies.add(m);
                }
            }
            helper.assertTrue(!proxies.isEmpty(), Component.literal("the creature has no hit proxies, so this proves "
                    + "nothing about them"));
            Entity made = carrier.getType().create(level, EntitySpawnReason.COMMAND);
            real = (LivingEntity) made;
            real.snapTo(at.x + 4, at.y, at.z, 0f, 0f);
            level.addFreshEntity(real);

            for (Mob p : proxies) {
                String type = EntityType.getKey(p.getType()).getPath();
                helper.assertFalse(ClaimWorld.matches(p, type), Component.literal("a claim about '" + type
                        + "' counted the creature's invisible hit proxy — a count of machinery, "
                        + "not of anything the child can see"));
                helper.assertFalse(ClaimWorld.matches(p, "pickleball_player"),
                        Component.literal("a claim about the creature counted its hit proxy as well as the creature"));
            }
            String carrierType = EntityType.getKey(carrier.getType()).getPath();
            helper.assertFalse(ClaimWorld.matches(carrier, carrierType),
                    Component.literal("a claim about real " + carrierType + "s counted the wished creature riding one"));
            helper.assertTrue(ClaimWorld.matches(real, carrierType),
                    Component.literal("a claim about " + carrierType + "s did not count the real one"));
            for (String asked : new String[] {"pickleball_player", "pickleball player",
                    "Pickleball Player Red", id}) {
                helper.assertTrue(ClaimWorld.matches(carrier, asked),
                        Component.literal("the creature is not found by '" + asked + "' — the false refusal"));
            }
            helper.succeed();
        } finally {
            if (carrier != null) {
                DisplayRig.reap(level, carrier.getUUID());
                carrier.discard();
            }
            if (real != null) {
                real.discard();
            }
        }
    }

    private static void plant(GameTestHelper helper, String id, String name) {
        try {
            java.nio.file.Path spec = SpikeFixture.find(
                    SpikeFixture.CORPUS_DIR + "/" + CASE + "/sculpt.json");
            helper.assertTrue(spec != null, Component.literal("the corpus sculpt is missing"));
            CreatureFiles.write(id, SpikeFixture.parts(CASE), java.nio.file.Files.readString(spec));
        } catch (Exception ex) {
            helper.fail(Component.literal("could not write the sculpt: " + ex));
        }
        com.moddingfromamod.testing.SpeciesForTest.put(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"modelKey\":\"" + id + "\","
                        + "\"health\":20,\"speed\":0.0,\"scale\":" + SpikeFixture.scale(CASE) + "}")
                .getAsJsonObject()));
        helper.assertTrue(!WishMobTags.forSpecies(id).equals(WishMobTags.ALL), Component.literal("bad species id"));
    }
}
