package com.moddingfromamod.vanilla;

import net.minecraft.world.entity.Entity;

/**
 * <b>The server-side "he is talking" flag on a rig's carrier</b> (Moddy Media Stage 2).
 *
 * <p>A tag on the carrier mob, so it is saved with him, can be read with {@code /data get entity …
 * Tags} and selected with {@code @e[tag=rig_talking]}, and needs no packet: the rig's animator reads
 * it every tick and plays the species' {@code talk} clip ({@code BedrockAnimation.Clip.TALK}) while it
 * is there, or the idle when the species has none. Set and cleared by {@code /wishmob talk on|off}.
 *
 * <p>Not {@code wishmob_…}: that prefix is the species tag, and {@code WishMobTags.isSpeciesTag}
 * would take this for one.
 */
public final class RigTalk {

    /** The flag. */
    public static final String TAG = "rig_talking";

    private RigTalk() {
    }

    public static boolean isTalking(Entity carrier) {
        return carrier != null && carrier.entityTags().contains(TAG);
    }

    /**
     * Turn the flag on or off.
     *
     * @return true when it changed
     */
    public static boolean set(Entity carrier, boolean on) {
        if (carrier == null || isTalking(carrier) == on) {
            return false;
        }
        return on ? carrier.addTag(TAG) : carrier.removeTag(TAG);
    }
}
