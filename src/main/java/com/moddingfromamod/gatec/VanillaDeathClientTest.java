package com.moddingfromamod.gatec;

import net.minecraft.world.entity.EntitySpawnReason;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigDriver;
import com.moddingfromamod.vanilla.RigFlop;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigCarrier;
import com.moddingfromamod.vanilla.SpikeFixture;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>Gate C, ladder tiers 3 and 4.1 — a creature is really KILLED, and the rig flashes red and
 * falls over.</b>
 *
 * <p>Every other state row in this spike is a state POKED onto a creature by a test. This one is
 * not: the subjects are given real health, hit with real lethal damage, and everything that
 * follows is vanilla's own death path — {@code hurtTime}, {@code deathTime}, the twenty-tick
 * animation, the removal at the end. The rig is driven from those fields and from nothing else.
 * That distinction is the whole point of the run, because the two mechanisms it exercises
 * (M4 the tint, M2 the whole-body modifier) are the first that have to be driven by an event
 * rather than set once for a photograph.
 *
 * <h2>The three-way comparison, and why arm A is not the control here</h2>
 *
 * <p>The vanilla cow is the control, as in the state run — and it matters more here, because
 * <b>the mod path does neither of these things for a sculpted creature.</b>
 * {@code WishMobRenderer.renderCustom} passes {@code OverlayTexture.NO_OVERLAY} (so no red flash)
 * and returns before {@code super.render} (so {@code setupRotations} never runs, hence no flop).
 * That is the third and fourth thing the custom-geometry branch skips, after the shadow. So arm A
 * is expected to do NOTHING here, and a run where the cow also does nothing is a broken test.
 *
 * <h2>The oracle is the SILHOUETTE'S ASPECT, which is the honest way to measure "it fell over"</h2>
 *
 * <p>A creature standing up is tall and narrow; the same creature lying down is short and wide.
 * Counting changed pixels cannot tell a flop from a creature that merely vanished — and vanishing
 * is exactly what happens at the end of a death, so it is not a hypothetical. The box's
 * width/height ratio before and after is unambiguous, needs no colour rule, and fails loudly if
 * the body simply disappeared (there would be no box at all).
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaDeathClientTest {

    private VanillaDeathClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanilladeath".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanilladeath"));

    private static final String CASE = System.getProperty(P + "cases", "thornshell");

    /** the game's tick rate while filming (5 = 4x slow motion, sped back up to ~60 fps); 20 changes nothing. */
    private static final int FILM_TICK_RATE = Integer.getInteger(P + "filmtickrate", 20);

    private enum Phase { WAIT_TITLE, WAIT_INGAME, EMPTY, PREPARE, RELOAD, SPAWN, SETTLE, ALIVE,
                         KILL, DYING, GONE, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    private static boolean reloadDone;
    private static boolean groundLaid;
    private static String key;
    private static DisplayRig rig;
    private static UUID mobA;
    private static UUID mobZ;
    private static long filmStart;
    private static String failLater;

    private static final List<String> NOTES = new ArrayList<>();
    private static final Map<String, String> RESULTS = new LinkedHashMap<>();

    // The same stage as the state run, at the same pose, so the two are comparable.
    private static final double CAM_X = 8.5;
    private static final double CAM_Y = 100.0;
    private static final double CAM_Z = 8.5;
    private static final float CAM_PITCH = 12f;
    private static final double STAGE_Z =
            CAM_Z + Double.parseDouble(System.getProperty(P + "standoff", "17.0"));
    private static final double FOOT_Y = 95.0;
    private static final int GROUND_Y = (int) FOOT_Y - 1;
    private static final double LANE = Double.parseDouble(System.getProperty(P + "lane", "10.0"));
    private static final double LANE_A = CAM_X + LANE;
    private static final double LANE_B = CAM_X - LANE;

    private static int[][] shotEmpty;
    private static int[][] shotAlive;
    private static int[] boxA;
    private static int[] boxZ;
    private static int[] boxB;

    /**
     * Peak redness per arm across the whole death, sampled every tick so a one-frame miss cannot
     * report zero. Stored as a RATIO rather than a pixel count — see {@link #redness}.
     */
    private static double redA = -1;
    private static double redZ = -1;
    private static double redB = -1;
    private static double redA0 = -1;
    private static double redZ0 = -1;
    private static double redB0 = -1;

    /** The flattest (widest-vs-tallest) the body got, per arm. */
    private static double flatA = -1;
    private static double flatZ = -1;
    private static double flatB = -1;
    private static double uprightA = -1;
    private static double uprightZ = -1;
    private static double uprightB = -1;

    /**
     * 🔴 <b>How long after the killing blow the aspect oracle is allowed to look, and it is the
     * fix for an instrument that PASSED while measuring the wrong thing.</b>
     *
     * <p>The first run reported the mod-path creature falling over — aspect 0.72 → 1.54 — for a
     * renderer that provably has no flop in it ({@code renderCustom} returns before
     * {@code super.render}, so {@code setupRotations} never runs). Opening the frame settled it in
     * one look: the creature was gone, and what the box had found was its <b>death poof</b>, a
     * wide short scatter of white particles that vanilla emits at removal
     * ({@code broadcastEntityEvent(60)}). A cloud is exactly the shape a fallen body is, so
     * "the silhouette got wide" was satisfied by a creature that had merely <em>vanished</em>.
     *
     * <p>That is §2.2's rule again — <b>a control that is true of the bug is not a control</b> —
     * and the run was GREEN with it, because the two subjects that were supposed to flop did flop.
     * It was only visible on the arm expected to do nothing.
     *
     * <p>Two guards, because either alone leaves a way in: particles are quieted, and the oracle
     * only looks during the fall. A vanilla death is 20 ticks and the body is flat at
     * {@link RigFlop#flatAt()} = 14, so 18 client ticks after the blow is past flat and before
     * removal, with the client/server tick offset absorbed.
     */
    private static final int FALL_WINDOW = 18;

    private static int drivenTicks;
    private static int maxDeathTime;
    private static float maxFlopDegrees;
    private static boolean sawRealDamage;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || phase == Phase.DONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            switch (phase) {
                case WAIT_TITLE -> {
                    if (ticks > ClientHarness.TITLE_SETTLE) {
                        go(Phase.WAIT_INGAME);
                    }
                }
                case WAIT_INGAME -> {
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-death", ticks, true)) {
                        go(Phase.EMPTY);
                    }
                }
                case EMPTY -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        hold(mc);
                        pinTheSky(mc);
                        ClientHarness.quietParticles(mc);
                        ground(mc);
                        if (ticks > ClientHarness.INGAME_SETTLE + 10) {
                            go(Phase.PREPARE);
                        }
                    }
                }
                case PREPARE -> prepare(mc);
                case RELOAD -> {
                    if (reloadDone && mc.getOverlay() == null && ticks > 20) {
                        go(Phase.SPAWN);
                    }
                }
                case SPAWN -> {
                    hold(mc);
                    if (ticks > 10) {
                        if (shotEmpty == null) {
                            shotEmpty = grab(mc, "0-empty");
                        }
                        spawn(mc);
                    }
                }
                case SETTLE -> {
                    hold(mc);
                    if (ticks == 2 && FILM_TICK_RATE != 20) {
                        mc.player.connection.sendCommand("tick rate " + FILM_TICK_RATE);
                    }
                    if (ticks > 30) {
                        go(Phase.ALIVE);
                    }
                }
                case ALIVE -> alive(mc);
                case KILL -> {
                    hold(mc);
                    kill(mc);
                    go(Phase.DYING);
                }
                case DYING -> dying(mc);
                case GONE -> gone(mc);
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    // ------------------------------------------------------------------------------- the setup

    private static void prepare(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        key = "vdeath_" + RigCarrier.safe(CASE);
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        try {
            CreatureFiles.write(key, parts, "{}");
            NOTES.add("carrier=" + RigCarrier.CARRIER + " (dyeable, so the tint channel is live)");
        } catch (Exception e) {
            finish(false, "creature write failed: " + e);
            return;
        }
        // (the pack-rig arm's per-creature pack write, and its refusal on an untintable `paper`
        // carrier, were deleted by an earlier change — the atom rig's carrier is always dyeable)
        NOTES.add(ClientHarness.excludePack(mc, "file/mfam-vanilla-rig"));
        mc.reloadResourcePacks().thenRun(() -> reloadDone = true);
        go(Phase.RELOAD);
    }

    private static void spawn(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        var server = mc.getSingleplayerServer();
        if (server == null) {
            finish(false, "no integrated server");
            return;
        }
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        double scale = SpikeFixture.scale(CASE);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            ServerLevel level = p.level();
            SpeciesRegistry.put(server, Species.fromJson(JsonParser.parseString(
                    "{\"id\":\"" + key + "\",\"modelKey\":\"" + key + "\",\"health\":40,"
                            + "\"speed\":0.0,\"scale\":" + scale + "}").getAsJsonObject()));

            WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
            if (mob == null) {
                finish(false, "could not create the wish mob");
                return;
            }
            mob.setSpeciesId(key);
            mob.snapTo(LANE_A, FOOT_Y, STAGE_Z, 270f, 0f);
            mob.setYBodyRot(270f);
            mob.setYHeadRot(270f);
            mob.setNoGravity(true);
            mob.setNoAi(true);
            mob.setSilent(true);
            mob.applySpecies(true);
            level.addFreshEntity(mob);
            mobA = mob.getUUID();

            var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
            rig = DisplayRig.spawn(level, new Vec3(LANE_B, FOOT_Y, STAGE_Z), 270f, parts, scale,
                    CreatureAnatomy.widthBlocks(extents) * scale,
                    CreatureAnatomy.heightBlocks(extents) * scale, null);
            rig.base().setNoGravity(true);
            rig.base().setNoAi(true);
            rig.base().setSilent(true);
            for (var d : rig.all()) {
                d.setNoGravity(true);
            }
            // 🔴 NOTHING is invulnerable in this run, unlike every other state run. The subject IS
            // the death, so making them survivable would be measuring a poke.

            var z = net.minecraft.world.entity.EntityType.COW.create(level, EntitySpawnReason.COMMAND);
            if (z != null) {
                z.snapTo(CAM_X, FOOT_Y, STAGE_Z, 270f, 0f);
                z.setYBodyRot(270f);
                z.setNoGravity(true);
                z.setNoAi(true);
                z.setSilent(true);
                z.setPersistenceRequired();
                level.addFreshEntity(z);
                mobZ = z.getUUID();
            }
            NOTES.add(String.format("subjects: wish mob %.2f x %.2f hp %.0f | rig carrier "
                            + "%.2f x %.2f hp %.0f | cow %.2f x %.2f hp %.0f",
                    mob.getBbWidth(), mob.getBbHeight(), mob.getHealth(),
                    rig.base().getBbWidth(), rig.base().getBbHeight(), rig.base().getHealth(),
                    z == null ? 0 : z.getBbWidth(), z == null ? 0 : z.getBbHeight(),
                    z == null ? 0 : z.getHealth()));
        });
        go(Phase.SETTLE);
    }

    // ------------------------------------------------------------------------------ the death

    private static void alive(Minecraft mc) {
        hold(mc);
        // ⚠ THE FILM HAS TO OPEN ON THE CREATURE STANDING. The first cut set the marker just
        // before the blow, and the clip's own first frame was a body already 70 degrees over --
        // so the one thing the film exists to show, a creature going from upright to flat, had
        // no "upright" in it. A clip of a fall needs a second of before.
        if (ticks == 1) {
            filmStart = System.currentTimeMillis();
        }
        if (ticks < 30) {
            return;
        }
        int[][] shot = grab(mc, "1-alive");
        shotAlive = shot;
        int w = shot[0].length;
        boxA = box(shotEmpty, shot, 0, (int) (w * 0.40));
        boxZ = box(shotEmpty, shot, (int) (w * 0.40), (int) (w * 0.60));
        boxB = box(shotEmpty, shot, (int) (w * 0.60), w);
        if (boxA == null || boxB == null || boxZ == null) {
            finish(false, "a subject never drew anything: A=" + fmt(boxA) + " cow=" + fmt(boxZ)
                    + " B=" + fmt(boxB));
            return;
        }
        uprightA = aspect(boxA);
        uprightZ = aspect(boxZ);
        uprightB = aspect(boxB);
        redA0 = redness(shot, boxA);
        redZ0 = redness(shot, boxZ);
        redB0 = redness(shot, boxB);
        NOTES.add("standing: arm A " + fmt(boxA) + " aspect " + r2(uprightA)
                + " | cow " + fmt(boxZ) + " aspect " + r2(uprightZ)
                + " | arm B " + fmt(boxB) + " aspect " + r2(uprightB));
        // 🔴 THE TINT CHANNEL'S OWN CONTROL, and it is the only thing that would catch the
        // leather-brown default: an untinted rig must look like the mod creature. If `wear` ever
        // stops writing RigTint.NONE, arm B's mean goes brown while everything else still passes.
        RESULTS.put("untinted", "mean rgb: arm A " + meanIn(shot, boxA)
                + " | cow " + meanIn(shot, boxZ) + " | arm B " + meanIn(shot, boxB));
        go(Phase.KILL);
    }

    private static void kill(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (Mob m : new Mob[] {armA(server), rig == null ? null : rig.base(),
                    byId(server, mobZ)}) {
                if (m == null) {
                    continue;
                }
                // A REAL hit from a real damage source, on a mob with real health. Not
                // `setHealth(0)`, not `discard()`, not `kill()` -- each of those skips some part
                // of the path this run exists to exercise.
                boolean landed = com.moddingfromamod.compat.Damage.hurt(m, m.damageSources().generic(), m.getMaxHealth() * 10f);
                sawRealDamage |= landed;
            }
        });
    }

    /** Set once the death has been dealt: from then on {@link #onServerTick} drives the rig. */
    private static volatile boolean driving;

    /**
     * <b>The rig's death, driven the way the shipped animator drives it</b>: on the SERVER
     * tick, after the carrier's own tick advanced {@code deathTime}, through the same
     * {@link RigDriver#dying} call {@code VanillaRigAnimator} makes, on the same beat and ease. It
     * used to be driven from the CLIENT tick with a one-tick ease, a schedule nothing ships: the
     * film then showed a fall that froze under lag and caught up, and that was what was judged.
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!driving || rig == null) {
            return;
        }
        Mob b = rig.base();
        if (b == null) {
            return;
        }
        // ⚠ Read the carrier while it still exists; a Mob is REMOVED at deathTime 20, and the
        // displays outlive it. The driver holds the last pose after that rather than snapping
        // upright, which is what a production driver would have to do too.
        int hurt = b.hurtTime;
        int death = b.deathTime;
        if (!b.isRemoved()) {
            maxDeathTime = Math.max(maxDeathTime, death);
            maxFlopDegrees = Math.max(maxFlopDegrees, RigFlop.degrees(death, 0f));
            drivenTicks += RigDriver.dying(rig, hurt, death, event.getServer().overworld().getGameTime(),
                    com.moddingfromamod.vanilla.VanillaRigAnimator.INTERVAL) > 0 ? 1 : 0;
        }
    }

    private static void dying(Minecraft mc) {
        hold(mc);
        driving = true;   // onServerTick drives the rig from now on, on the server's own tick
        int[][] shot = grab(mc, null);
        redA = Math.max(redA, redness(shot, boxA));
        redZ = Math.max(redZ, redness(shot, boxZ));
        redB = Math.max(redB, redness(shot, boxB));
        // 🔴 MEASURE THE FALL, NOT THE AFTERMATH. See FALL_WINDOW.
        if (ticks <= FALL_WINDOW) {
            track(shot);
        }
        if (ticks == 12) {
            grab(mc, "2-mid-death");
        }
        if (ticks > 46) {
            grab(mc, "3-after");
            go(Phase.GONE);
        }
    }

    private static void gone(Minecraft mc) {
        hold(mc);
        if (ticks < 20) {
            return;
        }
        var server = mc.getSingleplayerServer();
        if (server != null && rig != null && !fired) {
            fired = true;
            server.execute(() -> {
                int reaped = DisplayRig.reap((ServerLevel) rig.base().level(),
                        rig.base().getUUID());
                RESULTS.put("reap", reaped + " of " + rig.displays().size()
                        + " displays cleared after the death");
            });
            return;
        }
        if (ticks > 40) {
            grab(mc, "4-reaped");
            // The film runs from before the blow to after the reap, so one clip carries the whole
            // story: standing, hit, red, falling, lying, gone. Cutting it at the end of the fall
            // would leave the most important frame -- an empty floor -- out of the evidence.
            writeFilmWindow("death");
            score();
        }
    }

    /** The flattest each body got — the oracle for "it fell over". */
    private static void track(int[][] shot) {
        int w = shot[0].length;
        int[] a = box(shotEmpty, shot, 0, (int) (w * 0.40));
        int[] z = box(shotEmpty, shot, (int) (w * 0.40), (int) (w * 0.60));
        int[] b = box(shotEmpty, shot, (int) (w * 0.60), w);
        if (a != null) {
            flatA = Math.max(flatA, aspect(a));
        }
        if (z != null) {
            flatZ = Math.max(flatZ, aspect(z));
        }
        if (b != null) {
            flatB = Math.max(flatB, aspect(b));
        }
    }

    private static void score() {
        List<String> bad = new ArrayList<>();
        if (failLater != null) {
            bad.add(failLater);
        }
        RESULTS.put("driver", "real damage landed=" + sawRealDamage + "; the rig was driven on "
                + drivenTicks + " ticks, peak deathTime " + maxDeathTime + " -> "
                + Math.round(maxFlopDegrees) + " degrees over (vanilla is flat at deathTime "
                + RigFlop.flatAt() + ")");
        RESULTS.put("red flash", "red fraction of the body, alive -> peak while dying: arm A "
                + r3(redA0) + " -> " + r3(redA) + " | vanilla cow " + r3(redZ0) + " -> " + r3(redZ)
                + " | arm B " + r3(redB0) + " -> " + r3(redB));
        RESULTS.put("flop", "aspect standing -> flattest within " + FALL_WINDOW
                + " ticks of the blow: arm A " + r2(uprightA) + " -> " + r2(flatA)
                + " | vanilla cow " + r2(uprightZ) + " -> " + r2(flatZ)
                + " | arm B " + r2(uprightB) + " -> " + r2(flatB));

        for (var e : RESULTS.entrySet()) {
            NOTES.add(e.getKey() + ": " + e.getValue());
        }

        if (!sawRealDamage) {
            bad.add("no damage landed on anything, so nothing below measures a death");
        }
        if (maxDeathTime <= 0) {
            bad.add("the rig's carrier never entered its death animation (deathTime stayed 0) -- "
                    + "the hit did not kill it, or it was invulnerable");
        }
        // The cow is the control for BOTH rows: it is the only subject that gets vanilla's own
        // treatment, since the mod path's custom-model branch does neither.
        double bar = 1.04;
        if (redZ0 <= 0 || redZ < redZ0 * bar) {
            bad.add("the VANILLA COW never flashed red (" + RESULTS.get("red flash") + ") -- the "
                    + "shutter missed the whole flash, or the redness oracle is not measuring one, "
                    + "so the rig's number says nothing either way");
        } else if (redB0 <= 0 || redB < redB0 * bar) {
            bad.add("the rig never flashed red while the cow did: " + RESULTS.get("red flash"));
        } else {
            NOTES.add("red flash: the rig flashed -- its body's red fraction rose "
                    + pct(redB0, redB) + " against the vanilla cow's " + pct(redZ0, redZ));
        }
        if (flatZ <= uprightZ * 1.15) {
            bad.add("the VANILLA COW never fell over (aspect " + r2(uprightZ) + " -> " + r2(flatZ)
                    + ") -- the oracle is not measuring a flop at all");
        } else if (flatB <= uprightB * 1.15) {
            bad.add("the rig never fell over: " + RESULTS.get("flop"));
        } else {
            NOTES.add("flop: the rig fell over -- its silhouette went from "
                    + r2(uprightB) + " to " + r2(flatB) + " wide-over-tall, against the vanilla "
                    + "cow's " + r2(uprightZ) + " -> " + r2(flatZ));
        }
        if (redA < redA0 * 1.04 && flatA <= uprightA * 1.15) {
            NOTES.add("mod path: as predicted, arm A did NEITHER -- no red flash and no flop. "
                    + "renderCustom passes OverlayTexture.NO_OVERLAY and returns before "
                    + "super.render, so neither the overlay nor setupRotations ever runs for a "
                    + "sculpted creature");
        }
        finish(bad.isEmpty(), String.join("; ", bad));
    }

    // ------------------------------------------------------------------------------- the stage

    private static void ground(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || groundLaid) {
            return;
        }
        groundLaid = true;
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                groundLaid = false;
                return;
            }
            ServerLevel level = p.level();
            var floor = net.minecraft.world.level.block.Blocks.WHITE_CONCRETE.defaultBlockState();
            for (int x = (int) CAM_X - 16; x <= (int) CAM_X + 16; x++) {
                for (int z = (int) STAGE_Z - 8; z <= (int) STAGE_Z + 8; z++) {
                    level.setBlock(new net.minecraft.core.BlockPos(x, GROUND_Y, z), floor, 3);
                }
            }
        });
    }

    private static void pinTheSky(Minecraft mc) {
        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (ServerLevel level : server.getAllLevels()) {
                level.setDayTime(6000L);
                level.setWeatherParameters(6000, 0, false, false);
            }
            server.overworld().getGameRules().getRule(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME)
                    .set(false, server);
            server.overworld().getGameRules().getRule(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER)
                    .set(false, server);
        });
    }

    private static void hold(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        mc.options.hideGui = true;
        mc.setScreen(null);
        mc.player.setPos(CAM_X, CAM_Y, CAM_Z);
        mc.player.setYRot(0f);
        mc.player.setXRot(CAM_PITCH);
        mc.player.xOld = CAM_X;
        mc.player.yOld = CAM_Y;
        mc.player.zOld = CAM_Z;
        mc.player.yRotO = 0f;
        mc.player.xRotO = CAM_PITCH;
    }

    // ------------------------------------------------------------------------- the instruments

    private static final int BODY_THRESHOLD = 70;

    /** Width over height. Standing is well under 1; flat on its side is well over. */
    private static double aspect(int[] b) {
        return b == null ? -1 : (double) (b[2] - b[0] + 1) / Math.max(1, b[3] - b[1] + 1);
    }

    /**
     * <b>The red FRACTION of the body's own mean colour</b>, {@code r / (r+g+b)}, over the pixels
     * that are subject rather than stage.
     *
     * <p>🔴 <b>A per-pixel difference cannot measure this, and the first version of this method
     * was one.</b> The body is FALLING while it flashes, so most changed pixels are floor becoming
     * body or body becoming floor — and a red limb sliding onto white concrete satisfies "green
     * and blue fell, red held" perfectly. That is the §2.2 trap in a new costume: the test would
     * have been true of the movement rather than of the flash, and it would have passed on a rig
     * that flopped in its ordinary colours.
     *
     * <p>A ratio over the whole subject is immune to it: moving does not change what colour the
     * creature is, and both vanilla's overlay and our multiply raise this number by construction
     * (the overlay adds red and scales g and b by 0.698; the multiply scales g and b by the same
     * 0.698 and leaves red).
     *
     * <p>Subject pixels are found by differencing against the EMPTY stage, so the box may be
     * generous without letting the floor in.
     */
    private static double redness(int[][] shot, int[] box) {
        if (shot == null || box == null || shotEmpty == null) {
            return -1;
        }
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = Math.max(0, box[1] - 20); y <= Math.min(shot.length - 1, box[3] + 40); y++) {
            for (int x = Math.max(0, box[0] - 40);
                    x <= Math.min(shot[0].length - 1, box[2] + 40); x++) {
                if (delta(shotEmpty[y][x], shot[y][x]) <= BODY_THRESHOLD) {
                    continue;
                }
                int c = shot[y][x];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        long sum = r + g + b;
        return n < 40 || sum == 0 ? -1 : (double) r / sum;
    }

    private static String meanIn(int[][] shot, int[] box) {
        if (shot == null || box == null) {
            return "?";
        }
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(shot.length - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(shot[0].length - 1, box[2]); x++) {
                int c = shot[y][x];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        return n == 0 ? "?" : "(" + r / n + "," + g / n + "," + b / n + ")";
    }

    private static int[] box(int[][] before, int[][] after, int xFrom, int xTo) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        int yMax = (int) (before.length * 0.90);
        for (int y = 0; y < yMax; y++) {
            for (int x = xFrom; x < Math.min(xTo, before[0].length); x++) {
                if (delta(before[y][x], after[y][x]) > BODY_THRESHOLD) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[] {x0, y0, x1, y1};
    }

    private static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static String fmt(int[] b) {
        return b == null ? "none" : "(" + b[0] + "," + b[1] + ")-(" + b[2] + "," + b[3] + ")";
    }

    private static String r2(double d) {
        return d < 0 ? "?" : String.format("%.2f", d);
    }

    private static String r3(double d) {
        return d < 0 ? "?" : String.format("%.3f", d);
    }

    private static String pct(double from, double to) {
        return from <= 0 || to < 0 ? "?" : String.format("%+.1f%%", 100.0 * (to / from - 1.0));
    }

    private static Mob armA(net.minecraft.server.MinecraftServer server) {
        return byId(server, mobA);
    }

    private static Mob byId(net.minecraft.server.MinecraftServer server, UUID id) {
        if (id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            var e = level.getEntity(id);
            if (e instanceof Mob m) {
                return m;
            }
        }
        return null;
    }

    private static int[][] grab(Minecraft mc, String name) {
        if (mc.getOverlay() != null) {
            failLater = "a frame was captured with " + mc.getOverlay().getClass().getSimpleName()
                    + " still on screen";
        }
        try (var img = Screenshots.take(mc.getMainRenderTarget())) {
            int w = img.getWidth();
            int h = img.getHeight();
            int[][] px = new int[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // getPixelRGBA is ABGR -- see VanillaStateClientTest.grab for the whole story.
                    int abgr = com.moddingfromamod.compat.Pixels.abgr(img, x, y);
                    px[y][x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
                }
            }
            if (name != null) {
                try {
                    java.nio.file.Files.createDirectories(SIG);
                    img.writeToFile(SIG.resolve(name + ".png"));
                } catch (Exception ignored) {
                    // the pixels carry the verdict
                }
            }
            return px;
        }
    }

    private static void writeFilmWindow(String label) {
        if (filmStart == 0L) {
            return;
        }
        try {
            Path windows = SIG.resolve("film-windows.tsv");
            java.nio.file.Files.createDirectories(windows.getParent());
            java.nio.file.Files.writeString(windows,
                    filmStart + "\t" + System.currentTimeMillis() + "\t" + label + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            NOTES.add("film window '" + label + "' " + filmStart + ".."
                    + System.currentTimeMillis());
        } catch (Exception ignored) {
            // no recorder attached
        }
        filmStart = 0L;
    }

    private static void go(Phase next) {
        phase = next;
        ticks = 0;
        fired = false;
    }

    private static void finish(boolean pass, String message) {
        ClientHarness.verdict(SIG, pass,
                (pass ? "PASS" : "FAIL — " + message) + "\n" + String.join("\n", NOTES));
        phase = Phase.DONE;
        Minecraft.getInstance().stop();
    }
}
