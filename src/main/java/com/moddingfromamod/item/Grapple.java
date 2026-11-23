package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>A hook that pulls him there.</b> The {@code grapple} effect, thrown on a real
 * throwable (on_throw_land), tags him where it lands (WishThrowImpact records the point, per thrower);
 * at the end of the tick this sets his motion toward it in one arc ({@link GrappleRule}) and marks it hurt so the velocity
 * packet reaches his client — a stock client moves him, since a player's movement is his client's.
 * His next landing within {@link #SAFE_TICKS} takes no fall damage: a hook that yanks him onto a roof
 * must not break his legs on it.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class Grapple {

    /** E1's labels: the grappling-hook and pulling-rod rows. */
    public static final Set<String> PARTS = Set.of("grapple");

    public static final String TAG = "mfam_grapple";
    static final int SAFE_TICKS = 100;

    private static final Map<UUID, Long> FLUNG = new ConcurrentHashMap<>();

    private Grapple() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            if (p.getTags().contains(TAG)) {
                p.removeTag(TAG);
                pull(p);
            }
        }
    }

    /** How recent his landing must be to pull him: the tag is answered at the end of the same tick. */
    static final long FRESH = 5;

    /** Fling him at where HIS hook landed; the velocity, or null when there was none. */
    public static Vec3 pull(ServerPlayer p) {
        Vec3 at = WishThrowImpact.lastLanding(p.getUUID(), p.level().getGameTime(), FRESH);
        if (at == null) {
            return null;
        }
        // hurtMarked (in toward): the server sends ClientboundSetEntityMotionPacket, so his client flies.
        return toward(p, landing(p.level(), at));
    }

    /** How far up a wall the hook's point is climbed to find its top. */
    static final int CLIMB = 6;

    /**
     * Where to land for a hook that stopped at {@code at}. On open ground, there. Against a WALL — the
     * hook is nudged out of the face it hit, so a solid block sits beside it — the top of that wall,
     * standing on it, if there is one within {@link #CLIMB} blocks: "yank me onto the roof" is the
     * wish, and the first film showed a pull to the exact point flying him into the side of
     * the house and sliding him down it.
     */
    public static Vec3 landing(net.minecraft.server.level.ServerLevel level, Vec3 at) {
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(at);
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            net.minecraft.core.BlockPos wall = here.relative(d);
            if (!solid(level, wall)) {
                continue;
            }
            for (int up = 0; up <= CLIMB; up++) {
                net.minecraft.core.BlockPos top = wall.above(up);
                if (solid(level, top) && !solid(level, top.above()) && !solid(level, top.above(2))) {
                    // A block and a half IN from the edge, so he lands on the roof rather than its lip
                    // — the second film arrived exactly at the edge and fell back down the wall.
                    net.minecraft.core.BlockPos in = top.relative(d);
                    boolean deeper = solid(level, in) && !solid(level, in.above()) && !solid(level, in.above(2));
                    net.minecraft.core.BlockPos on = deeper ? in : top;
                    return new Vec3(on.getX() + 0.5, on.getY() + 1, on.getZ() + 0.5);
                }
            }
        }
        return at;
    }

    private static boolean solid(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        return level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
    }

    /**
     * The rod half: he reels in a rod carrying {@code grapple} while its bobber rests on a block —
     * not in water, not hooked on anything — and he is pulled to it. Vanilla's reel-in then runs as
     * always (the bobber comes back).
     */
    @SubscribeEvent
    public static void onReel(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.fishing == null) {
            return;
        }
        var data = event.getItemStack().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null || !data.copyTag().getBooleanOr(com.moddingfromamod.datapack.ItemProperties.GRAPPLE_DATA, false)) {
            return;
        }
        var hook = p.fishing;
        boolean resting = !hook.isInWater() && hook.getHookedIn() == null
                && (hook.onGround() || hook.horizontalCollision
                        || !p.level().getBlockState(hook.blockPosition().below()).isAir());
        if (resting) {
            toward(p, hook.position());
        }
    }

    /** Fling him at a point; the velocity, or null when it is out of reach or on him. */
    public static Vec3 toward(ServerPlayer p, Vec3 at) {
        double[] v = GrappleRule.velocity(p.getX(), p.getY(), p.getZ(), at.x, at.y, at.z, p.onGround());
        if (v == null) {
            return null;
        }
        Vec3 motion = new Vec3(v[0], v[1], v[2]);
        p.setDeltaMovement(motion);
        p.hurtMarked = true;
        p.resetFallDistance();
        FLUNG.put(p.getUUID(), p.level().getGameTime());
        return motion;
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        Long at = FLUNG.get(event.getEntity().getUUID());
        if (at != null && event.getEntity().level().getGameTime() - at <= SAFE_TICKS) {
            event.setDamageMultiplier(0);
            FLUNG.remove(event.getEntity().getUUID());
        }
    }
}
