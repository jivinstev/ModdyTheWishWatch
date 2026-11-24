package com.moddingfromamod.wish.reveal;

import com.mojang.math.Transformation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * <b>The display entities a wish's orb and reveals are drawn with</b>.
 *
 * <p>Every one carries {@link #TAG} and the wish's own tag, so whatever ends a wish (finish, stop,
 * a failed grant, a crash and a restart) can find and remove them, and Gate B can count what is
 * left. A floating orb after a failed wish is exactly what must never happen. ⚠ A display IS saved
 * with its chunk when that chunk unloads under it (the child walked away mid-wish), and nothing tracks
 * the copy that comes back — so a tagged display that joins a level LOADED FROM DISK is refused
 * ({@code WishOrbs.onJoin}). Every live one joins fresh, from {@link #add}.
 */
public final class Fx {

    /** On every orb and reveal display. */
    public static final String TAG = "moddy_wish_fx";

    /** How far a client draws them, as a multiple of the entity distance (1 = 64 blocks). */
    public static final float FAR = 4f;

    /** Ticks a client takes to glide to a new position: 2 keeps a 2-tick update smooth. */
    public static final int GLIDE = 2;

    private Fx() {
    }

    /** The wish's own tag: {@code moddy_wish_fx_<first 8 of its id>}. */
    public static String wishTag(java.util.UUID wishId) {
        return TAG + "_" + (wishId == null ? "none" : wishId.toString().substring(0, 8));
    }

    /** A glowing block display at (x, y, z), drawn from {@code range} × 64 blocks away. */
    public static Entity block(ServerLevel level, BlockState state, double x, double y, double z,
                               float range, String wishTag) {
        Entity d = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null) {
            return null;
        }
        d.snapTo(x, y, z, 0f, 0f);
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
        tag.put("block_state", NbtUtils.writeBlockState(state));
        common(tag, range);
        com.moddingfromamod.compat.EntityNbt.load(d, tag);
        return add(level, d, wishTag);
    }

    /**
     * A flat, camera-facing band of light: a text display whose only content is its background,
     * turned to face the viewer about the vertical axis. A block is shaded on every side (a white
     * concrete pillar filmed grey); a text background is drawn flat, in exactly {@code argb}, at
     * full brightness, and can be see-through, which is what a beam of light needs.
     */
    public static Entity beam(ServerLevel level, double x, double y, double z, float range, String wishTag,
                              int argb) {
        return light(level, x, y, z, range, wishTag, argb, "vertical");
    }

    /**
     * A flat band of light that does NOT turn to face the viewer: posed with {@link #ribbon}, for an
     * arc of lightning or a glow lying on the ground.
     */
    public static Entity band(ServerLevel level, double x, double y, double z, float range, String wishTag,
                              int argb) {
        return light(level, x, y, z, range, wishTag, argb, "fixed");
    }

    private static Entity light(ServerLevel level, double x, double y, double z, float range, String wishTag,
                                int argb, String billboard) {
        Entity d = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null) {
            return null;
        }
        d.snapTo(x, y, z, 0f, 0f);
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
        // ONE space, encoded through the codec. Since 1.21.5 a string tag here is LITERAL text, not
        // JSON: the 1.21.4 spelling "\" \"" became the three characters `" "`, a 14-pixel background
        // instead of {@link #BEAM_UNIT_W}'s 5, so every beam and the star at the pillar's foot came
        // out 2.8x too wide with two white quote marks drawn on them (the bump).
        tag.put("text", textTag(LIGHT_TEXT));
        tag.putString("billboard", billboard);
        tag.putInt("background", argb);
        tag.putInt("line_width", 200);
        tag.putBoolean("shadow", false);
        common(tag, range);
        com.moddingfromamod.compat.EntityNbt.load(d, tag);
        return add(level, d, wishTag);
    }

    /** A light's whole text: one space, so only its background shows. */
    static final String LIGHT_TEXT = " ";

    /** {@code text} as 1.21.8 stores it: the component codec's own NBT, never a JSON string. */
    static net.minecraft.nbt.Tag textTag(String text) {
        return net.minecraft.network.chat.ComponentSerialization.CODEC
                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, net.minecraft.network.chat.Component.literal(text))
                .getOrThrow();
    }

    /** One space's background, in blocks: 5 × 10 pixels at 1/40 of a block, standing on its foot. */
    static final float BEAM_UNIT_W = 0.125f;
    static final float BEAM_UNIT_H = 0.25f;

    /**
     * Size a {@link #beam} to {@code width} × {@code height}, standing on its display's position, pushed
     * {@code back} away from the viewer so layered beams do not flicker against each other.
     */
    public static void beamPose(Entity d, float width, float height, float back) {
        if (d == null) {
            return;
        }
        float sx = width / BEAM_UNIT_W;
        pose(d, new Transformation(new Vector3f(-BEAM_OFF_X * sx, 0f, -back), new Quaternionf(),
                new Vector3f(sx, height / BEAM_UNIT_H, 1f), new Quaternionf()), 0);
    }

    /** A space's background sits half a pixel right of centre; this pulls it back, per unit of scale. */
    static final float BEAM_OFF_X = 0.0125f;

    /**
     * Pose a {@link #band} as a strip of light {@code width} wide from {@code a} to {@code b} (offsets
     * from the display's own position), its flat face turned {@code twist} radians about the strip.
     * Zero length or width hides it.
     */
    public static void ribbon(Entity d, Vector3f a, Vector3f b, float width, float twist) {
        if (d == null) {
            return;
        }
        Vector3f dir = new Vector3f(b).sub(a);
        float len = dir.length();
        if (len < 1e-3f || width <= 0f) {
            pose(d, new Transformation(new Vector3f(a), new Quaternionf(), new Vector3f(0f, 0f, 0f),
                    new Quaternionf()), 0);
            return;
        }
        float sx = width / BEAM_UNIT_W;
        Quaternionf turn = new Quaternionf().rotationTo(0f, 1f, 0f, dir.x / len, dir.y / len, dir.z / len)
                .rotateY(twist);
        Vector3f shift = new Quaternionf(turn).transform(new Vector3f(-BEAM_OFF_X * sx, 0f, 0f));
        pose(d, new Transformation(new Vector3f(a).add(shift), turn, new Vector3f(sx, len / BEAM_UNIT_H, 1f),
                new Quaternionf()), 0);
    }

    /** An item display of the real item, for the item reveal. */
    public static Entity item(ServerLevel level, ItemStack stack, double x, double y, double z, String wishTag) {
        Entity d = EntityType.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null || stack == null || stack.isEmpty()) {
            return null;
        }
        d.snapTo(x, y, z, 0f, 0f);
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
        tag.put("item", com.moddingfromamod.compat.EntityNbt.saveStack(stack, level.registryAccess()));
        common(tag, 2f);
        com.moddingfromamod.compat.EntityNbt.load(d, tag);
        return add(level, d, wishTag);
    }

    private static void common(CompoundTag tag, float range) {
        tag.putInt("teleport_duration", GLIDE);
        tag.putFloat("view_range", range);
        CompoundTag light = new CompoundTag();
        light.putInt("block", 15);
        light.putInt("sky", 15);
        tag.put("brightness", light);
    }

    private static Entity add(ServerLevel level, Entity d, String wishTag) {
        d.addTag(TAG);
        if (wishTag != null) {
            d.addTag(wishTag);
        }
        d.setNoGravity(true);
        level.addFreshEntity(d);
        FxVeil.hideNow(level, d);      //: never on a camera's connection, so never in a photo
        return d;
    }

    /**
     * Pose a display as a cube of {@code size} centred on its own position, turned by {@code spin},
     * eased over {@code ease} ticks. A display draws its block from its corner, so the translation
     * moves the corner back by half a size (after turning, so it turns about its centre).
     */
    public static void cube(Entity d, float sx, float sy, float sz, Quaternionf spin, int ease) {
        if (d == null) {
            return;
        }
        Vector3f corner = new Quaternionf(spin).transform(new Vector3f(-sx / 2f, -sy / 2f, -sz / 2f));
        pose(d, new Transformation(corner, new Quaternionf(spin), new Vector3f(sx, sy, sz), new Quaternionf()), ease);
    }

    /**
     * Pose a display as a box of {@code sx × sy × sz} centred {@code offset} away from the display's
     * own position, turned by {@code spin}. The display stays put and only its shape moves, so a
     * whole pillar of pieces can stand on one spot and still swirl (no position packets, and the
     * client eases the change over {@code ease} ticks).
     */
    public static void cubeAt(Entity d, Vector3f offset, float sx, float sy, float sz, Quaternionf spin,
                              int ease) {
        if (d == null) {
            return;
        }
        Vector3f corner = new Quaternionf(spin).transform(new Vector3f(-sx / 2f, -sy / 2f, -sz / 2f));
        pose(d, new Transformation(corner.add(offset), new Quaternionf(spin), new Vector3f(sx, sy, sz),
                new Quaternionf()), ease);
    }

    /** Pose an item display at a uniform scale, turned by {@code spin} (item displays are centred). */
    public static void itemPose(Entity d, float scale, Quaternionf spin, int ease) {
        if (d == null) {
            return;
        }
        pose(d, new Transformation(new Vector3f(), new Quaternionf(spin), new Vector3f(scale, scale, scale),
                new Quaternionf()), ease);
    }

    private static void pose(Entity d, Transformation t, int ease) {
        var p = (com.moddingfromamod.mixin.DisplayPoseInvoker) d;
        p.moddingfromamod$setTransformation(t);
        p.moddingfromamod$setTransformationInterpolationDuration(Math.max(0, ease));
        p.moddingfromamod$setTransformationInterpolationDelay(0);
    }

    /**
     * Remove every orb and reveal display in this level; returns how many. For restarts and tests.
     * Sees LOADED chunks only ({@code getAllEntities}); a display saved with an unloaded chunk is
     * refused when it loads instead ({@code WishOrbs.onJoin}).
     */
    public static int sweep(ServerLevel level, String tag) {
        // Found first, discarded after: a discard takes the entity out of the very map
        // getAllEntities() is iterating, and when that map shrinks mid-loop the iterator runs off its end.
        java.util.List<Entity> found = new java.util.ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e != null && e.entityTags().contains(tag)) {
                found.add(e);
            }
        }
        found.forEach(Entity::discard);
        return found.size();
    }

    /** How many of them are alive in this level (Gate B's count). */
    public static int count(ServerLevel level, String tag) {
        int n = 0;
        for (Entity e : level.getAllEntities()) {
            if (e != null && e.isAlive() && e.entityTags().contains(tag)) {
                n++;
            }
        }
        return n;
    }
}
