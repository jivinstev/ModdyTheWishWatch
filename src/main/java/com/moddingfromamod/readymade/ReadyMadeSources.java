package com.moddingfromamod.readymade;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.EffectCatalogue;
import com.moddingfromamod.datapack.ItemCatalogue;

/**
 * <b>Where the index comes from</b>: every entry is generated from a store that already
 * ships, so the index cannot describe something the tools would refuse.
 *
 * <p>The shipped kinds — (b) the starter creatures, (c) the effect catalogue and the sculpt
 * abilities, (d) the item catalogue — are read here from the jar and need no game. Kind (a), the
 * things this server already made, needs a world, so the game hands its entries in through
 * {@link #priorWish}; the routing arm hands in a fixture world's instead.
 *
 * <p>{@link #itemDetail} and {@link #effectDetail} are the per-entry text {@code make_item} and
 * {@code apply_effect} printed for every entry on every turn. They live here so the
 * full list and {@code describe_ready_made} are one definition: the slim arm moves the text, it
 * does not rewrite it.
 */
public final class ReadyMadeSources {

    private ReadyMadeSources() {
    }

    /** Every kind that needs no world. (a) is added by the caller, from the server's stores. */
    public static List<ReadyMade> shipped() {
        List<ReadyMade> out = new ArrayList<>();
        out.addAll(starterCreatures());
        out.addAll(effects(EffectCatalogue.load()));
        if (ABILITIES_RUN_ON_THE_RIG) {
            out.addAll(abilities());
        }
        out.addAll(items(ItemCatalogue.load()));
        return out;
    }

    // ---- (c) effect templates ------------------------------------------------------------------

    public static List<ReadyMade> effects(EffectCatalogue cat) {
        List<ReadyMade> out = new ArrayList<>();
        for (EffectCatalogue.Effect e : cat.all()) {
            List<String> tags = new ArrayList<>();
            if (e.ability() != null && !e.ability().isBlank()) {
                tags.add(e.ability().replace('_', ' '));
            }
            out.add(new ReadyMade(ReadyMade.Kind.EFFECT, e.id(), e.id().replace('_', ' '), List.of(),
                    tags, e.summary(), "apply_effect effect:\"" + e.id() + "\" tag:<the creature>",
                    effectDetail(e), e.wishes()));
        }
        return out;
    }

    /** One catalogue effect as apply_effect's description has always printed it. */
    public static String effectDetail(EffectCatalogue.Effect e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.id()).append(" — ").append(e.summary())
          .append("\n      fires: ").append(e.when())
          .append("\n      lands on: ").append(e.who())
          .append("\n      e.g. \"").append(e.wishes().isEmpty() ? "" : e.wishes().get(0)).append('"');
        for (String p : e.params()) {
            sb.append("\n        - ").append(p);
            String def = e.defaults().get(p);
            if (def != null) {
                sb.append(" (default ").append(def).append(')');
            }
            String doc = e.paramDocs().get(p);
            if (doc != null) {
                sb.append(": ").append(doc);
            }
        }
        return sb.toString();
    }

    /**
     * The sculpt abilities ({@code runtime.Abilities.Kind}), each one line. A test holds this list
     * equal to the enum, so a new move cannot ship unoffered.
     */
    public static final Map<String, String> ABILITIES = abilityLines();

    /**
     * <b>True: the sculpt abilities run on the creature that is actually in the world</b> (
     * chunk 1). They were withheld while {@code runtime.Abilities} was ticked only by
     * {@code WishMobEntity}, which the rig never adds — a {@code slam} creature never
     * slammed. {@code vanilla/RigAbilities} carries the six moves out on the rig carrier, and
     * {@code SculptAbilitiesOnRigGameTest} proves each one in the world (and goes red, all eight
     * tests, with that tick switched off). Set it false again only alongside a test that shows a
     * move dead.
     */
    public static final boolean ABILITIES_RUN_ON_THE_RIG = true;

    private static Map<String, String> abilityLines() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ranged", "spits or shoots a projectile at something far away — fireballs, spit, arrows");
        m.put("slam", "a ground slam that knocks everything nearby away — stomps, smashes, quakes");
        m.put("charge", "a burst of speed straight at its target — rams, tackles, dashes");
        m.put("summon", "calls in small helpers to fight beside it — minions, babies, a swarm");
        m.put("teleport", "blinks to its target — vanishes and reappears");
        m.put("heal", "quietly heals itself over time — regenerates, can't be worn down");
        return m;
    }

    public static List<ReadyMade> abilities() {
        List<ReadyMade> out = new ArrayList<>();
        for (Map.Entry<String, String> e : ABILITIES.entrySet()) {
            out.add(new ReadyMade(ReadyMade.Kind.EFFECT, e.getKey(), e.getKey(), List.of(),
                    List.of("sculpt ability"), e.getValue(),
                    "sculpt_creature abilities:[\"" + e.getKey() + "\"]", "", List.of()));
        }
        return out;
    }

    // ---- (d) item abilities --------------------------------------------------------------------

    public static List<ReadyMade> items(ItemCatalogue cat) {
        List<ReadyMade> out = new ArrayList<>();
        for (ItemCatalogue.Entry e : cat.all()) {
            List<String> tags = new ArrayList<>();
            if (e.appliesTo() != null && !e.appliesTo().isBlank()) {
                tags.add("lands on " + e.appliesTo());
            }
            out.add(new ReadyMade(ReadyMade.Kind.ITEM, e.id(), e.id().replace('_', ' '), e.aka(),
                    tags, e.summary(), "make_item effect:\"" + e.id() + "\"", itemDetail(e), e.wishes()));
        }
        return out;
    }

    /** One catalogue effect as make_item's description has always printed it. */
    public static String itemDetail(ItemCatalogue.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.id()).append(" — ").append(e.summary())
          .append("\n      lands on: ").append(e.who());
        if (!e.wishes().isEmpty()) {
            sb.append("\n      e.g. \"").append(e.wishes().get(0)).append('"');
        }
        for (String p : e.params()) {
            // The doc AND the ask. The doc says what the value is; the ask says how to find
            // it in what the child actually typed. Printing only the first is what sent the
            // last catalogue's agent probing the tool with FEEL="?" to make the error
            // message tell it — good documentation nobody can read is documentation nobody has.
            sb.append("\n        - ").append(p).append(": ").append(e.paramDocs().get(p))
              .append("\n            → from the wish: ").append(e.paramAsks().get(p));
        }
        return sb.toString();
    }

    // ---- (b) creature templates ----------------------------------------------------------------

    /** The starter species in the jar ({@code starter-library/species/<id>/species.json}). */
    public static List<ReadyMade> starterCreatures() {
        List<ReadyMade> out = new ArrayList<>();
        Map<String, String> labels = starterLabels();
        String index = resource("/starter-library/index.txt");
        if (index == null) {
            return out;
        }
        for (String line : index.split("\n")) {
            String l = line.strip();
            if (!l.startsWith("species/") || !l.endsWith("/species.json")) {
                continue;
            }
            String json = resource("/starter-library/" + l);
            if (json == null) {
                continue;
            }
            JsonObject s = JsonParser.parseString(json).getAsJsonObject();
            String id = s.get("id").getAsString();
            String name = s.has("name") ? s.get("name").getAsString() : id;
            List<String> powers = new ArrayList<>();
            if (s.has("powers") && s.get("powers").isJsonArray()) {
                for (JsonElement p : s.getAsJsonArray("powers")) {
                    powers.add(p.getAsString().toLowerCase(Locale.ROOT));
                }
            }
            String label = labels.get(id);
            String purpose = (label != null ? label + " " : "A " + name.toLowerCase(Locale.ROOT)
                    + ", fully built and animated. ")
                    + (powers.isEmpty() ? "" : "(" + String.join(", ", powers) + ")");
            out.add(new ReadyMade(ReadyMade.Kind.CREATURE, id, name, List.of(), powers, purpose,
                    "sculpt_creature start_from:\"" + id + "\" (send only what changes; a part at a new size is a reshape line, not a new box)", "",
                    label == null ? List.of() : List.of(label)));
        }
        return out;
    }

    /** {@code starter_pet_dragon}'s label, keyed by the species its first command spawns. */
    private static Map<String, String> starterLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        for (JsonObject t : starterTemplates()) {
            for (JsonElement c : t.getAsJsonArray("commands")) {
                String cmd = c.getAsString();
                if (cmd.startsWith("wishmob spawn ")) {
                    m.put(cmd.substring("wishmob spawn ".length()).strip(), t.get("label").getAsString());
                }
            }
        }
        return m;
    }

    // ---- (a) related prior wishes --------------------------------------------------------------

    /**
     * One thing the server already made, as an (a) entry. {@code what} is its kind in a word
     * ({@code creature}, {@code item}, {@code building}, {@code button}); {@code words} is the
     * sentence that made it, or its note — never a bare id, which is how 38 of 52 learned buttons
     * were offered.
     */
    public static ReadyMade priorWish(String what, String id, String name, String words, String use) {
        String w = words == null || words.isBlank() ? name : words;
        return new ReadyMade(ReadyMade.Kind.WISH, id, name, List.of(), List.of(what),
                what + ": " + w, use, "", words == null || words.isBlank() ? List.of() : List.of(words));
    }

    /**
     * <b>How to reuse one of HIS things</b>, worded for both readings of a wish about it.
     *
     * <p>The suggestion used to say only {@code start_from:"sky_castle"}, and the tools teach that
     * {@code start_from} on its own CHANGES the thing he has. So a wish for a NEW one like his — an
     * ice castle like his floating one, a robot kitten like his robot dog — read as risky, and the
     * worker built it from nothing (pass 2: 4 of 5 Opus misses on (a)). Both calls are spelled out,
     * because which one the wish means is the worker's call, never this line's.
     *
     * @param what {@code creature}, {@code item} or {@code building} (anything else: inspect first)
     */
    public static String priorUse(String what, String id) {
        String q = "\"" + id + "\"";
        return switch (what == null ? "" : what) {
            case "creature" -> "a NEW one like it: sculpt_creature id:<a new id> start_from:" + q
                    + " (his stays as it is) · to CHANGE his: the same id · the very same one again: "
                    + "run_command \"wishmob spawn " + id + "\"";
            case "item" -> "a NEW one like it: make_item start_from:" + q + " as_new:true (he keeps "
                    + "both) · to CHANGE his: make_item start_from:" + q + " alone";
            case "building" -> "a NEW one like it: build_structure id:<a new id> start_from:" + q
                    + " (his stays where it is) · to CHANGE his: id:" + q + " start_from:" + q;
            default -> "inspect it, then change that one";
        };
    }

    /** The grown-ups' starter buttons ({@code starter-library/templates.json}) as (a) entries. */
    public static List<ReadyMade> starterButtons() {
        List<ReadyMade> out = new ArrayList<>();
        for (JsonObject t : starterTemplates()) {
            String id = t.get("id").getAsString();
            String phrase = t.has("phrase") ? t.get("phrase").getAsString() : t.get("label").getAsString();
            out.add(priorWish("button", id, t.get("label").getAsString(), phrase,
                    "run_command \"" + t.getAsJsonArray("commands").get(0).getAsString() + "\""));
        }
        return out;
    }

    private static List<JsonObject> starterTemplates() {
        List<JsonObject> out = new ArrayList<>();
        String json = resource("/starter-library/templates.json");
        if (json == null) {
            return out;
        }
        JsonArray arr = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("templates");
        for (JsonElement el : arr) {
            out.add(el.getAsJsonObject());
        }
        return out;
    }

    private static String resource(String path) {
        try (InputStream in = ReadyMadeSources.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
