package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>Add parts to a creature</b>: the sculpt kits the wish worker already hangs on a creature
 * ({@code runtime/SculptKits}: horns, wings, a tail, spikes, claws, fins, a shell, a crest), offered on any
 * ready-made body. Each hangs off the part a kid would expect (horns and a crest on the head; the rest on the
 * body), in its own colour unless he picked a colour scheme. Pure: {@code BuilderPartsTest}.
 */
public final class BuilderParts {

    private BuilderParts() {
    }

    /**
     * One part: its Builder id, the sculpt kit and style it is ({@code SculptKits}), the tile's words and icon,
     * whether it hangs on the head, its colour ("" = the creature's own), and its count (for the kits that take one).
     */
    public record Part(String id, String kit, String style, String label, String tile, boolean onHead, String colour,
                       int count, List<String> words) {
        /** The pre-F1 parts: one per kit, its default look; the id IS the kit (kept specs name them so). */
        Part(String kit, String label, String tile, boolean onHead, String colour, int count, List<String> words) {
            this(kit, kit, "", label, tile, onHead, colour, count, words);
        }
    }

    public static final List<Part> ALL = List.of(
            new Part("horn", "Horns", "minecraft:goat_horn", true, "#EFE6CF", 2, List.of("horns~", "horn~", "horned", "pointy horns", "devil horns")),
            new Part("antlers", "horn", "antler", "Antlers", "minecraft:dead_bush", true, "#8A6A45", 2, List.of("antlers", "antler", "deer horns", "reindeer horns", "horns~", "branchy horns", "deer antlers", "reindeer antlers")),
            new Part("unicorn_horn", "horn", "unicorn", "A unicorn horn", "minecraft:end_rod", true, "#F6E9B8", 1, List.of("unicorn horn", "one horn", "magic horn", "horn~", "spiral horn", "sparkly horn", "twisty horn", "single horn", "horn on its forehead")),
            new Part("ram_horns", "horn", "ram", "Ram horns", "minecraft:nautilus_shell", true, "#C9B48F", 2, List.of("ram horns", "curly horns", "goat horns", "horns~", "horn~")),
            new Part("ears", "ears", "pointy", "Pointy ears", "minecraft:rabbit_hide", true, "", 2, List.of("pointy ears", "pointed ears", "cat ears", "elf ears", "ears~")),
            new Part("round_ears", "ears", "round", "Round ears", "minecraft:brown_mushroom", true, "", 2, List.of("round ears", "bear ears", "mouse ears", "ears~", "ears like a bear", "teddy ears", "circle ears")),
            new Part("floppy_ears", "ears", "floppy", "Floppy ears", "minecraft:leather", true, "", 2, List.of("floppy ears", "dog ears", "droopy ears", "ears~", "ears that flop", "floppy")),
            new Part("bunny_ears", "ears", "long", "Bunny ears", "minecraft:rabbit_foot", true, "", 2, List.of("bunny ears", "rabbit ears", "long ears", "ears~")),
            new Part("snout", "snout", "", "A snout", "minecraft:porkchop", true, "", 1, List.of("snout", "pig nose", "big nose", "nose like a pig", "piggy nose", "pig snout")),
            new Part("beak", "beak", "", "A beak", "minecraft:orange_dye", true, "#F2A33A", 1, List.of("beak", "bill", "bird beak")),
            new Part("trunk", "trunk", "", "A trunk", "minecraft:gray_dye", true, "", 5, List.of("trunk", "elephant trunk", "long nose")),
            new Part("tusks", "tusk", "", "Tusks", "minecraft:bone_meal", true, "#F4EFE0", 2, List.of("tusks", "tusk", "fangs", "big teeth", "pointy teeth", "teeth sticking out", "sharp teeth")),
            new Part("mane", "mane", "", "A mane", "minecraft:hay_block", true, "#C7832F", 1, List.of("mane", "lion mane", "fluffy mane", "hair", "hairy mane", "big mane")),
            new Part("crest", "A crown", "minecraft:golden_helmet", true, "#FFD54A", 3, List.of("crown", "tiara", "crest", "king crown")),
            new Part("wing", "Wings", "minecraft:elytra", false, "#8E5BD0", 2, List.of("wings~", "wing", "winged")),
            new Part("feather_wings", "wing", "feather", "Feather wings", "minecraft:feather", false, "#F4F1EA", 2, List.of("feather wings", "angel wings", "bird wings", "feathery wings", "eagle wings", "wings~", "wings made of feathers", "feathered wings")),
            new Part("bat_wings", "wing", "bat", "Bat wings", "minecraft:phantom_membrane", false, "#3B3140", 2, List.of("bat wings", "demon wings", "vampire wings", "wings~", "leathery wings", "spooky wings", "leather wings")),
            new Part("butterfly_wings", "wing", "butterfly", "Butterfly wings", "minecraft:pink_dye", false, "#F07FC0", 2, List.of("butterfly wings", "fairy wings", "pretty wings", "wings~", "flutter wings", "colorful wings", "colourful wings")),
            new Part("dragon_wings", "wing", "dragon", "Dragon wings", "minecraft:dragon_breath", false, "#7A2E2E", 2, List.of("dragon wings", "scaly wings", "big wings", "wings~")),
            new Part("tail", "A tail", "minecraft:lead", false, "#8A5A35", 3, List.of("tail~", "tails", "long tail")),
            new Part("fluffy_tail", "tail", "fluffy", "A fluffy tail", "minecraft:white_wool", false, "", 4, List.of("fluffy tail", "fox tail", "bushy tail", "furry tail", "tail~", "puffy tail", "poofy tail")),
            new Part("fish_tail", "tail", "fish", "A fish tail", "minecraft:tropical_fish", false, "#3FA7C9", 3, List.of("fish tail", "mermaid tail", "fishy tail", "tail~", "tail like a mermaid", "tail like a fish")),
            new Part("peacock_tail", "tail", "peacock", "A peacock tail", "minecraft:green_dye", false, "#2E9C7E", 7, List.of("peacock tail", "peacock feathers", "fan tail", "feather tail", "tail~", "tail like a peacock")),
            new Part("lion_tail", "tail", "lion", "A lion tail", "minecraft:string", false, "", 4, List.of("lion tail", "tufted tail", "tiger tail", "tail~", "tail with a fluffy tip", "tail with a tuft")),
            new Part("insect_legs", "leg_row", "", "Six bug legs", "minecraft:spider_eye", false, "#2B2B2B", 3, List.of("bug legs", "six legs", "insect legs", "ant legs", "legs~", "legs like an insect", "legs like a bug")),
            new Part("spider_legs", "leg_row", "", "Eight spider legs", "minecraft:cobweb", false, "#2B2B2B", 4, List.of("spider legs", "eight legs", "lots of legs", "legs~", "legs like a spider", "spidery legs")),
            new Part("spike_row", "Spikes", "minecraft:pointed_dripstone", false, "#6E7A86", 3, List.of("spikes", "spiky", "spines", "spiked", "spike")),
            new Part("claw", "Claws", "minecraft:bone", false, "#F2F0E6", 2, List.of("claws", "claw", "talons", "sharp claws", "scratchy claws", "big claws")),
            new Part("fin", "Fins", "minecraft:prismarine_shard", false, "#3FA7C9", 2, List.of("fins", "fin", "shark fin", "flippers")),
            new Part("shell", "A shell", "minecraft:turtle_scute", false, "#4F8A3C", 1, List.of("shell", "turtle shell", "shelled")));

    /**
     * is this ready-made a VEHICLE (a truck, a boat, a rocket)? A vehicle takes no creature parts: ears and a
     * unicorn horn on a pickup truck are a creature's, and they were found offered on a phone. Every Builder body
     * says so in its species file, {@code "vehicle": true|false} (BuilderVehiclesTest holds every body to it, and a
     * vehicle to {@code behaves: vehicle}: parked until driven). A ready-made that is not a Builder body (the
     * starter species) has no marker and is not a vehicle. Pure.
     */
    public static boolean vehicle(String preset, JsonObject species) {
        return species != null && species.has("vehicle") && species.get("vehicle").isJsonPrimitive()
                && species.get("vehicle").getAsBoolean();
    }

    /** the powers only a creature has — a vehicle does not hop, pounce or nap. */
    public static final java.util.Set<String> CREATURE_ONLY_POWERS = java.util.Set.of("hops", "leaps", "naps");

    /** A Builder part by its id ({@code antlers}, or a pre-F1 kit name such as {@code horn}). */
    public static Part byId(String id) {
        for (Part p : ALL) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /**
     * How many boxes these parts add to a creature of {@code parts}, expanding them exactly as the sculpt tool
     * will. A row of legs is anatomy and is not counted, as the tool does not count it.
     */
    public static int boxes(List<String> chosen, JsonArray parts, String accent) {
        if (chosen.isEmpty() || parts == null) {
            return 0;
        }
        List<com.moddingfromamod.runtime.CreatureSculpt.Part> base = com.moddingfromamod.runtime.CreatureSculpt.parseParts(parts);
        int n = 0;
        for (var made : com.moddingfromamod.runtime.SculptKits.expand(kits(chosen, parts, accent), base)) {
            if (!made.name().startsWith(com.moddingfromamod.runtime.SculptKits.MARK + "leg_row")) {
                n++;
            }
        }
        return n;
    }

    /** The boxes kits may add to a creature of {@code partCount} parts (SculptCreatureTool's kit budget). */
    public static int budget(int partCount) {
        double share = com.moddingfromamod.agent.tools.SculptCreatureTool.MAX_KIT_SHARE;
        return Math.max(com.moddingfromamod.agent.tools.SculptCreatureTool.MIN_KIT_PARTS,
                (int) Math.floor(partCount * (share / (1.0 - share))));
    }

    /**
     * The {@code kits} array for sculpt_creature: each chosen kit on the head or the body of {@code parts}
     * (the ready-made's own parts), coloured by {@code accent} when a scheme gave one.
     */
    public static JsonArray kits(List<String> chosen, JsonArray parts, String accent) {
        JsonArray out = new JsonArray();
        String head = named(parts, "head");
        String body = named(parts, "body");
        String first = firstName(parts);
        for (String k : chosen) {
            Part p = byId(k);
            if (p == null) {
                throw new IllegalArgumentException("'" + k + "' is not a part the Builder adds");
            }
            JsonObject o = new JsonObject();
            o.addProperty("kit", p.kit());
            if (!p.style().isEmpty()) {
                o.addProperty("style", p.style());
            }
            String on = p.onHead() && head != null ? head : body != null ? body : head != null ? head : first;
            if (on == null) {
                throw new IllegalArgumentException("this creature has no part to hang " + p.label() + " on");
            }
            o.addProperty("on", on);
            o.addProperty("count", p.count());
            String colour = accent == null || accent.isEmpty() ? p.colour() : accent;
            if (colour.isEmpty()) {   // ears, a snout, a trunk, a lion's tail: the creature's own colour
                colour = colourOf(parts, on);
            }
            if (colour != null && !colour.isEmpty()) {
                o.addProperty("colour", colour);
            }
            if (p.kit().equals("spike_row")) {
                o.addProperty("along", "back");
            }
            out.add(o);
        }
        return out;
    }

    /** The colour of the named part, as the ready-made stores it, or null. */
    private static String colourOf(JsonArray parts, String name) {
        if (parts == null || name == null) {
            return null;
        }
        for (JsonElement e : parts) {
            if (e.isJsonObject() && e.getAsJsonObject().has("name") && e.getAsJsonObject().get("name").getAsString().equals(name)
                    && e.getAsJsonObject().has("colour") && e.getAsJsonObject().get("colour").isJsonPrimitive()) {
                return e.getAsJsonObject().get("colour").getAsString();
            }
        }
        return null;
    }

    private static String named(JsonArray parts, String name) {
        if (parts == null) {
            return null;
        }
        for (JsonElement e : parts) {
            if (e.isJsonObject() && e.getAsJsonObject().has("name") && e.getAsJsonObject().get("name").getAsString().equals(name)) {
                return name;
            }
        }
        return null;
    }

    private static String firstName(JsonArray parts) {
        if (parts == null) {
            return null;
        }
        for (JsonElement e : parts) {
            if (e.isJsonObject() && e.getAsJsonObject().has("name")) {
                return e.getAsJsonObject().get("name").getAsString();
            }
        }
        return null;
    }

    /** The kits a spec asks for: its {@code kit1..kitN} options, in order ({@code kit:horn} → {@code horn}). */
    public static List<String> chosen(BuilderSpec spec) {
        List<String> out = new ArrayList<>();
        // as many as he picked — the body's room (budget) is the limit, never a count.
        for (int i = 1; i <= ALL.size(); i++) {
            String v = spec.opt("kit" + i);
            if (!v.isEmpty()) {
                out.add(Catalogue.bare(v));
            }
        }
        return out;
    }
}
