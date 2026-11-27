package com.moddingfromamod.item;

import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * <b>What a wished item DOES, separated from which item is carrying it.</b>
 *
 * <p>Extracted verbatim from {@link WishItem#use} and {@link WishItem#useOn} — no behaviour is
 * changed and {@code WishItem} now delegates here, so the modded path runs exactly the code it
 * always ran and {@code WishItemGameTest} covers it unchanged.
 *
 * <p><b>Why extract rather than copy.</b> The vanilla-carrier spike
 * ({@code docs/VANILLA_ITEM_SPIKE.md}) needs the same three abilities to fire for an item that is
 * <i>not</i> ours, driven from a NeoForge interaction event instead of from an {@code Item}
 * override. Writing them a second time there would be a second source of truth, and this repo has
 * paid for one more than once — a transcribed NBT key made {@code IconTintClientTest} report a
 * working feature as broken. Two paths that must stay identical are exactly the thing that should
 * not be typed twice: an A/B between two copies of a behaviour measures the copying.
 *
 * <p>Everything here reads its instructions off the <b>stack</b>, never off the item, which is what
 * makes it carrier-agnostic. That is the same shape {@link WishMeleeHit} already had — which is why
 * {@code on_hit} needed no work at all.
 */
public final class WishItemActions {

    private WishItemActions() {
    }

    /**
     * Right-click in the air: throw it, or run its function. Both, if the wish asked for both.
     *
     * @return true if the stack had something to do — the caller turns that into its own result
     *         type ({@code InteractionResultHolder} for an item, a cancelled event for the bridge)
     */
    // TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
    // SEAM: one implementation for two arms. With one arm left, inline back into WishItem.
    public static boolean use(Level level, Player player, InteractionHand hand, ItemStack stack) {
        String thrown = WishItem.throwTag(stack);
        String fn = WishItem.onUse(stack);
        if (thrown.isEmpty() && fn.isEmpty()) {
            return false;
        }
        if (level instanceof ServerLevel serverLevel) {
            if (!thrown.isEmpty()) {
                // A tagged snowball: the projectile every throwable datapack already knows how to
                // watch (`as @e[type=snowball, tag=...]`), so the behaviour stays in CBScript.
                Snowball ball = com.moddingfromamod.compat.Projectiles.snowball(level, player);
                ball.setItem(stack.copyWithCount(1));
                ball.addTag(thrown);
                WishThrowImpact.arm(ball, WishItem.hitFunction(stack));
                ball.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.5F, 1.0F);
                level.addFreshEntity(ball);
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.5F, 0.4F);
            }
            if (!fn.isEmpty()) {
                runFunction(serverLevel, player, fn);
            }
        }
        if (!thrown.isEmpty()) {
            stack.consume(1, player);
        }
        return true;
    }

    /**
     * Place the anchor.
     *
     * <p>Deliberately a transcription of {@code ArmorStandItem.useOn}'s three load-bearing
     * behaviours rather than something new, because a datapack written against the vanilla item must
     * keep working unchanged: the anchor lands on the <b>clicked face's</b> cell, it is turned to
     * <b>face the player</b> (snapped to 45°, which is what lets a machine put its handle on the
     * side the child is standing at), and it refuses to place where it would overlap anything.
     */
    public static InteractionResult useOn(UseOnContext context) {
        // NOTE the tag is NOT checked here, and that is deliberate: today's WishItem.useOn places
        // an anchor whether or not the stack carries a place tag (it only ADDS the tag when there
        // is one). Adding a guard would have been tidier and would have changed the modded path's
        // behaviour, which this extraction must not do. The bridge checks for a tag itself, before
        // it ever calls in — it is asked about every right-click in the world, not just ours.
        ItemStack stack = context.getItemInHand();
        if (context.getClickedFace() == Direction.DOWN) {
            return InteractionResult.FAIL;
        }
        Level level = context.getLevel();
        BlockPos pos = new BlockPlaceContext(context).getClickedPos();
        Vec3 at = Vec3.atBottomCenterOf(pos);
        AABB box = EntityType.ARMOR_STAND.getDimensions().makeBoundingBox(at.x(), at.y(), at.z());
        if (!level.noCollision(null, box) || !level.getEntities(null, box).isEmpty()) {
            return InteractionResult.FAIL;
        }

        if (level instanceof ServerLevel serverLevel) {
            if (putDown(serverLevel, at, Mth.wrapDegrees(context.getRotation() - 180.0F),
                    WishItem.placeTag(stack)) == null) {
                return InteractionResult.FAIL;
            }
        }
        stack.consume(1, context.getPlayer());
        return com.moddingfromamod.compat.Interactions.success(level.isClientSide());
    }

    /**
     * <b>Put a placed thing down at {@code at}, facing {@code yaw}</b> — the invisible marker its
     * datapack runs from, tagged {@code tag}, snapped to the nearest 45°. What his right-click does
     * ({@link #useOn}) and what {@code wishput} does for a thing put somewhere by name, so
     * the two can never draw different machines. Null when the stand could not be made.
     */
    public static ArmorStand putDown(ServerLevel serverLevel, Vec3 at, float yaw, String tag) {
        ArmorStand anchor = EntityType.ARMOR_STAND.create(serverLevel, EntitySpawnReason.COMMAND);
        if (anchor == null) {
            return null;
        }
        CompoundTag shape = new CompoundTag();
        shape.putBoolean("Marker", true);
        shape.putBoolean("Invisible", true);
        shape.putBoolean("NoGravity", true);
        shape.putBoolean("Invulnerable", true);
        shape.putBoolean("Silent", true);
        // what CustomData.loadInto(Entity) did before 1.21.9 removed it: save, merge the shape, load
        CompoundTag merged = com.moddingfromamod.compat.EntityNbt.save(anchor);
        merged.merge(shape);
        com.moddingfromamod.compat.EntityNbt.load(anchor, merged);
        if (tag != null && !tag.isEmpty()) {
            anchor.addTag(tag);
        }
        float snapped = (float) Mth.floor((Mth.wrapDegrees(yaw) + 22.5F) / 45.0F) * 45.0F;
        anchor.snapTo(at.x(), at.y(), at.z(), snapped, 0.0F);
        serverLevel.addFreshEntity(anchor);
        serverLevel.playSound(null, at.x(), at.y(), at.z(),
                SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.75F, 0.8F);
        return anchor;
    }

    /**
     * Run a datapack function as the player, at the player, at permission level 2.
     *
     * <p>Level 2 because a loaded datapack's own functions run there, and a level-0 source cannot
     * even SEE {@code /setblock} — Brigadier reports a permission-gated command as "unknown", which
     * reads exactly like a typo in the wish's own script.
     */
    static void runFunction(ServerLevel level, Player player, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return;
        }
        var server = level.getServer();
        server.getFunctions().get(rl).ifPresent(fn ->
                server.getFunctions().execute(fn,
                        com.moddingfromamod.compat.EntityCommands.source(player).withPermission(2).withSuppressedOutput()));
    }
}
