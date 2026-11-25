package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;

/**
 * <b>The Builder's pages as THIS world shows them</b> (#14): a tile whose make the world hides, or that cannot be
 * fitted to its limits, is not drawn; a tile whose make it locks is drawn grey with the world's words and makes nothing;
 * a tile whose every way on leads to nothing allowed is culled from its page; a tune dialog's sliders and size names
 * stop at the most the limits allow.
 *
 * <p><b>A draw is a comparison, never a walk.</b> Every tile's facts (the make pressing it heads for, that make at its
 * lowest knobs, what lies ahead of it) are worked out once, off the server thread, by {@link BuilderPageFacts}; a draw
 * looks them up and compares them with the world's answer. A page whose state is not worked out yet is drawn with its
 * tiles locked in {@link #GETTING_READY}, never unfitted, and drawn again when it is ready. The make itself is still
 * fitted and checked when it is made ({@code MakeGate.build}).
 *
 * <p>Pure: the world's answer comes in as a {@link World}. The pages are still {@link PickPage}s and the dialog a
 * {@link TuneDialog}, opened through {@code MenuKit}: only their tiles and inputs are narrowed.
 */
public final class BuilderPageFit {

    private BuilderPageFit() {
    }

    /** Shown under each tile while its page's facts are still being worked out. */
    public static final String GETTING_READY = "⏳ Getting this page ready…";

    /** What a world makes of one make here: kept as it is, made but fitted to its limits, locked, or dropped. */
    public enum Seen { KEEP, FIT, LOCK, DROP }

    /** One judged make: kept, fitted, locked with its line, or dropped (hidden, or cannot be fitted). */
    public record Judged(Seen seen, String line, Map<String, Double> limits) {
        public static final Judged KEEP = new Judged(Seen.KEEP, "", Map.of());
        public static final Judged FIT = new Judged(Seen.FIT, "", Map.of());
        public static final Judged DROP = new Judged(Seen.DROP, "", Map.of());

        public Judged {
            line = line == null ? "" : line;
            limits = limits == null ? Map.of() : limits;
        }

        public Judged(Seen seen, String line) {
            this(seen, line, Map.of());
        }

        /** Is it made at all (as it is, or fitted)? */
        public boolean makes() {
            return seen == Seen.KEEP || seen == Seen.FIT;
        }
    }

    /** The world's answer about a make's facts: kept (with the limits it is made within), locked, or dropped. */
    @FunctionalInterface
    public interface World {
        Judged view(MakeFacts facts);
    }

    /** A world, asked at most once per facts in one draw. */
    static World remembering(World w) {
        Map<MakeFacts, Judged> seen = new HashMap<>();
        return f -> seen.computeIfAbsent(f, w::view);
    }

    /**
     * {@code page} as {@code world} shows it, at the flow that drew it, from {@code index}. {@code world} null (no world
     * answers): the page as it is, with no work at all. A state {@code index} does not hold yet: every tile that is not
     * picked is locked in {@link #GETTING_READY} ({@link #gettingReady} says so).
     */
    public static PickPage draw(BuilderFlow flow, PickPage page, BuilderPageFacts.Index index, World world) {
        if (world == null) {
            return page;
        }
        Map<String, BuilderPageFacts.TileFacts> facts = index == null ? null : index.page(BuilderPageFacts.stateKey(flow));
        if (facts == null) {
            return gettingReadyPage(page);
        }
        World w = remembering(world);
        List<PickPage.Tile> tiles = new ArrayList<>();
        boolean changed = false;
        for (PickPage.Tile t : page.tiles()) {
            Judged j = t.selected() ? Judged.KEEP : judge(facts.get(t.id()), w);
            switch (j.seen()) {
                case KEEP, FIT -> tiles.add(t);
                case LOCK -> {
                    tiles.add(locked(t, j.line()));
                    changed = true;
                }
                case DROP -> changed = true;
            }
        }
        return changed ? new PickPage(page.title(), tiles, page.back(), page.next(), page.nextLabel(), page.picks()) : page;
    }

    /** Is this a page drawn while its facts are still being worked out? */
    public static boolean gettingReady(PickPage page) {
        return page.tiles().stream().anyMatch(t -> !t.lore().isEmpty() && t.lore().get(0).equals(GETTING_READY));
    }

    private static PickPage gettingReadyPage(PickPage page) {
        List<PickPage.Tile> tiles = new ArrayList<>();
        for (PickPage.Tile t : page.tiles()) {
            tiles.add(t.selected() ? t : locked(t, GETTING_READY));
        }
        return new PickPage(page.title(), tiles, page.back(), page.next(), page.nextLabel(), page.picks());
    }

    static PickPage.Tile locked(PickPage.Tile t, String line) {
        List<String> lore = new ArrayList<>();
        lore.add(line);
        lore.addAll(t.lore());
        return new PickPage.Tile(t.id(), PickPage.BLOCKED_ITEM, t.label() + " 🔒", lore, false);
    }

    /**
     * One tile, by its worked-out facts: none (a tile that makes nothing by itself) is kept; otherwise the world's view
     * of its make decides, then whether that make can be fitted to the limits, then whether anything ahead of it can.
     */
    public static Judged judge(BuilderPageFacts.TileFacts t, World world) {
        if (t == null || t.make() == null) {
            return Judged.KEEP;
        }
        Judged j = world.view(t.make());
        if (!j.makes()) {
            return j;
        }
        if (!MakeFit.fittable(t.make(), t.floor(), j.limits())) {
            return Judged.DROP;
        }
        if (t.ahead() != null) {
            boolean viable = false;
            for (MakeFacts a : t.ahead()) {
                Judged ja = world.view(a);
                if (ja.makes() && MakeFit.satisfies(a, ja.limits())) {
                    viable = true;
                    break;
                }
            }
            if (!viable) {
                return Judged.DROP;
            }
        }
        return MakeFit.satisfies(t.make(), j.limits()) ? Judged.KEEP : Judged.FIT;
    }

    /**
     * The tune dialog as this world allows: each slider stops at the largest value whose make keeps within the limits
     * (one with a single value left is dropped: the Make fits to the limits anyway), each size name past the limits is
     * left out. A slider whose value IS its number (how many, the size step, the strength step, the amount) is compared
     * directly; only a number a value moves through the make (a creature's scale, an effect's duration or reach) asks
     * for the make's facts, once per value, remembered.
     */
    public static TuneDialog tune(BuilderFlow flow, TuneDialog dialog, Map<String, Double> limits) {
        if (limits == null || limits.isEmpty()) {
            return dialog;
        }
        List<TuneDialog.Input> in = new ArrayList<>();
        boolean changed = false;
        for (TuneDialog.Input i : dialog.inputs()) {
            if (i instanceof TuneDialog.Slider s && DIRECT.containsKey(s.key())) {
                int top = s.start() - 1;
                for (int v = s.end(); v >= s.start(); v -= s.step()) {
                    if (kept(flow, s.key(), v, limits)) {
                        top = v;
                        break;
                    }
                }
                if (top == s.end()) {
                    in.add(s);
                    continue;
                }
                changed = true;
                if (top > s.start()) {
                    in.add(new TuneDialog.Slider(s.key(), s.label(), s.start(), top, s.step(), Math.min(s.initial(), top)));
                }
            } else if (i instanceof TuneDialog.Choice c && c.key().equals("mobsize")
                    && (limits.containsKey(MakeFacts.SCALE_TIMES) || limits.containsKey(MakeFacts.BUILDER_SIZE_STEP))) {
                List<TuneDialog.Option> keep = new ArrayList<>();
                for (TuneDialog.Option o : c.options()) {
                    if (keptAs(flow, c.key(), o.id(), limits)) {
                        keep.add(o);
                    }
                }
                if (keep.size() == c.options().size() || keep.isEmpty()) {
                    in.add(c);
                    continue;
                }
                changed = true;
                String initial = keep.stream().anyMatch(o -> o.id().equals(c.initial())) ? c.initial()
                        : keep.get(keep.size() - 1).id();
                in.add(new TuneDialog.Choice(c.key(), c.label(), keep, initial, c.width(), c.labelOnButton()));
            } else {
                in.add(i);
            }
        }
        return changed ? new TuneDialog(dialog.title(), dialog.body(), in, dialog.actionLabel(), dialog.command()) : dialog;
    }

    /** The sliders whose value is itself a number, and that number. */
    static final Map<String, String> DIRECT = Map.of("count", MakeFacts.COUNT_MADE, "size", MakeFacts.BUILDER_SIZE_STEP,
            "strength", MakeFacts.BUILDER_STRENGTH_STEP, "amount", MakeFacts.AMOUNT_ITEMS);

    /** The numbers a slider's value moves only through the make. */
    static final Map<String, List<String>> THROUGH = Map.of(
            "size", List.of(MakeFacts.SCALE_TIMES),
            "strength", List.of(MakeFacts.DURATION_TICKS, MakeFacts.RADIUS_BLOCKS));

    private static boolean kept(BuilderFlow flow, String key, int v, Map<String, Double> limits) {
        Double most = limits.get(DIRECT.get(key));
        if (most != null && v > most + 1e-9) {
            return false;
        }
        List<String> through = THROUGH.getOrDefault(key, List.of());
        if (through.stream().noneMatch(limits::containsKey)) {
            return true;   // nothing else this value moves is limited: no make to read
        }
        return keptAs(flow, key, String.valueOf(v), limits);
    }

    private static boolean keptAs(BuilderFlow flow, String key, String value, Map<String, Double> limits) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        BuilderSpec s;
        try {
            s = flow.spec(values);
        } catch (RuntimeException e) {
            return true;
        }
        return MakeFit.satisfies(MakeFacts.of(s), limits);
    }
}
