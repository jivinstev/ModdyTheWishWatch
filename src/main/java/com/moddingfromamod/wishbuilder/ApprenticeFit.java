package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * <b>The Apprentice's ranked makes, as this world shows them</b> (#68, stage 2 of #14). Run AFTER ranking and OUTSIDE
 * the matcher: {@link Apprentice} ranks exactly as it would with no world, and this only takes its answer apart, so
 * the matcher, its gold-set floors and its tree fingerprint do not move. Fitting is not ranking: the order is kept.
 *
 * <p>Each offer is judged by the world (the same one gate and the same fitting a press goes through): kept, kept
 * fitted (drawn as it is; the make it presses is fitted and says so once, as a Builder tile's is), locked (shown with
 * its line, never pressable into a make), or dropped (hidden here, or more than any fit could make it). The layer's
 * per-offer picks stay with their offers. With no world, the answer is returned as it is and nothing is judged.
 *
 * <p>Pure.
 */
public final class ApprenticeFit {

    private ApprenticeFit() {
    }

    /** The answer the page is drawn from, and the offers in it shown locked, by index, with their line. */
    public record Shown(Apprentice.Answer answer, Map<Integer, String> locked) {
        public Shown {
            locked = Map.copyOf(locked);
        }
    }

    /** {@code answer} as {@code world} shows it; {@code world} null (none answers): exactly the answer. */
    public static Shown fit(Apprentice.Answer answer, Function<Apprentice.Pick, BuilderPageFit.Judged> world) {
        if (world == null || answer.offers().isEmpty()) {
            return new Shown(answer, Map.of());
        }
        List<Apprentice.Pick> offers = new ArrayList<>();
        List<List<String>> layer = new ArrayList<>();
        Map<Integer, String> locked = new LinkedHashMap<>();
        for (int i = 0; i < answer.offers().size(); i++) {
            Apprentice.Pick pick = answer.offers().get(i);
            BuilderPageFit.Judged j = world.apply(pick);
            if (j == null || j.seen() == BuilderPageFit.Seen.DROP) {
                continue;
            }
            if (j.seen() == BuilderPageFit.Seen.LOCK) {
                locked.put(offers.size(), j.line());
            }
            offers.add(pick);
            layer.add(i < answer.layer().size() ? answer.layer().get(i) : List.of());
        }
        return new Shown(new Apprentice.Answer(offers, answer.reach(), answer.unmatched(), layer), locked);
    }

    /** The Apprentice page drawn from a {@link Shown} answer, with its locked offers locked (grey, their line first). */
    public static com.moddingfromamod.ui.PickPage page(com.moddingfromamod.ui.PickPage page, Map<Integer, String> locked) {
        if (locked.isEmpty()) {
            return page;
        }
        List<com.moddingfromamod.ui.PickPage.Tile> tiles = new ArrayList<>();
        for (com.moddingfromamod.ui.PickPage.Tile t : page.tiles()) {
            String line = null;
            for (var e : locked.entrySet()) {
                if (t.id().equals(ApprenticeWords.OFFER + e.getKey())) {
                    line = e.getValue();
                }
            }
            tiles.add(line == null ? t : BuilderPageFit.locked(t, line));
        }
        return new com.moddingfromamod.ui.PickPage(page.title(), tiles, page.back(), page.next(), page.nextLabel(),
                page.picks());
    }
}
