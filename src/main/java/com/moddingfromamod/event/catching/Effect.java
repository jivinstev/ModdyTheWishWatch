package com.moddingfromamod.event.catching;

import net.minecraft.server.level.ServerLevel;

/** <b>What an event does to one thing it caught</b> (E3). See {@link Effects}. */
@FunctionalInterface
public interface Effect {

    void apply(ServerLevel level, Caught c, int age);
}
