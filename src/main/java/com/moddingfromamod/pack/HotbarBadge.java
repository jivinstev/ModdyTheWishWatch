package com.moddingfromamod.pack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>The Moddy-watch badge a wish wears on the hotbar, drawn with no pack reload.</b>
 *
 * <p>A hotbar proxy must read at a glance as "a WISH for this thing", not the thing. The badge is a small
 * Moddy watch drawn with the wish's own picture, and it rides the item pack every client already has at join:
 * nothing is pushed per wish. Two routes, one per kind of picture:
 * <ul>
 *   <li><b>An icon-library sprite</b> (a {@code wishpack:icon/<name>} definition, which a wished item usually
 *   is too): every composed icon definition carries a {@code condition} on {@code custom_model_data}'s
 *   {@code flags[FLAG_INDEX]}, which draws the badge layers when true. The history's pictures never set the
 *   flag, so they are unchanged; the proxy sets it.</li>
 *   <li><b>A vanilla item</b> (a cake, a block): one definition, {@code wishpack:hotbar}, a {@code select} on
 *   {@code custom_model_data}'s {@code strings[0]} whose every entry is written BY RULE ({@link #ruleModel}), with the
 *   badge around it. Nothing of Mojang's is copied: {@code tools/gen-hotbar-badge.py} reads the client jar only to
 *   find where the rule is wrong, and commits IDS (the block items drawn flat, and the {@code exceptions} the rule
 *   cannot draw: a special, tinted or conditional definition such as a chest or a potion). An exception's proxy keeps
 *   its own picture and the glint. The proxy names that definition and puts the vanilla id in the string.</li>
 * </ul>
 * The jar ships the definition and the pushed pack carries the same bytes ({@link #definition}), so a modded and a
 * stock client draw the same thing; {@code HotbarBadgeTest} checks the rule against the client jar (Gate A, where
 * the jar is cached) and {@code WishHotbarGameTest} against the item registry (Gate B).
 */
public final class HotbarBadge {

    /** The look that ships (docs/evidence-2755/overlay): which badge layers it draws. Mirrors the tool's LOOKS. */
    public enum Look {
        /** A small watch in the top-left corner, over the picture. Shipped. */
        CORNER(false, true),
        /** The watch's face behind the picture and its case in front: the picture inside the watch. */
        INSIDE(true, true),
        /** The whole watch behind the picture. */
        BEHIND(true, false);

        public final boolean back;
        public final boolean front;

        Look(boolean back, boolean front) {
            this.back = back;
            this.front = front;
        }
    }

    /** Which entry of {@code custom_model_data}'s {@code flags} turns the badge on for an icon. */
    public static final int FLAG_INDEX = 0;

    /** Which entry of {@code strings} names the vanilla picture in {@link #ITEM_MODEL}'s select. */
    public static final int VANILLA_INDEX = 0;

    /** The definition that draws a vanilla picture under the badge. */
    public static final String ITEM_MODEL = VanillaItemPack.NAMESPACE + ":hotbar";

    /** Where its definition is filed, in the pack and in the jar. */
    public static final String DEFINITION_PATH = "assets/" + VanillaItemPack.NAMESPACE + "/items/hotbar.json";

    /** The badge's sprites, under {@code textures/item/} and {@code models/item/}. */
    public static final String FRONT = "hotbar/badge";
    public static final String BACK = "hotbar/badge_back";

    private static volatile Table table;

    private record Table(Look look, java.util.Map<String, String> vanilla, java.util.Set<String> flat, java.util.Set<String> exceptions,
                         String jarDefinition) {
    }

    private HotbarBadge() {
    }

    /** The look the committed sprites and table were generated for. */
    public static Look look() {
        return table().look();
    }

    /** The sprites this look ships ({@link #FRONT}, {@link #BACK}), front last. */
    public static List<String> sprites() {
        List<String> out = new ArrayList<>();
        if (look().back) {
            out.add(BACK);
        }
        if (look().front) {
            out.add(FRONT);
        }
        return out;
    }

    /** Can {@link #ITEM_MODEL} draw this item definition id (a vanilla item's own picture)? */
    public static boolean drawsVanilla(String itemModelId) {
        return itemModelId != null && table().vanilla().containsKey(itemModelId);
    }

    /** How many vanilla pictures the table holds. */
    public static int vanillaCount() {
        return table().vanilla().size();
    }

    /**
     * The badge layers to compose AROUND an icon, as two nodes (behind the picture, in front of it), each a
     * {@code condition} on the flag that draws nothing unless the stack is a hotbar proxy. A null entry is a
     * layer this look does not have.
     */
    public static String[] iconLayers(String modelNs) {
        Look l = look();
        return new String[] {l.back ? flagged(modelNs, BACK) : null, l.front ? flagged(modelNs, FRONT) : null};
    }

    private static String flagged(String modelNs, String sprite) {
        return "{ \"type\": \"minecraft:condition\", \"property\": \"minecraft:custom_model_data\", \"index\": "
                + FLAG_INDEX + ", \"on_true\": { \"type\": \"minecraft:model\", \"model\": \"" + modelNs + ":item/"
                + sprite + "\" }, \"on_false\": { \"type\": \"minecraft:empty\" } }";
    }

    /**
     * How far the badge is drawn toward the viewer in a slot, in sixteenths of a block. A block item is a cube
     * turned toward you, and a flat badge at the slot's middle depth sank behind a chest's near corner
     * (docs/evidence-2755/overlay). The back layer goes as far the other way. Mirrors the tool's FORWARD.
     */
    public static final int FORWARD = 8;

    /** A badge layer's model: its sprite on {@code item/generated}, moved toward (or away from) the viewer. */
    public static String model(String modelNs, String sprite) {
        int z = BACK.equals(sprite) ? -FORWARD : FORWARD;
        return "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\n    \"layer0\": \""
                + modelNs + ":item/" + sprite + "\"\n  },\n  \"display\": {\n    \"gui\": {\n      \"translation\": [\n"
                + "        0,\n        0,\n        " + z + "\n      ]\n    }\n  }\n}\n";
    }

    /** Every vanilla id the hotbar definition draws, by rule. */
    public static java.util.Set<String> vanillaIds() {
        return table().vanilla().keySet();
    }

    /** The model the hotbar definition draws for this vanilla id, or null. */
    public static String modelOf(String id) {
        return table().vanilla().get(id);
    }

    /** The ids the rule cannot draw: their proxy keeps its own picture and the glint. */
    public static java.util.Set<String> exceptions() {
        return table().exceptions();
    }

    /** The block items drawn flat ({@code minecraft:item/<path>}) rather than as a block. */
    public static java.util.Set<String> flatBlocks() {
        return table().flat();
    }

    /** The suffixes of the blocks drawn by their inventory model (a fence, a wall, a button). */
    public static final List<String> INVENTORY_SUFFIXES = List.of("_fence", "_wall", "_button");

    /**
     * <b>The rule</b> that names a vanilla item's picture from its id: an item that is not a block, or a block on the
     * flat list, is {@code minecraft:item/<path>}; a fence, a wall or a button is {@code minecraft:block/<path>_inventory};
     * any other block is {@code minecraft:block/<path>}. Mirrors {@code tools/gen-hotbar-badge.py}'s rule_model.
     */
    public static String ruleModel(String id, boolean block, boolean flat) {
        String path = id.substring(id.indexOf(':') + 1);
        if (!block || flat) {
            return "minecraft:item/" + path;
        }
        for (String suffix : INVENTORY_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return "minecraft:block/" + path + "_inventory";
            }
        }
        return "minecraft:block/" + path;
    }

    /**
     * {@link #ITEM_MODEL}'s definition, for the models in {@code modelNs}: the jar's own bytes, which the pushed pack
     * carries too, with the badge's namespace swapped (the jar's badge is the mod's, the pack's is the pack's).
     */
    public static String definition(String modelNs) {
        return table().jarDefinition().replace("moddingfromamod:item/hotbar/", modelNs + ":item/hotbar/");
    }

    private static Table table() {
        Table t = table;
        if (t == null) {
            t = load();
            table = t;
        }
        return t;
    }

    private static Table load() {
        try (InputStream rules = HotbarBadge.class.getResourceAsStream("/items/hotbar-badge.json");
             InputStream def = HotbarBadge.class.getResourceAsStream("/" + DEFINITION_PATH)) {
            if (rules == null || def == null) {
                throw new IllegalStateException("items/hotbar-badge.json or " + DEFINITION_PATH + " is not on the classpath");
            }
            JsonObject o = JsonParser.parseReader(new InputStreamReader(rules, StandardCharsets.UTF_8)).getAsJsonObject();
            Look look = Look.valueOf(o.get("look").getAsString().toUpperCase(java.util.Locale.ROOT));
            String jarDef = new String(def.readAllBytes(), StandardCharsets.UTF_8);
            java.util.Map<String, String> vanilla = new java.util.LinkedHashMap<>();
            for (JsonElement m : JsonParser.parseString(jarDef).getAsJsonObject().getAsJsonObject("model")
                    .getAsJsonArray("models")) {
                if ("minecraft:select".equals(m.getAsJsonObject().get("type").getAsString())) {
                    for (JsonElement c : m.getAsJsonObject().getAsJsonArray("cases")) {
                        vanilla.put(c.getAsJsonObject().get("when").getAsString(),
                                c.getAsJsonObject().getAsJsonObject("model").get("model").getAsString());
                    }
                }
            }
            return new Table(look, Collections.unmodifiableMap(vanilla), ids(o, "flatBlocks"), ids(o, "exceptions"), jarDef);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the hotbar badge's files are unreadable", e);
        }
    }

    private static java.util.Set<String> ids(JsonObject o, String key) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (JsonElement e : o.getAsJsonArray(key)) {
            out.add(e.getAsString());
        }
        return Collections.unmodifiableSet(out);
    }
}
