package com.moddingfromamod.testing;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>Run only these GameTest classes</b> — an earlier issue's targeted Gate B.
 *
 * <p>{@code -Dmoddingfromamod.gameTestClasses=A,B,C} (simple class names; {@code tools/gate-b.sh}
 * passes the list {@code tools/gate-select.py --policy targeted} chose, through {@code -Pgametests}).
 * Absent or blank, every class runs, as always. {@code testing.gt.GameTestAdapter} asks {@link #allows}
 * for each method NeoForge registers, so a class outside the list registers no test, no batch hook,
 * nothing.
 *
 * <p>⚠ A filtered run is NOT a full tier, although it boots the same namespaces. So the run record
 * names the filter ({@code GateBTimings} writes {@code classes}), and {@code release-evidence.py}
 * refuses a filtered run as full-tier evidence — without that, a 90-test targeted run would read as
 * the 729-test tier it shares namespaces with.
 */
public final class GameTestClassFilter {

    public static final String PROPERTY = "moddingfromamod.gameTestClasses";
    /**
     * The FLAKE REGISTER's tests, left out of a release run ({@code corpus/gate-flakes.json},
     * {@code -Pgateexclude}): {@code Class.method} entries, compared case-insensitively. A run that
     * sets it records {@code excluded}, and release evidence accepts it only when every name is in
     * the register.
     */
    public static final String EXCLUDE = "moddingfromamod.gameTestExclude";

    private static volatile Set<String> cached;
    private static volatile Set<String> cachedExclude;

    private GameTestClassFilter() {
    }

    /** The allow-list, or an EMPTY set meaning "no filter". */
    public static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> active() {
        Set<String> c = cached;
        if (c == null) {
            c = parse(System.getProperty(PROPERTY));
            cached = c;
        }
        return c;
    }

    /** True when there is no filter, or the class's simple name is on it. */
    public static boolean allows(Set<String> filter, String simpleName) {
        return filter.isEmpty() || filter.contains(simpleName);
    }

    public static boolean allows(Class<?> cls) {
        return allows(active(), cls.getSimpleName()) && bedrockAllows(cls.getSimpleName(), bedrockBatchOn());
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
