package com.moddingfromamod.config;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The real {@link MagicianCheck.Steps}: the real graders on the picked helper model, the real sweeps, and one
 * real wish granted by the picked wish model, by {@code wisher} (the host in single-player; a stand-in player at a
 * dedicated server's start, D28). Spends real money: a few cents for the helper model, one small wish for the main.
 */
public final class LiveCheckSteps implements MagicianCheck.Steps {

    /** How long the test wish may take before the check calls it failed. */
    static final long WISH_TIMEOUT_MS = 8 * 60_000L;

    private final MinecraftServer server;
    private final java.util.function.Supplier<ServerPlayer> wisher;
    private final MagicianPicks picks;

    public LiveCheckSteps(MinecraftServer server, java.util.function.Supplier<ServerPlayer> wisher, MagicianPicks picks) {
        this.server = server;
        this.wisher = wisher;
        this.picks = picks;
    }

    private static final String INPUT = "the safety grader";

    @Override
    public MagicianCheck.Step inputAllows() {
        var g = com.moddingfromamod.safety.SafetyGrader.gradeInputNow(MagicianCheck.HARMLESS);
        String wrong = answeredBy(g.verdict().isUnavailable(), g.arm());
        if (wrong != null) {
            return new MagicianCheck.Step(INPUT, false, wrong, g.costUsd());
        }
        boolean ok = g.verdict().overall().compareTo(com.moddingfromamod.safety.SafetyVerdict.Band.MAYBE) <= 0;
        return new MagicianCheck.Step(INPUT, ok, ok ? "it let an ordinary wish through."
                : "it refused an ordinary wish (" + g.verdict().overallLabel() + ").", g.costUsd());
    }

    @Override
    public MagicianCheck.Step inputRefuses() {
        var g = com.moddingfromamod.safety.SafetyGrader.gradeInputNow(MagicianCheck.BLOCK_ROW);
        String wrong = answeredBy(g.verdict().isUnavailable(), g.arm());
        if (wrong != null) {
            return new MagicianCheck.Step("the safety refusal", false, wrong, g.costUsd());
        }
        boolean ok = g.verdict().overall() == com.moddingfromamod.safety.SafetyVerdict.Band.CLEARLY;
        return new MagicianCheck.Step("the safety refusal", ok, ok ? "it refused a wish it must refuse."
                : "it did NOT refuse a wish it must refuse (" + g.verdict().overallLabel() + ").", g.costUsd());
    }

    @Override
    public MagicianCheck.Step plan() {
        var g = com.moddingfromamod.safety.SafetyGrader.gradePlan(MagicianCheck.HARMLESS,
                List.of("sculpt a small red sheep", "make it hop around the house"), List.of("it is friendly"),
                "planned");
        String wrong = answeredBy(g.verdict().isUnavailable(), g.arm());
        if (wrong != null) {
            return new MagicianCheck.Step("the plan grader", false, wrong, g.costUsd());
        }
        boolean ok = g.verdict().overall().compareTo(com.moddingfromamod.safety.SafetyVerdict.Band.MAYBE) <= 0;
        return new MagicianCheck.Step("the plan grader", ok, ok ? "it graded an ordinary plan."
                : "it refused an ordinary plan (" + g.verdict().overallLabel() + ").", g.costUsd());
    }

    @Override
    public MagicianCheck.Step textSweep() {
        JsonObject friendly = new JsonObject();
        friendly.addProperty("message", "Here is your sheep!");
        JsonObject trip = new JsonObject();
        trip.addProperty("message", MagicianCheck.SWEEP_TRIP);
        boolean ok = !com.moddingfromamod.safety.SafetyTextSweep.sweep("notify_player", friendly, "check").refuses()
                && com.moddingfromamod.safety.SafetyTextSweep.sweep("notify_player", trip, "check").refuses();
        return new MagicianCheck.Step("the text sweep", ok, ok ? "it passed a friendly line and stopped a bad one."
                : "it did not stop a line it must stop.", 0);
    }

    @Override
    public MagicianCheck.Step datapackSweep() {
        boolean ok = !com.moddingfromamod.safety.SafetyDatapackSweep.sweep("moddy_check",
                        MagicianCheck.DATAPACK_FRIENDLY).refuses()
                && com.moddingfromamod.safety.SafetyDatapackSweep.sweep("moddy_check",
                        MagicianCheck.DATAPACK_TRIP).refuses();
        return new MagicianCheck.Step("the datapack sweep", ok, ok
                ? "it passed a friendly datapack and stopped a bad one." : "it did not stop words it must stop.", 0);
    }

    /** Why a grade does not count: nothing answered, or Haiku answered where the host picked a Muse helper model. */
    private String answeredBy(boolean unavailable, String arm) {
        if (unavailable) {
            return "the helper model did not answer (" + picks.cheap().map(SupportedModels.Model::label).orElse("?")
                    + ", " + picks.cheapPay().map(m -> m.label).orElse("?") + ").";
        }
        boolean museHelper = picks.cheap().map(m -> m.provider() == SupportedModels.Provider.MUSE).orElse(false);
        if (museHelper && com.moddingfromamod.agent.MuseExec.ARM_HAIKU.equals(arm)) {
            return "Ember did not answer; another model answered for it.";
        }
        return null;
    }

    /** A failed wish's known cause, as a clause ("" when none is known). Pure. */
    static String why(String cause) {
        return cause == null || cause.isBlank() ? "" : " — " + cause;
    }

    /** What the wish model spent on the check's wish, in words, and in dollars ({@link MagicianCheck.Step#UNKNOWN}). */
    record WishCost(String words, double usd) {
    }

    /**
     * The wish's own cost: read from the Muse Code workers' usage when the picks run the wish model there
     * (tokens, priced on an API key, 0 on a sign-in's flat fee); unknown on Claude Code, whose workers report none
     * to the game.
     */
    WishCost wishCost(long fromMs, long toMs) {
        var mode = MagicianWiring.museMain();
        if (mode.isEmpty()) {
            return new WishCost("", MagicianCheck.Step.UNKNOWN);
        }
        var t = com.moddingfromamod.agent.MuseUsage.spentSince(fromMs, toMs);
        if (t.steps() == 0) {
            return new WishCost(" (Muse Code recorded no usage for it)", MagicianCheck.Step.UNKNOWN);
        }
        String tokens = String.format(java.util.Locale.ROOT, "%,d tokens in, %,d out over %d model steps",
                t.inputTokens(), t.outputTokens(), t.steps());
        return mode.get() == SupportedModels.Mode.API
                ? new WishCost(String.format(java.util.Locale.ROOT, " (the wish model spent $%.4f: %s)", t.costUsd(),
                        tokens), t.costUsd())
                : new WishCost(" (on your Muse Code plan: " + tokens + ")", 0);
    }

    @Override
    public void wish(Consumer<MagicianCheck.Step> done) {
        server.execute(() -> {
            ServerPlayer p = wisher.get();
            if (p == null) {
                done.accept(MagicianCheck.Step.fail(MagicianCheck.WISH_STEP, "nobody to wish for."));
                return;
            }
            // Taken BEFORE the pool starts: a worker launched inside start() is one this wish may be granted by,
            // and the wish's cost is read from the workers launched since this moment.
            long checkStartedMs = System.currentTimeMillis();
            // Muse Code when the picks chose a Muse model, else Claude Code.
            com.moddingfromamod.agent.AgentSession.start(com.moddingfromamod.agent.AgentSession.workerBinary());
            var book = com.moddingfromamod.wish.WishBook.get(server);
            Set<UUID> before = book.recent(64).stream().map(com.moddingfromamod.wish.Wish::id)
                    .collect(Collectors.toSet());
            var outcome = com.moddingfromamod.wish.WishService.submit(p, MagicianCheck.WISH,
                    com.moddingfromamod.wish.WishSource.COMMAND, com.moddingfromamod.wish.WishRoute.Hint.MAGICIAN);
            UUID id = book.recent(64).stream().filter(w -> !before.contains(w.id()) && p.getUUID().equals(w.playerId()))
                    .map(com.moddingfromamod.wish.Wish::id).reduce((a, b) -> b).orElse(null);
            if (id == null) {
                done.accept(MagicianCheck.Step.fail(MagicianCheck.WISH_STEP, "it was not taken (" + outcome + ")."));
                return;
            }
            long started = System.currentTimeMillis();
            Thread watch = new Thread(() -> {
                while (System.currentTimeMillis() - started < WISH_TIMEOUT_MS) {
                    var w = book.byId(id).orElse(null);
                    if (w != null && w.isSettled()) {
                        long s = (System.currentTimeMillis() - started) / 1000;
                        boolean ok = w.status() == com.moddingfromamod.wish.WishStatus.DONE;
                        WishCost cost = wishCost(checkStartedMs, System.currentTimeMillis());
                        done.accept(new MagicianCheck.Step(MagicianCheck.WISH_STEP, ok, (ok ? "Moddy granted the test wish in "
                                + s + " s" : "the test wish ended " + w.status().name().toLowerCase(java.util.Locale.ROOT)
                                + (w.result() == null || w.result().isBlank() ? "" : ": " + w.result())
                                + why(com.moddingfromamod.agent.AgentSession.workerTrustProblem()))
                                + cost.words() + ".", cost.usd()));
                        return;
                    }
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                done.accept(MagicianCheck.Step.fail(MagicianCheck.WISH_STEP, "the wish model did not finish it in "
                        + WISH_TIMEOUT_MS / 60_000 + " minutes"
                        + why(com.moddingfromamod.agent.AgentSession.workerTrustProblem()) + "."));
            }, "mfam-magician-check-wish");
            watch.setDaemon(true);
            watch.start();
        });
    }
}
