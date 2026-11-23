package com.moddingfromamod.worker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code claude} command line a wish worker is launched with — <b>Minecraft-free on purpose</b>.
 *
 * <p>HO-6. The agent is a subprocess of the server JVM today, so one method built this argv and
 * nothing else needed it. Split across a container boundary a second process has to build the same
 * line, and <b>that argv is a security boundary</b>: {@code --tools} {@value #BUILT_INS} is what
 * takes Bash, Read, Write, WebFetch and the sub-agent out of the agent's hands (—
 * before it, {@code --permission-mode dontAsk} plus an MCP-only {@code --allowed-tools} was believed
 * to, and did not), and {@code dontAsk} is what stops a detached session parking on a prompt. Two copies of a permission set is the shape this repo's standing rule is about — a
 * defect found in one copy is a defect CLASS found — so there is one builder and both callers use
 * it: {@code AgentSession.startOne} in the server, {@link WorkerMain} in the worker container.
 *
 * <p>⚠ <b>Why it takes the tool list rather than computing it.</b> {@code AgentSession.allowedTools()}
 * derives the list from {@code WishAgent.buildTools()}, which imports Minecraft — so calling it
 * would drag the whole game onto a worker's classpath to read fifty strings. The list is therefore
 * a <b>generated, committed resource</b> ({@value #RESOURCE}) that a Gate A test regenerates and
 * compares, so it cannot drift from the registry and nobody maintains it by hand. Generated beats
 * both alternatives that were considered: a hand-written list rots, and serving it over the MCP
 * port would put the permission set on the wire on the one port where that trade is worst.
 *
 * <p>⚠ <b>And the worker does not trust the list blindly.</b> {@link #safe} drops anything that is
 * not an {@code mcp__wishwatch__} tool before it reaches {@code --allowed-tools}. Nothing should
 * ever be dropped; the point is that the one thing this list must never contain — a shell — cannot
 * arrive through it, whatever wrote the file.
 */
public final class WorkerArgv {

    /** The generated list, regenerated and compared by {@code AllowedToolsResourceTest}. */
    public static final String RESOURCE = "/agent/allowed-tools.txt";

    /**
     * Settings layered onto every worker's session: <b>Remote Control off</b>.
     *
     * <p>Claude Code starts a Remote Control bridge for every session when the user's own settings
     * say {@code remoteControlAtStartup: true}, or an org default turns it on. Its help text calls it
     * "Start Remote Control bridge automatically each session". A worker runs on the machine's
     * sign-in, so it inherited that, and every wish worker showed up as a remote session in the
     * player's Claude app. A setting passed with {@code --settings} sits above the user's own in
     * Claude Code's precedence, so this turns it off for Moddy's workers without touching the
     * player's settings for anything else. A managed (org-enforced) setting still wins, by design.
     *
     * <p>This is NOT {@code tools/wish-agent.sh}'s deliberate {@code --remote-control} session,
     * which is an opt-in developer steering surface and is not a wish worker.
     */
    public static final String SETTINGS = "{\"remoteControlAtStartup\":false}";

    /** Every pre-approved tool must be one of ours. See the class javadoc. */
    public static final String PREFIX = "mcp__wishwatch__";

    /**
     * The ONLY built-in tool a worker is offered: {@code ToolSearch}, and nothing else.
     *
     * <p>Measured with the CLI's own {@code init} event (2026-09-28, CLI 2.1.284), per host:
     * <pre>
     *   --tools          Anthropic host (Opus)                   redirected host (Muse)
     *   (absent)         39 built-ins incl. Bash/Read/Task/Write 23 built-ins incl. Bash/Read/Task
     *   "ToolSearch"     ToolSearch + the wish tools             the wish tools only
     *   ""               the wish tools only                     the wish tools only
     * </pre>
     * So one value is right on both. On Anthropic's host the wish tools are DEFERRED and
     * {@code ToolSearch} is how they are loaded — {@code ""} would take it away and change how Opus
     * works, which this fix was required not to do (that change is a separate one to measure).
     * On a redirected host Claude Code switches tool search off and puts every wish tool up front,
     * and {@code "ToolSearch"} offers exactly what {@code ""} does. {@code SafetyGrader} passes
     * {@code ""} because it has no MCP tools to load; a worker has fifty.
     *
     * <p>Why it matters: before this, production (Muse through the redirect) executed 1,169 Bash
     * calls, 36 Reads and 34 sub-agents in 58 worker transcripts (2026-09-24…28) — among them
     * {@code cat} of the worker's {@code mcp.json} and {@code curl} to the MCP port with its bearer.
     *
     * <p>⚠ {@code --allowed-tools} is a PERMISSION list, not this. It pre-approves; it removes
     * nothing from what the model is offered, and the CLI ran "read-only" built-ins without asking.
     */
    public static final String BUILT_INS = "ToolSearch";

    private WorkerArgv() {
    }

    /** The committed tool list, as the CLI wants it: comma-separated, already filtered. */
    public static String allowedToolsFromResource() throws IOException {
        try (InputStream in = WorkerArgv.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("the generated tool list " + RESOURCE + " is not on the "
                        + "classpath. Without it a worker would launch with NO pre-approved tools "
                        + "and, under --permission-mode dontAsk, every tool call would be denied — "
                        + "a worker that starts, answers nothing, and looks like a dead agent.");
            }
            return safe(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Keep only {@code mcp__wishwatch__*} entries, in order, de-duplicated.
     *
     * <p>Accepts the list in either shape — comma-separated or one per line — because the resource
     * is generated and a caller holding the live string should not have to reformat it.
     */
    public static String safe(String raw) {
        if (raw == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String part : raw.split("[,\\r\\n]")) {
            String t = part.trim();
            if (t.startsWith(PREFIX) && t.length() > PREFIX.length() && !kept.contains(t)) {
                kept.add(t);
            }
        }
        return String.join(",", kept);
    }

    /**
     * Build the command line.
     *
     * <p>The order matters and is not cosmetic: {@code --bg} takes its prompt POSITIONALLY, so the
     * prompt follows the name immediately and the cache flags go LAST — nothing may come between
     * {@code --bg} and its positional argument.
     *
     * @param claudeCli   the CLI binary
     * @param name        the session name, which is what {@code claude agents} and the phone show
     * @param prompt      the worker's opening prompt
     * @param mcpConfig   path to the {@code mcp.json} that points at the wish server
     * @param allowedTools comma-separated, already {@link #safe}
     * @param model       {@code ""} for the CLI's own default
     * @param effort      {@code ""} for the CLI's own default
     * @param cacheFlags  {@code PromptCache.flags()} — conditional, so possibly empty
     */
    public static List<String> build(String claudeCli, String name, String prompt, String mcpConfig,
                                     String allowedTools, String model, String effort,
                                     List<String> cacheFlags) {
        List<String> cmd = new ArrayList<>(List.of(
                claudeCli, "--bg", "-n", name, prompt,
                "--mcp-config", mcpConfig, "--strict-mcp-config",
                // Auto-DENY anything that would otherwise prompt. --allowed-tools only
                // PRE-APPROVES; it does not restrict, and a prompt in a detached session with
                // stdin on /dev/null is a prompt nobody can ever answer — the agent parks
                // blocked FOREVER and every later wish sits PENDING while the child watches a
                // ticker run on.
                "--permission-mode", "dontAsk",
                "--allowed-tools", safe(allowedTools),
                // THE BUILT-IN SET, which is what actually bounds the worker. See
                // BUILT_INS for the measurement and for why the one survivor is ToolSearch.
                "--tools", BUILT_INS,
                // REMOTE CONTROL OFF, for this worker only. See SETTINGS.
                "--settings", SETTINGS));
        if (model != null && !model.isEmpty()) {
            cmd.add("--model");
            cmd.add(model);
        }
        if (effort != null && !effort.isEmpty()) {
            cmd.add("--effort");
            cmd.add(effort);
        }
        if (cacheFlags != null) {
            cmd.addAll(cacheFlags);
        }
        return cmd;
    }
}
