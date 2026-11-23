package com.moddingfromamod.readymade;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.agent.ClaudeCli;
import com.moddingfromamod.runtime.RuntimeConfig;

/**
 * <b>The nudges, in a running game</b>: (a) at the hand-over, (b)(c)(d) together at
 * {@code plan_wish}. Each runs on a daemon thread of its own (the {@code CLAUDE.md} rule for
 * anything whose job is to speak when nothing else can) and posts to {@link WishNotes}; nothing
 * on the wish's path waits for it except the two tools {@code WishNotes} names.
 */
public final class ReadyMadeLive {

    private ReadyMadeLive() {
    }

    /** Small on purpose: a closed question over a menu (docs/PATTERNS.md). */
    /** Pinned, never the {@code haiku} alias; see {@code SafetyGrader.HAIKU_PIN}. */
    public static final String MODEL = com.moddingfromamod.safety.SafetyGrader.HAIKU_PIN;

    /**
     * {@code -Dmoddingfromamod.readymade.model}: the nudge's model. <b>Default Ember</b> ({@link
     * MuseGrade#EMBER}) since 2026-11-16: its tuned Ember-only prompt
     * met the bar on every set — wrong picks about half Haiku's, right picks at or above Haiku's mean, p50
     * 1.6 s against 3.0 s (docs/EMBER_GRADERS.md, corpus/prior-nudge/ember-tune-r1). When the Muse route gives
     * no answer it falls back to {@link #MODEL} (Haiku 5.5, pinned) with the ORIGINAL prompt.
     * {@code claude-haiku-5-5} puts the nudge back on Haiku.
     */
    public static final String MODEL_PROPERTY = "moddingfromamod.readymade.model";

    public static String model() {
        String m = System.getProperty(MODEL_PROPERTY, com.moddingfromamod.config.MagicianWiring.cheapSpec(com.moddingfromamod.agent.MuseGrade.EMBER));
        return m == null || m.isBlank() ? com.moddingfromamod.config.MagicianWiring.cheapSpec(com.moddingfromamod.agent.MuseGrade.EMBER) : m.strip();
    }

    private static volatile ReadyMadeNudge.Transport transport;

    /** A test's transport; null restores the shipped one. */
    public static void transportForTest(ReadyMadeNudge.Transport t) {
        transport = t;
    }

    public static ReadyMadeNudge.Transport transport() {
        ReadyMadeNudge.Transport t = transport;
        return t != null ? t : new ReadyMadeNudge.CliTransport(ClaudeCli.resolve(), model(), "low",
                RuntimeConfig.home());
    }

    /**
     * EVERY nudged kind, in ONE call, on the wish alone, the moment it is handed over. It was (b)(c)(d) at {@code plan_wish}, on the plan as well, and measured on real
     * grants that started it 6–8 s later than it had to: the plan-time nudge would have landed after
     * the worker's first build ~15% of the time, the hand-over one ~1%. {@link NudgeRace} records
     * every race so a reader can see whether that holds.
     */
    public static void atHandOver(String wishId, String wish) {
        atHandOver(wishId, wish, "");
    }

    /**
     * The nudge, with the wish note the worker was just handed: the nudge reads his FACTS out of it
     * ({@link ReadyMadeNudge#factsOf}), so a follow-up is matched against the same history.
     */
    public static void atHandOver(String wishId, String wish, String handedOver) {
        Set<ReadyMade.Kind> kinds = ReadyMadeConfig.nudges();
        if (!kinds.isEmpty()) {
            start(wishId, "handover", kinds, wish, List.of(), ReadyMadeNudge.factsOf(handedOver));
        }
    }

    /** A call reached the server: the first BUILD call of a raced wish decides its race. */
    public static void called(String wishId, String tool, long startedAtMs) {
        log(NudgeRace.global().called(wishId, tool, startedAtMs));
    }

    /** The last race this process logged — a GameTest's window onto the wiring. */
    static volatile NudgeRace.Outcome lastRace;

    private static void log(NudgeRace.Outcome o) {
        if (o != null) {
            lastRace = o;
            String id = o.wishId().length() > 8 ? o.wishId().substring(0, 8) : o.wishId();
            ActionLog.readyMadeRace(id, o.won(), o.ms(), o.picked(), ReadyMadeConfig.label());
        }
    }

    private static void start(String wishId, String moment, Set<ReadyMade.Kind> kinds, String wish,
                              List<String> steps, String facts) {
        if (wishId == null || wishId.isBlank()) {
            return;
        }
        WishNotes.global().expect(wishId);
        NudgeRace.global().started(wishId);
        String arm = ReadyMadeConfig.label();
        String shortId = wishId.length() > 8 ? wishId.substring(0, 8) : wishId;
        boolean canDescribe = !ReadyMadeConfig.searches().isEmpty();
        Thread t = new Thread(() -> {
            String note = "";
            boolean picked = false;
            long t0 = System.currentTimeMillis();
            try {
                ReadyMadeNudge.Reply r = ReadyMadeNudge.ask(transport(), ReadyMadeHouse.index(), kinds,
                        wish, steps, facts);
                note = ReadyMadeNudge.note(r.picks(), canDescribe);
                picked = !r.picks().isEmpty();
                List<String> keys = new ArrayList<>();
                r.picks().forEach(p -> keys.add(ReadyMadeNudge.key(p)));
                ActionLog.readyMade(shortId, moment, arm, keys, System.currentTimeMillis() - t0,
                        r.costUsd(), r.note());
            } catch (Throwable e) {
                ActionLog.readyMade(shortId, moment, arm, List.of(), System.currentTimeMillis() - t0, 0,
                        "failed: " + e);
            } finally {
                WishNotes.global().deliver(wishId, note);
                log(NudgeRace.global().landed(wishId, System.currentTimeMillis(), picked));
            }
        }, "mfam-readymade-" + moment);
        t.setDaemon(true);
        t.start();
    }
}
