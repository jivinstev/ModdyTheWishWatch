package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * <b>{@code /moddy credits} — who made what a child is standing in.</b> Pure, so what the
 * command says is pinned by {@code CreditsTest} without a server.
 *
 * <p>A world module can be made with third-party datapacks (one uses BluePsychoRanger's SkyBlock Collection,
 * MIT, used unmodified ({@code worlds/THIRD_PARTY_NOTICES.txt}, pinned in
 * {@code tools/datapacks.lock.json}). The credit is shown only in a world that actually runs one of
 * them, read off the server's selected packs: a line in a world crediting packs it does not
 * have would be a line nobody can check.
 */
public final class Credits {

    /** The id prefix every SkyBlock Collection pack has once the server loads it from a world. */
    static final String SKYBLOCK_PACK_PREFIX = "file/skyvoid_";

    static final String MODDY_LINE = "✨ Moddy the Wish Watch — made for you by the Moddy team.";
    static final String SKYBLOCK_LINE = "✨ The sky island, the void and the SkyBlock advancements are "
            + "BluePsychoRanger's SkyBlock Collection (Standard SkyBlock, Sky Void Additions, SkyBlock "
            + "Advancements) — MIT licence, github.com/BPR02/SkyBlock_Collection. Thank you!";

    private Credits() {
    }

    /** The lines, in order, for a server whose selected data packs are {@code selectedPackIds}. */
    public static List<String> lines(Collection<String> selectedPackIds) {
        List<String> out = new ArrayList<>();
        out.add(MODDY_LINE);
        if (selectedPackIds != null && selectedPackIds.stream().anyMatch(
                id -> id != null && id.startsWith(SKYBLOCK_PACK_PREFIX))) {
            out.add(SKYBLOCK_LINE);
        }
        return out;
    }
}
