package com.moddingfromamod.event.catching;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.StatusEffect;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;

/**
 * <b>The things an event can do to what it catches</b> (E3): {@code carry}, {@code lift},
 * {@code shake}, {@code ignite}, {@code hurt} and {@code status}. The feel is in {@link CatchRules};
 * this is only the part that touches an entity.
 *
 * <p><b>Everything that moves a body goes through one velocity with {@code syncVelocity}.</b> That flag
 * is the only wire a server-set velocity has to a player's own client, and a command-written
 * {@code Motion} is overwritten by that client (an earlier issue's launch pad shipped exactly that bug past every
 * server-side test). And every velocity REPLACES the component it acts on, so nothing compounds.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class Effects {

    private Effects() {
    }

    /** Who an event moved, and when, so their next landing is safe. */
    private static final Map<UUID, Long> MOVED = new ConcurrentHashMap<>();

    /** Carried along the way the event pushes, at up to {@link CatchRules#MAX_CARRY}. */
    public static Effect carry() {
        return (level, c, age) -> {
            if (c.dirX() == 0 && c.dirZ() == 0) {
                return;
            }
            Vec3 v = c.entity().getDeltaMovement();
            double along = v.x * c.dirX() + v.z * c.dirZ();
            double to = CatchRules.carry(along, c.strength());
            move(level, c.entity(), v.add(c.dirX() * (to - along), 0, c.dirZ() * (to - along)));
        };
    }

    /** Lifted, or slammed down while {@code slam} says so (a wave's crest landing on you). */
    public static Effect lift(IntPredicate slam) {
        return (level, c, age) -> {
            Vec3 v = c.entity().getDeltaMovement();
            move(level, c.entity(), new Vec3(v.x,
                    CatchRules.vertical(v.y, c.strength(), slam.test(age)), v.z));
        };
    }

    public static Effect lift() {
        return lift(age -> false);
    }

    /** Jolted about: a new sideways jolt each time, never one added to the last. */
    public static Effect shake() {
        return (level, c, age) -> {
            double[] j = CatchRules.shake(c.strength(), level.getRandom().nextDouble() * Math.PI * 2);
            Vec3 v = c.entity().getDeltaMovement();
            move(level, c.entity(), new Vec3(j[0], Math.max(v.y, j[1]), j[2]));
        };
    }

    /** Set alight, for longer the harder it was caught. */
    public static Effect ignite() {
        return (level, c, age) -> {
            Entity e = c.entity();
            if (!e.fireImmune() && e.getRemainingFireTicks() <= 0) {
                e.igniteForSeconds(CatchRules.burnSeconds(c.strength()));
            }
        };
    }

    /** Hurt by up to {@code amount}; a player never below {@link CatchRules#PLAYER_FLOOR}. */
    public static Effect hurt(float amount) {
        return (level, c, age) -> {
            if (!(c.entity() instanceof LivingEntity living)) {
                return;
            }
            float dealt = CatchRules.hurt((float) (amount * c.strength()), living.getHealth(),
                    living instanceof Player);
            if (dealt > 0) {
                com.moddingfromamod.compat.Damage.hurt(living, level.damageSources().generic(), dealt);
            }
        };
    }

    /**
     * Battered by a wrecking wave or vortex: hurt by up to {@code amount}, and it MAY kill — except
     * him (never below {@link CatchRules#PLAYER_FLOOR}), his pets (anything tamed, or claimed as a child's) and the
     * creatures he wished, which it carries but never hurts. Undo cannot bring back a killed creature; that was chosen
     * over a tornado that cannot hurt anything.
     */
    public static Effect batter(float amount) {
        return (level, c, age) -> {
            if (!(c.entity() instanceof LivingEntity living) || spared(living)) {
                return;
            }
            float dealt = CatchRules.hurt((float) (amount * Math.max(0.3, c.strength())), living.getHealth(),
                    living instanceof Player);
            if (dealt > 0) {
                com.moddingfromamod.compat.Damage.hurt(living, level.damageSources().generic(), dealt);
            }
        };
    }

    /** A creature a wrecking event never hurts: a pet (tamed, or claimed as a child's) or one he wished. */
    public static boolean spared(Entity e) {
        if (e instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame()) {
            return true;
        }
        if (e instanceof net.minecraft.world.entity.animal.equine.AbstractHorse h && h.isTamed()) {
            return true;
        }
        return e.getPersistentData().contains(com.moddingfromamod.mob.PetBehaviours.OWNER)
                || com.moddingfromamod.mob.Creatures.isCreature(e);
    }

    /**
     * One of the KIND status effects from the item catalogue ({@link StatusEffect#EFFECTS}) — a warm
     * glow, slow falling through a storm. Nothing from outside that table: an event that poisons or
     * blinds him is a thing done TO a child. Returns null for a name the table does not know.
     */
    public static Effect status(String name, int seconds) {
        String which = StatusEffect.effect(name);
        if (which == null) {
            return null;
        }
        var holder = BuiltInRegistries.MOB_EFFECT.getOptional(Identifier.withDefaultNamespace(which))
                .map(BuiltInRegistries.MOB_EFFECT::wrapAsHolder).orElse(null);
        if (holder == null) {
            return null;
        }
        int ticks = Math.max(20, Math.min(StatusEffect.MAX_SECONDS, seconds) * 20);
        return (level, c, age) -> {
            if (c.entity() instanceof LivingEntity living) {
                living.addEffect(new MobEffectInstance(holder, ticks, 0, true, true));
            }
        };
    }

    /** The one way an event moves anything. */
    public static void move(ServerLevel level, Entity e, Vec3 v) {
        e.setDeltaMovement(v);
        e.syncVelocity = true;
        e.resetFallDistance();
        MOVED.put(e.getUUID(), level.getGameTime());
    }

    /** Test seam: was this entity moved by an event within the safe window? */
    public static boolean safeToLand(Entity e) {
        Long at = MOVED.get(e.getUUID());
        return at != null && e.level().getGameTime() - at <= CatchRules.FALL_SAFE_TICKS;
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (safeToLand(event.getEntity())) {
            event.setDamageMultiplier(0);
        }
    }
}
