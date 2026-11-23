package com.moddingfromamod.wish;

import java.util.Locale;

/**
 * <b>One thing, three spellings.</b> The owned list names an item {@code id:black_hole_block_pull}
 * and a changed mob {@code @bee}; an agent naming either in {@code plan_wish}'s {@code about} writes
 * the bare word. Pure, so the rule is unit-tested rather than discovered in a grant.
 */
public final class AboutIds {

    private AboutIds() {
    }

    /** {@code id} without its catalogue prefix, lower-cased. */
    public static String key(String id) {
        if (id == null) {
            return "";
        }
        String k = id.strip().toLowerCase(Locale.ROOT);
        if (k.startsWith("id:")) {
            k = k.substring(3);
        }
        while (k.startsWith("@")) {
            k = k.substring(1);
        }
        return k;
    }

    /** True when two spellings name the same catalogue entry. */
    public static boolean same(String catalogueId, String said) {
        String a = key(catalogueId);
        return !a.isEmpty() && a.equals(key(said));
    }
}
