package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>Upgrading a stored Builder spec to the current version</b>. One pure step per version,
 * applied in order, so a make saved by any earlier Builder can be reopened and changed ("make it
 * bigger") or re-lowered after the engine moves. The rule is WISH_STORES §10: a shape we cannot read
 * is REFUSED by name, never read loosely, and never written over.
 *
 * <ul>
 *   <li><b>v0</b> — the B0u prototype's echo: {@code "from":{"preset":"dragon"}},
 *       {@code "look":{"size":3,"scheme":"forest"}}. v1 flattens {@code look} into {@code size} and
 *       {@code scheme}, and makes {@code from} a catalogue id ({@code preset:dragon}).</li>
 *   <li><b>v1, an ITEM made from a ready-made</b> — {@code "from":"preset:items/lightning_sword_lightning"}.
 *       The item kind has no ready-mades any more: each is a stock item, a power and a strength
 *       ({@link BuilderItemPowers#READY_MADES}), and the spec is rewritten to that composition on read, with the
 *       ready-made's name when he gave none. Same version on purpose: the composed shape is one a v1 reader
 *       already reads (docs/COMPAT_SHIMS.md, "design the new shape so the OLD reader still works"), and
 *       {@code BuilderItemComposableTest} proves both lower to the same tool calls.</li>
 *   <li><b>v1, a CREATURE or RIDE made on a Builder body</b> — a body brought its own powers (a truck
 *       stayed forever, a jellyfish glowed). It brings only how it moves now (flying); the rest are the child's picks.
 *       A make saved before has those powers written out as his picks on read, so it re-makes exactly as it was
 *       ({@code BuilderStarterGoldenTest}). A creature already standing is its stored sculpt and is never touched.</li>
 * </ul>
 * <ul>
 *   <li><b>v1, an ITEM with one 's six fixed pairs</b> — {@code "power":"smelt_iron"} and the rest. They
 *       are one power now, "Turn things into…", with the pair as its From and To picks ({@link #turnIntoPicks}).</li>
 * </ul>
 * Adding a version: add the step here, a fixture of the OLD shape to {@code SpecMigrationsTest}, and
 * bump {@link BuilderSpec#CURRENT}.
 */
public final class SpecMigrations {

    private SpecMigrations() {
    }

    /** A copy of {@code stored} at {@link BuilderSpec#CURRENT}. Throws when it cannot be read. */
    public static JsonObject upgrade(JsonObject stored) {
        if (stored == null) {
            throw new IllegalArgumentException("no spec");
        }
        JsonObject j = stored.deepCopy();
        if (!j.has("builder") || !j.get("builder").isJsonPrimitive()) {
            throw new IllegalArgumentException("not a Builder spec (no \"builder\" version)");
        }
        int v = j.get("builder").getAsInt();
        if (v > BuilderSpec.CURRENT) {
            // A NEWER Builder wrote it. Reading it here would lose what this build does not know.
            throw new IllegalArgumentException("spec version " + v + " is newer than this build ("
                    + BuilderSpec.CURRENT + ")");
        }
        if (v < 0) {
            throw new IllegalArgumentException("spec version " + v + " is not a version");
        }
        if (v == 0) {
            j = v0to1(j);
            v = 1;
        }
        return turnIntoPicks(readyEventComposed(readyBodyPowers(readyPictureComposed(readyBuildingComposed(readyBlockComposed(readyMachineComposed(readyItemComposed(j))))))));
    }

    /** an earlier issue's six fixed pairs, as the one power's picks (From group, To). */
    static final java.util.Map<String, String[]> OLD_PAIRS = java.util.Map.of(
            "smelt_iron", new String[] {"ores", "furnace"}, "charcoal", new String[] {"logs", "furnace"},
            "glass", new String[] {"sand", "glass"}, "dirt_to_sand", new String[] {"dirt_grass", "sand"},
            "cobble_to_gravel", new String[] {"stone", "gravel"}, "stone_to_cobble", new String[] {"stone", "cobblestone"});

    /**
     * an item made with one 's six fixed pairs (Raw iron into iron, Logs into charcoal…), as "Turn things
     * into…" with that pair as its From and To picks. Same version on purpose (a v1 reader reads the new opts as it
     * reads any), and the name, stock, strength and colours are kept. Untouched otherwise.
     */
    static JsonObject turnIntoPicks(JsonObject j) {
        if (!j.has("kind") || !"item".equals(j.get("kind").getAsString()) || !(j.get("opts") instanceof JsonObject o)
                || !o.has("power") || !OLD_PAIRS.containsKey(o.get("power").getAsString())) {
            return j;
        }
        String[] pair = OLD_PAIRS.get(o.get("power").getAsString());
        JsonObject out = j.deepCopy();
        JsonObject opts = out.getAsJsonObject("opts");
        opts.addProperty("power", BuilderTurnInto.POWER);
        opts.addProperty("effect", "effect:" + com.moddingfromamod.datapack.TurnInto.EFFECT);
        opts.addProperty(BuilderTurnInto.FROM_SLOT, pair[0]);
        opts.addProperty(BuilderTurnInto.TO_SLOT, pair[1]);
        return out;
    }

    /**
     * a snack machine made from one of B5's 23 ready-mades, as its composition: the look, its treat (or "just
     * yummy"), its strength, the look's own ingredient, one in one out. A name he gave the ready-made was the MACHINE's,
     * so it is kept as {@code machine}; the snack keeps the look's own. Untouched otherwise (the slushie machine, a
     * starter that is not one of the 23, still lowers from its preset).
     */
    static JsonObject readyMachineComposed(JsonObject j) {
        if (!j.has("kind") || !"machine".equals(j.get("kind").getAsString()) || !j.has("from")
                || !j.get("from").isJsonPrimitive()) {
            return j;
        }
        String from = j.get("from").getAsString();
        BuilderSnacks.Ready r = from.startsWith("preset:") ? BuilderSnacks.READY_MADES.get(from.substring("preset:".length())) : null;
        if (r == null) {
            return j;
        }
        BuilderSnacks.Look look = BuilderSnacks.look(r.look());
        JsonObject out = j.deepCopy();
        out.addProperty("from", "");
        String named = out.has("name") ? out.get("name").getAsString().strip() : "";
        out.addProperty("name", "");
        JsonObject opts = out.has("opts") && out.get("opts").isJsonObject() ? out.getAsJsonObject("opts") : new JsonObject();
        BuilderSnacks.opts(look.id(), java.util.List.of(r.power()), r.strength(), look.ingredient(), BuilderSnacks.FILL_DEFAULT,
                BuilderSnacks.CONVERT).forEach(opts::addProperty);
        if (!named.isEmpty()) {
            opts.addProperty("machine", named);
        }
        out.add("opts", opts);
        return out;
    }

    /**
     * an event made from a ready-made, as the composition it is (it rains chickens, a tidal wave of water, a
     * meteor): the same stage_event call (BuilderEventComposableTest). Its name, size and count are kept. Untouched
     * otherwise.
     */
    static JsonObject readyEventComposed(JsonObject j) {
        if (!j.has("kind") || !"event".equals(j.get("kind").getAsString()) || !j.has("from")
                || !j.get("from").isJsonPrimitive()) {
            return j;
        }
        String from = j.get("from").getAsString();
        String[] c = from.startsWith("preset:") ? BuilderEvents.READY_MADES.get(from.substring("preset:".length())) : null;
        if (c == null) {
            return j;
        }
        JsonObject out = j.deepCopy();
        out.addProperty("from", "");
        JsonObject opts = out.has("opts") && out.get("opts").isJsonObject() ? out.getAsJsonObject("opts") : new JsonObject();
        opts.addProperty("motion", "motion:" + BuilderEvents.byId(c[0]).motion());
        opts.addProperty("event", c[0]);
        opts.addProperty("what", c[1]);
        out.add("opts", opts);
        return out;
    }

    /**
     * a picture made from a ready-made, as its subject with just the drawing at the drawing's own frame size (the
     * subject IS that ready-made's drawing). Its name and colours are kept. Untouched otherwise.
     */
    static JsonObject readyPictureComposed(JsonObject j) {
        if (!j.has("kind") || !"picture".equals(j.get("kind").getAsString()) || !j.has("from")
                || !j.get("from").isJsonPrimitive()) {
            return j;
        }
        String subject = BuilderPictures.subject(j.get("from").getAsString());
        if (subject.isEmpty() || !j.get("from").getAsString().startsWith("preset:")) {
            return j;
        }
        JsonObject out = j.deepCopy();
        out.addProperty("from", "");
        JsonObject opts = out.has("opts") && out.get("opts").isJsonObject() ? out.getAsJsonObject("opts") : new JsonObject();
        BuilderPictures.opts(subject, BuilderPictures.DRAWN, 0).forEach(opts::addProperty);
        out.add("opts", opts);
        return out;
    }

    /**
     * a building made from a ready-made, as its type with nothing outside (the type IS that ready-made's body).
     * Its name, size and colours are kept as they were. Untouched otherwise.
     */
    static JsonObject readyBuildingComposed(JsonObject j) {
        if (!j.has("kind") || !"building".equals(j.get("kind").getAsString()) || !j.has("from")
                || !j.get("from").isJsonPrimitive()) {
            return j;
        }
        String from = j.get("from").getAsString();
        BuilderBuildings.Type t = from.startsWith("preset:buildings/") ? BuilderBuildings.type(from) : null;
        if (t == null) {
            return j;
        }
        JsonObject out = j.deepCopy();
        out.addProperty("from", "");
        JsonObject opts = out.has("opts") && out.get("opts").isJsonObject() ? out.getAsJsonObject("opts") : new JsonObject();
        BuilderBuildings.opts(t.id(), BuilderBuildings.NOTHING).forEach(opts::addProperty);
        out.add("opts", opts);
        return out;
    }

    /**
     * a magic block made from a ready-made (the Lucky Block), as its look, trigger, effect and strength. Its
     * stock name is kept as the name when he gave none, so it is still called what it was. Untouched otherwise.
     */
    static JsonObject readyBlockComposed(JsonObject j) {
        if (!j.has("kind") || !"magic_block".equals(j.get("kind").getAsString()) || !j.has("from")
                || !j.get("from").isJsonPrimitive()) {
            return j;
        }
        String from = j.get("from").getAsString();
        BuilderBlocks.Ready r = from.startsWith("preset:") ? BuilderBlocks.READY_MADES.get(from.substring("preset:".length())) : null;
        if (r == null) {
            return j;
        }
        JsonObject out = j.deepCopy();
        out.addProperty("from", "");
        if (!out.has("name") || out.get("name").getAsString().isBlank()) {
            out.addProperty("name", r.name());
        }
        JsonObject opts = out.has("opts") && out.get("opts").isJsonObject() ? out.getAsJsonObject("opts") : new JsonObject();
        BuilderBlocks.opts(r.look(), r.when(), r.does(), r.strength()).forEach(opts::addProperty);
        out.add("opts", opts);
        return out;
    }

    /**
     * a creature or ride made on a Builder body ({@code preset:bodies/…}) before bodies stopped bringing powers:
     * the body's own powers, but its mood words and flying, as his picks ahead of his own. Untouched otherwise (the
     * starter species never brought powers: the lowering's own list always overrode them).
     */
    static JsonObject readyBodyPowers(JsonObject j) {
        if (!j.has("kind") || !j.has("from") || !j.get("from").isJsonPrimitive()) {
            return j;
        }
        String kind = j.get("kind").getAsString();
        String from = j.get("from").getAsString();
        if (!(kind.equals("creature") || kind.equals("ride")) || !from.startsWith("preset:bodies/")) {
            return j;
        }
        JsonObject sculpt = LibraryPresets.SHIPPED.read(from.substring("preset:".length()));
        if (sculpt == null || !sculpt.has("powers") || !sculpt.get("powers").isJsonArray()) {
            return j;
        }
        java.util.LinkedHashSet<String> powers = new java.util.LinkedHashSet<>();
        for (JsonElement e : sculpt.getAsJsonArray("powers")) {
            String p = e.getAsString();
            // a ride is rideable already (the lowering adds it, and the powers page offers no such tick on a ride)
            if (!BuilderLowering.TEMPER_WORDS.contains(p) && !p.equals("flies") && !(kind.equals("ride") && p.equals("rideable"))) {
                powers.add("power:" + p);
            }
        }
        if (powers.isEmpty()) {
            return j;
        }
        if (j.has("powers") && j.get("powers").isJsonArray()) {
            j.getAsJsonArray("powers").forEach(e -> powers.add(e.getAsString()));
        }
        JsonObject out = j.deepCopy();
        JsonArray pw = new JsonArray();
        powers.forEach(pw::add);
        out.add("powers", pw);
        return out;
    }

    /** an item made from a ready-made, as the stock item, power and strength it is. Untouched otherwise. */
    static JsonObject readyItemComposed(JsonObject j) {
        if (!j.has("kind") || !"item".equals(j.get("kind").getAsString()) || !j.has("from")
                || !j.get("from").isJsonPrimitive()) {
            return j;
        }
        String from = j.get("from").getAsString();
        BuilderItemPowers.Composed c = from.startsWith("preset:")
                ? BuilderItemPowers.READY_MADES.get(from.substring("preset:".length())) : null;
        if (c == null) {
            return j;
        }
        BuilderItemPowers.Stock stock = BuilderItemPowers.stock(c.stock());
        BuilderItemPowers.Power power = BuilderItemPowers.power(c.power());
        JsonObject out = j.deepCopy();
        out.addProperty("from", "");
        if (!out.has("name") || out.get("name").getAsString().isBlank()) {
            out.addProperty("name", c.name());
        }
        JsonObject opts = out.has("opts") && out.get("opts").isJsonObject() ? out.getAsJsonObject("opts") : new JsonObject();
        opts.addProperty("stock", stock.id());
        opts.addProperty("shape", "shape:" + stock.shape());
        opts.addProperty("effect", "effect:" + power.effect());
        opts.addProperty("power", power.id());
        opts.addProperty("strength", String.valueOf(c.strength()));
        out.add("opts", opts);
        return out;
    }

    static JsonObject v0to1(JsonObject j) {
        JsonObject out = j.deepCopy();
        out.addProperty("builder", 1);
        if (j.has("from") && j.get("from").isJsonObject()) {
            JsonObject f = j.getAsJsonObject("from");
            if (f.has("preset")) {
                out.addProperty("from", "preset:" + f.get("preset").getAsString());
            } else if (f.has("mob")) {
                out.addProperty("from", "mob:" + f.get("mob").getAsString());
            } else {
                throw new IllegalArgumentException("v0 'from' names neither a preset nor a mob: " + f);
            }
        }
        if (j.has("look") && j.get("look").isJsonObject()) {
            JsonObject look = j.getAsJsonObject("look");
            for (String k : new String[] {"size", "scheme"}) {
                if (look.has(k)) {
                    out.add(k, look.get(k));
                }
            }
            out.remove("look");
        }
        if (j.has("powers") && j.get("powers").isJsonArray()) {
            JsonArray p = new JsonArray();
            for (JsonElement e : j.getAsJsonArray("powers")) {
                p.add(e.getAsString());
            }
            out.add("powers", p);
        }
        return out;
    }
}
