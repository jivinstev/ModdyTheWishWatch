package com.moddingfromamod.provenance;

/**
 * The <b>intent</b> currently being granted — set when the wish agent (or the offline engine) starts
 * handling a wish, read by whatever persists an Artifact during that grant so the Artifact gets
 * stamped with the wish that produced it.
 *
 * <h2>Why this stopped being a single field</h2>
 * It used to be two {@code volatile} strings, and the reasoning written here was sound at the time:
 * <i>the agent processes wishes one at a time, so a single current value is correct.</i> The moment
 * two wishes can be granted at once that is false, and it fails in the worst available way — not a
 * crash, not something a child sees, but a datapack quietly stamped with the other wish's words.
 * The corpus mined from those manifests is what drives the roadmap, so the damage is to what we
 * believe he asked for.
 *
 * <p>So the value now lives on the {@link com.moddingfromamod.marshal.WishRun}, and this class is a
 * lookup keyed on <b>who is asking</b> — the MCP session bound for the length of the tool call.
 * Every existing call site is unchanged.
 *
 * <p><b>The fallback is deliberately narrow.</b> With nothing bound and exactly one wish in flight,
 * the answer is unambiguous and is used. With two in flight and nothing bound, the honest answer is
 * "I do not know", and a process-wide field is returned instead of a guess — because guessing is
 * precisely the silent mis-attribution this exists to stop. The offline template engine, which has
 * no agent and no run, keeps using that field exactly as before.
 *
 * <p>Pure — no game types — so the publisher and its tests still share one seam. Never throws.
 */
public final class ProvenanceContext {

    /**
     * The canonical schema description for the {@code note} arg every authoring tool should expose,
     * kept here so all tools (and future ones) share one wording. Tells the agent to record its
     * design reasoning — the context a future upgrade-to-Java needs and the wish text lacks.
     */
    /**
     * <b>REQUIRED on every tool that offers it, and it was not.</b>
     *
     * <p>The text has always said <i>ALWAYS include</i> and the field sat in the OPTIONAL list on
     * all seven artifact tools. The agent obeyed the schema: measured over the action log,
     * {@code build_structure.note} was passed <b>0 times in 227 calls</b> — never once, while
     * costing permanent context on every one of them.
     *
     * <p><b>Why that is not merely wasteful.</b> This field is the only writer of
     * {@link ProvenanceContext#currentNote()}, and three things read it:
     *
     * <ul>
     *   <li>the {@code HandoverRecord} — what the next agent to touch this artifact is given on its
     *       {@code await_wish} wake;</li>
     *   <li>the {@code ArtifactMemory.Turn} journal — <i>"the thing that actually accrues"</i>;</li>
     *   <li>the published Manifest in the Mod Repo, per the provenance contract.</li>
     * </ul>
     *
     * All three have been receiving an empty string for every building wish. And
     * {@code FinishWishTool} justifies not asking for a design note at finish time on the grounds
     * that <i>"the design note is already on the run (ProvenanceContext.note)"</i> — reasoning from
     * a premise that has never once been true in a real session.
     *
     * <p>Required in the schema rather than refused in the tool, deliberately. A required parameter
     * is what the playbook's §4f rule 1 measured moving a wish set 4/8 → 6/8, and it costs no
     * turns; a refusal would cost the child a turn for a record he never sees, on a field that is
     * provenance rather than correctness.
     */
    public static final String NOTE_HINT =
            "REQUIRED. A short (one or two sentence) DESIGN NOTE: WHY you built it this way — "
            + "the key choices (colours, shapes, sizes, stats, blocks) and the reason behind them, "
            + "so a future you can re-edit or upgrade this without guessing. This is your reasoning, "
            + "not a description the child sees. It is the ONLY thing carried to whoever edits this "
            + "next — without it they re-derive every decision you just made.";

    /** Used only when no run can be identified — the offline path, and tests. */
    private static volatile String intent = "";
    private static volatile String note = "";

    private ProvenanceContext() {
    }

    private static com.moddingfromamod.marshal.WishRun run() {
        return com.moddingfromamod.agent.AgentContext.currentRun();
    }

    /**
     * Mark the wish now being granted. Passing null/blank clears it (and any prior design note).
     *
     * <p>When a run is identifiable this is a no-op for the intent — the run already knows its own
     * text, and it cannot be overwritten by another wish arriving. That is the whole fix.
     */
    public static void begin(String wishText) {
        var current = run();
        if (current != null) {
            current.note("");
            return;
        }
        intent = (wishText == null) ? "" : wishText.strip();
        note = "";
    }

    /** The wish text currently being granted, or "" if none. */
    public static String currentIntent() {
        var current = run();
        return current != null ? current.text() : intent;
    }

    /**
     * The agent's <b>design note</b> for the artifact it is building right now — a short rationale
     * ("green shell + spikes because he asked for Spikor; boss stats for a climactic fight"). Set by
     * an authoring tool from its {@code note} arg, read by the publisher so the note is stamped into
     * the manifest beside the wish text. This captures the agent's REASONING, which the raw wish text
     * doesn't — the context a future "upgrade this wish to a real Java mod" most wants. Passing
     * null/blank clears it.
     */
    public static void note(String designNote) {
        var current = run();
        if (current != null) {
            current.note(designNote);
            return;
        }
        note = (designNote == null) ? "" : designNote.strip();
    }

    /** The design note for the artifact currently being built, or "" if none. */
    public static String currentNote() {
        var current = run();
        return current != null ? current.note() : note;
    }

    /**
     * <b>Run {@code body} with a wish text of your own, and give the process back exactly as you
     * found it.</b> The test seam, and the only correct way for a GameTest to set an intent.
     *
     * <p>{@code begin}/{@code clear} around a test body is what a GameTest reaches for first, and
     * it is wrong here for the reason {@code SourceRuleSweepTest} names: tests in a batch run
     * interleaved on one server thread, so a {@code clear()} deletes what a concurrently-running
     * neighbour set — and the neighbour does not go red, it goes green having asserted nothing.
     * Measured elsewhere in this repo: a sharding experiment that merely regrouped batches
     * turned that hazard from invisible into deterministic.
     *
     * <p>Same shape as {@link com.moddingfromamod.wish.BuiltAbility#inIsolationForTest}. It does
     * not make the window atomic — a neighbour reading the intent DURING {@code body} still sees
     * this test's — so the honest claim is that it leaves no residue, not that it is invisible.
     * That is enough for the case it exists for: a test that needs an intent for the length of one
     * tool call.
     */
    /**
     * Put back an intent and note captured earlier — the other half of the test seam.
     *
     * <p>For a body that calls {@link #begin} several times and so cannot be wrapped in a lambda,
     * which {@code ProvenanceWiringGameTest} does deliberately to simulate the race.
     */
    public static void restoreForTest(String intent, String designNote) {
        begin(intent);
        note(designNote);
    }

    public static void withIntentForTest(String wishText, Runnable body) {
        String savedIntent = currentIntent();
        String savedNote = currentNote();
        begin(wishText);
        try {
            body.run();
        } finally {
            begin(savedIntent);
            note(savedNote);
        }
    }

    /** Done granting — forget the current intent + note so nothing is mis-attributed later. */
    public static void clear() {
        var current = run();
        if (current != null) {
            current.note("");
        }
        intent = "";
        note = "";
    }
}
