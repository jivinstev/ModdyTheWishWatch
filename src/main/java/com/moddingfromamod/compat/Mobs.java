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
}
