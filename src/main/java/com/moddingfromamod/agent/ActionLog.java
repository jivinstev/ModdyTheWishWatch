package com.moddingfromamod.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A record of <b>everything the agent does</b>, for improving the mod over time.
 *
 * <p>The wish journal already captures what the child asked for. This captures the other half: which
 * tools the agent reached for, what it passed, and whether it worked. Together they answer the
 * question that actually drives development — <i>"what does he keep asking for that we can't do
 * well yet?"</i>
 *
 * <p>Concretely, the signals worth mining:
 * <ul>
 *   <li>wishes that reached the agent at all (the offline templates didn't know them);</li>
 *   <li>agent tool calls that came back {@code isError} — where the game fought the agent;</li>
 *   <li>lots of {@code run_command} calls with no {@code add_template} — a wish that got granted
 *       once but never learned, so it'll cost an agent round-trip again next time;</li>
 *   <li>repeated {@code define_creature} edits of the same id — the child iterating, which hints at
 *       a knob the Creature Maker should expose directly.</li>
 * </ul>
 *
 * <p>Written as JSONL (one object per line) so it's trivially greppable and append-only: a crash can
 * only ever damage the final line. Lives outside any world, because the agent also acts while no
 * world is open.
 */
public final class ActionLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Truncate long results and free text: this is an analysis trail, not a transcript. */
    private static final int MAX_FIELD = 600;

    /**
     * Arguments get a far bigger budget, because <b>the arguments are the artifact</b>.
     *
     * <p>They used to share {@link #MAX_FIELD}, and that quietly destroyed the only surviving copy
     * of what the agent designed. All 37 {@code build_structure} calls ever recorded came out at
     * exactly 601 characters — every one cut mid-way through its {@code shapes} array, so not one
     * of them can be replayed or compared with what got built. The same clip already produced one
     * confident falsehood ("the agent never declares a palette" — it did, in every call, past
     * character 600) and the {@code argKeys} field was added to work around it. Keys were the right
     * fix for "which fields were used" and no fix at all for "what was in them".
     *
     * <p>A real castle is ~6 KB of JSON, so this holds them whole while still bounding a runaway
     * caller. When it does bite, it says so in {@code argsClipped} rather than leaving a consumer
     * to read a truncated blob as if it were complete — which is the actual defect here. The clip
     * was never the bug; the SILENT clip was.
     */
    private static final int MAX_ARGS = 20_000;

    /**
     * Results get their own budget too, for the same reason and after the same mistake.
     *
     * <p>A tool RESULT is a verdict, and a verdict is written last. {@code verify_contraption} lists
     * every kinetic component and its rpm and then concludes — so on any machine worth verifying the
     * component listing consumed the whole {@link #MAX_FIELD} budget and the conclusion was the part
     * that got cut. 85 of 104 recorded verifications lost their verdict, including every complex
     * machine; scoring an arm on what survived reported 2 of 9 machines "verified WORKING" when the
     * other 7 were simply illegible. That is the args bug above, repeated on the other field: a
     * number chosen for "an analysis trail, not a transcript" quietly deleted the analysis.
     *
     * <p>As with args, the clip still bites eventually — and when it does it says so in
     * {@code resultClipped}, so no consumer can read a truncated verdict as an absent one.
     */
    private static final int MAX_RESULT = 20_000;

    private static Path file;

    private ActionLog() {
    }

    private static synchronized Path file() {
        if (file == null) {
            // Under the CONFIGURED home, not a hardcoded one. The append is `synchronized`, which
            // only excludes threads inside one JVM, so two clients running side by side (different
            // branches, different worktrees) append to this file with no mutual exclusion between
            // them. Honouring the home lets a parallel run be given its own.
            //
            // MEASURED 13 Aug, because a spliced record was blamed on this and it was not the cause:
            // two real JVMs appending 60 lines each, at 8KB, 65KB and 250KB per line, produced ZERO
            // torn or interleaved lines on APFS. O_APPEND held. The splice that prompted the search
            // came from the wish BOX joining Moddy's seeded suggestion to the child's own sentence
            // (see WishBoxState#beforeTyping) — one process, before serialisation, which is why the
            // damaged record was still valid JSON. If tearing is ever suspected here again, look for
            // BROKEN JSON; a clean record with two sentences in one field is not this.
            file = com.moddingfromamod.runtime.RuntimeConfig.home()
                    .resolve("agent-actions.jsonl");
        }
        return file;
    }


    /**
     * Which world and which sitting — the two fields that make lineage a fact.
     *
     * <p>Added because their absence produced a wrong conclusion nobody could have checked. Seven
     * sculpts of Spikor were read as seven successive revisions that got steadily worse, and filed
     * as a guardrail failure. They were four independent creations in four different worlds:
     * quality was constant within every world and changed only at the boundaries, and since species
     * are per-world the agent could not have seen the earlier one to iterate on it. The reading was
     * not careless — it was the only reading available, because the records could not distinguish
     * "revised it" from "started over".
     *
     * <p>{@code session} is regenerated on every world-open rather than per world, because the
     * question is "did these happen in one go?". Two sculpts hours apart in one world are not a
     * revision series either.
     *
     * <p>Null at the title screen, and omitted rather than written as {@code "null"} so a consumer
     * can tell "no world open" from a world genuinely called that. Never throws: this is logging,
     * and logging must not be able to break a wish.
     */
    private static void stampLineage(JsonObject o) {
        try {
            String world = com.moddingfromamod.runtime.WorldPaths.worldName();
            if (world != null) {
                o.addProperty("world", world);
            }
            String session = com.moddingfromamod.runtime.WorldPaths.sessionId();
            if (session != null) {
                o.addProperty("session", session);
            }
            // WHICH EXPERIMENT ARM THIS RECORD BELONGS TO.
            //
            // Recorded at the source rather than inferred later from which file the results were
            // saved into. That inference is precisely how came to be misfiled: a conclusion
            // that could not be checked against the data, because the fact that settled it was
            // never written down. An A/B whose arms are distinguishable only by the operator's
            // memory has the same defect.
            o.addProperty("catalogue", !"off".equals(System.getProperty(
                    com.moddingfromamod.ModdingFromAModMod.MODID + ".effectCatalogue")));
            // AND WHICH SAFETY ARM. Same argument one experiment along: child safety ships with
            // three modes (on / shadow / off) and the shadow one exists precisely so a base rate
            // can be measured before anything is blocked. A record that does not say which mode
            // produced it cannot be pooled with any other, and the operator's memory is not a
            // field. See docs/CHILD_SAFETY_PLAN.md §6.
            o.addProperty("childsafety", com.moddingfromamod.safety.ChildSafetyConfig.modeLabel());
            // WHICH SOURCE BUILT THIS. Every client run compiles current source, so an edit made
            // mid-experiment silently splits it into two treatments -- which happened, and cost a
            // whole run. Worse, the A/B's resume logic then treats those old-source episodes as
            // "already done" and inherits them into the clean re-run. Stamping the commit is what
            // makes both detectable from the data instead of from memory.
            String sha = System.getProperty(
                    com.moddingfromamod.ModdingFromAModMod.MODID + ".sourceSha");
            if (sha != null && !sha.isBlank()) {
                o.addProperty("sourceSha", sha);
            }
            stampArm(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not stamp lineage: {}", e.toString());
        }
    }

    /**
     * When this JVM started, so a <b>new game instance</b> is a fact in the data.
     *
     * <p>{@code session} already changes on every world-open, which is not the same question: the
     * wish-context plan's arms D and E turn on "nothing is in any context any more", and re-opening
     * a world inside one running process does not clear a parked agent's memory. Only a new process
     * does. Read once, because a process has exactly one start.
     */
    private static final long SESSION_EPOCH = System.currentTimeMillis();

    /**
     * <b>Which experiment arm this record belongs to.</b>
     *
     * <p>Stamped on every record rather than reconstructed later. The two controls are read per call
     * (see {@link com.moddingfromamod.marshal.WishAffinity}) precisely so one client boot can measure
     * more than one arm — which means a whole run has no single arm, and a per-run label would be a
     * lie the moment the experiment did the thing it was built to do.
     *
     * <p>{@code workerId} is the field the whole baseline turns on: without it "same worker" and
     * "different worker" are indistinguishable in the log, so arms A and B could be run but not
     * scored. Empty at the title screen and in unit tests, where there is no worker.
     */
    private static void stampArm(JsonObject o) {
        o.addProperty("affinity", com.moddingfromamod.marshal.WishAffinity.current().label());
        o.addProperty("contextMode", com.moddingfromamod.marshal.WishContextMode.current().label());
        o.addProperty("sessionEpoch", SESSION_EPOCH);
        String worker = com.moddingfromamod.agent.AgentContext.workerId();
        if (!worker.isEmpty()) {
            o.addProperty("workerId", worker);
        }
        // The pool SLOT, which is what the CLI's transcript names. workerId above is the
        // MCP session and can change under a worker; this cannot. Omitted when no slot called.
        String slot = com.moddingfromamod.agent.AgentContext.workerName();
        if (!slot.isEmpty()) {
            o.addProperty("workerName", slot);
        }
        // A free-text label so an operator can name a run ("A1", "B3") without the scorer having to
        // guess. Optional: an unlabelled run is still fully identified by the fields above.
        String arm = System.getProperty(
                com.moddingfromamod.ModdingFromAModMod.MODID + ".arm");
        if (arm != null && !arm.isBlank()) {
            o.addProperty("arm", arm.trim());
        }
    }

    /**
     * <b>A refinement that made something ELSE</b>.
     *
     * <p>The wish leased an artifact — it is about that thing — and the tool just created a
     * different one. Sometimes that is right ("and give him a friend"), and sometimes it is the
     * defect: the child's "it" silently rebinds to the new thing and never comes back.
     *
     * <p>RECORDED, NOT REFUSED. Both readings are legitimate and only the transcript can tell them
     * apart, so a gate here would block real wishes to catch a failure we cannot yet name
     * precisely. What was missing was not enforcement, it was the ability to COUNT it: measured on
     * a creature ladder, one rung routed {@code anyone} because the agent had just made
     * {@code zibblewisp_turbo} beside the {@code zibblewisp} it was asked to change, and that read
     * in the summary as an arm-binding bug rather than as this.
     *
     * <p>Same argument as {@code ownerSeen}/{@code poolSeen}: a field turned a day of hand-analysis
     * into one glance, and the fix belongs wherever the transcript says it does — not here.
     */
    public static void drifted(String wishId, String about, String made, String tool) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "artifact_drift");
            if (wishId != null && !wishId.isBlank()) {
                o.addProperty("wishId", wishId);
            }
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("about", about == null ? "" : about);
            o.addProperty("made", made == null ? "" : made);
            o.addProperty("tool", tool == null ? "" : tool);
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log an artifact drift: {}", e.toString());
        }
    }

    /**
     * <b>One claim's verdict, for a grown-up</b>. The child no longer sees a checklist, so
     * this row is where "what did Moddy check, and what did the server see" is written down;
     * a wish trace prints it as a {@code check} line.
     *
     * @param verdict WORKS / TRIED / BROKEN — what the wish was recorded as
     * @param outcome the judge's reading (WORKS, TRIED, BROKEN, CONTRADICTED)
     */
    public static void claimVerdict(String wishId, String claim, String verdict, String outcome,
                                    String check, String evidence, String saw) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "claim_verdict");
            if (wishId != null && !wishId.isBlank()) {
                o.addProperty("wishId", wishId);
            }
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("claim", claim == null ? "" : claim);
            o.addProperty("verdict", verdict == null ? "" : verdict);
            o.addProperty("outcome", outcome == null ? "" : outcome);
            o.addProperty("check", check == null ? "" : check);
            o.addProperty("evidence", evidence == null ? "" : evidence);
            o.addProperty("saw", saw == null ? "" : saw);
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a claim verdict: {}", e.toString());
        }
    }

    /**
     * <b>An icon the agent wanted and the library does not have</b>. One row per miss, so
     * the next wave of drawing is a count over this file ({@code tools/icon-demand.py}) rather than
     * a guess — which is how Wave 1 was chosen, and the reason to stop.
     *
     * @param wanted what it asked for, normalised ({@code laser_blaster})
     * @param chosen the icon the item was made with in the end, or empty when the call was refused
     * @param how    {@code refused} (it named an icon that does not exist) or {@code wanted} (it
     *               chose a real one and said, in {@code icon_wanted}, what it would rather have)
     */
    public static void iconMiss(String wanted, String chosen, String how, String itemName) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "icon_miss");
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("wanted", wanted == null ? "" : wanted);
            o.addProperty("chosen", chosen == null ? "" : chosen);
            o.addProperty("how", how == null ? "" : how);
            o.addProperty("item", itemName == null ? "" : itemName);
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log an icon miss: {}", e.toString());
        }
    }

    /**
     * <b>A wish was stopped from reaching a child who did not make it</b> (option 2). One
     * record per time it happens, so "how often, when, and how" is a count over this file rather
     * than a guess — the Wish stats panel reads it through {@code digest.py}.
     *
     * <ul>
     *   <li>{@code action}: {@code narrowed} (it still ran, for the wisher only) or {@code refused}.
     *   <li>{@code why}: {@code owner_left} (the wisher has gone, decisions 1 and 3), or one
     *       of {@link PersonSelectors}' {@code reaches_everyone} / {@code names_another_child} /
     *       {@code reaches_any_entity}.
     *   <li>{@code others}: how many OTHER children were online — the ones it would have reached.
     * </ul>
     *
     * <p>No other child is named in the record: who was protected is a count, and the wisher is
     * the {@code playerId} every wish record already carries.
     */
    /**
     * <b>How far ahead of a wish the pool was asked to start</b> (2026-10-10). One row per
     * wish that reached the agent: the FIRST intent signal of its run-up ({@code watch} — the panel
     * opened, {@code anvil} — Wish-now opened, {@code key} — he typed, or {@code none} for a
     * {@code /wish} typed in chat), how many ms before the wish it came, every kind seen, and
     * whether the wish will wait for a boot ({@code IdlePolicy.wakeSaysSo}). The pool is reaped
     * after 5 quiet minutes on the strength of this lead covering the ~9 s boot; this row is how
     * that is checked. {@code tools/pool-economics.py} reads it.
     */
    public static void intent(String wishId, String playerId,
                              com.moddingfromamod.marshal.WishIntent.Taken taken, boolean bootWait) {
        try {
            append(intentRecord(wishId, playerId, taken, bootWait));
        } catch (RuntimeException e) {
            LOGGER.debug("could not log an intent: {}", e.toString());
        }
    }

    /**
     * <b>A Moddy menu was opened</b> (step 1): which menu, for whom. The watch logs one per open, so
     * "how often is the watch opened, and how often does an open end in a wish" is a count, not a guess.
     */
    public static void menuOpen(String playerId, String menu) {
        try {
            append(menuRecord("menu_open", playerId, menu, null, null, -1, -1, null, false));
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a menu open: {}", e.toString());
        }
    }

    /**
     * <b>A press on a Moddy menu</b> (step 1). Before this, only a press that became a wish or an undo
     * left a trace, so the menu's order could not be decided from use (2026-11-15: the guide, the cards, stop,
     * the toggle and the page turns were all invisible).
     *
     * @param menu   {@code watch}, or the {@code ui/MenuKit} page's menu name ({@code builder}, {@code thing}, …)
     * @param action the watch cell's action ({@code WISH}, {@code GUIDE}, …); null on a MenuKit page
     * @param family the watch cell's family; null on a MenuKit page
     * @param page   the watch page; -1 when the menu has none
     * @param slot   the slot pressed
     * @param tile   what the press names: a recipe id, an artifact id, a catalogue id or a tile id — never words a
     *               child typed (a preset's wish text is NOT logged here: its recipe id is)
     * @param secondary a Java right-click
     */
    public static void menuPress(String playerId, String menu, String action, String family, int page, int slot,
                                 String tile, boolean secondary) {
        try {
            append(menuRecord("menu_press", playerId, menu, action, family, page, slot, tile, secondary));
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a menu press: {}", e.toString());
        }
    }

    /**
     * <b>A next-wish idea in the wish box</b> (step 5): which rung offered it (AGENT, GUIDE, STARTER, CHANGE)
     * and what he did with it ({@code offered}, then {@code sent} as it was, {@code edited}, or {@code cleared}). No words:
     * the rung and the outcome are what the acceptance is measured from.
     */
    /**
     * a model or web call REFUSED because it was asked during a Wish Builder make (a Builder, Apprentice,
     * re-wish or free-change make), which is local only ({@code marshal/LocalOnly}). One record per refused call; any at
     * all is a regression. {@code what} names the call, never a word of the wish.
     */
    public static void localOnly(String what) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "local_only");
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            var run = AgentContext.currentRun();
            if (run != null) {
                o.addProperty("wishId", run.shortId());
            }
            o.addProperty("refused", what == null ? "" : what);
            append(o);
        } catch (RuntimeException e) {
            // the log never stands between a child and his wish
        }
    }

    /** a typed re-wish read by the local reader: what it read and what it did, never the words. */
    public static void rewish(String playerId, String intent, String band, String verb, java.util.List<String> slots,
                              String outcome) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "rewish");
            if (playerId != null && !playerId.isBlank()) {
                o.addProperty("playerId", playerId);
            }
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("intent", intent);
            o.addProperty("band", band);
            o.addProperty("verb", verb == null ? "" : verb);
            com.google.gson.JsonArray s = new com.google.gson.JsonArray();
            (slots == null ? java.util.List.<String>of() : slots).forEach(s::add);
            o.add("slots", s);
            o.addProperty("outcome", outcome == null ? "" : outcome);
            append(o);
        } catch (RuntimeException e) {
            // the log never stands between a child and his wish
        }
    }

    /**
     * what became of a named character's brief at the hand-over (used, or why not), how long it
     * took to be ready and how long it held the wish. {@code look} is the text the worker was given.
     */
    public static void brief(String wishId, String mode, String outcome, String character, boolean cached,
                             long readyMs, long heldMs, String look, String why) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "brief");
            o.addProperty("wishId", wishId == null ? "" : wishId);
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("mode", mode == null ? "" : mode);
            o.addProperty("outcome", outcome == null ? "" : outcome);
            o.addProperty("character", character == null ? "" : character);
            o.addProperty("cached", cached);
            o.addProperty("readyMs", readyMs);
            o.addProperty("heldMs", heldMs);
            o.addProperty("look", look == null ? "" : look);
            o.addProperty("why", why == null ? "" : why);
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a brief: {}", e.toString());
        }
    }

    public static void prefill(String playerId, String rung, String outcome) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "prefill");
            if (playerId != null && !playerId.isBlank()) {
                o.addProperty("playerId", playerId);
            }
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("rung", rung == null ? "" : rung);
            o.addProperty("outcome", outcome == null ? "" : outcome);
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a prefill: {}", e.toString());
        }
    }

    static JsonObject menuRecord(String kind, String playerId, String menu, String action, String family, int page,
                                 int slot, String tile, boolean secondary) {
        JsonObject o = new JsonObject();
        o.addProperty("at", java.time.Instant.now().toString());
        o.addProperty("kind", kind);
        if (playerId != null && !playerId.isBlank()) {
            o.addProperty("playerId", playerId);
        }
        o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
        stampLineage(o);
        o.addProperty("menu", menu == null ? "" : menu);
        if (action != null) {
            o.addProperty("action", action);
        }
        if (family != null) {
            o.addProperty("family", family);
        }
        if (page >= 0) {
            o.addProperty("page", page);
        }
        if (slot >= 0) {
            o.addProperty("slot", slot);
        }
        if (tile != null && !tile.isBlank()) {
            o.addProperty("tile", clip(tile, MENU_TILE_MAX));
        }
        if (secondary) {
            o.addProperty("secondary", true);
        }
        return o;
    }

    /** A tile id is an id; anything longer than this is clipped (a keepsake key can carry its NBT). */
    static final int MENU_TILE_MAX = 80;

    static JsonObject intentRecord(String wishId, String playerId,
                                   com.moddingfromamod.marshal.WishIntent.Taken taken, boolean bootWait) {
        JsonObject o = new JsonObject();
        o.addProperty("at", java.time.Instant.now().toString());
        o.addProperty("kind", "intent");
        if (wishId != null && !wishId.isBlank()) {
            o.addProperty("wishId", wishId);
        }
        if (playerId != null && !playerId.isBlank()) {
            o.addProperty("playerId", playerId);
        }
        o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
        stampLineage(o);
        o.addProperty("first", taken == null ? "none" : taken.first());
        if (taken != null) {
            o.addProperty("leadMs", taken.leadMs());
            o.addProperty("kinds", taken.kinds());
        }
        o.addProperty("bootWait", bootWait);
        return o;
    }

    public static void reach(String tool, String action, String why, String what, String command,
            int others) {
        try {
            append(reachRecord(tool, action, why, what, command, others));
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a reach: {}", e.toString());
        }
    }

    /**
     * <b>A line Moddy put in a child's chat, and the method that sent it</b>.
     *
     * <p>The first live bug report (2026-09-24, "make it daytime forever") was "it said it twice".
     * The trace could show two {@code plan_wish} calls and one {@code finish_wish}, and could not
     * show what the child actually READ — no line sent to a player was written down anywhere, so
     * "did he see the granted block twice?" had no evidence either way. One row per line, from the
     * one helper every wish announcement goes through ({@code WishFeedback}), with the sender named
     * so a duplicate points at its caller.
     *
     * <p>{@code text} is Moddy's words, which can quote the child's; it is the same bucket, the same
     * 180-day deletion and the same {@code --redact} as the wish text itself.
     * {@code wishId} is empty when the line cannot be pinned to exactly one wish — never guessed.
     */
    public static void said(String wishId, String playerId, String site, String text, boolean overlay) {
        try {
            append(saidRecord(wishId, playerId, site, text, overlay));
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a said line: {}", e.toString());
        }
    }

    /** The record {@link #said} writes — built separately so a unit test can read it. */
    static JsonObject saidRecord(String wishId, String playerId, String site, String text,
            boolean overlay) {
        JsonObject o = new JsonObject();
        o.addProperty("at", java.time.Instant.now().toString());
        o.addProperty("kind", "said");
        if (wishId != null && !wishId.isBlank()) {
            o.addProperty("wishId", wishId);
        }
        if (playerId != null && !playerId.isBlank()) {
            o.addProperty("playerId", playerId);
        }
        o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
        stampLineage(o);
        o.addProperty("site", site == null ? "" : site);
        o.addProperty("overlay", overlay);
        o.addProperty("text", text == null ? "" : clip(text, 1000));
        return o;
    }

    /** The record {@link #reach} writes — built separately so a unit test can read it. */
    static JsonObject reachRecord(String tool, String action, String why, String what,
            String command, int others) {
        JsonObject o = new JsonObject();
        o.addProperty("at", java.time.Instant.now().toString());
        o.addProperty("kind", "reach");
        var run = AgentContext.currentRun();
        if (run != null) {
            o.addProperty("wishId", run.wishId().toString().substring(0, 8));
            if (run.playerId() != null) {
                o.addProperty("playerId", run.playerId().toString());
            }
        }
        o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
        stampLineage(o);
        o.addProperty("tool", tool == null ? "" : tool);
        o.addProperty("action", action == null ? "" : action);
        o.addProperty("why", why == null ? "" : why);
        o.addProperty("what", what == null ? "" : clip(what, 120));
        if (command != null && !command.isEmpty()) {
            o.addProperty("command", clip(command, 400));
        }
        o.addProperty("others", Math.max(0, others));
        return o;
    }

    /**
     * <b>How a wish was routed, and whether the arm held.</b> One record per take, written at the
     * seam where the decision was actually made.
     *
     * <p>The {@code held} flag is what stops this becoming a dashboard. A forced arm cannot be
     * allowed to wedge a child's wish, so the preference expires
     * ({@link com.moddingfromamod.marshal.ArtifactAffinity#HOLD_MS}) and the wish goes to whoever is
     * free. That wish was NOT granted under the arm it is labelled with, and averaging it in would
     * report a result the run never measured. The scorer excludes it; this is where it learns to.
     */
    public static void routed(String wishId, String workerId, String artifactId,
                              com.moddingfromamod.marshal.ArtifactAffinity.Routing routing) {
        routed(wishId, workerId, artifactId, routing, null, -1);
    }

    /**
     * The same record, plus <b>what the router could see when it decided</b>.
     *
     * <p>Two numbers, each of which turns a day of hand-analysis into a field:
     *
     * <ul>
     *   <li>{@code ownerSeen} — who {@code ArtifactAffinity} thought owned this artifact at that
     *       instant. {@code ANYONE} under a forcing arm means it held <em>nothing</em>, and the
     *       whole question is how that happens while the ladder harness has an id pinned.
     *       The harness's view and the router's view are separate, and every score is computed from
     *       the harness's — so when they disagree the numbers are about an arm that never bound.</li>
     *   <li>{@code poolSeen} — how many distinct workers this world has offered a wish to. Below 2,
     *       a {@code DIFFERENT} arm is unsatisfiable by construction and the cell is not evidence,
     *       however clean its verdict.</li>
     * </ul>
     *
     * <p>Recorded rather than enforced, deliberately. A wish that cannot bind its arm must still be
     * granted — a child is waiting — so the honest move is to let it through and say so, which is
     * the same argument {@link com.moddingfromamod.marshal.ArtifactAffinity.Routing#held()} already
     * makes for FALLBACK.
     */
    public static void routed(String wishId, String workerId, String artifactId,
                              com.moddingfromamod.marshal.ArtifactAffinity.Routing routing,
                              String ownerSeen, int poolSeen) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "routed");
            if (wishId != null && !wishId.isBlank()) {
                o.addProperty("wishId", wishId);
            }
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            // Stamped from the argument rather than from AgentContext: this is a statement about the
            // worker that TOOK the wish, and the two are the same today only because the tool body
            // runs on the taking thread. An assumption like that is exactly what goes stale quietly.
            o.addProperty("workerId", workerId == null ? "" : workerId);
            o.addProperty("artifactId", artifactId == null ? "" : artifactId);
            o.addProperty("routing", routing == null ? "" : routing.label());
            o.addProperty("held", routing == null || routing.held());
            if (ownerSeen != null) {
                o.addProperty("ownerSeen", ownerSeen);
            }
            if (poolSeen >= 0) {
                o.addProperty("poolSeen", poolSeen);
                // THE VERDICT, not the ingredients. A reader should not have to know that DIFFERENT
                // needs two workers to see that this cell could not answer its own question.
                //
                // ⚠ MODE-AWARE, and it has to be. Only DIFFERENT needs a second worker: SAME is
                // perfectly satisfiable by a pool of one — the owner takes its own wish — and OFF
                // asks nothing of the pool at all. A flat `poolSeen >= 2` would have marked four
                // owner-arm cells of the machines matrix unanswerable when every one of them bound
                // 4/4, which is noise in exactly the column added to remove it.
                com.moddingfromamod.marshal.WishAffinity arm =
                        com.moddingfromamod.marshal.WishAffinity.current();
                o.addProperty("arm", arm == null ? "" : arm.label());
                o.addProperty("armSatisfiable",
                        arm != com.moddingfromamod.marshal.WishAffinity.DIFFERENT || poolSeen >= 2);
            }
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a routing decision: {}", e.toString());
        }
    }

    /**
     * <b>What was injected into a worker's wake, and what it cost.</b>
     *
     * <p>Criterion 6 of the plan is the one that stops this becoming "attach the transcript and
     * declare victory", and it cannot be checked against an estimate. So the size is measured on the
     * real block at the real seam, not worked out later as characters × 4 — the plan lists exactly
     * that substitution as threat 7.
     *
     * @param items how many pieces of history the block carried (records, turns) — 0 for none
     */
    public static void context(String wishId, String artifactId, String mode, int items,
                               int chars) {
        context(wishId, artifactId, mode, items, chars, 0, 0);
    }

    /**
     * As above, plus <b>what the budget had to leave out</b>.
     *
     * <p>Recorded because a brief that fits is a different measurement from one that was trimmed to
     * fit, and reading the second as the first is how a treatment gets credited with history it
     * never handed over. If {@code turnsDropped} climbs across a ladder, the budget — not the idea —
     * is what the arm measured.
     */
    public static void context(String wishId, String artifactId, String mode, int items,
                               int chars, int turnsDropped, int rejectionsDropped) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "context");
            if (wishId != null && !wishId.isBlank()) {
                o.addProperty("wishId", wishId);
            }
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("artifactId", artifactId == null ? "" : artifactId);
            o.addProperty("mode", mode == null ? "" : mode);
            o.addProperty("items", items);
            o.addProperty("chars", chars);
            o.addProperty("turnsDropped", turnsDropped);
            o.addProperty("rejectionsDropped", rejectionsDropped);
            o.addProperty("budgetChars", com.moddingfromamod.marshal.MemoryBrief.budgetChars());
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log an injected context block: {}", e.toString());
        }
    }

    /** Record one tool call. Never throws — logging must not be able to break a wish. */
    public static void toolCall(String tool, JsonObject args, ToolResult result, long durationMs) {
        toolCall(tool, args, result, durationMs, "");
    }

    /**
     * A tool call, stamped with WHICH WISH it was part of.
     *
     * <p><b>Why the id and not the clock.</b> Every per-wish number in {@code score-wish.py} used to
     * be worked out by taking the rows between one wish record and the next. That only ever held
     * while wishes ran one at a time. It is now wrong twice over: a child firing four preset buttons
     * in six seconds gives the first three wishes a two-second window each and bills the whole
     * burst's work to the fourth; and with more than one slot, two agents interleave their calls and
     * every one of them lands under whichever wish record happened to come last.
     *
     * <p>That is not a small skew. Measured on the 20-wish window, twelve wishes appeared to have
     * had NO work done on them, and their windows were 1.2 to 26 seconds long while every wish that
     * looked "picked up" had a window of 300 seconds or more. The metric was reporting which wish
     * came last in a burst.
     */
    public static void toolCall(String tool, JsonObject args, ToolResult result, long durationMs,
                                String wishId) {
        toolCall(tool, args, result, durationMs, wishId, java.util.List.of());
    }

    /**
     * As above, plus any argument names {@link ArgAliases} had to repair, in {@code sent->wanted}
     * form.
     *
     * <p>This exists because the repair happens IN PLACE, so {@code argKeys} below records the
     * corrected names — the very evidence that identified the problem would be erased by the fix
     * for it. A forgiving boundary that hides its own use turns a measurable naming problem into an
     * invisible one; this field is how the rate stays visible, and it is the evidence for whether a
     * field should simply be renamed to what the model keeps reaching for.
     */
    public static void toolCall(String tool, JsonObject args, ToolResult result, long durationMs,
                                String wishId, java.util.List<String> aliased) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "tool_call");
            if (wishId != null && !wishId.isBlank()) {
                o.addProperty("wishId", wishId);
            }
            // Tagged at the SOURCE so the improvement report can tell the child's real
            // play from the gates' own traffic. See RunContext.
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("tool", tool);
            String rawArgs = args == null ? "{}" : args.toString();
            o.addProperty("args", clip(rawArgs, MAX_ARGS));
            // Say so when it did not fit. A consumer cannot tell a complete record from a cut one
            // by looking at it, and every wrong conclusion this log has produced came from
            // exactly that.
            if (flatten(rawArgs).length() > MAX_ARGS) {
                o.addProperty("argsClipped", true);
                o.addProperty("argsChars", rawArgs.length());
            }
            // The top-level KEY NAMES, uncut.
            //
            // `args` is clipped, and a sculpt_creature call is mostly one enormous `parts` array,
            // so in 57 of 72 real records every field after `parts` fell off the end. That made
            // field-adoption unmeasurable -- and worse, measurable-looking: a report counting
            // fields in the clipped string returned a confident 0% for everything, and a census
            // of the clipped string produced "the agent never uses palette", which was FALSE. It
            // used it in every call. Keys are a few bytes and answer the question exactly.
            if (args != null) {
                com.google.gson.JsonArray keys = new com.google.gson.JsonArray();
                args.keySet().forEach(keys::add);
                o.add("argKeys", keys);
            }
            if (aliased != null && !aliased.isEmpty()) {
                com.google.gson.JsonArray fixed = new com.google.gson.JsonArray();
                aliased.forEach(fixed::add);
                o.add("aliased", fixed);
            }
            o.addProperty("ok", result != null && !result.isError());
            String rawResult = result == null ? "" : result.text();
            o.addProperty("result", clip(rawResult, MAX_RESULT));
            if (flatten(rawResult).length() > MAX_RESULT) {
                o.addProperty("resultClipped", true);
            }
            o.addProperty("ms", durationMs);
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a tool call: {}", e.toString());
        }
    }

    /**
     * Record a wish reaching (or not reaching) the offline engine — the demand signal.
     *
     * @param source one of the six words listed beside the field below, <b>not</b> only the three
     *               {@code WishSource} names — a wish can also be recorded at the place it ended
     */
    public static void wish(String text, String outcome, String source) {
        wish(text, outcome, source, "");
    }

    /** As above, carrying the id its tool calls will be stamped with. */
    public static void wish(String text, String outcome, String source, String wishId) {
        wish(text, outcome, source, wishId, "");
    }

    /**
     * …and <b>whose wish it was</b> — the player's UUID, never a name.
     *
     * <p>A UUID and not a name, deliberately and permanently. This file is mined into committed
     * corpora ({@code tools/mine-real-wishes.py}, {@code tools/mine-tool-calls.py}), so a child's
     * Mojang name written here would travel into the repo; the UUID is a join key and nothing
     * else. The NAME lives in {@code <world>/wishes/journal.jsonl}, which stays on the machine
     * that ran the world — a server-side report is the join, and the two halves
     * are separable precisely because only one of them is identifying.
     *
     * <p>Omitted rather than written empty when the caller does not know who asked (a compile with
     * no run bound, a gate calling the log directly), which is the same omit-not-null rule
     * {@code world} and {@code session} follow: a consumer can tell "nobody was attributed" from
     * "attributed to the empty player".
     */
    /**
     * <b>A wish stopped moving</b> — the engine's half of {@code spend.wish}.
     *
     * <p>Written once, at the first FINAL status, from {@code WishBook.complete}: the one place a
     * status moves. It carries what only the engine knows — whose wish it was (a UUID, never a
     * name), how it ended, how long the child waited, and which model and effort were ASKED FOR.
     * It carries no tokens: a {@code --bg} worker's usage is visible only in the CLI's transcript,
     * and {@code tools/wish-cost.py} stays the one place that counts it, because a Java copy of
     * that count would be a second definition of what a wish cost.
     *
     * <p>{@code model} is the requested SPEC ({@code opus}, {@code muse:muse-spark-1.2-contributor})
     * because a foreign endpoint chooses what model id to echo, and the contributor tier is a
     * different price reached only by asking for it by name.
     */
    public static void settled(String wishId, String playerId, String status, String source,
                               long tookMs, String model, String effort) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "settled");
            if (wishId != null && !wishId.isBlank()) {
                o.addProperty("wishId", wishId);
            }
            if (playerId != null && !playerId.isBlank()) {
                o.addProperty("playerId", playerId);
            }
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("status", status == null ? "" : status);
            o.addProperty("source", source == null ? "" : source);
            o.addProperty("tookMs", tookMs);
            o.addProperty("model", model == null ? "" : model);
            o.addProperty("effort", effort == null ? "" : effort);
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a settled wish: {}", e.toString());
        }
    }

    public static void wish(String text, String outcome, String source, String wishId,
                            String playerId) {
        wish(text, outcome, source, wishId, playerId, "");
    }

    /**
     * …and <b>which library recipe answered it</b> — the template id a typed match or a
     * button served, or {@code ""} when none did (the agent's wishes). Without it a BUTTON press
     * names no recipe anywhere in the log: a button writes no {@code cache} record, because it
     * names its own recipe and there was no routing decision to record. So "how often is each
     * starter used" was unanswerable for exactly the wishes that are starters.
     * Offline wish statistics read it; {@code WishRecordTest} pins the shape.
     */
    public static void wish(String text, String outcome, String source, String wishId,
                            String playerId, String templateId) {
        // ── A NEW WISH STARTS WITH NOTHING MISSING ────────────────────────────────────
        //
        // `MissingContent` records what a wish asked for that is not installed, so a substitute
        // cannot be scored as a grant. A static that is never reset would leak one wish's misses
        // into the next one's verdict, which is a worse bug than the one it fixes — the same
        // per-wish discipline `ToolNarrator` has for the same reason. This is the one place every
        // wish passes through.
        com.moddingfromamod.wish.MissingContent.clear();
        try {
            append(wishRecord(text, outcome, source, wishId, playerId, templateId));
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a wish: {}", e.toString());
        }
    }

    /**
     * <b>Something was promoted into the wish library</b> — the last step of the
     * promotion funnel, which until now nothing recorded: {@code /moddyop promote} wrote the
     * library and a log line, and the action log never learned a promotion happened. One record
     * per SUCCESSFUL promotion; a refusal promoted nothing.
     *
     * @param from  the world's key that was promoted, normally a fork ({@code spikor~1f2e3d4c})
     * @param id    the library id it is now served under
     * @param via   which door: {@code moddyop} (the in-game op command) today; an operator tool's queued
     *              command can be a second, and both must be admin-only
     * @param force whether it replaced an id the library already served
     */
    public static void promotion(String from, String id, String via, boolean force) {
        try {
            append(promotionRecord(from, id, via, force));
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a promotion: {}", e.toString());
        }
    }

    /** The record {@link #promotion} writes — built separately so a unit test can read it. */
    static JsonObject promotionRecord(String from, String id, String via, boolean force) {
        JsonObject o = new JsonObject();
        o.addProperty("at", java.time.Instant.now().toString());
        o.addProperty("kind", "promotion");
        o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
        stampLineage(o);
        o.addProperty("from", from == null ? "" : from);
        o.addProperty("id", id == null ? "" : id);
        o.addProperty("via", via == null ? "" : via);
        o.addProperty("force", force);
        return o;
    }

    /**
     * <b>What a granted wish made</b> — its run's {@code made()} list, the exact
     * {@code (kind, id)} of every artifact, fork suffix included.
     *
     * <p>An operator's library tool nominates a typed wish for promotion, and promotion takes a
     * KEY; before this the only join from a wish to its key was the agent's own tool arguments,
     * which name the id it ASKED for rather than the one stored ({@code spikor} for
     * {@code spikor~1f2e3d4c}). The run already knew; it just forgot at {@code Marshal.finish}.
     * No words and no player id — the wish's own record carries those.
     */
    public static void made(String wishId, java.util.List<com.moddingfromamod.marshal.WishRun.Made> made) {
        try {
            if (made == null || made.isEmpty()) {
                return;
            }
            append(madeRecord(wishId, made));
            long now = System.currentTimeMillis();
            for (var m : made) {
                com.moddingfromamod.store.ShelfTimes.made(m.kind(), m.id(), now);   //
            }
        } catch (RuntimeException e) {
            LOGGER.debug("could not log what a wish made: {}", e.toString());
        }
    }

    /** The record {@link #made} writes — built separately so a unit test can read it. */
    static JsonObject madeRecord(String wishId, java.util.List<com.moddingfromamod.marshal.WishRun.Made> made) {
        JsonObject o = new JsonObject();
        o.addProperty("at", java.time.Instant.now().toString());
        o.addProperty("kind", "made");
        if (wishId != null && !wishId.isBlank()) {
            o.addProperty("wishId", wishId);
        }
        o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
        stampLineage(o);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (com.moddingfromamod.marshal.WishRun.Made m : made) {
            JsonObject one = new JsonObject();
            one.addProperty("kind", m.kind());
            one.addProperty("id", m.id());
            arr.add(one);
        }
        o.add("made", arr);
        return o;
    }

    /** The record {@link #wish} writes — built separately so a unit test can read it. */
    static JsonObject wishRecord(String text, String outcome, String source, String wishId,
                                 String playerId, String templateId) {
        JsonObject o = new JsonObject();
        o.addProperty("at", java.time.Instant.now().toString());
        o.addProperty("kind", "wish");
        if (wishId != null && !wishId.isBlank()) {
            o.addProperty("wishId", wishId);
        }
        if (playerId != null && !playerId.isBlank()) {
            o.addProperty("playerId", playerId);
        }
        // Tagged at the SOURCE so the improvement report can tell the child's real
        // play from the gates' own traffic. See RunContext.
        //
        // ...and from a TEST WISH ON PRODUCTION: a canary, a probe, a stand-in account
        // or a session's armed window. RunContext is false on every dedicated server, so without `origin`
        // those were counted as the child's play. Written on every wish record, `child` included,
        // so "a child's wish" and "a record from before the field" stay distinct. The id is
        // remembered so the wish's later records (tool calls, made, settled) carry it too.
        String origin = com.moddingfromamod.wish.WishOrigin.of(playerId, null);
        o.addProperty("test", com.moddingfromamod.RunContext.isTestRun()
                || com.moddingfromamod.wish.WishOrigin.isTest(origin));
        o.addProperty("origin", origin);
        com.moddingfromamod.wish.WishOrigin.remember(wishId, origin);
        stampLineage(o);
        o.addProperty("text", clip(text));
        // FULFILLING | PENDING_ESCALATION | THROTTLED | EMPTY | BLOCKED
        //
        // BLOCKED is the child-safety outcome (docs/CHILD_SAFETY_PLAN.md §6) and it is the
        // first thing that ever sets WishStatus.REJECTED, which has been documented as
        // meaning "refused before doing anything ... unsafe" since it was written and has
        // never once been set. It is deliberately distinct from THROTTLED: a blocked wish
        // must not cost the child a rate-limit token.
        o.addProperty("outcome", outcome);
        // ── THE EFFORT THIS WISH WAS GRANTED AT (HO-9) ────────────────────────────
        //
        // One field, and it is the one that makes every per-effort comparison in this repo
        // possible: `wish-latency.py` has printed a per-`effort` A/B for months against a
        // field nothing wrote, and `spend.wish` copies it off this record so a cost can be
        // read by effort rather than pooled. Without it "is low effort cheaper" is a
        // question about artefacts nobody can join.
        //
        // ⚠ Stamped from AgentSession.resolvedEffort(), which is what is actually PASSED to
        // the CLI -- not the raw property. Those differ: an unrecognised value falls back to
        // the shipped default rather than being passed on, so reading the property here
        // would record an effort the session never ran at, which is worse than none.
        // Empty means the CLI's own default, and is written as empty rather than omitted so
        // "granted at the CLI default" and "this run predates the field" stay distinct.
        o.addProperty("effort", AgentSession.resolvedEffort());
        // ── THE `source` VOCABULARY (docs/CHILD_SAFETY_PLAN.md §6) ────────────────────
        //
        //   PRESET | TYPED | COMMAND     -- WishSource: how the child made the wish
        //   REDIRECT                     -- the free text of `/moddy redirect`, refused by L0
        //                                   at the re-aim (WishCancel.reaim)
        //   PLAN                         -- the wish ended at the plan gate (SafetyPlanGate.endWish);
        //                                   the text is the wish's own, already logged under
        //                                   its real source when it was submitted
        //   REJECTION                    -- the child said "that wasn't right" (WishService.reject):
        // the wish is UNDONE, outcome STOPPED (it was
        //                                   re-escalated, outcome PENDING_ESCALATION, before)
        //
        // It is a STRING and not `WishSource`, because three of those six are not a way of
        // making a wish at all -- they are a place a wish ENDED -- and widening the enum to
        // hold them would put safety and rejection words into the type every submit path
        // reads. The three extras were undocumented until CS-REVIEW-2 (REJECTION since it
        // shipped, PLAN and REDIRECT since CS-S2), which was safe only by luck: every
        // consumer tolerates an unknown value, and each does so differently.
        // `tools/mine-real-wishes.py` filters on `== "TYPED"`, an allow-list of one, so these
        // three are SKIPPED rather than mined -- which is what is wanted, because a REDIRECT
        // record's text is a string L0 refused and a PLAN record's would double-count a wish
        // already in the corpus. `tools/wish-report.sh` never reads the field and groups by
        // `outcome`, so they land under BLOCKED, which is the honest bucket.
        // `tools/rank-wishes.py` never reads this log at all. Pinned by
        // tools/test-mine-real-wishes.py and tools/test-wish-report.py.
        o.addProperty("source", source);
        if (templateId != null && !templateId.isBlank()) {
            // Omitted, not empty, when nothing served it — the omit-not-null rule `wishId` and
            // `playerId` follow, so "no recipe" and "a log from before this field" stay distinct
            // only by the date, which is the honest answer.
            o.addProperty("templateId", templateId);
        }
        return o;
    }

    /**
     * <b>One safety grade, and what was done about it</b> (docs/CHILD_SAFETY_PLAN.md §6).
     *
     * <p>Written <b>before</b> the action is taken, so a crash between the grade and the action
     * leaves the grade rather than leaving nothing. {@code wishId} joins it to the {@code wish},
     * {@code tool_call} and {@code routed} records, which is what lets a report say "these three
     * wishes were redirected and here is what the agent built instead".
     *
     * <p><b>Every band is logged, including FINE, and shadow mode logs identically to on.</b> That
     * is the whole instrument: over-blocking has to fall out of the telemetry rather than out of a
     * child telling somebody, and a rate needs its denominator. A log that only recorded the
     * refusals would make the false-positive rate — the number this layer actually lives or dies
     * on — uncomputable.
     *
     * @param layer   {@code input} | {@code plan} | {@code text} | {@code datapack} (CS-D4: the
     *                lexicon over a CBScript source at compile time)
     * @param action  what was DONE: PASS, NOTE, REDIRECT, BLOCK, DIVERT, STOP,
     *                UNAVAILABLE_OPEN, UNAVAILABLE_CLOSED. Recorded rather than inferred from the
     *                band, because the mode and the strictness both sit in between.
     */
    public static void safety(String wishId, String layer, SafetyVerdictView verdict,
                              String action, long latencyMs, double costUsd, String model,
                              String raw) {
        safety(wishId, layer, verdict, action, latencyMs, costUsd, model, raw, "", 0, false);
    }

    /**
     * The same record, plus which tool asked and whether the redirect was actually shown.
     *
     * <p>{@code redirectShown} is not decoration: §2 promises nothing is ever quietly substituted,
     * and this field is the only place that promise is checkable after the fact.
     */
    public static void safety(String wishId, String layer, SafetyVerdictView verdict,
                              String action, long latencyMs, double costUsd, String model,
                              String raw, String tool, int retry, boolean redirectShown) {
        safety(wishId, layer, verdict, action, latencyMs, costUsd, model, raw, tool, retry,
                redirectShown, false);
    }

    /**
     * …and whether the player was told anything (the chat sweep's line to the sender, say).
     *
     * <p>Its own field rather than a reuse of {@code redirectShown}, because the two answer
     * different questions: {@code redirectShown} is "was a substitution announced", which is §2's
     * transparency promise, and this is "was a line said to the player at all".
     *
     * <p>Written on EVERY record, like {@code redirectShown}, and for the same reason a FINE band
     * is logged: a rate needs its denominator. It says what WILL be done — this record is written
     * before the message goes out — so in shadow mode it is honestly {@code false}.
     */
    public static void safety(String wishId, String layer, SafetyVerdictView verdict,
                              String action, long latencyMs, double costUsd, String model,
                              String raw, String tool, int retry, boolean redirectShown,
                              boolean childTold) {
        safety(wishId, layer, verdict, action, latencyMs, costUsd, model, raw, tool, retry,
                redirectShown, childTold, "");
    }

    /**
     * …and <b>whose wish was graded</b> — see {@link #wish(String, String, String, String, String)}
     * for why this is a UUID and never a name.
     *
     * <p>It matters most on the records that have no {@code wishId} at all: an L0 refusal at
     * submit is logged before the book is written to, so there is no id to join it to and never
     * will be (see {@code WishService.submit}). Without this field such a refusal cannot be
     * attributed to a child by any means, which makes the one number a grown-up asks —
     * <i>how often is MY child being refused</i> — unanswerable for exactly the records that
     * answer it.
     */
    public static void safety(String wishId, String layer, SafetyVerdictView verdict,
                              String action, long latencyMs, double costUsd, String model,
                              String raw, String tool, int retry, boolean redirectShown,
                              boolean childTold, String playerId) {
        safety(wishId, layer, verdict, action, latencyMs, costUsd, model, raw, tool, retry, redirectShown,
                childTold, playerId, "");
    }

    /**
     * …and <b>which arm of the grader chain answered</b>: {@code subscription} (Ember on the
     * server's Muse Code plan), {@code api} (Ember on the API key), {@code haiku} (the job's pinned
     * Haiku). Written only when known, beside {@code model}; weekly
     * servicing reads both.
     */
    public static void safety(String wishId, String layer, SafetyVerdictView verdict,
                              String action, long latencyMs, double costUsd, String model,
                              String raw, String tool, int retry, boolean redirectShown,
                              boolean childTold, String playerId, String arm) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "safety");
            if (wishId != null && !wishId.isBlank()) {
                o.addProperty("wishId", wishId);
            }
            if (playerId != null && !playerId.isBlank()) {
                o.addProperty("playerId", playerId);
            }
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("layer", layer == null ? "" : layer);
            o.addProperty("action", action == null ? "" : action);
            o.addProperty("mode", com.moddingfromamod.safety.ChildSafetyConfig.modeLabel());
            o.addProperty("strictness",
                    com.moddingfromamod.safety.ChildSafetyConfig.strictnessLabel());
            o.addProperty("latencyMs", latencyMs);
            o.addProperty("costUsd", costUsd);
            if (model != null && !model.isBlank()) {
                o.addProperty("model", model);
            }
            if (arm != null && !arm.isBlank()) {
                o.addProperty("arm", arm);
            }
            if (tool != null && !tool.isBlank()) {
                o.addProperty("tool", tool);
            }
            o.addProperty("retry", retry);
            o.addProperty("redirectShown", redirectShown);
            o.addProperty("childTold", childTold);
            if (verdict != null) {
                o.addProperty("grader", verdict.grader());
                JsonObject bands = new JsonObject();
                verdict.bands().forEach(bands::addProperty);
                o.add("bands", bands);
                o.addProperty("overall", verdict.overallLabel());
                com.google.gson.JsonArray reasons = new com.google.gson.JsonArray();
                verdict.reasonNames().forEach(reasons::add);
                o.add("reasons", reasons);
                // OMITTED rather than written as "" when there is none, so a consumer can tell
                // "no redirect was proposed" from "one was proposed and was empty" -- the same
                // omit-not-null rule the world/session fields follow.
                if (!verdict.redirect().isEmpty()) {
                    o.addProperty("redirect", verdict.redirect());
                    // WHY it was not used, when it was not. `redirect` already carries the text, so
                    // one flag beside it is the whole record: a reader who wants the reason re-runs
                    // the lexicon over that string. Written only when true, so a consumer filtering
                    // on the field's presence gets exactly the rejections.
                    if (verdict.redirectRejected()) {
                        o.addProperty("redirectRejected", true);
                    }
                }
                if (verdict.stageIndex() >= 0) {
                    o.addProperty("stageIndex", verdict.stageIndex());
                }
            }
            String flatRaw = flatten(raw);
            o.addProperty("raw", clip(flatRaw, MAX_SAFETY_RAW));
            // Say so when it did not fit, for the reason every other clip in this file says so:
            // a consumer cannot tell a complete record from a cut one by looking at it.
            if (flatRaw.length() > MAX_SAFETY_RAW) {
                o.addProperty("rawClipped", true);
            }
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a safety grade: {}", e.toString());
        }
    }

    /**
     * A model reply is a few KB of envelope. Big enough to re-derive a verdict from, small enough
     * that a busy evening does not fill a disk — and clipped rather than dropped, because the reply
     * is the only evidence for whether a grade was the model's fault or the prompt's.
     */
    private static final int MAX_SAFETY_RAW = 4_000;

    /**
     * What {@link #safety} needs from a verdict, as strings and nothing else.
     *
     * <p>An interface rather than the record itself so the LOG SHAPE and the VERDICT TYPE can move
     * independently. §6 pins the record shape in the file a report reads; the safety package is
     * still being built and its record will gain fields. Naming the record here would couple the
     * one to the other, so that a change to the verdict silently changes the log format every
     * committed report and floor was measured against.
     *
     * <p>It also means a second grader — §7's direct-API transport, or a text-layer check with no
     * bands at all — logs through the same method instead of growing a second one.
     * {@code SafetyVerdict} implements it.
     */
    public interface SafetyVerdictView {
        /** {@code lexicon} | {@code haiku} | {@code unavailable}. */
        String grader();

        /** Axis name to band name, e.g. {@code violence -> FINE}. */
        java.util.Map<String, String> bands();

        /**
         * The max band, or the word UNAVAILABLE.
         *
         * <p>Named {@code overallLabel} rather than {@code overall} because the implementing
         * record's own {@code overall()} returns a typed band — and a record component cannot be
         * two types at once. The same applies to {@code reasonNames}.
         */
        String overallLabel();

        /** The closed reason vocabulary, already bounded. */
        java.util.List<String> reasonNames();

        /** "" when none was proposed. */
        String redirect();

        /**
         * True when one WAS proposed and the floor refused it (docs/CHILD_SAFETY_PLAN.md §12.5).
         *
         * <p>A default rather than an abstract method, because it is a fact about a redirect and a
         * grader that proposes none can never have one rejected — so §7's direct-API transport and
         * the text layer inherit the right answer without knowing the rule exists.
         */
        default boolean redirectRejected() {
            return false;
        }

        /** &lt; 0 when no single step is responsible. */
        int stageIndex();
    }

    /**
     * <b>What a library nudge suggested</b>, and when — the record a "wrong-suggestion" rate and
     * a "did the worker use it" join are computed from. {@code moment} is {@code handover} (kind a)
     * or {@code plan} (kinds b–d); {@code picks} are {@code <letter>:<id>} keys, empty for a none.
     */
    public static void readyMade(String wishId, String moment, String arm, java.util.List<String> picks,
                                 long latencyMs, double costUsd, String note) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "readymade");
            if (wishId != null && !wishId.isBlank()) {
                o.addProperty("wishId", wishId);
            }
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("moment", moment == null ? "" : moment);
            o.addProperty("arm", arm == null ? "" : arm);
            com.google.gson.JsonArray ps = new com.google.gson.JsonArray();
            if (picks != null) {
                picks.forEach(ps::add);
            }
            o.add("picks", ps);
            o.addProperty("latencyMs", latencyMs);
            o.addProperty("costUsd", costUsd);
            if (note != null && !note.isBlank()) {
                o.addProperty("note", clip(note));
            }
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a ready-made nudge: {}", e.toString());
        }
    }

    /**
     * <b>Whether a nudge beat the worker's first build call</b> ({@code NudgeRace}), one per
     * nudged wish: {@code won} with {@code ms} its lead, or lost with {@code ms} how late it was.
     * {@code picked} says whether it suggested anything — a lost race with nothing picked cost
     * nothing. An operator's wish report reads these (miss rate, p50/p90 miss).
     */
    public static void readyMadeRace(String wishId, boolean won, long ms, boolean picked, String arm) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "readymade_race");
            if (wishId != null && !wishId.isBlank()) {
                o.addProperty("wishId", wishId);
            }
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("won", won);
            o.addProperty("ms", ms);
            o.addProperty("picked", picked);
            o.addProperty("arm", arm == null ? "" : arm);
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a ready-made race: {}", e.toString());
        }
    }

    /**
     * <b>What one MCP session was offered</b> (step 1): written when a worker fetches the tool
     * list, so a slow or confused turn can be read against what the model actually had in front of
     * it — how many tools, how many characters of description and schema (a proxy for the prefix
     * the model re-reads each turn), and their names. Muse Code's own session log carries its view;
     * this is the server's, the same for every CLI.
     */
    public static void toolsOffered(String sessionId, java.util.List<String> names, int descChars,
                                    int schemaChars) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "tools_offered");
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            if (sessionId != null && !sessionId.isBlank()) {
                o.addProperty("session", sessionId);
            }
            o.addProperty("count", names.size());
            o.addProperty("descChars", descChars);
            o.addProperty("schemaChars", schemaChars);
            JsonArray a = new JsonArray();
            names.forEach(a::add);
            o.add("names", a);
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log the offered tool list: {}", e.toString());
        }
    }

    private static synchronized void append(JsonObject o) {
        stampOrigin(o);
        stampDeployment(o);
        count(o);
        append(file(), o);
    }

    /**
     * <b>A test wish's later records are test records too</b> — stamped HERE, in the one
     * funnel, so a tool call, a {@code made}, a {@code settled} or a line Moddy said for a canary's
     * wish cannot be counted as the child's because its writer forgot. Only a record carrying the
     * {@code wishId} of a wish {@link com.moddingfromamod.wish.WishOrigin} remembered as test is
     * touched; a child's records are left exactly as they were. Never throws: this is logging.
     */
    static void stampOrigin(JsonObject o) {
        try {
            if (o.has("origin") || !o.has("wishId")) {
                return;
            }
            String origin = com.moddingfromamod.wish.WishOrigin.ofWish(o.get("wishId").getAsString());
            if (origin != null) {
                o.addProperty("test", true);
                o.addProperty("origin", origin);
            }
        } catch (RuntimeException e) {
            LOGGER.debug("could not stamp a wish's origin: {}", e.toString());
        }
    }

    /**
     * <b>Which deployment wrote this record</b> — stamped HERE, in the one funnel every
     * record passes through, so no record kind can be written without it.
     *
     * <p>The {@code deployment} setting: a server's settings override reads {@code -Dmoddingfromamod.deployment} (its
     * launcher passes {@code MODDY_DEPLOYMENT}), else the {@code MODDY_DEPLOYMENT} environment variable. Omitted — never
     * guessed — when neither is set: a developer's client and every gate have no deployment, and
     * "absent" is how a reader tells that from a real deployment. A value the record already carries
     * wins. Never throws: this is logging.
     */
    static void stampDeployment(JsonObject o) {
        try {
            if (o.has("deployment")) {
                return;
            }
            // a server's own setting (its override reads -Dmoddingfromamod.deployment / MODDY_DEPLOYMENT);
            // the public mod has none.
            String d = com.moddingfromamod.config.ModdySettings.get("deployment").orElse(null);
            if (d != null) {
                o.addProperty("deployment", d);
            }
        } catch (RuntimeException e) {
            LOGGER.debug("could not stamp the deployment: {}", e.toString());
        }
    }

    /** The property, else the environment; null when neither names one. Pure, for the test. */
    public static String deploymentOf(String property, String env) {
        for (String v : new String[] {property, env}) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * <b>Feed {@code /metrics} from the record that is being written</b> (HO-2).
     *
     * <p>Here rather than at the five call sites, and that is the design: this is the one funnel
     * every record already passes through, so a counter and the records it counts <b>cannot
     * disagree</b> — they are incremented by the same call that writes the line. It is the property
     * {@code digest.py} has for money, applied to counts. Incrementing at the call sites would mean
     * five more places to keep in step, and the first one somebody forgot would be a metric that is
     * quietly low, which reads as good news.
     *
     * <p>Never throws: a counter is a diagnostic and the log line is the record. A metrics
     * endpoint that could stop a wish being recorded would be worth less than no metrics at all.
     */
    private static void count(JsonObject o) {
        try {
            String kind = str(o, "kind");
            switch (kind) {
                case "wish" -> com.moddingfromamod.health.Counters.wish(
                        str(o, "outcome"), str(o, "source"));
                case "safety" -> com.moddingfromamod.health.Counters.safety(
                        str(o, "layer"), str(o, "action"));
                // ⚠ `ok`, a boolean — NOT a `status` string. Read off the writer above rather
                // than assumed; the assumed spelling would have counted every call as a success,
                // which is a metric that is wrong in the reassuring direction.
                case "tool_call" -> com.moddingfromamod.health.Counters.toolCall(
                        str(o, "tool"),
                        !o.has("ok") || o.get("ok").isJsonNull() || o.get("ok").getAsBoolean());
                case "rejection" -> com.moddingfromamod.health.Counters.rejection();
                default -> { }
            }
        } catch (RuntimeException ignored) {
            // See the javadoc: the record is what matters.
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** The same, to a named file. */
    private static synchronized void append(Path p, JsonObject o) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, o + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.debug("could not write the action log: {}", e.toString());
        }
    }

    /**
     * Forget which file this log resolved to, so a test that has just pointed
     * {@code moddingfromamod.home} at a temp directory is written to THAT one.
     *
     * <p>The smallest possible seam: {@link #file()} memoises the path on first use, so without
     * this a test's records land in the developer's real home if anything logged before it ran —
     * which is both a polluted home and an assertion that reads a file nobody wrote.
     */
    public static synchronized void resetForTest() {
        file = null;
    }

    /**
     * Record that the child <b>refused</b> a wish — the highest-value line in this whole file.
     *
     * <p>Every other signal here is inferred: an error code, a missing template, a repeated edit.
     * This one is a direct verdict from the only person whose opinion decides whether the mod is
     * any good. A cluster of rejections around one kind of wish is the clearest possible statement
     * of what to build next.
     */
    public static void rejection(String text, String templateId, boolean forgotten,
                                 String complaint) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("at", java.time.Instant.now().toString());
            o.addProperty("kind", "rejection");
            // Tagged at the SOURCE so the improvement report can tell the child's real
            // play from the gates' own traffic. See RunContext.
            o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
            stampLineage(o);
            o.addProperty("text", clip(text));
            o.addProperty("templateId", templateId == null ? "" : templateId);
            o.addProperty("forgotten", forgotten);
            o.addProperty("complaint", clip(complaint));
            append(o);
        } catch (RuntimeException e) {
            LOGGER.debug("could not log a rejection: {}", e.toString());
        }
    }

    private static String clip(String s) {
        return clip(s, MAX_FIELD);
    }

    private static String clip(String s, int max) {
        String flat = flatten(s);
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    private static String flatten(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }

    /** Where the trail lives (shown in logs so it's findable). */
    public static Path path() {
        return file();
    }
}
