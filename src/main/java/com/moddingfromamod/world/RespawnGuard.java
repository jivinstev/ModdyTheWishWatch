package com.moddingfromamod.world;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.ChatEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A player is never stuck dying on his own base</b>. A player's island was covered in lava;
 * every respawn put him back in it and he died again before he could open the watch. Three parts, on any
 * world with bases (an island module's islands; nothing here names a module):
 * <ol>
 *   <li><b>A grace</b> after every respawn on his own base: {@value #GRACE_TICKS} ticks of Fire Resistance
 *       and Resistance II, and his fire put out — long enough to open the watch or click a link.</li>
 *   <li><b>A rescue pad</b> when his home is unsafe and nowhere near is safe either
 *       ({@link #rescuePad}): a 3×3 of glass in clear air above his home, on his own base. Placed only into
 *       air, never over a block.</li>
 *   <li><b>The loop offer</b>: {@link RespawnLoop#DEATHS} deaths on his base inside two minutes, and the
 *       next respawn says so with a link that works without the watch — {@code /moddy rescue}.</li>
 * </ol>
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class RespawnGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("mfam-respawn");

    /** Ten seconds. */
    public static final int GRACE_TICKS = 200;
    /** How far above his home the pad is looked for. */
    static final int PAD_REACH_UP = 48;

    static final RespawnLoop LOOP = new RespawnLoop();
    private static final Set<UUID> OFFER = ConcurrentHashMap.newKeySet();

    /** The line the loop offer says, and the words its link carries (a test reads both). */
    public static final String LOOP_LINE = "Uh oh, your island keeps hurting you! ";
    public static final String SAVE_ME = "[Save me]";
    public static final String RESCUED_LINE = "Moddy pulled you out! Here's a safe spot: you're fireproof for a few seconds.";
    public static final String PAD_LINE = "Your island isn't safe right now, so Moddy made you a glass platform above it.";
    public static final String NOTHING_TO_RESCUE = "You're safe here: Moddy has nothing to rescue you from.";

    private RespawnGuard() {
    }

    // ── the grace ──────────────────────────────────────────────────────────────────────────────

    /** Fire out, fireproof and tougher for {@value #GRACE_TICKS} ticks. Shown, so he can see why. */
    public static void grace(ServerPlayer p) {
        p.clearFire();
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, GRACE_TICKS, 0, false, true));
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, GRACE_TICKS, 1, false, true));
    }

    /**
     * After a respawn (WorldModuleHost.onRespawn, once he has been sent home): a grace when he is on his
     * own base, and the loop offer when one is owed.
     */
    public static void afterRespawn(ServerPlayer p, Bases b) {
        Base base = b == null ? null : b.baseOf(p.getUUID());
        if (base == null || !base.holds(p.level().dimension().location().toString(), p.getX(), p.getZ())) {
            return;
        }
        grace(p);
        if (OFFER.remove(p.getUUID())) {
            offer(p);
        }
    }

    /** The chat line with the way out. Public for a GameTest. */
    public static void offer(ServerPlayer p) {
        MutableComponent link = Component.literal(SAVE_ME).withStyle(Style.EMPTY
                .withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(ChatEvents.click(ClickEvent.Action.RUN_COMMAND, rescueCommand()))
                .withHoverEvent(ChatEvents.hoverText(Component.literal("Moddy moves you somewhere safe"))));
        p.sendSystemMessage(Component.literal(LOOP_LINE).withStyle(ChatFormatting.GOLD).append(link)
                .append(Component.literal(" (or type " + rescueCommand() + ")").withStyle(ChatFormatting.GRAY)));
        WorldModuleHost.sayHelp(p);                                              //: or start over (a module's)
        LOGGER.info("respawn guard: {} died {} times on their base in {} s; offered the rescue",
                p.getScoreboardName(), RespawnLoop.DEATHS, RespawnLoop.WINDOW_MS / 1000);
    }

    public static String rescueCommand() {
        return "/" + com.moddingfromamod.wish.ChildCommands.MODDY + " " + com.moddingfromamod.wish.ChildCommands.RESCUE;
    }

    /** A death ON his base counts toward the loop. Public so a GameTest drives the event's path. */
    public static void died(ServerPlayer p, Bases b, long nowMs) {
        Base base = b == null ? null : b.baseOf(p.getUUID());
        if (base == null || !base.holds(p.level().dimension().location().toString(), p.getX(), p.getZ())) {
            return;
        }
        if (LOOP.died(p.getUUID(), nowMs)) {
            OFFER.add(p.getUUID());
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            died(p, WorldModuleHost.liveBases(), System.currentTimeMillis());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LOOP.forget(event.getEntity().getUUID());
        OFFER.remove(event.getEntity().getUUID());
    }

    /** Forget a test's player. */
    public static void forgetForTest(UUID id) {
        LOOP.forget(id);
        OFFER.remove(id);
    }

    // ── /moddy rescue ──────────────────────────────────────────────────────────────────────────

    /**
     * {@code /moddy rescue}: to the nearest safe spot by his home (a pad when there is none), then the
     * grace. Returns 1 when he was moved.
     */
    public static int rescue(ServerPlayer p) {
        Bases b = WorldModuleHost.liveBases();
        return rescue(p, b);
    }

    public static int rescue(ServerPlayer p, Bases b) {
        Base base = b == null ? null : b.baseOf(p.getUUID());
        var server = p.getServer();
        if (base == null || server == null) {
            p.sendSystemMessage(Component.literal(NOTHING_TO_RESCUE).withStyle(ChatFormatting.AQUA));
            return 0;
        }
        WorldModuleHost.goHome(p, b, server.overworld(), base.x() + 0.5, base.homeY(), base.z() + 0.5);
        grace(p);
        LOOP.forget(p.getUUID());
        p.sendSystemMessage(Component.literal(RESCUED_LINE).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    // ── the rescue pad ─────────────────────────────────────────────────────────────────────────

    /**
     * A 3×3 glass pad in clear air above {@code (x, y, z)}: the first height (up to {@value #PAD_REACH_UP}
     * above) where a 5×5 column five blocks tall holds nothing but air — so no lava can flow onto it from
     * beside or above. Glass goes only into air. Returns where to stand, or null when no such air exists.
     */
    public static BlockPos rescuePad(ServerLevel level, double x, double y, double z) {
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        int top = Math.min(level.getMaxY() - 3, (int) Math.floor(y) + PAD_REACH_UP);
        for (int py = (int) Math.floor(y) + 3; py <= top; py++) {
            if (!clearAir(level, cx, py - 1, cz)) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, py - 1, cz + dz), Blocks.GLASS.defaultBlockState());
                }
            }
            LOGGER.info("respawn guard: a rescue pad at {} {} {}", cx, py - 1, cz);
            return new BlockPos(cx, py, cz);
        }
        return null;
    }

    /** A 5×5 column from {@code y0} up five blocks: all air, no fluid. */
    private static boolean clearAir(ServerLevel level, int cx, int y0, int cz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy < 5; dy++) {
                    BlockState s = level.getBlockState(new BlockPos(cx + dx, y0 + dy, cz + dz));
                    if (!s.isAir() || !s.getFluidState().isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
