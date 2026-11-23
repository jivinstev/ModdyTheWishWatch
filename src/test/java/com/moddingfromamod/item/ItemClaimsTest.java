package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** two children's first makes of one id in the same seconds — the second finds it the first's. */
class ItemClaimsTest {
    @Test
    void theFirstChildHoldsAFreeIdAndTheSecondFindsItHis() {
        ItemClaims c = new ItemClaims();
        assertEquals("ann", c.claim("turbo_pickaxe_status", "ann", 0));
        assertEquals("ann", c.claim("turbo_pickaxe_status", "bob", 1_000), "bob forks: the id is ann's");
        assertEquals("ann", c.claim("turbo_pickaxe_status", "ann", 2_000), "ann's own make again keeps it");
        assertEquals("bob", c.claim("another_id", "bob", 2_000));
    }

    @Test
    void aClaimLapsesSoAMakeThatDiedFreesTheId() {
        ItemClaims c = new ItemClaims();
        c.claim("x", "ann", 0);
        assertEquals("ann", c.claim("x", "bob", ItemClaims.CLAIM_MS - 1));
        assertEquals("bob", c.claim("x", "bob", ItemClaims.CLAIM_MS));
    }

    @Test
    void nobodyBoundClaimsNothing() {
        ItemClaims c = new ItemClaims();
        assertEquals("", c.claim("x", "", 0));
        assertEquals("ann", c.claim("x", "ann", 0), "an unbound call left no claim behind");
    }

    @Test
    void manyChildrenAtOnceAgreeOnOneOwner() throws Exception {
        ItemClaims c = new ItemClaims();
        Set<String> owners = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) {
            String who = "child" + i;
            pool.submit(() -> {
                go.await();
                owners.add(c.claim("turbo_pickaxe_status", who, 5));
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        assertEquals(1, owners.size(), "every child saw the same owner: " + owners);
    }
}
