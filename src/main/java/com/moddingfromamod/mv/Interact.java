package com.moddingfromamod.mv;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * {@code Player.interactOn(entity, hand)}, which 26.1 gave a third argument: where on the entity the click landed,
 * relative to the entity's position (the interact packet's location). A use that has no real hit (a test's scripted
 * right-click, a click forwarded from a hit proxy to its carrier) clicks the middle of the entity's box, so an entity
 * that reads the location (an armour stand picks a slot by its height) sees an ordinary click.
 */
public final class Interact {
    private Interact() {
    }

    public static InteractionResult on(Player player, Entity entity, InteractionHand hand) {
        Vec3 middle = entity.getBoundingBox().getCenter().subtract(entity.position());
        return player.interactOn(entity, hand, middle);
    }
}
