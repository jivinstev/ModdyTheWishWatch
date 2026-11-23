package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A child pressing the Builder's menus at random: every page's tiles as he would see them, a random one
 * pressed (a few on a page that takes several, then Done), and the tune dialog's controls set at random. What
 * comes back is the spec the real Make would write and the words of every pick on the way, for the namer's
 * property tests and its judged sample ({@code tools/builder-names-judge.py}).
 */
final class BuilderRandomWalk {

    /** One walk: the picks in the words he saw them in, the tune values, and the spec. */
    record Walk(BuilderSpec.Kind kind, List<String> picked, Map<String, String> tuned, BuilderSpec spec, String oldDefault) {
    }

    /** What the dialog's name control showed first before the picked label, or "Sparky" for a body. */
    static String oldDefault(BuilderFlow f, BuilderSpec.Kind kind) {
        if (kind == BuilderSpec.Kind.RULE) {
            return "";
        }
        String base = f.pickedLabel().replaceAll("[^A-Za-z ]", "").strip();
        boolean body = kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE;
        return !body && !base.isEmpty() && base.length() <= 20 ? base : "Sparky";
    }

    private BuilderRandomWalk() {
    }

    /** A random walk to a made spec, or null when this walk ran into a dead end (a refused press, a loop). */
    static Walk walk(Random r, BuilderAllowlist allow, LibraryPresets presets) {
        BuilderFlow f = new BuilderFlow(allow, presets);
        List<String> picked = new ArrayList<>();
        BuilderSpec.Kind kind = null;
        for (int presses = 0; presses < 40 && f.step() != BuilderFlow.Step.TUNE; presses++) {
            PickPage page = f.page();
            List<PickPage.Tile> tiles = page.tiles().stream()
                    .filter(t -> !t.id().startsWith("@") && (page.picks() == null || !page.picks().blocked().containsKey(t.id())))
                    .toList();
            if (page.next() && page.picks() != null) {
                // A page that takes several: none to a few of its tiles, then Done.
                int want = tiles.isEmpty() ? 0 : r.nextInt(Math.min(3, tiles.size()) + 1);
                List<PickPage.Tile> pool = new ArrayList<>(tiles);
                for (int i = 0; i < want && !pool.isEmpty(); i++) {
                    PickPage.Tile t = pool.remove(r.nextInt(pool.size()));
                    if (!t.selected()) {
                        BuilderFlow.Step before = f.step();
                        f.press(t.id());
                        if (f.step() == before) {
                            picked.add(stepWord(before) + ": " + t.label());
                        }
                    }
                }
                f.press(PickPage.NEXT_ID);
                continue;
            }
            if (tiles.isEmpty()) {
                return null;
            }
            PickPage.Tile t = tiles.get(r.nextInt(tiles.size()));
            BuilderFlow.Step before = f.step();
            f.press(t.id());
            if (f.step() == before) {
                return null;   // refused: a dead end for this walk
            }
            if (before == BuilderFlow.Step.KIND) {
                kind = BuilderSpec.Kind.of(t.id());
            }
            picked.add(stepWord(before) + ": " + t.label());
        }
        if (f.step() != BuilderFlow.Step.TUNE) {
            return null;
        }
        TuneDialog d = f.tune();
        Map<String, String> tuned = new LinkedHashMap<>();
        for (TuneDialog.Input in : d.inputs()) {
            if (in instanceof TuneDialog.Slider s) {
                int steps = (s.end() - s.start()) / Math.max(1, s.step());
                tuned.put(s.key(), String.valueOf(s.start() + s.step() * r.nextInt(steps + 1)));
            } else if (in instanceof TuneDialog.Choice c) {
                tuned.put(c.key(), c.options().get(r.nextInt(c.options().size())).id());
            }
        }
        BuilderSpec spec = f.spec(tuned);
        return new Walk(kind == null ? spec.kind() : kind, picked, tuned, spec, oldDefault(f, spec.kind()));
    }

    private static String stepWord(BuilderFlow.Step s) {
        return s.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
