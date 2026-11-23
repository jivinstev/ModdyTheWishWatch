package com.moddingfromamod.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Tiny helpers for hand-building the JSON Schema that describes a tool's arguments. */
public final class Schemas {

    private Schemas() {
    }

    public static JsonObject object() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        o.add("properties", new JsonObject());
        return o;
    }

    public static JsonObject prop(JsonObject schema, String name, String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        schema.getAsJsonObject("properties").add(name, p);
        return schema;
    }

    /**
     * A property that accepts EITHER a single string or a list of them.
     *
     * <p>One concept, one parameter. The alternative — a {@code command} and a {@code commands}
     * beside it — makes the model choose between two spellings of the same idea every call, and the
     * one it picks is the one it happened to see last.
     */
    public static JsonObject stringOrList(JsonObject schema, String name, String description,
                                          int maxItems) {
        JsonObject str = new JsonObject();
        str.addProperty("type", "string");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        JsonObject list = new JsonObject();
        list.addProperty("type", "array");
        list.add("items", items);
        list.addProperty("maxItems", maxItems);
        JsonArray either = new JsonArray();
        either.add(str);
        either.add(list);
        JsonObject p = new JsonObject();
        p.add("oneOf", either);
        p.addProperty("description", description);
        schema.getAsJsonObject("properties").add(name, p);
        return schema;
    }

    /**
     * A parameter that is <b>a list, and only a list</b>.
     *
     * <p>Exists because {@link #stringOrList} does not work as an instruction. {@code run_command}
     * offered both shapes and explained batching in its own description, and across <b>752 real
     * calls the list form was used ZERO times</b> — while 480 of those calls sat in unbroken streaks
     * of three or more, which is a script being typed one line at a time at about four seconds a
     * line.
     *
     * <p>That is this repo's own recorded rule arriving again: <i>an optional field is not an input
     * to generation — require it, or remove it.</i> A schema that offers a simpler shape gets the
     * simpler shape, however well the description argues otherwise.
     */
    public static JsonObject list(JsonObject schema, String name, String description,
                                  int maxItems) {
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        JsonObject p = new JsonObject();
        p.addProperty("type", "array");
        p.add("items", items);
        p.addProperty("maxItems", maxItems);
        p.addProperty("description", description);
        schema.getAsJsonObject("properties").add(name, p);
        return schema;
    }

    public static JsonObject required(JsonObject schema, String... names) {
        JsonArray req = new JsonArray();
        for (String n : names) {
            req.add(n);
        }
        schema.add("required", req);
        return schema;
    }

    /**
     * "At least one of these" — a constraint {@code required} cannot express.
     *
     * <p>Each named field's description is prefixed {@code [a|b REQUIRED — give one]}, and the tool
     * MUST also refuse a call with none of them, naming both fields in the error. Prose failed at this
     * once: {@code add_template} needs one of {@code all} or {@code any}; the schema marked both
     * optional and {@code any}'s own description opened with <em>"Optional:"</em>, and a real wish
     * died on it. The terse prefix, beside a refusal that says what to send, is what fixed that.
     *
     * <p>⛔ <b>Never as a top-level {@code anyOf}/{@code oneOf}/{@code allOf}</b>. That was
     * the spelling here until 2026-11-15, and it made the tool INVISIBLE to a Muse worker: the Claude
     * CLI, run from a redirected worker's own config directory ({@code WorkerCliConfig}), drops
     * every tool whose input schema combines at the root before the request is sent. Measured with
     * {@code tools/muse-tee-proxy.py}: 52 tools sent from the default directory, 49 from the worker's,
     * the missing three exactly {@code add_template}, {@code inspect_event} and {@code remove_event} —
     * and Muse, told by every reply to call {@code remove_event}, looped on {@code run_command}. Muse
     * had not called any of the three landed (0 of 72 archived runs, 2026-10-28 on,
     * against 15 of 15 the day before), while Opus kept calling them. A combinator INSIDE a property
     * ({@link #stringOrList}) is delivered and is fine. {@code ToolSchemaRootTest} holds every tool to this.
     */
    public static JsonObject requireOneOf(JsonObject schema, String... names) {
        String tag = "[" + String.join("|", names) + " REQUIRED — give "
                + (names.length == 2 ? "one" : "at least one") + "] ";
        JsonObject props = schema.getAsJsonObject("properties");
        for (String n : names) {
            JsonObject p = props.getAsJsonObject(n);
            String d = p.has("description") ? p.get("description").getAsString() : "";
            if (!d.startsWith("[")) {
                p.addProperty("description", tag + d);
            }
        }
        return schema;
    }
}
