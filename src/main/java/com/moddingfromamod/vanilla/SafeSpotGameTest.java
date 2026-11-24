package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.WishProgress;
import com.moddingfromamod.wish.WishUndo;
import com.moddingfromamod.wish.reveal.WishOrbs;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Where a wished creature is put, and whether it survives it</b> — a spawn over a lava
 * moat, a spawn inside rock, a build landing on a creature, and a castle built around one. Each
 * asserts where it ended up and that it is not inside a block; the rock case also proves the wish's
 * pillar reaches the new spot by ONE glide, never torn down (an earlier issue's path, {@code WishSpotGameTest}).
 *
 * <p>Lava here is real and is taken away in the same tick it is placed, before it can flow.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SafeSpotGameTest {

    private static final String BATCH = "safespot";
    private static final String ID = "gt_safespot_dragon";

    /** The plot's floor, found (it differs between the targets — see PetBehavioursGameTest.floor). */
    private static int floor(GameTestHelper helper) {
        return helper.getBlockState(new BlockPos(4, 0, 4)).isAir() ? 1 : 0;
    }

    /** A wished creature with no sculpt: the rig builds its stand-in, which is all a spot needs. */
    private static void species(GameTestHelper helper) {
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + ID + "\",\"name\":\"Safe Dragon\",\"health\":20,\"speed\":0.0,\"scale\":1.0,"
                        + "\"powers\":[\"FRIENDLY\"]}").getAsJsonObject()));
    }

    private static WishRun run(ServerPlayer child, String worker, String text) {
        UUID wish = UUID.nameUUIDFromBytes(("safespot-" + worker).getBytes());
        Marshal.global().submit(wish, child.getUUID(), text);
        return Marshal.global().assign(worker, wish);
    }

    private static void box(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    helper.setBlock(new BlockPos(x, y, z), b);
                }
            }
        }
    }

    private static void gone(ServerLevel level, Entity e) {
        if (e != null) {
            DisplayRig.reap(level, e.getUUID());
            e.discard();
        }
    }

    /**
     * <b>A spawn aimed at a lava moat gets a footing</b>, and stays where he aimed. An aim at a moat
     * used to stand the creature on the lava. Undoing the wish takes the footing away again.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aSpawnOverALavaMoatGetsAFootingThatUndoTakesAway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int f = floor(helper);
        species(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "safespot-moat");
        WishRun run = run(child, "gt-safespot-moat", "a dragon on the moat");
        LivingEntity c = null;
        try {
            box(helper, 1, f + 1, 1, 7, f + 1, 7, Blocks.LAVA);
            BlockPos aimed = helper.absolutePos(new BlockPos(4, f + 2, 4));
            LivingEntity made = AgentContext.with("gt-safespot-moat", () -> CreatureSpawn.spawn(level, ID,
                    aimed.getX() + 0.5, aimed.getY(), aimed.getZ() + 0.5, 0f, EntitySpawnReason.COMMAND));
            c = made;
            helper.assertTrue(c != null, Component.literal("nothing was spawned"));
            var settled = AgentContext.with("gt-safespot-moat", () -> SafePlacement.settleSpawn(child, made, true));
            helper.assertTrue("footing".equals(settled.what()), Component.literal("expected a footing, got " + settled.what()));
            helper.assertTrue(c.getX() == aimed.getX() + 0.5 && c.getY() == aimed.getY()
                            && c.getZ() == aimed.getZ() + 0.5,
                    Component.literal("a footing keeps the aimed spot; it moved to " + c.position()));
            BlockPos under = aimed.below();
            helper.assertTrue(level.getBlockState(under).is(Blocks.OBSIDIAN),
                    Component.literal("no obsidian under it: " + level.getBlockState(under)));
            helper.assertTrue(!c.isInLava() && !c.isInWall(), Component.literal("it is standing in the lava or a block"));
            int laid = SafePlacement.footings(run.wishId());
            helper.assertTrue(laid >= 9, Component.literal("a footing of at least 3x3 was journalled, saw " + laid));

            WishUndo.undo(level.getServer(), run);
            helper.assertTrue(level.getBlockState(under).getFluidState().is(net.minecraft.tags.FluidTags.LAVA),
                    Component.literal("undo did not give the moat back: " + level.getBlockState(under)));
            helper.assertTrue(SafePlacement.footings(run.wishId()) == 0, Component.literal("the footing is still journalled"));
            helper.succeed();
        } finally {
            box(helper, 1, f + 1, 1, 7, f + 1, 7, Blocks.AIR);
            gone(level, c);
            Marshal.global().finish(run.wishId());
            MockPlayerNetwork.remove(child);
        }
    }

    /**
     * <b>A spawn aimed inside rock moves up, glows and says so — and its pillar GLIDES there.</b>
     * The Hot Wheels T-Rex was conjured at exactly a garage's anchor and suffocated a minute later.
     * The move is more than {@link com.moddingfromamod.runtime.SafeSpot#SILENT_MOVE} blocks, so he
     * is told, it glows for five seconds, and the wish's spot follows it by ONE glide.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aSpawnInsideRockMovesUpGlowsSaysSoAndThePillarGlidesOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int f = floor(helper);
        species(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "safespot-rock");
        WishRun run = run(child, "gt-safespot-rock", "a dragon in the rock");
        LivingEntity c = null;
        try {
            box(helper, 0, f + 1, 0, 8, f + 6, 8, Blocks.STONE);
            BlockPos aimed = helper.absolutePos(new BlockPos(4, f + 2, 4));
            String dim = level.dimension().identifier().toString();
            run.defaultAnchor(new Spot(dim, aimed.getX(), aimed.getY(), aimed.getZ()));
            long now = 20_000;
            for (int i = 0; i < 4; i++) {
                WishOrbs.tick(level.getServer(), List.of(run), now += 2);
            }
            helper.assertTrue(WishOrbs.shown(run.wishId()), Component.literal("no pillar at the aimed spot"));

            LivingEntity made = AgentContext.with("gt-safespot-rock", () -> CreatureSpawn.spawn(level, ID,
                    aimed.getX() + 0.5, aimed.getY(), aimed.getZ() + 0.5, 0f, EntitySpawnReason.COMMAND));
            c = made;
            helper.assertTrue(c != null, Component.literal("nothing was spawned"));
            var settled = AgentContext.with("gt-safespot-rock", () -> SafePlacement.settleSpawn(child, made, true));
            int top = helper.absolutePos(new BlockPos(0, f + 7, 0)).getY();
            helper.assertTrue(settled.moved(), Component.literal("it was not moved out of the rock: " + settled.what()));
            helper.assertTrue(c.getY() == top, Component.literal("expected it straight up on the rock (y=" + top + "), it is at " + c.position()));
            helper.assertTrue(!c.isInWall(), Component.literal("it is still inside a block"));
            helper.assertTrue(c.hasEffect(MobEffects.GLOWING), Component.literal("a far move must glow"));
            helper.assertTrue(settled.said() != null && MockPlayerNetwork.chat(child).stream()
                            .anyMatch(l -> l.contains("Safe Dragon") && l.contains("inside the rock")),
                    Component.literal("Moddy did not tell him where it went: " + MockPlayerNetwork.chat(child)));
            BlockPos focus = AgentContext.with("gt-safespot-rock", () -> WishProgress.manifestAnchor(child));
            helper.assertTrue(focus != null && focus.getY() == top,
                    Component.literal("the wish's focus did not move to the creature (the reveal frames it): " + focus));

            for (int i = 0; i < 40; i++) {
                WishOrbs.tick(level.getServer(), List.of(run), now += 2);
                helper.assertTrue(WishOrbs.shown(run.wishId()),
                        Component.literal("the pillar was taken down mid-move at step " + i + " — a SafeSpot move must glide"));
            }
            helper.assertTrue(WishOrbs.glides(run.wishId()) == 1,
                    Component.literal("expected exactly one glide, saw " + WishOrbs.glides(run.wishId())));
            double[] foot = WishOrbs.footOf(run.wishId());
            helper.assertTrue(foot != null && Math.abs(foot[0] - (focus.getX() + 0.5)) < 0.01
                            && Math.abs(foot[2] - (focus.getZ() + 0.5)) < 0.01,
                    Component.literal("the pillar did not arrive at the creature: " + java.util.Arrays.toString(foot)));
            helper.succeed();
        } finally {
            box(helper, 0, f + 1, 0, 8, f + 6, 8, Blocks.AIR);
            gone(level, c);
            WishOrbs.drop(run.wishId());
            Marshal.global().finish(run.wishId());
            WishProgress.forget(child.getUUID());
            MockPlayerNetwork.remove(child);
        }
    }

    /**
     * <b>A build over a creature moves it the minimum, and one in the build's air stays.</b> The
     * oasis wish's 2,484 blocks landed on the Dragon Shark. A 3x3x3 stone cube lands on one cow —
     * moved the two blocks that clear it — while another cow, inside the build's bounds where it
     * places nothing, does not move at all.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aBuildOverACreatureMovesItTheMinimumAndLeavesTheOneInItsAir(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int f = floor(helper);
        Cow under = helper.spawn(EntityType.COW, new BlockPos(4, f + 1, 4));
        Cow beside = helper.spawn(EntityType.COW, new BlockPos(1, f + 1, 1));
        under.snapTo(under.getBlockX() + 0.5, under.getY(), under.getBlockZ() + 0.5);
        double ux = under.getX(), uy = under.getY(), uz = under.getZ();
        double bx = beside.getX(), by = beside.getY(), bz = beside.getZ();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, f + 1, 0));
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString("""
                {"id":"gt_safespot_cube","name":"Cube","shapes":[
                  {"kind":"box","dx":3,"dy":0,"dz":3,"width":3,"height":3,"depth":3,"block":"minecraft:stone"},
                  {"kind":"box","dx":0,"dy":3,"dz":0,"width":1,"height":1,"depth":1,"block":"minecraft:stone"}]}
                """).getAsJsonObject());
        try {
            String r = Builder.submit(level, anchor, plan, false);
            helper.assertTrue(r.startsWith("Building"), Component.literal("the build was refused: " + r));
            Builder.drainNow(level);
            double moved = Math.sqrt(Math.pow(under.getX() - ux, 2) + Math.pow(under.getY() - uy, 2)
                    + Math.pow(under.getZ() - uz, 2));
            helper.assertTrue(moved > 0.5, Component.literal("the cow under the cube was not moved"));
            helper.assertTrue(moved <= 2.0 + 1e-6, Component.literal("it was moved further than the minimum (2): " + moved));
            helper.assertTrue(!under.isInWall(), Component.literal("the moved cow is inside the cube"));
            helper.assertTrue(beside.getX() == bx && beside.getY() == by && beside.getZ() == bz,
                    Component.literal("a cow where the build has air was moved: " + beside.position()));
            helper.assertTrue(!beside.isInWall(), Component.literal("the cow in the air is inside a block"));
            helper.succeed();
        } finally {
            box(helper, 0, f + 1, 0, 8, f + 4, 8, Blocks.AIR);
            under.discard();
            beside.discard();
        }
    }

    /**
     * <b>"Build a castle for my Spikor" keeps Spikor inside it.</b> A hollow castle goes up around a
     * wished creature: its walls and roof are nowhere near its box, so it is not moved an inch.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aCastleBuiltAroundACreatureKeepsItInside(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int f = floor(helper);
        species(helper);
        BlockPos at = helper.absolutePos(new BlockPos(4, f + 1, 4));
        LivingEntity c = CreatureSpawn.spawn(level, ID, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f,
                EntitySpawnReason.COMMAND, false);
        BlockPos anchor = helper.absolutePos(new BlockPos(0, f, 0));
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString("""
                {"id":"gt_safespot_castle","name":"Castle","shapes":[
                  {"kind":"box","width":9,"height":8,"depth":9,"block":"minecraft:stone_bricks","hollow":true}]}
                """).getAsJsonObject());
        try {
            helper.assertTrue(c != null, Component.literal("nothing was spawned"));
            double x = c.getX(), y = c.getY(), z = c.getZ();
            String r = Builder.submit(level, anchor, plan, false);
            helper.assertTrue(r.startsWith("Building"), Component.literal("the build was refused: " + r));
            Builder.drainNow(level);
            helper.assertTrue(c.getX() == x && c.getY() == y && c.getZ() == z,
                    Component.literal("the creature was pushed out of the castle built around it: " + c.position()));
            helper.assertTrue(!c.isInWall(), Component.literal("the creature is inside the castle's wall"));
            helper.assertTrue(level.getBlockState(at.above(6)).is(Blocks.STONE_BRICKS), Component.literal("the castle has no roof over it"));
            helper.succeed();
        } finally {
            box(helper, 0, f + 1, 0, 8, f + 7, 8, Blocks.AIR);
            gone(level, c);
        }
    }
}
