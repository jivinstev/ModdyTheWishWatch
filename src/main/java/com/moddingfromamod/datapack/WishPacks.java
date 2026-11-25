package com.moddingfromamod.datapack;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;

/**
 * <b>Is this datapack namespace a wish's?</b> (#80) A wish datapack is written into its own namespace,
 * {@code data/<its id>/…}, and kept in {@link DatapackStore} (this world's compiled packs and the curated library's):
 * that store is what makes a namespace a wish's. {@code minecraft}, this mod's own namespace and every pack a world or
 * another mod ships are not. Asked once per datapack function run, so each answer is kept for a second.
 */
public final class WishPacks {

    private static final long KEEP_NANOS = 1_000_000_000L;
    private static final Map<String, Boolean> IS_WISH = new ConcurrentHashMap<>();
    private static volatile long checkedAt = System.nanoTime();
    private static final Set<String> FORCED = ConcurrentHashMap.newKeySet();

    private WishPacks() {
    }

    /** Was {@code namespace} written by a wish? */
    public static boolean isWishNamespace(String namespace) {
        if (namespace == null || namespace.isEmpty() || "minecraft".equals(namespace)
                || ModdingFromAModMod.MODID.equals(namespace)) {
            return false;
        }
        if (FORCED.contains(namespace)) {
            return true;
        }
        long now = System.nanoTime();
        if (now - checkedAt >= KEEP_NANOS) {
            IS_WISH.clear();
            checkedAt = now;
        }
        return IS_WISH.computeIfAbsent(namespace, DatapackStore::exists);
    }

    /** Tests: treat {@code namespace} as a wish pack's whether or not the store holds it ({@code null}: forget all). */
    public static void forceForTest(String namespace, boolean wish) {
        if (namespace == null) {
            FORCED.clear();
        } else if (wish) {
            FORCED.add(namespace);
        } else {
            FORCED.remove(namespace);
        }
    }
}
