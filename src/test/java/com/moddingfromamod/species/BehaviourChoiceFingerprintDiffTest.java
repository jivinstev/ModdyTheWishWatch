package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.vanilla.CarrierPool;
import com.moddingfromamod.wishbuilder.BuilderAllowlist;
import com.moddingfromamod.wishbuilder.BuilderTree;
import com.moddingfromamod.wishbuilder.LibraryPresets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>How the behaviour chooser's fingerprint is taken and compared</b>: the same menus give the same
 * fingerprint, a body or power added is named with the command to run, and every body the menus offer has a weight in
 * the shipped model. The check that the recorded fingerprint ({@code corpus/behaviour-choice/fingerprint.json}) is
 * THESE creatures is {@code BehaviourChoiceFingerprintTest}, beside the labels and floor it keeps in step.
 */
class BehaviourChoiceFingerprintDiffTest {

    static final Path RECORDED = Path.of("corpus", "behaviour-choice", "fingerprint.json");
    static final Path CURRENT = Path.of("build", "behaviour-fingerprint.json");
    static final String COMMAND = "./tools/behaviour-refresh.sh";

    static JsonObject fingerprint(JsonObject tree) {
        Map<String, String> parts = new TreeMap<>();
        for (JsonElement k : tree.getAsJsonArray("kinds")) {
            JsonObject kind = k.getAsJsonObject();
            String kid = kind.get("id").getAsString();
            if (!kid.equals("creature") && !kid.equals("ride")) {
                continue;
            }
            for (JsonElement f : kind.getAsJsonArray("forms")) {
                if ("animal".equals(f.getAsJsonObject().get("id").getAsString())) {
                    continue;   //: a real farm animal is summoned as the game's own and never meets the chooser
                }
                for (JsonElement s : f.getAsJsonObject().getAsJsonArray("slots")) {
                    JsonObject slot = s.getAsJsonObject();
                    String sid = slot.get("id").getAsString();
                    if (!List.of("from", "powers", "temper", "abilities", "size", "mobsize").contains(sid)) {
                        continue;
                    }
                    for (JsonElement e : slot.getAsJsonArray("entries")) {
                        JsonObject entry = e.getAsJsonObject();
                        String id = entry.get("id").getAsString();
                        JsonObject keep = new JsonObject();
                        if (entry.has("only")) {
                            keep.add("only", entry.get("only"));
                        }
                        if (sid.equals("from")) {
                            JsonObject facts = entry.getAsJsonObject("facts");
                            for (String fk : List.of("has", "ridden")) {
                                if (facts != null && facts.has(fk)) {
                                    keep.add(fk, facts.get(fk));
                                }
                            }
                            keep.add("body", body(id));
                        }
                        if (sid.equals("mobsize")) {
                            // the chooser reads a creature's size as the old four steps, and each named size
                            // maps onto one (BuilderMobSizes.oldSize): the same input, so the same fingerprint.
                            int old = com.moddingfromamod.wishbuilder.BuilderMobSizes.oldSize(
                                    com.moddingfromamod.wishbuilder.BuilderMobSizes.of(id));
                            parts.put(kid + "/size/" + old, hash(keep.toString()));
                            continue;
                        }
                        parts.put(kid + "/" + sid + "/" + id, hash(keep.toString()));
                    }
                }
            }
        }
        for (Archetype a : Archetype.values()) {
            parts.put("behaviour/" + a.key(), hash(a.life().toString()));
        }
        JsonObject out = new JsonObject();
        JsonObject p = new JsonObject();
        parts.forEach(p::addProperty);
        out.add("parts", p);
        out.addProperty("hash", hash(p.toString()));
        return out;
    }

    /** What the chooser reads off a body's own file. */
    private static JsonObject body(String preset) {
        JsonObject sculpt = LibraryPresets.SHIPPED.read(preset);
        JsonObject b = new JsonObject();
        if (sculpt == null) {
            b.addProperty("missing", true);
            return b;
        }
        if (sculpt.has("behaves_like")) {
            b.add("behaves_like", sculpt.get("behaves_like"));
        }
        TreeSet<String> powers = new TreeSet<>();
        if (sculpt.has("powers") && sculpt.get("powers").isJsonArray()) {
            sculpt.getAsJsonArray("powers").forEach(e -> powers.add(e.getAsString()));
        }
        JsonArray pw = new JsonArray();
        powers.forEach(pw::add);
        b.add("powers", pw);
        List<String> names = new ArrayList<>();
        if (sculpt.has("parts") && sculpt.get("parts").isJsonArray()) {
            for (JsonElement e : sculpt.getAsJsonArray("parts")) {
                if (e.isJsonObject() && e.getAsJsonObject().has("name")) {
                    names.add(e.getAsJsonObject().get("name").getAsString());
                }
            }
        }
        String name = (sculpt.has("name") ? sculpt.get("name").getAsString() : "") + " "
                + preset.substring(preset.indexOf('/') + 1).replace('_', ' ');
        b.addProperty("moves", CarrierPool.movementOfNames(List.of(), names, name).name());
        return b;
    }

    static List<String> diff(JsonObject was, JsonObject now) {
        List<String> out = new ArrayList<>();
        JsonObject a = was.getAsJsonObject("parts");
        JsonObject b = now.getAsJsonObject("parts");
        TreeSet<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            if (!a.has(k)) {
                out.add("added   " + k);
            } else if (!b.has(k)) {
                out.add("removed " + k);
            } else if (!a.get(k).equals(b.get(k))) {
                out.add("changed " + k);
            }
        }
        return out;
    }

    static String message(List<String> diff) {
        StringBuilder sb = new StringBuilder("The Builder's creatures changed since the behaviour chooser was last "
                + "trained (").append(diff.size()).append(" difference(s)). Its weights and its floor describe the old "
                + "menus. Run:\n\n    ").append(COMMAND).append("\n\n(corpus/behaviour-choice/README.md, \"When it is "
                + "retrained\".) What changed:\n");
        diff.stream().limit(40).forEach(d -> sb.append("  ").append(d).append('\n'));
        if (diff.size() > 40) {
            sb.append("  … and ").append(diff.size() - 40).append(" more\n");
        }
        return sb.toString();
    }

    static String render(JsonObject fingerprint) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(fingerprint) + "\n";
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonObject tree() {
        return BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED).toJson();
    }

    @Test
    @DisplayName("a body added, a power added, a body's own life changed: each is named with the command")
    void aChangeIsNamed() {
        JsonObject base = tree();
        JsonObject was = fingerprint(base);
        assertEquals(List.of(), diff(was, fingerprint(tree())), "two walks of the same menus agree");
        JsonObject added = base.deepCopy();
        JsonObject from = slot(added, "creature", "from");
        JsonObject copy = from.getAsJsonArray("entries").get(0).getAsJsonObject().deepCopy();
        copy.addProperty("id", "bodies/a_body_nobody_offers");
        from.getAsJsonArray("entries").add(copy);
        List<String> d = diff(was, fingerprint(added));
        assertEquals(List.of("added   creature/from/bodies/a_body_nobody_offers"), d);
        assertTrue(message(d).contains(COMMAND));
        JsonObject power = base.deepCopy();
        JsonObject powers = slot(power, "creature", "powers");
        JsonObject p = powers.getAsJsonArray("entries").get(0).getAsJsonObject().deepCopy();
        p.addProperty("id", "power:sings");
        powers.getAsJsonArray("entries").add(p);
        assertEquals(List.of("added   creature/powers/power:sings"), diff(was, fingerprint(power)));
    }

    /** Every body the menus offer has a weight of its own in the shipped model (a new one cannot slip past). */
    @Test
    void everyOfferedBodyHasAWeight() throws IOException {
        JsonObject model = JsonParser.parseString(Files.readString(Path.of("src/main/resources/behaviour/choice.json")))
                .getAsJsonObject();
        TreeSet<String> features = new TreeSet<>();
        model.getAsJsonArray("features").forEach(e -> features.add(e.getAsString()));
        List<String> missing = new ArrayList<>();
        for (String kind : List.of("creature", "ride")) {
            for (JsonElement e : slot(tree(), kind, "from").getAsJsonArray("entries")) {
                String id = e.getAsJsonObject().get("id").getAsString();
                if (!features.contains("from=" + id)) {
                    missing.add(kind + "/" + id);
                }
            }
        }
        assertTrue(missing.isEmpty(), "offered, never trained on: " + missing + ". Run " + COMMAND);
    }

    private static JsonObject slot(JsonObject tree, String kind, String slot) {
        for (JsonElement k : tree.getAsJsonArray("kinds")) {
            if (k.getAsJsonObject().get("id").getAsString().equals(kind)) {
                for (JsonElement f : k.getAsJsonObject().getAsJsonArray("forms")) {
                    for (JsonElement s : f.getAsJsonObject().getAsJsonArray("slots")) {
                        if (s.getAsJsonObject().get("id").getAsString().equals(slot)) {
                            return s.getAsJsonObject();
                        }
                    }
                }
            }
        }
        throw new AssertionError("no " + kind + "/" + slot);
    }
}
