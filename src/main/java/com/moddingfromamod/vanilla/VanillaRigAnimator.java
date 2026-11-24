package com.moddingfromamod.vanilla;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.AnimationState;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureSculpt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Tier 5 — the live driver, and it is ONE component rather than four clips.</b>
 *
 * <p>The ladder lists idle, walk, attack, run and head-look as separate rows, and building them
 * that way would have been four implementations of the same thing. They are not four clips: they
 * are four <em>time bases</em> feeding one selector that already exists.
 * {@link AnimationState#select} takes {@code (attacking, attackProgress, walkPosition, ageSeconds)}
 * and answers with a clip and a position in it — so what tier 5 actually needed was not animation
 * code at all. It needed something to <b>read those four numbers off a live creature every tick</b>,
 * which nothing in the mod did.
 *
 * <table>
 *   <caption>What drives each clock</caption>
 *   <tr><th>row</th><th>read from the carrier</th></tr>
 *   <tr><td>5.1 idle</td><td>{@code tickCount / 20} — wall time, monotonic forever</td></tr>
 *   <tr><td>5.2 attack</td><td>{@code getAttackAnim(1)} — 0→1 <b>progress</b>, and it RESETS per
 *       swing, so it is neither seconds nor monotonic</td></tr>
 *   <tr><td>5.3 head look</td><td>{@code getYHeadRot() - yBodyRot}, clamped to
 *       {@code getMaxHeadYRot()} ({@link RigHeadYaw}), and {@code getXRot()} — not a
 *       clip at all, and the first pose source that has to COMPOSE with one</td></tr>
 *   <tr><td>5.4 run</td><td>{@code walkAnimation.position()} — the walk clip, advanced faster
 *       because the position advances faster. Free, and that is the whole row</td></tr>
 * </table>
 *
 * <h2>4 Hz, and the interval IS the interpolation</h2>
 *
 * <p>Q2 measured a walk reading as smooth at <b>4 Hz</b> — the client tweens between poses, so the
 * server never sends one per tick, and the cost is 1.3–4.0 KB/s per creature per viewer. The
 * tween's length is derived from the gap between poses ({@link RigAnimator#ease}: the gap plus a
 * tick of slack), so {@link #INTERVAL} is the one number rather than written down twice: <b>a duration and a schedule that can disagree is
 * {@code docs/CAMERA.md}'s train-axis literal in another costume.</b>
 *
 * <h2>Death takes over completely</h2>
 *
 * <p>While {@code deathTime} is non-zero the clip driver stands down and {@link RigDriver#dying}
 * owns the rig. Vanilla's own condition is {@code hurtTime > 0 || deathTime > 0} for the overlay
 * and {@code deathTime} for the flop, so a HURT creature only flashes and keeps its clip (
 * it used to freeze for the flash), while running a walk cycle underneath a body that is falling
 * over would fight it — the flop is a
 * whole-body modifier (M2) applied on top of the clip pose, so both writing at 4 Hz means the last
 * writer wins and the creature twitches.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class VanillaRigAnimator {

    private VanillaRigAnimator() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-rig-animator");

    /**
     * Ticks between poses — <b>{@link RigAnimator#DEFAULT_INTERVAL}, not a number of our own.</b>
     *
     * <p>This class briefly carried its own 5, which is the exact failure {@code RigAnimator}'s
     * javadoc warns about one level up: <i>"two rules that must agree are two rules that will
     * not"</i>. The schedule and the tween duration are one number, and so is the schedule and the
     * schedule.
     */
    public static final int INTERVAL = RigAnimator.DEFAULT_INTERVAL;

    /**
     * Everything the driver needs about one live creature — <b>mutable, because of the swing.</b>
     */
    private static final class Live {
        final DisplayRig rig;
        final List<CreatureSculpt.Part> mirrored;
        final Map<String, BedrockAnimation> clips;
        /** Ticks into the current swing, or -1 for "not swinging". See {@link #SWING_TICKS}. */
        int swing = -1;
        /** How many swings have started (a native creature replays its attack clip on each). */
        int swings;
        /** The swing vanilla last reported ({@code getCurrentSwing()}); a new object is a new swing. */
        Object lastSwing;
        /** The game tick the talking flag was first seen on ({@link RigTalk}), or -1. */
        long talkSince = -1;
        /** The film script's last applied look (rigstate=showcase), or null. */
        RigStateScript.Look scriptLook;
        /** what fly, swim, hop, sit and sleep are drawn from, and the state machine
         *  that weights them. Null for a creature the generator cannot read. */
        final RigLocomotion.Plan plan;
        final RigGait gait = new RigGait();
        /** The body's drawn turn: damped, with a threshold ({@link RigTurn}). */
        final RigTurn turn = new RigTurn();
        /** A flier's heading, from where it has travelled ({@link RigCourse}). */
        final RigCourse course = new RigCourse();
        /** The head yaw last drawn, kept while a look is too steep to have a direction. */
        float headYaw;
        /** Was its last look too steep to have a direction, and when was that seen. */
        boolean steep;
        int steepSeen;
        /** Where the carrier stood last tick, to tell a teleport from a walk. */
        double lastX = Double.NaN, lastZ = Double.NaN;
        com.moddingfromamod.mob.PetBehaviour pet = com.moddingfromamod.mob.PetBehaviour.NONE;
        /**
         * Which {@code SpeciesRegistry} revision this rig's GEOMETRY was built from.
         *
         * <p>The legacy path has the same field on the entity ({@code WishMobEntity
         * .appliedRevision}) and re-applies on tick when the counter moves — which is what makes
         * "make him bigger" work on a creature already standing in the world. A rig's geometry is
         * BAKED into a resource pack and into every display's transform, so it cannot re-apply; it
         * has to be rebuilt. Without this field a second wish on an existing creature changed the
         * models in the pack and left every display posed at the old creature's proportions, which
         * is worse than doing nothing.
         */
        int builtAtRevision = com.moddingfromamod.species.SpeciesRegistry.revision();
        /**
         * The species record this rig was built from, or null if it could not be resolved.
         *
         * <p>The revision counter is GLOBAL — it moves when any species anywhere is edited — so it
         * says "something changed", never "this creature changed". Rebuilding on the counter alone
         * would rebuild every rig in the world, each with its own resource reload and its own
         * visible flicker, every time a child wished for anything. {@code Species} is a record of
         * value types, so comparing the record itself is exact and costs nothing.
         */
        com.moddingfromamod.species.Species builtFrom;
        /**
         * The {@link RigGeometryPrint} of the geometry this rig was built from, or null if the
         * species could not be resolved. Held PER RIG, which is what makes the re-wish signal
         * impossible to consume: each rig compares its own print against the species' current one,
         * so two Spikors from two eggs both see a re-sculpt, and neither can hide it from the other.
         */
        String builtGeometry;

        Live(DisplayRig rig, List<CreatureSculpt.Part> mirrored,
             Map<String, BedrockAnimation> clips) {
            this.rig = rig;
            this.mirrored = mirrored;
            this.clips = clips;
            this.builtFrom = rig.base() == null ? null : speciesOf(rig.base());
            this.builtGeometry = geometryOf(this.builtFrom);
            this.plan = planFor(mirrored);
        }
    }

    /**
     * How long a swing lasts, in ticks — vanilla's own default from
     * {@code LivingEntity.getCurrentSwingDuration()} with no Haste, which is {@code protected} and
     * so cannot be asked from here.
     */
    public static final int SWING_TICKS = 6;

    /**
     * The locomotion plan for a creature, or null — a malformed sculpt must still stand and walk,
     * so a failure to read its anatomy costs it the new layers and nothing else.
     */
    static RigLocomotion.Plan planFor(List<CreatureSculpt.Part> mirrored) {
        if (mirrored == null || mirrored.isEmpty()) {
            return null;
        }
        try {
            return RigLocomotion.plan(RigAnatomy.of(mirrored));
        } catch (RuntimeException e) {
            LOG.warn("could not read a locomotion plan off a sculpt of {} parts -- it will walk but "
                    + "not fly, swim, hop or sit", mirrored.size(), e);
            return null;
        }
    }

    /** The A/B seam the films use: {@code -Dmoddingfromamod.locomotion=off} draws the old rig. */
    static final boolean LOCOMOTION = !"off".equals(System.getProperty("moddingfromamod.locomotion"));

    /** A creature that follows and is this far from the child is called, and gets up. */
    static final double CALLED_BLOCKS = 8.0;

    /** Read the carrier into the gait's terms. */
    private static RigGait.Sense sense(Live live, Mob base) {
        var sp = live.builtFrom;
        if (base.tickCount % 10 == 0 || live.pet == com.moddingfromamod.mob.PetBehaviour.NONE) {
            live.pet = com.moddingfromamod.mob.PetBehaviours.of(base);
        }
        boolean staying = live.pet.stays();
        boolean follows = Boolean.TRUE.equals(live.pet.follow());
        boolean called = false;
        if (follows) {
            var near = base.level().getNearestPlayer(base, 64);
            called = near == null || near.distanceTo(base) > CALLED_BLOCKS;
        }
        long t = Math.floorMod(com.moddingfromamod.mv.DayTime.get(base.level()), 24000L);
        boolean night = base.level().dimensionType().hasSkyLight() && t >= 13000 && t < 23000;
        boolean wants = base.walkAnimation.speed() > AnimationState.WALK_THRESHOLD
                || !base.getNavigation().isDone();
        var v = base.getDeltaMovement();
        return new RigGait.Sense(base.onGround(), base.isInWater(), base.isUnderWater(),
                RigPowers.flies(base), live.plan != null && !live.plan.anatomy().wings().isEmpty(),
                sp != null && sp.has(com.moddingfromamod.species.Species.Power.HOPS),
                sp != null && sp.has(com.moddingfromamod.species.Species.Power.NAPS),
                staying, wants, night, base.hurtTime > 0, called, v.x, v.y, v.z);
    }

    /** Carry out what the gait asked of the carrier. */
    private static void act(RigGait.Action a, Mob base, RigGait.Sense s) {
        switch (a) {
            case HOLD -> {
                if (s.naps() && !s.hops()) {
                    base.getNavigation().stop();   // asleep: its own wander must not wake it
                }
                var v = base.getDeltaMovement();
                base.setDeltaMovement(0, v.y, 0);
            }
            case LAUNCH -> {
                // Towards where it is GOING -- the path's next node -- not where its body happens to
                // point: a body still turning, or one a wall turned, leaps into that wall and the
                // path gives up (measured in GateC:vanillalocomotion: 10.3 blocks one run, 0 the next).
                float facing = base.getYRot();
                var path = base.getNavigation().getPath();
                if (path != null && !path.isDone()) {
                    Vec3 next = path.getNextEntityPos(base);
                    double dx = next.x - base.getX();
                    double dz = next.z - base.getZ();
                    if (dx * dx + dz * dz > 0.01) {
                        facing = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        base.setYRot(facing);
                        base.setYBodyRot(facing);
                    }
                }
                double yaw = Math.toRadians(facing);
                double speed = base.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
                double h = Math.max(0.12, Math.min(0.35, speed * 1.4));
                base.setDeltaMovement(-Math.sin(yaw) * h, HOP_VY, Math.cos(yaw) * h);
                base.needsSync = true;
            }
            default -> {
            }
        }
    }

    /** A hop's take-off speed: vanilla's own jump, ~1.25 blocks high. */
    static final double HOP_VY = 0.42;

    private static final Map<UUID, Live> LIVE = new ConcurrentHashMap<>();

    /**
     * <b>The print of the geometry a species would be rigged from RIGHT NOW</b> — the same parts,
     * scale and clips {@link #rebuildIfRewished} would spawn with, so a rig built from them and a
     * check made against them can only disagree when the species really changed.
     *
     * <p>🔴 <b>This replaced the pack's byte-compare (blocker 1), and a global counter with
     * it.</b> The byte-compare was only true ONCE — the first rig of a species to republish saw
     * {@code changed}, and its twin then saw identical bytes — so it had to be turned into a
     * per-species generation counter ({@code GEOMETRY_GEN}) the moment it was observed. A print
     * held on each rig needs neither: it is a pure function of the species, so every rig asks the
     * same question and gets its own answer. It also needs no file, which is what let the atom arm
     * stop writing a per-creature pack it delivered to nobody.
     */
    static String geometryOf(com.moddingfromamod.species.Species sp) {
        if (sp == null) {
            return null;
        }
        List<CreatureSculpt.Part> sculpted = sp.hasCustomModel()
                ? com.moddingfromamod.runtime.ModelRepository.drawnParts(sp.modelKey())
                : List.of();
        List<CreatureSculpt.Part> parts = StandInSculpt.manifest(sp, sculpted);
        var clips = StandInSculpt.isStandIn(sculpted)
                ? java.util.Map.<String, BedrockAnimation>of()
                : com.moddingfromamod.runtime.ModelRepository.animations(sp.modelKey());
        return RigGeometryPrint.of(parts, sp.scale(), RigPose.animatedBones(clips));
    }

    /**
     * Start driving a rig.
     *
     * @param mirrored the sculpt in the SAME space the groups are in — {@link RigPose#compose}
     *                 composes against it, so handing it the raw sculpt animates a mirror image
     */
    public static void drive(DisplayRig rig, List<CreatureSculpt.Part> mirrored,
                             Map<String, BedrockAnimation> clips) {
        LIVE.put(rig.base().getUUID(), new Live(rig, mirrored, clips));
    }

    /**
     * What a rig was built from, captured when its rebuild BEGAN. A transformation hands
     * the new body to {@link #drive} two seconds later; reading the species then would record an
     * edit made during those two seconds as already built, and it would never be applied.
     */
    record Built(com.moddingfromamod.species.Species from, String geometry, int revision) {
    }

    static void drive(DisplayRig rig, List<CreatureSculpt.Part> mirrored,
                      Map<String, BedrockAnimation> clips, Built built) {
        Live l = new Live(rig, mirrored, clips);
        l.builtFrom = built.from();
        l.builtGeometry = built.geometry();
        l.builtAtRevision = built.revision();
        LIVE.put(rig.base().getUUID(), l);
    }

    /** Stop driving one. Called by the reaper; also safe to call for a creature never driven. */
    public static void forget(UUID carrier) {
        LIVE.remove(carrier);
        RigInstincts.forget(carrier);
        RigPowers.forget(carrier);
        RigVoice.forget(carrier);
        RigAbilities.forget(carrier);
        RigFacing.forget(carrier);
        RigGesture.forget(carrier);
    }

    /**
     * The species record this carrier's rig was last built or reshaped from, or null when it is not
     * driven. For a test: "the re-wish reached it" is this, now that a reshape in place keeps the
     * carrier's UUID.
     */
    public static com.moddingfromamod.species.Species builtFrom(UUID carrier) {
        Live l = LIVE.get(carrier);
        return l == null ? null : l.builtFrom;
    }

    /** How many rigs are being driven — for a test, and for a leak to be visible. */
    /**
     * A live rig as a reader sees it: the rig, the parts it draws in its own space, its clips, its locomotion
     * plan (null when the generator cannot read it). The Bedrock creature packs bake a creature from exactly this, so a
     * native Bedrock creature stands where its atoms stood.
     */
    public record LiveView(DisplayRig rig, List<CreatureSculpt.Part> rigSpaceParts,
                           Map<String, BedrockAnimation> clips, RigLocomotion.Plan plan, String builtGeometry) {
    }

    /** What a rig of this species would be built from, with no rig: the parts in rig space, clips, plan and scale. */
    public record SpeciesInputs(List<CreatureSculpt.Part> rigSpaceParts, Map<String, BedrockAnimation> clips,
                                RigLocomotion.Plan plan, double scale) {
    }

    /**
     * the inputs {@link #adoptAll} and a spawn build a rig of {@code sp} from, the same calls in the same order,
     * so something baked from them ahead of time (a Bedrock creature pack) is the creature a later rig will draw.
     */
    public static SpeciesInputs inputsFor(com.moddingfromamod.species.Species sp) {
        List<CreatureSculpt.Part> sculpted = sp.hasCustomModel()
                ? com.moddingfromamod.runtime.ModelRepository.drawnParts(sp.modelKey())
                : List.of();
        List<CreatureSculpt.Part> parts = StandInSculpt.manifest(sp, sculpted);
        Map<String, BedrockAnimation> clips = StandInSculpt.isStandIn(sculpted)
                ? Map.of()
                : com.moddingfromamod.runtime.ModelRepository.animations(sp.modelKey());
        List<CreatureSculpt.Part> rigSpace = RigFit.intoRigSpace(parts);
        return new SpeciesInputs(rigSpace, clips, planFor(rigSpace), sp.scale());
    }

    /** Every rig being driven, read-only. */
    public static List<LiveView> liveViews() {
        List<LiveView> out = new java.util.ArrayList<>(LIVE.size());
        for (Live l : LIVE.values()) {
            out.add(new LiveView(l.rig, l.mirrored, l.clips, l.plan, l.builtGeometry));
        }
        return out;
    }

    public static int driving() {
        return LIVE.size();
    }

    // There is deliberately NO clearForTest(). One existed, and it is the reason two tier-5 tests
    // failed against each other rather than against the code: GameTests in a batch run
    // concurrently and share every static, so emptying this map deletes whatever the sibling test
    // is in the middle of driving. Both reported "nothing is driving the rig" while the log showed
    // the driver starting perfectly for both. Scope a test to its own carrier with `forget(uuid)`.

    /**
     * <b>Re-adopt every rig standing in a level that nothing is driving.</b>
     *
     * <p>The driver's map is memory; the creatures are the world. A server restart therefore leaves
     * every rigged creature a statue — correct, complete, and animated by nobody — with no error
     * anywhere, because nothing failed. This is the sweep that fixes it, and it runs on level load
     * rather than being something an operator has to know about.
     *
     * <p>Idempotent: a carrier already being driven is skipped, so calling it twice costs nothing
     * and a mid-session call cannot restart a creature's clock.
     *
     * @return how many rigs were adopted
     */
    public static int adoptAll(ServerLevel level) {
        int adopted = 0;
        // A SNAPSHOT, because a rig that cannot be adopted is rebuilt below, which adds and
        // removes entities while this walks the level's own list.
        List<Entity> all = new java.util.ArrayList<>();
        level.getEntities().getAll().forEach(all::add);
        for (Entity e : all) {
            if (e instanceof Mob carrier && adoptOne(level, carrier, true) == Adopt.DONE) {
                adopted++;
            }
        }
        if (adopted > 0) {
            LOG.info("adopted {} orphaned rig(s) in {}", adopted, level.dimension().identifier());
        }
        return adopted;
    }

    /** What {@link #adoptOne} did. */
    enum Adopt {
        /** Driven now: adopted as saved, or rebuilt. */
        DONE,
        /** Its saved displays do not match (yet) and it was told not to rebuild: ask again later. */
        WAIT,
        /** Nothing to do: not a rig carrier, gone, already driven, or its species is unknown. */
        SKIP
    }

    /**
     * Put one carrier back under the driver.
     *
     * @param rebuild what to do when its saved displays do not match the atom plan: rebuild it
     *                (the answer once nothing more is going to load), or {@link Adopt#WAIT} (the
     *                answer while the chunk it came back with may still be delivering them)
     */
    static Adopt adoptOne(ServerLevel level, Mob carrier, boolean rebuild) {
        if (!carrier.isAlive()
                || !carrier.entityTags().contains(DisplayRig.RIG_TAG)
                || LIVE.containsKey(carrier.getUUID())
                // A body a TRANSFORMATION holds — the old one going white, or the new one hung
                // TINY before the swap — is undriven on purpose, not orphaned. Adopting it
                // "rebuilt on load" a second creature beside the one being transformed into, so a
                // re-wish left four rigs where there were two (an adoptAll sweep landing
                // mid-transformation).
                || RigTransformation.transforming(carrier.getUUID())) {
            return Adopt.SKIP;
        }
        // 🔴 WHICH species is this? The ability tag is the only link, and it is LOSSY:
        // `WishMobTags.forSpecies` strips colour codes and punctuation, so
        // "§6SPIKOR, King of the Kroaks" becomes "wishmob_spikor_king_of_the_kroaks" and
        // cannot be inverted. So do not invert it — ask each registered species whether the
        // carrier wears ITS tag, through the very same function. That cannot drift from the
        // spawn side by construction, where a hand-written inverse would drift the first time
        // the sanitiser changed.
        com.moddingfromamod.species.Species sp = speciesOf(carrier);
        if (sp == null) {
            return Adopt.SKIP;
        }
        // ⚠ THE SAME PAIR OF GUARDS THE BRIDGE USED TO HAVE, and they were three copies of
        // one rule. A rig built from a STAND-IN has no sculpt and no model key,
        // so these two `continue`s would have left it adopted by nobody and frozen where it
        // stood -- the bridge would manifest a creature the animator could never drive.
        // `StandInSculpt.manifest` is now the one answer to "what parts is this creature".
        List<CreatureSculpt.Part> sculpted = sp.hasCustomModel()
                ? com.moddingfromamod.runtime.ModelRepository.drawnParts(sp.modelKey())
                : List.of();
        List<CreatureSculpt.Part> parts = StandInSculpt.manifest(sp, sculpted);
        var clips = StandInSculpt.isStandIn(sculpted)
                ? java.util.Map.<String, com.moddingfromamod.runtime.BedrockAnimation>of()
                : com.moddingfromamod.runtime.ModelRepository.animations(sp.modelKey());
        DisplayRig rig = DisplayRig.adopt(level, carrier, parts, sp.scale(),
                CarrierPool.movementOf(sp, parts));
        if (rig == null && !rebuild) {
            return Adopt.WAIT;
        }
        if (rig == null) {
            // 🔴, blocker 5: A RIG THAT CANNOT BE ADOPTED IS REBUILT, NOT LEFT A STATUE.
            //
            // The common case is a creature saved by the pack rig -- the shipped default until
            // 2026-09-13, and deleted. Its displays carry no index tags and wear a
            // per-creature custom_model_data no client has a model for any more, so it stood
            // frozen as a heap of paper cards for ever, with one WARN line to say so. The
            // decision rule's answer is MIGRATE ("we do hold it -- a world we own -- and we can
            // rewrite it"): the species still knows the creature, so reap the old displays and
            // manifest it again as atoms, carrying its health, effects and name. Nothing here
            // reads the old shape; the migration needs only the carrier's tags, which are
            // arm-independent. The same answer is right for any other mismatch (a re-sculpt the
            // world never saw): a statue driven by nobody helps no child.
            respawn(level, carrier, sp, parts, sculpted,
                    "on load -- its saved displays did not match the atom plan (a pack-rig "
                            + "save, #582, or a re-sculpt this world never saw), so it was "
                            + "rebuilt rather than left a statue");
            return Adopt.DONE;
        }
        drive(rig, RigFit.intoRigSpace(parts), clips);
        // Its state came back with the save; what its species asks it to DO is read live and
        // only needs looking after again.
        com.moddingfromamod.mob.PetBehaviours.track(carrier);
        return Adopt.DONE;
    }

    /**
     * Which species is this carrier wearing?
     *
     * <p>🔴 The ability tag is the only link, and it is LOSSY:
     * {@code WishMobTags.forSpecies} strips colour codes and punctuation, so
     * "§6SPIKOR, King of the Kroaks" becomes "wishmob_spikor_king_of_the_kroaks" and cannot be
     * inverted. So do not invert it — ask each registered species whether the carrier wears ITS
     * tag, through the very same function. That cannot drift from the spawn side by construction,
     * where a hand-written inverse would drift the first time the sanitiser changed.
     */
    static com.moddingfromamod.species.Species speciesOf(Mob carrier) {
        // ONE implementation, and it lives next to the write (DisplayRig:527). It was duplicated
        // here first; the moment a second reader needed it (an earlier issue's modify_mob) two copies of an
        // inverse that must not drift was one too many.
        return DisplayRig.speciesOn(carrier);
    }

    /**
     * <b>A creature that has been re-wished is REBUILT, because a rig cannot re-apply.</b>
     *
     * <p>This is the rig's answer to the thing the legacy path gets for free. `sculpt_creature`
     * tells the agent <i>"every one already in the world updates"</i>, and on the legacy path that
     * is true: {@code WishMobEntity} compares {@code SpeciesRegistry.revision()} on tick and calls
     * {@code applySpecies} again. A rig has its geometry baked into the number, colour and
     * transform of every display, so there is nothing to re-apply — a re-sculpt can change how many
     * boxes the creature has.
     *
     * <p>So it is reaped and re-manifested, carrying its live health and effects exactly as the
     * bridge does for the first manifestation — a refinement must not heal the creature either.
     *
     * @return true when this rig was rebuilt and the caller must stop using its {@code Live}
     */
    /** One design's parts and geometry print, as read at one registry revision. */
    record SpeciesBuild(int revision, com.moddingfromamod.species.Species sp, List<CreatureSculpt.Part> sculpted,
                        List<CreatureSculpt.Part> parts, String geometry) {
    }

    /** Species id → its build at the revision it was read. Server thread. */
    private static final Map<String, SpeciesBuild> BUILDS = new java.util.HashMap<>();

    /** How many times a design was actually read from disk — for the perf test. */
    static int buildReads;

    /** This design's parts and print at {@code revision}: read once, then shared. */
    static SpeciesBuild speciesBuild(com.moddingfromamod.species.Species sp, int revision) {
        SpeciesBuild b = BUILDS.get(sp.id());
        if (b != null && b.revision() == revision && b.sp().equals(sp)) {
            return b;
        }
        buildReads++;
        List<CreatureSculpt.Part> sculpted = sp.hasCustomModel()
                ? com.moddingfromamod.runtime.ModelRepository.drawnParts(sp.modelKey())
                : List.of();
        List<CreatureSculpt.Part> parts = StandInSculpt.manifest(sp, sculpted);
        var clips = StandInSculpt.isStandIn(sculpted)
                ? java.util.Map.<String, BedrockAnimation>of()
                : com.moddingfromamod.runtime.ModelRepository.animations(sp.modelKey());
        b = new SpeciesBuild(revision, sp, List.copyOf(sculpted), List.copyOf(parts),
                RigGeometryPrint.of(parts, sp.scale(), RigPose.animatedBones(clips)));
        BUILDS.put(sp.id(), b);
        return b;
    }

    private static boolean rebuildIfRewished(UUID key, Live live, Mob base) {
        int now = com.moddingfromamod.species.SpeciesRegistry.revision();
        if (now == live.builtAtRevision) {
            return false;
        }
        // Claim the new revision FIRST. A rebuild that fails for any reason must not be retried
        // every tick for the rest of the session.
        live.builtAtRevision = now;

        var sp = speciesOf(base);
        if (sp == null || !(base.level() instanceof ServerLevel level)) {
            return false;
        }
        // The third copy of the same pair. It is the one that mattered most: with it
        // in place a stand-in rig could never be REBUILT, so "make the robot bigger" moved the
        // species record and left the creature in the world exactly as it was -- which is the
        // failure `PersistentEditGameTest` reports as "the creature already in the world must grow
        // on its own, was 1.0".
        // ONCE PER DESIGN PER REVISION (2026-10-31): every bump made EVERY live creature re-read its
        // sculpt twice and its clips from disk in one tick (~200 reads for 100 creatures). Within
        // one revision a design reads the same, so the creatures of a design share one read.
        SpeciesBuild design = speciesBuild(sp, now);
        List<CreatureSculpt.Part> sculpted = new java.util.ArrayList<>(design.sculpted());
        List<CreatureSculpt.Part> parts = new java.util.ArrayList<>(design.parts());
        // TWO things can make this creature different, and neither implies the other.
        //
        //   the SPECIES record  -- scale, health, name, powers. A value-typed record, so ==.
        //   the GEOMETRY        -- the sculpt's parts, which the record does not hold: it holds a
        //                          modelKey. `sculpt_creature` with the same id and different parts
        //                          leaves the record byte-identical.
        //
        // So the geometry answer is RigGeometryPrint, compared against the print this rig was
        // built from. It encodes every float by value -- CreatureSculpt.Part holds float[], whose
        // record equality is reference equality, a comparison that would silently always say
        // "different" -- and it needs no file (it replaced the pack's byte-compare).
        String geometryNow = design.geometry();
        if (java.util.Objects.equals(geometryNow, live.builtGeometry)
                && sp.equals(live.builtFrom)) {
            // The counter moved for some OTHER creature. Rebuilding on a global bump would rebuild
            // every rig in the world every time any species anywhere was edited, which is a
            // visible flicker for creatures nobody touched.
            return false;
        }
        // Record the new geometry for the reveal, which is what veils the rebuilt creature from a
        // stock child until it is revealed.
        RigReveal.publish(level.getServer(), sp.id(), parts, sp.scale(), true);

        // ON THE CARRIER IT ALREADY HAS when a fresh spawn would pick the same one: no
        // blink, the same UUID, and nothing to carry across by hand.
        DisplayRig reshaped;
        try {
            reshaped = DisplayRig.reshapeInPlace(level, live.rig, parts, sp.scale(), sp.id(),
                    CarrierPool.movementOf(sp, parts));
        } catch (RuntimeException e) {
            // Halfway through, the old displays may already be gone: never leave an invisible,
            // solid carrier with nothing on it (an earlier issue's review). The respawn below reaps whatever
            // is left by its owner tag and builds the creature fresh.
            LOG.warn("in-place reshape of '{}' failed; rebuilding it instead", sp.id(), e);
            reshaped = null;
        }
        if (reshaped != null) {
            // The species' stats, as respawn carries them: an edit can change health or damage,
            // and must not heal him either (RigVitals keeps his health in proportion).
            DisplayRig.applyVitals(base, RigVitals.carrying(sp.health(), sp.damage(), sp.armor(),
                    sp.knockbackResistance(), sp.speed(), base.getHealth(), base.getMaxHealth()));
            // And everything the species decides, as respawn's CarrierState.putOn does on a new
            // carrier: its fire resistance, its glow (the child's own glow kept, a glow the old
            // species gave dropped if the new one has none), and its instincts, re-derived from
            // the edited species (a coward re-wished into a hunter must hunt).
            boolean speciesGlowed = live.builtFrom != null
                    && live.builtFrom.has(com.moddingfromamod.species.Species.Power.GLOWING);
            SpeciesOnRig.applyAtBirth(base, sp, base.hasGlowingTag() && !speciesGlowed);
            RigInstincts.forget(base.getUUID());
            var clips = StandInSculpt.isStandIn(sculpted)
                    ? java.util.Map.<String, com.moddingfromamod.runtime.BedrockAnimation>of()
                    : com.moddingfromamod.runtime.ModelRepository.animations(sp.modelKey());
            var pet = live.pet;
            if (!reshaped.leaving().isEmpty()) {
                // A DIFFERENT PART COUNT: the old set fades out through white while the new
                // one grows out of it; the animator takes the carrier back at the swap.
                Built built = new Built(sp, geometryNow, now);
                LIVE.remove(base.getUUID());
                RigTransformation.sameCarrier(level, live.rig, reshaped, rig -> {
                    drive(rig, RigFit.intoRigSpace(parts), clips, built);
                    Live back = LIVE.get(base.getUUID());
                    if (back != null) {
                        back.pet = pet;
                    }
                });
                LOG.info("reshaping '{}' on the same carrier at revision {} -- {} displays, transforming "
                        + "from {}", sp.id(), now, reshaped.displays().size(), reshaped.leaving().size());
                return true;
            }
            drive(reshaped, RigFit.intoRigSpace(parts), clips);
            Live now2 = LIVE.get(base.getUUID());
            if (now2 != null) {
                now2.pet = pet;
            }
            LOG.info("reshaped the rig for '{}' in place at revision {} -- same carrier, {} displays",
                    sp.id(), now, reshaped.displays().size());
            return true;
        }
        transformInto(level, live, base, sp, parts, sculpted, new Built(sp, geometryNow, now));
        return true;
    }

    /**
     * <b>The carrier must change, so the creature TRANSFORMS into the new one</b>: the old
     * body whitens and shrinks away while the new carrier's body grows out of white and colours in
     * — {@link RigTransformation}. Everything {@link #respawn} carries across by hand is carried
     * the same way; it simply happens at the swap instead of in one tick.
     */
    private static void transformInto(ServerLevel level, Live live, Mob base,
                                      com.moddingfromamod.species.Species sp,
                                      List<CreatureSculpt.Part> parts,
                                      List<CreatureSculpt.Part> sculpted, Built built) {
        var at = base.position();
        float yaw = base.getYRot();
        UUID oldId = base.getUUID();
        RigVitals vitals = RigVitals.carrying(sp.health(), sp.damage(), sp.armor(),
                sp.knockbackResistance(), sp.speed(), base.getHealth(), base.getMaxHealth());
        // read BEFORE the transformation freezes it: its own NoAI and invulnerability are the ones
        // the new carrier must have (CarrierState)
        CarrierState animal = CarrierState.of(base);
        var clips = StandInSculpt.isStandIn(sculpted)
                ? java.util.Map.<String, com.moddingfromamod.runtime.BedrockAnimation>of()
                : com.moddingfromamod.runtime.ModelRepository.animations(sp.modelKey());
        LIVE.remove(oldId);
        RigInstincts.forget(oldId);
        RigPowers.forget(oldId);
        RigVoice.forget(oldId);
        RigAbilities.forget(oldId);
        RigTransformation.newCarrier(level, live.rig, () -> {
            try {
                DisplayRig rebuilt = DisplayRig.spawn(level, at, yaw, parts, sp.scale(), sp.id(),
                        CarrierPool.movementOf(sp, parts));
                DisplayRig.applyVitals(rebuilt.living(), vitals);
                animal.putOn(level, rebuilt.base(), sp);
                return rebuilt;
            } catch (Exception e) {
                LOG.error("could not build the new body for '{}' -- the old one goes and nothing "
                        + "replaced it", sp.id(), e);
                return null;
            }
        }, rebuilt -> {
            // The same creature in a new body, not a new creature: it keeps its birth (WishShimmer),
            // and every handle naming the old carrier now names this one.
            WishShimmer.rebuilt(oldId, rebuilt.living().getUUID());
            com.moddingfromamod.mob.EntityHandles.global().repoint(oldId, rebuilt.living().getUUID(),
                    com.moddingfromamod.mob.Creatures.kindId(rebuilt.living()));
            drive(rebuilt, RigFit.intoRigSpace(parts), clips, built);
            LOG.info("rebuilt the rig for '{}' at revision {} -- a new carrier, transformed into",
                    sp.id(), built.revision());
        }, () -> LIVE.put(oldId, live));   // the old creature, driven again (its revision stays claimed)
    }

    /**
     * <b>Reap a creature's rig and manifest it again from its species</b>, carrying its live health,
     * effects and name — the one rebuild, shared by a re-wish and by a rig that cannot be adopted.
     *
     * @param sculpted the species' own sculpt (empty for a stand-in), which decides whether it has
     *                 clips of its own
     * @param why      for the log line, so the two callers stay distinguishable in a server log
     */
    private static void respawn(ServerLevel level, Mob base,
                                com.moddingfromamod.species.Species sp,
                                List<CreatureSculpt.Part> parts,
                                List<CreatureSculpt.Part> sculpted, String why) {
        var at = base.position();
        float yaw = base.getYRot();
        RigVitals vitals = RigVitals.carrying(sp.health(), sp.damage(), sp.armor(),
                sp.knockbackResistance(), sp.speed(), base.getHealth(), base.getMaxHealth());
        // EVERYTHING ABOUT THIS ONE ANIMAL, read in one place. The rebuilt carrier
        // is a new entity: its name, glow and team were carried here by hand, and its pet
        // behaviour, NoAI, invulnerability and a datapack's tags were not -- so "follow me" then
        // "make him bigger" was a dragon that stopped following.
        CarrierState animal = CarrierState.of(base);

        LIVE.remove(base.getUUID());
        RigInstincts.forget(base.getUUID());
        RigPowers.forget(base.getUUID());
        RigVoice.forget(base.getUUID());
        RigAbilities.forget(base.getUUID());
        DisplayRig.reap(level, base.getUUID());
        base.discard();

        try {
            var clips = StandInSculpt.isStandIn(sculpted)
                    ? java.util.Map.<String, com.moddingfromamod.runtime.BedrockAnimation>of()
                    : com.moddingfromamod.runtime.ModelRepository.animations(sp.modelKey());
            DisplayRig rebuilt = DisplayRig.spawn(level, at, yaw, parts, sp.scale(), sp.id(),
                    CarrierPool.movementOf(sp, parts));
            // The same creature in a new body, not a new creature: it keeps its birth (WishShimmer).
            WishShimmer.rebuilt(base.getUUID(), rebuilt.living().getUUID());
            // ⚠ THE CARRIER ABOVE WAS DISCARDED, so every handle naming this creature is now
            // naming a dead entity -- the same defect the bridge has at the first manifestation
            //, one level along and live for SCULPTED creatures too. It is what makes
            // a SECOND edit fail: "make it bigger" rebuilds, and "no, BIGGER" then addresses the
            // carrier the first edit threw away.
            com.moddingfromamod.mob.EntityHandles.global().repoint(
                    base.getUUID(), rebuilt.living().getUUID(),
                    com.moddingfromamod.mob.Creatures.kindId(rebuilt.living()));
            DisplayRig.applyVitals(rebuilt.living(), vitals);
            animal.putOn(level, rebuilt.base(), sp);
            drive(rebuilt, RigFit.intoRigSpace(parts), clips);
            LOG.info("rebuilt the rig for '{}' {}", sp.id(), why);
        } catch (Exception e) {
            LOG.error("could not rebuild the rig for '{}' ({}) -- the old one is gone and nothing "
                    + "replaced it", sp.id(), why, e);
        }
    }

    /**
     * <b>Adopt a carrier when it is READ BACK FROM DISK </b> — at a server start, and every
     * time its chunk loads again after the child walked away and came back.
     *
     * <p>This replaced a sweep on {@code LevelEvent.Load}, which had two faults. It ran once, so
     * a creature whose chunk unloaded and loaded again was dropped from {@link #LIVE} (the
     * backstop in {@link #onServerTick}) and never put back: a statue, and one whose
     * hit proxies were gone too. And it ran too early: deferred one task, it found the saved
     * entities not yet loaded and adopted nothing (an earlier issue's rehearsal).
     *
     * <p>The carrier's own join is the moment that is never early for the CARRIER, but its
     * displays may still be arriving, so it is queued and adopted from the tick
     * ({@link #adoptPending}), waiting up to {@link #ADOPT_PATIENCE_TICKS} for its displays before
     * rebuilding it. Only entities loaded from disk: a rig spawned in this session is driven (or
     * deliberately not) by whoever spawned it.
     */
    @SubscribeEvent
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof Mob gone
                && gone.entityTags().contains(DisplayRig.LEAVING_TAG)) {
            // the old body of a transformation the server stopped in the middle of: its
            // new body was saved too, so this one is a duplicate
            level.getServer().execute(() -> {
                DisplayRig.reap(level, gone.getUUID());
                gone.discard();
            });
            return;
        }
        if (event.loadedFromDisk()
                && event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof Mob carrier
                && carrier.entityTags().contains(DisplayRig.RIG_TAG)
                && !LIVE.containsKey(carrier.getUUID())) {
            PENDING.put(carrier.getUUID(), new Pending(level, 0));
        }
    }

    /**
     * How long a carrier read back from disk waits for its displays before it is rebuilt. A
     * chunk's saved entities arrive together, so a match is expected on the first try; this is
     * the margin for a display saved into the neighbouring chunk.
     */
    static final int ADOPT_PATIENCE_TICKS = 40;

    private record Pending(ServerLevel level, int ticks) {
    }

    /** Carriers read back from disk and not yet driven. */
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    /** Adopt what has come back from disk; see {@link #onJoin}. */
    private static void adoptPending() {
        for (var entry : PENDING.entrySet()) {
            Pending p = entry.getValue();
            Entity e = p.level().getEntity(entry.getKey());
            if (!(e instanceof Mob carrier) || carrier.isRemoved()) {
                PENDING.remove(entry.getKey());   // unloaded again before it was adopted
                continue;
            }
            boolean lastChance = p.ticks() >= ADOPT_PATIENCE_TICKS;
            Adopt done;
            try {
                done = adoptOne(p.level(), carrier, lastChance);
            } catch (Exception ex) {
                LOG.warn("could not adopt rig carrier {} -- leaving it", entry.getKey(), ex);
                done = Adopt.SKIP;
            }
            if (done == Adopt.WAIT) {
                PENDING.put(entry.getKey(), new Pending(p.level(), p.ticks() + 1));
            } else {
                if (done == Adopt.DONE) {
                    LOG.info("adopted rig carrier {} as it loaded, after {} tick(s)",
                            entry.getKey(), p.ticks());
                }
                PENDING.remove(entry.getKey());
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!PENDING.isEmpty()) {
            adoptPending();
        }
        if (LIVE.isEmpty()) {
            return;
        }
        // No interval check here: RigAnimator.writesOn anchors on the LEVEL's game time, so
        // several creatures write on the SAME ticks rather than drifting into every tick between
        // them — the difference between a sixth of the traffic and all of it. Position and head,
        // below, are cheap and self-skipping, so they run every tick and stay responsive.
        for (var entry : LIVE.entrySet()) {
            Live live = entry.getValue();
            Mob base = live.rig.base();
            if (base == null || base.isRemoved() || !(base.level() instanceof ServerLevel)) {
                // The reaper normally gets here first; this is the backstop for a carrier that
                // left by a route the reaper does not watch (an unload, a dimension change), so
                // the map cannot grow without bound. Its hit proxies go with it: they are
                // never saved, and one left standing would be a hitbox over nothing.
                LIVE.remove(entry.getKey());
                RigInstincts.forget(entry.getKey());
                RigPowers.forget(entry.getKey());
                RigVoice.forget(entry.getKey());
                RigAbilities.forget(entry.getKey());
                RigFacing.forget(entry.getKey());
                CreatureLife.forget(entry.getKey());
                live.rig.discardProxies();
                continue;
            }
            try {
                if (rebuildIfRewished(entry.getKey(), live, base)) {
                    continue;
                }
                driveOne(live, base);
                // the shadow and (for a name he gave it) the name plate a vanilla mob would have.
                live.rig.wearLook(live.builtFrom == null ? null : live.builtFrom.name());
                // the light where the carrier stands, written only when it changed, on the
                // pose's own write tick so it rides the pose's packet. A dying creature too.
                if (RigLight.FOLLOW && RigLight.sampleOn(base.level().getGameTime(), INTERVAL)) {
                    live.rig.followLight((ServerLevel) base.level());
                }
                // What its species hunts and runs from. Here, so every rig this class
                // drives has it, whichever of the three birth points it came from.
                if (base.deathTime == 0) {
                    RigInstincts.tick(base, live.builtFrom);
                }
                // Boss bar, flight. (A death's bang is RigPowers.onDeath.)
                RigPowers.tick(base, live.builtFrom);
                // chunk 1: its sculpted moves (slam, ranged, charge, summon, teleport, heal).
                // its archetype's moves too (an archer's bow, a dragon's fire), when it fights.
                RigAbilities.tick(base, CreatureLife.withImpliedMoves(base, live.builtFrom));
                // how it LIVES when nothing else steers it — its archetype, not its carrier's.
                if (base.deathTime == 0) {
                    CreatureLife.tick(base, live.builtFrom);
                }
                // chunk 4: its voice — idle sounds and footfalls (hurt and death are events).
                RigVoice.tick(base, live.builtFrom);
            } catch (Exception e) {
                LOG.warn("rig animator failed for {} -- dropping it rather than throwing every "
                        + "tick", entry.getKey(), e);
                LIVE.remove(entry.getKey());
            }
        }
    }

    private static void driveOne(Live live, Mob base) {
        DisplayRig rig = live.rig;

        // POSITION AND FACING FIRST, always — a creature that is dying still falls where it stood
        // rather than at the origin, and a creature that is walking has to be followed whether or
        // not its clip pose changed this tick.
        //
        // 🔴 THE BODY'S YAW, not yRot. An idle mob's yRot is stale -- only MoveControl
        // writes it -- and vanilla keeps head and body together by turning yBodyRot towards the
        // head (BodyRotationControl). Passing yRot here pinned yBodyRot to it every tick, so a
        // creature watching the child walk round it turned its HEAD a half turn instead. See
        // RigHeadYaw.
        // a move aimed at something (a shot, a charge, a blink) faces it while it lasts.
        // The carrier's yaw is what it WANTS; what is drawn turns towards that at a creature's pace
        // and not for every twitch (RigTurn).
        // rig.look_at: a held facing turns the carrier first, so everything below draws it.
        RigFacing.apply(base);
        float carrierYaw = base.yBodyRot;
        float aimed = RigAbilities.drawnYaw(base, Float.NaN);
        boolean steep = RigTurn.ON && steepLook(live, base);
        // Put somewhere else (a teleport, a stage): it arrives facing its way, it does not turn to it.
        double jx = base.getX() - live.lastX, jz = base.getZ() - live.lastZ;
        boolean jumped = jx * jx + jz * jz > 16.0;
        live.lastX = base.getX();
        live.lastZ = base.getZ();
        float bodyYaw;
        if (!Float.isNaN(aimed)) {
            carrierYaw = aimed;
            bodyYaw = live.turn.snap(aimed);
            live.course.reset(base.getX(), base.getZ(), aimed);
        } else if (!RigTurn.ON || jumped || com.moddingfromamod.mob.RideSeat.riderOf(base) != null) {
            bodyYaw = live.turn.snap(carrierYaw);   // a rider steers it: his turn, at once
            live.course.reset(base.getX(), base.getZ(), carrierYaw);
        } else {
            var dv = base.getDeltaMovement();
            boolean moving = dv.x * dv.x + dv.z * dv.z > 0.03 * 0.03 || !base.getNavigation().isDone();
            if (RigPowers.flies(base)) {
                // A flier's own yaw is its bat's flapping; it heads the way it has travelled.
                float course = live.course.at(base.getX(), base.getZ(), carrierYaw);
                // travelling, it is drawn on its heading; drifting, it turns to it at the standing pace
                bodyYaw = live.course.drawn(live.turn, course);
            } else if (steep && !moving) {
                bodyYaw = live.turn.hold(carrierYaw);   //: the body was following a head with no direction
            } else {
                bodyYaw = live.turn.step(carrierYaw, moving);
            }
        }
        // The pitch BEFORE follow, which moves the carrier with xRot 0: read after it, the head
        // never looked up or down at all. RigHeadYaw.pitch is the A/B seam.
        float headPitch = RigHeadYaw.pitch(base.getXRot());
        RigDriver.follow(rig, new Vec3(base.getX(), base.getY(), base.getZ()), carrierYaw, bodyYaw);
        rig.mirrorGlow(base);   //: glow is the carrier's state, drawn on the rig
        // an /effect clear (run_command, a datapack), milk or a cure took the carrier's
        // invisibility and uncovered the cow inside the T-Rex. No call site of ours sees those,
        // so the rig's own tick puts it back. A dying carrier too: the flop is the rig's.
        DisplayRig.keepHidden(base);

        RigGait.Sense sensed = null;
        if (LOCOMOTION && live.plan != null) {
            sensed = sense(live, base);
            act(live.gait.tick(sensed), base, sensed);
        }

        int death = base.deathTime;
        int hurt = base.hurtTime;
        if (death > 0) {
            RigDriver.dying(rig, hurt, death, base.level().getGameTime(), INTERVAL);
            return;
        }
        if (hurt > 0) {
            // a hurt creature keeps moving, as a vanilla mob does: only the red flash. This used to
            // return here, so a creature hit mid-stride froze its whole pose for the ten ticks of the flash
            // while it slid on, and then snapped back into its walk.
            rig.tint(RigTint.hurtFlash(hurt, 0));
        } else {
            // Still being wished into shape: the shimmer and the sparkles. After the hurt flash, so a
            // punch mid-build still flashes red and the shimmer comes back after.
            WishShimmer.drive(rig, base, live.builtFrom == null ? null : live.builtFrom.id(),
                    base.level().getGameTime());
        }

        // 🔴 THE CLIP HALF IS RigAnimator'S, and this class does not get an opinion about it.
        // It reads the same four fields WishMobRenderer reads and calls the same
        // AnimationState.select, so the rig and the mod renderer cannot animate a creature on two
        // different schedules from one input. What tier 5 was missing was never that rule — it was
        // something to CALL it every tick, which is all this class is.
        // 🔴 OUR OWN SWING CLOCK, because vanilla does not run one for this creature.
        //
        // `updateSwingTime()` advances `attackAnim` 0→1 and is called from `Monster.aiStep()`,
        // `Player.serverAiStep()` and `RemotePlayer` — nowhere else in the game. A wished creature
        // is a `PathfinderMob` on the mod path and may not be a `Monster` at all on this one (a
        // Monster carrier is deleted on Peaceful — RigBase), so `getAttackAnim` is permanently 0
        // and an attack clip driven by it is selected correctly and FROZEN ON FRAME ONE. Both
        // paths, always, and it looks exactly like a clip that failed to load.
        //
        // Since 26.3 vanilla's own swing state (LivingEntity.SwingState) ticks for every creature
        // and ends itself, but a swing is a new SwingDescription object, not a flag: a swing starts
        // here when vanilla reports a swing we have not seen, so one swing never starts two clips.
        Object current = base.getCurrentSwing();
        if (current != null && current != live.lastSwing && live.swing < 0) {
            live.swing = 0;
            live.swings++;
        }
        live.lastSwing = current;
        float attackProgress = 0f;
        boolean attacking = false;
        if (live.swing >= 0) {
            live.swing++;
            if (live.swing >= SWING_TICKS) {
                live.swing = -1;
            } else {
                attacking = true;
                attackProgress = live.swing / (float) SWING_TICKS;
            }
        }
        // 🔴 ONE WRITER, ONE TICK. The head's look rides the clip's own write: composed
        // into the same frame (R(look)·R(clip)·R(rest) down the chain) and tweened on the same
        // tick as the body. It used to be a second writer, `lookAt`, run every tick straight after
        // the clip: it wrote the head subtree as rest + look (throwing away the clip's head keys)
        // and re-started its tween every tick while the body's ran every third, so the head swam
        // against the body -- it was filmed on the starter dragon.
        // Relative to the body the rig just drew, and never past the mob's own head limit.
        // looking nearly straight up or down, the head only pitches; its yaw stays as drawn.
        float headYaw = steep ? live.headYaw
                : RigHeadYaw.relative(bodyYaw, base.getYHeadRot(), base.getMaxHeadYRot());
        live.headYaw = headYaw;
        // A hopper's legs belong to the hop, never to the walk cycle.
        boolean hopper = sensed != null && sensed.hops();
        long now = base.level().getGameTime();
        // the talking flag. Its clip runs from the tick the flag was first seen, so the
        // mouth opens on its first frame; and the tick it flips is written at once (a one-tick
        // tween) instead of waiting up to INTERVAL - 1 ticks for the schedule, so a talk window
        // starts and ends within a tick of the command that set it.
        boolean talking = RigTalk.isTalking(base);
        boolean talkFlipped = talking != (live.talkSince >= 0);
        if (talkFlipped) {
            live.talkSince = talking ? now : -1;
            BedrockAnimation clip = BedrockAnimation.find(live.clips, BedrockAnimation.Clip.TALK);
            LOG.info("rig talk {} for {} ({}) at game tick {}: playing {}", talking ? "on" : "off",
                    live.builtFrom == null ? "?" : live.builtFrom.id(), base.getUUID(), now,
                    !talking ? "its motion clip" : clip != null ? clip.name() : "IDLE (no talk clip)");
        }
        RigAnimator.Motion motion = new RigAnimator.Motion(attacking, attackProgress,
                hopper ? 0f : base.walkAnimation.speed(), base.walkAnimation.position(),
                base.tickCount / 20.0f, talking, talking ? (now - live.talkSince) / 20.0f : 0f);
        int interval = talkFlipped ? 1 : INTERVAL;
        if (live.builtFrom != null && live.builtFrom.archetype() != null && live.builtFrom.archetype().rigid()) {
            // a vehicle is rigid — its rest pose, no clips, no look. Its wings do not flap.
            RigAnimator.tick(rig, live.mirrored, java.util.Map.of(), now, INTERVAL, motion, null);
            return;
        }
        // a directed gesture (a hat tip on a word) laid over the idle or talk clip.
        java.util.Map<String, com.moddingfromamod.runtime.BedrockAnimation> clips = RigGesture.layer(
                base.getUUID(), live.clips, now, base.tickCount / 20.0f,
                talking ? (now - live.talkSince) / 20.0f : 0f);
        RigAnimator.tick(rig, live.mirrored, clips, now, interval, motion,
                new float[]{headYaw, headPitch},
                sensed == null ? null : live.plan, drawnWeights(live, base.tickCount),
                (float) Math.floorMod(now, 72000L));
        if (STATE_SCRIPT != null) {
            RigStateScript.Look look = RigStateScript.look(STATE_SCRIPT, base.tickCount);
            if (look != null && !look.equals(live.scriptLook)) {
                RigStateScript.Look was = live.scriptLook == null ? RigStateScript.Look.NONE : live.scriptLook;
                if (look.tint() != was.tint()) {
                    rig.tint(look.tint());
                }
                if (look.white() != was.white()) {
                    rig.whiten(look.white());
                }
                if (look.bright() != was.bright() && base.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                    rig.fullBright(sl, look.bright());
                }
                if (look.scale() != was.scale()) {
                    rig.scaleBody(look.scale(), 2);
                }
                live.scriptLook = look;
            }
            if (Math.floorMod(base.tickCount, RigStateScript.SHOWCASE_PERIOD) == 0 && "showcase".equals(STATE_SCRIPT)) {
                LOG.info("rigstate showcase cycle at age {}", base.tickCount);   // the take syncs its commands to this
            }
            if (Math.floorMod(base.tickCount, RigStateScript.LIESLEEP_PERIOD) == 0 && "liesleep".equals(STATE_SCRIPT)) {
                LOG.info("rigstate liesleep cycle at age {}", base.tickCount);
            }
        }
        if (STATE_SCRIPT != null && base.tickCount % 2 == 0) {
            // the film's proof: the weights both paths draw, every 2 ticks, in the log
            RigLocomotion.Weights w = drawnWeights(live, base.tickCount);
            LOG.info("rigstate {} age={} lie={} sleep={} swim={} pitch={} moving={}", STATE_SCRIPT, base.tickCount,
                    String.format(java.util.Locale.ROOT, "%.3f", w.lie()), String.format(java.util.Locale.ROOT, "%.3f", w.sleep()), String.format(java.util.Locale.ROOT, "%.3f", w.swim()),
                    String.format(java.util.Locale.ROOT, "%.1f", w.travelPitch()), String.format(java.util.Locale.ROOT, "%.2f", w.moving()));
        }
    }

    /**
     * Is the carrier looking at something too near straight above or below it to have a direction?
     * Remembered for a few ticks: a look goal re-aims every other tick, and between them the
     * control says it is looking at nothing (measured: the hold let go on alternate ticks).
     */
    private static boolean steepLook(Live live, Mob base) {
        var look = base.getLookControl();
        if (look.isLookingAtTarget()) {
            live.steep = RigTurn.steep(look.getWantedX() - base.getX(),
                    look.getWantedY() - base.getEyeY(), look.getWantedZ() - base.getZ());
            live.steepSeen = base.tickCount;
        }
        return live.steep && base.tickCount - live.steepSeen <= 6;
    }

    /** The body yaw a driven creature was last drawn at, or NaN. */
    public static float drawnYawOf(Entity carrier) {
        Live live = carrier == null ? null : LIVE.get(carrier.getUUID());
        return live == null ? Float.NaN : live.turn.drawn();
    }

    /** Which display entity count belongs to a carrier — used by the adopt path and by tests. */
    /** The layer a driven creature is mostly in ({@link RigGait#layer}), for a film's control. */
    public static String layerOf(Entity carrier) {
        Live live = carrier == null ? null : LIVE.get(carrier.getUUID());
        return live == null ? "-" : live.gait.layer();
    }

    /**
     * The named clip a driven creature is playing over its motion, and how many times a clip has started (a second
     * swing is a new run):, for a creature drawn natively, whose pack holds the same clips. Null when it plays
     * none. An attack outranks a gesture, which outranks talking.
     */
    public record DrawnClip(String name, int run) {
    }

    public static DrawnClip drawnClipOf(Entity carrier) {
        Live live = carrier == null ? null : LIVE.get(carrier.getUUID());
        if (live == null || live.clips == null || live.clips.isEmpty()) {
            return null;
        }
        if (live.swing >= 0) {
            String k = keyOf(live.clips, BedrockAnimation.find(live.clips, BedrockAnimation.Clip.ATTACK));
            if (k != null) {
                return new DrawnClip(k, live.swings);
            }
        }
        RigGesture.Playing g = RigGesture.playing(carrier.getUUID());
        if (g != null) {
            String k = keyOf(live.clips, RigGesture.named(live.clips, g.clip()));
            if (k != null) {
                return new DrawnClip(k, (int) g.startTick());
            }
        }
        if (live.talkSince >= 0) {
            String k = keyOf(live.clips, BedrockAnimation.find(live.clips, BedrockAnimation.Clip.TALK));
            if (k != null) {
                return new DrawnClip(k, (int) live.talkSince);
            }
        }
        return null;
    }

    private static String keyOf(Map<String, BedrockAnimation> clips, BedrockAnimation clip) {
        if (clip == null) {
            return null;
        }
        for (Map.Entry<String, BedrockAnimation> e : clips.entrySet()) {
            if (e.getValue() == clip) {
                return e.getKey();
            }
        }
        return null;
    }

    /** The weights a driven creature is drawn with, or null. */
    public static RigLocomotion.Weights weightsOf(Entity carrier) {
        Live live = carrier == null ? null : LIVE.get(carrier.getUUID());
        return live == null ? null : drawnWeights(live, carrier.tickCount);
    }

    /** {@code -Ptestprops=rigstate=napcycle|swimcycle}: a scripted pose for a film; null is the gait's own. */
    private static final String STATE_SCRIPT = RigStateScript.named(System.getProperty(
            com.moddingfromamod.ModdingFromAModMod.MODID + ".rigstate"));

    /** The gait's weights, or the film script's at this age when one is set (the AI then decides nothing). */
    private static RigLocomotion.Weights drawnWeights(Live live, int age) {
        if (STATE_SCRIPT == null) {
            return live.gait.weights();
        }
        return RigStateScript.at(STATE_SCRIPT, age);
    }

    /** The locomotion plan a driven creature is drawn with (its anatomy, its legs), or null. */
    public static RigLocomotion.Plan planOf(Entity carrier) {
        Live live = carrier == null ? null : LIVE.get(carrier.getUUID());
        return live == null ? null : live.plan;
    }

    /** The live rig a carrier wears, or null when it is not being driven. */
    public static DisplayRig rigOf(Entity carrier) {
        Live live = carrier == null ? null : LIVE.get(carrier.getUUID());
        return live == null ? null : live.rig;
    }

    public static boolean isDriving(Entity carrier) {
        return carrier != null && LIVE.containsKey(carrier.getUUID());
    }

    /**
     * The game tick this carrier's rig started its talk clip on, or -1 when it is not talking or not
     * driven. For a test: "the rig saw the flag" rather than "the tag is on the mob".
     */
    static long talkSince(UUID carrier) {
        Live live = LIVE.get(carrier);
        return live == null ? -1 : live.talkSince;
    }

    /**
     * The talk clip this carrier's rig would play, or null when its rig has none (a stand-in rig, or a
     * species with no talk clip). For a test: "the rig really animates the talk", not just "the flag flipped".
     */
    static String talkClipOf(UUID carrier) {
        Live live = LIVE.get(carrier);
        if (live == null) {
            return null;
        }
        BedrockAnimation clip = BedrockAnimation.find(live.clips, BedrockAnimation.Clip.TALK);
        return clip == null ? null : clip.name();
    }
}
