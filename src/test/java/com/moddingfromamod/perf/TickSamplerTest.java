package com.moddingfromamod.perf;


import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TickSamplerTest {

    private static StackTraceElement f(String cls, String m) {
        return new StackTraceElement(cls, m, null, 1);
    }

    @Test
    void aRunningDatapackIsNamedFirst() {
        StackTraceElement[] st = {f("java.util.HashMap", "get"), f("com.moddingfromamod.x.Y", "z")};
        assertEquals("datapack:stomp_powers", TickSampler.where(st, "stomp_powers"));
    }

    @Test
    void otherwiseOurOwnFrameThenMinecraft() {
        StackTraceElement[] ours = {f("java.util.HashMap", "get"), f("net.minecraft.world.level.Level", "getEntities"),
                f("com.moddingfromamod.vanilla.Rig", "tick"), f("net.minecraft.server.MinecraftServer", "tickServer")};
        assertEquals("Rig.tick", TickSampler.where(ours, null));
        StackTraceElement[] vanilla = {f("java.lang.Object", "wait"), f("net.minecraft.server.level.ServerChunkCache", "tick")};
        assertEquals("ServerChunkCache.tick", TickSampler.where(vanilla, ""));
    }

    @Test
    void topCountsMostFirst() {
        TickSampler s = new TickSampler();
        for (int i = 0; i < 5; i++) {
            s.add("datapack:a");
        }
        s.add("B.c");
        s.add("B.c");
        assertEquals("datapack:a ×5, B.c ×2", s.top(6));
        assertEquals(7, s.total());
        s.reset();
        assertEquals("", s.top(6));
    }
}
