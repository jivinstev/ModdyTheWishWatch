package com.moddingfromamod.wishbuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * <b>What he is looking at and carrying, when a Turn-into page opens</b> (§1): a fixed fan of 9×5 rays across his
 * view, up to {@link #REACH} blocks, each counting the first block it hits (the wand's own rule: a block's outline
 * stops a ray, and so does water, which is then counted as nothing); the ray at his crosshair is the crosshair block.
 * A block that holds things (a chest, a sign, a magic block's anchor: anything with a block entity), a fluid, bedrock
 * and anything {@link BuilderFind#refused} refuses is never counted. His inventory: the hotbar, the backpack and the off
 * hand, never the armour he wears, and never Moddy's own things (the watch, the guide, the wish bag, a made item: a
 * {@code Wish…} mark). {@link BuilderSuggest} ranks them. Read once, on the server thread; nothing is kept.
 */
public final class BuilderView {

    private BuilderView() {
    }

    public static final double REACH = 32.0;
    static final int COLS = 9;
    static final int ROWS = 5;
    /** Degrees between two rays: the fan spans 64° across and 32° up and down, inside a default field of view. */
    static final float STEP = 8.0f;

    public static BuilderSuggest.Seen sample(ServerPlayer p) {
        ServerLevel level = p.level();
        Vec3 eye = p.getEyePosition(1.0f);
        String crosshair = first(level, p, eye, p.getViewVector(1.0f));
        Map<String, Integer> inView = new LinkedHashMap<>();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                float pitch = p.getXRot() + (r - ROWS / 2) * STEP;
                float yaw = p.getYRot() + (c - COLS / 2) * STEP;
                String id = first(level, p, eye, Vec3.directionFromRotation(Math.max(-90f, Math.min(90f, pitch)), yaw));
                if (id != null) {
                    inView.merge(id, 1, Integer::sum);
                }
            }
        }
        Map<String, Integer> carrying = new LinkedHashMap<>();
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            carry(carrying, s);
        }
        carry(carrying, p.getOffhandItem());
        return new BuilderSuggest.Seen(crosshair, inView, carrying);
    }

    /** The id of the first block this ray hits, or null (sky, unloaded, a fluid, or a block never offered). */
    private static String first(ServerLevel level, ServerPlayer p, Vec3 eye, Vec3 dir) {
        Vec3 end = eye.add(dir.scale(REACH));
        var chunks = level.getChunkSource();
        double t = com.moddingfromamod.builder.LoadedRay.loadedFraction(eye.x, eye.z, end.x, end.z,
                (cx, cz) -> chunks.getChunkNow(cx, cz) != null);
        if (t <= 0.0) {
            return null;
        }
        if (t < 1.0) {
            end = eye.add(end.subtract(eye).scale(t));
        }
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos at = hit.getBlockPos();
        BlockState state = level.getBlockState(at);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) {
            return null;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return BuilderFind.refused(id) ? null : id;
    }

    private static void carry(Map<String, Integer> out, ItemStack s) {
        if (s == null || s.isEmpty() || moddys(s) || s.is(net.minecraft.world.item.Items.WRITTEN_BOOK)) {   // the guide is a written book
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        if (!BuilderFind.refused(id)) {
            out.merge(id, s.getCount(), Integer::sum);
        }
    }

    /** Moddy's own or a made thing: a {@code Wish…} mark in its custom data (the watch, the guide, the bag, a wished item). */
    static boolean moddys(ItemStack s) {
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag tag = data.copyTag();
        for (String key : tag.keySet()) {
            if (key.startsWith("Wish") || key.equals(com.moddingfromamod.item.WishItem.WATCH)) {
                return true;
            }
        }
        return false;
    }
}
