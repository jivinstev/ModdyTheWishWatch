package com.moddingfromamod.vanilla;

import java.io.IOException;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.mixin.DisplayLightInvoker;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.phys.AABB;

/**
 * <b>A big flier is lit like the walker beside it, and stands on the ground, not in it</b>.
 *
 * <p>The owner marked six of the B4 builder bodies bad — exactly the six fliers that come out
 * bigger than a block (the plane, saucer, helicopter, balloon, rocket, griffin): black, and sunk.
 * Measured in the starter studio, each one's bat carrier stood 2 to 6 blocks INSIDE the grass, its
 * eye read sky light 0, and every display carried a brightness override of 0/0 — while a bear on a
 * cow beside it read 15. The cause is vanilla's roost: a bat is born resting, a resting bat is pinned
 * with its top against the block above ({@code setPosRaw(x, floor(y) + 1 - bbHeight, z)}), and
 * only the bat's own AI step wakes it — which {@code BatRideMixin} skips for a carrier its
 * archetype drives. Fixed there: a rig's carrier bat never rests.
 *
 * <p>This drives the real path a wish takes ({@code wishmob spawn}'s {@link CreatureSpawn#spawn}) with a
 * shipped builder body, the plane, and a walker beside it, and fails on the old behaviour three ways:
 * the plane's carrier is still resting, its feet are under the floor, or its displays carry less
 * light than the walker's.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class FlierCarrierLightGameTest {

    /**
     * ON the floor, as {@code wishmob spawn} stands a creature on the ground (y 1 is the top of
     * {@code empty_test}'s floor). One block higher, the first pin (0.9 down for this carrier) only
     * dropped it through air back onto the floor, and the old code passed.
     */
    private static final BlockPos FLIER = new BlockPos(2, 1, 2);
    private static final BlockPos WALKER = new BlockPos(6, 1, 2);

    /** Ticks it is left to settle: a pinned bat had sunk within a few, and lies there after. */
    private static final int SETTLE = 60;

    @GameTest(template = "empty_test", batch = "flier_carrier_light", timeoutTicks = 20 * 60)
    public static void aBigFlierIsLitLikeTheWalkerBesideItAndStandsOnTheGround(GameTestHelper helper) {
        String flier = "flierlight_plane";
        String walker = "flierlight_bear";
        try {
            plant(helper, flier, "plane");
            plant(helper, walker, "bear");
        } catch (IOException e) {
            helper.fail(Component.literal("could not read a builder body: " + e));
            return;
        }
        spawn(helper, flier, FLIER);
        spawn(helper, walker, WALKER);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(rigged(helper, flier) && rigged(helper, walker),
                        Component.literal("not rigged yet")))
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    Mob plane = RideFitProbeGameTest.carrier(helper, flier);
                    Mob bear = RideFitProbeGameTest.carrier(helper, walker);
                    helper.assertTrue(plane instanceof Bat,
                            Component.literal("the plane should ride a bat, rides " + plane.getType()));
                    double floor = helper.absolutePos(FLIER).getY();
                    int[] planeLight = shown(helper.getLevel(), plane);
                    int[] bearLight = shown(helper.getLevel(), bear);
                    // What a vanilla mob STANDING ON EACH SPOT is lit by: the light at its eye, read off the
                    // spot rather than off the carrier, so a carrier sunk into the floor is measured
                    // against the open air it should be standing in. Each creature is held to its OWN spot.
                    // Holding the plane to the bear's numbers assumed both spots see the same sky, and a
                    // neighbour can break that: the 1.21.8 runner never clears a finished plot and lays the
                    // next batch's beside it, and on 2026-11-12 BuilderMakeGameTest's size-4
                    // treehouse (20,524 blocks, slid off its own plot for a building) stood over this one —
                    // the plane's spot read sky 4 and the bear's sky 9, both lit exactly as vanilla lights
                    // those spots, and the old comparison called that a dark plane.
                    int[] planeSpot = spotLight(helper, FLIER, plane);
                    int[] bearSpot = spotLight(helper, WALKER, bear);
                    String said = String.format(java.util.Locale.ROOT,
                            "plane carrier bb %.2f tall, feet %.2f over the floor top (y=%.0f), resting=%s, inside a block=%s, "
                                    + "displays lit block %d sky %d (a mob on its spot: block %d sky %d); "
                                    + "the bear beside it feet %.2f, displays block %d sky %d (a mob on its spot: block %d sky %d)",
                            plane.getBbHeight(), plane.getY() - floor, floor, ((Bat) plane).isResting(),
                            !helper.getLevel().noCollision(plane, plane.getBoundingBox().deflate(1e-3)),
                            planeLight[0], planeLight[1], planeSpot[0], planeSpot[1],
                            bear.getY() - floor, bearLight[0], bearLight[1], bearSpot[0], bearSpot[1]);
                    System.out.println("FLIER-CARRIER-LIGHT: " + said);
                    helper.assertTrue(plane.getBbHeight() > 1.0,
                            Component.literal("the case needs a carrier taller than a block: " + said));
                    helper.assertFalse(((Bat) plane).isResting(),
                            Component.literal("a carrier bat must never roost: " + said));
                    helper.assertTrue(plane.getY() >= floor - 1e-3
                                    && helper.getLevel().noCollision(plane, plane.getBoundingBox().deflate(1e-3)),
                            Component.literal("the plane's carrier is inside the ground: " + said));
                    helper.assertTrue(planeSpot[1] > 0 && bearSpot[1] > 0,
                            Component.literal("a spot is in the dark, so the comparison says nothing: " + said));
                    helper.assertTrue(bearLight[0] == bearSpot[0] && bearLight[1] == bearSpot[1],
                            Component.literal("the walker (the control) is not lit like a mob on its spot: " + said));
                    helper.assertTrue(planeLight[0] == planeSpot[0] && planeLight[1] == planeSpot[1],
                            Component.literal("the plane is not lit like a mob standing on its spot: " + said));
                })
                .thenExecute(() -> {
                    clear(helper, flier);
                    clear(helper, walker);
                })
                .thenSucceed();
    }

    /** A shipped builder body, under a test id: its sculpt and its species. */
    private static void plant(GameTestHelper helper, String id, String body) throws IOException {
        String sculpt = resource("/builder-bodies/species/" + body + "/sculpt.json");
        List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(
                JsonParser.parseString(sculpt).getAsJsonObject().getAsJsonArray("parts"));
        CreatureFiles.write(id, parts, sculpt);
        JsonObject o = JsonParser.parseString(resource("/builder-bodies/species/" + body + "/species.json"))
                .getAsJsonObject();
        o.addProperty("id", id);
        o.addProperty("modelKey", id);
        com.moddingfromamod.testing.SpeciesForTest.put(helper, Species.fromJson(o));
    }

    private static void spawn(GameTestHelper helper, String id, BlockPos at) {
        // `wishmob spawn`'s own call (SpotSeat.spawnOne): the rig is built in the same tick, so a
        // driven carrier never gets the one AI step that would have woken it.
        BlockPos p = helper.absolutePos(at);
        CreatureSpawn.spawn(helper.getLevel(), id, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0f,
                net.minecraft.world.entity.EntitySpawnReason.COMMAND, false);
    }

    private static boolean rigged(GameTestHelper helper, String id) {
        Mob c = RideFitProbeGameTest.carrier(helper, id);
        return c != null && VanillaRigAnimator.rigOf(c) != null;
    }

    private static void clear(GameTestHelper helper, String id) {
        Mob c = RideFitProbeGameTest.carrier(helper, id);
        if (c != null) {
            DisplayRig.reap(helper.getLevel(), c.getUUID());
            c.discard();
        }
    }

    /**
     * {block, sky} vanilla lights a mob by when it stands on {@code spot} ({@code Entity.getLightLevelDependentMagicValue}
     * reads the block at the eye): the floor top plus this carrier's eye height, at the spot's centre. Taken from the spot,
     * never from where the carrier ended up, so it is the light the creature SHOULD be drawn in.
     */
    private static int[] spotLight(GameTestHelper helper, BlockPos spot, Mob carrier) {
        BlockPos p = helper.absolutePos(spot);
        BlockPos eye = BlockPos.containing(p.getX() + 0.5, p.getY() + carrier.getEyeHeight(), p.getZ() + 0.5);
        ServerLevel level = helper.getLevel();
        return new int[]{level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eye),
                level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye)};
    }

    /** {block, sky} of the light override this carrier's displays carry (the darkest of them). */
    private static int[] shown(ServerLevel level, Mob carrier) {
        String tag = DisplayRig.RIG_OWNER_PREFIX + carrier.getUUID();
        int block = 15;
        int sky = 15;
        int seen = 0;
        for (Entity d : level.getEntities((Entity) null, new AABB(carrier.blockPosition()).inflate(16),
                e -> e instanceof Display && e.getTags().contains(tag))) {
            int packed = ((DisplayLightInvoker) d).moddingfromamod$packedBrightnessOverride();
            if (packed == -1) {
                continue;
            }
            seen++;
            block = Math.min(block, (packed >> 4) & 0xF);
            sky = Math.min(sky, (packed >> 20) & 0xF);
        }
        return seen == 0 ? new int[]{-1, -1} : new int[]{block, sky};
    }

    private static String resource(String path) throws IOException {
        try (var in = FlierCarrierLightGameTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new java.io.FileNotFoundException(path);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
