package com.moddingfromamod.selector;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The {@code @e[tag=…]} index: how it is run, and what it did.</b>
 *
 * <p>A datapack's {@code @e[tag=X]} makes vanilla check the tag on every loaded entity, and every
 * rigged creature is ~25–60 display entities, so on one measured world's busiest area its 129 per-tick
 * selectors walked ~800 entities each. The index answers any {@code @e} with a positive
 * {@code tag=} and no position from the entities carrying that tag; vanilla's own predicate, sort
 * and limit still run over the answer ({@code mixin/SelectorIndexMixin}).
 *
 * <p>Three modes, from {@code -Dmoddingfromamod.selectorindex}:
 * <ul>
 *   <li>{@code on} (the default) — the index answers.</li>
 *   <li>{@code verify} — THE ORACLE: both answer, vanilla's is used, and any difference in the
 *       entities or their ORDER is counted and logged with the selector's text. Gate B can run whole
 *       in this mode, and so can a real world ({@code tools/selector-soak.sh}).</li>
 *   <li>{@code off} — vanilla answers and nothing is counted: ONLY the A/B baseline the soak's
 *       perf arm is measured against ({@code tools/selector-soak.sh}). Not a rollback switch — a
 *       suspicious server runs {@code verify}, which answers as vanilla does AND says what differed.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.moddingfromamod.ModdingFromAModMod.MODID)
public final class SelectorIndex {

    /** The run's totals, once, as the server stops: what a Gate B or a real-world rehearsal is judged by. */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        LOG.info("selector index at stop: {}, poisoned lookups {}", summary(), POISONED.get());
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-selector-index");

    public enum Mode { ON, VERIFY, OFF }

    private static volatile Mode mode = parse(System.getProperty("moddingfromamod.selectorindex", "on"));

    /** Selectors the index answered (or, in verify, also answered). */
    public static final AtomicLong INDEXED = new AtomicLong();
    /** Entities it handed to vanilla's predicate for them. */
    public static final AtomicLong CANDIDATES = new AtomicLong();
    /** {@code @e} selectors with no position that it could NOT answer (no positive tag, or a lookup it does not know). */
    public static final AtomicLong NOT_INDEXABLE = new AtomicLong();
    /** Verify mode: answers that differed from vanilla's. Must stay 0. */
    public static final AtomicLong MISMATCHES = new AtomicLong();
    /** Lookups whose index was disabled because an entity turned up in two (never seen; logged if it is). */
    public static final AtomicLong POISONED = new AtomicLong();

    private static final AtomicLong LOGGED = new AtomicLong();

    private SelectorIndex() {
    }

    static Mode parse(String v) {
        String m = v == null ? "" : v.strip();
        return "verify".equalsIgnoreCase(m) ? Mode.VERIFY : "off".equalsIgnoreCase(m) ? Mode.OFF : Mode.ON;
    }

    public static Mode mode() {
        return mode;
    }

    /** For a GameTest; every test that sets it puts it back. */
    public static void setModeForTest(Mode m) {
        mode = m;
    }

    /** A verify-mode difference: counted always, logged for the first few hundred. */
    public static void mismatch(String selector, Object vanilla, Object index) {
        MISMATCHES.incrementAndGet();
        if (LOGGED.incrementAndGet() <= 200) {
            LOG.error("selector index MISMATCH for {}: vanilla {} index {}", selector, vanilla, index);
        }
    }

    public static void poisoned(Object lookup, Object entity) {
        POISONED.incrementAndGet();
        LOG.warn("selector index: {} is in two entity lookups; the index for {} is off and vanilla answers there",
                entity, lookup);
    }

    private static final long[] LAST = new long[4];

    /** Since the last call: the tick monitor's window. */
    public static synchronized String window() {
        long[] now = {INDEXED.get(), CANDIDATES.get(), NOT_INDEXABLE.get(), MISMATCHES.get()};
        String out = String.format(java.util.Locale.ROOT, "indexed %d (%d candidates), not indexable %d, "
                        + "mismatches %d%s", now[0] - LAST[0], now[1] - LAST[1], now[2] - LAST[2], now[3] - LAST[3],
                mode == Mode.VERIFY ? " [verify]" : "");
        System.arraycopy(now, 0, LAST, 0, 4);
        return out;
    }

    /** One line for the tick monitor. */
    public static String summary() {
        return String.format(java.util.Locale.ROOT, "selectors indexed %d (%d candidates), not indexable %d, "
                        + "mismatches %d%s", INDEXED.get(), CANDIDATES.get(), NOT_INDEXABLE.get(), MISMATCHES.get(),
                mode == Mode.VERIFY ? " [verify]" : "");
    }
}
