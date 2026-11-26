package com.moddingfromamod.vanilla;

import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptOrientation;
import com.moddingfromamod.species.Species;

/**
 * <b>A sculpt's {@code body}, read off its parts</b> (#132). A species' {@link Species.Body} is the shape it stands in
 * as when its sculpted parts are not to hand ({@link StandInSculpt#manifest}: the stand-in's boxes and so its hitbox).
 * Every sculpt used to be stored {@code humanoid} whatever it was, so a dragon whose model went missing stood in as a
 * zombie two blocks tall. Read with the rig's own anatomy ({@link RigAnatomy}), in this order:
 * <ul>
 *   <li>six legs or more: {@link Species.Body#SPIDER};</li>
 *   <li>wings: {@link Species.Body#BIRD};</li>
 *   <li>legs both before and behind its middle: {@link Species.Body#QUADRUPED};</li>
 *   <li>no legs at all (a fish, a snake, a blob): {@link Species.Body#SLIME}, a low block rather than a person;</li>
 *   <li>anything else, legs under it like a person's: {@link Species.Body#HUMANOID}.</li>
 * </ul>
 * Nothing else reads the field for shape (movement, the carrier, the size bands and the make facts read the parts or
 * the stats). Pure, so the rule is a unit test ({@code SculptBodyTest}).
 */
public final class SculptBody {

    private SculptBody() {
    }

    /** The body these parts stand in as; {@link Species.Body#HUMANOID} when they cannot be read. */
    public static Species.Body of(List<CreatureSculpt.Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return Species.Body.HUMANOID;
        }
        try {
            RigAnatomy a = RigAnatomy.of(RigFit.intoRigSpace(SculptOrientation.faceForward(parts)));
            if (a.legs().size() >= 6) {
                return Species.Body.SPIDER;
            }
            if (!a.wings().isEmpty()) {
                return Species.Body.BIRD;
            }
            if (a.quadruped()) {
                return Species.Body.QUADRUPED;
            }
            if (a.legs().isEmpty()) {
                return Species.Body.SLIME;
            }
            return Species.Body.HUMANOID;
        } catch (RuntimeException e) {
            return Species.Body.HUMANOID;   // a sculpt the anatomy cannot read stands in as it always did
        }
    }
}
