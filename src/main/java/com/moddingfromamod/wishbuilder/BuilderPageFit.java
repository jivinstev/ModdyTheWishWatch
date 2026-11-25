package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;

/**
 * <b>The Builder's pages as THIS world shows them</b> (#14): a tile whose make the world hides, or that cannot be
 * fitted to its limits, is not drawn; a tile whose make it locks is drawn grey with the world's words and makes nothing;
 * a tile whose every way on leads to nothing allowed is culled from its page; a tune dialog's sliders and size names
 * stop at the most the limits allow. A tile is judged by the make that pressing it heads for ({@link BuilderFlow#after}),
 * so a page never needs to know what its tiles mean.
 *
 * <p>Pure: the world's answer comes in as a {@link Judge}, so Gate A walks every page under limits
 * ({@code BuilderPageFitTest}, which also times the walk). The pages are still {@link PickPage}s and the dialog a
 * {@link TuneDialog}, opened through {@code MenuKit}: only their tiles and inputs are narrowed.
 */
public final class BuilderPageFit {

    private BuilderPageFit() {
    }

    /** What a world makes of one make here: kept as it is, made but fitted to its limits, locked, or dropped. */
    public enum Seen { KEEP, FIT, LOCK, DROP }

    /** One judged make: kept, fitted, locked with its line, or dropped (hidden, or cannot be fitted). */
    public record Judged(Seen seen, String line) {
        public static final Judged KEEP = new Judged(Seen.KEEP, "");
        public static final Judged FIT = new Judged(Seen.FIT, "");
        public static final Judged DROP = new Judged(Seen.DROP, "");

        /** Is it made at all (as it is, or fitted)? */
        public boolean makes() {
            return seen == Seen.KEEP || seen == Seen.FIT;
        }
    }

    /** The world's answer for a make, already fitted to its limits. */
    @FunctionalInterface
    public interface Judge {
        Judged judge(BuilderSpec spec);
    }

    /** How deep a tile's way on is looked down before it is taken as viable: a page, its pages, theirs, theirs. */
    public static final int DEPTH = 3;

    /** {@code page} as this world shows it, at the flow that drew it. */
    public static PickPage page(BuilderFlow flow, PickPage page, Judge judge) {
        return page(flow, page, judge, null);
    }

    /**
     * …remembering what lies ahead of each tile under {@code ahead} (null: nothing remembered): the key of the tree and
     * the world's limits it was worked out for ({@link #aheadKey}), so a redraw does not walk the same pages again.
     */
    public static PickPage page(BuilderFlow flow, PickPage page, Judge judge, String ahead) {
        List<PickPage.Tile> tiles = new ArrayList<>();
        for (PickPage.Tile t : page.tiles()) {
            Judged j = tile(flow, t, judge, ahead);
            switch (j.seen()) {
                case KEEP, FIT -> tiles.add(t);
                case LOCK -> tiles.add(new PickPage.Tile(t.id(), PickPage.BLOCKED_ITEM, t.label() + " 🔒",
                        withFirst(j.line(), t.lore()), false));
                case DROP -> {
                }
            }
        }
        return tiles.size() == page.tiles().size() && tiles.equals(page.tiles()) ? page
                : new PickPage(page.title(), tiles, page.back(), page.next(), page.nextLabel(), page.picks());
    }

    private static List<String> withFirst(String line, List<String> lore) {
        List<String> out = new ArrayList<>();
        out.add(line);
        out.addAll(lore);
        return out;
    }

    /**
     * One tile: a picked one is always kept (taking a pick away is never stopped); otherwise the make it heads for is
     * judged, and a tile whose way on leads to nothing allowed is dropped.
     */
    public static Judged tile(BuilderFlow flow, PickPage.Tile t, Judge judge) {
        return tile(flow, t, judge, null);
    }

    static Judged tile(BuilderFlow flow, PickPage.Tile t, Judge judge, String ahead) {
        if (t.selected()) {
            return Judged.KEEP;
        }
        BuilderFlow next = flow.after(t.id());
        if (next == null) {
            return Judged.KEEP;   // a tile that makes nothing by itself (a page turn, a find)
        }
        BuilderSpec draft = next.draft();
        Judged j = draft == null ? Judged.KEEP : judge.judge(draft);
        if (!j.makes()) {
            return j;
        }
        return viable(next, judge, DEPTH, ahead) ? Judged.KEEP : Judged.DROP;
    }

    /**
     * The key what lies ahead is remembered under: the tree (the world's Builder list, which with the shipped library
     * decides every page) and the stamp of the world's limits. A world whose answers change changes its stamp.
     */
    public static String aheadKey(BuilderAllowlist allow, String stamp) {
        return Integer.toHexString(allow == null ? 0 : allow.hashCode()) + "|" + (stamp == null ? "" : stamp);
    }

    /** At most this many ways ahead are remembered; the oldest goes first. */
    static final int AHEAD_SIZE = 2048;

    private static final Map<String, Boolean> AHEAD = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > AHEAD_SIZE;
                }
            });

    static boolean viable(BuilderFlow flow, Judge judge, int depth, String ahead) {
        if (ahead == null) {
            return viable(flow, judge, depth);
        }
        BuilderSpec d = flow.draft();
        String key = ahead + "|" + flow.step() + "|" + depth + "|" + (d == null ? "" : d.toJson().toString());
        Boolean hit = AHEAD.get(key);
        if (hit != null) {
            return hit;
        }
        boolean v = viable(flow, judge, depth);
        AHEAD.put(key, v);
        return v;
    }

    /** Can anything allowed be made from here on, looking {@code depth} pages ahead? */
    static boolean viable(BuilderFlow flow, Judge judge, int depth) {
        if (flow.step() == BuilderFlow.Step.TUNE || depth <= 0) {
            BuilderSpec d = flow.draft();
            return d == null || judge.judge(d).seen() != Seen.DROP;
        }
        PickPage page;
        try {
            page = flow.page();
        } catch (RuntimeException e) {
            return true;
        }
        if (page.picks() != null || page.next()) {
            // a page that may be left as it is (its Done, its Next): its way on counts too
            BuilderFlow on = flow.after(PickPage.NEXT_ID);
            if (on != null && viable(on, judge, depth - 1)) {
                return true;
            }
        }
        for (PickPage.Tile t : page.tiles()) {
            BuilderFlow next = flow.after(t.id());
            if (next == null) {
                continue;
            }
            BuilderSpec d = next.draft();
            if ((d == null || judge.judge(d).seen() != Seen.DROP) && viable(next, judge, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tune dialog as this world allows: each slider stops at the largest value whose make is kept (one with a
     * single value left is dropped: the Make fits to the limits anyway), each size name past the limits is left out.
     */
    public static TuneDialog tune(BuilderFlow flow, TuneDialog dialog, Judge judge) {
        List<TuneDialog.Input> in = new ArrayList<>();
        boolean changed = false;
        for (TuneDialog.Input i : dialog.inputs()) {
            if (i instanceof TuneDialog.Slider s && List.of("size", "count", "strength", "amount").contains(s.key())) {
                int top = s.start() - 1;
                for (int v = s.end(); v >= s.start(); v -= s.step()) {
                    if (kept(flow, s.key(), String.valueOf(v), judge)) {
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
            } else if (i instanceof TuneDialog.Choice c && c.key().equals("mobsize")) {
                List<TuneDialog.Option> keep = new ArrayList<>();
                for (TuneDialog.Option o : c.options()) {
                    if (kept(flow, c.key(), o.id(), judge)) {
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

    private static boolean kept(BuilderFlow flow, String key, String value, Judge judge) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        BuilderSpec s;
        try {
            s = flow.spec(values);
        } catch (RuntimeException e) {
            return true;
        }
        return judge.judge(s).seen() == Seen.KEEP;
    }
}
