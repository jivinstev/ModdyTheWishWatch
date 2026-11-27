package com.moddingfromamod.testing;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>Run only these GameTest classes</b> (targeted Gate B, a split nightly, a retried test).
 *
 * <p>{@code -Dmoddingfromamod.gameTestClasses=A,B,C.method} ({@code -Pgametests}): simple class names, and
 * {@code Class.method} for one test of a class ({@code tools/gate/gate.py run} retries a failed test that way, by
 * itself). Absent or blank, every class runs, as always. Matching ignores case, because a test's id is lower case.
 * {@code -Dmoddingfromamod.gameTestNotClasses=D,E} ({@code -Pgatenotclasses}) is the other side: every class EXCEPT
 * these, which is how the first job of a split run takes every class no other job named, so a class the planner has
 * never heard of still runs somewhere. {@code testing.gt.GameTestAdapter} asks {@link #allows(Class)} for each class
 * and {@link #allowsMethod} for each test, so a class outside the list registers no test, no batch hook, nothing.
 *
 * <p>⚠ A filtered run is NOT a full tier, although it boots the same namespaces. So the run record names the filter
 * ({@code GateBTimings} writes {@code classes} and {@code notClasses}), and the release evidence
 * ({@code tools/gate/gate.py evidence}) refuses a filtered run as full-tier evidence: only a split run whose jobs
 * together ran every class counts. See docs/GATE_B.md.
 */
public final class GameTestClassFilter {

    public static final String PROPERTY = "moddingfromamod.gameTestClasses";
    /** Every class EXCEPT these (the first job of a split run). */
    public static final String NOT_CLASSES = "moddingfromamod.gameTestNotClasses";
    /**
     * The FLAKE REGISTER's tests, left out of a release run ({@code corpus/gate-flakes.json},
     * {@code -Pgateexclude}): {@code Class.method} entries, compared case-insensitively. A run that
     * sets it records {@code excluded}, and release evidence accepts it only when every name is in
     * the register.
     */
    public static final String EXCLUDE = "moddingfromamod.gameTestExclude";

    private static volatile Set<String> cached;
    private static volatile Set<String> cachedExclude;
    private static volatile Set<String> cachedNot;

    private GameTestClassFilter() {
    }

    /** The allow-list, lower-cased, or an EMPTY set meaning "no filter". */
    public static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> active() {
        Set<String> c = cached;
        if (c == null) {
            c = parse(System.getProperty(PROPERTY));
            cached = c;
        }
        return c;
    }

    private static Set<String> activeNot() {
        Set<String> c = cachedNot;
        if (c == null) {
            c = parse(System.getProperty(NOT_CLASSES));
            cachedNot = c;
        }
        return c;
    }

    /**
     * True when there is no filter, or the class is on it, by itself or through one of its tests ({@code Class.method}).
     */
    public static boolean allows(Set<String> filter, String simpleName) {
        if (filter.isEmpty()) {
            return true;
        }
        String c = simpleName.toLowerCase(java.util.Locale.ROOT);
        if (filter.contains(c)) {
            return true;
        }
        String prefix = c + ".";
        return filter.stream().anyMatch(e -> e.startsWith(prefix));
    }

    /**
     * May this test of an allowed class register? Yes, unless the filter names only SOME of its class's tests
     * ({@code Class.method}) and not this one.
     */
    public static boolean allowsMethod(Set<String> filter, String simpleClass, String method) {
        if (filter.isEmpty()) {
            return true;
        }
        String c = simpleClass.toLowerCase(java.util.Locale.ROOT);
        if (filter.contains(c)) {
            return true;
        }
        return filter.contains(c + "." + method.toLowerCase(java.util.Locale.ROOT));
    }

    /** True unless the class is on the deny list ({@link #NOT_CLASSES}). */
    public static boolean notDenied(Set<String> deny, String simpleName) {
        return deny.isEmpty() || !deny.contains(simpleName.toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean allows(Class<?> cls) {
        return allows(active(), cls.getSimpleName()) && notDenied(activeNot(), cls.getSimpleName())
                && bedrockAllows(cls.getSimpleName(), bedrockBatchOn());
    }

    public static boolean allowsMethod(java.lang.reflect.Method m) {
        return allowsMethod(active(), m.getDeclaringClass().getSimpleName(), m.getName());
    }

    /** {@code -Dmoddingfromamod.bedrockbatch=on}: Gate B's Bedrock batch is running (docs/BEDROCK_GATES.md). */
    public static final String BEDROCK_BATCH = "moddingfromamod.bedrockbatch";

    public static boolean bedrockBatchOn() {
        return "on".equalsIgnoreCase(System.getProperty(BEDROCK_BATCH, ""));
    }

    /**
     * A {@code BedrockBatch*} GameTest is registered ONLY when the Bedrock batch runs: each one waits for a
     * Bedrock bot that only that run starts, so registered without it every one would time out — and passing
     * it instead would report Bedrock coverage nobody measured.
     */
    public static boolean bedrockAllows(String simpleName, boolean batchOn) {
        return batchOn || !simpleName.startsWith("BedrockBatch");
    }

    /**
     * {@code -Dmoddingfromamod.bedrockOnly=a,b,c}: of the Bedrock batch, only these CASES (method names) —
     * the ones the coverage map picked for a change. Absent: every case, as {@code --all} and the nightly run.
     */
    public static final String BEDROCK_ONLY = "moddingfromamod.bedrockOnly";

    public static boolean bedrockCaseAllowed(java.lang.reflect.Method m) {
        return bedrockCaseAllowed(m.getDeclaringClass().getSimpleName(), m.getName(), System.getProperty(BEDROCK_ONLY));
    }

    static boolean bedrockCaseAllowed(String simpleClass, String method, String only) {
        if (!simpleClass.startsWith("BedrockBatch") || only == null || only.isBlank()) {
            return true;
        }
        return parse(only).contains(method);
    }

    /** The exclusion list, lower-cased {@code class.method}; EMPTY means nothing is left out. */
    public static Set<String> parseExclude(String raw) {
        return parse(raw).stream().map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** True when {@code simpleClass.method} is on the exclusion list. */
    public static boolean excluded(Set<String> exclude, String simpleClass, String method) {
        return !exclude.isEmpty()
                && exclude.contains((simpleClass + "." + method).toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean excluded(java.lang.reflect.Method m) {
        Set<String> c = cachedExclude;
        if (c == null) {
            c = parseExclude(System.getProperty(EXCLUDE));
            cachedExclude = c;
        }
        return excluded(c, m.getDeclaringClass().getSimpleName(), m.getName());
    }
}
