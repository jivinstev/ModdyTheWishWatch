package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>What the wish actually BUILT, so the child can be told how to set it off.</b>
 *
 * <p>Moddy's closing line used to be composed from what it <i>meant</i> to build. Measured over 21
 * real trigger-bearing episodes, 19 stated a trigger — so the habit is there — but at least one
 * stated the <i>wrong</i> one: the wish {@code "the spider bite should make you slow"} was built
 * correctly as {@code on_hit}, and the child was told <i>"Get close and she'll gum up your legs"</i>.
 *
 * <p><b>That is worse than building the wrong thing.</b> A wrong build described accurately is
 * recoverable — the child reads it, sees it is not what they meant, and wishes again. A RIGHT build
 * described wrongly is not: the child stands next to the spider as instructed, nothing happens, and
 * nothing in their experience points at the sentence as the culprit rather than the toy. The natural
 * response is to re-wish something that was already correct, which burns a wish and can replace a
 * right build with a wrong one.
 *
 * <p>So the sentence is derived from the ARTIFACT. {@code apply_effect} knows which catalogue entry
 * it instantiated, and each entry carries a {@code child_when} sentence; this class carries that
 * fact the short distance from the tool that built it to the tool that announces it.
 *
 * <p><b>Why a static holder rather than per-player state.</b> The tools the agent calls have no
 * player argument — {@code apply_effect} is invoked over MCP with nothing but its own JSON. One
 * agent grants one wish at a time, so a wish-scoped static is the accurate model, and it matches
 * {@link com.moddingfromamod.provenance.ProvenanceContext}, which carries the design note the same
 * way. Cleared at both ends of a wish so a sentence can never outlive the thing it describes and be
 * spoken over the next wish.
 *
 * <p>Only the {@code apply_effect} path records anything today. Authored datapacks report nothing
 * about the scaffold they emit, so their trigger cannot be derived without reading the compiled
 * output — deliberately deferred, and until then {@link #describe()} returns empty for
 * them rather than guessing. <b>Empty, not a fallback sentence:</b> a confident guess is exactly the
 * failure this class exists to remove.
 */
public final class BuiltAbility {

    /** One ability the current wish put into the world. */
    public record Built(String triggerId, String childWhen, String tag) {
    }

    /** One list per wish ({@link WishScope#key()}), so one wish's start never wipes another's. */
    private static final java.util.Map<String, List<Built>> BY_WISH = new java.util.HashMap<>();

    private static List<Built> mine() {
        return BY_WISH.computeIfAbsent(WishScope.key(), k -> new ArrayList<>());
    }

    private BuiltAbility() {
    }

    /**
     * Record an ability that was just built.
     *
     * @param triggerId the catalogue entry whose trigger this actually uses — for impulse that is
     *                  the trigger it RESOLVED to ({@code on_hit}/{@code proximity}), not
     *                  {@code impulse}, because the child needs the real answer
     * @param childWhen that entry's child-facing sentence, {@code ${TAG}} already substituted
     */
    public static synchronized void record(String triggerId, String childWhen, String tag) {
        if (childWhen == null || childWhen.isBlank()) {
            return;
        }
        mine().add(new Built(triggerId, childWhen.strip(), tag == null ? "" : tag));
    }

    /**
     * The sentence to tell the child, or empty if nothing was recorded.
     *
     * <p>Several abilities in one wish (slowness AND nausea, say) usually share one trigger, so
     * identical sentences collapse to one — being told the same thing twice reads as a stutter. Two
     * genuinely different triggers are both stated, because both are true and the child needs both.
     */
    public static synchronized String describe() {
        List<Built> current = mine();
        if (current.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Built b : current) {
            if (!lines.contains(b.childWhen())) {
                lines.add(b.childWhen());
            }
        }
        return String.join(" ", lines);
    }

    /** Everything recorded for the current wish (test/diagnostic view). */
    public static synchronized List<Built> current() {
        return List.copyOf(mine());
    }

    /** Forget this wish's abilities — its start and its end; never another wish's. */
    public static synchronized void clear() {
        BY_WISH.remove(WishScope.key());
    }

    /** Forget one wish's abilities (it settled). */
    public static synchronized void forget(String wishKey) {
        BY_WISH.remove(wishKey == null ? "" : wishKey);
    }

    /**
     * <b>Run a test's build against an EMPTY holder, then put back whatever was there.</b>
     *
     * <p>The seam a test needs, and the reason it is not simply {@link #clear}. This is a
     * wish-scoped singleton, which is the accurate model in production (one agent grants one wish
     * at a time) and a shared global under Gate B, where every test in a batch runs interleaved on
     * the one server thread. A test that clears it to get a clean read wipes it for a neighbour
     * mid-wish — and the neighbour does not fail, it passes having asserted nothing, which is the
     * worse of the two outcomes. See docs/GATE_PERFORMANCE.md.
     *
     * <p>Safe because a GameTest method body runs to completion inside a single tick, so nothing
     * can interleave between the save and the restore. Anything that needs to span ticks must not
     * use this — it needs its own id, not a scoped global.
     */
    public static void inIsolationForTest(Runnable body) {
        List<Built> saved;
        synchronized (BuiltAbility.class) {
            saved = List.copyOf(mine());
            mine().clear();
        }
        try {
            body.run();
        } finally {
            synchronized (BuiltAbility.class) {
                mine().clear();
                mine().addAll(saved);
            }
        }
    }
}
