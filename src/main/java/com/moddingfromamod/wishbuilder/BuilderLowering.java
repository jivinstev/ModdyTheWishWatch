package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>A Builder spec → the MCP tool calls the wish worker would make</b> (B0a;
 * docs/WISH_BUILDER_PLAN.md §2). Pure and Minecraft-free: the ready-mades it starts from are read
 * through {@link Presets}, so Gate A runs every kind ({@code BuilderLoweringTest}).
 *
 * <p>Every make is: {@code plan_wish} (the gate every grant passes), the tool calls for its
 * kind, and {@code finish_building} (the wish card photo). Nothing here is a new capability — the only
 * Builder-owned tables are translations: {@link Schemes} into {@code recolour}, a size step into
 * {@code scale} or an event size, and a temperament into creature powers.
 *
 * <p>It refuses (throws {@link IllegalArgumentException}, naming the problem) rather than guessing,
 * because a spec is what a child chose. Allowlists are a separate check ({@link BuilderAllowlist}) so a
 * test can lower a spec no world offers.
 */
public final class BuilderLowering {

    private BuilderLowering() {
    }

    /** One MCP call: the tool's name and its arguments, exactly as {@code tools/call} takes them. */
    public record Call(String tool, JsonObject args) {
    }

    /** Reads a ready-made's stored file by its preset id ({@code species/cat} → its sculpt). */
    public interface Presets {
        /** The JSON stored for {@code preset} (no prefix), or null when the library has no such entry. */
        JsonObject read(String preset);

        /** An item preset's icon ({@code sprite}, {@code tint}); null when it has none. */
        default JsonObject icon(String preset) {
            return null;
        }
    }

    /** Size steps 1–4 as a creature's overall {@code scale}. */
    static final double[] CREATURE_SCALE = {0.6, 1.0, 1.6, 2.5};

    /** Size steps 1–4 as a building's scale: every offset and dimension times this. */
    static final double[] BUILDING_SCALE = {0.75, 1.0, 1.5, 2.0};

    static String bare(JsonObject s) {
        return s.has("block") ? s.get("block").getAsString().replaceFirst("\\[.*", "").replaceFirst("^minecraft:", "") : "";
    }

    /**
     * what a player USES keeps a player's size, and only its place scales: one block stays one block (a lantern,
     * the maze's gold), and a bed, a door, a sign — and a ROW of single items (the base's two chests, the ship's three)
     * stays the same row, never a wall of them.
     */
    static boolean keepsSize(JsonObject s) {
        String block = bare(s);
        int w = s.has("width") ? s.get("width").getAsInt() : 1;
        int h = s.has("height") ? s.get("height").getAsInt() : 1;
        int d = s.has("depth") ? s.get("depth").getAsInt() : 1;
        return w == 1 && h == 1 && d == 1
                || block.endsWith("_bed") || block.endsWith("_door") || block.endsWith("_trapdoor")
                || block.endsWith("_sign") || block.endsWith("_banner")
                || block.endsWith("chest") || block.equals("barrel") || block.endsWith("shulker_box");
    }

    /**
     * Every shape of a ready-made building by {@code f} ({@link #scale}), and then the pieces that keep their size
     * and TOUCHED stay touching: a bed's head beside its foot, a chest beside a chest. Rounding each one's place on
     * its own put the head on top of the foot at size 1 — one bed half.
     */
    /**
     * a ready-made building is never dark inside, at any size. Whatever floor
     * {@link com.moddingfromamod.builder.DarkInterior} still calls dark after scaling gets a light set into it
     * ({@link com.moddingfromamod.builder.BuildLighting#forDark}); none where the building lights itself.
     * {@code -Ptestprops=builderlights=off} is the A/B seam.
     */
    static void light(JsonArray shapes) {
        if ("off".equalsIgnoreCase(System.getProperty("moddingfromamod.builderlights"))) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", "lighting");
        o.addProperty("name", "lighting");
        o.add("shapes", shapes);
        com.moddingfromamod.builder.BuildPlan plan = com.moddingfromamod.builder.BuildPlan.fromJson(o);
        if (plan == null) {
            return;
        }
        com.moddingfromamod.builder.BuildLighting.forDark(plan).forEach(s -> shapes.add(s.toJson()));
    }

    /**
     * NEVER A SILENT CUT. {@link com.moddingfromamod.builder.BuildPlan} keeps the first
     * {@link com.moddingfromamod.builder.BuildPlan#MAX_SHAPES} written shapes and the first
     * {@link com.moddingfromamod.builder.BuildPlan#MAX_GENERATED} generated ones and drops the rest; a Builder make that
     * would lose one is refused here, in words, rather than built short. Null when every shape is kept.
     */
    static String overTheShapeCaps(JsonArray shapes) {
        int wrote = 0;
        int made = 0;
        for (JsonElement e : shapes) {
            JsonObject s = e.getAsJsonObject();
            if (s.has("gen") && s.get("gen").getAsBoolean()) {
                made++;
            } else {
                wrote++;
            }
        }
        if (wrote > com.moddingfromamod.builder.BuildPlan.MAX_SHAPES) {
            return "would have " + wrote + " parts, and a building keeps at most " + com.moddingfromamod.builder.BuildPlan.MAX_SHAPES
                    + ": the last " + (wrote - com.moddingfromamod.builder.BuildPlan.MAX_SHAPES) + " would not be built";
        }
        if (made > com.moddingfromamod.builder.BuildPlan.MAX_GENERATED) {
            return "would have " + made + " generated pieces, and a building keeps at most "
                    + com.moddingfromamod.builder.BuildPlan.MAX_GENERATED + ": the last "
                    + (made - com.moddingfromamod.builder.BuildPlan.MAX_GENERATED) + " would not be built";
        }
        return null;
    }

    static void scaleAll(JsonArray shapes, double f) {
        scaleAll(shapes, f, com.moddingfromamod.builder.BuildScale.enabled());
    }

    /**
     * {@code byParts}: scale by what each shape IS for a player ({@link com.moddingfromamod.builder.BuildScale},
     * docs/BUILD_CIRCULATION_PLAN.md) instead of by multiplying every number. Off unless {@code partscale=on}.
     */
    static void scaleAll(JsonArray shapes, double f, boolean byParts) {
        if (byParts) {
            java.util.List<com.moddingfromamod.builder.BuildShape> in = new java.util.ArrayList<>();
            for (JsonElement e : shapes) {
                com.moddingfromamod.builder.BuildShape s = com.moddingfromamod.builder.BuildShape.fromJson(e.getAsJsonObject());
                if (s != null) {
                    in.add(s);
                }
            }
            java.util.List<com.moddingfromamod.builder.BuildShape> out = com.moddingfromamod.builder.BuildScale.scale(in, f);
            while (shapes.size() > 0) {
                shapes.remove(shapes.size() - 1);
            }
            out.forEach(s -> shapes.add(s.toJson()));
            return;
        }
        java.util.List<int[]> was = new java.util.ArrayList<>();
        for (JsonElement e : shapes) {
            JsonObject s = e.getAsJsonObject();
            was.add(new int[] {num(s, "dx", 0), num(s, "dy", 0), num(s, "dz", 0),
                    num(s, "width", 1), num(s, "height", 1), num(s, "depth", 1)});
            scale(s, f);
        }
        for (int i = 0; i < shapes.size(); i++) {
            JsonObject si = shapes.get(i).getAsJsonObject();
            if (!keepsSize(si)) {
                continue;
            }
            for (int j = 0; j < i; j++) {
                JsonObject sj = shapes.get(j).getAsJsonObject();
                if (keepsSize(sj) && faceTouch(box(was.get(i), shapes.get(i).getAsJsonObject()), box(was.get(j), sj))) {
                    String[] k = {"dx", "dy", "dz"};
                    for (int a = 0; a < 3; a++) {
                        si.addProperty(k[a], num(sj, k[a], 0) + was.get(i)[a] - was.get(j)[a]);
                    }
                    break;
                }
            }
        }
    }

    /** The box a shape fills, from where it was: a centre-anchored one's x and z are its middle (BuildShape). */
    private static int[] box(int[] was, JsonObject s) {
        boolean centre = s.has("anchor") && s.get("anchor").getAsString().toLowerCase(java.util.Locale.ROOT).startsWith("cent");
        return centre ? new int[] {was[0] - was[3] / 2, was[1], was[2] - was[5] / 2, was[3], was[4], was[5]} : was;
    }

    private static int num(JsonObject s, String k, int dflt) {
        return s.has(k) ? s.get(k).getAsInt() : dflt;
    }

    /** Two boxes (dx,dy,dz,w,h,d) that share a face. */
    static boolean faceTouch(int[] a, int[] b) {
        int touching = 0;
        for (int i = 0; i < 3; i++) {
            int aLo = a[i], aHi = a[i] + a[i + 3], bLo = b[i], bHi = b[i] + b[i + 3];
            if (aHi == bLo || bHi == aLo) {
                touching++;
            } else if (aHi <= bLo || bHi <= aLo) {
                return false;
            }
        }
        return touching == 1;
    }

    /**
     * One shape of a ready-made building by {@code f}, the size step's factor: every offset and every dimension by the
     * same factor, so the parts stay where they were relative to each other; never below one block (a door frame, a
     * window stays a block wide). Rounding each on its own can part two shapes that touched.
     */
    static void scale(JsonObject s, double f) {
        String block = bare(s);
        String name = s.has("name") ? s.get("name").getAsString().toLowerCase(java.util.Locale.ROOT) : "";
        boolean kept = keepsSize(s);
        boolean ladder = block.equals("ladder") || block.equals("vine") || block.equals("scaffolding");
        boolean opening = (block.equals("air") || block.equals("cave_air"))
                && name.matches(".*(door|gate|entrance|entry|hatch|opening|arch).*");
        for (String k : new String[] {"dx", "dy", "dz"}) {
            if (s.has(k)) {
                s.addProperty(k, (int) Math.round(s.get(k).getAsDouble() * f));
            }
        }
        for (String k : new String[] {"width", "height", "depth"}) {
            if (!s.has(k)) {
                continue;
            }
            int was = s.get(k).getAsInt();
            int scaled = Math.max(1, (int) Math.round(was * f));
            int to;
            if (kept) {
                to = was;
            } else if (ladder) {
                to = k.equals("height") ? scaled : was;   // a ladder grows taller, never thicker: each rung needs its wall
            } else if (opening) {
                // A way in stays a person's size: at least two high, never narrower than it was; through the wall it
                // goes as deep as the wall now is.
                to = k.equals("height") ? Math.max(2, Math.max(was, Math.min(scaled, was * 2)))
                        : was == 1 ? scaled : Math.max(was, Math.min(scaled, was * 2));
            } else {
                to = scaled;
            }
            s.addProperty(k, to);
        }
    }

    /** Size steps 1–4 as {@code stage_event}'s sizes. */
    static final String[] EVENT_SIZE = {"little", "medium", "big", "gigantic"};

    /** The temperaments a world may offer, and the creature powers each one is. */
    public static List<String> tempers() {
        return List.of("friendly", "shy", "wild", "guard", "boss", "pet", "stays");
    }

    /**
     * <b>Whose side it is on</b> (Moddy-Worlds#61). A spec that asks for {@code power:hostile} is hostile: the
     * temperament's {@code friendly} (an empty temperament means friendly) and {@code skittish} are dropped, and so is a
     * ready-made body's {@code guardian} base, which fights monsters. Before this a world's champion built on the rock
     * golem was friendly, hostile and a guardian at once, and hunted the wave it was part of. Otherwise a guard is
     * given the guardian base, as before.
     */
    static void sideOf(BuilderSpec spec, List<String> powers, JsonObject a) {
        if (powers.contains("hostile")) {
            powers.remove("friendly");
            powers.remove("skittish");
            if ("guardian".equals(str(a, "base"))) {
                a.remove("base");
            }
        } else if ("guard".equals(spec.temper())) {
            a.addProperty("base", "guardian");
        }
    }

    static List<String> temperPowers(String temper) {
        return switch (temper == null ? "" : temper) {
            case "", "friendly" -> List.of("friendly");
            case "shy" -> List.of("skittish");
            case "wild" -> List.of();
            // A guard fights MONSTERS for him: friendly + the guardian base (sculpt_creature's own rule).
            case "guard" -> List.of("friendly");
            case "boss" -> List.of("boss", "hostile");
            // step 6: "Follows you" and "Stays put" are friendly; what makes them follow or stay
            // is modify_mob's own follow / stay on the creature itself (withYou), as the wish worker would do it.
            case "pet", "stays" -> List.of("friendly");
            default -> throw new IllegalArgumentException("no temperament '" + temper + "'");
        };
    }

    /**
     * An EDIT of a thing already made: {@code ref} is what the tool knows it by (its id; an item's
     * name). Each tool's own "change the one he has" form, never a second copy: the same id rebuilds a
     * building where it stands and replaces a creature's design (every one in the world changes); a picture
     * and an item take {@code start_from}; a rule or an event the same name. Nothing new is spawned, handed
     * over or placed: an edit happens where the thing IS. {@code about} is what {@code plan_wish} is told the
     * wish is about: the thing's catalogue id when the catalogue lists it, else "new" (an item is listed by an
     * id {@code make_item} minted, which the Builder never sees) — the caller decides, because only the world
     * knows.
     */
    public record Edit(String ref, String about) {
        public Edit(String ref) {
            this(ref, ref);
        }
    }

    /** The tool calls for {@code spec}. {@code newId} is the id the make is saved under. */
    public static List<Call> lower(BuilderSpec spec, String newId, Presets presets) {
        return lower(spec, newId, presets, null);
    }

    /** …or, with {@code edit}, the calls that change the thing it already made (same id). */
    public static List<Call> lower(BuilderSpec spec, String newId, Presets presets, Edit edit) {
        if (spec == null) {
            throw new IllegalArgumentException("no spec");
        }
        for (String id : ids(spec)) {
            String problem = Catalogue.problem(id);
            if (problem != null) {
                throw new IllegalArgumentException(problem);
            }
        }
        if (spec.size() < 1 || spec.size() > 4) {
            throw new IllegalArgumentException("size " + spec.size() + " is not 1–4");
        }
        if (spec.count() < 1 || spec.count() > BuilderCounts.TOP) {   //: the world's spawn rules fit the rest
            throw new IllegalArgumentException("count " + spec.count() + " is not 1–" + BuilderCounts.TOP);
        }
        // no hand-set number of powers, moves or snack powers — only what a tool refuses or loses
        // (BuilderLimits, the one source the menus and the Apprentice also ask).
        String limit = BuilderLimits.problem(spec);
        if (limit != null) {
            throw new IllegalArgumentException(limit);
        }
        if (edit != null && Catalogue.prefix(spec.from()).equals("mob")) {
            throw new IllegalArgumentException("a real mob is not kept, so there is nothing to change: make a new one");
        }
        if (edit != null && spec.kind() == BuilderSpec.Kind.SUPPLIES) {
            throw new IllegalArgumentException("supplies are not kept, so there is nothing to change: make some more");
        }
        List<Call> calls = new ArrayList<>();
        calls.add(plan(spec, edit));
        switch (spec.kind()) {
            case CREATURE, RIDE -> creature(spec, newId, presets, calls);
            case ITEM, MACHINE, MAGIC_BLOCK -> item(spec, presets, calls);
            case BUILDING -> building(spec, newId, presets, calls);
            case PICTURE -> picture(spec, newId, presets, calls);
            case EVENT -> event(spec, newId, presets, calls);
            case RULE -> rule(spec, newId, calls);
            // a bundle of a vanilla thing — give_items, the wish worker's own tool, which caps its own count.
            case SUPPLIES -> calls.add(new Call("give_items", BuilderSupplies.args(spec)));
            default -> throw new IllegalArgumentException("no lowering for " + spec.kind());
        }
        if (edit != null) {
            asEdit(spec, edit, calls);
        }
        JsonObject fin = new JsonObject();
        fin.addProperty("label", label(spec));
        calls.add(new Call("finish_building", fin));
        return calls;
    }

    /** Turn a make's calls into the edit of the thing it made: see {@link Edit}. */
    private static void asEdit(BuilderSpec spec, Edit edit, List<Call> calls) {
        calls.removeIf(c -> c.tool().equals("run_command"));   // more spawns: an edit adds none
        // A snack machine's food was made with it and is still his: an edit changes the machine only. A COMPOSED one
        // is the exception: changing its power or name changes the snack's id, so the snack is made again
        // (as_new: the same id replaces it, a new one is a new snack) and the machine is pointed at it.
        boolean composed = BuilderSnacks.composed(spec);
        Call lastItem = null;
        for (Call c : calls) {
            if (c.tool().equals("make_item")) {
                lastItem = c;
            }
        }
        final Call machine = lastItem;
        if (!composed) {
            calls.removeIf(c -> c.tool().equals("make_item") && c != machine);
        }
        for (Call c : calls) {
            JsonObject a = c.args();
            switch (c.tool()) {
                case "sculpt_creature" -> {
                    a.addProperty("spawn", false);
                    a.remove("at");
                }
                case "make_item" -> {
                    if (c != machine) {
                        continue;   // a composed machine's snack: made again as it is (one to taste the change)
                    }
                    a.addProperty("start_from", edit.ref());
                    a.remove("as_new");
                    a.remove("count");
                }
                case "build_structure" -> a.remove("at");
                case "paint_surface" -> {
                    a.addProperty("start_from", edit.ref());
                    a.remove("at");
                }
                default -> {
                }
            }
        }
    }

    /**
     * What the tool will know the thing by after these calls, for {@link Edit}: an item's name (make_item mints
     * its own id), else {@code newId}.
     */
    public static String refOf(List<Call> calls, String newId) {
        String ref = newId;
        for (Call c : calls) {   // the LAST make_item: a snack machine's food is made before the machine
            if (c.tool().equals("make_item") && c.args().has("name")) {
                ref = c.args().get("name").getAsString();
            }
        }
        return ref;
    }

    /** Every catalogue id the spec names (its {@code from}, scheme, powers and opts). */
    static List<String> ids(BuilderSpec spec) {
        List<String> out = new ArrayList<>();
        if (!spec.from().isEmpty()) {
            out.add(spec.from());
        }
        if (!spec.scheme().isEmpty()) {
            out.add(spec.scheme().contains(":") ? spec.scheme() : "scheme:" + spec.scheme());
        }
        out.addAll(spec.powers());
        for (String v : spec.opts().values()) {
            if (v.contains(":") && Catalogue.PREFIXES.contains(Catalogue.prefix(v))) {
                out.add(v);
            }
        }
        return out;
    }

    /** The plan kind a spec is: creature, item, building, picture, event or other. */
    static String kindOf(BuilderSpec spec) {
        return switch (spec.kind()) {
            case CREATURE, RIDE -> "creature";
            case ITEM, MACHINE, MAGIC_BLOCK, SUPPLIES -> "item";
            case BUILDING -> "building";
            case PICTURE -> "picture";
            case EVENT -> "event";
            default -> "other";
        };
    }

    private static Call plan(BuilderSpec spec, Edit edit) {
        JsonObject a = new JsonObject();
        JsonArray steps = new JsonArray();
        steps.add(BuilderWords.step(spec, edit != null));   //: a wish's plan, not "make Its raining chickens from the Builder"
        a.add("steps", steps);
        a.addProperty("about", edit == null || edit.about() == null || edit.about().isBlank() ? "new" : edit.about());
        a.addProperty("kind", kindOf(spec));
        return new Call("plan_wish", a);
    }

    // ------------------------------------------------------------------ creatures and rides

    private static void creature(BuilderSpec spec, String newId, Presets presets, List<Call> calls) {
        if (Catalogue.prefix(spec.from()).equals("mob")) {
            // A real mob: summoned as it is. Powers belong to sculpted creatures only (v1).
            if (!spec.powers().isEmpty() || !spec.scheme().isEmpty()) {
                throw new IllegalArgumentException("a real mob takes no powers or colours in the Builder");
            }
            // up to a hundred, in run_command's batches; its spawn scope makes what the world allows and says the rest
            List<JsonArray> batches = new ArrayList<>();
            // 1.21.5+ SNBT: a text component is a plain quoted string, not quoted JSON.
            List<String> nbt = new ArrayList<>();
            if (!spec.name().isEmpty()) {
                nbt.add("CustomName:\"" + nbtSafe(spec.name()) + "\",PersistenceRequired:1b");
            }
            // a named size is the game's own scale attribute; How strong and fast (#178) are its health, attack
            // damage and speed, by the Builder's own table on the mob's own numbers (BuilderRealMobs)
            List<String> attrs = new ArrayList<>();
            if (!spec.opt("mobsize").isEmpty()) {
                attrs.add("{id:\"minecraft:scale\",base:" + BuilderMobSizes.of(spec.opt("mobsize")).scale() + "d}");
            }
            BuilderRealMobs.Numbers stats = BuilderRealMobs.numbers(spec);
            if (stats != null) {
                attrs.add("{id:\"minecraft:max_health\",base:" + stats.health() + "d}");
                if (stats.damage() > 0) {
                    attrs.add("{id:\"minecraft:attack_damage\",base:" + stats.damage() + "d}");
                }
                if (stats.speed() > 0) {
                    attrs.add("{id:\"minecraft:movement_speed\",base:" + stats.speed() + "d}");
                }
                nbt.add("Health:" + (float) stats.health() + "f");
            }
            if (!attrs.isEmpty()) {
                nbt.add("attributes:[" + String.join(",", attrs) + "]");
            }
            String nameTag = nbt.isEmpty() ? "" : " {" + String.join(",", nbt) + "}";
            for (int i = 0; i < spec.count(); i++) {
                if (i % com.moddingfromamod.agent.tools.RunCommandTool.MAX_BATCH == 0) {
                    batches.add(new JsonArray());
                }
                batches.get(batches.size() - 1).add("summon " + Catalogue.bare(spec.from()) + " ~ ~ ~" + nameTag);
            }
            for (JsonArray cmds : batches) {
                JsonObject a = new JsonObject();
                a.add("command", cmds);
                at(spec, a);
                calls.add(new Call("run_command", a));
            }
            acts(spec, calls);
            return;
        }
        if (Catalogue.bare(spec.from()).startsWith("bodies/")) {
            body(spec, newId, presets, calls);
            return;
        }
        String preset = presetOf(spec, "species");
        JsonObject sculpt = need(presets, preset);
        JsonObject a = new JsonObject();
        a.addProperty("id", newId);
        a.addProperty("name", spec.name().isEmpty() ? str(sculpt, "name") : spec.name());
        a.addProperty("start_from", Catalogue.bare(preset).substring("species/".length()));
        a.add("parts", new JsonArray());
        a.add("palette", sculpt.has("palette") ? sculpt.get("palette").deepCopy() : new JsonArray());
        a.addProperty("note", "Builder make: " + label(spec) + " from the ready-made "
                + Catalogue.bare(preset) + ".");
        a.addProperty("scale", creatureScale(spec, sculpt));
        Schemes.Scheme scheme = Schemes.resolve(spec);
        if (scheme != null) {
            JsonObject recolour = Schemes.recolour(sculpt.getAsJsonArray("parts"), scheme);
            if (recolour.size() > 0) {
                a.add("recolour", recolour);
            }
        }
        // #74: its health and attack damage are the ready-made's SHIPPED ones, sent with the sculpt: a Builder pet dragon
        // has its 60 health in every world. Inheriting them from this world's record of the species made it 20 in a
        // fresh world, and something else wherever a world had its own record. The shipped record wins over a world's;
        // a world holds it down with its limits (#14), never by its record of the species.
        JsonObject shipped = LibraryPresets.SHIPPED.species(Catalogue.bare(preset));
        for (String k : new String[] {"health", "damage"}) {
            if (shipped != null && shipped.has(k) && shipped.get(k).isJsonPrimitive()) {
                a.add(k, shipped.get(k).deepCopy());
            }
        }
        defence(shipped, a);
        strength(spec, a, sculpt.getAsJsonArray("parts"), BuilderParts.vehicle(preset, shipped));
        List<String> powers = new ArrayList<>(temperPowers(spec.temper()));
        // what the starter does by being itself (a dragon flies and never burns, a T-Rex pounces). The lowering's
        // list replaces the starter's own file, so before this a Builder pet dragon walked and burned.
        for (String p : BuilderNatural.of(preset, sculpt)) {
            if (!powers.contains(p)) {
                powers.add(p);
            }
        }
        if (spec.kind() == BuilderSpec.Kind.RIDE && !powers.contains("rideable")) {
            powers.add("rideable");
        }
        List<String> abilities = new ArrayList<>();
        for (String p : spec.powers()) {
            switch (Catalogue.prefix(p)) {
                case "power" -> {
                    if (!powers.contains(Catalogue.bare(p))) {
                        powers.add(Catalogue.bare(p));
                    }
                }
                case "ability" -> abilities.add(Catalogue.bare(p));
                default -> throw new IllegalArgumentException("'" + p + "' is not a creature power");
            }
        }
        sideOf(spec, powers, a);
        JsonArray pw = new JsonArray();
        powers.forEach(pw::add);
        a.add("powers", pw);
        // how it LIVES, chosen from the picks (BehaviourChoice). A ready-made species named none, so it lived
        // its carrier's life: a flying cat fluttered like the bat it rides.
        a.addProperty(com.moddingfromamod.species.BehavesLike.ARG, behaviourOf(spec, sculpt));
        speed(spec, a);
        // opts "spawn":"no": the design alone (#178: a world makes a champion once and spawns copies itself)
        a.addProperty("spawn", !"no".equals(spec.opt("spawn")));
        at(spec, a);
        // the parts he added, hung on the ready-made's own head or body.
        List<String> kits = BuilderParts.chosen(spec);
        if (!kits.isEmpty()) {
            JsonArray own = sculpt.getAsJsonArray("parts");
            // the body's room is the only limit on parts — sculpt_creature drops kits past it without a word.
            String tooBig = BuilderLimits.parts(BuilderParts.boxes(kits, own, ""), BuilderParts.budget(own.size()), str(sculpt, "name"));
            if (tooBig != null) {
                throw new IllegalArgumentException("parts: " + tooBig);
            }
            a.add("kits", BuilderParts.kits(kits, own, scheme == null ? "" : scheme.accent()));
        }
        calls.add(new Call("sculpt_creature", a));
        for (String ability : abilities) {
            calls.add(new Call("apply_effect", abilityArgs(ability, newId)));
        }
        if (spec.count() > 1) {
            JsonObject more = new JsonObject();
            JsonArray cmds = new JsonArray();
            cmds.add("wishmob spawn " + newId + " " + (spec.count() - 1));
            more.add("command", cmds);
            at(spec, more);
            calls.add(new Call("run_command", more));
        }
        withYou(spec, newId, calls);
    }

    /**
     * #101: a ready-made's shipped armour and knockback resistance (a turtle's shell, a rock golem that is never pushed),
     * sent with its sculpt now that the tool takes them; only where it has some, so every other make is unchanged.
     */
    static void defence(JsonObject shipped, JsonObject sculptArgs) {
        if (shipped == null) {
            return;
        }
        for (String[] k : new String[][] {{"armor", "armor"}, {"knockbackResistance", "knockback_resistance"}}) {
            if (shipped.has(k[0]) && shipped.get(k[0]).isJsonPrimitive() && shipped.get(k[0]).getAsDouble() > 0) {
                sculptArgs.add(k[1], shipped.get(k[0]).deepCopy());
            }
        }
    }

    /**
     * #103: its size and How strong move its health and attack damage ({@link BuilderCreatureStrength}), and a guard
     * always hits for at least its size band's damage, measured on the body it is made with ({@code parts}) at the
     * scale it is made at. A number the sculpt does not carry is left to the tool (its band's default, #100), except a
     * guard's damage, which it always carries.
     */
    static void strength(BuilderSpec spec, JsonObject sculptArgs, JsonArray parts, boolean vehicle) {
        BuilderMobSizes.Level size = BuilderCreatureStrength.sizeOf(spec);
        int st = BuilderCreatureStrength.strengthOf(spec);
        boolean guard = "guard".equals(spec.temper());
        if (sculptArgs.has("health") && sculptArgs.get("health").isJsonPrimitive()) {
            sculptArgs.addProperty("health", BuilderCreatureStrength.health(sculptArgs.get("health").getAsDouble(), size, st));
        }
        if (sculptArgs.has("damage") && sculptArgs.get("damage").isJsonPrimitive() || guard) {
            double body = sculptArgs.has("damage") && sculptArgs.get("damage").isJsonPrimitive()
                    ? sculptArgs.get("damage").getAsDouble() : 0;
            double scale = sculptArgs.has("scale") ? sculptArgs.get("scale").getAsDouble() : 1.0;
            double band = com.moddingfromamod.species.PowerBand.forVolume(
                    com.moddingfromamod.species.PowerBand.volume(boxes(parts), scale)).damage();
            sculptArgs.addProperty("damage", BuilderCreatureStrength.damage(body, size, st, guard, band, vehicle));
        }
    }

    /** A sculpt's parts as boxes ({@code from} and {@code to} in pixels), for measuring its solid volume. */
    static List<double[]> boxes(JsonArray parts) {
        List<double[]> out = new ArrayList<>();
        if (parts == null) {
            return out;
        }
        for (JsonElement e : parts) {
            JsonObject p = e.getAsJsonObject();
            if (p.has("from") && p.has("to")) {
                JsonArray f = p.getAsJsonArray("from");
                JsonArray t = p.getAsJsonArray("to");
                out.add(new double[] {f.get(0).getAsDouble(), f.get(1).getAsDouble(), f.get(2).getAsDouble(),
                        t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble()});
            }
        }
        return out;
    }

    /** step 6: the tune dialog's "How fast" (1 slow, 2 its own pace, 3 fast) as sculpt_creature's speed. */
    static final double[] CREATURE_SPEED = {0.15, 0, 0.4};

    static void speed(BuilderSpec spec, JsonObject sculpt) {
        String v = spec.opt("speed");
        if (v.equals("1") || v.equals("3")) {
            sculpt.addProperty("speed", CREATURE_SPEED[Integer.parseInt(v) - 1]);
        }
    }

    /**
     * step 6: "Follows you" / "Stays put" (a temperament on the powers page) as modify_mob's own follow / stay on
     * every one he has, the wish worker's own tool and fields. After any spawn, so the copies are there to tell; in an
     * edit it is kept (only run_command is dropped), so changing a creature to "Follows you" makes the ones he has follow.
     */
    /**
     * a real creature made to act as he asked, by the Magician's own modify_mob on the ones just summoned (they
     * answer to the name Moddy gave them): never attacks him, follows him, or stays where it is.
     */
    static void acts(BuilderSpec spec, List<Call> calls) {
        String acts = spec.opt(BuilderActs.SLOT);
        if (acts.isEmpty() || acts.equals(BuilderActs.GAME) || spec.name().isEmpty()) {
            return;
        }
        JsonObject m = new JsonObject();
        m.addProperty("scope", "group");
        m.addProperty("target", spec.name());
        m.addProperty("radius", (int) com.moddingfromamod.mob.MobTweak.MAX_RADIUS);
        m.addProperty("count", Math.max(1, Math.min(spec.count(), com.moddingfromamod.mob.MobTweak.MAX_COUNT)));
        m.addProperty("friendly", true);   // following him or staying put, it is still never against him
        if (acts.equals("pet")) {
            m.addProperty("follow", true);
        } else if (acts.equals("stays")) {
            m.addProperty("stay", WITH_YOU_STAY);
        }
        calls.add(new Call("modify_mob", m));
    }

    static void withYou(BuilderSpec spec, String id, List<Call> calls) {
        if (!spec.temper().equals("pet") && !spec.temper().equals("stays")) {
            return;
        }
        JsonObject m = new JsonObject();
        m.addProperty("scope", "group");
        m.addProperty("target", id);
        m.addProperty("radius", (int) com.moddingfromamod.mob.MobTweak.MAX_RADIUS);
        m.addProperty("count", Math.max(1, Math.min(spec.count(), com.moddingfromamod.mob.MobTweak.MAX_COUNT)));
        if (spec.temper().equals("pet")) {
            m.addProperty("follow", true);
        } else {
            m.addProperty("stay", WITH_YOU_STAY);
        }
        calls.add(new Call("modify_mob", m));
    }

    /**
     * The temperament the behaviour chooser reads: "Follows you" and "Stays put" live as a friendly creature does (what
     * they add is modify_mob's follow / stay, not a way of living), so the chooser was not retrained for them.
     */
    static String livesAs(String temper) {
        return "pet".equals(temper) || "stays".equals(temper) ? "friendly" : temper;
    }

    /** "Stays put": how far it may potter round its spot (modify_mob's stay, 0..PetBehaviour.MAX_STAY). */
    static final int WITH_YOU_STAY = 3;

    /** does this body move by flying (its stored powers say so)? The one power a body keeps as its own. */
    /**
     * the behaviour this make lives by ({@code behaves_like}), chosen from the picks by
     * {@link com.moddingfromamod.species.BehaviourChoice}: the body, whether it flies or swims or is a vehicle, the
     * kind, the powers, the temper, the size.
     */
    static String behaviourOf(BuilderSpec spec, JsonObject sculpt) {
        String from = Catalogue.bare(spec.from());
        String own = sculpt.has(com.moddingfromamod.species.BehavesLike.ARG)
                ? sculpt.get(com.moddingfromamod.species.BehavesLike.ARG).getAsString() : null;
        List<String> natural = BuilderNatural.of(from, sculpt);   //: a dragon flies by itself
        boolean flies = natural.contains("flies");
        List<String> parts = new ArrayList<>();
        if (sculpt.has("parts") && sculpt.get("parts").isJsonArray()) {
            for (JsonElement e : sculpt.getAsJsonArray("parts")) {
                if (e.isJsonObject() && e.getAsJsonObject().has("name")) {
                    parts.add(e.getAsJsonObject().get("name").getAsString());
                }
            }
        }
        String name = str(sculpt, "name") + " " + from.substring(from.indexOf('/') + 1).replace('_', ' ');
        boolean swims = !flies && com.moddingfromamod.vanilla.CarrierPool.movementOfNames(List.of(), parts, name)
                == com.moddingfromamod.vanilla.CarrierPool.Move.SWIM;
        // What HE picked: a body's own powers come back into a spec on every read (SpecMigrations.readyBodyPowers),
        // and they are the body's, not a choice that changes how it lives.
        java.util.Set<String> own2 = new java.util.HashSet<>();
        if (sculpt.has("powers") && sculpt.get("powers").isJsonArray()) {
            sculpt.getAsJsonArray("powers").forEach(e -> own2.add("power:" + e.getAsString()));
        }
        natural.forEach(n -> own2.add("power:" + n));
        java.util.Set<String> powers = new java.util.HashSet<>();
        boolean abilities = false;
        for (String p : spec.powers()) {
            if (Catalogue.prefix(p).equals("power") && !own2.contains("power:" + Catalogue.bare(p))) {
                powers.add("power:" + Catalogue.bare(p));
            } else if (Catalogue.prefix(p).equals("ability")) {
                abilities = true;
            }
        }
        var pick = new com.moddingfromamod.species.BehaviourChoice.Pick(from, own, flies, swims, "vehicle".equals(own),
                spec.kind() == BuilderSpec.Kind.RIDE, powers, livesAs(spec.temper()), spec.size(), abilities);
        return com.moddingfromamod.species.BehaviourChoice.choose(pick).key();
    }

    /**
     * A sculpted creature's scale: its ready-made's own, times its named size or, for a spec from before named
     * sizes, its old step; within sculpt_creature's own 0.2–8.
     */
    static double creatureScale(BuilderSpec spec, JsonObject sculpt) {
        double by = spec.opt("mobsize").isEmpty() ? CREATURE_SCALE[spec.size() - 1]
                : BuilderMobSizes.of(spec.opt("mobsize")).scale();
        return Math.max(com.moddingfromamod.mob.MobTweak.MIN_SCALE, Math.min(com.moddingfromamod.mob.MobTweak.MAX_SCALE,
                scaleOf(sculpt) * by));
    }

    /** Powers a temperament decides: a body's own are dropped, so the child's pick is what it does. */
    static final List<String> TEMPER_WORDS = List.of("friendly", "hostile", "skittish", "boss");

    /**
     * A Builder BODY: the whole sculpt, sent as it is stored, with the child's picks applied — the
     * scheme's colours written onto its parts, its size, its powers. Not {@code start_from}: a body is not in
     * the wish library (so the wish worker cannot reach it until B0c says it should), and so it cannot be a
     * base the tool looks up.
     */
    private static void body(BuilderSpec spec, String newId, Presets presets, List<Call> calls) {
        String preset = spec.from();
        JsonObject sculpt = need(presets, preset);
        JsonObject a = new JsonObject();
        a.addProperty("id", newId);
        a.addProperty("name", spec.name().isEmpty() ? str(sculpt, "name") : spec.name());
        JsonArray parts = sculpt.getAsJsonArray("parts").deepCopy();
        Schemes.Scheme scheme = Schemes.resolve(spec);
        if (scheme != null) {
            JsonObject recolour = Schemes.recolour(parts, scheme);
            for (JsonElement e : parts) {
                JsonObject p = e.getAsJsonObject();
                if (p.has("name") && recolour.has(p.get("name").getAsString())) {
                    p.add("colour", recolour.get(p.get("name").getAsString()));
                }
            }
        }
        a.add("parts", parts);
        JsonArray palette = sculpt.has("palette") ? sculpt.getAsJsonArray("palette").deepCopy() : new JsonArray();
        if (scheme != null) {
            // The palette is what the sculpt is checked against: its first three shares take the scheme's colours,
            // the same ranks Schemes.recolour paints the parts by.
            for (int i = 0; i < palette.size(); i++) {
                String to = scheme.forRank(i, scheme.colours());
                if (to != null) {
                    palette.get(i).getAsJsonObject().addProperty("colour", to);
                }
            }
        }
        a.add("palette", palette);
        a.addProperty("note", "Builder make: " + label(spec) + " from the Builder body " + Catalogue.bare(preset) + ".");
        a.addProperty("scale", creatureScale(spec, sculpt));
        // behaves_like: the body's LIFE (a vehicle parks until driven, a butterfly buzzes) — it was dropped here, so
        // every Builder-made truck, boat and rocket wandered like a creature (follow-up).
        // behaves_like is not copied: behaviourOf chooses it below, keeping the body's own while nothing changes it.
        for (String k : new String[] {"health", "speed", "damage", "voice", "base"}) {
            if (sculpt.has(k)) {
                a.add(k, sculpt.get(k).deepCopy());
            }
        }
        defence(LibraryPresets.SHIPPED.species(Catalogue.bare(preset)), a);
        strength(spec, a, parts, BuilderParts.vehicle(preset, LibraryPresets.SHIPPED.species(Catalogue.bare(preset))));
        List<String> powers = new ArrayList<>(temperPowers(spec.temper()));
        // (was an earlier issue's flies alone): a body brings what it does by being itself (a plane flies, a rabbit hops, a
        // phoenix glows and never burns, BuilderNatural); every other power is his pick on the powers page. A make saved
        // has its body's other powers written out on read (SpecMigrations.readyBodyPowers).
        for (String p : BuilderNatural.of(preset, sculpt)) {
            if (!powers.contains(p)) {
                powers.add(p);
            }
        }
        if (spec.kind() == BuilderSpec.Kind.RIDE && !powers.contains("rideable")) {
            powers.add("rideable");
        }
        List<String> abilities = new ArrayList<>();
        for (String p : spec.powers()) {
            switch (Catalogue.prefix(p)) {
                case "power" -> {
                    if (!powers.contains(Catalogue.bare(p))) {
                        powers.add(Catalogue.bare(p));
                    }
                }
                case "ability" -> abilities.add(Catalogue.bare(p));
                default -> throw new IllegalArgumentException("'" + p + "' is not a creature power");
            }
        }
        sideOf(spec, powers, a);
        JsonArray pw = new JsonArray();
        powers.forEach(pw::add);
        a.add("powers", pw);
        // how it LIVES, chosen from the picks (BehaviourChoice). A ready-made species named none, so it lived
        // its carrier's life: a flying cat fluttered like the bat it rides.
        a.addProperty(com.moddingfromamod.species.BehavesLike.ARG, behaviourOf(spec, sculpt));
        speed(spec, a);
        // opts "spawn":"no": the design alone (#178: a world makes a champion once and spawns copies itself)
        a.addProperty("spawn", !"no".equals(spec.opt("spawn")));
        at(spec, a);
        // the parts he added, hung on the ready-made's own head or body.
        List<String> kits = BuilderParts.chosen(spec);
        if (!kits.isEmpty()) {
            JsonArray own = sculpt.getAsJsonArray("parts");
            // the body's room is the only limit on parts — sculpt_creature drops kits past it without a word.
            String tooBig = BuilderLimits.parts(BuilderParts.boxes(kits, own, ""), BuilderParts.budget(own.size()), str(sculpt, "name"));
            if (tooBig != null) {
                throw new IllegalArgumentException("parts: " + tooBig);
            }
            a.add("kits", BuilderParts.kits(kits, own, scheme == null ? "" : scheme.accent()));
        }
        calls.add(new Call("sculpt_creature", a));
        for (String ability : abilities) {
            calls.add(new Call("apply_effect", abilityArgs(ability, newId)));
        }
        if (spec.count() > 1) {
            JsonObject more = new JsonObject();
            JsonArray cmds = new JsonArray();
            cmds.add("wishmob spawn " + newId + " " + (spec.count() - 1));
            more.add("command", cmds);
            at(spec, more);
            calls.add(new Call("run_command", more));
        }
        withYou(spec, newId, calls);
    }

    // ------------------------------------------------------------------ items

    private static void item(BuilderSpec spec, Presets presets, List<Call> calls) {
        if (BuilderSnacks.composed(spec)) {
            // a composed snack machine — the snack first (the machine's OUTPUT must already exist), then the
            // fill machine that pours it. Both are whole make_item calls the worker could send; count is the machine's.
            List<JsonObject> made = BuilderSnacks.calls(spec);
            if (made.size() > 1) {
                calls.add(new Call("make_item", made.get(0)));   // "just yummy" makes no snack: it pours a vanilla food
            }
            JsonObject m = made.get(made.size() - 1);
            m.addProperty("only_looks_like", true);
            m.addProperty("count", spec.count());
            m.addProperty("as_new", true);
            calls.add(new Call("make_item", m));
            return;
        }
        JsonObject a = new JsonObject();
        if (BuilderBlocks.composed(spec)) {
            // a magic block composed in the Builder — its look, its trigger and what it does, at his strength.
            JsonObject made = BuilderBlocks.args(spec);
            for (String k : made.keySet()) {
                a.add(k, made.get(k));
            }
            a.addProperty("name", spec.name().isEmpty() ? label(spec) : spec.name());
        } else if (Catalogue.prefix(spec.from()).equals("preset")) {
            JsonObject stored = need(presets, presetOf(spec, "items"));
            // a snack machine's OUTPUT (TO) is an item of its own (popcorn_food), kept beside it in the Builder's
            // library under outputs/. The tool refuses an OUTPUT it does not know, so the food is made first;
            // make_item names it from its name and effect, which is the id OUTPUT already holds.
            // a one-in-one-out machine names it TO (turn_into), a fill machine OUTPUT.
            JsonObject sp = stored.has("params") ? stored.getAsJsonObject("params") : new JsonObject();
            String output = sp.has("TO") ? sp.get("TO").getAsString()
                    : sp.has("OUTPUT") ? sp.get("OUTPUT").getAsString() : "";
            JsonObject food = output.matches("[a-z0-9_]+") ? presets.read("outputs/" + output) : null;
            if (food != null) {
                JsonObject f = new JsonObject();
                for (String k : new String[] {"base_item", "params", "properties", "shape", "parts", "variants"}) {
                    if (food.has(k)) {
                        f.add(k, food.get(k).deepCopy());
                    }
                }
                if (food.has("effects") && food.getAsJsonArray("effects").size() > 0) {
                    f.addProperty("effect", food.getAsJsonArray("effects").get(0).getAsString());
                }
                f.addProperty("name", str(food, "name"));
                JsonObject ficon = presets.icon("outputs/" + output);
                if (ficon != null) {
                    f.addProperty("icon", str(ficon, "sprite"));
                    f.addProperty("colour", str(ficon, "tint"));
                }
                f.addProperty("count", food.has("count") ? food.get("count").getAsInt() : 1);   // make_item asks it by name
                f.addProperty("as_new", true);
                calls.add(new Call("make_item", f));
            }
            for (String k : new String[] {"base_item", "params", "properties", "shape", "parts", "variants"}) {
                if (stored.has(k)) {
                    a.add(k, stored.get(k).deepCopy());
                }
            }
            if (stored.has("effects") && stored.getAsJsonArray("effects").size() > 0) {
                a.addProperty("effect", stored.getAsJsonArray("effects").get(0).getAsString());
            }
            a.addProperty("name", spec.name().isEmpty() ? str(stored, "name") : spec.name());
            // make_item asks for icon and colour by name and never fills them in: the ready-made's own.
            JsonObject icon = presets.icon(Catalogue.bare(presetOf(spec, "items")));
            if (icon != null) {
                a.addProperty("icon", str(icon, "sprite"));
                a.addProperty("colour", str(icon, "tint"));
            }
        } else if (BuilderItemPowers.power(spec.opt("power")) != null) {
            // made up in the Builder — a stock item and a power its trigger fits, at his strength.
            BuilderItemPowers.Stock stock = BuilderItemPowers.of(spec);
            JsonObject made = stock == null ? null : BuilderItemPowers.args(stock.id(), spec.opt("power"),
                    spec.opt("strength").isEmpty() ? 2 : Integer.parseInt(spec.opt("strength")),
                    spec.opt("tier").isEmpty() ? BuilderItemPowers.TIER_ANY : spec.opt("tier"),   //
                    spec.opt(BuilderTurnInto.FROM_SLOT), spec.opt(BuilderTurnInto.TO_SLOT));      //
            if (made == null) {
                throw new IllegalArgumentException("'" + spec.opt("power") + "' does not fit a "
                        + (stock == null ? Catalogue.bare(spec.opt("shape")) : stock.id()));
            }
            for (String k : made.keySet()) {
                a.add(k, made.get(k));
            }
            BuilderLooks.Look look = BuilderLooks.look(spec.opt(BuilderLooks.SLOT));
            if (look != null) {
                a.addProperty("icon", look.id());   //: the library icon, by name, as the Magician's make_item takes it
                // in the look's own colour (a gold crown, a red cape, a pink cupcake: its tile's), not the stock
                // item's (a crown came out light blue, a cape white, on both editions). A colour scheme still wins below.
                a.addProperty("colour", String.format("#%06X", BuilderLooks.tileColour(look.id())));
                if (a.has("base_item")) {
                    a.addProperty("look", true);   //: a real item (a cookie, a leather helmet) drawn as the look
                }
            }
            a.addProperty("name", spec.name().isEmpty() ? BuilderLooks.name(spec, stock) : spec.name());
        } else {
            // From parts: an effect and a shape, both catalogue ids.
            String effect = spec.opt("effect");
            String shape = spec.opt("shape");
            if (effect.isEmpty()) {
                throw new IllegalArgumentException("an item needs a preset or an effect");
            }
            a.addProperty("effect", Catalogue.bare(effect));
            a.addProperty("shape", shape.isEmpty() ? defaultShape(spec.kind()) : Catalogue.bare(shape));
            a.addProperty("icon", spec.opt("icon").isEmpty() ? defaultIcon(spec.kind()) : Catalogue.bare(spec.opt("icon")));
            a.addProperty("colour", "#FFD54A");
            a.addProperty("name", spec.name().isEmpty() ? "My " + spec.kind().id().replace('_', ' ') : spec.name());
        }
        if (spec.kind() == BuilderSpec.Kind.MACHINE) {
            a.addProperty("shape", "machine");
        }
        String shape = a.has("shape") ? a.get("shape").getAsString() : "";
        if (shape.equals("machine") || shape.equals("magic_block")) {
            // A machine or a magic block only LOOKS like what its name says (a "Cookie Oven" is not a cookie):
            // without this make_item refuses the name and asks for the real item as its base (measured, B5's oven).
            a.addProperty("only_looks_like", true);
        }
        if (!a.has("base_item") && a.has("name")
                && com.moddingfromamod.item.RealItemHint.forName(a.get("name").getAsString()).isPresent()) {
            // the Builder has already chosen what the item is built on, so a name that names a real item
            // (a "Cookie Jar" look on a wand, a "Fishing Rod" look, a wand he renamed "Sword") is a LOOK, never a
            // missing base. make_item's real-item hint is for the Magician; without this it refuses the tile.
            a.addProperty("only_looks_like", true);
        }
        Schemes.Scheme scheme = Schemes.resolve(spec);
        if (scheme != null) {
            a.addProperty("colour", scheme.main());
            a.addProperty("accent", scheme.accent());
            // a real tool or armour piece is drawn as itself and takes no colour, so a scheme that IS a
            // material (gold) is built on that material's piece: a gold pickaxe is the golden pickaxe.
            String material = a.has("base_item")
                    ? BuilderItemPowers.inScheme(a.get("base_item").getAsString(), scheme.id()) : null;
            if (material != null) {
                a.addProperty("base_item", material);
            } else if (a.has("base_item")
                    && com.moddingfromamod.pack.TintedItems.modelFor(a.get("base_item").getAsString()) != null) {
                // every other scheme is DRAWN on the real item: its tinted model, in the scheme's main colour.
                a.addProperty("tint", true);
            }
        }
        a.addProperty("count", spec.count());
        a.addProperty("as_new", true);
        calls.add(new Call("make_item", a));
    }

    /**
     * a Builder special move ({@link BuilderAbilities}: an effect with its parameters fixed), or, as before,
     * a bare catalogue effect with the catalogue's defaults.
     */
    static JsonObject abilityArgs(String ability, String tag) {
        BuilderAbilities.Ability a = BuilderAbilities.byId(ability);
        if (a != null) {
            return BuilderAbilities.args(a, tag);
        }
        JsonObject e = new JsonObject();
        e.addProperty("effect", ability);
        e.addProperty("tag", tag);
        return e;
    }

    static String defaultIcon(BuilderSpec.Kind kind) {
        return switch (kind) {
            case MACHINE -> "machine";
            case MAGIC_BLOCK -> "block";
            default -> "wand";
        };
    }

    static String defaultShape(BuilderSpec.Kind kind) {
        return switch (kind) {
            case MACHINE -> "machine";
            case MAGIC_BLOCK -> "magic_block";
            default -> "held";
        };
    }

    // ------------------------------------------------------------------ buildings, pictures, events

    private static void building(BuilderSpec spec, String newId, Presets presets, List<Call> calls) {
        // a composed building is its type's body (that preset's shapes) with what goes outside added round it.
        BuilderBuildings.Type type = BuilderBuildings.composed(spec) ? BuilderBuildings.type(spec.opt("type")) : null;
        String preset = type != null ? "preset:" + type.preset() : BuilderHouses.composed(spec) ? "" : presetOf(spec, "buildings");
        // What goes outside: several things (follow-up), "garden,lamps", each fitting the type.
        String outside = type == null ? BuilderBuildings.NOTHING : BuilderBuildings.join(BuilderBuildings.parse(spec.opt("outside")));
        if (type != null && !BuilderBuildings.fits(type, outside)) {
            throw new IllegalArgumentException("'" + outside + "' does not go outside " + type.label().toLowerCase(Locale.ROOT));
        }
        boolean parts = BuilderHouses.composed(spec);   //: a simple building built from parts, not a body
        if (parts) {
            outside = BuilderBuildings.join(BuilderBuildings.parse(spec.opt("outside")));
            for (String id : BuilderBuildings.parse(outside)) {
                if (BuilderBuildings.outside(id) == null) {
                    throw new IllegalArgumentException("nothing called '" + id + "' goes outside a building");
                }
            }
        }
        JsonObject stored = parts ? null : need(presets, preset);
        JsonObject a = new JsonObject();
        a.addProperty("id", newId);
        a.addProperty("name", spec.name().isEmpty() ? (parts ? label(spec) : str(stored, "name")) : spec.name());
        JsonArray shapes = parts ? BuilderHouses.shapes(spec) : stored.getAsJsonArray("shapes").deepCopy();
        // a WORKING build (a farm plot, a pen, a nursery, a monster cave) is built exactly as stored: its layout is
        // what works, so it is never scaled, never re-blocked in a colour scheme, and never lit (the cave is dark ON
        // PURPOSE, so monsters spawn in it). The menus offer it no size and no colours; this holds for any spec.
        BuilderBuildings.Type body = parts ? null : BuilderBuildings.type(preset);
        boolean working = body != null && body.working();
        double f = parts || working ? 1.0 : BUILDING_SCALE[spec.size() - 1];   // floors are a simple building's size
        Schemes.Scheme scheme = working ? null : Schemes.resolve(spec);
        java.util.Map<String, String> reblock = scheme == null || scheme.mainBlock() == null ? java.util.Map.of()
                : Schemes.reblock(shapes, scheme);   // a world's colours-only palette keeps a building's own blocks
        if (f != 1.0) {
            scaleAll(shapes, f);
        }
        for (JsonElement e : shapes) {
            JsonObject s = e.getAsJsonObject();
            if (s.has("block") && reblock.containsKey(s.get("block").getAsString())) {
                s.addProperty("block", reblock.get(s.get("block").getAsString()));
            }
        }
        shapes.addAll(BuilderBuildings.shapes(outside, shapes));   // nothing for "nothing outside"
        if (!working) {
            light(shapes);
        }
        String over = overTheShapeCaps(shapes);
        if (over != null) {
            throw new IllegalArgumentException(label(spec) + " " + over);
        }
        a.add("shapes", shapes);
        if (stored != null && stored.has("spots")) {
            a.add("spots", stored.get("spots").deepCopy());
        }
        a.addProperty("note", "Builder make: " + label(spec) + (parts
                ? " built from parts (" + spec.opt("floors") + " floors, " + spec.opt("roof") + " roof, door " + spec.opt("door")
                        + ", " + spec.opt("windows") + " windows)"
                : " from the ready-made " + Catalogue.bare(preset))
                + (outside.equals(BuilderBuildings.NOTHING) ? "" : ", with " + BuilderBuildings.says(outside)) + ".");
        at(spec, a);
        calls.add(new Call("build_structure", a));
    }

    private static void picture(BuilderSpec spec, String newId, Presets presets, List<Call> calls) {
        // a composed picture is its subject's drawing, with what is behind it painted first, at a frame size.
        boolean composed = BuilderPictures.composed(spec);
        JsonObject stored = composed ? need(presets, BuilderPictures.subject(spec.opt("subject"))) : need(presets, presetOf(spec, "pictures"));
        JsonObject pic = new JsonObject();
        pic.add("size", stored.get("size"));
        JsonArray rects = stored.getAsJsonArray("rects").deepCopy();
        Schemes.Scheme scheme = Schemes.resolve(spec);
        if (scheme != null) {
            java.util.Map<String, String> to = Schemes.recolourRects(rects, scheme);
            for (JsonElement e : rects) {
                JsonObject r = e.getAsJsonObject();
                String c = r.has("colour") ? r.get("colour").getAsString().toUpperCase(Locale.ROOT) : "";
                if (to.containsKey(c)) {
                    r.addProperty("colour", to.get(c));
                }
            }
        }
        String behind = composed ? spec.opt("background") : BuilderPictures.DRAWN;
        if (composed && !BuilderPictures.fitting(stored).contains(BuilderPictures.background(behind))) {
            throw new IllegalArgumentException("'" + behind + "' cannot go behind " + spec.opt("subject"));
        }
        JsonArray all = BuilderPictures.rects(behind, stored.get("size").getAsInt());   // under the drawing; its colours stay
        all.addAll(rects);
        pic.add("rects", all);
        JsonObject a = new JsonObject();
        a.addProperty("id", newId);
        a.addProperty("picture", pic.toString());
        // The frame: the size he set, else the drawing's own (a 3x3 duck hangs 3x3, as its library file says).
        int frame = composed && !spec.opt("frame").isEmpty() ? Integer.parseInt(spec.opt("frame")) : BuilderPictures.ownFrame(stored);
        a.addProperty("wide", frame);
        a.addProperty("tall", frame);
        a.addProperty("note", "Builder make: " + label(spec) + ".");
        at(spec, a);
        calls.add(new Call("paint_surface", a));
    }

    private static void event(BuilderSpec spec, String newId, Presets presets, List<Call> calls) {
        JsonObject a;
        if (Catalogue.prefix(spec.from()).equals("preset")) {
            a = need(presets, presetOf(spec, "events")).deepCopy();
        } else if (BuilderEvents.byId(spec.opt("event")) != null) {
            // made up in the Builder — a reviewed motion and what it is made of.
            a = BuilderEvents.args(spec.opt("event"), spec.opt("what"));
            if (a == null) {
                throw new IllegalArgumentException("'" + spec.opt("what") + "' is not something a " + spec.opt("event") + " is made of here");
            }
            BuilderEvents.WreckChoice wreck = BuilderEvents.wrecks(spec.opt("event")) ? BuilderEvents.wreck(spec.opt("wreck")) : null;
            if (wreck != null) {
                com.google.gson.JsonArray spares = new com.google.gson.JsonArray();
                wreck.spares().forEach(spares::add);
                a.add("spares", spares);   // what it leaves alone
            }
            if (BuilderEvents.spins(spec.opt("event")) && BuilderEvents.path(spec.opt("path")) != null) {
                a.addProperty("path", BuilderEvents.path(spec.opt("path")));   // How it moves, when he picked
            }
        } else {
            String motion = spec.opt("motion");
            if (motion.isEmpty()) {
                throw new IllegalArgumentException("an event needs a preset or a motion");
            }
            a = new JsonObject();
            a.addProperty("motion", Catalogue.bare(motion));
            if (!spec.opt("material").isEmpty()) {
                a.addProperty("material", spec.opt("material"));
            }
        }
        a.addProperty("name", newId);
        if (spec.name().isEmpty() && BuilderEvents.byId(spec.opt("event")) != null) {
            a.addProperty("title", BuilderEvents.title(spec.opt("event"), spec.opt("what")));
        } else if (!spec.name().isEmpty()) {
            // its own words across his screen — the stock name keeps its preset title's apostrophe
            // ("It's raining chickens!", not the tune dialog's stripped "Its raining chickens").
            a.addProperty("title", BuilderWords.eventWords(spec));
        }
        a.addProperty("size", EVENT_SIZE[spec.size() - 1]);
        if (spec.count() > 1) {
            a.addProperty("count", spec.count());
        }
        at(spec, a);
        calls.add(new Call("stage_event", a));
    }

    // ------------------------------------------------------------------ rules

    private static void rule(BuilderSpec spec, String newId, List<Call> calls) {
        String when = spec.opt("when");
        String does = spec.opt("does");
        if (when.isEmpty() || does.isEmpty()) {
            throw new IllegalArgumentException("a rule needs a 'when' and a 'does'");
        }
        RuleEffects.Effect effect = RuleEffects.byId(does);
        if (effect == null) {
            throw new IllegalArgumentException("no rule effect '" + does + "'");
        }
        JsonObject a = effect.args().deepCopy();
        String trigger = Catalogue.bare(when).toLowerCase(Locale.ROOT);
        if (!effect.fits(trigger)) {
            throw new IllegalArgumentException("'" + effect.label() + "' cannot follow '" + trigger + "'");
        }
        a.addProperty("when", trigger);
        if (RuleEffects.MOB_TRIGGERS.contains(trigger)) {
            // about a mob he picked. set_rule's `who` is the vanilla id.
            String who = spec.opt("who");
            if (!Catalogue.prefix(who).equals("mob")) {
                throw new IllegalArgumentException("'" + trigger + "' needs a mob ('who')");
            }
            if (!effect.fitsMob(who)) {
                throw new IllegalArgumentException("'" + effect.label() + "' does not show on a " + Catalogue.bare(who));
            }
            a.addProperty("who", Catalogue.bare(who));
        } else if (!List.of("sunrise", "sunset", "rain_starts", "rain_stops", "every").contains(trigger)) {
            a.addProperty("who", "player");
        }
        for (var e : spec.opts().entrySet()) {
            String k = e.getKey();
            if (k.equals("when") || k.equals("does") || k.equals("who")) {
                continue;
            }
            if (!RULE_ARGS.contains(k)) {
                throw new IllegalArgumentException("set_rule takes no '" + k + "' from the Builder");
            }
            String v = e.getValue();
            if (v.matches("-?\\d*\\.\\d+")) {
                a.addProperty(k, Double.parseDouble(v));
            } else {
                a.addProperty(k, v);
            }
        }
        // set_rule's `name` is the rule's id (WorldRule.clean), never words he reads.
        a.addProperty("name", newId);
        calls.add(new Call("set_rule", a));
    }

    /** The {@code set_rule} arguments a Builder rule may set beyond its trigger and effect. */
    static final List<String> RULE_ARGS = List.of("chance", "only");

    // ------------------------------------------------------------------ helpers

    private static String presetOf(BuilderSpec spec, String dir) {
        if (dir.equals("species") && Catalogue.bare(spec.from()).startsWith("bodies/")) {
            return spec.from();
        }
        if (!Catalogue.prefix(spec.from()).equals("preset")
                || !Catalogue.bare(spec.from()).startsWith(dir + "/")) {
            throw new IllegalArgumentException("a " + spec.kind().id() + " starts from a preset:" + dir
                    + "/… ready-made, not '" + spec.from() + "'");
        }
        return spec.from();
    }

    private static JsonObject need(Presets presets, String preset) {
        JsonObject j = presets == null ? null : presets.read(Catalogue.bare(preset));
        if (j == null) {
            throw new IllegalArgumentException("the ready-made " + Catalogue.bare(preset) + " cannot be read");
        }
        return j;
    }

    private static double scaleOf(JsonObject sculpt) {
        return sculpt.has("scale") && sculpt.get("scale").isJsonPrimitive() ? sculpt.get("scale").getAsDouble() : 1.0;
    }

    private static void at(BuilderSpec spec, JsonObject a) {
        if (!spec.at().isEmpty()) {
            a.addProperty("at", spec.at());
        }
    }

    static String label(BuilderSpec spec) {
        if (BuilderSupplies.is(spec) && BuilderSupplies.supply(spec.from()) != null) {
            return BuilderSupplies.words(BuilderSupplies.supply(spec.from()), BuilderSupplies.amount(spec));   //: "16 oak logs"
        }
        if (BuilderSnacks.composed(spec)) {
            return BuilderSnacks.machineName(spec);   //: "Your Zoom Pizza Machine is ready!"
        }
        if (!spec.name().isEmpty()) {
            return spec.name();
        }
        if (BuilderItemPowers.power(spec.opt("power")) != null) {
            BuilderItemPowers.Stock stock = BuilderItemPowers.of(spec);   //
            return BuilderLooks.name(spec, stock);
        }
        if (BuilderEvents.byId(spec.opt("event")) != null) {
            return BuilderEvents.title(spec.opt("event"), spec.opt("what"));   //
        }
        if (BuilderBlocks.composed(spec)) {   //
            return BuilderBlocks.name(BuilderBlocks.look(spec.opt("block_look")), BuilderBlocks.does(spec.opt("block_does")));
        }
        if (BuilderHouses.composed(spec)) {
            return BuilderHouses.name(BuilderHouses.simple(spec.opt("type")));   //: "House"
        }
        String from = BuilderBuildings.composed(spec)
                ? BuilderBuildings.type(spec.opt("type")).preset()   //: called what its body was ("cozy house")
                : BuilderPictures.composed(spec) ? BuilderPictures.subject(spec.opt("subject"))   //: "duck"
                : Catalogue.bare(spec.from());
        if (from == null || from.isEmpty()) {
            // Said as "Your new rule is ready!" and "Making your new rule..." ("your a rule" was the first film's).
            return "new " + spec.kind().id().replace('_', ' ');
        }
        BuilderSnacks.Ready ready = BuilderSnacks.READY_MADES.get(from);
        if (ready != null) {
            return BuilderSnacks.machine(BuilderSnacks.look(ready.look())).name();   //: "Pizza Oven", its own name
        }
        BuilderBlocks.Ready block = BuilderBlocks.READY_MADES.get(from);
        if (block != null) {
            return block.name();   //: "Lucky Block"
        }
        return from.substring(from.lastIndexOf('/') + 1).replace('_', ' ');
    }

    private static String str(JsonObject j, String k) {
        JsonElement e = j.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** A name inside an SNBT double-quoted string: letters, digits, spaces and a little punctuation. */
    static String nbtSafe(String name) {
        return name.replaceAll("[^\\p{L}\\p{N} .!?'-]", "").strip();
    }
}
