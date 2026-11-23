package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.marshal.ArtifactOwner;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.mob.Creatures;
import com.moddingfromamod.mob.MobTweak;
import com.moddingfromamod.species.CreatureFork;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A forked creature shows its fork on the rig</b>.
 *
 * <p>Editing a creature that is not his — the library's (every starter, including the pet dragon
 * every child is given) or his brother's — forks it: his change goes into a copy of his own and
 * the original stays everyone's ({@code ForkPolicy}). {@code persistToSpecies} then pointed "the
 * creature he is looking at" at the fork with {@code if (e instanceof WishMobEntity)}, which is
 * never true on the atom rig. So the carrier kept the base's tag, the base was unchanged, nothing
 * rebuilt, and the tool said <i>"Saved to the creature's design"</i> over a dragon that had not
 * changed at all.
 *
 * <p>The oracle is the rig IN THE WORLD — the drawn size of the carrier's own displays, which is
 * what he sees — never the species record, which was right the whole time. And a second copy of
 * the same creature beside it must not change: only the one he meant is his.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigForkGameTest {

    /** The library's starter pet dragon, spawned the way production spawns it. */
    @GameTest(template = "empty_test", batch = "t6g4_fork_library", timeoutTicks = 400)
    public static void makingTheStarterDragonBiggerShowsOnTheOneHeMeant(GameTestHelper helper) {
        helper.assertTrue(CreatureFork.inLibrary("pet_dragon"),
                Component.literal("the starter pet dragon is not in the library, so this is not the fork path"));
        forkAndLook(helper, "pet_dragon", "mfam-t6g4-lib", null);
    }

    /** His brother's creature: the per-child fork path. */
    @GameTest(template = "empty_test", batch = "t6g4_fork_brother", timeoutTicks = 400)
    public static void makingHisBrothersCreatureBiggerShowsOnTheOneHeMeant(GameTestHelper helper) {
        String id = SpeciesOnRigGameTest.plant(helper, "gt_t6g4_brothers", "{}");
        String brother = UUID.nameUUIDFromBytes("gt-t6g4-brother".getBytes()).toString();
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, SpeciesRegistry.get(id).withOwner(brother));
        forkAndLook(helper, id, "mfam-t6g4-bro", brother);
    }

    private static void forkAndLook(GameTestHelper helper, String base, String childName,
                                    String owner) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, childName);
        UUID wish = UUID.randomUUID();
        Marshal.global().submit(wish, child.getUUID(), "make my dragon bigger");
        String worker = "gt-t6g4-fork-" + wish;
        Marshal.global().assign(worker, wish);
        String forkId = ArtifactOwner.forkKey(base, child.getUUID().toString());
        Set<UUID> already = new HashSet<>();
        for (LivingEntity c : Creatures.carriersOf(level, base)) {
            already.add(c.getUUID());
        }
        List<LivingEntity> two = new ArrayList<>();
        try {
            // The library's creature, as HIS agent spawns it; his brother's, standing where his
            // brother put it (a child's own spawn cannot even name another child's creature).
            String said = "";
            if (owner == null) {
                said = WishExecutor.runOne(server, level, child.position(), child,
                        "wishmob spawn " + base + " 2").output().toString();
            } else {
                for (int i = 0; i < 2; i++) {
                    CreatureSpawn.spawn(level, base, child.getX() + 6 + 4 * i, child.getY(),
                            child.getZ() + 6, 0f,
                            net.minecraft.world.entity.EntitySpawnReason.COMMAND, false);
                }
            }
            for (LivingEntity c : Creatures.carriersOf(level, base)) {
                if (!already.contains(c.getUUID())) {
                    two.add(c);
                }
            }
            helper.assertTrue(two.size() == 2, Component.literal("expected two new " + base + "s, got " + two.size()
                    + ": " + said));
            LivingEntity his = two.get(0);
            LivingEntity other = two.get(1);
            double hisBefore = drawnSize(level, his);
            double otherBefore = drawnSize(level, other);
            double baseScale = SpeciesRegistry.get(base).scale();
            helper.assertTrue(hisBefore > 0 && otherBefore > 0, Component.literal("the creatures have no displays"));

            // A pet-only tweak is about THIS animal, and mints no fork.
            asAgent(worker, () -> ModifyMobTool.applyTo(level.getScoreboard(), his,
                    tweak("{\"follow\":false}")));
            helper.assertTrue(SpeciesRegistry.get(forkId) == null, Component.literal("#945: 'stop following me' "
                    + "forked the creature — a pet behaviour is per-animal, not the design"));

            // "Make him bigger", as HIS agent.
            List<String> notes = new ArrayList<>();
            asAgent(worker, () -> notes.addAll(ModifyMobTool.applyTo(level.getScoreboard(), his,
                    tweak("{\"scale\":" + (baseScale * 1.5) + "}"))));
            helper.assertTrue(SpeciesRegistry.get(forkId) != null, Component.literal("no fork was made: " + notes));

            helper.succeedWhen(() -> {
                List<LivingEntity> forked = Creatures.carriersOf(level, forkId);
                helper.assertTrue(forked.size() == 1, Component.literal("#945: the fork '" + forkId + "' exists "
                        + "and nothing in the world wears it (" + forked.size() + " carriers); "
                        + "the creature he meant still wears '" + base + "': "
                        + Creatures.carriersOf(level, base).contains(his)
                        + ". The tool told him: " + notes));
                helper.assertTrue(forked.get(0).getTags().contains(
                                com.moddingfromamod.entity.WishMobTags.forSpecies(base)),
                        Component.literal("the fork lost its ancestor's tag, so the abilities it inherited (the "
                                + "starter dragon's breath selects @e[tag=wishmob_pet_dragon]) no "
                                + "longer reach it"));
                double hisAfter = drawnSize(level, forked.get(0));
                helper.assertTrue(hisAfter > hisBefore * 1.3, Component.literal(String.format("#945: the creature "
                                + "he meant is drawn %.2f before and %.2f after 'make him "
                                + "bigger' — the fork did not reach what he sees",
                        hisBefore, hisAfter)));
                helper.assertTrue(other.isAlive() && !other.isRemoved()
                                && Creatures.carriersOf(level, base).contains(other),
                        Component.literal("the OTHER " + base + " changed species: only the one he meant is his"));
                double otherAfter = drawnSize(level, other);
                helper.assertTrue(Math.abs(otherAfter - otherBefore) < otherBefore * 0.02,
                        Component.literal(String.format("the other %s changed size: %.2f -> %.2f", base,
                                otherBefore, otherAfter)));
                helper.assertTrue(Math.abs(SpeciesRegistry.get(base).scale() - baseScale) < 1e-6,
                        Component.literal("the base creature's own design changed — a fork must leave it alone"));
                if (owner != null) {
                    helper.assertTrue(owner.equals(SpeciesRegistry.get(base).ownerId()),
                            Component.literal("his brother's creature stopped being his brother's"));
                }
                tidy(level, base, forkId, already);
                Marshal.global().finish(wish);
                MockPlayerNetwork.remove(child);
            });
        } catch (RuntimeException e) {
            tidy(level, base, forkId, already);
            Marshal.global().finish(wish);
            MockPlayerNetwork.remove(child);
            throw e;
        }
    }

    // ===================== helpers =====================

    /** How big the creature is DRAWN: {@link RigReadout#drawnSize}. */
    public static double drawnSize(ServerLevel level, Entity carrier) {
        return RigReadout.drawnSize(level, carrier);
    }

    private static MobTweak tweak(String json) {
        return MobTweak.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static void asAgent(String worker, Runnable body) {
        AgentContext.bind(worker);
        try {
            body.run();
        } finally {
            AgentContext.unbind();
        }
    }

    private static void tidy(ServerLevel level, String base, String forkId, Set<UUID> keep) {
        for (String id : new String[] {base, forkId}) {
            for (LivingEntity c : Creatures.carriersOf(level, id)) {
                if (!keep.contains(c.getUUID())) {
                    Creatures.remove(level, c);
                }
            }
        }
    }
}
