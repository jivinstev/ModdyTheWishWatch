package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A handle must name the body that is really there —.</b>
 *
 * <p>A wished creature is manifested twice: it joins the world as a {@code wish_mob} and the rig
 * bridge replaces it with a display rig on a vanilla carrier, then again on every re-wish, where
 * the rebuild discards the carrier and spawns a new one. Nothing followed either hop, and the
 * agent's own tools were among the losers — {@code DefineSpeciesTool} hands out a handle with the
 * words <i>"use it as modify_mob's target"</i> and the next edit was answered <i>"… is gone"</i>.
 *
 * <p>⚠ <b>There are two halves and only one of them fires in practice.</b> Which one you need
 * depends on whether the handle existed when the swap happened, and MEASURED over a whole Gate B
 * tier, 41 conversions moved <b>zero</b> handles: {@code MinecraftServer.execute} drains before the
 * caller's next line, so every caller mints on the far side of the swap. Shipping only
 * {@code repoint} — the tidier, more obvious half — would have shipped the one that never runs.
 * These tests pin both, and the rename rule that makes them safe to have.
 */
class HandleFollowsTheRigTest {

    private static EntityHandles fresh() {
        return new EntityHandles();
    }

    @Test
    @DisplayName("repoint moves the handle onto the replacement and KEEPS ITS NAME — the child "
            + "said 'make the robot bigger' about a name the agent already holds")
    void repointKeepsTheName() {
        EntityHandles h = fresh();
        UUID was = UUID.randomUUID();
        UUID now = UUID.randomUUID();
        String name = h.remember(was, "moddingfromamod:wish_mob", "Robot");

        assertEquals(1, h.repoint(was, now, "minecraft:horse"));

        var found = h.resolve(name);
        assertTrue(found.isPresent(), "the handle must still be known by the same name");
        assertEquals(now, found.get().id(), "and must name the replacement");
        assertEquals("minecraft:horse", found.get().typeId(), "with the carrier's type");
        assertEquals("Robot", found.get().label(), "and the child's own word for it");
    }

    @Test
    @DisplayName("repointing something with no handle is 0, not a new handle — most entities that "
            + "go through the bridge were never named")
    void repointingAnUnknownEntityMintsNothing() {
        EntityHandles h = fresh();
        assertEquals(0, h.repoint(UUID.randomUUID(), UUID.randomUUID(), "minecraft:horse"));
    }

    @Test
    @DisplayName("repoint refuses the degenerate cases rather than corrupting the table")
    void repointRefusesNonsense() {
        EntityHandles h = fresh();
        UUID id = UUID.randomUUID();
        h.remember(id, "moddingfromamod:wish_mob", "Bot");
        assertEquals(0, h.repoint(id, id, "x"), "moving something onto itself is not a move");
        assertEquals(0, h.repoint(null, id, "x"));
        assertEquals(0, h.repoint(id, null, "x"));
        assertEquals(1, h.recent(8).size(), "and none of that added or lost a handle");
    }

    @Test
    @DisplayName("a second hop follows too — a re-wish rebuilds the rig, discarding the carrier "
            + "the first edit was addressed to, which is what made 'no, BIGGER' fail")
    void handlesFollowASecondHop() {
        EntityHandles h = fresh();
        UUID mob = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String name = h.remember(mob, "moddingfromamod:wish_mob", "Bot");
        h.repoint(mob, first, "minecraft:horse");
        h.repoint(first, second, "minecraft:horse");

        assertEquals(second, h.resolve(name).orElseThrow().id());
        assertNotEquals(mob, h.resolve(name).orElseThrow().id());
    }

    @Test
    @DisplayName("the chain is followed, so a handle minted AFTER the swap still names the body — "
            + "this is the half that actually fires")
    void theRegisterFollowsAChain() {
        UUID mob = UUID.randomUUID();
        UUID carrier = UUID.randomUUID();
        UUID rebuilt = UUID.randomUUID();
        try {
            EntityReplacements.clearForTest();
            EntityReplacements.record(mob, carrier);
            EntityReplacements.record(carrier, rebuilt);

            assertEquals(rebuilt, EntityReplacements.settled(mob));
            assertEquals(rebuilt, EntityReplacements.settled(carrier));
        } finally {
            EntityReplacements.clearForTest();
        }
    }

    @Test
    @DisplayName("an entity nothing replaced settles to itself, so no caller has to ask whether it "
            + "is holding a wished creature")
    void anythingElseSettlesToItself() {
        EntityReplacements.clearForTest();
        UUID plain = UUID.randomUUID();
        assertSame(plain, EntityReplacements.settled(plain));
    }
}
