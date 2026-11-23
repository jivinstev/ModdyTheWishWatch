package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** the decision a waiting press makes each tick — run it, give up on it, or keep waiting. */
class PendingPressesTest {

    private static final UUID HIM = UUID.randomUUID();

    @Test
    void aPressRunsOnceItsPackHasLoaded() {
        PendingPresses q = new PendingPresses();
        q.add(HIM, "tiny_cookie_grow", "give", 0);
        assertTrue(q.take(id -> false, 1_000).ready().isEmpty(), "ran before the pack loaded");
        PendingPresses.Due due = q.take(Set.of("tiny_cookie_grow")::contains, 2_000);
        assertEquals(1, due.ready().size());
        assertTrue(q.isEmpty(), "a press that ran must not run again");
    }

    @Test
    void aPressThatNeverLoadsIsToldNotKeptForever() {
        PendingPresses q = new PendingPresses();
        q.add(HIM, "slow", "give", 0);
        assertTrue(q.take(id -> false, PendingPresses.DEADLINE_MS - 1).expired().isEmpty());
        assertEquals(1, q.take(id -> false, PendingPresses.DEADLINE_MS).expired().size());
        assertTrue(q.isEmpty());
    }

    @Test
    void loadedWinsOverLate() {
        PendingPresses q = new PendingPresses();
        q.add(HIM, "p", "give", 0);
        PendingPresses.Due due = q.take(id -> true, PendingPresses.DEADLINE_MS * 2);
        assertEquals(1, due.ready().size(), "a pack that arrived late still gives");
        assertTrue(due.expired().isEmpty());
    }

    @Test
    void pressingAgainWhileItLoadsIsStillOnePress() {
        PendingPresses q = new PendingPresses();
        q.add(HIM, "p", "give", 0);
        q.add(HIM, "p", "give", 500);
        assertEquals(1, q.take(id -> true, 600).ready().size(), "one cookie asked for, one given");
    }

    @Test
    void aPressRemembersWhetherItWasAHandOverOrAThingsOwnBehaviour() {
        PendingPresses q = new PendingPresses();
        q.add(HIM, "button_pack", "give", 0, "w1");
        q.add(HIM, "rule_pack", "give", 0, "", com.moddingfromamod.item.delivery.DeliveryPlan.Kind.BEHAVIOUR);
        var due = q.take(id -> true, 1);
        assertEquals(com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, due.ready().get(0).kind(),
                "a press made inside a wish or by a button is a hand-over");
        assertEquals(com.moddingfromamod.item.delivery.DeliveryPlan.Kind.BEHAVIOUR, due.ready().get(1).kind(),
                "a press a rule made keeps its behaviour when the pack loads (#1351)");
    }
}
