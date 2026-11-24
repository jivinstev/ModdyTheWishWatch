package com.moddingfromamod.mv;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.Level;

/**
 * The day time and the weather, which 26.1 moved off the level. The day is the overworld's world clock
 * ({@code WorldClocks.OVERWORLD} in the server's {@code ServerClockManager}): {@code getDayTime()} read it in every
 * dimension before (the derived level data shared the overworld's), so {@link #get} reads the overworld clock too, and
 * {@link #set} sets that clock's total ticks the way {@code /time set <ticks>} does. The weather moved to the server
 * ({@code MinecraftServer.setWeatherParameters}, which {@code /weather} calls).
 */
public final class DayTime {
    private DayTime() {
    }

    /** The day time in ticks, unbounded (take it mod 24000 for the time of day), as {@code getDayTime()} was. */
    public static long get(Level level) {
        return level.getOverworldClockTime();
    }

    /** {@code /time set <ticks>}: the overworld clock's total ticks. */
    public static void set(ServerLevel level, long ticks) {
        level.registryAccess().get(WorldClocks.OVERWORLD)
                .ifPresent(clock -> level.getServer().clockManager().setTotalTicks(clock, ticks));
    }

    /** {@code /weather}: clear for {@code clearTicks}, or rain (and thunder) for {@code weatherTicks}. */
    public static void weather(ServerLevel level, int clearTicks, int weatherTicks, boolean raining, boolean thundering) {
        level.getServer().setWeatherParameters(clearTicks, weatherTicks, raining, thundering);
    }
}
