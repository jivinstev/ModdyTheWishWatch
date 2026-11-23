package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * <b>Ideas for changing a Builder make he has</b> (step 5, rung 4): one change the editor ({@link BuilderEdit})
 * really accepts on that make, in words a child reads. Every idea is TRIED on the editor before it is offered, so one
 * pressed as it is never escalates: the suggestion and the grant are the same Builder edit. Chosen at random, so a
 * child sees the range of what Moddy can change.
 *
 * <p>Pure: no Minecraft.
 */
public final class EditIdeas {

    private EditIdeas() {
    }

    /** One idea: what he reads, and the change it makes. */
    public record Idea(String words, List<BuilderEdit.Op> ops) {
    }

    /**
     * A random idea for the make {@code id} kept as {@code spec}, or null when the editor accepts none of them. {@code name}
     * is what he calls it ("Sparky"; the kind's word when it has no name).
     */
    public static Idea pick(BuilderAllowlist allow, LibraryPresets presets, String id, BuilderSpec spec, Random random) {
        List<Idea> all = candidates(spec, presets);
        java.util.Collections.shuffle(all, random);
        for (Idea idea : all) {
            if (BuilderEdit.apply(allow, presets, id, spec, idea.ops()).ok()) {
                return idea;
            }
        }
        return null;
    }

    /** Every change worth suggesting for this kind, before the editor is asked which it accepts. */
    static List<Idea> candidates(BuilderSpec spec) {
        return candidates(spec, LibraryPresets.SHIPPED);
    }

    static List<Idea> candidates(BuilderSpec spec, LibraryPresets presets) {
        String it = name(spec, presets);
        List<Idea> out = new ArrayList<>();
        boolean creature = spec.kind() == BuilderSpec.Kind.CREATURE || spec.kind() == BuilderSpec.Kind.RIDE;
        if (creature || spec.kind() == BuilderSpec.Kind.BUILDING || spec.kind() == BuilderSpec.Kind.EVENT) {
            out.add(new Idea("Make " + it + " bigger", List.of(BuilderEdit.Op.step("size", 1))));
            out.add(new Idea("Make " + it + " smaller", List.of(BuilderEdit.Op.step("size", -1))));
        }
        if (creature) {
            for (Map.Entry<String, String> p : BuilderFlow.POWER_WORDS.entrySet()) {
                if (!spec.powers().contains("power:" + p.getKey()) && !p.getKey().equals("persistent")) {
                    out.add(new Idea(powerWords(it, p.getKey()), List.of(BuilderEdit.Op.add("powers", "power:" + p.getKey()))));
                }
            }
            if (!spec.temper().equals("pet")) {
                out.add(new Idea("Make " + it + " follow me", List.of(BuilderEdit.Op.set("temper", "temper:pet"))));
            }
            if (!spec.opt("speed").equals("3")) {
                out.add(new Idea("Make " + it + " faster", List.of(BuilderEdit.Op.step("speed", 1))));
            }
        }
        if (spec.kind() == BuilderSpec.Kind.ITEM || spec.kind() == BuilderSpec.Kind.MAGIC_BLOCK
                || spec.kind() == BuilderSpec.Kind.MACHINE) {
            out.add(new Idea("Make " + it + " stronger", List.of(BuilderEdit.Op.step("strength", 1))));
        }
        for (Schemes.Scheme s : Schemes.ALL) {
            if (Schemes.SOLIDS.contains(s) && !IDEA_SOLIDS.contains(s.id())) {
                continue;   //: a few solids as ideas, so colours do not crowd out every other idea
            }
            if (!s.id().equals(spec.scheme()) && !it.toLowerCase(java.util.Locale.ROOT).contains(s.id())) {
                out.add(new Idea(colour(it, s), List.of(BuilderEdit.Op.set("scheme", s.id()))));
            }
        }
        return out;
    }

    static String powerWords(String it, String power) {
        return switch (power) {
            case "flies" -> "Make " + it + " fly";
            case "glowing" -> "Make " + it + " glow";
            case "rideable" -> "Make " + it + " rideable";
            case "hops" -> "Make " + it + " hop";
            case "leaps" -> "Make " + it + " pounce";
            case "fireproof" -> "Make " + it + " fireproof";
            case "naps" -> "Make " + it + " sleepy";
            default -> "Give " + it + " " + BuilderFlow.POWER_WORDS.getOrDefault(power, power).toLowerCase(java.util.Locale.ROOT);
        };
    }

    /**
     * (first person, "make my"): what HE calls it, as he would type it. A name he chose is bare
     * ("Sparky"); a name Moddy gave is "my" and its last word when the whole would be long ("my whale", not "my Gold
     * Big Blue Whale"), else lower-case ("my healing cupcake"); no name is "my" and the kind ("my creature").
     */
    static String name(BuilderSpec spec, LibraryPresets presets) {
        String n = spec.name().strip();
        if (n.isEmpty()) {
            return "my " + spec.kind().id().replace('_', ' ');
        }
        if (!BuilderNames.isModdys(spec, presets, n)) {
            return n;   // a name he chose: said as he wrote it
        }
        if (n.contains(" the ")) {
            return n.substring(0, n.indexOf(" the "));   // a pet Moddy named ("Skye the Dragon"): its own name, "Skye"
        }
        String bare = n.replaceFirst(" [0-9]+$", "");
        if (!bare.contains(" ")) {
            return bare;   // one word Moddy gave is a pet's name ("Frosty"), said as one
        }
        String low = bare.toLowerCase(java.util.Locale.ROOT);
        String said = low.length() > LONGEST_NAME ? low.substring(low.lastIndexOf(' ') + 1) : low;
        return "my " + said;
    }

    static String name(BuilderSpec spec) {
        return name(spec, LibraryPresets.SHIPPED);
    }

    /** Past this many letters a Moddy name is said by its last word. */
    static final int LONGEST_NAME = 16;

    /**
     * a colour scheme said in a sentence, lower-case, never a second capitalised noun: "Give my whale sunset
     * colours", and a colour that IS one word as one: "Make my whale gold".
     */
    /** the solids an idea may suggest. */
    static final java.util.Set<String> IDEA_SOLIDS = java.util.Set.of("red", "blue", "green", "black", "white", "pink");

    static String colour(String it, Schemes.Scheme s) {
        return Schemes.solid(s.id()) ? "Make " + it + " " + s.label().toLowerCase(java.util.Locale.ROOT)
                : "Give " + it + " " + s.label().toLowerCase(java.util.Locale.ROOT) + " colours";
    }
}
