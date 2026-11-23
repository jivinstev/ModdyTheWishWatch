package com.moddingfromamod.item;

import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>An item id a make is about to write, held for the child who asked first</b>.
 *
 * <p>A FRESH make (no {@code start_from}) writes under the id its name and power mint ({@code turbo_pickaxe_status}),
 * unless that id is already another child's, when it writes the asker's own fork ({@code MakeItemTool.freshClash}).
 * The check read only the item STORE, and a make stores its design seconds after the check — so two children whose
 * first makes minted the same id in those seconds both found it free, both wrote {@code turbo_pickaxe_status}, and
 * the datapack the second one loaded handed BOTH of them its design: measured in Gate B's Bedrock batch on
 * 2026-11-20, an ocean-blue pickaxe made by one bot arrived golden, the colour another bot chose a moment later.
 *
 * <p>So the check also asks this table: the first child to find an id free CLAIMS it, and until it is stored (or the
 * claim lapses) a second child finds it held by the first, and forks, exactly as he would had the first make already
 * been stored. Pure and clocked by the caller, so the race is tested without a server.
 */
public final class ItemClaims {
    /** Longer than any make takes from its check to its store; a lapsed claim frees the id for a make that died. */
    public static final long CLAIM_MS = 120_000;

    private record Claim(String owner, long at) {}

    private final ConcurrentHashMap<String, Claim> claims = new ConcurrentHashMap<>();

    private static final ItemClaims SHARED = new ItemClaims();

    public static ItemClaims shared() {
        return SHARED;
    }

    /**
     * {@code asker} wants to write the free id {@code id} at {@code now}: returns who holds it after this call — the
     * asker (it is his now, or was already) or the child who claimed it first and has not stored it yet. An empty asker
     * (nobody bound to the make) claims nothing and is told the id is his.
     */
    public String claim(String id, String asker, long now) {
        if (asker == null || asker.isEmpty()) {
            return asker == null ? "" : asker;
        }
        Claim mine = new Claim(asker, now);
        Claim held = claims.compute(id, (k, v) ->
                v == null || now - v.at() >= CLAIM_MS || v.owner().equals(asker) ? mine : v);
        return held.owner();
    }
}
