package com.moddingfromamod.wish.reveal;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for the wish orb and the reveals</b>: the one promise a headless server
 * CAN check. <b>Nothing is left floating.</b> An orb whose wish is no longer live (finished, stopped,
 * failed) is gone on the next pass; every reveal ends with none of its displays left; a building's
 * dome is gone whether the build finishes (it shatters) or is stopped (it goes quietly).
 *
 * <p>How it looks is Gate C's (the film and the contact sheet). Each test uses its own wish id, so
 * the displays it counts are only its own, whatever else is running beside it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class GrandRevealGameTest {

    private static final String BATCH = "reveal";

    private static double[] at(GameTestHelper helper, int x, int y, int z) {
        BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
        return new double[]{p.getX() + 0.5, p.getY(), p.getZ() + 0.5};
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void anOrbIsGoneTheMomentItsWishIsNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID wish = UUID.randomUUID();
        double[] c = at(helper, 2, 4, 2);
        WishOrbs.showForTest(level, wish, c[0], c[1], c[2], 4, 0.5f, 0);
        int shown = Fx.count(level, Fx.wishTag(wish));
        helper.assertTrue(shown >= 7, Component.literal("an orb is a core, a shell, a column and a bead per step; saw " + shown));
        // No run is live: one pass must take it away.
        WishOrbs.tick(level.getServer(), List.of(), 1);
        helper.runAfterDelay(1, () -> {
            int left = Fx.count(level, Fx.wishTag(wish));
            helper.assertTrue(left == 0, Component.literal("an orb outlived its wish: " + left + " display(s) left"));
            helper.succeed();
        });
    }

    /**
     * Every band of light in the pillar (its beam and the star at its foot) is ONE space, read back
     * from the entity. {@link Fx#beamPose} and {@link Fx#ribbon} size a band by one space's 5-pixel
     * background, so any other text stretches the whole pillar. The 1.21.8 bump turned the 1.21.4
     * JSON spelling into the literal {@code " "} (three characters, 2.8x too wide, two quote marks
     * drawn on the star), and nothing headless noticed.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aPillarsLightIsOneSpaceSoItIsTheWidthItWasSized(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID wish = UUID.randomUUID();
        double[] c = at(helper, 2, 4, 2);
        WishOrbs.showForTest(level, wish, c[0], c[1], c[2], 4, 0.5f, 0);
        int lights = 0;
        for (Entity e : level.getAllEntities()) {
            if (e == null || !e.entityTags().contains(Fx.wishTag(wish)) || e.getType() != EntityTypes.TEXT_DISPLAY) {
                continue;
            }
            lights++;
            var saved = com.moddingfromamod.compat.EntityNbt.save(e).get("text");
            String text = saved == null ? null : net.minecraft.network.chat.ComponentSerialization.CODEC
                    .parse(net.minecraft.nbt.NbtOps.INSTANCE, saved).result().map(Component::getString).orElse(null);
            helper.assertTrue(Fx.LIGHT_TEXT.equals(text), Component.literal(
                    "a pillar light's text is [" + text + "], not one space: it is drawn wider than it was sized"));
        }
        helper.assertTrue(lights >= 5, Component.literal("a pillar is three beams and two star squares; saw " + lights + " lights"));
        Fx.sweep(level, Fx.wishTag(wish));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void everyRevealEndsWithNothingLeft(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        double[] orb = at(helper, 2, 5, 2);
        double[] ground = at(helper, 2, 1, 2);
        Reveals.Look[] looks = Reveals.Look.values();
        UUID[] ids = new UUID[looks.length];
        for (int i = 0; i < looks.length; i++) {
            ids[i] = UUID.randomUUID();
            Reveals.play(level, looks[i], ids[i], orb, ground, null, new ItemStack(Items.DIAMOND));
        }
        int[] peak = new int[looks.length];
        for (int t = 0; t < 120; t++) {
            Reveals.tick(level.getServer());
            for (int i = 0; i < looks.length; i++) {
                peak[i] = Math.max(peak[i], Fx.count(level, Fx.wishTag(ids[i])));
            }
        }
        for (int i = 0; i < looks.length; i++) {
            helper.assertTrue(peak[i] > 0, Component.literal(looks[i] + " never drew anything"));
            int left = Fx.count(level, Fx.wishTag(ids[i]));
            helper.assertTrue(left == 0, Component.literal(looks[i] + " left " + left + " display(s) behind"));
        }
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aDomeShattersWhenBuiltAndVanishesWhenStopped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        double[] g = at(helper, 2, 1, 2);
        UUID built = UUID.randomUUID();
        UUID stopped = UUID.randomUUID();
        Object builtKey = new Object();
        Object stoppedKey = new Object();
        Reveals.domeFor(builtKey, level, built, g[0], g[1], g[2], 3, 3, 5);
        Reveals.domeFor(stoppedKey, level, stopped, g[0] + 8, g[1], g[2], 3, 3, 5);
        Reveals.tick(level.getServer());
        helper.assertTrue(Fx.count(level, Fx.wishTag(built)) > 0, Component.literal("no dome over a building going up"));
        helper.assertTrue(Fx.count(level, Fx.wishTag(stopped)) > 0, Component.literal("no dome over the second building"));

        Reveals.dropDome(stoppedKey);
        helper.assertTrue(Fx.count(level, Fx.wishTag(stopped)) == 0, Component.literal("a stopped build left its dome behind"));

        Reveals.shatter(builtKey);
        helper.assertTrue(Fx.count(level, Fx.wishTag(built)) > 0, Component.literal("a shatter should throw the panels out"));
        // A build that lands at once still shows its dome for MIN_DOME_TICKS before it bursts.
        for (int t = 0; t < Reveals.MIN_DOME_TICKS - 5; t++) {
            Reveals.tick(level.getServer());
        }
        helper.assertTrue(Fx.count(level, Fx.wishTag(built)) > 0,
                Component.literal("the dome burst before he could see it (a fast build must still show it)"));
        for (int t = 0; t < 120; t++) {
            Reveals.tick(level.getServer());
        }
        int left = Fx.count(level, Fx.wishTag(built));
        helper.assertTrue(left == 0, Component.literal("the shattered dome left " + left + " display(s) behind"));
        helper.succeed();
    }

    /**
     * One whirlwind per wish. The agent often builds and then touches the same building up
     * ({@code build_structure} with {@code start_from}: doors, lights); that edit used to raise a
     * second whirlwind over the finished castle, so the wish arrived twice (2026-10-15). A build
     * that was stopped before it was ever seen does not count, so a retry still gets its moment.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aWishGetsOneWhirlwindNotOnePerBuild(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        double[] g = at(helper, 2, 1, 2);
        UUID wish = UUID.randomUUID();
        Object first = new Object();
        Object edit = new Object();
        Reveals.domeFor(first, level, wish, g[0], g[1], g[2], 3, 3, 5);
        helper.assertTrue(Reveals.domeUp(first), Component.literal("the wish's first build got no whirlwind"));
        Reveals.shatter(first);
        Reveals.domeFor(edit, level, wish, g[0], g[1], g[2], 3, 3, 5);
        helper.assertFalse(Reveals.domeUp(edit),
                Component.literal("an edit of the building this wish just made raised a second whirlwind"));

        UUID retried = UUID.randomUUID();
        Object stopped = new Object();
        Object retry = new Object();
        Reveals.domeFor(stopped, level, retried, g[0] + 8, g[1], g[2], 3, 3, 5);
        Reveals.dropDome(stopped);
        Reveals.domeFor(retry, level, retried, g[0] + 8, g[1], g[2], 3, 3, 5);
        helper.assertTrue(Reveals.domeUp(retry), Component.literal("a build retried after a stop lost its whirlwind"));
        Reveals.dropDome(retry);
        Reveals.dropDome(first);
        helper.succeed();
    }

    /**
     * The owner's case (2026-09-30): a wish came out as an upside-down building hanging in the sky.
     * A dome is open underneath, so over a floating build it would show everything going up from
     * below; such a build gets a closed bubble. This is the question that picks one.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aBuildingInTheAirGetsABubbleNotADome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(1, 1, 1));
        // Build the ground and the open air this test needs rather than trusting the template's
        // floor height, which is not where this test assumed on 1.21.1.
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = 0; dz <= 3; dz++) {
                level.setBlockAndUpdate(floor.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(floor.offset(dx, 11, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
        }
        // On the ground: the ground under its lowest layer is solid.
        helper.assertFalse(com.moddingfromamod.builder.Builder.floating(level,
                floor.getX(), floor.getX() + 3, floor.getY(), floor.getZ(), floor.getZ() + 3),
                Component.literal("a building standing on the floor was treated as floating"));
        // Twelve blocks up, over nothing.
        helper.assertTrue(com.moddingfromamod.builder.Builder.floating(level,
                floor.getX(), floor.getX() + 3, floor.getY() + 12, floor.getZ(), floor.getZ() + 3),
                Component.literal("a building in the air was given a dome, open underneath"));
        UUID id = UUID.randomUUID();
        Object key = new Object();
        Reveals.domeFor(key, level, id, floor.getX() + 2, floor.getY() + 12, floor.getZ() + 2, 2, 2, 4, true);
        Reveals.tick(level.getServer());
        int below = 0;
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Display.class,
                new net.minecraft.world.phys.AABB(floor.getX() - 8, floor.getY() + 5, floor.getZ() - 8,
                        floor.getX() + 12, floor.getY() + 12, floor.getZ() + 12))) {
            if (e.entityTags().contains(Fx.wishTag(id))) {
                below++;
            }
        }
        helper.assertTrue(below > 0, Component.literal("the bubble has nothing under the building"));
        Reveals.dropDome(key);
        helper.assertTrue(Fx.count(level, Fx.wishTag(id)) == 0, Component.literal("a dropped bubble left displays behind"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void eachKindOfThingGetsItsOwnLook(GameTestHelper helper) {
        helper.assertTrue(Reveals.lookFor("creature") == Reveals.Look.CREATURE, Component.literal("creature"));
        helper.assertTrue(Reveals.lookFor("item") == Reveals.Look.ITEM, Component.literal("item"));
        helper.assertTrue(Reveals.lookFor("event") == Reveals.Look.EVENT, Component.literal("event"));
        helper.assertTrue(Reveals.lookFor("painting") == Reveals.Look.PICTURE, Component.literal("picture"));
        // Anything else bursts — including "contraption", a kind an old watch may still hold.
        helper.assertTrue(Reveals.lookFor("contraption") == Reveals.Look.BURST, Component.literal("an old kind"));
        helper.assertTrue(Reveals.lookFor("build") == null, Component.literal("a building's reveal is the builder's dome"));
        helper.assertTrue(Reveals.lookFor("mob") == null, Component.literal("an edit to a mob is not an arrival"));
        helper.succeed();
    }

    /**
     * A served wish (starter, cache, library) gets its look from the commands it ran. The Opus demo's
     * cached sword arrived with no moment at all; a build is left to the builder's dome.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aServedWishGetsALookFromWhatItRan(GameTestHelper helper) {
        check(helper, Reveals.lookForCommands(List.of("wishdatapack lightning_sword_lightning give")),
                Reveals.Look.ITEM, "a served datapack item");
        check(helper, Reveals.lookForCommands(List.of("wishmob spawn pet_dragon")),
                Reveals.Look.CREATURE, "a library creature");
        check(helper, Reveals.lookForCommands(List.of("fill ~1 ~-1 ~-1 ~3 ~-1 ~1 minecraft:water",
                "summon minecraft:axolotl ~2 ~-1 ~")), Reveals.Look.CREATURE, "fill then summon");
        check(helper, Reveals.lookForCommands(List.of("wishpaint starter_creeper_face")),
                Reveals.Look.PICTURE, "a painting");
        check(helper, Reveals.lookForCommands(List.of("moddyevent stage meteor")),
                Reveals.Look.EVENT, "an event");
        check(helper, Reveals.lookForCommands(List.of("wishbuild secret_treehouse")),
                null, "a build (the dome is its moment)");
        check(helper, Reveals.lookForCommands(List.of("time set day")),
                Reveals.Look.BURST, "anything else");
        helper.succeed();
    }

    private static void check(GameTestHelper helper, Reveals.Look got, Reveals.Look want, String what) {
        if (got != want) {
            helper.fail(Component.literal(what + ": wanted " + want + ", got " + got));
        }
    }

    /**
     * A creature is designed before it stands anywhere. The Muse smoke played its summoning circle
     * at sculpt_creature, 38 s before run_command spawned the dragon. So designing queues nothing,
     * and the wish's announcement plays the circle, once.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aCreatureIsSummonedWhenItArrivesNotWhenItIsDesigned(GameTestHelper helper) {
        var run = com.moddingfromamod.marshal.WishRun.detachedForTest(
                UUID.randomUUID(), UUID.randomUUID(), "a tiny dragon");
        int before = Reveals.queued();
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, "tiny_dragon");
        Reveals.queue(run, com.moddingfromamod.marshal.MadeKind.CREATURE, "tiny_dragon");
        helper.assertTrue(Reveals.queued() == before,
                Component.literal("designing a creature queued a reveal before it stands anywhere"));
        Reveals.onFinished(run);
        helper.assertTrue(Reveals.queued() == before + 1, Component.literal("the announcement did not summon it"));
        Reveals.onFinished(run);     // finish_building then finish_wish: still one arrival
        helper.assertTrue(Reveals.queued() == before + 1, Component.literal("the creature arrived twice"));
        helper.succeed();
    }

    /**
     * a pillar from a previous session was still standing, because the restart sweep only sees
     * LOADED chunks and the copy a chunk saved came back with it, tracked by nothing. A tagged display
     * coming in through the real chunk-load door ({@code addLegacyChunkEntities}, which joins
     * "loaded from disk") is refused; an untagged one through the same door is let in (so the door
     * works and only ours are turned away); and a LIVE pillar is kept, and rebuilds itself when its
     * chunk unloads its displays from under it.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aWishDisplayBackFromDiskIsRefusedAndALivePillarKept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID live = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        double[] c = at(helper, 2, 4, 2);
        WishOrbs.showForTest(level, live, c[0], c[1], c[2], 4, 0.5f, 0);
        int shown = Fx.count(level, Fx.wishTag(live));
        helper.assertTrue(shown > 0, Component.literal("the live pillar never stood up"));

        Entity stale = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        Entity other = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(stale != null && other != null, Component.literal("no block display"));
        stale.snapTo(c[0], c[1], c[2], 0f, 0f);
        stale.addTag(Fx.TAG);
        stale.addTag(Fx.wishTag(gone));
        other.snapTo(c[0], c[1], c[2], 0f, 0f);
        level.addLegacyChunkEntities(java.util.stream.Stream.of(stale, other));
        helper.assertTrue(level.getEntity(stale.getUUID()) == null,
                Component.literal("a wish display came back from disk and was let in: nothing tracks it, so it stands forever"));
        helper.assertTrue(level.getEntity(other.getUUID()) != null,
                Component.literal("an untagged display through the same door was refused: the door is broken or too greedy"));
        other.discard();
        helper.assertTrue(Fx.count(level, Fx.wishTag(live)) == shown,
                Component.literal("refusing the stale copy touched the live pillar"));

        // Its chunk unloads a live display from under it: the pillar notices and builds itself again.
        for (Entity e : level.getAllEntities()) {
            if (e != null && e.entityTags().contains(Fx.wishTag(live))) {
                e.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                break;
            }
        }
        WishOrbs.showForTest(level, live, c[0], c[1], c[2], 4, 0.5f, 2);
        // Counted in the same tick: a test orb has no run, so the next server pass takes it down.
        int now = Fx.count(level, Fx.wishTag(live));
        WishOrbs.drop(live);
        helper.assertTrue(now == shown, Component.literal("a pillar whose chunk took a display did not rebuild: "
                + now + " of " + shown));
        helper.succeed();
    }
}
