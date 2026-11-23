package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;

/**
 * What the next agent to touch this thing needs to know — <b>a document, not a session.</b>
 *
 * <p>This is the piece that makes pinning optional, and that is what it is actually for. It is
 * tempting to justify it as a latency win, but the measurement says otherwise: introspection costs
 * <b>11.1s per wish, 5.8% of in-wish latency</b> over 86 granted wishes, and only about a third of
 * those calls are about a prior artifact at all — the rest are {@code find_content} and
 * {@code look_around}, questions about the world that no handover record can answer. And that 11.1s
 * was measured on an agent that already had session memory, so it is a floor.
 *
 * <p>Its real value is that <b>the context stops living in a session</b>. A worker that dies, a game
 * that restarts, a child who comes back the next morning — none of them lose it. Which in turn means
 * the scheduler never has to make him wait for a particular agent to be free, because any agent
 * arrives knowing what the last one decided. It is also inspectable and testable in a way a
 * session's memory never is, and it is the same artifact a future "upgrade this wish to real Java"
 * would need.
 *
 * <h2>Written at finish, not while working</h2>
 * Deliberately. Only at the end does a worker know what actually <i>happened</i>, as opposed to what
 * it intended — and {@code triedAndRejected} in particular can only be written afterwards. That
 * field is the one nothing captures today, and it is the part a second turn of the crank most needs,
 * because otherwise it re-walks the same dead end.
 *
 * <p>Bounded, like the lessons files. The risk here is a lazy record rather than a missing one,
 * which is a prompt-discipline problem — so the shape is fixed, the size is capped, and
 * {@link #isTrivial()} is what a gate fails on.
 *
 * <p>Pure — no game types, no I/O — so both the trimming and the triviality rule are unit tests.
 */
public record HandoverRecord(String artifactId, String kind, String whatItIs, List<String> aliases,
                             String decisionsAndWhy, String triedAndRejected, String where,
                             int revision, String beCarefulOf, long writtenMs) {

    /** Per field. Generous enough for real reasoning, small enough to ride on an await_wish wake. */
    public static final int MAX_FIELD_CHARS = 600;

    /** Below this, a "record" is someone typing something to get past the tool. */
    public static final int MIN_USEFUL_CHARS = 24;

    public HandoverRecord {
        artifactId = trim(artifactId, 96);
        kind = trim(kind, 32);
        whatItIs = trim(whatItIs, MAX_FIELD_CHARS);
        decisionsAndWhy = trim(decisionsAndWhy, MAX_FIELD_CHARS);
        triedAndRejected = trim(triedAndRejected, MAX_FIELD_CHARS);
        where = trim(where, 96);
        beCarefulOf = trim(beCarefulOf, MAX_FIELD_CHARS);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        revision = Math.max(1, revision);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.length() <= max) {
            return t;
        }
        // Cut on a word boundary: a record ending mid-word reads as corrupted data rather than as a
        // long note, and the agent reading it cannot tell which.
        int cut = t.lastIndexOf(' ', max);
        return (cut > max / 2 ? t.substring(0, cut) : t.substring(0, max)).strip() + "…";
    }

    /**
     * A record that would teach the next agent nothing.
     *
     * <p>What a gate fails on. The two fields checked are the two that cannot be reconstructed from
     * anywhere else: <b>why</b> it was built this way, and <b>what was tried and did not work</b>.
     * Everything else — the name, the kind, the position — is already on disk in the manifest, so a
     * record missing those is thin rather than useless.
     */
    public boolean isTrivial() {
        return decisionsAndWhy.length() + triedAndRejected.length() < MIN_USEFUL_CHARS;
    }

    /**
     * The record as the agent reads it, on the wake itself rather than fetched.
     *
     * <p>Handed over rather than made available on request for the reason the introspection
     * measurement implies: a record it has to ask for costs a turn, and a turn is the unit this
     * whole design is trying to remove.
     */
    public String forAgent() {
        StringBuilder sb = new StringBuilder();
        sb.append("── what you already know about '").append(artifactId).append("' ──\n");
        if (!whatItIs.isEmpty()) {
            sb.append("  it is: ").append(whatItIs).append('\n');
        }
        if (!aliases.isEmpty()) {
            sb.append("  he calls it: ").append(String.join(", ", aliases)).append('\n');
        }
        if (!where.isEmpty()) {
            sb.append("  where: ").append(where).append('\n');
        }
        sb.append("  this is edit #").append(revision).append('\n');
        if (!decisionsAndWhy.isEmpty()) {
            sb.append("  decided, and why: ").append(decisionsAndWhy).append('\n');
        }
        if (!triedAndRejected.isEmpty()) {
            // The reason the record exists at all: without it the next turn re-walks the dead end.
            sb.append("  ALREADY TRIED AND REJECTED (do not repeat): ")
              .append(triedAndRejected).append('\n');
        }
        if (!beCarefulOf.isEmpty()) {
            sb.append("  be careful of: ").append(beCarefulOf).append('\n');
        }
        return sb.toString();
    }

    /**
     * The next edit of the same thing — <b>accumulating, never overwriting</b>.
     *
     * <p>A revision chain that overwrites is an earlier issue's rework-undercount in a new place: four
     * "make it bigger"s would record as four one-shot successes and the history would read as four
     * creations rather than four edits. So what was tried is appended, and the revision counts up.
     */
    public HandoverRecord thenAlso(HandoverRecord newer) {
        if (newer == null) {
            return this;
        }
        List<String> mergedAliases = new ArrayList<>(aliases);
        for (String a : newer.aliases()) {
            if (!mergedAliases.contains(a)) {
                mergedAliases.add(a);
            }
        }
        return new HandoverRecord(
                artifactId,
                newer.kind().isEmpty() ? kind : newer.kind(),
                newer.whatItIs().isEmpty() ? whatItIs : newer.whatItIs(),
                mergedAliases,
                join(decisionsAndWhy, newer.decisionsAndWhy()),
                join(triedAndRejected, newer.triedAndRejected()),
                newer.where().isEmpty() ? where : newer.where(),
                revision + 1,
                newer.beCarefulOf().isEmpty() ? beCarefulOf : newer.beCarefulOf(),
                newer.writtenMs());
    }

    private static String join(String older, String newer) {
        if (newer.isEmpty()) {
            return older;
        }
        if (older.isEmpty()) {
            return newer;
        }
        // Newest first: a worker skimming a capped field should see the most recent decision, not
        // the oldest one that happened to be written before the cap was reached.
        return trim(newer + " · " + older, MAX_FIELD_CHARS);
    }
}
