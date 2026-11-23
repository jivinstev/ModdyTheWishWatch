package com.moddingfromamod.vanilla;

import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.mob.MobTweak;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.mob.PetMoves;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A rig rebuild keeps the animal, not just the species</b>.
 *
 * <p>"Follow me" is stored on ONE carrier ({@code PetBehaviours.apply}); "make him bigger" edits
 * the species, and the rig answers a species edit by reaping the carrier and building a new one
 * ({@code VanillaRigAnimator.respawn}). That carried the name, effects, glow and team — and not
 * the pet behaviour, so the dragon that followed him stopped, and nobody said so.
 *
 * <p>Two tests, because the two failures look different:
 * <ul>
 *   <li>{@link #aFollowerStillFollowsAfterItIsMadeBigger} measures FOLLOWING — distance to the
 *       child over time, on two legs, one before the rebuild (the control: it did follow) and one
 *       after. Reading the flag would pass a carrier that has the flag and no tracking. It has a
 *       batch and a floor to itself, because a companion follows the nearest player.</li>
 *   <li>{@link #everythingSetOnOneAnimalSurvivesARebuild} reads {@link CarrierState} before and
 *       after, so every field on that list is checked, including ones added later.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigRebuildKeepsTheAnimalGameTest {

    /** A creature that does NOT follow of its own accord: the starter dragon, made wild. */
    private static final String WILD = "{\"base\":\"wild\",\"powers\":[]}";

    private static final BlockPos WALK_TO = SpeciesOnRigGameTest.WALK_TO;
    private static final BlockPos WALK_BACK = SpeciesOnRigGameTest.WALK_BACK;
    private static final double ARRIVED = PetMoves.FOLLOW_CLOSE + 1.5;
    private static final int LEG_TICKS = 400;

    @GameTest(template = "follow_walk", batch = "t6g4_follow", timeoutTicks = 1400)
    public static void aFollowerStillFollowsAfterItIsMadeBigger(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String id = SpeciesOnRigGameTest.plant(helper, "gt_t6g4_follower", WILD);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-t6g4-follow");
        stand(helper, child, WALK_TO);
        var at = helper.absolutePos(SpeciesOnRigGameTest.SPOT);
        LivingEntity first = CreatureSpawn.spawn(level, id, at.getX() + 0.5, at.getY(),
                at.getZ() + 0.5, 0f, EntitySpawnReason.COMMAND, false);
        helper.assertTrue(first instanceof Mob, Component.literal("the creature did not stand up"));
        helper.assertTrue(!PetBehaviours.of(first).isActive(),
                Component.literal("the creature follows on its own, so this proves nothing about 'follow me'"));
        // What modify_mob does with "make him follow me" — the real per-animal path.
        ModifyMobTool.applyTo(level.getScoreboard(), first, tweak("{\"follow\":true}"));

        // leg 1 = walking to WALK_TO on the original carrier; 2 = rebuilt, walking to WALK_BACK;
        // 3 = came both times.
        int[] leg = {1};
        double[] bigger = {Double.NaN};   // the scale "make him bigger" put to the species
        long[] legStart = {helper.getTick()};
        double[] start = {first.distanceTo(child), -1};
        AtomicReference<Mob> now = new AtomicReference<>((Mob) first);
        StringBuilder trace = new StringBuilder();
        helper.onEachTick(() -> {
            if (leg[0] == 3) {
                return;
            }
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c == null) {
                return;   // between the reap and the rebuild
            }
            now.set(c);
            double d = c.distanceTo(child);
            long elapsed = helper.getTick() - legStart[0];
            if (elapsed % 20 == 0) {   //: a red run says what it was doing, not only where it ended
                var p = c.getNavigation().getPath();
                trace.append(String.format("%n    t%d leg%d d=%.1f path=%s done=%s ground=%s hunting=%s pos=%s diff=%s steered=%s",
                        elapsed, leg[0], d, p == null ? "none" : (p.canReach() ? "reaches" : "partial"),
                        c.getNavigation().isDone(), c.onGround(), RigInstincts.targetOf(c) != null,
                        c.blockPosition().toShortString(), level.getDifficulty(),
                        com.moddingfromamod.mob.PetBehaviours.steeredElsewhere(level, c)));
            }
            if (leg[0] == 1 && d < ARRIVED) {
                // THE REBUILD: "make him bigger", persisted to the species.
                Species sp = SpeciesRegistry.get(id);
                bigger[0] = sp.scale() * 1.25;
                com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, sp.with(bigger[0], null, null,
                        null, null, null));
                stand(helper, child, WALK_BACK);
                leg[0] = 2;
                legStart[0] = helper.getTick();
                return;
            }
            if (leg[0] == 2 && rebuiltAt(c, bigger[0]) && start[1] < 0) {
                // The rebuilt rig's first tick (on a new carrier, or reshaped in place on this one:
                // keeps the carrier when it can, so "rebuilt" is not "a new UUID"). It must
                // have a WALK ahead of it, or reaching him proves nothing (a wanderer on a narrow
                // floor gets there too).
                start[1] = d;
                if (d <= PetMoves.FOLLOW_CLOSE * 3 || d >= PetMoves.FOLLOW_TELEPORT) {
                    helper.fail(Component.literal(String.format("the second leg started %.1f blocks from him: not a "
                            + "walk", d)));
                }
            }
            if (leg[0] == 2 && start[1] >= 0 && d < ARRIVED) {
                leg[0] = 3;
                return;
            }
            if (elapsed > LEG_TICKS) {
                helper.fail(Component.literal(String.format("#947: leg %d of 2 — %s, and it is %.1f blocks from him "
                                + "(started %.1f) after %d ticks; rebuilt=%s stored pet='%s' %s",
                        leg[0], leg[0] == 1 ? "the control: it never followed at all"
                                : "after 'make him bigger' it stopped following him",
                        d, start[leg[0] - 1], elapsed, rebuiltAt(c, bigger[0]),
                        PetBehaviours.stored(c), SpeciesOnRigGameTest.whyNotFollowing(c, child)) + trace));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(leg[0] == 3, Component.literal("still walking (leg " + leg[0] + ")"));
            Mob c = now.get();
            helper.assertTrue(rebuiltAt(c, bigger[0]), Component.literal("the rig was never rebuilt, so this "
                    + "proves nothing about a rebuild"));
            tidy(level, id);
            MockPlayerNetwork.remove(child);
        });
    }

    /**
     * <b>A follower that walking cannot bring to him still comes</b>. He climbed something
     * it cannot: a 3-high platform. Walking takes it to the foot, about five blocks from him, and
     * there it stood for good — re-asked every five ticks for the same walk that ends where it is,
     * with the hop reserved for past {@link PetMoves#FOLLOW_TELEPORT}. That is the red run's
     * signature (5.2 blocks, no path, done); the leash already gave up on a walk that got no closer
     * ({@link PetMoves#stuck}), and following now does the same and hops to him.
     */
    @GameTest(template = "follow_walk", batch = "t6g4_follow_ledge", timeoutTicks = 600)
    public static void aFollowerThatCannotWalkToHimHopsToHim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // A 5x5 platform three blocks high at the far end of the floor; he stands in its middle.
        for (int x = 10; x <= 14; x++) {
            for (int z = 2; z <= 6; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.STONE);
                }
            }
        }
        String id = SpeciesOnRigGameTest.plant(helper, "gt_1931_ledge", WILD);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-1931-ledge");
        stand(helper, child, new BlockPos(12, 4, 4));
        var at = helper.absolutePos(SpeciesOnRigGameTest.SPOT);
        LivingEntity made = CreatureSpawn.spawn(level, id, at.getX() + 0.5, at.getY(),
                at.getZ() + 0.5, 0f, EntitySpawnReason.COMMAND, false);
        helper.assertTrue(made instanceof Mob, Component.literal("the creature did not stand up"));
        ModifyMobTool.applyTo(level.getScoreboard(), made, tweak("{\"follow\":true}"));
        long t0 = helper.getTick();
        double[] closestOnFoot = {Double.MAX_VALUE};
        helper.onEachTick(() -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            if (c != null && c.getY() < child.getY() - 2) {
                closestOnFoot[0] = Math.min(closestOnFoot[0], c.distanceTo(child));
            }
            if (c != null && helper.getTick() - t0 > 400) {
                helper.fail(Component.literal(String.format("#1931: it could not walk up to him and never "
                                + "came: %.1f blocks from him after %d ticks (closest on foot %.1f) %s",
                        c.distanceTo(child), helper.getTick() - t0, closestOnFoot[0],
                        SpeciesOnRigGameTest.whyNotFollowing(c, child))));
            }
        });
        helper.succeedWhen(() -> {
            Mob c = SpeciesOnRigGameTest.carrier(helper, id);
            helper.assertTrue(c != null && c.distanceTo(child) < ARRIVED,
                    Component.literal("not with him yet"));
            // It must have tried to WALK first and been stopped short, or this proves nothing
            // about a walk that ends short (a creature dropped beside him passes trivially).
            helper.assertTrue(closestOnFoot[0] > ARRIVED && closestOnFoot[0] < PetMoves.FOLLOW_TELEPORT,
                    Component.literal(String.format("the platform did not stop its walk short of him "
                            + "(closest on foot %.1f), so this proves nothing", closestOnFoot[0])));
            tidy(level, id);
            MockPlayerNetwork.remove(child);
        });
    }

    @GameTest(template = "empty_test", batch = "t6g4_state", timeoutTicks = 200)
    public static void everythingSetOnOneAnimalSurvivesARebuild(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String id = SpeciesOnRigGameTest.plant(helper, "gt_t6g4_state", WILD);
        var at = helper.absolutePos(SpeciesOnRigGameTest.SPOT);
        LivingEntity made = CreatureSpawn.spawn(level, id, at.getX() + 0.5, at.getY(),
                at.getZ() + 0.5, 0f, EntitySpawnReason.COMMAND, false);
        helper.assertTrue(made instanceof Mob, Component.literal("the creature did not stand up"));
        Mob c = (Mob) made;
        // Through modify_mob, as the child asks it: "stop following me" (an explicit OFF, which
        // must not come back as the species default), friendly, rideable, and can't be hurt.
        ModifyMobTool.applyTo(level.getScoreboard(), c, tweak(
                "{\"follow\":false,\"friendly\":true,\"rideable\":true,\"invulnerable\":true}"));
        // And what a nametag, a datapack and a command put on one animal.
        c.setCustomName(Component.literal("Sparky"));
        c.setCustomNameVisible(true);
        c.addTag("his_datapack_marked_this_one");
        c.setNoAi(true);
        c.addEffect(new MobEffectInstance(MobEffects.SPEED,
                MobEffectInstance.INFINITE_DURATION, 1));
        CarrierState was = CarrierState.of(c);
        helper.assertTrue(!was.pet().isEmpty() && was.noAi() && was.invulnerable()
                && was.tags().contains("his_datapack_marked_this_one"),
                Component.literal("the setup did not take, so this proves nothing: " + was));

        Species sp = SpeciesRegistry.get(id);
        double bigger = sp.scale() * 1.25;
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, sp.with(bigger, null, null, null, null, null));
        helper.succeedWhen(() -> {
            Mob after = SpeciesOnRigGameTest.carrier(helper, id);
            helper.assertTrue(after != null && rebuiltAt(after, bigger),
                    Component.literal("the rig has not been rebuilt yet"));
            CarrierState is = CarrierState.of(after);
            helper.assertTrue(is.equals(was), Component.literal("#947: a rebuild lost what was set on this ONE "
                    + "animal.\n  before: " + was + "\n  after:  " + is));
            helper.assertTrue(PetBehaviours.of(after).follow() == Boolean.FALSE,
                    Component.literal("'stop following me' did not survive the rebuild"));
            tidy(level, id);
        });
    }

    // ===================== helpers =====================

    /**
     * Has this creature's rig been rebuilt from the species at {@code scale}? Read off the rig, not
     * the carrier's identity: a rebuild that keeps its carrier type reshapes IN PLACE
     * (same UUID), so "a new UUID" stopped meaning "rebuilt" — and these two tests, which asked it
     * that way, failed the 2026-10-22 release.
     */
    static boolean rebuiltAt(Mob c, double scale) {
        Species built = c == null || Double.isNaN(scale) ? null : VanillaRigAnimator.builtFrom(c.getUUID());
        return built != null && Math.abs(built.scale() - scale) < 1e-6;
    }

    private static MobTweak tweak(String json) {
        return MobTweak.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static void stand(GameTestHelper helper, ServerPlayer child, BlockPos rel) {
        var at = helper.absolutePos(rel);
        child.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    private static void tidy(ServerLevel level, String id) {
        for (LivingEntity c : com.moddingfromamod.mob.Creatures.carriersOf(level, id)) {
            com.moddingfromamod.mob.Creatures.remove(level, c);
        }
    }
}
