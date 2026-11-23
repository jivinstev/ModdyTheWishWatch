package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.CallPets;
import com.moddingfromamod.item.CreatureHorn;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.mob.PetMoves;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

import java.util.UUID;

/**
 * <b>A wished mount never flies off for good, waits to be climbed on, and comes when its horn is
 * blown</b>. Live, 2026-11-01: a rideable dragon kept flying away and he never got on it — a
 * flying creature rides a bat, and a bat's own random flight ran whenever nobody was riding it.
 *
 * <p>Through the real paths: the species says {@code flies} and {@code rideable}, its rig gets the
 * bat carrier, and PetBehaviours, BatRideMixin, CallPets and CreatureHorn do the rest. Each test is
 * in a batch of its own (they share a plot spot and the static pet sets).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class MountLeashAndHornGameTest {

    private static double flat(Mob m, BlockPos p) {
        double dx = m.getX() - (p.getX() + 0.5), dz = m.getZ() - (p.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Pulled 24 blocks off its home (past the {@link PetMoves#LEASH}), an unridden flying mount comes
     * back inside, then roams for ten seconds without leaving it. Its rider stands off holding
     * something, so it is not waiting for him.
     */
    @GameTest(template = "empty_test", batch = "mount_leash", timeoutTicks = 20 * 60)
    public static void anUnriddenFlyingMountStaysInsideItsLeash(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-leash");
        String id = "leash_dragon";
        Mob[] c = new Mob[1];
        BlockPos[] home = new BlockPos[1];
        double[] worst = {0};
        GameTestSequence seq = helper.startSequence();
        seq.thenExecute(() -> {
            RideFitProbeGameTest.plant(helper, id, "starter:pet_dragon", "flies");
            RideFitProbeGameTest.wish(helper, id);
        });
        seq.thenWaitUntil(() -> helper.assertTrue(RideFitProbeGameTest.carrier(helper, id) != null
                && VanillaRigAnimator.rigOf(RideFitProbeGameTest.carrier(helper, id)) != null, Component.literal("no rig yet")));
        seq.thenExecute(() -> {
            c[0] = RideFitProbeGameTest.carrier(helper, id);
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.flies(c[0]), Component.literal("a flying mount rides a flying carrier, not a " + c[0].getType()));
            kid.getInventory().clearContent();
            kid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
            home[0] = c[0].blockPosition();
            // A GameTest ticks only the plot's own chunks: 24 blocks off, the carrier simply stopped
            // (measured: tickCount 20 before the move, 20 for ever after). Force the chunks it crosses.
            forceAround(helper, home[0], true);
        });
        seq.thenExecute(() -> {
            PetBehaviours.setLeashHome(c[0], home[0]);
            // Somebody's, with him away: an unowned follower follows the nearest player, and in a full
            // Gate B run that was another test's mock player still standing in the level (2026-11-02).
            PetBehaviours.claim(c[0], UUID.randomUUID());
            kid.teleportTo(c[0].getX() + 10, c[0].getY(), c[0].getZ());
            c[0].teleportTo(c[0].getX() - 24, c[0].getY() + 2, c[0].getZ());
        });
        seq.thenWaitUntil(() -> helper.assertTrue(flat(c[0], home[0]) <= PetMoves.LEASH_BACK + 2,
                Component.literal(String.format("pulled 24 blocks off, it is still %.1f from home; pet=%s holding=%s tracked=%s noAi=%s "
                        + "ticks=%d v=%s pos=%s home=%s", flat(c[0], home[0]), PetBehaviours.of(c[0]), PetBehaviours.holding(c[0]),
                        PetBehaviours.tracked(helper.getLevel()), c[0].isNoAi(), c[0].tickCount, c[0].getDeltaMovement(),
                        c[0].blockPosition(), home[0]))));
        for (int t = 0; t < 200; t += 5) {
            seq.thenExecuteAfter(5, () -> worst[0] = Math.max(worst[0], flat(c[0], home[0])));
        }
        seq.thenExecute(() -> {
            forceAround(helper, home[0], false);
            helper.assertTrue(worst[0] <= PetMoves.LEASH + 3, Component.literal(String.format(
                    "left alone for 10 s it went %.1f blocks from home (leash %.0f)", worst[0], PetMoves.LEASH)));
        });
        seq.thenSucceed();
    }

    /**
     * Hovering above him and nobody on it, a mount WAITS while he is close with a free hand — it stops
     * and a flyer comes down — and goes back to its own life the moment he picks something up.
     */
    @GameTest(template = "empty_test", batch = "mount_wait", timeoutTicks = 20 * 40)
    public static void aMountWaitsForItsRiderAndComesDown(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-wait");
        String id = "wait_dragon";
        Mob[] c = new Mob[1];
        double[] high = {0};
        GameTestSequence seq = helper.startSequence();
        seq.thenExecute(() -> {
            RideFitProbeGameTest.plant(helper, id, "starter:pet_dragon", "flies");
            RideFitProbeGameTest.wish(helper, id);
        });
        seq.thenWaitUntil(() -> helper.assertTrue(RideFitProbeGameTest.carrier(helper, id) != null
                && VanillaRigAnimator.rigOf(RideFitProbeGameTest.carrier(helper, id)) != null, Component.literal("no rig yet")));
        seq.thenExecute(() -> {
            c[0] = RideFitProbeGameTest.carrier(helper, id);
            kid.getInventory().clearContent();
            c[0].teleportTo(c[0].getX(), c[0].getY() + 4, c[0].getZ());
            high[0] = c[0].getY();
            kid.teleportTo(c[0].getX() + 2, c[0].getY() - 4, c[0].getZ());
        });
        seq.thenWaitUntil(() -> {
            helper.assertTrue(PetBehaviours.waiting(c[0]), Component.literal("a free hand 4 blocks off: it is not waiting"));
            int ground = helper.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, c[0].getBlockX(), c[0].getBlockZ());
            helper.assertTrue(c[0].getY() <= ground + 1.0, Component.literal(String.format(
                    "waiting, it should come down to him: at y=%.1f (was %.1f), ground %d", c[0].getY(), high[0], ground)));
        });
        seq.thenExecute(() -> kid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE)));
        seq.thenWaitUntil(() -> helper.assertFalse(PetBehaviours.waiting(c[0]),
                Component.literal("he picked up a stone: it should go back to its own life")));
        seq.thenSucceed();
    }

    /**
     * Its named horn brings HIS creature to his side, and never a stranger's of the same kind; it
     * lives where he called it from then on. The horn's name says what it calls.
     */
    @GameTest(template = "empty_test", batch = "mount_horn", timeoutTicks = 20 * 40)
    public static void itsNamedHornBringsOnlyHisOwnToHim(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-horn");
        String id = "horn_dragon";
        Mob[] c = new Mob[2];
        GameTestSequence seq = helper.startSequence();
        seq.thenExecute(() -> {
            RideFitProbeGameTest.plant(helper, id, "starter:pet_dragon", "flies");
            RideFitProbeGameTest.wish(helper, id);
            RideFitProbeGameTest.wish(helper, id);
        });
        seq.thenWaitUntil(() -> {
            var all = helper.getLevel().getEntitiesOfClass(Mob.class,
                    new net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos.ZERO)).inflate(48),
                    e -> e.getTags().contains(DisplayRig.RIG_TAG)
                            && e.getTags().contains(com.moddingfromamod.entity.WishMobTags.forSpecies(id)));
            helper.assertTrue(all.size() >= 2, Component.literal("waiting for two of them: " + all.size()));
            c[0] = all.get(0);
            c[1] = all.get(1);
        });
        seq.thenExecute(() -> {
            forceAround(helper, c[0].blockPosition(), true);
            PetBehaviours.claim(c[0], kid.getUUID());
            PetBehaviours.claim(c[1], UUID.randomUUID());   // somebody else's
            kid.getInventory().clearContent();
            kid.teleportTo(c[0].getX() + 14, c[0].getY(), c[0].getZ() + 14);
            Species sp = SpeciesRegistry.get(id);
            ItemStack horn = CreatureHorn.make(kid, CreatureHorn.kindFor(sp), sp.name());
            helper.assertTrue(horn.getHoverName().getString().equals("Call " + sp.name()),
                    Component.literal("the horn must say what it calls: '" + horn.getHoverName().getString() + "'"));
            kid.setItemInHand(InteractionHand.MAIN_HAND, horn);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickItem(kid, InteractionHand.MAIN_HAND));
            helper.assertTrue(kid.getTags().contains(CallPets.TAG + CreatureHorn.kindFor(sp)),
                    Component.literal("blowing the horn did not call: " + kid.getTags()));
        });
        // The call puts it in a 1.5-block ring round him; a bat wandering near cannot pass this.
        seq.thenWaitUntil(() -> helper.assertTrue(c[0].distanceTo(kid) < 2.5,
                Component.literal(String.format("his creature did not come: %.1f blocks off", c[0].distanceTo(kid)))));
        seq.thenExecute(() -> {
            helper.assertTrue(c[1].distanceTo(kid) > 6, Component.literal("a stranger's creature came to HIS horn"));
            helper.assertTrue(PetBehaviours.leashHome(c[0]).closerThan(kid.blockPosition(), 3),
                    Component.literal("called to him, it should live where he is now: home " + PetBehaviours.leashHome(c[0])));
            forceAround(helper, c[1].blockPosition(), false);
        });
        seq.thenSucceed();
    }

    /**
     * {@code wishmob spawn} of a RIDEABLE creature hands him its horn once — a second spawn (a re-wish,
     * his button) does not give another — and makes the creature his.
     */
    @GameTest(template = "empty_test", batch = "mount_horn_given", timeoutTicks = 20 * 40)
    public static void aRideableCreatureComesWithOneHorn(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-horn-given");
        String id = "given_dragon";
        GameTestSequence seq = helper.startSequence();
        seq.thenExecute(() -> {
            RideFitProbeGameTest.plant(helper, id, "starter:pet_dragon", "flies");
            kid.getInventory().clearContent();
            var src = kid.createCommandSourceStack().withPermission(4);
            kid.getServer().getCommands().performPrefixedCommand(src, "wishmob spawn " + id);
            kid.getServer().getCommands().performPrefixedCommand(src, "wishmob spawn " + id);
        });
        seq.thenWaitUntil(() -> {
            Species sp = SpeciesRegistry.get(id);
            String kind = CreatureHorn.kindFor(sp);
            int horns = 0;
            for (ItemStack s : kid.getInventory().getNonEquipmentItems()) {
                horns += kind.equals(CreatureHorn.callsOf(s)) ? s.getCount() : 0;
            }
            for (ItemStack s : com.moddingfromamod.bag.WishBagService.contents(kid.getServer(), kid.getUUID())) {
                horns += kind.equals(CreatureHorn.callsOf(s)) ? s.getCount() : 0;
            }
            helper.assertTrue(horns == 1, Component.literal("two spawns of a rideable creature: expected ONE horn, he has " + horns));
            Mob any = near(helper, id, kid);
            helper.assertTrue(any != null && kid.getUUID().toString().equals(PetBehaviours.ownerStored(any)),
                    Component.literal("the creature spawned for him should be his"));
        });
        seq.thenSucceed();
    }

    /**
     * His horn still calls his creature after he CHANGED it (2026-11-13, a Bedrock player's report: "no
     * matter how close I am to my dragons, I use the horn and it says my dragon has gone too far away").
     * Changing a creature that is not his alone — the library's, every starter — forks it: the carrier
     * now wears {@code <id>~<his short id>}, and a horn handed over for {@code <id>} matched species ids
     * EXACTLY, so it heard nothing from then on. And a horn handed over for the fork itself was not a
     * horn at all: its kind carries the {@code ~}, which {@link CallPets#isKindWord} refused.
     */
    @GameTest(template = "empty_test", batch = "mount_horn_fork", timeoutTicks = 20 * 40)
    public static void hisHornStillCallsHisCreatureAfterHeChangedIt(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-horn-fork");
        String id = "hornfork_dragon";
        String forkId = com.moddingfromamod.marshal.ArtifactOwner.forkKey(id, kid.getUUID().toString());
        Mob[] c = new Mob[1];
        ItemStack[] baseHorn = new ItemStack[1];
        UUID wish = UUID.randomUUID();
        String worker = "gt-horn-fork-" + wish;
        GameTestSequence seq = helper.startSequence();
        seq.thenExecute(() -> {
            RideFitProbeGameTest.plant(helper, id, "starter:pet_dragon", "flies");
            // Somebody else's design (as the library's is), so his change forks it.
            com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, SpeciesRegistry.get(id)
                    .withOwner(UUID.nameUUIDFromBytes("gt-horn-fork-brother".getBytes()).toString()));
            RideFitProbeGameTest.wish(helper, id);
        });
        seq.thenWaitUntil(() -> helper.assertTrue(RideFitProbeGameTest.carrier(helper, id) != null
                && VanillaRigAnimator.rigOf(RideFitProbeGameTest.carrier(helper, id)) != null, Component.literal("no rig yet")));
        seq.thenExecute(() -> {
            c[0] = RideFitProbeGameTest.carrier(helper, id);
            forceAround(helper, c[0].blockPosition(), true);
            PetBehaviours.claim(c[0], kid.getUUID());
            kid.getInventory().clearContent();
            Species sp = SpeciesRegistry.get(id);
            baseHorn[0] = CreatureHorn.make(kid, CreatureHorn.kindFor(sp), sp.name());
            // "Make him giant", as his agent: a fork of his own.
            com.moddingfromamod.marshal.Marshal.global().submit(wish, kid.getUUID(), "make my dragon giant");
            com.moddingfromamod.marshal.Marshal.global().assign(worker, wish);
            com.moddingfromamod.agent.AgentContext.bind(worker);
            try {
                com.moddingfromamod.agent.tools.ModifyMobTool.applyTo(helper.getLevel().getScoreboard(), c[0],
                        com.moddingfromamod.mob.MobTweak.fromJson(com.google.gson.JsonParser
                                .parseString("{\"scale\":" + (sp.scale() * 1.5) + "}").getAsJsonObject()));
            } finally {
                com.moddingfromamod.agent.AgentContext.unbind();
            }
            helper.assertTrue(SpeciesRegistry.get(forkId) != null, Component.literal("no fork was made: " + forkId));
        });
        seq.thenWaitUntil(() -> {
            Mob f = RideFitProbeGameTest.carrier(helper, forkId);
            helper.assertTrue(f != null, Component.literal("nothing wears the fork " + forkId + " yet"));
            c[0] = f;
        });
        seq.thenExecute(() -> {
            helper.assertTrue(kid.getUUID().toString().equals(PetBehaviours.ownerStored(c[0])),
                    Component.literal("the forked creature stopped being his: owner '" + PetBehaviours.ownerStored(c[0]) + "'"));
            kid.teleportTo(c[0].getX() + 10, c[0].getY(), c[0].getZ() + 10);
            kid.setItemInHand(InteractionHand.MAIN_HAND, baseHorn[0]);
            // The call itself, so a mount that drifts over on its own cannot pass for one that heard.
            int came = CallPets.call(kid, CreatureHorn.callsOf(baseHorn[0]));
            helper.assertTrue(came == 1, Component.literal("his horn for '" + id + "' heard " + came
                    + " creature(s); his changed '" + forkId + "' is " + String.format("%.1f", c[0].distanceTo(kid)) + " blocks off"));
        });
        seq.thenWaitUntil(() -> helper.assertTrue(c[0].distanceTo(kid) < 2.5,
                Component.literal(String.format("his horn for '%s' did not bring his changed '%s': %.1f blocks off",
                        id, forkId, c[0].distanceTo(kid)))));
        seq.thenExecute(() -> {
            forceAround(helper, c[0].blockPosition(), false);
            com.moddingfromamod.marshal.Marshal.global().finish(wish);
            helper.assertTrue(CreatureHorn.kindFor(SpeciesRegistry.get(forkId)).equals(CreatureHorn.kindFor(SpeciesRegistry.get(id))),
                    Component.literal("his fork's horn and the original's call different words: "
                            + CreatureHorn.kindFor(SpeciesRegistry.get(forkId))));
            // A horn handed over for the fork before the fix carries the fork's own id, '~' and all.
            ItemStack forkHorn = CreatureHorn.make(kid, "creature_" + forkId, "x");
            helper.assertTrue(CreatureHorn.callsOf(forkHorn) != null,
                    Component.literal("a horn handed over for his fork '" + forkId + "' is not read as a horn at all"));

        });
        seq.thenSucceed();
    }

    /**
     * He climbs on with its HORN in hand (2026-11-13, a Bedrock player: he blew his dragon's horn, then
     * pressed the Ride button Geyser drew over it, horn still in hand — and stayed on the ground). A mount
     * took only an empty hand or the watch. Through the real click ({@code interactOn}, which a Bedrock tap
     * reaches through Geyser and a version proxy (ViaProxy) as INTERACT_AT + INTERACT, read from both jars).
     */
    @GameTest(template = "empty_test", batch = "mount_horn_ride", timeoutTicks = 20 * 40)
    public static void heClimbsOnWithItsHornInHand(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-horn-ride");
        String id = "hornride_dragon";
        GameTestSequence seq = helper.startSequence();
        seq.thenExecute(() -> {
            RideFitProbeGameTest.plant(helper, id, "starter:pet_dragon", "flies");
            RideFitProbeGameTest.wish(helper, id);
        });
        seq.thenWaitUntil(() -> helper.assertTrue(RideFitProbeGameTest.carrier(helper, id) != null
                && VanillaRigAnimator.rigOf(RideFitProbeGameTest.carrier(helper, id)) != null, Component.literal("no rig yet")));
        seq.thenExecute(() -> {
            Mob c = RideFitProbeGameTest.carrier(helper, id);
            PetBehaviours.claim(c, kid.getUUID());
            kid.getInventory().clearContent();
            Species sp = SpeciesRegistry.get(id);
            kid.setItemInHand(InteractionHand.MAIN_HAND, CreatureHorn.make(kid, CreatureHorn.kindFor(sp), sp.name()));
            kid.teleportTo(c.getX() + 1.5, c.getY(), c.getZ());
            kid.interactOn(c, InteractionHand.MAIN_HAND);
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.mountOf(kid) == c, Component.literal(
                    "a click with his dragon's horn in hand did not climb him on: he rides " + kid.getRootVehicle()));
            kid.stopRiding();
            // Something that does a thing to a creature stays that thing: a lead is not a ride.
            kid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEAD));
            kid.interactOn(c, InteractionHand.MAIN_HAND);
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.mountOf(kid) == null,
                    Component.literal("a click with a LEAD climbed him on"));
        });
        seq.thenSucceed();
    }

    /** One of {@code id}'s carriers near him (a wishmob spawn puts it where he can see it). */
    private static Mob near(GameTestHelper helper, String id, ServerPlayer kid) {
        String tag = com.moddingfromamod.entity.WishMobTags.forSpecies(id);
        for (Mob m : helper.getLevel().getEntitiesOfClass(Mob.class, kid.getBoundingBox().inflate(32),
                e -> e.getTags().contains(DisplayRig.RIG_TAG) && e.getTags().contains(tag) && e.isAlive())) {
            return m;
        }
        return null;
    }

    /** Force (or release) the chunks within 40 blocks of {@code at}, so a creature out there ticks. */
    private static void forceAround(GameTestHelper helper, BlockPos at, boolean on) {
        var level = helper.getLevel();
        int r = 3;
        for (int cx = (at.getX() >> 4) - r; cx <= (at.getX() >> 4) + r; cx++) {
            for (int cz = (at.getZ() >> 4) - r; cz <= (at.getZ() >> 4) + r; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }
}
