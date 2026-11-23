package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * <b>: a wish and its parts, on what the agent ACTUALLY made</b> — every {@code made} record
 * of real play on a live server (2026-09-25 .. 10-03, ids only), through the same rule
 * {@code LearnedTemplates.recordParts} stores. an earlier issue's own tests were fixtures; replayed here, its
 * rule called a creature and its own spawn two parts, a creature and its fire-breath pack three,
 * three versions of one fork three, and the wish that parked cars in the garage a wish of the
 * garage and the carwash.
 */
class WishPartsRealDataTest {

    private static final String FIXTURE = "/wish-parts/cloud1-made-2026-10-03.json";

    private record Rec(String wish, String source, List<WishRun.Made> made, Set<WishRun.Made> removed,
                       Set<WishRun.Made> before, List<TaughtJoin.Taught> taught) {
    }

    private static final List<Rec> RECORDS = new ArrayList<>();

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = WishPartsRealDataTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(in, FIXTURE + " is on the test classpath");
            JsonObject doc = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (JsonElement el : doc.getAsJsonArray("records")) {
                JsonObject o = el.getAsJsonObject();
                List<TaughtJoin.Taught> taught = new ArrayList<>();
                if (o.has("taught")) {
                    for (JsonElement t : o.getAsJsonArray("taught")) {
                        List<String> cmds = new ArrayList<>();
                        t.getAsJsonObject().getAsJsonArray("commands").forEach(c -> cmds.add(c.getAsString()));
                        taught.add(new TaughtJoin.Taught(t.getAsJsonObject().get("id").getAsString(), cmds));
                    }
                }
                RECORDS.add(new Rec(o.get("wish").getAsString(), o.get("source").getAsString(),
                        made(o.getAsJsonArray("made")), new LinkedHashSet<>(made(o.getAsJsonArray("removed"))),
                        new LinkedHashSet<>(made(o.getAsJsonArray("existedBefore"))), taught));
            }
        }
    }

    private static List<WishRun.Made> made(JsonArray a) {
        List<WishRun.Made> out = new ArrayList<>();
        if (a != null) {
            for (JsonElement el : a) {
                out.add(new WishRun.Made(el.getAsJsonObject().get("kind").getAsString(),
                        el.getAsJsonObject().get("id").getAsString()));
            }
        }
        return out;
    }

    private static List<WishRun.Made> partsOf(Rec r) {
        return WishParts.of(r.made(), r.removed(), r.before(), null);
    }

    private static String show(List<WishRun.Made> parts) {
        List<String> out = new ArrayList<>();
        parts.forEach(p -> out.add(p.kind() + " " + p.id()));
        return String.join(", ", out);
    }

    /** What each wish of more than one {@code made} entry really is — the issue's table. */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        // Real parts: two or more distinct new things.
        EXPECTED.put("b65bb198", "item cactus_flower_plain, creature cactus_dog");   // a test wish's
        EXPECTED.put("7d13bc7f", "build pickleball_court, creature pickleball_player");
        EXPECTED.put("b113cf81", "item diamond_map_plain, item cave_compass_locate");
        EXPECTED.put("e544ae70", "item vanilla_cappuccino_heal, item cappuccino_machine_convert");
        EXPECTED.put("55dc6ddf", "build tropical_oasis, item tropical_oasis_drink_heal, "
                + "item tropical_oasis_bar_convert");
        EXPECTED.put("f998e7c3", "build hotwheels_garage, creature hotwheels_trex");
        // Not parts.
        EXPECTED.put("1687741d", "creature spikor");         // + its fire pack and a llama it changed
        EXPECTED.put("779b6631", "creature pegasus");        // + mob @pegasus, its own spawn
        EXPECTED.put("203d5953", "creature submarine");      // + mob @submarine
        EXPECTED.put("dbed69d2", "item cake_fork_heal");     // two earlier versions removed in-wish
        EXPECTED.put("0bc44a2f", "");                        // parked cars in two existing builds
    }

    @Test
    void theRealWishesSplitIntoTheWishAndItsParts() {
        Map<String, String> got = new TreeMap<>();
        for (Rec r : RECORDS) {
            if (r.made().size() > 1) {
                got.put(r.wish(), show(partsOf(r)));
            }
        }
        assertEquals(new TreeMap<>(EXPECTED), got,
                "every real wish that recorded more than one made entry, as wish → its parts");
    }

    @Test
    void theCountsTheIssueQuotes() {
        assertEquals(47, RECORDS.size(), "47 real wishes made something");
        long one = RECORDS.stream().filter(r -> r.made().size() == 1).count();
        assertEquals(36, one, "36 recorded one made entry");
        long multi = RECORDS.stream().filter(r -> partsOf(r).size() >= 2).count();
        assertEquals(6, multi, "after the rule, 6 wishes are wishes of several parts — not 11");
    }

    @Test
    void aWishOfOneMadeEntryNeverGrowsAPartFromAnEditOrAMob() {
        for (Rec r : RECORDS) {
            if (r.made().size() == 1) {
                assertTrue(partsOf(r).size() <= 1, r.wish() + " made one thing");
            }
        }
        // ...and the mob-only and edit-only ones record no part at all.
        for (String w : List.of("8e552c2f", "e3423f8f", "4fa51570", "42173367")) {
            Rec r = RECORDS.stream().filter(x -> x.wish().equals(w)).findFirst().orElseThrow();
            assertEquals("", show(partsOf(r)), w + ": a changed mob or an edit is not a new thing");
        }
    }

    /**
     * Half 2: every genuine multi-part wish is RE-WISHABLE, part by part — or this change refuses
     * the finish once and says exactly which commands to teach. Before, three of the six
     * ended with no recipe (or a short one) and nothing said.
     */
    @Test
    void everyGenuineMultiPartWishIsRemakablePartByPartOrIsSentBackOnce() {
        int remakable = 0;
        int sentBack = 0;
        for (Rec r : RECORDS) {
            List<WishRun.Made> parts = partsOf(r);
            if (parts.size() < 2) {
                continue;
            }
            List<String> taughtCommands = new ArrayList<>();
            r.taught().forEach(t -> taughtCommands.addAll(t.commands()));
            String refusal = WishParts.teachRefusal(parts, !r.taught().isEmpty(), taughtCommands);
            if (refusal == null) {
                // Saved: the recipe (a WISH, two or more parts) remakes each part ALONE.
                TaughtJoin.Taught recipe = new TaughtJoin.Taught(r.taught().get(0).id(), taughtCommands,
                        "", ids(parts));
                assertTrue(TaughtJoin.isWish(recipe), r.wish() + " is a wish of several parts");
                for (WishRun.Made p : parts) {
                    List<String> alone = TaughtJoin.commandsFor(recipe.commands(), p.id());
                    assertEquals(List.of(TaughtJoin.key(p.id())), TaughtJoin.artifactsNamed(alone),
                            r.wish() + ": the part " + p.id() + " is remade by its own command only");
                    assertEquals(TaughtJoin.partRecipe(recipe.id(), p.id()),
                            TaughtJoin.recipeFor(p.id(), List.of(recipe)),
                            r.wish() + ": the part's tile carries the part recipe");
                }
                remakable++;
            } else {
                // Not saved, or saved short: the refusal names a command for every missing part,
                // and those commands remake each part alone.
                List<String> suggested = commandsIn(refusal);
                List<String> all = new ArrayList<>(taughtCommands);
                all.addAll(suggested);
                assertEquals(List.of(), WishParts.unremakable(parts, all),
                        r.wish() + ": following the refusal leaves no part without a command — " + refusal);
                sentBack++;
            }
        }
        assertEquals(3, remakable, "coffee machine, oasis and garage were saved whole");
        assertEquals(3, sentBack, "the test wish's dog+flower (taught short), the court and the "
                + "compass+map (never taught) are now sent back once with the lines to teach");
    }

    @Test
    void aCreatureAndItsOwnSpawnIsNotAWish() {
        Rec pegasus = RECORDS.stream().filter(r -> r.wish().equals("779b6631")).findFirst().orElseThrow();
        assertNull(WishParts.teachRefusal(partsOf(pegasus), true, pegasus.taught().get(0).commands()),
                "the pegasus is one thing, and its recipe remakes it");
    }

    private static List<String> ids(List<WishRun.Made> parts) {
        List<String> out = new ArrayList<>();
        parts.forEach(p -> out.add(p.id()));
        return out;
    }

    private static List<String> commandsIn(String refusal) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"(wish[a-z]+ [^\"]+)\"").matcher(refusal);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
