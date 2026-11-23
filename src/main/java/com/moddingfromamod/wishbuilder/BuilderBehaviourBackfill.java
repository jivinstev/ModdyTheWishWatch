package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonObject;
import com.moddingfromamod.species.Archetype;

/**
 * <b>: the behaviour a SAVED Builder or Apprentice creature's picks choose</b>, read from the spec its make kept on
 * his shelf ({@link BuilderSpecStore}). The same {@link BuilderLowering#behaviourOf} a new make uses, so a
 * creature made lives exactly as the same make would today. Null for anything it cannot say: no kept
 * spec (a Magician-made creature), not a creature or a ride, a body the menus no longer have.
 */
public final class BuilderBehaviourBackfill {

    private BuilderBehaviourBackfill() {
    }

    public static Archetype choose(String owner, String id) {
        BuilderSpecStore.Made kept = BuilderSpecStore.get(owner, id);
        if (kept == null || kept.magicianTouched()) {   //: a Magician change stands; the old picks do not say how it lives
            return null;
        }
        BuilderSpec spec = kept.read();
        if (spec == null || (spec.kind() != BuilderSpec.Kind.CREATURE && spec.kind() != BuilderSpec.Kind.RIDE)) {
            return null;
        }
        String from = Catalogue.bare(spec.from());
        if (!from.startsWith("species/") && !from.startsWith("bodies/")) {
            return null;
        }
        JsonObject sculpt = LibraryPresets.SHIPPED.read(from);
        return sculpt == null ? null : Archetype.byKey(BuilderLowering.behaviourOf(spec, sculpt));
    }
}
