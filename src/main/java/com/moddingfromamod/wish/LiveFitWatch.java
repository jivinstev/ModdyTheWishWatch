package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The one place a world's limits hold every wished body, whatever route made it strong</b> (#174). Before this, a
 * world's limits reached only the creatures and tools the core recognised, and every other route failed open: a
 * learned recipe's {@code wishsummon} wolf at attack 100, a {@code summon} with NBT attributes, a wild mob a wish's
 * {@code attribute} or {@code effect} command or {@code modify_mob} made stronger, a datapack's summons, a spawn egg a
 * wish handed over. Remembering each route had already failed once; this holds them by construction instead:
 *
 * <ol>
 *   <li><b>Marked where they come in.</b> A body a wish brings in is marked by the spawn scope every wish command,
 *       event and wish datapack runs in ({@code mob/WishMobLimit} tags it {@code moddy_wished}), a sculpted creature
 *       carries {@code wishmob}, a spawn egg a wish hands over carries {@link LiveFit#HELD_TAG} into the mob it hatches
 *       ({@code item/delivery/WishDelivery}). A body the world made whose attributes change WHILE a wish runs
 *       (any spawn scope: a command, a replay, a rule, a wish datapack's function) is seen at that change
 *       ({@code mixin/AttributeTouchMixin} calls {@link #touched}) and marked {@link LiveFit#TOUCHED_TAG}.</li>
 *   <li><b>Fitted the moment the wish command ends</b> ({@link #afterWishCommand}, from {@code WishExecutor.runOne}),
 *       with the player who ran it as the one whose limits apply, before the body can act.</li>
 *   <li><b>Kept fitted.</b> Twice a second, while a world answers, every marked living body is read and any that
 *       stands above the limits it is held to (an effect, a held weapon, a later command, anything) is fitted again;
 *       and a player whose limits changed without the world saying so (a different stamp) has his creatures refit,
 *       raised as well as lowered.</li>
 * </ol>
 *
 * As everywhere in the live fit, nothing is persisted into a make: the fit is a modifier on the live body, and the body
 * comes back to its own numbers when the limit rises or in a world without one. With no world answering, nothing here
 * runs at all. Server thread; registered on {@code NeoForge.EVENT_BUS}.
 */
public final class LiveFitWatch {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** How often every marked body is read, in ticks. */
    static final int EVERY_TICKS = 10;

    /** Attribute maps changed while a wish ran, not yet looked at. Weak: a body that is gone takes its map with it. */
    private static final Set<AttributeMap> TOUCHED = Collections.newSetFromMap(new WeakHashMap<>());
    /** Bodies a wish's scope admitted, not yet fitted with their wisher's limits. */
    private static final List<Entity> ADMITTED = new ArrayList<>();
    /** The limits stamp each player's creatures were last fitted at. */
    private static final Map<UUID, String> STAMPS = new HashMap<>();
    /** True while the live fit itself changes a body (its change is not a wish's). */
    private static boolean fitting;
    private static long lastSaid;

    private LiveFitWatch() {
    }

    /** The live fit is changing a body: its own change is not one to answer. Server thread. */
    static void fitting(boolean on) {
        fitting = on;
    }

    /**
     * An attribute of some body changed while a wish's spawn scope was open ({@code mixin/AttributeTouchMixin}). Only
     * remembered here: which body it is is found when the wish command ends, or on the next look. Cheap.
     */
    public static void touched(AttributeMap map) {
        if (fitting || map == null || !MakeGate.anyWorld()) {
            return;
        }
        synchronized (TOUCHED) {
            TOUCHED.add(map);
        }
    }

    /** A body joined inside a wish's spawn scope and was admitted ({@code mob/WishMobLimit}). */
    public static void admitted(Entity e) {
        if (e instanceof LivingEntity && !(e instanceof Player) && MakeGate.anyWorld()) {
            synchronized (ADMITTED) {
                ADMITTED.add(e);
            }
        }
    }

    /**
     * A wish command just ended ({@code WishExecutor.runOne}): every body it brought in or changed is held to
     * {@code wisher}'s limits now (the tightest any player here is held to when nobody ran it), before it can act.
     */
    public static void afterWishCommand(MinecraftServer server, ServerPlayer wisher) {
        if (server == null || !MakeGate.anyWorld()) {
            return;
        }
        settle(server, wisher == null ? null : wisher.getUUID());
    }

    /** Fit what came in or was changed since the last look, as {@code owner}'s (null: whoever it remembers). */
    static int settle(MinecraftServer server, UUID owner) {
        List<Entity> admitted;
        synchronized (ADMITTED) {
            admitted = ADMITTED.isEmpty() ? List.of() : new ArrayList<>(ADMITTED);
            ADMITTED.clear();
        }
        Set<AttributeMap> touched;
        synchronized (TOUCHED) {
            touched = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            touched.addAll(TOUCHED);
            TOUCHED.clear();
        }
        int held = 0;
        for (Entity e : admitted) {
            if (e instanceof LivingEntity mob && mob.isAlive()) {
                held += fitNow(mob, owner) ? 1 : 0;
            }
        }
        if (!touched.isEmpty()) {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity e : level.getAllEntities()) {
                    if (e instanceof LivingEntity mob && !(e instanceof Player) && mob.isAlive()
                            && touched.contains(mob.getAttributes())) {
                        held += fitNow(mob, owner) ? 1 : 0;
                    }
                }
            }
        }
        return held;
    }

    /** One body a wish brought in or changed: a species creature fitted by its species, any other held. */
    private static boolean fitNow(LivingEntity mob, UUID owner) {
        String speciesId = LiveFit.speciesOf(mob);
        if (speciesId != null) {
            return !LiveFit.apply(mob, speciesId).isEmpty();
        }
        UUID whose = LiveFit.fitOwnerOf(mob) != null ? null : owner;   // the first wisher it was held for stays
        return !LiveFit.hold(mob, whose).isEmpty();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVERY_TICKS != 0 || !MakeGate.anyWorld()) {
            return;
        }
        try {
            look(server);
        } catch (RuntimeException e) {
            LOGGER.warn("live fit watch: {}", e.toString());
        }
    }

    /** One look: new arrivals and changes settled, stamps compared, every marked body over its limits fitted again. */
    static int look(MinecraftServer server) {
        int held = settle(server, null);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String stamp = MakeGate.limits(p.getUUID()).stamp();
            String was = STAMPS.get(p.getUUID());
            if (was == null) {
                STAMPS.put(p.getUUID(), stamp);
            } else if (!was.equals(stamp)) {
                // his limits changed and nobody said so (a world that answers by where he stands): fit his creatures
                // again, raised as well as lowered, and tell him once, exactly as a world's own change does
                LiveFit.refit(server, p.getUUID());
            }
        }
        Map<UUID, Map<String, Double>> perOwner = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof LivingEntity mob) || e instanceof Player || !mob.isAlive()
                        || !LiveFit.markedByTags(e.getTags()) && !com.moddingfromamod.world.Wished.isWished(e)) {
                    continue;
                }
                Map<String, Double> limits = LiveFit.heldLimits(mob, perOwner);
                if (!limits.isEmpty() && LiveFit.above(mob, limits)) {
                    fitNow(mob, null);
                    held++;
                }
            }
        }
        if (held > 0) {
            long now = System.currentTimeMillis();
            if (now - lastSaid > 10_000L) {
                lastSaid = now;
                LOGGER.info("live fit watch: {} wished bod(ies) had gone past this world's limits and are held to them",
                        held);
            }
        }
        return held;
    }

    /** {@link LiveFit#refit} fitted this player's creatures at this stamp: no second refit for the same change. */
    static void fittedAt(UUID player, String stamp) {
        if (player != null && stamp != null) {
            STAMPS.put(player, stamp);
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        synchronized (TOUCHED) {
            TOUCHED.clear();
        }
        synchronized (ADMITTED) {
            ADMITTED.clear();
        }
        STAMPS.clear();
    }
}
