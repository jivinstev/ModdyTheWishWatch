package com.moddingfromamod.library;

import com.google.gson.JsonObject;

/**
 * <b>A library entry may say what this Minecraft must be able to do for it to work</b>.
 *
 * <p>{@code "needs": "wearables"} on a {@code templates.json} entry means its item goes on the body,
 * which the 1.21.1 compat build cannot do ({@code compat.Wearables}). There the entry is LEFT OFF
 * the row and the reason is logged — never shown as a button that fails when pressed, and never
 * quietly turned into something he has to right-click instead.
 */
public final class StarterNeeds {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("mfam-library");

    private StarterNeeds() {
    }

    /** The one capability an entry may name today. */
    public static final String WEARABLES = "wearables";

    /** Why this entry cannot be served on this build, or null when it can. */
    public static String unmet(JsonObject entry) {
        if (entry == null || !entry.has("needs") || !entry.get("needs").isJsonPrimitive()) {
            return null;
        }
        String needs = entry.get("needs").getAsString().trim();
        if (WEARABLES.equals(needs)) {
            return com.moddingfromamod.compat.Wearables.SUPPORTED ? null
                    : "it is something he WEARS, and this Minecraft cannot put a wished thing on "
                            + "the body (worn items need 1.21.4)";
        }
        return "it needs '" + needs + "', which this build does not know";
    }

    /**
     * The item/datapack ids an entry hands out ({@code wishdatapack <id> …}). When the entry is
     * left off the row, what only IT uses is withheld too: a datapack whose give line this
     * Minecraft cannot parse would otherwise still be installed into every world and fail to load
     * there, which is the quiet half of the same bug (StarterDatapackDialectGameTest found it).
     */
    public static java.util.Set<String> packsNamed(JsonObject entry) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (entry == null || !entry.has("commands") || !entry.get("commands").isJsonArray()) {
            return out;
        }
        for (var c : entry.getAsJsonArray("commands")) {
            if (!c.isJsonPrimitive()) {
                continue;
            }
            String[] t = c.getAsString().trim().split("\\s+");
            if (t.length >= 2 && t[0].equals("wishdatapack")) {
                out.add(t[1]);
            }
        }
        return out;
    }

    /** {@link #unmet}, logged when it leaves an entry off. */
    public static boolean servable(String id, JsonObject entry) {
        String why = unmet(entry);
        if (why != null) {
            LOGGER.info("starter '{}' is left off the row: {}", id, why);
            return false;
        }
        return true;
    }
}
