package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.species.Archetype;
import com.moddingfromamod.species.ArchetypeLife;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

import java.util.Set;

/**
 * <b>A sculpted creature lives its archetype, on whatever carrier it rides</b>. Each test
 * plants the same sculpt (the starter pet dragon's body), gives its species one archetype, and watches
 * what the creature DOES — measured in the world, never read off its own state alone.
 *
 * <p>Every test is in its own batch (they share static life state and the plot), and forces the chunks
 * round its home: a GameTest ticks only the plot's own chunks, and a creature that roams past them stops.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ArchetypeLifeGameTest {

    /** The plot floor's y (relative), found rather than assumed (PetBehavioursGameTest.floor). */
    private static int floor(GameTestHelper helper) {
        return helper.getBlockState(new BlockPos(4, 0, 4)).isAir() ? 1 : 0;
    }

    /** Plant the creature living {@code a} (and {@code base}, when not null), spawned at {@code rel}. */
    private static void plant(GameTestHelper helper, String id, Archetype a, Species.Base base, BlockPos rel) {
        // The vehicle test's is a FLYING vehicle (a plane), so it rides a flying carrier and has to come down.
        RideFitProbeGameTest.plant(helper, id, "starter:pet_dragon", a.flies() || a == Archetype.VEHICLE ? "flies" : "");
        Species sp = SpeciesRegistry.get(id).withBehaves(a);
        sp = sp.withBehaviour(base == null ? Species.Base.WILD : base, Set.of(), Set.of());
        if (base == Species.Base.GUARDIAN) {
            // The starter dragon is a gentle pet with 0 attack damage: a fighter here needs teeth.
            sp = sp.with(null, null, 4.0, null, null, null);
        }
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, sp);
        BlockPos at = helper.absolutePos(rel);
        forceAround(helper, at, true);
        // The GameTest world is kept between runs, and so is any rig of this id a past run left behind:
        // the carrier finder takes the first in range, which was once a stale pig with its home in
        // another run's plot (2026-11-02). Clear them before this run's own.
        String tag = com.moddingfromamod.entity.WishMobTags.forSpecies(id);
        for (var e : helper.getLevel().getEntities((net.minecraft.world.entity.Entity) null,
                new net.minecraft.world.phys.AABB(at).inflate(96), e -> e.entityTags().contains(tag))) {
            DisplayRig.reap(helper.getLevel(), e.getUUID());
            e.discard();
        }
        RideFitProbeGameTest.spawn(helper.getLevel(), id, new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
    }

    private static void forceAround(GameTestHelper helper, BlockPos at, boolean on) {
        for (int cx = (at.getX() >> 4) - 3; cx <= (at.getX() >> 4) + 3; cx++) {
            for (int cz = (at.getZ() >> 4) - 3; cz <= (at.getZ() >> 4) + 3; cz++) {
                helper.getLevel().setChunkForced(cx, cz, on);
            }
        }
    }

    private static double aboveGround(GameTestHelper helper, Mob m) {
        // Under the plot's ceiling the heightmap is the ceiling: measure down from the creature.
        return m.getY() - CreatureLife.groundBelow(helper.getLevel(), m.getX(), m.getY(), m.getZ());
    }

    private static double flat(Mob m, BlockPos p) {
        return Math.hypot(m.getX() - p.getX() - 0.5, m.getZ() - p.getZ() - 0.5);
    }

    /** Wait for the rig, then sample {@code samples} times, every 5 ticks; {@code each} is handed the carrier. */
    private static GameTestSequence watch(GameTestHelper helper, String id, int samples, java.util.function.Consumer<Mob> each,
                                          Mob[] got) {
        GameTestSequence seq = helper.startSequence();
        seq.thenWaitUntil(() -> helper.assertTrue(RideFitProbeGameTest.carrier(helper, id) != null
                && VanillaRigAnimator.rigOf(RideFitProbeGameTest.carrier(helper, id)) != null, Component.literal("no rig yet")));
        seq.thenExecute(() -> got[0] = RideFitProbeGameTest.carrier(helper, id));
        seq.thenExecuteAfter(40, () -> { });   // its first decision
        for (int i = 0; i < samples; i++) {
            seq.thenExecuteAfter(5, () -> each.accept(got[0]));
        }
        return seq;
    }

    /**
     * A DRAGON rides a flyer and climbs to a circling point high over home (its glide carried out), and
     * left to its own life it never strays past its circle.
     */
    @GameTest(template = "empty_test", batch = "arch_dragon", timeoutTicks = 20 * 60)
    public static void aDragonCirclesHighOverItsHome(GameTestHelper helper) {
        String id = "arch_dragon";
        plant(helper, id, Archetype.DRAGON, null, new BlockPos(4, floor(helper) + 1, 4));
        Mob[] c = new Mob[1];
        double[] far = {0}, high = {0};
        BlockPos[] home = new BlockPos[1];
        watch(helper, id, 60, m -> {
            if (home[0] == null) {
                home[0] = PetBehaviours.leashHome(m);
            }
            far[0] = Math.max(far[0], flat(m, home[0]));
        }, c).thenExecute(() -> {
            double ground = CreatureLife.groundBelow(helper.getLevel(), c[0].getX(), c[0].getY(), c[0].getZ());
            // The plot's barrier ceiling is 9 up: a circling point 7 over the ground is as high as it fits.
            CreatureLife.pin(c[0], new ArchetypeLife.Intent(ArchetypeLife.Act.FLY, home[0].getX() + 2.5, ground + 7,
                    home[0].getZ() + 0.5, 0.8, 200), 200);
        }).thenWaitUntil(() -> {
            high[0] = Math.max(high[0], aboveGround(helper, c[0]));
            helper.assertTrue(high[0] >= 5.5, Component.literal(String.format("told to circle up high, it got %.1f above the ground", high[0])));
        }).thenExecute(() -> {
            forceAround(helper, home[0], false);
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.flies(c[0]), Component.literal("a dragon rides a flying carrier"));
            helper.assertTrue(far[0] <= Archetype.DRAGON.life().circleRadius() + 8,
                    Component.literal(String.format("left to itself it went %.1f blocks from home", far[0])));
        }).thenSucceed();
    }

    /** A BUZZER flits LOW — left to its own life it never climbs high — and flies to a spot it is sent. */
    @GameTest(template = "empty_test", batch = "arch_buzzer", timeoutTicks = 20 * 60)
    public static void aBuzzerFlitsLowAndKeepsMoving(GameTestHelper helper) {
        String id = "arch_buzzer";
        plant(helper, id, Archetype.BUZZER, null, new BlockPos(4, floor(helper) + 1, 4));
        Mob[] c = new Mob[1];
        String[] at = {""};
        double[] top = {0};
        BlockPos[] home = new BlockPos[1];
        watch(helper, id, 80, m -> {
            if (home[0] == null) {
                home[0] = PetBehaviours.leashHome(m);
            }
            double up = aboveGround(helper, m);
            if (up > top[0]) {
                top[0] = up;
                at[0] = String.format("t=%d intent=%s y=%.1f vy=%.2f", m.tickCount, CreatureLife.intentOf(m), m.getY(),
                        m.getDeltaMovement().y);
            }
        }, c).thenExecute(() -> {
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.flies(c[0]), Component.literal("a buzzer rides a flying carrier"));
            helper.assertTrue(top[0] <= 6.5, Component.literal(String.format("a buzzer stays low, yet it went %.1f up (%s)", top[0], at[0])));
            CreatureLife.pin(c[0], new ArchetypeLife.Intent(ArchetypeLife.Act.FLY, home[0].getX() - 2.5,
                    CreatureLife.groundBelow(helper.getLevel(), c[0].getX(), c[0].getY(), c[0].getZ()) + 3,
                    home[0].getZ() + 2.5, 0.7, 200), 200);
        }).thenWaitUntil(() -> helper.assertTrue(Math.hypot(c[0].getX() - home[0].getX() + 2.0, c[0].getZ() - home[0].getZ() - 3.0) < 2.0,
                Component.literal("it did not flit to the spot it was sent"))).thenExecute(() -> forceAround(helper, home[0], false)).thenSucceed();
    }

    /**
     * A GRAZER lives on the ground near home — left to itself it never takes off and never strays — and
     * its carrier's own goals are gone: what it does is its archetype's.
     */
    @GameTest(template = "empty_test", batch = "arch_grazer", timeoutTicks = 20 * 60)
    public static void aGrazerStaysNearHomeAndStandsAbout(GameTestHelper helper) {
        String id = "arch_grazer";
        plant(helper, id, Archetype.GRAZER, null, new BlockPos(4, floor(helper) + 1, 4));
        Mob[] c = new Mob[1];
        double[] far = {0}, up = {0};
        watch(helper, id, 80, m -> {
            far[0] = Math.max(far[0], flat(m, PetBehaviours.leashHome(m)));
            up[0] = Math.max(up[0], aboveGround(helper, m));
        }, c).thenExecute(() -> {
            forceAround(helper, PetBehaviours.leashHome(c[0]), false);
            helper.assertTrue(c[0].goalSelector.getAvailableGoals().stream().allMatch(g -> g.getGoal() instanceof FloatGoal),
                    Component.literal("its carrier's own goals are still there: " + c[0].goalSelector.getAvailableGoals()));
            helper.assertFalse(CarrierPool.SELF_WILLED.contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(c[0].getType()).getPath()) || CarrierPool.BRAINS.contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(c[0].getType()).getPath()), Component.literal("an archetype creature rides a brain: " + c[0].getType()));
            helper.assertTrue(CreatureLife.doing(c[0]) != null, Component.literal("nothing drives it"));
            helper.assertTrue(far[0] <= Archetype.GRAZER.life().wanderRadius() + 6, Component.literal(String.format("it wandered %.1f off", far[0])));
            helper.assertTrue(up[0] < 2.5, Component.literal(String.format("a grazer never leaves the ground: %.1f up", up[0])));
        }).thenSucceed();
    }

    /** A SWIMMER in a pool stays in the water. */
    @GameTest(template = "empty_test", batch = "arch_swimmer", timeoutTicks = 20 * 60)
    public static void aSwimmerStaysInTheWater(GameTestHelper helper) {
        String id = "arch_swimmer";
        int y = floor(helper);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                for (int h = 0; h < 3; h++) {
                    helper.setBlock(new BlockPos(x, y + h, z), Blocks.WATER);
                }
            }
        }
        plant(helper, id, Archetype.SWIMMER, null, new BlockPos(4, y + 1, 4));
        Mob[] c = new Mob[1];
        int[] wet = {0}, n = {0};
        watch(helper, id, 60, m -> {
            n[0]++;
            wet[0] += m.isInWater() ? 1 : 0;
        }, c).thenExecute(() -> {
            forceAround(helper, PetBehaviours.leashHome(c[0]), false);
            helper.assertTrue(wet[0] >= n[0] * 0.8, Component.literal("a swimmer stays in the water: " + wet[0] + "/" + n[0]
                    + " on " + c[0].getType()));
        }).thenSucceed();
    }

    /**
     * A CLIMBER shut in a pit with walls three high climbs out — and a GRAZER in the same pit does not,
     * so it is the climbing, not the pit, that let it out.
     */
    @GameTest(template = "empty_test", batch = "arch_climber", timeoutTicks = 20 * 90)
    public static void aClimberClimbsOutOfAPitAGrazerCannot(GameTestHelper helper) {
        climbTest(helper, "arch_climber", Archetype.CLIMBER, true);
    }

    @GameTest(template = "empty_test", batch = "arch_climber_control", timeoutTicks = 20 * 90)
    public static void aGrazerInThePitStaysIn(GameTestHelper helper) {
        climbTest(helper, "arch_pitgrazer", Archetype.GRAZER, false);
    }

    private static void climbTest(GameTestHelper helper, String id, Archetype a, boolean escapes) {
        int y = floor(helper) + 1;
        for (int x = 1; x <= 7; x++) {
            for (int z = 1; z <= 7; z++) {
                boolean wall = x == 1 || x == 7 || z == 1 || z == 7;
                for (int h = 0; h < 3; h++) {
                    if (wall) {
                        helper.setBlock(new BlockPos(x, y + h, z), Blocks.STONE);
                    }
                }
            }
        }
        plant(helper, id, a, null, new BlockPos(4, y, 4));
        Mob[] c = new Mob[1];
        double[] top = {0};
        BlockPos out = helper.absolutePos(new BlockPos(0, y, 4));   // outside the pit's west wall
        watch(helper, id, 1, m -> CreatureLife.pin(m, new ArchetypeLife.Intent(ArchetypeLife.Act.WALK, out.getX() + 0.5,
                out.getY(), out.getZ() + 0.5, 0.8, 400), 400), c);
        GameTestSequence again = helper.startSequence();
        again.thenWaitUntil(() -> helper.assertTrue(c[0] != null, Component.literal("no rig yet")));
        for (int i = 0; i < 80; i++) {
            again.thenExecuteAfter(5, () -> top[0] = Math.max(top[0], c[0].getY() - helper.absolutePos(new BlockPos(0, y, 0)).getY()));
        }
        again.thenExecute(() -> {
                    forceAround(helper, PetBehaviours.leashHome(c[0]), false);
                    if (escapes) {
                        // Two blocks up a sheer wall: a walker cannot (the control stays under 1.5).
                        BlockPos rel = helper.relativePos(c[0].blockPosition());
                        helper.assertTrue(top[0] >= 2.0, Component.literal(String.format("a climber should go up the pit's wall: rose %.1f on a %s"
                                + " (at %s abs %s home %s, intent %s, doing %s, navDone %s, wanted %s, hcol %s, noAi %s, passengers %s, vehicle %s)",
                                top[0], c[0].getType().toShortString(), rel, c[0].blockPosition(), PetBehaviours.leashHome(c[0]), CreatureLife.intentOf(c[0]), CreatureLife.doing(c[0]),
                                c[0].getNavigation().isDone(), c[0].getMoveControl().hasWanted(), c[0].horizontalCollision,
                                c[0].isNoAi(), c[0].getPassengers().size(), c[0].getVehicle())));
                    } else {
                        helper.assertTrue(top[0] < 1.5, Component.literal(String.format("a grazer cannot climb, yet it rose %.1f", top[0])));
                    }
                }).thenSucceed();
    }

    /** An ARCHER guardian keeps its distance from the zombie it fights, and still hurts it. */
    @GameTest(template = "empty_test", batch = "arch_archer", timeoutTicks = 20 * 60)
    public static void anArcherKeepsItsDistanceAndShoots(GameTestHelper helper) {
        String id = "arch_archer";
        int y = floor(helper) + 1;
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, y + 6, z), Blocks.STONE);   // a roof: no sun on the zombie
            }
        }
        plant(helper, id, Archetype.ARCHER, Species.Base.GUARDIAN, new BlockPos(4, y, 4));
        // A zombie that stands still (NoAI) close by, in a corner: the archer must BACK OFF across the
        // plot to get into its bow's range (6+), then shoot. (Shot, a live zombie runs a slow body down
        // as it does a real skeleton, which would measure the zombie's legs, not the archer's.)
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, y, 2));
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        Mob[] c = new Mob[1];
        int[] apart = {0}, n = {0};
        double[] farthest = {0};
        watch(helper, id, 120, m -> {
            farthest[0] = Math.max(farthest[0], m.distanceTo(zombie));
            if (RigInstincts.targetOf(m) != null && zombie.isAlive()) {
                n[0]++;
                apart[0] += m.distanceTo(zombie) >= 4.5 ? 1 : 0;
            }
        }, c).thenExecute(() -> {
            forceAround(helper, PetBehaviours.leashHome(c[0]), false);
            helper.assertTrue(n[0] > 5, Component.literal("the archer never took the zombie on: " + n[0] + " samples hunting"));
            helper.assertTrue(farthest[0] >= 6, Component.literal(String.format("an archer backs off out of reach: it got no further than %.1f", farthest[0])));
            helper.assertTrue(!zombie.isAlive() || zombie.getHealth() < zombie.getMaxHealth(),
                    Component.literal("it kept its distance but never hurt the zombie: " + zombie.getHealth()));
        }).thenSucceed();
    }

    /** A MOUNT drifts over to its rider when he is about (and does not wait while he holds something). */
    @GameTest(template = "empty_test", batch = "arch_mount", timeoutTicks = 20 * 60)
    public static void aMountComesOverToItsRider(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-arch-mount");
        String id = "arch_mount";
        int y = floor(helper) + 1;
        plant(helper, id, Archetype.MOUNT, null, new BlockPos(1, y, 1));
        kid.getInventory().clearContent();
        kid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        // Inside the plot: a GameTest plot is walled with barriers, and a rider outside it is unreachable.
        BlockPos at = helper.absolutePos(new BlockPos(7, y, 7));
        kid.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        Mob[] c = new Mob[1];
        // Where it was put and where he stands: 6 blocks apart on each axis, so it starts ~8.5 off.
        double[] closest = {99}, start = {Math.hypot(6, 6)};
        watch(helper, id, 100, m -> {
            if (CreatureLife.intentOf(m) == null || CreatureLife.intentOf(m).act() != ArchetypeLife.Act.TO_RIDER) {
                CreatureLife.pin(m, new ArchetypeLife.Intent(ArchetypeLife.Act.TO_RIDER, kid.getX(), kid.getY(), kid.getZ(),
                        1.0, 200), 200);
            }
            closest[0] = Math.min(closest[0], m.distanceTo(kid));
        }, c).thenExecute(() -> {
            forceAround(helper, PetBehaviours.leashHome(c[0]), false);
            helper.assertTrue(start[0] > 6.5, Component.literal(String.format("it started too close to prove anything: %.1f", start[0])));
            helper.assertTrue(closest[0] < 4, Component.literal(String.format("a mount should come over to him: closest %.1f (from %.1f)",
                    closest[0], start[0])));
        }).thenSucceed();
    }

    /** A guardian BRUISER or PACK HUNTER takes on a zombie near it and hurts it (charge / pounce). */
    @GameTest(template = "empty_test", batch = "arch_bruiser", timeoutTicks = 20 * 60)
    public static void aGuardianBruiserTakesOnAZombie(GameTestHelper helper) {
        fightTest(helper, "arch_bruiser", Archetype.BRUISER);
    }

    @GameTest(template = "empty_test", batch = "arch_pack", timeoutTicks = 20 * 60)
    public static void aGuardianPackHunterTakesOnAZombie(GameTestHelper helper) {
        fightTest(helper, "arch_pack", Archetype.PACK_HUNTER);
    }

    private static void fightTest(GameTestHelper helper, String id, Archetype a) {
        int y = floor(helper) + 1;
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, y + 6, z), Blocks.STONE);
            }
        }
        plant(helper, id, a, Species.Base.GUARDIAN, new BlockPos(2, y, 2));
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(6, y, 6));
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        Mob[] c = new Mob[1];
        watch(helper, id, 100, m -> { }, c).thenExecute(() -> {
            forceAround(helper, PetBehaviours.leashHome(c[0]), false);
            helper.assertTrue(!zombie.isAlive() || zombie.getHealth() < zombie.getMaxHealth(),
                    Component.literal("a guardian " + a.key() + " never hurt the zombie beside it: " + zombie.getHealth()));
        }).thenSucceed();
    }

    /** A VILLAGER-like creature standing about turns to look at him. */
    @GameTest(template = "empty_test", batch = "arch_villager", timeoutTicks = 20 * 60)
    public static void aVillagerStandsAboutFacingHim(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-arch-villager");
        String id = "arch_villager";
        int y = floor(helper) + 1;
        kid.getInventory().clearContent();
        kid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        BlockPos at = helper.absolutePos(new BlockPos(6, y, 6));
        kid.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        plant(helper, id, Archetype.VILLAGER, null, new BlockPos(2, y, 2));
        Mob[] c = new Mob[1];
        double[] off = {180};
        watch(helper, id, 20, m -> {
            CreatureLife.pin(m, new ArchetypeLife.Intent(ArchetypeLife.Act.STAND, kid.getX(), kid.getEyeY(), kid.getZ(), 0, 200), 200);
            double want = Math.toDegrees(Math.atan2(kid.getZ() - m.getZ(), kid.getX() - m.getX())) - 90;
            double d = ((m.getYHeadRot() - want) % 360 + 540) % 360 - 180;
            off[0] = Math.min(off[0], Math.abs(d));
        }, c).thenExecute(() -> {
            forceAround(helper, PetBehaviours.leashHome(c[0]), false);
            helper.assertTrue(off[0] < 40, Component.literal(String.format("standing about, it should turn to look at him: %.0f degrees off", off[0])));
        }).thenSucceed();
    }

    /**
     * The tool path, driven for real (a rule tested only where it is DEFINED is not tested where it
     * RUNS): define_species with behaves_like "dragon" saves a dragon's life and its flight and says so;
     * a word that is no mob is refused by name and makes nothing.
     */
    @GameTest(template = "empty_test", batch = "arch_tool", timeoutTicks = 20 * 30)
    public static void defineSpeciesTakesBehavesLikeAndRefusesAWordThatIsNoMob(GameTestHelper helper) {
        java.util.concurrent.atomic.AtomicReference<com.moddingfromamod.agent.ToolResult> ok = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<com.moddingfromamod.agent.ToolResult> no = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            ok.set(new com.moddingfromamod.agent.tools.DefineSpeciesTool().call(com.google.gson.JsonParser.parseString(
                    "{\"id\":\"arch_tool_dragon\",\"name\":\"Ember\",\"spawn\":false,\"behaves_like\":\"Dragon\","
                            + "\"powers\":[\"friendly\"]}").getAsJsonObject()));
            no.set(new com.moddingfromamod.agent.tools.DefineSpeciesTool().call(com.google.gson.JsonParser.parseString(
                    "{\"id\":\"arch_tool_unicorn\",\"name\":\"Sparkle\",\"spawn\":false,\"behaves_like\":\"unicorn\"}")
                    .getAsJsonObject()));
        }, "arch-tool-gametest");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(ok.get() != null && no.get() != null, Component.literal("waiting for define_species"));
            helper.assertFalse(ok.get().isError(), Component.literal("define_species with behaves_like failed: " + ok.get().text()));
            Species sp = SpeciesRegistry.get("arch_tool_dragon");
            helper.assertTrue(sp != null && sp.archetype() == Archetype.DRAGON, Component.literal("it did not save a dragon's life: " + sp));
            helper.assertTrue(sp.has(Species.Power.FLIES), Component.literal("a dragon's life brings its flight"));
            helper.assertTrue(ok.get().text().contains("lives like a dragon"), Component.literal("the reply does not say how it lives: "
                    + ok.get().text()));
            helper.assertTrue(no.get().isError() && no.get().text().contains("'unicorn' is not a mob"),
                    Component.literal("a word that is no mob should be refused by name: " + no.get().text()));
            helper.assertTrue(SpeciesRegistry.get("arch_tool_unicorn") == null, Component.literal("a refused call made a creature"));
        });
    }

    /**
     *, through the real sculpt_creature: a flier the agent named a ground life ('wolf') is given a flying one and
     * told so; a creature it named no life for gets one chosen (never a refusal), and the reply says which.
     */
    @GameTest(template = "empty_test", batch = "arch_tool_sculpt", timeoutTicks = 6000)
    public static void sculptCreatureLiftsAFlierOffTheGroundAndChoosesALifeWhenNoneIsNamed(GameTestHelper helper) {
        java.util.concurrent.atomic.AtomicReference<com.moddingfromamod.agent.ToolResult> lifted = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<com.moddingfromamod.agent.ToolResult> chosen = new java.util.concurrent.atomic.AtomicReference<>();
        String body = "\"parts\":[{\"name\":\"body\",\"from\":[-4,6,-6],\"to\":[4,14,6],\"colour\":\"white\"},"
                + "{\"name\":\"head\",\"from\":[-3,10,6],\"to\":[3,16,11],\"colour\":\"white\"}],"
                + "\"palette\":[{\"colour\":\"white\",\"percent\":100}],\"note\":\"a GameTest creature\",\"spawn\":false";
        Thread t = new Thread(() -> {
            lifted.set(new com.moddingfromamod.agent.tools.SculptCreatureTool().call(com.google.gson.JsonParser.parseString(
                    "{\"id\":\"arch_sculpt_flier\",\"name\":\"Puff\",\"behaves_like\":\"wolf\",\"powers\":[\"friendly\",\"flies\"],"
                            + body + "}").getAsJsonObject()));
            chosen.set(new com.moddingfromamod.agent.tools.SculptCreatureTool().call(com.google.gson.JsonParser.parseString(
                    "{\"id\":\"arch_sculpt_unnamed\",\"name\":\"Plod\",\"powers\":[\"friendly\"]," + body + "}").getAsJsonObject()));
        }, "arch-sculpt-gametest");
        t.setDaemon(true);
        t.start();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(lifted.get() != null && chosen.get() != null, Component.literal("waiting for sculpt_creature"));
            helper.assertFalse(lifted.get().isError(), Component.literal("the flier was refused: " + lifted.get().text()));
            Species flier = SpeciesRegistry.get("arch_sculpt_flier");
            helper.assertTrue(flier != null && flier.archetype() != null && flier.archetype().flies()
                            || flier != null && flier.archetype() == Archetype.MOUNT,
                    Component.literal("a flier named 'wolf' must live a flying life: " + (flier == null ? null : flier.archetype())));
            helper.assertTrue(lifted.get().text().contains("flying life"),
                    Component.literal("the reply must say its life was changed: " + lifted.get().text()));
            helper.assertFalse(chosen.get().isError(), Component.literal("no behaves_like must never be refused: " + chosen.get().text()));
            Species plod = SpeciesRegistry.get("arch_sculpt_unnamed");
            helper.assertTrue(plod != null && plod.archetype() != null && !plod.archetype().flies(),
                    Component.literal("a ground creature named no life must get a ground one: " + (plod == null ? null : plod.archetype())));
            helper.assertTrue(chosen.get().text().contains("named no behaves_like"),
                    Component.literal("the reply must say which life was chosen: " + chosen.get().text()));
        });
    }

    /**
     * A VEHICLE stays parked: on its own for twelve seconds it does not move, a flying one comes down to
     * the ground and stops, and one driven far from where it was made stays where he left it (no leash).
     */
    @GameTest(template = "empty_test", batch = "arch_vehicle", timeoutTicks = 20 * 60)
    public static void aVehicleStaysParkedAndAFlyingOneComesDown(GameTestHelper helper) {
        String id = "arch_vehicle";
        plant(helper, id, Archetype.VEHICLE, null, new BlockPos(4, floor(helper) + 1, 4));
        Mob[] c = new Mob[1];
        double[] moved = {0}, start = new double[3];
        watch(helper, id, 1, m -> {
            helper.assertTrue(SpeciesRegistry.get(id).has(Species.Power.RIDEABLE), Component.literal("a vehicle is driven"));
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.flies(m), Component.literal("a flying vehicle rides a flying carrier"));
            // Up in the air, and far from where it was made: it should come down, and stay out here.
            BlockPos far = PetBehaviours.leashHome(m).offset(30, 0, 0);
            forceAround(helper, far, true);
            m.teleportTo(far.getX() + 0.5, far.getY() + 6, far.getZ() + 0.5);
        }, c).thenExecuteAfter(120, () -> {
            helper.assertTrue(aboveGround(helper, c[0]) < 1.5, Component.literal(String.format("a flying vehicle left alone comes down and "
                    + "parks; it is %.1f up (a %s moving %s)", aboveGround(helper, c[0]),
                    c[0].getType().toShortString(), c[0].getDeltaMovement())));
            start[0] = c[0].getX();
            start[1] = c[0].getY();
            start[2] = c[0].getZ();
        });
        GameTestSequence again = helper.startSequence();
        again.thenWaitUntil(() -> helper.assertTrue(start[0] != 0, Component.literal("waiting for it to park")));
        for (int i = 0; i < 48; i++) {
            again.thenExecuteAfter(5, () -> moved[0] = Math.max(moved[0], Math.hypot(c[0].getX() - start[0], c[0].getZ() - start[2])));
        }
        again.thenExecute(() -> {
            forceAround(helper, PetBehaviours.leashHome(c[0]), false);
            forceAround(helper, c[0].blockPosition(), false);
            helper.assertTrue(moved[0] < 0.5, Component.literal(String.format("a parked vehicle moved %.1f blocks on its own", moved[0])));
            helper.assertTrue(flat(c[0], PetBehaviours.leashHome(c[0])) > 25, Component.literal("left out here, it was pulled back home"));
            helper.assertTrue(RigInstincts.targetOf(c[0]) == null, Component.literal("a vehicle hunts nothing"));
        }).thenSucceed();
    }

    /**
     * The ground under a flyer just beneath a roof is the FLOOR, never the roof: a "ground" found more
     * than a block above its feet is a ceiling (CreatureLife.groundUnder). The release gate caught a bee
     * sent to perch on top of the plot's barrier lid, 6.8 up (2026-11-02, 1 run in ~20 before).
     */
    @GameTest(template = "empty_test", batch = "arch_ceiling", timeoutTicks = 100)
    public static void theGroundUnderAFlyerBelowARoofIsTheFloor(GameTestHelper helper) {
        int y = floor(helper) + 1;
        BlockPos roof = new BlockPos(4, y + 4, 4);
        helper.setBlock(roof, net.minecraft.world.level.block.Blocks.STONE);
        BlockPos at = helper.absolutePos(new BlockPos(4, y, 4));
        double x = at.getX() + 0.5, z = at.getZ() + 0.5;
        double feet = at.getY() + 2.6;   // flying, 1.4 below the roof's underside
        double ground = CreatureLife.groundUnder(helper.getLevel(), x, feet + 2, feet, z);
        helper.assertTrue(Math.abs(ground - at.getY()) < 0.01, Component.literal(String.format(
                "the ground under a flyer below a roof is the floor (%d), not the roof: %.1f", at.getY(), ground)));
        // And a step up a walker can take is still found: one block, beside it.
        helper.setBlock(new BlockPos(5, y, 4), net.minecraft.world.level.block.Blocks.STONE);
        double step = CreatureLife.groundUnder(helper.getLevel(), x + 1, at.getY() + 2, at.getY(), z);
        helper.assertTrue(Math.abs(step - (at.getY() + 1)) < 0.01, Component.literal("a one-block step is still a step: " + step));
        helper.succeed();
    }
}
