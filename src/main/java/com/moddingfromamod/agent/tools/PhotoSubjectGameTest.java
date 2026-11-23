package com.moddingfromamod.agent.tools;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.wish.PhotoSubject;
import com.moddingfromamod.wish.WishSpotShot;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: the reveal photographs THIS wish's thing, never the previous wish's.</b>
 *
 * <p>Live 2026-10-03: an oasis build was photographed as the previous wish's dragon shark
 * ({@code orbit: framing 'dragon_shark'}), because {@code finish_building} named no subject and the
 * default walked the child's FINISHED wishes. These drive the real pieces against a real world —
 * the default, the building path it now reaches, the creature search with a previous wish's
 * creature standing nearer the camera, and the fallback's aim at the wish's spot. The photograph
 * itself needs a client: Gate C, {@code docs/evidence-1341/}.
 *
 * <p>Own batch: two of these put a live run into {@code Marshal.global()}, which other tests count.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PhotoSubjectGameTest {

    private static BuildPlan plan(String id) {
        return BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"%s","name":"Oasis","shapes":[
              {"kind":"box","width":7,"height":4,"depth":7,"block":"minecraft:sandstone","hollow":true}]}
            """.formatted(id)).getAsJsonObject());
    }

    private static LivingEntity creature(GameTestHelper helper, String species, BlockPos at) {
        var mob = helper.spawnWithNoFreeWill(EntityType.PIG, at);
        mob.addTag(WishMobTags.ALL);
        mob.addTag(WishMobTags.forSpecies(species));
        return mob;
    }

    /**
     * The oasis: a build wish, the previous wish's creature standing right beside the camera. The
     * subject is the BUILD, the building path takes it (it fails only for want of a player, which a
     * GameTest has none of), and the previous creature is flagged as not this wish's.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = "photoSubject")
    public static void aBuildWishIsFramedOnTheBuildNotThePreviousWishsCreature(GameTestHelper helper) {
        long t = helper.getLevel().getGameTime();
        String oasis = "gt_oasis_" + t;
        String shark = "gt_prev_shark_" + t;
        BuildRegistry.put(helper.getLevel().getServer(), plan(oasis));
        BuildRegistry.rememberAnchor(oasis, helper.absolutePos(new BlockPos(1, 1, 1)).asLong(),
                helper.getLevel().dimension());
        creature(helper, shark, new BlockPos(2, 2, 2));

        WishRun run = WishRun.detachedForTest(UUID.randomUUID(), UUID.randomUUID(), "an oasis");
        run.made(MadeKind.BUILD, oasis);

        // What the old default would have said: the previous wish's creature.
        String aim = PhotoSubject.choose(run.made(), shark);
        helper.assertTrue(oasis.equals(aim), Component.literal("the subject is this wish's build, not '" + aim + "'"));
        helper.assertTrue(PhotographTool.wrongSubjectNote(run, oasis) == null,
                Component.literal("its own build is not a camera fault"));
        String note = PhotographTool.wrongSubjectNote(run, shark);
        helper.assertTrue(note != null && note.contains("NOT something this wish made"),
                Component.literal("an agent naming the previous wish's creature is told: " + note));

        AtomicReference<String> why = new AtomicReference<>();
        Thread th = new Thread(() -> {
            try {
                PhotographTool.captureBuilding("gt_photo_1341", aim, null);
                why.set(String.valueOf(PhotographTool.BUILDING_SKIP_ON_THIS_THREAD.get()));
            } catch (Throwable e) {
                why.set("threw: " + e);
            }
        }, "gametest-photo-1341");
        th.setDaemon(true);
        th.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(why.get() != null, Component.literal("waiting for the tool"));
            helper.assertTrue(!why.get().contains("no saved design") && !why.get().contains("never built"),
                    Component.literal("the building path must take the build (a missing player is the only excuse here): "
                            + why.get()));
        });
    }

    /**
     * The creature search: a previous wish's creature of the SAME species stands beside the camera,
     * this wish's own stands at the wish's spot. The one this wish spawned is framed.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200, batch = "photoSubject")
    public static void theCreatureFramedIsTheOneThisWishSpawned(GameTestHelper helper) {
        String species = "gt_pony_" + helper.getLevel().getGameTime();
        Player camera = helper.makeMockPlayer(GameType.CREATIVE);
        camera.snapTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 2, 0.5)));
        LivingEntity old = creature(helper, species, new BlockPos(1, 2, 0));
        LivingEntity mine = creature(helper, species, new BlockPos(6, 2, 6));
        UUID wish = UUID.randomUUID();
        WishRun run = Marshal.global().submit(wish, UUID.randomUUID(), "a pony");
        try {
            run.creatureSpawned(species, mine.getUUID());
            var found = PhotographTool.findSubject(helper.getLevel(), camera, species);
            helper.assertTrue(found != null && found.getUUID().equals(mine.getUUID()),
                    Component.literal("the pony this wish spawned, not the one beside the camera: "
                            + (found == null ? "none" : found == old ? "the old one" : found.getUUID())));
        } finally {
            Marshal.global().finish(wish);
        }
        var after = PhotographTool.findSubject(helper.getLevel(), camera, species);
        helper.assertTrue(after != null && after.getUUID().equals(old.getUUID()),
                Component.literal("with no live wish it is the nearest again, as before"));
        helper.succeed();
    }

    /**
     * The fallback: a wish with nothing to frame aims at ITS spot — read from the run, seen from
     * where he wished, with a clear line through the real world to the spot (never empty sky).
     */
    @GameTest(template = "empty_test", timeoutTicks = 200, batch = "photoSubject")
    public static void theFallbackAimsAtTheWishsSpot(GameTestHelper helper) {
        var level = helper.getLevel();
        // Inside the 9³ template: GameTest walls a structure in barriers, which are solid to a ray.
        BlockPos floor = helper.absolutePos(new BlockPos(6, 1, 6));
        BlockPos stood = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(floor.below(), Blocks.GOLD_BLOCK.defaultBlockState());
        String dim = level.dimension().location().toString();
        WishRun run = WishRun.detachedForTest(UUID.randomUUID(), UUID.randomUUID(), "a garage");
        run.aimedFrom(new WishRun.Aim(dim, stood.getX() + 0.5, stood.getY(), stood.getZ() + 0.5,
                stood.getY() + 1.62, -45f, 20f));
        run.defaultAnchor(new Spot(dim, floor.getX() + 40, floor.getY(), floor.getZ()));
        run.lockAnchor(new Spot(dim, floor.getX(), floor.getY(), floor.getZ()));   // the FINAL spot

        helper.assertTrue("".equals(PhotoSubject.choose(run.made(), "pegasus")),
                Component.literal("nothing made: no subject, and never the previous wish's"));
        var spot = PhotographTool.wishSpot(run);
        helper.assertTrue(spot != null && spot.x() == floor.getX() && spot.z() == floor.getZ(),
                Component.literal("the fallback reads the wish's locked spot: " + spot));
        var pose = WishSpotShot.atSpot(spot.x(), spot.y(), spot.z(), spot.from(), 1.62);
        helper.assertTrue("from where he wished".equals(pose.how()), Component.literal(pose.how()));
        var seat = com.moddingfromamod.runtime.ClearView.find(PhotographTool.sight(level, null),
                pose.x(), pose.feetY(), pose.z(), pose.yaw(), pose.aimX(), pose.aimY(), pose.aimZ(), 1.62);
        helper.assertTrue(!seat.blind(), Component.literal("a clear line from the seat to the spot"));
        helper.assertTrue(seat.pitch() > -10f, Component.literal("looking at the spot, not up at the sky: " + seat.pitch()));
        double dx = pose.aimX() - (floor.getX() + 0.5);
        double dz = pose.aimZ() - (floor.getZ() + 0.5);
        helper.assertTrue(Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6, Component.literal("aimed at the spot itself"));

        WishRun teleport = WishRun.detachedForTest(UUID.randomUUID(), UUID.randomUUID(), "take me");
        teleport.wentTo("castle");
        var went = PhotographTool.wishSpot(teleport);
        helper.assertTrue(went != null && went.went(), Component.literal("a teleport is shot from where he went"));
        helper.succeed();
    }

    /**
     * A, the snow golem: summoned with run_command, made a pet with modify_mob, recorded on
     * the run as its handle. The camera used to look for a creature by species and tag, found none
     * ("orbit skipped for '@snow_golem': missing") and photographed the empty spot instead.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200, batch = "photoSubject")
    public static void aChangedVanillaMobIsFoundByItsHandle(GameTestHelper helper) {
        Player camera = helper.makeMockPlayer(GameType.CREATIVE);
        camera.snapTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 2, 0.5)));
        var nearer = helper.spawnWithNoFreeWill(EntityType.SNOW_GOLEM, new BlockPos(1, 2, 1));
        var golem = helper.spawnWithNoFreeWill(EntityType.SNOW_GOLEM, new BlockPos(6, 2, 6));
        String handle = com.moddingfromamod.mob.EntityHandles.global().remember(
                golem.getUUID(), "minecraft:snow_golem", "Snow Golem");
        helper.assertTrue(handle != null && handle.startsWith("@"), Component.literal("a handle: " + handle));
        String aim = PhotographTool.creatureSubject(handle);
        var found = PhotographTool.findSubject(helper.getLevel(), camera, aim);
        helper.assertTrue(found != null && found.getUUID().equals(golem.getUUID()),
                Component.literal("the golem the handle names, not the nearer one and not nothing: "
                        + (found == null ? "none" : found == nearer ? "the nearer golem" : found.getUUID())));
        golem.discard();
        helper.assertTrue(PhotographTool.findSubject(helper.getLevel(), camera, aim) == null,
                Component.literal("a handle whose animal is gone finds nothing, never another of its kind"));
        helper.succeed();
    }
}
