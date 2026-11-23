package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptCritique;

/** Every visual check, with its severity declared through the rubric. */
public final class VisualChecks {

    private VisualChecks() {
    }

    public static List<VisualCheck> all() {
        return List.of(new BuriedParts(), new DegenerateGeometry(), new UndeclaredPaletteLoss(),
                new MonochromeCollapse());
    }

    /** Findings grouped by what they permit. */
    public record Verdict(List<String> blocking, List<String> warnings) {
        public boolean blocked() {
            return !blocking.isEmpty();
        }
    }

    public static Verdict inspect(VisualCheck.Subject subject) {
        List<String> block = new ArrayList<>();
        List<String> warn = new ArrayList<>();
        for (VisualCheck c : all()) {
            List<String> found;
            try {
                found = c.run(subject);
            } catch (RuntimeException e) {
                continue;   // a broken check must never break a wish
            }
            for (String f : found) {
                (c.gate().severity() == VisualGate.Severity.BLOCK ? block : warn)
                        .add(c.gate().name() + ": " + f);
            }
        }
        return new Verdict(List.copyOf(block), List.copyOf(warn));
    }

    // ───────────────────────────────── BLOCK ─────────────────────────────────

    /** A part nobody can see is a part you didn't build. */
    static final class BuriedParts implements VisualCheck {
        @Override
        public VisualGate gate() {
            // DEMOTED, and the reason is worth keeping. This shipped as a BLOCK on the strength of
            // "Chucko's face was buried in his hair". Measured properly against the real sculpts,
            // that is FALSE: his head is 72% visible from the front, the most of all three Chuckos
            // (the accepted one is 56%). The original 20% came from my own metric measuring the
            // BACK of the head. The defect was an artifact of the measurement, not of the creature.
            //
            // A wholly enclosed part is still worth saying something about, so the check survives —
            // but it has no validated real-world case, which is criterion 4 failing, so it may only
            // warn until one turns up.
            return VisualGate.warning("buried part",
                    "a part invisible from the front and both sides is, to the child, not built",
                    "no validated real case — the mascot-clown example it was built on was a measurement "
                            + "artifact, so there is no proven true-positive, let alone a "
                            + "non-trigger test");
        }

        @Override
        public List<String> run(Subject s) {
            // Not a change-check: a buried face is wrong whether or not it was intended, so
            // criterion 6 is satisfied vacuously.
            return SculptCritique.buried(s.parts());
        }
    }

    /** Geometry that cannot render as anything. */
    static final class DegenerateGeometry implements VisualCheck {
        @Override
        public VisualGate gate() {
            return VisualGate.blocking("degenerate geometry",
                    "a zero-thickness or detached box renders as nothing or as a floating "
                            + "fragment; both read as a broken creature");
        }

        @Override
        public List<String> run(Subject s) {
            List<String> out = new ArrayList<>();
            for (CreatureSculpt.Part p : s.parts()) {
                if (p.width() <= 0 || p.height() <= 0 || p.depth() <= 0) {
                    out.add(p.name() + " has no thickness (" + p.width() + "x" + p.height() + "x"
                            + p.depth() + ") so it renders as nothing — give it a real size");
                }
            }
            return out;
        }
    }

    /**
     * A revision that threw away a colour nobody asked it to touch.
     *
     * <p><b>The Spikor gate.</b> Six consecutive revisions each made him less like Spikor: skin
     * tone fell 64% → 5% in one step and never came back, and every later revision built on the
     * broken version. None of those revisions was ABOUT colour — they were about fire breath and
     * size — so the loss was collateral every time.
     *
     * <p>Criterion 6 is the whole design here. It fires only when the agent did <b>not</b> declare
     * a colour change, so "make Spikor all green" is obeyed without complaint and "add fire breath"
     * that silently drains him green is caught.
     */
    static final class UndeclaredPaletteLoss implements VisualCheck {
        /** A colour has to have been a real presence before, not a speck. */
        private static final double WAS_SIGNIFICANT = 0.15;
        /**
         * ...and has to have lost most of itself — measured RELATIVELY.
         *
         * <p>The first version used an absolute floor ("now below 5%") and <b>missed the real
         * Spikor regression by 0.003</b>: tan went 64.0% → 5.34%, which is a 92% loss of that
         * colour, and the gate called it fine. An absolute floor is an arbitrary number; "it lost
         * nearly all of itself" is the actual defect, and it scales to colours that started at 20%
         * as well as ones that started at 64%.
         */
        private static final double LOST_FRACTION = 0.70;

        @Override
        public VisualGate gate() {
            return VisualGate.blocking("undeclared palette loss",
                    "a revision that removes a colour the child never asked to change is a side "
                            + "effect, and side effects are the bug — a frog knight lost his tan across "
                            + "six revisions that were about fire breath and size");
        }

        @Override
        public List<String> run(Subject s) {
            if (!s.isRevision()) {
                return List.of();
            }
            // CRITERION 6. If the agent said it was recolouring, this is obedience, not damage.
            if (s.declaredMentions("colour", "color", "recolour", "recolor", "palette", "paint",
                    "green", "red", "blue", "yellow", "tan", "orange", "purple", "white", "black")) {
                return List.of();
            }
            Map<String, Double> before = SculptCritique.palette(s.previous());
            Map<String, Double> after = SculptCritique.palette(s.parts());
            List<String> out = new ArrayList<>();
            before.forEach((colour, was) -> {
                double now = after.getOrDefault(colour, 0.0);
                if (was >= WAS_SIGNIFICANT && (was - now) / was >= LOST_FRACTION) {
                    out.add(String.format("this creature was %.0f%% %s and is now %.0f%% — and you "
                            + "did not say you were changing its colours. Either put it back, or "
                            + "say plainly that you meant to change how it looks.",
                            was * 100, colour, now * 100));
                }
            });
            return out;
        }
    }

    // ───────────────────────────────── WARN ─────────────────────────────────

    /** One colour swallowing the whole creature. */
    static final class MonochromeCollapse implements VisualCheck {
        private static final double DOMINATES = 0.90;

        @Override
        public VisualGate gate() {
            // Cannot block: a slime, a snow golem, an ender creature are all legitimately one
            // colour, so there is no honest non-trigger test (criterion 4).
            return VisualGate.warning("monochrome",
                    "a creature that is one colour usually means detail was lost rather than "
                            + "intended",
                    "some creatures really are one colour, so there is no legitimate-case test");
        }

        @Override
        public List<String> run(Subject s) {
            Map<String, Double> pal = SculptCritique.palette(s.parts());
            List<String> out = new ArrayList<>();
            pal.forEach((c, share) -> {
                // CRITERION 6 again. An invented creature was asked for as "a squishy PURPLE slug" and
                // came out 99% purple, which is obedience. Warning about it was the gate's only
                // false positive on the one creature the child accepted first time.
                if (s.declaredMentions(c.split("/"))) {
                    return;
                }
                if (share >= DOMINATES) {
                    out.add(String.format("%.0f%% of this creature is %s. If it is meant to have "
                            + "markings, a face or a shell, they are not there.", share * 100, c));
                }
            });
            return out;
        }
    }
}
