package com.moddingfromamod.selector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Which entities of one entity lookup carry each tag, in that lookup's own order</b> —
 * pure, generic so it is unit-tested without Minecraft.
 *
 * <p>One index lives on each {@code EntityLookup} (the map an {@code @e} with no position walks,
 * {@code LevelEntityGetterAdapter.visibleEntities}). It is fed by exactly three things: the lookup
 * adding an entity, the lookup removing it, and the entity's own tag set changing while it is in
 * the lookup ({@code ObservedTagSet}). Vanilla walks the lookup's {@code byId}, a LINKED map, so
 * its order is insertion order; {@link #candidates} returns entities in that same order (a sequence
 * number taken at insertion), which is what makes a {@code limit=}ed answer identical.
 *
 * <p>Identity throughout: two entities are never "equal" here unless they are the same object.
 */
public final class TagIndex<E> {

    private final Map<String, Set<E>> byTag = new HashMap<>();
    /** The tags each entity is filed under — so removal never depends on its tags NOW. */
    private final Map<E, Set<String>> filed = new IdentityHashMap<>();
    private final Map<E, Long> seq = new IdentityHashMap<>();
    private long next;

    /** Is this entity in the lookup (as far as this index was told)? */
    public boolean contains(E e) {
        return seq.containsKey(e);
    }

    public int size() {
        return seq.size();
    }

    /** The lookup inserted it, carrying these tags. */
    public void added(E e, Iterable<String> tags) {
        if (seq.containsKey(e)) {
            return;
        }
        seq.put(e, next++);
        Set<String> mine = new java.util.HashSet<>();
        filed.put(e, mine);
        for (String t : tags) {
            file(e, t, mine);
        }
    }

    /** The lookup removed it. */
    public void removed(E e) {
        if (seq.remove(e) == null) {
            return;
        }
        Set<String> mine = filed.remove(e);
        if (mine != null) {
            for (String t : mine) {
                unfile(e, t);
            }
        }
    }

    /** It gained a tag while in the lookup. */
    public void tagAdded(E e, String tag) {
        Set<String> mine = filed.get(e);
        if (mine != null) {
            file(e, tag, mine);
        }
    }

    /** It lost a tag while in the lookup. */
    public void tagRemoved(E e, String tag) {
        Set<String> mine = filed.get(e);
        if (mine != null && mine.remove(tag)) {
            unfile(e, tag);
        }
    }

    /**
     * The entities carrying the RAREST of {@code tags}, in insertion order — a superset of the
     * entities carrying all of them, and empty when any is carried by nobody. The caller applies
     * vanilla's full predicate (which checks every tag again), so this is exact and cheaper than
     * intersecting.
     */
    public List<E> candidates(List<String> tags) {
        Set<E> best = null;
        for (String t : tags) {
            Set<E> s = byTag.get(t);
            if (s == null || s.isEmpty()) {
                return List.of();
            }
            if (best == null || s.size() < best.size()) {
                best = s;
            }
        }
        if (best == null) {
            return List.of();
        }
        List<E> out = new ArrayList<>(best);
        out.sort((a, b) -> Long.compare(seq.get(a), seq.get(b)));
        return out;
    }

    private void file(E e, String tag, Set<String> mine) {
        if (mine.add(tag)) {
            byTag.computeIfAbsent(tag, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(e);
        }
    }

    private void unfile(E e, String tag) {
        Set<E> s = byTag.get(tag);
        if (s != null) {
            s.remove(e);
            if (s.isEmpty()) {
                byTag.remove(tag);
            }
        }
    }
}
