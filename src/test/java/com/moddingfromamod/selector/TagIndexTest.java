package com.moddingfromamod.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** an earlier issue's pure parts: the selector's tags, the index and the observed tag set. */
class TagIndexTest {

    @Test
    @DisplayName("positive top-level tags only; anything not fully understood is not indexed")
    void selectorTags() {
        assertEquals(List.of("wishmob_trex"), SelectorTags.positive("@e[tag=wishmob_trex]"));
        assertEquals(List.of("a", "b"), SelectorTags.positive("@e[tag=a, tag=b ,limit=1,sort=nearest]"));
        assertEquals(List.of("a"), SelectorTags.positive("@e[tag=!x,tag=a,tag=]"));
        assertEquals(List.of(), SelectorTags.positive("@e[tag=!x]"));
        assertEquals(List.of(), SelectorTags.positive("@e[tag=]"));
        assertEquals(List.of(), SelectorTags.positive("@e"));
        assertEquals(List.of(), SelectorTags.positive("@e[type=cow]"));
        // a tag= inside nbt or scores is not an option
        assertEquals(List.of("real"), SelectorTags.positive("@e[nbt={Tags:[\"fake\"],tag=fake},tag=real]"));
        assertEquals(List.of(), SelectorTags.positive("@e[nbt={tag=x}]"));
        assertEquals(List.of("a.b-c+d_1"), SelectorTags.positive("@e[tag=a.b-c+d_1]"));
        // not something vanilla reads as one unquoted tag: refuse to index rather than guess
        assertEquals(List.of(), SelectorTags.positive("@e[tag=\"quoted\"]"));
        assertEquals(List.of(), SelectorTags.positive("@e[tag=a b]"));
        assertEquals(List.of(), SelectorTags.positive("@e[nbt={unbalanced]"));
    }

    @Test
    @DisplayName("candidates come back in insertion order, and a re-added entity goes to the end")
    void order() {
        TagIndex<String> ix = new TagIndex<>();
        ix.added("a", List.of("t"));
        ix.added("b", List.of("t"));
        ix.added("c", List.of());
        ix.tagAdded("c", "t");          // tagged later, but ordered by when it was INSERTED
        assertEquals(List.of("a", "b", "c"), ix.candidates(List.of("t")));
        ix.removed("a");
        ix.added("a", List.of("t"));   // vanilla's linked map appends a re-insert
        assertEquals(List.of("b", "c", "a"), ix.candidates(List.of("t")));
        ix.tagRemoved("b", "t");
        assertEquals(List.of("c", "a"), ix.candidates(List.of("t")));
        assertEquals(List.of(), ix.candidates(List.of("t", "nobody")));
    }

    @Test
    @DisplayName("random add/remove/tag/untag: the index always equals a brute-force scan, in order")
    void differential() {
        Random r = new Random(1871);
        TagIndex<Integer> ix = new TagIndex<>();
        Map<Integer, java.util.Set<String>> world = new LinkedHashMap<>();   // insertion order = vanilla's
        String[] tags = {"a", "b", "c", "d"};
        for (int step = 0; step < 50_000; step++) {
            int e = r.nextInt(40);
            String t = tags[r.nextInt(tags.length)];
            switch (r.nextInt(5)) {
                case 0 -> {
                    if (!world.containsKey(e)) {
                        java.util.Set<String> ts = new java.util.HashSet<>();
                        if (r.nextBoolean()) {
                            ts.add(t);
                        }
                        world.put(e, ts);
                        ix.added(e, ts);
                    }
                }
                case 1 -> {
                    if (world.remove(e) != null) {
                        ix.removed(e);
                    }
                }
                case 2 -> {
                    if (world.containsKey(e) && world.get(e).add(t)) {
                        ix.tagAdded(e, t);
                    }
                }
                case 3 -> {
                    if (world.containsKey(e) && world.get(e).remove(t)) {
                        ix.tagRemoved(e, t);
                    }
                }
                default -> {
                    List<String> want = r.nextBoolean() ? List.of(t) : List.of(t, tags[r.nextInt(tags.length)]);
                    List<Integer> brute = new ArrayList<>();
                    world.forEach((k, v) -> {
                        if (v.containsAll(want)) {
                            brute.add(k);
                        }
                    });
                    List<Integer> got = new ArrayList<>(ix.candidates(want));
                    got.removeIf(k -> !world.get(k).containsAll(want));   // what the predicate does
                    assertEquals(brute, got, "step " + step + " " + want);
                }
            }
        }
        assertEquals(world.size(), ix.size());
    }

    @Test
    @DisplayName("every way to change the set reports, and only while listened to")
    void observedSet() {
        ObservedTagSet s = new ObservedTagSet();
        s.add("before");                     // not listened to yet: silent
        List<String> log = new ArrayList<>();
        s.listen(new ObservedTagSet.Listener() {
            @Override
            public void tagAdded(String tag) {
                log.add("+" + tag);
            }

            @Override
            public void tagRemoved(String tag) {
                log.add("-" + tag);
            }
        });
        s.add("a");
        s.add("a");                          // no change, no report
        s.addAll(List.of("b", "c", "d", "e"));
        s.remove("b");
        s.remove("nope");
        s.removeIf(t -> t.equals("c"));
        s.retainAll(List.of("a", "d", "before"));
        var it = s.iterator();
        while (it.hasNext()) {
            if (it.next().equals("d")) {
                it.remove();
            }
        }
        s.clear();
        assertTrue(s.isEmpty());
        assertEquals(List.of("+a", "+b", "+c", "+d", "+e", "-b", "-c", "-e", "-d"),
                log.subList(0, 9));
        assertEquals(java.util.Set.of("-a", "-before"), java.util.Set.copyOf(log.subList(9, log.size())));
    }
}
