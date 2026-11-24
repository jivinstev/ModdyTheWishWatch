package com.moddingfromamod.wish.reveal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.wish.WishCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The grand reveal: one moment per kind of wish</b>.
 *
 * <p>Before this the thing simply appeared: a building assembled in view as it was written, a
 * creature popped in, an item arrived. Now each kind has its own two-to-three second moment, drawn in
 * display entities, that starts where the orb was:
 * <ul>
 *   <li><b>building</b>: a whirlwind of glowing blocks opens out around the footprint while it is
 *   built, so he watches a mystery rather than half a wall, and at the end the blocks fly off
 *   ({@link #domeFor}, {@link #shatter}, which replaced the first cut's glass dome);</li>
 *   <li><b>creature</b>: a summoning circle on the ground and a pillar of light that collapses into
 *   it;</li>
 *   <li><b>item</b>: the real item spirals down from the orb into his hand, then pops;</li>
 *   <li><b>event</b>: the orb shoots up into the sky and bursts as the event begins, so it feels
 *   summoned;</li>
 *   <li><b>picture</b>: a frame draws itself around the spot, then flashes;</li>
 *   <li>anything else: the orb bursts into a ring that flies outward.</li>
 * </ul>
 * Every one plays the same signature sound, so he hears it even facing away, and none of them is in
 * the agent's photograph: a camera client is never sent them ({@link FxVeil}), and when his own eyes
 * take the picture they are taken down near him. At a wish's end the reveal plays BEFORE "it's
 * here!" ({@link #finale}).
 *
 * <p><b>Threads.</b> The tools that make things run off the server thread, so they only {@link #queue}
 * a reveal; this class starts it on the next server tick.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class Reveals {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Which reveal a kind of made thing gets. */
    public enum Look { BUILD, CREATURE, ITEM, EVENT, PICTURE, BURST }

    /** A reveal waiting for the server thread. */
    private record Pending(UUID wishId, UUID playerId, Look look, String madeId, long queuedMs) {
        Pending(UUID wishId, UUID playerId, Look look, String madeId) {
            this(wishId, playerId, look, madeId, System.currentTimeMillis());
        }

        /** Waited longer than any photograph can hold him: play it anyway. */
        boolean overdue() {
            return System.currentTimeMillis() - queuedMs > WishCapture.MAX_SESSION_MS + 2_000;
        }
    }

    private static final ConcurrentLinkedQueue<Pending> QUEUE = new ConcurrentLinkedQueue<>();
    private static final List<Anim> RUNNING = new ArrayList<>();
    private static final java.util.Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final Map<Object, Dome> DOMES = new ConcurrentHashMap<>();
    /**
     * The wishes whose building has had its whirlwind. Once per wish, like {@link #SEEN}: an agent
     * that builds and then touches its building up ({@code build_structure} again with
     * {@code start_from}, to add doors or light a dark room) used to get a SECOND whirlwind over the
     * finished castle, which read as the wish arriving twice (how-to-wish film, 2026-10-15: two of
     * three real castle grants did it).
     */
    private static final java.util.Set<UUID> DOMED = ConcurrentHashMap.newKeySet();

    /** How many reveals have started, by look (for tests and the smoke's log). */
    private static final Map<Look, Integer> PLAYED = new ConcurrentHashMap<>();

    private Reveals() {
    }

    /** The look for a {@link MadeKind}; BUILD is not here because the builder drives the dome. */
    public static Look lookFor(String madeKind) {
        if (madeKind == null) {
            return null;
        }
        return switch (madeKind) {
            case MadeKind.CREATURE -> Look.CREATURE;
            case MadeKind.ITEM -> Look.ITEM;
            case MadeKind.EVENT -> Look.EVENT;
            case MadeKind.PAINTING -> Look.PICTURE;
            case MadeKind.BUILD -> null;
            case MadeKind.MOB, MadeKind.DATAPACK -> null;   // an edit to a mob, or a rule: no "arrival"
            default -> Look.BURST;
        };
    }

    /** Called from the tool seam (any thread): this wish just made {@code madeId} of this kind. */
    public static void queue(WishRun run, String madeKind, String madeId) {
        Look look = lookFor(madeKind);
        if (run == null || look == null) {
            return;
        }
        // A creature is DESIGNED before it stands anywhere: sculpt_creature makes the design and a
        // later run_command spawns it. The Muse smoke played its summoning circle 38 s before the
        // dragon existed, around nothing. So a creature waits for the wish's announcement
        // (onFinished), when it is really there.
        if (look == Look.CREATURE) {
            return;
        }
        enqueue(run, look, madeId);
    }

    /**
     * <b>The early reveal</b>: the wish's first creature now really STANDS in the
     * world, so its summoning circle plays now, taking the pillar with it, rather than at
     * {@code finish_building}. The wait above (a creature designed before it stands) is exactly why
     * this is called from {@code WishEarlyReveal}, which fires only once a rig has manifested.
     * Once per wish, like every look; the finish then finds it played and announces at once.
     */
    public static void earlyCreature(WishRun run, String madeId) {
        if (run != null) {
            enqueue(run, Look.CREATURE, madeId);
        }
    }

    private static void enqueue(WishRun run, Look look, String madeId) {
        // Once per wish per look: an edit after the first make ("make him taller") is not a
        // second arrival.
        if (!SEEN.add(run.wishId() + "/" + look)) {
            return;
        }
        SEEN.add(run.wishId() + "/any");
        QUEUE.add(new Pending(run.wishId(), run.playerId(), look, madeId));
    }

    /**
     * The wish just announced its thing ({@code finish_building}). If nothing has had its moment
     * yet, this is it — the look chosen from what the wish touched. The Opus demo found why this is
     * needed: a library dragon summoned by {@code run_command} and shaped by {@code modify_mob}
     * never passed through a making tool, so the orb just vanished with no arrival at all.
     */
    public static void onFinished(WishRun run) {
        if (run == null || SEEN.contains(run.wishId() + "/any")) {
            return;
        }
        Look look = null;
        String id = null;
        boolean built = false;
        for (WishRun.Made m : run.made()) {
            if (MadeKind.BUILD.equals(m.kind())) {
                built = true;       // the builder's dome is that wish's moment
            }
            Look l = MadeKind.MOB.equals(m.kind()) ? Look.CREATURE : lookFor(m.kind());
            if (l != null) {
                look = l;
                id = m.id();
            }
        }
        if (built && look == null) {
            return;
        }
        enqueue(run, look == null ? Look.BURST : look, id);
    }

    /**
     * A wish served with no agent (a starter template, a cached wish, a library entry) finished.
     * The Opus demo found these never had a moment: the sword arrived in his hand with nothing
     * happening, because only the making tools queue a reveal. The look is read off the commands
     * the plan ran; a {@code wishbuild} is skipped because the builder's dome is already its moment.
     */
    public static void served(UUID wishId, UUID playerId, java.util.List<String> commands) {
        Look look = lookForCommands(commands);
        if (wishId == null || look == null || !SEEN.add(wishId + "/any")) {
            return;
        }
        SEEN.add(wishId + "/" + look);
        QUEUE.add(new Pending(wishId, playerId, look, look == Look.ITEM ? HAND : null));
    }

    /** Pure: which reveal a served plan gets, or null when the plan already has one (a build). */
    public static Look lookForCommands(java.util.List<String> commands) {
        Look look = null;
        for (String raw : commands == null ? java.util.List.<String>of() : commands) {
            String c = raw.strip();
            if (c.startsWith("/")) {
                c = c.substring(1);
            }
            String first = c.split("\\s+", 2)[0];
            Look l = switch (first) {
                case "wishbuild" -> null;
                case "wishmob", "summon" -> Look.CREATURE;
                case "wishpaint" -> Look.PICTURE;
                case "moddyevent" -> Look.EVENT;
                case "give" -> Look.ITEM;
                case "wishdatapack" -> c.endsWith(" give") ? Look.ITEM : Look.BURST;
                default -> Look.BURST;
            };
            if ("wishbuild".equals(first)) {
                return null;
            }
            // The most specific thing the plan made wins; a burst never overrides a real look.
            if (look == null || look == Look.BURST) {
                look = l;
            }
        }
        return look;
    }

    /** A served item is already in his hand when the plan finishes; fly THAT, not a stand-in. */
    private static final String HAND = "@hand";

    /** How many reveals are waiting for the server tick to start them. For tests. */
    public static int queued() {
        return QUEUE.size();
    }

    public static int played(Look look) {
        return PLAYED.getOrDefault(look, 0);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tick(event.getServer());
    }

    /** One tick: start what is queued, step what is running. Public for tests and films. */
    public static void tick(MinecraftServer server) {
        // a camera client is never sent any of this (FxVeil), so only a child lending his
        // OWN eyes to a photograph takes it down — and only near him.
        List<ServerPlayer> eyes = WishCapture.ownEyesShooting(server);
        List<Pending> later = new ArrayList<>();
        Pending p;
        while ((p = QUEUE.poll()) != null) {
            // Not started into a photograph through his eyes: it would be cut at once and he would
            // miss it. It waits (at most a capture session, MAX_SESSION_MS) and plays when he is back.
            if (!eyes.isEmpty() && hiddenFor(server, p, eyes) && !p.overdue()) {
                later.add(p);
            } else {
                start(server, p);
            }
        }
        QUEUE.addAll(later);
        for (Iterator<Anim> it = RUNNING.iterator(); it.hasNext(); ) {
            Anim a = it.next();
            boolean done;
            if (!eyes.isEmpty() && a.hiddenFrom(eyes)) {
                a.end();            // never in a photograph: a reveal cut short is fine
                done = true;
            } else {
                done = a.step(a.age++);
                if (done) {
                    a.end();
                }
            }
            if (done) {
                it.remove();
                finished(a.wishId);
            }
        }
        for (var e : DOMES.entrySet()) {
            Dome d = e.getValue();
            d.step(!eyes.isEmpty() && FxVeil.hiddenAt(eyes, d.level, d.cx, d.cz));
            if (d.shatterPending && d.age >= MIN_DOME_TICKS) {
                DOMES.remove(e.getKey());
                burst(d);
            }
        }
        long now = System.currentTimeMillis();
        for (var e : AFTER.entrySet()) {
            if (now - e.getValue().since >= FINALE_DEADLINE_MS) {
                LOGGER.warn("[reveal] wish {}: its reveal never finished; announcing anyway",
                        e.getKey().toString().substring(0, 8));
                finished(e.getKey(), true);
            }
        }
    }

    /** Is a queued reveal's spot near a child whose own eyes are taking a photograph? */
    private static boolean hiddenFor(MinecraftServer server, Pending p, List<ServerPlayer> eyes) {
        for (WishRun r : Marshal.global().all()) {
            if (r.wishId().equals(p.wishId()) && r.anchor() != null) {
                ServerLevel level = WishOrbs.levelOf(server, r.anchor().dimension());
                return level != null && FxVeil.hiddenAt(eyes, level, r.anchor().x() + 0.5, r.anchor().z() + 0.5);
            }
        }
        return true;      // no spot to judge by: he is the one being photographed, so wait
    }

    // ---- the finale: reveal first, THEN "it's here!" -------------------------------------

    /** What waits for a wish's reveal to finish, and since when. */
    private static final class After {
        final List<Runnable> then = new ArrayList<>();
        final long since = System.currentTimeMillis();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    }

    private static final Map<UUID, After> AFTER = new ConcurrentHashMap<>();
    private static final Map<UUID, java.util.concurrent.CountDownLatch> ANNOUNCED = new ConcurrentHashMap<>();

    /** A reveal that has not finished in this long is not waited for any more. */
    public static final long FINALE_DEADLINE_MS = 15_000;

    /**
     * <b>The end of a wish, in the order the child must see it</b>: the
     * photograph has been taken; now the reveal plays; and only when it has, {@code then} runs —
     * "✨ … IS HERE!", and for a creature the rig release, as part of the reveal moment. Before this
     * the announcement came FIRST, the pillar then vanished for the camera, came back, and burst:
     * it looked finished, then suddenly it wasn't.
     *
     * <p>Server thread. {@code then} runs now when this wish has no reveal still to play (its
     * moment already happened — a picture's frame when it was painted, a building's whirlwind when
     * it finished — or there is nothing to reveal).
     */
    public static void finale(WishRun run, Runnable then) {
        if (run == null) {
            if (then != null) {
                then.run();
            }
            return;
        }
        ANNOUNCED.computeIfAbsent(run.wishId(), k -> new java.util.concurrent.CountDownLatch(1));
        onFinished(run);
        if (pendingFor(run.wishId())) {
            AFTER.computeIfAbsent(run.wishId(), k -> new After()).then.add(then);
            LOGGER.info("[reveal] wish {}: announcement waits for the reveal", run.shortId());
            return;
        }
        runSafely(then);
        ANNOUNCED.get(run.wishId()).countDown();
    }

    /**
     * Wait (off the server thread) until this wish's finale has been announced, so
     * {@code finish_wish}'s own words never come before "it's here!". Returns at once for a wish
     * that has no finale; never waits longer than {@code maxMs}.
     */
    public static void awaitAnnounced(UUID wishId, long maxMs) {
        var latch = wishId == null ? null : ANNOUNCED.get(wishId);
        if (latch == null) {
            return;
        }
        try {
            latch.await(maxMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Is a reveal for this wish queued, playing, or a whirlwind still up? */
    private static boolean pendingFor(UUID wishId) {
        for (Pending p : QUEUE) {
            if (wishId.equals(p.wishId())) {
                return true;
            }
        }
        for (Anim a : RUNNING) {
            if (wishId.equals(a.wishId)) {
                return true;
            }
        }
        for (Dome d : DOMES.values()) {
            if (wishId.equals(d.wishId)) {
                return true;
            }
        }
        return false;
    }

    /** This wish's reveal is over (played, cut, or never able to start): run what waited for it. */
    private static void finished(UUID wishId) {
        finished(wishId, false);
    }

    private static void finished(UUID wishId, boolean force) {
        if (wishId == null || (!force && pendingFor(wishId))) {
            return;
        }
        After a = AFTER.remove(wishId);
        if (a != null) {
            for (Runnable r : a.then) {
                runSafely(r);
            }
        }
        var latch = ANNOUNCED.get(wishId);
        if (latch != null) {
            latch.countDown();
        }
    }

    private static void runSafely(Runnable r) {
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (RuntimeException e) {
            LOGGER.warn("[reveal] a finale step threw", e);
        }
    }

    private static void start(MinecraftServer server, Pending p) {
        WishRun run = null;
        for (WishRun r : Marshal.global().all()) {
            if (r.wishId().equals(p.wishId())) {
                run = r;
            }
        }
        ServerPlayer player = p.playerId() == null ? null : server.getPlayerList().getPlayer(p.playerId());
        double[] from = WishOrbs.release(p.wishId());
        ServerLevel level;
        double x, y, z;
        var spot = run == null ? null : run.anchor();
        if (spot != null) {
            level = WishOrbs.levelOf(server, spot.dimension());
            x = spot.x() + 0.5;
            y = spot.y();
            z = spot.z() + 0.5;
        } else if (player != null) {
            level = player.level();
            x = player.getX();
            y = player.getY();
            z = player.getZ();
        } else {
            finished(p.wishId());
            return;
        }
        if (level == null) {
            finished(p.wishId());
            return;
        }
        if (from == null) {
            from = new double[]{x, y + OrbShape.ORB_HEIGHT, z};
        }
        ItemStack stack = p.look() != Look.ITEM ? ItemStack.EMPTY
                : HAND.equals(p.madeId()) && player != null && !player.getMainHandItem().isEmpty()
                        ? player.getMainHandItem().copyWithCount(1)
                        : itemFor(level, p.madeId());
        RevealFrame frame = p.look() == Look.PICTURE
                ? frameFor(level, p.madeId(), new double[]{x, y, z}, from, player) : null;
        play(level, p.look(), p.wishId(), from, new double[]{x, y, z}, player, stack, frame);
    }

    /**
     * Where a picture's frame goes: around the picture itself, read off its placement
     * record. The record is found by the id the making tool named, else (a served {@code /wishpaint}
     * names none) as the newest picture hung near the spot. With no record at all it falls back to
     * the pillar, turned to face the child the way {@code hangBoard} turns a board.
     */
    public static RevealFrame frameFor(ServerLevel level, String madeId, double[] spot, double[] orb, ServerPlayer player) {
        String dim = level.dimension().location().toString();
        com.moddingfromamod.paint.PaintRecord r = com.moddingfromamod.paint.PaintRegistry.get(madeId);
        if (r == null || !dim.equals(r.dimension())) {
            r = com.moddingfromamod.paint.PaintRegistry.newestNear(dim, spot[0], spot[2], PICTURE_REACH);
        }
        RevealFrame f = r == null ? null : sittingOnTheGround(level, r, boardOf(level, r));
        if (f != null) {
            LOGGER.info("[reveal] picture frame from record {}: facing {} {}x{} at {},{},{}", r.id(),
                    f.facing(), Math.round(2 * f.halfW() - 1), Math.round(2 * f.halfH() - 1), r.x(), r.y(), r.z());
            return f;
        }
        double dx = player == null ? 0 : player.getX() - orb[0];
        double dz = player == null ? 1 : player.getZ() - orb[2];
        return RevealFrame.facingViewer(orb[0], orb[1], orb[2], dx, dz);
    }

    /** How far from the spot a served picture's record may be (hangBoard steps up to 8 widths aside). */
    static final double PICTURE_REACH = 48;

    /**
     * <b>Where a hung picture's board is</b>: its centre, which way it faces and its wide × tall,
     * read the one way for everybody — the reveal's bead frame AND the camera that photographs it
     * (the finish photograph was taken from the child's eye, at his feet's height, and a
     * 16-tall picture came back headless). Null when the record does not describe a board the world
     * still holds. Never trimmed to a floor: that is {@link #sittingOnTheGround}, for beads only.
     */
    public static RevealFrame boardOf(ServerLevel level, com.moddingfromamod.paint.PaintRecord r) {
        if (level == null || r == null) {
            return null;
        }
        if (r.hasVanillaCopy()) {
            return RevealFrame.forPicture(r.facing(), r.x(), r.y(), r.z(),
                    com.moddingfromamod.paint.VanillaArt.tiles(r.wide()),
                    com.moddingfromamod.paint.VanillaArt.tiles(r.tall()));
        }
        // Our own panel block: the facing and the span live on the block, not in the record.
        BlockPos at = new BlockPos(r.x(), r.y(), r.z());
        var st = level.getBlockState(at);
        if (!st.hasProperty(com.moddingfromamod.paint.PaintPanelBlock.FACING)) {
            return null;
        }
        int wide = 1, tall = 1;
        if (level.getBlockEntity(at) instanceof com.moddingfromamod.paint.PaintPanelBlockEntity be) {
            wide = Math.max(1, be.widthBlocks());
            tall = Math.max(1, be.heightBlocks());
        }
        RevealFrame f = RevealFrame.forPicture(st.getValue(com.moddingfromamod.paint.PaintPanelBlock.FACING).getName(),
                r.x(), r.y(), r.z(), wide, tall);
        return f == null ? null : f.outBy(0.5);
    }

    /** A board standing on the ground: keep the bottom row of beads out of the grass. */
    private static RevealFrame sittingOnTheGround(ServerLevel level, com.moddingfromamod.paint.PaintRecord r,
                                                  RevealFrame f) {
        if (f == null || !r.hasVanillaCopy()) {
            return f;
        }
        BlockPos under = new BlockPos(r.x(), r.y() - 1, r.z());
        return !level.getBlockState(under).isAir() ? f.sitOn(r.y()) : f;
    }

    /**
     * Start a reveal now (server thread). {@code orb} is where it starts, {@code ground} is the spot on
     * the ground, {@code player} (may be null) is who the item flies to.
     */
    public static void play(ServerLevel level, Look look, UUID wishId, double[] orb, double[] ground,
                            ServerPlayer player, ItemStack stack) {
        play(level, look, wishId, orb, ground, player, stack, look == Look.PICTURE
                ? RevealFrame.facingViewer(orb[0], orb[1], orb[2],
                        player == null ? 0 : player.getX() - orb[0], player == null ? 1 : player.getZ() - orb[2])
                : null);
    }

    /** As above; {@code frame} is where a picture's frame is traced (null for every other look). */
    public static void play(ServerLevel level, Look look, UUID wishId, double[] orb, double[] ground,
                            ServerPlayer player, ItemStack stack, RevealFrame frame) {
        String tag = Fx.wishTag(wishId);
        Anim a = switch (look) {
            case CREATURE -> new Summon(level, tag, ground);
            case ITEM -> new Spiral(level, tag, orb, player, stack);
            case EVENT -> new Launch(level, tag, orb);
            case PICTURE -> new Frame(level, tag, frame != null ? frame
                    : RevealFrame.facingViewer(orb[0], orb[1], orb[2], 0, 1));
            case BUILD, BURST -> new Burst(level, tag, orb);
        };
        a.wishId = wishId;
        RUNNING.add(a);
        PLAYED.merge(look, 1, Integer::sum);
        signature(level, orb[0], orb[1], orb[2]);
        LOGGER.info("[reveal] {} for wish {}", look.name().toLowerCase(), wishId == null ? "-" : wishId.toString().substring(0, 8));
    }

    /** The reveal's sound, layered from vanilla: a resonant chime, a rising sparkle, a pop. */
    static void signature(ServerLevel level, double x, double y, double z) {
        level.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 2.0f, 1.0f);
        level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 1.5f, 1.2f);
        level.playSound(null, x, y, z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.5f);
    }

    private static ItemStack itemFor(ServerLevel level, String key) {
        if (key != null) {
            for (var e : com.moddingfromamod.item.WishItemCatalogue.all()) {
                if (key.equals(e.key())) {
                    ItemStack s = com.moddingfromamod.item.WishItemCatalogue.stack(e, level.registryAccess());
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                }
            }
        }
        return new ItemStack(Items.NETHER_STAR);
    }

    // ---- the building dome ---------------------------------------------------------------------

    /**
     * Put a dome over a building while it is built. {@code key} is the build job; the dome is
     * shattered by {@link #shatter}, or quietly removed by {@link #dropDome} when the build is
     * stopped. Server thread.
     */
    public static void domeFor(Object key, ServerLevel level, UUID wishId, double cx, double cy, double cz,
                               double halfX, double halfZ, double height) {
        domeFor(key, level, wishId, cx, cy, cz, halfX, halfZ, height, false);
    }

    /**
     * As above; {@code bubble} closes it underneath too, for a building that is not standing on
     * the ground (a sky castle, an upside-down wish): a dome's open bottom would show the whole
     * build going up from below.
     */
    public static void domeFor(Object key, ServerLevel level, UUID wishId, double cx, double cy, double cz,
                               double halfX, double halfZ, double height, boolean bubble) {
        if (key == null || level == null) {
            return;
        }
        if (wishId != null && !DOMED.add(wishId)) {
            return;      // this wish's building has had its moment; an edit just appears
        }
        WishOrbs.release(wishId);
        Dome d = new Dome(level, Fx.wishTag(wishId), cx, cy, cz, halfX, halfZ, height, bubble);
        d.wishId = wishId;
        DOMES.put(key, d);
    }

    /**
     * How long a dome stays up before it may shatter. A real castle of 7,406 blocks went up in about
     * a tick (an Opus demo, 2026-09-30), so the dome appeared and burst in the same instant and he
     * never saw the mystery. The build is done underneath; the shatter waits for the moment.
     */
    static final int MIN_DOME_TICKS = 40;

    /** The build finished: the dome flies apart (once it has been up long enough to be seen). */
    public static void shatter(Object key) {
        Dome d = key == null ? null : DOMES.get(key);
        if (d == null) {
            return;
        }
        if (d.age < MIN_DOME_TICKS) {
            d.shatterPending = true;
            return;
        }
        DOMES.remove(key);
        burst(d);
    }

    private static void burst(Dome d) {
        d.take();
        Shatter s = new Shatter(d);
        s.wishId = d.wishId;
        RUNNING.add(s);
        PLAYED.merge(Look.BUILD, 1, Integer::sum);
        signature(d.level, d.cx, d.bottom + d.span / 2, d.cz);
        d.level.playSound(null, d.cx, d.cy, d.cz, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.BLOCKS, 3.0f, 0.7f);
        LOGGER.info("[reveal] build (whirlwind shattered)");
    }

    /** The build was stopped: the dome goes quietly. */
    public static void dropDome(Object key) {
        Dome d = key == null ? null : DOMES.remove(key);
        if (d != null) {
            d.take();
            if (d.wishId != null) {
                DOMED.remove(d.wishId);      // never seen, so a retried build still gets its moment
            }
            finished(d.wishId);
        }
    }

    /** Cut one wish's reveal short (a served wish was stopped mid-fanfare). */
    public static void cut(UUID wishId) {
        if (wishId == null) {
            return;
        }
        String tag = Fx.wishTag(wishId);
        for (Iterator<Anim> it = RUNNING.iterator(); it.hasNext(); ) {
            Anim a = it.next();
            if (tag.equals(a.tag)) {
                a.end();
                it.remove();
            }
        }
        finished(wishId);
    }

    /** Is a dome up for this build job? (Gate B) */
    static boolean domeUp(Object key) {
        return key != null && DOMES.containsKey(key);
    }

    /** Everything alive right now (Gate B and films). */
    public static int running() {
        return RUNNING.size() + DOMES.size();
    }

    /** Remove everything (world stop). */
    public static void clear() {
        for (Anim a : RUNNING) {
            a.end();
        }
        RUNNING.clear();
        for (Dome d : DOMES.values()) {
            d.take();
        }
        DOMES.clear();
        DOMED.clear();
        QUEUE.clear();
        SEEN.clear();
        for (UUID id : List.copyOf(AFTER.keySet())) {
            AFTER.remove(id);
        }
        for (var latch : ANNOUNCED.values()) {
            latch.countDown();
        }
        ANNOUNCED.clear();
    }

    // ---- the animations -----------------------------------------------------------------------

    /** One reveal: a handful of displays stepped each tick until done. */
    abstract static class Anim {
        final ServerLevel level;
        final String tag;
        final List<Entity> parts = new ArrayList<>();
        int age;
        /** Whose reveal this is: its end runs the wish's finale. */
        UUID wishId;

        /** Is a child near it lending his own eyes to a photograph? */
        boolean hiddenFrom(List<ServerPlayer> eyes) {
            for (Entity e : parts) {
                if (e != null) {
                    return FxVeil.hiddenAt(eyes, level, e.getX(), e.getZ());
                }
            }
            return false;
        }

        Anim(ServerLevel level, String tag) {
            this.level = level;
            this.tag = tag;
        }

        Entity block(BlockState s, double x, double y, double z) {
            Entity e = Fx.block(level, s, x, y, z, Fx.FAR, tag);
            if (e != null) {
                parts.add(e);
            }
            return e;
        }

        /** Advance; true when finished. */
        abstract boolean step(int age);

        void end() {
            for (Entity e : parts) {
                if (e != null) {
                    e.discard();
                }
            }
            parts.clear();
        }
    }

    /** Creature: a ring of glowing beads on the ground spins inward while a pillar rises and falls. */
    static final class Summon extends Anim {
        static final int BEADS = 16;
        static final int TICKS = 50;
        final double x, y, z;
        Entity pillar;

        Summon(ServerLevel level, String tag, double[] g) {
            super(level, tag);
            x = g[0];
            y = g[1];
            z = g[2];
            for (int i = 0; i < BEADS; i++) {
                block(i % 2 == 0 ? Blocks.CYAN_STAINED_GLASS.defaultBlockState() : Blocks.GOLD_BLOCK.defaultBlockState(), x, y, z);
            }
            pillar = block(Blocks.WHITE_STAINED_GLASS.defaultBlockState(), x, y, z);
        }

        @Override
        boolean step(int age) {
            double t = age / (double) TICKS;
            double r = 4.5 * (1 - t * t);
            double spin = age * 0.25;
            for (int i = 0; i < BEADS; i++) {
                double a = spin + Math.PI * 2 * i / BEADS;
                Entity b = parts.get(i);
                b.snapTo(x + Math.cos(a) * r, y + 0.1, z + Math.sin(a) * r, 0f, 0f);
                Fx.cube(b, 1.0f, 0.2f, 1.0f, new Quaternionf().rotationY((float) a), 2);
            }
            float h = (float) (t < 0.6 ? 18 * (t / 0.6) : 18 * (1 - (t - 0.6) / 0.4));
            float w = (float) (2.2 * (1 - t) + 0.2);
            pillar.snapTo(x, y + h / 2, z, 0f, 0f);
            Fx.cube(pillar, w, Math.max(0.05f, h), w, new Quaternionf(), 2);
            if (age % 4 == 0) {
                level.sendParticles(ParticleTypes.END_ROD, x, y + 0.3, z, 6, r * 0.4, 0.1, r * 0.4, 0.02);
            }
            if (age == TICKS) {
                level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), x, y + 1, z, 2, 0.5, 0.5, 0.5, 0);
                level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y + 1, z, 60, 1.5, 1.5, 1.5, 0.3);
            }
            return age >= TICKS;
        }
    }

    /** Item: the real item spirals down from the orb into his hand, then pops. */
    static final class Spiral extends Anim {
        static final int TICKS = 40;
        final double[] from;
        final ServerPlayer player;
        Entity item;

        Spiral(ServerLevel level, String tag, double[] from, ServerPlayer player, ItemStack stack) {
            super(level, tag);
            this.from = from;
            this.player = player;
            item = Fx.item(level, stack, from[0], from[1], from[2], tag);
            if (item != null) {
                parts.add(item);
                // Born at nothing, so it grows OUT of the flash: spawned at its default size the
                // client drew one giant sword for a couple of frames before its first pose landed.
                Fx.itemPose(item, 0.01f, new Quaternionf(), 0);
            }
            // The moment it is wished into existence: a flash of light AT the point where the
            // pillar ends and the item begins, so he sees exactly where it came from. A white
            // flash, a burst of sparks out of that point, and a chime.
            level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), from[0], from[1], from[2], 2, 0, 0, 0, 0);
            level.sendParticles(ParticleTypes.END_ROD, from[0], from[1], from[2], 36, 0.1, 0.1, 0.1, 0.25);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, from[0], from[1], from[2], 24, 0.3, 0.3, 0.3, 0.4);
            level.playSound(null, from[0], from[1], from[2], SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundSource.PLAYERS, 2.0f, 1.4f);
        }

        @Override
        boolean step(int age) {
            double t = Math.min(1, age / (double) TICKS);
            // It stops a block and a half in front of his eyes rather than flying through his face.
            double tx = from[0], ty = from[1] - 2, tz = from[2];
            if (player != null) {
                var look = player.getLookAngle();
                tx = player.getX() + look.x * 1.6;
                ty = player.getEyeY() + look.y * 1.6 - 0.2;
                tz = player.getZ() + look.z * 1.6;
            }
            double r = 1.6 * (1 - t);
            double a = age * 0.45;
            double ease = t * t * (3 - 2 * t);
            double x = from[0] + (tx - from[0]) * ease + Math.cos(a) * r;
            double y = from[1] + (ty - from[1]) * ease + Math.sin(t * Math.PI) * 1.2;
            double z = from[2] + (tz - from[2]) * ease + Math.sin(a) * r;
            if (item != null) {
                item.snapTo(x, y, z, 0f, 0f);
                Fx.itemPose(item, (float) (1.6 - 0.8 * t), new Quaternionf().rotationY((float) (age * 0.35)), 2);
            }
            if (age % 3 == 0) {
                level.sendParticles(ParticleTypes.ENCHANT, x, y, z, 4, 0.1, 0.1, 0.1, 0.2);
            }
            if (age == TICKS) {
                level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, tx, ty, tz, 30, 0.4, 0.4, 0.4, 0.25);
                level.playSound(null, tx, ty, tz, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0f, 0.8f);
            }
            return age >= TICKS;
        }
    }

    /** Event: the orb shoots straight up and bursts, as the event begins below it. */
    static final class Launch extends Anim {
        static final int TICKS = 24;
        final double[] from;
        Entity core;

        Launch(ServerLevel level, String tag, double[] from) {
            super(level, tag);
            this.from = from;
            core = block(WishOrbs.CORE, from[0], from[1], from[2]);
        }

        @Override
        boolean step(int age) {
            double t = age / (double) TICKS;
            double y = from[1] + 12 * t * t;
            core.snapTo(from[0], y, from[2], 0f, 0f);
            Fx.cube(core, 2.0f, 2.0f, 2.0f, new Quaternionf().rotationXYZ(age * 0.3f, age * 0.2f, 0), 2);
            level.sendParticles(ParticleTypes.FIREWORK, from[0], y - 0.8, from[2], 3, 0.1, 0.1, 0.1, 0.02);
            if (age == TICKS) {
                level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, from[0], y, from[2], 1, 0, 0, 0, 0);
                level.sendParticles(ParticleTypes.FIREWORK, from[0], y, from[2], 80, 2, 2, 2, 0.3);
                level.playSound(null, from[0], y, from[2], SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 3f, 1f);
            }
            return age >= TICKS;
        }
    }

    /**
     * Picture: a gold frame draws itself around the picture, bead by bead, then flashes. It is
     * traced in the picture's own plane and at its size — the first cut drew a fixed 7 × 5
     * in the X-Y plane, 90° off any picture facing east or west.
     */
    static final class Frame extends Anim {
        /** 1.3 s: the first cut's 2.2 s trail of beads was too slow (2026-10-01). */
        static final int TICKS = 26;
        final RevealFrame f;
        final int beads;
        final double[] c;

        Frame(ServerLevel level, String tag, RevealFrame f) {
            super(level, tag);
            this.f = f;
            this.beads = f.beads();
            this.c = new double[]{f.cx(), f.cy(), f.cz()};
        }

        @Override
        boolean step(int age) {
            int want = Math.min(beads, age * beads / (TICKS - 8) + 1);
            while (parts.size() < want) {
                double[] e = f.edge(parts.size() / (double) beads);
                Entity b = block(Blocks.GOLD_BLOCK.defaultBlockState(), e[0], e[1], e[2]);
                Fx.cube(b, 0.7f, 0.7f, 0.7f, new Quaternionf(), 0);
            }
            if (age == TICKS - 6) {
                int[] r = f.right();
                double sx = Math.abs(r[0]) * f.halfW() * 0.6 + 0.2, sz = Math.abs(r[1]) * f.halfW() * 0.6 + 0.2;
                level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), c[0], c[1], c[2], 2, sx, 1, sz, 0);
                level.sendParticles(ParticleTypes.END_ROD, c[0], c[1], c[2], 40, sx, f.halfH() * 0.6, sz, 0.05);
            }
            return age >= TICKS;
        }
    }

    /** Anything else: the orb bursts into a ring of beads that fly outward and shrink. */
    static final class Burst extends Anim {
        static final int BEADS = 16;
        static final int TICKS = 30;
        final double[] c;

        Burst(ServerLevel level, String tag, double[] c) {
            super(level, tag);
            this.c = c;
            for (int i = 0; i < BEADS; i++) {
                block(i % 2 == 0 ? WishOrbs.BEAD_LIT : WishOrbs.SHELL, c[0], c[1], c[2]);
            }
            level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), c[0], c[1], c[2], 1, 0, 0, 0, 0);
        }

        @Override
        boolean step(int age) {
            double t = age / (double) TICKS;
            double r = 9 * Math.sqrt(t);
            for (int i = 0; i < BEADS; i++) {
                double a = Math.PI * 2 * i / BEADS;
                Entity b = parts.get(i);
                b.snapTo(c[0] + Math.cos(a) * r, c[1] + Math.sin(t * Math.PI) * 1.5, c[2] + Math.sin(a) * r, 0f, 0f);
                float s = (float) (1.1 * (1 - t) + 0.05);
                Fx.cube(b, s, s, s, new Quaternionf().rotationXYZ(age * 0.3f, (float) a, 0), 2);
            }
            if (age == 2) {
                level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, c[0], c[1], c[2], 50, 1, 1, 1, 0.4);
            }
            return age >= TICKS;
        }
    }

    /**
     * A building's whirlwind: the pillar's own blocks, scaled up into a whirlwind that
     * opens out from the middle of the spot and spins around the building while it goes up, so the
     * building is a mystery inside a storm of glowing blocks rather than behind glass. The design:
     * the block swirl scaled up to many more blocks, expanding into a whirlwind around the building,
     * then the shatter, where the blocks fly off from it. Over a building that is not standing on the ground it tapers to a tail
     * underneath, so the build is wrapped from below too. Hidden for photographs.
     */
    static final class Dome {
        /** Ticks the whirlwind takes to open out from the middle to its full width. */
        static final int EXPAND = 20;
        /** The most blocks one whirlwind spins: every one is a display moved each tick. */
        static final int MAX_BLOCKS = 168;
        final ServerLevel level;
        final String tag;
        final double cx, cy, cz, rx, rz, height;
        /** Where the swirl starts and how tall it is (a floating build's reaches below it). */
        final double bottom, span;
        final boolean bubble;
        boolean shatterPending;
        UUID wishId;
        /** Each block: {height fraction, phase, angular speed, size, palette index}. */
        final List<double[]> seeds = new ArrayList<>();
        final List<Entity> parts = new ArrayList<>();
        int age;

        Dome(ServerLevel level, String tag, double cx, double cy, double cz, double halfX, double halfZ,
             double height, boolean bubble) {
            this.level = level;
            this.tag = tag;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.rx = Math.min(26, halfX + 2.5);
            this.rz = Math.min(26, halfZ + 2.5);
            this.bubble = bubble;
            this.height = Math.min(48, height + 2);
            this.bottom = bubble ? cy - 5 : cy;
            this.span = bubble ? this.height + 7 : this.height + 1;
            double r = (rx + rz) / 2;
            int bands = (int) Math.max(4, Math.min(12, Math.round(span / 2.2)));
            int perBand = (int) Math.max(8, Math.min(26, Math.round(2 * Math.PI * r / 2.4)));
            perBand = Math.min(perBand, MAX_BLOCKS / bands);
            java.util.Random rnd = new java.util.Random(tag.hashCode());
            int n = 0;
            for (int b = 0; b < bands; b++) {
                // Alternate bands spin a little faster, so the whirlwind reads as moving air rather
                // than one rigid wheel.
                double speed = 0.16 + 0.05 * (b % 3) + 0.02 * b / (double) bands;
                for (int k = 0; k < perBand; k++) {
                    double f = (b + rnd.nextDouble()) / bands;
                    double phase = Math.PI * 2 * (k + 0.5 * (b % 2)) / perBand + rnd.nextDouble() * 0.3;
                    double size = (0.55 + 0.35 * rnd.nextDouble()) * Math.max(0.8, Math.min(1.5, r / 7));
                    seeds.add(new double[]{f, phase, speed, size, n++ % WishOrbs.HELIX.length});
                }
            }
        }

        /** How wide the whirlwind is at height fraction {@code f}: it flares out toward the top. */
        double flare(double f) {
            if (bubble) {
                double base = (bottom + span - cy - height) / span;   // fraction below the build
                return f < base ? 0.25 + 0.75 * f / Math.max(0.01, base) : 1.0 + 0.2 * f;
            }
            return 0.85 + 0.3 * f;
        }

        double[] at(int i, int age) {
            double[] s = seeds.get(i);
            double t = Math.min(1, age / (double) EXPAND);
            double grow = 1 - (1 - t) * (1 - t) * (1 - t);     // out fast, settle gently
            double a = s[1] + age * s[2];
            double rs = (0.12 + 0.88 * grow) * flare(s[0]);
            double y = bottom + s[0] * span * (0.25 + 0.75 * grow) + 0.35 * Math.sin(age * 0.17 + s[1] * 3);
            return new double[]{cx + Math.cos(a) * rx * rs, y, cz + Math.sin(a) * rz * rs};
        }

        void step(boolean shooting) {
            if (shooting) {
                take();
                return;
            }
            if (parts.isEmpty()) {
                for (int i = 0; i < seeds.size(); i++) {
                    double[] q = at(i, age);
                    parts.add(Fx.block(level, WishOrbs.HELIX[(int) seeds.get(i)[4]], q[0], q[1], q[2], Fx.FAR, tag));
                }
            }
            age++;
            for (int i = 0; i < parts.size(); i++) {
                Entity e = parts.get(i);
                if (e == null) {
                    continue;
                }
                double[] q = at(i, age);
                e.snapTo(q[0], q[1], q[2], 0f, 0f);
                float sz = (float) seeds.get(i)[3];
                Fx.cube(e, sz, sz, sz, new Quaternionf().rotationXYZ(age * 0.21f + i, age * 0.33f, i * 0.7f), 2);
            }
            if (age % 6 == 0) {
                level.sendParticles(ParticleTypes.CLOUD, cx, bottom + span * 0.4, cz, 14, rx * 0.7, span * 0.35, rz * 0.7, 0.08);
            }
            if (age % 10 == 0) {
                level.sendParticles(ParticleTypes.END_ROD, cx, bottom + span / 2, cz, 20, rx * 0.8, span * 0.4, rz * 0.8, 0.05);
            }
        }

        void take() {
            for (Entity e : parts) {
                if (e != null) {
                    e.discard();
                }
            }
            parts.clear();
        }
    }

    /**
     * The whirlwind flying apart when the building is done: every block is flung off along the way
     * it was spinning and outward, tumbling and shrinking as it falls away, with a firework.
     */
    static final class Shatter extends Anim {
        static final int TICKS = 36;
        final Dome d;
        final List<double[]> vel = new ArrayList<>();
        final List<double[]> pos = new ArrayList<>();
        final List<Float> size = new ArrayList<>();

        Shatter(Dome d) {
            super(d.level, d.tag);
            this.d = d;
            for (int i = 0; i < d.seeds.size(); i++) {
                double[] s = d.seeds.get(i);
                double[] q = d.at(i, d.age);
                double dx = q[0] - d.cx, dz = q[2] - d.cz;
                double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                double ox = dx / len, oz = dz / len;
                // Outward, plus the way it was already going round (a sling letting go), plus a lift.
                double sp = 0.8 + 0.4 * ((i * 37) % 10) / 10.0;
                Entity e = block(WishOrbs.HELIX[(int) s[4]], q[0], q[1], q[2]);
                if (e == null) {
                    continue;
                }
                pos.add(q);
                vel.add(new double[]{(ox - oz * 0.6) * sp, 0.35 + 0.25 * s[0], (oz + ox * 0.6) * sp});
                size.add((float) s[3]);
            }
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, d.cx, d.bottom + d.span / 2, d.cz, 1, 0, 0, 0, 0);
            level.sendParticles(ParticleTypes.FIREWORK, d.cx, d.bottom + d.span, d.cz, 120, d.rx * 0.6, 2, d.rz * 0.6, 0.4);
        }

        @Override
        boolean step(int age) {
            double t = age / (double) TICKS;
            for (int i = 0; i < parts.size(); i++) {
                double[] p = pos.get(i), v = vel.get(i);
                p[0] += v[0];
                p[1] += v[1];
                p[2] += v[2];
                v[0] *= 0.97;
                v[2] *= 0.97;
                v[1] -= 0.05;
                Entity e = parts.get(i);
                e.snapTo(p[0], p[1], p[2], 0f, 0f);
                float s = (float) (size.get(i) * (1 - t * 0.85));
                Fx.cube(e, s, s, s, new Quaternionf().rotationXYZ(age * 0.5f + i, age * 0.4f, i), 2);
            }
            return age >= TICKS;
        }
    }

    /** A spot, for callers holding a BlockPos. */
    public static double[] centre(BlockPos p) {
        return new double[]{p.getX() + 0.5, p.getY(), p.getZ() + 0.5};
    }
}
