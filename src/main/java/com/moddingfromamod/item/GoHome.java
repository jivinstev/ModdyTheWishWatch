package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Set;

/**
 * <b>He goes home (T6F follow-up).</b>
 *
 * <p>HOME is the one a child already has in vanilla: the bed he slept in (or a respawn anchor, or a
 * {@code /spawnpoint}), and the world's spawn if he has none. It is not a new concept to keep: a
 * wish-made "home" would be a second place he has to remember setting, and the bed is the one he
 * already knows he goes back to.
 *
 * <p>The {@code teleport_home} effect's function tags him {@code mfam_go_home}. On a real ender
 * pearl that function runs at the landing, as him, BEFORE the pearl teleports him — so the pearl's
 * own teleport (and its fall damage) is cancelled here for a tagged thrower, and the tick takes him
 * home. The tag is always taken off.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class GoHome {

    /** E1's label (tools/parts-coverage.py reads this set). */
    public static final Set<String> PARTS = Set.of("teleport_home");

    public static final String TAG = "mfam_go_home";

    private GoHome() {
    }

    /** The pearl he threw would put him where it landed; this wish sends him home instead. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPearlLands(EntityTeleportEvent.EnderPearl event) {
        if (event.getPlayer().entityTags().contains(TAG)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            if (p.entityTags().contains(TAG)) {
                p.removeTag(TAG);
                sendHome(p);
            }
        }
    }

    /** Where home is for him, and the level it is in. */
    public record Home(ServerLevel level, Vec3 at, boolean isHisOwn) {
    }

    public static Home homeOf(ServerPlayer p) {
        var server = ((ServerLevel) p.level()).getServer();
        BlockPos respawn = com.moddingfromamod.compat.Respawn.pos(p);
        ResourceKey<Level> dim = com.moddingfromamod.compat.Respawn.dimension(p);
        ServerLevel level = respawn == null || server == null ? null : server.getLevel(dim);
        if (level != null) {
            BlockState state = level.getBlockState(respawn);
            if (state.getBlock() instanceof BedBlock) {
                var stand = BedBlock.findStandUpPosition(EntityTypes.PLAYER, level, respawn,
                        state.getValue(BedBlock.FACING), com.moddingfromamod.compat.Respawn.angle(p));
                if (stand.isPresent()) {
                    return new Home(level, stand.get(), true);
                }
            }
            return new Home(level, Vec3.atBottomCenterOf(respawn.above(state.isAir() ? 0 : 1)), true);
        }
        ServerLevel overworld = server == null ? (ServerLevel) p.level() : server.overworld();
        BlockPos spawn = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                overworld.getRespawnData().pos());
        return new Home(overworld, Vec3.atBottomCenterOf(spawn), false);
    }

    public static void sendHome(ServerPlayer p) {
        Home h = homeOf(p);
        com.moddingfromamod.compat.Teleports.player(p, h.level(), h.at().x, h.at().y, h.at().z,
                p.getYRot(), p.getXRot());
        p.fallDistance = 0;
    }
}
