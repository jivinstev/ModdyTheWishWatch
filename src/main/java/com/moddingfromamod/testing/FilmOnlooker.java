package com.moddingfromamod.testing;

import com.moddingfromamod.film.FilmCamera;
import com.moddingfromamod.ModdingFromAModMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The film's ONLOOKER</b> ({@code -Dmoddingfromamod.bxonlooker=<name>}): a real client who
 * is not in the exercise at all — flying in creative, so no monster goes for him — kept beside the
 * creature at a distance set by its SIZE, looking at it, so a recording of his screen is a recording
 * of the creature from where a person standing near would see it. The rider, the bystander and the
 * one who punches it are then the stand-in child, as on a measuring run.
 *
 * <p>Three things the first takes got wrong, each seen only on the film: a SPECTATOR sees invisible
 * entities as ghosts, and the rig's carrier is an invisible mob, so the T-rex was a translucent cow
 * with a sculpt round it; moving him every tick made the picture judder, so he moves only when the
 * creature has drifted from his spot and turns every tick; and following its height took the leap
 * out of the picture, so he keeps the height of its feet on the ground and a leap rises in frame.
 * And the fix for the second turned out not to be enough: snapping his view to the creature every
 * tick still shook the picture, so the camera is now {@link FilmCamera}, which glides.
 *
 * <p>Why: the child's own eyes were the only camera. On his back the T-rex's six-block leap was
 * the horizon shifting behind its tail, and following on foot a creature was a speck for most of a
 * film (both). A site film wants the creature filling the frame and its move seen from the
 * side.
 */
public final class FilmOnlooker {

    private FilmOnlooker() {
    }

    /** The onlooker's name; empty when none. */
    static String name() {
        return System.getProperty(ModdingFromAModMod.MODID + ".bxonlooker", "");
    }

    static boolean on() {
        return !name().isEmpty();
    }

    /**
     * Which side he watches from ({@code -Dmoddingfromamod.bxonlookerside=east|west|north|south},
     * east by default) and how much farther than the size says ({@code bxonlookerfar}, 1 by default).
     * The pet dragon breathed its fire at the monsters and the camera stood in the cone: the whole
     * frame was flames for half the film.
     */
    static Vec3 side() {
        return switch (System.getProperty(ModdingFromAModMod.MODID + ".bxonlookerside", "east")) {
            case "west" -> new Vec3(-1, 0, 0);
            case "north" -> new Vec3(0, 0, -1);
            case "south" -> new Vec3(0, 0, 1);
            default -> new Vec3(1, 0, 0);
        };
    }

    static double far() {
        try {
            return Math.max(0.5, Math.min(4.0, Double.parseDouble(
                    System.getProperty(ModdingFromAModMod.MODID + ".bxonlookerfar", "1"))));
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    /** How far off he stands, in blocks, for a creature this size: it fills about half the frame. */
    public static double distance(double width, double height) {
        return Math.max(4.0, 1.3 * Math.max(width, height) + 2.0);
    }

    /** How far above its feet his eyes are: a little under its middle, never under a child's eyes. */
    public static double eyeHeight(double height) {
        return Math.max(1.6, height * 0.45);
    }

    /**
     * Where he stands for a creature whose feet are at {@code at} on the ground: beside it, on the
     * side the film looks from ({@code side}, a unit vector — east, across the ±z line the walk and
     * the ride run). The Y is his FEET.
     */
    public static Vec3 spot(Vec3 at, double width, double height, Vec3 side) {
        double d = distance(width, height);
        return new Vec3(at.x + side.x * d, at.y + eyeHeight(height) - 1.62, at.z + side.z * d);
    }

    /** True when he has drifted far enough from his spot to be moved: a quarter of his distance. */
    public static boolean drifted(Vec3 now, Vec3 spot, double distance) {
        double dx = now.x - spot.x, dz = now.z - spot.z;
        return Math.sqrt(dx * dx + dz * dz) > distance * 0.25 || Math.abs(now.y - spot.y) > 1.5;
    }

    private static double groundY = Double.NaN;

    /** How much of the gap to its spot the camera closes each tick when it follows a creature. */
    static final double FOLLOW = 0.06;

    /** How much of a creature's rise above the ground its camera's gaze follows. */
    static final double GAZE_LIFT = 0.33;

    /** Keep him there. No-op without an onlooker online. */
    static void tick(MinecraftServer server, Entity creature) {
        if (!on() || server == null || creature == null || !creature.isAlive()) {
            return;
        }
        ServerPlayer p = server.getPlayerList().getPlayerByName(name());
        if (p == null) {
            return;
        }
        if (creature.onGround() || Double.isNaN(groundY)) {
            groundY = creature.getY();
        }
        Vec3 feet = new Vec3(creature.getX(), groundY, creature.getZ());
        Vec3 to = spot(feet, creature.getBbWidth(), creature.getBbHeight(), side().scale(far()));
        // Through FilmCamera, eased: the old teleport-when-drifted and look-at-every-tick made every
        // creature film jostle (the dragon worst, its middle bobbing as it flew). It now glides after
        // the creature and its gaze is filtered the same way. FOLLOW is slow enough to take out a
        // bob and quick enough to keep a walking creature in frame.
        // The gaze follows only a third of a rise: following it all tilted the camera with every leap
        // (the T-rex's take measured 3-7 px of shake a frame, tools/camera-shake.py), and none of it
        // let a flier climb out of the picture.
        double lift = (creature.getY() - groundY) * GAZE_LIFT;
        if (creature.tickCount % 100 == 0) {
            org.slf4j.LoggerFactory.getLogger("film-onlooker").info(
                    "[onlooker] creature {} at ({}, {}, {}); camera goes to ({}, {}, {}), is at {}, attached {}, his body at {}",
                    creature.getType().toShortString(), String.format("%.1f", creature.getX()),
                    String.format("%.1f", creature.getY()), String.format("%.1f", creature.getZ()),
                    String.format("%.1f", to.x), String.format("%.1f", to.y), String.format("%.1f", to.z),
                    FilmCamera.where(), p.getCamera() != p, p.position());
            String ownTag = com.moddingfromamod.vanilla.DisplayRig.RIG_OWNER_PREFIX + creature.getUUID();
            var parts = creature.level().getEntities((Entity) null, creature.getBoundingBox().inflate(64),
                    e -> e instanceof net.minecraft.world.entity.Display && e.entityTags().contains(ownTag));
            double sx = 0, sz = 0;
            for (Entity e : parts) {
                sx += e.getX();
                sz += e.getZ();
            }
            org.slf4j.LoggerFactory.getLogger("film-onlooker").info("[onlooker] its {} display part(s) centred at ({}, {}), driven {}",
                    parts.size(), parts.isEmpty() ? "-" : String.format("%.1f", sx / parts.size()),
                    parts.isEmpty() ? "-" : String.format("%.1f", sz / parts.size()),
                    com.moddingfromamod.vanilla.VanillaRigAnimator.isDriving((net.minecraft.world.entity.Mob) creature));
        }
        FilmCamera.aim(p, to.add(0, 1.62, 0),
                new Vec3(creature.getX(), groundY + lift + creature.getBbHeight() * 0.5, creature.getZ()), FOLLOW);
    }
}
