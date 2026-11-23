package com.moddingfromamod.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * The registry private plug-ins fill and the core reads. Pure Java: no Minecraft, so it is unit-tested
 * directly.
 *
 * <p>Hooks are keyed by their interface. {@link #all} keeps registration order, so a plug-in loaded earlier
 * answers first. A hook with nothing registered is simply absent, and the core must then behave as the public
 * single-player mod does: that is the whole contract.
 */
public final class Plugins {

    private static final Plugins SHARED = new Plugins();
    /** A unit test's own registry while it runs; null everywhere else, which is the server's case. */
    private static volatile Plugins scope;

    private final Map<Class<?>, List<Object>> hooks = new LinkedHashMap<>();
    private final List<String> loaded = new ArrayList<>();
    private final java.util.Set<Class<?>> hidden = new java.util.HashSet<>();
    private final Map<Class<?>, Object> replaced = new java.util.HashMap<>();
    private boolean loadedOnce;

    /** The mod's one registry (or, while a unit test has one in scope, that test's: {@link #scopeForTest}). */
    public static Plugins shared() {
        Plugins s = scope;
        return s != null ? s : SHARED;
    }

    /**
     * A unit test's seam: until it is put back, {@link #shared()} answers {@code test} instead of the mod's one
     * registry, so a test sees only the plug-ins it registered itself and leaves nothing behind for the next test in
     * its JVM. Returns what was in scope before, which the caller passes back here when it ends ({@code null} is the
     * real registry). Used through the test extensions {@code plugin/PluginScope} and {@code plugin/RealPlugins}; a
     * unit test never loads or adds to the real registry ({@code PluginScopeSweepTest}). Process-wide, so it suits
     * Gate A, whose test JVMs run one test at a time; never a GameTest, whose batches run concurrently.
     */
    public static Plugins scopeForTest(Plugins test) {
        Plugins was = scope;
        scope = test;
        return was;
    }

    /**
     * Find every {@link ModdyPlugin} listed for {@code loader} and let each register. Idempotent: a second call
     * does nothing. A plug-in that throws is reported and skipped, never fatal to the core.
     */
    public synchronized void load(ClassLoader loader, Consumer<String> log) {
        if (loadedOnce) return;
        loadedOnce = true;
        // The iterator itself throws ServiceConfigurationError for a listed class it cannot load (a line left in
        // the services file whose class is not in this jar), so hasNext/next sit inside the try as well as
        // register(). Bounded, so a provider list that keeps failing cannot loop forever.
        java.util.Iterator<ModdyPlugin> it = ServiceLoader.load(ModdyPlugin.class, loader).iterator();
        for (int guard = 0; guard < 1000; guard++) {
            ModdyPlugin p;
            try {
                if (!it.hasNext()) break;
                p = it.next();
            } catch (java.util.ServiceConfigurationError e) {
                log.accept("plugin FAILED to load (listed but not loadable): " + e.getMessage());
                continue;
            }
            try {
                p.register(this);
                loaded.add(p.name());
                log.accept("plugin loaded: " + p.name());
            } catch (RuntimeException | LinkageError e) {
                log.accept("plugin FAILED to load: " + p.getClass().getName() + ": " + e);
            }
        }
        if (loaded.isEmpty()) log.accept("plugins: none (the public single-player mod)");
    }

    /** Register {@code impl} as an implementation of the hook {@code type}. */
    public synchronized <T> void add(Class<T> type, T impl) {
        hooks.computeIfAbsent(type, k -> new ArrayList<>()).add(type.cast(impl));
    }

    /** Every implementation of {@code type}, in registration order; empty when none. */
    public synchronized <T> List<T> all(Class<T> type) {
        Object r = replaced.get(type);
        if (r != null) return List.of(type.cast(r));
        List<Object> l = hidden.contains(type) ? null : hooks.get(type);
        if (l == null) return List.of();
        List<T> out = new ArrayList<>(l.size());
        for (Object o : l) out.add(type.cast(o));
        return Collections.unmodifiableList(out);
    }

    /** The first implementation of {@code type}, if any. */
    public <T> Optional<T> first(Class<T> type) {
        List<T> l = all(type);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    /**
     * A test's seam: answer as if nothing implemented {@code type}, so a GameTest can drive the core as the
     * public mod runs it while the private plug-ins are loaded. Process-wide: use a batch of its own, and always
     * undo it ({@code hide} false) when the test ends.
     */
    public synchronized void hideForTest(Class<?> type, boolean hide) {
        if (hide) {
            hidden.add(type);
        } else {
            hidden.remove(type);
        }
    }

    /**
     * A test's seam: answer as if {@code impl} were the ONLY implementation of {@code type} ({@code null}
     * puts the registered ones back), so a GameTest can drive the core with an example plug-in while other
     * plug-ins are loaded. Process-wide, like {@link #hideForTest}: a batch of its own, always undone.
     */
    public synchronized <T> void replaceForTest(Class<T> type, T impl) {
        if (impl == null) {
            replaced.remove(type);
        } else {
            replaced.put(type, type.cast(impl));
        }
    }

    /** Every hook type something is registered for, in first-registration order (what an adapt-only test reads). */
    public synchronized List<Class<?>> hookTypes() {
        return List.copyOf(hooks.keySet());
    }

    /** Names of the plug-ins that loaded, in order. */
    public synchronized List<String> loaded() {
        return List.copyOf(loaded);
    }
}
