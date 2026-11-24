package com.moddingfromamod.film;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The site films' camera</b>: the onlooker's client looks THROUGH an invisible
 * {@code item_display} that the server moves, instead of being a player the server teleports.
 *
 * <p>Why: the first films were jostly. The onlooker was re-aimed with a look-at packet every tick
 * and teleported whenever his subject drifted, and each of those reaches a client as a snap — a
 * shake on every tick and a jump on every move (the dragon, the boots, the slushie, the lucky
 * block). A display entity is sent to clients every tick ({@code updateInterval 1}) and a client
 * GLIDES it to each new position over {@code teleport_duration} ticks, and the camera here moves
 * by easing ({@link CameraPath.Ease}), so the picture is a dolly, never a shake.
 *
 * <p>Three things it relies on, all read from the 1.21.4 bytecode rather than assumed:
 * {@code ServerPlayer.setCamera} works in any game mode (the client's {@code handleSetCamera} has no
 * spectator check) and the server then keeps the player AT the camera, so chunks and entities load
 * round it; the onlooker stays CREATIVE, because a spectator draws every invisible mob as a ghost and
 * every wished creature rides an invisible carrier; and he is made invisible, because with the camera
 * elsewhere his client draws his own body where he stands. Anything that teleports him, or changes
 * his game mode, drops the camera back to his eyes ({@code teleportTo}/{@code setGameMode} call
 * {@code setCamera(this)}), so {@link #aim} re-asserts it every tick.
 */
public final class FilmCamera {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/film-camera");

    /**
     * How many ticks a client takes to glide to each new position ({@code teleport_duration}). <b>0 on
     * 1.21.8</b>: with 4, the camera a stock client looks through lagged and turned wrong —
     * a 1.21.8 display entity's position/rotation interpolation does not carry a spectator's view —
     * so the pet dragon's film was a still of a spider while the server's camera followed the dragon,
     * and the fire imp was off screen for half its take. At 0 both stay in frame, and the jitter is
     * no worse (tools/camera-shake.py 0.97 px against 1.19 px): the aim is already eased server-side
     * ({@link CameraPath.Ease}). {@code -Dmoddingfromamod.filmglide=N} brings a glide back to measure.
     */
    static final int GLIDE_TICKS = 0;

    private static Display.ItemDisplay rig;
    private static CameraPath.Ease eye;
    private static CameraPath.Ease look;

    private FilmCamera() {
    }

    /**
     * Put the camera's EYE toward {@code at} and its gaze toward {@code target}, each eased at
     * {@code rate} (1 = exactly there; 0.05 = a two-second glide). The first call snaps.
     */
    public static void aim(ServerPlayer onlooker, Vec3 at, Vec3 target, double rate) {
        if (!rig(onlooker, at)) {
            return;
        }
        if (eye == null || look == null) {
            eye = new CameraPath.Ease(rate);
            look = new CameraPath.Ease(rate);
        }
        double[] p = eye.step(at.x, at.y, at.z);
        double[] l = look.step(target.x, target.y, target.z);
        show(onlooker, p, l);
    }

    /**
     * Put the camera's eye EXACTLY at {@code at}, looking at {@code target}: no easing, because a
     * keyframed path ({@link FilmPathPlayer}) has already eased it and must be the same on every
     * play. A later {@link #aim} starts afresh: its first call snaps, as on a new camera.
     */
    public static void place(ServerPlayer onlooker, double[] at, double[] target) {
        if (!rig(onlooker, new Vec3(at[0], at[1], at[2]))) {
            return;
        }
        eye = null;
        look = null;
        show(onlooker, at, target);
    }

    /** The camera's yaw and pitch now, or null before the first {@link #aim}/{@link #place}. */
    public static float[] facing() {
        return rig == null ? null : new float[] {rig.getYRot(), rig.getXRot()};
    }

    /** Make sure the rig exists in the onlooker's level and he is ready to look through it. */
    private static boolean rig(ServerPlayer onlooker, Vec3 at) {
        ServerLevel level = onlooker.level();
        if (rig == null || rig.isRemoved() || rig.level() != level) {
            if (rig != null && !rig.isRemoved()) {
                rig.discard();
            }
            rig = EntityTypes.ITEM_DISPLAY.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (rig == null) {
                return false;
            }
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putInt("teleport_duration", Integer.getInteger("moddingfromamod.filmglide", GLIDE_TICKS));
            com.moddingfromamod.compat.EntityNbt.load(rig, com.moddingfromamod.compat.EntityNbt.save(rig).merge(tag));
            rig.snapTo(at.x, at.y, at.z, 0f, 0f);
            rig.addTag("mfam_film_camera");
            level.addFreshEntity(rig);
            eye = null;
            look = null;
            LOGGER.info("[camera] a film camera at {}", at);
        }
        if (onlooker.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
            onlooker.setGameMode(GameType.CREATIVE);
        }
        if (!onlooker.hasEffect(MobEffects.INVISIBILITY)) {
            onlooker.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
        }
        return true;
    }

    /** Move the rig to eye {@code p} facing {@code l}, and keep the onlooker looking through it. */
    private static void show(ServerPlayer onlooker, double[] p, double[] l) {
        float[] yp = CameraPath.yawPitch(l[0] - p[0], l[1] - p[1], l[2] - p[2]);
        rig.snapTo(p[0], p[1], p[2], yp[0], yp[1]);
        if (onlooker.getCamera() != rig) {
            onlooker.setCamera(rig);
        }
        // ⚠ AND SAY IT AGAIN while the camera is new. setCamera sends its packet at once, but
        // the camera's own spawn packet goes out with the next tracking pass — so the client is told to
        // look through an entity it does not have yet, finds nothing, and ignores it, silently. It then
        // films from wherever the player was put, facing his own way: on 1.21.8 the pet dragon's whole
        // film was one still of a spider while the server said the camera was gliding after the dragon.
        if (rig.tickCount < CAMERA_RESEND_TICKS) {
            onlooker.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCameraPacket(rig));
        }
    }

    /** For how many of the camera's first ticks its camera packet is re-sent (see {@link #aim}). */
    static final int CAMERA_RESEND_TICKS = 20;

    /** The camera entity's network id (what a client knows it by), or -1 when there is none. */
    public static int rigId() {
        return rig == null || rig.isRemoved() ? -1 : rig.getId();
    }

    /** Where the camera's eye is now, or null before the first {@link #aim}. */
    public static Vec3 where() {
        return rig == null ? null : rig.position();
    }

    /** Take the camera away and give the onlooker his own eyes back. */
    public static void release(ServerPlayer onlooker) {
        if (onlooker != null && onlooker.getCamera() != onlooker) {
            onlooker.setCamera(onlooker);
        }
        if (rig != null) {
            rig.discard();
        }
        rig = null;
        eye = null;
        look = null;
    }

    /** Never film THROUGH the camera's own entity: a show clearing the area must spare it. */
    public static boolean isCamera(Entity e) {
        return e == rig;
    }
}
