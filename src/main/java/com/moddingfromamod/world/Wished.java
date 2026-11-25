package com.moddingfromamod.world;

import java.util.Collection;

import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.mob.WishMobLimit;
import net.minecraft.world.entity.Entity;

/**
 * <b>Did a player's wish make this body?</b> (#75) The one documented way for a world to tell a creature a wish made
 * from any other mob, whatever path made it: a Builder, Apprentice or Magician make, "Make another one", a recipe or a
 * hotbar fire. Read this rather than a tag, so the markers underneath can change.
 *
 * <p>Two markers are read today, and their exact meaning is documented in {@code docs/EXTENDING.md}:
 * <ul>
 *   <li>{@link WishMobTags#ALL} ({@code wishmob}): on every sculpted wish creature and on a rig's carrier (the
 *       living entity that holds a rig creature's stats), put on at spawn and again on every load;</li>
 *   <li>{@link WishMobLimit#WISHED_TAG} ({@code moddy_wished}): on a plain mob admitted inside a spawn scope (a wish's
 *       command, an event, a datapack function), the mob the wish's census counts.</li>
 * </ul>
 * A rig's display parts are not bodies and are never wished; a mob the world spawns by itself, or a child's own spawn
 * egg, carries neither marker.
 */
public final class Wished {

    private Wished() {
    }

    /** Is {@code e} a body a player's wish made? Server or client; cheap (two tag lookups). */
    public static boolean isWished(Entity e) {
        if (e == null || e instanceof net.minecraft.world.entity.Display) {
            return false;   // a rig's display part: part of a body, never one
        }
        return e instanceof com.moddingfromamod.entity.WishMobEntity || byTags(e.entityTags());
    }

    /** The decision on an entity's tags alone: pure. */
    public static boolean byTags(Collection<String> tags) {
        return tags != null && (tags.contains(WishMobTags.ALL) || tags.contains(WishMobLimit.WISHED_TAG));
    }
}
