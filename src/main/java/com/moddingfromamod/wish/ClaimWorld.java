package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.agent.ActingChild;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.datapack.DatapackReloader;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.marshal.KidTyping;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.mob.PetBehaviours;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * <b>Asks the world whether each claim is true</b> — the half of {@link ClaimJudge}
 * that looks. Called from {@code finish_building} OFF the server thread: it samples once, lets the
 * world run {@link ClaimJudge#WINDOW_TICKS} ticks when any claim has to be watched (a creature that
 * wanders off, a follower that does not come, a Spikor his own fire hurts), samples again, and hands
 * the two samples to the judge. Nothing here changes the world.
 *
 * <h2>Whether this wish's thing exists is asked by IDENTITY </h2>
 *
 * <p>Four checks asked the wrong question, each measured — one live, the rest in 198 archived runs:
 * <ul>
 *   <li><b>WHERE.</b> {@code creature_count} counted around the wish's spot, so a Spikor set to
 *       follow walked away with the child and "Spikor appears" failed the moment "he follows me"
 *       held. Now this wish's OWN creatures — the carriers it spawned ({@link WishRun#creaturesSpawned}),
 *       what its commands spawned ({@link WishRun#commandSpawned}), the mobs it changed
 *       ({@code EntityHandles} touches) — are counted wherever they are, alive. A radius counts
 *       only OTHER things.</li>
 *   <li><b>WHAT name.</b> {@code item_in_inventory} matched a registry id or a display name, and
 *       the agent names a made item by its wish key ({@code boomerang_plain}): 20 of 24 archived
 *       item contradictions were of a key the same run had just handed over. A made item's key
 *       ({@code custom_data} {@code WishItem}) now matches too, exactly.</li>
 *   <li><b>WHICH container.</b> Since an overflow lands in the Wish Bag, which the check never
 *       opened. And it counted anything of that name he held, so an older wish's boomerang
 *       satisfied a new claim. Now it counts what THIS wish handed over ({@code WishDelivery}'s
 *       per-wish record, the one an undo reads), wherever it now is — hotbar, inventory, bag — and
 *       never more than it handed over. A thing this wish never handed over is not "his from this
 *       wish", however many of them he has.</li>
 *   <li><b>HOW FAR.</b> {@code block_present} parsed radii up to {@link ClaimCheck#MAX_RADIUS} and
 *       then silently scanned 24 ("0 within 24" of a heart whose red rows began 28 up). It now scans
 *       the radius asked for, by chunk section, skipping a section whose palette cannot hold the
 *       block. Inside the old cube of 24 a chunk is loaded as it always was; past it, only loaded
 *       chunks are counted and the rest reported — timed at 48 by {@code ClaimIdentityGameTest}.</li>
 * </ul>
 */
public final class ClaimWorld {

    private ClaimWorld() {
    }

    /** How far from the child a follower / a creature to watch is looked for. */
    static final int SEARCH = 64;
    /** Wall-clock cap on the watch window, for a server that is not ticking. */
    static final long WINDOW_WALL_CAP_MS = 8_000L;

    /** One sample of everything the checks measure. Keyed by check index. */
    private record Sample(Map<Integer, Object> byCheck, long tick) {
    }

    /** One entity's state at a sample. */
    private record Body(UUID id, double distToChild, float health, boolean alive, boolean follows) {
    }

    /** A creature count: {@code own} of {@code total} are this wish's, counted wherever they are. */
    private record CreatureTally(int total, int own) {
    }

    /** A block count over the loaded part of the cube; {@code unloaded} chunks were not looked in. */
    record BlockTally(int n, int unloaded) {
    }

    /** What this wish handed over that is {@code what}, and how much of it is still with him. */
    private record ItemTally(int handed, int withHim) {
    }

    /** A follower is "in another dimension" this far away: never with him, never closing. */
    static final double ELSEWHERE = 1.0e6;

    /**
     * Observe every check. The result is in the same order as {@code checks}; a check the server could
     * not answer comes back as not holding, with why.
     */
    public static List<ClaimJudge.Observed> observe(WishRun run, List<ClaimCheck> checks) {
        List<ClaimJudge.Observed> out = new ArrayList<>();
        if (checks.isEmpty()) {
            return out;
        }
        try {
            Sample first = AgentBridge.onServer(() -> sample(run, checks), 5_000L);
            if (checks.stream().anyMatch(ClaimCheck::watched)) {
                waitTicks(first.tick() + ClaimJudge.WINDOW_TICKS);
            }
            Sample second = AgentBridge.onServer(() -> sample(run, checks), 5_000L);
            for (int i = 0; i < checks.size(); i++) {
                out.add(judge(checks.get(i), first.byCheck().get(i), second.byCheck().get(i)));
            }
        } catch (RuntimeException e) {
            // The world could not be asked. Not a pass: the claim is told as "I tried".
            out.clear();
            for (int i = 0; i < checks.size(); i++) {
                out.add(null);
            }
        }
        return out;
    }

    private static void waitTicks(long untilTick) {
        long deadline = System.currentTimeMillis() + WINDOW_WALL_CAP_MS;
        while (System.currentTimeMillis() < deadline) {
            MinecraftServer server = AgentBridge.serverOrNull();
            if (server == null || server.getTickCount() >= untilTick) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ── one sample, on the server thread ───────────────────────────────────────────────────

    private static Sample sample(WishRun run, List<ClaimCheck> checks) {
        MinecraftServer server = AgentBridge.serverOrNull();
        Map<Integer, Object> by = new HashMap<>();
        if (server == null) {
            return new Sample(by, 0);
        }
        ServerPlayer child = run == null ? null : server.getPlayerList().getPlayer(run.playerId());
        if (child == null) {
            child = ActingChild.of(server, run == null ? null : run.playerId());
        }
        for (int i = 0; i < checks.size(); i++) {
            ClaimCheck c = checks.get(i);
            try {
                by.put(i, measure(server, run, child, c));
            } catch (RuntimeException e) {
                by.put(i, "could not measure: " + e.getMessage());
            }
        }
        return new Sample(by, server.getTickCount());
    }

    private static Object measure(MinecraftServer server, WishRun run, ServerPlayer child, ClaimCheck c) {
        switch (c.kind()) {
            case CREATURE_COUNT -> {
                // OWN creatures wherever they are; a radius only for OTHER things.
                Map<UUID, LivingEntity> own = own(server, run, c.what());
                Place p = place(server, run, child, c.near());
                if (p == null && own.isEmpty()) {
                    return "there is no wish spot and no child to count around";
                }
                int others = p == null ? 0 : p.level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(p.at, p.at).inflate(c.radius()),
                        e -> e.isAlive() && !own.containsKey(e.getUUID()) && matches(e, c.what())).size();
                return new CreatureTally(own.size() + others, own.size());
            }
            case FOLLOWS, STAYS_HEALTHY -> {
                Map<UUID, LivingEntity> own = own(server, run, c.what());
                Place p = c.kind() == ClaimCheck.Kind.FOLLOWS || child != null
                        ? place(server, run, child, ClaimCheck.Near.CHILD)
                        : place(server, run, child, ClaimCheck.Near.WISH);
                if (p == null) {
                    return "the child is not in the world";
                }
                Map<UUID, Body> bodies = new java.util.LinkedHashMap<>();
                for (LivingEntity e : own.values()) {
                    bodies.put(e.getUUID(), body(e, p));
                }
                // "His fire won't burn him" is about HIS creature: when this wish has one, it is the
                // one watched. A follower is picked from his own AND whatever is near him, by the
                // same rule as before, so a pet he already had still counts.
                if (c.kind() == ClaimCheck.Kind.FOLLOWS || own.isEmpty()) {
                    for (LivingEntity e : p.level.getEntitiesOfClass(LivingEntity.class,
                            new AABB(p.at, p.at).inflate(SEARCH), e -> matches(e, c.what()))) {
                        bodies.putIfAbsent(e.getUUID(), body(e, p));
                    }
                }
                return new ArrayList<>(bodies.values());
            }
            case BLOCK_PRESENT -> {
                Place p = place(server, run, child, c.near());
                if (p == null) {
                    return "there is no wish spot and no child to look around";
                }
                return countBlocks(p.level, BlockPos.containing(p.at), c.radius(), c.what());
            }
            case ITEM_IN_INVENTORY -> {
                return items(server, run, child, c.what());
            }
            case EFFECT_ON -> {
                if (child == null) {
                    return "the child is not in the world";
                }
                for (MobEffectInstance fx : child.getActiveEffects()) {
                    String id = fx.getEffect().unwrapKey().map(k -> k.location().toString()).orElse("");
                    if (idMatches(id, c.what())) {
                        return 1;
                    }
                }
                return 0;
            }
            case DATAPACK_ACTIVE -> {
                String w = c.what().toLowerCase(Locale.ROOT);
                if (DatapackReloader.isEnabled(server, c.what())) {
                    return 1;
                }
                for (String id : server.getPackRepository().getSelectedIds()) {
                    if (id.toLowerCase(Locale.ROOT).contains(w)) {
                        return 1;
                    }
                }
                return 0;
            }
            default -> {
                return "unknown check";
            }
        }
    }

    private static Body body(LivingEntity e, Place p) {
        double d = e.level() == p.level ? e.position().distanceTo(p.at) : ELSEWHERE;
        return new Body(e.getUUID(), d, e.getHealth(), e.isAlive(),
                Boolean.TRUE.equals(PetBehaviours.of(e).follow()));
    }

    // ── identity: this wish's own creatures, this wish's own hand-overs ─────────────

    /**
     * This wish's own creatures that are {@code what}, alive, wherever they are (any loaded level):
     * the carriers it spawned, what its commands spawned, and the mobs it changed. Keyed by UUID.
     */
    static Map<UUID, LivingEntity> own(MinecraftServer server, WishRun run, String what) {
        Map<UUID, LivingEntity> out = new java.util.LinkedHashMap<>();
        if (server == null || run == null) {
            return out;
        }
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        for (var s : run.creaturesSpawned()) {
            if (s.carrier() != null) {
                ids.add(s.carrier());
            }
        }
        for (var p : run.commandSpawned()) {
            ids.add(p.id());
        }
        String wishKey = run.wishId() == null ? "" : run.wishId().toString();
        for (var h : com.moddingfromamod.mob.EntityHandles.global().recent(Integer.MAX_VALUE)) {
            for (var t : h.history()) {
                if (!wishKey.isEmpty() && wishKey.equals(t.wishId())) {
                    ids.add(h.id());
                    break;
                }
            }
        }
        for (UUID id : ids) {
            LivingEntity e = com.moddingfromamod.mob.EntityCapture.findAnywhere(server, id);
            if (e != null && matches(e, what)) {
                out.put(id, e);
            }
        }
        return out;
    }

    /**
     * What THIS wish handed over that is {@code what} — by wish key, registry id or name — and how
     * much of it is still with the child: hotbar, inventory, the item on his cursor, his Wish Bag.
     * Each hand-over counts at most what it handed over, so an older wish's identical thing cannot
     * stand in for one this wish gave and he threw away.
     */
    private static Object items(MinecraftServer server, WishRun run, ServerPlayer child, String what) {
        if (run == null || run.wishId() == null) {
            return "there is no wish to ask what it handed over";
        }
        if (child == null) {
            return "the child is not in the world";
        }
        List<ItemStack> likes = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (var h : com.moddingfromamod.item.delivery.WishDelivery.handedOverBy(run.wishId().toString())) {
            if (!h.player().equals(run.playerId()) || !itemMatches(h.like(), what)) {
                continue;
            }
            int at = -1;
            for (int i = 0; i < likes.size(); i++) {
                if (sameThing(likes.get(i), h.like())) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                likes.add(h.like());
                counts.add(h.count());
            } else {
                counts.set(at, counts.get(at) + h.count());
            }
        }
        int handed = counts.stream().mapToInt(Integer::intValue).sum();
        if (handed == 0) {
            return new ItemTally(0, 0);
        }
        List<ItemStack> held = new ArrayList<>();
        var inv = child.getInventory();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            held.add(inv.getItem(s));
        }
        held.add(child.containerMenu.getCarried());
        held.addAll(com.moddingfromamod.bag.WishBagService.contents(server, run.playerId()));
        int withHim = 0;
        for (int i = 0; i < likes.size(); i++) {
            int have = 0;
            for (ItemStack st : held) {
                if (!st.isEmpty() && sameThing(likes.get(i), st)) {
                    have += st.getCount();
                }
            }
            withHim += Math.min(have, counts.get(i));
        }
        return new ItemTally(handed, withHim);
    }

    /** Is this stack {@code what}: its wish key exactly, its registry id, or the name on it. */
    static boolean itemMatches(ItemStack st, String what) {
        return !st.isEmpty() && (ClaimJudge.keyMatches(com.moddingfromamod.item.WishItem.wishFeature(st), what)
                || idMatches(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), what)
                || nameMatches(st.getHoverName().getString(), what));
    }

    /**
     * The same THING as a hand-over: a made item by its wish key (so a bow that took damage, or a
     * stack a later restamp touched, is still the bow this wish gave); anything else by item and
     * components, damage aside.
     */
    static boolean sameThing(ItemStack like, ItemStack st) {
        if (like.isEmpty() || st.isEmpty() || like.getItem() != st.getItem()) {
            return false;
        }
        String key = com.moddingfromamod.item.WishItem.wishFeature(like);
        if (!key.isEmpty()) {
            return key.equals(com.moddingfromamod.item.WishItem.wishFeature(st));
        }
        ItemStack a = like.copyWithCount(1);
        ItemStack b = st.copyWithCount(1);
        a.remove(net.minecraft.core.component.DataComponents.DAMAGE);
        b.remove(net.minecraft.core.component.DataComponents.DAMAGE);
        return ItemStack.isSameItemSameComponents(a, b);
    }

    // ── blocks: the radius asked for, never a silent cap ─────────────────────────────

    /**
     * Count {@code what} in the cube of {@code r} about {@code centre}, by chunk section: past the
     * old cube of {@link #OLD_CUBE} a chunk that is not loaded is skipped and reported (asking for
     * it would load or generate it on the server thread), and a section whose palette cannot hold
     * the block is never walked. At
     * {@link ClaimCheck#MAX_RADIUS} that is 97³ ≈ 913k positions of which nearly every section is
     * skipped — timed by {@code ClaimIdentityGameTest}.
     */
    static BlockTally countBlocks(ServerLevel level, BlockPos centre, int r, String what) {
        int x0 = centre.getX() - r;
        int x1 = centre.getX() + r;
        int y0 = centre.getY() - r;
        int y1 = centre.getY() + r;
        int z0 = centre.getZ() - r;
        int z1 = centre.getZ() + r;
        // Which blocks `what` names, resolved ONCE: an id comparison per position is string work
        // ×913k, and was most of the scan's time when it was measured.
        java.util.Set<net.minecraft.world.level.block.Block> wanted = new java.util.HashSet<>();
        for (var b : BuiltInRegistries.BLOCK) {
            if (idMatches(BuiltInRegistries.BLOCK.getKey(b).toString(), what)) {
                wanted.add(b);
            }
        }
        if (wanted.isEmpty()) {
            return new BlockTally(0, 0);
        }
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> is =
                st -> wanted.contains(st.getBlock());
        boolean wantsAir = wanted.stream().anyMatch(b -> b.defaultBlockState().isAir());
        int n = 0;
        int unloaded = 0;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null && withinOldCube(centre, cx, cz)) {
                    // Inside the cube of 24 this scan used to be, a chunk is LOADED as it always
                    // was (getBlockState loaded it), so nothing the old judge counted can be
                    // missed: a radius widened from 24 counts a superset, never less.
                    chunk = level.getChunk(cx, cz);
                }
                if (chunk == null) {
                    unloaded++;
                    continue;
                }
                var sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    var section = sections[i];
                    int sy = chunk.getSectionYFromSectionIndex(i) << 4;
                    if (section == null || (section.hasOnlyAir() && !wantsAir) || sy + 15 < y0 || sy > y1
                            || !section.getStates().maybeHas(is)) {
                        continue;
                    }
                    int ax = Math.max(x0, cx << 4);
                    int bx = Math.min(x1, (cx << 4) + 15);
                    int ay = Math.max(y0, sy);
                    int by = Math.min(y1, sy + 15);
                    int az = Math.max(z0, cz << 4);
                    int bz = Math.min(z1, (cz << 4) + 15);
                    if (!section.getStates().maybeHas(is.negate())) {
                        // Its palette holds nothing else: every position matches, without walking.
                        n += (bx - ax + 1) * (by - ay + 1) * (bz - az + 1);
                        continue;
                    }
                    for (int x = ax; x <= bx; x++) {
                        for (int y = ay; y <= by; y++) {
                            for (int z = az; z <= bz; z++) {
                                if (is.test(section.getBlockState(x & 15, y & 15, z & 15))) {
                                    n++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return new BlockTally(n, unloaded);
    }

    /** The radius {@code block_present} scanned, whose chunks it loaded on demand. */
    static final int OLD_CUBE = 24;

    private static boolean withinOldCube(BlockPos centre, int cx, int cz) {
        return cx >= (centre.getX() - OLD_CUBE) >> 4 && cx <= (centre.getX() + OLD_CUBE) >> 4
                && cz >= (centre.getZ() - OLD_CUBE) >> 4 && cz <= (centre.getZ() + OLD_CUBE) >> 4;
    }

    // ── the two samples, judged ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ClaimJudge.Observed judge(ClaimCheck c, Object a, Object b) {
        if (a instanceof String why) {
            return new ClaimJudge.Observed(false, why);
        }
        if (b instanceof String why) {
            return new ClaimJudge.Observed(false, why);
        }
        switch (c.kind()) {
            case CREATURE_COUNT -> {
                CreatureTally end = (CreatureTally) b;
                return ClaimJudge.count(c, ((CreatureTally) a).total(), end.total(), end.own());
            }
            case BLOCK_PRESENT -> {
                BlockTally end = (BlockTally) b;
                int n = end.n();
                String what = n + "× " + c.what() + " within " + c.radius() + " blocks"
                        + (end.unloaded() > 0 ? " (" + end.unloaded() + " chunk(s) of that cube are not "
                                + "loaded, so not counted)" : "");
                return new ClaimJudge.Observed(n >= c.min(), what + (n >= c.min() ? "" : "; the claim needs " + c.min()));
            }
            case ITEM_IN_INVENTORY -> {
                ItemTally end = (ItemTally) b;
                return ClaimJudge.items(c, end.handed(), end.withHim());
            }
            case EFFECT_ON -> {
                boolean on = (Integer) b > 0;
                return new ClaimJudge.Observed(on, on ? "he has " + c.what() : "he does not have " + c.what() + " on him");
            }
            case DATAPACK_ACTIVE -> {
                boolean on = (Integer) b > 0;
                return new ClaimJudge.Observed(on, on ? "datapack " + c.what() + " is enabled"
                        : "no enabled datapack is called " + c.what());
            }
            case FOLLOWS -> {
                List<Body> start = (List<Body>) a;
                List<Body> end = (List<Body>) b;
                if (start.isEmpty() || end.isEmpty()) {
                    return ClaimJudge.follows(c.what(), false, false, 0, 0);
                }
                // The one he means is the one set to follow, nearest first.
                Body pick = start.stream().filter(Body::follows)
                        .min(java.util.Comparator.comparingDouble(Body::distToChild))
                        .orElse(start.stream().min(java.util.Comparator.comparingDouble(Body::distToChild)).get());
                Body later = end.stream().filter(x -> x.id().equals(pick.id())).findFirst().orElse(null);
                if (later == null) {
                    return new ClaimJudge.Observed(false, "the " + c.what() + " disappeared while I watched");
                }
                return ClaimJudge.follows(c.what(), true, pick.follows(), pick.distToChild(), later.distToChild());
            }
            case STAYS_HEALTHY -> {
                List<Body> start = (List<Body>) a;
                List<Body> end = (List<Body>) b;
                if (start.isEmpty()) {
                    return ClaimJudge.staysHealthy(c.what(), false, false, 0, 0);
                }
                float h0 = 0;
                float h1 = 0;
                boolean alive = true;
                for (Body s : start) {
                    h0 += s.health();
                    Body e = end.stream().filter(x -> x.id().equals(s.id())).findFirst().orElse(null);
                    if (e == null || !e.alive()) {
                        alive = false;
                    } else {
                        h1 += e.health();
                    }
                }
                return ClaimJudge.staysHealthy(c.what(), true, alive, h0, h1);
            }
            default -> {
                return new ClaimJudge.Observed(false, "unknown check");
            }
        }
    }

    // ── where, and what matches ────────────────────────────────────────────────────────────

    private record Place(ServerLevel level, Vec3 at) {
    }

    private static Place place(MinecraftServer server, WishRun run, ServerPlayer child, ClaimCheck.Near near) {
        if (near == ClaimCheck.Near.WISH && run != null && run.anchor() != null) {
            var spot = run.anchor();
            ServerLevel level = null;
            try {
                level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(spot.dimension())));
            } catch (RuntimeException ignored) {
                // an unreadable dimension falls through to the child
            }
            if (level == null) {
                level = server.getLevel(Level.OVERWORLD);
            }
            if (level != null) {
                return new Place(level, new Vec3(spot.x() + 0.5, spot.y(), spot.z() + 0.5));
            }
        }
        if (child != null && child.level() instanceof ServerLevel sl) {
            return new Place(sl, child.position());
        }
        return null;
    }

    /**
     * <b>What a claim check counts — asked of the one resolver.</b>
     *
     * <p>A wished creature and a rig's machinery are {@code CreatureMatch}'s to decide, as they are
     * for {@code modify_mob}, {@code look_around} and the ladder: a hit proxy is never anything (a
     * Muse retry that asked for {@code llama} was counting one), and a creature is never its
     * carrier's type. On top of the resolver, two rules this check needs, both measured:
     * <ul>
     *   <li>a creature's species or name matches on its LEADING WORDS, spaces and underscores the
     *       same — the agent checked {@code pickleball_player} for species
     *       {@code pickleball_player_a} named "Pickleball Player Red", and an exact match counted 0,
     *       a false refusal;</li>
     *   <li>a real mob matches its type EXACTLY ({@link #idMatches}), or the name over its head —
     *       a count of "zombie" is not a count of zombie villagers.</li>
     * </ul>
     */
    static boolean matches(Entity e, String what) {
        if (e == null || e instanceof ServerPlayer) {
            return false;
        }
        String w = ClaimJudge.words(what);
        if (w.isEmpty()) {
            return false;
        }
        var seen = com.moddingfromamod.mob.Creatures.seen(e);
        switch (com.moddingfromamod.mob.CreatureMatch.what(seen)) {
            case INTERNAL:
                return false;
            case CREATURE: {
                if (com.moddingfromamod.mob.CreatureMatch.matches(seen, what)) {
                    return true;
                }
                for (String tag : e.getTags()) {
                    if (WishMobTags.isSpeciesTag(tag) && ClaimJudge.leading(
                            ClaimJudge.words(tag.substring(WishMobTags.PREFIX.length())), w)) {
                        return true;
                    }
                }
                return e.hasCustomName() && ClaimJudge.leading(
                        ClaimJudge.words(KidTyping.plain(e.getCustomName().getString())), w);
            }
            default:
                return idMatches(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), what)
                        || (e.hasCustomName() && ClaimJudge.leading(ClaimJudge.words(
                                KidTyping.plain(e.getCustomName().getString())), w));
        }
    }

    static boolean idMatches(String id, String what) {
        String w = what.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        String i = id.toLowerCase(Locale.ROOT);
        return i.equals(w) || i.equals("minecraft:" + w)
                || (i.indexOf(':') >= 0 && i.substring(i.indexOf(':') + 1).equals(w));
    }

    static boolean nameMatches(String name, String what) {
        String n = ClaimJudge.words(KidTyping.plain(name == null ? "" : name));
        String w = ClaimJudge.words(what);
        return !w.isEmpty() && (n.equals(w) || ("_" + n + "_").contains("_" + w + "_"));
    }
}
