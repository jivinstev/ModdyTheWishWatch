package com.moddingfromamod.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishCancel;
import com.moddingfromamod.wish.WishStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>L2 — the plan grade, at the one seam every tool call already passes through.</b>
 * ({@code docs/CHILD_SAFETY_PLAN.md} §3 L2, and the PLAN column of §2.)
 *
 * <p>The wish itself was graded on its own at L1. This is the gap between <i>a wish that was
 * fine</i> and <i>a plan that is not</i>: "make a scary villager" is an ordinary wish, and a plan to
 * pool blood under him and loop a scream is not an ordinary plan.
 *
 * <h2>Why it lives here rather than in PlanWishTool</h2>
 *
 * <p>{@code PlanWishTool.call} is the moment the child is shown the stages —
 * {@code WishFeedback.planned} sends "✨ Here's my plan: …" into his own chat. Returning <b>before
 * the tool body runs</b> is therefore the whole property: a diverted plan is one the child never
 * read. A check inside the tool would be a check after the announcement, which is no check at all.
 *
 * <h2>It refuses; it does not inform</h2>
 *
 * <p>The {@link com.moddingfromamod.marshal.CancelGate} shape, for the reason recorded there: an
 * agent that reads "that was objectionable" and carries on is invisible except as the thing
 * appearing anyway. So the tool body never runs, and the refusal <b>names an alternative</b> —
 * {@code RefusalRedirectTest}'s rule, measured on a refusal that named none and cost 21 s of a 64 s
 * build to an agent improvising its way round.
 *
 * <h2>The decision is pure; only the STOP is not</h2>
 *
 * <p>{@link #decide} touches no Minecraft and is a unit test. {@link #endWish} is the half that has
 * to reach the game — the marshal, the book, the bar, the child — and is deliberately the only
 * method here that does.
 */
public final class SafetyPlanGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(SafetyPlanGate.class);

    /** The only tool this layer grades. Every other tool's child-visible text is L0's job (§3). */
    public static final String GRADED_TOOL = "plan_wish";

    private SafetyPlanGate() {
    }

    /** What the choke point should do about this call. */
    public enum Act {
        /** Run the tool, say nothing. */
        PASS,
        /** Run the tool, then append one line of guidance to its result. */
        NOTE,
        /** Refuse this call; the agent re-plans one stage and calls {@code plan_wish} again. */
        DIVERT,
        /** Refuse, and end the wish through the existing stop machinery. */
        STOP,
        /** The grader could not run. Behaves as PASS, and is logged as its own thing (§4). */
        UNAVAILABLE
    }

    /**
     * One decision, plus what it would have been.
     *
     * @param act      what will actually happen — always {@link Act#PASS} in shadow mode
     * @param assessed what the grade earned, whatever the mode. In {@code on} the two are equal; in
     *                 {@code shadow} this is the prediction and {@code act} is PASS. Kept apart so a
     *                 test can assert the layer THOUGHT the right thing while doing nothing, which
     *                 is the only claim shadow mode can make about itself.
     * @param message  the refusal (DIVERT/STOP) or the guidance line (NOTE); empty otherwise
     * @param verdict  the graded verdict, <b>strictness already applied</b> — for a caller that
     *                 would rather assert on bands than on prose. Deliberately NOT what the log
     *                 carries: the record keeps the RAW grade beside the {@code strictness} it was
     *                 read under, because a base rate measured on the adjusted band would be a
     *                 fact about one machine's dial. This is the verdict the DECISION was taken on;
     *                 that is the verdict the GRADER gave
     */
    public record Decision(Act act, Act assessed, String message, SafetyVerdict verdict) {

        /** Nothing to do, nothing graded — a tool this layer does not look at, or the OFF arm. */
        public static final Decision PASS =
                new Decision(Act.PASS, Act.PASS, "", SafetyVerdict.unavailable());

        /** Should the tool body be skipped? */
        public boolean refuses() {
            return act == Act.DIVERT || act == Act.STOP;
        }
    }

    // ---- the decision ----

    /**
     * Grade this call and say what to do about it.
     *
     * <p>Costs a model round trip, and <b>the band drives the bill</b> — MEASURED live through the
     * real agent and real Haiku (Gate C½, 2026-09-11): an ordinary plan <b>11.5 s / $0.0086</b>,
     * one that leans <b>38.5 s / $0.0213</b>. So §7's "~10 s" and S0's 22.6 s are both the easy
     * case, and the number to budget against is the tail, not the median — a grader thinks longest
     * about exactly the plans this layer exists for.
     *
     * <p>That is spent inside a tool call the agent is already waiting on, in a wish that costs
     * 60–300 s, and nothing on the transport is near a limit: the MCP client's own abort is
     * {@code AgentSession.MCP_CLIENT_TIMEOUT_MS} = <b>900 s</b>, and a tool on this very server
     * already parks <b>110 s</b> every time a worker goes idle
     * ({@code AwaitWishTool.DEFAULT_TIMEOUT_MS}) — so a 38.5 s call is a third of something the
     * transport does routinely, with 23× headroom to the abort.
     *
     * <p>⚠ Both numbers are read from the CONSTANTS, because the comment beside one of them is
     * stale: {@code AgentSession.writeMcpConfig} says "900 s here against AwaitWishTool's 540 s
     * hold", and that field has been 110 s. The argument survives either way, which is exactly why
     * it would have shipped unchecked.
     *
     * <p>The child is not left staring at nothing either — the
     * caller starts {@code ToolNarrator} BEFORE this runs, so he gets the agent's own status line
     * immediately and then {@code plan_wish}'s narration beats (2.5 s / 12 s / 25 s — "Thinking
     * about how to make this…", "Still dreaming it up…", "This is a big idea — working out the
     * pieces"), which were written for a wait that until now never happened, because the tool
     * itself returns in milliseconds. They fit this exactly; past 25 s the last one simply holds.
     *
     * @param tool     the tool being called; anything but {@link #GRADED_TOOL} is
     *                 {@link Decision#PASS}
     * @param args     its arguments, <b>after</b> {@code ArgAliases.normalise} — see the caller
     * @param wishText what the child asked for
     * @param wishId   the short id, for the log
     * @param wishUuid <b>which wish</b>, for the one-shot divert budget; null skips it (see
     *                 {@link SafetyRetryBudget#divertOnce}). Two ids rather than one because they
     *                 answer different questions: the short one is the log's join key, and a
     *                 budget that can END a wish is not keyed on eight hex characters
     */
    public static Decision decide(String tool, JsonObject args, String wishText, String wishId,
                                  UUID wishUuid) {
        if (!GRADED_TOOL.equals(tool) || !ChildSafetyConfig.grading()) {
            return Decision.PASS;
        }
        SafetyGrader.Graded graded;
        try {
            graded = SafetyGrader.gradePlan(wishText, strings(args, "steps"),
                    strings(args, "claims"), string(args, "status"));
        } catch (Throwable e) {
            // A grader that can take a wish down with it is worse than no grader (SafetyGrader's
            // own rule). Anything unexpected here is an outage, not a refusal.
            LOGGER.debug("plan grade failed: {}", e.toString());
            graded = SafetyGrader.Graded.unavailable(0);
        }
        // ⚠ TWO VERDICTS, AND WHICH ONE GOES WHERE IS THE WHOLE OF THIS LINE. `asGraded` is what
        // the grader said; `verdict` is that grade with the parent's dial applied, and every branch
        // below decides on `verdict` -- the same rule `SafetyInputGate.decide` records, for the
        // same reason (a branch there that read the unadjusted grade under `strict` handed the
        // child an empty redirect).
        //
        // THE LOG CARRIES `asGraded`. CS-S1b's rule, stated in `SafetyInputGate.Decision#verdict`
        // and quietly broken here until CS-REVIEW-2: logging the ADJUSTED bands would make every
        // measured base rate a function of one machine's `child_safety_strictness`, so a floor
        // recorded on a `standard` machine could not be compared with a run on a `strict` one --
        // and `tools/safety-arm.py` says in as many words that it does not model strictness
        // because "a report that quietly applied one would be quoting a configuration nobody
        // runs". Nothing is lost: `action` already records what was DONE (decided on the adjusted
        // band) and `strictness` sits in the same record, so the adjusted band is recoverable from
        // the raw one and the raw one is NOT recoverable from the adjusted one.
        SafetyVerdict asGraded = graded.verdict();
        SafetyVerdict verdict = asGraded.adjusted(ChildSafetyConfig.strictness());

        // READ BEFORE SPENDING. Every grade is logged with its retry number, the FINE ones
        // included -- a FINE plan on a wish that was diverted earlier is the evidence that the
        // divert worked, and it is the most interesting record in the file.
        int retry = SafetyRetryBudget.alreadyDiverted(wishUuid) ? 1 : 0;

        Act assessed;
        String message;
        if (verdict.isUnavailable()) {
            // §2: at PLAN, unavailable is "pass, log" and has no closed mode -- the INPUT grade
            // already ran, and ending a wish the child is watching because a subprocess hiccupped
            // would be a refusal about the machine rather than about the wish.
            assessed = Act.UNAVAILABLE;
            message = "";
        } else {
            switch (verdict.overall()) {
                case FINE -> {
                    assessed = Act.PASS;
                    message = "";
                }
                case MAYBE -> {
                    assessed = Act.NOTE;
                    message = guidanceFor(verdict);
                }
                case LIKELY -> {
                    // The one-shot budget, spent HERE rather than at the call site so that "the
                    // second divert stops the wish" is a property of the decision and can be
                    // unit-tested without a marshal, a server or a child.
                    boolean first = SafetyRetryBudget.divertOnce(wishUuid);
                    assessed = first ? Act.DIVERT : Act.STOP;
                    message = first ? divertMessage(verdict, strings(args, "steps")) : stopMessage();
                }
                default -> {
                    assessed = Act.STOP;
                    message = stopMessage();
                }
            }
        }

        boolean enforcing = ChildSafetyConfig.enforcing();
        // WRITTEN BEFORE THE ACTION, in every mode and for every band (§6). A log that recorded
        // only the refusals would make the false-positive rate -- the number this layer lives or
        // dies on -- uncomputable, because a rate needs its denominator.
        // `asGraded`, not `verdict` -- the RAW bands, per the comment above.
        // WHOSE wish, from the run this worker is bound to. `assess` is handed a wish id and
        // never a player, and threading one down from McpProtocol would put a ServerPlayer into a
        // gate that has none today; AgentContext already answers exactly this question for the
        // worker on this thread, and answers "" rather than guessing when it cannot.
        ActionLog.safety(wishId, "plan", asGraded, (enforcing ? "" : "SHADOW_") + assessed.name(),
                graded.latencyMs(), graded.costUsd(), graded.modelId(), graded.raw(),
                GRADED_TOOL, retry, false, false,
                com.moddingfromamod.agent.AgentContext.currentPlayerId(), graded.arm());

        return enforcing
                ? new Decision(assessed, assessed, message, verdict)
                // Shadow acts on NOTHING -- the tool result included, because appending guidance
                // an agent reads is an action.
                : new Decision(Act.PASS, assessed, "", verdict);
    }

    /**
     * Append the MAYBE guidance to a result the tool has produced.
     *
     * <p>§2's PLAN column: a MAYBE plan passes and "the guidance is appended to the tool result".
     * That cannot be done before the call, which is why this layer has two halves rather than one
     * pre-emptive hook. An errored result is left alone — the agent has a real problem to read, and
     * stacking advice on top of it buries the thing it has to act on.
     */
    public static ToolResult annotate(ToolResult out, Decision decision) {
        if (out == null || decision == null || decision.act() != Act.NOTE
                || decision.message().isEmpty() || out.isError()) {
            return out;
        }
        return new ToolResult(out.text() + "\n\n" + decision.message(), false,
                out.imageBase64(), out.imageMime());
    }

    // ---- the words ----

    /**
     * The refusal the agent reads on a DIVERT.
     *
     * <p>Four things, in order, each a measured requirement rather than a flourish: <b>which</b>
     * step (by index AND by its own text, so it cannot be misidentified), <b>why</b> (from the
     * closed reason vocabulary — the agent needs it in order to re-plan, and this is a tool result
     * read by a model, never by the child), <b>what instead</b> (a refusal that names no
     * alternative makes a builder improvise, which is worse than the step objected to), and
     * <b>what to do next</b>.
     *
     * <p>And one thing that is not obvious: it tells the agent <i>not to mention any of this to the
     * child</i>. §5's rule is that a child is never told what was wrong with his wish — naming the
     * category is both a lesson in vocabulary he did not have and an invitation to find out how
     * close he can get. An agent left to its own devices narrates its own refusals, cheerfully.
     */
    static String divertMessage(SafetyVerdict verdict, List<String> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("SAFETY — this plan was not accepted, so plan_wish did NOT run and the child has "
                + "NOT been shown it.\n");
        int i = verdict.stageIndex();
        String step = steps != null && i >= 0 && i < steps.size() ? steps.get(i) : "";
        if (!step.isEmpty()) {
            sb.append("Step ").append(i).append(" (\"").append(step).append("\") is the problem");
        } else if (i >= 0) {
            sb.append("Step ").append(i).append(" is the problem");
        } else {
            // The grader is told to answer -1 when no single step is responsible, and a plan that
            // leans as a WHOLE is a real answer rather than a missing one.
            sb.append("The plan as a whole is the problem");
        }
        String why = why(verdict);
        sb.append(why.isEmpty() ? "" : ": " + why).append(".\n");
        sb.append("Re-plan ").append(step.isEmpty() ? "it" : "that ONE step").append(" as: ")
          .append(alternative(verdict)).append('\n');
        sb.append("Keep the rest of the plan exactly as it is, then call plan_wish again with the "
                + "whole plan.\n");
        sb.append("Do NOT tell the child about this, do not apologise to him and do not mention "
                + "safety — just plan it the other way and carry on.");
        return sb.toString();
    }

    /**
     * The refusal on a STOP: the wish is over.
     *
     * <p>Its tail is deliberately {@code CancelGate.message}'s, in shape and in wording, because
     * the agent is in exactly the state that message describes — {@link #endWish} puts it in the
     * marshal's winding-down set, so every tool it tries next is refused by that gate anyway. Two
     * different sentences for one state would be two chances to misread it.
     */
    static String stopMessage() {
        return "SAFETY — this wish is over. plan_wish did NOT run, nothing was built, and the "
                + "child has already been told.\n"
                + "Do NOT plan it again, do not build anything, do not tidy up and do not explain.\n"
                + "Call await_wish immediately — that is the only tool that will answer you now.";
    }

    /**
     * One line for a MAYBE plan: carry on, keep it cartoon.
     *
     * <p>Per AXIS rather than per reason, because the axis is what the advice differs on and the
     * reasons are an annotation. The agent is told no band and no score: "keep it cartoon" is
     * actionable, "your violence axis is MAYBE" is not.
     */
    static String guidanceFor(SafetyVerdict verdict) {
        SafetyVerdict.Band worst = verdict.overall();
        String advice;
        if (verdict.violence() == worst) {
            advice = "keep it cartoon — big, silly and bloodless, and nothing that would frighten a "
                    + "7-year-old who did not ask to be frightened";
        } else if (verdict.sexual() == worst) {
            advice = "keep it silly rather than grown-up — gross-out and bathroom humour are exactly "
                    + "right, romance is not";
        } else if (verdict.hate() == worst) {
            advice = "keep it kind — goofy rather than mean, and never pointed at a real person";
        } else {
            advice = "keep it Minecraft — in-game potions, TNT and cartoon weapons are fine, "
                    + "real-world ones are not";
        }
        return "NOTE (build this plan as it stands — this is not a refusal): " + advice + ".";
    }

    /** The closed reason vocabulary, rendered for a reader. */
    private static String why(SafetyVerdict verdict) {
        List<String> out = new ArrayList<>();
        for (SafetyVerdict.Reason r : verdict.reasons()) {
            out.add(r.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        return String.join(", ", out);
    }

    /**
     * What to do instead.
     *
     * <p>The grader's own {@code redirect} when it gave a usable one — it saw the plan and we did
     * not — and otherwise a generic that is still an ALTERNATIVE rather than a prohibition. Never
     * an empty string: that is the failure this whole message exists to avoid.
     */
    private static String alternative(SafetyVerdict verdict) {
        return verdict.redirectIfUsable().orElse(
                "the cartoon version of the same idea — funny or spooky instead of realistic, no "
                + "blood, nothing real-world, and nothing aimed at a real person");
    }

    // ---- the stop, which is the only part that touches the game ----

    /**
     * End the wish through the machinery that already ends wishes.
     *
     * <p>Order matters and is not arbitrary. The marshal is cancelled <b>first</b>, on this thread,
     * so the worker is in the winding-down set before anything else can go wrong: if the game
     * thread is busy and the child's half never lands, the agent is still refused by
     * {@code CancelGate} on its next call and the wish still ends. The other order can leave an
     * agent building happily on against a wish nobody is watching, which is the worst shape this
     * design can produce.
     *
     * <p>{@link WishStatus#REJECTED} rather than {@code STOPPED}: its own documentation reads
     * "refused before doing anything … unsafe", while STOPPED means <i>the child stopped it</i>,
     * which is not what happened. §0 records that REJECTED has never once been set.
     */
    public static void endWish(Marshal marshal, WishRun run) {
        if (marshal == null || run == null) {
            return;
        }
        UUID wishId = run.wishId();
        UUID playerId = run.playerId();
        marshal.cancel(wishId, CancelMode.STOP, "", "");
        // This wish is over, so its budget is dead weight. Tidiness only: SafetyRetryBudget is
        // bounded precisely because a wish can end half a dozen other ways that never reach here.
        SafetyRetryBudget.forget(wishId);
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return Boolean.FALSE;
                }
                WishCancel.clearHud(playerId, wishId, marshal);
                WishBook.get(server).complete(wishId, WishStatus.REJECTED,
                        "Stopped at the planning step by the safety layer.");
                ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                if (p != null) {
                    p.sendSystemMessage(Component.literal(SafetyWords.sayPlanStopped())
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)));
                }
                return Boolean.TRUE;
            });
        } catch (Throwable e) {
            // Nobody online, no world, a busy server: the wish is still over, which is the part
            // that matters. A stop that threw would hand the agent a broken tool call instead of
            // the message telling it to park.
            LOGGER.debug("could not tell the child the plan was stopped: {}", e.toString());
        }
        ActionLog.wish(run.text(), "BLOCKED", "PLAN", run.shortId(),
                playerId == null ? "" : playerId.toString());
    }

    // ---- reading the arguments ----

    /** A string-array field, in order, with anything that is not a string dropped. */
    static List<String> strings(JsonObject args, String key) {
        List<String> out = new ArrayList<>();
        if (args == null || !args.has(key) || !args.get(key).isJsonArray()) {
            return out;
        }
        JsonArray arr = args.getAsJsonArray(key);
        for (JsonElement e : arr) {
            if (e != null && e.isJsonPrimitive()) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    /** A string field, or empty. */
    static String string(JsonObject args, String key) {
        return args != null && args.has(key) && args.get(key).isJsonPrimitive()
                ? args.get(key).getAsString() : "";
    }
}
