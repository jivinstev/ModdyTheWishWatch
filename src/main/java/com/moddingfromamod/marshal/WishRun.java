package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One wish being granted — <b>the thing that used to be a per-player global.</b>
 *
 * <p>Today's code keeps the anchor, the status line, the progress bar and the provenance stamp in
 * maps keyed by <i>player</i> (or in a single {@code volatile} field). That is correct exactly while
 * one wish exists at a time, and silently wrong the moment two do: the second wish's
 * {@code conjure_at} moves the first one's sparkles, and the second wish's artifacts get stamped
 * with the first one's intent — which is not a visible bug but a quiet corruption of the corpus that
 * drives the roadmap.
 *
 * <p>So everything that is <b>about one wish</b> lives here, and the components are handed the run
 * rather than looking a player up. That also fixes a test problem the repo already has on record:
 * GameTests in the same batch run interleaved on the one server thread, so anything asserting
 * on a singleton's statics fails
 * against another test rather than against the code.
 *
 * <p>Mutable, and every mutator is synchronised: a run is touched from the server thread (anchoring,
 * bars), from HTTP handler threads (every tool call), and from the narrator thread.
 */
public final class WishRun {

    private final UUID wishId;
    private final UUID playerId;
    private final String text;
    private final long createdMs;

    private RunState state = RunState.GETTING_READY;
    private String workerId;
    private long assignedMs = -1;
    private long finishedMs = -1;
    /** The last tool call by the holding worker started or ended here; -1 for none. */
    private long lastActivityMs = -1;
    /** Re-queued after its worker died of a spent quota or a failed login; -1 for never. */
    private long requeuedMs = -1;
    /** Tool calls by the holding worker that have started and not yet returned. */
    private int toolsInFlight;
    /**
     * the worker that took this wish off the queue and has not bound it yet (it is deciding the pickup
     * gates, or waiting for the wish's brief); null otherwise. {@code pickedUpMs} is when, or -1.
     */
    private String pickedUpBy;
    private long pickedUpMs = -1;

    private Spot anchor;
    private boolean anchorLocked;

    private String subject = "";
    private String about = "";
    private CancelMode cancelMode;
    private String cancelNote = "";
    private long cancelledMs = -1;
    private String redirectTo = "";

    private final Set<String> toolsUsed = new LinkedHashSet<>();
    private final Set<String> leases = new LinkedHashSet<>();

    /** A run no marshal holds, for a GameTest that needs a wish to exist (reveals). */
    public static WishRun detachedForTest(UUID wishId, UUID playerId, String text) {
        return new WishRun(wishId, playerId, text, 0L);
    }

    WishRun(UUID wishId, UUID playerId, String text, long createdMs) {
        this.wishId = wishId;
        this.playerId = playerId;
        this.text = text == null ? "" : text;
        this.createdMs = createdMs;
    }

    public UUID wishId() {
        return wishId;
    }

    public UUID playerId() {
        return playerId;
    }

    public String text() {
        return text;
    }

    public long createdMs() {
        return createdMs;
    }

    /**
     * Since when this wish has been waiting for a worker: its creation, or the moment it was put
     * back after its worker died. The claim clock runs from here, so a wish handed back
     * twenty minutes after it was made is not "never claimed" the instant it is re-queued.
     */
    public synchronized long waitingSinceMs() {
        return Math.max(createdMs, requeuedMs);
    }

    public synchronized RunState state() {
        return state;
    }

    public synchronized String workerId() {
        return workerId;
    }

    public synchronized long assignedMs() {
        return assignedMs;
    }

    public synchronized long finishedMs() {
        return finishedMs;
    }

    /**
     * <b>The worker that has taken this wish off the queue and not bound it yet</b>, or null. Between
     * the dequeue and {@code Marshal.take} a worker decides the pickup gates and may wait for the wish's
     * brief; somebody IS behind the wish then, so the claim ceiling must not cancel it as never claimed and
     * the supervisor must not count that worker as deaf.
     */
    public synchronized String pickedUpBy() {
        return workerId == null ? pickedUpBy : null;
    }

    /** When {@link #pickedUpBy} took it (epoch ms), or -1 when nobody holds it unbound. */
    public synchronized long pickedUpMs() {
        return workerId == null && pickedUpBy != null ? pickedUpMs : -1;
    }

    synchronized boolean pickedUp(String worker, long nowMs) {
        if (state.isFinished() || workerId != null || worker == null || worker.isBlank()) {
            return false;
        }
        if (!worker.equals(pickedUpBy)) {
            pickedUpBy = worker;
            pickedUpMs = nowMs;
        }
        return true;
    }

    /** The picking worker let it go (it died, or the wish went back to the queue): its claim clock runs again. */
    synchronized void dropPickup() {
        pickedUpBy = null;
        pickedUpMs = -1;
    }

    /**
     * <b>A tool call by the worker holding this wish has started</b> — the evidence the
     * no-progress watchdog reads ({@code WishTimeoutPolicy.Reason.NO_PROGRESS}). Stamped for every
     * MCP {@code tools/call} on the holding session, start and end, so a long tool body (a
     * reveal's camera wait has taken 108 s) is a call in flight and never "no progress".
     */
    public synchronized void toolStarted(long nowMs) {
        toolsInFlight++;
        lastActivityMs = Math.max(lastActivityMs, nowMs);
    }

    /** …and has returned. Always paired with {@link #toolStarted} by the caller's {@code finally}. */
    public synchronized void toolEnded(long nowMs) {
        if (toolsInFlight > 0) {
            toolsInFlight--;
        }
        lastActivityMs = Math.max(lastActivityMs, nowMs);
    }

    /** The later of the claim and the last tool call's start or end; -1 while unclaimed. */
    public synchronized long lastActivityMs() {
        return assignedMs < 0 ? -1 : Math.max(assignedMs, lastActivityMs);
    }

    public synchronized int toolsInFlight() {
        return toolsInFlight;
    }

    /**
     * What this wish is about, in the child's words — "your ice dragon". Used to name the pick out
     * loud and to label this wish's own progress bar, because "Building (1 of 4)" does not say which
     * of three stacked bars it belongs to.
     */
    /**
     * What to call this wish on screen.
     *
     * <p>What the resolver decided he meant ("your ice dragon"), falling back to a shortened version
     * of what he actually typed. <b>Never blank</b>, because it now labels a progress bar as well as
     * a watch row — and with three bars stacked, an unnamed one is precisely the confusion the name
     * is there to remove.
     *
     * <p>One copy of this rule. The watch had it privately; the bars needed the same answer, and two
     * places deciding what a wish is called is two places to disagree in front of a child.
     */
    public synchronized String displayName() {
        String s = subject.isEmpty() ? text : subject;
        String t = s == null ? "" : s.strip();
        return t.length() <= NAME_MAX ? t : t.substring(0, NAME_MAX - 1) + "…";
    }

    /** Long enough for "your enormous fire-breathing dragon", short enough for a bar. */
    public static final int NAME_MAX = 34;

    public synchronized String subject() {
        return subject;
    }

    public synchronized void subject(String s) {
        this.subject = s == null ? "" : s.strip();
    }

    /** What {@code plan_wish}'s {@code about} says a wish that makes something NEW is about. */
    public static final String ABOUT_NEW = "new";

    /**
     * <b>What the AGENT said this wish is about</b>: one of his artifact ids,
     * {@link #ABOUT_NEW}, or {@code ""} while it has not said. Set once per plan by
     * {@code plan_wish}; nothing local guesses it any more.
     */
    public synchronized String about() {
        return about;
    }

    public synchronized void about(String a) {
        this.about = a == null ? "" : a.strip();
    }

    /** The artifact this wish is about, or null for a new thing or one not yet named. */
    public synchronized String aboutArtifact() {
        return about.isEmpty() || ABOUT_NEW.equalsIgnoreCase(about) ? null : about;
    }

    /**
     * The agent's design note for what it is building <b>for this wish</b> — its reasoning, stamped
     * into the artifact's manifest beside the wish text.
     *
     * <p>Per-run rather than per-process because getting it wrong is silent: the artifact still
     * publishes, it just carries the other wish's reasoning, and the corpus mined from those
     * manifests is what drives the roadmap.
     */
    public synchronized String note() {
        return note;
    }

    public synchronized void note(String designNote) {
        this.note = designNote == null ? "" : designNote.strip();
    }

    private String note = "";

    /**
     * The handles ({@code @spikor}) this wish created, newest first — so {@code @last} means "the
     * last thing <i>I</i> made" rather than "the last thing anyone made".
     */
    public synchronized void rememberedHandle(String handle) {
        if (handle != null && !handle.isBlank()) {
            handles.add(0, handle);
        }
    }

    public synchronized List<String> handles() {
        return new ArrayList<>(handles);
    }

    private final List<String> handles = new ArrayList<>();

    /**
     * When the AGENT last narrated this wish itself, so the mod's narrator knows when it may fill
     * in.
     *
     * <p>Per-run because it is a statement about one conversation. Shared, one agent's
     * {@code set_status} silences the <i>other</i> wish's narrator — and the whole point of that
     * narrator is to speak during the window where the model structurally cannot.
     */
    private volatile long agentSpokeAtMs;

    public long agentSpokeAtMs() {
        return agentSpokeAtMs;
    }

    public void agentSpoke(long nowMs) {
        this.agentSpokeAtMs = nowMs;
    }

    // ---- made by the Wish Builder ----------------------------------------------------

    private volatile boolean builderMade;

    /**
     * This run is a Wish Builder make ({@code wishbuilder/BuilderMake}): its tool calls were
     * LOWERED from a spec of reviewed catalogue ids, not written by a model. The plan grade (L2,
     * {@code SafetyPlanGate}) skips it — the plan is a machine translation of picks a grown-up
     * reviewed (the world's allowlist), and grading it would put a model call on a make that costs
     * none. Every child-visible word still passes L0 ({@code SafetyTextSweep}) at the same seam.
     * docs/CHILD_SAFETY.md §4¾.
     */
    public boolean builderMade() {
        return builderMade;
    }

    public void markBuilderMade() {
        builderMade = true;
    }

    private volatile boolean kept;

    /**
     * A world keeps what this run makes and hands it over (#70, {@code WorldMakes.landing}): its creature is designed
     * and not spawned, and no preview of it stands ({@code WishEarlyReveal}), because nothing appears until he fires it.
     */
    public boolean kept() {
        return kept;
    }

    public void markKept() {
        kept = true;
    }

    private volatile com.moddingfromamod.world.WorldRefusal worldRefusal;

    /**
     * A world rule refused one of this wish's calls ({@code McpProtocol}'s reach rule): the FIRST such refusal is kept,
     * so a make with no agent can tell the child that rule rather than "didn't work" ({@code BuilderMake}). The agent
     * reads the refusal's own words in the call's reply; this is the same fact, as data. Null is ignored.
     */
    public void refusedByWorld(com.moddingfromamod.world.WorldRefusal why) {
        if (why != null && worldRefusal == null) {
            worldRefusal = why;
        }
    }

    /** The first world rule that refused one of this wish's calls, or null. */
    public com.moddingfromamod.world.WorldRefusal worldRefusal() {
        return worldRefusal;
    }

    // ---- what the child has already been told --------------------------------------

    private boolean planAnnounced;

    private boolean planned;

    /**
     * This wish has had a SUCCESSFUL {@code plan_wish}. Set by {@code McpProtocol} only when
     * the call returned without error, so a plan the safety gate diverted, or one refused for bad
     * arguments, is not a plan — nothing was shown to the child and there is nothing to price.
     */
    public synchronized boolean planned() {
        return planned;
    }

    public synchronized void markPlanned() {
        planned = true;
    }
    private boolean celebrated;

    /**
     * True the FIRST time it is asked for this run, false ever after — whether the plan may be read
     * out loud. A re-plan still moves the bar; it is the chat line that is said once. Live, a
     * "make it daytime forever" said "Here's my plan" twice in two different wordings, and a child
     * reading two plans cannot tell which one is being built.
     */
    /** Has a plan been read out to him yet? A look that changes nothing (the plan is shown later). */
    public synchronized boolean planAnnounced() {
        return planAnnounced;
    }

    public synchronized boolean announcePlanOnce() {
        if (planAnnounced) {
            return false;
        }
        planAnnounced = true;
        return true;
    }

    // ---- "see the sparkles?": said once, when the spot is marked AND the kind is known ----

    private boolean conjured;
    private String planKind;
    private boolean sparklesSaid;

    /** {@code conjure_at} marked this wish's spot. */
    public synchronized void conjured() {
        conjured = true;
    }

    /** {@code plan_wish}'s {@code kind}, the first time it is given. */
    public synchronized void planKind(String kind) {
        if (planKind == null && kind != null && !kind.isBlank()) {
            planKind = kind.strip();
        }
    }

    /**
     * True exactly once, at whichever of {@code conjure_at} and {@code plan_wish} comes second, when
     * the wish is a thing that appears in the world ({@link com.moddingfromamod.wish.WishStage#saySparkles}).
     */
    public synchronized boolean saySparklesOnce() {
        if (!com.moddingfromamod.wish.WishStage.saySparkles(conjured, planKind, sparklesSaid)) {
            return false;
        }
        sparklesSaid = true;
        return true;
    }

    /**
     * The child has had THIS wish's one celebration — the bold line, the level-up sound and the
     * sparkles. {@code finish_building} gives it; {@code finish_wish} then adds the verdict
     * without celebrating a second time.
     */
    public synchronized void celebrate() {
        celebrated = true;
    }

    public synchronized boolean celebrated() {
        return celebrated;
    }

    private String plannedSay = "";

    /**
     * A {@code say} handed to {@code finish_building} — written BEFORE the photo, so it describes
     * the plan. It is no longer said at the reveal; it is held as the description of last
     * resort when {@code finish_wish} brings none of its own.
     */
    public synchronized void holdPlannedSay(String say) {
        plannedSay = say == null ? "" : say.strip();
    }

    public synchronized String plannedSay() {
        return plannedSay;
    }

    // ---- where it lands -------------------------------------------------------------------

    /** The locked spot this wish manifests at, or null if nothing has been aimed yet. */
    public synchronized Spot anchor() {
        return anchor;
    }

    /**
     * The wish-time default — where the watch was pointing. Loses to a later {@code conjure_at}, and
     * <b>cannot clobber an already-locked spot</b>, which is what stops a second wish moving the
     * first one's sparkles.
     */
    public synchronized void defaultAnchor(Spot where) {
        if (where != null && anchor == null) {
            anchor = where;
        }
    }

    /** The agent's decision, via {@code conjure_at}. Wins over the default and locks. */
    public synchronized void lockAnchor(Spot where) {
        if (where != null) {
            anchor = where;
            anchorLocked = true;
        }
    }

    public synchronized boolean anchorLocked() {
        return anchorLocked;
    }

    /**
     * <b>Where he was and which way he looked when he wished</b>: his feet, eye
     * height, yaw and pitch. Everything that later places this wish relative to "him" places it
     * relative to THIS, never to the live player — a {@code conjure_at} twenty seconds later used to
     * re-aim from wherever he had flown to, and the pillar jumped.
     */
    public record Aim(String dimension, double x, double y, double z, double eyeY, float yaw, float pitch) {
    }

    private Aim aimedFrom;
    private boolean slid;
    private boolean raised;

    /** Record the wish-time snapshot. First write wins: it is taken once, when he wishes. */
    public synchronized void aimedFrom(Aim a) {
        if (a != null && aimedFrom == null) {
            aimedFrom = a;
        }
    }

    /** The wish-time snapshot, or null for a wish that never had one (a test, a headless probe). */
    public synchronized Aim aimedFrom() {
        return aimedFrom;
    }

    /** §3: what was in his view and his bag when he wished (the From page's own reading). First write wins. */
    private com.moddingfromamod.wishbuilder.BuilderSuggest.Seen sawWhenWished;

    public synchronized void sawWhenWished(com.moddingfromamod.wishbuilder.BuilderSuggest.Seen seen) {
        if (seen != null && sawWhenWished == null) {
            sawWhenWished = seen;
        }
    }

    /** What he saw and carried when he wished, or null (a wish with no wish-time snapshot). Never re-read live. */
    public synchronized com.moddingfromamod.wishbuilder.BuilderSuggest.Seen sawWhenWished() {
        return sawWhenWished;
    }

    /** True the first time only: the one slide by kind a wish is allowed (owner decision iii). */
    public synchronized boolean slideOnce() {
        if (slid) {
            return false;
        }
        slid = true;
        return true;
    }

    /** The agent lifted this wish into the air with {@code up}: nothing settles or slides it again. */
    public synchronized void raise() {
        raised = true;
    }

    public synchronized boolean raised() {
        return raised;
    }

    // ---- lifecycle ------------------------------------------------------------------------

    synchronized void assignTo(String worker, long nowMs) {
        this.workerId = worker;
        this.assignedMs = nowMs;
        // A re-opened wish (a 👎 re-wish) starts its no-progress clock again from THIS claim.
        this.lastActivityMs = -1;
        this.toolsInFlight = 0;
        this.state = RunState.WORKING;
        dropPickup();
    }

    synchronized void requeued(long nowMs) {
        this.requeuedMs = nowMs;
        dropPickup();
    }

    synchronized void unassign() {
        this.workerId = null;
        this.assignedMs = -1;
        dropPickup();
        if (state == RunState.WORKING) {
            state = RunState.GETTING_READY;
        }
    }

    synchronized void finish(long nowMs) {
        if (state.isFinished()) {
            return;
        }
        state = RunState.DONE;
        finishedMs = nowMs;
    }

    synchronized void stop(CancelMode mode, String note, String newTarget, long nowMs) {
        if (state == RunState.STOPPED) {
            return;   // double-cancel is idempotent, not an error
        }
        state = RunState.STOPPED;
        cancelMode = mode;
        cancelNote = note == null ? "" : note;
        redirectTo = newTarget == null ? "" : newTarget;
        cancelledMs = nowMs;
        finishedMs = nowMs;
    }

    /** Non-null once the child stopped it, and it says <b>which</b> of the three outcomes he got. */
    public synchronized CancelMode cancelMode() {
        return cancelMode;
    }

    public synchronized boolean cancelled() {
        return cancelMode != null;
    }

    public synchronized String cancelNote() {
        return cancelNote;
    }

    public synchronized String redirectTo() {
        return redirectTo;
    }

    public synchronized long cancelledMs() {
        return cancelledMs;
    }

    // ---- what it did ----------------------------------------------------------------------

    /**
     * Record a tool this wish used. This is the whole basis for an honest undo: a wish knows, at the
     * end, whether every tool it used had an inverse.
     */
    public synchronized void usedTool(String tool) {
        if (tool != null && !tool.isBlank()) {
            toolsUsed.add(tool);
        }
    }

    public synchronized List<String> toolsUsed() {
        return new ArrayList<>(toolsUsed);
    }

    // ---- what its calls SAID they made ----------------------------------------------

    private final com.moddingfromamod.wish.SayClaim.Evidence evidence =
            new com.moddingfromamod.wish.SayClaim.Evidence();

    /**
     * Record the arguments of a world-changing call that succeeded, so {@code finish_wish} can ask
     * whether anything this wish did backs what its closing line promises. Bounded inside.
     */
    public void calledWith(String tool, com.google.gson.JsonObject args) {
        evidence.add(tool, args, null);
    }

    /** What this wish's calls made, as {@code SayClaim} reads it. */
    public com.moddingfromamod.wish.SayClaim.Evidence evidence() {
        return evidence;
    }

    private volatile boolean changedWorld;

    /** A call that can change his world succeeded for this wish. */
    public void changedWorld() {
        changedWorld = true;
    }

    /** False while every successful call of this wish was a look-up, a plan or a status line. */
    public boolean hasChangedWorld() {
        return changedWorld;
    }

    // ---- what it ran, where it took him, and whether it was about an earlier wish -----

    private final List<String> commandsRan = new ArrayList<>();
    private String wentTo;
    private boolean followup;

    /**
     * A {@code run_command} line that SUCCEEDED for this wish, as it ran. The draft {@code finish_wish}
     * hands back when a command-granted wish was never taught is built from these.
     */
    public synchronized void ranCommand(String command) {
        if (command != null && !command.isBlank() && commandsRan.size() < 256) {
            commandsRan.add(command.strip());
        }
    }

    public synchronized List<String> commandsRan() {
        return new ArrayList<>(commandsRan);
    }

    private final List<com.moddingfromamod.wish.ModifyMobFold.Change> mobChanges = new ArrayList<>();

    /**
     * A {@code modify_mob} call that SUCCEEDED for this wish: its target and the change. The
     * teach draft folds it into the summon it changed, as {@code wishsummon <mob> {spec}}, so the
     * button's mob comes back WITH its change rather than as a plain one.
     */
    public synchronized void modifiedMob(String target, com.google.gson.JsonObject spec) {
        if (spec != null && !spec.isEmpty() && mobChanges.size() < 64) {
            mobChanges.add(new com.moddingfromamod.wish.ModifyMobFold.Change(
                    target == null ? "" : target, spec.deepCopy()));
        }
    }

    public synchronized List<com.moddingfromamod.wish.ModifyMobFold.Change> mobChanges() {
        return new ArrayList<>(mobChanges);
    }

    /** {@code take_me_to} took him to this artifact (a build, machine or train id). */
    public synchronized void wentTo(String artifactId) {
        if (artifactId != null && !artifactId.isBlank()) {
            wentTo = artifactId;
        }
    }

    public synchronized String wentTo() {
        return wentTo;
    }

    private boolean movedByCommand;

    /**
     * A command moved him a real distance during this wish ({@code wish.WishMoves}) — a
     * {@code tp}, an {@code execute … tp}, a {@code spreadplayers}. Like {@link #wentTo}, the reveal
     * is then shot from where he now stands; unlike it, there is no artifact to teach a button to.
     */
    public synchronized void movedByCommand() {
        movedByCommand = true;
    }

    /** Did this wish take him somewhere — {@code take_me_to} or a command that moved him? */
    public synchronized boolean tookHimSomewhere() {
        return wentTo != null || movedByCommand;
    }

    /**
     * {@code plan_wish} said this wish is ABOUT something already made — a fix, a complaint, a
     * question about an earlier wish — rather than a new thing. Such a wish is never told
     * "I can't save it", and never nudged to become a button.
     */
    public synchronized void markFollowup() {
        followup = true;
    }

    public synchronized boolean followup() {
        return followup;
    }

    // ---- what it looked at -----------------------------------------------------------

    private boolean looked;
    private int lookRefusals;

    /** A read tool succeeded for this wish — a question may now be answered. */
    public synchronized void lookedWith(String tool) {
        if (tool != null && !tool.isBlank()) {
            looked = true;
        }
    }

    public synchronized boolean looked() {
        return looked;
    }

    /** An answer was refused because nothing had been looked at yet. */
    public synchronized void lookRefused() {
        lookRefusals++;
    }

    public synchronized int lookRefusals() {
        return lookRefusals;
    }

    /**
     * Something this wish made, and what kind of thing it is — so an undo can call the same inverse
     * the {@code remove_*} tools already implement.
     *
     * <p>Kinds are plain strings ({@link com.moddingfromamod.marshal.MadeKind}) rather than an enum
     * over game types, because this class is Minecraft-free on purpose.
     */
    public record Made(String kind, String id) {
    }

    private final List<Made> made = new ArrayList<>();

    /** Record something this wish created. Idempotent per (kind, id). */
    public synchronized void made(String kind, String id) {
        if (kind == null || id == null || kind.isBlank() || id.isBlank()) {
            return;
        }
        Made m = new Made(kind, id.trim());
        if (!made.contains(m)) {
            made.add(m);
        }
        removed.remove(m);   // made again after a remove: it is kept after all
        // A creature or a building made under a wish that said "item" still gets its photograph.
        com.moddingfromamod.wish.CameraNeed.shared().made(wishId, kind, m.id(), System.currentTimeMillis());
    }

    /**
     * Record something this wish made, and whether it was ALREADY THERE before the wish: a
     * thing an earlier wish made and this one changed is an edit, never a part of this wish. Only
     * the FIRST record of a thing in this run decides — remaking it later in the same wish does not
     * turn it into an edit.
     */
    public synchronized void made(String kind, String id, boolean existedBefore) {
        if (kind == null || id == null || kind.isBlank() || id.isBlank()) {
            return;
        }
        Made m = new Made(kind, id.trim());
        if (existedBefore && !made.contains(m)) {
            this.existedBefore.add(m);
        }
        made(kind, id);
    }

    private final java.util.Set<Made> existedBefore = new java.util.LinkedHashSet<>();
    private final java.util.Set<Made> removed = new java.util.LinkedHashSet<>();

    /** Something this wish took away again ({@code remove_*}) — a superseded version. */
    public synchronized void removed(String kind, String id) {
        if (kind != null && id != null && !kind.isBlank() && !id.isBlank()) {
            removed.add(new Made(kind, id.trim()));
        }
    }

    /** What this wish took away after it last made it. */
    public synchronized java.util.Set<Made> removedThings() {
        return new java.util.LinkedHashSet<>(removed);
    }

    /** What this wish changed that was already there before it. */
    public synchronized java.util.Set<Made> existedBefore() {
        return new java.util.LinkedHashSet<>(existedBefore);
    }

    /**
     * A creature whose SAVED DESIGN this wish changed: a re-sculpt of one an earlier wish
     * made, a {@code modify_mob} that wrote to its design, a {@code paint_creature}. Not a
     * {@link #made} entry on purpose: undo reads {@code made()} and reverts a CREATURE by popping
     * its last sculpt, so recording "make my snake bigger" there would undo it into the troll.
     *
     * @param id      the creature's id now (his own copy's id when the change forked one)
     * @param before  its name before this wish
     * @param after   its name after the change
     * @param forked  the change made HIS OWN copy of someone else's creature
     * @param reshaped its BODY was re-sculpted (a new name then means it became something else; a
     *                 name change without one is a rename)
     */
    public record CreatureEdit(String id, String before, String after, boolean forked,
                               boolean reshaped) {
    }

    private final java.util.Map<String, CreatureEdit> creaturesEdited = new java.util.LinkedHashMap<>();

    /** Record one change to a creature's saved design; the first {@code before} for an id is kept. */
    public synchronized void creatureEdited(String id, String before, String after, boolean forked,
                                            boolean reshaped) {
        if (id == null || id.isBlank()) {
            return;
        }
        CreatureEdit was = creaturesEdited.get(id);
        creaturesEdited.put(id, new CreatureEdit(id, was != null ? was.before() : before, after,
                forked || (was != null && was.forked()), reshaped || (was != null && was.reshaped())));
    }

    /** Every creature whose saved design this wish changed, in the order it first did. */
    public synchronized java.util.List<CreatureEdit> creaturesEdited() {
        return new ArrayList<>(creaturesEdited.values());
    }

    /**
     * <b>Something a COMMAND spawned while this wish was in flight — an earlier issue's bracket.</b>
     *
     * <p>Deliberately a different ledger from {@link #made(String, String)}, and the difference is
     * the whole rule: {@code made} is what the wish <i>kept</i>, this is what it <i>created</i>. The
     * agent's test props — an armour stand to aim a fire breath at, a chicken to burn — land here
     * and never there, so "scaffolding is what the wish made and did not keep" becomes a set
     * subtraction instead of a guess about what an armour stand is for.
     *
     * <p>Recorded per RUN rather than globally so a second wish's sweep can never reach the first
     * one's props, which is the same reasoning the leases and the anchor already use.
     *
     * @param handle the name {@code EntityCapture} gave it, so a REGISTERED prop can be recognised
     * @param kept   the command that spawned it carried {@code keep: true}: the wish
     *               DELIVERED it, so the sweep never takes it
     */
    public record Prop(UUID id, String handle, String typeId, boolean kept) {

        public Prop(UUID id, String handle, String typeId) {
            this(id, handle, typeId, false);
        }
    }

    private final List<Prop> commandSpawned = new ArrayList<>();

    /** Record something one of this wish's commands spawned. Idempotent per entity. */
    public synchronized void commandSpawned(UUID id, String handle, String typeId) {
        commandSpawned(id, handle, typeId, false);
    }

    /**
     * Record something one of this wish's commands spawned, and whether that command said to KEEP
     * it. Idempotent per entity, and the FIRST record wins: an entity is new in exactly
     * one command, so a later {@code keep: true} call can never reach back and keep another
     * command's probe — over-keeping stays bounded by the one command that asked.
     */
    public synchronized void commandSpawned(UUID id, String handle, String typeId, boolean kept) {
        if (id == null) {
            return;
        }
        for (Prop p : commandSpawned) {
            if (id.equals(p.id())) {
                return;
            }
        }
        commandSpawned.add(new Prop(id, handle == null ? "" : handle.trim(),
                typeId == null ? "" : typeId, kept));
    }

    /** What this wish's commands spawned, in the order they appeared. */
    public synchronized List<Prop> commandSpawned() {
        return new ArrayList<>(commandSpawned);
    }

    /**
     * <b>One creature this wish tried to put in the world</b> — the species, and the
     * carrier that is standing there, or {@code null} for an attempt that produced nothing.
     *
     * <p>Two readers. {@code finish_wish} asks whether a creature this wish SPAWNED is standing
     * anywhere before a {@code done} may stand ("I can't see the sea creature you made");
     * and an undo removes exactly the carriers this wish spawned, not every creature of the species.
     * A sculpt that spawned nothing records nothing, so a spawn-egg-only wish is not
     * mistaken for a missing creature.
     */
    public record CreatureSpawned(String species, UUID carrier) {
    }

    private final List<CreatureSpawned> creaturesSpawned = new ArrayList<>();

    /** Record one spawn attempt; {@code carrier} null when nothing is standing. */
    public synchronized void creatureSpawned(String species, UUID carrier) {
        if (species == null || species.isBlank()) {
            return;
        }
        creaturesSpawned.add(new CreatureSpawned(species.trim(), carrier));
    }

    /** Every creature spawn this wish attempted, oldest first. */
    public synchronized List<CreatureSpawned> creaturesSpawned() {
        return new ArrayList<>(creaturesSpawned);
    }

    // ──: WHERE ITS CREATURES SAT — "boss sits on castle:throne" ──

    private final java.util.Map<String, String> seats = new java.util.LinkedHashMap<>();

    /** This wish put creature {@code species} on {@code at} ({@code "<build>:<spot>"}). */
    public synchronized void seated(String species, String at) {
        if (species != null && !species.isBlank() && at != null && !at.isBlank()) {
            seats.put(species.trim().toLowerCase(java.util.Locale.ROOT), at.trim().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Creature id → {@code "<build>:<spot>"} it was put on, in the order they were seated. */
    public synchronized java.util.Map<String, String> seats() {
        return new java.util.LinkedHashMap<>(seats);
    }

    /** The newest building this wish made (from nothing or by an edit), or null. */
    public synchronized String lastBuild() {
        for (int i = made.size() - 1; i >= 0; i--) {   // the field is oldest first
            if (MadeKind.BUILD.equals(made.get(i).kind())) {
                return made.get(i).id();
            }
        }
        return null;
    }

    // ──: ONE THING, NOT TWO — what NoDuplicate needs to remember for the length of a wish ──

    private final List<String> freshBuilds = new ArrayList<>();
    private String refusedEdit;

    /** A building this wish created from nothing (no start_from, an id nobody had). */
    public synchronized void builtFresh(String id) {
        if (id != null && !id.isBlank() && !freshBuilds.contains(id.trim())) {
            freshBuilds.add(id.trim());
        }
    }

    /** The buildings this wish created from nothing, oldest first. */
    public synchronized List<String> freshBuilds() {
        return List.copyOf(freshBuilds);
    }

    /** An edit of {@code baseId} ({@code make_item start_from}) was refused in this wish. */
    public synchronized void editRefused(String baseId) {
        if (baseId != null && !baseId.isBlank()) {
            refusedEdit = baseId.trim();
        }
    }

    /** An edit of {@code baseId} went through after all: it no longer stands refused. */
    public synchronized void editLanded(String baseId) {
        if (refusedEdit != null && baseId != null && refusedEdit.equalsIgnoreCase(baseId.trim())) {
            refusedEdit = null;
        }
    }

    /** The thing whose edit this wish was refused and has not landed since, or null. */
    public synchronized String refusedEdit() {
        return refusedEdit;
    }

    /** What this wish made, <b>newest first</b> — the order an undo has to walk it back in. */
    private final List<String> taught = new ArrayList<>();

    /** A recipe this wish taught ({@code add_template}) — its parts are what this wish made. */
    public synchronized void taught(String templateId) {
        if (templateId != null && !templateId.isBlank() && !taught.contains(templateId.trim())) {
            taught.add(templateId.trim());
        }
    }

    /** The recipes this wish taught, in the order it taught them. */
    public synchronized List<String> taughtIds() {
        return List.copyOf(taught);
    }

    public synchronized List<Made> made() {
        List<Made> out = new ArrayList<>(made);
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * <b>The artifact this wish turned out to be ABOUT</b> — which is not the same as what it made.
     *
     * <p>{@link #made} means "things with a real inverse the mod can perform", and that meaning is
     * load-bearing: {@code MadeKind}'s own doc says a kind recorded but never reversed makes the
     * {@code [undo]} button lie. So the list is deliberately narrow, and a class with no reversal
     * yet — items, today ({@code }) — correctly records nothing in it.
     *
     * <p>Which quietly broke something else. "Which thing did he last work on" is the only question
     * a refinement like <i>"make it hurt way more"</i> can be routed by: it names nothing, so the
     * alias index cannot resolve it and the subject falls back to the last artifact. That fallback
     * read {@code made()}, so for an item it was always empty, the subject came back {@code ""} and
     * every rung routed {@code anyone}. Measured: the items class's own confirming matrix reports
     * {@code refinements_routed_under_the_arm 0.0} with {@code {anyone: 16}} in BOTH arms — arm A
     * and arm B were the same experiment, and the class's A/B numbers are not about A and B.
     *
     * <p>So the two questions are stored apart. This one costs nothing and promises nothing: it is
     * what {@code finish_wish} said the wish was about, and no undo path reads it.
     */
    public synchronized String finishedArtifact() {
        return finishedArtifact;
    }

    public synchronized void finishedArtifact(String artifactId) {
        this.finishedArtifact = artifactId == null ? "" : artifactId.trim();
    }

    private String finishedArtifact = "";

    // ---- leases ---------------------------------------------------------------------------

    synchronized void holdLease(String artifactId) {
        if (artifactId != null && !artifactId.isBlank()) {
            leases.add(artifactId);
        }
    }

    synchronized List<String> releaseAllLeases() {
        List<String> held = new ArrayList<>(leases);
        leases.clear();
        return held;
    }

    public synchronized List<String> leases() {
        return new ArrayList<>(leases);
    }

    @Override
    public String toString() {
        return "WishRun[" + shortId() + " " + state() + " worker=" + workerId() + "]";
    }

    /** Eight characters, the same short form the wish book and the logs already use. */
    public String shortId() {
        return wishId == null ? "????????" : wishId.toString().substring(0, 8);
    }
}
