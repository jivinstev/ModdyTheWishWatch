package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;

/**
 * <b>A creature that has nothing to build a rig out of still gets a body —.</b>
 *
 * <p>Two creatures could not be rigged, and both fell back to {@code WishMobRenderer}:
 *
 * <ul>
 *   <li>a species with <b>no custom model</b> — {@code define_creature} without a sculpt, which is
 *       a live path rather than an edge case;</li>
 *   <li>a model with <b>no sculpt spec</b> — an import, or a creature that predates the spec being
 *       saved, which is most of an old save.</li>
 * </ul>
 *
 * <p>The fallback's stated reason was <em>"cancelling those into nothing would be far worse than
 * rendering them the old way"</em>, and it is right as far as it goes. What it missed is who is
 * looking: <b>"the old way" is client-side mod code, and v1's audience is a stock client that has
 * none of it</b> (`docs/DEPLOYMENTS.md` shape 1). So for the child this ships to, both branches of
 * that trade were nothing — the fallback bought an invisible creature, not an old-looking one.
 *
 * <p>This is the third answer. Every species yields parts, so {@link VanillaRigBridge} is
 * <b>total</b>: nothing routes to the legacy renderer, which is what lets the legacy renderer go.
 * A stand-in is blocky and flat-coloured rather than pretty, and that is the deliberate trade — a
 * child can see it, walk round it, name it and kill it on a client with nothing installed.
 *
 * <h2>Why the shapes are hand-written and not derived</h2>
 *
 * <p>The tempting move for the second case is to convert the imported {@code BedrockGeometry} back
 * into parts, which is nearly a field rename. It is not done here because <b>geometry carries no
 * colour</b> — colour lives in the texture PNG, and the atom rig draws one solid
 * {@code dyed_color} per box. A faithful import therefore needs a per-cube texture SAMPLE, which
 * is real work and belongs 's replacement oracle, not under a deprecation. Deriving the
 * shape and inventing the colour would produce a creature that is the right silhouette in the
 * wrong palette, which reads as a sculpting bug rather than as a stand-in.
 *
 * <p>Minecraft-free and pure, so every shape is a unit test rather than a screenshot.
 */
public final class StandInSculpt {

    private StandInSculpt() {
    }

    /** Bedrock pixels per block, the unit every {@link CreatureSculpt.Part} coordinate is in. */
    private static final float PX = 16f;

    /**
     * The parts that manifest this species — <b>never empty</b>.
     *
     * <p>This is the one question {@link VanillaRigBridge}, {@link VanillaRigAnimator#adoptAll} and
     * {@code rebuildIfRewished} all used to answer for themselves, each with the same pair of
     * guards and each falling back differently. Three copies of a guard is how an earlier issue's fallback came
     * to be documented in one place and reachable from three.
     *
     * @param species the creature, or {@code null} for a wish mob whose species has been forgotten
     * @param sculpted what the model repository holds for it, possibly empty
     */
    public static List<CreatureSculpt.Part> manifest(Species species,
                                                     List<CreatureSculpt.Part> sculpted) {
        if (sculpted != null && !sculpted.isEmpty()) {
            return sculpted;
        }
        if (species == null) {
            return forBody(Species.Body.HUMANOID, 0xFFFFFF);
        }
        return forBody(species.body(), species.tint());
    }

    /** True when {@link #manifest} would substitute a stand-in rather than the real sculpt. */
    public static boolean isStandIn(List<CreatureSculpt.Part> sculpted) {
        return sculpted == null || sculpted.isEmpty();
    }

    /**
     * A blocky body of the given shape, feet at {@code y=0} and centred on the entity's own
     * position — the same convention {@code CreatureAnatomy} measures in.
     *
     * <p>Proportions are the vanilla model each {@link Species.Body} borrows, rounded to whole
     * pixels: a humanoid is a zombie, a quadruped a pig, a bird a chicken, and so on. That keeps a
     * stand-in's hitbox close to what the same species had on the legacy renderer, so a creature
     * that crossed the retirement does not change size under a child.
     */
    public static List<CreatureSculpt.Part> forBody(Species.Body body, int tint) {
        int c = tint == 0 ? 0xFFFFFF : (tint & 0xFFFFFF);
        List<CreatureSculpt.Part> parts = new ArrayList<>();
        switch (body == null ? Species.Body.HUMANOID : body) {
            case HUMANOID -> {
                // zombie: 12px legs, 12px torso, 8px head — 32px, two blocks.
                box(parts, "leg_left", null, -4, 0, -2, -1, 12, 2, c);
                box(parts, "leg_right", null, 1, 0, -2, 4, 12, 2, c);
                box(parts, "body", null, -4, 12, -2, 4, 24, 2, c);
                box(parts, "arm_left", null, -7, 12, -2, -4, 24, 2, c);
                box(parts, "arm_right", null, 4, 12, -2, 7, 24, 2, c);
                box(parts, "head", null, -4, 24, -4, 4, 32, 4, c);
            }
            case QUADRUPED -> {
                // pig: 6px legs, an 8-tall barrel, a head off the front.
                box(parts, "leg_fl", null, -4, 0, -5, -1, 6, -2, c);
                box(parts, "leg_fr", null, 1, 0, -5, 4, 6, -2, c);
                box(parts, "leg_bl", null, -4, 0, 2, -1, 6, 5, c);
                box(parts, "leg_br", null, 1, 0, 2, 4, 6, 5, c);
                box(parts, "body", null, -5, 6, -6, 5, 14, 6, c);
                box(parts, "head", null, -4, 7, -12, 4, 15, -6, c);
            }
            case BIRD -> {
                // chicken: short legs, a small body, a head on top of it.
                box(parts, "leg_left", null, -3, 0, -1, -1, 5, 1, c);
                box(parts, "leg_right", null, 1, 0, -1, 3, 5, 1, c);
                box(parts, "body", null, -3, 5, -3, 3, 11, 3, c);
                box(parts, "head", null, -2, 11, -5, 2, 15, -1, c);
                box(parts, "beak", null, -1, 12, -7, 1, 14, -5, c);
            }
            case SPIDER -> {
                // spider: wide and low — a cephalothorax, an abdomen and eight stubs.
                box(parts, "body", null, -5, 3, -3, 5, 11, 6, c);
                box(parts, "head", null, -4, 4, -9, 4, 10, -3, c);
                for (int i = 0; i < 4; i++) {
                    float z = -4 + i * 3f;
                    // ⚠ The legs reach y=0. The first cut had them at y=2..4 -- a spider
                    // hovering two pixels off the ground, which `everyBodyIsCreatureSized` caught
                    // and an eye would not have.
                    box(parts, "leg_l" + i, null, -12, 0, z, -5, 3, z + 2, c);
                    box(parts, "leg_r" + i, null, 5, 0, z, 12, 3, z + 2, c);
                }
            }
            case SLIME -> box(parts, "body", null, -4, 0, -4, 4, 8, 4, c);
            default -> box(parts, "body", null, -4, 0, -4, 4, 8, 4, c);
        }
        return List.copyOf(parts);
    }

    /** Blocks tall, before the species' own scale — handy for a test that wants a real number. */
    public static float heightBlocks(List<CreatureSculpt.Part> parts) {
        float top = 0f;
        for (CreatureSculpt.Part p : parts) {
            top = Math.max(top, p.to()[1]);
        }
        return top / PX;
    }

    private static void box(List<CreatureSculpt.Part> into, String name, String parent,
                            float x0, float y0, float z0, float x1, float y1, float z1, int colour) {
        into.add(new CreatureSculpt.Part(name, parent, new float[]{0, 0, 0},
                new float[]{x0, y0, z0}, new float[]{x1, y1, z1}, colour));
    }
}
