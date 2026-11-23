package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <b>A finding compass — the half that touches the game.</b> The {@code locate}
 * effect tags him {@code mfam_locate_<place>}; at the end of the tick this runs vanilla's own search
 * for the nearest one ({@code /locate}'s structure search, or its 3-D biome search), points the real
 * compass in his hand at it through {@code lodestone_tracker} (not tracked, so no lodestone is
 * needed and a stock client draws the needle natively), tells him how far it is, and draws the way
 * in a line of sparkles. Nothing found is said, never faked.
 *
 * <p>⚠ <b>What a search costs, measured.</b> In the GameTest world, which generates no structures, a
 * village search at vanilla's radius took <b>59 s on the server thread</b> — the game frozen for a
 * minute for a click. In a real world (1.21.4, seed 1024, vanilla {@code /locate} over RCON, each
 * including ~0.4 s of round trip): village 1.3 s (352 blocks), trial chambers 0.4 s, ancient city
 * 0.6 s (3394 blocks), mansion 2.2 s (7651 blocks), fortress in the overworld 0.4 s (none); jungle
 * biome 0.65 s, lush caves 0.45 s. The radius counts PLACEMENT CELLS (a structure's spacing), not
 * chunks, which is why 48 still reaches a mansion 7.6 km away. So:
 * <ul>
 *   <li>a world that generates no structures answers "none" at once;</li>
 *   <li>a structure is searched {@link #STRUCTURE_CHUNKS} placement cells out, on the server thread (it
 *       generates structure starts, which is not safe anywhere else), and every search logs its
 *       time;</li>
 *   <li>a biome is searched OFF the server thread: it samples the climate noise and loads no
 *       chunk, and the answer is applied on a later tick.</li>
 * </ul>
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class Locate {

    /** E1's labels: the compass rows (syn-3-038, syn-5-038) name {@code locate_structure}. */
    public static final Set<String> PARTS = Set.of("locate_structure", "locate_biome");

    public static final String TAG = "mfam_locate_";

    /** How far a structure search reaches, in placement cells (vanilla's is 100). */
    public static final int STRUCTURE_CHUNKS = 48;
    /** How far a biome search reaches, in blocks (vanilla's is 6400). */
    public static final int BIOME_BLOCKS = 3200;

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final java.util.concurrent.ExecutorService BIOMES =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "moddy-locate-biome");
                t.setDaemon(true);
                return t;
            });
    private static final java.util.Queue<Runnable> DONE = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private Locate() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Runnable r;
        while ((r = DONE.poll()) != null) {
            r.run();
        }
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            for (String tag : List.copyOf(p.getTags())) {
                if (tag.startsWith(TAG)) {
                    p.removeTag(tag);
                    String suffix = tag.substring(TAG.length());
                    if (suffix.equals(LocateTargets.PLAYER) || suffix.startsWith(LocateTargets.PLAYER + ".")) {
                        askPlayer(p, LocateTargets.playerName(suffix));
                        continue;
                    }
                    LocateTargets.Target t = LocateTargets.of(suffix);
                    if (t != null) {
                        ask(p, t);
                    }
                }
            }
        }
    }

    /**
     * What a click does: a structure is searched now, a biome on the worker and answered on a later
     * tick. The biome worker never touches the player or the level's chunks.
     */
    public static void ask(ServerPlayer p, LocateTargets.Target t) {
        if (t.kind() == LocateTargets.Kind.STRUCTURE) {
            point(p, t);
            return;
        }
        ServerLevel level = p.level();
        BlockPos from = p.blockPosition();
        BIOMES.execute(() -> {
            long t0 = System.nanoTime();
            Optional<BlockPos> found;
            try {
                found = find(level, from, t);
            } catch (RuntimeException e) {
                LOG.warn("locate {} failed off-thread: {}", t.word(), e.toString());
                found = Optional.empty();
            }
            LOG.info("locate {} took {} ms off the server thread", t.word(), (System.nanoTime() - t0) / 1_000_000);
            Optional<BlockPos> answer = found;
            DONE.add(() -> {
                if (!p.isRemoved()) {
                    deliver(p, t, answer);
                }
            });
        });
    }

    /**
     * A compass at ANOTHER PLAYER: {@code name}, or the nearest other child when null —
     * re-read at every click, and only where this world's {@code player_reach} is open. Reading a
     * position is all it does: the other child is never touched. Answers what it pointed at, or null.
     */
    public static ServerPlayer askPlayer(ServerPlayer p, String name) {
        if (com.moddingfromamod.world.PlayerVisits.mode() != com.moddingfromamod.agent.PlayerReach.Mode.OPEN) {
            p.sendSystemMessage(Component.literal("✨ On this world a compass can't point at another player"
                    + " — that's for " + com.moddingfromamod.world.WorldRulesService.current().workshop() + "!"));
            com.moddingfromamod.agent.ActionLog.reach("locate", "refused",
                    com.moddingfromamod.agent.PlayerReach.WHY_REACH_OFF, name == null ? "nearest" : name, "", 0);
            return null;
        }
        ServerPlayer host = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer o : p.getServer().getPlayerList().getPlayers()) {
            if (o == p || !com.moddingfromamod.marshal.PlayerKind.isChild(o.getUUID())
                    || o.level() != p.level()) {
                continue;
            }
            if (name != null && !o.getScoreboardName().equalsIgnoreCase(name)) {
                continue;
            }
            double d = o.distanceToSqr(p);
            if (d < best) {
                best = d;
                host = o;
            }
        }
        if (host == null) {
            p.sendSystemMessage(Component.literal(name == null
                    ? "✨ There's nobody else here for the compass to find."
                    : "✨ " + name + " isn't anywhere the compass can find from here."));
            return null;
        }
        BlockPos at = host.blockPosition();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = p.getItemInHand(hand);
            if (held.is(Items.COMPASS)) {
                held.set(DataComponents.LODESTONE_TRACKER,
                        new LodestoneTracker(Optional.of(GlobalPos.of(host.level().dimension(), at)), false));
                break;
            }
        }
        int blocks = (int) Math.round(Math.sqrt(p.blockPosition().distSqr(new BlockPos(at.getX(), p.getBlockY(), at.getZ()))));
        p.sendSystemMessage(Component.literal("✨ This way to " + host.getScoreboardName() + " — about "
                + blocks + " blocks!"));
        com.moddingfromamod.agent.ActionLog.reach("locate", "allowed", "point", host.getScoreboardName(), "", 0);
        sparkles(p.level(), p, at);
        return host;
    }

    /** Find the nearest one here and now, point his compass at it, and tell him. */
    public static Optional<BlockPos> point(ServerPlayer p, LocateTargets.Target t) {
        long t0 = System.nanoTime();
        Optional<BlockPos> found = find(p.level(), p.blockPosition(), t);
        LOG.info("locate {} took {} ms", t.word(), (System.nanoTime() - t0) / 1_000_000);
        deliver(p, t, found);
        return found;
    }

    private static void deliver(ServerPlayer p, LocateTargets.Target t, Optional<BlockPos> found) {
        ServerLevel level = p.level();
        if (found.isEmpty()) {
            p.sendSystemMessage(Component.literal(LocateTargets.notFound(t)));
            return;
        }
        BlockPos at = found.get();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = p.getItemInHand(hand);
            if (held.is(Items.COMPASS)) {
                held.set(DataComponents.LODESTONE_TRACKER,
                        new LodestoneTracker(Optional.of(GlobalPos.of(level.dimension(), at)), false));
                break;
            }
        }
        int blocks = (int) Math.round(Math.sqrt(p.blockPosition().distSqr(new BlockPos(at.getX(), p.getBlockY(), at.getZ()))));
        p.sendSystemMessage(Component.literal(LocateTargets.found(t, blocks)));
        sparkles(level, p, at);
    }

    /** Vanilla's own search for the nearest place of this kind. */
    public static Optional<BlockPos> find(ServerLevel level, BlockPos from, LocateTargets.Target t) {
        if (t.kind() == LocateTargets.Kind.STRUCTURE) {
            if (!level.getServer().getWorldData().worldGenOptions().generateStructures()) {
                return Optional.empty();   // nothing to find, and searching for it froze the game 59 s
            }
            net.minecraft.core.HolderGetter<Structure> reg = level.holderLookup(Registries.STRUCTURE);
            List<Holder<Structure>> hs = new ArrayList<>();
            for (String id : t.ids()) {
                if (id.startsWith("#")) {
                    reg.get(TagKey.create(Registries.STRUCTURE, ResourceLocation.parse(id.substring(1))))
                            .ifPresent(set -> set.forEach(hs::add));
                } else {
                    reg.get(ResourceKey.create(Registries.STRUCTURE, ResourceLocation.parse(id))).ifPresent(hs::add);
                }
            }
            if (hs.isEmpty()) {
                return Optional.empty();
            }
            Pair<BlockPos, Holder<Structure>> r = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, HolderSet.direct(hs), from, STRUCTURE_CHUNKS, false);
            return r == null ? Optional.empty() : Optional.of(r.getFirst());
        }
        Predicate<Holder<Biome>> is = h -> {
            for (String id : t.ids()) {
                if (id.startsWith("#") ? h.is(TagKey.create(Registries.BIOME, ResourceLocation.parse(id.substring(1))))
                        : h.is(ResourceLocation.parse(id))) {
                    return true;
                }
            }
            return false;
        };
        Pair<BlockPos, Holder<Biome>> r = level.findClosestBiome3d(is, from, BIOME_BLOCKS, 32, 64);
        return r == null ? Optional.empty() : Optional.of(r.getFirst());
    }

    /** A line of sparkles from him toward it, eight blocks long: the way to go, for a few seconds. */
    private static void sparkles(ServerLevel level, ServerPlayer p, BlockPos at) {
        Vec3 eye = p.position().add(0, 1.2, 0);
        Vec3 dir = new Vec3(at.getX() + 0.5 - eye.x, 0, at.getZ() + 0.5 - eye.z);
        if (dir.lengthSqr() < 1e-6) {
            return;
        }
        dir = dir.normalize();
        for (int i = 1; i <= 16; i++) {
            Vec3 q = eye.add(dir.scale(i * 0.5));
            level.sendParticles(ParticleTypes.END_ROD, q.x, q.y, q.z, 2, 0.02, 0.02, 0.02, 0.0);
        }
    }
}
