package com.moddingfromamod.wish;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.world.MakeView;
import com.moddingfromamod.world.WorldLimits;

/**
 * <b>What the core does with a world's {@link com.moddingfromamod.world.WorldMakes} answer</b>: whether the make is
 * shown, fitted, shown locked or not offered, and the one line the player reads. Pure: the sweep is handed in, so Gate A
 * decides every case without Minecraft ({@code MakeVerdictTest}).
 *
 * <ul>
 *   <li>No answer (or {@code null}) and no limits: allowed, as with no world at all.</li>
 *   <li>A child in distress is never stopped or fitted (as with the charge): allowed, whatever the world answered.</li>
 *   <li>Hidden: not offered here; pressed anyway (a replay, a hotbar fire, a stale page) it says {@link #HIDDEN_LINE}.</li>
 *   <li>Locked: shown, not made; its line is the world's words when they pass the text sweep a chat line passes, else
 *       {@link #LOCKED_FALLBACK}.</li>
 *   <li>Fit: made within {@link #limits}, the world's up-front limits and the answer's own, the tighter of each. A make
 *       that cannot be fitted is {@link #locked} in {@link #TOO_MUCH}; one that was is told once in {@link #fittedLine}.</li>
 * </ul>
 */
public record MakeVerdict(MakeView.State state, String line, Map<String, Double> limits) {

    public static final MakeVerdict ALLOWED = new MakeVerdict(MakeView.State.ALLOWED, "", Map.of());

    /** Said when a make this world does not offer is pressed anyway. */
    public static final String HIDDEN_LINE = "✨ That can't be made in this world. Try something else!";

    /** Said in place of a world's lock words that do not pass the text sweep, or when it gave none. */
    public static final String LOCKED_FALLBACK = "🔒 That one is locked in this world for now.";

    /** Said for a make the core cannot fit to this world's limits (a number no knob lowers, or none low enough). */
    public static final String TOO_MUCH = "🔒 That one is too much for this world right now.";

    public MakeVerdict {
        limits = limits == null ? Map.of() : Map.copyOf(limits);
        line = line == null ? "" : line;
    }

    public boolean allowed() {
        return state == MakeView.State.ALLOWED;
    }

    public boolean hidden() {
        return state == MakeView.State.HIDDEN;
    }

    public boolean locked() {
        return state == MakeView.State.LOCKED;
    }

    /** May it be made (as it is, or fitted to {@link #limits})? */
    public boolean makes() {
        return state == MakeView.State.ALLOWED || state == MakeView.State.FIT;
    }

    /** A make this verdict let through but the core could not fit: locked, in the core's own words. */
    public MakeVerdict tooMuch() {
        return new MakeVerdict(MakeView.State.LOCKED, TOO_MUCH, Map.of());
    }

    /**
     * @param distress the safety lexicon read this wish as a child in distress (decides first)
     * @param view     the world's answer about this make ({@code null} reads as allowed)
     * @param upFront  the world's limits for every make ({@link com.moddingfromamod.world.WorldMakes#limits})
     * @param sweeps   true when a line passes the text sweep
     */
    public static MakeVerdict decide(boolean distress, MakeView view, Map<String, Double> upFront, Predicate<String> sweeps) {
        if (distress) {
            return ALLOWED;
        }
        MakeView v = view == null ? MakeView.ALLOWED : view;
        switch (v.state()) {
            case HIDDEN -> {
                return new MakeVerdict(MakeView.State.HIDDEN, HIDDEN_LINE, Map.of());
            }
            case LOCKED -> {
                String words = v.words();
                boolean passes;
                try {
                    passes = !words.isEmpty() && sweeps.test(words);
                } catch (RuntimeException e) {
                    passes = false;   // a line that could not be swept is not said
                }
                return new MakeVerdict(MakeView.State.LOCKED, passes ? words : LOCKED_FALLBACK, Map.of());
            }
            default -> {
                Map<String, Double> limits = WorldLimits.tightest(upFront, v.state() == MakeView.State.FIT ? v.limits() : Map.of());
                limits = known(limits);
                return limits.isEmpty() ? ALLOWED : new MakeVerdict(MakeView.State.FIT, "", limits);
            }
        }
    }

    /** Only the vocabulary's own keys: a key the core does not know is ignored. */
    static Map<String, Double> known(Map<String, Double> limits) {
        Map<String, Double> out = new java.util.TreeMap<>();
        limits.forEach((k, v) -> {
            if (MakeFacts.NUMBERS.contains(k)) {
                out.put(k, v);
            }
        });
        return out;
    }

    /**
     * The one short line a make that was fitted is told, in the core's own words, by what was lowered: smaller, fewer,
     * or gentler. Empty when nothing was.
     */
    public static String fittedLine(List<String> lowered) {
        if (lowered == null || lowered.isEmpty()) {
            return "";
        }
        if (lowered.contains(MakeFacts.SCALE_TIMES) || lowered.contains(MakeFacts.BUILDER_SIZE_STEP)) {
            return "✨ Made a bit smaller for this world.";
        }
        if (lowered.contains(MakeFacts.COUNT_MADE) || lowered.contains(MakeFacts.AMOUNT_ITEMS)) {
            return "✨ Made fewer for this world.";
        }
        return "✨ Made a bit gentler for this world.";
    }
}
