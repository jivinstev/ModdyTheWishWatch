package com.moddingfromamod.selector;

import java.util.AbstractSet;
import java.util.HashSet;
import java.util.Iterator;

/**
 * <b>An entity's tag set that tells its {@link TagIndex} about every change</b> — pure.
 *
 * <p>Swapped in for {@code Entity.tags} at construction. Hooking {@code addTag}/{@code removeTag}
 * would not be enough: {@code Entity.getTags()} hands out the live set (checked in 1.21.8's
 * bytecode), vanilla's own load clears and refills it directly, and any code can mutate it. So the
 * DATA STRUCTURE reports, and every path — {@code /tag}, {@code /data merge}, a load, a direct
 * {@code getTags().add} — is covered by construction.
 *
 * <p>Behaves exactly like the {@code HashSet} vanilla uses (it delegates to one, so iteration order
 * — and therefore the order of a saved {@code Tags} list — is unchanged). It reports only while
 * {@link #listener} is set, i.e. while the entity is in an indexed lookup.
 */
public final class ObservedTagSet extends AbstractSet<String> {

    /** Told about each change. */
    public interface Listener {
        void tagAdded(String tag);

        void tagRemoved(String tag);
    }

    private final HashSet<String> tags = new HashSet<>();
    private volatile Listener listener;

    public void listen(Listener l) {
        this.listener = l;
    }

    public Listener listener() {
        return listener;
    }

    @Override
    public boolean add(String t) {
        boolean changed = tags.add(t);
        Listener l = listener;
        if (changed && l != null) {
            l.tagAdded(t);
        }
        return changed;
    }

    @Override
    public boolean remove(Object o) {
        boolean changed = tags.remove(o);
        Listener l = listener;
        if (changed && l != null && o instanceof String t) {
            l.tagRemoved(t);
        }
        return changed;
    }

    @Override
    public boolean contains(Object o) {
        return tags.contains(o);
    }

    @Override
    public int size() {
        return tags.size();
    }

    @Override
    public void clear() {
        Listener l = listener;
        if (l == null) {
            tags.clear();
            return;
        }
        for (String t : new java.util.ArrayList<>(tags)) {
            tags.remove(t);
            l.tagRemoved(t);
        }
    }

    @Override
    public Iterator<String> iterator() {
        Iterator<String> it = tags.iterator();
        return new Iterator<>() {
            private String last;

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public String next() {
                last = it.next();
                return last;
            }

            @Override
            public void remove() {
                it.remove();
                Listener l = listener;
                if (l != null) {
                    l.tagRemoved(last);
                }
            }
        };
    }
}
