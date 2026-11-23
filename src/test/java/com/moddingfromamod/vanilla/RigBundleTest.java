package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** a tracker pass's rig packets reach each viewer together, in order, and nothing is kept past the flush. */
class RigBundleTest {

    private record Sent(String viewer, List<Integer> packets) {
    }

    @Test
    void eachViewerGetsItsPacketsTogetherInOrder() {
        RigBundle<String, Integer> b = new RigBundle<>();
        b.add(List.of("alex", "sam"), 1);
        b.add(List.of("sam"), 2);
        b.add(List.of("alex", "sam"), 3);
        List<Sent> sent = new ArrayList<>();
        b.flush((v, ps) -> sent.add(new Sent(v, List.copyOf(ps))));
        assertEquals(List.of(new Sent("alex", List.of(1, 3)), new Sent("sam", List.of(1, 2, 3))), sent);
        assertTrue(b.isEmpty(), "a flush forgets what it sent");
        List<Sent> again = new ArrayList<>();
        b.flush((v, ps) -> again.add(new Sent(v, List.copyOf(ps))));
        assertTrue(again.isEmpty(), "nothing is sent twice");
    }

    @Test
    void aBundleStaysUnderTheProtocolLimit() {
        RigBundle<String, Integer> b = new RigBundle<>();
        int n = RigBundle.MAX_PER_BUNDLE * 2 + 5;
        for (int i = 0; i < n; i++) {
            b.add(List.of("alex"), i);
        }
        List<Integer> sizes = new ArrayList<>();
        List<Integer> all = new ArrayList<>();
        b.flush((v, ps) -> {
            sizes.add(ps.size());
            all.addAll(ps);
        });
        assertEquals(List.of(RigBundle.MAX_PER_BUNDLE, RigBundle.MAX_PER_BUNDLE, 5), sizes);
        assertTrue(RigBundle.MAX_PER_BUNDLE < 4096, "the protocol refuses a bundle of 4096 or more");
        for (int i = 0; i < n; i++) {
            assertEquals(i, all.get(i), "order kept across bundles");
        }
    }

    @Test
    void holdsOnlyBetweenBeginAndEndOnItsOwnThread() throws Exception {
        assertTrue(RigBundle.ON, "on by default");
        List<Sent> sent = new ArrayList<>();
        assertFalse(RigBundle.holding());
        RigBundle.begin((v, ps) -> sent.add(new Sent((String) v, List.copyOf((List<Integer>) (List<?>) ps))));
        assertTrue(RigBundle.holding());
        boolean[] other = new boolean[1];
        Thread t = new Thread(() -> other[0] = RigBundle.holding());
        t.start();
        t.join();
        assertFalse(other[0], "another thread's broadcasts are never held");
        RigBundle.hold(List.of("alex"), 7);
        RigBundle.hold(List.of("alex"), 8);
        assertTrue(sent.isEmpty(), "held until the pass ends");
        RigBundle.end((v, ps) -> sent.add(new Sent((String) v, List.copyOf((List<Integer>) (List<?>) ps))));
        assertFalse(RigBundle.holding());
        assertEquals(List.of(new Sent("alex", List.of(7, 8))), sent);
    }
}
