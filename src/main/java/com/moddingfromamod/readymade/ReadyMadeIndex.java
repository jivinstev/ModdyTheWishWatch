package com.moddingfromamod.readymade;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>The one index over the four kinds</b>. Generated from the stores every time — never
 * hand-maintained, because a hand list is a second copy of the catalogue and the copies drift.
 *
 * <p>Ids are unique per kind, not across kinds: a starter creature called {@code cat} and an item
 * icon called {@code cat} are different things, and the menu always shows the kind beside the id.
 * Lookups by a bare id therefore take an optional kind and prefer the first match otherwise.
 */
public final class ReadyMadeIndex {

    private final List<ReadyMade> entries;

    public ReadyMadeIndex(List<ReadyMade> entries) {
        Map<String, ReadyMade> seen = new LinkedHashMap<>();
        for (ReadyMade e : entries) {
            // First wins: a duplicate is a source listing the same thing twice, and the first
            // source is the one closer to what ships.
            seen.putIfAbsent(e.kind().letter + ":" + e.id(), e);
        }
        this.entries = List.copyOf(seen.values());
    }

    public List<ReadyMade> all() {
        return entries;
    }

    public List<ReadyMade> of(Set<ReadyMade.Kind> kinds) {
        List<ReadyMade> out = new ArrayList<>();
        for (ReadyMade e : entries) {
            if (kinds.contains(e.kind())) {
                out.add(e);
            }
        }
        return out;
    }

    public ReadyMadeIndex only(Set<ReadyMade.Kind> kinds) {
        return new ReadyMadeIndex(of(kinds));
    }

    /** The entry with this id, of this kind when one is given. Null when none. */
    public ReadyMade get(String id, ReadyMade.Kind kind) {
        if (id == null) {
            return null;
        }
        String want = id.strip();
        for (ReadyMade e : entries) {
            if ((kind == null || e.kind() == kind) && e.id().equalsIgnoreCase(want)) {
                return e;
            }
        }
        // An alias is a lookup too, the rule items/catalogue.json's `_aka` records: the worker
        // calls a thing by the word it has, and a refusal over a known alias is a wasted turn.
        for (ReadyMade e : entries) {
            if (kind != null && e.kind() != kind) {
                continue;
            }
            for (String a : e.aliases()) {
                if (a.equalsIgnoreCase(want)) {
                    return e;
                }
            }
        }
        return null;
    }

    /** The menu for these kinds: one line per entry, grouped by kind. */
    public String menu(Set<ReadyMade.Kind> kinds) {
        StringBuilder sb = new StringBuilder();
        for (ReadyMade.Kind k : EnumSet.copyOf(kinds.isEmpty() ? EnumSet.allOf(ReadyMade.Kind.class) : kinds)) {
            List<ReadyMade> some = of(EnumSet.of(k));
            if (some.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(k.letter)).append(") ").append(k.noun).append("s:\n");
            for (ReadyMade e : some) {
                sb.append("  ").append(e.line()).append('\n');
            }
        }
        return sb.toString();
    }

    /** UTF-8 bytes of the whole index as JSON — what the budget test bounds. */
    public int bytes() {
        return toJson().toString().getBytes(StandardCharsets.UTF_8).length;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        JsonArray arr = new JsonArray();
        for (ReadyMade e : entries) {
            JsonObject j = new JsonObject();
            j.addProperty("kind", String.valueOf(e.kind().letter));
            j.addProperty("id", e.id());
            j.addProperty("name", e.name());
            j.add("aliases", strings(e.aliases()));
            j.add("tags", strings(e.tags()));
            j.addProperty("purpose", e.purpose());
            j.addProperty("use", e.use());
            if (!e.examples().isEmpty()) {
                j.add("examples", strings(e.examples()));
            }
            if (!e.detail().isEmpty()) {
                j.addProperty("detail", e.detail());
            }
            arr.add(j);
        }
        o.add("entries", arr);
        return o;
    }

    public static ReadyMadeIndex fromJson(JsonObject o) {
        List<ReadyMade> out = new ArrayList<>();
        if (o != null && o.has("entries")) {
            for (JsonElement el : o.getAsJsonArray("entries")) {
                JsonObject j = el.getAsJsonObject();
                ReadyMade.Kind k = ReadyMade.Kind.parse(str(j, "kind"));
                if (k == null) {
                    continue;
                }
                out.add(new ReadyMade(k, str(j, "id"), str(j, "name"), list(j, "aliases"),
                        list(j, "tags"), str(j, "purpose"), str(j, "use"), str(j, "detail"),
                        list(j, "examples")));
            }
        }
        return new ReadyMadeIndex(out);
    }

    private static JsonArray strings(List<String> xs) {
        JsonArray a = new JsonArray();
        xs.forEach(a::add);
        return a;
    }

    private static String str(JsonObject j, String k) {
        return j.has(k) && j.get(k).isJsonPrimitive() ? j.get(k).getAsString() : "";
    }

    private static List<String> list(JsonObject j, String k) {
        List<String> out = new ArrayList<>();
        if (j.has(k) && j.get(k).isJsonArray()) {
            for (JsonElement el : j.getAsJsonArray(k)) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                }
            }
        }
        return out;
    }
}
