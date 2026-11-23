package com.moddingfromamod.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * <b>What an event changed, so the world can be given back</b> (E1). Pure: the state type is
 * a parameter, so the rules are tested with strings and run with block states.
 *
 * <p>Three rules, each a bug an earlier mod paid for:
 * <ul>
 *   <li><b>The first prior wins.</b> A cell an event writes twice (a wave passing over it) still
 *       remembers what was there before the event, never the event's own earlier write.</li>
 *   <li><b>Give back only what is still ours.</b> If something else changed a cell since we wrote it
 *       (the child placed a block in the water), restoring would delete his block; the cell is left
 *       alone and forgotten.</li>
 *   <li><b>Give back in reverse.</b> The last thing written comes off first, so a layered event (a
 *       flood) drains from the top and a stacked write never uncovers into a floating block.</li>
 * </ul>
 * Positions are packed longs (a {@code BlockPos.asLong}), so a big event stays a small map: states
 * are shared singletons, and the budget (docs/EVENTS_PLAN.md D2) keeps it under ~250k cells.
 */
public final class EventJournal<S> {

    /** One changed cell: what was there first, and what the event last put there. */
    public record Entry<S>(S prior, S written) {
    }

    /** One cell to put back. */
    public record Restore<S>(long pos, S state) {
    }

    private final LinkedHashMap<Long, Entry<S>> entries = new LinkedHashMap<>();

    /**
     * Record a write. Returns true for a cell the journal had not seen (it now counts toward the
     * event's total), false for one it already held — whose {@code prior} is kept.
     */
    public boolean record(long pos, S prior, S written) {
        Entry<S> had = entries.get(pos);
        if (had != null) {
            entries.put(pos, new Entry<>(had.prior(), written));
            return false;
        }
        entries.put(pos, new Entry<>(prior, written));
        return true;
    }

    /** What the event last wrote at {@code pos}, or null when it holds no such cell. */
    public S written(long pos) {
        Entry<S> e = entries.get(pos);
        return e == null ? null : e.written();
    }

    public boolean holds(long pos) {
        return entries.containsKey(pos);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Stop tracking one cell mid-event — a moving front whose shape has left it. Returns the state to
     * put back, or null when the cell is no longer ours (then it is simply forgotten).
     */
    public S release(long pos, S current) {
        Entry<S> e = entries.remove(pos);
        if (e == null) {
            return null;
        }
        return Objects.equals(e.written(), current) ? e.prior() : null;
    }

    /**
     * Take up to {@code max} cells off the journal, newest first, returning the ones to put back.
     * A cell whose current state is not the one we wrote is dropped without a restore.
     */
    public List<Restore<S>> drain(int max, LongFunction<S> current) {
        List<Restore<S>> out = new ArrayList<>();
        for (int taken = 0; taken < max && !entries.isEmpty(); taken++) {
            Map.Entry<Long, Entry<S>> last = entries.pollLastEntry();
            long pos = last.getKey();
            if (Objects.equals(last.getValue().written(), current.apply(pos))) {
                out.add(new Restore<>(pos, last.getValue().prior()));
            }
        }
        return out;
    }

    /** Every entry, oldest first (for saving). */
    public Iterable<Map.Entry<Long, Entry<S>>> entries() {
        return entries.entrySet();
    }

    /**
     * The journal in a flat, saveable shape: a palette of distinct states, and per cell its position
     * and two palette indexes. A quarter of a million cells saves as three int/long arrays plus a
     * palette of a few dozen states, rather than a quarter of a million compounds.
     */
    public <T> Flat<T> flatten(Function<S, T> encode) {
        List<T> palette = new ArrayList<>();
        Map<S, Integer> index = new LinkedHashMap<>();
        long[] pos = new long[entries.size()];
        int[] prior = new int[entries.size()];
        int[] written = new int[entries.size()];
        int i = 0;
        for (Map.Entry<Long, Entry<S>> e : entries.entrySet()) {
            pos[i] = e.getKey();
            prior[i] = index.computeIfAbsent(e.getValue().prior(), s -> {
                palette.add(encode.apply(s));
                return palette.size() - 1;
            });
            written[i] = index.computeIfAbsent(e.getValue().written(), s -> {
                palette.add(encode.apply(s));
                return palette.size() - 1;
            });
            i++;
        }
        return new Flat<>(palette, pos, prior, written);
    }

    /** The saved shape. */
    public record Flat<T>(List<T> palette, long[] pos, int[] prior, int[] written) {
    }

    /**
     * Rebuild from the saved shape. A palette entry that no longer decodes (a block from a mod that
     * was removed) decodes to null and its cells are skipped rather than failing the whole load: a
     * damaged record must never cost the rest of the world its restore.
     */
    public static <S, T> EventJournal<S> unflatten(Flat<T> flat, Function<T, S> decode) {
        List<S> states = new ArrayList<>();
        for (T t : flat.palette()) {
            S s;
            try {
                s = decode.apply(t);
            } catch (RuntimeException e) {
                s = null;
            }
            states.add(s);
        }
        EventJournal<S> j = new EventJournal<>();
        int n = Math.min(flat.pos().length, Math.min(flat.prior().length, flat.written().length));
        for (int i = 0; i < n; i++) {
            int p = flat.prior()[i];
            int w = flat.written()[i];
            if (p < 0 || p >= states.size() || w < 0 || w >= states.size()) {
                continue;
            }
            S prior = states.get(p);
            S written = states.get(w);
            if (prior == null || written == null) {
                continue;
            }
            j.entries.put(flat.pos()[i], new Entry<>(prior, written));
        }
        return j;
    }

    /** Test seam: the positions, oldest first. */
    List<Long> positions() {
        List<Long> out = new ArrayList<>();
        for (Iterator<Long> it = entries.keySet().iterator(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }
}
