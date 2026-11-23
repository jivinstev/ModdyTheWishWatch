package com.moddingfromamod.wish.reveal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.wish.WishCapture;
import com.moddingfromamod.wish.WishPlan;
import com.moddingfromamod.wish.WishProgress;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * <b>A wish orb you can find from anywhere</b>.
 *
 * <p>For every wish being granted that has a locked spot ({@code WishRun.anchor}, the same FOCUS the
 * build lands on, never the live player), this keeps a floating orb there, drawn in display entities
 * (see {@link OrbShape}). The particle beam in {@code WishProgress} stays; this is the solid landmark
 * next to it, and the one thing still visible on "decreased particles".
 *
 * <p><b>Three rules it keeps.</b>
 * <ol>
 *   <li><b>Gone the moment the wish is.</b> Every tick it asks the marshal which wishes are live, and an
 *   orb whose wish is not (finished, stopped, failed) is removed. A restart sweeps every tagged
 *   display in a loaded chunk, and one saved with an UNLOADED chunk is refused when that chunk
 *   loads ({@link #onJoin}), so neither a crash nor a child walking away can leave one
 *   floating.</li>
 *   <li><b>Never in the agent's photograph.</b> A camera client is never sent them ({@link FxVeil});
 *   when the child's own eyes take the picture, the orbs near him are taken down and come back
 *   afterwards — except at a wish's end, when it stays down for the reveal.</li>
 *   <li><b>A few displays.</b> One core, one shell, one column and at most {@link OrbShape#MAX_RINGS}
 *   beads: ten per wish.</li>
 * </ol>
 * A reveal takes the orb over ({@link #release}): the orb becomes the start of the reveal instead of
 * vanishing and something else appearing.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WishOrbs {

    /** Off switch for A/B films and for a server that wants particles only. */
    public static volatile boolean enabled = true;

    static final BlockState CORE = Blocks.AMETHYST_BLOCK.defaultBlockState();
    static final BlockState SHELL = Blocks.MAGENTA_STAINED_GLASS.defaultBlockState();
    static final BlockState COLUMN = Blocks.WHITE_STAINED_GLASS.defaultBlockState();

    /** The pillar: a white core with a cyan glowing outline, and the star at its foot. */
    /** The arcs (white-cyan) and the star on the ground (cyan, see-through), ARGB. */
    static final int ARC_LIGHT = 0xF2DDFAFF;
    static final int STAR_LIGHT = 0x804FE6FF;
    /** The beam's three layers, ARGB: a white core, a cyan halo, a faint haze. */
    static final int BEAM_CORE = 0xFFF4FDFF;
    static final int BEAM_HALO = 0x9A4FE6FF;
    static final int BEAM_HAZE = 0x3A4FE6FF;

    /** What the helix is made of, in order up each strand: things that glow and things you dig for. */
    static final BlockState[] HELIX = {
            Blocks.GLOWSTONE.defaultBlockState(),
            Blocks.DIAMOND_BLOCK.defaultBlockState(),
            Blocks.AMETHYST_BLOCK.defaultBlockState(),
            Blocks.GOLD_BLOCK.defaultBlockState(),
            Blocks.SEA_LANTERN.defaultBlockState(),
            Blocks.EMERALD_BLOCK.defaultBlockState(),
            Blocks.DIAMOND_ORE.defaultBlockState(),
            Blocks.REDSTONE_BLOCK.defaultBlockState(),
    };
    static final BlockState BEAD_DARK = Blocks.GRAY_STAINED_GLASS.defaultBlockState();
    static final BlockState BEAD_LIT = Blocks.GOLD_BLOCK.defaultBlockState();

    /** One wish's pillar. Its displays are empty while it is hidden for a photograph. */
    static final class Orb {
        final UUID wishId;
        final ServerLevel level;
        /** Where a reveal starts: {@link OrbShape#ORB_HEIGHT} up the pillar. Moves while it glides. */
        double x;
        double y;
        double z;
        /** Where it is going: the run's spot. Equal to x/y/z when it is standing still. */
        double tx, ty, tz;
        /** The glide: where it set off from, when, and for how long. {@code glideTicks == 0}: none. */
        double[] glideFrom;
        long glideStart;
        int glideTicks;
        /** Total glides, for Gate B: a correction is one glide, never a rebuild. */
        int glides;
        /** Everything stands on this one spot, the pillar's foot; only shapes move. */
        final List<Entity> pillar = new ArrayList<>();
        final List<Entity> helix = new ArrayList<>();
        final List<Entity> arcs = new ArrayList<>();
        double angle;

        Orb(UUID wishId, ServerLevel level, double x, double y, double z) {
            this.wishId = wishId;
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tx = x;
            this.ty = y;
            this.tz = z;
        }

        /** Set off toward a new spot from wherever it is now (even mid-glide). */
        void glideTo(double nx, double ny, double nz, long now) {
            double dx = nx - x, dy = ny - y, dz = nz - z;
            glideFrom = new double[]{x, y, z};
            glideStart = now;
            glideTicks = OrbShape.glideTicks(Math.sqrt(dx * dx + dy * dy + dz * dz));
            tx = nx;
            ty = ny;
            tz = nz;
            glides++;
        }

        /** Step the glide; true when the pillar moved this tick (its displays must follow). */
        boolean stepGlide(long now) {
            if (glideTicks == 0) {
                return false;
            }
            double t = (now - glideStart) / (double) glideTicks;
            double[] p = OrbShape.glide(glideFrom, new double[]{tx, ty, tz}, t);
            x = p[0];
            y = p[1];
            z = p[2];
            if (t >= 1) {
                x = tx;
                y = ty;
                z = tz;
                glideTicks = 0;
                glideFrom = null;
            }
            return true;
        }

        double foot() {
            return y - OrbShape.ORB_HEIGHT;
        }

        boolean shown() {
            return !pillar.isEmpty();
        }

        List<Entity> all() {
            List<Entity> out = new ArrayList<>(pillar);
            out.addAll(helix);
            out.addAll(arcs);
            return out;
        }
    }

    private static final Map<UUID, Orb> ORBS = new ConcurrentHashMap<>();

    /** Films only: orbs held up with no run behind them, and the progress each should show. */
    private static final Map<UUID, float[]> PINS = new ConcurrentHashMap<>();

    private WishOrbs() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 2 != 0) {
            return;
        }
        tick(server, Marshal.global().live(), server.getTickCount());
    }

    /** A restart leaves nothing floating: every orb and reveal display from before is swept. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ORBS.clear();
        PINS.clear();
        HELD.clear();
        REVEALED.clear();
        Reveals.clear();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            Fx.sweep(level, Fx.TAG);
        }
    }

    /**
     * <b>A wish display that comes back from disk is never let back in</b>.
     *
     * <p>Every live orb and reveal display is made by {@link Fx} with {@code addFreshEntity} in THIS
     * server's lifetime and held by reference, so none of them ever joins "loaded from disk". One that
     * does is a copy its chunk saved: the restart sweep above only sees loaded chunks, and a pillar
     * whose chunk unloaded mid-wish (the child walked off) leaves its tracked references dead while
     * the saved copy waits in the region file. Nothing tracks that copy, so nothing would ever take
     * it down — the pillar a child found still standing the next session. Refusing it at the door
     * cannot kill a live pillar: a live one never joins from disk, and an orb whose displays were
     * unloaded under it rebuilds itself ({@link #animate}).
     */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (staleFromDisk(event.getEntity(), event.loadedFromDisk())) {
            event.setCanceled(true);
        }
    }

    /** True for a wish display loaded back from a chunk: never live, always refused. */
    public static boolean staleFromDisk(Entity e, boolean loadedFromDisk) {
        return loadedFromDisk && e != null && e.getTags().contains(Fx.TAG);
    }

    /** One pass. Public so a GameTest can drive it with the runs it wants to be live. */
    public static void tick(MinecraftServer server, List<WishRun> live, long now) {
        Set<UUID> keep = new HashSet<>();
        // a camera client is never sent the pillar (FxVeil), so only a child lending his OWN
        // eyes takes it down — and only near him, never every pillar in the world.
        List<net.minecraft.server.level.ServerPlayer> eyes = WishCapture.ownEyesShooting(server);
        if (enabled) {
            Set<UUID> liveIds = new HashSet<>();
            for (WishRun run : live) {
                liveIds.add(run.wishId());
            }
            REVEALED.retainAll(liveIds);
            for (WishRun run : live) {
                var spot = run.anchor();
                if (spot == null || REVEALED.contains(run.wishId())) {
                    // A reveal has taken this pillar over: it does not come back for the rest of the
                    // wish (— it used to be rebuilt on the next pass until finish_wish).
                    continue;
                }
                ServerLevel level = levelOf(server, spot.dimension());
                if (level == null) {
                    continue;
                }
                double x = spot.x() + 0.5, y = spot.y() + OrbShape.ORB_HEIGHT, z = spot.z() + 0.5;
                Orb o = ORBS.get(run.wishId());
                if (o != null && o.level != level) {
                    take(o);                   // another dimension: nothing to glide through
                    ORBS.remove(run.wishId());
                    o = null;
                }
                if (o == null) {
                    o = new Orb(run.wishId(), level, x, y, z);
                    ORBS.put(run.wishId(), o);
                } else if (o.tx != x || o.ty != y || o.tz != z) {
                    // the spot was corrected. The pillar GLIDES there, visibly, rather than
                    // being torn down here and rebuilt there.
                    o.glideTo(x, y, z, now);
                }
                keep.add(run.wishId());
                if (hold(o, eyes)) {
                    continue;
                }
                WishPlan plan = WishProgress.planFor(run.wishId());
                animate(o, plan == null ? 1 : plan.size(), plan == null ? 0f : plan.fraction(), now);
            }
        }
        for (Map.Entry<UUID, float[]> pin : PINS.entrySet()) {
            Orb o = ORBS.get(pin.getKey());
            if (o == null) {
                continue;
            }
            keep.add(pin.getKey());
            if (!hold(o, eyes)) {
                animate(o, (int) pin.getValue()[0], pin.getValue()[1], now);
            }
        }
        for (UUID id : new ArrayList<>(ORBS.keySet())) {
            if (!keep.contains(id)) {
                take(ORBS.remove(id));
                HELD.remove(id);
            }
        }
    }

    /**
     * Wishes at their END: {@code finish_building} has started, so once this pillar is down
     * it stays down until the reveal takes it — "once the pillar comes down it must not come back".
     * With a camera client it never comes down at all until the reveal.
     */
    private static final Set<UUID> HELD = ConcurrentHashMap.newKeySet();

    /** Wishes whose pillar a reveal has taken over: never rebuilt while the wish lives on. */
    private static final Set<UUID> REVEALED = ConcurrentHashMap.newKeySet();

    /** The wish is at its end: if its pillar is taken down from here, it is not put back. */
    public static void holdForReveal(UUID wishId) {
        if (wishId != null) {
            HELD.add(wishId);
        }
    }

    /** Is this pillar staying down this tick? Takes it down when a child's own eyes are shooting near it. */
    private static boolean hold(Orb o, List<net.minecraft.server.level.ServerPlayer> eyes) {
        if (!eyes.isEmpty() && FxVeil.hiddenAt(eyes, o.level, o.x, o.z)) {
            take(o);
            return true;
        }
        return HELD.contains(o.wishId) && !o.shown();
    }

    /**
     * Hand the orb to a reveal: its displays are removed and its centre returned, so the reveal
     * starts where he was looking. Null when this wish has no orb.
     */
    public static double[] release(UUID wishId) {
        if (wishId != null) {
            PINS.remove(wishId);
            HELD.remove(wishId);
            REVEALED.add(wishId);
        }
        Orb o = wishId == null ? null : ORBS.remove(wishId);
        if (o == null) {
            return null;
        }
        take(o);
        return new double[]{o.x, o.y, o.z};
    }

    /** Test seam: show an orb, standing on the foot {@code (x, y, z)}, for a wish with no run. */
    public static void showForTest(ServerLevel level, UUID wishId, double x, double y, double z,
                                   int steps, float fraction, long now) {
        Orb o = ORBS.computeIfAbsent(wishId, id -> new Orb(id, level, x, y + OrbShape.ORB_HEIGHT, z));
        animate(o, steps, fraction, now);
    }

    /**
     * Film seam: hold an orb up on the foot {@code (x, y, z)} with no run behind it, showing {@code steps} steps at
     * {@code fraction} done; call again to move the progress on. {@link #release} ends it.
     */
    public static void pinForFilm(ServerLevel level, UUID wishId, double x, double y, double z,
                                  int steps, float fraction) {
        ORBS.computeIfAbsent(wishId, id -> new Orb(id, level, x, y + OrbShape.ORB_HEIGHT, z));
        PINS.put(wishId, new float[]{steps, fraction});
    }

    /**
     * A served wish (a button, a cached wish, a starter) has no run for {@link #tick} to read, so its
     * pillar is pinned like a film's: stood on the foot {@code (x, y, z)} and moved on with
     * {@link #pinServed} again. {@link #release} hands it to the reveal.
     */
    public static void pinServed(ServerLevel level, UUID wishId, double x, double y, double z,
                                 int steps, float fraction) {
        pinForFilm(level, wishId, x, y, z, steps, fraction);
    }

    /** Drop a pinned pillar with no reveal (the served wish was stopped). */
    public static void drop(UUID wishId) {
        if (wishId == null) {
            return;
        }
        PINS.remove(wishId);
        HELD.remove(wishId);
        Orb o = ORBS.remove(wishId);
        if (o != null) {
            take(o);
        }
    }

    /** Is this wish's pillar drawn right now (Gate B)? */
    public static boolean shown(UUID wishId) {
        Orb o = wishId == null ? null : ORBS.get(wishId);
        return o != null && o.shown();
    }

    /** How many times this wish's pillar has glided to a corrected spot (Gate B), or -1 with no orb. */
    public static int glides(UUID wishId) {
        Orb o = wishId == null ? null : ORBS.get(wishId);
        return o == null ? -1 : o.glides;
    }

    /** Where this wish's pillar foot is right now, or null (Gate B, films). */
    public static double[] footOf(UUID wishId) {
        Orb o = wishId == null ? null : ORBS.get(wishId);
        return o == null ? null : new double[]{o.x, o.foot(), o.z};
    }

    /** How many orbs exist (Gate B). */
    public static int count() {
        return ORBS.size();
    }

    private static void animate(Orb o, int steps, float fraction, long now) {
        String tag = Fx.wishTag(o.wishId);
        if (o.shown() && unloadedUnder(o)) {
            // its chunk unloaded (the child walked away) and took these displays with it.
            // The saved copies are refused when the chunk loads (onJoin), so let them go (take also
            // discards any still standing, if something else removed only some) and build the
            // pillar again once the chunk is back.
            take(o);
        }
        // Nobody is near: build nothing a chunk would save. (Not areEntitiesLoaded: a chunk that is
        // loaded while its saved entities are still being read answers false, and a pillar must
        // stand there; the disk copies that arrive after it are refused by onJoin.)
        if (!o.shown() && !o.level.hasChunkAt(net.minecraft.core.BlockPos.containing(o.x, o.foot(), o.z))) {
            return;
        }
        if (o.stepGlide(now) && o.shown()) {
            double gy = o.foot();
            for (Entity e : o.all()) {
                if (e != null) {
                    e.snapTo(o.x, gy, o.z, 0f, 0f);
                }
            }
            if (now % 2 == 0) {
                o.level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, o.x, o.y, o.z,
                        6, 0.4, 0.6, 0.4, 0.02);
                o.level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT, o.x, gy + 0.5, o.z,
                        10, 0.6, 0.3, 0.6, 0.6);
            }
        }
        double fy = o.foot();
        if (!o.shown()) {
            // Pillar: the beam's three layers, and the two squares of the star. All stand at the foot.
            // (Tried and dropped, both from the film: a glass sheath tinted the core grey-blue, and
            // a white concrete core is shaded grey on every side.)
            o.pillar.add(Fx.beam(o.level, o.x, fy, o.z, Fx.FAR * 2, tag, BEAM_CORE));
            o.pillar.add(Fx.beam(o.level, o.x, fy, o.z, Fx.FAR * 2, tag, BEAM_HALO));
            o.pillar.add(Fx.beam(o.level, o.x, fy, o.z, Fx.FAR * 2, tag, BEAM_HAZE));
            o.pillar.add(Fx.band(o.level, o.x, fy, o.z, Fx.FAR, tag, STAR_LIGHT));
            o.pillar.add(Fx.band(o.level, o.x, fy, o.z, Fx.FAR, tag, STAR_LIGHT));
            for (int i = 0; i < OrbShape.ARCS * 4; i++) {   // two bars per arc (the kink), crossed
                Entity arc = Fx.band(o.level, o.x, fy, o.z, Fx.FAR, tag, ARC_LIGHT);
                Fx.ribbon(arc, new Vector3f(), new Vector3f(), 0f, 0f);    // hidden until it first fires
                o.arcs.add(arc);
            }
        }
        int up = OrbShape.visible(OrbShape.PER_STRAND, fraction);
        int want = up * OrbShape.STRANDS;
        while (o.helix.size() < want) {
            int k = o.helix.size();
            int i = k / OrbShape.STRANDS;
            o.helix.add(Fx.block(o.level, HELIX[i % HELIX.length], o.x, fy, o.z, Fx.FAR, tag));
        }
        while (o.helix.size() > want) {
            Entity e = o.helix.remove(o.helix.size() - 1);
            if (e != null) {
                e.discard();
            }
        }
        o.angle += OrbShape.spinRate(fraction) * 2;

        float h = OrbShape.PILLAR_HEIGHT;
        // The beam: a white core, a cyan halo behind it and a faint haze behind that, each flat
        // and turned to face whoever is looking, so it reads as light rather than as a block.
        Fx.beamPose(o.pillar.get(0), OrbShape.CORE_WIDTH, h, 0f);
        Fx.beamPose(o.pillar.get(1), OrbShape.HALO_WIDTH, h, 0.08f);
        Fx.beamPose(o.pillar.get(2), OrbShape.HAZE_WIDTH, h, 0.16f);
        // The star: two flat squares of light lying on the ground, turning opposite ways.
        for (int k = 0; k < 2; k++) {
            double a = (k == 0 ? 1 : -1) * now * 0.03 + k * Math.PI / 4;
            float hx = (float) Math.cos(a) * OrbShape.STAR / 2, hz = (float) Math.sin(a) * OrbShape.STAR / 2;
            float y = 0.05f + k * 0.01f;
            Fx.ribbon(o.pillar.get(3 + k), new Vector3f(-hx, y, -hz), new Vector3f(hx, y, hz), OrbShape.STAR,
                    (float) (Math.PI / 2));
        }

        Vector3f[] at = new Vector3f[o.helix.size()];
        for (int k = 0; k < o.helix.size(); k++) {
            double[] p = OrbShape.helix(k % OrbShape.STRANDS, k / OrbShape.STRANDS, o.angle);
            at[k] = new Vector3f((float) p[0], (float) p[1], (float) p[2]);
            Quaternionf spin = new Quaternionf().rotationXYZ((float) (now * 0.05 + k), (float) (now * 0.07), 0f);
            Fx.cubeAt(o.helix.get(k), at[k], OrbShape.HELIX_BLOCK, OrbShape.HELIX_BLOCK, OrbShape.HELIX_BLOCK,
                    spin, 2);
        }

        // Arcs: re-drawn every fourth tick to new blocks, so they flicker rather than glide. Each runs
        // from the core out to a helix block, with one kink in the middle.
        if (now % 4 == 0 || now % 4 == 1) {
            java.util.Random r = new java.util.Random(o.wishId.getLeastSignificantBits() ^ (now / 4) * 31L);
            for (int n = 0; n < OrbShape.ARCS; n++) {
                Entity[] bars = {o.arcs.get(n * 4), o.arcs.get(n * 4 + 1), o.arcs.get(n * 4 + 2), o.arcs.get(n * 4 + 3)};
                if (at.length == 0 || r.nextFloat() < 0.35f) {
                    for (Entity e : bars) {
                        Fx.ribbon(e, new Vector3f(), new Vector3f(), 0f, 0f);
                    }
                    continue;
                }
                Vector3f to = at[r.nextInt(at.length)];
                float out = OrbShape.CORE_WIDTH / 2f;
                Vector3f flat = new Vector3f(to.x, 0, to.z).normalize();
                Vector3f from = new Vector3f(flat).mul(out).add(0, to.y + (r.nextFloat() - 0.5f) * 1.2f, 0);
                Vector3f mid = new Vector3f(from).add(to).mul(0.5f)
                        .add((r.nextFloat() - 0.5f) * 0.6f, (r.nextFloat() - 0.5f) * 0.6f, (r.nextFloat() - 0.5f) * 0.6f);
                // Each half is two crossed ribbons, so the arc shows from any side.
                Fx.ribbon(bars[0], from, mid, OrbShape.ARC_WIDTH, 0f);
                Fx.ribbon(bars[1], from, mid, OrbShape.ARC_WIDTH, (float) (Math.PI / 2));
                Fx.ribbon(bars[2], mid, to, OrbShape.ARC_WIDTH, 0f);
                Fx.ribbon(bars[3], mid, to, OrbShape.ARC_WIDTH, (float) (Math.PI / 2));
            }
        }
    }

    /** Were this pillar's displays taken away with their chunk (never by us: {@link #take} clears)? */
    private static boolean unloadedUnder(Orb o) {
        for (Entity e : o.all()) {
            if (e != null && e.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    /** Remove a pillar's displays (it stays known, so it comes back after a photograph). */
    private static void take(Orb o) {
        if (o == null) {
            return;
        }
        for (Entity e : o.all()) {
            if (e != null) {
                e.discard();
            }
        }
        o.pillar.clear();
        o.helix.clear();
        o.arcs.clear();
    }

    public static ServerLevel levelOf(MinecraftServer server, String dimension) {
        if (dimension == null || dimension.isEmpty()) {
            return server.overworld();
        }
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** For callers that only have a Level. */
    static ServerLevel serverLevel(Level level) {
        return level instanceof ServerLevel s ? s : null;
    }
}
