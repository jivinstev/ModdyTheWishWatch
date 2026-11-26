package com.moddingfromamod.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.place.At;
import com.moddingfromamod.place.PlaceFinder;
import com.moddingfromamod.redstone.ControlSpots;
import com.moddingfromamod.redstone.DeviceModule;
import com.moddingfromamod.redstone.Devices;
import com.moddingfromamod.redstone.Control;
import com.moddingfromamod.redstone.Doorway;
import com.moddingfromamod.redstone.LevelView;
import com.moddingfromamod.redstone.MechanismPlan;
import com.moddingfromamod.redstone.MechanismPlanner;
import com.moddingfromamod.redstone.MechanismProof;
import com.moddingfromamod.redstone.MechanismStore;
import com.moddingfromamod.redstone.MechanismWorld;
import com.moddingfromamod.redstone.Module;
import com.moddingfromamod.redstone.PistonDoor;
import com.moddingfromamod.redstone.RsDir;
import com.moddingfromamod.redstone.RsPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Builds a working redstone mechanism into the world: the agent says WHAT (the mechanism and its controls) and
 * WHERE (the {@code at} places); every block, facing, wire route, repeater and casing is the planner's
 * ({@code redstone/}). The mechanism is then PROVED in the world — each control used for real and the outputs
 * checked, twice over — and taken back down if the proof fails, so a broken machine never stays.
 */
public final class BuildMechanismTool implements McpTool {

    static final List<String> MECHANISMS = List.of("piston_door", "iron_door", "lamps", "gate", "dispensers",
            "droppers", "note_blocks");
    static final List<String> INSTRUMENTS = List.of("harp", "bell", "flute", "chime", "guitar", "xylophone", "bass",
            "basedrum", "snare", "hat", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling");
    static final List<String> CONTROL_KINDS = List.of("lever", "button", "pressure_plate", "logic");
    /** Always-on clocks ("every") per world before a new one is built switched off (the owner's cap; value measured later). */
    static final int CLOCK_CAP = 8;

    @Override
    public String name() {
        return "build_mechanism";
    }

    @Override
    public String description() {
        return "Build a WORKING redstone mechanism into the world, wired to the switches the child asked for. "
                + "Use it for anything that MOVES, LIGHTS, SHOOTS or PLAYS with a switch, button, pressure plate or "
                + "lever: doors, lights, gates, dispensers and droppers, note blocks. You choose only WHAT "
                + "(`mechanism`, `controls`, a few options) and WHERE (`at`); the tool works out every block, wire, "
                + "repeater and facing, hides the wiring underground, builds it, then TESTS it by using each control "
                + "for real. If the test fails it takes everything back down and says why; nothing half-working is "
                + "left. Mechanisms: "
                + "piston_door: a flush 2-tall door of the wall's own blocks (or `material`), in the doorway of the "
                + "wall at `at` (its existing doorway, 1 or 2 wide, or a new one). "
                + "iron_door: an iron door (1 or 2 wide) in that doorway. "
                + "For both doors a switch, button or plate OPENS it (shut the rest of the time); on:\"close\" when he "
                + "says the switch CLOSES it. A door shut the rest of the time always gets a way to open it from "
                + "inside. "
                + "lamps: `count` redstone lamps set flush into the floor of the building at `at` (or the ground round "
                + "a spot), lit by the switches (`inverted`: lit until switched off). "
                + "gate: the fence gate(s) near `at` (or a new one there) opened by the switches. "
                + "dispensers / droppers: `count` of them on the ground at `at`, loaded with `items`, facing `aim`; "
                + "a switch fires them (fireworks: aim up; arrows: aim at a place). "
                + "note_blocks: one note block per entry of `notes` (0-24) on `instrument`, played by the switches "
                + "(a doorbell is one note). "
                + "Remove one with remove_mechanism.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        JsonObject mech = new JsonObject();
        mech.addProperty("type", "string");
        JsonArray kinds = new JsonArray();
        MECHANISMS.forEach(kinds::add);
        mech.add("enum", kinds);
        mech.addProperty("description", "Which mechanism. piston_door: a hidden door of the wall's own blocks.");
        s.getAsJsonObject("properties").add("mechanism", mech);
        Schemas.prop(s, "at", "string", At.describe("the mechanism",
                "its front",
                "For a door: the building whose doorway gets it (\"<house>:front\" is its entrance), or \"me:front\" "
                        + "for the wall he is looking at."));
        JsonObject controls = new JsonObject();
        controls.addProperty("type", "array");
        controls.addProperty("maxItems", 4);
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject kind = new JsonObject();
        kind.addProperty("type", "string");
        JsonArray ck = new JsonArray();
        CONTROL_KINDS.forEach(ck::add);
        kind.add("enum", ck);
        kind.addProperty("description", "A switch (lever) stays on or off; a button and a pressure plate are only on "
                + "while pressed or stood on.");
        props.add("kind", kind);
        JsonObject cat = new JsonObject();
        cat.addProperty("type", "string");
        cat.addProperty("description", "Where it goes, in the same <thing>:<spot> words: \"<house>:front\" puts it "
                + "outside beside the door, \"<house>:inside\" inside beside it, \"me:here\" where he stands.");
        props.add("at", cat);
        JsonObject tg = new JsonObject();
        tg.addProperty("type", "boolean");
        tg.addProperty("description", "A button or plate that TOGGLES: press once to switch it on and it stays on, "
                + "press again to switch it off (\"opens when I press it and closes when I press it again\").");
        props.add("toggle", tg);
        JsonObject hd = new JsonObject();
        hd.addProperty("type", "boolean");
        hd.addProperty("description", "A SECRET switch: a button (or lever) set low on the nearest wall beside that "
                + "place, where nobody would look, instead of one standing on the floor.");
        props.add("hidden", hd);
        JsonObject when = new JsonObject();
        when.addProperty("type", "object");
        when.addProperty("description", "Only for kind \"logic\": WHEN it switches on, for what a lever cannot tell "
                + "apart. {\"if\": one of player_near (only the players in `who`, or anyone; \"only me\" is his name), "
                + "mob_near (`who`: mob ids or \"monster\"), paid (`item`, `count`: put in the barrel it places), "
                + "code (`code`: the order of the code buttons it places, e.g. \"2,0,1\"), every (`seconds` apart, on "
                + "for `for_seconds`: a clock), night, day, target_hit (a target block it places; on for `for_seconds`); "
                + "`radius` for the near ones}. A clock past the world's limit is built switched off.");
        props.add("when", when);
        item.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("kind");
        req.add("at");
        item.add("required", req);
        controls.add("items", item);
        controls.addProperty("description", "The switches that work it, one entry each (at least one).");
        s.getAsJsonObject("properties").add("controls", controls);
        Schemas.prop(s, "material", "string", "Optional: what the door is MADE of, a block id (e.g. "
                + "\"minecraft:iron_block\" for a bank vault, \"minecraft:gold_block\"). Leave it out and the door is the "
                + "wall's own block, so it disappears into the wall when shut (a secret door). It must be a plain full "
                + "block a piston can push: not glass, not obsidian, not a chest.");
        JsonObject on = new JsonObject();
        on.addProperty("type", "string");
        JsonArray onv = new JsonArray();
        onv.add("open");
        onv.add("close");
        on.add("enum", onv);
        on.addProperty("description", "What switching it ON (or pressing, or standing on) does: \"open\" (the default) "
                + "or \"close\" (\"a door I can shut with a lever\"). The other way round happens when it is off.");
        s.getAsJsonObject("properties").add("on", on);
        Schemas.prop(s, "count", "integer", "How many lamps, dispensers or droppers (1-12; default 4 lamps, 1 else).");
        Schemas.prop(s, "items", "string", "For dispensers/droppers: what to load, \"<item id>*<count>\" joined with ';' "
                + "(e.g. \"minecraft:arrow*64\", \"minecraft:firework_rocket*32\", \"minecraft:cookie*64\").");
        Schemas.prop(s, "aim", "string", "For dispensers/droppers: up, down, north/south/east/west, or a place "
                + "(\"<thing>:<spot>\") to face. Default up.");
        Schemas.list(s, "notes", "For note_blocks: the notes, 0 (low F#) to 24 (high F#), one block each; 12 is "
                + "middle F#. Write them as numbers.", 24);
        Schemas.prop(s, "instrument", "string", "For note_blocks: harp (default), bell, flute, chime, guitar, "
                + "xylophone, bass, basedrum, snare, hat, iron_xylophone, cow_bell, didgeridoo, bit, banjo, pling.");
        Schemas.prop(s, "new_opening", "boolean", "Doors: cut a NEW doorway in the building's wall nearest `at` (a "
                + "secret passage, a hidden room) instead of using its entrance. Use a compass spot (\"<id>:west\") for "
                + "the wall you mean.");
        Schemas.prop(s, "inverted", "boolean", "Lamps and other devices: ON until a switch turns them OFF.");
        Schemas.prop(s, "width", "integer", "Only for a NEW doorway: 1 or 2 wide (default 2). An existing doorway "
                + "keeps its own width.");
        Schemas.required(s, "mechanism", "at", "controls");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String mechanism = str(args, "mechanism");
        if (!MECHANISMS.contains(mechanism)) {
            return ToolResult.error("mechanism must be one of " + MECHANISMS + ", not '" + mechanism + "'.");
        }
        String at = str(args, "at");
        if (At.parse(at) == null) {
            return ToolResult.error("at must read \"<thing>:<spot>\" (a building's id, a creature or me), not '"
                    + at + "'.");
        }
        List<String[]> asked = new ArrayList<>();
        JsonElement ce = args.get("controls");
        if (ce == null || !ce.isJsonArray() || ce.getAsJsonArray().isEmpty()) {
            return ToolResult.error("controls needs at least one {kind, at}: a mechanism nobody can switch is no use.");
        }
        for (JsonElement e : ce.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                return ToolResult.error("each control is an object {kind, at}.");
            }
            String k = str(e.getAsJsonObject(), "kind").toLowerCase(Locale.ROOT);
            String a = str(e.getAsJsonObject(), "at");
            if (!CONTROL_KINDS.contains(k)) {
                return ToolResult.error("a control's kind is one of " + CONTROL_KINDS + ", not '" + k + "'.");
            }
            if (At.parse(a) == null) {
                return ToolResult.error("a control's at must read \"<thing>:<spot>\", not '" + a + "'.");
            }
            boolean tog = e.getAsJsonObject().has("toggle") && e.getAsJsonObject().get("toggle").getAsBoolean();
            boolean hid = e.getAsJsonObject().has("hidden") && e.getAsJsonObject().get("hidden").getAsBoolean();
            if (tog && k.equals("lever")) {
                tog = false;                  // a lever already stays where it is put
            }
            if (hid && k.equals("pressure_plate")) {
                return ToolResult.error("a hidden control is a button or a lever on a wall; a pressure plate cannot be one.");
            }
            String whenJson = "";
            if (k.equals("logic")) {
                JsonElement w = e.getAsJsonObject().get("when");
                if (w == null || !w.isJsonObject() || !w.getAsJsonObject().has("if")
                        || !com.moddingfromamod.redstone.Logic.KINDS.contains(w.getAsJsonObject().get("if").getAsString())) {
                    return ToolResult.error("a logic control needs when: {\"if\": one of "
                            + com.moddingfromamod.redstone.Logic.KINDS + ", …}.");
                }
                whenJson = w.toString();
            }
            asked.add(new String[] {k, a, tog ? "toggle" : "", hid ? "hidden" : "", whenJson});
        }
        String material = str(args, "material");
        String onArg = str(args, "on").toLowerCase(Locale.ROOT);
        if (!onArg.isEmpty() && !onArg.equals("open") && !onArg.equals("close")) {
            return ToolResult.error("on is \"open\" or \"close\".");
        }
        boolean opens = !onArg.equals("close");
        int count = args.has("count") && args.get("count").isJsonPrimitive() ? args.get("count").getAsInt() : 0;
        if (count < 0 || count > 12) {
            return ToolResult.error("count is 1 to 12.");
        }
        String items = str(args, "items");
        String itemsWhy = itemsProblem(items);
        if (itemsWhy != null) {
            return ToolResult.error(itemsWhy);
        }
        List<Integer> notes = new ArrayList<>();
        if (args.has("notes") && args.get("notes").isJsonArray()) {
            for (JsonElement n : args.getAsJsonArray("notes")) {
                try {
                    notes.add(Integer.parseInt(n.getAsString().trim()));
                } catch (RuntimeException e) {
                    return ToolResult.error("notes are numbers from 0 to 24, not '" + n + "'.");
                }
            }
        }
        String instrument = str(args, "instrument").isEmpty() ? "harp" : str(args, "instrument").toLowerCase(Locale.ROOT);
        if (!INSTRUMENTS.contains(instrument)) {
            return ToolResult.error("instrument is one of " + INSTRUMENTS + ".");
        }
        boolean inverted = args.has("inverted") && args.get("inverted").isJsonPrimitive()
                && args.get("inverted").getAsBoolean();
        boolean newOpening = args.has("new_opening") && args.get("new_opening").isJsonPrimitive()
                && args.get("new_opening").getAsBoolean();
        Spec spec = new Spec(mechanism, at, asked, 0, material, opens, count, items, str(args, "aim"), notes,
                instrument, inverted, newOpening);
        int width = args.has("width") && args.get("width").isJsonPrimitive() ? args.get("width").getAsInt() : 2;
        if (width != 1 && width != 2) {
            return ToolResult.error("width is 1 or 2.");
        }

        // a building still going up would lay its blocks over the mechanism: wait for it first (up to 30 s)
        for (int i = 0; i < 150 && AgentBridge.onServer(com.moddingfromamod.builder.Builder::anyBuilding, 5_000L); i++) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 1-3: resolve, plan and place on the server thread
        Built built;
        try {
            Spec sp = new Spec(spec.mechanism(), spec.at(), spec.asked(), width, spec.material(), spec.opens(),
                    spec.count(), spec.items(), spec.aim(), spec.notes(), spec.instrument(), spec.inverted(),
                    spec.newOpening());
            built = AgentBridge.onServer(() -> buildOnServer(sp), 30_000L);
        } catch (RuntimeException e) {
            return ToolResult.error("Could not build it: " + e.getMessage());
        }
        if (built.error != null) {
            return built.error;
        }
        // 4: prove it, off the server thread (it waits for the game to tick)
        MechanismProof.Result proof = MechanismProof.prove(built.level, built.plan);
        if (!proof.ok()) {
            AgentBridge.onServer(() -> {
                MechanismWorld.takeBack(built.level, built.placed);
                return null;
            }, 30_000L);
            return ToolResult.error("Built it, TESTED it, and it did not work, so it was taken back down and the world is "
                    + "as it was. What failed: " + proof.why() + ". Try a different spot or control, or tell the "
                    + "child this one could not be made here.");
        }
        String id = AgentBridge.onServer(() -> {
            String fresh = MechanismStore.freshId(built.level, mechanism);
            List<BlockPos> controls = built.plan.controls().stream().map(c -> LevelView.bp(c.use())).toList();
            MechanismStore.put(built.level, new MechanismStore.Entry(fresh, mechanism, built.owner,
                    built.level.dimension().location().toString(), built.summary, built.placed, controls, built.logic));
            return fresh;
        }, 30_000L);
        StringBuilder b = new StringBuilder();
        b.append("Built and TESTED '").append(id).append("': ").append(built.summary).append('\n');
        for (Control c : built.plan.controls()) {
            b.append("- a ").append(c.kind().replace('_', ' ')).append(" at ").append(c.use().x()).append(' ')
                    .append(c.use().y()).append(' ').append(c.use().z()).append('\n');
        }
        b.append("Test: ").append(String.join("; ", proof.steps())).append('\n');
        built.plan.notes().forEach(n -> b.append("Note: ").append(n).append('\n'));
        b.append(built.shutAtRest ? "It is SHUT now; a switch on (or a press) opens it."
                        : built.plan.kind().equals("piston_door") || built.plan.kind().equals("iron door")
                        ? "It is OPEN now; a switch on shuts it." : "It is in its resting state now.")
                .append(" Tell the child which switch does what. remove_mechanism id '").append(id)
                .append("' takes it all back out.");
        return ToolResult.ok(b.toString());
    }

    /** What the server-thread half made: the plan, what it placed, or the error to return. */
    static final class Built {
        ServerLevel level;
        MechanismPlan plan;
        List<MechanismWorld.Placed> placed;
        String owner = "", summary = "";
        ToolResult error;
        boolean shutAtRest;
        com.google.gson.JsonArray logic = new com.google.gson.JsonArray();

        static Built fail(ToolResult r) {
            Built b = new Built();
            b.error = r;
            return b;
        }
    }

    /** Everything the agent asked for, parsed. */
    record Spec(String mechanism, String at, List<String[]> asked, int width, String material, boolean opens,
                int count, String items, String aim, List<Integer> notes, String instrument, boolean inverted,
                boolean newOpening) {
        Spec(String mechanism, String at, List<String[]> asked, int width, String material, boolean opens, int count,
             String items, String aim, List<Integer> notes, String instrument, boolean inverted) {
            this(mechanism, at, asked, width, material, opens, count, items, aim, notes, instrument, inverted, false);
        }
    }

    static Built buildOnServer(String mechanism, String at, List<String[]> asked, int width, String wanted,
                               boolean opens) {
        return buildOnServer(new Spec(mechanism, at, asked, width, wanted, opens, 0, "", "", List.of(), "harp", false));
    }

    static Built buildOnServer(Spec spec) {
        List<String[]> asked = spec.asked();
        var server = AgentBridge.serverOrNull();
        ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);
        if (player == null) {
            return Built.fail(ToolResult.noPlayerOnline("Nobody is in the world to build it for."));
        }
        PlaceFinder.Place place = PlaceFinder.find(server, player, spec.at(), com.moddingfromamod.builder.BuildSpots.FRONT);
        if (!place.ok()) {
            return Built.fail(ToolResult.error("Could not find " + spec.at() + ": " + place.why()));
        }
        ServerLevel level = place.level();
        LevelView view = new LevelView(level);
        BlockPos feet = place.block();
        RsPos start;
        RsDir in;
        if ("building".equals(place.kind())) {
            start = LevelView.rs(feet);
            in = snap(place.ox() - (feet.getX() + 0.5), place.oz() - (feet.getZ() + 0.5));
        } else {
            // a body (him, a creature): the wall he is looking at, from where he stands
            start = LevelView.rs(player.blockPosition());
            double r = Math.toRadians(player.getYRot());
            in = snap(-Math.sin(r), Math.cos(r));
        }
        int[] box = place.buildId() == null ? null : PlaceFinder.buildingBox(place.buildId());
        boolean isDoor = spec.mechanism().equals("piston_door") || spec.mechanism().equals("iron_door");
        Doorway.Found door = null;
        if (isDoor) {
            door = box != null ? Doorway.inBuilding(view, box, start, spec.width(), spec.newOpening(),
                    spec.mechanism().equals("iron_door") ? 1 : 4)
                    : Doorway.find(view, start, in, spec.width());
            if (!door.ok()) {
                return Built.fail(ToolResult.error("No door could go there: " + door.why() + "."));
            }
        } else if (box != null) {
            Doorway.Found d = Doorway.inBuilding(view, box, start, 2);
            door = d.ok() ? d : null;     // only to put switches "at the door"
        }
        RsPos ground = LevelView.rs(feet).up(-1);
        Module module;
        String summary;
        boolean shutAtRest = false;
        RsPos centre;
        String wall = door == null ? null : door.wallBlock();
        switch (spec.mechanism()) {
            case "piston_door" -> {
                String towerTop = conducts(level, wall) ? wall : "minecraft:stone";
                String material = wall;
                if (!spec.material().isEmpty()) {
                    String why = doorBlockProblem(level, spec.material());
                    if (why != null) {
                        return Built.fail(ToolResult.error("The door cannot be made of " + spec.material() + ": " + why
                                + ". Leave material out for the wall's own block, or pick a plain full block."));
                    }
                    material = spec.material().contains(":") ? spec.material() : "minecraft:" + spec.material();
                } else if (doorBlockProblem(level, wall) != null) {
                    material = towerTop;           // a glass wall: the door is stone rather than nothing
                }
                module = PistonDoor.plan(door.frame(), door.width(), material, towerTop, wall, spec.opens());
                shutAtRest = spec.opens();
                summary = "a hidden piston door " + door.width() + " wide and 2 tall in the "
                        + (door.existing() ? "doorway" : "new doorway") + " at " + where(door.frame().origin())
                        + ", made of " + material;
                centre = door.frame().origin();
            }
            case "iron_door" -> {
                List<DeviceModule.Device> devs = new ArrayList<>();
                for (int u = 0; u < door.width(); u++) {
                    devs.add(Devices.ironDoor(door.frame().at(u, 0, 0), door.frame().in(), u == 1));
                }
                DeviceModule.Result r = DeviceModule.plan(view, "iron door", devs, spec.opens(), keep(level, view, devs),
                        new ArrayList<>(List.of("an iron door: " + (spec.opens() ? "switched on (or pressed) it OPENS"
                                : "switched on it CLOSES"))));
                if (!r.ok()) {
                    return Built.fail(ToolResult.error("The iron door could not be wired: " + r.why() + "."));
                }
                module = r.module();
                shutAtRest = spec.opens();
                summary = "an iron door " + door.width() + " wide in the " + (door.existing() ? "doorway" : "new doorway")
                        + " at " + where(door.frame().origin());
                centre = door.frame().origin();
            }
            default -> {
                int n = spec.count() > 0 ? spec.count() : defaultCount(spec.mechanism(), spec.notes().size());
                List<DeviceModule.Device> devs = new ArrayList<>();
                String what;
                switch (spec.mechanism()) {
                    case "lamps" -> {
                        List<RsPos> at2 = box != null ? Devices.floorGrid(view, box, n, MechanismStore.keepClear(level))
                                : Devices.groundRing(view, ground, n, 2);
                        at2.forEach(p -> devs.add(Devices.lamp(p)));
                        what = devs.size() + " lamp(s) set into the " + (box != null ? "floor" : "ground");
                    }
                    case "gate" -> {
                        List<RsPos> gates = existingGates(level, ground, box, n);
                        if (gates.isEmpty()) {
                            gates = List.of(ground.up(1));
                        }
                        for (RsPos g : gates) {
                            var st = level.getBlockState(LevelView.bp(g));
                            String id = st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                                    ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString()
                                    : "minecraft:oak_fence_gate";
                            RsDir f = st.hasProperty(net.minecraft.world.level.block.FenceGateBlock.FACING)
                                    ? RsDir.valueOf(st.getValue(net.minecraft.world.level.block.FenceGateBlock.FACING).name())
                                    : in;
                            devs.add(Devices.fenceGate(g, f, id));
                        }
                        what = devs.size() + " gate(s)";
                    }
                    case "dispensers", "droppers" -> {
                        boolean dropper = spec.mechanism().equals("droppers");
                        String facing = aimFacing(server, player, spec.aim(), ground);
                        for (RsPos g : Devices.groundRing(view, ground, n, 1)) {
                            devs.add(Devices.dispenser(g.up(1), facing, spec.items(), dropper));
                        }
                        what = devs.size() + " " + spec.mechanism() + " facing " + facing
                                + (spec.items().isEmpty() ? " (empty)" : ", loaded with " + spec.items().replace(";", ", "));
                    }
                    case "note_blocks" -> {
                        List<Integer> notes = spec.notes().isEmpty() ? List.of(12) : spec.notes();
                        List<RsPos> spots = Devices.groundRing(view, ground, notes.size(), 1);
                        for (int i = 0; i < spots.size(); i++) {
                            devs.add(Devices.noteBlock(spots.get(i).up(1), Math.max(0, Math.min(24, notes.get(i))),
                                    spec.instrument()));
                        }
                        what = devs.size() + " note block(s) (" + spec.instrument() + ")";
                    }
                    default -> {
                        return Built.fail(ToolResult.error("Unknown mechanism " + spec.mechanism() + "."));
                    }
                }
                if (devs.isEmpty()) {
                    return Built.fail(ToolResult.error("There was no room for any " + spec.mechanism() + " at "
                            + spec.at() + " (they need flat solid ground with room above)."));
                }
                DeviceModule.Result r = DeviceModule.plan(view, spec.mechanism(), devs, !spec.inverted(),
                        keep(level, view, devs),
                        new ArrayList<>(List.of(spec.inverted() ? "on at rest; a switch turns them OFF"
                                : "a switch turns them ON")));
                if (!r.ok()) {
                    return Built.fail(ToolResult.error("The " + spec.mechanism() + " could not be wired: " + r.why()
                            + ". Try fewer, or a flatter spot."));
                }
                module = r.module();
                summary = what + " at " + spec.at();
                centre = devs.get(0).feed();
            }
        }

        // A door shut at rest with every switch outside would shut him in: add a button inside beside it.
        String addedInside = null;
        if (shutAtRest && place.buildId() != null && door != null) {
            boolean anyInside = false;
            for (String[] c : asked) {
                PlaceFinder.Place cp = PlaceFinder.find(server, player, c[1], com.moddingfromamod.builder.BuildSpots.FRONT);
                if (cp.ok() && ("inside".equals(cp.r().spot())
                        || ControlSpots.side(door.frame(), LevelView.rs(cp.block())) > 0)) {
                    anyInside = true;
                }
            }
            if (!anyInside) {
                asked = new ArrayList<>(asked);
                asked.add(new String[] {"button", place.buildId() + ":inside", "", "", ""});
                addedInside = "a button inside beside the door, so nobody is shut in";
            }
        }
        // controls: each resolved, then the first spot the planner can wire
        Set<RsPos> foreign = new java.util.HashSet<>(view.foreignRedstone(centre.plus(-24, -10, -24),
                centre.plus(24, 10, 24)));
        foreign.addAll(MechanismStore.keepClear(level));
        List<MechanismPlanner.ControlAsk> chosen = new ArrayList<>();
        List<String> notHidden = new ArrayList<>();
        List<LogicParts> logicAsks = new ArrayList<>();
        MechanismPlan plan = null;
        for (String[] c : asked) {
            PlaceFinder.Place cp = PlaceFinder.find(server, player, c[1], com.moddingfromamod.builder.BuildSpots.FRONT);
            if (!cp.ok()) {
                return Built.fail(ToolResult.error("Could not find the " + c[0] + "'s place " + c[1] + ": " + cp.why()));
            }
            RsPos spot = LevelView.rs(cp.block());
            // a switch at the mechanism's own building goes beside its door: inside for "inside", else outside
            boolean sameBuilding = cp.buildId() != null && cp.buildId().equals(place.buildId()) && door != null;
            List<RsPos> tries = sameBuilding
                    ? ControlSpots.atDoor(door.frame(), door.width(), c[0], "inside".equals(cp.r().spot()) ? 1 : -1)
                    : door != null && ControlSpots.nearDoor(door.frame(), door.width(), spot)
                    ? ControlSpots.atDoor(door.frame(), door.width(), c[0], ControlSpots.side(door.frame(), spot))
                    : ControlSpots.around(spot.up(-1));
            if (c[0].equals("logic")) {
                JsonObject when = com.google.gson.JsonParser.parseString(c[4]).getAsJsonObject();
                LogicParts lp = logicParts(level, view, spot.up(-1), when);
                List<MechanismPlanner.ControlAsk> withIt = new ArrayList<>(chosen);
                withIt.add(new MechanismPlanner.ControlAsk("logic", lp.sensorGround, null, false));
                MechanismPlan p = MechanismPlanner.plan(view, module, withIt, foreign);
                if (!p.ok()) {
                    return Built.fail(ToolResult.error("The logic at " + c[1] + " could not be wired: " + p.why()
                            + ". Nothing was built."));
                }
                chosen = withIt;
                plan = p;
                logicAsks.add(lp);
                continue;
            }
            boolean toggle = c.length > 2 && "toggle".equals(c[2]);
            boolean hidden = c.length > 3 && "hidden".equals(c[3]);
            List<MechanismPlanner.ControlAsk> options = new ArrayList<>();
            if (hidden) {
                for (RsPos guess : tries) {
                    RsPos g = groundNear(view, guess);
                    if (g != null) {
                        options.addAll(wallSpots(view, g.up(1), c[0], toggle));
                    }
                }
            } else {
                for (RsPos guess : tries) {
                    RsPos g = groundNear(view, guess);
                    if (g != null) {
                        options.add(new MechanismPlanner.ControlAsk(c[0], g, null, toggle));
                    }
                }
            }
            String last = options.isEmpty() ? (hidden ? "there is no wall near " + c[1] + " to hide it on"
                    : "there is no ground to stand it on near " + c[1]) : null;
            MechanismPlan ok = null;
            for (MechanismPlanner.ControlAsk ask : options) {
                List<MechanismPlanner.ControlAsk> withIt = new ArrayList<>(chosen);
                withIt.add(ask);
                MechanismPlan p = MechanismPlanner.plan(view, module, withIt, foreign);
                if (p.ok()) {
                    ok = p;
                    chosen = withIt;
                    break;
                }
                last = p.why();
            }
            if (ok == null && !hidden) {
                // every usual spot blocked (another mechanism's wiring is there, say): further out, up to 6 blocks
                outer:
                for (int r = 2; r <= 6; r++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                continue;
                            }
                            RsPos g = groundNear(view, spot.up(-1).plus(dx, 0, dz));
                            if (g == null) {
                                continue;
                            }
                            List<MechanismPlanner.ControlAsk> withIt = new ArrayList<>(chosen);
                            withIt.add(new MechanismPlanner.ControlAsk(c[0], g, null, toggle));
                            MechanismPlan p = MechanismPlanner.plan(view, module, withIt, foreign);
                            if (p.ok()) {
                                ok = p;
                                chosen = withIt;
                                break outer;
                            }
                            last = p.why();
                        }
                    }
                }
            }
            if (ok == null && hidden) {
                // no wall there lets its wire go down out of sight: an ordinary switch, and say so
                for (RsPos guess : tries) {
                    RsPos g = groundNear(view, guess);
                    if (g == null) {
                        continue;
                    }
                    List<MechanismPlanner.ControlAsk> withIt = new ArrayList<>(chosen);
                    withIt.add(new MechanismPlanner.ControlAsk(c[0], g, null, toggle));
                    MechanismPlan p = MechanismPlanner.plan(view, module, withIt, foreign);
                    if (p.ok()) {
                        ok = p;
                        chosen = withIt;
                        notHidden.add(c[0] + " at " + c[1]);
                        break;
                    }
                    last = p.why();
                }
            }
            if (ok == null) {
                return Built.fail(ToolResult.error("The " + c[0] + " at " + c[1] + " could not be wired: " + last
                        + ". Nothing was built."));
            }
            plan = ok;
        }
        if (addedInside != null) {
            plan.notes().add("Added " + addedInside + ".");
        }
        if (!notHidden.isEmpty()) {
            plan.notes().add("Could not hide the " + String.join(", ", notHidden) + " in a wall there (no wall whose "
                    + "wiring could go down out of sight), so it stands on the floor. Tell him, or try a spot by an "
                    + "outside wall.");
        }
        // the logic rules: each logic control's cell, in order, gets its rule and its parts (placed last, not wired)
        com.google.gson.JsonArray logic = new com.google.gson.JsonArray();
        if (!logicAsks.isEmpty()) {
            List<com.moddingfromamod.redstone.RsCell> cells = new ArrayList<>(plan.cells());
            int li = 0;
            int clocks = countClocks(level);
            for (Control ctl : plan.controls()) {
                if (!ctl.kind().equals("logic")) {
                    continue;
                }
                LogicParts lp = logicAsks.get(li++);
                cells.addAll(lp.cells);
                JsonObject o = lp.json.deepCopy();
                o.add("cell", arr(ctl.use()));
                if (o.getAsJsonObject("rule").get("kind").getAsString().equals("every")) {
                    if (clocks >= CLOCK_CAP) {
                        o.addProperty("disabled", true);
                        plan.notes().add("This world already has " + clocks + " always-on clocks, the most it runs (each "
                                + "one costs the game time every tick), so this one is built SWITCHED OFF. Tell him: "
                                + "taking out another clocked mechanism (remove_mechanism) frees a slot.");
                    }
                    clocks++;
                }
                lp.describe.forEach(plan.notes()::add);
                logic.add(o);
            }
            plan = new MechanismPlan(plan.kind(), cells, plan.expects(), plan.controls(), plan.replaced(), plan.notes(),
                    null);
        }
        Built b = new Built();
        b.logic = logic;
        b.level = level;
        b.plan = plan;
        b.owner = player.getGameProfile().getName();
        b.summary = summary + (plan.replaced().isEmpty() ? "" : "; it replaced " + plan.replaced().size() + " block(s)");
        b.shutAtRest = shutAtRest;
        b.placed = MechanismWorld.place(level, plan);
        return b;
    }

    /**
     * Wall faces for a hidden control near {@code feet} (the level a player stands at): a solid block at that level
     * with open, walkable air in front of it, within 3 blocks; nearest first.
     */
    static List<MechanismPlanner.ControlAsk> wallSpots(LevelView view, RsPos feet, String kind, boolean toggle) {
        List<MechanismPlanner.ControlAsk> out = new ArrayList<>();
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    RsPos a = feet.plus(dx, 0, dz);
                    var k = view.kind(a);
                    if (!(k == com.moddingfromamod.redstone.WorldView.Kind.SOLID
                            || k == com.moddingfromamod.redstone.WorldView.Kind.NATURAL) || !view.conductor(a)) {
                        continue;
                    }
                    for (RsDir f : RsDir.values()) {
                        RsPos front = a.step(f);
                        if (view.kind(front) == com.moddingfromamod.redstone.WorldView.Kind.AIR
                                && view.kind(front.up(1)) == com.moddingfromamod.redstone.WorldView.Kind.AIR
                                && view.conductor(front.up(-1))) {
                            out.add(new MechanismPlanner.ControlAsk(kind, a, f, toggle));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** A logic control's rule, its visible parts (not wired) and where its sensor stands. */
    static final class LogicParts {
        RsPos sensorGround;
        final List<com.moddingfromamod.redstone.RsCell> cells = new ArrayList<>();
        final JsonObject json = new JsonObject();
        final List<String> describe = new ArrayList<>();
    }

    static com.google.gson.JsonArray arr(RsPos p) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        a.add(p.x());
        a.add(p.y());
        a.add(p.z());
        return a;
    }

    static int countClocks(ServerLevel level) {
        int n = 0;
        for (MechanismStore.Entry e : MechanismStore.all(level)) {
            for (JsonElement l : e.logic()) {
                JsonObject o = l.getAsJsonObject();
                if (o.getAsJsonObject("rule").get("kind").getAsString().equals("every")
                        && !(o.has("disabled") && o.get("disabled").getAsBoolean())) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Builds a logic control's rule from `when`, and places its parts near `ground` (the block under the spot). */
    static LogicParts logicParts(ServerLevel level, LevelView view, RsPos ground, JsonObject when) {
        LogicParts lp = new LogicParts();
        lp.sensorGround = ground;
        String kind = when.get("if").getAsString();
        JsonObject rule = new JsonObject();
        rule.addProperty("kind", kind);
        int onFor = 20 * (when.has("for_seconds") ? Math.max(1, Math.min(60, when.get("for_seconds").getAsInt())) : 5);
        rule.addProperty("onFor", onFor);
        rule.addProperty("radius", when.has("radius") ? Math.max(1, Math.min(16, when.get("radius").getAsInt())) : 4);
        if (when.has("who")) {
            com.google.gson.JsonArray who = new com.google.gson.JsonArray();
            JsonElement w = when.get("who");
            if (w.isJsonArray()) {
                w.getAsJsonArray().forEach(x -> who.add(x.getAsString()));
            } else {
                who.add(w.getAsString());
            }
            rule.add("who", who);
        }
        List<RsPos> ring = Devices.groundRing(view, ground, 6, 1);
        switch (kind) {
            case "paid" -> {
                String item = when.has("item") ? when.get("item").getAsString() : "minecraft:emerald";
                rule.addProperty("item", item.contains(":") ? item : "minecraft:" + item);
                rule.addProperty("count", when.has("count") ? Math.max(1, when.get("count").getAsInt()) : 1);
                if (!ring.isEmpty()) {
                    RsPos barrel = ring.get(0).up(1);
                    lp.cells.add(com.moddingfromamod.redstone.RsCell.of(barrel, "minecraft:barrel",
                            com.moddingfromamod.redstone.RsCell.Role.CONTROL, "facing", "up", "open", "false"));
                    lp.json.add("slot", arr(barrel));
                    lp.describe.add("The coin slot is the barrel at " + where(barrel) + ": put "
                            + rule.get("count").getAsInt() + " " + rule.get("item").getAsString() + " in it.");
                }
            }
            case "code" -> {
                List<Integer> code = new ArrayList<>();
                for (String x : (when.has("code") ? when.get("code").getAsString() : "1,0,2").split(",")) {
                    code.add(Integer.parseInt(x.trim()));
                }
                int n = code.stream().mapToInt(Integer::intValue).max().orElse(2) + 1;
                com.google.gson.JsonArray codeArr = new com.google.gson.JsonArray();
                code.forEach(codeArr::add);
                rule.add("code", codeArr);
                com.google.gson.JsonArray buttons = new com.google.gson.JsonArray();
                List<RsPos> row = Devices.groundRing(view, ground, n, 1);
                for (int i = 0; i < row.size(); i++) {
                    RsPos b = row.get(i).up(1);
                    lp.cells.add(com.moddingfromamod.redstone.RsCell.of(b, "minecraft:stone_button",
                            com.moddingfromamod.redstone.RsCell.Role.CONTROL, "face", "floor", "facing", "north",
                            "powered", "false"));
                    buttons.add(arr(b));
                }
                lp.json.add("buttons", buttons);
                StringBuilder d = new StringBuilder("The code buttons are numbered 0.." + (row.size() - 1) + ":");
                for (int i = 0; i < row.size(); i++) {
                    d.append(" ").append(i).append(" at ").append(where(row.get(i).up(1))).append(';');
                }
                d.append(" the code is ").append(code).append(" (tell him, or let him find it).");
                lp.describe.add(d.toString());
            }
            case "every" -> {
                int secs = when.has("seconds") ? Math.max(2, Math.min(600, when.get("seconds").getAsInt())) : 30;
                rule.addProperty("period", secs * 20);
                rule.addProperty("onFor", Math.min(onFor, secs * 20 - 10));
            }
            case "target_hit" -> {
                if (!ring.isEmpty()) {
                    RsPos t = ring.get(0).up(1);
                    lp.cells.add(com.moddingfromamod.redstone.RsCell.of(t, "minecraft:target",
                            com.moddingfromamod.redstone.RsCell.Role.CONTROL, "power", "0"));
                    lp.json.add("target", arr(t));
                    lp.describe.add("The target is at " + where(t) + ": an arrow that hits it switches it on.");
                }
            }
            default -> {
            }
        }
        lp.json.add("rule", rule);
        lp.json.add("sensor", arr(ground.up(1)));
        return lp;
    }

    /** Redstone already round {@code devs}, and every other mechanism's blocks with a block's clearance. */
    static Set<RsPos> keep(ServerLevel level, LevelView view, List<DeviceModule.Device> devs) {
        RsPos c = devs.isEmpty() ? new RsPos(0, 0, 0) : devs.get(0).feed();
        Set<RsPos> k = new java.util.HashSet<>(view.foreignRedstone(c.plus(-24, -12, -24), c.plus(24, 10, 24)));
        k.addAll(MechanismStore.keepClear(level));
        return k;
    }

    static String where(RsPos p) {
        return p.x() + " " + p.y() + " " + p.z();
    }

    static int defaultCount(String mechanism, int notes) {
        return switch (mechanism) {
            case "lamps" -> 4;
            case "note_blocks" -> Math.max(1, notes);
            default -> 1;
        };
    }

    /** Fence gates already standing near {@code ground} (or in the building's box), nearest first. */
    static List<RsPos> existingGates(ServerLevel level, RsPos ground, int[] box, int n) {
        List<RsPos> out = new ArrayList<>();
        int r = 8;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    RsPos p = ground.plus(dx, dy, dz);
                    if (level.getBlockState(LevelView.bp(p)).getBlock()
                            instanceof net.minecraft.world.level.block.FenceGateBlock) {
                        out.add(p);
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt(p -> p.manhattan(ground)));
        return out.subList(0, Math.min(Math.max(n, 1), out.size()));
    }

    /** A dispenser's facing from `aim`: up/down/a compass word, or toward a place; up when nothing is said. */
    static String aimFacing(net.minecraft.server.MinecraftServer server, ServerPlayer player, String aim, RsPos from) {
        String a = aim == null ? "" : aim.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || a.equals("up") || a.equals("sky")) {
            return "up";
        }
        if (List.of("down", "north", "south", "east", "west").contains(a)) {
            return a;
        }
        if (At.parse(a) != null) {
            PlaceFinder.Place t = PlaceFinder.find(server, player, a, com.moddingfromamod.builder.BuildSpots.FRONT);
            if (t.ok()) {
                return snap(t.r().x() - (from.x() + 0.5), t.r().z() - (from.z() + 0.5)).id();
            }
        }
        return "up";
    }

    /**
     * The real ground in {@code guess}'s column: the block a control stands on, with room above it. A doorway is
     * often a step above the ground outside (a raised floor) or below it, so the guess is searched 2 up to 3 down.
     */
    static RsPos groundNear(LevelView view, RsPos guess) {
        for (int dy = 2; dy >= -3; dy--) {
            RsPos g = guess.up(dy);
            var above = view.kind(g.up(1));
            var k = view.kind(g);
            if ((k == com.moddingfromamod.redstone.WorldView.Kind.SOLID
                    || k == com.moddingfromamod.redstone.WorldView.Kind.NATURAL) && view.conductor(g)
                    && above == com.moddingfromamod.redstone.WorldView.Kind.AIR
                    && view.kind(g.up(2)) == com.moddingfromamod.redstone.WorldView.Kind.AIR) {
                return g;
            }
        }
        return null;
    }

    /** Null when {@code items} reads "<item id>*<count>;…" with real items. */
    static String itemsProblem(String items) {
        if (items.isEmpty()) {
            return null;
        }
        for (String part : items.split(";")) {
            String[] ic = part.trim().split("\\*");
            var rl = net.minecraft.resources.ResourceLocation.tryParse(ic[0].contains(":") ? ic[0] : "minecraft:" + ic[0]);
            if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl)) {
                return "items: there is no item '" + ic[0] + "' (write \"minecraft:arrow*64;minecraft:cookie*16\").";
            }
            if (ic.length > 1) {
                try {
                    int n = Integer.parseInt(ic[1].trim());
                    if (n < 1 || n > 576) {
                        return "items: a count is 1 to 576 (a dispenser holds 9 stacks).";
                    }
                } catch (NumberFormatException e) {
                    return "items: '" + part + "' should read <item>*<count>.";
                }
            }
        }
        return null;
    }

    /** Null when {@code id} is a block a door can be made of: full, solid, pushable, no block entity. */
    static String doorBlockProblem(ServerLevel level, String id) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl)) {
            return "there is no such block";
        }
        var state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(rl).defaultBlockState();
        if (state.hasBlockEntity()) {
            return "it holds things, and a piston cannot move it";
        }
        if (state.getPistonPushReaction() != net.minecraft.world.level.material.PushReaction.NORMAL) {
            return "a piston cannot push it";
        }
        if (!state.isCollisionShapeFullBlock(level, BlockPos.ZERO)) {
            return "it is not a full block, so the door would have gaps";
        }
        return null;
    }

    static boolean conducts(ServerLevel level, String id) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl)) {
            return false;
        }
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(rl).defaultBlockState()
                .isRedstoneConductor(level, BlockPos.ZERO);
    }

    static RsDir snap(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? RsDir.EAST : RsDir.WEST;
        }
        return dz >= 0 ? RsDir.SOUTH : RsDir.NORTH;
    }

    static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }
}
