package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>How the Apprentice's tree fingerprint is taken and compared</b>: the same menus give the same fingerprint,
 * and an added, removed or changed pick or slot is named with the command to run. The check that the recorded
 * fingerprint ({@code corpus/apprentice/tree-fingerprint.json}) is THIS tree is {@code ApprenticeTreeFingerprintTest},
 * beside the Apprentice's corpora, trained layer and floors that it keeps in step.
 */
class ApprenticeTreeFingerprintDiffTest {

    static final Path RECORDED = Path.of("corpus", "apprentice", "tree-fingerprint.json");
    static final Path CURRENT = Path.of("build", "apprentice-tree-fingerprint.json");
    static final String COMMAND = "./tools/apprentice-refresh.sh";

    static JsonObject tree() {
        return BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED).toJson();
    }

    @Test
    @DisplayName("an added, removed or changed pick is named, with the command to run; an unchanged tree is silent")
    void aChangedTreeIsNamed() {
        JsonObject base = tree();
        JsonObject was = fingerprint(base);
        assertEquals(List.of(), diff(was, fingerprint(tree())), "two walks of the same menus give the same fingerprint");
        assertEquals(render(was), render(fingerprint(tree())), "and the same bytes");

        JsonObject added = base.deepCopy();
        JsonObject slot = firstSlot(added);
        JsonObject pick = slot.getAsJsonArray("entries").get(0).getAsJsonObject().deepCopy();
        pick.addProperty("id", "test:a_pick_nobody_offers");
        slot.getAsJsonArray("entries").add(pick);
        List<String> d = diff(was, fingerprint(added));
        assertEquals(1, d.size(), d.toString());
        assertTrue(d.get(0).startsWith("added   pick ") && d.get(0).endsWith("/test:a_pick_nobody_offers"), d.get(0));
        assertTrue(message(d).contains(COMMAND), "the failure prints the one command");

        JsonObject removed = base.deepCopy();
        JsonElement gone = firstSlot(removed).getAsJsonArray("entries").remove(0);
        d = diff(was, fingerprint(removed));
        assertTrue(d.stream().anyMatch(x -> x.startsWith("removed pick ")
                && x.endsWith("/" + gone.getAsJsonObject().get("id").getAsString())), d.toString());

        JsonObject reworded = base.deepCopy();
        firstSlot(reworded).getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("words").add("a brand new word");
        d = diff(was, fingerprint(reworded));
        assertEquals(1, d.size(), d.toString());
        assertTrue(d.get(0).startsWith("changed pick "), d.get(0));

        JsonObject rules = base.deepCopy();
        firstSlot(rules).addProperty("most", 99);
        d = diff(was, fingerprint(rules));
        assertEquals(1, d.size(), d.toString());
        assertTrue(d.get(0).startsWith("changed slot "), d.get(0));

        JsonObject comment = base.deepCopy();
        comment.addProperty("_comment", "words about the file are not the tree");
        assertEquals(List.of(), diff(was, fingerprint(comment)));
    }

    private static JsonObject firstSlot(JsonObject tree) {
        return tree.getAsJsonArray("kinds").get(0).getAsJsonObject().getAsJsonArray("forms").get(0).getAsJsonObject()
                .getAsJsonArray("slots").get(0).getAsJsonObject();
    }

    static String message(List<String> diff) {
        StringBuilder sb = new StringBuilder("The Builder's tree changed since the Apprentice was last refreshed (")
                .append(diff.size()).append(" difference(s)). Its trained layer, recorded answers, test clauses and "
                        + "floors describe the old tree. Run:\n\n    ").append(COMMAND)
                .append("\n\nIt writes example and test clauses for new picks, retrains, re-measures and records this "
                        + "fingerprint last (docs/APPRENTICE.md, \"When the Builder's tree changes\").\n");
        diff.stream().limit(60).forEach(x -> sb.append("  ").append(x).append('\n'));
        if (diff.size() > 60) {
            sb.append("  … and ").append(diff.size() - 60).append(" more\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ the fingerprint

    /** {hash, kinds:{id: h}, slots:{kind/form/slot: h}, picks:{kind/form/slot/entry: h}} of a tree export. */
    static JsonObject fingerprint(JsonObject tree) {
        Map<String, String> kinds = new TreeMap<>();
        Map<String, String> slots = new TreeMap<>();
        Map<String, String> picks = new TreeMap<>();
        for (JsonElement ke : tree.getAsJsonArray("kinds")) {
            JsonObject k = ke.getAsJsonObject();
            String kid = k.get("id").getAsString();
            kinds.put(kid, sha(without(k, "forms").toString()));
            for (JsonElement fe : k.getAsJsonArray("forms")) {
                JsonObject f = fe.getAsJsonObject();
                String fid = kid + "/" + f.get("id").getAsString();
                for (JsonElement se : f.getAsJsonArray("slots")) {
                    JsonObject s = se.getAsJsonObject();
                    String sid = fid + "/" + s.get("id").getAsString();
                    slots.put(sid, sha(without(s, "entries").toString()));
                    for (JsonElement ee : s.getAsJsonArray("entries")) {
                        picks.put(sid + "/" + ee.getAsJsonObject().get("id").getAsString(), sha(ee.toString()));
                    }
                }
            }
        }
        StringBuilder all = new StringBuilder();
        kinds.forEach((k, v) -> all.append("kind|").append(k).append('|').append(v).append('\n'));
        slots.forEach((k, v) -> all.append("slot|").append(k).append('|').append(v).append('\n'));
        picks.forEach((k, v) -> all.append("pick|").append(k).append('|').append(v).append('\n'));
        JsonObject out = new JsonObject();
        out.addProperty("_comment", "The Builder tree the Apprentice was last refreshed against (#2046). WRITTEN by "
                + COMMAND + " as its last step; never by hand. ApprenticeTreeFingerprintTest fails Gate A when the "
                + "tree no longer matches, naming what changed. docs/APPRENTICE.md, \"When the Builder's tree changes\".");
        out.addProperty("hash", sha256(all.toString()));
        out.add("kinds", object(kinds));
        out.add("slots", object(slots));
        out.add("picks", object(picks));
        return out;
    }

    /** What differs between two fingerprints, one line each: "added   pick creature/ready/parts/kit:horn". */
    static List<String> diff(JsonObject was, JsonObject now) {
        List<String> out = new ArrayList<>();
        for (String part : List.of("kinds", "slots", "picks")) {
            String noun = part.substring(0, part.length() - 1);
            JsonObject a = was.has(part) ? was.getAsJsonObject(part) : new JsonObject();
            JsonObject b = now.getAsJsonObject(part);
            for (String k : b.keySet()) {
                if (!a.has(k)) {
                    out.add("added   " + noun + " " + k);
                } else if (!a.get(k).equals(b.get(k))) {
                    out.add("changed " + noun + " " + k);
                }
            }
            for (String k : a.keySet()) {
                if (!b.has(k)) {
                    out.add("removed " + noun + " " + k);
                }
            }
        }
        return out;
    }

    static String render(JsonObject fingerprint) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(fingerprint) + "\n";
    }

    private static JsonObject without(JsonObject o, String key) {
        JsonObject copy = new JsonObject();
        for (String k : o.keySet()) {
            if (!k.equals(key)) {
                copy.add(k, o.get(k));
            }
        }
        return copy;
    }

    private static JsonObject object(Map<String, String> map) {
        JsonObject o = new JsonObject();
        map.forEach(o::addProperty);
        return o;
    }

    private static String sha(String s) {
        return sha256(s).substring(0, 12);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
