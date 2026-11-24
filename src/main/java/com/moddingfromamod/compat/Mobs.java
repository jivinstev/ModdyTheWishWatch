package com.moddingfromamod.compat;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;

/** 1.21.5 removed {@code FlyingMob}, the base of exactly the ghast and the phantom. */
public final class Mobs {

    private Mobs() {
    }

    /** What {@code mob instanceof FlyingMob} said on 1.21.4. */
    public static boolean isFlyingMob(Object mob) {
        return mob instanceof Ghast || mob instanceof Phantom;
    }

    /**
     * What {@code mob instanceof FlyingAnimal} said on 26.1.2. 26.2 removed the interface; what it marked is now the
     * protected {@code LivingEntity.omnidirectionalAirMover()}, which only the bee and the parrot override, the two
     * classes that implemented it.
     */
    public static boolean isFlyingAnimal(Object mob) {
        return mob instanceof net.minecraft.world.entity.animal.bee.Bee
                || mob instanceof net.minecraft.world.entity.animal.parrot.Parrot;
    }
}
