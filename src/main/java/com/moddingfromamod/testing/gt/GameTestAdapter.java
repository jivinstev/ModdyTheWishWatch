package com.moddingfromamod.testing.gt;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Consumer;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Gate B on 1.21.8</b> (docs/VERSION_BUMP_1218.md §7, measured in tools/bump/spike-1218): registers every {@link GameTest} method of every {@link GameTestHolder} class on the 1.21.5+
 * registry-based framework — a TEST_FUNCTION per method, a TEST_INSTANCE per test, a TEST_ENVIRONMENT per
 * (namespace, batch) so the runner's grouping-by-environment reproduces today's batches.
 */
public final class GameTestAdapter {
    private static final Logger LOG = LoggerFactory.getLogger("gt-adapter");

    private GameTestAdapter() {
    }

    record Found(String ns, String id, Method method, GameTest spec) {
    }

    private static List<Found> found;

    static synchronized List<Found> found() {
        if (found != null) {
            return found;
        }
        Set<String> enabled = new HashSet<>(Arrays.asList(
                System.getProperty("neoforge.enabledGameTestNamespaces", "").split(",")));
        List<Found> out = new ArrayList<>();
        ModList.get().getAllScanData().forEach(scan -> scan.getAnnotatedBy(GameTestHolder.class, java.lang.annotation.ElementType.TYPE)
                .forEach(a -> {
                    try {
                        Class<?> c = Class.forName(a.clazz().getClassName());
                        String ns = c.getAnnotation(GameTestHolder.class).value();
                        // the shard (GateShards) and an earlier issue's targeted run (GameTestClassFilter) — what
                        // GameTestRegistryMixin did on 1.21.4, here where every test is registered
                        if (!enabled.contains(ns) || !com.moddingfromamod.testing.GameTestClassFilter.allows(c)) {
                            return;
                        }
                        for (Method m : c.getDeclaredMethods()) {
                            GameTest g = m.getAnnotation(GameTest.class);
                            // NOT only static: 109 of the suite's tests are instance methods, which
                            // NeoForge's 1.21.4 scanner ran on a fresh instance (measured: skipping
                            // them made Gate B 1147 tests instead of 1256, and green says nothing)
                            if (g == null || com.moddingfromamod.testing.GameTestClassFilter.excluded(m)) {
                                continue;   // excluded: the flake register, left out of a release run
                            }
                            if (!com.moddingfromamod.testing.GameTestClassFilter.allowsMethod(m)) {
                                continue;   //: the filter names other tests of this class (a retry runs one alone)
                            }
                            if (!com.moddingfromamod.testing.GameTestClassFilter.bedrockCaseAllowed(m)) {
                                continue;   //: a Bedrock case the coverage map did not pick for this change
                            }
                            String id = c.getSimpleName().toLowerCase(Locale.ROOT) + "." + m.getName().toLowerCase(Locale.ROOT);
                            out.add(new Found(ns, id, m, g));
                        }
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                }));
        out.sort(Comparator.comparing(Found::ns).thenComparing(Found::id));
        LOG.info("gt-adapter: {} tests found", out.size());
        found = out;
        return out;
    }

    /**
     * The batch a test runs in: its own, or on a coverage-mapping run ({@link CoverageMap}) its own
     * WITHIN its class, so no two classes ever run at once and each one's execution data can be told
     * apart. Its own batches are kept: tests of one class that share a static are in separate batches
     * on purpose (ReadyMadeNudgeGameTest sat 15 minutes when one class was one batch).
     */
    private static String batchOf(Found f) {
        return CoverageMap.on() ? CoverageMap.batchName(f.method().getDeclaringClass().getSimpleName(), f.spec().batch())
                : f.spec().batch();
    }

    /** The GameTest classes this server registered, in order (a mapping run gives each a settle window). */
    private static List<String> mappedClasses() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Found f : found()) {
            out.add(f.method().getDeclaringClass().getSimpleName());
        }
        return new ArrayList<>(out);
    }

    /** How many tests this server registered (after the shard, the class filter and the flake register). */
    public static int registeredCount() {
        return found == null ? -1 : found.size();
    }

    /** Only where tests can run: on a player's server this registers nothing (no stray registry entries). */
    public static void register(IEventBus modBus) {
        if (!net.neoforged.neoforge.gametest.GameTestHooks.isGametestEnabled()) {
            return;
        }
        CoverageMap.start();
        modBus.addListener(RegisterEvent.class, e -> e.register(Registries.TEST_FUNCTION, h -> {
            for (Found f : found()) {
                Method m = f.method();
                ResourceLocation testId = ResourceLocation.fromNamespaceAndPath(f.ns(), f.id());
                h.register(testId, (Consumer<GameTestHelper>) helper -> {
                    com.moddingfromamod.testing.GateBTimings.started(testId.toString());
                    CoverageMap.enter(CoverageMap.unitOf(m));
                    Set<UUID> live = TestHygiene.before();   //: a wish this body puts in flight ends with it
                    try {
                        Object target = Modifier.isStatic(m.getModifiers()) ? null
                                : m.getDeclaringClass().getDeclaredConstructor().newInstance();
                        m.invoke(target, helper);
                    } catch (java.lang.reflect.InvocationTargetException ex) {
                        // a test exception is the verdict; anything else fails THIS test (1.21.4's
                        // GameTestInfo.fail(Throwable) did it; 1.21.8's would crash the server)
                        if (ex.getCause() instanceof net.minecraft.gametest.framework.GameTestException g2) {
                            throw g2;
                        }
                        throw GameTestCompat.failure(ex.getCause());
                    } catch (ReflectiveOperationException ex) {
                        throw GameTestCompat.failure(ex);
                    } finally {
                        TestHygiene.after(helper, f.id(), live);
                    }
                });
            }
            if (CoverageMap.on()) {     // the idle window: what a server with no test of ours running executes anyway
                h.register(CoverageMap.IDLE_ID, (Consumer<GameTestHelper>) helper -> {
                    CoverageMap.enter(CoverageMap.IDLE);
                    helper.runAfterDelay(CoverageMap.IDLE_TICKS, helper::succeed);
                });
                // each class's settle window: what it left running is still its own
                for (String cls : mappedClasses()) {
                    h.register(CoverageMap.settleId(cls), (Consumer<GameTestHelper>) helper -> {
                        CoverageMap.enter(cls);
                        long start = System.currentTimeMillis();
                        helper.runAfterDelay(CoverageMap.SETTLE_TICKS, () -> helper.succeedWhen(() -> {
                            if (!CoverageMap.settled(start, System.currentTimeMillis(), CoverageMap.quiet())) {
                                throw GameTestCompat.failure(new IllegalStateException("settling"));
                            }
                        }));
                    });
                }
            }
        }));
        modBus.addListener(RegisterGameTestsEvent.class, e -> {
            Map<String, Holder<TestEnvironmentDefinition>> envs = new HashMap<>();
            java.util.Set<String> still = new java.util.HashSet<>();
            for (Found f : found()) {
                if (!f.spec().randomTicks()) {
                    still.add(f.ns() + ":" + batchOf(f));
                }
            }
            for (Found f : found()) {
                String batch = batchOf(f);
                String key = f.ns() + ":" + batch;
                Holder<TestEnvironmentDefinition> env = envs.computeIfAbsent(key, k -> e.registerEnvironment(
                        ResourceLocation.fromNamespaceAndPath(f.ns(), "batch/" + batch.toLowerCase(Locale.ROOT)),
                        new TestEnvironmentDefinition.AllOf(still.contains(k)
                                ? List.of(Holder.direct(new TestEnvironmentDefinition.SetGameRules(List.of(), List.of(
                                        TestEnvironmentDefinition.SetGameRules.entry(
                                                net.minecraft.world.level.GameRules.RULE_RANDOMTICKING, 0)))))
                                : List.of())));
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(f.ns(), f.id());
                TestData<Holder<TestEnvironmentDefinition>> data = new TestData<>(env,
                        ResourceLocation.fromNamespaceAndPath(f.ns(), f.spec().template()),
                        CoverageMap.timeoutOf(f.method(), f.spec().timeoutTicks()), 0, f.spec().required(), net.minecraft.world.level.block.Rotation.NONE,
                        false, f.spec().attempts(), 1, f.spec().skyAccess());
                e.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id), data));
            }
            if (CoverageMap.on()) {
                for (String cls : mappedClasses()) {
                    Holder<TestEnvironmentDefinition> settle = e.registerEnvironment(
                            ResourceLocation.fromNamespaceAndPath(CoverageMap.IDLE_ID.getNamespace(),
                                    "batch/" + CoverageMap.settleBatch(cls).toLowerCase(Locale.ROOT)),
                            new TestEnvironmentDefinition.AllOf(List.of()));
                    ResourceLocation sid = CoverageMap.settleId(cls);
                    e.registerTest(sid, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, sid),
                            new TestData<>(settle, ResourceLocation.fromNamespaceAndPath(sid.getNamespace(), "empty_test"),
                                    2000, 0, true, net.minecraft.world.level.block.Rotation.NONE, false, 1, 1, false)));
                }
                Holder<TestEnvironmentDefinition> idle = e.registerEnvironment(
                        ResourceLocation.fromNamespaceAndPath(CoverageMap.IDLE_ID.getNamespace(),
                                "batch/" + CoverageMap.IDLE_BATCH),
                        new TestEnvironmentDefinition.AllOf(List.of()));
                e.registerTest(CoverageMap.IDLE_ID, new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, CoverageMap.IDLE_ID),
                        new TestData<>(idle, ResourceLocation.fromNamespaceAndPath(CoverageMap.IDLE_ID.getNamespace(), "empty_test"),
                                CoverageMap.IDLE_TICKS * 2, 0, true, net.minecraft.world.level.block.Rotation.NONE,
                                false, 1, 1, false)));
            }
            LOG.info("gt-adapter: registered {} tests in {} environments", found().size(), envs.size());
        });
    }
}
