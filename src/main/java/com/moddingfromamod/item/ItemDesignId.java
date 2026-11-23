package com.moddingfromamod.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Which design a catalogue key could name</b>. Pure.
 *
 * <p>{@link WishItemCatalogue#keyOf} keys an item on its feature id when it carries one
 * ({@code id:cake_fork_heal} — the design's own id), and otherwise on its BEHAVIOUR tag, which is a
 * function id: {@code <feature>:act} for anything {@code make_item} wrote, or a datapack's own
 * {@code player_tracker:track}. A function id is never a design key, so the card looked it up whole,
 * found nothing, and printed "—" four times. The design, when there is one, is the namespace.
 */
public final class ItemDesignId {

    private ItemDesignId() {
    }

    /** The design ids to try for a catalogue key, most specific first; empty for a keepsake key. */
    public static List<String> candidates(String catalogueKey) {
        List<String> out = new ArrayList<>();
        if (catalogueKey == null || !catalogueKey.startsWith("id:")) {
            return out;
        }
        String id = catalogueKey.substring(3).trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return out;
        }
        out.add(id);
        int colon = id.indexOf(':');
        if (colon > 0) {
            out.add(id.substring(0, colon));
        }
        return out;
    }

    /** The design behind a catalogue key, or null when nothing here made it. */
    public static ItemSpecStore.Spec designFor(String catalogueKey) {
        for (String id : candidates(catalogueKey)) {
            ItemSpecStore.Spec spec = ItemSpecStore.get(id);
            if (spec != null) {
                return spec;
            }
        }
        return null;
    }

    /** The id of the design behind a catalogue key, or the key's own id when there is none. */
    public static String designIdFor(String catalogueKey) {
        ItemSpecStore.Spec spec = designFor(catalogueKey);
        if (spec != null) {
            return spec.id();
        }
        List<String> c = candidates(catalogueKey);
        return c.isEmpty() ? catalogueKey : c.get(0);
    }
}
