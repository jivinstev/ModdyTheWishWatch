package com.moddingfromamod.vanilla;

import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.species.Species;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The wiring — the thing the spike measured for weeks and never connected.</b>
 *
 * <p>{@code VanillaRigFlags#enabled()} (deleted) had <b>zero callers</b> in production before this class.
 * Every rig ever built was built by a test calling {@link DisplayRig#spawn} directly, so the whole
 * path was a capability held by a gate rather than something a wish could reach — scoreboard row
 * B9, which this closes. Nothing here changes what the agent is told or how it authors: the MCP
 * path is frozen for the spike, so the bridge is a <em>listener</em> rather than an edit to
 * {@code sculpt_creature}, and every creature-spawning route in the mod (the tool, the spawn egg,
 * {@code /wishmob}, a re-sculpt) goes through it for free.
 *
 * <h2>Off is off</h2>
 *
 * <p>This runs on every entity that joins any level, so the flag test is load-bearing in a way the
 * rest of the spike's flag reads are not: a bridge that stopped checking it would silently convert
 * every wished creature in a shipped build, and the shipped build is the thing that must not
 * change. {@code VanillaRigBridgeGameTest.withTheFlagOffNothingIsIntercepted} is the guard, and it
 * is deliberately an assertion about the WORLD (a wish mob still standing, zero displays) rather
 * than about this class.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class VanillaRigBridge {

    private VanillaRigBridge() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-rig-bridge");

    /**
     * A wished creature joins the world — swap it for a rig.
     *
     * <p><b>Cancel, then spawn on the server's own queue.</b> Building a rig inside the join event
     * means adding twenty entities to a level that is part-way through adding one, and the rig's
     * own carrier would re-enter this handler. {@code server.execute} runs it at the end of the
     * tick, which is late enough to be safe and early enough that nothing has drawn a frame.
     */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof WishMobEntity mob)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Pending pending = prepare(mob);
        event.setCanceled(true);


        level.getServer().execute(() -> manifest(level, pending));
    }

    /**
     * <b>Manifest a wished creature NOW and hand back what is really standing there</b>
     * ().
     *
     * <p>Every spawn site used to add a {@code WishMobEntity} and count {@code addFreshEntity}
     * returning true. {@link #onJoin} cancels that join, so the count was 0 every time while a
     * rig arrived a tick later: <i>"Spawned 0× Spikor"</i> for every creature in production, the
     * agent spawned him again, and a wish whose creature really did not appear ended DONE. This
     * builds the rig from the same (never-added) mob synchronously, through the very code the
     * join path runs, so the caller counts CARRIERS and mints a handle from the carrier.
     *
     * <p>Server thread only, and never from inside a join event (see {@link #onJoin}).
     *
     * @return the carrier, alive in {@code level}; or null when nothing is standing there
     */
    public static LivingEntity manifestNow(ServerLevel level, WishMobEntity mob) {
        if (level == null || mob == null) {
            return null;
        }
        DisplayRig rig = manifest(level, prepare(mob));
        if (rig == null) {
            return null;
        }
        LivingEntity carrier = rig.living();
        // STANDING THERE, not merely constructed: a carrier the level refused (a hostile on
        // Peaceful, an unloaded chunk) is not a creature the child can see.
        return carrier != null && carrier.isAlive() && !carrier.isRemoved()
                && level.getEntity(carrier.getUUID()) == carrier ? carrier : null;
    }

    /** Everything the rig needs, read off the wish mob BEFORE it stops existing. */
    private record Pending(Species species, List<CreatureSculpt.Part> parts, boolean standIn,
                           double scale, Vec3 at, float yaw, String id, RigVitals vitals,
                           List<net.minecraft.world.effect.MobEffectInstance> carried,
                           net.minecraft.network.chat.Component givenName,
                           java.util.UUID wasUuid) {
    }

    private static Pending prepare(WishMobEntity mob) {
        Species species = mob.species();
        // ⚠ THE TWO EARLY RETURNS THAT USED TO LIVE HERE ARE GONE --.
        //
        // They left a creature on the legacy renderer when there was nothing to build a rig out
        // of: a species with no custom model (`define_creature` without a sculpt, a live path),
        // and a model with no sculpt spec (an import, or a creature older than the spec -- most
        // of an old save). The reason given was that cancelling them into nothing would be worse
        // than the old renderer, and that is right as far as it goes. What it missed is WHO IS
        // LOOKING: `WishMobRenderer` is client-side mod code and v1 ships to a STOCK client, so
        // for the child this is built for both branches of that trade were nothing.
        //
        // `StandInSculpt` is the third answer, and it makes this method TOTAL -- which is what
        // lets the legacy renderer go, because nothing routes to it any more.
        List<CreatureSculpt.Part> sculpted = species != null && species.hasCustomModel()
                ? ModelRepository.drawnParts(species.modelKey())
                : List.of();
        List<CreatureSculpt.Part> parts = StandInSculpt.manifest(species, sculpted);
        boolean standIn = StandInSculpt.isStandIn(sculpted);

        double scale = species == null ? 1.0 : species.scale();
        // A summoned helper is built at the scale it was given (RigAbilities.summon).
        var scaleAttr = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
        if (mob.isMinion() && scaleAttr != null) {
            scale = scaleAttr.getBaseValue();
        }
        Vec3 at = mob.position();
        float yaw = mob.getYRot();
        // A species record that has been forgotten still leaves the mob its ID STRING, and
        // that is what every selector and every tag is built from -- so use it rather than
        // a generic name that would make every speciesless creature answer to one tag.
        String id = species != null ? species.id()
                : (mob.speciesId() == null || mob.speciesId().isBlank()
                        ? "wish_mob" : mob.speciesId());

        // EVERYTHING LIVE MUST BE READ BEFORE THE CANCEL. The wish mob is about to stop existing,
        // and it is the only thing that knows what has happened to this creature: its health, its
        // effects, and any name a child gave it with a name tag. The species knows none of that --
        // WishMobEntity persists only the species id and rebuilds the rest from the record, so a
        // rig built from the species alone would be a full-health, unpoisoned, re-named creature.
        RigVitals vitals = species == null
                ? RigVitals.carrying(mob.getMaxHealth(), 0, 0, 0, 0.25,
                        mob.getHealth(), mob.getMaxHealth())
                : RigVitals.carrying(species.health(), species.damage(), species.armor(),
                        species.knockbackResistance(), species.speed(),
                        mob.getHealth(), mob.getMaxHealth());
        var carried = new java.util.ArrayList<>(mob.getActiveEffects());
        var givenName = mob.hasCustomName() ? mob.getCustomName() : null;
        var wasUuid = mob.getUUID();

        return new Pending(species, parts, standIn, scale, at, yaw, id, vitals, carried,
                givenName, wasUuid);
    }

    /** Build the rig for a prepared creature; null (and an error line) when that failed. */
    private static DisplayRig manifest(ServerLevel level, Pending p) {
        Species species = p.species();
        List<CreatureSculpt.Part> parts = p.parts();
        boolean standIn = p.standIn();
        double scale = p.scale();
        Vec3 at = p.at();
        float yaw = p.yaw();
        String id = p.id();
        RigVitals vitals = p.vitals();
        var carried = p.carried();
        var givenName = p.givenName();
        var wasUuid = p.wasUuid();
        try {
            RigReveal.publish(level.getServer(), id, parts, scale);
            // A stand-in has no clips of its own: there is no model directory to read them
            // from. It still walks, because the locomotion tier drives the rig's own bones.
            var clips = standIn || species == null
                    ? java.util.Map.<String, com.moddingfromamod.runtime.BedrockAnimation>of()
                    : ModelRepository.animations(species.modelKey());
            DisplayRig rig = DisplayRig.spawn(level, at, yaw, parts, scale, id,
                    CarrierPool.movementOf(species, parts));

            // The creature's own numbers, then the state it was carrying. Order matters:
            // applyVitals raises MAX_HEALTH before setting health, or a 200-health creature is
            // clamped to the carrier's default and stays there.
            int landed = DisplayRig.applyVitals(rig.living(), vitals, id);
            for (var effect : carried) {
                // Not INVISIBILITY: the rig applies its own, infinite and hidden, to keep the
                // carrier from being drawn (DisplayRig.hide). Copying a finite one over it
                // would make the horse appear when the child's potion ran out.
                if (!effect.getEffect().is(net.minecraft.world.effect.MobEffects.INVISIBILITY)) {
                    rig.living().addEffect(new net.minecraft.world.effect.MobEffectInstance(effect));
                }
            }
            if (givenName != null) {
                rig.living().setCustomName(givenName);
            }
            // The species itself, which this class used to drop: a companion follows,
            // a friendly one never attacks, fireproof does not burn, glowing glows.
            SpeciesOnRig.applyAtBirth(rig.base(), species, false);
            // ⚠ ANYTHING HOLDING THE OLD UUID IS NOW HOLDING A DEAD ENTITY, and the agent's
            // own tools are among them: `DefineSpeciesTool` remembers the wish mob's UUID in
            // the SAME TICK this runs and tells the agent to use it as `modify_mob`'s target.
            // The handle was right when it was minted and wrong one tick later, so the next
            // edit answered "it died or unloaded". Follow the conversion instead of re-minting
            // -- the child said "make the robot bigger" about a name the agent already holds.
            // ⚠ BOTH HALVES, AND THE SECOND IS THE ONE THAT WORKS.
            //
            // `repoint` moves a handle that ALREADY names the wish mob. `settled` answers
            // whoever asks afterwards. Which is needed depends on whether the caller minted
            // its handle before or after this runs -- and MEASURED over a whole Gate B tier,
            // 41 conversions moved ZERO handles: every caller is on the far side, because
            // `MinecraftServer.execute` drains before the caller's next line. So `repoint`
            // stays for the rebuild path and for any caller that mints first, and `settled`
            // is what actually keeps the agent's handle alive. Keeping only the tidier one
            // would have been keeping the one that never fires.
            com.moddingfromamod.mob.EntityReplacements.record(
                    wasUuid, rig.living().getUUID());
            com.moddingfromamod.mob.EntityHandles.global().repoint(
                    wasUuid, rig.living().getUUID(),
                    com.moddingfromamod.mob.Creatures.kindId(rig.living()));
            LOG.info("rig '{}'{} took {} of 5 species attributes, {} effect(s) and health "
                            + "{}/{} -- it replaced wish mob {}",
                    id, standIn ? " (a STAND-IN -- no sculpt to build from)" : "",
                    landed, carried.size(),
                    String.format("%.1f", rig.living().getHealth()),
                    String.format("%.1f", rig.living().getMaxHealth()), wasUuid);
            // The rig is now standing there and, without this line, standing there FOREVER.
            // Tier 5 is the driver; spawning without starting it is the B9 shape one level in.
            VanillaRigAnimator.drive(rig,
                    RigFit.intoRigSpace(parts), clips);
            return rig;
        } catch (Exception e) {
            LOG.error(
                    "the vanilla rig bridge could not manifest '{}' -- the creature was "
                            + "cancelled and nothing replaced it", id, e);
            return null;
        }
    }

    /**
     * 🔴 <b>Reap on REMOVAL, not on death — and the difference is a whole feature.</b>
     *
     * <p>The obvious hook is {@code LivingDeathEvent}, and it deletes the rig the instant the
     * creature dies. That is twenty ticks before vanilla removes the body, and those twenty ticks
     * are exactly the death flop tier 4.1 exists to produce: a reaper on the death event makes a
     * creature <em>vanish</em> where it should fall over, and the flop would read as never having
     * been built. {@code VanillaRigBridgeGameTest} asserts the displays are still there five ticks
     * after the killing blow for that reason.
     *
     * <p><b>And the removal REASON matters.</b> {@code EntityLeaveLevelEvent} also fires when a
     * chunk unloads or a player carries the entity across a dimension — reaping there would delete
     * the displays of a creature that is merely asleep, and it would come back as an invisible
     * carrier with nothing hanging off it. Only {@code KILLED} and {@code DISCARDED} are the end
     * of a creature.
     */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        Entity e = event.getEntity();
        if (event.getLevel().isClientSide()
                || !(event.getLevel() instanceof ServerLevel level)
                || !(e instanceof LivingEntity)
                || !e.getTags().contains(DisplayRig.RIG_TAG)) {
            return;
        }
        Entity.RemovalReason why = e.getRemovalReason();
        if (why != Entity.RemovalReason.KILLED && why != Entity.RemovalReason.DISCARDED) {
            return;
        }
        VanillaRigAnimator.forget(e.getUUID());
        int reaped = DisplayRig.reap(level, e.getUUID());
        if (reaped > 0) {
            LOG.debug("reaped {} rig displays for carrier {} ({})",
                    reaped, e.getUUID(), why);
        }
    }
}
