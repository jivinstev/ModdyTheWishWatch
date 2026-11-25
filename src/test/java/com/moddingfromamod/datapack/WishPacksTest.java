package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.ModdingFromAModMod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** #80: only a namespace a wish wrote is a wish pack's. */
class WishPacksTest {

    @AfterEach
    void forget() {
        WishPacks.forceForTest(null, false);
    }

    @Test
    void vanillaThisModAndAPackNoWishWroteAreNot() {
        assertFalse(WishPacks.isWishNamespace("minecraft"));
        assertFalse(WishPacks.isWishNamespace(ModdingFromAModMod.MODID));
        assertFalse(WishPacks.isWishNamespace("some_worlds_own_pack_" + System.nanoTime()));
        assertFalse(WishPacks.isWishNamespace(""));
        assertFalse(WishPacks.isWishNamespace(null));
    }

    @Test
    void aPackAWishWroteIs() {
        WishPacks.forceForTest("gt_wish_pack", true);
        assertTrue(WishPacks.isWishNamespace("gt_wish_pack"));
        WishPacks.forceForTest("minecraft", true);
        assertFalse(WishPacks.isWishNamespace("minecraft"), "vanilla is never a wish's, forced or not");
    }
}
