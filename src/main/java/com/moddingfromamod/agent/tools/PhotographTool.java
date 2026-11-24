package com.moddingfromamod.agent.tools;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.wish.WishCapture;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Look at what you just made.</b> Takes a photo through the child's own eyes and returns the
 * picture to the agent.
 *
 * <p>This is the missing half of the loop. Everything else the agent can check is a number — the
 * creature spawned, the model baked, the command succeeded — and none of those answer the only
 * question that matters: <i>does it look right?</i> Asked for Chucko McBurger, the system produced a
 * blood-red zombie and reported total success, because nothing ever looked at it.
 *
 * <p>The image is returned <b>inline</b>, not as a path. The agent runs with no file-reading tool, so
 * a path would be useless to it; the pixels have to travel in the result. They're scaled down first,
 * because a full-resolution screenshot is megabytes of base64 and the detail isn't needed to tell
 * whimsical from horrifying.
 */
public final class PhotographTool implements McpTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Long edge of the returned image. Enough to judge a creature, small enough to be cheap. */
    private static final int MAX_EDGE = 512;

    /** How long to wait for the client to take the shot. */
    /**
     * How long to keep waiting after the LAST sign of progress, rather than in total.
     *
     * <p>See the wait loop for why this is not a total budget. Four seconds of complete silence is
     * a long time for a file that a client is actively writing; it is nothing next to the ten and
     * a half seconds one measured shot took to become stable on a machine with no GPU.
     */
    private static final long QUIET_MS = 4000;

    /** Backstop for a file that grows for ever. Never reached in a healthy run. */
    private static final long HARD_CAP_MS = 30_000;

    /**
     * How long the single shot waits for its file to APPEAR at all — a separate wait from
     * {@link #QUIET_MS}, which starts only once there is a file to watch.
     *
     * <p>Deliberately as long as the backstop, because nothing measured says it can be shorter.
     * The only timings on record are of the FINISHED file — stable at 10,666 ms, returned at
     * 9,804 ms on a no-GPU box (docs/CAMERA_CLIENT.md §6e, "the defect this found") — and both were taken
     * while an absent file still counted as progress, so the first byte may have arrived as late
     * as ~10 s, well past the 4 s quiet period. A remote camera VM pushes the PNG only once it is
     * whole and the server writes it by rename, so there it appears all at once, late. A shorter
     * deadline would tell the agent "the photo didn't arrive" about one on its way, the failure
     * the quiet period was introduced to stop. Shorten it only from a measured appearance time.
     */
    private static final long APPEAR_MS = HARD_CAP_MS;



    /**
     * ...and per stop of an orbit, where four of them run back to back.
     *
     * <p>Shorter than a single shot's budget on purpose. The 8s deadline exists so ONE photograph
     * never gives up early; four of those is a 32 second worst case in front of a waiting child,
     * and a missing angle costs a tile on a sheet rather than the whole photograph. A shot that
     * has not landed in three seconds is not about to.
     */
    private static final long STOP_TIMEOUT_MS = 3000;

    /**
     * ...but the HERO still — the first of a take, and often the only one — gets a
     * single shot's patience. It is the wish card, and it is the frame most likely to be slow: on
     * a bench server a picture's front still came 1 s after its VM camera joined, the client was
     * still drawing chunks past 3 s, the sheet came back empty and the card was an unframed
     * snapshot. The four-stops-back-to-back reason for 3 s no longer holds when there is one stop.
     */
    static final long HERO_TIMEOUT_MS = 12_000;

    /**
     * Frames the client renders at each new camera position before the shutter.
     *
     * <p>Two, not three: {@code takeScreenshot} reads the already-rendered framebuffer, so this
     * only has to cover the teleport arriving and one frame being drawn from it. Every extra tick
     * is time the creature spends walking out of the shot.
     */
    private static final int ORBIT_SETTLE_TICKS = 2;

    /** Wall-clock between firing one stop and the next — about three ticks at 20/s. */
    private static final long ORBIT_GAP_MS = 160;

    /** How long the camera settles before the facing is re-checked. */
    private static final long RECHECK_GAP_MS = 60;

    /**
     * How far a creature may WALK between aiming and shooting before the camera re-aims.
     *
     * <p>Half a block. A creature fills a bit over half the frame by design, so it does not take
     * much lateral movement to push its head out of shot — and unlike a turn, walking away is
     * invisible to a check that only watches which way it is pointing.
     */
    private static final double MAX_DRIFT_BLOCKS = 0.5;
    private static final long POLL_MS = 150;

    @Override
    public String name() {
        return "photograph";
    }

    @Override
    public String description() {
        return "SEE what you made. Takes a screenshot through the child's eyes and returns the "
                + "actual picture to you. Use it after making or changing a creature, especially a "
                + "character with a look to get right — you cannot tell whimsical from terrifying "
                + "from the numbers, and the child can. If it looks wrong, fix it with "
                + "sculpt_creature or paint_creature and photograph it again before saying you're "
                + "done. ALWAYS pass 'subject' with the ARTIFACT'S ID — a creature, a BUILDING, "
                + "a MACHINE, an ITEM. It points the camera at that thing and you get it "
                + "from FOUR SIDES in one picture, framed for what it is: "
                + "a building from outside and in. Without it the camera picks the newest "
                + "thing THIS wish made, or looks at the spot the wish landed, from one side only.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "label", "string",
                "USE THE ARTIFACT'S ID — the same id you gave sculpt_creature, build_structure"
                        + " or make_item. The photo is filed under it, and that is how it "
                        + "reaches the child's wish card: a shot named anything else is a picture "
                        + "nobody can find again.");
        Schemas.prop(s, "subject", "string",
                "OPTIONAL but strongly recommended: the id of the thing to point the camera at — a "
                        + "CREATURE, a BUILDING, "
                        + "a MACHINE or an ITEM, whichever you just "
                        + "made. Not creatures only: this field said \"a creature\" for a long time "
                        + "and every other framing path was skipped for years as a result (#219). "
                        + "With it you get four sides, framed for what the thing IS; without it the "
                        + "camera picks the newest thing this wish made, or the spot it landed.");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String label = args != null && args.has("label") && args.get("label").isJsonPrimitive()
                ? args.get("label").getAsString() : "photo";
        String subject = args != null && args.has("subject") && args.get("subject").isJsonPrimitive()
                ? args.get("subject").getAsString() : "";
        awaitTransformations();
        return capture(label, subject, null);
    }

    /** The longest a photograph waits for a creature to finish transforming. */
    static final long TRANSFORM_WAIT_MS = 4000;

    /**
     * A creature mid-transformation is a white body or two (two seconds): photograph it a
     * moment later. Off the server thread only — the transformation is ticked there.
     */
    private static void awaitTransformations() {
        var server = com.moddingfromamod.agent.AgentBridge.serverOrNull();
        if (server == null || server.isSameThread()) {
            return;
        }
        long until = System.currentTimeMillis() + TRANSFORM_WAIT_MS;
        while (com.moddingfromamod.vanilla.RigTransformation.anyRunning()
                && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * <b>Every shot this tool sends goes through here</b>: a camera viewpoint is given
     * night vision for the take first, so a dark room photographs legibly; a child's eyes are
     * never given it ({@code Viewpoint.seeInTheDark}). One door, so a new shot site cannot forget
     * — {@code CameraNightVisionWiringTest} fails a {@code Send.to} anywhere else in this file.
     * For a STILL, {@code label} names the photograph, which the no-client darkroom develops by that name.
     */
    private static void shutter(ServerPlayer p, String label,
            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        com.moddingfromamod.wish.Viewpoint.seeInTheDark(p);
        com.moddingfromamod.net.Send.to(p, payload);
        if (label != null && !NO_CLIENT_DARKROOM.isEmpty()) {
            java.util.function.Consumer<Path> develop = NO_CLIENT_DARKROOM.remove(photoPath(label));
            if (develop != null) {
                develop.accept(photoPath(label));
            }
        }
    }

    /** A shot that is not a still (a spin, a clip): nothing for the darkroom to develop. */
    private static void shutter(ServerPlayer p, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        shutter(p, null, payload);
    }

    /**
     * <b>A GameTest's stand-in for the client that writes the photograph</b>.
     *
     * <p>A mock player has no client, so a headless test must write the PNG itself. Seven tests
     * did that on a TIMER — 400 ms after the tool was called — and the timer is a race: the wait
     * below only counts a file whose mtime is NEWER than the one it read before the shot, so when
     * the tool was slow to reach the shutter (its claim checks alone took 650 ms on a loaded
     * server) the photo landed first, was taken for an old one, and the tool waited out its 30 s
     * cap. The test's own 25 s deadline failed, and the still-running tool leaked its wish into
     * every test after it. Developing the film AT the shutter, after that read, is what a real
     * client does and cannot be early.
     */
    private static final java.util.Map<Path, java.util.function.Consumer<Path>> NO_CLIENT_DARKROOM =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Write {@code label}'s photograph when its shutter fires (see {@link #NO_CLIENT_DARKROOM}). */
    public static void developForTest(String label, java.util.function.Consumer<Path> develop) {
        NO_CLIENT_DARKROOM.put(photoPath(label), develop);
    }

    /**
     * Take a photo and return it inline. Shared with {@code finish_building}, which is where the photo
     * SHOULD happen — one deterministic shot at the moment the build finishes, rather than the
     * agent having to remember. Kept here because this is where the file/timing dance lives.
     *
     * @param intro extra sentence to lead the result with, or null for the default judging prompt
     */
    // Public so the Gate-C client test can drive the REAL tool rather than a copy of its logic.
    public static ToolResult capture(String label, String subject, String intro) {
        // AN ITEM IS DRAWN, NOT PHOTOGRAPHED (2026-10-03): its picture is its icon, read off
        // the stack he holds, so an item wish wakes no camera at all. Asked BEFORE the camera wait,
        // which is the whole saving; if no icon can be drawn the camera path below still answers.
        String aim = subject == null ? "" : subject.trim();
        if (aim.isEmpty()) {
            // ⚠ "populated well before the photo" was true of every wish but the one being
            // photographed: lastArtifactFor reads FINISHED runs. A picture this wish hung is asked
            // for first, or an 8×16 board is shot from his feet with its head cut off.
            // ⚠ AND NEVER THE LAST FINISHED WISH'S THING WHILE THIS ONE IS RUNNING: an oasis
            // build was photographed as the previous wish's dragon shark, a teleport as the
            // Pegasus. With a run, only what IT made is a default; with nothing to frame the camera
            // aims at the wish's own spot below. The old default answers only when there is no run.
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            aim = com.moddingfromamod.wish.PhotoSubject.choose(run == null ? null : run.made(),
                    run == null ? lastArtifactForCurrentPlayer() : "");
        }
        String notMine = wrongSubjectNote(com.moddingfromamod.agent.AgentContext.currentRun(), aim);
        return withNote(captureSubject(label, subject, aim, intro), notMine);
    }

    /**
     * <b>Every part of a compound wish, in one picture</b>: each part photographed as
     * {@link #capture} would, and the shots stacked top to bottom (the place first). The text says
     * which part is which, so the agent can judge each.
     *
     * <p>It fails the way {@link #capture} fails (an earlier issue's review): no picture at all is an ERROR, so
     * the caller's "don't retry the camera" still answers; the first shot that fails ends the run,
     * so a dead camera costs one wait and not one per part; and the header names only the shots
     * that are IN the picture, so the agent never grades a seahorse as the castle that was left out.
     * An event is FILMED, once ({@link #captureSubject} films every shot of an event wish whatever
     * its subject), so it takes the single path.
     */
    public static ToolResult captureParts(String label, java.util.List<String> parts, String intro) {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        String cls = run == null ? null : com.moddingfromamod.wish.CameraNeed.shared().cls(run.wishId());
        if ("event".equals(cls)) {
            return capture(label, null, intro);
        }
        java.util.List<String> pictures = new java.util.ArrayList<>();
        java.util.List<String> shown = new java.util.ArrayList<>();
        StringBuilder notes = new StringBuilder();
        ToolResult failed = null;
        int n = 0;
        for (String part : parts) {
            n++;
            ToolResult shot = capture(label + "_" + n, part, null);
            if (shot.isError() || shot.imageBase64() == null || shot.imageBase64().isEmpty()) {
                failed = shot;
                notes.append("\n\n[").append(part).append(": no photograph — ").append(shot.text())
                        .append("]");
                break;   // a camera that failed once fails again: do not wait for it per part
            }
            pictures.add(shot.imageBase64());
            shown.add(part);
            notes.append("\n\n[").append(shown.size()).append(": ").append(part).append("] ")
                    .append(shot.text());
        }
        String stacked = pictures.isEmpty() ? null : com.moddingfromamod.wish.PhotoParts.stack(pictures);
        if (stacked == null) {
            return ToolResult.error(failed != null ? failed.text() : "no photograph came back");
        }
        StringBuilder text = new StringBuilder();
        if (intro != null && !intro.isBlank()) {
            text.append(intro).append("\n\n");
        }
        if (shown.size() == 1) {
            text.append("This wish made ").append(parts.size()).append(" things, but only ")
                    .append(shown.get(0)).append(" could be photographed; judge it, and judge the rest ")
                    .append("from what you built.");
        } else {
            text.append("This wish made ").append(parts.size()).append(" things; the picture is ")
                    .append(shown.size()).append(" photographs stacked top to bottom: ")
                    .append(String.join(", then ", shown))
                    .append(". Judge each one — and if one could be better, say which.");
            if (shown.size() < parts.size()) {
                text.append(" (").append(parts.size() - shown.size())
                        .append(" could not be photographed and are not in the picture.)");
            }
        }
        text.append(notes);
        return ToolResult.image(text.toString(), stacked, "image/png");
    }

    /**
     * <b> E: this photograph is not of the wish.</b> When the subject the camera is about to
     * frame is nothing this wish made (an agent naming an earlier wish's creature, say), the
     * agent is told before it judges — and one {@code camera-fault: photo-wrong-subject} line goes
     * to the server log for session reports. Null when there is nothing to say.
     */
    static String wrongSubjectNote(com.moddingfromamod.marshal.WishRun run, String aim) {
        if (run == null || aim == null || aim.isBlank()) {
            return null;
        }
        java.util.List<String> also = new java.util.ArrayList<>();
        also.add(run.wentTo());
        for (var m : run.made()) {
            if (com.moddingfromamod.marshal.MadeKind.MOB.equals(m.kind())) {
                also.add(creatureSubject(m.id()));   // a changed mob's handle names its species
            }
        }
        for (var c : run.creaturesSpawned()) {
            also.add(c.species());
        }
        String resolved = creatureSubject(aim);
        if (!com.moddingfromamod.wish.PhotoSubject.notThisWish(run.made(), also, run.followup(),
                aim, resolved)) {
            return null;
        }
        java.util.List<String> mine = new java.util.ArrayList<>();
        for (var m : run.made()) {
            mine.add(m.kind() + ":" + m.id());
        }
        LOGGER.warn("camera-fault: photo-wrong-subject wish {} subject '{}' is not anything this "
                + "wish made {}", run.shortId(), aim, mine);
        return "(CAMERA FAULT: this photograph is of '" + aim + "', which is NOT something this "
                + "wish made — it is not a picture of your wish. Do not judge your wish from it; "
                + "judge from what you built.)";
    }

    private static ToolResult withNote(ToolResult r, String note) {
        if (note == null || r == null || r.isError()) {
            return r;
        }
        return new ToolResult(note + " " + r.text(), r.isError(), r.imageBase64(), r.imageMime());
    }

    private static ToolResult captureSubject(String label, String subject, String aim, String intro) {
        ToolResult icon = drawnItem(label, aim, intro);
        if (icon != null) {
            return icon;
        }
        if (lastIconUndrawable) {
            // AN ITEM IS NEVER PHOTOGRAPHED FROM HIS EYES (2026-10-08: "useless photos are
            // worse than no photo"). The camera's answer for an item is the world he faces — a
            // field, his hotbar — filed under the wish and shown on his profile as its picture.
            LOGGER.info("photograph: '{}' is an item with no art to draw — no photograph", aim);
            return ToolResult.ok("No picture for this item: it has no icon art to draw, and a photo from "
                    + "his eyes would show the world, not the item. Judge it by what it does. Call finish_wish now.");
        }
        // A WISH THAT ONLY PUT BACK KEPT THINGS takes no photograph and waits for no camera
        // (2026-10-03): nothing new exists, and its pictures from when it was first made stand.
        var reuseRun = com.moddingfromamod.agent.AgentContext.currentRun();
        if (reuseRun != null && com.moddingfromamod.wish.CameraNeed.shared().reuseOnly(reuseRun.wishId())) {
            LOGGER.info("photograph: wish {} only put back kept things — no camera, no photograph",
                    reuseRun.shortId());
            return ToolResult.ok("Nothing new was made: this wish put back something already kept, "
                    + "exactly as it was, so it is not photographed again. Call finish_wish now.");
        }
        awaitCameraForFirstPhoto();
        // AN EVENT IS FILMED: a held frame on where it happens, long enough to see it happen.
        // Asked for when the wish said it is an event, or when nothing names a subject and an event
        // has just started — the old answer was one plain still from wherever the camera stood.
        var runNow = com.moddingfromamod.agent.AgentContext.currentRun();
        String cls = runNow == null ? null : com.moddingfromamod.wish.CameraNeed.shared().cls(runNow.wishId());
        if (SPIN && ("event".equals(cls) || subject == null || subject.isBlank())) {
            ToolResult ev = captureEventLeased(label, intro);
            if (ev != null) {
                return ev;
            }
        }
        // ⚠ EVERY FRAMING PATH BELOW IS GATED ON `subject`, AND THE AGENT USUALLY DOES NOT PASS ONE.
        //
        // Measured on a real three-wish train ladder: finish_building was called with
        // argKeys ['say', 'label', 'claims'] — no subject — on every rung. So `capture` took the
        // empty-subject branch and shot a plain snapshot from wherever the player happened to be
        // standing, and that snapshot is the child's WISH CARD.
        //
        // It is not the agent's mistake. The parameter is described to it as "the CREATURE id to
        // point the camera at", so an agent that has just built a train, a castle or a machine
        // reasonably leaves it blank — and then the buildings orbit, the held-item framing
        // and the train framing are all skipped in one go. Every one of those was
        // fixed as a framing bug when the picture was never reaching the framing code at all.
        //
        // So it is defaulted in capture() — to what THIS wish made, never the
        // child's last finished thing while a wish is running, which framed the previous wish's
        // dragon shark for an oasis build. Nothing is taken away from an agent that passes one.
        return captureAimedAt(label, creatureSubject(aim), intro);
    }

    /** Why the last item was not DRAWN (and went to the camera), or null when it was. Read by the gates. */
    public static volatile String lastIconSkip;
    /** The last subject WAS a wished item and nothing could draw it. */
    static volatile boolean lastIconUndrawable;

    /**
     * <b>An item's picture: its icon, drawn from the stack he holds</b> ({@link
     * com.moddingfromamod.item.ItemIconShot}), filed under the wish like any photograph. Null when
     * {@code subject} names no wished item, or no icon could be drawn — the camera then answers.
     */
    static ToolResult drawnItem(String label, String subject, String intro) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        lastIconUndrawable = false;
        byte[] png;
        try {
            png = AgentBridge.onServer(() -> {
                var spec = com.moddingfromamod.item.ItemSpecStore.find(subject);
                if (spec == null) {
                    lastIconSkip = "'" + subject + "' names no wished item";
                    return null;
                }
                var server = AgentBridge.serverOrNull();
                ServerPlayer child = com.moddingfromamod.agent.ActingChild.person(server);
                byte[] drawn = null;
                if (child != null) {
                    var inv = child.getInventory();
                    for (int i = 0; i < inv.getContainerSize() && drawn == null; i++) {
                        var stack = inv.getItem(i);
                        if (spec.id().equals(com.moddingfromamod.item.WishItem.wishFeature(stack))) {
                            drawn = com.moddingfromamod.item.ItemIconShot.of(stack);
                        }
                    }
                }
                if (drawn == null) {
                    // Built on a real vanilla item (no icon number on the stack), or not in his
                    // hands: draw it from its DESIGN, as the account page does.
                    drawn = com.moddingfromamod.item.ItemIconShot.ofDesign(spec.icon(),
                            spec.colour() == null ? null : MakeItemTool.colourOf(spec.colour()), spec.effects());
                }
                lastIconSkip = drawn == null ? (child == null ? "no child to read the stack from"
                        : "neither his stack of " + spec.id() + " nor its design names art our library draws") : null;
                if (drawn == null) {
                    lastIconUndrawable = true;
                }
                return drawn;
            });
        } catch (AgentBridge.Unavailable e) {
            lastIconSkip = "unavailable: " + e.getMessage();
            return null;
        }
        if (png == null) {
            // Said out loud: on a bench server an item wish went to the camera instead of its icon
            // and nothing anywhere said why. Only for an ITEM: every subject is asked first.
            if (lastIconSkip != null && !lastIconSkip.endsWith("names no wished item")) {
                LOGGER.info("item icon not drawn for '{}': {} — the camera answers", subject, lastIconSkip);
            }
            return null;
        }
        Path out = photoPath(label);
        try {
            Files.createDirectories(out.getParent());
            Files.write(out, png);
            com.moddingfromamod.wish.WishShots.keep(out);   // filed under its wish, as a photograph is
        } catch (java.io.IOException e) {
            // the picture still goes to the agent; only the filing failed
        }
        String lead = intro != null ? intro
                : "This is his item, drawn exactly as its icon appears in his inventory — its picture, its "
                + "colour and what it shows it does. Judge it on this, then finish_wish.";
        return ToolResult.image(lead, java.util.Base64.getEncoder().encodeToString(png), "image/png");
    }

    /**
     * <b>The wish's FIRST photograph waits for a sleeping camera, and says how long.</b>
     *
     * <p>On a world whose cameras sleep on VMs ({@link com.moddingfromamod.wish.CameraWait}) the wish
     * woke its camera when it was submitted; if it has not joined yet, the first photograph waits for
     * it rather than borrow the child's eyes. Every wish's first wait — 0 when a camera was there — is
     * one line of {@code camera-waits.jsonl}, which an operator's report turns into p50 / p95 / % delayed. Never
     * on the server thread (it would stall the world it waits on).
     */
    static void awaitCameraForFirstPhoto() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        java.util.UUID wish = run == null ? null : run.wishId();
        // Whatever the wish declared, asking for a photograph means it needs a camera NOW — so the
        // supervisor wakes one even for a wish that said "item" and then sculpted a pet.
        com.moddingfromamod.wish.CameraNeed.shared().photographing(wish, System.currentTimeMillis());
        if (!com.moddingfromamod.wish.CameraWait.firstFor(wish)) {
            return;
        }
        var server = AgentBridge.serverOrNull();
        if (server == null || server.isSameThread()) {
            return;
        }
        boolean demand = com.moddingfromamod.wish.CameraWait.demandIsWishes();
        java.util.function.BooleanSupplier serving = () -> {
            try {
                return Boolean.TRUE.equals(AgentBridge.onServer(
                        () -> com.moddingfromamod.wish.CameraSupervisor.serving(server)));
            } catch (AgentBridge.Unavailable e) {
                return false;
            }
        };
        // Only what is left of a normal join (2026-10-21): a camera that should have been
        // here by now is not waited for at all — the gold-dragon demo waited 90 s for one that
        // never came. CameraWait.budgetMs.
        long budget = com.moddingfromamod.wish.CameraWait.budgetMs(
                com.moddingfromamod.wish.CameraWait.maxWaitMs(),
                com.moddingfromamod.wish.CameraSupervisor.startingForMs(System.currentTimeMillis()));
        var r = com.moddingfromamod.wish.CameraWait.await(demand, serving,
                com.moddingfromamod.wish.CameraSupervisor::gaveUp,
                budget, 250, System::currentTimeMillis, PhotographTool::sleep);
        com.moddingfromamod.wish.CameraWait.record(com.moddingfromamod.wish.CameraWait.file(),
                com.moddingfromamod.wish.CameraWait.line(System.currentTimeMillis(), wish, r, demand,
                        com.moddingfromamod.wish.CameraNeed.shared().cls(wish)));
    }

    /**
     * <b>A creature's HANDLE, photographed as the creature</b>. Every subject path
     * below frames a creature by its species id; {@code finish_building subject:"@llama"} — the
     * handle production minted for Spikor's carrier — answered <i>"Couldn't find a '@llama' in
     * the world to point at"</i>. So a handle that names a wished creature becomes its species id
     * here, once, and nothing below needs to know handles exist. Anything else passes unchanged.
     */
    static String creatureSubject(String aim) {
        if (aim == null || !com.moddingfromamod.mob.EntityHandles.looksLikeHandle(aim)) {
            return aim;
        }
        var h = com.moddingfromamod.mob.EntityHandles.global().resolve(aim);
        if (h.isEmpty()) {
            return aim;
        }
        try {
            var server = AgentBridge.serverOrNull();
            java.util.function.Supplier<String> look = () -> {
                var e = com.moddingfromamod.mob.EntityCapture.findAnywhere(server, h.get().id());
                var sp = e == null ? null : com.moddingfromamod.mob.Creatures.speciesOf(e);
                return sp == null ? null : sp.id();
            };
            String live = server == null ? null
                    : server.isSameThread() ? look.get() : AgentBridge.onServer(look);
            if (live != null) {
                return live;
            }
        } catch (RuntimeException e) {
            // fall through to what the handle itself recorded
        }
        String word = com.moddingfromamod.mob.CreatureMatch.speciesOfKind(h.get().typeId());
        return word == null ? aim : word;
    }

    /** What this child most recently made, or "" if that cannot be answered here. */
    static String lastArtifactForCurrentPlayer() {
        try {
            var server = AgentBridge.serverOrNull();
            if (server == null) {
                return "";
            }
            // ⚠ THE CHILD, not the viewpoint — the ONE site in this file that is not a camera.
            // "What did he last make" is a question about a person's history; a camera has no
            // artifacts, so eyes() here would answer nothing for every wish once one is configured.
            // A history needs no body: the wish's own owner answers even after he has left, and
            // another child's history never does (decision 1).
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            ServerPlayer p = com.moddingfromamod.agent.ActingChild.person(server);
            java.util.UUID who = run != null && run.playerId() != null ? run.playerId()
                    : p == null ? null : p.getUUID();
            if (who == null) {
                return "";
            }
            return com.moddingfromamod.marshal.Marshal.global().lastArtifactFor(who);
        } catch (Exception e) {
            return "";                      // a missing default must never cost the photograph
        }
    }

    private static ToolResult captureAimedAt(String label, String subject, String intro) {
        // FOUR SIDES WHENEVER WE KNOW WHAT WE ARE LOOKING AT. An orbit needs something to
        // orbit; with no subject there is nothing to circle and the single shot is still the
        // honest answer -- "whatever the child is facing" has no other side.
        if (!subject.isEmpty()) {
            // A BUILDING FIRST, because a building is not a mob and findSubject only knows mobs.
            // Without this a castle fell through to the single plain snapshot below — taken from
            // wherever the child happened to be standing — and that was the picture on every
            // building wish card.
            ToolResult built = captureBuilding(label, subject, intro);
            if (built != null) {
                return built;
            }
            // AND AN ITEM, which is neither. It is in his hand, so nothing in the world frames it
            // — before this, every item wish card was the single plain snapshot below: a picture of
            // grass, some flowers and the wish sparkles, with the wand nowhere in it.
            ToolResult held = captureHeldItem(label, subject, intro);
            if (held != null) {
                return held;
            }
            ToolResult sheet = captureOrbit(label, subject, intro);
            if (sheet != null) {
                return sheet;
            }
            // The creature could not be found, or not one angle arrived. Fall through: a plain
            // photograph of the wrong thing still beats no photograph, and the agent is told.
        }
        Path out = photoPath(label);
        final StringBuilder framing = new StringBuilder();
        // Read on THIS thread: the run is bound to the agent's thread, not the server's.
        final WishSpot spot = wishSpot(com.moddingfromamod.agent.AgentContext.currentRun());

        long before = lastModified(out);
        try {
            String sent = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                // ⚠ THE CAMERA IF THERE IS ONE, ELSE THE CHILD'S EYES — and the branch happens
                // HERE, once (Part B). Everything below this line is identical for both:
                // framing, the line-of-sight check, the settle, waiting for the file to stop
                // growing, the decode. Two viewpoints must not become two capture paths, which is
                // the thing that would make the camera a maintenance cost rather than a feature.
                com.moddingfromamod.wish.Viewpoint view =
                        com.moddingfromamod.wish.Viewpoint.acquire(server);
                if (view == null) {
                    return "nobody";
                }
                ServerPlayer player = view.player();
                // Enter Wish Capture Mode BEFORE aiming, so the child's real spot + view are recorded
                // before the camera turns — he's frozen, made safe, told what's happening, and warped
                // back when the burst ends. A later shot in the same burst just extends the session.
                // The camera is a lease: he has one body, so only one wish may freeze him at a
                // time. Refused rather than stolen — stealing gives the other wish a picture of
                // somewhere else AND leaves him unfrozen while it is still shooting.
                String noCamera = leaseProblem(view);
                if (noCamera != null) {
                    return noCamera;
                }
                if (!subject.isEmpty()) {
                    framing.append(aimAt(view, subject));
                }
                // NEVER A BLIND SHOT WHILE A WISH IS RUNNING (F): with no subject, or one the
                // world does not hold, this used to be wherever the viewpoint already stood — a
                // garage's face shot was empty sky. Aim at the wish's own spot instead.
                if (subject.isEmpty() || framing.indexOf(NOT_FOUND) >= 0) {
                    String at = aimAtWishSpot(view, spot);
                    if (at != null) {
                        framing.setLength(0);
                        framing.append(at);
                    }
                }
                shutter(player, label, com.moddingfromamod.net.ModdedClient.photo(label));
                return "ok";
            });
            if ("nobody".equals(sent)) {
                return ToolResult.noPlayerOnline("Nobody is in the world, so there's nothing to photograph.");
            }
            if ("noeyes".equals(sent)) {
                // NOT an error the agent should retry or apologise for: nothing in the world can
                // open a shutter. Said plainly so the agent carries on building rather than
                // burning turns on it. §0.5 holds — a runtime result, not a schema change.
                //
                // ⚠ IT NO LONGER NAMES A CAUSE, and that is the fix. It used to say "this child is
                // playing without the mod installed", which was the only way to get here while a
                // camera had to be configured by hand. Since a dedicated server starts one
                // whenever a child is online, so a vanilla child normally photographs perfectly
                // well through it and reaching this line usually means the CAMERA is down --
                // CameraSupervisor logs that separately and loudly. Naming the wrong one of two
                // causes in the agent's own transcript is how a misdiagnosis gets repeated back to
                // a human later, and the agent's next move is identical either way.
                return ToolResult.error("There is nothing in this world that can take a "
                        + "photograph right now — no camera is connected and the child's own "
                        + "screen can't take one. Carry on without a picture; don't try again.");
            }
            if ("busy".equals(sent)) {
                return ToolResult.error("Another wish is borrowing the child's eyes for its own "
                        + "photo right now. Carry on with something else and photograph in a moment "
                        + "— it will be free within a few seconds.");
            }
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }

        // The client takes the shot a few ticks later and writes the file; wait for it to change.
        // KEEP TRYING until the deadline, and only decode once the file has stopped growing.
        //
        // The timestamp changes when the file is CREATED, not when it is finished, and a screenshot
        // of a modern window is 1-2 MB. The old code slept a flat 150ms, decoded ONCE, and on
        // failure reported "the photo was taken but couldn't be read back" — burning 0.5s of an 8s
        // budget and giving up. Live on 2026-08-04 that failed all five times in a session while
        // the PNGs sat complete on disk moments later. It left the agent blind, which is what sent
        // it probing the world with commands for four minutes.
        //
        // Waiting for a stable size (rather than decoding every poll) means one decode in the
        // normal case instead of up to 50 on the player's own machine.
        //
        // ⚠ THE DEADLINE IS A QUIET PERIOD, NOT A FIXED BUDGET, and that is a measurement rather
        // than a preference. A flat 8s straddles the variance of a headless client: measured on a
        // no-GPU box, one run's photograph was stable at 10,666 ms while the tool returned at
        // 8,245 ms — so the agent was told "the photo didn't arrive" about a picture that was on
        // disk — and an earlier run of the same code fitted inside 8s. Any constant I pick here is
        // a guess about somebody's machine.
        //
        // This matters most where it is least convenient: a service-run camera lives on a
        // server with NO GPU, so software rendering is its NORMAL case, not its worst one.
        //
        // So: keep waiting while the file is still ARRIVING, and give up when nothing has happened
        // for QUIET_MS. Fast when it works — the stable-size check returns on the first quiet poll
        // exactly as before — and still bounded when the shutter never fires, which is what the
        // budget was really for. HARD_CAP_MS is the backstop for a file that grows for ever.
        //
        // ⚠ AND THE QUIET PERIOD ONLY STARTS ONCE THE FILE EXISTS. An absent file is not a
        // file that is still arriving: the loop that used to live here read its size as 0 against
        // an initial -1 and counted that as growth on every poll, so a photo that never came ran
        // to the cap. Waiting for the file to APPEAR is now its own deadline, APPEAR_MS, and the
        // rules for both waits live in PhotoWait, where a fake clock tests them.
        PhotoWait.Outcome<byte[]> got = awaitFile(out, before, APPEAR_MS, QUIET_MS);
        if (got.status() == PhotoWait.Status.SETTLED) {
            String lead = intro != null ? intro
                    : "Here's what the child can see right now. Look at it honestly: "
                      + "does it match what they asked for? If it looks scary when they "
                      + "wanted something friendly, fix it and photograph it again.";
            return ToolResult.image(lead + (framing.length() == 0 ? "" : " " + framing),
                    Base64.getEncoder().encodeToString(got.value()), "image/png");
        }
        long waited = got.waitedMs();
        if (got.appeared()) {
            LOGGER.warn("photo {} appeared but never became readable ({}); gave up after {}ms",
                    out, got.status(), waited);
            return ToolResult.error("The photo was written but never became readable (waited "
                    + (waited / 1000) + "s). Carry on without it — do NOT keep retrying.");
        }
        return ToolResult.error("The photo didn't arrive (waited " + (waited / 1000)
                + "s with nothing happening). Is the game window open?");
    }

    /**
     * <b>Four sides, one picture.</b>
     *
     * <p>One photograph is one gamble. A spawned mob inherits the spawning player's yaw and mob AI
     * ignores whoever is watching, so which way it is pointing when the shutter opens is arbitrary
     * — measured in the eval harness, every "front" shot it took was of the creature's BACK until
     * this was fixed there, and the first fully-built Spikor photograph is a picture of a shell.
     * The agent judged the wish, and told the child, from that.
     *
     * <p>The CAMERA flies; the creature is not touched. Forcing a mob to face the camera mutates
     * the thing being measured, and a mob whose facing has been forced is not the mob the child
     * meets. Borrowing the child's eyes is only acceptable because {@code WishCapture} has already
     * recorded where they were standing and will put them back.
     *
     * <p><b>Degrades rather than fails.</b> Any stop whose file never arrives is dropped and the
     * sheet is composed from the rest, with the count stated. A photograph that fails outright
     * leaves the agent blind, and a blind agent probes the world with commands — which is where
     * four minutes of one measured wish went.
     *
     * @return the sheet, or null when the subject cannot be found (caller falls back to one shot)
     */
    /**
     * <b>Eight photographs of a building</b> — four around it, four standing inside — or null when
     * this subject is not a building we have a plan and an anchor for.
     *
     * <h2>Why it is not captureOrbit with a different subject</h2>
     * That method orbits an ENTITY: it finds a mob, frames on creature model geometry, and re-aims
     * between aiming and firing because a creature can turn or walk away in that gap. A building
     * does none of those things, so the re-check is pure latency here and is dropped — one round
     * trip per stop instead of two.
     *
     * <p>Everything else it needs is already recorded: {@code BuildRegistry} keeps the plan AND the
     * anchor the builder placed it at, so "where do I stand" is arithmetic on data we already have.
     * {@link BuildFraming} does that arithmetic, and the test fixture uses the same call, so a
     * photograph that comes out wrong is wrong in both places at once rather than in only the one
     * nobody is looking at.
     */
    /** How long to wait between attempts at the camera lease. */
    private static final long CAMERA_RETRY_MS = 500L;

    /**
     * The longest a FILMED take holds the lease — and the second reason the wait below must
     * be derived. A spin or an event film renews its claim every second until its strip lands, so it
     * holds the camera far past {@code MAX_SESSION_MS}: measured on a bench server's VM cameras, an
     * event film 8.7–16.9 s, a spin 5.4–13.1 s. With the wait still sized for a ten-second still, a
     * picture behind two event films gave up "busy" after 12 s and its card was an unframed snapshot.
     * Outlasting two takes covers a wish queued behind two others on one body.
     */
    static final long LONGEST_TAKE_MS = 30_000L;

    /** How long a capture really waits for a busy camera — for the gate that compares it. */
    static long cameraWaitBudgetMs() {
        return CAMERA_ATTEMPTS * CAMERA_RETRY_MS;
    }

    /**
     * How many times to wait for the camera lease — <b>derived, so it cannot be shorter than the
     * thing it is waiting for.</b>
     *
     * <p>This was a flat 6, i.e. three seconds, against a lease whose holder is guaranteed to let
     * go only after {@link WishCapture#MAX_SESSION_MS} — ten. So a wish that queued for the camera
     * gave up at 3 s every single time and fell through to the unframed snapshot, which is the wish
     * card. The queue made that wait FAIR without making it long enough to be worth taking, which
     * is a worse failure than the one it replaced: fair, ordered, and still nobody's turn.
     *
     * <p>Two constants in two files, and nothing compared them. Deriving one from the other is what
     * stops the next person tuning the cap and silently un-fixing this.
     */
    private static final int CAMERA_ATTEMPTS =
            attemptsToOutlast(Math.max(WishCapture.MAX_SESSION_MS, 2 * LONGEST_TAKE_MS), CAMERA_RETRY_MS);

    /**
     * Enough attempts to outlast a hold cap, plus one — pure, so the invariant is assertable.
     *
     * <p>Deliberately NOT sized against {@link WishCapture#MAX_CAMERA_HOLD_MS} (a minute): a wish
     * cannot block for a minute waiting on a camera another wish is legitimately using. That case
     * is one camera not being enough, and it degrades to an unframed card rather than to a stall.
     */
    static int attemptsToOutlast(long holdCapMs, long retryMs) {
        // NEVER ZERO. With both caps at 0 the arithmetic answers 0, and a loop that runs no times
        // does not "give up quickly" -- it never asks at all, so a camera that was free the whole
        // time reads as unavailable. Caught by the gate that asserts it, on my own code.
        return Math.max(1, (int) Math.ceil((holdCapMs + retryMs) / (double) Math.max(1, retryMs)));
    }

    /**
     * Take the camera, and say what to return if we could not.
     *
     * <p>One place maps {@link com.moddingfromamod.wish.WishCapture.Lease} onto the sentinel
     * strings these bodies pass back across {@code AgentBridge.onServer}, so a seventh capture site
     * cannot invent a seventh spelling — and so {@code "noeyes"} is, by construction, not something
     * {@link #worthWaitingFor} will retry.
     *
     * @return null when the ritual may proceed; otherwise the sentinel to return
     */
    /**
     * <b>The eyes this shot is taken through</b> — the camera if one is in the world, else the
     * child's.
     *
     * <p>Every capture site in this file goes through here, and that is the point rather than
     * tidiness: with a camera configured and only SOME sites routed, one wish would shoot one frame
     * from the camera and the rest from the child, which is worse than either choice and invisible
     * until a contact sheet comes back with one frame from somewhere else.
     *
     * <p>Inert when no camera is configured: {@link com.moddingfromamod.wish.Viewpoint#acquire}
     * falls through to {@code ActingChild}, so a one-machine game behaves exactly as it did.
     */
    private static ServerPlayer eyes(net.minecraft.server.MinecraftServer server) {
        var view = com.moddingfromamod.wish.Viewpoint.acquire(server);
        return view == null ? null : view.player();
    }

    private static String leaseProblem(ServerPlayer player) {
        return leaseProblem(com.moddingfromamod.wish.Viewpoint.of(player));
    }

    /** The same, for a caller that has already resolved which eyes it is using. */
    private static String leaseProblem(com.moddingfromamod.wish.Viewpoint view) {
        var lease = view.take();
        if (lease == com.moddingfromamod.wish.WishCapture.Lease.TAKEN) {
            return null;
        }
        return lease == com.moddingfromamod.wish.WishCapture.Lease.NO_CAMERA ? "noeyes" : "busy";
    }

    /**
     * Is this a reason to wait and try again, or a settled fact?
     *
     * <p>Only a contended camera and a bridge that is not up yet are worth waiting out. "It is not a
     * building" and "it has never been built" will be just as true in three seconds, and retrying
     * them only delays the creature orbit that should get its turn.
     *
     * <p>Its own method so the policy is assertable without a player, a client or a camera —
     * headlessly, the reasons are all a GameTest world can reach.
     */
    public static boolean worthWaitingFor(String reason) {
        return reason != null && ("busy".equals(reason) || reason.startsWith("unavailable"));
    }

    /**
     * Stop waiting for the camera, and say so.
     *
     * <p>Best-effort by design: it runs on the agent thread and the body it is queued behind may
     * already be gone, so it asks the server for the viewpoint and does nothing if there is not
     * one. A missed abandon costs a stale place that {@code MAX_WAIT_MS} clears anyway; blocking a
     * give-up on a server round trip that may fail would cost the give-up.
     */
    private static void releaseQueuePlace() {
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                var view = com.moddingfromamod.wish.Viewpoint.acquire(server);
                var run = com.moddingfromamod.agent.AgentContext.currentRun();
                if (view != null && view.player() != null && run != null) {
                    WishCapture.abandonCamera(view.player().getUUID(), run.wishId());
                }
                return "";
            });
        } catch (AgentBridge.Unavailable ignored) {
            // The bridge is down, so nothing is holding a place on a server we cannot reach.
        }
    }

    /** Why the last ITEM shot was skipped, or null if it was taken. Read by the gates. */
    public static volatile String lastItemSkip;

    /**
     * <b>Photograph a thing he HOLDS, by putting it in his hand and looking at it.</b>
     *
     * <p>An item is the one artifact with nothing in the world to frame. It is not a building and
     * not a mob, so it fell to the single unframed snapshot taken from wherever the child happened
     * to be standing — and the wish card for a purple wand that shoots lightning was a photograph of
     * grass, flowers, a lake and the wish sparkles, with the wand not in it.
     *
     * <p>The picture is taken FIRST PERSON on purpose: that is literally the view he gets when he
     * uses the thing, and the item model is drawn by {@code RenderHandEvent}, which only runs for
     * the local view. Framing it in the world would mean dropping it as an entity — a different
     * thing, lying on the ground, at the mercy of anything that eats items.
     *
     * @return the photo, or null when this subject is not an item — never a wrong picture
     */
    /**
     * Put the stack of this made item he already carries into his main hand, swapping it with
     * whatever was there. @return false when he carries none, so the caller gives him one.
     */
    static boolean intoHand(ServerPlayer player, String feature) {
        var inv = player.getInventory();
        int held = inv.getSelectedSlot();
        if (feature.equals(com.moddingfromamod.item.WishItem.wishFeature(inv.getItem(held)))) {
            return true;
        }
        for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
            if (feature.equals(com.moddingfromamod.item.WishItem.wishFeature(inv.getItem(i)))) {
                net.minecraft.world.item.ItemStack his = inv.getItem(i);
                inv.setItem(i, inv.getItem(held));
                inv.setItem(held, his);
                inv.setChanged();
                player.inventoryMenu.broadcastChanges();
                return true;
            }
        }
        return false;
    }

    public static ToolResult captureHeldItem(String label, String subject, String intro) {
        final String[] gave = {null};
        String found;
        try {
            found = AgentBridge.onServer(() -> {
                com.moddingfromamod.item.ItemSpecStore.Spec spec =
                        com.moddingfromamod.item.ItemSpecStore.find(subject);
                if (spec == null) {
                    return "no item by that name";
                }
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = eyes(server);
                if (player == null) {
                    return "nobody";
                }
                String noCamera = leaseProblem(player);
                if (noCamera != null) {
                    return noCamera;
                }
                gave[0] = spec.id();
                return "ok";
            });
        } catch (AgentBridge.Unavailable e) {
            found = "unavailable: " + e.getMessage();
        }
        if (!"ok".equals(found)) {
            lastItemSkip = found;
            System.out.println("[photograph] not photographing " + subject + " as a held item: "
                    + found);
            return null;
        }
        lastItemSkip = null;
        Path out = photoPath(label);
        long before = lastModified(out);
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = eyes(server);
                if (player != null) {
                    // HIS OWN COPY, from the pack that defines it, rather than a stack built here:
                    // the item IS its datapack, and a hand-assembled lookalike would photograph the
                    // components rather than the thing.
                    // ⚠ ONLY IF HE HAS NONE. make_item tells the agent to hand it over, so
                    // by the reveal he usually holds one already, and giving another here left him
                    // with TWO — measured by Gate D's itemCheck, two tridents and two rods in every
                    // run. If he has it, it is moved into his hand instead.
                    if (!intoHand(player, gave[0])) {
                        server.getCommands().performPrefixedCommand(
                                com.moddingfromamod.compat.EntityCommands.source(player).withPermission(4),
                                "wishdatapack " + gave[0] + " give");
                    }
                    shutter(player, label,
                            // `held`, NOT `at` --. `at` hides the HUD, and vanilla draws the
                            // held item on that path, so an item photograph taken with `at` is a
                            // picture of the scenery behind an empty hand. Every one of them was.
                            com.moddingfromamod.net.ModdedClient.photoHeld(label, ORBIT_SETTLE_TICKS, false,
                                    player.getX(), player.getY(), player.getZ(),
                                    player.getYRot(), 0f, true));
                }
                return "ok";
            });
        } catch (AgentBridge.Unavailable e) {
            return null;
        }
        sleep(ORBIT_GAP_MS);
        String lead = intro != null ? intro
                : "This is what he is holding — the item in his own hand, which is the view he gets "
                + "when he uses it. Judge it on this, then finish_wish.";
        // AWAIT IT PROPERLY. A PNG's mtime moves when the file is CREATED, not when it is finished,
        // and the version that read once and gave up failed five times in one live session while
        // complete files sat on disk moments later.
        byte[] png = awaitPhoto(out, before, STOP_TIMEOUT_MS);
        return png == null
                ? ToolResult.ok(lead + "\n(the picture did not arrive; say so rather than guessing)")
                : ToolResult.image(lead, java.util.Base64.getEncoder().encodeToString(png),
                        "image/png");
    }

    /**
     * <b>The board of the picture {@code subject} names</b>, or null when it names none —
     * through {@code Reveals.boardOf}, the same lookup the reveal traces its beads from, so every
     * caller that photographs a picture frames the one board the world holds. {@code picture:<id>}
     * names a picture explicitly (starter thumbnails use it, so a picture id can never
     * be mistaken for a creature or a build of the same name). Server thread.
     */
    static com.moddingfromamod.wish.reveal.RevealFrame pictureBoard(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        String id = subject.startsWith(PICTURE_PREFIX) ? subject.substring(PICTURE_PREFIX.length()) : subject;
        com.moddingfromamod.paint.PaintRecord pic = com.moddingfromamod.paint.PaintRegistry.get(id);
        var server = AgentBridge.serverOrNull();
        if (pic == null || server == null) {
            return null;
        }
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(pic.dimension())) {
                return com.moddingfromamod.wish.reveal.Reveals.boardOf(level, pic);
            }
        }
        return null;
    }

    /** A subject that names a picture and nothing else: {@code picture:<id>}. */
    public static final String PICTURE_PREFIX = "picture:";

    /** Why the last building shot was skipped, or null if it was taken. Read by the Gate-C test. */
    public static volatile String lastBuildingSkip;

    /**
     * The same answer, for the thread that asked. {@link #lastBuildingSkip} is one value for the
     * whole server, so two GameTests shooting at once read each other's reason — measured
     * 2026-09-27: "a design never built" came back as its neighbour's "no saved design".
     */
    public static final ThreadLocal<String> BUILDING_SKIP_ON_THIS_THREAD = new ThreadLocal<>();

    public static ToolResult captureBuilding(String label, String subject, String intro) {
        // WHERE THE SUBJECT IS, horizontally — the one thing a Pose does not carry and the line-
        // of-sight check cannot do without. Its HEIGHT is recovered exactly from each
        // pose's own pitch, so only x/z has to be plumbed; NaN means "this branch did not say",
        // and the correction is then skipped rather than aimed at a guess.
        final double[] subjectXZ = { Double.NaN, Double.NaN };
        // A picture has a front and nothing else, so it is not "the building from four sides".
        final boolean[] picture = { false };
        final java.util.List<com.moddingfromamod.builder.BuildFraming.Pose> poses =
                new java.util.ArrayList<>();
        // WAIT FOR THE CAMERA RATHER THAN GIVING UP ON IT. WishCapture.enter's own contract says
        // "the caller should shoot later rather than steal it", and this caller used to hear "busy"
        // and quietly fall through to the single unframed snapshot — which is the wish card. One
        // ladder run produced no framed shot at all and an identical build produced eight per rung,
        // so the difference was never the code; it was who happened to be holding the lease.
        String found = "unavailable";
        // ⚠ HO-14: this loop IS the camera queueing, so this is where it gets measured. A counter
        // that nothing increments is decoration — the shape this repo names as "tested where it is
        // DEFINED, not where it RUNS" — and every other place a wait could be recorded would be
        // recording somebody's guess about this one.
        final long cameraWaitStartedMs = System.currentTimeMillis();
        for (int attempt = 0; attempt < CAMERA_ATTEMPTS; attempt++) {
            poses.clear();
            try {
                found = AgentBridge.onServer(() -> {
                    // IS IT A BUILDING FIRST, before looking for anybody. Neither question needs a
                    // player, and asking about the player first collapses every distinct reason
                    // into "nobody" the moment there is not one — which is every headless test, so
                    // the gate on these reasons could not tell any of them apart.
                    com.moddingfromamod.builder.BuildPlan plan =
                            com.moddingfromamod.builder.BuildRegistry.get(subject);
                    Long anchor = com.moddingfromamod.builder.BuildRegistry.anchorOf(subject);
                    if (plan == null) {
                        // A MADE MACHINE HE HAS PUT DOWN: an item, so no registry above
                        // knows it, but its anchor is an armor stand tagged `<id>_built` — the
                        // body, handle and sign stand around it. Not put down: fall through, and
                        // the held-item framing shows it in his hand instead.
                        var server2 = AgentBridge.serverOrNull();
                        ServerPlayer p2 = server2 == null ? null : eyes(server2);
                        var spec2 = com.moddingfromamod.item.ItemSpecStore.get(subject);
                        if (p2 != null && spec2 != null
                                && (com.moddingfromamod.datapack.ItemCatalogue.MACHINE.equals(spec2.shape())
                                    // a magic block stands in the world on the same anchor
                                    || com.moddingfromamod.datapack.MagicBlock.SHAPE.equals(spec2.shape()))) {
                            net.minecraft.world.entity.Entity stand = null;
                            for (var e : p2.level().getEntities().getAll()) {
                                if (e != null && !e.isRemoved() && e.getTags().contains(subject + "_built")
                                        && (stand == null || e.distanceToSqr(p2) < stand.distanceToSqr(p2))) {
                                    stand = e;
                                }
                            }
                            if (stand != null) {
                                net.minecraft.core.BlockPos mAt = stand.blockPosition();
                                subjectXZ[0] = mAt.getX() + 0.5;
                                subjectXZ[1] = mAt.getZ() + 0.5;
                                // The shape tags which way it faces (${FEATURE}_s/_n/_e/_w: the
                                // handle is on that side), so the first frame is its front.
                                java.util.Set<String> tags = stand.getTags();
                                double front = tags.contains(subject + "_s") ? 180
                                        : tags.contains(subject + "_e") ? 90
                                        : tags.contains(subject + "_w") ? 270 : 0;
                                poses.addAll(com.moddingfromamod.item.MadeMachineFraming.orbit(
                                        mAt.getX(), mAt.getY(), mAt.getZ(), front));
                                String noCamera = leaseProblem(p2);
                                if (noCamera != null) {
                                    return noCamera;
                                }
                                p2.setNoGravity(true);
                                return "ok";
                            }
                        }
                        // A PICTURE: found by the first real picture wish on Gate D, whose
                        // reveal said "Couldn't find a '<id>' in the world to point at" and shot
                        // whatever the child was facing. PaintRegistry knows where each one hangs,
                        // which way it faces and how big it is, so it is framed from the front.
                        com.moddingfromamod.wish.reveal.RevealFrame board = pictureBoard(subject);
                        if (board != null) {
                            ServerPlayer p3 = server2 == null ? null : eyes(server2);
                            if (p3 == null) {
                                return "nobody";
                            }
                            // FRAMED BY THE BOARD: the reveal's own record of where it
                            // hangs, which way it faces and how big it is — never his eye.
                            subjectXZ[0] = board.cx();
                            subjectXZ[1] = board.cz();
                            picture[0] = true;
                            poses.addAll(com.moddingfromamod.paint.PictureFraming.poses(board));
                            if (poses.isEmpty()) {
                                return "plan composes to nothing";
                            }
                            String noCamera = leaseProblem(p3);
                            if (noCamera != null) {
                                return noCamera;
                            }
                            p3.setNoGravity(true);
                            return "ok";
                        }
                        return "no saved design under that id";
                    }
                    if (anchor == null) {
                        return "never built this run, so nobody knows where it stands";
                    }
                    var server = AgentBridge.serverOrNull();
                    ServerPlayer player = eyes(server);
                    if (player == null) {
                        return "nobody";
                    }
                    net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.of(anchor);
                    subjectXZ[0] = at.getX() + 0.5;
                    subjectXZ[1] = at.getZ() + 0.5;
                    poses.addAll(com.moddingfromamod.builder.BuildFraming.all(
                            plan, at.getX(), at.getY(), at.getZ()));
                    if (poses.isEmpty()) {
                        return "plan composes to nothing";
                    }
                    String noCamera = leaseProblem(player);
                    if (noCamera != null) {
                        return noCamera;
                    }
                    player.setNoGravity(true);
                    return "ok";
                });
            } catch (AgentBridge.Unavailable e) {
                found = "unavailable: " + e.getMessage();
            }
            if ("ok".equals(found) || !worthWaitingFor(found)) {
                if ("ok".equals(found) && attempt > 0) {
                    // It waited and then got it. That is the cap working as intended, and it is
                    // still a cost: `moddy_wish_wait_seconds_total{reason="camera"}` is what a
                    // concurrency limit of 2 buys and what raising it would buy back.
                    com.moddingfromamod.health.Counters.waited(
                            "camera", System.currentTimeMillis() - cameraWaitStartedMs);
                }
                break;
            }
            sleep(CAMERA_RETRY_MS);
        }
        if (!"ok".equals(found)) {
            // ⚠ AND THIS IS THE SILENT QUALITY LOSS, counted at last. The wish still succeeds, the
            // child still gets a card, and it is the wrong picture — one ladder run produced no
            // framed shot at all while an identical build produced eight per rung. Nothing
            // anywhere said so. HO-14 caps the cameras, which makes this MORE likely, so a cap
            // that costs something is now arguable with evidence rather than with taste.
            com.moddingfromamod.health.Counters.waited(
                    "camera", System.currentTimeMillis() - cameraWaitStartedMs);
            com.moddingfromamod.health.Counters.photoDegraded(
                    worthWaitingFor(found) ? "camera_timeout" : "no_camera");
            // GIVE UP THE PLACE IN LINE, not just the attempt. A waiter that walks away without
            // saying so is a corpse at the head of the queue: the wish behind it waits out the
            // whole 60 s backstop for a turn that was already free, and queueDepth reports
            // congestion that is not real -- which is the number an earlier issue's follow-up is meant to
            // judge whether one camera is still enough.
            releaseQueuePlace();
            // SAY WHY. This fell through to the single unframed snapshot silently, and the wish
            // card for a real Spikor castle came out as a close-up of a wall — with every gate
            // green, because the fixture drove BuildFraming itself and never asked this method
            // anything. A quiet fallback is how a fix ships without being wired up.
            lastBuildingSkip = found;
            BUILDING_SKIP_ON_THIS_THREAD.set(found);
            System.out.println("[photograph] not photographing " + subject
                    + " as a building: " + found);
            return null;
        }
        lastBuildingSkip = null;
        BUILDING_SKIP_ON_THIS_THREAD.remove();

        final int settleTicks = ORBIT_SETTLE_TICKS;
        java.util.List<ContactSheet.Frame> frames = new java.util.ArrayList<>();
        // a HERO still (and one interior, the best room) then one filmed turn through the
        // framer's own poses — a building's four sides as a ring, a machine's orbit as a ring. A
        // picture keeps only its front: the sides were a 25° nudge of a flat board.
        final java.util.List<com.moddingfromamod.builder.BuildFraming.Pose> stills =
                new java.util.ArrayList<>(poses);
        java.util.List<com.moddingfromamod.runtime.OrbitClipPath.Pose> spinKeys = null;
        boolean spinClosed = true;
        com.moddingfromamod.builder.BuildFraming.Pose insideRoom = null;
        if (SPIN && !poses.isEmpty()) {
            if (picture[0]) {
                stills.clear();
                stills.add(poses.get(0));
            } else if (!Double.isNaN(subjectXZ[0])) {
                java.util.List<com.moddingfromamod.builder.BuildFraming.Pose> outside = new java.util.ArrayList<>();
                com.moddingfromamod.builder.BuildFraming.Pose room = null;
                for (var p : poses) {
                    if (p.name().startsWith("in")) {
                        room = room == null ? p : room;
                    } else {
                        outside.add(p);
                    }
                }
                java.util.List<com.moddingfromamod.builder.BuildFraming.Pose> keyPoses = new java.util.ArrayList<>();
                if (outside.size() >= 4) {
                    keyPoses.addAll(outside.subList(0, 4));
                }
                if (keyPoses.size() >= 2) {
                    spinKeys = new java.util.ArrayList<>();
                    for (var p : keyPoses) {
                        spinKeys.add(new com.moddingfromamod.runtime.OrbitClipPath.Pose(p.x(), p.y(), p.z(),
                                p.yaw(), p.pitch()));
                    }
                    stills.clear();
                    stills.add(keyPoses.get(0));
                    if (room != null) {
                        stills.add(room);
                        insideRoom = room;
                    }
                }
            }
        }
        for (int i = 0; i < stills.size(); i++) {
            com.moddingfromamod.builder.BuildFraming.Pose pose = stills.get(i);
            byte[] png = shootStill(label, subject, pose, settleTicks, subjectXZ, i == stills.size() - 1,
                    i == 0 ? HERO_TIMEOUT_MS : STOP_TIMEOUT_MS);
            if (png != null) {
                frames.add(new ContactSheet.Frame(png, (int) pose.yaw()));
            }
        }
        if (spinKeys != null) {
            // The aim the stills were pointed at, recovered from the first key exactly as their
            // line-of-sight check recovers it: eyeY − flat·tan(pitch).
            var k0 = spinKeys.get(0);
            double flat0 = Math.hypot(k0.x() - subjectXZ[0], k0.z() - subjectXZ[1]);
            double aimY = k0.y() + EYE_HEIGHT - flat0 * Math.tan(Math.toRadians(k0.pitch()));
            Spin spin = spinTake(label, spinKeys, spinClosed, subjectXZ[0], subjectXZ[1], aimY,
                    spinClosed ? SPIN_SECONDS : SPIN_SECONDS * 0.75);
            if (spin != null) {
                // the sheet keeps its four sides: the hero and three quarter-turns of the take
                java.util.List<ContactSheet.Frame> sides = new java.util.ArrayList<>();
                addSpinSides(sides, spin, k0.yaw(), spinClosed);
                frames.addAll(Math.min(1, frames.size()), sides);
            } else {
                // no strip came back: the stills the spin replaced, as before
                for (var p : poses) {
                    if (!stills.contains(p)) {
                        byte[] png = shootStill(label, subject, p, settleTicks, subjectXZ,
                                p == poses.get(poses.size() - 1));
                        if (png != null) {
                            frames.add(new ContactSheet.Frame(png, (int) p.yaw()));
                        }
                    }
                }
            }
        }

        if (INSIDE_SPIN && insideRoom != null) {
            // The inside turn (2026-11-09: "an inside 360 in addition to the outside 360", tried
            // behind camera.insideSpin before it becomes a default). Filed as <label>__inside; the sheet
            // keeps its interior still.
            Spin inside = insideTake(label, insideRoom);
            LOGGER.info("inside spin: '{}' {}", subject, inside == null ? "no strip came back" : inside.frames().size() + " frames");
        }

        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = eyes(server);
                if (player != null) {
                    player.setNoGravity(false);
                }
                return "ok";
            });
        } catch (AgentBridge.Unavailable ignored) {
            // shutting down; the flag goes with the session
        }

        byte[] sheet = ContactSheet.compose(frames);
        if (sheet == null) {
            // SAY SO: framed, leased, and not one frame back — the caller then tries it as a creature
            // and reports "missing", which hid this on a bench server.
            LOGGER.warn("{} '{}' was framed but no frame came back — falling back", picture[0] ? "picture"
                    : "building", subject);
            return null;
        }
        int outside = Math.min(frames.size(), com.moddingfromamod.runtime.OrbitCamera.STOPS);
        int inside = Math.max(0, frames.size() - outside);
        String lead = intro != null ? intro
                : picture[0]
                ? "Here is the picture, square-on from in front and from a little to either side. "
                  + "Look at it honestly: is the WHOLE thing what they asked for?"
                : "Here is the building from four sides"
                  + (inside > 0 ? ", and from inside it looking four ways" : "")
                  + ". Look at it honestly: is it what they asked for, and can they get in?"
                  + (inside == 0
                     ? " (There was no room inside to stand in — if it was meant to be a place they "
                       + "can go into, it is solid.)"
                     : "");
        return ToolResult.image(lead, Base64.getEncoder().encodeToString(sheet),
                "image/png");
    }

    /** Eye height of a standing camera body: a pose's feet + this is where the lens is. */
    static final double EYE_HEIGHT = 1.62;

    /**
     * One building / train / machine / picture still: line of sight checked, pinned, awaited — the
     * body of captureBuilding's stills loop, shared with the spin's fallback.
     */
    private static byte[] shootStill(String label, String subject,
                                     com.moddingfromamod.builder.BuildFraming.Pose pose, int settleTicks,
                                     double[] subjectXZ, boolean last) {
        return shootStill(label, subject, pose, settleTicks, subjectXZ, last, STOP_TIMEOUT_MS);
    }

    private static byte[] shootStill(String label, String subject,
                                     com.moddingfromamod.builder.BuildFraming.Pose pose, int settleTicks,
                                     double[] subjectXZ, boolean last, long timeoutMs) {
        String name = label + "__" + pose.name();
        Path out = photoPath(name);
        long before = lastModified(out);
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = eyes(server);
                if (player != null) {
                    // ⚠ CHECK THE LINE OF SIGHT — this is what the creature orbit has always
                    // done through moveCameraTo, and what buildings and trains never got
                    //. Without it the pose goes straight to the client and the child's
                    // wish card is whatever stands between the lens and the subject: measured
                    // on a real Gate C take as a side-on view of a train that was half oak
                    // canopy, correctly framed, correctly lit, with every counter clean.
                    //
                    // THE AIM'S HEIGHT IS RECOVERED FROM THE POSE ITSELF rather than guessed.
                    // The framing set pitch = -atan(aimY - eyeY, flat), so aimY is exactly
                    // eyeY - flat*tan(pitch) — the point this camera was already pointed at.
                    // Guessing instead (the anchor, the middle of the box) would aim the check
                    // somewhere the camera is not looking, and "correct" it away from a good
                    // shot.
                    double px = pose.x();
                    double py = pose.y();
                    double pz = pose.z();
                    float pyaw = pose.yaw();
                    float ppitch = pose.pitch();
                    if (!Double.isNaN(subjectXZ[0])) {
                        double eye = py + player.getEyeHeight();
                        double flat = Math.hypot(px - subjectXZ[0], pz - subjectXZ[1]);
                        double aimY = eye - flat * Math.tan(Math.toRadians(ppitch));
                        var seat = com.moddingfromamod.runtime.ClearView.find(
                                sight(player), px, py, pz, pyaw,
                                subjectXZ[0], aimY, subjectXZ[1], player.getEyeHeight());
                        px = seat.x();
                        py = seat.feetY();
                        pz = seat.z();
                        ppitch = seat.pitch();
                        if (seat.blind()) {
                            // SAY WHY rather than ship a clean-looking run of bad frames.
                            System.out.println("[photograph] " + name + ": no clear line to "
                                    + subject + " after a " + Math.round(seat.rose())
                                    + "-block climb — this frame may be of whatever is in "
                                    + "the way");
                        }
                    }
                    shutter(player, name, com.moddingfromamod.net.ModdedClient.photoAt(
                            name, settleTicks, !last,
                            px, py, pz, pyaw, ppitch, false));
                }
                return "ok";
            });
        } catch (AgentBridge.Unavailable e) {
            return null;
        }
        sleep(ORBIT_GAP_MS);
        // WAIT FOR THE SHOT BEFORE MOVING ON, the same rule the creature orbit learned: firing
        // every stop on a timer means the camera reaches stop N+1 while N's shutter is pending,
        // and the picture is of somewhere else.
        return awaitPhoto(out, before, timeoutMs);
    }

    // ──: THE SPIN — a hero still, then one turn filmed along the framer's own path ──────

    /** Whether a capture spins (2026-10-03). {@code -Dmoddingfromamod.camera.spin=off} is the A/B seam. */
    static final boolean SPIN = !"off".equalsIgnoreCase(
            System.getProperty(ModdingFromAModMod.MODID + ".camera.spin", "on"));
    /** One turn this long — a "~2 s 360". */
    static final double SPIN_SECONDS = 2.0;

    /**
     * Also film a turn standing INSIDE a building, in its best room (2026-11-09), off by default
     * until it has been reviewed: {@code -Dmoddingfromamod.camera.insideSpin=on}.
     */
    static final boolean INSIDE_SPIN = "on".equalsIgnoreCase(
            System.getProperty(ModdingFromAModMod.MODID + ".camera.insideSpin", "off"));

    /**
     * <b>A full turn on the spot, standing in {@code room}</b> — the framer's interior pose (its seat and
     * pitch), its bearing swept through 360°. Not seated by line of sight as an outside turn is: indoors a
     * ray that meets a wall would lift the camera through the roof, and the room pose is already one
     * the framer found standing room for. Each frame looks 4 blocks ahead along its own bearing.
     */
    static Spin insideTake(String label, com.moddingfromamod.builder.BuildFraming.Pose room) {
        if (!SPIN || room == null) {
            return null;
        }
        int steps = Math.max(8, Math.min(com.moddingfromamod.net.ModdedClient.MAX_SPIN_POSES,
                (int) Math.round(SPIN_SECONDS * 1.5 * 1000 / SPIN_GAP_MS)));
        java.util.List<com.moddingfromamod.runtime.OrbitClipPath.Pose> path =
                com.moddingfromamod.runtime.OrbitClipPath.turnInPlace(room.x(), room.y(), room.z(), room.yaw(),
                        room.pitch(), steps);
        double eye = room.y() + EYE_HEIGHT;
        double[][] aims = new double[steps][];
        for (int i = 0; i < steps; i++) {
            double yaw = Math.toRadians(path.get(i).yaw());
            double ahead = 4.0;
            aims[i] = new double[] {room.x() - Math.sin(yaw) * ahead,
                    eye - ahead * Math.tan(Math.toRadians(room.pitch())), room.z() + Math.cos(yaw) * ahead};
        }
        return filmPath(label + "__inside", path, true, room.x(), room.z(), eye, 0, aims, null,
                subjectBox(room.x(), eye, room.z()), SPIN_READY_MS, false);
    }
    /** Played at this gap: 20 poses for 2 s, 18° a frame, smooth enough to read as a turn. */
    static final int SPIN_GAP_MS = 100;
    /** Wide enough that a quarter-turn frame is a readable side for the agent's sheet. */
    static final int SPIN_WIDTH = 480;
    /** The animation filed for the operator's tools and the account page: the old clip's width, ~1 MB a turn. */
    static final int FILED_WIDTH = 320;
    /** The first pose waits for chunks like a still (PhotoClient.MIN_FRAMES); the rest are a step away. */
    static final int SPIN_SETTLE_FRAMES = 12;
    /** Line-of-sight raises are held this many frames either side (OrbitClipPath.smoothFeet). */
    static final int SPIN_HOLD_FRAMES = 2;

    /**
     * A creature's turn: the four orbit poses {@code OrbitCamera} chose for it (its locked bearings,
     * its portrait distance and height), threaded into a ring round where it stands now.
     */
    static Spin spinCreature(String label, String subject, double height, double[] bearings) {
        return spinCreature(label, subject, height, 1.0, bearings);
    }

    /** The same, standing {@code widthScale} times further back for a creature wider than it is tall (F1). */
    static Spin spinCreature(String label, String subject, double height, double widthScale, double[] bearings) {
        java.util.List<com.moddingfromamod.runtime.OrbitClipPath.Pose> keys = new java.util.ArrayList<>();
        final double[] at = new double[3];
        try {
            String found = AgentBridge.onServer(() -> {
                ServerPlayer player = eyes(AgentBridge.serverOrNull());
                var mob = player == null ? null : findSubject(player, subject);
                if (mob == null) {
                    return "gone";
                }
                double eyeH = player.getEyeHeight();
                for (double b : bearings) {
                    var shot = com.moddingfromamod.runtime.OrbitCamera.at(mob.getX(), mob.getY(), mob.getZ(),
                            height, b, widthScale);
                    keys.add(new com.moddingfromamod.runtime.OrbitClipPath.Pose(shot.x(), shot.eyeY() - eyeH,
                            shot.z(), shot.yaw(), shot.pitch()));
                }
                at[0] = mob.getX();
                at[1] = mob.getY() + height * 0.55;
                at[2] = mob.getZ();
                return "ok";
            });
            if (!"ok".equals(found)) {
                return null;
            }
        } catch (AgentBridge.Unavailable e) {
            return null;
        }
        return spinTake(label, keys, true, at[0], at[2], at[1], SPIN_SECONDS);
    }

    /** An event clip lasts this long: long enough for a wave to arrive or a meteor to land. */
    static final double EVENT_SECONDS = 6.0;
    /** Its frames are this far apart in real time (and in playback): 48 frames for 6 s. */
    static final int EVENT_GAP_MS = 125;
    /** An event this recent is the one a subject-less photograph means. */
    static final long EVENT_RECENT_MS = 120_000;

    /**
     * <b>Film the event that just started</b> — {@code EventFraming}'s held frame on its centre,
     * from the side the child stands on, {@link #EVENT_SECONDS} of it in real time. Null when no
     * event knows where it is happening (the plain shot then answers, as before).
     */
    /** The last event clip, by event id — stage_event films it as it starts; the photograph returns it. */
    private static final java.util.Map<String, ToolResult> EVENT_FILMS = new java.util.concurrent.ConcurrentHashMap<>();
    /** The core of an event, as a fraction of how far it MAY reach (EventScale.radius): a meteor's
     *  crater is ~6 blocks of a MEDIUM's 32, and framing the whole reach made it a speck. */
    static final double EVENT_CORE = 0.5;
    /**
     * Roughly from a take being sent to its first frame — used only to guess where an event that was
     * NOT held for the camera will be by the first frame. A held event starts on the camera's own word
     * (SpinReadyPayload).
     */
    static final int EVENT_SETTLE_MS = com.moddingfromamod.runtime.PacedTake.LEAD_MS;
    /** The most an event's first tick waits for its camera, whatever happens to the take. */
    public static final int EVENT_CUE_MAX_MS = 5000;     // the chosen ceiling (2026-10-03): up to 5 s for the camera

    /**
     * Is a camera here that {@link #filmEventNow} would film through? A modded, non-child body that
     * can play a take. No side effects: asked BEFORE staging, to hold the event's start for it.
     * Server thread.
     */
    public static boolean canFilmEvents(net.minecraft.server.MinecraftServer server) {
        if (server == null || !SPIN) {
            return false;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID())
                    && com.moddingfromamod.net.ModdedClient.can(p, com.moddingfromamod.net.ModdedClient.Channel.SPIN)) {
                return true;
            }
        }
        return false;
    }

    /** Film the event that has just started, now (stage_event). Null when none is filmable. */
    public static ToolResult filmEventNow() {
        // Only through a CAMERA that can play the take, and only holding its lease. The child's own
        // eyes are not borrowed here: stage_event has no wish ritual round it, and "can this child
        // play a take" cannot be told from a GameTest's mock (it answers every channel) — measured,
        // the mock got a 55 s wait and was left where the framing stood. With no camera the wish's
        // photograph films the event later, inside the ritual, as it always did.
        final com.moddingfromamod.wish.Viewpoint[] view = {null};
        // A camera BUSY with another wish's take is waited for inside the event's own hold (it starts
        // at EVENT_CUE_MAX_MS whatever happens): giving up at once let the event play to nobody.
        long until = System.currentTimeMillis() + EVENT_CUE_MAX_MS - 500;
        String ok = "busy";
        while ("busy".equals(ok)) {
            try {
                ok = AgentBridge.onServer(() -> {
                    var v = com.moddingfromamod.wish.Viewpoint.acquire(AgentBridge.serverOrNull());
                    if (v == null || !v.isCamera() || !com.moddingfromamod.net.ModdedClient.can(v.player(), com.moddingfromamod.net.ModdedClient.Channel.SPIN)) {
                        return "noeyes";
                    }
                    if (v.take() != com.moddingfromamod.wish.WishCapture.Lease.TAKEN) {
                        return "busy";
                    }
                    view[0] = v;
                    return "ok";
                });
            } catch (AgentBridge.Unavailable e) {
                return null;
            }
            if ("busy".equals(ok)) {
                if (System.currentTimeMillis() >= until) {
                    return null;
                }
                sleep(CAMERA_RETRY_MS);
            }
        }
        if (!"ok".equals(ok)) {
            return null;
        }
        try {
            // the ground may be waited for only while the event's start is still held for this take
            return captureEvent("event_now", null, (int) Math.max(0, until + 500 - System.currentTimeMillis() - 250));
        } finally {
            try {
                AgentBridge.onServer(() -> {
                    view[0].release();
                    return "ok";
                });
            } catch (AgentBridge.Unavailable ignored) {
                // the lease times out by itself
            }
        }
    }

    /**
     * {@link #captureEvent} holding the camera's LEASE, waited for as every other capture waits.
     * Without it, two event wishes finishing together drove one camera at once (measured on a
     * bench server: two takes 0.2 s apart, each superseding the other, both waiting 55 s for a strip
     * that never came — and holding the camera that long from the item, the picture and the pet
     * that asked in between). Null when no camera came (the caller falls back to stills).
     */
    static ToolResult captureEventLeased(String label, String intro) {
        final com.moddingfromamod.wish.Viewpoint[] view = {null};
        String found = "busy";
        long waitStarted = System.currentTimeMillis();
        for (int attempt = 0; attempt < CAMERA_ATTEMPTS; attempt++) {
            try {
                found = AgentBridge.onServer(() -> {
                    var server = AgentBridge.serverOrNull();
                    var e = server == null ? null : com.moddingfromamod.event.EventDirector.latestFocused(server,
                            ownerName(server), EVENT_RECENT_MS);
                    if (e == null) {
                        return "none";
                    }
                    var v = com.moddingfromamod.wish.Viewpoint.acquire(server);
                    if (v == null) {
                        return "nobody";
                    }
                    String problem = leaseProblem(v);
                    if (problem == null) {
                        view[0] = v;
                        return "ok";
                    }
                    return problem;
                });
            } catch (AgentBridge.Unavailable e) {
                found = "unavailable: " + e.getMessage();
            }
            if ("ok".equals(found) || !worthWaitingFor(found)) {
                break;
            }
            sleep(CAMERA_RETRY_MS);
        }
        if (!"ok".equals(found)) {
            if (worthWaitingFor(found)) {
                com.moddingfromamod.health.Counters.waited("camera", System.currentTimeMillis() - waitStarted);
                releaseQueuePlace();
            }
            return null;
        }
        try {
            return captureEvent(label, intro, EVENT_LATE_READY_MS);
        } finally {
            try {
                AgentBridge.onServer(() -> {
                    view[0].release();
                    return "ok";
                });
            } catch (AgentBridge.Unavailable ignored) {
                // the lease times out by itself
            }
        }
    }

    /** The name this wish's events are staged under (StagedEvent.owner): its child's. Server thread. */
    private static String ownerName(net.minecraft.server.MinecraftServer server) {
        ServerPlayer child = com.moddingfromamod.agent.ActingChild.person(server);
        return child == null ? null : child.getGameProfile().getName();
    }

    /** An event filmed after it started: its ground is waited for briefly, the event already moving. */
    static final int EVENT_LATE_READY_MS = 2000;

    static ToolResult captureEvent(String label, String intro, int readyMs) {
        int n = (int) Math.round(EVENT_SECONDS * 1000 / EVENT_GAP_MS);
        final java.util.List<com.moddingfromamod.runtime.OrbitClipPath.Pose> path = new java.util.ArrayList<>(n);
        final double[][] aims = new double[n][];
        final java.util.List<com.moddingfromamod.runtime.EventFraming.Shot> shots = new java.util.ArrayList<>(n);
        final String[] what = {""};
        final String[] eventId = {null};
        final com.moddingfromamod.event.StagedEvent[] staged = {null};
        final int[][] readyBox = {null};
        try {
            String found = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                // THIS wish's event: with two event wishes at once, "the latest by anybody" filmed
                // the same one twice (bench server, 2026-10-03)
                var e = server == null ? null
                        : com.moddingfromamod.event.EventDirector.latestFocused(server, ownerName(server),
                                EVENT_RECENT_MS);
                if (e == null) {
                    return "none";
                }
                ServerPlayer child = com.moddingfromamod.agent.ActingChild.person(server);
                double vx = child == null ? Double.NaN : child.getX();
                double vz = child == null ? Double.NaN : child.getZ();
                var at = e.focus();
                var reach = e.planned();
                // Frame 0 is at the event's tick 0 when its start is held for this take; otherwise
                // it has been running, and the first frame lands about a settle later.
                double tick0 = e.held(System.currentTimeMillis()) ? 0 : e.age() + EVENT_SETTLE_MS / 50.0;
                // what frame 0 looks at must be DRAWN first: where the action is at moment 0
                var first = reach == null ? com.moddingfromamod.runtime.EventReach.around(at.getX() + 0.5,
                        at.getY(), at.getZ() + 0.5, e.scale().radius * EVENT_CORE, e.scale().radius * EVENT_CORE)
                        : reach.frontAt(tick0);
                readyBox[0] = new int[] {(int) Math.floor(first.x0()), (int) Math.floor(first.y0()),
                        (int) Math.floor(first.z0()), (int) Math.floor(first.x1()), (int) Math.floor(first.y1()),
                        (int) Math.floor(first.z1())};
                for (int i = 0; i < n; i++) {
                    com.moddingfromamod.runtime.EventFraming.Shot shot;
                    if (reach == null) {
                        shot = com.moddingfromamod.runtime.EventFraming.frame(at.getX() + 0.5, at.getY(),
                                at.getZ() + 0.5, e.scale().radius * EVENT_CORE, vx, vz);
                    } else if (reach.tracked()) {
                        // a front that travels is TRACKED: one side-on framing per moment
                        shot = com.moddingfromamod.runtime.EventFraming.frame(
                                reach.frontAt(tick0 + i * EVENT_GAP_MS / 50.0), vx, vz);
                    } else {
                        shot = com.moddingfromamod.runtime.EventFraming.frame(reach, vx, vz);
                    }
                    shots.add(shot);
                    path.add(new com.moddingfromamod.runtime.OrbitClipPath.Pose(shot.x(), shot.feetY(),
                            shot.z(), shot.yaw(), shot.pitch()));
                    // the aim is on the bearing line, aimY high, behind the pose by the framed distance
                    double rad = Math.toRadians(shot.yaw());
                    double flat = (shot.feetY() + EYE_HEIGHT - shot.aimY())
                            / Math.tan(Math.toRadians(Math.max(1, shot.pitch())));
                    aims[i] = new double[] {shot.x() - flat * Math.sin(rad), shot.aimY(),
                            shot.z() + flat * Math.cos(rad)};
                }
                what[0] = e.id() + " at " + at.toShortString() + ", radius " + e.scale().radius
                        + (reach == null ? "" : reach.tracked() ? ", tracked along its way"
                        : ", framed on where it goes");
                eventId[0] = e.id();
                staged[0] = e;
                return "ok";
            });
            if (!"ok".equals(found)) {
                return null;
            }
        } catch (AgentBridge.Unavailable e) {
            return null;
        }
        ToolResult filmed = eventId[0] == null ? null : EVENT_FILMS.get(eventId[0]);
        if (filmed != null) {
            return filmed;           // filmed as it started (stage_event): that is the clip to show
        }
        Runnable sent = () -> staged[0].releaseAt(System.currentTimeMillis());
        Spin film = filmPath(label + "__event", path, false, aims[0][0], aims[0][2], aims[0][1], EVENT_GAP_MS,
                aims, sent, readyBox[0], readyMs);
        if (film == null) {
            return null;
        }
        // the agent sees it HAPPEN: four moments, first to last
        java.util.List<ContactSheet.Frame> sheet = new java.util.ArrayList<>();
        int m = film.frames().size();
        for (double t : new double[] {0, 1.0 / 3, 2.0 / 3, 1}) {
            int i = Math.min(m - 1, (int) Math.round(t * (m - 1)));
            byte[] png = com.moddingfromamod.runtime.SpinFilm.png(film.frames().get(i));
            if (png != null) {
                sheet.add(new ContactSheet.Frame(png, (int) Math.round(t * EVENT_SECONDS * 1000)));
            }
        }
        byte[] composed = ContactSheet.compose(sheet);
        if (composed == null) {
            return null;
        }
        String lead = intro != null ? intro
                : "Here is the event happening, as he sees it from where he stands — four moments of "
                + Math.round(EVENT_SECONDS) + " seconds, first to last (" + what[0] + "). Look at it "
                + "honestly: can he see it, and is it what he asked for?";
        String missed = outOfShot(staged[0], shots);
        if (missed != null) {
            lead = lead + " " + missed;
        }
        ToolResult result = ToolResult.image(lead, Base64.getEncoder().encodeToString(composed), "image/png");
        if (eventId[0] != null) {
            if (EVENT_FILMS.size() > 32) {
                EVENT_FILMS.clear();      // a few minutes' worth is all anyone asks for again
            }
            EVENT_FILMS.put(eventId[0], result);
        }
        return result;
    }

    /**
     * Did the take SEE what the event did? Asked of the blocks it really wrote (the director's box),
     * against every frame's shot — not of the pixels, which cannot tell grass from a few small
     * chickens. Null when it was seen, or when it wrote nothing to check (a shower of creatures).
     */
    static String outOfShot(com.moddingfromamod.event.StagedEvent e,
                            java.util.List<com.moddingfromamod.runtime.EventFraming.Shot> shots) {
        if (e == null || shots.isEmpty()) {
            return null;
        }
        try {
            return AgentBridge.onServer(() -> {
                int[] b = e.written();
                var r = e.planned();
                var s0 = shots.get(0);
                LOGGER.info("event film: {} age {} reach {} wrote {} first shot ({}, {}, {}) yaw {} pitch {}",
                        e.id(), e.age(), r == null ? "none" : String.format("[%.0f,%.0f,%.0f → %.0f,%.0f,%.0f]%s",
                                r.x0(), r.y0(), r.z0(), r.x1(), r.y1(), r.z1(), r.tracked() ? " tracked" : ""),
                        b == null ? "nothing" : java.util.Arrays.toString(b),
                        Math.round(s0.x()), Math.round(s0.feetY()), Math.round(s0.z()),
                        Math.round(s0.yaw()), Math.round(s0.pitch()));
                if (b == null) {
                    return null;
                }
                double mx = (b[0] + b[3]) / 2.0 + 0.5, my = (b[1] + b[4]) / 2.0 + 0.5, mz = (b[2] + b[5]) / 2.0 + 0.5;
                for (var shot : shots) {
                    if (com.moddingfromamod.runtime.EventFraming.inFrame(shot, mx, my, mz)) {
                        return null;
                    }
                }
                LOGGER.warn("event film: {} happened round ({}, {}, {}), outside every frame", e.id(),
                        Math.round(mx), Math.round(my), Math.round(mz));
                return "⚠ The camera MISSED most of it: what it did is centred round (" + Math.round(mx) + ", "
                        + Math.round(my) + ", " + Math.round(mz) + "), outside every frame — so these pictures "
                        + "do not show it, and an empty one is not proof nothing happened.";
            });
        } catch (AgentBridge.Unavailable ex) {
            return null;
        }
    }

    /** What each body could do in the air before a take let it fly (filmPath), to put it back. */
    private static final java.util.Map<java.util.UUID, boolean[]> flightBefore =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Put every body a take let fly back as it was. Off the server thread; runs there. */
    private static void giveFlightBack() {
        if (flightBefore.isEmpty()) {
            return;
        }
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                for (var it = flightBefore.entrySet().iterator(); it.hasNext(); ) {
                    var e = it.next();
                    it.remove();
                    ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(e.getKey());
                    // only a CHILD gets his old flight back: a camera keeps flying for good
                    // (CameraPresence.keepCamerasSafe) — giving it back left one 140 blocks up, and it
                    // "fell from a high place" and filmed its own death screen for every wish after
                    if (p != null && !p.isCreative() && !p.isSpectator()
                            && com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID())) {
                        p.getAbilities().mayfly = e.getValue()[0];
                        p.getAbilities().flying = e.getValue()[1];
                        p.onUpdateAbilities();
                    }
                }
                return "ok";
            });
        } catch (AgentBridge.Unavailable ignored) {
            // the next take puts it back; a body left able to fly is harmless, a kicked one is not
        }
    }

    /** Releases waiting on a camera's first frame, by take name (SpinReadyPayload). */
    private static final java.util.Map<String, Runnable> READY = new java.util.concurrent.ConcurrentHashMap<>();

    /** A camera took the first frame of take {@code label}: let its held event go. Server thread. */
    public static void spinReady(ServerPlayer from, String label) {
        // Anyone's word is taken, including a child's client whose eyes ARE the camera (no camera
        // serving): all it can do is start an event that is already held for a take, a little early.
        if (from == null || label == null) {
            return;
        }
        Runnable r = READY.remove(label);
        if (r != null) {
            r.run();
            LOGGER.info("spin: '{}' first frame taken — its event goes now", label);
        }
    }

    /** A filmed turn: its frames (strip order) and where its animated PNG was filed. */
    record Spin(java.util.List<java.awt.image.BufferedImage> frames, Path apng, long ms) { }

    /**
     * <b>Film one turn along {@code keys}</b> — the framer's own poses, FEET convention (what
     * {@code PhotoPayload.at} pins), aimed at ({@code cx}, {@code aimY}, {@code cz}).
     *
     * <p>Every frame gets the stills' line-of-sight check ({@code ClearView}) BEFORE the take, so the
     * raises can be smoothed across neighbours ({@code OrbitClipPath.smoothFeet}) — a camera that
     * climbed for one tree and dropped back the next frame is a jolt, not a view. The client then
     * pins pose k for frame k ({@code SpinClient}), so the turn is complete whatever its frame rate.
     *
     * @return the take, or null when no strip came back — the caller falls back to stills
     */
    static Spin spinTake(String label, java.util.List<com.moddingfromamod.runtime.OrbitClipPath.Pose> keys,
                         boolean closed, double cx, double cz, double aimY, double seconds) {
        if (!SPIN || keys == null || keys.size() < 2) {
            return null;
        }
        int steps = Math.max(8, Math.min(com.moddingfromamod.net.ModdedClient.MAX_SPIN_POSES,
                (int) Math.round(seconds * 1000 / SPIN_GAP_MS)));
        var path = closed
                ? com.moddingfromamod.runtime.OrbitClipPath.ring(keys, cx, cz, steps)
                : com.moddingfromamod.runtime.OrbitClipPath.arc(keys, cx, cz, steps);
        return filmPath(label + "__spin", path, closed, cx, cz, aimY, 0);
    }

    /**
     * Film {@code path} — one frame per pose, each seated by line of sight and the raises smoothed —
     * and file it as an animated PNG. {@code paceMs} 0 for a turn of a held subject; the playback
     * gap for an event, whose frames are moments.
     */
    static Spin filmPath(String name, java.util.List<com.moddingfromamod.runtime.OrbitClipPath.Pose> path,
                         boolean closed, double cx, double cz, double aimY, int paceMs) {
        return filmPath(name, path, closed, cx, cz, aimY, paceMs, null, null);
    }

    /** The same, its first frame waiting for the subject's own ground ({@link #subjectBox}). */
    static Spin filmPath(String baseName, java.util.List<com.moddingfromamod.runtime.OrbitClipPath.Pose> path,
                         boolean closed, double cx, double cz, double aimY, int paceMs,
                         double[][] aims, Runnable onSent) {
        return filmPath(baseName, path, closed, cx, cz, aimY, paceMs, aims, onSent, subjectBox(cx, aimY, cz),
                SPIN_READY_MS);
    }

    /**
     * How long a take's first frame waits for its box to be DRAWN (GroundReady). A camera that has
     * been nearby answers at once; one that has just joined or crossed the map is still meshing the
     * world — the bench volcano's island filled in across its whole clip.
     */
    static final int SPIN_READY_MS = 4000;
    /** Round a turn's subject: what a hero and a spin look at. */
    static final int SUBJECT_READY_XZ = 12;
    static final int SUBJECT_READY_Y = 10;

    static int[] subjectBox(double cx, double aimY, double cz) {
        int x = (int) Math.floor(cx), y = (int) Math.floor(aimY), z = (int) Math.floor(cz);
        return new int[] {x - SUBJECT_READY_XZ, y - SUBJECT_READY_Y, z - SUBJECT_READY_XZ,
                x + SUBJECT_READY_XZ, y + SUBJECT_READY_Y, z + SUBJECT_READY_XZ};
    }

    /**
     * The same, with an aim per pose ({@code aims[i]} = x, y, z; null for the one aim) — a TRACKED
     * shot looks at where the subject is at that frame — and {@code sent}, run on the server thread
     * once the take is on its way (an event's held start is released by it).
     */
    static Spin filmPath(String baseName, java.util.List<com.moddingfromamod.runtime.OrbitClipPath.Pose> path,
                         boolean closed, double cx, double cz, double aimY, int paceMs,
                         double[][] aims, Runnable onSent, int[] readyBox, int readyMs) {
        return filmPath(baseName, path, closed, cx, cz, aimY, paceMs, aims, onSent, readyBox, readyMs, true);
    }

    /** …and {@code seat} false: every pose as given, no line-of-sight climb (a turn on the spot indoors). */
    static Spin filmPath(String baseName, java.util.List<com.moddingfromamod.runtime.OrbitClipPath.Pose> path,
                         boolean closed, double cx, double cz, double aimY, int paceMs,
                         double[][] aims, Runnable onSent, int[] readyBox, int readyMs, boolean seat) {
        int steps = path.size();
        java.util.UUID wish = currentWishId();
        // ONE TAKE, ONE NAME: two wishes' takes were both "finish_building__event", so each found
        // the other's strip (or neither did). The wish's short id makes every take its own.
        final String name = wish == null ? baseName : baseName + "_" + wish.toString().substring(0, 8);
        String stem = sanitizeStem(name);
        long started = System.currentTimeMillis();
        String sent;
        try {
            sent = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = eyes(server);
                if (player == null) {
                    return "nobody";
                }
                // A GameTest's mock answers yes to EVERY channel (MockPlayerNetwork), so ask the seam too: a mock has
                // no client to play a take, and waiting for its strip cost a 36 s timeout per make (release smoke,
                // 2026-11-23, ApprenticeGameTest.fromTheWordsToTheThingNoModelAndNoWebCall).
                if (com.moddingfromamod.seam.MockPlayers.is(player)
                        || !com.moddingfromamod.net.ModdedClient.can(player, com.moddingfromamod.net.ModdedClient.Channel.SPIN)) {
                    return "noclient";   // a stock client (or a GameTest's mock) cannot play a take: stills
                }
                double eyeH = player.getEyeHeight();
                int n = path.size();
                double[] asked = new double[n];
                double[] seated = new double[n];
                double[] xs = new double[n];
                double[] zs = new double[n];
                int blind = 0;
                var sight = sight(player);
                for (int i = 0; i < n; i++) {
                    var p = path.get(i);
                    double ax = aims == null ? cx : aims[i][0];
                    double ay = aims == null ? aimY : aims[i][1];
                    double az = aims == null ? cz : aims[i][2];
                    asked[i] = p.y();
                    if (!seat) {
                        seated[i] = p.y();
                        xs[i] = p.x();
                        zs[i] = p.z();
                        continue;
                    }
                    var view = com.moddingfromamod.runtime.ClearView.find(sight, p.x(), p.y(), p.z(),
                            (float) p.yaw(), ax, ay, az, eyeH);
                    if (view.blind()) {
                        // A CLIMB THAT ENDS BLIND BOUGHT NOTHING — keep the framer's own pose. Measured
                        // on the secret treehouse: between the stills' bearings its own floating
                        // canopy cuts the ray more than 3 blocks from the aim, ClearView climbed its
                        // whole allowance, still saw nothing, and the turn jumped to a bird's-eye view
                        // for five frames. A still at that bearing would have been the same bad view;
                        // a clip shows the jump. A raise that FOUND a clear line is kept (smoothed).
                        blind++;
                        seated[i] = p.y();
                        xs[i] = p.x();
                        zs[i] = p.z();
                    } else {
                        seated[i] = view.feetY();
                        xs[i] = view.x();
                        zs[i] = view.z();
                    }
                }
                double[] feet = com.moddingfromamod.runtime.OrbitClipPath.smoothFeet(asked, seated,
                        SPIN_HOLD_FRAMES, closed);
                java.util.List<Double> flat = new java.util.ArrayList<>(n * 5);
                for (int i = 0; i < n; i++) {
                    double eye = feet[i] + eyeH;
                    double ax = aims == null ? cx : aims[i][0];
                    double ay = aims == null ? aimY : aims[i][1];
                    double az = aims == null ? cz : aims[i][2];
                    double run = Math.hypot(xs[i] - ax, zs[i] - az);
                    double pitch = Math.toDegrees(-Math.atan2(ay - eye, Math.max(0.01, run)));
                    flat.add(xs[i]);
                    flat.add(feet[i]);
                    flat.add(zs[i]);
                    flat.add(path.get(i).yaw());
                    flat.add(pitch);
                }
                // The body goes to the first pose so the server streams that ground (and the
                // freeze is told where it now belongs —); the client pins every pose itself.
                // ⚠ ALLOWED TO FLY for the take (put back after it): the client moves its own body
                // through the path, and on a dedicated server a body that may not fly is kicked
                // "for floating too long" after 4 s in the air — measured on a bench server, a
                // VM camera was kicked as its 20th frame landed and every next wish paid a rejoin.
                // A child whose own eyes film an event (no camera) would be kicked the same way.
                // Not spectator: a spectator sees invisible entities, and every creature rides one.
                flightBefore.put(player.getUUID(), new boolean[] {player.getAbilities().mayfly,
                        player.getAbilities().flying});
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.setNoGravity(true);
                player.setDeltaMovement(0, 0, 0);
                player.fallDistance = 0;
                player.connection.teleport(flat.get(0), flat.get(1), flat.get(2),
                        flat.get(3).floatValue(), flat.get(4).floatValue());
                com.moddingfromamod.wish.WishCapture.aimedAt(player.getUUID(), flat.get(0), flat.get(1),
                        flat.get(2));
                java.util.List<Integer> ready = new java.util.ArrayList<>(6);
                if (readyBox != null && readyBox.length == 6 && readyMs > 0) {
                    for (int v : readyBox) {
                        ready.add(v);
                    }
                }
                shutter(player, com.moddingfromamod.net.ModdedClient.spin(name, flat, SPIN_SETTLE_FRAMES,
                        paceMs > 0 ? paceMs : SPIN_GAP_MS, SPIN_WIDTH, paceMs, Math.max(0, readyMs), ready));
                if (onSent != null) {
                    READY.put(name, onSent);     // run when the camera's first frame is taken
                }
                LOGGER.info("spin: {} poses round ({}, {}, {}) {}, {} blind after the climb", n,
                        Math.round(cx), Math.round(aimY), Math.round(cz), closed ? "a full turn" : "an arc",
                        blind);
                return "ok";
            });
        } catch (AgentBridge.Unavailable e) {
            return null;
        }
        if (!"ok".equals(sent)) {
            return null;
        }
        // Wait for the strip, keeping the lease alive (a take is longer than the idle exit).
        long deadline = started + steps * (600L + paceMs) + Math.max(0, readyMs) + 20_000L;
        Path dir = RuntimeConfig.home().resolve("photos");
        Path strip = null;
        long lastClaim = 0;
        while (System.currentTimeMillis() < deadline) {
            strip = newestStrip(dir, stem, started);
            if (strip != null) {
                break;
            }
            if (System.currentTimeMillis() - lastClaim > 1000) {
                lastClaim = System.currentTimeMillis();
                try {
                    AgentBridge.onServer(() -> {
                        ServerPlayer player = eyes(AgentBridge.serverOrNull());
                        if (player != null && wish != null) {
                            com.moddingfromamod.wish.WishCapture.claimCameraFor(player.getUUID(), wish);
                        }
                        return "ok";
                    });
                } catch (AgentBridge.Unavailable ignored) {
                    // the take still lands; only the lease refresh was missed
                }
            }
            sleep(POLL_MS);
        }
        READY.remove(name);          // a take that never said "ready": its hold's own cap let it go
        giveFlightBack();
        if (strip == null) {
            LOGGER.warn("spin: no strip for {} after {} ms — falling back to stills", name,
                    System.currentTimeMillis() - started);
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(strip);
            var m = java.util.regex.Pattern.compile("__clip(\\d+)_(\\d+)\\.png$")
                    .matcher(strip.getFileName().toString());
            int n = m.find() ? Integer.parseInt(m.group(1)) : 0;
            var frames = com.moddingfromamod.runtime.SpinFilm.frames(bytes, n);
            if (frames.size() < 2) {
                return null;
            }
            // the FILED animation is the small copy (a phone loads it on the account page); the
            // agent's sheet keeps the full frames
            java.util.List<java.awt.image.BufferedImage> small = new java.util.ArrayList<>();
            for (var fr : frames) {
                small.add(com.moddingfromamod.runtime.SpinFilm.scaled(fr, FILED_WIDTH));
            }
            int playGap = m.groupCount() >= 2 && n > 0 ? Integer.parseInt(m.group(2)) : SPIN_GAP_MS;
            byte[] apng = com.moddingfromamod.runtime.SpinFilm.apng(small, playGap);
            Path out = dir.resolve(stem + ".png");
            if (apng != null) {
                Files.write(out, apng);
                com.moddingfromamod.wish.WishShots.keep(out);   // filed as a photograph: it is one
            }
            long ms = System.currentTimeMillis() - started;
            LOGGER.info("spin: {} — {} of {} frames in {} ms", name, frames.size(), steps, ms);
            return new Spin(frames, apng == null ? null : out, ms);
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("spin: {} could not be read: {}", name, String.valueOf(e));
            return null;
        }
    }

    /** Quarter-turn frames of a take as sheet frames, for the agent's four sides. */
    static void addSpinSides(java.util.List<ContactSheet.Frame> sheet, Spin spin, double startBearing,
                             boolean closed) {
        int n = spin.frames().size();
        double[] at = closed ? new double[] {0.25, 0.5, 0.75} : new double[] {0.5, 0.999};
        for (double t : at) {
            int i = closed ? com.moddingfromamod.runtime.SpinFilm.at(t, n) : Math.min(n - 1, (int) Math.round(t * (n - 1)));
            byte[] png = com.moddingfromamod.runtime.SpinFilm.png(spin.frames().get(i));
            if (png != null) {
                sheet.add(new ContactSheet.Frame(png, (int) Math.round(startBearing + (closed ? t * 360 : 0))));
            }
        }
    }

    private static Path newestStrip(Path dir, String stem, long after) {
        try (var s = Files.list(dir)) {
            return s.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith(stem + "__clip") && n.endsWith(".png") && lastModified(f) >= after - 1000;
            }).max(java.util.Comparator.comparingLong(PhotographTool::lastModified)).orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** The client's file-name rule (ClipStrip.sanitize), here because the server may not load client classes. */
    static String sanitizeStem(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private static java.util.UUID currentWishId() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? null : run.wishId();
    }

    static ToolResult captureOrbit(String label, String subject, String intro) {
        return captureOrbit(label, subject, intro, false);
    }

    /**
     * @param quiet suppress the creature's own particle effects for these frames.
     *
     *     <p>Ported from the eval harness, which was the only thing that could photograph a
     *     creature without its own fire in the way. Off by default and deliberately so: a child's
     *     Spikor should have his flames in the picture. The harness shoots BOTH — live, so an
     *     effect can be checked at all, and quiet, so form and colour can be judged.
     */
    static ToolResult captureOrbit(String label, String subject, String intro, boolean quiet) {
        final StringBuilder framing = new StringBuilder();
        final double[] shape = new double[5];       // x, feetY, z, heightBlocks, how much further back its width needs
        // The creature is held still for the stills and its facing is read ONCE (SubjectHold,
        // OrbitCamera.lockedBearings): a live mob that walks and turns between stops made four
        // "sides" out of one side twice and an empty field.
        final SubjectHold[] hold = {null};
        final double[][] bearings = {null};
        try {
            String found = "busy";
            long orbitWaitStarted = System.currentTimeMillis();
            // A BUSY CAMERA IS WAITED FOR, as the building, picture and event paths wait: this
            // returned "busy" at once and a pet sculpted beside two event films got one plain
            // snapshot instead of its hero and its turn (bench server, 2026-10-03). The lease is
            // asked before anything is touched below, so a retry repeats nothing.
            for (int attempt = 0; attempt < CAMERA_ATTEMPTS; attempt++) {
            found = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = eyes(server);
                if (player == null) {
                    return "nobody";
                }
                var best = findSubject(player, subject);
                if (best == null) {
                    return "missing";
                }
                // Enter capture mode BEFORE moving anything, so the child's real spot is recorded.
                String noCamera = leaseProblem(player);
                if (noCamera != null) {
                    return noCamera;
                }
                double h = subjectHeightBlocks(subject, best);
                // LOG THE FRAMING INPUT, not just where the camera went. Every framing question so
                // far has been answered by running the orbit again and looking at the pictures,
                // because the height is only ever REPORTED to the agent and the camera line below
                // prints the consequence rather than the cause: a camera 29 blocks back and a
                // camera 8 blocks back are the same log line. This one line is what turned "the
                // pictures look far away" into "scale 12.25, subject minecraft:bee" in one run.
                LOGGER.info("orbit: framing '{}' -> {} blocks tall (entity scale {}, carrier {})",
                        subject, Math.round(h * 10) / 10.0, best.getScale(),
                        best.getType().builtInRegistryHolder().key().location());
                shape[0] = best.getX();
                shape[1] = best.getY();
                shape[2] = best.getZ();
                shape[3] = h;
                shape[4] = com.moddingfromamod.runtime.Framing.widthScale(h, subjectSpanBlocks(subject, best));
                hold[0] = SubjectHold.freeze(best);
                // yBodyRot, not getYRot: the RENDERER draws the body from yBodyRot, and a mob's
                // entity yaw can differ from the body it shows.
                bearings[0] = com.moddingfromamod.runtime.OrbitCamera.lockedBearings(best.yBodyRot);
                // FRAME HONESTY, from the harness. A photograph with three creatures in it is a
                // photograph of a crowd, and the agent judging "does this look right" has no way
                // to know it was not looking at the one it asked for. Reported, never silently
                // cropped -- a bad photo that says so is worth far more than a bad photo.
                long onStage = 0;
                for (var e : player.level().getEntities().getAll()) {
                    // BOTH renderers, or this warning goes quiet exactly when it is needed: it
                    // counted WishMobEntity only, so once the rig-on-a-vanilla-base became the
                    // default a contact sheet with three creatures in it reported one
                    //. A frame-honesty check that cannot see the frame is worse than none.
                    String sp = e instanceof com.moddingfromamod.entity.WishMobEntity w
                            ? w.speciesId() : null;
                    if (com.moddingfromamod.runtime.CaptureSubject.isAnyWishCreature(sp, e.getTags())
                            && e.distanceToSqr(best) < 400) {
                        onStage++;
                    }
                }
                if (onStage > 1) {
                    framing.append("(WARNING: ").append(onStage)
                            .append(" creatures are close enough to be in this shot, so it may not "
                                    + "be a picture of just '").append(subject).append("'.) ");
                }
                framing.append("(Orbited '").append(subject).append("', ")
                        .append(Math.round(h)).append(" blocks tall, from ")
                        .append(com.moddingfromamod.runtime.OrbitCamera.STOPS)
                        .append(" sides — the creature was held still for the four shots but not moved ")
                        .append("or turned, so these are the angles the child would actually see.)");
                return "ok";
            });
                if (!"busy".equals(found)) {
                    if ("ok".equals(found) && attempt > 0) {
                        com.moddingfromamod.health.Counters.waited("camera",
                                System.currentTimeMillis() - orbitWaitStarted);
                    }
                    break;
                }
                sleep(CAMERA_RETRY_MS);
            }
            if ("nobody".equals(found)) {
                return ToolResult.noPlayerOnline("Nobody is in the world, so there's nothing to photograph.");
            }
            if (!"ok".equals(found)) {
                // "missing" (no such creature) or "busy" (another wish holds the camera). Either
                // way the caller falls back to one plain photograph, which takes the same lease and
                // reports the same refusal — so a busy camera is never silently turned into a
                // successful-looking no-op.
                LOGGER.warn("orbit skipped for '{}': {} — falling back to one plain photograph",
                        subject, found);
                return null;
            }
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }

        // FIRE THE WHOLE ORBIT, THEN COLLECT. The first version waited for each file before
        // moving to the next stop, which put several seconds between shots -- and a mob does not
        // hold still for that. It walks out of frame, turns away, or wanders behind the camera, so
        // four "angles" became four pictures of wherever it had got to. Firing the stops back to
        // back and reading the files afterwards decouples the orbit's speed from disk I/O.
        java.util.List<ContactSheet.Frame> frames = new java.util.ArrayList<>();
        final int[] wandered = {0};
        // the HERO still (stop 0, the face), then one filmed turn instead of stops 1–3 —
        // taken with the creature still held, so the turn shows all of it standing as it stood.
        final boolean[] spinning = {SPIN};
        Spin spin = null;
        try {
        for (int stop = 0; stop < com.moddingfromamod.runtime.OrbitCamera.STOPS; stop++) {
            if (spinning[0] && stop == 1) {
                spin = spinCreature(label, subject, shape[3], shape[4], bearings[0]);
                if (spin != null) {
                    if (frames.isEmpty()) {
                        // the hero still did not arrive in time: the take's first frame is the face
                        byte[] face = com.moddingfromamod.runtime.SpinFilm.png(spin.frames().get(0));
                        if (face != null) {
                            frames.add(new ContactSheet.Frame(face, (int) Math.round(bearings[0][0])));
                        }
                    }
                    addSpinSides(frames, spin, bearings[0][0], true);
                    break;
                }
                spinning[0] = false;     // no strip came back: the other three stills, as before
            }
            final int s0 = stop;
            final int[] bearingOut = new int[1];
            final long[] before = {lastModified(photoPath(label + "__" + stopName(stop)))};
            final double[][] pose = {{0, 0, 0, 0, 0}};
            final double[] aimedAt = {Double.NaN, 0, 0};
            // AIM, THEN CHECK AGAIN BEFORE THE SHUTTER. The creature is held, but a held mob can
            // still be pushed or fall, so the second pass re-aims if it has moved. The BEARING is
            // never re-read: it was, and a creature that turned between stops dragged every later
            // stop round with it (stops at 180/186/346/135 degrees, measured in a real wish).
            for (int attempt = 0; attempt < 2; attempt++) {
                final boolean shoot = attempt == 1;
                try {
                    AgentBridge.onServer(() -> {
                        var server = AgentBridge.serverOrNull();
                        ServerPlayer player = eyes(server);
                        if (player == null) {
                            return "gone";
                        }
                        var mob = findSubject(player, subject);
                        if (mob == null) {
                            return "gone";
                        }
                        double bearing = bearings[0][s0];
                        int rounded = (int) Math.round(bearing);
                        // HAS IT MOVED? A held creature does not walk, but it can still fall or be
                        // shoved, and a camera left pointing at where it used to be photographs
                        // scenery. Position is followed; the bearing (above) is not.
                        boolean walked = shoot && !Double.isNaN(aimedAt[0])
                                && mob.distanceToSqr(aimedAt[0], aimedAt[1], aimedAt[2])
                                        > MAX_DRIFT_BLOCKS * MAX_DRIFT_BLOCKS;
                        var placed = com.moddingfromamod.runtime.OrbitCamera.at(
                                mob.getX(), mob.getY(), mob.getZ(), shape[3], bearing, shape[4]);
                        if (!shoot || walked) {
                            // First pass always aims; the second only re-aims if it actually moved,
                            // because a needless teleport costs another frame of settling.
                            bearingOut[0] = rounded;
                            aimedAt[0] = mob.getX();
                            aimedAt[1] = mob.getY();
                            aimedAt[2] = mob.getZ();
                            pose[0] = moveCameraTo(player, placed,
                                    mob.getX(), mob.getY() + shape[3] * 0.55, mob.getZ());
                        }
                        if (shoot) {
                            // the hero is the burst's LAST still when a spin follows, so PhotoClient
                            // gives the settings back before SpinClient takes them
                            boolean last = s0 == com.moddingfromamod.runtime.OrbitCamera.STOPS - 1
                                    || (spinning[0] && s0 == 0);
                            double[] c = pose[0];
                            shutter(player, label + "__" + stopName(s0), com.moddingfromamod.net.ModdedClient.photoAt(
                                    label + "__" + stopName(s0), ORBIT_SETTLE_TICKS, !last,
                                    c[0], c[1], c[2], (float) c[3], (float) c[4], quiet));
                        }
                        return "ok";
                    });
                } catch (AgentBridge.Unavailable e) {
                    break;
                }
                sleep(shoot ? ORBIT_GAP_MS : RECHECK_GAP_MS);
            }
            // WAIT FOR THE SHOT BEFORE MOVING ON. Firing all four stops on a timer and collecting
            // afterwards loses the race in the other direction: the camera reaches stop N+1 while
            // stop N's shutter is still pending, so the picture is taken from the wrong place and
            // comes back as landscape. Sequential is what "snapshot, THEN teleport" means, and with
            // a two-tick settle it costs about a tenth of a second per stop.
            Path out = photoPath(label + "__" + stopName(stop));
            byte[] png = awaitPhoto(out, before[0], stop == 0 ? HERO_TIMEOUT_MS : STOP_TIMEOUT_MS);
            // DID IT STAY PUT FOR THE EXPOSURE? Re-aiming closes the gap between aiming and firing,
            // but a creature can still stroll out of shot while the shutter is open, and a frame
            // it has left is a photograph of scenery that passes every check a file existing can
            // pass. Counted here so the agent is TOLD rather than left judging an empty field.
            try {
                Boolean stayed = AgentBridge.onServer(() -> {
                    var server = AgentBridge.serverOrNull();
                    ServerPlayer player = eyes(server);
                    var mob = player == null ? null : findSubject(player, subject);
                    return mob == null || Double.isNaN(aimedAt[0])
                            || mob.distanceToSqr(aimedAt[0], aimedAt[1], aimedAt[2]) < 4.0;
                });
                if (Boolean.FALSE.equals(stayed)) {
                    wandered[0]++;
                }
            } catch (AgentBridge.Unavailable ignored) {
                // no server to ask; the frame stands on its own
            }
            if (png != null) {
                frames.add(new ContactSheet.Frame(png, bearingOut[0]));
            }
        }
        } finally {
            release(hold[0]);
        }
        // THEN FILM, released, from the face. This ran inside the loop after stop 0, which put a
        // two-second clip of a live creature BETWEEN the face and the other three stops -- time
        // enough to walk off and turn round -- and filmed it frozen would be a still that cost
        // twenty frames (clipOf). Stop 0 is the FACE by construction (OrbitCamera.faceBearing): a
        // creature's ability plays forwards, and the front is where it plays TO you.
        // a filmed turn replaces it — one clip, all the way round, not a second take.
        if (spin == null) {
            filmFromHere(label, 0, subject, shape[3]);
        }

        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = eyes(server);
                if (player != null) {
                    player.setNoGravity(false);      // the child falls again, as they should
                }
                return "ok";
            });
        } catch (AgentBridge.Unavailable ignored) {
            // shutting down; the flag goes with the session
        }

        byte[] sheet = ContactSheet.compose(frames);
        if (sheet == null) {
            return null;             // nothing arrived; the caller falls back to a single shot
        }
        String lead = intro != null ? intro
                : "Here is what the child can see, from four sides. Look at it honestly: does it "
                  + "match what they asked for? If it looks scary when they wanted something "
                  + "friendly, fix it and photograph it again.";
        if (wandered[0] > 0) {
            lead += " (" + wandered[0] + " of the shots caught it mid-walk, so it may be small or "
                    + "off to one side in those — photograph again if you cannot judge it.)";
        }
        if (frames.size() < com.moddingfromamod.runtime.OrbitCamera.STOPS) {
            lead += " (Only " + frames.size() + " of "
                    + com.moddingfromamod.runtime.OrbitCamera.STOPS
                    + " angles came back, so one side of this creature is unchecked.)";
        }
        return ToolResult.image(lead + " " + framing,
                Base64.getEncoder().encodeToString(sheet), "image/png");
    }

    /**
     * <b>Shoot the standard orbit and hand back the frames on disk.</b>
     *
     * <p>For the eval harness, which needs the FILES rather than a contact sheet — it names them
     * by arm and revision for the corpus. Same camera, same bearings, same settle: two
     * implementations of "where do I stand to see this creature" drift, and the drift is invisible
     * because both of them produce photographs.
     */
    public static java.util.List<Path> orbitFiles(String label, String subject, boolean quiet) {
        captureOrbit(label, subject, null, quiet);
        java.util.List<Path> out = new java.util.ArrayList<>();
        for (int stop = 0; stop < com.moddingfromamod.runtime.OrbitCamera.STOPS; stop++) {
            Path f = photoPath(label + "__" + stopName(stop));
            if (Files.isRegularFile(f)) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * What each stop IS, for the filename.
     *
     * <p>Named by role rather than by compass bearing, and that is not cosmetic. The bearings are
     * relative to whichever way the creature is standing, so the face lands on 180 for a mob at yaw
     * 0 and on 43 for a mob at yaw 223. Files called {@code __000} and {@code __180} therefore read
     * as "front" and "back" to anyone looking at them, and are nothing of the kind — a reviewer of
     * nine creatures concluded the camera was photographing the backs of their heads when it was
     * the FILENAMES that were lying. Stop 0 is the face, always.
     */
    /**
     * <b>How many seconds to FILM at the face, after photographing it. 0 = never, and that is
     * the default.</b>
     *
     * <p>Set with {@code -Ptestprops="filmSeconds=15"}, alongside the recorder in
     * {@code tools/record-client.sh}.
     */
    private static final int FILM_SECONDS =
            Integer.getInteger(ModdingFromAModMod.MODID + ".filmSeconds", 0);

    /**
     * <b>Seconds of CLIP to capture at the face — on by default, unlike the external film.</b>
     *
     * <p>Two seconds, and it is the default because a creature is a thing that moves and four
     * stills cannot say so. Spikor's fire breath burns for 40 ticks out of every 80, so two seconds
     * catches a burst and the gap either side of it.
     *
     * <p>Distinct from {@link #FILM_SECONDS}, which drives an EXTERNAL recorder for the gates and
     * cannot ship — the child has no ffmpeg. This one is captured by the client itself.
     */
    private static final int CLIP_SECONDS =
            Integer.getInteger(ModdingFromAModMod.MODID + ".clipSeconds", 2);

    /** Ten a second: smooth enough for a two-second loop, cheap enough to not cost a reveal. */
    private static final int CLIP_GAP_MS = 100;

    /**
     * <b>How long an OFFLINE clip holds — ten seconds, five times the in-game one.</b>
     *
     * <p>The two lengths answer to different constraints and there is no number that serves both.
     * {@link #CLIP_SECONDS} rides at the end of a real wish, where the clip is the last thing
     * between a child and their creature and every second is a second they are not playing with it;
     * two seconds is enough to catch one burst of Spikor's fire (40 ticks on, 40 off) and get out
     * of the way.
     *
     * <p>An offline contact card has the opposite problem. Nobody is waiting, the creature has no
     * abilities to burst (they lived in a datapack the archive never kept), and what there is to see
     * is slow: a walk cycle, a turn, an idle sway. <b>Two seconds of that reads as a jitter</b> —
     * measured on the eight-Spikor card, where the clips are correctly framed, genuinely moving,
     * and still do not show enough of the creature to say what it is like. Ten seconds is a hundred
     * frames at {@link #CLIP_GAP_MS}, long enough to come back round to where it started.
     *
     * <p>Set per take, never globally: a property would let a card's length leak into a wish.
     */
    private static final int OFFLINE_CLIP_SECONDS = 10;

    /**
     * <b>Let the camera arrive before rolling.</b>
     *
     * <p>The clip used to start on the same tick as the still, and spent most of its two seconds
     * recording the camera getting into position: the tracking re-aims every 100 ms and the client
     * interpolates toward each correction, so the first second is a drift across the treetops with
     * no creature in it. Measured on a real Spikor — of twenty frames, the creature was properly
     * framed in the last TWO, and the middle eight are a picture of a jungle.
     *
     * <p>Half a second is enough for the pose to settle, and it costs nothing: the hold is
     * lengthened by the same amount, so the clip is still a full two seconds.
     */
    private static final long CLIP_SETTLE_MS = 500;

    /**
     * <b>How much further back the camera stands to FILM than to photograph.</b>
     *
     * <p>2.2, so a 6.5-block creature is filmed from ~18.6 blocks rather than 8.5. A creature that
     * emits anything emits it forwards, and the front stop is exactly where that lands: measured on
     * a real Spikor, whose breath reaches about nine blocks, the clip came back as a wall of flame
     * with the creature visible in one frame of twenty. The stills from the same run are fine,
     * because a still is over before the breath is.
     *
     * <p>Applied ONLY to the clip's re-aim, so the four photographs keep the framing they were
     * tuned for and only the film pulls back.
     */
    private static final double CLIP_STANDOFF = 2.2;

    /**
     * <b>Frame the creature the way the four stills do</b> — for a subject that cannot emit
     * anything, so there is nothing to leave room for. A shade over 1.0 because a clip's subject
     * moves and a portrait's does not: a walking creature at exactly the portrait distance clips
     * its own feet on the frame edge.
     */
    private static final double PORTRAIT_STANDOFF = 1.15;

    /** Wide enough to read a creature on a card, small enough that twenty frames is one PNG. */
    private static final int CLIP_WIDTH = 320;

    /**
     * <b>FILM the creature from this stop, tracking it, so something outside can record it.</b>
     *
     * <p><b>Why this is a step in the photograph rig rather than a camera of its own.</b>
     * Everything about where the camera stands for a creature — the bearing that finds its FACE
     * whichever way it turned, the distance derived from the vertical FOV so it fills a
     * comparable share of frame, and the lift that gets the lens out of a hillside and into
     * line of sight — was earned one bug at a time and lives in {@link
     * com.moddingfromamod.runtime.OrbitCamera} and {@code moveCameraTo}. A film that stood
     * somewhere of its own choosing would re-earn every one of them, and would do it while
     * MOVING, where an off-centre subject reads as a wobble rather than as a defect.
     *
     * <p><b>It must TRACK, and that is the whole difference between a photograph and a film.</b>
     * The first version of this held ONE pose for the whole take — the pose the still was shot
     * from — on the reasoning that the client already pins a pose every tick for the length of a
     * settle. That is true, and it is not enough, because <b>the rig's assumptions are about a
     * two-tick exposure and a film is three hundred ticks long</b>. This file already counts
     * {@code wandered}, "a creature can stroll out of shot while the shutter is open"; over two
     * ticks that is rare, and over fifteen seconds a mob with legs is nearly certain to do it.
     * Measured on the first real take: the camera held its pose perfectly and Spikor walked away,
     * so the still at the end of the take is a photograph of an empty beach. Nothing failed —
     * the mp4 was the right length, correctly exposed, and of nothing.
     *
     * <p>So the aim is recomputed from the creature's LIVE position and facing, through the same
     * {@code OrbitCamera.faceBearing} / {@code OrbitCamera.at} / {@code moveCameraTo} the stills
     * use. The camera is moved by the server rather than pinned by the client, which is exactly
     * backwards from a photograph and right for a film: {@code PhotoClient}'s pin exists to
     * defeat the client's interpolation for one crisp frame, and interpolation is what makes a
     * moving shot smooth instead of a slideshow.
     *
     * <p><b>The payload therefore carries no pose.</b> It is sent only so the client hides the
     * HUD, lifts the brightness and holds both for the length of the take — the same treatment
     * every photograph gets — and takes one still at the end for the record. A pose here would
     * fight the tracking, pinning the camera to the spot the take began.
     *
     * <p><b>Particles are NOT quieted, whatever the caller asked for.</b> {@code quiet} exists so
     * form and colour can be judged through a creature's own fire; here the fire IS the subject,
     * and a filmed fire breath with the particles off is a film of a creature standing still.
     * This is the one place in the rig where quiet would invert the purpose.
     *
     * <p>Nothing is recorded in-process: {@code tools/record-client.sh} is grabbing the X display
     * throughout and cuts this window out afterwards. Windows are APPENDED, one line per take,
     * because the agent may photograph several times in one wish and which take is the
     * interesting one is not knowable from here.
     */
    /**
     * <b>Film a clip of a subject, on its own, after the stills have already been taken.</b>
     *
     * <p>Exists because <b>a clip of a frozen subject is a still that cost twenty frames</b>, and
     * that is what the rephoto harness was producing for every creature on the contact card.
     * Measured over the five clips it had already shipped: 18/19, 16/19, 13/19, 10/19 and 7/19
     * consecutive frame pairs were near-identical, and the one large delta in each was the camera
     * arriving, not the creature moving. The harness holds a creature still on purpose — a mob that
     * walks or falls ruins the four stills — so the freeze is right for the orbit and wrong for the
     * film, and the two cannot share one pass.
     *
     * <p>So: shoot the orbit frozen, RELEASE the creature, then call this. The caller owns the
     * release, because only the caller knows what it froze.
     *
     * <p><b>Films at PORTRAIT distance, not the in-game clip standoff, and that is the point.</b>
     * {@link #CLIP_STANDOFF} pulls the camera back 2.2x so a creature's fire fits in frame — right
     * in a real wish, and wrong for every caller of this method, because the only thing that calls
     * it is a harness rebuilding a creature from {@code sculpt.json}, and a rebuilt creature has
     * <b>no abilities</b>: they lived in a datapack nobody archived. So there is nothing to leave
     * room for, and leaving room for it anyway is what made every clip on the first contact card a
     * landscape with a twenty-pixel speck in it.
     *
     * @param label  what the frames are named after, as with {@link #captureOrbit}
     * @param subject the creature to follow
     */
    public static void clipOf(String label, String subject) {
        clipOf(label, subject, PORTRAIT_STANDOFF, OFFLINE_CLIP_SECONDS);
    }

    /**
     * The same, at a chosen standoff — {@code 1.0} frames the creature as the stills do, and
     * anything above that leaves room for whatever it emits.
     */
    public static void clipOf(String label, String subject, double standoff) {
        clipOf(label, subject, standoff, OFFLINE_CLIP_SECONDS);
    }

    /** The same, at a chosen standoff and length. */
    public static void clipOf(String label, String subject, double standoff, int clipSeconds) {
        final double[] h = new double[1];
        try {
            String ok = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = eyes(server);
                if (player == null) {
                    return "nobody";
                }
                var best = findSubject(player, subject);
                if (best == null) {
                    return "missing";
                }
                h[0] = subjectHeightBlocks(subject, best);
                return "ok";
            });
            if (!"ok".equals(ok)) {
                LOGGER.info("clipOf: no subject '{}' ({})", subject, ok);
                return;
            }
        } catch (AgentBridge.Unavailable e) {
            return;
        }
        filmFromHere(label, 0, subject, h[0], standoff, clipSeconds);
    }

    private static void filmFromHere(String label, int stop, String subject, double height) {
        filmFromHere(label, stop, subject, height, CLIP_STANDOFF);
    }

    /**
     * The same, at a caller-chosen standoff.
     *
     * <p>See {@link #CLIP_STANDOFF} for why the shipped default pulls back and why a caller that
     * knows the creature cannot emit anything should not.
     */
    private static void filmFromHere(String label, int stop, String subject, double height,
                                     double standoff) {
        filmFromHere(label, stop, subject, height, standoff, CLIP_SECONDS);
    }

    /**
     * The same, holding for a caller-chosen number of seconds.
     *
     * <p>See {@link #OFFLINE_CLIP_SECONDS} for why an offline contact card holds far longer than
     * the clip a child gets at the end of a wish.
     */
    private static void filmFromHere(String label, int stop, String subject, double height,
                                     double standoff, int clipSeconds) {
        if (stop != 0 || (FILM_SECONDS <= 0 && clipSeconds <= 0)) {
            return;
        }
        String sigDir = System.getProperty(ModdingFromAModMod.MODID + ".sigdir", "");
        if (FILM_SECONDS > 0 && sigDir.isBlank()) {
            return;      // an external recorder was asked for and there is nothing recording
        }
        // The orb and reveals stay down for the whole take, stills and clip alike.
        AutoCloseable filming = com.moddingfromamod.wish.WishCapture.filming();
        try {
            filmTake(label, subject, height, standoff, clipSeconds, sigDir);
        } finally {
            try {
                filming.close();
            } catch (Exception ignored) {
                // closing a counter cannot fail
            }
        }
    }

    private static void filmTake(String label, String subject, double height, double standoff,
                                 int clipSeconds, String sigDir) {
        long startMs = System.currentTimeMillis();
        final int seconds = Math.max(FILM_SECONDS, clipSeconds);
        try {
            AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = eyes(server);
                if (player == null) {
                    return "gone";
                }
                // No pose: the server is doing the aiming below. This is here for the HUD and the
                // brightness, and for the one still it leaves at the end of the take.
                shutter(player, label + "__film",
                        com.moddingfromamod.net.ModdedClient.photo(label + "__film", seconds * 20, true));
                return "ok";
            });
        } catch (AgentBridge.Unavailable e) {
            return;
        }
        long endMs = startMs + seconds * 1000L + (clipSeconds > 0 ? CLIP_SETTLE_MS : 0);
        boolean rolling = false;
        // Held across the take so the camera stops cancelling the subject's own rotation.
        final Double[] clipBearing = new Double[1];
        while (System.currentTimeMillis() < endMs) {
            if (clipSeconds > 0 && !rolling
                    && System.currentTimeMillis() - startMs >= CLIP_SETTLE_MS) {
                rolling = true;
                try {
                    AgentBridge.onServer(() -> {
                        var server = AgentBridge.serverOrNull();
                        ServerPlayer p = eyes(server);
                        if (p != null) {
                            // The client films itself. Asked for after the still so the HUD is
                            // already down and the brightness already up — a clip should look like
                            // the photographs it sits beside, not like a different camera.
                            shutter(p,
                                    com.moddingfromamod.net.ModdedClient.clip(label,
                                            (clipSeconds * 1000) / CLIP_GAP_MS,
                                            CLIP_GAP_MS, CLIP_WIDTH));
                        }
                        return "ok";
                    });
                } catch (AgentBridge.Unavailable e) {
                    break;
                }
            }
            try {
                AgentBridge.onServer(() -> {
                    var server = AgentBridge.serverOrNull();
                    ServerPlayer player = eyes(server);
                    if (player == null) {
                        return "gone";
                    }
                    var mob = findSubject(player, subject);
                    if (mob == null) {
                        return "gone";   // it died or despawned; keep filming where it was
                    }
                    // ── FOLLOW THE POSITION, LOCK THE BEARING (#347-adjacent, see docs/CAMERA.md)
                    //
                    // This used to re-read yBodyRot every 100ms, which holds the camera at the
                    // creature's FRONT for the whole take. That sounds right and it is the one
                    // thing a clip must not do: if the subject turns and the camera turns with it,
                    // the turn is subtracted out of the shot. Every creature clip came back looking
                    // like a slow orbit of a statue -- the camera was moving and the creature was
                    // not, which is the opposite of what a clip is for. The four stills already
                    // answer "what shape is it" better than any orbit can.
                    //
                    // So the bearing is taken ONCE, from the first frame, and held. Position is
                    // still followed, so a creature that walks cannot leave the frame -- but a
                    // creature that turns, lunges or breathes fire now visibly does so.
                    if (clipBearing[0] == null) {
                        clipBearing[0] = com.moddingfromamod.runtime.OrbitCamera.faceBearing(
                                mob.yBodyRot, 0);
                    }
                    double bearing = clipBearing[0];
                    var placed = com.moddingfromamod.runtime.OrbitCamera.at(
                            mob.getX(), mob.getY(), mob.getZ(), height, bearing,
                            clipSeconds > 0 ? standoff : 1.0);
                    moveCameraTo(player, placed,
                            mob.getX(), mob.getY() + height * 0.55, mob.getZ());
                    return "ok";
                });
            } catch (AgentBridge.Unavailable e) {
                break;
            }
            // Every other tick. The client interpolates between corrections, so this is a smooth
            // follow rather than a step per update, and it halves the cross-thread traffic.
            sleep(100L);
        }
        if (FILM_SECONDS <= 0 || sigDir.isBlank()) {
            LOGGER.info("clip: held {}s at the face for {}", seconds, label);
            return;      // in-game clip only; there is no external window to mark
        }
        try {
            Path windows = Path.of(sigDir, "film-windows.tsv");
            Files.createDirectories(windows.getParent());
            Files.writeString(windows,
                    startMs + "\t" + System.currentTimeMillis() + "\t" + label + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            LOGGER.info("filmed {} for {}s, tracking its face", label, FILM_SECONDS);
        } catch (java.io.IOException e) {
            LOGGER.warn("could not record the film window: {}", String.valueOf(e));
        }
    }

    /** Give the creature its AI back, on the server thread. */
    private static void release(SubjectHold hold) {
        if (hold == null) {
            return;
        }
        try {
            AgentBridge.onServer(() -> {
                hold.close();
                return "ok";
            });
        } catch (AgentBridge.Unavailable ignored) {
            // shutting down; nothing left to release it for
        }
    }

    private static String stopName(int stop) {
        return switch (stop) {
            case 0 -> "0_face";
            case 1 -> "1_left";
            case 2 -> "2_back";
            default -> "3_right";
        };
    }

    /** Put the camera exactly where the orbit says, without disturbing the creature. */
    /** @return {x, feetY, z, yaw, pitch} — exactly where the camera ended up. */
    private static double[] moveCameraTo(ServerPlayer player,
                                         com.moddingfromamod.runtime.OrbitCamera.Shot shot,
                                         double aimX, double aimY, double aimZ) {
        double feetY = shot.eyeY() - player.getEyeHeight();
        // ⚠ THE RULE MOVED, THE BEHAVIOUR DID NOT. Everything that used to be written out here --
        // lift out of a block, rise until the subject is visible, re-aim, slide back down the
        // proven-clear ray -- now lives in ClearView, unchanged and with its scars intact. It was
        // moved because it was reachable from HERE ONLY: this method takes an OrbitCamera.Shot and
        // teleports a player, which is the creature orbit's shape, so captureBuilding could not
        // call it and never did. Buildings and trains were therefore photographed through whatever
        // stood in the way, for the child's actual wish card.
        //
        // The comment this replaces said it out loud -- "a 100-block train is the worst case for
        // this ... but it is not train-specific, and fixing it here rather than in TrainFraming
        // fixes buildings and creatures with it" -- which was right, and applied somewhere the
        // train path does not reach.
        var seat = com.moddingfromamod.runtime.ClearView.find(
                sight(player), shot.x(), feetY, shot.z(), (float) shot.yaw(),
                aimX, aimY, aimZ, player.getEyeHeight());
        double camX = seat.x();
        double camZ = seat.z();
        double y = seat.feetY();
        double eyeY = y + player.getEyeHeight();
        double flat = Math.hypot(camX - aimX, camZ - aimZ);
        if (seat.blind()) {
            LOGGER.info("orbit: no clear line to the subject after a {}-block climb — shooting "
                    + "anyway, but this frame may be of whatever is in the way",
                    Math.round(seat.rose()));
        }
        float pitch = seat.pitch();
        // PIN THE CAMERA. Between stops the orbit waits for a file, and a player who is not
        // flying FALLS during that wait -- ~40 blocks in three seconds. Two of four frames came
        // back dark and underwater while the log cheerfully recorded the height it had asked for,
        // because the height it asked for is not where the player was when the shutter opened.
        player.setNoGravity(true);
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0;
        player.connection.teleport(camX, y, camZ, shot.yaw(), pitch);
        // ⚠ AND TELL THE FREEZE WHERE THE BODY NOW BELONGS. WishCapture holds a borrowed
        // child at his own standing spot every few ticks so he cannot walk off mid-burst -- and an
        // orbit seat is eighteen blocks from that spot, so without this line the hold spends the
        // whole take dragging the camera back off the creature. The stills usually beat it by a
        // tenth of a second; the film's still, requested two seconds ahead, did not, which is the
        // empty field.
        com.moddingfromamod.wish.WishCapture.aimedAt(player.getUUID(), camX, y, camZ);
        // Logged because this has now been guessed at three times. A frame that comes back empty
        // is answerable from here instead of from another run.
        LOGGER.info("orbit: cam({}, {}, {}) yaw {} pitch {} -> aim({}, {}, {})",
                Math.round(camX), Math.round(y), Math.round(camZ),
                Math.round(shot.yaw()), Math.round(pitch),
                Math.round(aimX), Math.round(aimY), Math.round(aimZ));
        return new double[]{camX, y, camZ, shot.yaw(), pitch};
    }

    /**
     * The nearest creature called {@code subjectId}, on EITHER renderer.
     *
     * <p>Returns {@link net.minecraft.world.entity.LivingEntity} rather than {@code WishMobEntity}
     * because the shipping creature is a display rig riding a vanilla base, and this
     * method returning the narrower type is exactly what made the camera blind to it: the
     * orbit fell through to one plain shot, the clip and the film were never written, and the run
     * reported PASS because one file had been created. Every caller only ever asked the result for
     * {@code getX/Y/Z}, {@code getScale}, {@code yBodyRot} and {@code distanceToSqr}, all of which
     * a {@code LivingEntity} answers, so widening it costs nothing.
     *
     * <p>The MATCHING RULE itself is {@link com.moddingfromamod.runtime.CaptureSubject}, which is
     * Minecraft-free and unit-tested. It lives there and not here because a rule that can only be
     * exercised by booting a server, a world and a client is a rule that ships wrong.
     */
    private static net.minecraft.world.entity.LivingEntity findSubject(ServerPlayer player,
                                                                       String subjectId) {
        return findSubject(player.level(), player, subjectId);
    }

    /** The same, for any viewer — so a GameTest can ask it with a mock player in a real world. */
    static net.minecraft.world.entity.LivingEntity findSubject(
            net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity player,
            String subjectId) {
        if (subjectId == null || subjectId.isEmpty()) {
            return null;
        }
        java.util.List<net.minecraft.world.entity.LivingEntity> found = new java.util.ArrayList<>();
        java.util.List<com.moddingfromamod.wish.SubjectPick.Candidate> cs = new java.util.ArrayList<>();
        for (var e : level.getEntities().getAll()) {
            if (!(e instanceof net.minecraft.world.entity.LivingEntity living)) {
                continue;
            }
            String species = e instanceof com.moddingfromamod.entity.WishMobEntity w
                    ? w.speciesId() : null;
            if (!com.moddingfromamod.runtime.CaptureSubject.isSubject(
                    subjectId, species, e.getTags())) {
                continue;
            }
            found.add(living);
            cs.add(new com.moddingfromamod.wish.SubjectPick.Candidate(living.getUUID(),
                    living.getX(), living.getY(), living.getZ(), living.distanceToSqr(player)));
        }
        if (found.isEmpty()) {
            return byHandle(level, subjectId);
        }
        // NOT simply the nearest to the viewpoint — a camera parks wherever its last take
        // left it. The one a live wish spawned, else the one at a live wish's spot (SubjectPick).
        java.util.Set<java.util.UUID> spawned = new java.util.HashSet<>();
        java.util.List<double[]> anchors = new java.util.ArrayList<>();
        String dim = level.dimension().location().toString();
        String want = subjectId.trim().toLowerCase(java.util.Locale.ROOT);
        for (var run : com.moddingfromamod.marshal.Marshal.global().live()) {
            boolean about = false;
            for (var c : run.creaturesSpawned()) {
                if (c.species().equalsIgnoreCase(want)) {
                    about = true;
                    if (c.carrier() != null) {
                        spawned.add(c.carrier());
                    }
                }
            }
            for (var m : run.made()) {
                about |= m.id().equalsIgnoreCase(want);
            }
            var a = run.anchor();
            if (about && a != null && dim.equals(a.dimension())) {
                anchors.add(new double[] { a.x() + 0.5, a.y(), a.z() + 0.5 });
            }
        }
        int i = com.moddingfromamod.wish.SubjectPick.pick(cs, spawned, anchors);
        return i < 0 ? null : found.get(i);
    }

    /**
     * <b>A handle that names no creature is still ONE animal (A).</b> {@code modify_mob}
     * records the mob it changed by handle, so a summoned-and-changed snow golem reaches the camera
     * as {@code @snow_golem}; {@link #creatureSubject} turns a handle into a species only for a
     * wished creature, and every match above is by species or tag, so a plain vanilla mob was never
     * found: "orbit skipped … missing", and the wish card had no picture of the golem. The handle
     * already says which entity it is, so it is asked for that one, in this level.
     */
    private static net.minecraft.world.entity.LivingEntity byHandle(
            net.minecraft.server.level.ServerLevel level, String subjectId) {
        if (!com.moddingfromamod.mob.EntityHandles.looksLikeHandle(subjectId)) {
            return null;
        }
        var h = com.moddingfromamod.mob.EntityHandles.global().resolve(subjectId);
        if (h.isEmpty()) {
            return null;
        }
        return level.getEntity(h.get().id()) instanceof net.minecraft.world.entity.LivingEntity l
                && l.isAlive() ? l : null;
    }

    /**
     * How tall the subject really is, in blocks -- the ONE framing input, computed once.
     *
     * <p>Three call sites asked this question with their own copy of
     * {@code heightInBlocks(modelHeightUnits(geometry(id)), found.getScale())}, which is how they
     * all three got it wrong together the day the rig became the default: the scale on a
     * rig's carrier belongs to the carrier. The rule lives in
     * {@link com.moddingfromamod.runtime.CaptureSubject#scaleOf} and is unit-tested; this is only
     * the wiring that fetches the two facts it needs.
     */
    /**
     * How far the subject reaches sideways, in blocks (F1): its model's X/Z extent times its scale, or a vanilla
     * mob's own hitbox width. 0 when unknown, which frames on height alone as before.
     */
    private static double subjectSpanBlocks(String subject, net.minecraft.world.entity.LivingEntity found) {
        float units = com.moddingfromamod.runtime.Framing.modelSpanUnits(
                com.moddingfromamod.runtime.ModelRepository.geometry(subject));
        var species = com.moddingfromamod.species.SpeciesRegistry.get(subject);
        if (units <= 0) {
            return found.getBbWidth();   // no model of ours: its own box, as its height above
        }
        double scale = com.moddingfromamod.runtime.CaptureSubject.scaleOf(
                found instanceof com.moddingfromamod.entity.WishMobEntity,
                found.getScale(),
                species == null ? null : species.scale());
        return units / 16.0 * Math.max(0.01, scale);
    }

    private static double subjectHeightBlocks(String subject,
                                              net.minecraft.world.entity.LivingEntity found) {
        float units = com.moddingfromamod.runtime.Framing.modelHeightUnits(
                com.moddingfromamod.runtime.ModelRepository.geometry(subject));
        var species = com.moddingfromamod.species.SpeciesRegistry.get(subject);
        if (units <= 0 && species == null
                && !(found instanceof com.moddingfromamod.entity.WishMobEntity)
                && !found.getTags().contains(com.moddingfromamod.vanilla.DisplayRig.RIG_TAG)) {
            // A plain vanilla mob found by its handle (A) has no model of ours: its own
            // hitbox IS what is drawn, already scaled. Never for a rig — its box is the carrier's.
            return Math.max(0.5, found.getBbHeight());
        }
        if (units <= 0) {
            // NO MODEL OF OURS TO MEASURE (a define_creature lookalike: "spider shape", scale 3). The fallback was a
            // flat 1 block, and the wish camera stood inside a giant tarantula's legs (2026-11-09). The entity's own
            // box is the best measure there is: rough for a rig, never smaller than the creature.
            return Math.max(1.0, found.getBbHeight());
        }
        double scale = com.moddingfromamod.runtime.CaptureSubject.scaleOf(
                found instanceof com.moddingfromamod.entity.WishMobEntity,
                found.getScale(),
                species == null ? null : species.scale());
        return com.moddingfromamod.runtime.Framing.heightInBlocks(units, scale);
    }

    /**
     * Wait for one screenshot to land and become readable, or null.
     *
     * <p>Extracted from {@link #capture} rather than copied: the "wait for the SIZE to stop
     * changing" rule is the whole reason photographs stopped failing. A screenshot of a modern
     * window is 1-2 MB, the timestamp moves when the file is CREATED rather than finished, and the
     * version that decoded once and gave up failed all five times in one live session while the
     * PNGs sat complete on disk moments later.
     */
    /**
     * The per-frame wait used by the orbit and film bursts.
     *
     * <p>⚠ <b>Same rule as the single-shot loop, and it needed it MORE.</b> Callers pass
     * {@code STOP_TIMEOUT_MS} (3s), which is tighter than the 8s that was measured missing a
     * photograph by 2.4 seconds on a machine with no GPU — so on that box every frame of a contact
     * sheet would be dropped, and a sheet with holes in it reads as a camera that cannot see rather
     * than a clock that is too fast.
     *
     * <p>{@code timeoutMs} is now the QUIET period — how long to keep waiting after the last sign
     * of progress — not the total. A burst therefore still cannot hang: each frame gives up 3s
     * after its file stops changing, which for a frame that never arrives is 3s exactly as before.
     */
    private static byte[] awaitPhoto(Path out, long before, long quietMs) {
        // A frame that never appears gives up after quietMs, exactly as it always has: these
        // callers' budgets were chosen with an absent file timing out at quietMs, and
        // changes only the single shot's absent-file wait.
        return awaitFile(out, before, quietMs, quietMs).value();
    }

    /**
     * The one wait for a photograph file, for the single shot and every burst frame alike: up to
     * {@code appearMs} for a file newer than {@code before} to appear, then until it stops growing
     * ({@code quietMs} of silence gives up), decoded and filed under its wish. The decision is
     * {@link PhotoWait}'s; this supplies only the real clock, the disk and the decoder.
     */
    private static PhotoWait.Outcome<byte[]> awaitFile(Path out, long before, long appearMs,
                                                       long quietMs) {
        return PhotoWait.await(before, appearMs, quietMs, HARD_CAP_MS, POLL_MS,
                System::currentTimeMillis,
                () -> new PhotoWait.Probe(lastModified(out), sizeOf(out)),
                () -> {
                    byte[] png = shrink(out);
                    if (png != null) {
                        com.moddingfromamod.wish.WishShots.keep(out);   // File it under its wish
                    }
                    return png;
                },
                PhotographTool::sleep);
    }

    /**
     * Point the viewpoint at a creature, travelling to it if it will not otherwise fit.
     *
     * <p>⚠ <b>This used to be rotation-only for a child</b>, on a rule that reads as obviously
     * kind: <i>yanking a child across the world to frame a photo is a far ruder thing to do than
     * handing back a picture that needs a second try.</i> Two things retired it. It was already
     * false — the ORBIT path teleports whatever body it is handed, so a child without a camera in
     * the world was already being flown to four seats and put back, and this fallback was the lone
     * holdout. And its failure mode was worse than rudeness: beyond {@code VISIBLE_RANGE} a
     * rotation-only viewpoint turns to face a dot on the horizon and photographs it, and all the
     * caller could do was hand over the frame saying <i>this picture is NOT of the creature</i>.
     *
     * <p>The decision lives in {@link com.moddingfromamod.runtime.AimPlan}, which is Minecraft-free
     * and unit-tested and no longer takes a viewpoint kind at all. This is the wiring that executes
     * it. What makes moving a child acceptable is not a check in here: {@code capture()} takes the
     * {@link com.moddingfromamod.wish.WishCapture} lease BEFORE aiming, and that refuses on a
     * client which cannot be shown the wish-cam — so a body this method can move has already been
     * told, frozen, made safe, and had its exact spot recorded for the warp back.
     */
    /** The start of {@link #aimAt}'s "no such thing in the world" answer, so the fallback can see it. */
    private static final String NOT_FOUND = "(Couldn't find a '";

    /**
     * What a subject-less shot aims at (F), read from the run on the agent's thread: the
     * wish's spot (its FINAL anchor — a {@code conjure_at} or a safe-spot move re-locks it), where
     * he stood when he wished, and whether the wish took him somewhere ({@code take_me_to}).
     */
    record WishSpot(java.util.UUID child, String dimension, int x, int y, int z, double[] from,
                    boolean went, String wish) {
    }

    static WishSpot wishSpot(com.moddingfromamod.marshal.WishRun run) {
        if (run == null) {
            return null;
        }
        var a = run.anchor();
        var f = run.aimedFrom();
        double[] from = f == null || a == null || !f.dimension().equals(a.dimension()) ? null
                : new double[] { f.x(), f.eyeY(), f.z(), f.yaw() };
        // a tp by command took him somewhere too, not only take_me_to (wish.WishMoves).
        boolean went = run.tookHimSomewhere();
        if (a == null && !went) {
            return null;
        }
        return new WishSpot(run.playerId(), a == null ? "" : a.dimension(),
                a == null ? 0 : a.x(), a == null ? 0 : a.y(), a == null ? 0 : a.z(), from, went,
                run.shortId());
    }

    /**
     * Point the viewpoint at the wish's own spot, or — for a teleport — stand where he now stands
     * and look where he looks. Through {@link com.moddingfromamod.runtime.ClearView}, like every
     * other move here. Null when there is no run to say where (the old behaviour stands).
     */
    static String aimAtWishSpot(com.moddingfromamod.wish.Viewpoint view, WishSpot spot) {
        if (spot == null) {
            return null;
        }
        ServerPlayer player = view.player();
        var level = player.level();
        com.moddingfromamod.wish.WishSpotShot.Pose pose;
        if (spot.went()) {
            ServerPlayer him = spot.child() == null ? null
                    : player.getServer().getPlayerList().getPlayer(spot.child());
            if (him == null || him == player) {
                return "(Nothing was made to frame: this is what he sees where the wish took him.)";
            }
            if (him.level() != level) {
                return "(Nothing was made to frame, and he is in another dimension from the camera, "
                        + "so this picture is not of where the wish took him.)";
            }
            pose = com.moddingfromamod.wish.WishSpotShot.fromHim(him.getX(), him.getY(), him.getZ(),
                    him.getYRot(), him.getXRot(), player.getEyeHeight());
        } else {
            if (!level.dimension().location().toString().equals(spot.dimension())) {
                return "(Nothing was named to frame, and the wish's spot is in another dimension "
                        + "from the camera, so this picture is not of your wish.)";
            }
            pose = com.moddingfromamod.wish.WishSpotShot.atSpot(spot.x(), spot.y(), spot.z(),
                    spot.from(), player.getEyeHeight());
        }
        var seat = spot.went()
                ? new com.moddingfromamod.runtime.ClearView.Seat(pose.x(), pose.feetY(), pose.z(),
                        pose.yaw(), pose.pitch(), 0, false, false)
                : com.moddingfromamod.runtime.ClearView.find(sight(player), pose.x(), pose.feetY(),
                        pose.z(), pose.yaw(), pose.aimX(), pose.aimY(), pose.aimZ(),
                        player.getEyeHeight());
        player.setNoGravity(true);
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0;
        player.connection.teleport(seat.x(), seat.feetY(), seat.z(), seat.yaw(), seat.pitch());
        com.moddingfromamod.wish.WishCapture.aimedAt(player.getUUID(), seat.x(), seat.feetY(), seat.z());
        LOGGER.info("aim: no subject — {} shot wish {} at its spot ({}, {}, {}), {}, seat({}, {}, {}) "
                        + "blind={}", view.isCamera() ? "camera" : "child", spot.wish(),
                spot.x(), spot.y(), spot.z(), pose.how(), Math.round(seat.x()),
                Math.round(seat.feetY()), Math.round(seat.z()), seat.blind());
        return spot.went()
                ? "(Nothing was made to frame, so this is the view from where the wish took him.)"
                : "(Nothing was named to frame, so the camera looked at the spot where this wish "
                        + "landed, " + pose.how()
                        + (seat.blind() ? " — but found no clear line to it, so this frame may be of "
                                + "whatever is in the way" : "") + ".)";
    }

    private static String aimAt(com.moddingfromamod.wish.Viewpoint view, String subjectId) {
        ServerPlayer player = view.player();
        // ONE subject search, not two. This used to carry its own copy of the WishMobEntity-only
        // match, which made it blind to the rig-on-a-vanilla-base default in exactly the same way
        // findSubject was -- and this is the WORSE half, because it is the FALLBACK: when
        // the orbit finds nothing, the plain shot taken instead is aimed by this method, so both
        // failing together means the wish card gets a photograph of whatever the child happened to
        // be facing. On the run that produced that was the Mojang loading screen.
        // A PICTURE is not an entity, so findSubject cannot see one: stand at its front
        // pose, through ClearView like every other move here, and look at its middle.
        var board = pictureBoard(subjectId);
        var front = com.moddingfromamod.paint.PictureFraming.poses(board);
        if (!front.isEmpty()) {
            var f = front.get(0);
            var seat = com.moddingfromamod.runtime.ClearView.find(
                    sight(player), f.x(), f.y(), f.z(), f.yaw(),
                    board.cx(), board.cy(), board.cz(), player.getEyeHeight());
            player.setNoGravity(true);
            player.setDeltaMovement(0, 0, 0);
            player.fallDistance = 0;
            player.connection.teleport(seat.x(), seat.feetY(), seat.z(), seat.yaw(), seat.pitch());
            com.moddingfromamod.wish.WishCapture.aimedAt(player.getUUID(),
                    seat.x(), seat.feetY(), seat.z());
            return "";
        }
        var best = findSubject(player, subjectId);
        if (best == null) {
            return NOT_FOUND + subjectId + "' in the world to point at, so this is "
                    + "whatever the child was already looking at.)";
        }
        double heightBlocks = subjectHeightBlocks(subjectId, best);

        // Look at the middle of the creature, not its feet.
        double dx = best.getX() - player.getX();
        double dz = best.getZ() - player.getZ();
        double dy = (best.getY() + heightBlocks / 2.0) - player.getEyeY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, pitch);

        double distance = Math.sqrt(best.distanceToSqr(player));
        var aim = com.moddingfromamod.runtime.AimPlan.plan(heightBlocks, distance);

        if (aim.move() == com.moddingfromamod.runtime.AimPlan.Move.MOVE_VIEWPOINT) {
            // THE VIEWPOINT GOES TO THE SUBJECT -- whichever body it is. It must NOT step the
            // creature instead: the creature is what the agent is about to critique, and a subject
            // that was teleported to make the photo work is not the subject the child will see.
            //
            // ⚠ THIS BRANCH IS NO LONGER CAMERA-ONLY. A child reaches it too, and the safety that
            // makes that acceptable is upstream and structural rather than a check here:
            // capture() takes the WishCapture lease BEFORE aiming, and enter() refuses outright on
            // a client that cannot be shown the wish-cam. So a body this line can move is a body
            // that was told, frozen, made invulnerable, and has its exact spot and view recorded
            // for the warp back. The old rule -- "a camera may travel and a child may not" -- is
            // retired in AimPlan, with the reason it was already false on the orbit path.
            double want = aim.standoff();
            double ux = flat < 0.001 ? 1.0 : dx / flat;
            double uz = flat < 0.001 ? 0.0 : dz / flat;
            double aimX = best.getX();
            double aimY = best.getY() + heightBlocks / 2.0;
            double aimZ = best.getZ();
            double wantX = aimX - ux * want;
            double wantZ = aimZ - uz * want;
            float wantYaw = (float) (Math.toDegrees(Math.atan2(aimZ - wantZ, aimX - wantX)) - 90.0);
            // ⚠ THROUGH ClearView, NOT a bare teleport. Its whole scar list -- lift out of a
            // block, rise until the subject is visible, re-aim, slide back down the proven-clear
            // ray -- was for years reachable from the creature ORBIT only, so buildings and trains
            // were shot through whatever stood in the way. Adding a SECOND camera-moving
            // path that skips it would reintroduce that defect in a new place, and it is the one
            // this repo's own notes name: "there is no check" and "the check is not wired to this
            // path" look identical from outside. It is also what keeps a CHILD out of a wall now
            // that a child can be sent here.
            var seat = com.moddingfromamod.runtime.ClearView.find(
                    sight(player), wantX, best.getY(), wantZ, wantYaw,
                    aimX, aimY, aimZ, player.getEyeHeight());
            // PIN IT. A body that is not flying falls while the shot waits on a file -- ~40
            // blocks in three seconds, which is how two of four frames came back underwater.
            // WishCapture.restore puts the flag back for a child; a camera's is cleared by the
            // orbit paths that raise it.
            player.setNoGravity(true);
            player.setDeltaMovement(0, 0, 0);
            player.fallDistance = 0;
            player.connection.teleport(seat.x(), seat.feetY(), seat.z(), seat.yaw(), seat.pitch());
            // TELL THE FREEZE where the body now belongs. This line was written while the
            // branch was camera-only and was a deliberate no-op, on the argument that "correct
            // because of what the caller currently is" is how TrainFraming's 90.0 was correct for
            // years. It is live now: without it the hold would spend the whole shot dragging a
            // frozen child back off the creature.
            com.moddingfromamod.wish.WishCapture.aimedAt(player.getUUID(),
                    seat.x(), seat.feetY(), seat.z());
            LOGGER.info("aim: {} travelled {} blocks to '{}' -> stand-off {}, seat({}, {}, {}) "
                    + "rose {} blind={}",
                    view.isCamera() ? "camera" : "child", Math.round(distance), subjectId,
                    Math.round(want), Math.round(seat.x()), Math.round(seat.feetY()),
                    Math.round(seat.z()), Math.round(seat.rose()), seat.blind());
            return "(Pointed at '" + subjectId + "', " + Math.round(want) + " blocks away, "
                    + Math.round(heightBlocks) + " blocks tall. The view travelled "
                    + Math.round(distance) + " blocks to it"
                    + (seat.blind() ? ", but found no clear line to it -- this frame may be of "
                            + "whatever is in the way" : "") + ".)";
        }

        return "(Pointed at '" + subjectId + "', " + Math.round(distance) + " blocks away, "
                + Math.round(heightBlocks) + " blocks tall.)";
    }

    /** Read the screenshot and scale it down for the response. */
    /**
     * Read the screenshot and scale it down for the wire, or null if it isn't readable YET.
     *
     * <p>Null is an ordinary, expected outcome here — a half-written PNG — so the caller retries.
     * What is NOT ordinary is the reason being invisible: this used to {@code catch (Exception e)
     * { return null; }}, so five consecutive real failures in one session all surfaced as the same
     * six-word message and nobody could tell a truncated file from a broken decoder. Log it.
     */
    // Package-private so PhotographToolTest can pin the null-vs-bytes contract the retry rests on.
    static byte[] shrink(Path file) {
        try {
            BufferedImage src = ImageIO.read(file.toFile());
            if (src == null) {
                LOGGER.debug("photo {} not decodable yet (no reader / partial file)", file);
                return null;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            double factor = Math.min(1.0, (double) MAX_EDGE / Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * factor));
            int nh = Math.max(1, (int) Math.round(h * factor));
            // no Java2D: it froze the game window on macOS (runtime/Pixels)
            BufferedImage dst = com.moddingfromamod.runtime.Pixels.scaled(src, nw, nh, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(dst, "PNG", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            LOGGER.debug("photo {} not readable yet: {}", file, e.toString());
            return null;
        }
    }

    /** Mirrors {@code PhotoClient.photoPath} without touching that client-only class. */
    private static Path photoPath(String label) {
        StringBuilder sb = new StringBuilder();
        for (char c : label.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    ? c : '_');
            if (sb.length() >= 64) {
                break;
            }
        }
        String safe = sb.length() == 0 ? "photo" : sb.toString();
        return RuntimeConfig.home().resolve("photos").resolve(safe + ".png");
    }

    private static long lastModified(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Current size on disk, or 0 if it isn't there. Two equal readings mean the write finished. */
    private static long sizeOf(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** How many times the camera may rise looking for a clear view of the subject. */
    private static final int LOS_MAX_RISE = 24;

    /** How far it rises each time. Coarse on purpose: a hill is tens of blocks, not one. */
    private static final double LOS_STEP = 4.0;

    /**
     * Is there a clear line from the lens to the thing being photographed?
     *
     * <p>Clipped on {@code COLLIDER} rather than {@code OUTLINE} so that leaves and grass, which a
     * camera can see through well enough, do not veto an otherwise good angle — while a hillside,
     * which it cannot, does.
     */
    /**
     * The world, as {@link com.moddingfromamod.runtime.ClearView} asks about it.
     *
     * <p>Two questions and nothing else, which is what lets the rule itself be a unit test. Both
     * are the queries this file already made; only their shape is new.
     */
    static com.moddingfromamod.runtime.ClearView.Sight sight(ServerPlayer who) {
        return sight(who.level(), who);
    }

    /**
     * The same adapter without a player, so a GameTest can drive it against a REAL world.
     *
     * <p>{@code ClearViewTest} exercises the geometry through a fake {@code Sight}, which is what
     * makes the rule cheap to test — and leaves this adapter, the half that actually asks
     * Minecraft, unexercised. That is the shape {@code CLAUDE.md} names: <i>a rule tested only
     * where it is DEFINED is not tested where it RUNS.</i>
     *
     * <p>{@code viewer} may be null. ⚠ It is threaded into {@code ClipContext} rather than dropped,
     * because the {@code Entity} overload calls {@code CollisionContext.of(entity)} and NPEs on
     * null — the migrator catalogue's R10, and a real crash in this codebase's history.
     */
    public static com.moddingfromamod.runtime.ClearView.Sight sight(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.entity.Entity viewer) {
        return new com.moddingfromamod.runtime.ClearView.Sight() {
            public boolean solid(double x, double y, double z) {
                return !level.getBlockState(
                        net.minecraft.core.BlockPos.containing(x, y, z)).isAir();
            }

            public boolean clear(double ex, double ey, double ez,
                                 double ax, double ay, double az) {
                return canSee(level, viewer, ex, ey, ez, ax, ay, az);
            }
        };
    }

    private static boolean canSee(net.minecraft.server.level.ServerLevel level,
                                  net.minecraft.world.entity.Entity who,
                                  double ex, double ey, double ez,
                                  double ax, double ay, double az) {
        try {
            var from = new net.minecraft.world.phys.Vec3(ex, ey, ez);
            var to = new net.minecraft.world.phys.Vec3(ax, ay, az);
            var hit = level.clip(new net.minecraft.world.level.ClipContext(
                    from, to,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    who == null
                            ? net.minecraft.world.phys.shapes.CollisionContext.empty()
                            : net.minecraft.world.phys.shapes.CollisionContext.of(who)));
            if (hit == null
                    || hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                return true;
            }
            // Hitting the subject itself IS seeing it: the ray stops on the train's own blocks.
            return hit.getLocation().distanceToSqr(to) < 9.0;
        } catch (Exception e) {
            return true;                    // never let the check itself cost a photograph
        }
    }
}
