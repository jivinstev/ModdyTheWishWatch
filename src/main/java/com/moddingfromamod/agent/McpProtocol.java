package com.moddingfromamod.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Model Context Protocol, as a <b>pure function</b> of (headers, body) → {@link HttpReply}.
 *
 * <p>No {@code HttpExchange}, no Minecraft: {@link McpHttpServer} is a thin adapter around this, so
 * the entire protocol — status codes, session handling, error shapes — is unit-tested in
 * milliseconds instead of by poking a live game.
 *
 * <p>This implements <b>Streamable HTTP with JSON responses</b>: a single endpoint, JSON-RPC in and
 * out over POST, no SSE. That's a deliberate simplification the spec permits and the reference
 * implementation (the Blockbench MCP plugin) also uses, and it's why this fits in one small Java
 * class rather than needing a streaming stack.
 *
 * <p>Shapes worth remembering:
 * <ul>
 *   <li>A <b>notification</b> (no {@code id}) gets HTTP 202 with an empty body — never a result.</li>
 *   <li>A <b>tool failure</b> is a successful result with {@code isError:true}; only protocol
 *       problems are JSON-RPC errors.</li>
 *   <li>An <b>unknown/expired session</b> is HTTP 404, which tells the client to re-initialize.
 *       Returning 400 leaves a client permanently wedged.</li>
 * </ul>
 */
public final class McpProtocol {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Protocol versions we'll accept from a client; we echo theirs when we know it. */
    private static final List<String> SUPPORTED_VERSIONS =
            List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final String LATEST_VERSION = "2025-06-18";

    /** what a worker whose wish was taken back is told on every call while the pool stops it. */
    static final String PICKUP_WITHDRAWN = "This wish was taken back by the grown-ups' check and is being "
            + "handled elsewhere. Nothing is wrong with you. Do not call any more tools and do not call "
            + "await_wish — end your session now.";

    /**
     * how long a gated call waits for a wish's pickup verdicts — the input grade's own budget
     * (it is what the old, waiting pickup would have held the wish for) plus the world-rules grade's.
     */
    static final String PLAN_BEING_CHECKED = "(Your plan is with the grown-ups' check for a few seconds. "
            + "Carry straight on — any tool that builds or talks to the child waits for the check by itself.)";

    /** how long a gated call waits for a plan grade — CliTransport's own ceiling for one grade. */
    static long planParkMs() {
        return 45_000L;
    }

    /**
     * grade a plan the agent has already been told is set, on a daemon thread of its own. The
     * same decision the waiting path made ({@code SafetyPlanGate.decide}), with the same consequences:
     * <ul>
     *   <li>passed (FINE, MAYBE, or unavailable-and-open) → what the plan shows the child is shown
     *       now, the wish counts as planned (PlanFirst), and the parked calls go on — a MAYBE's
     *       guidance riding the first of them, as it rode the plan's result before;</li>
     *   <li>refused (a divert or a stop) → nothing the plan would have shown is ever shown, a STOP
     *       ends the wish, the parked calls return the grader's message so the agent revises IN PLACE,
     *       and the worker is retired after this wish (PickupHold.retireAfterWish).</li>
     * </ul>
     * A grade that throws is a refusal (fail closed): the agent is asked to plan again.
     */
    private static void planGradeLater(com.moddingfromamod.marshal.Marshal marshal,
                                       com.moddingfromamod.marshal.WishRun run, JsonObject args,
                                       String wishId, String sessionId) {
        Thread t = new Thread(() -> {
            com.moddingfromamod.safety.SafetyPlanGate.Decision d = null;
            try {
                d = com.moddingfromamod.safety.SafetyPlanGate.decide("plan_wish", args, run.text(), wishId,
                        run.wishId());
            } catch (Throwable e) {
                LOGGER.warn("wish {}: the plan grade threw ({}) — asking the agent to plan again", wishId,
                        e.toString());
            }
            var plans = com.moddingfromamod.marshal.PickupHold.plans();
            if (d == null || d.refuses()) {
                com.moddingfromamod.marshal.PlanDeferral.global().drop(run.wishId());
                if (d != null && d.act() == com.moddingfromamod.safety.SafetyPlanGate.Act.STOP) {
                    com.moddingfromamod.safety.SafetyPlanGate.endWish(marshal, run);
                }
                com.moddingfromamod.marshal.PickupHold.global().retireAfterWish(sessionId);
                plans.refuse(run.wishId(), d == null
                        ? "Your plan could not be checked. Call plan_wish again with the same plan."
                        : d.message());
                return;
            }
            var show = com.moddingfromamod.marshal.PlanDeferral.global().flush(run.wishId());
            if (!show.isEmpty()) {
                try {
                    AgentBridge.onServer(() -> {
                        show.forEach(Runnable::run);
                        return Boolean.TRUE;
                    });
                } catch (RuntimeException e) {
                    LOGGER.warn("wish {}: could not show the plan ({})", wishId, e.toString());
                }
            }
            run.markPlanned();
            plans.clear(run.wishId(), d.act() == com.moddingfromamod.safety.SafetyPlanGate.Act.NOTE
                    ? d.message() : "");
        }, "plan-verdict-" + wishId);
        t.setDaemon(true);
        t.start();
    }

    static long pickupParkMs() {
        return com.moddingfromamod.safety.ChildSafetyConfig.inputBudgetMs()
                + com.moddingfromamod.world.WorldRulesGrader.budgetMs();
    }

    public static final String SERVER_NAME = "Minecraft Wish Watch";
    public static final String SERVER_VERSION = "1.0.0";

    private final ToolRegistry tools;
    private final McpSessions sessions;

    /**
     * <b>Which marshal this dispatch asks, rather than always the process-wide one.</b>
     *
     * <p>The three questions below — which wish is this worker on, has it been cancelled, what has
     * it made — used to be asked of {@code Marshal.global()} directly. That is correct in a running
     * game, where there is one, and it is why the concurrent-wish GameTests could not be isolated:
     * a test that wants its own queue has to share the global anyway, because the dispatch it is
     * exercising reads the global regardless of what the test was handed. Two overlapping tests
     * then claim from one queue, and a worker gets the other test's wish — which is exactly what
     * {@code twoSessionsEachGrantTheirOwnWish} reported, naming a wish text from a different test
     * file.
     *
     * <p>Never null: the two-argument constructor supplies the global, so a running game is
     * unchanged and no call site had to move.
     */
    private final com.moddingfromamod.marshal.Marshal marshal;

    public McpProtocol(ToolRegistry tools, McpSessions sessions) {
        this(tools, sessions, com.moddingfromamod.marshal.Marshal.global());
    }

    /** The seam a test uses: its own queue, so it cannot claim a neighbour's wish. */
    public McpProtocol(ToolRegistry tools, McpSessions sessions,
                       com.moddingfromamod.marshal.Marshal marshal) {
        this(tools, sessions, marshal, com.moddingfromamod.marshal.PlanFirst::budgetedWorld);
    }

    /**
     * …and its own answer to "does this world price wishes?", because GameTests in one
     * batch run concurrently and share every static: a test that flipped the global budget would
     * flip it under its neighbours.
     */
    public McpProtocol(ToolRegistry tools, McpSessions sessions,
                       com.moddingfromamod.marshal.Marshal marshal,
                       java.util.function.BooleanSupplier budgetedWorld) {
        this(tools, sessions, marshal, budgetedWorld, null);
    }

    /**
     * …and its own bases (behind {@code Bases}): the world a test's reach rule
     * is judged against, for the same reason — a GameTest must not switch bases on under its
     * neighbours. {@code null} is the running world's ({@code WorldModuleHost.liveBases()}, itself
     * null on a world without bases).
     */
    public McpProtocol(ToolRegistry tools, McpSessions sessions,
                       com.moddingfromamod.marshal.Marshal marshal,
                       java.util.function.BooleanSupplier budgetedWorld,
                       java.util.function.Supplier<com.moddingfromamod.world.Bases> islands) {
        this(tools, sessions, marshal, budgetedWorld, islands, null);
    }

    /**
     * …and its own world rules: {@code null} is the running world's hot-reloaded file
     * ({@code WorldRulesService.loadedOrNull()}), and a supplier answering null means "no file read",
     * which {@code BaseReach} treats as {@code WorldRules.CLOSED} on a world with bases.
     */
    public McpProtocol(ToolRegistry tools, McpSessions sessions,
                       com.moddingfromamod.marshal.Marshal marshal,
                       java.util.function.BooleanSupplier budgetedWorld,
                       java.util.function.Supplier<com.moddingfromamod.world.Bases> islands,
                       java.util.function.Supplier<com.moddingfromamod.world.WorldRules> rules) {
        this.tools = tools;
        // the names a child must never read are the ones this server actually serves.
        com.moddingfromamod.safety.AgentInternals.know(
                tools.all().stream().map(McpTool::name).toList());
        this.sessions = sessions;
        this.marshal = marshal == null ? com.moddingfromamod.marshal.Marshal.global() : marshal;
        this.budgetedWorld = budgetedWorld == null
                ? com.moddingfromamod.marshal.PlanFirst::budgetedWorld : budgetedWorld;
        this.islands = islands == null ? com.moddingfromamod.world.WorldModuleHost::liveBases : islands;
        this.rules = rules == null ? com.moddingfromamod.world.WorldRulesService::loadedOrNull : rules;
    }

    /** What the reach rule asks about this world's bases; a null answer means none. */
    private final java.util.function.Supplier<com.moddingfromamod.world.Bases> islands;

    /** The world's rules the reach rule judges world-wide tools by; null = none read. */
    private final java.util.function.Supplier<com.moddingfromamod.world.WorldRules> rules;

    /** Whether run_command needs a plan here (PlanFirst). Default: the process-wide predicate. */
    private final java.util.function.BooleanSupplier budgetedWorld;

    /** The queue this dispatch answers from — a test reads it back to assert against the same one. */
    public com.moddingfromamod.marshal.Marshal marshal() {
        return marshal;
    }

    /**
     * Handle one POST.
     *
     * @param headers request headers, looked up case-insensitively by the caller
     * @param body    the raw request body
     * @param nowMs   current time (injected so session expiry is testable without sleeping)
     */
    public HttpReply handlePost(Headers headers, String body, long nowMs) {
        // The worker's slot name is bound in `dispatch` and must not outlive this request: the
        // HTTP handler threads are pooled, so a stale binding would stamp one worker's name on
        // the next worker's records — a mis-attribution of money that no check downstream sees.
        try {
            return handlePostBound(headers, body, nowMs);
        } finally {
            AgentContext.unbindWorkerName();
        }
    }

    private HttpReply handlePostBound(Headers headers, String body, long nowMs) {
        // --- transport-level checks -------------------------------------------------
        String contentType = headers.get("content-type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            // Also our CSRF defence: application/json isn't a CORS-safelisted content type, so a
            // random web page can't reach this endpoint without a preflight we never answer.
            return error(415, null, -32000, "Unsupported Media Type: expected application/json");
        }
        String accept = headers.get("accept");
        if (accept == null || !accept.contains("application/json") || !accept.contains("text/event-stream")) {
            return error(406, null, -32000,
                    "Not Acceptable: client must accept application/json and text/event-stream");
        }
        String version = headers.get("mcp-protocol-version");
        if (version != null && !SUPPORTED_VERSIONS.contains(version)) {
            return error(400, null, -32000, "Unsupported protocol version: " + version);
        }

        // --- parse ------------------------------------------------------------------
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body == null ? "" : body);
        } catch (RuntimeException e) {
            return error(400, null, -32700, "Parse error");
        }
        if (parsed == null || parsed.isJsonNull()) {
            return error(400, null, -32700, "Parse error");
        }
        // A batch with no requests (all notifications) is answered with 202 and no body.
        if (parsed.isJsonArray()) {
            JsonArray arr = parsed.getAsJsonArray();
            boolean anyRequest = false;
            for (JsonElement el : arr) {
                if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                    anyRequest = true;
                    break;
                }
            }
            if (!anyRequest) {
                return HttpReply.empty(202);
            }
            // Answer the first request in the batch; batching isn't something Claude Code needs.
            for (JsonElement el : arr) {
                if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                    return dispatch(el.getAsJsonObject(), headers, nowMs);
                }
            }
        }
        if (!parsed.isJsonObject()) {
            return error(400, null, -32600, "Invalid Request");
        }
        return dispatch(parsed.getAsJsonObject(), headers, nowMs);
    }

    private HttpReply dispatch(JsonObject msg, Headers headers, long nowMs) {
        String method = msg.has("method") && msg.get("method").isJsonPrimitive()
                ? msg.get("method").getAsString() : "";
        JsonElement id = msg.get("id");
        boolean isNotification = id == null;

        // Notifications get no body at all — including notifications/initialized.
        if (isNotification) {
            return HttpReply.empty(202);
        }

        if (method.equals("initialize")) {
            String session = sessions.create(nowMs);
            JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                    ? msg.getAsJsonObject("params") : new JsonObject();
            String want = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
                    ? params.get("protocolVersion").getAsString() : null;
            String use = (want != null && SUPPORTED_VERSIONS.contains(want)) ? want : LATEST_VERSION;

            JsonObject caps = new JsonObject();
            JsonObject toolCaps = new JsonObject();
            toolCaps.addProperty("listChanged", false);
            caps.add("tools", toolCaps);

            JsonObject info = new JsonObject();
            info.addProperty("name", SERVER_NAME);
            info.addProperty("version", SERVER_VERSION);

            JsonObject result = new JsonObject();
            result.addProperty("protocolVersion", use);
            result.add("capabilities", caps);
            result.add("serverInfo", info);
            return HttpReply.json(200, rpcResult(id, result), session);
        }

        // Everything after initialize needs a live session.
        String sessionId = headers.get("mcp-session-id");
        if (sessionId == null || sessionId.isEmpty()) {
            return error(400, id, -32000, "Mcp-Session-Id header is required");
        }
        if (!sessions.touch(sessionId, nowMs)) {
            // 404 is the spec's "re-initialize" signal. 400 here wedges the client forever.
            return error(404, id, -32001, "Session not found");
        }
        // WHO this is: each pool worker's own config names its slot. Recorded on every
        // request, not once, because an MCP session id can change under a worker (a re-initialise
        // after the TTL) while its slot does not. Unknown names are ignored by the roster.
        String worker = headers.get(AgentSession.WORKER_HEADER);
        if (worker != null) {
            AgentSession.roster().heard(worker, sessionId, nowMs);
            AgentSession.roster().asked(worker, method, nowMs);
        }
        // …and stamped on every record this request writes, so a model turn in that
        // worker's transcript can be charged to the wish it held. See AgentContext.workerName.
        AgentContext.bindWorkerName(worker);

        // THE NO-PROGRESS WATCHDOG'S EVIDENCE: every tool call on the session holding a
        // wish stamps that wish's run, start and end — here, around the whole dispatch, so every
        // early refusal below is stamped too and no return path can leave a call "in flight".
        var activity = "tools/call".equals(method) ? marshal.runForWorker(sessionId) : null;
        if (activity != null) {
            activity.toolStarted(System.currentTimeMillis());
        }
        try {
            return dispatchMethod(method, id, msg, sessionId);
        } finally {
            if (activity != null) {
                activity.toolEnded(System.currentTimeMillis());
            }
        }
    }

    /**
     * Render the tool list once, off the record: every tool's description and schema, so the
     * first worker's {@code tools/list} is answered warm. Never throws — a tool that cannot render
     * here will say so on the real request.
     *
     * @return how many tools were rendered
     */
    public int warm() {
        int n = 0;
        for (McpTool tool : tools.all()) {
            try {
                tool.description();
                tool.inputSchema();
                n++;
            } catch (RuntimeException ignored) {
                // the real tools/list reports it
            }
        }
        return n;
    }

    /** One MCP method on a live session — {@link #dispatch} after the session checks. */
    private HttpReply dispatchMethod(String method, JsonElement id, JsonObject msg, String sessionId) {
        switch (method) {
            case "ping" -> {
                return HttpReply.json(200, rpcResult(id, new JsonObject()), sessionId);
            }
            case "tools/list" -> {
                JsonArray list = new JsonArray();
                java.util.List<String> names = new java.util.ArrayList<>();
                int descChars = 0;
                int schemaChars = 0;
                for (McpTool tool : tools.all()) {
                    JsonObject t = new JsonObject();
                    t.addProperty("name", tool.name());
                    t.addProperty("description", tool.description());
                    t.add("inputSchema", tool.inputSchema());
                    list.add(t);
                    names.add(tool.name());
                    descChars += tool.description() == null ? 0 : tool.description().length();
                    schemaChars += tool.inputSchema() == null ? 0 : tool.inputSchema().toString().length();
                }
                // what this worker was offered, once per tools/list.
                ActionLog.toolsOffered(sessionId, names, descChars, schemaChars);
                // its first turn has been waiting for exactly this.
                AgentSession.toolsListed(AgentContext.workerName());
                JsonObject result = new JsonObject();
                result.add("tools", list);
                return HttpReply.json(200, rpcResult(id, result), sessionId);
            }
            case "tools/call" -> {
                JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                        ? msg.getAsJsonObject("params") : new JsonObject();
                String name = params.has("name") && params.get("name").isJsonPrimitive()
                        ? params.get("name").getAsString() : "";
                JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                        ? params.getAsJsonObject("arguments") : new JsonObject();
                // READY MEANS "ASKING FOR WORK", not "connected".
                //
                // The first version of this recorded readiness on any post-initialize message and
                // measured 2 SECONDS for a pool that takes far longer to become usable — that is
                // the MCP client finishing its handshake, before the model has thought about
                // anything. A worker parked on await_wish is the first moment it could actually be
                // given a wish, and it is the moment the ~40s claim was always about.
                if ("await_wish".equals(name)) {
                    AgentSession.coldStart().workerReady(sessionId, System.currentTimeMillis());
                }
                // WHICH WISH this call belongs to, asked of the marshal rather than inferred from
                // the clock. The scorer used to attribute calls by the gap between wish records,
                // which cannot survive either a burst of preset presses or two agents working at
                // once — both of which are now normal.
                var runNow = marshal.runForWorker(sessionId);
                String wishId = runNow == null ? "" : runNow.shortId();
                // on a briefed wish the worker writes {{NAME}} where it would say the name; the child
                // reads the name. Before every sweep and the body, so they all see the words he will read.
                if (runNow != null) {
                    com.moddingfromamod.reference.WishBriefs.unveilInPlace(runNow.wishId(), args);
                }

                McpTool tool = tools.byName(name);
                ToolResult out;
                java.util.List<String> aliased = java.util.List.of();
                long startedAt = System.currentTimeMillis();
                // DOES A STOP ACTUALLY STOP? One check here covers every tool, now and in future —
                // the same argument as the narrator and the action log below. It REFUSES rather
                // than informs: an agent that reads "cancelled" and keeps going is invisible except
                // as blocks appearing after the bar went away.
                String refusal = com.moddingfromamod.marshal.CancelGate.refusalFor(
                        marshal, sessionId, name);
                if (refusal != null) {
                    out = ToolResult.error(refusal);
                    ActionLog.toolCall(name, args, out, 0, wishId);
                    return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                }
                // ── THE PICKUP RATCHET ───────────────────────────────────────────────────
                //
                // A wish may be handed over while its input safety grade (and world-rules grade) is
                // still running. Until both land, this worker may only LOOK: every tool that puts a
                // word in front of the child or changes the world parks here (PickupGatedTools), and
                // goes on only once the verdicts passed the wish unchanged. A verdict that refused or
                // reworded it takes the wish back and marks this session: every call it makes from
                // then on is refused while the pool stops it. CancelGate's shape — before the
                // narrator and the body, so nothing a refused call might have done happens. Parking
                // is safe here: this is an MCP handler thread, never the server thread.
                String releaseNote = "";
                var hold = com.moddingfromamod.marshal.PickupHold.global();
                if (hold.rescinded(sessionId)) {
                    out = ToolResult.error(PICKUP_WITHDRAWN);
                    ActionLog.toolCall(name, args, out, 0, wishId);
                    return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                }
                if (runNow != null && com.moddingfromamod.marshal.PickupGatedTools.gated(name)
                        && hold.state(runNow.wishId()) == com.moddingfromamod.marshal.PickupHold.State.PENDING) {
                    com.moddingfromamod.marshal.PickupHold.State decided;
                    try {
                        decided = hold.await(runNow.wishId(), pickupParkMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        decided = com.moddingfromamod.marshal.PickupHold.State.PENDING;
                    }
                    // path B: the wish may have been STOPPED while this call was parked —
                    // CancelGate ran before the park, so it is asked again after it.
                    String stoppedMeanwhile = com.moddingfromamod.marshal.CancelGate.refusalFor(
                            marshal, sessionId, name);
                    if (stoppedMeanwhile != null
                            || decided == com.moddingfromamod.marshal.PickupHold.State.STOPPED) {
                        out = ToolResult.error(stoppedMeanwhile != null ? stoppedMeanwhile
                                : com.moddingfromamod.marshal.CancelGate.message(runNow));
                        ActionLog.toolCall(name, args, out, 0, wishId);
                        return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                    }
                    if (decided == com.moddingfromamod.marshal.PickupHold.State.RESCINDED
                            || hold.rescinded(sessionId)) {
                        out = ToolResult.error(PICKUP_WITHDRAWN);
                        ActionLog.toolCall(name, args, out, 0, wishId);
                        return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                    }
                    if (decided == com.moddingfromamod.marshal.PickupHold.State.PENDING) {
                        // FAIL CLOSED: a call that cannot be cleared does not run, and says so.
                        out = ToolResult.error("The grown-ups' check on this wish is still running. "
                                + "Nothing is wrong — wait a moment, then call " + name + " again.");
                        ActionLog.toolCall(name, args, out, 0, wishId);
                        return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                    }
                    releaseNote = hold.takeNote(runNow.wishId());
                }
                // …and the same for a PLAN still being graded (planGradeLater): every tool that
                // builds or speaks parks until the plan grade passes; a refused plan answers every such
                // call with the grader's own message until the agent makes a new plan. plan_wish itself
                // is never held — it is how a refused plan is revised.
                var planHold = com.moddingfromamod.marshal.PickupHold.plans();
                if (runNow != null && !"plan_wish".equals(name)
                        && com.moddingfromamod.marshal.PickupGatedTools.gated(name)) {
                    com.moddingfromamod.marshal.PickupHold.State planState = planHold.state(runNow.wishId());
                    if (planState == com.moddingfromamod.marshal.PickupHold.State.PENDING) {
                        try {
                            planState = planHold.await(runNow.wishId(), planParkMs());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        // path B, the plan hold: re-ask CancelGate after the park.
                        String stoppedMeanwhile = com.moddingfromamod.marshal.CancelGate.refusalFor(
                                marshal, sessionId, name);
                        if (stoppedMeanwhile != null
                                || planState == com.moddingfromamod.marshal.PickupHold.State.STOPPED) {
                            out = ToolResult.error(stoppedMeanwhile != null ? stoppedMeanwhile
                                    : com.moddingfromamod.marshal.CancelGate.message(runNow));
                            ActionLog.toolCall(name, args, out, 0, wishId);
                            return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                        }
                    }
                    if (planState == com.moddingfromamod.marshal.PickupHold.State.REFUSED) {
                        out = ToolResult.error(planHold.refusal(runNow.wishId()));
                        ActionLog.toolCall(name, args, out, 0, wishId);
                        return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                    }
                    if (planState == com.moddingfromamod.marshal.PickupHold.State.PENDING) {
                        out = ToolResult.error("The grown-ups' check on your plan is still running. "
                                + "Nothing is wrong — wait a moment, then call " + name + " again.");
                        ActionLog.toolCall(name, args, out, 0, wishId);
                        return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                    }
                    if (planState == com.moddingfromamod.marshal.PickupHold.State.CLEARED) {
                        String n = planHold.takeNote(runNow.wishId());
                        releaseNote = releaseNote.isEmpty() ? n : n.isEmpty() ? releaseNote : releaseNote + "\n\n" + n;
                    }
                }
                // the two tools that start a worker WRITING ITS OWN — author_datapack and
                // cbscript_help — wait (≤ WishNotes.PARK_MS) for a plan-time library nudge still in
                // flight, so a ready-made answer lands before the hand-written one starts. Nothing
                // else waits, and with every nudge off nothing is ever pending.
                if (runNow != null && ("author_datapack".equals(name) || "cbscript_help".equals(name))
                        && com.moddingfromamod.readymade.WishNotes.global().pending(runNow.wishId().toString())) {
                    try {
                        com.moddingfromamod.readymade.WishNotes.global().await(runNow.wishId().toString(),
                                com.moddingfromamod.readymade.WishNotes.PARK_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // a third park, so the same re-check.
                    String stoppedMeanwhile = com.moddingfromamod.marshal.CancelGate.refusalFor(
                            marshal, sessionId, name);
                    if (stoppedMeanwhile != null) {
                        out = ToolResult.error(stoppedMeanwhile);
                        ActionLog.toolCall(name, args, out, 0, wishId);
                        return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                    }
                }
                // A QUESTION IS ANSWERED AFTER LOOKING. "Let me have a look…" is promised to
                // the child the moment a question is routed; this is what keeps the promise — the
                // tools that ANSWER refuse until the wish has made one successful read, and give
                // way after LookFirst.MAX_REFUSALS so a question can always end.
                if (runNow != null) {
                    String look = com.moddingfromamod.wish.LookFirst.refusal(name,
                            com.moddingfromamod.wish.WishIntent.isQuestion(runNow.text()),
                            runNow.looked(), runNow.lookRefusals());
                    if (look != null) {
                        runNow.lookRefused();
                        out = ToolResult.error(look);
                        ActionLog.toolCall(name, args, out, 0, wishId);
                        return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                    }
                }
                // ── PLAN FIRST (D16) ─────────────────────────────────────────────────────
                //
                // A world-changing tool REFUSES, naming plan_wish, until the bound wish has had a
                // successful plan — guardrail 2, enforced where tools RUN rather than hoped for in
                // the prompt (Opus skipped the plan in 94 of 351 grants in an earlier pass, Muse in 115 of 149).
                // Beside SafetyPlanGate for the same reason: the plan is what the plan safety grade
                // reads, what the child's plan line shows, and what T17 prices a fresh wish from.
                // Only for a REAL tool: an unknown name is answered "No such tool" below, not told
                // to plan for a tool that does not exist. CancelGate's shape — it refuses before
                // the narrator or the body, so nothing a refused call might have done happens.
                if (runNow != null && tool != null) {
                    String plan =com.moddingfromamod.marshal.PlanFirst.refusal(
                            name, true, runNow.planned(), budgetedWorld.getAsBoolean());
                    if (plan != null) {
                        out = ToolResult.error(plan);
                        ActionLog.toolCall(name, args, out, 0, wishId);
                        return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                    }
                    // 2026-10-21: finish_building ENDS the wish for the child, and the
                    // photograph after it is for grading only, never another turn of the crank.
                    // 2026-10-22: except a polish IN PLACE of this wish's own thing.
                    String over = com.moddingfromamod.marshal.AfterFinish.refusal(name,
                            com.moddingfromamod.wish.BuildGate.forWish(runNow.wishId())
                                    .wasFinishBuildingCalled(), args, madeIds(runNow));
                    if (over != null) {
                        out = ToolResult.error(over);
                        ActionLog.toolCall(name, args, out, 0, wishId);
                        return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                    }
                }
                // Where the record of creatures standing in the world is, before the call (the early
                // reveal, 2026-10-21): afterwards, anything newer stood up DURING it.
                final long stoodMark = com.moddingfromamod.vanilla.RigReveal.stoodMark();
                if (tool == null) {
                    out = ToolResult.error("No such tool: " + name);
                } else {
                    // REPAIR A NAMING NEAR-MISS BEFORE THE TOOL EVER SEES IT. The refusals in the
                    // real runs were `commands` for `command` and `message` for `doing` -- not
                    // absent fields, differently-named ones, which no amount of marking a field
                    // REQUIRED can reach. Conservative by construction (ArgAliases): the only
                    // transition it can cause is refusal -> acceptance. One hook here covers every
                    // tool, for the same reason the narrator and the action log sit here.
                    aliased = ArgAliases.normalise(tool.inputSchema(), args);
                    // ── THE REACH RULE ───────────────────────────────────────────────────
                    //
                    // On an islands world a wish lands on its caster's island and nowhere else.
                    // Here, beside PlanFirst and the plan safety grade, because this is where every
                    // tool RUNS; after ArgAliases so the rule reads the schema's field names (an
                    // `offset_forward` near-miss must not slip a build past the offset check).
                    // CancelGate's shape: it refuses before the narrator or the body, so nothing a
                    // refused call might have done happens. Which coordinates count, per tool, is
                    // BaseReach.RULES — and run_command's gap is written down there.
                    //
                    // The world-rules grader decided the WISH at pickup; this judges what the
                    // agent actually DOES, so every refusal here is a wish the grader let through —
                    // counted as a grader miss (WorldRulesLog.backstop), per rule.
                    com.moddingfromamod.world.WorldRules inForce = rules.get();
                    com.moddingfromamod.world.Bases bases = islands.get();
                    com.moddingfromamod.world.BaseReach.Refusal reach = runNow == null ? null
                            : com.moddingfromamod.world.BaseReach.judge(name, args, runNow.playerId(),
                                    runNow.anchor(), bases, inForce);
                    if (reach != null) {
                        com.moddingfromamod.world.WorldRulesLog.backstop(wishId,
                                runNow.playerId() == null ? "" : runNow.playerId().toString(), name,
                                reach.effect(), inForce);
                        // which rule refused, as data on the wish: a make with no agent says it to the child
                        // (BuilderMake), where the agent would have read these words and explained them.
                        runNow.refusedByWorld(com.moddingfromamod.world.WorldRefusal.of(reach.effect(), inForce,
                                bases == null ? null : bases.noun()));
                        out = ToolResult.error(reach.words());
                        ActionLog.toolCall(name, args, out, 0, wishId);
                        return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
                    }
                    // NO BOXES IN HIS CHAT. Every argument a child reads is stripped of
                    // code points a stock client draws as a box (U+FE0F behind "☀️" was the live
                    // one). The same field list the safety sweep reads, and like ArgAliases it
                    // rewrites rather than refuses — see ChildGlyphs.cleanArgs for why. Not a
                    // safety layer: it decides nothing about what may be said.
                    com.moddingfromamod.wish.ChildGlyphs.cleanArgs(name, args);

                    // NARRATE WHILE THE MODEL CANNOT. Shows any `status` the agent attached to
                    // the work immediately -- a whole set_status round trip, ~4s, removed from
                    // every authoring turn -- then talks to the child during the call itself, which
                    // is a window the agent is structurally incapable of filling: it is mid
                    // inference and cannot send anything. Same argument as the action log below:
                    // one hook here covers every tool, now and in future.
                    var narrator = com.moddingfromamod.wish.ToolNarrator.begin(name, args);
                    try {
                        // ── CHILD SAFETY, L2 (docs/CHILD_SAFETY_PLAN.md §3) ──────────────────
                        //
                        // The plan grade, and it sits HERE rather than three lines up beside the
                        // CancelGate for two deliberate reasons, both of which change what the
                        // check is able to see:
                        //
                        //  · AFTER ArgAliases.normalise, so the grader reads the SCHEMA's field
                        //    names. An agent that sent `stages` for `steps` would otherwise be
                        //    graded on an empty plan — and a grader shown nothing passes
                        //    everything, which is the quietest way for a safety layer to switch
                        //    itself off.
                        //  · AFTER ToolNarrator.begin, so the child is not watching a frozen
                        //    screen for the 11.5-38.5s the grade costs (MEASURED live, Gate C½,
                        //    2026-09-11: an ordinary plan 11.5s, one that leans 38.5s — the band
                        //    drives it). plan_wish was ALREADY narratable, with beats at
                        //    2.5s/12s/25s that until now could never fire because the tool itself
                        //    returns in milliseconds. They read exactly right for a plan being
                        //    thought about, which is what is now happening.
                        //
                        // Everything else is CancelGate's shape: it REFUSES rather than informs, so
                        // the tool body never runs and WishFeedback.planned never shows the child
                        // "✨ Here's my plan:" for a stage that is about to be diverted.
                        //
                        // The last argument is the WISH's id, not the child's: the one-shot divert
                        // budget is per wish (§12.11). Keyed by player it was reset by the next
                        // wish of the same child and shared between two in flight, which loses a
                        // stop in one direction and causes one in the other.
                        //
                        // ASYNC for plan_wish — the grade runs BESIDE the agent's next turn
                        // (planGradeLater) instead of holding this call for its 3–4.5 s. Nothing the
                        // child would see waits less: what the plan shows him is held (PlanDeferral)
                        // until the grade passes, and every tool that builds or speaks parks behind
                        // the plan hold (the ratchet above) until then. A refusal reaches the agent on
                        // its next parked call, with the same message, and it revises in place.
                        // A Wish Builder make is a lowering of reviewed picks, not a
                        // model's plan: L2 skips it (WishRun#builderMade); L0 below still runs.
                        final boolean builderMade = runNow != null && runNow.builderMade();
                        final boolean asyncPlan = "plan_wish".equals(name) && runNow != null && !builderMade
                                && com.moddingfromamod.safety.ChildSafetyConfig.grading()
                                && com.moddingfromamod.safety.SafetyGrader.gradesTakeTime();
                        var safety = asyncPlan || builderMade ? com.moddingfromamod.safety.SafetyPlanGate.Decision.PASS
                                : com.moddingfromamod.safety.SafetyPlanGate.decide(
                                        name, args, runNow == null ? "" : runNow.text(), wishId,
                                        runNow == null ? null : runNow.wishId());
                        // ── CHILD SAFETY, L0 (docs/CHILD_SAFETY_PLAN.md §3 L0) ───────────────
                        //
                        // Every word the agent is about to put in front of the child, swept
                        // against the lexicon. Deterministic, microseconds, and on the path of
                        // every tool call — so SafetyTextSweep exits before reading the arguments
                        // at all for a tool with no child-visible text, which is most of them.
                        //
                        // It sits AFTER the plan grade rather than before it, and for plan_wish
                        // that ordering is a decision rather than an accident:
                        //
                        //  · plan_wish's own `status` and `steps` ARE child-visible, so it is
                        //    swept like any other tool. The two layers write SEPARATE records
                        //    (layer:"plan" and layer:"text"), which is not double-logging — they
                        //    answer different questions, and §6's whole point is that a decision
                        //    can be explained afterwards.
                        //  · a DIVERTED plan never reaches the sweep, and that is correct: the
                        //    tool body did not run either, so no word of it reached the child and
                        //    a "text" record would be describing something that never happened.
                        //  · the cost of grading first is a lexicon hit paying for an 11.5-38.5s
                        //    model call before an instant check refuses it. That is rare enough to
                        //    be unmeasurable (23 unambiguous terms, in text the agent composed)
                        //    and it buys one readable block instead of two interleaved ones.
                        //
                        // ⚠ THE SWEEP RUNS INSIDE THE `else`, which is what makes the third
                        // bullet above true of the code rather than only of the comment. It was
                        // computed above this branch, unconditionally — and `SafetyTextSweep`
                        // LOGS what it finds, so a DIVERTED plan whose own `status` or `steps[]`
                        // also tripped the lexicon left a `layer:"text"` record about a sentence
                        // the tool body never ran and no child ever read. Narrow, and
                        // exactly the sort of record §6 cannot afford: an action log that
                        // describes things which did not happen cannot be counted.
                        if (safety.refuses()) {
                            if (safety.act()
                                    == com.moddingfromamod.safety.SafetyPlanGate.Act.STOP) {
                                com.moddingfromamod.safety.SafetyPlanGate.endWish(marshal, runNow);
                            }
                            // the refused plan is in this worker's context now; it finishes
                            // this wish (a divert is revised in place) and never takes another.
                            com.moddingfromamod.marshal.PickupHold.global().retireAfterWish(sessionId);
                            out = ToolResult.error(safety.message());
                        } else {
                            var sweep = com.moddingfromamod.safety.SafetyTextSweep.sweep(
                                    name, args, wishId);
                            if (sweep.refuses()) {
                                // REFUSES rather than sanitises, for CancelGate's reason: silently
                                // rewriting what the agent said would leave it believing it had
                                // said the other thing, and it would say it again next call.
                                out = ToolResult.error(sweep.message());
                            } else {
                                // ── NOT THE AGENT'S MACHINERY EITHER ──────────────────
                                // A child-visible field that names a tool or narrates the
                                // agent's own steps ("Question answered; nothing built, so no
                                // finish_building.") is blanked before the body runs and the
                                // agent is told below. Coerced, not refused: rewording is always
                                // possible, and a refusal could only risk a wish that cannot end.
                                final var heldBack = com.moddingfromamod.safety.AgentInternals.scrub(name, args);
                                // WHO IS CALLING, for the length of the call. With more than one
                                // wish in flight "what is the current wish?" has no answer unless
                                // you also say who is asking; an MCP session is one parked agent,
                                // so it is the worker id.
                                final McpTool called = tool;
                                // #69: what this world allows, fitted into the arguments a make fact measures, AFTER
                                // safety and the world's rules (a refused call never gets here); the core's note is
                                // added to the result below. No world answering: nothing is touched.
                                final var fittedArgs = runNow != null && com.moddingfromamod.wish.MakeGate.anyWorld()
                                        ? ToolArgFit.fit(name, args,
                                                com.moddingfromamod.wish.MakeGate.limits(runNow.playerId()).limits())
                                        : java.util.List.<ToolArgFit.Lowered>of();
                                if (asyncPlan) {
                                    // Opened BEFORE the body, so what it shows the child is held.
                                    com.moddingfromamod.marshal.PlanDeferral.global().hold(runNow.wishId());
                                    com.moddingfromamod.marshal.PickupHold.plans().open(runNow.wishId());
                                }
                                // …and FOR WHICH WISH, so a photograph it takes is filed under that
                                // wish for the operator's tools (WishShots).
                                out = com.moddingfromamod.wish.WishShots.during(wishId,
                                        () -> AgentContext.with(sessionId, () -> called.call(args)));
                                if (asyncPlan) {
                                    if (out.isError()) {
                                        com.moddingfromamod.marshal.PlanDeferral.global().forget(runNow.wishId());
                                        com.moddingfromamod.marshal.PickupHold.plans().forget(runNow.wishId());
                                    } else {
                                        planGradeLater(marshal, runNow, args.deepCopy(), wishId, sessionId);
                                        out = new ToolResult(out.text() + "\n\n" + PLAN_BEING_CHECKED,
                                                false, out.imageBase64(), out.imageMime());
                                    }
                                }
                                if (!fittedArgs.isEmpty() && !out.isError()) {
                                    LOGGER.info("[world fit] wish {}: {} fitted {}", wishId, name, fittedArgs);
                                    out = new ToolResult(out.text() + ToolArgFit.notes(fittedArgs), false,
                                            out.imageBase64(), out.imageMime());
                                }
                                if (!heldBack.isEmpty()) {
                                    LOGGER.info("[internals] wish {}: {} held back {}", wishId, name, heldBack);
                                    out = new ToolResult(out.text()
                                            + com.moddingfromamod.safety.AgentInternals.notice(name, heldBack),
                                            out.isError(), out.imageBase64(), out.imageMime());
                                }
                                // A MAYBE plan is the child's own plan and it gets built. The
                                // guidance rides out on the result the agent was going to read
                                // anyway, which is why this half cannot be a pre-emptive hook.
                                out = com.moddingfromamod.safety.SafetyPlanGate.annotate(out, safety);
                            }
                        }
                    } catch (Throwable t) {
                        // A throwing tool must not look like a broken connection. The AGENT gets
                        // the message so it can adapt; the game log gets the STACK TRACE, because
                        // "Tool blew up: IllegalStateException" says what but never where, and the
                        // grown-up reading latest.log afterwards is the only one who can fix it.
                        LOGGER.warn("wish tool '{}' threw", name, t);
                        out = ToolResult.error("Tool blew up: " + t);
                    } finally {
                        // UNCONDITIONAL. A narrator that outlives its call leaves a stale line on
                        // screen, which is the 52-second-stale-status bug it exists to remove.
                        narrator.stop();
                    }
                }
                // What this wish has actually used, so "can I honestly offer [undo]?" is answered
                // from the record rather than from a guess. Only tools that ran, and only ones that
                // changed something — a look_around can never be why undo is unavailable.
                var running = marshal.runForWorker(sessionId);
                if (running != null && !out.isError() && com.moddingfromamod.wish.LookFirst.isRead(name)) {
                    running.lookedWith(name);   //: this question has now looked
                }
                // Only a plan that went THROUGH counts — a diverted or refused plan_wish is
                // an error result, and it leaves the wish unplanned.
                if (running != null && !out.isError() && ("finish_building".equals(name) || "finish_wish".equals(name))) {
                    // A wish whose thing never passed a making tool still gets its moment,
                    // and a creature gets its summoning circle once it really stands there. On
                    // finish_wish too, for a wish that never called finish_building; played once.
                    com.moddingfromamod.wish.reveal.Reveals.onFinished(running);
                }
                if (running != null && tool != null && !out.isError()
                        && com.moddingfromamod.wish.BuildGate.forWish(running.wishId())
                                .wasFinishBuildingCalled()) {
                    // A polish after the finish (AfterFinish.polish): a glint, and one quiet line.
                    com.moddingfromamod.wish.reveal.WishPolish.afterTool(running, name, args,
                            com.moddingfromamod.marshal.AfterFinish.polish(name, args, madeIds(running)));
                }
                if (running != null && tool != null) {
                    // A creature that came to stand in the world during this call bursts the
                    // pillar now and shimmers until finish_building (WishEarlyReveal).
                    com.moddingfromamod.wish.reveal.WishEarlyReveal.afterTool(running, name, stoodMark,
                            args, !out.isError());
                }
                // the library nudge — every nudged kind, at the hand-over. It runs beside the
                // worker and posts to WishNotes; a no-op with the nudges off.
                // the worker's first BUILD call that RAN decides whether the hand-over nudge beat
                // it, timed from when the call began (NudgeRace → ActionLog `readymade_race`). A refused call committed to nothing, so it decides nothing.
                if (running != null && !out.isError()) {
                    com.moddingfromamod.readymade.ReadyMadeLive.called(running.wishId().toString(), name,
                            startedAt);
                }
                if (running != null && !out.isError() && "await_wish".equals(name)) {
                    com.moddingfromamod.readymade.ReadyMadeLive.atHandOver(running.wishId().toString(), running.text(),
                            out.text());
                    // the reference for a named character rides the next reply once it lands.
                    com.moddingfromamod.reference.ReferenceRideAlong.atHandOver(running.wishId().toString(),
                            running.text(), com.moddingfromamod.readymade.WishNotes.global()::deliver);
                }
                if (running != null && !out.isError() && "plan_wish".equals(name)
                        && com.moddingfromamod.marshal.PickupHold.plans().state(running.wishId())
                                != com.moddingfromamod.marshal.PickupHold.State.PENDING) {
                    // an async plan is marked planned by its grade (planGradeLater), not here.
                    running.markPlanned();
                }
                if (running != null && !out.isError()
                        && com.moddingfromamod.marshal.UndoContract.touchesTheWorld(name)) {
                    // A run_command that only HANDS THINGS OVER (a give, a made item's
                    // `wishdatapack <id> give`) is a hand-over, with a hand-over's inverse.
                    running.usedTool("run_command".equals(name)
                            && com.moddingfromamod.marshal.UndoContract.onlyHandsOver(commandsOf(args))
                            ? "give_items" : name);
                    // what the call MADE, so finish_wish can ask whether the closing line
                    // promises a part no call built.
                    running.calledWith(name, args);
                    // did anything this wish asked for actually change his world? A look-up
                    // or a plan does not; the rule that reads this lives in FinishWishTool.
                    if (com.moddingfromamod.wish.WorldChanging.tool(name)) {
                        running.changedWorld();
                    }
                    // a thing made and then taken away again inside one wish is a
                    // superseded version, not one of the wish's parts.
                    String removedKind = com.moddingfromamod.marshal.MadeKind.forRemoveTool(name);
                    String removedField = com.moddingfromamod.marshal.MadeKind.idField(name);
                    if (removedKind != null && args.has(removedField)
                            && args.get(removedField).isJsonPrimitive()) {
                        running.removed(removedKind, args.get(removedField).getAsString());
                    }
                    String madeKind = com.moddingfromamod.marshal.MadeKind.forTool(name);
                    if (madeKind != null) {
                        String field = com.moddingfromamod.marshal.MadeKind.idField(name);
                        if (args.has(field) && args.get(field).isJsonPrimitive()) {
                            String madeId = args.get(field).getAsString();
                            running.made(madeKind, madeId, madeBefore(madeKind, madeId));
                            magicianTouched(running, madeKind, madeId);
                        } else {
                            // DERIVED IDS. An item's id is computed from its first name and kept
                            // for life, so there is no argument to read and this hook recorded
                            // nothing — undo left the item behind. Opt-in, so a tool that does not
                            // report one still gets no record, which is the safe direction.
                            String reported = tool.lastMadeId();
                            if (reported != null && !reported.isBlank()) {
                                running.made(madeKind, reported, madeBefore(madeKind, reported));
                                magicianTouched(running, madeKind, reported);
                            }
                        }
                        // WHAT HE IS TALKING ABOUT NOW —.
                        //
                        // The stickiness map existed and nothing in production wrote to it, so
                        // "make him taller" in a world that already holds last week's creatures had
                        // three candidates and no reason to prefer the one made ninety seconds ago.
                        // This is the one place every artifact a wish makes or edits passes through.
                        String subject = args.has(field) && args.get(field).isJsonPrimitive()
                                ? args.get(field).getAsString() : tool.lastSubjectId();
                        com.moddingfromamod.wish.WishTopic.remember(running.playerId(), subject,
                                madeKind, running.text());
                        // The grand reveal — the orb becomes this kind's moment (queued; the
                        // server thread starts it). A building's dome is the Builder's, not this.
                        com.moddingfromamod.wish.reveal.Reveals.queue(running, madeKind, subject);
                        // …AND WHETHER IT WAS THE THING THE WISH IS ABOUT. A wish holds a
                        // lease on the artifact it resolved to; making a DIFFERENT one is how the
                        // child's "it" silently rebinds and never comes back. Empty leases mean a
                        // creation, where there is nothing to drift from.
                        java.util.List<String> about = running.leases();
                        if (!about.isEmpty() && subject != null && !subject.isBlank()
                                && !about.contains(subject)) {
                            com.moddingfromamod.agent.ActionLog.drifted(
                                    running.shortId(), String.join(",", about), subject, name);
                        }
                    }
                }
                // A RULE IS A THING HE MADE TOO. set_rule has no undo kind (MadeKind), so the
                // hook above never named it, and "make the cloud bounce me higher" reached the agent
                // with nothing saying his last wish made a cloud rule.
                if (running != null && !out.isError() && "set_rule".equals(name)) {
                    String rule = ruleTouched(args);
                    if (rule != null) {
                        com.moddingfromamod.wish.WishTopic.remember(running.playerId(), rule, "rule",
                                running.text());
                    }
                }
                // what the pickup verdicts said about this wish (a MAYBE's guidance, a world-rules
                // note), riding the first call that waited for them — the hand-over could not carry it.
                // …and what a library nudge found, on the first good reply after it landed.
                if (running != null && !out.isError()) {
                    String lib = com.moddingfromamod.readymade.WishNotes.global().drain(running.wishId().toString());
                    if (!lib.isEmpty()) {
                        releaseNote = releaseNote.isEmpty() ? lib : releaseNote + "\n\n" + lib;
                    }
                }
                if (!releaseNote.isEmpty() && !out.isError()) {
                    out = new ToolResult(releaseNote + "\n\n" + out.text(), false, out.imageBase64(),
                            out.imageMime());
                }
                // and on a briefed wish, nothing the worker reads names the character (a lookup's
                // verdict, a colour check, a list of creatures): the name goes back to {{NAME}}.
                if (running != null && com.moddingfromamod.reference.WishBriefs.veiled(running.wishId())) {
                    out = new ToolResult(com.moddingfromamod.reference.WishBriefs.veil(running.wishId(), out.text()),
                            out.isError(), out.imageBase64(), out.imageMime());
                }
                // One hook here covers every tool, now and in future — the trail we mine to work
                // out what the child keeps asking for that the mod can't do well yet.
                ActionLog.toolCall(name, args, out, System.currentTimeMillis() - startedAt, wishId,
                        aliased);
                return HttpReply.json(200, rpcResult(id, toolContent(out)), sessionId);
            }
            case "resources/list" -> {
                JsonObject result = new JsonObject();
                result.add("resources", new JsonArray());
                return HttpReply.json(200, rpcResult(id, result), sessionId);
            }
            case "prompts/list" -> {
                JsonObject result = new JsonObject();
                result.add("prompts", new JsonArray());
                return HttpReply.json(200, rpcResult(id, result), sessionId);
            }
            default -> {
                return error(200, id, -32601, "Method not found: " + method, sessionId);
            }
        }
    }

    /**
     * {@code {content:[{type:"text",…}(,{type:"image",…})], isError:bool}}
     *
     * <p>The optional image block is what lets a tool <b>show</b> the agent something. It runs with
     * no file-reading tool, so a path in the text would be useless — the pixels have to ride in the
     * response itself.
     */
    /**
     * Whether an EARLIER granted wish made this thing, so this wish changing it is an edit
     * and not one of its parts. {@code ShelfTimes} hears of a wish's things only when that wish is
     * granted ({@code ActionLog.made}), so the wish in flight never finds its own.
     */
    /**
     * a WISH (not a Builder make) just made or changed a thing. If the thing is one of his Builder makes, the
     * wish changed it, and its kept spec no longer describes it: mark the row, or "Change it in the Builder" would
     * re-lower the old picks over the Magician's change. Matched by the id the tool used and, for an item, by its
     * current name (an older row knows an item only by the name it was made under). Deliberately NOT gated on
     * {@link #madeBefore}: that knows only what a GRANTED agent wish recorded, so a Builder make is "not made before"
     * to it, and the mark would never fire. A new thing has a fresh id, matches no row, and writes nothing. Never
     * fatal: a failed mark leaves an earlier issue's behaviour as it was.
     */
    static void magicianTouched(com.moddingfromamod.marshal.WishRun running, String kind, String id) {
        if (running == null || running.builderMade() || running.playerId() == null) {
            return;
        }
        try {
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            names.add(id);
            if (com.moddingfromamod.marshal.MadeKind.ITEM.equals(kind)) {
                var spec = com.moddingfromamod.item.ItemSpecStore.get(id);
                if (spec != null && spec.name() != null) {
                    names.add(spec.name());
                }
            }
            var marked = com.moddingfromamod.wishbuilder.BuilderSpecStore.touch(running.playerId().toString(), names,
                    running.wishId().toString());
            if (!marked.isEmpty()) {
                LOGGER.info("builder makes {} changed by wish {}: no longer offered as a Builder edit (#2238)", marked,
                        running.wishId());
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("could not mark a Builder make changed by wish {}: {}", running.wishId(), e.toString());
        }
    }

    public static boolean madeBefore(String kind, String id) {
        try {
            return com.moddingfromamod.store.ShelfTimes.madeAt(kind, id) > 0L;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code run_command}'s commands, a string or an array of them, as it reads them. */

    /** The rule a set_rule call made or changed, or null for a list or a removal. Pure. */
    static String ruleTouched(JsonObject args) {
        if (args == null || !args.has("name") || !args.get("name").isJsonPrimitive()) {
            return null;
        }
        for (String flag : new String[] {"list", "remove"}) {
            if (args.has(flag) && args.get(flag).isJsonPrimitive() && args.get(flag).getAsBoolean()) {
                return null;
            }
        }
        String n = com.moddingfromamod.rules.WorldRule.clean(args.get("name").getAsString());
        return n.isEmpty() ? null : n;
    }

    static List<String> commandsOf(JsonObject args) {
        List<String> out = new java.util.ArrayList<>();
        var raw = args == null ? null : args.get("command");
        if (raw == null) {
            return out;
        }
        if (raw.isJsonArray()) {
            for (var e : raw.getAsJsonArray()) {
                if (e != null && e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
        } else if (raw.isJsonPrimitive()) {
            out.add(raw.getAsString());
        }
        return out;
    }

    private static JsonObject toolContent(ToolResult result) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", result.text());
        JsonArray content = new JsonArray();
        content.add(text);
        if (result.hasImage()) {
            JsonObject image = new JsonObject();
            image.addProperty("type", "image");
            image.addProperty("data", result.imageBase64());
            image.addProperty("mimeType", result.imageMime());
            content.add(image);
        }
        JsonObject out = new JsonObject();
        out.add("content", content);
        out.addProperty("isError", result.isError());
        return out;
    }

    private static String rpcResult(JsonElement id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o.toString();
    }

    private static HttpReply error(int status, JsonElement id, int code, String message) {
        return error(status, id, code, message, null);
    }

    private static HttpReply error(int status, JsonElement id, int code, String message, String session) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        if (id != null) {
            o.add("id", id);
        } else {
            o.add("id", com.google.gson.JsonNull.INSTANCE);
        }
        o.add("error", err);
        return HttpReply.json(status, o.toString(), session);
    }

    /** Case-insensitive header lookup, so callers don't have to normalise. */
    public interface Headers {
        String get(String lowerCaseName);

        static Headers of(Map<String, String> map) {
            return name -> {
                for (Map.Entry<String, String> e : map.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(name)) {
                        return e.getValue();
                    }
                }
                return null;
            };
        }
    }

    /** The ids of everything a wish has made so far. */
    private static java.util.List<String> madeIds(com.moddingfromamod.marshal.WishRun run) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var m : run.made()) {
            out.add(m.id());
        }
        return out;
    }

}
