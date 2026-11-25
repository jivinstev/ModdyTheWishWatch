package com.moddingfromamod.agent.tools;

import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.wish.Wish;

/**
 * Park here until a child makes a wish the game can't grant by itself.
 *
 * <p>This is the whole latency story. The old path was a shell script polling a log file every 5
 * seconds, behind a 45-second rate limit, which then <b>spawned a fresh agent</b> — around 40 s of
 * start-up before it could take its first action. Total: 20–90 s from wish to work, and no memory
 * of anything that came before.
 *
 * <p>Now an agent sits blocked in this call. A wish is handed over in <b>0.1 ms</b> (measured), and
 * because the same session loops back in here, it keeps everything it has already learned this run.
 *
 * <h2>Two things that will break the game if you change them carelessly</h2>
 * <ol>
 *   <li><b>The wait must not touch the server thread.</b> {@link AgentBridge#onServer} hops onto the
 *       game thread — parking there for 55 s would freeze Minecraft outright. So the block happens
 *       first, on the HTTP handler thread, and only <i>afterwards</i> do we hop across to read
 *       world state. Never fold the two together.</li>
 *   <li><b>This holds one HTTP thread for its whole timeout.</b> The pool must stay comfortably
 *       larger than the number of parked agents, or a second tool call has nowhere to run and the
 *       agent deadlocks against itself.</li>
 * </ol>
 */
public final class AwaitWishTool implements McpTool {

    /**
     * <b>How long a parked worker holds this call — and it is the pool's biggest running cost.</b>
     *
     * <p>This was 55 s, with {@code MAX} at 120 s, on the reasoning that it should be "long enough
     * to be efficient, short enough that a wedged call self-heals". The first half of that was
     * badly wrong about the price. When this returns, the agent takes a whole turn to call it
     * again, and that turn re-reads its entire context. Measured on a real pool:
     *
     * <pre>
     *   359bad69  parked 00:17:57  polled again 00:18:53   write 110  read 59,554
     *   8a3c0edf  parked 00:17:39  polled again 00:18:35   write 127  read 51,960
     *   96efdeda  parked 00:17:55  polled again 00:18:51   write 111  read 81,570
     * </pre>
     *
     * 56 s apart, ~$0.033 a poll. At 55 s that is ~65 polls an hour, <b>$2.16 per idle worker per
     * hour</b> — ten times what booting one costs, forever, for doing nothing.
     *
     * <h2>Nothing outside this file ever required 55 s, and that was measured too</h2>
     *
     * <p>Both numbers were ours. The CLI's own limits, checked against the real binary rather than
     * read from documentation:
     *
     * <ul>
     *   <li>its hard cap on a tool call is {@code Integer.MAX_VALUE} ms — effectively none;</li>
     *   <li>its default per-call timeout is <b>60 s</b> (measured: <i>"The tool call timed out after
     *       60s"</i>), which is why 55 s worked and 120 s would NOT have);</li>
     *   <li>a per-server {@code timeout} key in the mcp.json overrides it — measured binding, and
     *       {@link com.moddingfromamod.agent.AgentSession} now writes one.</li>
     * </ul>
     *
     * <p><b>The real obstacle was never a timeout.</b> Given a long one, the CLI moves the call to
     * a background task and the agent's turn ENDS — <i>"The tool call is still running — it was
     * moved to the background"</i> — which leaves the worker deaf, exactly the state is about.
     * {@code CLAUDE_AUTO_BACKGROUND_TASKS=0} disables that, and {@code PromptCache} sets it. With
     * both in place a call was held to completion for <b>200 s and then 400 s</b>, the agent
     * finishing normally each time.
     *
     * <h2>⚠ THE CEILING IS 121 SECONDS, and it is not a timeout — measure before raising this</h2>
     *
     * <p>9 minutes was tried first and does not work. Handed a call it considers long-running, the
     * CLI moves it to a background task and ENDS the agent's turn — <i>"The tool call is still
     * running — it was moved to the background"</i>. The worker then stops being a listener and
     * starts poking at a task handle instead, which is both a worse loop and the deaf state of
     *. Measured in a real game at <b>121 s</b> after the call began, twice, and again in a
     * standalone {@code --bg} probe at 121 s.
     *
     * <p>{@code CLAUDE_AUTO_BACKGROUND_TASKS} does <b>not</b> save you here. It genuinely works in
     * {@code -p} mode — calls were held to completion at 200 s and 400 s with it off — but a
     * {@code --bg} agent backgrounds at 121 s regardless, tested with both {@code 0} and
     * {@code false}. The mod runs {@code --bg}, so 121 s is the real wall. It is left set because
     * it is free and correct for the mode where it applies.
     *
     * <p>So <b>110 s</b>: as much of that window as can be taken without touching the edge. ~33
     * polls an hour instead of ~65, about <b>$1.08 per idle worker per hour</b> against $2.16 —
     * half. The other half of the saving comes from not running workers nobody asked for
     * ({@link com.moddingfromamod.marshal.PoolPlan}); together a one-worker pool costs $1.08/h
     * where three parked workers cost $6.48/h.
     *
     * <p>The mcp.json still sets a 900 s client timeout. That is deliberate and not contradictory:
     * this server returns first at 110 s in every normal case, so the client abort never fires, and
     * if this server ever fails to return, backgrounding at 121 s is the self-heal the original
     * 55 s comment was reaching for.
     *
     * <p>⚠ Each parked worker holds one HTTP thread for this whole duration — see
     * {@code McpHttpServer.HTTP_THREADS}. That constraint gets tighter the longer this is.
     */
    private static final long DEFAULT_TIMEOUT_MS = 110_000L;

    /** Also under the 121 s backgrounding wall — an agent asking for more would fall off it. */
    private static final long MAX_TIMEOUT_MS = 115_000L;

    /** Extra wishes reported alongside the one that woke us, so a backlog isn't invisible. */
    private static final int BACKLOG_LIMIT = 5;

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(AwaitWishTool.class);

    private final WishQueue queue;

    /** Production: the one queue the whole process shares. */
    public AwaitWishTool() {
        this(WishQueue.global());
    }

    /**
     * Injected queue.
     *
     * <p>Not merely for tidiness: tests that park on a shared global queue steal each other's
     * wishes when the runner executes them concurrently, which showed up immediately as one test
     * receiving another's wish text. Each test gets its own channel.
     */
    public AwaitWishTool(WishQueue queue) {
        this.queue = queue == null ? WishQueue.global() : queue;
    }

    @Override
    public String name() {
        return "await_wish";
    }

    @Override
    public String description() {
        return "Wait for the next wish the game could not grant on its own. This BLOCKS until a "
                + "child actually wishes for something (or until it times out), so it costs "
                + "nothing to sit here. THIS IS YOUR MAIN LOOP: call await_wish, handle whatever "
                + "comes back, then call await_wish again — immediately, every time, forever. If it "
                + "returns 'no wish', just call it again. Never stop looping and never wait to be "
                + "asked.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.prop(Schemas.object(), "timeoutSeconds", "integer",
                "How long to wait before giving up and letting you loop, default 110. Leave it alone: every extra loop costs a full turn, and above ~115 the call is moved to the background and you stop listening.");
    }

    @Override
    public ToolResult call(JsonObject args) {
        long timeout = DEFAULT_TIMEOUT_MS;
        if (args != null && args.has("timeoutSeconds")) {
            try {
                timeout = Math.max(1_000L,
                        Math.min(MAX_TIMEOUT_MS, args.get("timeoutSeconds").getAsLong() * 1000L));
            } catch (RuntimeException ignored) {
                timeout = DEFAULT_TIMEOUT_MS;
            }
        }

        // ── STEP 0 — REFUSE IF THIS WORKER IS STILL HOLDING A WISH.
        //
        // Marshal.claim() has always forbidden a busy worker taking a second wish, and says so in
        // its own comment: "handing it a second is how one agent ends up interleaving two wishes
        // with no yield point, which is the design this rejects." This tool never consulted it —
        // it blocks on the QUEUE and calls assign() directly.
        //
        // Measured, world "daddus moddy 4", 2026-08-14: the agent called await_wish FOUR times
        // while still building the factory (23:20:09, 23:20:12, 23:21:04, 23:21:09), and a second
        // wish landed on it at 23:22:00 with the first not finished until 23:25:26. byWorker holds
        // one entry per worker, so the second assignment OVERWROTE the first — which is why the
        // child's progress bar tracked the wrong wish.
        //
        // REFUSED HERE, ABOVE queue.await(), AND NOWHERE ELSE. This is the only point in the path
        // where nothing has been dequeued yet, so a refusal cannot lose a wish: it stays in the
        // queue for a genuinely free worker, or for this one the moment it finishes. Refusing after
        // the await -- in assign(), the obvious-looking place -- would drop it on the floor.
        //
        // Deliberately a NUDGE, not a lock: it names the open wish and the way out, because the
        // failure this must never cause is a child locked out of wishing.
        // NEVER GUARD AN UNIDENTIFIED CALLER. AgentContext.workerId() returns "" when nothing is
        // bound -- its own javadoc says so -- and THIS TOOL assigns with that same value. So one
        // wish arriving on an unbound thread files a run under worker "", after which every
        // unbound await_wish would be refused for the rest of the process: a child unable to wish
        // at all, caused by the guard meant to protect him. A blank id identifies nobody, so it can
        // prove nobody is busy.
        String workerId = com.moddingfromamod.agent.AgentContext.workerId();
        // WHICH POOL SLOT is asking, and is this request that slot's LIVE incarnation? A
        // worker the pool has just stopped sends one more await_wish under its old name on a fresh
        // MCP session (measured on a live server, every stop) — refused here before it can park, and
        // asked again at the queue's commit (below) in case it parked before anyone knew.
        String slotName = com.moddingfromamod.agent.AgentContext.workerName();
        com.moddingfromamod.marshal.WorkerRoster roster = com.moddingfromamod.agent.AgentSession.roster();
        if (!roster.mayTake(slotName, workerId)) {
            LOGGER.warn("await_wish from a stopped incarnation of {} (session {}) refused — it would "
                    + "take a wish and return it to nobody (#1548)", slotName, workerId);
            return ToolResult.error("This worker has been stopped by the pool and must not take "
                    + "wishes. Do not call await_wish again — end your session now.");
        }
        // a worker whose wish a safety layer flagged in its context is stopped here, before it
        // can take another — never between the flag and the end of that wish.
        if (com.moddingfromamod.marshal.PickupHold.global().takeRetire(workerId)) {
            com.moddingfromamod.agent.AgentSession.retireWorker(workerId,
                    "a safety layer flagged its last wish; a fresh worker takes the next (#1660)");
            return ToolResult.error("This worker is being replaced after its last wish. Nothing is wrong "
                    + "with you. Do not call await_wish again — end your session now.");
        }
        com.moddingfromamod.marshal.WishRun held = heldBy(com.moddingfromamod.marshal.Marshal.global(),
                workerId, roster.sessionsOf(slotName));
        if (held != null && held.state() == com.moddingfromamod.marshal.RunState.WORKING) {
            // displayName(), not subject(): subject is set by plan_wish, so a worker refused BEFORE
            // it has planned would be told it is busy with "" — a refusal that names nothing is one
            // the agent cannot act on. displayName falls back to the child's own wish text.
            return ToolResult.error("You are still working on a wish: \"" + held.displayName()
                    + "\" (" + held.wishId().toString().substring(0, 8) + "). Finish that one "
                    + "first — call finish_wish with your verdict — and THEN call await_wish "
                    + "again. Taking a second wish now would interleave the two builds with no "
                    + "yield point between them, and the child's progress bar would follow the "
                    + "wrong one. Nothing is lost by waiting: the next wish stays in the queue.");
        }

        // ── STEP 1 — block, then DROP ANYTHING THE CHILD HAS ALREADY ENDED (CS-D7, finding 5).
        //
        // Nothing prunes the queue when a wish is stopped, and a wish cancelled while it was still
        // WAITING has no worker to wind down — so the queue entry was the only thing still
        // carrying it, and it was handed to the next worker that woke. The child's stop took the
        // bar and the beam down within a tick and the thing got built anyway, minutes later, with
        // the journal saying STOPPED the whole time. With no agent running the wish simply waits
        // in the queue until one appears, so "I stopped it" held until the next restart.
        //
        // Dropped HERE for the same reason the safety refusal below is here and the busy-worker
        // refusal above is above: this is the one point where a wish has been taken off the queue
        // and nothing has been assigned, so dropping one can neither lose a wish nor half-assign
        // it. It is also the one point that sees every path a wish can reach an agent by,
        // including the three that skip WishService.submit.
        //
        // LOOP, don't return. A stale entry is not an answer to give a parked worker — it would
        // spend a whole turn calling back for a queue that may have had a real wish in it all
        // along. So the remaining budget is re-waited on the same deadline, which is why the
        // timeout is a deadline rather than a duration from here on.
        final long deadlineMs = System.currentTimeMillis() + timeout;
        Wish dequeued = null;
        WishQueue.Taken picked = null;
        while (dequeued == null) {
            long remaining = deadlineMs - System.currentTimeMillis();
            if (remaining > 0) {
                try {
                    // Deliberately outside AgentBridge: see the class note. Parking the server
                    // thread here would freeze the game for the whole timeout.
                    //
                    // WHO is asking, not just how long for: under a forced WishAffinity arm the
                    // queue only hands this worker a wish it is allowed to have. Under the default
                    // (OFF) this is the old call exactly — the first poll returns ANYONE and
                    // nothing is ever held aside.
                    picked = queue.await(remaining, workerId,
                            () -> roster.mayTake(slotName, workerId));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.ok("Interrupted — call await_wish again.");
                }
            }
            if (remaining <= 0 || picked == null || picked.isEmpty()) {
                return ToolResult.ok("No wish arrived in time. Nothing is wrong — call await_wish "
                        + "again straight away to keep listening.");
            }
            com.moddingfromamod.wish.WishStatus over = alreadyOver(picked.wish());
            if (over == null) {
                dequeued = picked.wish();
                break;
            }
            // NOT told to the agent, and nothing is said to the child either: he pressed stop a
            // while ago, was told so at the time, and has had no bar since. There is nobody for a
            // message about this to be news to — which is precisely why it needs a log line, or
            // the drop is the kind of thing that gets diagnosed as a dead queue.
            LOGGER.info("dropped wish {} at the dequeue: the book says {} — {}",
                    picked.wish().shortId(), over, picked.wish().text());
        }
        // EFFECTIVELY FINAL from here on, because the lambdas below capture them. The loop above
        // needs to reassign, so the two are separate names rather than one mutable local.
        final Wish wish = dequeued;
        final WishQueue.Taken taken = picked;
        // THIS WORKER HAS IT from here, before the gates and the brief wait below. Until take() binds it,
        // the marshal records the pickup, so the claim ceiling does not cancel as "never claimed" a wish a worker
        // holds, and the supervisor does not count this worker as deaf (or start a spare for it).
        com.moddingfromamod.marshal.Marshal.global().pickedUp(workerId, wish.id());

        // ── THE PICKUP GATES: safety, the world's rules, the wish bank, the cache router ──────────
        //
        // All four decide BETWEEN THE AWAIT AND THE ASSIGN (decideGates, which carries each one's
        // reasoning).: when the graders are still running, waiting here held the worker idle
        // for 5.4–7.4 s on every wish for a verdict that changed nothing 99% of the time — so such a
        // wish is handed over NOW, behind the pickup ratchet (PickupHold): the agent may look, and
        // every tool that speaks to the child or changes the world parks until the verdicts land.
        // A verdict that refuses or rewords the wish takes it back from this worker, which is
        // stopped, and a fresh one is handed the redirect — so "the redirected text is what the
        // agent gets" holds exactly as before. Every other case is decided here, as it always was.
        // (2026-11-18): a wish that will carry a brief is never handed over ahead of its grade.
        // The child's own grade is decided first (decideGates), so a refused or redirected wish never reaches
        // a worker with a brief, exactly as without briefs. willBrief waits as the hand-over would anyway.
        final boolean speculative = !com.moddingfromamod.reference.WishBriefs.willBrief(wish.id())
                && speculative(wish);
        com.moddingfromamod.world.WorldRules worldRules = com.moddingfromamod.world.WorldRulesService.current();
        com.moddingfromamod.safety.SafetyInputGate.Decision safety;
        com.moddingfromamod.world.WorldRulesGate.Decision world;
        String wishText;
        if (speculative) {
            safety = com.moddingfromamod.safety.SafetyInputGate.Decision.pass(wish.text());
            world = com.moddingfromamod.world.WorldRulesGate.pass(wish.text());
            wishText = wish.text();
            com.moddingfromamod.marshal.PickupHold.global().open(wish.id());
            LOGGER.info("wish {}: handed over before its grades landed (#1660) — the pickup ratchet holds "
                    + "every tool that acts until they do", wish.shortId());
        } else {
            Gates gates = decideGates(wish);
            if (gates.refusal() != null) {
                com.moddingfromamod.reference.WishBriefs.forget(wish.id());   // its brief is never used
                return ToolResult.error(gates.refusal());
            }
            safety = gates.safety();
            world = gates.world();
            wishText = gates.wishText();
        }
        final boolean worldChangedText = world.act() == com.moddingfromamod.world.WorldRulesGate.Act.SPLIT;
        // a named character's wish is handed over as a nameless brief, once its look has passed the
        // injection check and the child-safety grade (WishBriefs; started at submit, enforced HERE). Any other
        // outcome, and every redirect, hands over `wishText` exactly as before. Before an earlier issue's re-check below,
        // so a stop while this waits is still caught.
        final String workerText = com.moddingfromamod.reference.WishBriefs.atHandOver(wish.id(), wish.text(),
                wishText, wish.shortId());

        // ──: WAS IT STOPPED WHILE THE GATES DECIDED? ───────────────────────────────────────────
        //
        // The book was asked once, at the dequeue, and the gates above can wait seconds on a grade. A
        // stop in that window used to leave a STOPPED run that the old submit-then-assign RE-OPENED as
        // a fresh run (or bound nothing at all), so the wish was granted after the child was told
        // "I stopped and put it back!" — live, a top hat on an owl 24 s after /wish undo. So: the book
        // again, and then the bind as ONE step in the marshal (Marshal.take), which refuses a stopped
        // wish and winds this worker down. Dropped here, before anything is recorded against it.
        //
        // Record who took it. The QUEUE decided the order, not the marshal — this is the marshal
        // writing down what happened, which is what makes "stop this one" and "which wish is this
        // worker on?" answerable at all. Submitted inside take() because a wish can reach an agent by
        // paths that never went through WishService (a rejection re-offer, a reloaded journal entry).
        com.moddingfromamod.wish.WishStatus overNow = alreadyOver(wish);
        com.moddingfromamod.marshal.Marshal marshal = com.moddingfromamod.marshal.Marshal.global();
        com.moddingfromamod.marshal.WishRun bound = overNow != null ? null
                : marshal.take(workerId, wish.id(), wish.playerId(), wishText);
        com.moddingfromamod.marshal.WishRun current = marshal.run(wish.id());
        if (overNow != null || current == null || current.state().isFinished()) {
            if (speculative) {
                com.moddingfromamod.marshal.PickupHold.global().forget(wish.id());
            }
            if (overNow != null) {
                marshal.droppedAfterTheGates(workerId, wish.id());   // take() was never asked: wind down here
            }
            LOGGER.info("dropped wish {} after its pickup gates: the child stopped it before a worker "
                    + "was bound ({}) — {}", wish.shortId(),
                    overNow != null ? overNow : current == null ? "no run" : current.state(), wish.text());
            return ToolResult.ok("The wish you were about to be given was stopped by the child before you "
                    + "started it. Do nothing for it, do not mention it — call await_wish again straight "
                    + "away to keep listening.");
        }
        final com.moddingfromamod.marshal.WishRun run = bound != null ? bound : current;
        // A REDIRECTED WISH IS NAMED AFTER THE REDIRECT, on the bar and everywhere else the run's
        // display name reaches. `submit` returns the EXISTING run when there is one (there almost
        // always is -- WishService made it), so the text argument above is ignored on that path and
        // the subject is the only way to say it. Nothing quietly substituted: the child read this
        // exact sentence a moment ago.
        if (safety.redirectShown() || worldChangedText) {
            run.subject(wishText);
        }

        // THIS WORKER NOW OWNS THE THING IT IS ABOUT TO CHANGE. Recorded on the take rather than
        // only at finish_wish, because a refinement names an existing artifact and the NEXT wish
        // about it must route against who is working it now, not who worked it last time.
        com.moddingfromamod.marshal.ArtifactAffinity affinity =
                com.moddingfromamod.marshal.ArtifactAffinity.global();
        // ⚠ READ THE OWNER BEFORE OVERWRITING IT. `remember` below makes THIS worker the owner, so
        // asking afterwards returns the worker itself — a field that always agrees with itself and
        // can never show the disagreement it exists to show.
        String ownerBefore = affinity.ownerOf(taken.artifactId());
        affinity.remember(taken.artifactId(), workerId);

        // Stamp the intent now being granted, so any Artifact the agent persists while handling this
        // wish (a datapack, a learned template, …) is attributed to it — the Provenance Contract.
        com.moddingfromamod.provenance.ProvenanceContext.begin(wish.text());

        if (speculative) {
            // The verdicts are decided on a thread of their own, never on this worker's turn.
            resolveLater(queue, wish, workerId);
        }
        // THE CLAIM IS A STAGE THE CHILD CAN SEE: his bar moves from "Reading your wish…"
        // to "Working out how to make it…" — the one beat of the first ~20 s the mod observes.
        com.moddingfromamod.wish.WishProgress.claimed(wish.id());

        // WHICH ARM GRANTED THIS, written at the source. Inferring it afterwards from which file
        // the results were saved into is exactly how came to be misfiled; and `routing` is the
        // half that says whether the arm actually HELD, since a wish that fell back is not evidence
        // about the arm it is labelled with.
        // THE SHORT ID, like every other record kind. `wish` and `tool_call` have always been
        // stamped with WishRun.shortId(); these two were added carrying the full UUID, so they
        // could not be joined to anything and the arm's own tool calls were invisible. Fixed at
        // the source rather than only in the scorer, so the log is internally consistent.
        // …AND WHAT THE ROUTER COULD SEE: the owner as it stood BEFORE this take, and
        // how many workers this world has ever offered a wish to.
        com.moddingfromamod.agent.ActionLog.routed(wish.shortId(), workerId,
                taken.artifactId(), taken.routing(), ownerBefore, affinity.workersSeen());

        // STEP 2 — only now is it safe to look at the world.
        // PEEKED, not taken. Draining here starves every other worker in the pool: the first to
        // wake empties the queue and the rest long-poll an empty channel forever.
        List<Wish> backlog = queue.peekRest(BACKLOG_LIMIT);
        int dropped = queue.droppedCount();

        StringBuilder sb = new StringBuilder();
        sb.append("A child just wished for something the game could not do by itself.\n\n");
        // THE NOTE GOES WITH THE TEXT. It was computed from the child's own words at submit
        // time, so on a redirected wish it is a second copy of what the redirect replaced.
        describe(sb, wish, workerText, safety.redirectShown() || worldChangedText ? "" : wish.result());
        for (Wish extra : backlog) {
            sb.append("\nAlso waiting (ANOTHER agent may be taking this one — do not start it "
                    + "unless it is still listed by list_wishes when you get there):\n");
            // Its OWN text: a backlog entry has not been graded yet, so there is no redirect to
            // show and nothing to substitute. Whoever takes it goes through this same gate.
            describe(sb, extra, extra.text(), extra.result());
        }
        if (dropped > 0) {
            sb.append("\n(").append(dropped).append(" earlier notification(s) overflowed — call")
              .append(" list_wishes to be sure you haven't missed any.)\n");
        }
        // What's actually around, with CURRENT sizes and health, plus saved buildings. Entity
        // handles alone weren't enough: they named things without saying what state they were in,
        // and they never mentioned buildings at all — so "make the castle bigger" arrived with a
        // list containing no castles. Safe to hop to the server thread here; the blocking wait is
        // already over.
        try {
            sb.append('\n').append(AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                // Claim this wish as RUNNING now that the agent has actually taken it. This moves it
                // out of the PENDING backlog (so it can never be mis-announced as a "queued" wish),
                // and lets the progress HUD keep reassuring the child while the agent works instead
                // of hitting the give-up timeout meant for wishes that were never picked up.
                if (server != null) {
                    com.moddingfromamod.wish.WishBook.get(server).complete(
                            wish.id(), com.moddingfromamod.wish.WishStatus.RUNNING, wish.result());
                }
                var player = com.moddingfromamod.agent.ActingChild.of(server);
                var level = player != null ? player.level() : server.overworld();
                var origin = player != null ? player.position()
                        : net.minecraft.world.phys.Vec3.atBottomCenterOf(level.getSharedSpawnPos());
                return com.moddingfromamod.agent.Surroundings.describe(level, origin,
                        run == null ? null : run.sawWhenWished());   // §3: in view when he wished
            }));
        } catch (AgentBridge.Unavailable ignored) {
            // A wish still has to reach the agent even if the world is momentarily busy.
        }

        // WHAT THE LAST AGENT LEARNED ABOUT THE THING HE IS TALKING ABOUT, handed over on the wake
        // itself rather than fetched. Fetching it would cost a turn, and a turn is the unit this
        // whole design exists to remove -- and the record's real value is not latency anyway, it is
        // that the context survives a worker dying, a restart, and tomorrow morning.
        //
        // Rung 0 picks WHICH record, which is the same free lookup that resolves "give King Kroak
        // fire breath" -- so the two features pay for each other.
        // WHAT THE LAST AGENT LEARNED, under whichever context mode this run is in. OFF is the old
        // five-field summary; ON and SHUFFLED are the turn journal rendered inside a budget.
        var ctx = com.moddingfromamod.wish.HandoverBrief.contextFor(wish.text(), taken.artifactId());
        String handover = ctx.isEmpty()
                ? com.moddingfromamod.wish.HandoverBrief.forWish(wish.text())
                : ctx.text();
        if (!handover.isEmpty()) {
            sb.append('\n').append(handover);
        }
        // WHAT THIS WAKE ACTUALLY CARRIED, measured rather than estimated. Criterion 6 of the
        // wish-context plan caps the injected block, and a cap checked against characters × 4 is
        // not a check -- the plan lists that substitution as threat 7. Logged even when the block
        // is empty, because "nothing was handed over" is the baseline the treatment is judged
        // against and an absent record is indistinguishable from an unrecorded one.
        com.moddingfromamod.agent.ActionLog.context(wish.shortId(), taken.artifactId(),
                com.moddingfromamod.marshal.WishContextMode.current().label(),
                ctx.isEmpty() ? (handover.isEmpty() ? 0 : 1) : ctx.turnsShown() + ctx.turnsSummarised(),
                handover.length(), ctx.turnsDropped(), ctx.rejectionsDropped());

        // THE MAYBE GUIDANCE, riding the wake the agent was going to read anyway (§2's INPUT
        // column, deferred here from CS-S4 which owns the standing prompt section but not this
        // file). Empty in every other band and in shadow mode, where acting on a grade — and a
        // line a worker builds differently for is acting on it — is the one thing forbidden.
        //
        // BEFORE the "decide where it appears" block on purpose: that block is the instruction
        // the agent acts on first, and advice that arrives after it reads as an afterthought.
        if (!safety.guidance().isEmpty()) {
            sb.append('\n').append(safety.guidance()).append('\n');
        }
        // THE WORLD'S RULES IN THE BRIEF: on every wish on a world that denies anything —
        // e.g. a steer to base-local tools — plus this wish's own note (split, clamp, unsure).
        String rulesBrief = com.moddingfromamod.world.WorldRulesGate.brief(worldRules);
        if (!rulesBrief.isEmpty()) {
            sb.append('\n').append(rulesBrief).append('\n');
        }
        if (!world.briefNote().isEmpty()) {
            sb.append(world.briefNote()).append('\n');
        }

        sb.append("\nDECIDE WHERE IT APPEARS FIRST: the wish is already anchored to the spot the "
          + "child was pointing the watch at, and that's where the sparkles are gathering. Call "
          + "conjure_at right away to LOCK that spot (or move it — e.g. up into the sky for "
          + "'in the air') and set the sparkles there — IN THE SAME TURN as plan_wish, as "
          + "parallel tool calls, not a turn of its own. Then just build: build_structure / "
          + "etc. land at the locked spot automatically, so the thing appears exactly "
          + "where the child is looking even if they walk off (no more mid-air surprises).");
        sb.append("\nAs you work, keep the child's status bar alive with set_status — say what "
          + "you're making the moment you start, and update it at every new step (dreaming → "
          + "building → checking → finishing) in a few pithy, magical words. Grant it, tell the "
          + "child with notify_player, then TEACH it with add_template (commands that worked, his "
          + "words, an icon) so next time it is a button — then call "
          + "await_wish again immediately.");
        // on a briefed wish nothing in the wake may name the character (the backlog, the surroundings).
        return ToolResult.ok(com.moddingfromamod.reference.WishBriefs.veil(wish.id(), sb.toString()));
    }

    /**
     * Await the input grade, combine it with the lexicon, log it, and say what to do.
     *
     * <h2>Two timeouts, and conflating them throws the answer away</h2>
     *
     * <p>{@link com.moddingfromamod.safety.ChildSafetyConfig#inputBudgetMs()} is how long THIS
     * side is willing to hold a child's wish; {@code SafetyGrader.CliTransport.TIMEOUT_MS} is how
     * long the grade is worth running. They are deliberately different numbers. Measured in CS-S0:
     * benign wishes came back in 7.9–9.4 s and the two that leaned objectionable took 16–25 s, so
     * <b>a short budget loses exactly the grades that matter</b>; CS-S1b then measured p95 at
     * 33.6 s on the objectionable rows, which is why the default is 40 s. When the budget expires
     * the grade KEEPS RUNNING on its own daemon thread and lands in the memo, where a re-wish of
     * the same text picks it up instantly. That is why the record below says which of the two
     * happened rather than writing one UNAVAILABLE for both: "the grader is down" and "the grader
     * was slower than this side's patience" want completely different fixes, and a log that cannot
     * tell them apart cannot suggest either.
     *
     * <h2>The lexicon runs HERE too, and it is not a duplicate</h2>
     *
     * <p>{@code WishService.submit} already checked it — but the three re-wish paths never went
     * through {@code submit}, and this is the one point that sees all of them. It is a regex sweep
     * over one short string (microseconds), so re-running it is cheaper than threading the earlier
     * answer through a queue, and it cannot go stale against an edited override.
     *
     * <p>Never throws and never leaves the wish unassigned: every failure path here is a log line
     * and a PASS.
     */
    /**
     * <b>The wish this worker is still holding, if any</b> — asked of its current MCP
     * session AND of every session its slot has been heard on.
     *
     * <p>The marshal files a run under the session that took it, and a session can change under a
     * worker (a re-initialise after the TTL). Asked of the current session alone, a worker that
     * re-initialised mid-wish would look free and be handed a second wish while the first is still
     * open — two claims on one worker, which is the shape was reported as.
     *
     * <p>Pure apart from the marshal it is handed, so a unit test drives it directly.
     */
    public static com.moddingfromamod.marshal.WishRun heldBy(com.moddingfromamod.marshal.Marshal marshal,
                                                             String workerId,
                                                             java.util.Collection<String> slotSessions) {
        if (marshal == null) {
            return null;
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (workerId != null && !workerId.isBlank()) {
            ids.add(workerId);
        }
        if (slotSessions != null) {
            for (String id : slotSessions) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        for (String id : ids) {
            com.moddingfromamod.marshal.WishRun run = marshal.runForWorker(id);
            if (run != null && run.state() == com.moddingfromamod.marshal.RunState.WORKING) {
                return run;
            }
        }
        return null;
    }

    /**
     * <b>Has this wish already ended?</b> — the status if so, or null to hand it over.
     *
     * <p>The BOOK is asked and not the marshal, and the two really can disagree: a marshal run is
     * in-memory and forgotten at world stop, while the book is the durable record a grown-up reads
     * and the thing {@code /moddy stop}, {@code /moddy no} and {@code /moddy redirect} all write. It
     * is also the only one of the two that a re-wish path updates <i>before</i> it offers the wish,
     * which is what makes {@link com.moddingfromamod.wish.WishStatus#isFinished()} the right
     * predicate: both paths put a finished wish back to {@code PENDING} first, so a refused or
     * re-aimed wish passes and a stopped one does not.
     *
     * <p><b>Fails OPEN, deliberately.</b> No server, no world, an unreachable bridge or a wish the
     * book has never heard of all answer "hand it over". A wish that reaches an agent it should not
     * have is the bug being fixed here; a wish silently dropped because the book could not be
     * consulted is the child losing work he asked for and being told nothing, which this codebase
     * treats as the worse failure everywhere else it comes up ({@code Marshal.workerDied}: "the
     * child made it and nothing about an agent falling over is his problem").
     *
     * <p><b>It costs one hop to the server thread</b>, which is one tick — and it is the second hop
     * this call already makes, since the wake text is built inside {@code AgentBridge.onServer} a
     * few lines below. Against a wish that takes minutes, and a model turn that takes seconds, a
     * tick is not worth reading the book off-thread for: {@code WishBook}'s methods are
     * synchronised, but the one write on this path goes through the bridge and having the read take
     * a different route is how two answers about the same wish start disagreeing.
     *
     * <p>{@code RUNNING} is not finished and is not dropped. A second offer of a wish some other
     * worker is already granting is a real hazard and a different one, it is already loud
     * where it happens, and the backlog text already tells a worker another agent may be taking a
     * listed wish. Widening this predicate to cover it would drop a wish on the strength of a
     * status that another thread is in the middle of writing.
     */
    private static com.moddingfromamod.wish.WishStatus alreadyOver(Wish wish) {
        if (wish == null || wish.id() == null) {
            return null;
        }
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return null;
                }
                return com.moddingfromamod.wish.WishBook.get(server).byId(wish.id())
                        .map(com.moddingfromamod.wish.Wish::status)
                        .filter(com.moddingfromamod.wish.WishStatus::isFinished)
                        .orElse(null);
            });
        } catch (Throwable e) {
            // Including AgentBridge.Unavailable. See "fails OPEN" above: not knowing is not a
            // reason to throw a child's wish away.
            LOGGER.debug("could not check whether wish {} is still wanted: {}",
                    wish.shortId(), e.toString());
            return null;
        }
    }

    // ──: the pickup gates as one decision, for the wait here AND the ratchet's resolver ─────

    /** What the pickup gates decided: a refusal for the agent, or the text to work on and why. */
    record Gates(String refusal, com.moddingfromamod.safety.SafetyInputGate.Decision safety,
                 com.moddingfromamod.world.WorldRulesGate.Decision world, String wishText) {

        static Gates refused(String refusal) {
            return new Gates(refusal, null, null, null);
        }

        /** Did a gate change what the agent works on (a redirect, a split, a clamp)? */
        boolean reworded() {
            return refusal == null && (safety.redirectShown() || !world.childMessage().isEmpty()
                    || world.act() == com.moddingfromamod.world.WorldRulesGate.Act.SPLIT
                    || world.act() == com.moddingfromamod.world.WorldRulesGate.Act.CLAMP);
        }

        /** What the agent should read about this wish that it was not handed at pickup. */
        String note() {
            if (refusal != null) {
                return "";
            }
            StringBuilder n = new StringBuilder();
            if (!safety.guidance().isEmpty()) {
                n.append(safety.guidance());
            }
            if (!world.briefNote().isEmpty()) {
                n.append(n.length() > 0 ? "\n" : "").append(world.briefNote());
            }
            return n.toString();
        }
    }

    /**
     * Pickups the ratchet's resolver already DECIDED, with every side effect done (the record written,
     * the redirect said to the child): a redirected wish is put back for a fresh worker, and its next
     * pickup must use this decision rather than grade, say and log it all a second time.
     */
    private static final java.util.Map<java.util.UUID, Gates> DECIDED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final String WITHHELD = "That wish was withheld before it reached you — the child has "
            + "already been told and nothing needs building. Nothing is wrong with you or "
            + "with the game. Do NOT ask what it was, do not mention it to anyone, and do "
            + "not try to work it out: call await_wish again immediately and carry on.";

    /**
     * Run the four pickup gates in their order, with every side effect each one has (records, the
     * child told, the bank charged). Blocking: it waits for the graders. Called by {@link #call} when
     * the verdicts are already in (or speculation is off), and by the ratchet's resolver thread.
     */
    static Gates decideGates(Wish wish) {
        Gates decided = DECIDED.remove(wish.id());
        if (decided != null) {
            return decided;
        }
        // ── CHILD SAFETY, L1 ENFORCEMENT POINT (docs/CHILD_SAFETY_PLAN.md §3 L1, §2's INPUT column).
        //
        // Before any worker may ACT on the wish, and nowhere else. Until that point was the
        // dequeue itself — between the await and the assign, the only point where a refusal can take
        // effect without losing the wish or half-assigning it. Since a wish whose grade is
        // still running may be handed over first (AwaitWishTool.speculative), behind the pickup
        // ratchet: the worker can only LOOK until this decision is made, and a refusal or a redirect
        // takes the wish away from it again. Either way the decision itself is made here.
        //
        // It is also the ONLY point that sees the three re-wish paths which skip WishService.submit
        // entirely (`/moddy redirect`, an agent re-offer; `/moddy no` re-offered too made it an undo), which is the other half of
        // why the enforcement is here and not at the front door.
        //
        // In SHADOW mode `decision.act()` is PASS whatever the grade said, and the record still
        // carries the prediction — §8's base rate.
        com.moddingfromamod.safety.SafetyInputGate.Decision safety = gradeInput(wish);
        if (safety.refuses()) {
            refuse(wish, safety);
            // MIRRORS THE BUSY-WORKER REFUSAL in shape and in wording: an error the agent reads and
            // follows, naming the one thing to do next. Nothing is lost by looping — this wish is
            // over and will not come back, and the next one is already waiting.
            return Gates.refused(WITHHELD);
        }
        // The text the agent is handed. §2: "the redirected text is what the agent gets" -- the
        // child's own words are kept on the Wish record and are deliberately NOT shown here,
        // because a worker that can see both will build the first one.
        String wishText = safety.runText();
        if (safety.redirectShown()) {
            // BEFORE the agent is woken, so the promise is kept in the order it was made.
            sayTheRedirect(wish, wishText);
        }

        // ── THE WORLD'S RULES (locked 2026-09-29) ─────────────────────────────────────────
        //
        // HERE, BESIDE THE SAFETY GATE AND FOR THE SAME REASON: the wish is dequeued and nothing is
        // assigned, so a refusal can neither lose it nor half-assign it, and no worker has been spent.
        // AFTER safety, so a wish safety refuses never waits on this grade. The grade was fired at
        // submit in parallel with the safety grade; on a world whose rules deny nothing
        // this is a PASS that asked nobody. A refusal costs nothing — no charge exists yet
        // and none is ever taken for one.
        com.moddingfromamod.world.WorldRules worldRules = com.moddingfromamod.world.WorldRulesService.current();
        com.moddingfromamod.world.WorldRulesGate.Decision world = worldRulesAtPickup(wish, wishText,
                worldRules, safety.redirectShown());
        if (world.refuses()) {
            refuseForTheWorld(wish, world);
            return Gates.refused("That wish was declined by this world's rules before it reached "
                    + "you — the child has already been told why and where it works. Nothing needs "
                    + "building and nothing is wrong. Call await_wish again immediately and carry on.");
        }
        if (!world.childMessage().isEmpty()) {
            // A split or a clamp is SAID before the agent is woken, like a redirect.
            tellTheChild(wish, world.childMessage(), "world-rules");
        }
        wishText = world.runText();
        final boolean worldChangedText = world.act() == com.moddingfromamod.world.WorldRulesGate.Act.SPLIT;

        // ── THE WISH BANK'S CHARGE (D18) ──────────────────────────────────────────────────
        //
        // HERE, AFTER BOTH GATES, so a wish that safety or the world's rules refused costs nothing,
        // and before the assign, so a wish that cannot be paid for is refused without spending a
        // worker. A re-wish of the same wish id (/moddy redirect) was paid for already and
        // is free. Charged BEFORE the cache router below: a wish the library serves is still a wish.
        // A failure or a stop is refunded where the status moves (WishBook.complete). A no-op on a
        // world whose rules declare no wish_bank. Speculatively, it runs on the resolver after the verdicts.
        com.moddingfromamod.wish.WishCharge.Paid paid = com.moddingfromamod.wish.WishCharge.get().atPickup(wish);
        if (paid.refused()) {
            refuseForTheBank(wish, paid);
            return Gates.refused("That wish was not started — the child has no wishes left in this "
                    + "world's wish bank right now and has already been told when the next one arrives. "
                    + "Nothing needs building and nothing is wrong. Call await_wish again immediately.");
        }

        return new Gates(null, safety, world, wishText);
    }

    /**
     * <b>Hand this wish over before its verdicts land?</b> Only when there is something to wait
     * for and nothing about the wish is already known to be wrong. Never when:
     * <ul>
     *   <li>nothing is graded at all (the OFF arm);</li>
     *   <li>its pickup was already decided (a redirect put back for a fresh worker);</li>
     *   <li>the LEXICON flags it — L0 is instant, and a wish it hits waits for the full decision;</li>
     *   <li>Moddy is tired (the grader is down): an ungradeable wish is refused, so do not start one.</li>
     * </ul>
     * <p>A WISH BANK does not stop it (2026-10-25): the charge is taken inside
     * {@link #decideGates}, after safety and the world's rules, so only a wish that passed is ever
     * charged; a reworded wish is charged once there and its kept decision is reused at the next
     * pickup, so it is never charged twice; and a bank already empty refuses at SUBMIT
     * ({@code WishBankService.refusesAtSubmit}), so a refusal at pickup is the rare race it was.
     * <ul>
     *   <li>both verdicts are already in — then deciding now costs nothing.</li>
     * </ul>
     */
    static boolean speculative(Wish wish) {
        try {
            if (!com.moddingfromamod.safety.ChildSafetyConfig.grading()
                    || !com.moddingfromamod.safety.SafetyGrader.gradesTakeTime()
                    || DECIDED.containsKey(wish.id())) {
                return false;
            }
            if (com.moddingfromamod.safety.SafetyLexicon.shared().check(wish.text()).isPresent()) {
                return false;
            }
            if (com.moddingfromamod.health.ModdyTired.armed() && com.moddingfromamod.health.ModdyTired.global()
                    .current(System.currentTimeMillis(),
                            com.moddingfromamod.safety.ChildSafetyConfig.enforcing()).isPresent()) {
                return false;
            }
            boolean safetyReady = com.moddingfromamod.safety.SafetyGrader.inputGradeIfReady(wish.text()).isPresent();
            com.moddingfromamod.world.WorldRules rules = com.moddingfromamod.world.WorldRulesService.current();
            boolean worldGraded = rules != null && rules.deniesAnything()
                    && com.moddingfromamod.world.WorldRulesGrader.enabled();
            boolean worldReady = !worldGraded
                    || com.moddingfromamod.world.WorldRulesGrader.ifReady(wish.text(), rules).isPresent();
            return !(safetyReady && worldReady);
        } catch (RuntimeException e) {
            LOGGER.debug("speculation check threw ({}); deciding at pickup as before", e.toString());
            return false;              // when in doubt, the old, waiting path
        }
    }

    /** Decide a speculatively handed-over wish's verdicts on a daemon thread of its own. */
    private static void resolveLater(WishQueue queue, Wish wish, String workerId) {
        long handedOver = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                String outcome = resolve(queue, wish, workerId);
                long landed = System.currentTimeMillis() - handedOver;
                LOGGER.info("wish {}: pickup verdicts landed {} ms after the hand-over — {}", wish.shortId(),
                        landed, outcome);
                logHold(wish, landed, com.moddingfromamod.world.WorldRulesLog.holdOutcome(outcome));
            } catch (Throwable e) {
                logHold(wish, System.currentTimeMillis() - handedOver, "FAILED");
                // FAIL CLOSED: the worker never acts, and the wish goes back to be decided at pickup.
                LOGGER.warn("wish {}: the pickup verdicts could not be decided ({}) — taking it back from "
                        + "its worker; it will be decided at the next pickup", wish.shortId(), e.toString());
                takeBack(queue, wish, workerId, "its pickup verdicts could not be decided");
            }
        }, "pickup-verdict-" + wish.shortId());
        t.setDaemon(true);
        t.start();
    }

    /** the hold, counted in world-rules.jsonl (ids and a closed outcome only, never the words). */
    private static void logHold(Wish wish, long landedMs, String outcome) {
        com.moddingfromamod.world.WorldRulesLog.hold(wish.shortId(),
                wish.playerId() == null ? "" : wish.playerId().toString(), landedMs, outcome);
    }

    /**
     * The ratchet's resolution: run the gates exactly as a waiting pickup would, then
     * <ul>
     *   <li>unchanged → CLEAR the hold: the parked calls go on, the first carrying any note;</li>
     *   <li>refused → the gate has already wound the run down and told the child; the worker, which
     *       read words it should not have, is stopped;</li>
     *   <li>reworded → the redirect has been said; the decision is kept, the wish put back, and the
     *       worker stopped, so a FRESH worker is handed only the new words.</li>
     * </ul>
     */
    static String resolve(WishQueue queue, Wish wish, String workerId) {
        Gates g = decideGates(wish);
        var hold = com.moddingfromamod.marshal.PickupHold.global();
        if (g.refusal() == null && !g.reworded()) {
            hold.clear(wish.id(), g.note());
            return g.note().isEmpty() ? "cleared" : "cleared with a note";
        }
        hold.rescind(wish.id(), workerId);
        if (g.refusal() != null) {
            com.moddingfromamod.agent.AgentSession.retireWorker(workerId,
                    "wish " + wish.shortId() + " was refused after it was handed over (#1660)");
            com.moddingfromamod.marshal.Marshal.global().woundDown(workerId);
            return "refused; worker retired";
        }
        DECIDED.put(wish.id(), g);
        takeBack(queue, wish, workerId, "a gate reworded it after it was handed over");
        return "reworded; requeued for a fresh worker";
    }

    /** Put a handed-over wish back on the queue it came from for a fresh worker, and stop the one that held it. */
    private static void takeBack(WishQueue queue, Wish wish, String workerId, String why) {
        var hold = com.moddingfromamod.marshal.PickupHold.global();
        hold.rescind(wish.id(), workerId);
        com.moddingfromamod.marshal.Marshal.global().requeueAfterDeath(workerId);
        hold.forget(wish.id());
        com.moddingfromamod.agent.AgentSession.retireWorker(workerId,
                "wish " + wish.shortId() + ": " + why + " — a fresh worker takes it (#1660)");
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return Boolean.FALSE;
                }
                // The order a re-opened wish already takes (HealthService.requeueAccountDeaths): the
                // book back to PENDING (not final, so nothing is refunded or settled), then offered
                // to the queue. The child's bar stays up — it IS still being granted.
                var book = com.moddingfromamod.wish.WishBook.get(server);
                String note = book.byId(wish.id()).map(Wish::result).orElse("");
                book.complete(wish.id(), com.moddingfromamod.wish.WishStatus.PENDING, note)
                        .ifPresent(queue::offer);
                return Boolean.TRUE;
            });
        } catch (AgentBridge.Unavailable e) {
            LOGGER.warn("wish {}: could not put it back on the queue ({})", wish.shortId(), e.getMessage());
        }
    }

    private static com.moddingfromamod.safety.SafetyInputGate.Decision gradeInput(Wish wish) {
        if (!com.moddingfromamod.safety.ChildSafetyConfig.grading()) {
            return com.moddingfromamod.safety.SafetyInputGate.Decision.pass(wish.text());
        }
        com.moddingfromamod.safety.SafetyVerdict lexicon = null;
        com.moddingfromamod.safety.SafetyGrader.Graded graded =
                com.moddingfromamod.safety.SafetyGrader.Graded.unavailable(0);
        String note = "";
        // Could the grade not be OBTAINED? True for no answer (an empty model id is what
        // every transport-level failure returns), an expired budget, or a wait that could not
        // happen — and FALSE for a reply that came back and would not parse, because then the
        // grader was reachable and decision 12.2's `open` still holds for it.
        boolean unobtained = false;
        try {
            lexicon = com.moddingfromamod.safety.SafetyLexicon.shared().check(wish.text())
                    .orElse(null);
            long budget = com.moddingfromamod.safety.ChildSafetyConfig.inputBudgetMs();
            long started = System.currentTimeMillis();
            try {
                graded = com.moddingfromamod.safety.SafetyGrader.gradeInput(wish.text())
                        .get(budget, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                graded = com.moddingfromamod.safety.SafetyGrader.Graded.unavailable(
                        System.currentTimeMillis() - started);
                note = "the input budget of " + budget + "ms expired before the grade arrived; "
                        + "the grade is still running and will land in the memo";
                unobtained = true;
            }
            if (graded.verdict().isUnavailable() && graded.modelId().isEmpty()) {
                unobtained = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            note = "the wait for the grade was interrupted";
            unobtained = true;
        } catch (Exception e) {
            // A grade that could not even be waited on must never cost a child a wish.
            note = "the grade could not be awaited: " + e;
            unobtained = true;
        }

        // ── MODDY IS TIRED (D8): closed for every grant. It first kept RE-wishes open
        // (`/moddy no`, `/moddy redirect`, a wish resumed at the next join) so nothing half-made was
        // stranded; that was decided on 2026-09-23: "Moddy is too tired whether it's a
        // new wish or a refinement". A re-wish carries NEW words, and during an outage nothing but
        // the lexicon would have read them.
        boolean tired = unobtained && com.moddingfromamod.health.ModdyTired.armed();

        com.moddingfromamod.safety.SafetyInputGate.Decision decision;
        try {
            decision = com.moddingfromamod.safety.SafetyInputGate.decide(
                    lexicon, graded.verdict(), wish.text(), tired);
        } catch (RuntimeException e) {
            // The gate is pure and total, so this cannot happen -- and if it ever does, the answer
            // is still a wish granted rather than a child refused by a bug in the refuser.
            LOGGER.debug("the input gate threw: {}", e.toString());
            return com.moddingfromamod.safety.SafetyInputGate.Decision.pass(wish.text());
        }
        try {
            // WRITTEN BEFORE THE ACTION, in every mode and for every band (§6). A log that recorded
            // only the refusals would make the false-positive rate -- the number this layer lives
            // or dies on -- uncomputable, because a rate needs its denominator.
            //
            // The COMBINED verdict, whose provenance says which of the two graders decided it; the
            // model's own latency, cost and reply beside it, because that is what was spent.
            com.moddingfromamod.agent.ActionLog.safety(wish.shortId(), "input", decision.verdict(),
                    decision.logAction(), graded.latencyMs(), graded.costUsd(), graded.modelId(),
                    note.isEmpty() ? graded.raw() : note, "", 0, decision.redirectShown(), false,
                    wish.playerId() == null ? "" : wish.playerId().toString(), graded.arm());
        } catch (RuntimeException e) {
            LOGGER.debug("could not log the input grade: {}", e.toString());
        }
        return decision;
    }

    /**
     * Wait for the world-rules grade (fired at submit), decide, and log it. Never throws; every
     * failure is a PASS or an UNSURE, because this grader fails open.
     */
    private static com.moddingfromamod.world.WorldRulesGate.Decision worldRulesAtPickup(Wish wish,
            String runText, com.moddingfromamod.world.WorldRules rules, boolean safetyRedirected) {
        if (rules == null || !rules.deniesAnything() || !com.moddingfromamod.world.WorldRulesGrader.enabled()) {
            return com.moddingfromamod.world.WorldRulesGate.pass(runText);
        }
        com.moddingfromamod.world.WorldRulesGrader.Graded g;
        com.moddingfromamod.world.WorldRulesGate.Decision d;
        try {
            // The CHILD's words are what was graded at submit (the memo key), not a redirect.
            g = com.moddingfromamod.world.WorldRulesGrader.await(wish.text(), rules);
            d = com.moddingfromamod.world.WorldRulesGate.decide(g.verdict(), rules, runText,
                    safetyRedirected, text -> sweeps(wish, text));
        } catch (RuntimeException e) {
            LOGGER.debug("the world-rules gate threw: {}", e.toString());
            return com.moddingfromamod.world.WorldRulesGate.pass(runText);
        }
        com.moddingfromamod.world.WorldRulesLog.grade(wish.shortId(),
                wish.playerId() == null ? "" : wish.playerId().toString(), rules, d, g.latencyMs(),
                g.costUsd(), g.modelId(), g.arm());
        return d;
    }

    /** Does a line the grader wrote pass the text sweep a child's chat line passes? */
    private static boolean sweeps(Wish wish, String text) {
        JsonObject a = new JsonObject();
        a.addProperty("message", text);
        try {
            return !com.moddingfromamod.safety.SafetyTextSweep.sweep("notify_player", a, wish.shortId()).refuses();
        } catch (RuntimeException e) {
            return false;                  // a line that could not be swept is not said
        }
    }

    /**
     * A world's rules declined the wish: end it like a safety refusal (the marshal first, the bar
     * down, the book says REJECTED with the rule), and tell the child the kind message.
     */
    private static void refuseForTheWorld(Wish wish, com.moddingfromamod.world.WorldRulesGate.Decision d) {
        com.moddingfromamod.marshal.Marshal marshal = com.moddingfromamod.marshal.Marshal.global();
        try {
            marshal.cancel(wish.id(), com.moddingfromamod.marshal.CancelMode.STOP, "", "");
        } catch (RuntimeException e) {
            LOGGER.debug("could not wind the run down: {}", e.toString());
        }
        String rule = d.rule() == null ? "" : d.rule().id();
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return Boolean.FALSE;
                }
                com.moddingfromamod.wish.WishCancel.clearHud(wish.playerId(), wish.id(), marshal);
                com.moddingfromamod.wish.WishBook.get(server).complete(wish.id(),
                        com.moddingfromamod.wish.WishStatus.REJECTED,
                        "Declined by this world's rules (" + rule + ") before any agent saw it.");
                net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(wish.playerId());
                if (p != null) {
                    com.moddingfromamod.wish.WishFeedback.worldRule(p, d.childMessage());
                }
                return Boolean.TRUE;
            });
        } catch (Throwable e) {
            LOGGER.debug("could not tell the child the world declined the wish: {}", e.toString());
        }
        com.moddingfromamod.agent.ActionLog.wish(wish.text(), "WORLD_RULE", wish.source().name(),
                wish.shortId(), wish.playerId() == null ? "" : wish.playerId().toString());
    }

    /**
     * The wish bank emptied between submit and pickup (two wishes in flight on the last one): end it
     * like a world refusal — nothing was charged and no worker spent — and say when the next arrives.
     */
    private static void refuseForTheBank(Wish wish, com.moddingfromamod.wish.WishCharge.Paid paid) {
        com.moddingfromamod.marshal.Marshal marshal = com.moddingfromamod.marshal.Marshal.global();
        try {
            marshal.cancel(wish.id(), com.moddingfromamod.marshal.CancelMode.STOP, "", "");
        } catch (RuntimeException e) {
            LOGGER.debug("could not wind the run down: {}", e.toString());
        }
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return Boolean.FALSE;
                }
                com.moddingfromamod.wish.WishCancel.clearHud(wish.playerId(), wish.id(), marshal);
                com.moddingfromamod.wish.WishBook.get(server).complete(wish.id(),
                        com.moddingfromamod.wish.WishStatus.REJECTED,
                        "Not started: the wish bank was empty at pickup. Nothing was charged.");
                net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(wish.playerId());
                if (p != null) {
                    com.moddingfromamod.wish.WishFeedback.bank(p, paid.line());
                }
                return Boolean.TRUE;
            });
        } catch (Throwable e) {
            LOGGER.debug("could not tell the child the wish bank was empty: {}", e.toString());
        }
        com.moddingfromamod.agent.ActionLog.wish(wish.text(), "BANK_EMPTY", wish.source().name(),
                wish.shortId(), wish.playerId() == null ? "" : wish.playerId().toString());
    }

    /** Say a world-rules line (a split or a clamp) to the child, off the server thread. */
    private static void tellTheChild(Wish wish, String message, String site) {
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                net.minecraft.server.level.ServerPlayer p = server == null ? null
                        : server.getPlayerList().getPlayer(wish.playerId());
                if (p != null) {
                    com.moddingfromamod.wish.WishFeedback.worldRule(p, message);
                }
                return Boolean.TRUE;
            });
        } catch (Throwable e) {
            LOGGER.debug("could not say the {} line: {}", site, e.toString());
        }
    }

    /**
     * End the wish here: the agent never sees it and the child is told.
     *
     * <p>The stop machinery that already exists, in the order {@code SafetyPlanGate.endWish} uses
     * and for the same reason — the marshal first, on this thread, so the run is finished even if
     * the game thread is busy and the child's half never lands. The other order can leave a wish
     * nobody is watching sitting in the marshal's waiting list for ever.
     *
     * <p>{@link com.moddingfromamod.wish.WishStatus#REJECTED} rather than {@code STOPPED}: its own
     * documentation reads "refused before doing anything … unsafe", and §0 records that nothing has
     * ever set it. This is the first thing that does.
     */
    private static void refuse(Wish wish, com.moddingfromamod.safety.SafetyInputGate.Decision d) {
        // The tired line is the one message that is about Moddy rather than about the wish.
        final boolean tired = com.moddingfromamod.health.OpsWords.sayTired().equals(d.childMessage());
        com.moddingfromamod.marshal.Marshal marshal = com.moddingfromamod.marshal.Marshal.global();
        try {
            marshal.cancel(wish.id(), com.moddingfromamod.marshal.CancelMode.STOP, "", "");
        } catch (RuntimeException e) {
            LOGGER.debug("could not wind the run down: {}", e.toString());
        }
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return Boolean.FALSE;
                }
                // THE BAR COMES DOWN. Per wish, not per player: a child with two wishes in flight
                // who watches both bars vanish has been told, as far as he can tell, that we
                // stopped the other one too.
                com.moddingfromamod.wish.WishCancel.clearHud(wish.playerId(), wish.id(), marshal);
                com.moddingfromamod.wish.WishBook.get(server).complete(wish.id(),
                        com.moddingfromamod.wish.WishStatus.REJECTED, tired
                                ? "Moddy was tired: the safety grade could not be obtained, so "
                                        + "this new wish was not started."
                                : "Withheld by the safety layer before any agent saw it.");
                net.minecraft.server.level.ServerPlayer p =
                        server.getPlayerList().getPlayer(wish.playerId());
                if (p != null) {
                    // CHAT, because he has already read the opener ("Moddy's on it…") -- see
                    // WishFeedback.blockedAfterThinking.
                    if (tired) {
                        com.moddingfromamod.wish.WishFeedback.tiredAfterThinking(p);
                    } else if (d.act() == com.moddingfromamod.safety.SafetyInputGate.Act
                            .UNAVAILABLE_CLOSED) {
                        com.moddingfromamod.wish.WishFeedback.genieResting(p);
                    } else {
                        com.moddingfromamod.wish.WishFeedback.blockedAfterThinking(p);
                    }
                }
                return Boolean.TRUE;
            });
        } catch (Throwable e) {
            // Nobody online, no world, a busy server: the wish is still over, which is the part
            // that matters. A refusal that threw would hand the agent a broken tool call instead of
            // the message telling it to loop.
            LOGGER.debug("could not tell the child the wish was withheld: {}", e.toString());
        }
        // TIRED is not BLOCKED: the child said nothing wrong, and a report that counts safety
        // refusals from this row must not count an outage as one (WishService.Outcome.TIRED).
        com.moddingfromamod.agent.ActionLog.wish(wish.text(), tired ? "TIRED" : "BLOCKED",
                wish.source().name(), wish.shortId(),
                wish.playerId() == null ? "" : wish.playerId().toString());
        if (tired) {
            com.moddingfromamod.health.WishSweeper.announceTiredness(System.currentTimeMillis());
        }
    }

    /**
     * Say the redirect out loud, before anything is built.
     *
     * <p>§2: "nothing is ever quietly substituted". The chat line IS the mechanism — a redirect the
     * child never read is a substitution — so it is sent before the wake text is even assembled,
     * and {@code redirectShown} in the log is how that promise stays checkable afterwards.
     */
    private static void sayTheRedirect(Wish wish, String redirect) {
        if (redirect == null || redirect.isEmpty()) {
            return;
        }
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                net.minecraft.server.level.ServerPlayer p = server == null ? null
                        : server.getPlayerList().getPlayer(wish.playerId());
                if (p != null) {
                    com.moddingfromamod.wish.WishFeedback.redirected(p, redirect);
                }
                return Boolean.TRUE;
            });
        } catch (Throwable e) {
            // He does not hear it, and the wish is still redirected -- which is the one case §2
            // calls a quiet substitution. Logged loudly enough to find, and NOT a reason to refuse
            // the wish: a child whose chat was momentarily unreachable has done nothing wrong.
            LOGGER.debug("could not tell the child about the redirect: {}", e.toString());
        }
    }

    /**
     * @param text what the agent should build — the child's words, or the safety redirect that
     *             replaced them (§2). Passed in rather than read off the wish, because the wish
     *             record deliberately keeps the original and the agent deliberately does not see it
     * @param note the wish's own note, or empty to suppress it. ⚠ MEASURED, Gate C½ 2026-09-11:
     *             the note is the offline matcher's hint and it QUOTES the words it could not
     *             explain — a redirected wish arrived with its redirect as the text and
     *             {@code [torture, villager, slowly, blood, everywhere]} on the line underneath,
     *             which hands the agent the original in pieces. Replacing the text is not enough;
     *             everything DERIVED from the original has to go with it
     */
    private static void describe(StringBuilder sb, Wish w, String text, String note) {
        sb.append("  id=").append(w.shortId())
          .append(" from=").append(w.playerName())
          .append(" at=(").append(Math.round(w.x())).append(",").append(Math.round(w.y()))
          .append(",").append(Math.round(w.z())).append(")")
          .append(" dim=").append(w.dimension()).append('\n')
          .append("  wish: \"").append(text == null || text.isBlank() ? w.text() : text)
          .append("\"\n");
        // A re-opened wish says why it came back; without this the agent repeats its mistake.
        if (note != null && !note.isBlank()) {
            sb.append("  ⚠ ").append(note).append('\n');
        }
    }
}
