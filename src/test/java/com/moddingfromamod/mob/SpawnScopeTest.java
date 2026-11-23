package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.item.delivery.DeliveryPlan;
import com.moddingfromamod.mob.SpawnBudget.Kind;
import com.moddingfromamod.mob.SpawnBudget.Stop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the "a wish made this" mark the world guard reads, and the per-wish tally it keeps. */
class SpawnScopeTest {

    @BeforeEach
    void clean() {
        SpawnScope.forgetAll();
    }

    @Test
    @DisplayName("nothing open means nobody's wish; a scope is closed again however the body ends")
    void opensAndCloses() {
        assertNull(SpawnScope.current());
        SpawnScope.during("w1", DeliveryPlan.Kind.HANDOVER, () -> {
            assertEquals("w1", SpawnScope.current().wishKey());
            return null;
        });
        assertNull(SpawnScope.current());
        try {
            SpawnScope.during("w1", DeliveryPlan.Kind.HANDOVER, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // fall through
        }
        assertNull(SpawnScope.current(), "a throwing body must not leave the scope open");
    }

    @Test
    @DisplayName("an open scope is KEPT: a command inside a wish's batch stays that wish's")
    void nests() {
        SpawnScope.during("outer", DeliveryPlan.Kind.HANDOVER, () -> {
            SpawnScope outer = SpawnScope.current();
            boolean opened = SpawnScope.open("inner", DeliveryPlan.Kind.HANDOVER);
            assertFalse(opened);
            assertSame(outer, SpawnScope.current());
            SpawnScope.close(opened);
            assertSame(outer, SpawnScope.current(), "closing what it did not open must not close the outer");
            SpawnScope.during("inner2", DeliveryPlan.Kind.HANDOVER, () -> {
                assertEquals("outer", SpawnScope.current().wishKey());
                return null;
            });
            return null;
        });
    }

    @Test
    @DisplayName("a wish's tally spans every scope it opens — four run_command calls meet ONE wave")
    void tallySpansScopes() {
        for (int call = 0; call < 4; call++) {
            SpawnScope.during("w", DeliveryPlan.Kind.HANDOVER, () -> {
                for (int i = 0; i < 20; i++) {
                    SpawnScope.current().admitted(Kind.EXPLOSIVE, "minecraft:creeper");
                }
                return null;
            });
        }
        assertEquals(80, SpawnScope.madeBy("w", Kind.EXPLOSIVE));
        assertEquals(0, SpawnScope.madeBy("w", Kind.VANILLA));
        assertEquals(0, SpawnScope.madeBy("other", Kind.EXPLOSIVE), "another wish starts fresh: that is 'more'");
        SpawnScope.during(SpawnScope.NO_WISH, DeliveryPlan.Kind.HANDOVER, () -> {
            assertEquals(-1, SpawnScope.current().madeByThisWish(Kind.EXPLOSIVE),
                    "a datapack has no wave, only the world ceiling");
            return null;
        });
    }

    @Test
    @DisplayName("what was turned away comes back as ONE true sentence per creature, with how many came")
    void lines() {
        List<String> said = SpawnScope.during("w", DeliveryPlan.Kind.HANDOVER, () -> {
            SpawnScope s = SpawnScope.current();
            for (int i = 0; i < 50; i++) {
                s.admitted(Kind.EXPLOSIVE, "minecraft:creeper");
            }
            for (int i = 0; i < 30; i++) {
                s.dropped(Kind.EXPLOSIVE, "minecraft:creeper", Stop.WAVE);
            }
            s.dropped(Kind.FLYING, "minecraft:phantom", Stop.WORLD);
            assertTrue(s.droppedAny());
            return s.lines();
        });
        assertEquals(2, said.size(), String.valueOf(said));
        assertEquals(SpawnBudget.waveLine(Kind.EXPLOSIVE, "minecraft:creeper", 50), said.get(0));
        assertEquals(SpawnBudget.worldLine(Kind.FLYING, "minecraft:phantom"), said.get(1),
                "none came and the world was the reason: say the world line, not 'here come the first 0'");
    }

    @Test
    @DisplayName("an old wish's tally is forgotten, not kept forever")
    void bounded() {
        for (int i = 0; i < SpawnScope.REMEMBERED + 10; i++) {
            String key = "w" + i;
            SpawnScope.during(key, DeliveryPlan.Kind.HANDOVER, () -> {
                SpawnScope.current().admitted(Kind.VANILLA, "minecraft:cow");
                return null;
            });
        }
        assertEquals(0, SpawnScope.madeBy("w0", Kind.VANILLA));
        assertEquals(1, SpawnScope.madeBy("w" + (SpawnScope.REMEMBERED + 9), Kind.VANILLA));
    }

    @Test
    @DisplayName("#1351: the scope carries WHY a give happens, and an inner scope inherits it")
    void carriesTheKind() {
        SpawnScope.during("w", DeliveryPlan.Kind.HANDOVER, () -> {
            // A made item's `wishdatapack <id> give` passes through the function manager, which
            // asks for a BEHAVIOUR scope: inside a wish it must stay that wish's hand-over.
            boolean opened = SpawnScope.open(SpawnScope.NO_WISH, DeliveryPlan.Kind.BEHAVIOUR);
            assertFalse(opened);
            assertEquals(DeliveryPlan.Kind.HANDOVER, SpawnScope.current().kind());
            assertEquals("w", SpawnScope.current().wishKey());
            SpawnScope.close(opened);
            return null;
        });
        SpawnScope.during(SpawnScope.NO_WISH, DeliveryPlan.Kind.BEHAVIOUR, () -> {
            assertEquals(DeliveryPlan.Kind.BEHAVIOUR, SpawnScope.current().kind());
            return null;
        });
    }

    @Test
    @DisplayName("#1351: a world rule's scope is its OWN, even inside a wish, and the wish's comes back after")
    void aRuleIsAlwaysItsOwnScope() {
        SpawnScope.during("w", DeliveryPlan.Kind.HANDOVER, () -> {
            SpawnScope outer = SpawnScope.current();
            SpawnScope.own(SpawnScope.NO_WISH, DeliveryPlan.Kind.BEHAVIOUR, () -> {
                assertEquals(DeliveryPlan.Kind.BEHAVIOUR, SpawnScope.current().kind());
                assertEquals(SpawnScope.NO_WISH, SpawnScope.current().wishKey());
                return null;
            });
            assertSame(outer, SpawnScope.current(), "the wish's scope must be put back");
            return null;
        });
        SpawnScope.own(SpawnScope.NO_WISH, DeliveryPlan.Kind.BEHAVIOUR, () -> null);
        assertNull(SpawnScope.current(), "an own scope with nothing outside it closes to nothing");
    }

    @Test
    @DisplayName("a wished creature that is GONE gives its wave slot back: the boss that dug away does not block the retry")
    void aVanishedCreatureFreesItsSlot() {
        SpawnScope.during("w", DeliveryPlan.Kind.HANDOVER, () -> {
            SpawnScope.current().admitted(Kind.BOSS, "minecraft:warden");
            return null;
        });
        assertEquals(Stop.WAVE, SpawnBudget.admit(Kind.BOSS, SpawnScope.madeBy("w", Kind.BOSS), 0),
                "while it stands, one boss a wish");
        SpawnScope.released("w", Kind.BOSS);
        assertEquals(0, SpawnScope.madeBy("w", Kind.BOSS));
        assertEquals(Stop.NONE, SpawnBudget.admit(Kind.BOSS, SpawnScope.madeBy("w", Kind.BOSS), 0),
                "once it is gone, the retry is admitted");
        SpawnScope.released("w", Kind.BOSS);
        assertEquals(0, SpawnScope.madeBy("w", Kind.BOSS), "never below zero");
        SpawnScope.released("never-seen", Kind.BOSS);
        SpawnScope.released(SpawnScope.NO_WISH, Kind.BOSS);
        SpawnScope.released(null, Kind.BOSS);
        assertEquals(0, SpawnScope.madeBy("never-seen", Kind.BOSS));
    }
}
