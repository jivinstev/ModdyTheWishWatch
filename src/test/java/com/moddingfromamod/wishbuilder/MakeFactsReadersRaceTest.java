package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * #92: a fact worked out while the game's readers were being installed is never remembered. A page walk on its own
 * thread reads a sword before the item's attack can be read; the readers are installed while it is still reading; the
 * sword asked for afterwards carries the game's attack damage, not the half-ready reading. Deterministic: two latches
 * hold the walk inside the old reader until the new one is installed.
 */
class MakeFactsReadersRaceTest {

    private static BuilderSpec strongestSword() {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("stock", "sword");
        o.put("shape", "shape:on_hit");
        o.put("effect", "effect:lightning");
        o.put("power", "lightning");
        o.put("strength", "3");
        o.put("tag", "readers-race");   // a spec no other test remembers
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.ITEM, "", "", 2, List.of(), "", 1, "", "", o);
    }

    @Test
    void aFactReadBeforeTheReadersWereInstalledIsNotRemembered() throws Exception {
        BuilderSpec sword = strongestSword();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // the game not readable yet: the item's attack cannot be read (and the walk is held while it reads)
        MakeFacts.itemAttack(id -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OptionalDouble.empty();
        });
        try {
            Thread walk = new Thread(() -> MakeFacts.of(sword), "a page walk");
            walk.start();
            assertTrue(inside.await(10, TimeUnit.SECONDS), "the walk reached the item's attack");
            long before = MakeFacts.generation();
            // the server stands: the game's own readers are installed while the walk is still reading
            MakeFacts.itemAttack(id -> id.equals("minecraft:diamond_sword") ? OptionalDouble.of(9) : OptionalDouble.empty());
            assertTrue(MakeFacts.generation() > before, "installing a reader moves the generation on");
            release.countDown();
            walk.join(10_000);
            assertEquals(9.0, MakeFacts.of(sword).number(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS).orElse(-1), 1e-9,
                    "the sword carries the game's attack damage, not the reading from before the game could be read");
        } finally {
            release.countDown();
            MakeFacts.itemAttack(null);
        }
    }
}
