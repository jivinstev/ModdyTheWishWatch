package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate C½ must watch the wish it submitted —.</b>
 *
 * <p>The probe is the ~165-second rung between the routing arm and full Gate D, and the tier the
 * item rollout makes <b>mandatory</b> for any trigger step, precisely because the failure it exists
 * to catch is <i>"the agent granted something plausible and every gate stayed green"</i>. It was
 * doing that itself.
 *
 * <p>It took the FIRST probe-owned wish out of the book. The probe player's id is the same every
 * run and the book returns insertion order — oldest first — so from the second run onward it
 * watched a PREVIOUS run's wish, found it already settled, and printed that run's verdict as this
 * one's. Measured: three consecutive runs returned a wish about a club as PASS carrying a sword's
 * closing message, byte-identical, in 20 s, with zero authoring calls in the log.
 *
 * <p>It is correct exactly once — the first run of a fresh world, where there is only one candidate
 * — which is why nobody caught it, and why the single genuine measurement ever taken with it was
 * the first one anybody made.
 */
class HeadlessWishProbeTest {

    private static final UUID PROBE = UUID.nameUUIDFromBytes("probe".getBytes());
    private static final UUID SOMEBODY_ELSE = UUID.nameUUIDFromBytes("a real child".getBytes());

    private static Wish wish(String text, UUID owner) {
        return Wish.create(text, WishSource.COMMAND, 0L, owner, "probe",
                "minecraft:overworld", 0, 64, 0);
    }

    private static Set<UUID> idsOf(List<Wish> ws) {
        return ws.stream().map(Wish::id).collect(Collectors.toCollection(HashSet::new));
    }

    @Test
    @DisplayName("the SECOND run watches its own wish, not the first run's")
    void aSecondRunDoesNotInheritTheFirstRunsVerdict() {
        // The exact shape. Both wishes belong to the probe; only one of them is this run's.
        Wish lastTime = wish("a sword that does something when it hits", PROBE);
        List<Wish> before = List.of(lastTime);
        Wish thisTime = wish("a club that gives me a diamond when i bonk something", PROBE);

        UUID picked = HeadlessWishProbe.ourNewWish(
                List.of(lastTime, thisTime), idsOf(before), PROBE);

        assertEquals(thisTime.id(), picked,
                "picked a previous run's wish — which is already settled, so the probe reports that "
                        + "run's answer in 20s having measured nothing");
    }

    @Test
    @DisplayName("the FIRST run still works — the one case the old rule got right")
    void theFirstRunOfAFreshWorldStillWorks() {
        Wish only = wish("a lamp that follows me around", PROBE);
        assertEquals(only.id(),
                HeadlessWishProbe.ourNewWish(List.of(only), Set.of(), PROBE));
    }

    @Test
    @DisplayName("a wish somebody else made is never mistaken for the probe's")
    void anotherPlayersWishIsNotOurs() {
        // The probe shares a world with whatever else is in it. A real child's wish arriving
        // between submit and read must not become the thing the probe reports on.
        Wish theirs = wish("i want a dragon", SOMEBODY_ELSE);
        Wish ours = wish("a lamp that follows me around", PROBE);
        assertEquals(ours.id(),
                HeadlessWishProbe.ourNewWish(List.of(theirs, ours), Set.of(), PROBE));
    }

    @Test
    @DisplayName("nothing new means null, which is a FAILED submit rather than a slow one")
    void nothingNewIsNull() {
        // Distinguishing "not dispatched" from "still running" is the whole reason the id is
        // resolved at submit time. Returning an old id here is what produced the false PASS.
        Wish lastTime = wish("a sword that does something when it hits", PROBE);
        assertNull(HeadlessWishProbe.ourNewWish(
                List.of(lastTime), idsOf(List.of(lastTime)), PROBE));
    }

    @Test
    @DisplayName("two new probe wishes in one submit: take the newest, never the oldest")
    void twoNewOnesTakeTheNewest() {
        // Should not happen, and the direction still matters: oldest-first IS the bug, so if this
        // ever occurs the rule must fail toward the fresh one.
        Wish a = wish("first", PROBE);
        Wish b = wish("second", PROBE);
        assertEquals(b.id(), HeadlessWishProbe.ourNewWish(List.of(a, b), Set.of(), PROBE));
    }
}
