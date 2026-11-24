package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * <b>The two shapes that fire from vanilla's own actions: {@code arrow} and
 * {@code on_mine}.</b>
 *
 * <p>Both read their instructions off the STACK, like {@link WishMeleeHit}, so they work on a real
 * bow or pickaxe (base_item) and on ours alike, and never on an item a wish did not make.
 *
 * <ul>
 *   <li><b>arrow</b> — every arrow (or trident) that joins the world is asked which weapon fired it;
 *       if that weapon carries an arrow function, the arrow is ARMED with it and tagged for its
 *       trail, and {@link WishThrowImpact} runs it where it lands.</li>
 *   <li><b>on_mine</b> — when he breaks a block holding a stack with a mine function, it runs as him
 *       at the block's centre, on the NEXT server task: {@code BreakEvent} fires before the block is
 *       removed, so a function run inside it would place a light (say) that the break then erases.
 *       Queued to the end of the tick: {@code execute()} on the server thread runs at once.</li>
 * </ul>
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WishShapeEvents {

    private WishShapeEvents() {
    }

    /** Mine wishes waiting for the tick to finish, so the break has happened first. */
    private static final java.util.Queue<Runnable> PENDING = new java.util.concurrent.ConcurrentLinkedQueue<>();

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        Runnable r;
        while ((r = PENDING.poll()) != null) {
            try {
                r.run();
            } catch (RuntimeException e) {
                com.mojang.logging.LogUtils.getLogger().warn("on_mine wish skipped: {}", e.toString());
            }
        }
    }

    /** The weapon that fired this arrow: vanilla's own record, or a trident's own stack. */
    static ItemStack weaponOf(AbstractArrow arrow) {
        ItemStack w = arrow.getWeaponItem();
        if ((w == null || w.isEmpty())
                && arrow instanceof net.minecraft.world.entity.projectile.arrow.ThrownTrident trident) {
            w = trident.getPickupItemStackOrigin();
        }
        return w == null ? ItemStack.EMPTY : w;
    }

    /**
     * The thrower's last click with a landing wish, for a projectile that carries no item of its own
     * (a wind charge): shooter → (function, game time). Matched on the same tick, then forgotten.
     */
    private static final java.util.Map<java.util.UUID, java.util.Map.Entry<String, Long>> LAND_CLICK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** A right-click with a landing wish: remember it for the projectile it is about to throw. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void onThrowClick(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        String fn = WishItem.landFunction(event.getItemStack());
        if (!fn.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            LAND_CLICK.put(event.getEntity().getUUID(), java.util.Map.entry(fn, level.getGameTime()));
        }
    }

    /**
     * <b>ON_THROW_LAND: arm what a real throwable threw.</b> A snowball, egg, pearl or
     * potion carries the stack it was thrown from, wish and all; a trident is its own stack; a
     * wind charge carries nothing, so it is matched to the click that threw it this tick.
     * WishThrowImpact then runs the wish at the landing — nudged out of a block face, as an
     * arrow's is.
     */
    @SubscribeEvent
    public static void onThrownJoins(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk() || event.isCanceled()
                || !(event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile p)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        String fn = "";
        if (p instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile t) {
            fn = WishItem.landFunction(t.getItem());
        } else if (p instanceof AbstractArrow a) {
            fn = WishItem.landFunction(weaponOf(a));
        }
        if (fn.isEmpty() && p.getOwner() != null) {
            var click = LAND_CLICK.remove(p.getOwner().getUUID());
            if (click != null && click.getValue() == level.getGameTime()) {
                fn = click.getKey();
            }
        }
        if (!fn.isEmpty()) {
            WishThrowImpact.arm(p, fn);
            p.getPersistentData().putBoolean(WishThrowImpact.NUDGE, true);
        }
    }

    /**
     * <b>ON_USE_DONE: he let go, or the use ran out.</b> Stop and Finish never both fire for
     * one use, so each is the end of it. Queued to the end of the tick like the others.
     */
    @SubscribeEvent
    public static void onUseStopped(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Stop event) {
        useDone(event.getEntity(), event.getItem());
    }

    @SubscribeEvent
    public static void onUseFinishedForDone(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) {
        useDone(event.getEntity(), event.getItem());
    }

    private static void useDone(net.minecraft.world.entity.LivingEntity who, ItemStack stack) {
        if (!(who.level() instanceof ServerLevel level)) {
            return;
        }
        String fn = WishItem.useDoneFunction(stack);
        Identifier id = fn.isEmpty() ? null : Identifier.tryParse(fn);
        if (id == null) {
            return;
        }
        var server = level.getServer();
        PENDING.add(() -> server.getFunctions().get(id).ifPresent(f -> server.getFunctions().execute(f,
                com.moddingfromamod.compat.EntityCommands.source(who)
                        .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput())));
    }

    @SubscribeEvent
    public static void onArrowJoins(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractArrow arrow)
                || event.loadedFromDisk() || event.isCanceled()) {
            return;     // cancelled: PropertyRides replaced it and armed the replacement
        }
        ItemStack weapon = weaponOf(arrow);
        shot(arrow, weapon);
        String fn = WishItem.arrowFunction(weapon);
        if (fn.isEmpty()) {
            return;
        }
        WishThrowImpact.arm(arrow, fn);
        String feature = WishItem.wishFeature(weapon);
        if (!feature.isEmpty()) {
            arrow.addTag(feature + "_fly");
        }
    }

    /** Shooter → the game time he last fired a wish's beam: one beam per shot, not per arrow. */
    private static final java.util.Map<java.util.UUID, Long> LAST_SHOT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * <b>ON_SHOT: the wish runs the moment he shoots, as him.</b> Queued to the end of the
     * tick, like on_mine, so the arrow is in the world first. A multishot crossbow looses three
     * arrows in one tick, and the wish is ONE shot — so once per shooter per tick.
     */
    /** The same, for {@link PropertyRides}, which cancels the arrow before this handler sees it. */
    static void shotFrom(AbstractArrow arrow, ItemStack weapon) {
        shot(arrow, weapon);
    }

    private static void shot(AbstractArrow arrow, ItemStack weapon) {
        String fn = WishItem.shotFunction(weapon);
        Identifier id = fn.isEmpty() ? null : Identifier.tryParse(fn);
        if (id == null || !(arrow.getOwner() instanceof net.minecraft.world.entity.LivingEntity shooter)
                || !(arrow.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        Long last = LAST_SHOT.put(shooter.getUUID(), now);
        if (last != null && last == now) {
            return;
        }
        var server = level.getServer();
        PENDING.add(() -> server.getFunctions().get(id).ifPresent(f -> server.getFunctions().execute(f,
                com.moddingfromamod.compat.EntityCommands.source(shooter)
                        .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput())));
    }

    /**
     * <b>ON_CONSUME: he has FINISHED eating or drinking it, so the wish happens.</b> Vanilla
     * does the eating — the hunger, the bottle left behind — and fires this at the end of it with a
     * copy of the stack as it was, which still carries the wish. Queued to the end of the tick like
     * the other two, so the wish runs after the eating has fully happened.
     */
    @SubscribeEvent
    public static void onFinishUsing(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        String fn = WishItem.consumeFunction(event.getItem());
        Identifier id = fn.isEmpty() ? null : Identifier.tryParse(fn);
        if (id == null) {
            return;
        }
        var who = event.getEntity();
        var server = level.getServer();
        PENDING.add(() -> server.getFunctions().get(id).ifPresent(f -> server.getFunctions().execute(f,
                com.moddingfromamod.compat.EntityCommands.source(who)
                        .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput())));
    }

    /**
     * <b>ON_BLOCK: his shield STOPPED a hit, so the wish happens.</b> Lowest priority, so the
     * question is answered after everyone else has had their say: a block another mod cancelled is
     * not a block. The shield is the item he is using. After the wish's own function runs,
     * {@link ReflectProjectiles} sends the blocked arrow back if the wish asked for that.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void onShieldBlock(net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent event) {
        var who = event.getEntity();
        if (!event.getBlocked() || !(who.level() instanceof ServerLevel level)) {
            return;
        }
        String fn = WishItem.blockFunction(who.getUseItem());
        Identifier id = fn.isEmpty() ? null : Identifier.tryParse(fn);
        if (id == null) {
            return;
        }
        var server = level.getServer();
        var projectile = event.getDamageSource().getDirectEntity();
        PENDING.add(() -> {
            server.getFunctions().get(id).ifPresent(f -> server.getFunctions().execute(f,
                    com.moddingfromamod.compat.EntityCommands.source(who)
                            .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput()));
            ReflectProjectiles.afterBlock(who, projectile);
        });
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getPlayer() == null) {
            return;
        }
        String fn = WishItem.onMine(event.getPlayer().getMainHandItem());
        Identifier id = fn.isEmpty() ? null : Identifier.tryParse(fn);
        if (id == null) {
            return;
        }
        BlockPos pos = event.getPos().immutable();
        var player = event.getPlayer();
        var server = level.getServer();
        // ⚠ QUEUED FOR THE END OF THE TICK, NOT server.execute(): called from the server thread,
        // execute() does not queue — it runs right there, inside the break, and the wish found the
        // block still standing (measured: glowstone never appeared on the mined block). A queue of
        // our own, drained at ServerTickEvent.Post, is the same on both targets (1.21.4 renamed
        // the event loop's tell()).
        PENDING.add(() -> server.getFunctions().get(id).ifPresent(f -> server.getFunctions().execute(f,
                com.moddingfromamod.compat.EntityCommands.source(player)
                        .withPosition(Vec3.atCenterOf(pos)).withLevel(level)
                        .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput())));
    }
}
