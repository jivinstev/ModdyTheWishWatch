package com.moddingfromamod.runtime;

import java.util.Collection;
import java.util.Locale;

import com.moddingfromamod.entity.WishMobTags;

/**
 * <b>Which entity in the world IS the creature the camera was asked to photograph.</b>
 *
 * <p>Minecraft-free on purpose: the caller hands over the two things an entity can say about
 * itself -- the species id it carries as a {@code WishMobEntity}, if it is one, and its tags -- and
 * gets back a yes or a no. That is the whole rule, and it is unit-tested, because it was wrong in
 * production and nothing above it could tell.
 *
 * <p><b>The bug this exists to prevent.</b> {@code PhotographTool.findSubject} matched only
 * {@code entity instanceof WishMobEntity && id.equals(w.speciesId())}. That was complete for as
 * long as a wish creature could only BE a {@code WishMobEntity} -- and stopped being complete the
 * day made the vanilla display rig the default, because the shipping creature is then a rig
 * riding a vanilla base ({@code RigAnchor} says so in as many words: "a {@code WishMobEntity} <b>and
 * any vanilla base</b>"). The subject search returned nothing, so every subject-driven capture
 * degraded AT ONCE -- the four-stop orbit fell back to one plain shot, the clip was never written,
 * the film was never written -- and the run still reported {@code PASS -- 1 photographed}, because
 * one file really had been created. It was a photograph of the Mojang loading screen.
 *
 * <p>Measured, same commit, same creature, only the flag differing: rig <b>off</b> produced 7
 * frames; rig <b>on</b> produced 1. This is the same shape {@code docs/CAMERA.md} keeps recording
 * -- a camera bug does not throw, it produces a plausible image of the wrong thing and the run
 * reports PASS.
 *
 * <p><b>Why the tag is the right handle, rather than a second {@code instanceof}.</b> The carrier
 * is tagged {@code wishmob_<species>} on BOTH paths already -- {@code WishMobEntity.applySpecies}
 * does it on spawn, on world load and on a species edit, and {@code DisplayRig} does it when it
 * dresses a vanilla base. So the tag is the one fact both arms agree on, and it needs no new field,
 * no migration, and no contract change. The species-id comparison is kept as the first branch so a
 * {@code WishMobEntity} is matched exactly as it always was: sanitising is lossy (see below), and a
 * path that already holds the unsanitised id should not be routed through a lossy one.
 */
public final class CaptureSubject {

    /**
     * A tag string no entity can carry, returned when an id does not survive sanitising. It
     * contains a space, which {@link WishMobTags#forSpecies} turns into an underscore, so nothing
     * it produces can ever equal this.
     */
    private static final String MATCHES_NOTHING = " no such species";

    private CaptureSubject() {
    }

    /**
     * Is this entity the subject called {@code wantedId}?
     *
     * @param wantedId        the id the camera was asked for
     * @param entitySpeciesId the entity's own species id if it is a {@code WishMobEntity},
     *                        otherwise {@code null}
     * @param tags            the entity's scoreboard tags; may be null or empty
     */
    public static boolean isSubject(String wantedId, String entitySpeciesId,
                                    Collection<String> tags) {
        if (wantedId == null || wantedId.isBlank()) {
            return false;
        }
        if (entitySpeciesId != null && wantedId.equalsIgnoreCase(entitySpeciesId)) {
            return true;
        }
        return tags != null && tags.contains(speciesTagOrNothing(wantedId));
    }

    /**
     * How big the creature actually is, given the entity the camera found.
     *
     * <p><b>The framing input, and the second half -- which is the worse half, because it
     * does not fail, it MISFRAMES.</b> Once the subject search could see a rig (above), the camera
     * went on to ask the entity it had found how big it was. On a {@code WishMobEntity} that is the
     * creature's own scale and has always been right. On a rig it is not: {@code RigBase.Choice}
     * computes {@code scale = wantHeight / base.height()} and sets it as the carrier's
     * {@code minecraft:scale}, so the number is a STRETCH FACTOR FOR THAT BASE.
     *
     * <p>Measured, on the first run where the subject was found at all: the rig had chosen a
     * <b>bee</b>, so the carrier's scale read <b>12.25</b>, and the camera framed a creature
     * {@code 49 units / 16 * 12.25 = 37.5} blocks tall. True height is about 7.4. It stood 29
     * blocks back and took four perfectly sharp, correctly angled photographs of a speck.
     *
     * <p>This is {@code docs/VANILLA_RIG_ANIMATION_LADDER.md}'s shadow-row lesson arriving in the
     * camera: <i>anything read off the carrier is a fact about the carrier</i>. It is stated there
     * about a horse stretched to fit, it cost that row three wrong answers, and it cost this one
     * a run -- so it is encoded here rather than left as a sentence in a doc.
     *
     * @param isWishMob     true if the found entity IS the creature (the pre-#500 renderer), in
     *                      which case its own scale is the answer and must be preferred -- a child
     *                      who says "make THIS one bigger" changes the entity, not the species, and
     *                      reading the species back would undo him in the photograph
     * @param entityScale   the found entity's {@code minecraft:scale}
     * @param speciesScale  the scale on the saved species record, or null when there is none (a
     *                      modded boss, say, which is not ours and whose own scale is the truth)
     */
    public static double scaleOf(boolean isWishMob, double entityScale, Double speciesScale) {
        if (isWishMob) {
            return entityScale;
        }
        return speciesScale != null && speciesScale > 0 ? speciesScale : entityScale;
    }

    /**
     * Is this entity one of ours at all, whatever its species? Used for the crowd warning on a
     * contact sheet -- "three creatures are close enough to be in this shot" -- which was counting
     * {@code WishMobEntity} only, and so silently stopped warning for the default creature at the
     * same moment and for the same reason as.
     */
    public static boolean isAnyWishCreature(String entitySpeciesId, Collection<String> tags) {
        return entitySpeciesId != null || (tags != null && tags.contains(WishMobTags.ALL));
    }

    /**
     * The per-species tag for an id, or a string no entity carries when the id does not survive
     * sanitising.
     *
     * <p>WARNING -- the guard is the point. {@link WishMobTags#forSpecies} falls back to the bare
     * {@code "wishmob"} for an id that sanitises to nothing (all punctuation, say), and EVERY wish
     * creature carries that tag. Matching on it would make "photograph '!!!'" return whichever
     * creature happens to be nearest, confidently and with the right framing -- a wrong photograph
     * that looks exactly like a right one. So an id that collapses to the fallback matches nothing.
     */
    private static String speciesTagOrNothing(String wantedId) {
        String tag = WishMobTags.forSpecies(wantedId);
        return WishMobTags.ALL.equals(tag) ? MATCHES_NOTHING : tag.toLowerCase(Locale.ROOT);
    }
}
