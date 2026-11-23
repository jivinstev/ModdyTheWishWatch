package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** an old catalogue row for Moddy's watch is recognised (and dropped on load); nothing else is. */
class WatchKeyTest {

    @Test
    void theWatchRowAsLiveServersSavedIt() {
        // the exported cell's id on a live server, 2026-10-12
        assertTrue(WishItemCatalogue.isWatchKey("moddy#1000#{WishWatch:1b}"));
    }

    @Test
    void aWishedThingIsNotTheWatch() {
        assertFalse(WishItemCatalogue.isWatchKey("id:cake_fork_heal"));
        assertFalse(WishItemCatalogue.isWatchKey("trophy#1000#{}"));
        assertFalse(WishItemCatalogue.isWatchKey("moddy#1000#{WishWatch:0b}"));
        assertFalse(WishItemCatalogue.isWatchKey(null));
    }
}
