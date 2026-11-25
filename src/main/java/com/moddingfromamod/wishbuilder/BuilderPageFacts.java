package com.moddingfromamod.wishbuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import com.moddingfromamod.ui.PickPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The facts of every Builder tile, worked out once, off the server thread</b> (#14). Drawing a page under a world's
 * limits needs, for each tile, the facts of the make pressing it heads for, of that make at its lowest knobs, and of what
 * lies ahead of it. Working those out is the expensive part, and none of it depends on the limits, so it is done ONCE
 * per tree (keyed by the tree's own {@link BuilderTree#fingerprint}) on one background daemon thread, and a draw is then
 * a lookup and a comparison per tile ({@link BuilderPageFit#draw}).
 *
 * <p>Started only when a world answers makes ({@code WorldMakes}): a server where none does never walks, never starts
 * the thread and keeps nothing ({@code BuilderPageFactsTest}). A page state the walk has not reached yet (the walk is
 * still running, or a page it never visits, such as a second pick on a multi-pick page) is asked for with {@link #need}:
 * it jumps the queue, and its page is drawn again when it is ready. Until then the page shows its tiles locked with
 * {@link BuilderPageFit#GETTING_READY}, never unfitted.
 *
 * <p>The thread is a plain daemon with its own try/catch around every piece of work, which logs a failure and goes on
 * (a {@code ScheduledExecutorService} task that throws dies silently). Pure apart from it.
 */
public final class BuilderPageFacts {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-page-facts");

    private BuilderPageFacts() {
    }

    /**
     * One tile's facts: of the make pressing it heads for ({@code make}), of that make at its {@link MakeFit#floor}, and
     * the floors of the makes its way on reaches ({@code ahead}; empty: a dead end; null: too many to list, or past how
     * far the walk looks, so taken as viable).
     */
    public record TileFacts(MakeFacts make, MakeFacts floor, List<MakeFacts> ahead) {
    }

    /** Every page state's tiles, for one tree. Read by the server thread while the worker fills it. */
    public static final class Index {
        private final String fingerprint;
        private final Map<String, Map<String, TileFacts>> pages = new ConcurrentHashMap<>();
        /** The states in the order they were worked out: what a player meets first is first. */
        private final List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile boolean walked;

        Index(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        public String fingerprint() {
            return fingerprint;
        }

        /** The tiles of the page at this state, or null when it is not worked out yet. */
        public Map<String, TileFacts> page(String stateKey) {
            return pages.get(stateKey);
        }

        public int size() {
            return pages.size();
        }

        /** The states in the order they were worked out. */
        public List<String> order() {
            synchronized (order) {
                return List.copyOf(order);
            }
        }

        /** Has the whole walk from the first page finished? */
        public boolean walked() {
            return walked;
        }
    }

    /** How many pages ahead a tile's way on is followed (further than that is taken as viable). */
    static final int DEPTH = 4;
    /** At most this many makes ahead are kept per state; more is "too many to list" (viable). */
    static final int AHEAD_MOST = 24;
    /** At most this many page states are walked per tree; the rest are worked out when asked for. */
    static final int MOST_STATES = 6000;

    /** A flow's state, as the index keys it: its step and the make it holds so far. */
    public static String stateKey(BuilderFlow flow) {
        BuilderSpec d = flow.draft();
        return flow.step() + "|" + (d == null ? "" : d.toJson().toString());
    }

    // ── working it out (any thread but the server's) ───────────────────────────────────────────

    /** The worker's memory for one tree: what lies ahead of each state already followed. */
    static final class Walk {
        final Index index;
        final Map<String, List<MakeFacts>> ahead = new HashMap<>();
        final Map<String, Boolean> aheadKnown = new HashMap<>();

        Walk(Index index) {
            this.index = index;
        }
    }

    private static MakeFacts facts(BuilderSpec s) {
        return s == null ? null : MakeFacts.of(s);
    }

    /** Work out the tiles of the page at {@code flow}'s state, into the index. */
    static Map<String, TileFacts> page(Walk w, BuilderFlow flow) {
        String key = stateKey(flow);
        Map<String, TileFacts> have = w.index.pages.get(key);
        if (have != null) {
            return have;
        }
        Map<String, TileFacts> tiles = new LinkedHashMap<>();
        PickPage page;
        try {
            page = flow.page();
        } catch (RuntimeException e) {
            if (w.index.pages.putIfAbsent(key, Map.of()) == null) {
                w.index.order.add(key);
            }
            return Map.of();
        }
        boolean multi = page.picks() != null;
        for (PickPage.Tile t : page.tiles()) {
            BuilderFlow child = flow.after(t.id());
            if (child == null) {
                continue;   // a tile that makes nothing by itself: no entry, always drawn
            }
            BuilderSpec d = child.draft();
            BuilderFlow onward = multi && child.step() == flow.step() ? child.after(PickPage.NEXT_ID) : child;
            List<MakeFacts> ahead = onward == null ? List.of() : ahead(w, onward, DEPTH);
            tiles.put(t.id(), new TileFacts(facts(d), d == null ? null : facts(MakeFit.floor(d)), ahead));
        }
        Map<String, TileFacts> done = Map.copyOf(tiles);
        if (w.index.pages.putIfAbsent(key, done) == null) {
            w.index.order.add(key);
        }
        return done;
    }

    /** The floors of the makes reachable from {@code flow}'s state; null for too many or too far. */
    static List<MakeFacts> ahead(Walk w, BuilderFlow flow, int depth) {
        if (flow.step() == BuilderFlow.Step.TUNE) {
            BuilderSpec d = flow.draft();
            return d == null ? null : List.of(facts(MakeFit.floor(d)));
        }
        if (depth <= 0) {
            return null;
        }
        String key = stateKey(flow);
        if (w.aheadKnown.containsKey(key)) {
            return w.ahead.get(key);
        }
        w.aheadKnown.put(key, Boolean.TRUE);   // a way back round to here adds nothing new
        w.ahead.put(key, List.of());
        PickPage page;
        try {
            page = flow.page();
        } catch (RuntimeException e) {
            w.ahead.put(key, null);
            return null;
        }
        boolean multi = page.picks() != null;
        Set<MakeFacts> out = new LinkedHashSet<>();
        boolean tooMany = false;
        List<BuilderFlow> ways = new ArrayList<>();
        if (multi || page.next()) {
            BuilderFlow on = flow.after(PickPage.NEXT_ID);
            if (on != null) {
                ways.add(on);
            }
        }
        // a pick on a multi-pick page only adds to the make, so its least way on is the one left as it is (Done);
        // a one-of page's way on is each of its tiles
        // (unless it cannot be left with no pick: then each pick, then Done)
        boolean leaves = !ways.isEmpty();
        for (PickPage.Tile t : multi && leaves ? List.<PickPage.Tile>of() : page.tiles()) {
            BuilderFlow next = flow.after(t.id());
            if (next != null && multi && next.step() == flow.step()) {
                next = next.after(PickPage.NEXT_ID);
            }
            if (next != null) {
                ways.add(next);
            }
        }
        for (BuilderFlow next : ways) {
            List<MakeFacts> a = ahead(w, next, depth - 1);
            if (a == null) {
                tooMany = true;
                break;
            }
            out.addAll(a);
            if (out.size() > AHEAD_MOST) {
                tooMany = true;
                break;
            }
        }
        List<MakeFacts> result = tooMany ? null : List.copyOf(out);
        w.ahead.put(key, result);
        return result;
    }

    /**
     * Walk every page from the first, working each out (at most {@link #MOST_STATES}). Synchronous: the worker runs it,
     * and a test calls it to build an index without the thread. {@code between} runs after each page (the worker
     * serves queued pages there first).
     */
    static void walk(Walk w, BuilderAllowlist allow, LibraryPresets presets, Runnable between, int most) {
        // breadth first: every kind's first pages are worked out before any page deep in one of them
        Deque<BuilderFlow> todo = new ArrayDeque<>();
        Set<String> seen = new java.util.HashSet<>();
        todo.addLast(new BuilderFlow(allow, presets));
        while (!todo.isEmpty() && seen.size() < most) {
            BuilderFlow f = todo.pollFirst();
            if (f.step() == BuilderFlow.Step.TUNE || !seen.add(stateKey(f))) {
                continue;
            }
            page(w, f);
            if (between != null) {
                between.run();
            }
            PickPage page;
            try {
                page = f.page();
            } catch (RuntimeException e) {
                continue;
            }
            boolean multi = page.picks() != null;
            if (multi || page.next()) {
                BuilderFlow on = f.after(PickPage.NEXT_ID);
                if (on != null) {
                    todo.addLast(on);
                }
            }
            for (PickPage.Tile t : page.tiles()) {
                BuilderFlow next = f.after(t.id());
                // a pick on a multi-pick page is followed as "pick, then Done": the page with two picks on it is
                // worked out when someone opens it (need), never walked for every pair
                if (next != null && multi && next.step() == f.step()) {
                    next = next.after(PickPage.NEXT_ID);
                }
                if (next != null) {
                    todo.addLast(next);
                }
            }
        }
        w.index.walked = true;
    }

    /** An index for {@code allow}'s tree, built here and now: the test's way, never the server thread's. */
    public static Index buildNow(BuilderAllowlist allow, LibraryPresets presets, int most) {
        Index idx = new Index(BuilderTree.of(allow, presets).fingerprint());
        walk(new Walk(idx), allow, presets, null, most);
        return idx;
    }

    // ── the worker ────────────────────────────────────────────────────────────────────────────

    /** A page a player opened that is not worked out yet: its flow (a copy), and what to run once it is. */
    record Need(BuilderAllowlist allow, BuilderFlow flow, Runnable ready) {
    }

    /**
     * Serve every page asked for, now, ahead of the walk: the worker runs this between two pages of its own walk, so a
     * page a player opens waits for at most the one page being worked out, never for the rest of the tree.
     */
    static void drain(Walk w, java.util.Queue<Need> needs) {
        Need need;
        while ((need = needs.poll()) != null) {
            try {
                page(w, need.flow());
                need.ready().run();
            } catch (Throwable t) {
                LOG.warn("page facts: a page asked for failed: {}", t.toString());
            }
        }
    }

    private static final Map<BuilderAllowlist, String> TREE_OF = new ConcurrentHashMap<>();
    private static final Map<String, Walk> WALKS = new ConcurrentHashMap<>();
    private static final LinkedBlockingDeque<Need> NEEDS = new LinkedBlockingDeque<>();
    private static final LinkedBlockingDeque<BuilderAllowlist> TREES = new LinkedBlockingDeque<>();
    private static volatile Thread worker;

    /** Has the worker been started (only ever by a world that answers makes)? */
    public static boolean started() {
        return worker != null;
    }

    /**
     * The index for {@code allow}'s tree, or null while its fingerprint is still being taken. The first call for a tree
     * starts its walk on the worker (and the worker itself). Cheap: two map reads. Call only when a world answers.
     */
    public static Index forWorld(BuilderAllowlist allow) {
        String fp = TREE_OF.get(allow);
        Walk w = fp == null ? null : WALKS.get(fp);
        if (fp == null) {
            start();
            if (TREE_OF.putIfAbsent(allow, "") == null) {
                TREES.offer(allow);
            }
            return null;
        }
        return w == null ? null : w.index;
    }

    /**
     * Work out the page at {@code snapshot}'s state ahead of the walk (a copy of the open flow, never the flow itself),
     * then run {@code ready} (which should hop to the server thread and draw the page again).
     */
    public static void need(BuilderAllowlist allow, BuilderFlow snapshot, Runnable ready) {
        start();
        NEEDS.offerFirst(new Need(allow, snapshot, ready));
    }

    private static synchronized void start() {
        if (worker != null) {
            return;
        }
        Thread t = new Thread(BuilderPageFacts::work, "moddy-page-facts");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        worker = t;
        t.start();
        LOG.info("page facts: a world answers makes, so the Builder's tiles are being worked out in the background");
    }

    private static void work() {
        Deque<Runnable> walking = new ArrayDeque<>();
        while (true) {
            try {
                Need need = NEEDS.poll();
                if (need != null) {
                    serve(need);
                    continue;
                }
                BuilderAllowlist tree = TREES.poll();
                if (tree != null) {
                    beginTree(tree);
                    continue;
                }
                need = NEEDS.poll(1, TimeUnit.SECONDS);
                if (need != null) {
                    serve(need);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                LOG.warn("page facts: a piece of work failed, going on: {}", t.toString());
            }
        }
    }

    /** Take the tree's fingerprint, then walk it, serving any page asked for between two of its own. */
    private static void beginTree(BuilderAllowlist allow) {
        long t0 = System.nanoTime();
        String fp = BuilderTree.of(allow, LibraryPresets.SHIPPED).fingerprint();
        Walk w = WALKS.computeIfAbsent(fp, k -> new Walk(new Index(k)));
        TREE_OF.put(allow, fp);
        if (w.index.walked()) {
            return;   // the same tree as a world already walked
        }
        walk(w, allow, LibraryPresets.SHIPPED, () -> drainFor(w), MOST_STATES);
        LOG.info("page facts: tree {} walked, {} pages in {} s", fp.substring(0, 12), w.index.size(),
                String.format("%.1f", (System.nanoTime() - t0) / 1e9));
    }

    /** The pages asked for while walking {@code w}'s tree: its own served at once, another tree's left queued. */
    private static void drainFor(Walk w) {
        java.util.Deque<Need> mine = new ArrayDeque<>();
        java.util.Deque<Need> others = new ArrayDeque<>();
        Need need;
        while ((need = NEEDS.poll()) != null) {
            (w.index.fingerprint().equals(TREE_OF.get(need.allow())) ? mine : others).add(need);
        }
        others.descendingIterator().forEachRemaining(NEEDS::offerFirst);
        drain(w, mine);
        // another tree's page waits for this walk's next step, served by serve() then
    }

    private static void serve(Need need) {
        String fp = TREE_OF.get(need.allow());
        if (fp == null || fp.isEmpty()) {
            // its tree's fingerprint is not taken yet: take it first, then this page
            if (fp == null && TREE_OF.putIfAbsent(need.allow(), "") == null) {
                TREES.offerFirst(need.allow());
            }
            NEEDS.offerLast(need);
            BuilderAllowlist tree = TREES.poll();
            if (tree != null) {
                String f = BuilderTree.of(tree, LibraryPresets.SHIPPED).fingerprint();
                WALKS.computeIfAbsent(f, k -> new Walk(new Index(k)));
                TREE_OF.put(tree, f);
                TREES.offerLast(tree);   // and walk it after
            }
            return;
        }
        Walk w = WALKS.get(fp);
        page(w, need.flow());
        need.ready().run();
    }
}
