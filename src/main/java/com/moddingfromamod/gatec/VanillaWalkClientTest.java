package com.moddingfromamod.gatec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigAnimator;
import com.moddingfromamod.vanilla.RigDriver;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigCarrier;
import com.moddingfromamod.vanilla.RigPose;
import com.moddingfromamod.vanilla.RigTransform;
import com.moddingfromamod.vanilla.AtomLibrary;
import com.moddingfromamod.vanilla.SpikeFixture;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>Gate C for a creature that is actually WALKING.</b>
 *
 * <p>⚰️ <b>ARM A IS GONE, and it was silently killing arm B (fixed 2026-09-23).</b> Arm A was a
 * {@code wish_mob} drawn by the mod renderer, which deleted; since then {@code VanillaRigBridge}
 * cancels every {@code wish_mob} the moment it joins and rigs it instead. So {@code getEntity(mobA)}
 * was null on every tick, and the per-tick walk's {@code if (a == null ...) return;} took ARM B down
 * with it: the rig was never moved, never posed, and the run reported "the creatures barely moved
 * (A 0.00 blocks, B 0.00) -- it is the TEST that is broken, not the rig". It was right. The same
 * trap {@code VanillaCreatureAbClientTest} fell into in an earlier change, and the same fix: the rig is the only
 * subject. The side-by-side notes below describe the film this used to make.
 *
 * <p>Every other measurement of this feature freezes a frame. That is the right instrument for
 * geometry and it cannot answer the question a child asks, which is whether the thing MOVES right.
 * So this one drives both arms from their own motion and films them together.
 *
 * <h2>Side by side, deliberately — the opposite of the A/B fixture's choice</h2>
 *
 * <p>{@code VanillaCreatureAbClientTest} puts both arms in the SAME place one after the other,
 * because a pixel diff between two frames with different backgrounds is 95% scenery. That is right
 * for a number and wrong for a film: a human comparing two clips shot minutes apart has to hold one
 * in their head, and the thing being compared is a rhythm. One camera, both creatures, same tick.
 *
 * <p>The cost is that this produces no pixel delta, and it should not pretend to. Its assertions are
 * about MOTION — did the walk clip get selected, did the phase advance with distance, did the rig
 * actually get rewritten — and the picture is for a person.
 *
 * <h2>The control that makes the film mean anything</h2>
 *
 * <p>A creature standing still and a creature whose animation is broken look identical in a film,
 * and both look like a still photograph. So the run reports the base mob's own
 * {@code walkAnimation} speed and position every second, and FAILS if the creature never walked —
 * otherwise a film of two motionless creatures is a green run with a video attached.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaWalkClientTest {

    private VanillaWalkClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanillawalk".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillawalk"));

    private static final String CASE = System.getProperty(P + "cases", "thornshell");

    /**
     * <b>The A/B seam for the model control</b> — {@code rigsabotage=on} dresses
     * {@link #SABOTAGED} of the rig's displays in a WISH ICON's {@code custom_model_data}, which
     * production never does.
     *
     * <p>It exists so the model control can be watched going RED on the same commit: a control that
     * has only ever been green is not an oracle. The failure it recreates is the one that control was
     * written for — every display resolving a REAL, non-missing, non-carrier model that is not the
     * atom cube, so the creature films as a scatter of wrong shapes while every other check passes.
     * A wish icon rides the SAME carrier item ({@code VanillaCarrier}), so its number resolves a
     * genuine model with a sprite the atom library does not own: exactly that shape, with nothing
     * forged. The previous seam, {@code rigpackselect=on}, recreated it by selecting the pack rig's
     * per-creature pack, and went with the pack rig.
     */
    private static final boolean SABOTAGE =
            "on".equalsIgnoreCase(System.getProperty(P + "rigsabotage", "off"));

    /** How many displays {@link #SABOTAGE} re-dresses: a few, so one flaky frame cannot hide them. */
    private static final int SABOTAGED = 3;

    /** Ticks between pose writes for arm B. The client tweens the gap. */
    private static final int INTERVAL =
            Integer.parseInt(System.getProperty(P + "interval",
                    String.valueOf(RigAnimator.DEFAULT_INTERVAL)));

    /** How long to walk them for, in ticks. 12 s at 20 tps by default. */
    private static final int WALK_TICKS =
            Integer.parseInt(System.getProperty(P + "walkticks", "240"));

    /** Blocks per tick. 0.09 is a brisk vanilla walk and gives a readable leg swing. */
    private static final double SPEED =
            Double.parseDouble(System.getProperty(P + "walkspeed", "0.09"));

    /**
     * The game's tick rate while filming ("slow the server tick so you can re speed up the
     * frames"). A software-rendered client draws ~15 fps; at tick rate 5 the scene runs 4x slower, so a film sped back
     * up 4x shows the motion at ~60 fps. 20 (the default) changes nothing. The client's own ticks follow the server's
     * rate, so the walk, the clip and every display's ease all slow together.
     */
    private static final int FILM_TICK_RATE = Integer.getInteger(P + "filmtickrate", 20);

    /** How far to walk before turning round, in blocks. */
    private static final double LEG = Double.parseDouble(System.getProperty(P + "walkleg", "3"));

    private enum Phase { WAIT_TITLE, WAIT_INGAME, PREPARE, RELOAD, SPAWN, SETTLE, WALK, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    private static final List<String> NOTES = new ArrayList<>();
    private static volatile boolean reloadDone;

    private static final double CAM_X = 8.5;
    private static final double CAM_Y = 97.0;
    private static final double CAM_Z = 8.5;
    private static double stageZ =
            Double.parseDouble(System.getProperty(P + "standoff", "11.0")) + 8.5;
    private static double footY = 95.0;

    /** The rig's lane. (Arm A's lane went with arm A — see the class javadoc.) */
    private static final double LANE_B = CAM_X - Double.parseDouble(
            System.getProperty(P + "lane", "4.5"));

    private static DisplayRig rig;
    private static List<CreatureSculpt.Part> mirrored;
    private static Map<String, BedrockAnimation> clips;
    private static double travelled;
    private static int direction = 1;
    private static long filmStart;
    private static long standStart;
    private static int rigWrites;
    private static int walkFrames;
    private static float peakWalkSpeed;
    private static float lastWalkPos;
    private static String key;
    private static double movedB;
    private static String failLater;
    private static boolean modelChecked;

    /**
     * Worst angle, over the whole walk, between a creature's rendered BODY and the way it is
     * actually travelling -- and separately between arm A's body and arm B's rig. Both are
     * controls for the same class of failure, which is the one this run shipped first time: a
     * film that is correct in every number and wrong to look at.
     */
    private static float worstMoonwalkB;
    private static float worstArmDisagreement;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || phase == Phase.DONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            switch (phase) {
                case WAIT_TITLE -> {
                    if (ticks > ClientHarness.TITLE_SETTLE) {
                        go(Phase.WAIT_INGAME);
                    }
                }
                case WAIT_INGAME -> {
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-walk", ticks, true)) {
                        go(Phase.PREPARE);
                    }
                }
                case PREPARE -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        prepare(mc);
                    }
                }
                case RELOAD -> {
                    if (reloadDone && ticks > 20) {
                        go(Phase.SPAWN);
                    }
                }
                case SPAWN -> spawn(mc);
                case SETTLE -> {
                    if (ticks == 2 && FILM_TICK_RATE != 20) {
                        mc.player.connection.sendCommand("tick rate " + FILM_TICK_RATE);
                        System.out.println("[vanillawalk] tick rate " + FILM_TICK_RATE + " (play the film back x" + (20.0 / FILM_TICK_RATE) + ")");
                    }
                    hold(mc);
                    checkArmBIsWearingItsModels(mc);
                    // A STILL take, filmed as its own window. Z-fighting between a patterned box's
                    // two coincident surfaces is a per-frame trade rather than a displacement, so
                    // it CRAWLS on a creature that is not moving and a single frame cannot see it
                    // at all (§5.9.7). The walk window that follows is the wrong instrument for it:
                    // every pixel changes every frame there anyway. Opened a little into the settle
                    // so the spawn's own last motion is not in it.
                    if (ticks == 10) {
                        standStart = System.currentTimeMillis();
                    }
                    if (ticks > 40) {
                        long now = System.currentTimeMillis();
                        if (standStart != 0L) {
                            filmStart = standStart;
                            writeFilmWindow("stand");
                        }
                        filmStart = now;
                        go(Phase.WALK);
                    }
                }
                case WALK -> walk(mc);
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    // ------------------------------------------------------------------------------- the setup

    private static void prepare(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        var server = mc.getSingleplayerServer();
        if (server == null) {
            finish(false, "no integrated server");
            return;
        }
        // The arm, in the run's own words -- so a film file and a verdict cannot disagree about
        // which backend they are of.
        NOTES.add("arm B = ATOMS (one display per box, " + AtomLibrary.modelName() + ")");
        key = "vwalk_" + RigCarrier.safe(CASE);
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        mirrored = com.moddingfromamod.vanilla.RigFit.intoRigSpace(parts);
        // 🔴 SYNCHRONOUSLY, ON THIS THREAD. The first cut queued these on the server thread and
        // then called reloadResourcePacks() straight after -- so the reload raced the file writes
        // and usually won. It does not fail: the pack loads with no models in it, every display
        // falls back to the plain carrier, and arm B films as a scatter of loose PAPER standing
        // where the creature should be. The verdict was PASS, because every assertion in this test
        // is about MOTION and a sheet of paper moves as well as a creature does.
        try {
            CreatureFiles.write(key, parts, "{}");
            clips = ModelRepository.animations(key);
            NOTES.add("clips: " + clips.keySet() + "; animated bones "
                    + RigPose.animatedBones(clips));
        } catch (Exception e) {
            finish(false, "creature write failed: " + e);
            return;
        }
        NOTES.add(ClientHarness.excludePack(mc, "file/mfam-vanilla-rig"));
        mc.reloadResourcePacks().thenRun(() -> reloadDone = true);
        go(Phase.RELOAD);
    }

    private static void spawn(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        var server = mc.getSingleplayerServer();
        if (server == null) {
            finish(false, "no integrated server");
            return;
        }
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        double scale = SpikeFixture.scale(CASE);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            ServerLevel level = p.level();
            SpeciesRegistry.put(server, Species.fromJson(JsonParser.parseString(
                    "{\"id\":\"" + key + "\",\"modelKey\":\"" + key + "\",\"health\":40,"
                            + "\"speed\":0.0,\"scale\":" + scale + "}").getAsJsonObject()));

            var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
            rig = DisplayRig.spawn(level, new Vec3(LANE_B, footY, stageZ), 90f, parts, scale,
                    CreatureAnatomy.widthBlocks(extents) * scale,
                    CreatureAnatomy.heightBlocks(extents) * scale, null);
            rig.base().setNoGravity(true);
            stopAiMoving(rig.base());
            for (var d : rig.all()) {
                d.setNoGravity(true);
            }
            if (SABOTAGE) {
                int icon = com.moddingfromamod.item.WishIcons.all().get(0).id();
                int n = Math.min(SABOTAGED, rig.displays().size());
                for (int i = 0; i < n; i++) {
                    DisplayRig.wear(rig.displays().get(i), icon, -1);
                }
                NOTES.add("⚠ rigsabotage=on: " + n + " display(s) re-dressed in wish icon "
                        + com.moddingfromamod.item.WishIcons.all().get(0).name() + " (#" + icon
                        + ") -- this run MUST fail on the model control, or that control is blind");
            }
            NOTES.add("the rig = " + rig.displays().size() + " item_display + 1 "
                    + (rig.choice() == null ? "?" : rig.choice().id()) + " at x=" + LANE_B);
        });
        go(Phase.SETTLE);
    }

    /**
     * Leave the AI running — {@code travel()} is where the walk animation comes from — but take away
     * the mob's ability to move itself, so the only thing that displaces it is this test.
     */
    /** Set once the walk starts: from then on {@link #onServerTick} follows and poses the rig. */
    private static volatile boolean driving;
    private static volatile float walkYaw;

    /**
     * Catch the displays up to the carrier and pose them, on the server's own tick after the carrier
     * moved, as {@code VanillaRigAnimator} does on a server. LOCOMOTION is free (no transform);
     * the pose goes on the beat.
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        DisplayRig r = rig;
        if (!ENABLED || !driving || r == null || r.base() == null) {
            return;
        }
        RigDriver.follow(r, r.base().position(), walkYaw);
        rigWrites += RigAnimator.tick(r, mirrored, clips, event.getServer().overworld().getGameTime(), INTERVAL);
        // ...and does every display carry its carrier's half turn? This compared the rig against arm A's
        // body; with arm A gone it compares the displays against their OWN carrier, through the same
        // helper the driver uses -- the half turn applied in `spawn` and missing from `follow` was exactly
        // this disagreement (VANILLA_RIG_PARITY §12).
        for (var d : r.displays()) {
            worstArmDisagreement = Math.max(worstArmDisagreement,
                    Math.abs(Mth.degreesDifference(d.getYRot(), DisplayRig.displayYaw(walkYaw))));
        }
    }

    private static void stopAiMoving(net.minecraft.world.entity.Mob mob) {
        var speed = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.0);
        }
        mob.setTarget(null);
        mob.goalSelector.removeAllGoals(g -> true);
        mob.targetSelector.removeAllGoals(g -> true);
    }

    // -------------------------------------------------------------------------------- the walk

    /**
     * Walk both creatures the same distance, the same way, on the same tick.
     *
     * <p>Neither has AI — a pathfinding creature would arrive at different times and the two clips
     * would drift apart, which reads as an animation bug. They are MOVED, and vanilla's own
     * {@code walkAnimation} picks the phase up from the displacement: arm A's client computes it
     * from what it observes, arm B's server from what it did. Same displacement, same phase, with
     * nothing synced between them.
     */
    private static void walk(Minecraft mc) {
        hold(mc);
        checkArmBIsWearingItsModels(mc);
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        final int tick = ticks;
        server.execute(() -> {
            ServerLevel level = server.getAllLevels().iterator().next();
            if (rig == null) {
                return;
            }
            travelled += SPEED;
            if (travelled > LEG) {
                travelled = 0;
                direction = -direction;
            }
            // 🔴 YAW 90 FACES -X. `Entity.calculateViewVector` is x = sin(-yaw), so yaw 90 looks
            // WEST and yaw 270 looks EAST; the direction of travel here is +X when direction is
            // +1. The first cut paired +X travel with yaw 90 and filmed two creatures MOON-WALKING
            // for eighteen seconds, and the run reported PASS -- distance, clip selection and
            // model resolution are all true of a creature sliding backwards.
            //
            // Vanilla does not correct it, it COMMITS to it: LivingEntity.aiStep aims yBodyRot at
            // the travel direction unless the head is 95-265 degrees away from it, in which case it
            // uses travel-180 on purpose, so a mob looking one way while moving the other renders
            // as walking backwards. Ours was exactly 180 out, so the body locked onto the head.
            float yaw = direction > 0 ? 270f : 90f;

            var base = rig.base();
            base.setYRot(yaw);
            base.setYBodyRot(yaw);
            base.setYHeadRot(yaw);

            // 🔴 SET THE VELOCITY AND LET VANILLA MOVE THEM. Calling move() from here does displace
            // the entity, and walkAnimation stays at ZERO: `calculateEntityAnimation` measures
            // getX() - xo from INSIDE the entity's own tick, and a queued task runs outside it, so
            // by the time the entity ticks its old position is already the new one. Measured --
            // 240 ticks of movement, peak walk speed 0.000, both arms playing IDLE, and a perfectly
            // good film of two creatures standing still.
            //
            // travel() runs for a no-AI mob (setNoAi gates the GOAL selector, not aiStep), so a
            // delta set here is applied inside the tick and the animation state follows for free --
            // on the server for arm B, and on the client, from the movement it observes, for arm A.
            base.setDeltaMovement(SPEED * direction, 0, 0);

            // The displays follow and pose on the SERVER tick (onServerTick), not from here: this
            // task runs once per CLIENT tick, which is not locked to the server's, so a pose beat with no
            // client tick in it was skipped and the rig stood still for six ticks -- a schedule nothing ships.
            walkYaw = yaw;
            driving = true;

            float sp = base.walkAnimation.speed();
            float pos = base.walkAnimation.position();
            peakWalkSpeed = Math.max(peakWalkSpeed, sp);
            if (RigAnimator.selectionFor(RigAnimator.motionOf(base)).clip()
                    == BedrockAnimation.Clip.WALK) {
                walkFrames++;
            }
            if (tick % 20 == 0) {
                NOTES.add(String.format("  t=%3d  x=%.2f  walkAnimation speed=%.3f "
                                + "position=%.2f  clip=%s  rig writes so far=%d",
                        tick, base.getX(), sp, pos,
                        RigAnimator.selectionFor(RigAnimator.motionOf(base)).clip(), rigWrites));
            }
            lastWalkPos = pos;
            movedB = Math.max(movedB, Math.abs(base.getX() - LANE_B));

            // ── IS IT FACING THE WAY IT IS GOING? ──────────────────────────────────────────────
            // The bearing of travel in Minecraft's own convention, taken from the velocity we just
            // applied rather than from the yaw we asked for -- asking the yaw whether it agrees
            // with itself is the tautology this file already fell into once elsewhere.
            float bearing = (float) (Mth.atan2(0.0, SPEED * direction) * (180.0 / Math.PI)) - 90.0f;
            worstMoonwalkB = Math.max(worstMoonwalkB,
                    Math.abs(Mth.degreesDifference(base.yBodyRot, bearing)));
            // (does every display carry its carrier's half turn? is asked in onServerTick, after
            // the follow: here the carrier has just been turned and its displays not yet followed)
        });
        if (ticks > WALK_TICKS) {
            writeFilmWindow("walk");
            score();
        }
    }

    private static void score() {
        List<String> bad = new ArrayList<>();
        if (failLater != null) {
            bad.add(failLater);
        }
        scoreModelControl(bad);
        // The control BEFORE the control: a creature that never moved cannot have a walk animation,
        // and "walkAnimation is zero" would then be a true statement about the wrong thing.
        NOTES.add(String.format("travel: the rig moved %.2f blocks from its lane", movedB));
        if (movedB < 1.0) {
            bad.add("the creature barely moved (" + String.format("%.2f", movedB) + " blocks) -- "
                    + "nothing downstream of this means anything, and it is the TEST that is "
                    + "broken, not the rig");
        }
        // THE CONTROL. Two motionless creatures make a perfectly good film of nothing.
        if (peakWalkSpeed <= com.moddingfromamod.runtime.AnimationState.WALK_THRESHOLD) {
            bad.add("the creatures never walked -- peak walkAnimation speed was " + peakWalkSpeed
                    + ", at or under the " + com.moddingfromamod.runtime.AnimationState.WALK_THRESHOLD
                    + " threshold, so both arms played their IDLE clip and the film shows nothing");
        }
        if (walkFrames < WALK_TICKS / 2) {
            bad.add("the walk clip was selected on only " + walkFrames + " of " + WALK_TICKS
                    + " ticks");
        }
        if (lastWalkPos <= 0.5f) {
            bad.add("the walk phase never advanced (position " + lastWalkPos + ")");
        }
        // The rig must have been driven, and at the SCHEDULED rate -- not every tick.
        int expected = WALK_TICKS / INTERVAL;
        if (rigWrites <= 0) {
            bad.add("the rig was never posed: " + rigWrites + " transform writes");
        }
        // ── THE MOONWALK CONTROLS ─────────────────────────────────────────────────────────────
        // Added after the first film: eighteen seconds of two creatures sliding backwards, and a
        // PASS, because every number the run took was true of a creature facing the wrong way.
        // Distance travelled, clip selection, phase advance and model resolution are all blind to
        // facing; only a person watching noticed. These are the cheapest questions that are not.
        NOTES.add(String.format("facing: worst body-vs-travel %.1f deg | worst display-vs-carrier "
                        + "disagreement %.1f deg", worstMoonwalkB, worstArmDisagreement));
        if (worstMoonwalkB > 5f) {
            bad.add(String.format("the creature MOON-WALKED: worst angle between the rendered body "
                            + "and the direction of travel was %.1f deg. "
                            + "At ~180 the yaw is simply reversed; vanilla's aiStep will not "
                            + "correct it, it commits to it (LivingEntity.aiStep, the 95-265 "
                            + "branch)", worstMoonwalkB));
        }
        if (worstArmDisagreement > 5f) {
            bad.add(String.format("a display faced away from its carrier by up to %.1f deg -- at ~180 "
                            + "that is the half turn being applied on one path and not the other "
                            + "(everything that moves a display must go through "
                            + "DisplayRig.displayYaw)", worstArmDisagreement));
        }
        NOTES.add(String.format("peak walkAnimation speed %.3f | walk clip on %d of %d ticks | "
                        + "final phase %.2f | %d transform writes over %d ticks "
                        + "(~%d per write cycle, interval %d)",
                peakWalkSpeed, walkFrames, WALK_TICKS, lastWalkPos, rigWrites, WALK_TICKS,
                expected == 0 ? 0 : rigWrites / Math.max(1, expected), INTERVAL));
        finish(bad.isEmpty(), String.join("; ", bad));
    }

    /**
     * <b>Is arm B wearing its generated models, or a sheet of paper?</b>
     *
     * <p>This exists because the first film came back as a scatter of loose paper standing where the
     * creature should be, and the run said PASS — every assertion here is about MOTION, and a sheet
     * of paper moves exactly as well as a creature does. The cause was a race: the pack was written
     * on the server thread and the resource reload was called straight after, from the client.
     *
     * <p>A pixel check could not tell the difference either, since a rig of paper and a rig of
     * creature parts both draw something in roughly the right place. Asking the RENDERER which model
     * it resolves for the stack can: a plain carrier and the missing model are both distinguishable
     * from a real one by their quad count.
     *
     * <h2>Two things it did not do, both of which made it unable to fire on the ATOM arm</h2>
     *
     * <ol>
     *   <li><b>The plain control was hard-coded to {@code paper}</b>, which is the carrier only
     *       while {@code rigtint=off}. The atom rig wears a TINTABLE carrier
     *       ({@code leather_horse_armor}), so an override that failed to match would resolve the
     *       carrier's own model — which is not paper's — and this probe would have reported
     *       "a real model". The same fault, in the same words, as the one
     *       {@code VanillaCreatureAbClientTest} fixed in the atom rig's design notes</li>
     *   <li><b>It asked ONE display, ONCE.</b> A film runs for twelve seconds and the interesting
     *       failure is a subset of the rig losing its model mid-walk, which one sample of display
     *       zero at settle time cannot see. On the atom arm "every display wears the library cube"
     *       is the whole control — 62 displays sharing one model is the design — so it is now asked
     *       of every display on EVERY frame, and the worst frame is what is reported.</li>
     * </ol>
     */
    private static void checkArmBIsWearingItsModels(Minecraft mc) {
        if (rig == null || mc.level == null || rig.displays().isEmpty()) {
            return;
        }
        // Asked by SPRITE through compat.ItemModelProbe: 1.21.2 removed
        // ItemRenderer.getModel and the missing BakedModel this compared against, which is why
        // this whole test was dropped from the 1.21.4 build and `vanillawalk` idled there until
        // its timeout. The sprite is the question both targets answer the same way, and it was
        // already this check's discriminator below.
        var carrierItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace(
                        RigCarrier.CARRIER));
        String plain = ItemModelProbe.sprite(
                new net.minecraft.world.item.ItemStack(carrierItem), mc.level, null);

        int seen = 0;
        int bare = 0;
        int gone = 0;
        int wrongCmd = 0;
        int untinted = 0;
        int wrongSprite = 0;
        int patternOverlay = 0;
        for (var e : rig.displays()) {
            var live = mc.level.getEntity(e.getId());
            if (!(live instanceof net.minecraft.world.entity.Display.ItemDisplay id)) {
                continue;
            }
            var st = id.getSlot(0).get();
            if (st.isEmpty()) {
                // Not an error yet -- the item rides its own synced data and arrives a few ticks
                // after the entity. Only NEVER arriving is a failure, and score() decides that.
                continue;
            }
            seen++;
            String spriteName = ItemModelProbe.sprite(st, mc.level, null);
            if (ItemModelProbe.missing(st, mc.level, null)) {
                gone++;
            } else if (spriteName.equals(plain)) {
                bare++;
            }
            // 🔴 IS IT THE ATOM CUBE, or merely SOME real model? Asking "not the carrier and
            // not the missing model" cannot tell them apart, and the difference is a whole
            // creature: a selected per-creature rig pack replaces the carrier's override table
            // with one that runs 1..N and has no entry for 2000, so a vanilla `>=` predicate
            // resolves the pack's LAST creature group for every atom display. Measured — 62
            // displays all wearing `..._shellrim`, filmed as a scatter of slabs, every control
            // green. The SPRITE is the discriminator: the atom cube's is the library's own,
            // a group model's is that creature's generated atlas page.
            var sprite = net.minecraft.resources.Identifier.tryParse(spriteName);
            // ⚠ Phase 2d: a patterned box's SECOND display legitimately wears
            // `rig_atom_<pattern>`, so this asks the LIBRARY rather than the one plain cube.
            // Pinning it to AtomLibrary.SPRITE would report the pack-shadowing failure about a
            // rig that is working. The overlays are counted, not merely tolerated, so the
            // control keeps its teeth: `patternOverlay` is asserted against the plan's own
            // count by score().
            if (sprite == null || !AtomLibrary.isLibrarySprite(sprite)) {
                wrongSprite++;
                lastSprite = spriteName;
            } else if (AtomLibrary.patternSprites(sprite)) {
                patternOverlay++;
            }
            var cmd = st.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
            // claims() rather than modelData(): derived from all(), so it accepts a pattern
            // cube's number for the same reason isLibrarySprite accepts its sprite.
            if (cmd == null || !com.moddingfromamod.vanilla.AtomLibrary.claims(com.moddingfromamod.compat.ModelData.id(cmd))) {
                wrongCmd++;
            }
            if (st.get(net.minecraft.core.component.DataComponents.DYED_COLOR) == null) {
                untinted++;
            }
        }
        if (seen == 0) {
            return;
        }
        modelFrames++;
        mostSeen = Math.max(mostSeen, seen);
        worstBare = Math.max(worstBare, bare);
        worstGone = Math.max(worstGone, gone);
        worstWrongCmd = Math.max(worstWrongCmd, wrongCmd);
        worstUntinted = Math.max(worstUntinted, untinted);
        worstWrongSprite = Math.max(worstWrongSprite, wrongSprite);
        mostPatternOverlay = Math.max(mostPatternOverlay, patternOverlay);
        if (!modelChecked) {
            modelChecked = true;
            NOTES.add("arm B model check (first frame with items): " + seen + " displays, plain "
                    + RigCarrier.CARRIER + " on " + bare + ", missing model on " + gone
                    + ", wrong custom_model_data on " + wrongCmd + " (want "
                    + AtomLibrary.modelData() + " = " + AtomLibrary.modelName() + ")"
                    + ", no dyed_color on " + untinted
                    + ", not wearing an atom sprite on " + wrongSprite
                    + ", pattern overlays " + patternOverlay);
        }
    }

    /** Frames on which the model control could be asked at all (i.e. the items had arrived). */
    private static int modelFrames;
    private static int mostSeen;
    private static int worstBare;
    private static int worstGone;
    private static int worstWrongCmd;
    private static int worstUntinted;
    private static int worstWrongSprite;
    private static int mostPatternOverlay;
    private static String lastSprite = "";

    /** The per-frame control's verdict, folded into {@link #score()}. */
    private static void scoreModelControl(List<String> bad) {
        NOTES.add(String.format("model control: asked on %d frames, most displays carrying an item "
                        + "%d; WORST frame -- plain carrier %d, missing model %d%s",
                modelFrames, mostSeen, worstBare, worstGone,
                String.format(", wrong custom_model_data %d, no dyed_color %d, "
                                + "not wearing %s %d", worstWrongCmd, worstUntinted,
                        AtomLibrary.SPRITE, worstWrongSprite)
                        + ", pattern overlays " + mostPatternOverlay));
        if (!modelChecked) {
            bad.add("arm B's model was never checked -- no display ever carried an item on the "
                    + "client, so nothing here can say whether the film shows a creature or a "
                    + "scatter of paper");
            return;
        }
        if (worstBare > 0) {
            bad.add(worstBare + " of " + mostSeen + " displays rendered the plain carrier ("
                    + RigCarrier.CARRIER + ") on some frame -- the override table did not match, "
                    + "so the film shows carriers where the creature is");
        }
        if (worstGone > 0) {
            bad.add(worstGone + " of " + mostSeen + " displays resolved the MISSING model on some "
                    + "frame");
        }
        if (worstWrongSprite > 0) {
            bad.add(worstWrongSprite + " of " + mostSeen + " displays resolved a model whose sprite "
                    + "is not one of the atom library's (last seen: " + lastSprite + ") -- they are "
                    + "wearing a REAL model that is not the atom cube -- a resource pack shadowing "
                    + "the carrier's override table does exactly this (measured when the pack rig "
                    + "existed: every display resolved its last creature group), and so does a "
                    + "display dressed in the wrong number (rigsabotage=on)");
        }
        if (worstWrongCmd > 0 || worstUntinted > 0) {
            bad.add(worstWrongCmd + " displays wore the wrong custom_model_data and " + worstUntinted
                    + " carried no dyed_color on some frame -- the atom rig is not wearing the "
                    + "library cube, or would render undyed leather brown");
        }
    }

    // ------------------------------------------------------------------------------ the camera

    private static void hold(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        mc.options.hideGui = true;
        mc.gui.setScreen(null);
        mc.player.setPos(CAM_X, CAM_Y, CAM_Z);
        mc.player.setYRot(0f);
        mc.player.setXRot(6f);
        mc.player.xOld = CAM_X;
        mc.player.yOld = CAM_Y;
        mc.player.zOld = CAM_Z;
        mc.player.yRotO = 0f;
        mc.player.xRotO = 6f;
    }

    /** The recorder's contract — see tools/record-client.sh. */
    private static void writeFilmWindow(String label) {
        if (filmStart == 0L) {
            return;
        }
        try {
            Path windows = SIG.resolve("film-windows.tsv");
            Files.createDirectories(windows.getParent());
            Files.writeString(windows,
                    filmStart + "\t" + System.currentTimeMillis() + "\t" + label + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            NOTES.add("film window '" + label + "' " + filmStart + ".."
                    + System.currentTimeMillis());
        } catch (Exception ignored) {
            // no recorder attached; the assertions still stand
        }
        filmStart = 0L;
    }

    private static void go(Phase next) {
        phase = next;
        ticks = 0;
        fired = false;
    }

    private static void finish(boolean pass, String message) {
        ClientHarness.verdict(SIG, pass,
                (pass ? "PASS" : "FAIL — " + message) + "\n" + String.join("\n", NOTES));
        phase = Phase.DONE;
        Minecraft.getInstance().stop();
    }
}
