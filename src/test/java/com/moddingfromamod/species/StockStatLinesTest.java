package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.vanilla.RigInstinct;

/**
 * <b>Every stock creature has a reviewed stat line</b> (#99). The 55 ready-made creatures (the starter library's 11 and
 * the Builder's 44 bodies) each have a row here: health, attack damage, armour, knockback resistance. Before #99 most of
 * them had numbers nobody chose: 0 damage on a rock golem set to guard, 30 health on a butterfly, the duck on the
 * read-in default 3. A change to a stock creature's numbers is now a change to this table, made on purpose.
 *
 * <p>What every row must keep:
 * <ul>
 *   <li>its files say it, explicitly: both {@code species.json} (what spawns) and {@code sculpt.json} (what the
 *       Builder sends) carry the row's health and damage, so nothing is left on {@code Species.fromJson}'s default;</li>
 *   <li>it fits the band for its size ({@link PowerBand}, by its sculpt's solid volume and scale): a creature that looks
 *       like a fighter can fight, a gentle one is not secretly a tank;</li>
 *   <li>anything with a fighting instinct hits for more than 0 (a melee hit of 0 lands nothing), and every creature
 *       but a vehicle carries some damage, so its moves and any world that enlists it work; a friendly one still never
 *       attacks;</li>
 *   <li>a vehicle hits for nothing (and its health is how much it takes, not a size: it is not banded).</li>
 * </ul>
 */
class StockStatLinesTest {

    /** health, damage, armour, knockback resistance. */
    record Line(double health, double damage, double armor, double knockback) {
    }

    private static Line line(double h, double d) {
        return new Line(h, d, 0, 0);
    }

    static final Map<String, Line> TABLE = new LinkedHashMap<>();

    static {
        // ── the starter library
        TABLE.put("cat", line(12, 3));
        TABLE.put("chicken", line(6, 1));
        TABLE.put("fire_imp", line(80, 6));               // kept
        TABLE.put("frog", line(12, 2));
        TABLE.put("helper_caller", line(80, 4));          // Captain Puff, kept
        TABLE.put("pet_dragon", line(60, 6));
        TABLE.put("sheep", line(10, 1));
        TABLE.put("snail", new Line(12, 1, 8, 0));
        TABLE.put("trex", new Line(200, 12, 0, 0.8));     // the Giant T-Rex
        TABLE.put("turtle", new Line(40, 2, 10, 0));
        TABLE.put("wolf", line(40, 4));                   // kept
        // ── the Builder's bodies
        TABLE.put("bear", line(30, 6));
        TABLE.put("boat", line(80, 0));
        TABLE.put("butterfly", line(8, 1));
        TABLE.put("car", line(40, 0));
        TABLE.put("crab", new Line(20, 3, 6, 0));
        TABLE.put("dolphin", line(20, 3));
        TABLE.put("duck", line(8, 1));
        TABLE.put("eagle", line(16, 4));
        TABLE.put("elephant", new Line(80, 8, 0, 0.6));
        TABLE.put("flying_saucer", line(100, 0));
        TABLE.put("ghost", line(16, 3));
        TABLE.put("gnome", line(8, 1));
        TABLE.put("griffin", line(60, 7));
        TABLE.put("hamster", line(6, 1));
        TABLE.put("helicopter", line(100, 0));
        TABLE.put("horse", line(30, 3));
        TABLE.put("hot_air_balloon", line(100, 0));
        TABLE.put("jellyfish", line(12, 2));
        TABLE.put("lion", line(40, 7));
        TABLE.put("long_neck_dinosaur", new Line(160, 8, 0, 0.8));
        TABLE.put("monkey", line(18, 3));
        TABLE.put("motorbike", line(60, 0));
        TABLE.put("octopus", line(18, 3));
        TABLE.put("ogre", line(150, 12));
        TABLE.put("owl", line(16, 3));
        TABLE.put("penguin", line(10, 1));
        TABLE.put("person", line(20, 3));
        TABLE.put("phoenix", line(60, 6));
        TABLE.put("pig", line(10, 1));
        TABLE.put("plane", line(100, 0));
        TABLE.put("rabbit", line(4, 1));
        TABLE.put("raptor", line(40, 5));                 // kept
        TABLE.put("robot", new Line(40, 5, 6, 0));
        TABLE.put("rock_golem", new Line(100, 15, 0, 1.0));
        TABLE.put("rocket", line(100, 0));
        TABLE.put("shark", line(60, 6));                  // kept
        TABLE.put("snake", line(12, 2));
        TABLE.put("submarine", line(80, 0));
        TABLE.put("t_rex", new Line(200, 12, 0, 0.8));
        TABLE.put("tank", line(100, 0));
        TABLE.put("triceratops", new Line(120, 10, 8, 0.8));
        TABLE.put("truck", line(100, 0));
        TABLE.put("whale", line(200, 6));
        TABLE.put("yeti", line(80, 9));
    }

    /** One stock creature's two files. */
    record Stock(String id, JsonObject species, JsonObject sculpt) {
        boolean vehicle() {
            return species.has("vehicle") && species.get("vehicle").getAsBoolean();
        }

        double volume() {
            List<double[]> boxes = new ArrayList<>();
            for (JsonElement p : sculpt.getAsJsonArray("parts")) {
                JsonArray f = p.getAsJsonObject().getAsJsonArray("from");
                JsonArray t = p.getAsJsonObject().getAsJsonArray("to");
                boxes.add(new double[] {f.get(0).getAsDouble(), f.get(1).getAsDouble(), f.get(2).getAsDouble(),
                        t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble()});
            }
            return PowerBand.volume(boxes, species.get("scale").getAsDouble());
        }
    }

    static List<Stock> stock() throws Exception {
        List<Stock> out = new ArrayList<>();
        for (String id : ids("starter-library/index.txt", true)) {
            out.add(new Stock(id, json("starter-library/species/" + id + "/species.json"),
                    json("starter-library/species/" + id + "/sculpt.json")));
        }
        for (String id : ids("builder-bodies/index.txt", false)) {
            out.add(new Stock(id, json("builder-bodies/species/" + id + "/species.json"),
                    json("builder-bodies/species/" + id + "/sculpt.json")));
        }
        return out;
    }

    private static List<String> ids(String index, boolean byFile) throws Exception {
        List<String> out = new ArrayList<>();
        for (String l : text(index).split("\n")) {
            String t = l.trim();
            if (byFile && t.startsWith("species/") && t.endsWith("/species.json")) {
                out.add(t.substring("species/".length(), t.length() - "/species.json".length()));
            } else if (!byFile && t.startsWith("species/") && t.indexOf('/', "species/".length()) < 0) {
                out.add(t.substring("species/".length()));
            }
        }
        return out;
    }

    @Test
    @DisplayName("every stock creature has a row, and every row a creature")
    void theTableNamesEveryStockCreature() throws Exception {
        TreeSet<String> have = new TreeSet<>();
        stock().forEach(s -> have.add(s.id()));
        TreeSet<String> missing = new TreeSet<>(have);
        missing.removeAll(TABLE.keySet());
        TreeSet<String> extra = new TreeSet<>(TABLE.keySet());
        extra.removeAll(have);
        assertTrue(have.size() == 55, "55 stock creatures (11 starters, 44 bodies); found " + have.size());
        assertTrue(missing.isEmpty() && extra.isEmpty(),
                "a stock creature with no reviewed line: " + missing + "; a line with no creature: " + extra);
    }

    @Test
    @DisplayName("both files of every stock creature say its reviewed line, explicitly")
    void theFilesSayTheLine() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Stock s : stock()) {
            Line want = TABLE.get(s.id());
            if (want == null) {
                continue;
            }
            check(wrong, s.id() + "/species.json", s.species(), "health", want.health());
            check(wrong, s.id() + "/species.json", s.species(), "damage", want.damage());
            check(wrong, s.id() + "/species.json", s.species(), "armor", want.armor());
            check(wrong, s.id() + "/species.json", s.species(), "knockbackResistance", want.knockback());
            check(wrong, s.id() + "/sculpt.json", s.sculpt(), "health", want.health());
            check(wrong, s.id() + "/sculpt.json", s.sculpt(), "damage", want.damage());
        }
        assertTrue(wrong.isEmpty(), "a stock file that does not say its reviewed line (a missing number is read as a "
                + "default nobody chose):\n  " + String.join("\n  ", wrong));
    }

    @Test
    @DisplayName("every stock line fits the band for its size; a fighter hits; a vehicle does not")
    void everyLineFitsItsBand() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Stock s : stock()) {
            Line l = TABLE.get(s.id());
            if (l == null) {
                continue;
            }
            Species sp = Species.fromJson(s.species());
            if (s.vehicle()) {
                if (l.damage() != 0) {
                    wrong.add(s.id() + ": a vehicle hits for " + l.damage() + ", not 0");
                }
                continue;
            }
            PowerBand.Band band = PowerBand.forVolume(s.volume());
            if (!band.healthFits(l.health())) {
                wrong.add(String.format("%s: health %s is outside the %s band's %s-%s (solid volume %.2f)", s.id(),
                        l.health(), band.name(), band.minHealth(), band.maxHealth(), s.volume()));
            }
            if (!band.damageFits(l.damage())) {
                wrong.add(String.format("%s: damage %s is outside the %s band's %s-%s (solid volume %.2f)", s.id(),
                        l.damage(), band.name(), band.minDamage(), band.maxDamage(), s.volume()));
            }
            if (RigInstinct.of(sp).fights() && sp.damage() <= 0) {
                wrong.add(s.id() + ": has a fighting instinct and a melee hit of 0, which lands nothing");
            }
            if (sp.damage() <= 0) {
                wrong.add(s.id() + ": a creature (not a vehicle) with 0 damage: its moves and any world that enlists it "
                        + "do nothing; a friendly one still never attacks");
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n  ", wrong));
    }

    @Test
    @DisplayName("a world's copy of a library creature takes the shipped numbers and keeps everything else")
    void aCopyTakesTheShippedNumbersOnly() {
        Species copy = Species.fromJson(JsonParser.parseString("{\"id\":\"pet_dragon\",\"name\":\"Pet Dragon\","
                + "\"powers\":[\"friendly\",\"fireproof\"],\"base\":\"companion\",\"health\":60,\"damage\":0,"
                + "\"scale\":1.2,\"modelKey\":\"pet_dragon\"}").getAsJsonObject());
        JsonObject shipped = JsonParser.parseString("{\"health\":60,\"damage\":6,\"armor\":2,\"knockbackResistance\":0.5,"
                + "\"name\":\"Somebody Else\",\"powers\":[]}").getAsJsonObject();
        Species now = copy.withShippedStats(shipped);
        assertTrue(now.damage() == 6 && now.armor() == 2 && now.knockbackResistance() == 0.5 && now.health() == 60,
                "the shipped numbers: " + now);
        assertTrue(now.name().equals("Pet Dragon") && now.powers().equals(copy.powers()) && now.base() == copy.base()
                && now.scale() == copy.scale() && now.modelKey().equals(copy.modelKey()), "nothing else moves: " + now);
        assertTrue(copy.withShippedStats(new JsonObject()).equals(copy), "a record stating no numbers changes nothing");
        assertTrue(copy.withShippedStats(null).equals(copy));
    }

    private static void check(List<String> wrong, String where, JsonObject o, String key, double want) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            // armour and knockback of 0 may be left out of a sculpt; a species file states every number
            wrong.add(where + ": no " + key + " (want " + want + ")");
        } else if (Math.abs(o.get(key).getAsDouble() - want) > 1e-9) {
            wrong.add(where + ": " + key + " " + o.get(key).getAsDouble() + ", the reviewed line says " + want);
        }
    }

    private static JsonObject json(String path) throws Exception {
        return JsonParser.parseString(text(path)).getAsJsonObject();
    }

    private static String text(String path) throws Exception {
        try (InputStream in = StockStatLinesTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
