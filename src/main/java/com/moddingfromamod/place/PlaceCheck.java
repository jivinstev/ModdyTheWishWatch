package com.moddingfromamod.place;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.builder.BuildSpots;
import com.moddingfromamod.mob.Creatures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * <b>Did the thing really land in / on / next to / over the thing he named?</b> — measured
 * in the world after a real grant, for Gate D's {@code placeCheck} and the contact sheet's captions.
 *
 * <p>The tool's own reply says where it MEANT to put the thing; this looks at what is THERE. For a
 * building target every find is classified against the building's box — {@code IN} (inside its
 * walls), {@code ON} (over its footprint, at or above its top), or {@code BY} (outside it, n blocks
 * from its nearest wall) — because that relation is the whole claim, and a creature that slid off
 * the roof reads {@code BY} however near it stands.
 */
public final class PlaceCheck {

    private PlaceCheck() {
    }

    /**
     * One {@code at}, checked.
     *
     * @param found        how many things are at it now
     * @param line         the finding, said in full
     * @param photoSubject what to point the camera at (a building or creature id), or null
     */
    public record Seen(String tool, String at, boolean resolved, int found, String line, String photoSubject,
                       double[] focus, int[] box, String spot, String facing) {

        Seen(String tool, String at, boolean resolved, int found, String line, String photoSubject) {
            this(tool, at, resolved, found, line, photoSubject, null, null, null, null);
        }
    }

    /** The spot a tool takes when its {@code at} names only the thing. */
    public static String defaultWhere(String tool) {
        return switch (tool == null ? "" : tool) {
            case "stage_event" -> "top";
            case "build_structure" -> "beside";
            default -> BuildSpots.FRONT;
        };
    }

    /**
     * Look at what {@code tool} put at {@code at}. {@code madeId} is the id the call named (a new
     * building's, a picture's), or null. Server thread.
     */
    public static Seen look(MinecraftServer server, ServerPlayer child, String tool, String at, String madeId) {
        PlaceFinder.Place p = PlaceFinder.find(server, child, at, defaultWhere(tool));
        if (!p.ok()) {
            return new Seen(tool, at, false, 0, "✗ " + tool + " at " + at + ": does not resolve now — " + p.why(), null);
        }
        var r = p.r();
        ServerLevel level = p.level();
        int[] box = buildingBox(p.buildId());
        String subject = p.buildId() != null ? p.buildId() : "creature".equals(p.kind()) ? At.parse(at).thing() : null;
        double feet = p.feetAt(r.x(), r.z());
        List<String> seen = new ArrayList<>();
        double[] focus = null;
        String facing = null;
        int[] shotBox = box;
        switch (tool) {
            case "build_structure" -> {
                int[] nb = madeId == null ? null : buildingBox(madeId);
                if (nb != null) {
                    seen.add("building '" + madeId + "' " + relation(box, nb));
                    focus = new double[]{(nb[0] + nb[3] + 1) / 2.0, nb[1], (nb[2] + nb[5] + 1) / 2.0};
                    shotBox = box == null ? nb : new int[]{Math.min(box[0], nb[0]), Math.min(box[1], nb[1]),
                            Math.min(box[2], nb[2]), Math.max(box[3], nb[3]), Math.max(box[4], nb[4]),
                            Math.max(box[5], nb[5])};
                }
            }
            case "paint_surface" -> {
                double reach = 3;
                for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, around(box, r, feet, reach),
                        f -> f.getItem().is(Items.FILLED_MAP))) {
                    // Flush on a wall is inside its box, so "IN it" would misread: say which wall.
                    seen.add("a framed map on its " + f.getDirection().getName() + " wall "
                            + where(null, f.getX(), f.getY(), f.getZ(), r, feet));
                    if (focus == null) {
                        focus = new double[]{f.getX(), f.getY(), f.getZ()};
                        facing = f.getDirection().getName();
                    }
                }
            }
            case "stage_event" -> {
                for (var e : com.moddingfromamod.event.EventDirector.all(server)) {
                    BlockPos fo = e.focus();
                    var reach = e.planned();
                    // A wave is born beyond the place and rolls over it, so its focus is far from the
                    // spot; its planned reach still covers it (measured, at-e4's replay).
                    boolean covers = reach != null && r.x() >= reach.x0() && r.x() <= reach.x1()
                            && r.z() >= reach.z0() && r.z() <= reach.z1();
                    if (fo != null && (covers || Math.hypot(fo.getX() + 0.5 - r.x(), fo.getZ() + 0.5 - r.z()) <= r.ring() + 12)) {
                        seen.add("event " + e.id() + " centred " + where(box, fo.getX() + 0.5, fo.getY() + 1.0,
                                fo.getZ() + 0.5, r, feet));
                        if (focus == null) {
                            boolean near = Math.hypot(fo.getX() + 0.5 - r.x(), fo.getZ() + 0.5 - r.z()) <= r.ring() + 12;
                            // A wave's focus is where it ENDS, 100+ blocks on: frame the place it rolls
                            // over, not that (measured, at-e4: the fort was a dot on the horizon).
                            focus = near ? new double[]{fo.getX() + 0.5, fo.getY() + 1.0, fo.getZ() + 0.5}
                                    : new double[]{r.x(), r.y(), r.z()};
                        }
                    }
                }
            }
            default -> {
                double reach = r.ring() > 0 ? 4 : 3.5;
                for (Entity e : level.getEntities((Entity) null, around(box, r, feet, reach),
                        e -> e.isAlive() && !(e instanceof Player) && !Creatures.isRigInternal(e))) {
                    seen.add(name(e) + " " + where(box, e.getX(), e.getY(), e.getZ(), r, feet));
                    if (focus == null) {
                        focus = new double[]{e.getX(), e.getY(), e.getZ()};
                    }
                }
                BlockPos c = BlockPos.containing(r.x(), feet, r.z());
                for (BlockPos q : BlockPos.betweenClosed(c.offset(-2, -1, -2), c.offset(2, 1, 2))) {
                    if (level.getBlockEntity(q) instanceof Container box2 && !box2.isEmpty()) {
                        List<String> held = new ArrayList<>();
                        for (int i = 0; i < box2.getContainerSize(); i++) {
                            if (!box2.getItem(i).isEmpty()) {
                                held.add(box2.getItem(i).getCount() + "× " + box2.getItem(i).getHoverName().getString());
                            }
                        }
                        seen.add("a " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath()
                                + " holding " + held + " " + where(box, q.getX() + 0.5, q.getY(), q.getZ() + 0.5, r, feet));
                        if (focus == null) {
                            focus = new double[]{q.getX() + 0.5, q.getY(), q.getZ() + 0.5};
                        }
                    }
                }
            }
        }
        String head = (seen.isEmpty() ? "✗ " : "✓ ") + tool + " at " + r.link() + " (feet " + (int) Math.floor(r.x())
                + "," + (int) Math.floor(feet) + "," + (int) Math.floor(r.z()) + (r.ring() > 0 ? ", ring " + r.ring() : "")
                + "): ";
        if (focus == null) {
            focus = new double[]{r.x(), feet, r.z()};
        }
        return new Seen(tool, at, true, seen.size(), head + (seen.isEmpty() ? "NOTHING is there now" : String.join("; ",
                seen.size() > 8 ? seen.subList(0, 8) : seen) + (seen.size() > 8 ? "; …" : "")), subject,
                focus, shotBox, r.spot(), facing);
    }

    /**
     * <b>Where the placement photograph is taken from</b> for one finding: from INSIDE the
     * room when the thing is inside the building (docs/CAMERA.md §5: never calculated — searched,
     * the direction from the thing with the longest clear view at eye height, the camera a little
     * short of the wall), facing a picture's wall for a picture, and otherwise {@link
     * PlaceShot#framing} the building and the thing together from his side.
     */
    public static PlaceShot.Pose shotPose(ServerLevel level, Seen s, double childX, double childZ) {
        double[] f = s.focus();
        int[] b = s.box();
        if (f == null) {
            return null;
        }
        boolean indoors = b != null && f[0] >= b[0] && f[0] < b[3] + 1 && f[2] >= b[2] && f[2] < b[5] + 1
                && f[1] < b[4] && !"paint_surface".equals(s.tool()) && !"stage_event".equals(s.tool())
                && !"build_structure".equals(s.tool());
        if (indoors) {
            double eye = f[1] + 1.7;
            double best = 0, bx = 0, bz = 0;
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16, dx = -Math.sin(a), dz = Math.cos(a);
                double d = 0;
                while (d < 7) {
                    BlockPos q = BlockPos.containing(f[0] + dx * (d + 0.5), eye, f[2] + dz * (d + 0.5));
                    BlockPos feet = q.below();
                    // Never out through an open side: a camera outside a stall shot its wall (at-b1).
                    if (q.getX() < b[0] || q.getX() > b[3] || q.getZ() < b[2] || q.getZ() > b[5]
                            || !level.getBlockState(q).getCollisionShape(level, q).isEmpty()
                            || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) {
                        break;
                    }
                    d += 0.5;
                }
                if (d > best) {
                    best = d;
                    bx = dx;
                    bz = dz;
                }
            }
            if (best >= 2) {
                double back = best - 0.8;
                return PlaceShot.looking(f[0] + bx * back, eye, f[2] + bz * back, f[0], f[1] + 0.7, f[2]);
            }
        }
        if ("paint_surface".equals(s.tool()) && s.facing() != null) {
            var d = net.minecraft.core.Direction.byName(s.facing());
            if (d != null) {
                // The furthest point out to 9 from which the picture is in plain sight: straight out at
                // 9 a camera stood inside planks in front of the castle (measured, at-p1).
                net.minecraft.world.phys.Vec3 target = new net.minecraft.world.phys.Vec3(f[0], f[1] + 0.5, f[2]);
                for (int out = 9; out >= 2; out--) {
                    double ex = f[0] + d.getStepX() * out, ey = f[1] + 1.5, ez = f[2] + d.getStepZ() * out;
                    var eye = new net.minecraft.world.phys.Vec3(ex, ey, ez);
                    if (!level.getBlockState(BlockPos.containing(eye)).getCollisionShape(level,
                            BlockPos.containing(eye)).isEmpty()) {
                        continue;
                    }
                    var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, target,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                            net.minecraft.world.phys.shapes.CollisionContext.empty()));
                    if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                            || hit.getLocation().distanceTo(target) < 1.2) {
                        return PlaceShot.looking(ex, ey, ez, f[0], f[1] + 0.5, f[2]);
                    }
                }
                return PlaceShot.looking(f[0] + d.getStepX() * 3, f[1] + 1.5, f[2] + d.getStepZ() * 3,
                        f[0], f[1] + 0.5, f[2]);
            }
        }
        double[] from = viewFrom(b, f, childX, childZ, s.spot());
        if ("build_structure".equals(s.tool()) && b != null) {
            // The PAIR, whole and unclipped: clipped round the new one, the old one fell out of the
            // frame and the camera sat over the stable's roof (at-b1's third run).
            from = across(b, f, childX, childZ);
            return PlaceShot.whole(b, from[0], from[1]);
        }
        return PlaceShot.framing(b, f[0], f[1], f[2], from[0], from[1]);
    }

    /**
     * Which side to shoot from: for a thing OUTSIDE the building (beside, behind, around it), from
     * beyond the thing looking back at the building, so the building never stands in front of it —
     * from his side, a shed behind the barn was the barn's roof (measured, at-b4). For a thing on or
     * in it, his side.
     */
    /**
     * For a NEW building beside another: across the line between them, from his side of it, so both
     * stand side by side in the frame. From beyond the new one the stable hid the cottage (at-b1).
     */
    static double[] across(int[] b, double[] f, double childX, double childZ) {
        if (b == null) {
            return new double[] {childX, childZ};
        }
        double cx = (b[0] + b[3] + 1) / 2.0, cz = (b[2] + b[5] + 1) / 2.0;
        double dx = f[0] - cx, dz = f[2] - cz;
        if (Math.hypot(dx, dz) < 0.5) {
            return new double[] {childX, childZ};
        }
        double px = -dz, pz = dx;                            // perpendicular to the line between them
        if (!Double.isNaN(childX) && (childX - cx) * px + (childZ - cz) * pz < 0) {
            px = -px;
            pz = -pz;
        }
        return new double[] {cx + px * 4, cz + pz * 4};
    }

    static double[] viewFrom(int[] b, double[] f, double childX, double childZ, String spot) {
        if (b == null) {
            return new double[] {childX, childZ};
        }
        double cx = (b[0] + b[3] + 1) / 2.0, cz = (b[2] + b[5] + 1) / 2.0;
        // By the spot's NAME too: a new shed behind the barn is framed in a box holding both, so its
        // centre is "inside" that box (measured, at-b4's second run).
        boolean outside = f[0] < b[0] || f[0] >= b[3] + 1 || f[2] < b[2] || f[2] >= b[5] + 1
                || (spot != null && java.util.Set.of("behind", "beside", "north", "east", "south", "west", "around")
                .contains(spot));
        if (!outside || Math.hypot(f[0] - cx, f[2] - cz) < 0.5) {
            return new double[] {childX, childZ};
        }
        return new double[] {f[0] + (f[0] - cx) * 4, f[2] + (f[2] - cz) * 4};
    }

    private static String name(Entity e) {
        String label = Creatures.isCreature(e) ? Creatures.label(e) : null;
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        if (e instanceof ItemEntity it) {
            return "a dropped " + it.getItem().getHoverName().getString();
        }
        if (e.entityTags().stream().anyMatch(t -> t.endsWith("_here") || t.endsWith("_new") || t.endsWith("_built"))) {
            return "the placed marker of " + e.entityTags().stream().filter(t -> t.endsWith("_here") || t.endsWith("_new")
                    || t.endsWith("_built")).findFirst().orElse("?");
        }
        return label != null ? "creature '" + label + "'" : (e.hasCustomName() ? type + " '" + e.getCustomName().getString()
                + "'" : type);
    }

    /** The world box a find is looked for in: round the spot, and the whole building when there is one. */
    private static AABB around(int[] box, PlaceResolver.Resolved r, double feet, double reach) {
        double rr = r.ring() > 0 ? r.ring() + reach : reach;
        AABB a = new AABB(r.x() - rr, feet - 2, r.z() - rr, r.x() + rr, feet + 4, r.z() + rr);
        return box == null || r.ring() > 0 ? a
                : a.minmax(new AABB(box[0], box[1], box[2], box[3] + 1, box[4] + 2, box[5] + 1).inflate(1));
    }

    /** Where a point is against the building (IN / ON / BY n) and against the spot. */
    static String where(int[] box, double x, double y, double z, PlaceResolver.Resolved r, double feet) {
        String d = String.format(Locale.ROOT, "%.1f from the spot", Math.hypot(x - r.x(), z - r.z()));
        if (box == null) {
            return "(" + d + ")";
        }
        return "(" + relationOfPoint(box, x, y, z) + ", " + d + ")";
    }

    /** IN / ON / BY for one point against a building box {x0,y0,z0,x1,y1,z1} (inclusive blocks). */
    public static String relationOfPoint(int[] b, double x, double y, double z) {
        boolean overFoot = x >= b[0] && x < b[3] + 1 && z >= b[2] && z < b[5] + 1;
        // Feet level with its TOP layer is on it: a creature on a battlemented deck stands between
        // the merlons, which are the top layer (measured, the dragon on the tower).
        if (overFoot && y >= b[4] - 0.01) {
            return "ON it";
        }
        if (overFoot && y >= b[1] && y <= b[4] + 1) {
            return "IN it";
        }
        double dx = Math.max(Math.max(b[0] - x, x - (b[3] + 1)), 0), dz = Math.max(Math.max(b[2] - z, z - (b[5] + 1)), 0);
        return String.format(Locale.ROOT, "BY it, %.1f from its walls", Math.hypot(dx, dz));
    }

    /** How a second building's box sits against the first: beside with a gap, around it, on it, or overlapping. */
    public static String relation(int[] a, int[] b) {
        if (a == null || b == null) {
            return "(no box to compare)";
        }
        int gx = Math.max(b[0] - a[3], a[0] - b[3]) - 1, gz = Math.max(b[2] - a[5], a[2] - b[5]) - 1;
        boolean overlapXZ = gx < 0 && gz < 0;
        if (b[1] >= a[4] && overlapXZ) {
            return "stands ON it (its base at y " + b[1] + ", the top at " + a[4] + ")";
        }
        if (b[0] < a[0] && b[3] > a[3] && b[2] < a[2] && b[5] > a[5]) {
            return "stands AROUND it (" + (a[0] - b[0]) + "–" + (b[3] - a[3]) + " blocks out on x, " + (a[2] - b[2]) + "–"
                    + (b[5] - a[5]) + " on z)";
        }
        if (overlapXZ) {
            return "OVERLAPS it";
        }
        return "stands BESIDE it, " + Math.max(gx, gz) + " block(s) of air between";
    }

    /** A standing building's world box, {x0,y0,z0,x1,y1,z1}, or null ({@link PlaceFinder#buildingBox}). */
    static int[] buildingBox(String id) {
        return PlaceFinder.buildingBox(id);
    }
}
