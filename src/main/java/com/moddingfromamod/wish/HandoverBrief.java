package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.marshal.AliasIndex;
import com.moddingfromamod.marshal.ArtifactMemory;
import com.moddingfromamod.marshal.MemoryBrief;
import com.moddingfromamod.marshal.WishContextMode;
import com.moddingfromamod.marshal.HandoverRecord;

/**
 * Choosing which handover records to hand a waking agent — and stopping there.
 *
 * <p>The temptation is to send everything the world knows. That is wrong twice over: it is a wall of
 * text on every wake, and it teaches the agent that the brief is noise to skim. So it sends the
 * records for the things <b>this wish actually names</b>, using rung 0 — the same free lookup that
 * resolves "give King Kroak fire breath" — and nothing when the wish names nothing.
 *
 * <p>That the two features share a lookup is not a coincidence worth being pleased about; it is the
 * reason rung 0 earns its place even after the replay showed it resolves only 21% of referring
 * wishes. Naming a thing is cheap and is useful for more than one purpose.
 */
public final class HandoverBrief {

    /** More than this on one wake is a wall of text nobody reads. */
    public static final int MAX_RECORDS = 2;

    private HandoverBrief() {
    }

    /** The brief for a wish, or "" when it names nothing we have notes on. */
    public static String forWish(String wishText) {
        return forWish(wishText, indexOfKnownThings());
    }

    /**
     * <b>The brief the treatment hands over</b> — the turn journal, rendered inside a budget.
     *
     * <p>Which of the three this is comes from {@link com.moddingfromamod.marshal.WishContextMode}:
     *
     * <ul>
     *   <li>{@code OFF} — the old five-field summary. Today's behaviour, and arm A/B's floor.</li>
     *   <li>{@code ON} — {@link MemoryBrief} over the whole journal.</li>
     *   <li>{@code SHUFFLED} — the same renderer over a <b>different artifact's</b> journal.
     *       Threat 3's control, and it is not optional: without an arm that pays the same token
     *       cost carrying irrelevant history, a positive result cannot be told apart from "more
     *       tokens made it try harder". We would have measured attention and reported memory.</li>
     * </ul>
     *
     * @return the block, and what it cost — so criterion 6 is measured rather than estimated
     */
    public static MemoryBrief.Rendered contextFor(String wishText, String artifactId) {
        WishContextMode mode = WishContextMode.current();
        if (!mode.injects()) {
            return new MemoryBrief.Rendered("", 0, 0, 0, 0, 0);
        }
        String id = artifactId == null || artifactId.isBlank()
                ? artifactFor(wishText) : artifactId.trim();
        if (mode == WishContextMode.SHUFFLED) {
            id = decoyFor(id);
        }
        if (id.isEmpty()) {
            return new MemoryBrief.Rendered("", 0, 0, 0, 0, 0);
        }
        ArtifactMemory memory = ArtifactMemoryStore.read(id);
        if (memory == null) {
            return new MemoryBrief.Rendered("", 0, 0, 0, 0, 0);
        }
        return MemoryBrief.render(memory);
    }

    /**
     * Some OTHER artifact's history, for the shuffled control.
     *
     * <p>Picked as the one with the most turns rather than at random, on purpose: the control has to
     * cost <b>at least</b> as much as the treatment or it controls for nothing, and a random pick
     * would sometimes hand over a one-turn stub and quietly make the control cheaper than the thing
     * it is controlling for.
     */
    static String decoyFor(String realId) {
        ArtifactMemory best = null;
        for (ArtifactMemory m : ArtifactMemoryStore.all()) {
            if (m.artifactId().equalsIgnoreCase(realId)) {
                continue;
            }
            if (best == null || m.turns().size() > best.turns().size()) {
                best = m;
            }
        }
        return best == null ? "" : best.artifactId();
    }

    /** Seam: the same thing against an index handed in, so it is testable without a world. */
    public static String forWish(String wishText, AliasIndex index) {
        if (wishText == null || wishText.isBlank() || index == null) {
            return "";
        }
        AliasIndex.Result result = index.resolve(wishText);
        List<AliasIndex.Artifact> named = new ArrayList<>();
        if (result.resolved()) {
            named.add(result.artifact());
        } else if (result.verdict() == AliasIndex.Verdict.AMBIGUOUS) {
            // Two things named is a two-artifact wish, not a failure — and it is exactly the case
            // where knowing about BOTH matters most.
            named.addAll(result.candidates());
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (AliasIndex.Artifact a : named) {
            if (shown >= MAX_RECORDS) {
                break;
            }
            HandoverRecord record = HandoverStore.read(a.id());
            if (record != null) {
                sb.append(record.forAgent());
                shown++;
            }
        }
        return sb.toString();
    }

    /**
     * <b>Which thing this wish is about</b>, or "" when it names nothing we hold notes on.
     *
     * <p>The same rung-0 lookup as {@link #forWish(String)}, answering the id rather than the prose,
     * because {@link com.moddingfromamod.marshal.WishAffinity} routing needs the identity <i>before</i>
     * a worker starts — and the brief needs it after. One lookup, two callers, no second definition
     * of "what is this wish about" to disagree with the first.
     *
     * <p>Ambiguity resolves to "": two things named is genuinely a two-artifact wish, and picking one
     * of them to route by would send it to a worker chosen by coin toss while labelling the result as
     * an arm.
     */
    public static String artifactFor(String wishText) {
        return artifactFor(wishText, indexOfKnownThings());
    }

    /** Seam: the same lookup against an index handed in, so it is testable without a world. */
    public static String artifactFor(String wishText, AliasIndex index) {
        if (wishText == null || wishText.isBlank() || index == null) {
            return "";
        }
        AliasIndex.Result result = index.resolve(wishText);
        return result.resolved() ? result.artifact().id() : "";
    }

    /**
     * Everything we hold notes on, as a rung-0 index.
     *
     * <p>Deliberately built from the handover store rather than from the artifact registries: a
     * thing with no notes cannot contribute a brief, so indexing it here would only add false
     * matches.
     */
    static AliasIndex indexOfKnownThings() {
        AliasIndex index = new AliasIndex();
        java.util.Set<String> fromJournal = new java.util.HashSet<>();
        // THE JOURNAL FIRST, because it is the only source that can tell a NAME from a shouted
        // emphasis word — it knows how often he actually said each one. Rung 0 is now what
        // WishAffinity routes on as well as what the brief is chosen by, so a false alias here
        // sends the wish to the wrong worker AND hands it the wrong history.
        for (ArtifactMemory m : ArtifactMemoryStore.all()) {
            fromJournal.add(m.artifactId());
            index.add(new AliasIndex.Artifact(m.artifactId(), m.kind(),
                    m.artifactId().replace('_', ' '), m.confirmedAliases()));
        }
        for (HandoverRecord r : HandoverStore.all()) {
            if (!fromJournal.contains(r.artifactId().toLowerCase(java.util.Locale.ROOT))) {
                index.add(new AliasIndex.Artifact(r.artifactId(), r.kind(),
                        r.artifactId().replace('_', ' '), r.aliases()));
            }
        }
        return index;
    }
}
