package com.moddingfromamod.reference;

import java.util.List;
import java.util.Optional;

import com.moddingfromamod.wish.CreatureCorpus;

/**
 * Run the resolver over every wish in the corpus and show what it extracted.
 *
 * <pre>./gradlew resolveCorpus</pre>
 *
 * <p>The cheap first step before anything downstream is built: if the resolver cannot name the
 * character, nothing that depends on it can work, and every later measurement is of a feature that
 * never fired. That has already happened twice.
 *
 * <p>Scored against the corpus tier, which encodes the right answer — a NAMED creature should
 * produce a name, and every other tier should produce nothing.
 */
public final class ResolveCorpus {

    private ResolveCorpus() {
    }

    /** {wish, expected kind or null for NONE}. Synthetic phrasings, never a child's words. */
    static final List<String[]> PICTURES = List.of(
            new String[]{"a painting of Professor Pebble on my wall", "CHARACTER"},
            new String[]{"put a picture of Dash the fox on the train", "CHARACTER"},
            new String[]{"make a big picture of a creeper", "CHARACTER"},
            new String[]{"paint a capybara on the side of my house", "CREATURE"},
            new String[]{"make a picture of PixelPete", "PERSON"},
            new String[]{"a poster of my friend Jake", null},
            new String[]{"paint a sunset on the wall", null},
            new String[]{"a picture of a castle", null});

    public static void main(String[] args) {
        var cases = CreatureCorpus.load();
        int wantName = 0;
        int gotName = 0;
        int wantNone = 0;
        int gotNone = 0;
        System.out.printf("%-18s %-10s %-26s %s%n", "corpus id", "tier", "resolved", "verdict");
        System.out.println("-".repeat(78));
        for (var c : cases) {
            Optional<String> r = CharacterResolver.resolve(c.prompt());
            boolean shouldName = "named".equals(c.tier());
            boolean ok;
            if (shouldName) {
                wantName++;
                ok = r.isPresent();
                if (ok) {
                    gotName++;
                }
            } else {
                wantNone++;
                ok = r.isEmpty();
                if (ok) {
                    gotNone++;
                }
            }
            System.out.printf("%-18s %-10s %-26s %s%n", c.id(), c.tier(),
                    r.orElse("(none)"), ok ? "ok" : "WRONG");
        }
        System.out.println("-".repeat(78));
        System.out.printf("named creatures resolved:      %d/%d%n", gotName, wantName);
        System.out.printf("non-named correctly left alone: %d/%d%n", gotNone, wantNone);
        // ── PICTURE PHRASINGS. "a painting of Spikor" was never in the creature corpus,
        // so whether a picture wish resolves at all was unmeasured. Expected kind, or null = NONE.
        // A PERSON row also passes through PublicFigure, so "Jake" (no article) must be NONE.
        int picOk = 0;
        System.out.println();
        for (String[] row : PICTURES) {
            var got = CharacterResolver.resolveSubject(row[0]);
            String want = row[1];
            boolean ok = want == null ? got.isEmpty()
                    : got.isPresent() && got.get().kind().name().equals(want);
            if (ok) {
                picOk++;
            }
            System.out.printf("%-44s %-10s %-26s %s%n", row[0], want == null ? "NONE" : want,
                    got.map(r -> r.kind() + " " + r.name()).orElse("(none)"), ok ? "ok" : "WRONG");
        }
        System.out.printf("picture phrasings resolved:    %d/%d%n", picOk, PICTURES.size());
        System.out.println("\nA miss on a NAMED creature is the expensive one: the whole feature "
                + "silently does nothing for it.\nA false positive costs one ~26s background "
                + "search nobody waits for.");
    }
}
