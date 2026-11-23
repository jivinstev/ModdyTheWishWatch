package com.moddingfromamod.testing;

import java.util.UUID;

import com.moddingfromamod.mob.EntityHandles;
import com.moddingfromamod.vanilla.DisplayRig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * <b>"Where is that creature, really?" — the one answer, for tests.</b>
 *
 * <p>Since {@link com.moddingfromamod.vanilla.VanillaRigBridge} became total, a wished creature is
 * <b>never</b> a {@code WishMobEntity} by the time anything can look at it: the mob is cancelled as
 * it joins and a display rig on a vanilla carrier is spawned at the end of the same tick. A test
 * that keeps the {@code WishMobEntity} it constructed is holding an object that was never in the
 * level — and, worse, one whose attributes still answer, so an assertion on it <b>passes
 * vacuously</b>. Several did: they asserted a scale on a creature that had already been replaced.
 *
 * <p>So this is the probe, and it deliberately offers only the two questions that survive the
 * conversion:
 *
 * <ul>
 *   <li>{@link #carrierFor} — which body in this level IS that species now;</li>
 *   <li>{@link #bodyFor} — which body does that HANDLE name now, which is the same question the
 *       agent's {@code modify_mob} asks and is what {@code EntityHandles.repoint} keeps true.</li>
 * </ul>
 *
 * <p>⚠ <b>What it does NOT offer is a scale.</b> The carrier's {@code minecraft:scale} is
 * {@code RigBase.choose}'s fitting factor — a horse stretched to fit — so it is a fact about the
 * carrier and says nothing about the creature ({@code docs/VANILLA_RIG_ANIMATION_LADDER.md}'s
 * shadow row, which measured three wrong answers before that one). What a child actually sees grow
 * is the creature's <b>height</b>, and that is {@code getBbHeight()} on the carrier, whose
 * dimensions the rig refreshes to the model's own extents.
 */
public final class RigProbe {

    private RigProbe() {
    }

    /** The rig carrier standing for this species id, or {@code null} if none is manifested yet. */
    public static LivingEntity carrierFor(ServerLevel level, String speciesId) {
        if (level == null || speciesId == null) {
            return null;
        }
        for (var e : level.getEntities().getAll()) {
            if (!(e instanceof LivingEntity live) || !e.getTags().contains(DisplayRig.RIG_TAG)) {
                continue;
            }
            var sp = DisplayRig.speciesOn(live);
            if (sp != null && speciesId.equalsIgnoreCase(sp.id())) {
                return live;
            }
        }
        return null;
    }

    /** The live body this handle names, following every conversion, or {@code null}. */
    public static LivingEntity bodyFor(ServerLevel level, String handle) {
        if (level == null || handle == null || handle.isBlank()) {
            return null;
        }
        var h = EntityHandles.global().resolve(handle);
        if (h.isEmpty()) {
            return null;
        }
        UUID id = h.get().id();
        return level.getEntity(id) instanceof LivingEntity live ? live : null;
    }

    /**
     * <b>Why {@link #bodyFor} said no</b> — for an assertion message, never for a decision.
     *
     * <p>This repo's own rule: when a wait times out, the message has to carry the evidence, or the
     * next session guesses. "waiting" is indistinguishable from "the handle resolves to an entity
     * in another level" and from "the handle was never repointed".
     */
    public static String describe(ServerLevel level, String handle) {
        if (level == null) {
            return "no level";
        }
        var h = EntityHandles.global().resolve(handle);
        if (h.isEmpty()) {
            return "handle " + handle + " resolves to NOTHING; known: "
                    + EntityHandles.global().recent(8);
        }
        UUID id = h.get().id();
        var e = level.getEntity(id);
        return "handle " + handle + " -> " + id + " (" + h.get().typeId() + "); level holds "
                + (e == null ? "NOTHING for it" : e.getType() + " alive=" + e.isAlive());
    }

    /**
     * How tall the creature actually stands, in blocks — what a child means by "bigger", and the
     * one size observable that is about the creature rather than about its carrier.
     *
     * @return the height, or -1 when the handle names nothing that is standing
     */
    public static double heightOf(ServerLevel level, String handle) {
        LivingEntity body = bodyFor(level, handle);
        return body == null ? -1 : body.getBbHeight();
    }
}
