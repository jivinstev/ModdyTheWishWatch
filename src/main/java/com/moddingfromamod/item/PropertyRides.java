package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Set;

/**
 * <b>Two PROPERTIES that change what a real item's own action produces (T6F follow-ups).</b> Both
 * are read off the stack's {@code custom_data} ({@link com.moddingfromamod.datapack.ItemProperties}
 * renders them), so they work on the real vanilla item and on nothing a wish did not make.
 *
 * <ul>
 *   <li><b>{@code projectile} </b> — a bow or crossbow still draws, looses and aims; the
 *       arrow it looses is replaced, on the tick it joins the world, by the named projectile with
 *       the arrow's position, motion and owner. So "a bow that shoots fireballs" fires a fireball
 *       as far as the draw sends it, rather than an arrow that makes fire where it lands. An
 *       {@code arrow} wish on the same bow arms the new projectile instead (it lands, so it runs).
 *       {@code on_shot} still fires: {@link WishShapeEvents} handles the shot before this runs.</li>
 *   <li><b>{@code never_empty} </b> — the bucket's pour is vanilla's (water where a bucket
 *       puts water, a cauldron filled); at the end of that tick, if the hand that held the wished
 *       bucket now holds an EMPTY bucket, the wished one goes back. A drink (milk) ends in the use's
 *       Finish, whose result is set to the full bucket directly.</li>
 * </ul>
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class PropertyRides {

    /**
     * E1's labels (tools/parts-coverage.py reads this set): a launcher that shoots something other
     * than its arrow. Named as the wish rows name them (syn-3-023, syn-5-026).
     */
    public static final Set<String> PARTS = Set.of("fireball", "egg_projectile",
            "snowball_projectile", "wind_charge_projectile");

    private PropertyRides() {
    }

    private static final java.util.Queue<Runnable> PENDING = new java.util.concurrent.ConcurrentLinkedQueue<>();

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Runnable r;
        while ((r = PENDING.poll()) != null) {
            try {
                r.run();
            } catch (RuntimeException e) {
                com.mojang.logging.LogUtils.getLogger().warn("property ride skipped: {}", e.toString());
            }
        }
    }

    // ── projectile ────────────────────────────────────────────────────────────────────────────

    /**
     * HIGH priority so the arrow never reaches the arrow-shape handlers: cancelled here, it does not
     * join, and the replacement (armed with the bow's arrow wish, if it has one) is what flies.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onArrowJoins(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk()
                || !(event.getEntity() instanceof AbstractArrow arrow)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ItemStack weapon = WishShapeEvents.weaponOf(arrow);
        String shoots = WishItem.projectile(weapon);
        if (shoots.isEmpty() || !(arrow.getOwner() instanceof LivingEntity shooter)) {
            return;
        }
        // on_shot rides the SHOT, which this does not replace: let it see the arrow first.
        WishShapeEvents.shotFrom(arrow, weapon);
        event.setCanceled(true);
        Vec3 at = arrow.position();
        Vec3 motion = arrow.getDeltaMovement();
        String landFn = WishItem.arrowFunction(weapon);
        PENDING.add(() -> fire(level, shooter, shoots, at, motion, landFn));
    }

    /** The replacement shot: the named projectile, where the arrow was, going where it was going. */
    static Projectile fire(ServerLevel level, LivingEntity shooter, String id, Vec3 at, Vec3 motion, String landFn) {
        Identifier rl = Identifier.tryParse(id);
        var type = rl == null ? null : BuiltInRegistries.ENTITY_TYPE.getValue(rl);
        Entity e = type == null ? null : type.create(level, EntitySpawnReason.COMMAND);
        if (!(e instanceof Projectile p)) {
            return null;
        }
        p.setOwner(shooter);
        p.snapTo(at.x, at.y, at.z, shooter.getYRot(), shooter.getXRot());
        p.setDeltaMovement(motion);
        if (!landFn.isEmpty()) {
            WishThrowImpact.arm(p, landFn);
        }
        level.addFreshEntity(p);
        return p;
    }

    // ── never_empty ───────────────────────────────────────────────────────────────────────────

    /** A pour into the world: BucketItem.use. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBucketUse(PlayerInteractEvent.RightClickItem event) {
        watch(event.getEntity(), event.getHand(), event.getItemStack(), event.isCanceled());
    }

    /** A pour into a block that takes it (a cauldron): the block's own interaction empties it. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBucketUseOnBlock(PlayerInteractEvent.RightClickBlock event) {
        watch(event.getEntity(), event.getHand(), event.getItemStack(), event.isCanceled());
    }

    private static void watch(Player who, InteractionHand hand, ItemStack stack, boolean cancelled) {
        if (cancelled || who.level().isClientSide() || !WishItem.neverEmpty(stack)) {
            return;
        }
        ItemStack full = stack.copyWithCount(1);
        PENDING.add(() -> {
            if (who.getItemInHand(hand).is(Items.BUCKET)) {
                ItemStack now = who.getItemInHand(hand);
                if (now.getCount() == 1) {
                    who.setItemInHand(hand, full.copy());
                } else if (who instanceof net.minecraft.server.level.ServerPlayer sp) {
                    // A stack of empties (he was carrying some): one of them was ours. HandOver asks
                    // for room first (Inventory.add's boolean lies to a Creative player, LEARNINGS).
                    now.shrink(1);
                    HandOver.toPlayer(sp, full.copy());
                }
            }
        });
    }

    /** A drink (milk): the Finish's result IS what he holds next, so it is the full bucket. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDrinkFinish(LivingEntityUseItemEvent.Finish event) {
        if (!event.getEntity().level().isClientSide() && WishItem.neverEmpty(event.getItem())
                && event.getResultStack().is(Items.BUCKET)) {
            event.setResultStack(event.getItem().copyWithCount(1));
        }
    }
}
