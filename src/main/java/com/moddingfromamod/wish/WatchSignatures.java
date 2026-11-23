package com.moddingfromamod.wish;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Has this child's watch changed since we last sent it?</b> Asked per child.
 *
 * <p>{@code WatchSnapshot.onServerTick} kept ONE signature for every player and {@code return}ed on
 * the first unchanged one: with two children on, the first child's unchanged watch stopped the
 * check for everyone after him, and each child's watch read as "changed" whenever the other's
 * differed, so both were resent every check. Pure, so that is unit-tested.
 */
final class WatchSignatures {

    private final Map<UUID, String> last = new HashMap<>();

    /** True, and remembered, when this child's signature differs from what he was last sent. */
    synchronized boolean changed(UUID player, String signature) {
        String s = signature == null ? "" : signature;
        return !s.equals(last.put(player, s));
    }

    /** He (re)joined or left: the next look is a change. */
    synchronized void forget(UUID player) {
        last.remove(player);
    }

    synchronized void clear() {
        last.clear();
    }
}
