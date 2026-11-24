package com.moddingfromamod.item;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>A wish's lightning never strikes the one who used it</b>. A walkthrough's Lightning Sword hit a zombie
 * and the bolt — summoned AT the zombie, two blocks away — hit him too: a bolt's strike reaches about three blocks, a
 * sword about two. All 7 of the run's near-deaths were his own sword.
 *
 * <p>Two ways a player is marked, for {@link #SPARE_TICKS} ticks:
 * <ul>
 *   <li>the {@code lightning} item effect tags {@code @s} with {@link #TAG} before it strikes (items/lightning.cbs) —
 *       every shape where {@code @s} is the player who used it (a swing, a right-click, a machine's pull);</li>
 *   <li>{@link WishMeleeHit} marks the player who swung a wish item before its function runs — which also covers every
 *       lightning sword made before the tag, whose datapack is already compiled.</li>
 * </ul>
 * A marked player is not struck ({@link EntityStruckByLightningEvent} cancelled for him alone); everything else the bolt
 * reaches still is, so the sword still zaps what it hits. A storm's lightning in that second is spared too — a second.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WishBoltGuard {

    /** The tag items/lightning.cbs puts on the player who used the item. */
    public static final String TAG = "moddy_bolt_maker";
    /** A bolt strikes over its first few ticks; a second is ample. */
    public static final int SPARE_TICKS = 20;

    private static final Map<UUID, Long> UNTIL = new ConcurrentHashMap<>();

    private WishBoltGuard() {
    }

    /** He is about to use a wish item: spare him from its lightning for a second. */
    public static void mark(Player p) {
        MinecraftServer server = serverOf(p);
        if (server != null) {
            UNTIL.put(p.getUUID(), server.getTickCount() + (long) SPARE_TICKS);
        }
    }

    static boolean spared(Player p) {
        Long until = UNTIL.get(p.getUUID());
        MinecraftServer server = serverOf(p);
        return p.entityTags().contains(TAG) || until != null && server != null
                && server.getTickCount() <= until;
    }

    /** The server a player is on; only a ServerPlayer has one on the Player type in 1.21.10. */
    private static MinecraftServer serverOf(Player p) {
        return p instanceof ServerPlayer sp ? sp.level().getServer() : null;
    }

    @SubscribeEvent
    public static void onStruck(EntityStruckByLightningEvent event) {
        if (event.getEntity() instanceof Player p && spared(p)) {
            event.setCanceled(true);
            p.clearFire();
        }
    }

    /** The tag the datapack set becomes a mark with an end, and the tag goes: a tag never outlives its second. */
    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        long now = event.getServer().getTickCount();
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            if (p.entityTags().contains(TAG)) {
                UNTIL.merge(p.getUUID(), now + SPARE_TICKS, Math::max);
                p.removeTag(TAG);
            }
        }
        UNTIL.values().removeIf(t -> t < now);
    }
}
