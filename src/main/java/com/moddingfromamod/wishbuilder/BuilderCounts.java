package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.mob.SpawnBudget;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>How many creatures one Builder make may bring</b> (what the
 * Magician may do under the world's rules, the Apprentice may too, capped at the world's rules). The
 * Builder and the Apprentice ask the same per-world numbers the Magician's spawns do ({@link SpawnBudget}): a real mob
 * by its class (ordinary 100, flyers 25, things that explode 50, bosses 1 on a default world, each a live knob), a
 * Builder body at most {@link SpawnBudget#SCULPTED_MAX_COUNT} (the spawn then fits them to the world's display budget
 * and says so when it stops short). A world's {@code crafters.builder.maxCount} can only lower it.
 *
 * <p>Pure: {@code BuilderCountsTest}.
 */
public final class BuilderCounts {

    private BuilderCounts() {
    }

    /** The highest count any make offers: the ordinary class's ceiling a world may set is far above it. */
    public static final int TOP = 100;
    /** The counts the menus and the Apprentice say by name (the slider moves one at a time). */
    public static final List<Integer> STEPS = List.of(1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 64, 75, 100);

    /** The class this make's creatures count in, or SCULPTED for a Builder body. */
    public static SpawnBudget.Kind kindOf(String from) {
        String f = from == null ? "" : from;
        if (Catalogue.prefix(f).equals("mob")) {
            SpawnBudget.Kind k = SpawnBudget.classify(Catalogue.bare(f), true);
            return k == null ? SpawnBudget.Kind.VANILLA : k;
        }
        return SpawnBudget.Kind.SCULPTED;
    }

    /** How many of an ITEM or an EVENT one make gives (unchanged, which is about creatures). */
    public static final int OTHER = 5;

    /** The most this make may bring on this world, by its kind: a creature by {@link #most(BuilderAllowlist, String)}. */
    public static int most(BuilderAllowlist allow, BuilderSpec.Kind kind, String from) {
        return kind == BuilderSpec.Kind.CREATURE ? most(allow, from) : Math.max(1, Math.min(allow.maxCount(), OTHER));
    }

    /** The most this make may bring on this world: its class's number, never over the world's Builder list. */
    public static int most(BuilderAllowlist allow, String from) {
        SpawnBudget.Kind k = kindOf(from);
        int world = k == SpawnBudget.Kind.SCULPTED ? Math.min(SpawnBudget.SCULPTED_MAX_COUNT, TOP) : SpawnBudget.perWish(k);
        return Math.max(1, Math.min(allow.maxCount(), Math.min(TOP, world)));
    }

    /** The steps up to {@code most}, and {@code most} itself when it falls between two. */
    public static List<Integer> steps(int most) {
        List<Integer> out = new ArrayList<>();
        for (int s : STEPS) {
            if (s <= most) {
                out.add(s);
            }
        }
        if (out.isEmpty() || out.get(out.size() - 1) != most) {
            out.add(most);
        }
        return out;
    }

    /** each class's most and members, for the Python twin (tools/apprentice_lib.py count_most). */
    static com.google.gson.JsonObject toJson() {
        com.google.gson.JsonObject j = new com.google.gson.JsonObject();
        j.addProperty("top", TOP);
        j.addProperty("other", OTHER);
        j.addProperty("sculpted", Math.min(SpawnBudget.SCULPTED_MAX_COUNT, TOP));
        com.google.gson.JsonObject per = new com.google.gson.JsonObject();
        com.google.gson.JsonObject members = new com.google.gson.JsonObject();
        for (SpawnBudget.Kind k : SpawnBudget.Kind.values()) {
            if (k == SpawnBudget.Kind.SCULPTED) {
                continue;
            }
            String id = k.name().toLowerCase(java.util.Locale.ROOT);
            per.addProperty(id, SpawnBudget.defaultFor(k, false));
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            SpawnBudget.members(k).stream().sorted().forEach(a::add);
            if (!a.isEmpty()) {
                members.add(id, a);
            }
        }
        j.add("perWish", per);
        j.add("members", members);
        return j;
    }

    /** What a tile says when a number was fitted down to the most: "25 flyers is the most at once here". */
    public static String mostLine(String from, int most) {
        String what = switch (kindOf(from)) {
            case FLYING -> most == 1 ? "flyer" : "flyers";
            case EXPLOSIVE -> most == 1 ? "thing that explodes" : "things that explode";
            case BOSS -> most == 1 ? "boss" : "bosses";
            default -> most == 1 ? "creature" : "creatures";
        };
        return most + " " + what + " is the most at once here";
    }
}
