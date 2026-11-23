package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Hang what rides on a swinging limb FROM that limb.</b>
 *
 * <p>The standard clips ({@link CreatureFiles}) swing the bones named {@link #SWUNG}. A hand, a
 * claw, a glove or a paw pad sculpted as a ROOT beside its arm or leg does not swing with it, so
 * every stride and every swipe leaves it hanging in the air. Measured over 57 archived creatures
 * once the body stopped bobbing: 7 still came apart when posed, every one this — Spikor's claws and
 * Chucko's glove left behind by the attack, the starter cat's back paws by its own hind legs.
 *
 * <p>The sibling of {@link SculptHeadAdoption}, and as narrow. A part is adopted by limb {@code L}
 * only when it is a root, smaller than {@code L}, touches it, and {@code L} is the part it rests on
 * most (the same carrier rule {@link SculptBase} uses for an edit). Skipped when {@code L} is tilted
 * at rest, because a child inherits its parent's tilt and adoption would then MOVE the part. So the
 * creature at rest is unchanged; only its moving pose is.
 *
 * <p>Pure and Minecraft-free.
 */
public final class SculptLimbAdoption {

    /** The bones the standard clips swing. {@code SculptLimbAdoptionTest} holds this to the clips. */
    public static final Set<String> SWUNG = Set.of("armL", "armR", "legL", "legR");

    private SculptLimbAdoption() {
    }

    /** The creature with each riding part hung from its limb; the SAME list when nothing moved. */
    public static List<CreatureSculpt.Part> adopt(List<CreatureSculpt.Part> parts) {
        Map<String, String> moves = adoptions(parts);
        if (moves.isEmpty()) {
            return parts;
        }
        List<CreatureSculpt.Part> out = new ArrayList<>(parts.size());
        for (CreatureSculpt.Part p : parts) {
            String to = moves.get(p.name());
            out.add(to == null ? p : new CreatureSculpt.Part(p.name(), to, p.pivot(), p.from(),
                    p.to(), p.colour(), p.rotation(), p.inflate(), p.pattern(), p.patternColour()));
        }
        return out;
    }

    /** Which parts {@link #adopt} re-parents, and to which limb, so the tool can say so. */
    public static Map<String, String> adoptions(List<CreatureSculpt.Part> parts) {
        Map<String, String> out = new LinkedHashMap<>();
        if (parts == null || parts.size() < 2) {
            return out;
        }
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            byName.putIfAbsent(p.name().toLowerCase(Locale.ROOT), p);
        }
        // To a fixed point: a second claw resting on the first rides the arm too (Spikor's did).
        boolean grew = true;
        while (grew) {
            grew = false;
            for (CreatureSculpt.Part p : parts) {
                if (p.parent() != null || out.containsKey(p.name()) || SWUNG.contains(p.name())
                        || SculptHeadAdoption.isHeadBone(p.name())) {
                    continue;
                }
                CreatureSculpt.Part on = SculptBase.carrier(p, parts, byName);
                boolean limb = on != null && SWUNG.contains(on.name()) && on.parent() == null;
                boolean riding = on != null && out.containsKey(on.name());
                if ((limb || riding) && !on.rotated()) {
                    out.put(p.name(), on.name());
                    grew = true;
                }
            }
        }
        return out;
    }
}
