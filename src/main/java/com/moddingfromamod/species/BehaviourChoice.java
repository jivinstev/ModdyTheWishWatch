package com.moddingfromamod.species;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>How a Builder creature LIVES, chosen from its picks</b>. A creature with no behaviour lives its
 * carrier's vanilla life, and the carrier is chosen for its hitbox, not for the wish: a ground creature grazed,
 * rammed or rolled up by chance, and every flier fluttered like the bat it rides. Every Builder and Apprentice make
 * now names one (the lowering puts it in {@code behaves_like}).
 *
 * <p><b>How it chooses.</b> A small learned model ({@code behaviour/choice.json}, written by
 * {@code tools/behaviour-train.py}): one weight per (pick, behaviour), the behaviour with the largest sum wins. It
 * was trained on Sonnet's choice for 700 random Builder creatures and agrees with Sonnet on 88.3% of 300 it never
 * saw, where Sonnet agrees with itself 89.0% and the hand-written {@link #rule} alone 77.3%
 * ({@code corpus/behaviour-choice/README.md}). The rule is one of the model's inputs, and what stands in for it if
 * the model file is missing.
 *
 * <p><b>The hard rules come after</b> ({@link #hardRules}), whatever was chosen: a vehicle body parks; a creature
 * that flies gets a flying life and one that does not never does; a sea body that does not fly swims; and a body
 * whose life was already chosen (its file's {@code behaves_like}) keeps it while nothing is changed from how
 * the Builder starts it (what people get by default does not move).
 *
 * <p>Pure: no Minecraft. {@code BehaviourChoiceTest} holds it to {@code corpus/behaviour-choice/expected.jsonl},
 * the Python reference's answer for every labelled creature.
 */
public final class BehaviourChoice {

    private BehaviourChoice() {
    }

    /**
     * What the Builder knows about one make. {@code from} is the Builder tile ({@code species/cat},
     * {@code bodies/eagle}); {@code ownLife} the body file's own {@code behaves_like} (null for none);
     * {@code powers} the picked ones as the tree spells them ({@code power:flies}); {@code temper} friendly, shy,
     * wild or guard; {@code size} 1–4.
     */
    public record Pick(String from, String ownLife, boolean bodyFlies, boolean bodySwims, boolean vehicle,
                       boolean ride, Set<String> powers, String temper, int size, boolean abilities) {
        public Pick {
            powers = powers == null ? Set.of() : Set.copyOf(powers);
            temper = temper == null || temper.isBlank() ? "friendly" : temper;
            ownLife = ownLife == null || ownLife.isBlank() ? null : ownLife;
        }

        public boolean flies() {
            return bodyFlies || powers.contains("power:flies");
        }

        boolean ridden() {
            return ride || powers.contains("power:rideable");
        }
    }

    /** The behaviour for this make: the model, then the hard rules. */
    public static Archetype choose(Pick p) {
        String chosen = Model.get() == null ? rule(p) : Model.get().predict(p);
        return Archetype.byKey(hardRules(p, chosen));
    }

    // ── the hand-written rule (tools/behaviour-score.py `rules`, the model's input) ─────────────────────

    /** One life per body as the Builder starts it, for the bodies whose file names none. */
    static final Map<String, String> BASE = Map.ofEntries(
            Map.entry("species/cat", "pack_hunter"), Map.entry("species/chicken", "grazer"),
            Map.entry("species/fire_imp", "villager"), Map.entry("species/frog", "grazer"),
            Map.entry("species/helper_caller", "villager"), Map.entry("species/pet_dragon", "dragon"),
            Map.entry("species/sheep", "grazer"), Map.entry("species/snail", "grazer"),
            Map.entry("species/trex", "bruiser"), Map.entry("species/turtle", "grazer"),
            Map.entry("species/wolf", "pack_hunter"),
            Map.entry("bodies/bear", "bruiser"), Map.entry("bodies/dolphin", "swimmer"),
            Map.entry("bodies/duck", "grazer"), Map.entry("bodies/eagle", "dragon"),
            Map.entry("bodies/elephant", "grazer"), Map.entry("bodies/horse", "mount"),
            Map.entry("bodies/lion", "pack_hunter"), Map.entry("bodies/long_neck_dinosaur", "grazer"),
            Map.entry("bodies/monkey", "climber"), Map.entry("bodies/octopus", "swimmer"),
            Map.entry("bodies/penguin", "grazer"), Map.entry("bodies/person", "villager"),
            Map.entry("bodies/pig", "grazer"), Map.entry("bodies/rabbit", "grazer"),
            Map.entry("bodies/robot", "villager"), Map.entry("bodies/shark", "swimmer"),
            Map.entry("bodies/snake", "pack_hunter"), Map.entry("bodies/t_rex", "bruiser"));

    /**
     * The pinned behaviour of every starter species and Builder body with no life of its own, AS THE BUILDER STARTS IT
     * (friendly, a creature, nothing picked): a reviewed table, never the model's guess ("the
     * ML model doesn't need to make a determination about a pure starter creature, only about the modded
     * creatures"). It is {@link #BASE}: the pet dragon walked (pinned {@code pack_hunter}) until the Builder gave it
     * the flying every dragon has ({@code wishbuilder/BuilderNatural}).
     */
    public static final Map<String, String> DEFAULTS = BASE;

    static final Set<String> PEOPLE = Set.of("species/fire_imp", "species/helper_caller", "bodies/person",
            "bodies/robot", "bodies/gnome", "bodies/ogre", "bodies/yeti", "bodies/monkey");
    static final Set<String> HUNTERS = Set.of("species/cat", "species/wolf", "bodies/lion", "bodies/raptor",
            "bodies/snake", "bodies/bear");
    static final Set<String> PREY = Set.of("species/chicken", "species/sheep", "species/frog", "species/snail",
            "species/turtle", "bodies/duck", "bodies/hamster", "bodies/rabbit", "bodies/pig", "bodies/penguin");
    /** Lives that fly (a mount is a pegasus when it flies; a flying vehicle flies while he drives it). */
    public static final Set<String> FLYING = Set.of("buzzer", "dragon", "mount", "vehicle");

    static String bodyLife(Pick p) {
        String base = BASE.get(p.from());
        if (base != null) {
            return base;
        }
        if (p.ownLife() != null) {
            return p.ownLife();
        }
        return p.vehicle() ? "vehicle" : p.bodyFlies() ? "buzzer" : p.bodySwims() ? "swimmer" : "grazer";
    }

    /** A flier circles like a dragon when it is giant, or big and fierce; otherwise it flits. */
    static String flier(Pick p) {
        return p.size() >= 4 || (p.size() == 3 && (p.temper().equals("wild") || p.temper().equals("guard")))
                ? "dragon" : "buzzer";
    }

    /** The hand-written rule: the model's input, and its stand-in. */
    public static String rule(Pick p) {
        String life = bodyLife(p);
        if (life.equals("vehicle") || p.vehicle()) {
            return "vehicle";
        }
        String t = p.temper();
        if (p.bodySwims() && !p.flies()) {
            return "swimmer";
        }
        if (p.flies() && life.equals("mount")) {
            return "mount";
        }
        if (p.bodyFlies()) {
            return p.ridden() ? "mount" : flier(p);
        }
        if (p.ridden()) {
            return "mount";
        }
        if (p.flies()) {
            return flier(p);
        }
        switch (t) {
            case "shy" -> {
                return "grazer";
            }
            case "guard" -> {
                return PREY.contains(p.from()) ? "grazer" : HUNTERS.contains(p.from()) ? "pack_hunter" : "bruiser";
            }
            case "wild" -> {
                return HUNTERS.contains(p.from()) ? "pack_hunter" : "bruiser";
            }
            default -> {
            }
        }
        if (p.ownLife() != null) {
            return p.ownLife();
        }
        if (PEOPLE.contains(p.from())) {
            return "villager";
        }
        if (HUNTERS.contains(p.from())) {
            return "pack_hunter";
        }
        return life.equals("climber") || life.equals("archer") || life.equals("mount") ? life : "grazer";
    }

    // ── the hard rules ───────────────────────────────────────────────────────────────────────────────

    /** Applied after whatever chose (tools/behaviour-train.py `hard_rules`). */
    public static String hardRules(Pick p, String chosen) {
        String b = chosen == null ? rule(p) : chosen;
        if (p.vehicle()) {
            return "vehicle";
        }
        if (p.temper().equals("friendly") && !p.ride() && p.powers().isEmpty()) {
            // NOTHING CHANGED from how the Builder starts it: never the model's call. The body's own
            // chosen life, else the pinned starter table.
            if (p.ownLife() != null) {
                return p.ownLife();
            }
            String pinned = DEFAULTS.get(p.from());
            if (pinned != null) {
                return pinned;
            }
        }
        if (b.equals("vehicle")) {
            b = rule(p);
        }
        if (p.bodySwims() && !p.flies()) {
            return "swimmer";
        }
        if (p.flies() && !FLYING.contains(b)) {
            return flier(p);
        }
        if (!p.flies() && (b.equals("buzzer") || b.equals("dragon"))) {
            b = rule(p);
            if (b.equals("buzzer") || b.equals("dragon")) {
                b = "grazer";
            }
        }
        return b;
    }

    // ── the model ───────────────────────────────────────────────────────────────────────────────────

    /** The learned weights ({@code behaviour/choice.json}); null when the jar has none. */
    static final class Model {
        private static volatile Model loaded;
        private static volatile boolean tried;

        final List<String> behaviours;
        final List<String> features;
        final double[][] weights;
        final double[] bias;

        Model(JsonObject j) {
            behaviours = strings(j.getAsJsonArray("behaviours"));
            features = strings(j.getAsJsonArray("features"));
            JsonArray w = j.getAsJsonArray("weights");
            weights = new double[w.size()][];
            for (int k = 0; k < w.size(); k++) {
                JsonArray row = w.get(k).getAsJsonArray();
                weights[k] = new double[row.size()];
                for (int i = 0; i < row.size(); i++) {
                    weights[k][i] = row.get(i).getAsDouble();
                }
            }
            JsonArray b = j.getAsJsonArray("bias");
            bias = new double[b.size()];
            for (int k = 0; k < b.size(); k++) {
                bias[k] = b.get(k).getAsDouble();
            }
            if (weights.length != behaviours.size() || bias.length != behaviours.size()) {
                throw new IllegalArgumentException("behaviour/choice.json: weights and behaviours disagree");
            }
        }

        static Model get() {
            if (!tried) {
                synchronized (Model.class) {
                    if (!tried) {
                        try (InputStream in = BehaviourChoice.class.getResourceAsStream("/behaviour/choice.json")) {
                            loaded = in == null ? null : new Model(JsonParser.parseReader(
                                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
                        } catch (Exception e) {
                            loaded = null;   // the rule stands in; BehaviourChoiceTest fails a jar without the model
                        }
                        tried = true;
                    }
                }
            }
            return loaded;
        }

        String predict(Pick p) {
            Set<String> on = new HashSet<>();
            on.add("from=" + p.from());
            on.add("temper=" + p.temper());
            on.add("size=" + p.size());
            on.add("rule=" + rule(p));
            on.addAll(p.powers());
            if (p.ride()) {
                on.add("ride");
            }
            if (p.flies()) {
                on.add("flies");
            }
            if (p.bodyFlies()) {
                on.add("body_flies");
            }
            if (p.bodySwims()) {
                on.add("body_swims");
            }
            if (p.vehicle()) {
                on.add("vehicle");
            }
            if (p.abilities()) {
                on.add("abilities");
            }
            int best = 0;
            double top = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < behaviours.size(); k++) {
                double v = bias[k];
                for (int i = 0; i < features.size(); i++) {
                    if (on.contains(features.get(i))) {
                        v += weights[k][i];
                    }
                }
                if (v > top) {
                    top = v;
                    best = k;
                }
            }
            return behaviours.get(best);
        }

        private static List<String> strings(JsonArray a) {
            List<String> out = new ArrayList<>();
            a.forEach(e -> out.add(e.getAsString()));
            return out;
        }
    }

    /** Is the learned model in this jar? (A test seam, and the log line at boot.) */
    public static boolean modelLoaded() {
        return Model.get() != null;
    }
}
