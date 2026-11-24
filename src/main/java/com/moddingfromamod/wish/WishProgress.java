package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps telling the child that his wish is being worked on.
 *
 * <p>This exists because of a real moment that looked exactly like a broken mod. He asked for a
 * giant airplane; the agent picked it up in under a millisecond, searched the installed mods, found
 * nothing, and spent <b>88 seconds</b> composing a 9,468-block aeroplane out of shapes. All he saw
 * in that time was one line — <i>"I don't know how to do that one yet"</i> — and then silence. It
 * worked perfectly and still felt broken, which for a seven-year-old is the same as broken.
 *
 * <p>Two things were wrong and both are fixed here: that message was written when escalation meant
 * "maybe someday" and now means "working on it right now", and nothing said anything while the
 * agent thought. A big build is genuinely slow — most of that time was real work — so the answer is
 * honest, continuous feedback rather than pretending it will be quick.
 *
 * <p>On {@code NeoForge.EVENT_BUS}. State is per-player and in memory; the worst case if it is lost
 * is that the ticker stops, which is exactly what happens when the wish finishes anyway.
 */
public final class WishProgress {

    /** How often to refresh the action bar. Slow enough to be calm, fast enough to look alive. */
    private static final int TICK_INTERVAL = 20;

    /**
     * Give up on a wish the agent never even <b>picked up</b> (still PENDING), so a lost wish doesn't
     * nag forever. A wish the agent is actively working (RUNNING) is NOT subject to this — see the
     * ticker. This was the "half-timeout" bug: the HUD wiped after 6 min while the agent was still
     * building, and the thing appeared a minute later.
     */
    private static final long MAX_WAIT_MS = 6 * 60_000L;

    /** Final safety valve even for a wish the agent is actively working, so a wedged run self-heals. */
    private static final long ABSOLUTE_MAX_MS = 30 * 60_000L;

    /** A little animation, so it reads as "working" rather than a frozen string. */
    private static final String[] SPINNER = {"✨", "⭐", "🌟", "⭐"};

    private static final Map<UUID, Waiting> WAITING = new ConcurrentHashMap<>();

    private record Waiting(UUID wishId, long startedMs) {
    }

    /**
     * What has actually HAPPENED to each wish, keyed by wish: submitted, claimed, planned,
     * and the last real event of any kind. The action bar is chosen from this by {@link WishStage},
     * never from the clock alone. Created in {@link #started}; the hooks below only ever update a
     * track that exists, so a wish this class never saw cannot leak one.
     */
    private static final Map<UUID, WishStage.Track> TRACKS = new ConcurrentHashMap<>();

    static {
        // a cold brief tells the bar which character it is looking up before a worker can take the wish.
        com.moddingfromamod.reference.WishBriefs.lookingUpListener = WishProgress::lookingUp;
    }

    /**
     * What Moddy is doing <b>right now</b>, in the agent's own kid-facing words — set by the agent
     * via {@code set_status} as it moves through dreaming → building → checking → finishing. This is
     * what turns "Working out how to make it… (42s)" into "🏗️ Building your purple crystal
     * lighthouse... (42s)". Per-player; replaced each time the agent narrates a new step, cleared when
     * the wish finishes so it never bleeds onto the next one.
     */
    private static final Map<UUID, Status> STATUS = new ConcurrentHashMap<>();

    /** The named steps of a wish, each with a little bit of magic on it. */
    public enum Phase {
        DREAMING("🔮"), BUILDING("🏗"), CHECKING("🔍"), FINISHING("✨"), CASTING("🪄");

        final String emoji;

        Phase(String emoji) {
            this.emoji = emoji;
        }

        /** Tolerant of however the agent phrases it ("verifying", "testing", "making", ...). */
        public static Phase parse(String s) {
            if (s == null) {
                return CASTING;
            }
            String t = s.toLowerCase().strip();
            if (t.contains("dream") || t.contains("think") || t.contains("plan")
                    || t.contains("imagin")) {
                return DREAMING;
            }
            if (t.contains("build") || t.contains("mak") || t.contains("sculpt")
                    || t.contains("construct") || t.contains("grow")) {
                return BUILDING;
            }
            if (t.contains("check") || t.contains("verif") || t.contains("test")
                    || t.contains("sure")) {
                return CHECKING;
            }
            if (t.contains("finish") || t.contains("polish") || t.contains("final")
                    || t.contains("sparkle")) {
                return FINISHING;
            }
            return CASTING;
        }
    }

    private record Status(String doing, Phase phase, long setMs) {
    }

    /** Newer wishes a child submitted while Moddy was still busy — acknowledged once, per player. */
    private static final Map<UUID, Set<UUID>> ANNOUNCED_QUEUED = new ConcurrentHashMap<>();

    /**
     * Where the magic is going to land, if the agent has said.
     *
     * <p>Sparkles around the child say "something is happening"; sparkles at a <b>spot</b> say
     * "something is happening THERE", which is far better — he turns and watches the right patch of
     * sky instead of staring at chat. The agent sets this as soon as it knows, which is usually
     * long before it has finished composing the build.
     */
    private static final Map<UUID, BlockPos> FOCUS = new ConcurrentHashMap<>();

    /**
     * <b>Lock where the wish manifests</b> — the agent's decision, via conjure_at. This same spot is
     * where the sparkles gather AND where the build is anchored, so "where it looked like it was
     * coming" and "where it actually appeared" are guaranteed to match. Overrides any wish-time
     * default.
     */
    public static void focusOn(ServerPlayer player, BlockPos where) {
        if (player == null || where == null) {
            return;
        }
        // ONE FOCUS PER WISH, not one per player. With two in flight, a per-player spot means the
        // second wish's conjure_at moves the FIRST wish's sparkles — the child watches the wrong
        // patch of sky, and then something appears somewhere he was never looking.
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (run != null) {
            run.lockAnchor(new com.moddingfromamod.marshal.Spot(
                    player.level().dimension().identifier().toString(),
                    where.getX(), where.getY(), where.getZ()));
        }
        focusOn(player.getUUID(), where);
    }

    /** UUID seam (testable without a game player). Locks the manifest spot, overriding any default. */
    static void focusOn(UUID playerId, BlockPos where) {
        if (playerId != null && where != null) {
            FOCUS.put(playerId, where);
        }
    }

    /**
     * The wish-time <b>default</b> anchor (where the watch was aiming), set only if nothing is locked
     * yet — so a later conjure_at wins, and a wish queued while another is being granted can't clobber
     * the active one's spot. Also starts the sparkles gathering there the instant the wish is made.
     */
    public static void defaultAnchor(ServerPlayer player, BlockPos where) {
        if (player == null || where == null) {
            return;
        }
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (run != null) {
            run.defaultAnchor(new com.moddingfromamod.marshal.Spot(
                    player.level().dimension().identifier().toString(),
                    where.getX(), where.getY(), where.getZ()));
        }
        defaultAnchor(player.getUUID(), where);
    }

    /** UUID seam. Sets the default spot only if none is locked yet (putIfAbsent). */
    static void defaultAnchor(UUID playerId, BlockPos where) {
        if (playerId != null && where != null) {
            FOCUS.putIfAbsent(playerId, where);
        }
    }

    /**
     * The locked spot where the current wish will manifest (and where the sparkles are), or null.
     *
     * <p>The CALLING wish's spot when one can be identified, so two builds land at their own anchors
     * rather than both at whichever was written last. The per-player map is the fallback for
     * everything that has no run — the offline template engine, and a single-wish session, where the
     * two answers are identical anyway.
     */
    public static BlockPos manifestAnchor(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        if (SERVED_SPOT != null) {
            return SERVED_SPOT;
        }
        BlockPos mine = currentWishAnchor();
        return mine != null ? mine : FOCUS.get(player.getUUID());
    }

    /**
     * The CALLING wish's own locked spot, or null when no run can be identified.
     *
     * <p>Its own method because it needs no player at all — which is the whole point of the change,
     * and also the only way a headless gate can assert that two wishes really do anchor
     * independently. Asking through a player is what made this a per-player value in the first place.
     */
    public static BlockPos currentWishAnchor() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (run == null) {
            return null;
        }
        var spot = run.anchor();
        return spot == null ? null : new BlockPos(spot.x(), spot.y(), spot.z());
    }

    /**
     * A served wish's locked spot, set only while that wish's own commands run on the server
     * thread, so its build, creature or picture lands where its pillar stood. Never FOCUS: FOCUS is
     * {@code putIfAbsent}, so a served spot left there would hijack his next agent wish.
     */
    private static BlockPos SERVED_SPOT;
    /** The served wish whose commands are running, for its building's dome. */
    private static UUID SERVED_WISH;

    /** The served spot in force right now, or null. */
    public static BlockPos servedSpot() {
        return SERVED_SPOT;
    }

    /** The served wish whose commands are running right now, or null. */
    public static UUID servedWish() {
        return SERVED_WISH;
    }

    /** Run {@code body} as served wish {@code wishId} at {@code spot}; nulls run it as neither. */
    public static <T> T withServedSpot(BlockPos spot, UUID wishId, java.util.function.Supplier<T> body) {
        BlockPos was = SERVED_SPOT;
        UUID wasWish = SERVED_WISH;
        SERVED_SPOT = spot;
        SERVED_WISH = wishId;
        try {
            return body.get();
        } finally {
            SERVED_SPOT = was;
            SERVED_WISH = wasWish;
        }
    }

    /** UUID seam. */
    static BlockPos manifestAnchor(UUID playerId) {
        return playerId == null ? null : FOCUS.get(playerId);
    }

    private static int tickCounter;

    private WishProgress() {
    }

    /**
     * A wish has gone to the agent — start reassuring the child. If one of this child's wishes is
     * ALREADY being worked on, this newer wish does NOT hijack the ticker: it stays queued (the tick
     * loop notices it, tells the child it's heard, and promotes it once the active one finishes).
     */
    public static void started(ServerPlayer player, Wish wish) {
        if (player != null && wish != null) {
            long now = System.currentTimeMillis();
            WAITING.putIfAbsent(player.getUUID(), new Waiting(wish.id(), now));
            TRACKS.putIfAbsent(wish.id(), new WishStage.Track(now));
            // Fresh wish, fresh finish_building requirement — otherwise the guard covers only the first wish
            // of a session and then silently stops.
            BuildGate.startWish(wish.id());      // THIS wish's gate; another wish's is untouched
        }
    }

    /**
     * <b>A worker picked this wish up</b> — the bar moves from "Reading your wish…" to
     * "Working out how to make it…". Called at the claim ({@code AwaitWishTool}, right after
     * {@code Marshal.assign}); the ticker also counts a RUNNING wish as claimed, so a claim path
     * that forgets to say so is a second late, never missing. Any thread.
     */
    public static void claimed(UUID wishId) {
        WishStage.Track t = wishId == null ? null : TRACKS.get(wishId);
        if (t != null) {
            t.claimed(System.currentTimeMillis());
        }
    }

    /** The bar's stage words for this wish now, with no plan step or agent line (for a GameTest). */
    public static String stageWordsForTest(UUID wishId) {
        return WishStage.choose(System.currentTimeMillis(), wishId == null ? null : TRACKS.get(wishId), "", null, 0)
                .words();
    }

    /**
     * <b>The character this wish names is being looked up</b>: the bar reads "Looking up Scorch…" until
     * a worker takes the wish, rather than "Reading your wish…" for the whole wait. Any thread.
     */
    public static void lookingUp(UUID wishId, String name) {
        if (wishId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // computeIfAbsent: the brief starts before the bar does (WishService), and a fast resolve can beat it;
        // startThinking's putIfAbsent then keeps this track.
        TRACKS.computeIfAbsent(wishId, id -> new WishStage.Track(now)).lookingUp(name, now);
    }

    /**
     * <b>Something real happened to this wish</b> — a tool call arrived ({@code ToolNarrator.begin}).
     * It does not change the stage; it only tells the bar the wish is not quiet, so the "still
     * working" lines wait for real silence. Any thread.
     */
    public static void event(UUID wishId) {
        WishStage.Track t = wishId == null ? null : TRACKS.get(wishId);
        if (t != null) {
            t.event(System.currentTimeMillis());
        }
    }

    private static void planned(UUID wishId) {
        WishStage.Track t = wishId == null ? null : TRACKS.get(wishId);
        if (t != null) {
            t.planned(System.currentTimeMillis());
        }
    }

    /**
     * The agent narrates what it's doing right now (via the {@code set_status} tool). Shows up on the
     * action bar in place of the stage line, with a bit of phase magic, for as long as it is the
     * newest thing that happened ({@link WishStage#choose}).
     */
    public static void setStatus(ServerPlayer player, String doing, String phase) {
        if (player == null || doing == null || doing.isBlank()) {
            return;
        }
        String trimmed = doing.length() > 90 ? doing.substring(0, 90) : doing;
        STATUS.put(player.getUUID(),
                new Status(trimmed.strip(), Phase.parse(phase), System.currentTimeMillis()));
    }

    /**
     * What the child's status line currently says, or "" — for tests and diagnostics.
     *
     * <p>Exists because the mod-side narrator is otherwise unobservable: it writes to a
     * private map and shows up on an action bar, so a headless test could only assert that calling
     * it did not throw, which is the shape of check that passes while nothing works.
     */
    public static String currentStatus(java.util.UUID playerId) {
        Status s = STATUS.get(playerId);
        return s == null ? "" : s.doing();
    }

    // ---- the plan + its progress bar ----
    //
    // "Making something amazing…" for eleven minutes is indistinguishable from a hang. A bar reading
    // "Giving Spikor his fire (2 of 3)" is a promise with an end in sight, and a 7-year-old can read
    // it. See WishPlan for why planning up front also fixes the build ORDER.

    /**
     * Keyed by WISH, not by child — one bar each, stacked.
     *
     * <p><b>This was per-player, and with more than one wish in flight that was three bugs.</b>
     * {@code startThinking} refused to make a second plan ("a real plan always wins"), so wishes two
     * and three were silent; {@code setPlan} then overwrote whichever plan was there, so the one bar
     * could show another wish's steps under the first wish's name; and the FIRST wish to finish
     * called {@code clearPlan(playerId)} and took the bar down while two were still building —
     * indistinguishable from everything having stopped.
     *
     * <p>Vanilla stacks boss bars natively, so one {@code ServerBossEvent} per wish IS the
     * multi-progress UI; there is nothing to invent. Uncapped on purpose: six wishes in flight
     * means six bars is the correct picture of the world, screen space limits it naturally, and a
     * child who thinks it is too busy has a stop button on every one of them.
     */
    private record Bar(net.minecraft.server.level.ServerBossEvent event, UUID playerId) { }

    private static final Map<UUID, WishPlan> PLANS = new ConcurrentHashMap<>();
    private static final Map<UUID, Bar> BARS = new ConcurrentHashMap<>();
    /** What a wish cost from the wish bank, said on its bar; absent where there is no bank. */
    private static final Map<UUID, String> COSTS = new ConcurrentHashMap<>();

    /**
     * Who owns which bar, and what it is called — kept in a pure class so the RULE is unit-tested.
     *
     * <p>Everything that went wrong here was a question about a map: can a second wish open a bar,
     * does a third overwrite the second, does closing one close the others. None of it needs
     * Minecraft, and a GameTest could not have covered it anyway — asserting it on a real server
     * needs a mock player in the level, and Create broadcasts {@code sync_rail_graph} to the player
     * list, which crashes on a player with no connection.
     */
    private static final com.moddingfromamod.marshal.BarBook BOOK =
            new com.moddingfromamod.marshal.BarBook();

    /**
     * Show a placeholder bar while Moddy is still working out WHAT to build.
     *
     * <p>The real bar needs a plan, and the plan is the single slowest thinking step of the wish —
     * measured at 69 seconds on one run, during which the child had nothing but a generic ticker.
     * A one-step stand-in costs nothing and is replaced the moment {@link #setPlan} arrives.
     */
    public static void startThinking(ServerPlayer player, UUID wishId, String name) {
        if (player == null || wishId == null || PLANS.containsKey(wishId)) {
            return;   // a real plan always wins
        }
        WishPlan placeholder = WishPlan.thinking();
        PLANS.put(wishId, placeholder);
        BOOK.open(wishId, ownerIdFor(player, wishId), name);
        refreshBar(player, wishId);
    }

    /** Record what Moddy is about to build, and show the child a bar for it. */
    public static void setPlan(ServerPlayer player, UUID wishId, WishPlan plan) {
        if (player == null || wishId == null || plan == null) {
            return;
        }
        PLANS.put(wishId, plan);
        plan.startStep(1);
        if (!plan.isThinking()) {
            planned(wishId);
        }
        refreshBar(player, wishId);
    }

    /**
     * Name this wish's bar.
     *
     * <p>Same rule the watch uses: what the resolver decided he meant, falling back to a shortened
     * version of what he actually typed. Never blank — an unnamed bar among three is the problem
     * this exists to fix.
     */
    public static void nameBar(UUID wishId, String name) {
        if (wishId == null) {
            return;
        }
        BOOK.rename(wishId, name);
    }

    /** Move the plan on. Clamped inside {@link WishPlan}, so a miscount can't show "5 of 3". */
    public static void startStep(ServerPlayer player, UUID wishId, int step) {
        if (player == null || wishId == null) {
            return;
        }
        WishPlan plan = PLANS.get(wishId);
        if (plan != null) {
            plan.startStep(step);
            if (!plan.isThinking()) {
                planned(wishId);
            }
            refreshBar(player, wishId);
        }
    }

    public static void completePlan(ServerPlayer player, UUID wishId) {
        if (player == null || wishId == null) {
            return;
        }
        WishPlan plan = PLANS.get(wishId);
        if (plan != null) {
            plan.markComplete();
            refreshBar(player, wishId);
        }
    }

    /**
     * <b>The bar mentions what the wish cost</b>: set at pickup when the wish bank charged it,
     * shown on every refresh after. A bar already up is renamed now, so the cost appears the moment
     * it is taken rather than at the next status line.
     *
     * <p>{@code said} is the charge's own words for the bar (e.g. {@code " · uses 1 ✨"}), written by the charge
     * plug-in: the core never formats a price. Nothing is shown when no wish is charged.
     */
    public static void cost(UUID wishId, String said) {
        if (wishId == null || said == null || said.isEmpty()) {
            return;
        }
        COSTS.put(wishId, said);
        Bar bar = BARS.get(wishId);
        WishPlan plan = PLANS.get(wishId);
        if (bar != null && plan != null) {
            bar.event().setName(Component.literal(barName(wishId, plan)));
        }
    }

    /** The plan for one wish, or null. */
    /**
     * How many wishes are running right now.
     *
     * <p>Read by {@link CameraSupervisor}: the camera must outlive the child who asked, because a
     * wish outlives its wisher and killing the camera mid-wish loses the photographs the wish is
     * about to hand back. Counted from the BARS rather than a second register, so it cannot drift
     * from what the child can see happening.
     */
    public static int inFlight() {
        return BARS.size();
    }

    /** The wishes running right now, by id — {@link CameraNeed#demand}'s truth about "in flight". */
    public static java.util.Set<UUID> inFlightIds() {
        return java.util.Set.copyOf(BARS.keySet());
    }

    public static WishPlan planFor(UUID wishId) {
        return wishId == null ? null : PLANS.get(wishId);
    }

    /**
     * Draw it as <b>"name: what's happening"</b>.
     *
     * <p>The bar text used to be the status alone, which was fine while there was only ever one.
     * With three stacked, three lines of pure status say nothing about WHICH wish each belongs to —
     * so the name leads and the status follows it.
     */
    private static void refreshBar(ServerPlayer player, UUID wishId) {
        WishPlan plan = PLANS.get(wishId);
        if (plan == null) {
            return;
        }
        Bar bar = BARS.computeIfAbsent(wishId, k -> {
            // 🔴 THE BAR BELONGS TO THE WISH, NOT TO WHOEVER CALLED (D1).
            //
            // This used to be `addPlayer(player)`, which is correct only if every caller hands over
            // the right child — and two of them do not: PlanWishTool:112 and ConjureAtTool:72 both
            // resolve "the player" as getPlayers().stream().findFirst(), i.e. WHOEVER JOINED FIRST.
            // With one child that is the same person and the bug is invisible; with two, every
            // wish's bar lands on the first child. Photographed in the first two-child session ever
            // run: Alice's screen carried her castle bar AND Bob's rocket bar, and Bob's screen
            // carried none (docs/MULTIPLAYER_DECISIONS.md §4c).
            //
            // Fixed HERE rather than at the two call sites because `computeIfAbsent` means the bar
            // is attached exactly once, by whichever caller happens to be first — so fixing one
            // site would leave the other deciding, which is the same bug with a different winner.
            // This is the choke point; a future caller inherits the rule without knowing it exists.
            ServerPlayer owner = ownerOf(player, wishId);
            ServerPlayer shownTo = owner == null ? player : owner;
            var b = new net.minecraft.server.level.ServerBossEvent(java.util.UUID.randomUUID(),
                    Component.literal("✨ Moddy"),
                    net.minecraft.world.BossEvent.BossBarColor.PURPLE,
                    net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
            b.addPlayer(shownTo);
            return new Bar(b, shownTo.getUUID());
        });
        bar.event().setName(Component.literal(barName(wishId, plan)));
        bar.event().setProgress(plan.fraction());
    }

    private static String barName(UUID wishId, WishPlan plan) {
        String name = BOOK.nameOf(wishId);
        String status = plan.progressLabel("Making your wish...");
        String cost = COSTS.get(wishId);
        return (name == null || name.isBlank() ? "✨ " + status : "✨ " + name + ": " + status)
                + (cost == null ? "" : cost);
    }

    /**
     * Whose wish is this? Resolved from the wish book by id, so it does not depend on the caller
     * having got the player right.
     *
     * <p>Returns null when the wish is not in the book (a probe's hand-made wish, a book not yet
     * loaded), and the caller then keeps the old behaviour rather than showing nobody a bar — an
     * unattached bar is strictly worse than one attached to the wrong child, because it is
     * invisible.
     */
    private static ServerPlayer ownerOf(ServerPlayer caller, UUID wishId) {
        var server = ((ServerLevel) caller.level()).getServer();
        if (server == null) {
            return null;
        }
        UUID id = ownerIdFor(caller, wishId);
        return id.equals(caller.getUUID()) ? caller : server.getPlayerList().getPlayer(id);
    }

    /**
     * Whose wish is this, by id — falling back to the caller's own player when the book cannot say.
     *
     * <p>⚠ <b>A bar has TWO ownership records and they must agree.</b> {@code addPlayer} decides who
     * the client SHOWS it to; {@link com.moddingfromamod.marshal.BarBook} decides who
     * {@link #barCount(UUID)} counts it for, which is what every gate reads. Fixing only the first
     * left the gate reporting the pre-fix answer over corrected code — a green build over a red
     * test, with the real behaviour already right. Both go through here.
     */
    private static UUID ownerIdFor(ServerPlayer caller, UUID wishId) {
        var server = ((ServerLevel) caller.level()).getServer();
        if (server != null) {
            Wish wish = WishBook.get(server).byId(wishId).orElse(null);
            if (wish != null && wish.playerId() != null) {
                return wish.playerId();
            }
        }
        return caller.getUUID();
    }

    /**
     * Take ONE wish's bar down. Called whenever that wish ends, however it ends — a bar left hanging
     * after the work stops is exactly the "is it still going?" ambiguity the bar exists to remove.
     *
     * <p>One wish's, not the child's. Clearing them all when the first of three finished is what
     * made two still-running wishes look dead.
     */
    public static void clearWishBar(UUID wishId) {   // a WISH id — see forget() for why that matters
        if (wishId == null) {
            return;
        }
        PLANS.remove(wishId);
        COSTS.remove(wishId);
        TRACKS.remove(wishId);
        BOOK.close(wishId);
        CameraNeed.shared().close(wishId);
        var bar = BARS.remove(wishId);
        if (bar != null) {
            bar.event().removeAllPlayers();
            bar.event().setVisible(false);
        }
    }

    /**
     * Wishes that are over FOR THE CHILD although not yet settled: {@code finish_building} has played
     * the end, and what remains (the photograph, the agent's verdict) is Moddy checking his own work.
     */
    private static final java.util.Set<UUID> ENDED_FOR_CHILD = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<UUID, Boolean>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<UUID, Boolean> e) {
                    return size() > 1024;   // a wish settles within minutes; this is a memory, not a log
                }
            }));

    /**
     * <b>The wish is over for him</b>: its bar comes down and its status line
     * with its running clock stops, the moment "All done" plays. The grading after it only
     * recommends; it is told in chat, and he goes about his business with nothing on screen waiting
     * for it. A newer wish of his is promoted onto the ticker exactly as when a wish settles.
     */
    public static void endForChild(ServerPlayer player, UUID wishId) {
        if (wishId == null) {
            return;
        }
        ENDED_FOR_CHILD.add(wishId);
        clearWishBar(wishId);
        if (player != null) {
            Waiting w = WAITING.get(player.getUUID());
            if (w != null && wishId.equals(w.wishId())) {
                STATUS.remove(player.getUUID());
                // blank the action bar now, rather than leave the last line up for its fade
                player.sendOverlayMessage(Component.empty());
            }
        }
    }

    /** Is this wish over for the child (its bar and clock gone), though maybe not yet settled? */
    public static boolean endedForChild(UUID wishId) {
        return wishId != null && ENDED_FOR_CHILD.contains(wishId);
    }

    /** Every bar this child has — for logout, and for "stop everything". */
    public static void clearAllFor(UUID playerId) {
        if (playerId == null) {
            return;
        }
        for (UUID wishId : BOOK.forPlayer(playerId)) {
            clearWishBar(wishId);
        }
    }

    /** How many bars this child is looking at. For the gates, and for reasoning about clutter. */
    public static int barCount(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        return BOOK.countFor(playerId);
    }

    /**
     * Is a progress bar showing for this child?
     *
     * <p>Exists for the gates. The bar lives in a private map and is otherwise only observable on a
     * real client, so a headless test could only assert that clearing it did not throw — which is
     * the shape of check that passes while nothing works.
     */
    public static boolean hasBar(UUID playerId) {
        return barCount(playerId) > 0;
    }

    /** Stop (world unload, or the player left). */
    public static void forget(UUID playerId) {
        Waiting was = WAITING.remove(playerId);
        if (was != null) {
            TRACKS.remove(was.wishId());
        }
        STATUS.remove(playerId);
        ANNOUNCED_QUEUED.remove(playerId);
        FOCUS.remove(playerId);   // so the next wish captures a fresh anchor, not a stale one
        // ALL of his bars, by PLAYER. This read clearWishBar(playerId) — a player id handed to a
        // method that takes a WISH id. Both are UUID, so it compiled, matched nothing, and leaked
        // every bar he had whenever a wish finished or he logged out. The method is named for what
        // it takes now, so the mismatch is visible at the call site rather than inside it.
        clearAllFor(playerId);
    }

    public static void clear() {
        for (UUID wishId : new ArrayList<>(BARS.keySet())) {
            clearWishBar(wishId);
        }
        ENDED_FOR_CHILD.clear();
        WAITING.clear();
        TRACKS.clear();
        FOCUS.clear();
        STATUS.clear();
        ANNOUNCED_QUEUED.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (WAITING.isEmpty() || ++tickCounter % TICK_INTERVAL != 0) {
            return;
        }
        var server = event.getServer();
        WishBook book = WishBook.get(server);
        long now = System.currentTimeMillis();

        for (UUID pid : new ArrayList<>(WAITING.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(pid);
            if (player == null) {
                forget(pid);
                continue;
            }
            Waiting waiting = WAITING.get(pid);
            if (waiting == null) {
                continue;
            }
            Wish wish = book.byId(waiting.wishId()).orElse(null);

            // The active wish is FINISHED (or vanished) — note: RUNNING is NOT finished, so a wish
            // the agent just claimed stays the active one. If the child queued another while we
            // worked, promote it and keep the ticker going; otherwise stand down (the agent's own
            // "done!" message + the landing flash are the payoff).
            if (wish == null || wish.status().isFinished() || ENDED_FOR_CHILD.contains(waiting.wishId())) {
                Wish next = null;
                for (Wish w : pendingFor(book.pending(), pid)) {
                    if (!w.id().equals(waiting.wishId()) && !ENDED_FOR_CHILD.contains(w.id())) {
                        next = w;
                        break;
                    }
                }
                if (next == null) {
                    forget(pid);
                    continue;
                }
                TRACKS.remove(waiting.wishId());
                waiting = new Waiting(next.id(), now);   // fresh timer for the new work
                WAITING.put(pid, waiting);
                STATUS.remove(pid);                      // fresh wish, fresh narration
                FOCUS.remove(pid);                       // fresh wish, fresh manifest spot
                wish = next;
            }

            // Only give up when the agent isn't actively on it (a wish that never got picked up),
            // or as a final safety valve if even an in-flight wish runs absurdly long. A wish the
            // agent is still working (RUNNING + a live session) must NEVER be declared timed-out.
            long elapsed = now - waiting.startedMs();
            boolean agentWorkingIt = wish.status() == WishStatus.RUNNING
                    && com.moddingfromamod.agent.AgentSession.isRunning();
            if (elapsed > (agentWorkingIt ? ABSOLUTE_MAX_MS : MAX_WAIT_MS)) {
                player.sendOverlayMessage(Component.literal(
                                "😴 That one is taking a very long time. Try asking again!")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
                forget(pid);
                continue;
            }

            // Queue accounting: only wishes this child made AFTER the one being worked count as
            // "queued behind it". Older still-PENDING wishes (e.g. left over from a previous session
            // and reloaded from the journal) must NOT be announced as "your next wish" — that phantom
            // fired ~1 second after the very first wish of a session.
            // "2 WISHES AHEAD OF YOURS" (plan §9 item 6). Only while his wish is genuinely
            // queued behind other people's — `Marshal.aheadOf` answers -1 for a wish that is being
            // granted, which is the ordinary case, and QueueNotice says nothing for -1 or 0.
            int ahead = com.moddingfromamod.marshal.Marshal.global().aheadOf(waiting.wishId());
            final long since = waiting.startedMs();
            WishStage.Track track = TRACKS.computeIfAbsent(waiting.wishId(),
                    k -> new WishStage.Track(since));
            if (wish.status() == WishStatus.RUNNING) {
                track.claimed(now);   // the backstop for a claim nobody reported (see claimed())
            }
            WishPlan plan = PLANS.get(waiting.wishId());
            String step = plan == null || plan.isThinking() ? "" : plan.currentLabel();
            player.sendOverlayMessage(
                    render(now, elapsed, track, step, STATUS.get(pid), ahead));
            conjure(player, elapsed);
        }
    }

    /** This child's pending wishes, oldest first (the oldest is the one being granted now). */
    static List<Wish> pendingFor(List<Wish> pending, UUID playerId) {
        List<Wish> mine = new ArrayList<>();
        for (Wish w : pending) {
            if (playerId.equals(w.playerId())) {
                mine.add(w);
            }
        }
        mine.sort(Comparator.comparingLong(Wish::createdMs));
        return mine;
    }

    /** The next wish to become active after {@code excludeId} finishes — the oldest still-pending. */
    static Wish nextActive(List<Wish> pending, UUID playerId, UUID excludeId) {
        for (Wish w : pendingFor(pending, playerId)) {
            if (!w.id().equals(excludeId)) {
                return w;
            }
        }
        return null;
    }

    /**
     * This child's PENDING wishes made strictly AFTER the active one — the genuine backlog waiting
     * behind it. A wish created before (or at the same instant as) the active one is NOT queued
     * behind it; counting those (every PENDING wish, including stale leftovers reloaded from the
     * journal) was exactly how a lone first wish got a phantom "I heard your next wish".
     */
    static List<Wish> queuedBehind(List<Wish> pending, UUID playerId, long activeCreatedMs) {
        List<Wish> out = new ArrayList<>();
        for (Wish w : pendingFor(pending, playerId)) {
            if (w.createdMs() > activeCreatedMs) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * The magic itself: particles that build as the wait goes on.
     *
     * <p>Deliberately escalating. A steady trickle reads as idle; something that visibly gathers
     * reads as <i>a big thing being made</i> — which is exactly what a long wait actually is.
     */
    private static void conjure(ServerPlayer player, long elapsedMs) {
        var level = player.level();
        long seconds = elapsedMs / 1000;
        // 1 -> 4 as the wait grows, so the effect thickens rather than just repeating.
        int intensity = (int) Math.min(4, 1 + seconds / 10);

        // A LANDMARK PER WISH. Sparkle beams are promises, and ten of them is a skyline of
        // promises rather than clutter -- the only channels that need arbitrating are the ones that
        // are physically singular, and the sky is not one of them.
        String dim = player.level().dimension().identifier().toString();
        java.util.List<BlockPos> spots = new ArrayList<>();
        for (var run : com.moddingfromamod.marshal.Marshal.global().liveFor(player.getUUID())) {
            var s = run.anchor();
            if (s != null && (s.dimension().isEmpty() || s.dimension().equals(dim))) {
                BlockPos at = new BlockPos(s.x(), s.y(), s.z());
                if (!spots.contains(at)) {
                    spots.add(at);
                }
            }
        }
        if (spots.isEmpty()) {
            BlockPos legacy = FOCUS.get(player.getUUID());
            if (legacy != null) {
                spots.add(legacy);
            }
        }
        for (BlockPos each : spots) {
            beamAt(player, each, seconds, intensity);
        }
        if (spots.isEmpty()) {
            // Nothing located yet: swirl around the child so he knows it's his wish being worked on.
            level.sendParticles(ParticleTypes.ENCHANT,
                    player.getX(), player.getY() + 1.2, player.getZ(),
                    6 * intensity, 1.1, 0.8, 1.1, 0.04);
        }
    }

    /** One wish's landmark, at its own spot. */
    private static void beamAt(ServerPlayer player, BlockPos spot, long seconds, int intensity) {
        var level = player.level();

        // A big, unmissable LANDMARK at the spot the wish will manifest, so a child (or anyone) is
        // drawn to look right there. Three pieces, all growing with the wait:
        //   1. a beacon-style BEAM straight up, visible from far away,
        //   2. a wide spinning RING on the ground marking the footprint,
        //   3. a rising SPIRAL that pulls the eye up the beam.
        double cx = spot.getX() + 0.5;
        double cy = spot.getY();
        double cz = spot.getZ() + 0.5;
        double spin = seconds * 0.35;              // slow rotation, so it reads as alive
        int beamH = 6 + intensity * 6;             // up to ~30 blocks tall

        // 1) BEAM — bright END_ROD column with firework glints.
        for (int i = 0; i < beamH; i++) {
            level.sendParticles(ParticleTypes.END_ROD, cx, cy + i, cz, 1, 0.06, 0.0, 0.06, 0.0);
            if (i % 3 == 0) {
                level.sendParticles(ParticleTypes.FIREWORK, cx, cy + i, cz, 1, 0.15, 0.05, 0.15, 0.01);
            }
        }

        // 2) RING — a wide circle on the ground, gold + enchant, spinning.
        double radius = 2.0 + intensity;           // up to ~6 blocks across the middle
        int points = 20 + intensity * 6;
        for (int i = 0; i < points; i++) {
            double a = spin + (Math.PI * 2 * i / points);
            double rx = cx + Math.cos(a) * radius;
            double rz = cz + Math.sin(a) * radius;
            level.sendParticles(ParticleTypes.ENCHANT, rx, cy + 0.3, rz, 1, 0.0, 0.1, 0.0, 0.02);
            if (i % 4 == 0) {
                level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, rx, cy + 0.2, rz,
                        1, 0.02, 0.25, 0.02, 0.03);
            }
        }

        // 3) SPIRAL — blue soul-flame winding up the beam.
        for (int i = 0; i < beamH; i++) {
            double t = i / (double) beamH;
            double a = spin * 2 + t * 3 * Math.PI * 2;
            double r = radius * (1 - t) * 0.8 + 0.4;
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    cx + Math.cos(a) * r, cy + t * beamH, cz + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
        }

        // A crown of sparks bursting at the top, now and then.
        if (seconds % 2 == 0) {
            level.sendParticles(ParticleTypes.FIREWORK, cx, cy + beamH, cz,
                    12 * intensity, 0.6, 0.3, 0.6, 0.08);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, cx, cy + beamH - 1, cz,
                    20, 1.0, 0.5, 1.0, 0.25);
        }

        // Sound: a soft chime you can place, plus a low beacon hum so it feels like a real landmark.
        if (seconds % 3 == 0) {
            level.playSound(null, spot, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS,
                    0.6f, 0.7f + (intensity * 0.12f));
            level.playSound(null, spot, SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS,
                    0.35f, 1.4f);
        }
    }

    /** The payoff: a flash and a chime where the thing appeared. */
    public static void landed(ServerPlayer player, BlockPos where) {
        if (player == null || where == null) {
            return;
        }
        FOCUS.remove(player.getUUID());
        var level = player.level();
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF),
                where.getX() + 0.5, where.getY() + 1.0, where.getZ() + 0.5, 4, 1.5, 1.5, 1.5, 0.0);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                where.getX() + 0.5, where.getY() + 2.0, where.getZ() + 0.5, 80, 2.5, 2.5, 2.5, 0.35);
        level.playSound(null, where, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.9f, 1.3f);
    }

    /**
     * A stage line as the bar draws it: the spinner, the words, and how long the wish has been
     * going — the spinner and the seconds stay because they show it is alive.
     *
     * <p>The words used to come from here, by timer tier — "Thinking really hard…", then "this one
     * is BIG" at 8 s and "a HUGE one" at 60 s, for every wish whatever its size. They are
     * {@link WishStage}'s now, named after what the mod has seen happen.
     */
    static Component message(long elapsedMs, String words) {
        String spin = SPINNER[(int) ((elapsedMs / 1000) % SPINNER.length)];
        long seconds = elapsedMs / 1000;
        return Component.literal(spin + " " + words + " (" + seconds + "s)")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA));
    }

    /**
     * The action-bar line for one wish: {@link WishStage} picks the words from what has happened
     * (the stage, the plan's step, the agent's or narrator's newest line, or a "still" line after
     * real quiet), and this draws them.
     */
    static Component render(long nowMs, long elapsedMs, WishStage.Track track, String planStep,
                            Status status, int ahead) {
        WishStage.Line line = WishStage.choose(nowMs, track, planStep,
                status == null ? null : status.doing(), status == null ? 0 : status.setMs());
        if (line.agent()) {
            return renderLine(elapsedMs, line.words(), status.phase(), 0, ahead);
        }
        return withQueue(message(elapsedMs, line.words()), ahead);
    }

    /** The same from raw parts, for tests: the agent's line and its phase are passed in directly. */
    static Component render(long nowMs, long elapsedMs, WishStage.Track track, String planStep,
                            String doing, Phase phase, long doingMs) {
        return render(nowMs, elapsedMs, track, planStep,
                doing == null ? null : new Status(doing, phase == null ? Phase.CASTING : phase, doingMs),
                -1);
    }

    /**
     * Build the line from raw parts (testable, no game state).
     *
     * <p><b>The queue notices are gone, and the {@code queued} count is ignored.</b> A seven-year-old
     * never sees a queue: not the words, not the count, not a reminder that something is held. Both
     * lines that used to appear here — "🪄 I heard your next wish!" and "…and I've got your next wish
     * ready — right after this one!" — were queue management wearing a friendly voice.
     *
     * <p>They were kept for a while on the argument that a waiting wish would otherwise have nothing
     * to show. That stopped being true the moment a wish raised its own bar and beam within a tick of
     * being made: "Moddy is planning…" is honest, it is what a wish being thought about looks
     * like, and from the outside the two are indistinguishable. The parameter is kept so the call
     * sites need not change and so this comment has somewhere to live.
     *
     * <ul>
     *   <li>When the agent has <b>narrated</b> what it's doing, show that with its phase emoji.</li>
     *   <li>Otherwise "Reading your wish…" — the line before anything is known. The ticker does
     *       not come here without words; it goes through {@link #render}, which knows the stage.</li>
     * </ul>
     */
    static Component renderLine(long elapsedMs, String doing, Phase phase, int queued) {
        return renderLine(elapsedMs, doing, phase, queued, -1);
    }

    /**
     * <b>…and how many OTHER children's wishes are in front of his</b> (plan §9 item 6).
     *
     * <p>⚠ This is NOT a re-run of the two notices the paragraph above says were deleted. Those
     * were about the child's own next wish, which already had a bar and a beam of its own, so
     * removing them hid nothing. This is the case a shared server created: his wish is behind
     * SOMEBODY ELSE'S, there is no bar for that, and without the line the screen is identical to
     * the screen he gets when his own wish is being worked on. {@code QueueNotice} is where the
     * wording and the say-nothing rule live, and it says nothing unless the number is real.
     */
    static Component renderLine(long elapsedMs, String doing, Phase phase, int queued, int ahead) {
        long seconds = elapsedMs / 1000;
        String queue = com.moddingfromamod.marshal.QueueNotice.aheadLine(ahead);
        String suffix = queue.isEmpty() ? "" : " · " + queue;
        if (doing != null && !doing.isBlank()) {
            String emoji = (phase == null ? Phase.CASTING : phase).emoji;
            String spin = SPINNER[(int) (seconds % SPINNER.length)];   // keep it visibly alive
            return Component.literal(
                    emoji + " " + doing + " " + spin + " (" + seconds + "s)" + suffix)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA));
        }
        return withQueue(message(elapsedMs, WishWaitWords.READING), ahead);
    }

    private static Component withQueue(Component line, int ahead) {
        String queue = com.moddingfromamod.marshal.QueueNotice.aheadLine(ahead);
        if (queue.isEmpty()) {
            return line;
        }
        return line.copy().append(Component.literal(" · " + queue)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }
}
