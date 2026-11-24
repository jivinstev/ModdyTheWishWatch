package com.moddingfromamod.vanilla;

import net.minecraft.world.entity.EntitySpawnReason;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * <b>A sculpted creature spawned as vanilla entities only</b> — the arm-B half of the
 * vanilla-creature spike.
 *
 * <p>Everything this class puts in the world is a {@code minecraft:} entity type, because the whole
 * question is whether a client with no mod installed can see the creature. That is also the spike's
 * control: {@link #entityTypeIds} exists so a test can assert no {@code moddingfromamod:} type is
 * present, and a rig that accidentally rendered {@code wish_mob} would prove nothing at all.
 *
 * <h2>Everything goes through NBT, and that is not a workaround</h2>
 *
 * <p>Every mutator on {@code Display} and {@code Interaction} is <b>private</b> — verified with
 * {@code javap} against the patched jar rather than assumed, because it is the sort of thing you
 * would otherwise discover by writing a class that cannot compile. There is no public
 * {@code setTransformation}.
 *
 * <p>So the transform is written the way the spike doc says the mechanism is specified: as vanilla
 * NBT. {@code Entity.saveWithoutId} and {@code Entity.load} are both public, so a round trip —
 * save, overlay our keys, load — sets the fields with no access transformer, no reflection, and no
 * build-configuration change. The keys and their codecs are read off {@code Display}'s own
 * {@code readAdditionalSaveData} bytecode: {@code transformation} through
 * {@code Transformation.EXTENDED_CODEC}, then {@code interpolation_duration},
 * {@code start_interpolation}, {@code teleport_duration}, {@code view_range}, {@code brightness}.
 *
 * <p>The round trip is not free, and Q2 measures it rather than waving at it. An implementation that
 * shipped would widen those setters with an access transformer and pay a field write instead; the
 * spike deliberately does not change build configuration to find that out.
 */
public final class DisplayRig {

    /**
     * Degrees added to every display's yaw, to reconcile the two renderers' conventions.
     * {@code -Ptestprops="rigyaw=180"}. Default 0 so the measurement starts from the naive rig.
     */
    /**
     * <b>Whether the sculpt is mirrored into world space before the rig is built</b> — a bisect
     * lever, because the mirror is the one convention a SINGLE-box fixture is structurally blind to.
     *
     * <p>The single-box A/B reads 0 px at three stage yaws, which proved the yaw convention and
     * proved nothing about the mirror: that box's group origin is its own centre, so the box sits
     * symmetric about model 8 and reflecting it about its own centre moves nothing. Two boxes at
     * ±x in one group swapped places, which is what actually sees it.
     */
    public static final String MIRROR_MODE = System.getProperty(
            com.moddingfromamod.ModdingFromAModMod.MODID + ".rigmirror", "on");

    public static final float YAW_OFFSET = Float.parseFloat(
            System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".rigyaw", "180"));

    /**
     * The yaw to put on a DISPLAY, given the creature's body yaw. Spelt here and nowhere else.
     *
     * <p>⚠ This exists because the offset was applied in {@code spawn} and nowhere else, and
     * {@link RigDriver#follow} then rewrote every display with the RAW yaw — so a rig that stood
     * still was correct and a rig that WALKED was 180 degrees from the mod's sculpt, permanently,
     * from its first step. Same shape as the train-axis literal in {@code docs/CAMERA.md}: a
     * constant that is right at one call site and simply absent at the other.
     *
     * <p>Nothing could catch it above the eye. The static A/B never calls {@code follow}; the
     * animation oracles measure TRANSFORMS, which live in the display's own frame and do not care
     * which way the body points; and the walk run's three controls — both creatures moved 3.17
     * blocks, the walk clip was selected on 360 of 360 ticks, arm B wore real models — are all
     * true of a creature moon-walking. It was found by a human watching the film.
     */
    public static float displayYaw(float bodyYaw) {
        return bodyYaw + YAW_OFFSET;
    }

    /** Vanilla item-display entity type id — spelt once. */
    public static final Identifier ITEM_DISPLAY =
            Identifier.withDefaultNamespace("item_display");

    private final Mob base;
    private final List<Entity> displays;
    /** The name plate, if this creature has been named. Never one of {@link #displays}. */
    private Entity nameplate;
    private final List<RigPlan.Group> groups;
    private final double scale;
    /** The carrier the plan chose; changed only by {@link #fitSeatToBack}. */
    private RigBase.Choice choice;
    /** The rig's drawn top at rest, in blocks over the carrier's feet; 0 for no atoms. */
    private final double drawnTop;
    /** invisible mobs over the parts the carrier's box cannot cover. Never displays. */
    private final List<Mob> proxies = new ArrayList<>();
    /** Where each of {@link #proxies} stands, in the rig's frame; the same order. */
    private List<HitProxyPlan.Proxy> proxyPlan = List.of();
    /** The plan that chose the carrier and the proxies, or null for a rig built without one. */
    private HitProxyPlan.Plan hitPlan;
    private DisplayRig(Mob base, List<Entity> displays, List<RigPlan.Group> groups,
                       double scale, RigBase.Choice choice) {
        this.base = base;
        this.displays = displays;
        this.groups = groups;
        this.scale = scale;
        this.choice = choice;
        RigFit.Box drawn = RigFit.bounds(RigFit.atoms(groups, scale));
        this.drawnTop = drawn == null ? 0 : drawn.maxY();
        // ONE carrier-motion smoothing for the whole rig, written here because this is the
        // one place both a fresh rig (spawn) and a reloaded one (adopt) pass through. An adopted
        // rig matters most: its displays were saved by an older jar, where each carried whatever
        // its last pose write had left (0 or 3), which is exactly the disconnected walk.
        for (Entity d : displays) {
            smoothMotion(d);
        }
    }

    /**
     * <b>Ticks the client takes to ease a display into each new POSITION &mdash; one number for the
     * whole rig, never per bone.</b>
     *
     * <p>{@link RigDriver#follow} moves every display to the carrier's feet every tick, and the
     * client eases each one into its new position over that display's {@code teleport_duration}.
     * Until the only writer of that value was {@link #apply}, which set it to the TRANSFORM
     * tween &mdash; 0 at spawn, 3 once a clip or the head had posed that display. So a walking rig
     * was a mix: posed displays arrived two ticks after unposed ones, and the head (posed by the
     * look) slid against the legs (never posed). It was filmed on the starter dragon.
     *
     * <p>Any value, applied to every display, is rigid. The number is the smoothness, chosen by
     * measurement (<code>docs/VANILLA_RIG_ANIMATION_LADDER.md</code> §5.4): 0 snaps each move at the
     * tick boundary, a 20 Hz stutter at any frame rate; 3 is what the client uses for the CARRIER
     * itself ({@code ClientPacketListener} lerps a mob over 3 steps). Since a rig atom is sent its
     * move every 3rd tick, not every tick, and eases over that gap plus one tick ({@link #easeTicks}):
     * 4, the cushion that keeps a late packet from stopping the body.
     */
    public static final int MOTION_TICKS = easeTicks(System.getProperty("moddingfromamod.rigeaseticks"));

    /**
     * the move cadence plus {@link RigCadence#EASE_SLACK}, 4 on the shipped 3-tick cadence: an ease a
     * tick longer than the gap between moves is the cushion for a late packet.
     * {@code -Dmoddingfromamod.rigeaseticks=N} sets it to N, the arm that measured it; clamped to 1..10.
     */
    static int easeTicks(String prop) {
        int shipped = RigCadence.MOVE_INTERVAL + RigCadence.EASE_SLACK;
        if (prop == null || prop.isBlank()) {
            return Math.max(1, Math.min(10, shipped));
        }
        try {
            return Math.max(1, Math.min(10, Integer.parseInt(prop.trim())));
        } catch (NumberFormatException e) {
            return Math.max(1, Math.min(10, shipped));
        }
    }

    /** Write {@link #MOTION_TICKS} onto one display (or the name plate), if it is not already. */
    public static void smoothMotion(Entity display) {
        // by the one field. This read the WHOLE entity's NBT just to look at one int, on
        // every display of every rig built or adopted (8.5% of a 40-creature load, after wear's
        // own round trip was gone) -- and a saved rig already has it, so it was all reading.
        if (display instanceof com.moddingfromamod.mixin.DisplayPoseInvoker d) {
            if (d.moddingfromamod$getPosRotInterpolationDuration() != MOTION_TICKS) {
                d.moddingfromamod$setPosRotInterpolationDuration(MOTION_TICKS);
            }
            return;
        }
        smoothMotionByRoundTrip(display);
    }

    /** The pre-#1869 way, kept as {@code WearDirectGameTest}'s reference. */
    static void smoothMotionByRoundTrip(Entity display) {
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(display);
        if (tag.getIntOr("teleport_duration", 0) == MOTION_TICKS) {
            return;
        }
        tag.putInt("teleport_duration", MOTION_TICKS);
        com.moddingfromamod.compat.EntityNbt.load(display, tag);
    }

    /** The last CLIP pose written, so a head write composes with it rather than replacing it. */
    private java.util.Map<String, com.moddingfromamod.runtime.BedrockAnimation.BonePose> lastPose =
            java.util.Map.of();

    /** Bones the last clip frame moved: a bone the next clip lets go of is written back once. */
    private java.util.Set<String> lastPosed = java.util.Set.of();

    /** The clip pose last written to this rig &mdash; empty before the first. */
    public java.util.Map<String, com.moddingfromamod.runtime.BedrockAnimation.BonePose> lastPose() {
        return lastPose;
    }

    /** Bones the last clip frame moved. */
    public java.util.Set<String> lastPosed() {
        return lastPosed;
    }

    /** Called by the one pose writer after it writes a clip frame. */
    void posed(java.util.Map<String, com.moddingfromamod.runtime.BedrockAnimation.BonePose> pose,
               java.util.Set<String> bones) {
        lastPose = pose == null ? java.util.Map.of() : pose;
        lastPosed = bones == null ? java.util.Set.of() : bones;
    }

    /**
     * Where one of this rig's atoms belongs, at rest.
     *
     * <p>{@link RigDriver} composes poses on top of this; nothing else in the package should call
     * {@link RigTransform#placeAtom} directly. (Until this was also the one place the two
     * granularities differed — atoms and the pack rig's baked groups — and the pack rig is gone.)
     */
    public RigTransform.Placed placed(RigPlan.Group g) {
        return RigTransform.placeAtom(g, scale);
    }

    public Mob base() {
        return base;
    }

    public List<Entity> displays() {
        return List.copyOf(displays);
    }

    public List<RigPlan.Group> groups() {
        return List.copyOf(groups);
    }

    public RigBase.Choice choice() {
        return choice;
    }

    /** The rig's hit proxies, in plan order. A copy; the live list is re-seated on loss. */
    public List<Mob> proxies() {
        return List.copyOf(proxies);
    }

    /** The plan that chose this rig's carrier and proxies, or null. */
    public HitProxyPlan.Plan hitPlan() {
        return hitPlan;
    }

    /** Give the rig its proxies — spawned now, over their parts, for a body facing {@code yaw}. */
    void takeProxies(ServerLevel level, HitProxyPlan.Plan plan, float yaw) {
        discardProxies();
        // a creature he cannot ride wears no hitbox Bedrock offers to ride (HitProxyPlan.LOOKS_RIDEABLE) —
        // whatever plan it brings: one saved on its carrier before this rule, or one planned around a carrier
        // kept on load. Every path comes through here, so this is the one place it is decided.
        boolean rides = Boolean.TRUE.equals(com.moddingfromamod.mob.PetBehaviours.of(base).rideable());
        if (!rides && plan != null && plan.proxies().stream()
                .anyMatch(p -> HitProxyPlan.LOOKS_RIDEABLE.contains(p.id()))) {
            HitProxyPlan.Plan clean = HitProxies.planAround(RigFit.atoms(groups, scale), base, candidates(level),
                    HitProxyPlan.proxyPool(false));
            if (clean != null) {
                plan = clean;
            }
        }
        hitPlan = plan;
        // Saved with the carrier, so the next load reuses it instead of planning on the server
        // thread (the join stall, 2026-11-08). Every path that gives a rig proxies comes through
        // here — spawn, reshape, adopt — so the save is always the plan the creature really has.
        HitProxies.remember(base, RigFit.atoms(groups, scale), candidates(level), plan);
        if (plan == null || plan.proxies().isEmpty()) {
            proxyPlan = List.of();
            return;
        }
        proxyPlan = plan.proxies();
        proxies.addAll(HitProxies.spawn(level, base, proxyPlan, yaw));
        // a rideable creature's horse-like stays UNTAMED. Measured through Geyser (Gate BG
        // ride_button_rideable_pocket): tamed, it offers "Saddle" — not Ride — to any hand holding something.
    }

    /**
     * <b>Bring the carrier down to the creature's back when it would seat a rider above it</b>.
     * A creature that was rideable when it was made has a carrier fitted under its back (the plan's
     * {@code seatCap}); one made rideable later keeps the carrier fitted to its whole body, and a
     * flier's bat is sized over its head and wings, so its rider floated over the creature. Climbing
     * on calls this first ({@code RideSeat.mount}): the same carrier, resized as a rideable
     * creature's would have been ({@link HitProxyPlan#fitUnderBack}), with proxies over what it then
     * leaves out. The new size is the carrier's own scale, so a stock client seats him from it.
     *
     * @param carrierSeat where the carrier now seats a rider, over its feet
     * @param back        the top of the creature's back over the carrier's feet
     * @return whether the carrier was resized
     */
    public boolean fitSeatToBack(ServerLevel level, double carrierSeat, double back) {
        if (base == null || !base.isAlive()) {
            return false;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(base.getType()).getPath();
        List<RigFit.Atom> atoms = RigFit.atoms(groups, scale);
        HitProxyPlan.Plan plan = HitProxyPlan.fitUnderBack(atoms, candidates(level), id, carrierSeat, back);
        var attr = base.getAttribute(Attributes.SCALE);
        if (plan == null || attr == null) {
            return false;
        }
        attr.setBaseValue(plan.core().scale());
        base.refreshDimensions();   // or the box it collides with stays the old size (spawn's note)
        choice = plan.core();
        takeProxies(level, plan, base.yBodyRot);
        return true;
    }

    /** Keep the proxies over their parts; {@link RigDriver#follow} calls this every tick. */
    void followProxies(Vec3 feet, float bodyYaw) {
        if (!proxies.isEmpty()) {
            HitProxies.follow(proxies, proxyPlan, base, feet, bodyYaw);
            for (Mob m : proxies) {
                keepHidden(m);     //: hidden by the same effect, cleared by the same /effect clear
            }
        }
    }

    /** Take the proxies out of the world (the carrier left, or the rig is being rebuilt). */
    public void discardProxies() {
        for (Mob m : proxies) {
            m.discard();
        }
        proxies.clear();
    }

    /** The creature's own scale — what the group transforms were built with. */
    public double scale() {
        return scale;
    }

    /** Every entity this rig put in the world, base first. */
    public List<Entity> all() {
        List<Entity> out = new ArrayList<>();
        out.add(base);
        out.addAll(displays);
        if (nameplate != null) {
            out.add(nameplate);
        }
        return out;
    }

    /**
     * The name plate, or null. Deliberately NOT in {@link #displays()}: that list is
     * index-matched to {@link #groups()}, and a plate appended to it would be handed a bone's
     * transform by every loop that walks the two together.
     */
    public Entity nameplate() {
        return nameplate;
    }

    /**
     * The registry ids of everything spawned — <b>the spike's control</b>.
     *
     * <p>Guard 1 of the spike: arm B must be genuinely vanilla. A test asserts every id here starts
     * {@code minecraft:}, because an arm that quietly rendered the mod's own entity would produce a
     * beautiful photograph and answer no question whatsoever.
     */
    public List<String> entityTypeIds() {
        List<String> out = new ArrayList<>();
        for (Entity e : all()) {
            out.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        }
        return out;
    }

    private float lastHeadYaw = Float.NaN;
    private float lastHeadPitch = Float.NaN;

    /**
     * <b>Forget the head's last written angle, so the next {@link RigDriver#lookAt} writes it
     * whatever the angle is.</b>
     *
     * <p>🔴 This exists because two pose sources write the same display and only one of them keeps
     * a memo. A clip pose covers every moving bone <em>including the head</em>, so when
     * {@link RigAnimator#tick} writes a frame it silently overwrites whatever {@code lookAt} last
     * put there — and {@code lookAt}'s own guard is "has the ANGLE changed since I last wrote",
     * which is still false. The head therefore snaps back to the clip's idea of it and stays
     * there, permanently, the moment a creature stops turning its head.
     *
     * <p>Measured: a 55-degree turn moved <b>851 px</b> in a run where the clip happened not to
     * land on the sampled frames and <b>71 px</b> in one where it did — same code, same creature,
     * and the difference was which writer went last. A guard that is right about its own writes
     * and blind to somebody else's is not a guard.
     */
    public void forgetHead() {
        lastHeadYaw = Float.NaN;
        lastHeadPitch = Float.NaN;
    }

    /** The look last written ({@code {yaw, pitch}}), or null if the head has never been turned. */
    public float[] currentHead() {
        if (Float.isNaN(lastHeadYaw)) {
            return null;
        }
        return new float[]{lastHeadYaw, lastHeadPitch};
    }

    /**
     * Has the head turned since the last write? Records the new angles as a side effect, so a
     * caller that asks is committing to writing.
     */
    public boolean headMoved(float yawDeg, float pitchDeg) {
        // a yaw change always writes; a pitch change alone only once it is VISIBLE. Before
        // the first write this is the old rule (the NaN / 0,0 case below).
        if (!Float.isNaN(lastHeadYaw) && !RigHeadYaw.worthWriting(lastHeadYaw, lastHeadPitch,
                yawDeg, pitchDeg, RigHeadYaw.activeStep())) {
            return false;
        }
        // Straight ahead is the pose the rig was BUILT in, so the first call at 0,0 is also a no-op.
        if (Float.isNaN(lastHeadYaw) && yawDeg == 0f && pitchDeg == 0f) {
            lastHeadYaw = 0f;
            lastHeadPitch = 0f;
            return false;
        }
        lastHeadYaw = yawDeg;
        lastHeadPitch = pitchDeg;
        return true;
    }

    /**
     * <b>Hide the carrier</b> — and it must be the EFFECT, not the flag.
     *
     * <p>{@code LivingEntity.updateInvisibilityStatus()} runs every tick and is literally
     * {@code setInvisible(hasEffect(INVISIBILITY))}, so a manual {@code setInvisible(true)} is
     * undone on the very next tick. Measured in Gate C the expensive way: the base was set
     * invisible at spawn and the photograph came back as a plainly visible HORSE with the rig's
     * displays as slivers under its belly.
     *
     * <p>Exposed as a method because it has to be called again after anything clears effects — a
     * bucket of milk, an {@code /effect clear}; {@link #keepHidden} does that every tick for a
     * driven rig. That is a one-tick exposure rather than a fix; the
     * only way to be hidden with nothing to clear is an entity that is invisible by nature, and no
     * vanilla {@code Mob} is.
     */
    public static void hide(Mob mob) {
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.INVISIBILITY, -1, 0, true, false, false));
        mob.setInvisible(true);
    }

    /**
     * <b>Put the carrier's invisibility back if something took it</b> — called every tick
     * for every driven rig by {@code VanillaRigAnimator}. {@code /effect clear} from the agent's
     * {@code run_command} or from a datapack, a bucket of milk, a mod's cure: none of them has a
     * call site of ours to hook, so the rig's own tick is the only place that sees all of them. The
     * child filmed a giant cow standing inside his T-Rex after three wishes that touched it. The
     * exposure is the one tick between the clear and this check. True when it had to re-hide.
     */
    public static boolean keepHidden(Mob mob) {
        if (mob.hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY)) {
            return false;
        }
        hide(mob);
        return true;
    }

    /**
     * <b>Give the carrier the creature's own numbers.</b>
     *
     * <p>Without this a rig has the creature's SHAPE and a horse's STATISTICS — see
     * {@link RigVitals} for how that survived every gate. Attributes that the chosen base does not
     * declare are skipped rather than forced: {@code minecraft:attack_damage} is <b>not</b> in a
     * horse's default supplier, and {@code AttributeMap.getInstance} returns null for one it does
     * not hold (the migrator catalog's R9, arriving from the other side). The count returned is how
     * many actually landed, so a test can assert on it rather than on the absence of a crash.
     *
     * @return the number of attributes the base accepted, out of the five offered
     */
    public static int applyVitals(net.minecraft.world.entity.LivingEntity mob, RigVitals v) {
        int applied = 0;
        applied += set(mob, Attributes.MAX_HEALTH, v.maxHealth());
        applied += set(mob, Attributes.ATTACK_DAMAGE, v.damage());
        applied += set(mob, Attributes.ARMOR, v.armor());
        applied += set(mob, Attributes.KNOCKBACK_RESISTANCE, v.knockbackResistance());
        applied += set(mob, Attributes.MOVEMENT_SPEED, v.speed());
        // AFTER max health, never before: setHealth clamps to the CURRENT maximum, so a creature
        // with 200 species health set before the attribute lands is clamped to the horse's 15 and
        // stays there. The order is the fix, and it is invisible in a diff that only adds lines.
        mob.setHealth((float) Math.min(v.health(), mob.getMaxHealth()));
        return applied;
    }

    private static int set(net.minecraft.world.entity.LivingEntity mob,
                           net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a,
                           double value) {
        var inst = mob.getAttribute(a);
        if (inst == null) {
            return 0;
        }
        inst.setBaseValue(value);
        return 1;
    }

    /** Marks the carrier of a rig, so a sweeper can find one after a reload. */
    public static final String RIG_TAG = com.moddingfromamod.mob.CreatureMatch.CARRIER_TAG;

    /** Prefix of the tag tying a display to its carrier: {@code mfamrigof_<carrier uuid>}. */
    public static final String RIG_OWNER_PREFIX = "mfamrigof_";

    /**
     * <b>Which group this display is</b> — the ATOM arm's replacement for the ordering
     * {@link #adopt} used to read off {@code custom_model_data}.
     *
     * <p>On the grouped arm every display wears a DIFFERENT number ({@code base + index}), so the
     * number it is already carrying says which group it is and the ordering is stored in the world
     * rather than reconstructed by luck. The atom arm's whole point is that every display wears the
     * SAME number, which takes that away — and {@code level.getEntities()} hands them back in
     * whatever order it likes, so without this a creature comes back from a save with its limbs
     * permuted: correct in every measurable way and visibly wrong.
     *
     * <p>Written only when the atom arm is on, so a grouped rig's entities are byte-identical to
     * what they were before this existed.
     */
    public static final String RIG_INDEX_PREFIX = "mfamrigix_";

    /**
     * <b>Which carrier a display belongs to</b> — the inverse of the tag written in {@code spawn},
     * and it lives here next to the write so the two cannot drift (the same argument as
     * {@link #speciesOn}).
     *
     * <p>Added for ATOM-P3c: the reveal has to group the parts it is about to release by the
     * creature they hang off, and the tag is the only link that survives a reload. The CARRIER
     * wears its own tag too, so this answers for a carrier as well as for a display.
     *
     * @return the carrier's uuid, or null if this entity is not part of a rig
     */
    public static java.util.UUID ownerOf(Entity e) {
        if (e == null) {
            return null;
        }
        for (String tag : e.entityTags()) {
            if (tag.startsWith(RIG_OWNER_PREFIX)) {
                try {
                    return java.util.UUID.fromString(tag.substring(RIG_OWNER_PREFIX.length()));
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The group index a display is tagged with, or -1 if it carries no index tag. */
    public static int indexOf(Entity display) {
        for (String tag : display.entityTags()) {
            if (tag.startsWith(RIG_INDEX_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(RIG_INDEX_PREFIX.length()));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * <b>Which species is this carrier wearing — the inverse of the tag written at line 527.</b>
     *
     * <p>It lives here, next to the write, so the two cannot drift. Everything above the rig that
     * needs to know <i>what creature is this</i> has to ask, because the shipping
     * creature is <b>not</b> a {@code WishMobEntity}: the bridge cancels that join and dresses a
     * vanilla base instead. {@code CaptureSubject} records what that cost the camera; this
     * is the same question asked by anything that wants to EDIT the creature rather than photograph
     * it.
     *
     * <p>🔴 <b>Do not invert the tag by string surgery.</b> {@code WishMobTags.forSpecies} strips
     * colour codes and punctuation, so {@code "§6SPIKOR, King of the Kroaks"} becomes
     * {@code "wishmob_spikor_king_of_the_kroaks"} and cannot be inverted. Ask each registered
     * species whether the carrier wears ITS tag, through the very same function — that cannot drift
     * from the spawn side by construction, where a hand-written inverse would drift the first time
     * the sanitiser changed.
     *
     * @return the species, or null if this entity is not a rig carrier we know
     */
    public static com.moddingfromamod.species.Species speciesOn(Entity carrier) {
        if (carrier == null || !carrier.entityTags().contains(RIG_TAG)) {
            return null;
        }
        // A fork's carrier wears its ancestor's tag as well as its own (see speciesTags), so the
        // fork is the answer when both match: it is the more specific of the two. Only a carrier
        // wearing TWO species tags can be one, so every other carrier keeps the first-match early
        // return — this runs every tick for every rig (RigInstincts, RigPowers).
        int speciesTags = 0;
        for (String t : carrier.entityTags()) {
            if (t.startsWith(com.moddingfromamod.entity.WishMobTags.PREFIX)) {
                speciesTags++;
            }
        }
        boolean mayBeAFork = speciesTags > 1;
        // BY INDEX, NOT BY SCAN (2026-10-31): this ran 2-4 times per creature per tick and, each time,
        // copied the whole registry and rebuilt every species' tag (four regex replaces apiece). The
        // index maps a tag to the species wearing it, in the registry's own order, so "the first
        // match" and "a fork wins over its ancestor" are decided exactly as the scan decided them.
        TagIndex idx = tagIndex();
        int first = -1, firstFork = -1;
        for (String t : carrier.entityTags()) {
            int[] at = idx.byTag().get(t);
            if (at == null) {
                continue;
            }
            for (int i : at) {
                if (first < 0 || i < first) {
                    first = i;
                }
                if ((firstFork < 0 || i < firstFork)
                        && com.moddingfromamod.marshal.ArtifactOwner.isFork(idx.order().get(i).id())) {
                    firstFork = i;
                }
            }
        }
        if (first < 0) {
            return null;
        }
        return idx.order().get(mayBeAFork && firstFork >= 0 ? firstFork : first);
    }

    /** Tag → positions (in {@code order}) of the species wearing it, for one registry state. */
    private record TagIndex(long writes, java.util.Map<String, int[]> byTag,
                            java.util.List<com.moddingfromamod.species.Species> order) {
    }

    private static volatile TagIndex tagIndex;

    /** The index for the registry as it is now; rebuilt only when it has been written to. */
    private static TagIndex tagIndex() {
        long w = com.moddingfromamod.species.SpeciesRegistry.writes();
        TagIndex t = tagIndex;
        if (t != null && t.writes() == w) {
            return t;
        }
        var order = com.moddingfromamod.species.SpeciesRegistry.all();
        java.util.Map<String, java.util.List<Integer>> lists = new java.util.HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            lists.computeIfAbsent(com.moddingfromamod.entity.WishMobTags.forSpecies(order.get(i).id()),
                    k -> new java.util.ArrayList<>()).add(i);
        }
        java.util.Map<String, int[]> byTag = new java.util.HashMap<>();
        lists.forEach((k, v) -> byTag.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        // Read the count BEFORE the copy: a write between the two leaves an index stamped older
        // than its contents, which the next call rebuilds — never one stamped newer.
        t = new TagIndex(w, byTag, order);
        tagIndex = t;
        return t;
    }

    /**
     * The scan {@link #speciesOn} replaced, kept as the oracle its test compares against: the two
     * must agree on every carrier.
     */
    static com.moddingfromamod.species.Species speciesOnByScan(Entity carrier) {
        if (carrier == null || !carrier.entityTags().contains(RIG_TAG)) {
            return null;
        }
        int speciesTags = 0;
        for (String t : carrier.entityTags()) {
            if (t.startsWith(com.moddingfromamod.entity.WishMobTags.PREFIX)) {
                speciesTags++;
            }
        }
        boolean mayBeAFork = speciesTags > 1;
        com.moddingfromamod.species.Species found = null;
        for (var candidate : com.moddingfromamod.species.SpeciesRegistry.all()) {
            if (carrier.entityTags().contains(
                    com.moddingfromamod.entity.WishMobTags.forSpecies(candidate.id()))) {
                if (!mayBeAFork
                        || com.moddingfromamod.marshal.ArtifactOwner.isFork(candidate.id())) {
                    return candidate;
                }
                if (found == null) {
                    found = candidate;
                }
            }
        }
        return found;
    }

    /**
     * <b>The species tags a carrier of {@code species} wears</b>: its own, and — for a fork — its
     * ancestor's. A fork is a copy that inherits everything, and its abilities are
     * datapacks that select the ANCESTOR's tag: the starter pet dragon breathes with
     * {@code @e[tag=wishmob_pet_dragon]}, so a dragon made his own by "make him bigger" would stop
     * breathing fire if it wore only {@code wishmob_pet_dragon<who>}. {@link #speciesOn} prefers
     * the fork, so which creature it IS stays unambiguous.
     */
    public static java.util.List<String> speciesTags(String species) {
        if (species == null || species.isBlank()) {
            return java.util.List.of();
        }
        String own = com.moddingfromamod.entity.WishMobTags.forSpecies(species);
        if (!com.moddingfromamod.marshal.ArtifactOwner.isFork(species)) {
            return java.util.List.of(own);
        }
        String ancestor = com.moddingfromamod.entity.WishMobTags.forSpecies(
                com.moddingfromamod.marshal.ArtifactOwner.baseOf(species));
        return ancestor.equals(own) || ancestor.equals(com.moddingfromamod.entity.WishMobTags.ALL)
                ? java.util.List.of(own) : java.util.List.of(own, ancestor);
    }

    /**
     * <b>Remove every display belonging to a carrier that is gone.</b>
     *
     * <p>Called on the carrier's death and, because a death can also be an unload or a
     * {@code /kill} the rig object never hears about, safe to call from a periodic sweep: it works
     * from tags in the world rather than from the in-memory rig, so it still works after a reload
     * when the {@code DisplayRig} object no longer exists.
     *
     * @return how many displays were removed (its hit proxies go too, uncounted)
     */
    public static int reap(ServerLevel level, java.util.UUID carrier) {
        String want = RIG_OWNER_PREFIX + carrier;
        // COLLECT, THEN DISCARD. Discarding inside the iteration mutates the level's entity
        // collection under the iterator and silently SKIPS the next match: measured, `reap removed
        // 5 of 6`, i.e. one display left standing per creature, forever, because a display entity
        // has no despawn rule. A reaper that removes almost all of them is the worst version of
        // this bug -- the world looks cleaned up and accumulates one ghost per kill.
        java.util.List<Entity> doomed = new ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if ((e instanceof Display && e.entityTags().contains(want))
                    || HitProxies.belongsTo(e, carrier)) {   //: its hit proxies too
                doomed.add(e);
            }
        }
        int displays = 0;
        for (Entity e : doomed) {
            displays += e instanceof Display ? 1 : 0;
            e.discard();
        }
        return displays;
    }

    /**
     * Vanilla mobs worth offering as a base, measured from the live registry.
     *
     * <p>The list is a starting set spanning the ratio range — tall and thin through squat and wide
     * — not a claim that these are the only options. Their dimensions are <b>read at runtime</b>:
     * an earlier attempt to lift them out of {@code EntityType}'s bytecode mis-paired names with
     * sizes and reported a warden as 0.6x1.95, which would have made Q4's error look smaller than
     * it is.
     */
    public static List<RigBase.Candidate> candidates() {
        return candidates(null);
    }

    /**
     * Vanilla mobs worth offering as a base, measured from the live registry.
     *
     * <p>Deliberately weighted toward NON-HOSTILE mobs — see {@link RigBase#PEACEFUL_RULE}. A
     * Monster base is discarded outright on a Peaceful world, which Gate C found the expensive way.
     * The hostile ones are still listed so the ranking can show what they would have offered, and
     * {@code RigBase.choose} refuses them.
     *
     * <p>Their dimensions and their hostility are both <b>read at runtime</b>: an attempt to lift
     * the dimensions out of {@code EntityType}'s bytecode produced a confidently wrong answer
     * twice, and a hardcoded "is this hostile" list is exactly the thing that goes stale.
     *
     * @param level a level to instantiate probes in; when null, hostility is unknown and every
     *              candidate is treated as unsafe rather than guessed at
     */
    public static List<RigBase.Candidate> candidates(net.minecraft.world.level.Level level) {
        // The rows are CarrierPool's: classified by how each mob moves, with what it does
        // besides carry written beside it. The sizes are still read from the live registry here,
        // and CarrierPoolGameTest fails if the table and the registry ever disagree.
        List<RigBase.Candidate> out = new ArrayList<>();
        for (CarrierPool.Skeleton row : CarrierPool.ALL) {
            EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getValue(
                    Identifier.withDefaultNamespace(row.id()));
            var d = t.getDimensions();
            out.add(new RigBase.Candidate(row.id(), d.width(), d.height(),
                    level != null && peacefulSafe(level, t)));
        }
        return out;
    }

    /** Half the width of the column over the carrier's middle a rider's seat is read from. */
    public static final double BACK_COLUMN = 0.25;

    /** The sculpt in rig space — the conversion {@link #spawn} and {@link #adopt} share. */
    static List<CreatureSculpt.Part> rigSpace(List<CreatureSculpt.Part> parts) {
        return "off".equalsIgnoreCase(MIRROR_MODE)
                ? RigFit.centreFootprint(parts) : RigFit.intoRigSpace(parts);
    }

    /**
     * Is this type a non-hostile Mob? Asked of a created instance, as before — and now asked ONCE
     * per type per server rather than once per type per SPAWN: the answer is a fact about the
     * class, and the old path created and discarded twenty-one probe entities for every creature
     * that manifested (an earlier issue's performance row).
     */
    private static boolean peacefulSafe(net.minecraft.world.level.Level level, EntityType<?> t) {
        return PEACEFUL_SAFE.computeIfAbsent(t, k -> {
            Entity probe = k.create(level, EntitySpawnReason.COMMAND);
            boolean safe = probe instanceof Mob
                    && !(probe instanceof net.minecraft.world.entity.monster.Enemy);
            if (probe != null) {
                probe.discard();
            }
            return safe;
        });
    }

    private static final java.util.Map<EntityType<?>, Boolean> PEACEFUL_SAFE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Build the rig: pick a base, scale it to the creature's height, and hang one item-display per
     * box off it, each wearing {@link AtomLibrary}'s constant cube in its box's colour.
     *
     * @param parts the sculpt, in RAW sculpt space — mirroring into Minecraft space happens here so
     *              no caller can forget it
     */
    public static DisplayRig spawn(ServerLevel level, Vec3 at, float yaw,
                                   List<CreatureSculpt.Part> parts, double scale,
                                   double wantWidth, double wantHeight) {
        return spawn(level, at, yaw, parts, scale, wantWidth, wantHeight, null);
    }

    /**
     * @param species the creature's species id, e.g. {@code "spikor"} — <b>and it is not cosmetic</b>.
     *
     *     <p>An ability the agent builds does not target an entity by id or by handle. {@code
     *     apply_effect} writes a datapack whose selectors are {@code @e[tag=wishmob]} and {@code
     *     @e[tag=wishmob_<species>]} — {@link com.moddingfromamod.entity.WishMobTags} exists so the
     *     agent never has to invent a selector, and {@code WishMobEntity.applySpeciesTag} puts both
     *     on every creature the mod spawns.
     *
     *     <p>The first cut of this class put neither on the carrier, so every ability ever built for
     *     a vanilla-path creature installed correctly, compiled correctly, reported {@code DONE},
     *     and then <b>selected nothing at all, forever</b>. There is no error anywhere: a selector
     *     that matches no entities is an ordinary tick that does nothing. Found by
     *     {@code VanillaRigSurvivalGameTest.anAgentBuiltAbilityCanFindTheCreature} and invisible to
     *     all four of the spike's own questions — a photograph, a packet count, an emission point
     *     measured off the base directly, and a raycast all stay green.
     *
     *     <p><b>The carrier only.</b> Tagging everything the rig spawns is the obvious way to do it
     *     and turns one fire breath into nineteen, because a selector counts entities and the rig is
     *     6–19 of them standing in the same place. Pinned by
     *     {@code exactlyOneEntityInTheRigMayWearTheAbilityTag}.
     * @param wantWidth  unused — the carrier is chosen on the rig as drawn
     *                   ({@link RigBase#chooseByFit}); kept so the harnesses that pass it compile
     * @param wantHeight unused, likewise
     */
    public static DisplayRig spawn(ServerLevel level, Vec3 at, float yaw,
                                   List<CreatureSculpt.Part> parts, double scale,
                                   double wantWidth, double wantHeight, String species) {
        return spawn(level, at, yaw, parts, scale, species,
                CarrierPool.movementOf(null, parts, species));
    }

    /**
     * @param move how the creature gets about ({@link CarrierPool#movementOf}) — it picks which
     *             carriers are allowed at all, before shape picks among them
     */
    public static DisplayRig spawn(ServerLevel level, Vec3 at, float yaw,
                                   List<CreatureSculpt.Part> parts, double scale,
                                   String species, CarrierPool.Move move) {
        // Rig space: mirrored, and slid so the creature's middle is over the carrier. Every
        // path that places a display goes through this one conversion (RigFit.intoRigSpace).
        List<CreatureSculpt.Part> rigSpace = rigSpace(parts);
        // NOT run through a scale: there is one shared model, so there is nothing per-creature to
        // fold a scale into (RigPlanAtomTest). The display transform carries it instead.
        List<RigPlan.Group> groups = RigPlan.atoms(rigSpace);

        // THE CARRIER IS CHOSEN ON THE CREATURE AS DRAWN: its resting box, from the same
        // transforms the displays are about to be given, against every carrier its movement class
        // allows where it stands. A swimmer out of water is offered only the amphibians.
        //
        // and HOW MANY OTHER MOBS it needs. One square box is the wrong shape for a long or
        // sprawling body, so the plan may choose a tighter carrier plus a few invisible proxy mobs
        // over the parts it leaves out -- as few as make clicks and arrows land the way they do on
        // a vanilla mob, and none at all for a creature that is its own box (HitProxyPlan).
        Planned planned = plan(level, at, groups, scale, species, move);
        HitProxyPlan.Plan hits = planned.hits();
        double seat = planned.seat();
        RigBase.Choice pick = hits == null ? null : hits.core();
        EntityType<?> baseType = pick == null ? EntityTypes.ZOMBIE
                : BuiltInRegistries.ENTITY_TYPE.getValue(
                        Identifier.withDefaultNamespace(pick.id()));
        Entity raw = baseType.create(level, EntitySpawnReason.COMMAND);
        if (!(raw instanceof Mob mob)) {
            throw new IllegalStateException("base " + baseType + " is not a Mob");
        }
        mob.snapTo(at.x, at.y, at.z, yaw, 0f);
        com.moddingfromamod.mob.RideSeat.setSeat(mob, seat);
        mob.setYBodyRot(yaw);
        mob.setYHeadRot(yaw);
        // INVISIBLE, NOT ABSENT. The base still has to be a real mob -- it carries the hitbox, the
        // health and the AI that make the creature fightable -- so it is hidden rather than removed.
        //
        // AND IT HAS TO BE THE EFFECT, NOT setInvisible(). LivingEntity.tick() calls
        // updateInvisibilityStatus(), which is literally
        // `setInvisible(hasEffect(MobEffects.INVISIBILITY))` -- so a manual setInvisible(true) is
        // overwritten on the very next tick. Measured in Gate C: the base was set invisible at
        // spawn and the photograph came back as a plainly visible HORSE with the rig's displays
        // as slivers under its belly. No assertion could have caught it; the picture did, which is
        // this repo's rule about photographs arriving intact.
        //
        // Infinite, ambient, no particles and no icon: a swirl of grey motes around every creature
        // would be its own visual bug.
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.INVISIBILITY,
                -1, 0, true, false, false));
        mob.setInvisible(true);
        mob.setSilent(true);
        mob.setNoAi(false);
        if (pick != null) {
            var attr = mob.getAttribute(Attributes.SCALE);
            if (attr != null) {
                attr.setBaseValue(pick.scale());
                // REFRESH, OR THE ATTRIBUTE DOES NOTHING.
                //
                // LivingEntity.getDimensions is getDefaultDimensions(pose).scale(getScale()), and
                // getScale() does read minecraft:scale -- but the bounding box Minecraft actually
                // collides against is CACHED, and only refreshDimensions() recomputes it. Setting
                // the attribute alone leaves a Spikor-sized creature colliding as a 1.95x2.20
                // ravager: measured exactly that in Gate B, where the rig predicted 3.99x4.50 and
                // the world reported the base's natural size.
                //
                // That is Q4's whole trap arriving early -- a hitbox that ignores the model, the
                // same defect WishMobEntity#getDefaultDimensions was written to fix -- and nothing
                // about it is visible without asking the live entity how big it is.
                mob.refreshDimensions();
            }
        }
        mob.setPersistenceRequired();
        // THE RIG IS TIED TO THE CARRIER BY A TAG, so a sweeper can find it after a reload.
        // Vanilla's death path knows nothing about the displays hanging off a mob, and a display
        // entity has no despawn rule -- so without this every creature ever killed leaves a
        // creature-shaped ghost standing in the world forever, 6-19 entities at a time, surviving
        // save and reload. Measured before the fix: `carrier killed -- 6 of 6 display entities
        // still alive`. The link has to be a TAG rather than a field because the rig object lives
        // only in memory and the world outlives it.
        mob.addTag(RIG_TAG);
        mob.addTag(RIG_OWNER_PREFIX + mob.getUUID());
        // The ability tags — carrier only, never the displays. See the species javadoc above.
        if (species != null && !species.isBlank()) {
            mob.addTag(com.moddingfromamod.entity.WishMobTags.ALL);
            for (String t : speciesTags(species)) {
                mob.addTag(t);
            }
        }
        level.addFreshEntity(mob);

        // 🔴 THE 180. Derived, then MEASURED, and it was the whole Q1 residual.
        //
        // WishMobRenderer draws a sculpt with Axis.YP.rotationDegrees(180 - bodyRot) — the standard
        // entity-model convention, because a bedrock model faces +Z in model space. A Display
        // applies its own yaw, so matching the two needs
        //     -displayYaw  ==  180 - bodyYaw     =>     displayYaw = bodyYaw + 180.
        //
        // MEASURED, on a deliberately asymmetric single box with no grouping, no rotation, no
        // shrink and no articulation to hide behind: sweeping the offset over 0/90/180/270 gave
        // deltas of 8.6% / 8.1% / **0.0%** / 5.3%. At 180 the vanilla display rig is
        // PIXEL-FOR-PIXEL IDENTICAL to the mod renderer — both silhouettes 853x335 at (0,144).
        //
        // Why it cost so much to find: a 180-degree error keeps a creature the right SIZE, the
        // right HEIGHT and the right COLOURS, and only moves the parts. Four other causes were
        // proposed and refuted first — rotation snapping, ancestor-chain composition, the
        // model-to-block constant and the model origin — and none of them moved the number,
        // because none of them was this.
        List<Entity> made = hang(level, mob, at, yaw, groups, scale, 0, species);
        WishShimmer.spawned(species, mob.getUUID(), level.getGameTime());
        DisplayRig rig = new DisplayRig(mob, made, groups, scale, pick);
        rig.takeProxies(level, hits, yaw);
        return rig;
    }

    /** How long a reshape in place eases each display from its old shape to its new one. */
    public static final int MORPH_TICKS = 8;

    /**
     * <b>Change a standing creature's shape on the carrier it already has</b>, or return
     * null when it needs a different carrier and must be rebuilt.
     *
     * <p>A re-sculpt used to discard the carrier and stand a new one (VanillaRigAnimator.respawn):
     * a new UUID, its state carried across by hand, and a creature that blinked out and back. But
     * the carrier only HAS to change when the carrier choice does, and that is the very decision a
     * spawn makes ({@link #plan}): asked again for the new atoms where the creature stands, it
     * names a carrier TYPE and a scale. When the type is the one it already rides:
     * <ul>
     *   <li>the carrier stays (UUID, health, pet behaviour, leash, riders, handles, name), and is
     *       resized with {@code minecraft:scale}, its box refreshed;</li>
     *   <li>the displays MORPH when the new shape has as many atoms as the old: each is re-worn
     *       and eased to its new transform over {@link #MORPH_TICKS} ticks, so "make him bigger"
     *       grows rather than pops; otherwise a new set is hung on the same carrier;</li>
     *   <li>the hit proxies are planned again for the new body.</li>
     * </ul>
     *
     * @param parts the new sculpt, in RAW sculpt space (as {@link #spawn} takes it)
     */
    public static DisplayRig reshapeInPlace(ServerLevel level, DisplayRig old,
                                            List<CreatureSculpt.Part> parts, double scale,
                                            String species, CarrierPool.Move move) {
        if (old == null || old.base() == null || !old.base().isAlive()) {
            return null;
        }
        Mob mob = old.base();
        List<RigPlan.Group> groups = RigPlan.atoms(rigSpace(parts));
        Planned planned = plan(level, mob.position(), groups, scale, species, move);
        RigBase.Choice pick = planned.hits() == null ? null : planned.hits().core();
        if (pick == null || !sameCarrier(pick.id(),
                BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath())) {
            return null;
        }
        var attr = mob.getAttribute(Attributes.SCALE);
        if (attr != null) {
            attr.setBaseValue(pick.scale());
            mob.refreshDimensions();   // or the box it collides with stays the old size (spawn's note)
        }
        com.moddingfromamod.mob.RideSeat.setSeat(mob, planned.seat());
        List<Entity> displays;
        if (old.displays.size() == groups.size()) {
            displays = new ArrayList<>(old.displays);
            for (int i = 0; i < groups.size(); i++) {
                RigPlan.Group g = groups.get(i);
                Entity d = displays.get(i);
                wear(d, AtomLibrary.modelDataFor(g), AtomLibrary.colourFor(g));
                apply(d, RigTransform.placeAtom(g, scale), MORPH_TICKS);
            }
            List<Integer> kept = new ArrayList<>(displays.size());
            for (Entity d : displays) {
                kept.add(d.getId());
            }
            RigVeil.restamp(kept, level.getGameTime());   // the reveal keeps the newest word
        } else {
            // A DIFFERENT PART COUNT: the old displays are not discarded here but handed
            // to the transformation, which whitens and shrinks them while the new set grows out of
            // white (VanillaRigAnimator, RigTransformation). Tagged so a server stop mid-way never
            // adopts them as the creature's (adopt and the reaper discard a LEAVING display).
            for (Entity d : old.displays) {
                d.addTag(LEAVING_TAG);
            }
            displays = hang(level, mob, mob.position(), mob.yBodyRot, groups, scale, 0, species);
        }
        old.discardProxies();
        DisplayRig rig = new DisplayRig(mob, displays, groups, scale, pick);
        if (old.displays.size() != groups.size()) {
            rig.leaving = List.copyOf(old.displays);
        }
        rig.nameplate = old.nameplate;
        rig.takeProxies(level, planned.hits(), mob.yBodyRot);
        return rig;
    }

    /** Is a carrier the plan names the one already standing? Pure: ids with or without a namespace. */
    static boolean sameCarrier(String planned, String standing) {
        if (planned == null || standing == null) {
            return false;
        }
        String a = planned.contains(":") ? planned.substring(planned.indexOf(':') + 1) : planned;
        String b = standing.contains(":") ? standing.substring(standing.indexOf(':') + 1) : standing;
        return a.equals(b);
    }

    /** The carrier and proxies a rig of these atoms gets where it stands, and where he would sit. */
    private record Planned(HitProxyPlan.Plan hits, double seat) {
    }

    /**
     * Choose the carrier and its proxies for these atoms at {@code at}: the one
     * decision {@link #spawn} and {@link #reshapeInPlace} share, so an in-place reshape keeps its
     * carrier exactly when a fresh spawn would have picked the same one.
     */
    private static Planned plan(ServerLevel level, Vec3 at, List<RigPlan.Group> groups, double scale,
                                String species, CarrierPool.Move move) {
        boolean inWater = level.getFluidState(net.minecraft.core.BlockPos.containing(at))
                .is(net.minecraft.tags.FluidTags.WATER);
        // A creature whose species follows the child is never offered a carrier that
        // cannot follow (CarrierPool.CANNOT_FOLLOW) -- changed HERE, in the input to the plan,
        // because the proxies are planned around whichever carrier it picks.
        boolean follows = Boolean.TRUE.equals(SpeciesOnRig.behaviour(
                species == null ? null : com.moddingfromamod.species.SpeciesRegistry.get(species))
                .follow());
        // and only a creature meant to fight rides a carrier that monsters hunt.
        var sp = species == null ? null : com.moddingfromamod.species.SpeciesRegistry.get(species);
        boolean fights = sp != null && (sp.base() == com.moddingfromamod.species.Species.Base.GUARDIAN
                || sp.base() == com.moddingfromamod.species.Species.Base.HUNTER);
        // 2026-10-11: and a creature he RIDES never gets a carrier that throws him off.
        boolean rides = sp != null && sp.has(com.moddingfromamod.species.Species.Power.RIDEABLE);
        // And where he sits: the top of its back, which the carrier's own seat must not be above
        // (RideSeat -- a seat can be raised and never lowered).
        double seat = rides ? RigFit.backTop(RigFit.atoms(groups, scale), BACK_COLUMN) : Double.NaN;
        // the CARRIER is not filtered for a creature he cannot ride (only its proxies are, takeProxies):
        // filtering it changed the carrier of every such creature, which SafeSpotGameTest and
        // VanillaRigBridgeGameTest measured as a different rig. A camel/horse/llama carrier is the partial left.
        return new Planned(HitProxies.plan(RigFit.atoms(groups, scale), candidates(level),
                CarrierPool.forArchetype(CarrierPool.forInstinct(CarrierPool.allowed(
                        move == null ? CarrierPool.Move.WALK : move, inWater, follows, rides), fights),
                        sp != null && sp.archetype() != null),
                seat), seat);
    }

    /**
     * Hang one item display per atom group off {@code mob}, tagged to it and in order (the loop
     * {@link #spawn} always ran; {@link #reshapeInPlace} hangs a new set on the carrier it keeps).
     *
     * @param interpolationTicks how long the client eases each display into place; 0 snaps
     * @param species            the creature's species, for the veil
     */
    private static List<Entity> hang(ServerLevel level, Mob mob, Vec3 at, float yaw,
                                     List<RigPlan.Group> groups, double scale, int interpolationTicks,
                                     String species) {
        float rigYaw = displayYaw(yaw);
        List<Entity> made = new ArrayList<>();
        for (int gi = 0; gi < groups.size(); gi++) {
            RigPlan.Group g = groups.get(gi);
            Entity d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            if (d == null) {
                continue;
            }
            d.snapTo(at.x, at.y, at.z, rigYaw, 0f);
            // Which carrier this display belongs to, so `reap` can clear it when the creature dies
            // even after a reload has thrown the in-memory rig away. NOT the ability tag: exactly
            // one entity in a rig may wear that, or one fire breath fires nineteen times.
            d.addTag(RIG_OWNER_PREFIX + mob.getUUID());
            level.addFreshEntity(d);
            // ORDER ON A TAG, because every display wears the SAME custom_model_data, so the
            // number cannot say which atom this is -- `adopt` reads this instead. (A PATTERN
            // overlay wears its own pattern's number, which does not restore the ordering: the
            // base cubes still share one.)
            d.addTag(RIG_INDEX_PREFIX + gi);
            wear(d, AtomLibrary.modelDataFor(g), AtomLibrary.colourFor(g));
            matchLight(d, level, mob);
            matchViewRange(d, mob);
            apply(d, RigTransform.placeAtom(g, scale), interpolationTicks);
            made.add(d);
        }
        // 🔴 #515b: the rig is BUILT here whatever happens, and a client that is not getting the
        // pack is simply not told about it. Placed at the one method every rig's displays are hung
        // by (a spawn, and a reshape in place that needs a new set), and AFTER
        // addFreshEntity rather than before, because vanilla's tracker has already sent the spawn
        // by then -- so the hide rides out in the same tick's flush and nothing is drawn in
        // between. See RigVeilService for why this is packets and not a flag on the entity.
        // ...unless its wish has already shown him this creature (the early reveal):
        // a repaint or reshape of a creature he is watching shimmer appears at once.
        RigVeilService.veil(level, made, species);
        return made;
    }

    /**
     * <b>Pin every display to the light the BASE MOB is lit by.</b>
     *
     * <p>A mob is lit once, at its own eye, and the whole model is drawn at that brightness. A rig
     * is nineteen separate entities spread over three blocks, each of which would otherwise be lit
     * at ITS OWN position — so a creature whose head is in a brighter block than its feet would be
     * drawn as two different brightnesses. This is the light at SPAWN; {@link #followLight} keeps
     * it current every write cycle after that.
     *
     * <p>⚠ <b>For seven weeks this was the ONLY write</b>, and the doc here said "measured neutral"
     * because the test world was fully lit. Gate C {@code vanillalight} measured what that cost: a
     * rig that appeared in a dark room stayed BLACK once the room was lit (luma 8 against a
     * villager's 34 beside it), and one that appeared in the sun glowed in a dim room.
     */
    public static void matchLight(Entity display, ServerLevel level, Entity base) {
        writeLight(display, lightAt(level, base));
    }

    /** Vanilla's light for a mob standing where {@code base} stands — {@link RigLight#of}. */
    public static RigLight lightAt(ServerLevel level, Entity base) {
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(
                base.getEyePosition(1.0f));
        return RigLight.of(
                level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at),
                level.getBrightness(net.minecraft.world.level.LightLayer.SKY, at),
                base.isOnFire());
    }

    /** One field, through the setter: an unchanged value dirties nothing and sends nothing. */
    static void writeLight(Entity display, RigLight light) {
        if (display instanceof com.moddingfromamod.mixin.DisplayLightInvoker d) {
            d.moddingfromamod$setBrightnessOverride(
                    new net.minecraft.util.Brightness(light.block(), light.sky()));
        }
    }

    /** What {@link #followLight} last wrote, or null before its first call for this rig. */
    private RigLight lightShown;

    /** The displays {@link #setEmissive} made fullbright: {@link #followLight} leaves them be. */
    private final java.util.BitSet emissive = new java.util.BitSet();

    /**
     * Light writes since the server started, and how many of them landed on a display nothing else
     * had dirtied this tick (so cost a packet of their own rather than riding a pose's). Read by
     * {@code RigPerfProbe} (an earlier issue's cost arm); server thread only.
     */
    static long lightWrites;
    static long lightOwnPackets;

    /**
     * <b>Keep the rig in the light the carrier stands in</b>. Called by
     * {@code VanillaRigAnimator} after the pose write of each animator write tick
     * ({@link RigLight#sampleOn}); writes every non-emissive display only when the light at the
     * carrier's eye CHANGED, so a creature standing still, or nightfall (the sky LEVEL does not
     * change at dusk), costs nothing.
     *
     * @return how many displays were rewritten
     */
    public int followLight(ServerLevel level) {
        if (whiteness > 0.5f) {
            return 0;   // a white body glows; its own light comes back as it colours in
        }
        RigLight write = RigLight.next(lightShown, lightAt(level, base));
        if (write == null) {
            return 0;
        }
        lightShown = write;
        int n = 0;
        for (int i = 0; i < displays.size(); i++) {
            if (emissive.get(i)) {
                continue;
            }
            Entity d = displays.get(i);
            boolean riding = d.getEntityData().isDirty();
            writeLight(d, write);
            if (d.getEntityData().isDirty()) {
                n++;
                lightWrites++;
                if (!riding) {
                    lightOwnPackets++;
                }
            }
        }
        return n;
    }

    /**
     * Give a display the carrier item that resolves to this group's generated model.
     *
     * <p>1.21.1 cannot point a stack at a model id directly, so every piece is the same vanilla
     * carrier item and the {@code CustomModelData} number selects the override — see
     * {@link RigCarrier#CARRIER}. Written through the entity's own save format, like the
     * transform, because {@code Display.ItemDisplay.setItemStack} is private too.
     *
     * <p><b>The failure this shape produces is loud and worth recognising:</b> if the pack is
     * missing, or the number does not match an override, the client falls back to the carrier's own
     * model and the creature renders as a cloud of paper — not as a magenta cube, and not as
     * nothing. A rig that renders as paper is a PACK problem; a rig that renders magenta is a MODEL
     * problem. They want different fixes.
     *
     * <p>✅ <b>PHOTOGRAPHED, and this line was right where eight others were wrong.</b> Eight sites
     * had drifted to "untextured blocks" as shorthand for the same failure, which erases the whole
     * diagnostic — it points at a texture fault when the fault is delivery. Both arms are paper: a
     * stock client with no pack resolves no override, and (deleted the direct-install
     * route) a modded client whose fetch failed resolved this same carrier model too. The A/B is {@code docs/PACK_DELIVERY.md} §12.
     */
    /**
     * <b>Rebuild the rig object for a carrier that is already standing in the world.</b>
     *
     * <p>Everything a rig IS survives a server restart — the carrier, the display entities, their
     * transforms, their items, the tags that link them. The only thing that does not is the
     * in-memory object that lets something drive them, so without this a creature comes back from
     * a save as a perfect statue: correct in every way except that nobody is animating it, and
     * with no error anywhere because nothing failed.
     *
     * <p>🔴 <b>The ORDER is recovered from the world, not assumed.</b> {@code groups} and
     * {@code displays} are index-matched by every driver in this package, and
     * {@code level.getEntities()} hands them back in whatever order it likes. Each display carries
     * {@link #RIG_INDEX_PREFIX}{@code <i>}, so the ordering is stored in the world rather than
     * reconstructed by luck. Sorting by anything else (entity id, position, spawn order) works on
     * the day it is written and silently permutes a creature's limbs later.
     *
     * @return the rebuilt rig, or {@code null} if this carrier has no displays or they do not
     *         match the atom plan — a re-sculpt the world never saw, or a rig saved by the pack
     *         rig deleted, whose displays carry no index tags. The caller
     *         ({@code VanillaRigAnimator.adoptAll}) REBUILDS such a creature rather than leaving
     *         it a statue
     */
    public static DisplayRig adopt(ServerLevel level, Mob carrier,
                                   List<CreatureSculpt.Part> parts, double scale) {
        return adopt(level, carrier, parts, scale, null);
    }

    /**
     * {@link #adopt(ServerLevel, Mob, List, double)}, knowing how the creature moves — which lets
     * its hit proxies come back EXACTLY as they were planned at spawn, rather than
     * re-planned around the carrier alone.
     */
    public static DisplayRig adopt(ServerLevel level, Mob carrier,
                                   List<CreatureSculpt.Part> parts, double scale,
                                   CarrierPool.Move move) {
        List<RigPlan.Group> groups = RigPlan.atoms(rigSpace(parts));

        String want = RIG_OWNER_PREFIX + carrier.getUUID();
        java.util.TreeMap<Integer, Entity> byIndex = new java.util.TreeMap<>();
        List<Entity> stale = new ArrayList<>();
        List<Entity> plates = new ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if (!(e instanceof Display) || !e.entityTags().contains(want)) {
                continue;
            }
            if (e.entityTags().contains(LEAVING_TAG)) {
                stale.add(e);   // a transformation the server stopped in the middle of
                continue;
            }
            int index = indexOf(e);
            if (index >= 0) {
                byIndex.put(index, e);
            } else if (e instanceof Display.TextDisplay) {
                plates.add(e);   // its saved name plate: one is kept below, any extra goes
            }
        }
        stale.forEach(Entity::discard);
        if (byIndex.size() != groups.size()) {
            return null;
        }
        List<Entity> ordered = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            Entity d = byIndex.get(i);
            if (d == null) {
                // A gap in the run means these displays are not this plan's -- refuse rather than
                // drive a creature whose head is wearing its leg.
                return null;
            }
            ordered.add(d);
        }
        // 🔴 RE-SEAT EVERY DISPLAY AT REST. A display's transform is saved with the world,
        // and one saved centred the rig carries the OLD offset: the drivers rewrite
        // only the bones an animation moves, so an idle body would stay where it was saved while
        // a swinging arm jumped to the centred plan — a creature torn in two on its first step.
        // One write per display, once per load, makes every rig agree with this plan whatever
        // build saved it: the migration is to the world we own, and no reader of the old shape
        // survives (docs/COMPAT_SHIMS.md, "we can rewrite it").
        for (int i = 0; i < groups.size(); i++) {
            apply(ordered.get(i), RigTransform.placeAtom(groups.get(i), scale), 0);
        }
        DisplayRig rig = new DisplayRig(carrier, ordered, groups, scale, null);
        // The plate saved with the world is THIS rig's: adopted, so the first wearLook re-reads the
        // carrier's name into it rather than standing a second plate beside it on every load.
        if (!plates.isEmpty()) {
            rig.nameplate = plates.get(0);
            plates.subList(1, plates.size()).forEach(Entity::discard);
        }
        // Its own colours, whatever was saved: a server stop in the middle of a transformation
        // saves a body part-way to white. One write per display, once per load.
        rig.lastTint = REWRITE;
        rig.tint(RigTint.NONE);
        // proxies are never saved (HitProxies refuses one read back from disk), so an
        // adopted rig is given fresh ones -- placed by the PLAN saved with its carrier when that
        // plan was made from exactly this creature on exactly this carrier (HitProxies.recall),
        // and otherwise planned around the carrier it already has, which keeps its health, name
        // and leash. Planning was ~57% of a load's stall, at 40-100 ms a creature on a dedicated server.
        List<RigFit.Atom> atoms = RigFit.atoms(groups, scale);
        List<RigBase.Candidate> cands = candidates(level);
        HitProxyPlan.Plan saved = HitProxies.recall(carrier, atoms, cands);
        HitProxyPlan.Plan hits;
        if (saved != null) {
            HitProxies.RECALLED.incrementAndGet();
            hits = saved;
        } else {
            HitProxies.REPLANNED.incrementAndGet();
            boolean inWater = level.getFluidState(carrier.blockPosition())
                    .is(net.minecraft.tags.FluidTags.WATER);
            hits = HitProxies.planFor(atoms, carrier, cands,
                    move == null ? null : CarrierPool.allowed(move, inWater));
        }
        rig.takeProxies(level, hits, carrier.yBodyRot);
        return rig;
    }

    /**
     * @param tintRgb 0xRRGGBB to tint the whole model, or -1 for {@link RigTint#NONE}. The
     *                carrier is dyeable ({@link RigCarrier}) and the atom cube's faces declare a
     *                {@code tintindex}, so this is the atom rig's colour channel.
     */
    public static void wear(Entity display, int customModelData, int tintRgb) {
        CompoundTag item = carrierItem(customModelData, tintRgb);
        // ONE FIELD, not the whole entity. This used to save the display's entire NBT,
        // swap `item` and load it all back -- a full codec round trip per display (12% of a
        // 40-creature load; a hurt flash repaints every display), which also re-set every synced
        // field and re-sent the item to every watcher each time (an ItemStack has no equals; see
        // DisplayLightInvoker). Now: parse the item alone and set it, and skip a display that is
        // already wearing exactly it (the load-time REWRITE then costs nothing for a body
        // saved in its own colours, and still repaints one saved half-white).
        if (display instanceof com.moddingfromamod.mixin.ItemDisplayItemInvoker d) {
            var parsed = com.moddingfromamod.compat.EntityNbt.parseStack(display.registryAccess(), item);
            if (parsed.isEmpty()) {
                // The round trip hid this: a refused item left the old one on, silently (an earlier issue's
                // 0 of 64). Say it once; leaving the display as it is matches what that path did.
                if (!wearRefusedLogged) {
                    wearRefusedLogged = true;
                    LOG.error("rig: the carrier item {} was refused by this Minecraft's item codec -- "
                            + "displays keep their old look; a component is spelled for another version", item);
                }
                return;
            }
            net.minecraft.world.item.ItemStack next = parsed.get();
            net.minecraft.world.item.ItemStack now = d.moddingfromamod$getItemStack();
            if (now.getCount() == next.getCount()
                    && net.minecraft.world.item.ItemStack.isSameItemSameComponents(now, next)) {
                return;
            }
            wearWrites++;
            d.moddingfromamod$setItemStack(next);
            return;
        }
        wearByRoundTrip(display, item);
    }

    /** Displays {@link #wear} actually rewrote (not skipped as already right) — for tests and the probe. */
    static volatile long wearWrites;

    private static boolean wearRefusedLogged;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("mfam-rig");

    /**
     * The pre-#1869 way: the whole entity saved, {@code item} swapped, loaded back. Kept as the
     * REFERENCE {@code WearDirectGameTest} compares the direct path against, and for an entity that
     * is not an item display (none today).
     */
    static void wearByRoundTrip(Entity display, CompoundTag item) {
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(display);
        tag.put("item", item);
        com.moddingfromamod.compat.EntityNbt.load(display, tag);
    }

    /** The carrier item a display wears: the one place its components are spelled. */
    static CompoundTag carrierItem(int customModelData, int tintRgb) {
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:" + RigCarrier.CARRIER);
        item.putInt("count", 1);
        CompoundTag components = new CompoundTag();
        // The number AND, on 1.21.4, the atom's own item_model: a new atom draws by name.
        com.moddingfromamod.compat.ModelData.putWithModel(components, customModelData);
        // 🔴 WRITE A COLOUR EVEN WHEN THERE IS NO TINT. The carrier is a dyeable item and every
        // face of the atom cube declares `tintindex: 0`, so vanilla
        // asks its dyed handler for a colour on every face -- and that handler's fallback is
        // `DyedItemColor.getOrDefault(stack, -6265536)`, i.e. 0xA06540, UNDYED LEATHER BROWN. A
        // display with no `dyed_color` component is therefore not untinted; it is multiplied by
        // brown. The bug would be in the ABSENCE of a component, so nothing in the pack, the model
        // or this method would look wrong. See RigTint.NONE.
        // since 1.21.5 the component is the bare int — the old {rgb:N} compound is refused by
        // the codec, and the display silently kept its old colour (measured: 0 of 64 recoloured).
        components.putInt("minecraft:dyed_color", tintRgb >= 0 ? tintRgb : RigTint.NONE);
        item.put("components", components);
        return item;
    }

    /**
     * Tint every display in the rig — the damage-flash / poison / wither emulation.
     *
     * @param tintRgb 0xRRGGBB, or -1 to clear
     * @return how many displays were rewritten
     */
    public int tint(int tintRgb) {
        // A tint that has not changed costs nothing -- the same rule `lookAt` and `animate` follow
        // for a bone that did not move, and the reason a driver can call this every tick. Without
        // it, a creature standing still repays 41 item rewrites a tick forever.
        if (tintRgb == lastTint) {
            return 0;
        }
        lastTint = tintRgb;
        int n = 0;
        for (int i = 0; i < displays.size() && i < groups.size(); i++) {
            // 🔴 THE TINT IS THE COLOUR CHANNEL, so a flash MULTIPLIES the box's own colour
            // rather than replacing it -- writing `tintRgb` straight through would repaint a whole
            // creature one flat red for the ten ticks after a hit. RigTint.multiply IS the
            // arithmetic vanilla's shader does, and multiply(colour, RigTint.NONE) == colour, so
            // the untinted case needs no branch.
            wear(displays.get(i), AtomLibrary.modelDataFor(groups.get(i)),
                    RigTint.multiply(ownColour(groups.get(i)),
                            tintRgb < 0 ? RigTint.NONE : tintRgb));
            n++;
        }
        return n;
    }

    /** The last colour {@link #tint} actually wrote, so an unchanged tint is free. */
    private int lastTint = RigTint.NONE;

    /** What {@link #lastTint} holds when the next {@link #tint} must write whatever it is asked. */
    private static final int REWRITE = -3;

    // ── THE TRANSFORMATION ─────────────────────────────────────────────────────────────
    //
    // How far towards white every box is, under whatever tint or shimmer is on: a creature that is
    // transforming goes white and comes back through the SAME colour channel the hurt flash and the
    // shimmer use, so they compose instead of fighting (a hit while it colours in still flashes).

    /** Marks a display (or a carrier) on its way out of a transformation: never adopted, reaped on load. */
    public static final String LEAVING_TAG = "moddy_rig_leaving";

    /** 0 = its own colours, 1 = pure white. */
    private float whiteness = 0f;

    /** Displays this rig replaced on the same carrier (a different part count), still fading out. */
    private List<Entity> leaving = List.of();

    /** A group's colour after the whiteness, before any tint. */
    private int ownColour(RigPlan.Group g) {
        int c = AtomLibrary.colourFor(g);
        return whiteness <= 0f ? c : RigTint.towardWhite(c, whiteness);
    }

    public float whiteness() {
        return whiteness;
    }

    /**
     * Set how white the body is; the next {@link #tint} or {@link #shimmer} writes it. A driven rig
     * calls one of them every tick ({@code WishShimmer.drive}), so this is all a driven rig needs.
     */
    public void setWhiteness(float w) {
        float k = Math.max(0f, Math.min(1f, w));
        if (k != whiteness) {
            whiteness = k;
            lastTint = REWRITE;
        }
    }

    /** Set the whiteness and write it NOW: for a body nobody drives (the leaving one). */
    public int whiten(float w) {
        setWhiteness(w);
        return tint(RigTint.NONE);
    }

    /** Every display fullbright (a body that is white should glow, not shade), or back to the carrier's light. */
    public void fullBright(ServerLevel level, boolean on) {
        RigLight light = on ? new RigLight(RigLight.MAX, RigLight.MAX)
                : (base == null ? new RigLight(RigLight.MAX, RigLight.MAX) : lightAt(level, base));
        for (Entity d : displays) {
            writeLight(d, light);
        }
        lightShown = on ? null : light;
        bright = on;
    }

    /** Whether {@link #fullBright} last turned it on (a native creature is told). */
    private boolean bright;

    public boolean isFullBright() {
        return bright;
    }

    /** The factor {@link #scaleBody} last scaled the body by (a native creature is told). */
    private float bodyScale = 1f;

    public float bodyScale() {
        return bodyScale;
    }

    /**
     * The whole body at rest, scaled by {@code f} about its own centre, eased by the client over
     * {@code ticks}. One write per display — a transformation's swell, shrink and grow.
     */
    public void scaleBody(float f, int ticks) {
        bodyScale = f;
        List<float[]> ts = new ArrayList<>(groups.size());
        for (RigPlan.Group g : groups) {
            var t = placed(g).translation();
            ts.add(new float[] {t.x(), t.y(), t.z()});
        }
        float[] c = TransformPlan.centre(ts);
        for (int i = 0; i < displays.size() && i < groups.size(); i++) {
            RigTransform.Placed p = placed(groups.get(i));
            float[] t = TransformPlan.about(ts.get(i), c, f);
            apply(displays.get(i), new RigTransform.Placed(new org.joml.Vector3f(t[0], t[1], t[2]),
                    p.leftRotation(), new org.joml.Vector3f(p.scale()).mul(f), p.rightRotation()), ticks);
        }
    }

    /** The displays this rig replaced on its carrier and that are still fading out. */
    public List<Entity> leaving() {
        return leaving;
    }


    /** What {@link #lastTint} holds while the shimmer owns the colours: no single tint is on. */
    private static final int SHIMMERING = -2;

    /**
     * <b>One frame of the in-progress shimmer</b> ({@link RigShimmer}): every box
     * gets its own colour, multiplied over its own, a little further round the wheel the higher it
     * sits in the body, so a rainbow climbs up the creature while it is still being wished into
     * shape. Through the same colour channel as {@link #tint}; the hurt flash simply wins on the
     * ticks it is drawn ({@code VanillaRigAnimator}).
     *
     * @return how many displays were rewritten
     */
    public int shimmer(long gameTime) {
        int n = groups.size();
        if (n == 0) {
            return 0;
        }
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            ys[i] = placed(groups.get(i)).translation().y();
            lo = Math.min(lo, ys[i]);
            hi = Math.max(hi, ys[i]);
        }
        float span = hi - lo < 1e-4f ? 1f : hi - lo;
        int written = 0;
        for (int i = 0; i < displays.size() && i < n; i++) {
            int wave = RigShimmer.tintFor(gameTime, (ys[i] - lo) / span);
            wear(displays.get(i), AtomLibrary.modelDataFor(groups.get(i)),
                    RigTint.multiply(ownColour(groups.get(i)), wave));
            written++;
        }
        lastTint = SHIMMERING;
        return written;
    }

    /** Is the shimmer on this rig now (so stopping it has something to put back)? */
    public boolean shimmering() {
        return lastTint == SHIMMERING;
    }

    /** Stop the shimmer: every box back to its own colour. Free when it was not shimmering. */
    public int clearShimmer() {
        return shimmering() ? tint(RigTint.NONE) : 0;
    }

    /** What {@link #tint} last wrote — for a test that wants to assert the driver's decision. */
    public int currentTint() {
        return lastTint;
    }

    /**
     * Write a transform onto a display entity, through the entity's own save format.
     *
     * @param interpolationTicks how long the CLIENT should tween into this pose. Zero snaps.
     */
    public static void apply(Entity display, RigTransform.Placed p, int interpolationTicks) {
        var t = new com.mojang.math.Transformation(
                p.translation(), p.leftRotation(), p.scale(), p.rightRotation());
        // THE THREE POSE FIELDS, SET DIRECTLY. This was a saveWithoutId -> load round trip,
        // which re-read the whole display every write -- and on 1.21.1 the server then sent every
        // posed display a position update with an unchanged target, restarting its easing while
        // the unposed ones carried on: a standing creature turning its body drew ~5 degrees apart.
        // It also re-sent each display's item stack on every write (an ItemStack has no equals).
        // Nothing else a pose needs is in the entity: teleport_duration is the rig's own
        // (MOTION_TICKS) and view_range is matchViewRange's, both written once at spawn.
        // A WRITE THAT CHANGES NOTHING SENDS NOTHING. Vanilla's delay setter forces a resend
        // (`set(…, true)`), and on the client a new start tick restarts the tween while the
        // interpolator still runs from the display's PREVIOUS pose to this one -- so re-writing an
        // unchanged transform snaps the display back to where it was before its last change and
        // tweens it forward again. A held sit re-wrote every display every 3 ticks (its whole-body
        // root marks every bone moved) and the creature was seen to twitch through the whole
        // hold. Equal is exact: the pose is recomputed from the same floats.
        if (t.equals(com.moddingfromamod.mixin.DisplayPoseInvoker
                .moddingfromamod$createTransformation(display.getEntityData()))) {
            return;
        }
        var pose = (com.moddingfromamod.mixin.DisplayPoseInvoker) display;
        pose.moddingfromamod$setTransformation(t);
        pose.moddingfromamod$setTransformationInterpolationDuration(Math.max(0, interpolationTicks));
        // start_interpolation is RELATIVE to now, and -1 means "already started". Vanilla treats a
        // start it has already passed as an instant snap, which is what makes a zero-duration
        // update land immediately instead of a tick late.
        pose.moddingfromamod$setTransformationInterpolationDelay(interpolationTicks > 0 ? 0 : -1);
    }

    /** Vanilla text-display entity type id — spelt once, beside the item-display one. */
    public static final Identifier TEXT_DISPLAY =
            Identifier.withDefaultNamespace("text_display");

    /**
     * How far above the creature's head the plate floats, <b>derived from the two renderers rather
     * than tuned by eye</b>.
     *
     * <p>Vanilla's name tag translates to the {@code NAME_TAG} attachment (the top of the box for
     * an ordinary mob) <b>plus 0.5</b>, then scales by {@code (0.025, -0.025, 0.025)} — a NEGATIVE
     * Y — and draws the glyphs at font y 0..9. So its text hangs DOWNWARD from that point and
     * occupies {@code head + 0.275 .. head + 0.5}.
     *
     * <p>A {@code text_display} does the opposite. {@code TextDisplayRenderer} computes
     * {@code l = lines * 10} and does {@code translate(1 - k/2, -l, 0)} in the scaled space, so its
     * text hangs UPWARD from the entity and occupies {@code y + 0.025 .. y + 0.25}.
     *
     * <p>Placed naively at {@code head + 0.5} the plate therefore floats <b>exactly one line height
     * — 10 × 0.025 = 0.25 blocks — too high</b>, which is what the first build measured (~0.26
     * blocks by pixel, on a creature 4.5 blocks tall). The lift is 0.5 minus that line, and it is
     * written as the subtraction so the next reader can check it rather than trust it.
     */
    public static final double TEXT_LINE_BLOCKS = 10 * 0.025;

    public static final double NAME_TAG_LIFT = 0.5 - TEXT_LINE_BLOCKS;

    /**
     * <b>Give the creature a name plate — or take it away.</b>
     *
     * <p>Vanilla's own name tag cannot reach this rig, and the reason is a method one level down
     * from the one it is natural to read: {@code EntityRenderer.render} calls {@code renderNameTag}
     * with no invisibility guard, but {@code LivingEntityRenderer} <b>overrides</b>
     * {@code shouldShowName} and ends {@code && !entity.isInvisibleTo(localPlayer)}. The carrier is
     * hidden by the INVISIBILITY effect, so that is false forever.
     *
     * <p>⚠ <b>The tempting escape does not work and would look worse.</b> {@code isInvisibleTo}
     * returns false when the viewer shares a team with {@code canSeeFriendlyInvisibles}, which does
     * bring the tag back — and {@code LivingEntityRenderer.render} then computes
     * {@code flag1 = !bodyVisible && !isInvisibleTo(player)} and draws the carrier as a
     * TRANSLUCENT GHOST. You would get the name tag and a spectral horse.
     *
     * <p>So the plate is a {@code text_display}, which is the same family of entity the rig is
     * already made of and is gated on nothing. It needs almost no tuning, because
     * {@code DisplayRenderer.TextDisplayRenderer} scales text by {@code 0.025} — the identical
     * magnitude {@code EntityRenderer.renderNameTag} uses — so the text comes out the same size at
     * the same distance with no scale of our own.
     *
     * @param name the text, or null to remove the plate
     */
    public void setName(net.minecraft.network.chat.Component name) {
        if (name == null) {
            if (nameplate != null) {
                nameplate.discard();
                nameplate = null;
            }
            return;
        }
        if (nameplate == null) {
            Entity made = EntityTypes.TEXT_DISPLAY.create(base.level(), EntitySpawnReason.COMMAND);
            if (made == null) {
                return;
            }
            made.addTag(RIG_OWNER_PREFIX + base.getUUID());
            made.snapTo(base.getX(), plateY(), base.getZ(), 0f, 0f);
            // The plate is moved with the rig every tick, so it eases like the rig does.
            smoothMotion(made);
            base.level().addFreshEntity(made);
            nameplate = made;
        }
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(nameplate);
        net.minecraft.network.chat.ComponentSerialization.CODEC
                .encodeStart(NbtOps.INSTANCE, name)
                .result()
                .ifPresent(encoded -> tag.put("text", encoded));
        // CENTER is what reproduces vanilla's `mulPose(cameraOrientation())` -- the plate turns to
        // face the camera on every axis, so it reads the same from above and below as well as
        // around. FIXED or VERTICAL would be a plate you can edge onto and lose.
        tag.putString("billboard", "center");
        // SEE_THROUGH (2), matching what vanilla gives any entity that is not `isDiscrete()`.
        tag.putByte("style_flags", (byte) 2);
        // The same translucent plate vanilla draws, read from the same option -- `true` here means
        // "use Minecraft's own background opacity", not a colour we picked.
        tag.putBoolean("default_background", true);
        // A plate that culls before the creature does reads as the name blinking out while the
        // creature is still there; match the displays' own range, which is the carrier's.
        double size = base.getBoundingBox().getSize();
        tag.putFloat("view_range", (float) (Double.isNaN(size) || size <= 0 ? 1.0 : size));
        com.moddingfromamod.compat.EntityNbt.load(nameplate, tag);
    }

    /**
     * Where the plate floats: over the creature's head, plus vanilla's own half block. The head is
     * the higher of the carrier's box and the rig's drawn top ({@link RigFit#headTop}) --
     * never the carrier's box alone, which can be shorter than the creature.
     */
    private double plateY() {
        return base.getY() + RigFit.headTop(base.getBbHeight(), drawnTop) + NAME_TAG_LIFT;
    }

    /**
     * Put the plate back over the creature's head. Called by {@link RigDriver#follow}, because a
     * plate that keeps its own Y is the one part of the rig that does NOT ride at the carrier's
     * feet — and a locomotion loop that treats it like a bone leaves the name on the floor.
     */
    public void followNameplate(double x, double z) {
        if (nameplate != null) {
            nameplate.snapTo(x, plateY(), z, 0f, 0f);
        }
    }

    /** Whether {@link #wearLook} has run on THIS rig object (a rebuild or a reload is a new one). */
    private boolean lookWorn;
    /** The name the plate shows, or null for none — what {@link #wearLook} last wrote. */
    private String shownName;

    /**
     * <b>The shadow and the name plate a vanilla mob of this size would have — both on, on the shipped
     * path.</b> Both mechanisms were built and measured (scoreboard S5, S3b) and
     * only ever switched on by Gate C, so no wished creature had a shadow, and a child who NAMED his
     * creature never saw the name: the carrier holds it, and it is invisible.
     *
     * <p>Called every tick by {@link VanillaRigAnimator}; cheap, because it writes only on a change:
     * <ul>
     *   <li>the SHADOW once per rig object — a re-sculpt or a reload is a new object, and its displays
     *       may be new ones;</li>
     *   <li>the PLATE when the carrier's name changes. Only a name a child GAVE is shown: every carrier
     *       is born wearing its species' name ({@code WishMobEntity} names itself and the bridge copies
     *       it), and a plate on every starter, helper and herd would be a new look nobody asked for. So
     *       a name equal to {@code speciesName} is no plate. Vanilla shows a name-tagged mob's name while
     *       it is looked at; the plate shows it always — the closest a text display can come, and the
     *       mod path showed its names always too.</li>
     * </ul>
     */
    public void wearLook(String speciesName) {
        if (base == null) {
            return;
        }
        boolean first = !lookWorn;
        if (first) {
            setShadow(shadowRadiusFor(living()), DEFAULT_SHADOW_STRENGTH);
            lookWorn = true;
        }
        String want = shownNameFor(base.hasCustomName() ? base.getCustomName().getString() : null, speciesName);
        if (first || !java.util.Objects.equals(want, shownName)) {
            setName(want == null ? null : base.getCustomName());
            shownName = want;
        }
    }

    /** Pure: the name a plate shows — the carrier's, unless it is none or only its species' own. */
    static String shownNameFor(String carrierName, String speciesName) {
        if (carrierName == null || carrierName.isBlank()) {
            return null;
        }
        return carrierName.equals(speciesName) ? null : carrierName;
    }

    // ------------------------------------------------------------------------------------------
    // TIER 2 — one display PROPERTY each. See docs/VANILLA_RIG_ANIMATION_LADDER.md §1 tier 2.
    // ------------------------------------------------------------------------------------------

    /**
     * <b>Give the rig a ground shadow — on EXACTLY ONE display.</b>
     *
     * <p>The shadow is drawn by {@code EntityRenderDispatcher.render}, not by any renderer, from
     * {@code renderer.getShadowRadius(entity)} — and {@code DisplayRenderer.render} sets that field
     * from the display's own {@code shadow_radius} render state. So a display can cast one.
     *
     * <p>⚠ <b>The carrier cannot.</b> That same block is gated on {@code !entity.isInvisible()},
     * and the carrier is hidden by INVISIBILITY forever. This is the third distinct vanilla check
     * the rig has now tripped over that keys off invisibility, after {@code shouldShowName} (the
     * name plate) and the potion-swirl rate — and the fourth thing in the same method that does
     * NOT check it is the flame. Whether a given overlay survives an invisible entity is decided
     * one {@code if} at a time; there is no rule to generalise from.
     *
     * <p>⚠ <b>Exactly one, and this is the trap the ladder predicted.</b> Every display in a rig
     * sits at the SAME entity position (the placement lives in the transform, not in the position),
     * so N displays with a shadow are N shadows stacked on one spot — each one darkening the last,
     * because {@code renderShadow} is alpha-blended per draw. Nineteen of them is a black disc.
     * The same "exactly one entity in a rig may wear that" rule as {@code RIG_OWNER_PREFIX}.
     *
     * @param radius   blocks. {@code EntityRenderDispatcher} clamps at 32 and fades the strength
     *                 with distance; 0 disables.
     * @param strength 0..1, vanilla's own default is 1
     * @return how many displays were rewritten — always the whole rig, because the ones that are
     *         not the shadow-bearer must be explicitly CLEARED or a second call moves the shadow
     *         and leaves the first one behind
     */
    public int setShadow(float radius, float strength) {
        int n = 0;
        for (int i = 0; i < displays.size(); i++) {
            Entity d = displays.get(i);
            CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
            tag.putFloat("shadow_radius", i == 0 ? radius : 0f);
            tag.putFloat("shadow_strength", i == 0 ? strength : 0f);
            com.moddingfromamod.compat.EntityNbt.load(d, tag);
            n++;
        }
        return n;
    }

    /**
     * <b>The base shadow radius the MOD path uses for a sculpted creature — and it is a CONSTANT,
     * which is a finding about arm A rather than a number this class needs.</b>
     *
     * <p>{@code WishMobRenderer} sets {@code this.shadowRadius} from a per-body table only on the
     * VANILLA-BODY branch; the custom-geometry branch (which every sculpted creature takes)
     * returns before that line, so the field keeps whatever the shared renderer instance was last
     * left holding — initially the {@code 0.5F} passed to {@code MobRenderer}'s constructor, which
     * is the humanoid default. Times the species scale that is the whole of a sculpted creature's
     * shadow, however big the creature is.
     *
     * <p>Kept so {@link #shadowRadiusFor} can be compared against it and so Gate C can print both.
     * The mod path is frozen for this spike; fixing it there is a separate change.
     */
    public static final float MOD_PATH_SHADOW_RADIUS = 0.5f;

    /**
     * <b>The radius to pass to {@link #setShadow}: HALF THE CREATURE'S OWN WIDTH.</b>
     *
     * <p>Two wrong answers were measured before this one, and both are worth keeping because each
     * looked like the derivation and was not.
     *
     * <p><b>Wrong answer 1 — the bare 0.5.</b> A mob's shadow is not its renderer's number: the
     * chain is {@code MobRenderer.getShadowRadius = EntityRenderer.shadowRadius *
     * LivingEntity.getScale() * Mob.getAgeScale()}, so the field is a BASE and the entity's scale
     * is applied on top of it every frame. {@code DisplayRenderer} copies {@code shadow_radius}
     * straight out of the render state and multiplies by nothing, so the multiply has to happen
     * here. Measured: 729 px of darkened floor against the mod path's 5650 in the same pose.
     *
     * <p><b>Wrong answer 2 — 0.5 × the CARRIER's scale.</b> That reproduces the formula with the
     * wrong input. The carrier is an arbitrary vanilla mob stretched until its box matches, so its
     * scale says how far a horse had to be stretched, not how big the creature is: measured
     * <b>2.813</b> against the creature's own <b>1.200</b>. It gave a shadow 2.3× too wide, and
     * because too-wide reads as "correct at last" after wrong answer 1, it is the version that
     * would have shipped. <b>Anything read off the carrier is a fact about the carrier.</b>
     *
     * <p><b>The answer: half the width</b>, which is what vanilla authors for its own big mob.
     * Radius against width across the vanilla renderers — cow 0.7/0.9, pig 0.7/0.9, creeper and
     * zombie 0.5/0.6, spider 0.8/1.4, <b>iron golem 0.7/1.4</b> — is 0.78 of the width for the
     * small ones and falls to exactly <b>half</b> for the biggest. A sculpted creature is larger
     * than every one of them, so the golem is the calibration point rather than the cow.
     *
     * <p>⚠ <b>This DELIBERATELY does not match the mod path, and the reason is measured.</b> Arm A
     * gets {@code 0.5 × 1.2 = 0.6} — a disc 1.2 blocks across under a creature 3.6 blocks wide, so
     * it is entirely hidden beneath the body and <b>a child never sees it</b>. Gate C's diff image
     * shows a clean ellipse under the rig and bare floor under the mod creature, with the vanilla
     * cow's shadow plainly visible between them. Parity here would mean reproducing an invisible
     * feature; the mod path's 0.5 is a humanoid default that no custom-model creature ever updates
     * (see {@link #MOD_PATH_SHADOW_RADIUS}).
     *
     * <p>This is the fifth thing vanilla SIZES off the entity that the rig reproduces by hand,
     * after the flame (bounding box), the name plate (box height), the culling range (box size)
     * and the swirl rate. The pattern is the row itself: <b>a Display is given absolute numbers
     * where a mob is given a base and a scale.</b>
     */
    public static float shadowRadiusFor(LivingEntity mob) {
        return mob.getBbWidth() / 2f;
    }

    /** Vanilla's own default shadow strength — {@code Display.INITIAL_SHADOW_STRENGTH}. */
    public static final float DEFAULT_SHADOW_STRENGTH = 1.0f;

    /**
     * <b>Outline the creature</b> — the glowing effect, and optionally in a colour of its own.
     *
     * <p>Two facts make this work on a rig, and both were read out of the bytecode rather than
     * assumed. {@code LevelRenderer} picks the outline buffer per entity from
     * {@code Minecraft.shouldEntityAppearGlowing}, which is the shared {@code Glowing} flag — the
     * one written by the {@code Glowing} NBT boolean, so the same save/load round trip the rest of
     * this class uses reaches it. And the outline COLOUR is {@code entity.getTeamColor()}, which
     * {@code Display} overrides to return {@code glow_color_override} whenever one is set. So a
     * display can be outlined in the creature's own colour with no team and no scoreboard.
     *
     * <p>⚠ <b>Unlike the shadow, this goes on EVERY display.</b> The outline is traced around the
     * geometry each entity actually draws, so a rig with the flag on one display is a creature with
     * one glowing limb. The two rules are opposite for the same reason: the shadow is a function of
     * POSITION (which every display shares) and the outline is a function of GEOMETRY (which none
     * of them share).
     *
     * <p>⚠ <b>Do not call this to make a creature glow — make the CARRIER glow</b>. This
     * is the writer {@link #mirrorGlow} uses, on a change, to carry the carrier's
     * glow across; {@code modify_mob}, effects and teams all write the carrier. And the carrier's
     * own outline is suppressed by {@code mixin/CarrierGlowFlagMixin}, because an invisible mob is
     * NOT outline-free: {@code LivingEntityRenderer.getRenderType} draws an invisible glowing mob
     * as {@code RenderType.outline}, which is the horse-shaped outline that was filmed on a sea
     * serpent. (This paragraph used to say the carrier "renders nothing, so it would contribute no
     * outline" — reasoned, never looked at.)
     *
     * @param on  whether to glow
     * @param rgb 0xRRGGBB for the outline, or -1 for vanilla's team-colour default (white)
     * @return how many displays were rewritten
     */
    public int setGlowing(boolean on, int rgb) {
        int n = 0;
        for (Entity d : displays) {
            CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
            tag.putBoolean("Glowing", on);
            if (on && rgb >= 0) {
                // ARGB, and the alpha must be non-zero: LevelRenderer reads the red/green/blue
                // channels of getTeamColor() and passes 255 for alpha itself, but Display treats
                // -1 (and only -1) as "no override", so a plain 0xRRGGBB with a zero alpha byte is
                // a legal override that happens to be the colour asked for.
                tag.putInt("glow_color_override", rgb);
            } else {
                tag.putInt("glow_color_override", -1);
            }
            com.moddingfromamod.compat.EntityNbt.load(d, tag);
            n++;
        }
        return n;
    }

    /**
     * The outline last written to the displays by {@link #mirrorGlow}, or null before its first
     * call. A display write is a save/load round trip on every display and a data packet to every
     * tracking player, so this is what makes the mirror free in the steady state.
     */
    private RigGlow glowShown;

    /**
     * <b>Glowing belongs on the rig, not on the carrier</b>. Whatever made the creature glow
     * wrote the CARRIER — {@code modify_mob}, {@code MobStates}, the GLOWING effect a datapack or a
     * spectral arrow gave it, a {@code /team} colour — because that is the real entity, and it is
     * what a save, an undo and {@code look_around} read. This carries it across to every display,
     * writing only on a CHANGE; {@code mixin/CarrierGlowFlagMixin} keeps the carrier's own synced
     * flag off, so the outline is the creature's shape and never the invisible horse's.
     *
     * <p>Called every tick by {@code VanillaRigAnimator.driveOne}, before its dying branch, so a
     * creature killed while glowing stays outlined as it falls, as a vanilla mob does.
     *
     * @return whether the displays were rewritten this call
     */
    public boolean mirrorGlow(Mob base) {
        var team = base.getTeam();
        RigGlow want = RigGlow.of(base.hasGlowingTag(),
                base.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING),
                team == null ? -1 : base.getTeamColor());
        if (glowShown == null) {
            // FIRST CALL FOR THIS RIG. A save writes both halves, so an adopted rig's displays may
            // already glow -- read what they carry rather than rewriting all of them on every
            // world load. And re-derive the carrier's flag: `Entity.load` reads `Glowing` BEFORE
            // `Tags`, so a carrier saved glowing raised flag 6 before the mixin could know it was
            // a carrier. Writing the same tag back goes through setSharedFlag again, now tagged.
            base.setGlowingTag(base.hasGlowingTag());
            if (displays.isEmpty() || !displays.get(0).hasGlowingTag()) {
                glowShown = RigGlow.OFF;   // what a fresh rig is; a glowing one is rewritten once
            }
        }
        if (want.equals(glowShown)) {
            return false;
        }
        if (glowShown != null && want.rgb() == glowShown.rgb()) {
            // Only the flag moved. `setGlowingTag` is public and writes shared flag 6 alone, where
            // setGlowing's save/load round trip re-sends every display's item stack too (an
            // ItemStack has no equals, so SynchedEntityData always calls it changed) -- measured
            // ~3 ms of tick and ~54 B a display for a 64-display rig, for one bit.
            for (Entity d : displays) {
                d.setGlowingTag(want.on());
            }
        } else {
            setGlowing(want.on(), want.rgb());
        }
        glowShown = want;
        return true;
    }

    /**
     * <b>Make some bones emissive</b> — glowing eyes, a lit core, a hot mouth.
     *
     * <p>{@code brightness} is the display's light override, and {@link #matchLight} already writes
     * it on every display to pin the whole rig to the carrier's own light. This is the same key
     * used for the opposite purpose on a subset: force 15/15 so those groups are drawn fully lit
     * whatever the world does.
     *
     * <p>⚠ <b>It is FULLBRIGHT, not additive.</b> Vanilla's emissive layers (an enderman's eyes, a
     * spider's) draw a second pass in {@code RenderType.eyes}, which ADDS light and glows in the
     * dark past white. A brightness override caps at the maximum ordinary light, so an emissive
     * bone on this rig looks like a bone standing in daylight — correct in a cave, and invisible at
     * noon. That is an emulation and must be written up as one, exactly like the tint channel.
     *
     * <p>⚠ <b>Which bones is not something the sculpt can say.</b> There is no emissive opt-in in
     * the sculpt schema and the sculpt tool is frozen for this spike, so the caller supplies the
     * predicate. The plumbing is what this row delivers; the authoring is a later change to the
     * tool.
     *
     * @param emissive tested against each group's name
     * @return how many displays were made emissive
     */
    public int setEmissive(java.util.function.Predicate<String> emissive) {
        int n = 0;
        for (int i = 0; i < displays.size() && i < groups.size(); i++) {
            if (!emissive.test(groups.get(i).name())) {
                continue;
            }
            CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(displays.get(i));
            CompoundTag brightness = new CompoundTag();
            brightness.putInt("block", 15);
            brightness.putInt("sky", 15);
            tag.put("brightness", brightness);
            com.moddingfromamod.compat.EntityNbt.load(displays.get(i), tag);
            this.emissive.set(i);
            n++;
        }
        return n;
    }

    /** Undo {@link #setEmissive}: every display back to the carrier's own light. */
    public void clearEmissive() {
        emissive.clear();
        ServerLevel level = (ServerLevel) base.level();
        RigLight now = lightAt(level, base);
        for (Entity d : displays) {
            writeLight(d, now);
        }
        lightShown = now;
    }

    /**
     * <b>Cull the display at the same distance the carrier culls at</b> — derived, not a literal.
     *
     * <p>Both sides use the same formula and differ only in one factor. {@code Entity} renders
     * while {@code d < (boundingBox.getSize() * 64 * viewScale)²}; {@code Display} overrides it to
     * {@code d < (view_range * 64 * viewScale)²}. So setting {@code view_range} to the carrier's
     * own {@code getBoundingBox().getSize()} makes the two expressions identical and the rig
     * disappears at exactly the moment the creature would have.
     *
     * <p>This replaces a hard-coded {@code 4.0f}, which was right in spirit (a big creature needs a
     * big range) and arbitrary in value — the same shape of bug as the train-framing literal in
     * {@code docs/CAMERA.md}: correct for the size that was in front of us, silently wrong for the
     * next one. A 0.5-block critter got 256 blocks of range; a 12-block one still got 256.
     */
    public static void matchViewRange(Entity display, Entity base) {
        double size = base.getBoundingBox().getSize();
        if (Double.isNaN(size) || size <= 0) {
            size = 1.0;
        }
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(display);
        tag.putFloat("view_range", (float) size);
        com.moddingfromamod.compat.EntityNbt.load(display, tag);
    }

    /** Take the whole rig out of the world, hit proxies included. */
    public void discard() {
        for (Entity e : all()) {
            e.discard();
        }
        discardProxies();
    }

    /** The measured hitbox of the base, in blocks — what Q4 reports rather than assumes. */
    public double[] measuredHitbox() {
        return new double[]{base.getBbWidth(), base.getBbHeight()};
    }

    /** Convenience for a test that wants the base as a LivingEntity. */
    public LivingEntity living() {
        return base;
    }
}
