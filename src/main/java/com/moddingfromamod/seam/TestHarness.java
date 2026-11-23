package com.moddingfromamod.seam;

import java.lang.reflect.InvocationTargetException;

import net.neoforged.bus.api.IEventBus;

/**
 * <b>Boot the GameTest harness, if it is here</b>. The harness ({@code com.moddingfromamod.testing}) runs Gate
 * B from the build's classes and never ships: the jar leaves the package out and {@code NEVER_SHIP} fails a jar that
 * carries it. So the mod constructor cannot name it. It asks this instead, which loads
 * {@code testing/HarnessBoot} by name: present (a dev run, Gate B, a headless probe) it registers the GameTests, the
 * per-test timings and the behaviour exercise; absent (every shipped jar) nothing happens.
 *
 * <p>An internal seam, not a plug-in: nothing but the harness fills it, so it is not in {@code plugin/Plugins}.
 */
public final class TestHarness {

    /** The class that wires the harness in; it lives in the package that never ships. */
    public static final String BOOT = "com.moddingfromamod.testing.HarnessBoot";

    private TestHarness() {
    }

    /** Boots the harness on the mod bus; false when this build carries none (a shipped jar). */
    public static boolean install(IEventBus modBus) {
        Class<?> boot;
        try {
            boot = Class.forName(BOOT, true, TestHarness.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return false;
        }
        try {
            boot.getMethod("boot", IEventBus.class).invoke(null, modBus);
            return true;
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("the GameTest harness failed to boot", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(BOOT + " has no public static boot(IEventBus)", e);
        }
    }

    /** True when this build carries the harness (a dev run); false in a shipped jar. */
    public static boolean present() {
        try {
            Class.forName(BOOT, false, TestHarness.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
