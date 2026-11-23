package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Everything anyone has ever done to one thing</b> — an append-only journal of turns, not a
 * summary that gets rewritten.
 *
 * <h2>Why this replaces {@code HandoverRecord}</h2>
 * That design capped every field at 600 characters and merged a new wish onto the old one by
 * prepending and truncating the tail. It compacts by <b>deleting</b>, and it does the deleting at
 * <i>write</i> time. Measured on Steve's own save: {@code crushing_plant} reached revision 8 with
 * <b>two</b> surviving segments of reasoning, both fields sitting at 598/597 of 600 characters, cut
 * mid-sentence — six turns' worth of "why" gone, and nothing in the record saying so.
 *
 * <p>Three things are wrong with that, in increasing order of seriousness.
 *
 * <ol>
 *   <li><b>The budget was two orders of magnitude too small.</b> The whole record is 1,749
 *       characters ≈ 437 tokens, against the ~85,000-token context an agent re-reads on <i>every
 *       turn</i>. It was costing 0.5% of one turn and being truncated to protect a wake payload
 *       that was never the expensive thing. The plan's own criterion 6 allows 1500 tokens.</li>
 *   <li><b>It answered a read-time question at write time.</b> Which is fatal rather than merely
 *       wasteful: once the oldest reasoning is truncated away, <b>no</b> better renderer can ever
 *       get it back. So storage here is deliberately unbounded-ish and generous, and the budget
 *       lives entirely in {@link MemoryBrief}, where it can be tuned, measured, and changed by an
 *       experiment without destroying the evidence.</li>
 *   <li><b>It kept conclusions and dropped trajectory,</b> which is most of what sequential
 *       refinement <i>is</i>. "Thinner than that" means nothing without the previous attempt.</li>
 * </ol>
 *
 * <h2>What a turn keeps, and why each field earns its place</h2>
 * Almost all of it is <b>free</b> — already known to the run or already asked for by
 * {@code finish_wish} — which is the point. A handover form with eight new fields gets filled in
 * with eight shrugs.
 *
 * <ul>
 *   <li>{@code wish} — <b>his verbatim words.</b> The highest-value and smallest part: he re-uses
 *       his own phrasing ("the crushing plant") and an agent given a paraphrase of its own earlier
 *       paraphrase is doing lossy compression repeatedly.</li>
 *   <li>{@code intent} — what the agent understood him to mean. Free: the resolver's subject.</li>
 *   <li>{@code did} / {@code why} — free: what the run made, and its own design note.</li>
 *   <li>{@code rejected} — what was tried and abandoned, <b>with the reason</b>. The one thing
 *       nothing else records anywhere, and the reason a second turn re-walks a dead end.</li>
 *   <li>{@code saidToChild} — free: {@code finish_wish}'s note. Without it an agent re-explains
 *       things he already knows and re-asks things he already answered.</li>
 *   <li>{@code verdict} — free, and previously thrown away. "This turn came out <i>rough</i>" is
 *       exactly the trajectory a summary flattens.</li>
 * </ul>
 *
 * <p>{@code state} is separate from the turns because it is not history — it is what is
 * <b>true now</b> (the scale, the coordinates, which datapacks exist). Today's record has no such
 * field, so agents have been smuggling it into {@code beCarefulOf}; {@code crushing_plant}'s reads
 * as a command listing rather than as a warning, which is the design telling us what it needs.
 *
 * <p>Pure — no game types, no I/O — so the merge rules and the budget are unit tests.
 */
public record ArtifactMemory(String artifactId, String kind, String whatItIs, List<String> aliases,
                             String where, List<String> state, List<Turn> turns,
                             int carriedOverRevisions) {

    /**
     * A generous per-field ceiling, and a <b>safety net rather than a policy</b>.
     *
     * <p>Ten times the whole of yesterday's per-field budget. Not chosen to fit anything: real
     * reasoning about a machine runs to a paragraph or two, and this is comfortably past that, so in
     * practice it never bites. It exists only so a runaway caller cannot write a megabyte into a
     * world save. What the agent actually READS is bounded by {@link MemoryBrief}, which is where a
     * budget belongs.
     */
    public static final int MAX_FIELD_CHARS = 4_000;

    /** Below this, a "note" is someone typing something to get past the tool. */
    public static final int MIN_USEFUL_CHARS = 24;

    /**
     * One wish's worth of work on this thing.
     *
     * @param wishId      so an append is idempotent and two workers cannot double-record one turn
     * @param rejected    what was tried and abandoned, one entry per thing, never merged into prose
     */
    public record Turn(String wishId, long atMs, String workerId, String wish, String intent,
                       String did, String why, List<String> rejected, String saidToChild,
                       String verdict, String careful) {

        public Turn {
            wishId = trim(wishId, 64);
            workerId = trim(workerId, 64);
            wish = trim(wish, MAX_FIELD_CHARS);
            intent = trim(intent, 400);
            did = trim(did, MAX_FIELD_CHARS);
            why = trim(why, MAX_FIELD_CHARS);
            saidToChild = trim(saidToChild, MAX_FIELD_CHARS);
            verdict = trim(verdict, 32);
            careful = trim(careful, MAX_FIELD_CHARS);
            rejected = clean(rejected, MAX_FIELD_CHARS);
        }

        /** A turn that teaches the next agent nothing about WHY or about what failed. */
        public boolean isThin() {
            return why.length() + String.join("", rejected).length() < MIN_USEFUL_CHARS;
        }
    }

    /** Compact form: nothing carried over from the pre-journal format. */
    public ArtifactMemory(String artifactId, String kind, String whatItIs, List<String> aliases,
                          String where, List<String> state, List<Turn> turns) {
        this(artifactId, kind, whatItIs, aliases, where, state, turns, 0);
    }

    public ArtifactMemory {
        carriedOverRevisions = Math.max(0, carriedOverRevisions);
        artifactId = trim(artifactId, 96).toLowerCase(Locale.ROOT);
        kind = trim(kind, 32);
        whatItIs = trim(whatItIs, MAX_FIELD_CHARS);
        where = trim(where, 96);
        aliases = clean(aliases, 96);
        state = clean(state, MAX_FIELD_CHARS);
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    /** An empty memory for a thing nobody has notes on yet. */
    public static ArtifactMemory of(String artifactId, String kind) {
        return new ArtifactMemory(artifactId, kind, "", List.of(), "", List.of(), List.of());
    }

    /**
     * How many wishes have touched this thing.
     *
     * <p>Includes {@code carriedOverRevisions} — the edits that happened under the old format and
     * were folded into one synthetic turn. Without that the brief tells a returning agent "this is
     * edit #5" about a machine Steve has changed twelve times, which is a small lie in the one field
     * whose whole job is to say how much history it is looking at.
     */
    public int revision() {
        return turns.size() + carriedOverRevisions;
    }

    /** The original intent — what he actually wanted, before any of the refining. */
    public Turn firstTurn() {
        return turns.isEmpty() ? null : turns.get(0);
    }

    /**
     * The names he has actually <b>re-used</b> for this thing.
     *
     * <p>An alias is only a name if it comes back. {@code AliasBackfill} treats SHOUTING as a naming
     * signal — right that a seven-year-old capitalises what he cares about, wrong that what he cares
     * about is always the name. Steve's real {@code crushing_plant} collected
     * {@code [grinds, magically, butt cheek water, nothing happens, animate]}: four emphasis words
     * and one actual name. "nothing happens" as an alias means a wish saying <i>"I built a dragon
     * but nothing happens"</i> resolves to the crushing plant — and since rung 0 is now also what
     * {@link WishAffinity} routes on, a false alias is a mis-ROUTE as well as a wrong brief.
     *
     * <p>Requiring two sightings is the cheapest rule that separates the two, needs no model, and is
     * only possible at all because turns are kept individually: the merged format had one alias list
     * and no way to ask how often he said something. A single-turn artifact keeps its aliases as-is,
     * since there is nothing yet to confirm them against.
     */
    public List<String> confirmedAliases() {
        if (turns.size() < 2) {
            return aliases;
        }
        List<String> out = new ArrayList<>();
        for (String alias : aliases) {
            String needle = alias.toLowerCase(Locale.ROOT);
            int seen = 0;
            for (Turn t : turns) {
                if (t.wish().toLowerCase(Locale.ROOT).contains(needle)) {
                    seen++;
                }
            }
            // Or it is simply the artifact's own name, which needs no confirming.
            if (seen >= 2 || artifactId.replace('_', ' ').equalsIgnoreCase(alias)) {
                out.add(alias);
            }
        }
        return out;
    }

    /**
     * Everything ever tried and rejected, oldest first, de-duplicated.
     *
     * <p>Flattened across every turn on purpose. Under the old design this was ONE 600-character
     * field for the whole life of an artifact, so by the fifth revision the reasons the first three
     * shapes were wrong had been compressed out — and re-walking a dead end is the single most
     * expensive thing a refinement can do.
     */
    public List<String> allRejected() {
        Set<String> seen = new LinkedHashSet<>();
        for (Turn t : turns) {
            seen.addAll(t.rejected());
        }
        return new ArrayList<>(seen);
    }

    /**
     * Append a turn. <b>Idempotent per wish</b>, and that is what makes two workers on one artifact
     * safe without a lease: re-recording the same wish replaces its turn rather than adding a second.
     *
     * <p>The head fields update rather than accumulate, because they describe the thing as it is
     * now — but {@code whatItIs} is only ever <b>filled in</b>, never overwritten by a later turn.
     * The old design overwrote it with the newest wish text, so {@code crushing_plant}'s "what it
     * is" had become <i>"cool can you ANIMATE the tree parts flying into the grinder"</i>: an agent
     * waking on turn 9 was told the artifact IS turn 8's request.
     */
    public ArtifactMemory plus(Turn turn, String newWhatItIs, String newWhere,
                               List<String> newState, List<String> newAliases) {
        if (turn == null) {
            return this;
        }
        List<Turn> merged = new ArrayList<>();
        boolean replaced = false;
        for (Turn t : turns) {
            if (!t.wishId().isEmpty() && t.wishId().equals(turn.wishId())) {
                merged.add(turn);
                replaced = true;
            } else {
                merged.add(t);
            }
        }
        if (!replaced) {
            merged.add(turn);
        }
        Set<String> mergedAliases = new LinkedHashSet<>(aliases);
        if (newAliases != null) {
            mergedAliases.addAll(clean(newAliases, 96));
        }
        return new ArtifactMemory(
                artifactId, kind,
                whatItIs.isEmpty() ? orBlank(newWhatItIs) : whatItIs,
                new ArrayList<>(mergedAliases),
                orElse(newWhere, where),
                newState == null || newState.isEmpty() ? state : newState,
                merged, carriedOverRevisions);
    }

    /** Correct the description — the one head field a later turn may deliberately rewrite. */
    public ArtifactMemory describedAs(String description) {
        String d = orBlank(description);
        return d.isEmpty() ? this
                : new ArtifactMemory(artifactId, kind, d, aliases, where, state, turns,
                        carriedOverRevisions);
    }

    /** How many edits happened before turn-by-turn notes existed. Set once, by the migration. */
    public ArtifactMemory carryingOver(int priorRevisions) {
        return new ArtifactMemory(artifactId, kind, whatItIs, aliases, where, state, turns,
                Math.max(0, priorRevisions));
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static String orBlank(String s) {
        return s == null ? "" : s.strip();
    }

    private static String orElse(String preferred, String fallback) {
        String p = orBlank(preferred);
        return p.isEmpty() ? fallback : p;
    }

    private static List<String> clean(List<String> in, int max) {
        if (in == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            String t = trim(s, max);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.length() <= max) {
            return t;
        }
        // Cut on a word boundary and SAY SO. A record ending mid-word reads as corrupted data
        // rather than as a long note, and the agent reading it cannot tell which — which is exactly
        // what 598-of-600 characters looked like in every record the old design produced.
        int cut = t.lastIndexOf(' ', max);
        return (cut > max / 2 ? t.substring(0, cut) : t.substring(0, max)).strip() + " […cut]";
    }
}
