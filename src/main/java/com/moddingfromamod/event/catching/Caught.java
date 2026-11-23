package com.moddingfromamod.event.catching;

import net.minecraft.world.entity.Entity;

/**
 * <b>One thing an event caught this tick</b> (E3): the entity, how hard it was caught (0–1),
 * and which way the event is pushing it, as a unit vector on the ground ({@code 0, 0} when it pushes
 * no particular way).
 */
public record Caught(Entity entity, double strength, double dirX, double dirZ) {
}
