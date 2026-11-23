package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The Apprentice reaches "Turn things into…" however a child says it</b>: the synonym
 * suite {@code corpus/apprentice/turn-into.jsonl} through the REAL matcher on the REAL tree. Each row's top tile must be
 * the power with exactly those From groups and that To; a CONTROL row ({@code not:turn_into}) must not have a turn-into
 * top tile. Run on the reviewed Builder here, and with the same expectations on a house world's Builder by
 * {@code ApprenticeTurnIntoHouseWorldTest} (every To target is offered on both). Every top tile is then PRESSED through
 * the real menus and checked against the world's allowlist: a make the Builder could not do by hand is a failure here.
 *
 * <p>A miss is fixed in the matcher or the words, never by special-casing a sentence. These wishes are tests: they are
 * never added to the trained layer's examples (docs/APPRENTICE.md).
 */
class ApprenticeTurnIntoTest {

    static final Path SUITE = Path.of("corpus", "apprentice", "turn-into.jsonl");

    record Row(String id, String wish, String power, Set<String> from, String to) {
    }

    static List<Row> rows() throws IOException {
        List<Row> out = new ArrayList<>();
        for (String line : Files.readAllLines(SUITE)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject j = JsonParser.parseString(line).getAsJsonObject();
            if (!j.has("id")) {
                continue;   // the _comment row
            }
            JsonObject e = j.getAsJsonObject("expect");
            Set<String> from = new TreeSet<>();
            if (e.has("from")) {
                e.getAsJsonArray("from").forEach(x -> from.add(x.getAsString()));
            }
            out.add(new Row(j.get("id").getAsString(), j.get("wish").getAsString(), e.get("power").getAsString(), from,
                    e.has("to") ? e.get("to").getAsString() : ""));
        }
        return out;
    }

    static boolean turnInto(Apprentice.Pick p) {
        return p.kind().equals("item") && p.first("power").equals("itempower:" + BuilderTurnInto.POWER);
    }

    static Set<String> from(BuilderTree tree, Apprentice.Pick p) {
        Set<String> out = new TreeSet<>();
        Apprentice.filled(tree, p).picks().getOrDefault(BuilderTurnInto.FROM_SLOT, List.of())
                .forEach(id -> out.add(id.substring(BuilderTurnInto.FROM_PREFIX.length())));
        return out;
    }

    static String to(BuilderTree tree, Apprentice.Pick p) {
        String id = Apprentice.filled(tree, p).first(BuilderTurnInto.TO_SLOT);
        return id.isEmpty() ? "" : id.substring(BuilderTurnInto.TO_PREFIX.length());
    }

    /** What went wrong with each row on this Builder; empty when every row is right. */
    static List<String> run(BuilderAllowlist allow) throws IOException {
        BuilderTree tree = BuilderTree.of(allow, LibraryPresets.SHIPPED);
        Apprentice a = new Apprentice(tree, Apprentice.english());
        List<String> wrong = new ArrayList<>();
        List<Row> rows = rows();
        assertEquals(44, rows.size(), "the owner's suite is 44 wishes");
        for (Row r : rows) {
            Apprentice.Answer ans = a.match(r.wish());
            Apprentice.Pick top = ans.offers().isEmpty() ? null : ans.offers().get(0);
            String got = top == null ? "no tiles" : Apprentice.key(Apprentice.filled(tree, top));
            if (top != null) {
                try {
                    BuilderSpec spec = ApprenticeMake.spec(allow, LibraryPresets.SHIPPED, tree, top);
                    if (!allow.problems(spec).isEmpty()) {
                        wrong.add(r.id() + " '" + r.wish() + "': the world refuses the top tile: " + allow.problems(spec));
                    }
                } catch (RuntimeException e) {
                    wrong.add(r.id() + " '" + r.wish() + "': the menus refuse the top tile " + got + ": " + e.getMessage());
                }
            }
            if (r.power().startsWith("not:")) {
                if (top != null && turnInto(top)) {
                    wrong.add(r.id() + " '" + r.wish() + "' (a control): the top tile is turn-into: " + got);
                }
                continue;
            }
            if (top == null || !turnInto(top)) {
                wrong.add(r.id() + " '" + r.wish() + "': the top tile is not turn-into: " + got);
                continue;
            }
            if (!from(tree, top).equals(r.from())) {
                wrong.add(r.id() + " '" + r.wish() + "': From " + from(tree, top) + ", want " + r.from() + " (" + got + ")");
            }
            if (!to(tree, top).equals(r.to())) {
                wrong.add(r.id() + " '" + r.wish() + "': To " + to(tree, top) + ", want " + r.to() + " (" + got + ")");
            }
        }
        return wrong;
    }

    @Test
    @DisplayName("#2216: every wish of the synonym suite reaches 'Turn things into…' with its From and To (the reviewed Builder)")
    void theSuiteOnTheReviewedBuilder() throws IOException {
        List<String> wrong = run(BuilderAllowlist.REVIEWED);
        assertEquals(List.of(), wrong, wrong.size() + " of 44 wrong. Fix the matcher (Apprentice + tools/apprentice_match.py)"
                + " or the words (BuilderTurnInto), never a sentence");
    }

    @Test
    @DisplayName("#2220 §4: a material the pages do not list, named exactly, is an installed block as From or To, made by the menus")
    void aMaterialThePagesDoNotListIsFoundByName() {
        BuilderAllowlist allow = BuilderAllowlist.REVIEWED;
        BuilderTree tree = BuilderTree.of(allow, LibraryPresets.SHIPPED);
        Apprentice a = new Apprentice(tree, Apprentice.english());
        record Want(String wish, String from, String to) {
        }
        List<String> wrong = new ArrayList<>();
        for (Want w : List.of(new Want("turn dirt into honey blocks", "dirt_grass", "found:minecraft:honey_block"),
                new Want("a wand that turns tuff into gold", "found:minecraft:tuff", "gold"),
                new Want("turn everything into cake", "any_block", "found:minecraft:cake"))) {
            Apprentice.Answer ans = a.match(w.wish());
            Apprentice.Pick top = ans.offers().isEmpty() ? null : ans.offers().get(0);
            if (top == null || !turnInto(top) || !from(tree, top).equals(Set.of(w.from())) || !to(tree, top).equals(w.to())) {
                wrong.add(w.wish() + " → " + (top == null ? "no tiles" : Apprentice.key(Apprentice.filled(tree, top))));
                continue;
            }
            BuilderSpec spec = ApprenticeMake.spec(allow, LibraryPresets.SHIPPED, tree, top);   // the menus take it
            if (!allow.problems(spec).isEmpty()) {
                wrong.add(w.wish() + ": the world refuses it: " + allow.problems(spec));
            }
        }
        // Blocks only, never a guess: "chickens" is no raw chicken, and a person is never turned into a block
        for (String control : List.of("a sword that turns zombies into chickens", "a wand that turns me into a chicken")) {
            Apprentice.Answer ans = a.match(control);
            Apprentice.Pick top = ans.offers().isEmpty() ? null : ans.offers().get(0);
            if (top != null && Apprentice.key(Apprentice.filled(tree, top)).contains(BuilderTurnInto.FOUND)) {
                wrong.add(control + " (a control) found " + Apprentice.key(Apprentice.filled(tree, top)));
            }
        }
        assertEquals(List.of(), wrong);
    }
}
