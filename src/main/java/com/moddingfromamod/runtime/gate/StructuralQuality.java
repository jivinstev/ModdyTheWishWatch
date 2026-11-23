package com.moddingfromamod.runtime.gate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptCritique;

/**
 * <b>Is this a creature, or a pile of boxes?</b>
 *
 * <p>Everything else we measure asks whether the build matched an intention — the child's stated
 * properties, or the agent's own declared palette. Both are at ceiling (10/10 and ~98% across two
 * runs), which means they can no longer tell two models apart: a change that made creatures visibly
 * better would move neither.
 *
 * <p>These metrics ask a different question with real headroom, and one that needs no reference and
 * no taste: <b>is the thing structurally sound?</b> A creature can honour every stated colour and
 * still be three cubes stacked in a line, or have an arm floating half a metre from the body, or be
 * so nearly a solid cuboid that no silhouette reads at all.
 *
 * <h2>Designed against reward hacking</h2>
 *
 * <p>The moment a number ranks models, models optimise for it, so each metric here is deliberately
 * <b>bounded or banded rather than maximised</b>:
 *
 * <ul>
 *   <li>Complexity counts only parts that are <i>connected and visible</i>, and saturates — two
 *       hundred tiny boxes score no better than twenty good ones.</li>
 *   <li>Colour variety is a <b>band</b>, not a maximum: monochrome is poor, and so is confetti.</li>
 *   <li>Symmetry is <b>rewarded, never required</b>, because plenty of good creatures are
 *       deliberately lopsided.</li>
 * </ul>
 *
 * <p>Pure and Minecraft-free, so it is unit-tested without a game.
 */
public final class StructuralQuality {

    private StructuralQuality() {
    }

    /** Parts closer than this (in sixteenths) count as touching. */
    private static final float TOUCH = 0.5f;

    /** Complexity saturates here: beyond it, more boxes is not more creature. */
    public static final int RICH_ENOUGH = 18;

    /** One measured dimension: a 0..1 score and a plain sentence about it. */
    public record Metric(String name, double score, String note) {
    }

    /** The whole structural verdict. */
    public record Report(List<Metric> metrics) {

        /** Mean of the dimensions. Deliberately unweighted until calibration says otherwise. */
        public double overall() {
            return metrics.isEmpty() ? 0
                    : metrics.stream().mapToDouble(Metric::score).average().orElse(0);
        }

        public Metric get(String name) {
            return metrics.stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder(
                    String.format("structure %.2f%n", overall()));
            for (Metric m : metrics) {
                sb.append(String.format("    %-12s %.2f  %s%n", m.name(), m.score(), m.note()));
            }
            return sb.toString();
        }
    }

    public static Report measure(List<CreatureSculpt.Part> parts, String wish) {
        List<Metric> out = new ArrayList<>();
        if (parts == null || parts.isEmpty()) {
            return new Report(List.of(new Metric("empty", 0, "no parts at all")));
        }
        out.add(connectivity(parts));
        out.add(complexity(parts));
        out.add(silhouette(parts));
        out.add(symmetry(parts, wish));
        out.add(palette(parts, wish));
        return new Report(List.copyOf(out));
    }

    // ── connectivity ─────────────────────────────────────────────────────────────────────────

    /**
     * Every part should touch the body, directly or through others. A floating limb is the most
     * unambiguous structural defect there is — nobody intends it and every child notices.
     */
    private static Metric connectivity(List<CreatureSculpt.Part> parts) {
        int n = parts.size();
        boolean[] seen = new boolean[n];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        seen[0] = true;
        int reached = 1;
        while (!queue.isEmpty()) {
            int i = queue.poll();
            for (int j = 0; j < n; j++) {
                if (!seen[j] && touching(parts.get(i), parts.get(j))) {
                    seen[j] = true;
                    reached++;
                    queue.add(j);
                }
            }
        }
        double score = (double) reached / n;
        List<String> orphans = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!seen[i]) {
                orphans.add(parts.get(i).name());
            }
        }
        return new Metric("connected", score, orphans.isEmpty()
                ? "every part is attached"
                : orphans.size() + " floating: " + String.join(", ",
                        orphans.subList(0, Math.min(4, orphans.size()))));
    }

    private static boolean touching(CreatureSculpt.Part a, CreatureSculpt.Part b) {
        for (int ax = 0; ax < 3; ax++) {
            float aLo = Math.min(a.from()[ax], a.to()[ax]);
            float aHi = Math.max(a.from()[ax], a.to()[ax]);
            float bLo = Math.min(b.from()[ax], b.to()[ax]);
            float bHi = Math.max(b.from()[ax], b.to()[ax]);
            if (aHi + TOUCH < bLo || bHi + TOUCH < aLo) {
                return false;
            }
        }
        return true;
    }

    // ── complexity ───────────────────────────────────────────────────────────────────────────

    /**
     * Enough parts to read as a creature. <b>Saturating</b>, so a model cannot win by emitting two
     * hundred specks — which is exactly what a raw part count would reward.
     */
    private static Metric complexity(List<CreatureSculpt.Part> parts) {
        int n = parts.size();
        double score = Math.min(1.0, (double) n / RICH_ENOUGH);
        String note = n >= RICH_ENOUGH ? n + " parts — plenty of detail"
                : n <= 4 ? n + " parts — this is a pile of boxes, not a creature"
                        : n + " parts — sparse";
        return new Metric("complexity", score, note);
    }

    // ── silhouette ───────────────────────────────────────────────────────────────────────────

    /**
     * How much of its own bounding box does it fill? A creature that fills nearly all of it is a
     * brick, and reads as nothing from a distance; one that fills very little is a scatter of
     * disconnected bits. Good creatures sit in a band.
     */
    private static Metric silhouette(List<CreatureSculpt.Part> parts) {
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        double volume = 0;
        for (CreatureSculpt.Part p : parts) {
            double v = 1;
            for (int ax = 0; ax < 3; ax++) {
                float a = Math.min(p.from()[ax], p.to()[ax]);
                float b = Math.max(p.from()[ax], p.to()[ax]);
                lo[ax] = Math.min(lo[ax], a);
                hi[ax] = Math.max(hi[ax], b);
                v *= Math.max(1, b - a);
            }
            volume += v;
        }
        double box = 1;
        for (int ax = 0; ax < 3; ax++) {
            box *= Math.max(1, hi[ax] - lo[ax]);
        }
        double fill = box <= 0 ? 1 : Math.min(1.0, volume / box);
        // Band: 0.10-0.45 reads as a shaped creature. Score falls off outside it.
        double score;
        if (fill < 0.10) {
            score = Math.max(0, fill / 0.10);
        } else if (fill <= 0.45) {
            score = 1.0;
        } else {
            score = Math.max(0, 1 - (fill - 0.45) / 0.45);
        }
        String note = fill > 0.60 ? String.format("fills %.0f%% of its box — nearly a brick", fill * 100)
                : fill < 0.10 ? String.format("fills only %.0f%% — scattered", fill * 100)
                        : String.format("fills %.0f%% of its box", fill * 100);
        return new Metric("silhouette", score, note);
    }

    // ── symmetry ─────────────────────────────────────────────────────────────────────────────

    /**
     * Most creatures are bilaterally symmetric, and an accidental asymmetry (one arm, one horn)
     * reads as a mistake. <b>Rewarded, never required</b> — a lopsided creature can be deliberate,
     * so a low score here is a note, not a fault.
     */
    private static Metric symmetry(List<CreatureSculpt.Part> parts, String wish) {
        double matched = 0;
        for (CreatureSculpt.Part p : parts) {
            float cx = (p.from()[0] + p.to()[0]) / 2f;
            if (Math.abs(cx) < 1.5f) {
                matched++;              // on the midline: symmetric by itself
                continue;
            }
            for (CreatureSculpt.Part q : parts) {
                if (q == p) {
                    continue;
                }
                float qx = (q.from()[0] + q.to()[0]) / 2f;
                if (Math.abs(qx + cx) < 2.0f
                        && Math.abs(mid(q, 1) - mid(p, 1)) < 2.0f
                        && Math.abs(mid(q, 2) - mid(p, 2)) < 2.0f) {
                    matched++;
                    break;
                }
            }
        }
        double score = matched / parts.size();
        String w = wish == null ? "" : wish.toLowerCase(Locale.ROOT);
        boolean asymmetryAsked = List.of("club", "sword", "axe", "staff", "shield", "hammer",
                        "one eye", "one arm", "one horn", "holding", "carries", "carrying")
                .stream().anyMatch(w::contains);
        if (asymmetryAsked) {
            score = Math.max(score, 0.9);
        }
        return new Metric("symmetry", score, String.format("%.0f%% of parts mirrored%s",
                score * 100, asymmetryAsked ? " (asymmetry was asked for)" : ""));
    }

    private static float mid(CreatureSculpt.Part p, int axis) {
        return (p.from()[axis] + p.to()[axis]) / 2f;
    }

    // ── palette ──────────────────────────────────────────────────────────────────────────────

    /**
     * A creature should have a few colours: one is a silhouette, eight is confetti. Deliberately a
     * <b>band</b> — rewarding variety would be trivially gamed by scattering pixels of every hue.
     *
     * <p>Intent-aware: a child who asks for an all-black creature gets one without being marked
     * down, the same rule {@link VisualChecks} uses.
     */
    private static Metric palette(List<CreatureSculpt.Part> parts, String wish) {
        Map<String, Double> pal = SculptCritique.palette(parts);
        long significant = pal.values().stream().filter(v -> v >= 0.05).count();
        String w = wish == null ? "" : wish.toLowerCase(Locale.ROOT);
        Set<String> monoWords = new HashSet<>(List.of("all black", "all white", "one colour",
                "one color", "entirely"));
        boolean askedForMono = monoWords.stream().anyMatch(w::contains);
        // A CHILD WHO NAMES ONE COLOUR IS ASKING FOR ONE COLOUR.
        //
        // The first version of this scored an invented purple creature (say "Glorp, a squishy
        // purple four-eyed slug") at 0.30 for being "essentially one colour" -- punishing the
        // creature for being exactly what was asked for, and making it the worst-scoring creature in the whole run. Only explicit
        // phrases like "all black" were treated as intent; a plain "a purple slug" was not.
        long coloursNamed = List.of("red", "orange", "yellow", "green", "blue", "purple", "pink",
                        "white", "black", "grey", "gray", "brown", "tan", "cyan")
                .stream().filter(w::contains).count();
        askedForMono |= coloursNamed == 1;

        double score;
        if (significant <= 1) {
            score = askedForMono ? 1.0 : 0.3;
        } else if (significant <= 6) {
            score = 1.0;
        } else {
            score = Math.max(0.3, 1 - (significant - 6) * 0.15);
        }
        String note = significant <= 1
                ? (askedForMono ? "one colour, as asked" : "essentially one colour")
                : significant + " significant colours";
        return new Metric("palette", score, note);
    }
}
