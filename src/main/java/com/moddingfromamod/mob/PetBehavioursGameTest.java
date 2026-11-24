package com.moddingfromamod.mob;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.RiderInput;
import com.moddingfromamod.testing.MockPlayerNetwork;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A vanilla mob as a pet, on a running server</b> ({@link PetBehaviours}): it follows, it
 * is friendly, it finds, and it carries a rider where he looks.
 *
 * <p>What the unit tests cannot see: that the handlers are on the right bus (a wrong bus compiles
 * and does nothing), that a mob's own AI does not undo what was asked between two ticks, and that
 * the target event really is the choke point a zombie's aggression passes.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PetBehavioursGameTest {

    /**
     * The y the plot's floor is at, FOUND rather than assumed. ⚠ It differs between the targets:
     * the {@code empty_test} floor is at relative y=0 on 1.21.4 and y=1 on 1.21.1. Measured: a cow
     * spawned at y=1 stood INSIDE the 1.21.1 floor and suffocated at tick ~114 ({@code KILLED},
     * health 0), and every movement test read as "the pet never moved".
     */
    static int floor(GameTestHelper helper) {
        // The CENTRE of the plot, not its corner: the framework keeps its own structure block at
        // a corner, so (0,0,0) is never air and a corner probe answers 0 on both targets.
        return helper.getBlockState(new BlockPos(4, 0, 4)).isAir() ? 1 : 0;
    }

    private static PetBehaviour pet(String json) {
        return PetBehaviour.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    @GameTest(template = "empty_test", batch = "pets", timeoutTicks = 400)
    public static void aCowThatFollowsWalksToItsOwner(GameTestHelper helper) {
        // The owner can be any entity; an armour stand does not wander off mid-test.
        int y = floor(helper) + 1;
        ArmorStand owner = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(7, y, 7));
        Cow cow = helper.spawn(EntityTypes.COW, new BlockPos(1, y, 1));
        double start = cow.distanceTo(owner);
        helper.assertTrue(start > PetMoves.FOLLOW_CLOSE + 2, Component.literal("the cow started too close to prove anything"));
        PetBehaviours.apply(cow, pet("{follow:true}"), owner.getUUID());
        helper.assertTrue(cow.isPersistenceRequired(), Component.literal("a pet that can despawn is a broken pet"));
        helper.succeedWhen(() -> helper.assertTrue(cow.distanceTo(owner) < PetMoves.FOLLOW_CLOSE + 1,
                Component.literal("the cow did not come to its owner: " + String.format("%.1f", cow.distanceTo(owner))
                        + " blocks (started at " + String.format("%.1f", start) + "); tracked="
                        + PetBehaviours.tracked(helper.getLevel()) + " alive=" + cow.isAlive())));
    }

    @GameTest(template = "empty_test", batch = "pets", timeoutTicks = 40)
    public static void aFriendlyZombieNeverTargetsAPlayer(GameTestHelper helper) {
        Player child = helper.makeMockPlayer(GameType.SURVIVAL);
        // THE CONTROL FIRST: an ordinary zombie takes the player as its target, or this test
        // proves nothing about the friendly one.
        int y = floor(helper) + 1;
        Zombie plain = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1, y, 1));
        plain.setTarget(child);
        helper.assertTrue(plain.getTarget() == child, Component.literal("the control zombie would not target at all"));

        Zombie friend = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(3, y, 3));
        friend.setTarget(child);
        PetBehaviours.apply(friend, pet("{friendly:true}"), null);
        helper.assertTrue(friend.getTarget() == null, Component.literal("making it friendly did not calm it down"));
        friend.setTarget(child);
        helper.assertTrue(friend.getTarget() == null, Component.literal("a friendly zombie took a player as its target"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "pets", timeoutTicks = 400)
    public static void aCowThatFindsDiamondsShowsWhereTheyAre(GameTestHelper helper) {
        int y = floor(helper) + 1;
        BlockPos ore = new BlockPos(7, y - 1, 7);
        helper.setBlock(ore, Blocks.DIAMOND_ORE);
        Cow cow = helper.spawn(EntityTypes.COW, new BlockPos(1, y, 1));
        PetBehaviours.apply(cow, pet("{finds:'diamonds'}"), null);
        BlockPos abs = helper.absolutePos(ore);
        helper.succeedWhen(() -> {
            helper.assertTrue(cow.isCurrentlyGlowing(), Component.literal("the finder is not glowing to say it found some"));
            helper.assertTrue(cow.blockPosition().distSqr(abs) <= 9,
                    Component.literal("the finder did not go to the diamonds: it is at " + cow.blockPosition()
                            + ", the ore at " + abs));
        });
    }

    @GameTest(template = "empty_test", batch = "pets", timeoutTicks = 40)
    public static void aFinderOfSomethingThatDoesNotExistIsRefused(GameTestHelper helper) {
        helper.assertTrue(PetBehaviours.knows("#minecraft:diamond_ores"), Component.literal("a real tag"));
        helper.assertTrue(PetBehaviours.knows("minecraft:chest"), Component.literal("a real block"));
        helper.assertTrue(!PetBehaviours.knows("minecraft:unicorn_ore"), Component.literal("a made-up block"));
        helper.assertTrue(!PetBehaviours.knows("#minecraft:unicorn_ores"), Component.literal("a made-up tag"));
        helper.succeed();
    }

    /**
     * A REAL player climbs on with an empty hand and holds W. Needs a ServerPlayer in the player
     * list, so it sits in the {@code mockplayer} batch with the other tests that make one.
     */
    @GameTest(template = "empty_test", batch = "mockplayer", timeoutTicks = 200)
    public static void aRideableCowCarriesHimWhereHeLooks(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-rider");
        Cow cow = helper.spawn(EntityTypes.COW, new BlockPos(4, floor(helper) + 1, 1));
        PetBehaviours.apply(cow, pet("{rideable:true}"), child.getUUID());
        child.teleportTo(cow.getX(), cow.getY(), cow.getZ() - 1);

        var ev = NeoForge.EVENT_BUS.post(
                com.moddingfromamod.mv.Interact.event(child, InteractionHand.MAIN_HAND, cow));
        if (child.getVehicle() != cow) {
            helper.fail(Component.literal("an empty hand on a rideable cow did not climb on: canceled=" + ev.isCanceled()
                    + " pet=" + PetBehaviours.stored(cow) + " hand=" + child.getMainHandItem()
                    + " shift=" + child.isShiftKeyDown() + " vehicle=" + child.getVehicle()
                    + " cowIsVehicle=" + cow.isVehicle() + " directMount=" + child.startRiding(cow)));
        }

        child.setYRot(0f);          // facing south: +z
        child.setXRot(0f);
        double z0 = cow.getZ();
        helper.runAfterDelay(1, () -> RiderInput.hold(child, 1f, 0f));
        helper.succeedWhen(() -> {
            RiderInput.hold(child, 1f, 0f);
            helper.assertTrue(cow.getZ() > z0 + 3,
                    Component.literal("holding W facing south, the cow went from z=" + String.format("%.2f", z0)
                            + " to z=" + String.format("%.2f", cow.getZ())));
            child.stopRiding();
        });
    }

    /**
     * a cow told to STAY is pushed six blocks off its spot and walks back; its staying
     * survives a save and load (a chunk reload, a restart); and the tool refuses follow AND stay.
     */
    @GameTest(template = "empty_test", batch = "pets", timeoutTicks = 600)
    public static void aStayingCowWalksBackAndKeepsItsHomeThroughAReload(GameTestHelper helper) {
        var refused = new com.moddingfromamod.agent.tools.ModifyMobTool().call(JsonParser.parseString(
                "{\"scope\":\"one\",\"follow\":true,\"stay\":0}").getAsJsonObject());
        helper.assertTrue(refused.isError() && refused.text().contains("opposites"),
                Component.literal("follow and stay together are refused in words: " + refused.text()));
        int y = floor(helper) + 1;
        BlockPos spot = new BlockPos(2, y, 2);
        Cow cow = helper.spawn(EntityTypes.COW, spot);
        cow.setDeltaMovement(0, 0, 0);
        PetBehaviours.apply(cow, pet("{stay:0}"), null);
        PetBehaviour now = PetBehaviours.of(cow);
        helper.assertTrue(now.stays() && now.home() == cow.blockPosition().asLong(),
                Component.literal("it is home where it stood: " + now));
        helper.assertTrue(cow.isPersistenceRequired(), Component.literal("a stayer does not despawn"));
        var saved = com.moddingfromamod.compat.EntityNbt.save(cow);
        Cow reloaded = new Cow(EntityTypes.COW, helper.getLevel());
        com.moddingfromamod.compat.EntityNbt.load(reloaded, saved);
        helper.assertTrue(PetBehaviours.of(reloaded).stays()
                        && PetBehaviours.of(reloaded).home().equals(now.home()),
                Component.literal("its home came back with it from a save: " + PetBehaviours.of(reloaded)));
        BlockPos pushed = helper.absolutePos(spot.offset(6, 0, 0));
        cow.teleportTo(pushed.getX() + 0.5, pushed.getY(), pushed.getZ() + 0.5);
        BlockPos home = BlockPos.of(now.home());
        helper.succeedWhen(() -> {
            double d = Math.hypot(cow.getX() - (home.getX() + 0.5), cow.getZ() - (home.getZ() + 0.5));
            helper.assertTrue(d <= 1.5, Component.literal("pushed off its spot, it walks back: " + String.format("%.1f", d)
                    + " blocks from home"));
        });
    }
}
