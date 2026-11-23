package com.moddingfromamod.library;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * <b>The rules every shipped starter wish follows</b>, read off the shipped files.
 *
 * <p>The starter wishes are one pool on the panel, in {@code templates.json} order — the server
 * library's order. What this guards is invisible on a real panel until it is wrong: two entries with one id
 * silently shadow each other, and a canned wish taken from a class's HOLDOUT coaches the agent on
 * the very wishes it is later graded on.
 */
class StarterWishesTest {

    private static final Path SRC = Path.of("src", "main", "resources", "starter-library");
    private static final Path HOLDOUT = Path.of("corpus", "holdout");

    private static List<JsonObject> entries() throws Exception {
        JsonObject root = JsonParser.parseString(
                Files.readString(SRC.resolve("templates.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("templates")) {
            out.add(el.getAsJsonObject());
        }
        return out;
    }

    @Test
    void idsAreUniqueAndEveryEntryHasALabel() throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonObject e : entries()) {
            String id = e.get("id").getAsString();
            assertTrue(ids.add(id), "templates.json names '" + id + "' twice");
            assertTrue(e.has("label") && !e.get("label").getAsString().isBlank(),
                    id + " has no label — its button would read as its id");
        }
    }

    /**
     * Rows are retired: the panel is one pool in file order, so a {@code "row"} field
     * would be a promise nothing reads. Grouping, if it comes back, is the operator tools'.
     */
    @Test
    void noEntryNamesARow() throws Exception {
        for (JsonObject e : entries()) {
            assertFalse(e.has("row"), e.get("id").getAsString()
                    + " names a row, but the panel has no rows — order is file order");
        }
    }

    /**
     * <b>No canned wish is a HOLDOUT wish</b> (an earlier issue's C17). A canned wish is a lesson the agent
     * sees on every panel; featuring a held-out one would coach it on the very wishes it is graded
     * on. It reads EVERY {@code *.jsonl} in {@code corpus/holdout/}, comparing each entry's label,
     * phrase and commands against every string in every holdout row (and says so loudly, rather
     * than passing silently, if the directory is ever absent).
     */
    @Test
    void noCannedWishIsInAHoldout() throws Exception {
        if (!Files.isDirectory(HOLDOUT)) {
            // VACUOUS PASS, deliberately loud: there is no holdout to leak into yet.
            System.out.println("StarterWishesTest: corpus/holdout/ does not exist yet — the holdout "
                    + "overlap check passes vacuously");
            return;
        }
        Set<String> held = new LinkedHashSet<>();
        int files = 0;
        try (var list = Files.list(HOLDOUT)) {
            for (Path f : list.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                files++;
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    collectStrings(JsonParser.parseString(line), held);
                }
            }
        }
        System.out.println("StarterWishesTest: read " + files + " holdout file(s), "
                + held.size() + " distinct string(s)");
        for (JsonObject e : entries()) {
            List<String> mine = new ArrayList<>();
            for (String key : List.of("label", "phrase")) {
                if (e.has(key)) {
                    mine.add(e.get(key).getAsString());
                }
            }
            if (e.has("commands")) {
                e.getAsJsonArray("commands").forEach(c -> mine.add(c.getAsString()));
            }
            for (String s : mine) {
                assertFalse(held.contains(norm(s)), e.get("id").getAsString()
                        + " carries '" + s + "', which is in a holdout — a canned wish must never "
                        + "be a held-out one");
            }
        }
    }

    private static void collectStrings(JsonElement el, Set<String> out) {
        if (el == null || el.isJsonNull()) {
            return;
        }
        if (el.isJsonPrimitive()) {
            if (el.getAsJsonPrimitive().isString()) {
                String n = norm(el.getAsString());
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
        } else if (el.isJsonArray()) {
            el.getAsJsonArray().forEach(x -> collectStrings(x, out));
        } else {
            el.getAsJsonObject().entrySet().forEach(x -> collectStrings(x.getValue(), out));
        }
    }

    /** Case, punctuation and spacing do not make a held-out wish a different wish. */
    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9~@:_/. -]", "")
                .replaceAll("\\s+", " ").trim();
    }
}
