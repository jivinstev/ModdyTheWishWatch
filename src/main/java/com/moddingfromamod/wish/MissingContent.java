package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>What this wish asked for that is not installed — so a substitute cannot be scored as a grant.</b>
 *
 * <p>THE MEASURED CASE. Asked for a machine that turns logs into BUTT CHEEK WATER, in a
 * world with no companion mod jar, the agent searched three times, was told
 * <i>"Nothing installed matches"</i>, and built {@code grain_mill} plus a chest — a flour mill. It
 * then told the child <i>"YOUR BUTT CHEEK WATER MACHINE IS ALIVE! … BOTTLES of Butt Cheek Water"</i>
 * and the snapshot recorded {@code delivered: 4, working: true}. Both cells did it.
 *
 * <p>Nothing in the metric could disagree: {@code delivered} counts items arriving in a receiver and
 * cannot tell flour from anything else. So the machine that scored highest was the one that did not
 * do what was asked.
 *
 * <p><b>⚠ A FAILED SEARCH IS NOT A SUBSTITUTION, and the first version of this said it was.</b>
 * Measured on six cells of one fluid wish: four were flagged, and every one of them had searched
 * for {@code "sludge"} — a word out of the machine's own name (<i>"a machine called the
 * SLUDGEWORKS"</i>), not content the wish asked for. What it asked for was water, which exists,
 * and two of those four went on to move 17,600mB and 8,000mB of it. A child who invents a name
 * for a machine is the common case, so a flag that fires on it is wrong most of the time.
 *
 * <p>So what is recorded is only the fact: a search was made and came back empty. The QUERIES are
 * the useful output; the boolean is a hint to go and read them. Anything more would be this class
 * inferring intent it cannot see.
 *
 * <p>This does not decide whether the substitute was reasonable — often it is, and for a CREATURE
 * wish "no mod provides it, so build a lookalike" is exactly right. What it records is that the
 * question was asked and the answer was no, which is enough for the grader to treat a green verdict
 * on that rung as <b>unjudgeable rather than passing</b>. The distinction this repo keeps paying
 * for: {@code None} is not {@code False}, and here it is also not {@code True}.
 *
 * <p>Per-wish and explicitly cleared, in the style of {@code ToolNarrator} — a static that is never
 * reset leaks one wish's misses into the next one's verdict, which would be a worse bug than the
 * one being fixed.
 */
public final class MissingContent {

    private static final Set<String> ASKED = new LinkedHashSet<>();
    /** A wish that searched for fifty absent things is a runaway, not a signal. */
    private static final int MAX = 32;

    private MissingContent() {
    }

    /** A search that came back with nothing installed. */
    public static synchronized void record(String query) {
        if (query == null || query.isBlank() || ASKED.size() >= MAX) {
            return;
        }
        ASKED.add(query.trim().toLowerCase(Locale.ROOT));
    }

    /** Everything this wish looked for and did not find, oldest first. */
    public static synchronized List<String> asked() {
        return new ArrayList<>(ASKED);
    }

    /** Did this wish name anything that is not installed? */
    public static synchronized boolean anythingMissing() {
        return !ASKED.isEmpty();
    }

    /** Called when a new wish starts. Not optional — see the class note. */
    public static synchronized void clear() {
        ASKED.clear();
    }
}
