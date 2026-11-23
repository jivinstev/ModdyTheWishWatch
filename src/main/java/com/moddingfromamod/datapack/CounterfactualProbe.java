package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>"Only at night" — go and check it is not ALSO happening at noon.</b>
 *
 * <h2>The defect, and why it is the only one source inspection cannot see</h2>
 * A generated {@code raincod} pack asked for cod only at night. It rained cod at night as asked,
 * and at roughly 2% of that rate <b>during the day</b>. Every cheap instrument called the cell
 * perfect: the script contained a correct time gate
 * ({@code execute if score sky codsky matches 13000..22999 run function cod_rain:downpour}),
 * {@code source_has:night} passed, a blind model grader said yes, and the ladder scored it
 * <b>1.000</b>. The clause was present; it simply was not the only path — a phase counter kept
 * advancing and summoning whether or not the gated function ran.
 *
 * <p>Reading the source cannot distinguish <i>"the gate is there"</i> from <i>"the gate is the only
 * path"</i>. Counting cod at midday can.
 *
 * <h2>Measured rate, and the approach that FAILED</h2>
 * Characterised at <b>1 leak in 5</b> generated scripts (runs of 0/0/0/0/8, plus the original 7).
 * Guidance was tried first, per the standing preference, and <b>did not work</b>: a
 * {@code lessons/datapack.md} rule saying "gate EVERY path" was added and the very first
 * verification run leaked <b>20</b> cod in daylight — worse than before. It was reverted. The
 * issue's own reading of why: the two lessons that worked that day told the agent <i>a capability
 * exists</i>, while this one asks it to be exhaustive about the control flow of code it is still
 * writing — a property of its own output rather than a fact about the toolset.
 *
 * <h2>Why this is NOT a static analyser, which explicitly rejects</h2>
 * <i>"Not static analysis of generated CBScript. Deciding 'is this spawn inside the gate' means
 * parsing control flow, and being wrong about it either blocks good packs or waves bad ones
 * through."</i> That judgement stands and nothing here makes it.
 *
 * <p>This class answers a far smaller question — <b>does this pack gate on time at all, and if so
 * which way round?</b> — which a regex genuinely can settle, and then hands the agent the two
 * commands that put the world into the other state. The verdict comes from the world, which is the
 * issue's own named next step: <i>"a tool call with a yes/no answer, not a judgement about code."</i>
 * If a second path leaks, the leak shows up as cod at noon whether or not anyone can parse the
 * control flow that produced it.
 *
 * <h2>Reported, never a refusal</h2>
 * A time gate is not a defect and this cannot tell a leaking pack from a sound one — only the
 * agent's own observation can. So it rides out on {@code verify_datapack}'s report in the moment
 * before the child is told it works, exactly like the dead-selector warning beside it.
 */
public final class CounterfactualProbe {

    /**
     * One world state to put the game into, and what the agent should see when it does.
     *
     * @param gate     the condition found, in the child's terms ("only at NIGHT")
     * @param setup    the command that makes the condition FALSE
     * @param restore  the command that puts it back, so the child's world is left as he wants it
     */
    public record Probe(String gate, String setup, String restore) {
    }

    /** Minecraft's day cycle. Night is 13000..22999; everything else is day. */
    private static final int NIGHT_FROM = 13000;
    private static final int NIGHT_TO = 22999;

    /**
     * A score compared against a numeric range. CBScript emits the vanilla form, so this matches
     * {@code matches 13000..22999}, {@code matches 13000..} and {@code matches ..12999} alike.
     */
    private static final Pattern RANGE = Pattern.compile(
            "matches\\s+(\\d+)?\\s*\\.\\.\\s*(\\d+)?");

    private CounterfactualProbe() {
    }

    /**
     * The counterfactual to run for this program, or {@code null} if it does not gate on time.
     *
     * <p>Both halves are required. {@code time query daytime} alone is a readout — a pack may show
     * the time without acting on it — and a numeric range alone is any counter at all, including
     * the periodic trigger's own tick clock. It is the two together that mean "this acts at some
     * times and not others".
     */
    public static Probe of(String cbscript) {
        if (cbscript == null || cbscript.isBlank()) {
            return null;
        }
        String src = code(cbscript);
        if (!src.contains("daytime")) {
            return null;
        }
        Matcher m = RANGE.matcher(src);
        while (m.find()) {
            Integer low = m.group(1) == null ? null : Integer.parseInt(m.group(1));
            Integer high = m.group(2) == null ? null : Integer.parseInt(m.group(2));
            // Out of the day-cycle's range entirely (a tick counter that happens to sit in the
            // same file) — not a time gate, keep looking.
            if (low != null && low > 24000) {
                continue;
            }
            if (isNight(low, high)) {
                return new Probe("only at NIGHT", "/time set noon", "/time set midnight");
            }
            if (isDay(low, high)) {
                return new Probe("only during the DAY", "/time set midnight", "/time set noon");
            }
        }
        return null;
    }

    /** The whole warning, or empty — the string {@code verify_datapack} appends. */
    public static String warning(String cbscript) {
        Probe p = of(cbscript);
        if (p == null) {
            return "";
        }
        return "\n⏰ CHECK THE OTHER HALF OF THE CLOCK: this pack acts " + p.gate()
                + ", so before you tell him it works, prove it does NOT happen the rest of the "
                + "time. Run `" + p.setup() + "`, watch for a few seconds, confirm NOTHING "
                + "happens, then `" + p.restore() + "`. A gate that reads perfectly can still be "
                + "bypassed by a second path you did not think about while writing it — measured "
                + "at one script in five, and reading your own source cannot tell you which one "
                + "you wrote. Only looking at the world can.";
    }

    private static boolean isNight(Integer low, Integer high) {
        // Fully inside the night window, either bound open. `13000..` and `13000..22999` are both
        // night gates; so is `..22999` combined with a low bound the author left implicit.
        return low != null && low >= NIGHT_FROM && (high == null || high <= NIGHT_TO);
    }

    private static boolean isDay(Integer low, Integer high) {
        // Wholly before nightfall. `..12999` is the common form; `0..12999` the explicit one.
        return high != null && high < NIGHT_FROM && (low == null || low < NIGHT_FROM);
    }

    /**
     * Code lines only, lowercased.
     *
     * <p>Shares {@link TriggerShape}'s reason for existing: every generated pack carries a comment
     * header, and one that says <i>"only at night"</i> in prose would otherwise be read as a gate
     * the program does not have — sending the agent to check a condition it never implemented.
     */
    private static String code(String cbscript) {
        List<String> out = new ArrayList<>();
        for (String line : cbscript.split("\n", -1)) {
            int hash = line.indexOf('#');
            if (hash >= 0 && line.substring(0, hash).isBlank()) {
                continue;
            }
            out.add(line);
        }
        return String.join("\n", out).toLowerCase(Locale.ROOT);
    }
}
