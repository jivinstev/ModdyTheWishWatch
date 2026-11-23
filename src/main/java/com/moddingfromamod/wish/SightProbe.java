package com.moddingfromamod.wish;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.runtime.CreatureSculpt;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Two questions with one answer each, asked of two real clients at the same instant.</b>
 *
 * <p>{@link CameraPresence} is gated on the wire, which proves the packet is sent and cannot prove
 * what a person sees. This drives the shipped capture rig end to end so both halves can be
 * <em>looked at</em>:
 *
 * <ol>
 *   <li><b>The camera can see the child.</b> A real creature is sculpted beside him and the camera
 *       photographs it through {@code PhotographTool.capture} — the same call the agent's
 *       {@code photograph} tool makes, so {@code Viewpoint.acquire}, the lease, {@code aimAt},
 *       {@code ClearView} and {@code PhotoClient}'s shutter are all the shipped ones. He is
 *       standing next to the subject, so he is in the frame or the claim is false.</li>
 *   <li><b>The child cannot see the camera.</b> He is then turned to face wherever the camera ended
 *       up, and his own screen is photographed from outside (he is a stock client and has no
 *       shutter of ours).</li>
 * </ol>
 *
 * <h2>🔴 The control, because "he saw nothing" is true of the bug too</h2>
 *
 * <p>A frame of empty grass passes "the camera is not in it" and also passes "the harness pointed
 * him at the sky". So a <b>gold pillar</b> is placed two blocks from the camera's final seat,
 * squarely in the same shot. The result is only meaningful if his frame contains the gold and does
 * not contain the player — one thing he must see and one he must not, in one picture.
 *
 * <p>Completely inert unless {@code -Dmoddingfromamod.sightprobe=<dir>} names somewhere to write,
 * and registered on the bus only then (see {@code ModdingFromAModMod}). It teleports both players
 * and spawns a creature, which is not something to leave one unset property away from a real game.
 */
public final class SightProbe {

    private SightProbe() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-sight-probe");
    private static final String PROPERTY = ModdingFromAModMod.MODID + ".sightprobe";

    /** The species the creature is tagged with, so {@code findSubject} can be pointed at it. */
    private static final String SPECIES = "sightcheck";

    /** How far apart the two clients stand. Far enough to be a real look, close enough to render. */
    private static final double SEPARATION = 14.0;

    public static boolean active() {
        return !System.getProperty(PROPERTY, "").isBlank();
    }

    private static Path sigDir() {
        return Path.of(System.getProperty(PROPERTY));
    }

    private static int settled;
    private static volatile boolean started;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (started) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        ServerPlayer child = child(server);
        ServerPlayer camera = camera(server);
        if (child == null || camera == null) {
            settled = 0;
            return;
        }
        // Both are in, but a client that has just joined is still streaming chunks and has drawn
        // nothing. A photograph taken then is of a grey void and says nothing about visibility.
        if (++settled < 20 * 15) {
            return;
        }
        started = true;
        Thread t = new Thread(() -> run(server), "mfam-sight-probe");
        t.setDaemon(true);
        // A plain daemon thread with its own try/catch, not an executor: a scheduled task that dies
        // takes its reason into a Future nobody reads, and this one's whole job is to report.
        t.start();
    }

    private static void run(MinecraftServer server) {
        try {
            sequence(server);
        } catch (Throwable e) {
            LOG.error("sight probe failed", e);
            write("sight.txt", "FAILED " + e);
        }
    }

    private static void sequence(MinecraftServer server) throws Exception {
        // ── stage the two of them, facing each other across SEPARATION blocks ─────────────────
        double[] spot = new double[6];
        onServer(server, () -> {
            ServerPlayer child = child(server);
            ServerPlayer camera = camera(server);
            if (child == null || camera == null) {
                return;
            }
            ServerLevel level = child.level();
            double cx = child.getX() + SEPARATION;
            double cz = child.getZ();
            double cy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types
                    .MOTION_BLOCKING_NO_LEAVES, (int) cx, (int) cz);
            camera.setNoGravity(true);
            camera.setDeltaMovement(0, 0, 0);
            camera.connection.teleport(cx, cy, cz, -90f, 0f);
            spot[0] = cx;
            spot[1] = cy;
            spot[2] = cz;
            // A real creature beside the child, so the camera has a mob to snap through the rig.
            Vec3 at = new Vec3(child.getX() + 2.5, child.getY(), child.getZ());
            com.moddingfromamod.vanilla.DisplayRig.spawn(level, at, 0f, sculpt(), 1.0, 1.4, 2.4, SPECIES);
            spot[3] = at.x;
            spot[4] = at.y;
            spot[5] = at.z;
            LOG.info("SIGHT: staged -- child at {} {} {}, camera at {} {} {}, creature at {} {} {}",
                    r(child.getX()), r(child.getY()), r(child.getZ()),
                    r(cx), r(cy), r(cz), r(at.x), r(at.y), r(at.z));
        });
        Thread.sleep(3000);

        // ── ARM 2: the camera photographs the creature the child is standing next to ──────────
        var result = com.moddingfromamod.agent.tools.PhotographTool.capture(
                "sight_camera_view", SPECIES, "");
        LOG.info("SIGHT: camera capture -> {}", result == null ? "(null)" : result.text());
        Thread.sleep(2000);

        // ── ARM 1: turn the child to face wherever the camera ended up, and say when ──────────
        StringBuilder aimed = new StringBuilder();
        onServer(server, () -> {
            ServerPlayer child = child(server);
            ServerPlayer camera = camera(server);
            if (child == null || camera == null) {
                return;
            }
            double dx = camera.getX() - child.getX();
            double dz = camera.getZ() - child.getZ();
            double dy = camera.getEyeY() - child.getEyeY();
            double flat = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
            // connection.teleport, not setYRot: rotation reaches a client only this way, which is
            // the same thing `aimAt` does and the same trap docs/CAMERA_CLIENT.md records.
            child.connection.teleport(child.getX(), child.getY(), child.getZ(), yaw, pitch);
            double d = Math.sqrt(dx * dx + dz * dz + dy * dy);
            aimed.append("child ").append(r(child.getX())).append(' ').append(r(child.getY()))
                    .append(' ').append(r(child.getZ()))
                    .append(" looking at camera ").append(r(camera.getX())).append(' ')
                    .append(r(camera.getY())).append(' ').append(r(camera.getZ()))
                    .append(" -- ").append(r(d)).append(" blocks, yaw ").append(r(yaw))
                    .append(" pitch ").append(r(pitch))
                    .append("\nsame pose for both frames; the only difference is the rule");
            LOG.info("SIGHT: AIMED {}", aimed.toString().replace('\n', ' '));
        });
        // ── THE A/B, in one pose ─────────────────────────────────────────────────────────────
        // "He could not see the camera" and "the harness pointed him at the sky" produce the same
        // photograph. A gold block somewhere in shot does not settle it either -- it only proves
        // the client is rendering SOMETHING. The one control that cannot be argued with is the
        // same pose, seconds apart, with the rule switched off and then on.
        //
        // OFF first, and the order is load-bearing: with suppression on from the start the child
        // was never sent the camera at all, and turning it off would need a hand-rolled re-pair.
        // Going visible -> hidden is what the sweep already does and needs nothing.
        CameraPresence.setSuppressing(false);
        Thread.sleep(8000);
        write("control.txt", "CONTROL (hiding OFF -- the camera must be AT the crosshair)\n" + aimed);
        report(server, "control");
        LOG.info("SIGHT: control frame -- hiding OFF, the camera should be visible");
        Thread.sleep(8000);

        CameraPresence.setSuppressing(true);
        Thread.sleep(8000);
        write("aimed.txt", "TEST (hiding ON -- the camera must be GONE)\n" + aimed);
        report(server, "test");
        LOG.info("SIGHT: test frame -- hiding ON, the camera should be gone");
        Thread.sleep(12000);
        write("sight.txt", "DONE\n" + aimed);
        LOG.info("SIGHT: done");
    }

    /**
     * Say what the world contained at the instant a frame was taken, so a blob in a photograph is
     * a name rather than a guess. The first A/B run showed a figure at the crosshair in BOTH
     * frames and there was no way to tell from the picture whether the rule had failed or whether
     * something else was standing there.
     */
    private static void report(MinecraftServer server, String which) throws Exception {
        onServer(server, () -> {
            ServerPlayer child = child(server);
            if (child == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (var e : child.level().getEntities().getAll()) {
                if (e == child || e.distanceTo(child) > 40) {
                    continue;
                }
                sb.append("\n    ").append(e.getType().toShortString())
                        .append(" id=").append(e.getId())
                        .append(" '").append(e.getName().getString()).append("'")
                        .append(e instanceof ServerPlayer p
                                ? (PlayerKind.isChild(p.getUUID()) ? " CHILD" : " SERVICE") : "")
                        .append(e.isInvisible() ? " invisible" : "")
                        .append(String.format(java.util.Locale.ROOT, " %.1fm", e.distanceTo(child)));
            }
            LOG.info("SIGHT[{}]: suppressing={} -- within 40m of the child:{}",
                    which, CameraPresence.suppressing(), sb);
        });
    }

    private static void onServer(MinecraftServer server, Runnable body) throws Exception {
        java.util.concurrent.CompletableFuture<Void> f = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                body.run();
                f.complete(null);
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        f.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static List<CreatureSculpt.Part> sculpt() {
        return List.of(
                new CreatureSculpt.Part("body", null, new float[] { 0, 0, 0 },
                        new float[] { -5, 0, -4 }, new float[] { 5, 12, 4 }, 0x2f8f3f),
                new CreatureSculpt.Part("head", null, new float[] { 0, 12, 0 },
                        new float[] { -4, 12, -4 }, new float[] { 4, 19, 4 }, 0xe0c020),
                new CreatureSculpt.Part("tail", null, new float[] { 0, 3, 4 },
                        new float[] { -2, 3, 4 }, new float[] { 2, 7, 10 }, 0xc03020));
    }

    private static ServerPlayer child(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (PlayerKind.isChild(p.getUUID())) {
                return p;
            }
        }
        return null;
    }

    private static ServerPlayer camera(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!PlayerKind.isChild(p.getUUID())) {
                return p;
            }
        }
        return null;
    }

    private static String r(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static void write(String name, String body) {
        try {
            Path dir = sigDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), body + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("could not write {}", name, e);
        }
    }
}
