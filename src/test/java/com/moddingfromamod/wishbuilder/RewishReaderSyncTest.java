package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * the Java reader IS the reader that was measured. {@code tools/rewish_reader.py} is the reference, and
 * {@code src/test/resources/rewish/sync.json} holds its answer on every wish of the re-wish corpus (dev and the sealed
 * test, each also as its misspelt twin: {@code rewish-eval.py SPLIT [--typos] --fixture}). The Java answer must be the
 * same on every one: intent, thing, operators, command and band. A change to either reader that is not made to both
 * fails here by name; refresh the fixture only after changing BOTH.
 */
class RewishReaderSyncTest {

    @Test
    void javaReadsEveryCorpusWishAsPythonDoes() throws Exception {
        JsonArray cases;
        try (var in = getClass().getClassLoader().getResourceAsStream("rewish/sync.json")) {
            cases = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        }
        RewishReader reader = new RewishReader(BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED));
        List<String> wrong = new ArrayList<>();
        for (JsonElement e : cases) {
            JsonObject c = e.getAsJsonObject();
            List<RewishReader.Thing> things = new ArrayList<>();
            for (JsonElement t : c.getAsJsonArray("things")) {
                JsonObject o = t.getAsJsonObject();
                things.add(new RewishReader.Thing(o.get("id").getAsString(), o.get("what").getAsString(), o.get("name").getAsString()));
            }
            List<String> starters = new ArrayList<>();
            c.getAsJsonArray("starters").forEach(s -> starters.add(s.getAsString()));
            String last = c.get("last").isJsonNull() ? null : c.get("last").getAsString();
            RewishReader.Answer a = reader.read(c.get("wish").getAsString(), things, starters, last);
            String want = c.get("intent").getAsString() + "|" + (c.get("label").isJsonNull() ? null : c.get("label").getAsString())
                    + "|" + opsOf(c.getAsJsonArray("ops")) + "|" + c.get("verb").getAsString() + "|" + c.get("band").getAsString() + "|" + c.getAsJsonArray("candidates");
            String got = a.intent().name().toLowerCase() + "|" + a.label() + "|" + opsOf(a.ops()) + "|" + a.verb() + "|" + a.band().name() + "|" + candidates(a.candidates());
            if (!Objects.equals(want, got)) {
                wrong.add("'" + c.get("wish").getAsString() + "'\n   python " + want + "\n   java   " + got);
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " of " + cases.size() + " read differently:\n"
                + String.join("\n", wrong.subList(0, Math.min(20, wrong.size()))));
        assertTrue(cases.size() >= 1000, "the fixture holds " + cases.size() + " wishes");
    }

    /** Live play (an earlier issue's switch-on, Gate B): "a flying pig…" from a child with a pig named Piggy is a NEW pig. */
    @Test
    void aNameIntroducedByAIsANewThingNeverHis() {
        RewishReader reader = new RewishReader(BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED));
        List<RewishReader.Thing> his = List.of(new RewishReader.Thing("pig1", "creature", "Piggy"));
        RewishReader.Answer a = reader.read("a flying pig with a top hat", his, List.of(), "pig1");
        assertTrue(a.intent() == RewishReader.Intent.NEW && a.label() == null, "read as " + a);
        RewishReader.Answer b = reader.read("make piggy bigger", his, List.of(), "pig1");
        assertTrue(b.intent() == RewishReader.Intent.EDIT && "pig1".equals(b.label()), "his named pig is still his: " + b);
        // Gate B, the same day: the Builder had named his dino's id *_ride_*, and "ride" matched it.
        List<RewishReader.Thing> dino = List.of(new RewishReader.Thing("forest_rex_ride_1a2b", "ride", "Mossy Rex"));
        RewishReader.Answer c = reader.read("a green dino i can ride", dino, List.of(), "forest_rex_ride_1a2b");
        assertTrue(c.intent() == RewishReader.Intent.NEW && c.label() == null, "a wish starting \"a\" is new: " + c);
        RewishReader.Answer d = reader.read("make mossy rex bigger", dino, List.of(), "forest_rex_ride_1a2b");
        assertTrue(d.intent() == RewishReader.Intent.EDIT, "his named dino is still his: " + d);
    }

    /**
     * a word only a LOSING thing matched is still a word of the wish. In a long Gate B run his watch also lists
     * the things nobody owns (a gate's), and one with "gold" in its name or id swallowed the colour of "make goldie
     * gold": Goldie won by her whole name, "gold" was marked as part of a name anyway, and no change was left to read.
     */
    @Test
    void aWordOnlyALosingThingMatchedIsStillReadAsAChange() {
        RewishReader reader = new RewishReader(BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED));
        RewishReader.Thing goldie = new RewishReader.Thing("cat_goldie_1a2b", "creature", "Goldie");
        RewishReader.Answer alone = reader.read("make goldie gold", List.of(goldie), List.of(), "cat_goldie_1a2b");
        assertTrue(alone.band() == RewishReader.Band.SURE && "cat_goldie_1a2b".equals(alone.label()),
                "alone, Goldie is recoloured: " + alone);
        List<RewishReader.Thing> crowded = List.of(goldie,
                new RewishReader.Thing("gt_gold_block_tower", "building", "Shiny Tower"),
                new RewishReader.Thing("gt_bowl", "item", "Gold Bowl"));
        RewishReader.Answer a = reader.read("make goldie gold", crowded, List.of(), "gt_bowl");
        assertTrue(a.intent() == RewishReader.Intent.EDIT && a.band() == RewishReader.Band.SURE
                && "cat_goldie_1a2b".equals(a.label()) && a.ops().equals(alone.ops()),
                "beside other things with gold in them, Goldie is still recoloured: " + a);
    }

    /**
     * The same rule for "a …" (an earlier issue's fix, an earlier issue's lesson): whether the thing a wish names is introduced by "a" is asked
     * of the words the WINNING thing matched. It was asked of every word ANY thing matched, so in a long Gate B run (where
     * his watch also lists the things nobody owns) a word only a losing thing matched kept the winner his: "ride" in
     * another dino's *_ride_* id, after "can" and so not after "a", made "a green dino i can ride" an edit of his creature
     * named Dino, and a Crafter was told "The Builder can't change that about it" instead of reaching the Apprentice.
     */
    @Test
    void aLosingThingsWordNeverMakesANameAfterAHis() {
        RewishReader reader = new RewishReader(BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED));
        RewishReader.Thing dino = new RewishReader.Thing("cat_dino_1a2b", "creature", "Dino");
        RewishReader.Answer alone = reader.read("a green dino i can ride", List.of(dino), List.of(), "cat_dino_1a2b");
        assertTrue(alone.intent() == RewishReader.Intent.NEW && alone.label() == null, "control, alone: a new dino: " + alone);
        List<RewishReader.Thing> crowded = List.of(dino,
                new RewishReader.Thing("forest_rex_ride_9z9z", "ride", "Mossy Rex"));
        RewishReader.Answer a = reader.read("a green dino i can ride", crowded, List.of(), "cat_dino_1a2b");
        assertTrue(a.intent() == RewishReader.Intent.NEW && a.label() == null,
                "beside a thing whose id says ride, still a new dino: " + a);
        RewishReader.Answer named = reader.read("make dino green", crowded, List.of(), "forest_rex_ride_9z9z");
        assertTrue(named.intent() == RewishReader.Intent.EDIT && "cat_dino_1a2b".equals(named.label()),
                "his Dino named without \"a\" is still his: " + named);
    }

    /**
     * A thing named by its whole name is never ALSO named by a shorter thing whose whole name sits inside it. In a long
     * Gate B run his watch also lists the buildings nobody owns, and four gates build one called "Hut": its whole name
     * matched the "hut" of "make the mossy hut bigger" and tied his Mossy Hut, so the wish was offered as CLOSE ("Did you
     * mean…") instead of reaching the Magician (RewishGameTest's "a Magician building's size was changed by the Builder").
     */
    @Test
    void aShorterNameInsideHisThingsWholeNameNeverTiesIt() {
        RewishReader reader = new RewishReader(BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED));
        RewishReader.Thing mossy = new RewishReader.Thing("mossy_hut_1a2b", "building", "Mossy Hut");
        RewishReader.Thing goldie = new RewishReader.Thing("cat_goldie_1a2b", "creature", "Goldie");
        RewishReader.Answer alone = reader.read("make the mossy hut bigger", List.of(mossy, goldie), List.of(), "cat_goldie_1a2b");
        assertTrue(alone.band() == RewishReader.Band.SURE && "mossy_hut_1a2b".equals(alone.label()),
                "control, alone: his Mossy Hut: " + alone);
        RewishReader.Thing hut = new RewishReader.Thing("gt_owner_offline_hut", "building", "Hut");
        List<RewishReader.Thing> crowded = List.of(mossy, goldie, hut);
        for (String wish : List.of("make the mossy hut bigger", "make the mossy hut icy", "take me to my mossy hut")) {
            RewishReader.Answer a = reader.read(wish, crowded, List.of(), "cat_goldie_1a2b");
            RewishReader.Answer b = reader.read(wish, List.of(mossy, goldie), List.of(), "cat_goldie_1a2b");
            assertTrue(a.band() == RewishReader.Band.SURE && "mossy_hut_1a2b".equals(a.label())
                    && a.intent() == b.intent() && a.ops().equals(b.ops()) && a.verb().equals(b.verb()),
                    "beside a building called Hut, '" + wish + "' is still his Mossy Hut: " + a);
        }
        // The Hut itself is still the Hut when only it is named.
        RewishReader.Answer h = reader.read("make the hut bigger", crowded, List.of(), "cat_goldie_1a2b");
        assertTrue("gt_owner_offline_hut".equals(h.label()), "'the hut' alone is the Hut: " + h);
    }

    private static String candidates(List<String> c) {
        JsonArray a = new JsonArray();
        c.forEach(a::add);
        return a.toString();
    }

    private static String opsOf(JsonArray ops) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : ops) {
            JsonObject o = e.getAsJsonObject();
            String op = o.get("op").getAsString();
            out.add(op + ":" + o.get("slot").getAsString() + ":" + (op.equals("step") ? String.valueOf(o.get("by").getAsInt()) : o.get("value").getAsString()));
        }
        return out.toString();
    }

    private static String opsOf(List<BuilderEdit.Op> ops) {
        List<String> out = new ArrayList<>();
        for (BuilderEdit.Op o : ops) {
            String op = o.verb().name().toLowerCase();
            out.add(op + ":" + o.slot() + ":" + (op.equals("step") ? String.valueOf(o.by()) : o.value()));
        }
        return out.toString();
    }
}
