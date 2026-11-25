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

    static final List<String> MECHANISMS = List.of("piston_door");
    static final List<String> CONTROL_KINDS = List.of("lever", "button", "pressure_plate");

    @Override
    public String name() {
        return "build_mechanism";
    }

    @Override
    public String description() {
        return "Build a WORKING redstone mechanism into the world, wired to the switches the child asked for. "
                + "Use it for anything that MOVES or SWITCHES with redstone: a secret or vault door that opens and "
                + "closes, a door worked by a lever, a button or a pressure plate. You choose only WHAT (`mechanism`, "
                + "`controls`) and WHERE (`at`); the tool works out every block, wire, repeater and facing, hides "
                + "the wiring underground, builds it, then TESTS it by using each control for real. If the test "
                + "fails it takes everything back down and says why; nothing half-working is left. "
                + "piston_door: a flush 2-tall door of the wall's own blocks, put into the doorway of the wall at "
                + "`at` (its existing doorway, 1 or 2 wide, or a new 2-wide one). By default a switch, button or plate "
                + "OPENS it (shut the rest of the time); on:\"close\" when he says the switch CLOSES it. Remove one "
                + "with remove_mechanism.";
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
            asked.add(new String[] {k, a});
        }
        String material = str(args, "material");
        String onArg = str(args, "on").toLowerCase(Locale.ROOT);
        if (!onArg.isEmpty() && !onArg.equals("open") && !onArg.equals("close")) {
            return ToolResult.error("on is \"open\" or \"close\".");
        }
        boolean opens = !onArg.equals("close");
        int width = args.has("width") && args.get("width").isJsonPrimitive() ? args.get("width").getAsInt() : 2;
        if (width != 1 && width != 2) {
            return ToolResult.error("width is 1 or 2.");
        }

        // 1-3: resolve, plan and place on the server thread
        Built built;
        try {
            built = AgentBridge.onServer(() -> buildOnServer(mechanism, at, asked, width, material, opens), 30_000L);
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
                    built.level.dimension().location().toString(), built.summary, built.placed, controls));
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
        b.append(opens ? "It is SHUT now; a switch on (or a press) opens it." : "It is OPEN now; a switch on shuts it.")
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

        static Built fail(ToolResult r) {
            Built b = new Built();
            b.error = r;
            return b;
        }
    }

    static Built buildOnServer(String mechanism, String at, List<String[]> asked, int width, String wanted,
                               boolean opens) {
        var server = AgentBridge.serverOrNull();
        ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);
        if (player == null) {
            return Built.fail(ToolResult.noPlayerOnline("Nobody is in the world to build it for."));
        }
        PlaceFinder.Place place = PlaceFinder.find(server, player, at, com.moddingfromamod.builder.BuildSpots.FRONT);
        if (!place.ok()) {
            return Built.fail(ToolResult.error("Could not find " + at + ": " + place.why()));
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
        Doorway.Found door = box != null ? Doorway.inBuilding(view, box, start, width)
                : Doorway.find(view, start, in, width);
        if (!door.ok()) {
            return Built.fail(ToolResult.error("No door could go there: " + door.why() + "."));
        }
        String wall = door.wallBlock();
        String towerTop = conducts(level, wall) ? wall : "minecraft:stone";
        String material = wall;
        if (!wanted.isEmpty()) {
            String why = doorBlockProblem(level, wanted);
            if (why != null) {
                return Built.fail(ToolResult.error("The door cannot be made of " + wanted + ": " + why
                        + ". Leave material out for the wall's own block, or pick a plain full block."));
            }
            material = wanted.contains(":") ? wanted : "minecraft:" + wanted;
        } else if (doorBlockProblem(level, wall) != null) {
            material = towerTop;           // a glass wall: the door is stone rather than nothing
        }
        Module module = PistonDoor.plan(door.frame(), door.width(), material, towerTop, wall, opens);

        // A door shut at rest with every switch outside would shut him in: add a button inside beside it.
        boolean anyInside = false;
        for (String[] c : asked) {
            PlaceFinder.Place cp = PlaceFinder.find(server, player, c[1], com.moddingfromamod.builder.BuildSpots.FRONT);
            if (cp.ok() && ("inside".equals(cp.r().spot())
                    || ControlSpots.side(door.frame(), LevelView.rs(cp.block())) > 0)) {
                anyInside = true;
            }
        }
        String addedInside = null;
        if (opens && !anyInside && place.buildId() != null) {
            asked = new ArrayList<>(asked);
            asked.add(new String[] {"button", place.buildId() + ":inside"});
            addedInside = "a button inside beside the door, so nobody is shut in";
        }
        // controls: each resolved, then the first spot the planner can wire
        Set<RsPos> foreign = view.foreignRedstone(door.frame().origin().plus(-24, -8, -24),
                door.frame().origin().plus(24, 8, 24));
        List<MechanismPlanner.ControlAsk> chosen = new ArrayList<>();
        MechanismPlan plan = null;
        for (String[] c : asked) {
            PlaceFinder.Place cp = PlaceFinder.find(server, player, c[1], com.moddingfromamod.builder.BuildSpots.FRONT);
            if (!cp.ok()) {
                return Built.fail(ToolResult.error("Could not find the " + c[0] + "'s place " + c[1] + ": " + cp.why()));
            }
            RsPos spot = LevelView.rs(cp.block());
            // a switch at the door's own building goes beside its door: inside for "inside", else outside
            boolean sameBuilding = cp.buildId() != null && cp.buildId().equals(place.buildId());
            List<RsPos> tries = sameBuilding
                    ? ControlSpots.atDoor(door.frame(), door.width(), c[0],
                            "inside".equals(cp.r().spot()) ? 1 : -1)
                    : ControlSpots.nearDoor(door.frame(), door.width(), spot)
                    ? ControlSpots.atDoor(door.frame(), door.width(), c[0], ControlSpots.side(door.frame(), spot))
                    : ControlSpots.around(spot.up(-1));
            String last = null;
            MechanismPlan ok = null;
            for (RsPos guess : tries) {
                RsPos ground = groundNear(view, guess);
                if (ground == null) {
                    last = "there is no ground to stand it on near " + guess;
                    continue;
                }
                List<MechanismPlanner.ControlAsk> withIt = new ArrayList<>(chosen);
                withIt.add(new MechanismPlanner.ControlAsk(c[0], ground));
                MechanismPlan p = MechanismPlanner.plan(view, module, withIt, foreign);
                if (p.ok()) {
                    ok = p;
                    chosen = withIt;
                    break;
                }
                last = p.why();
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
        Built b = new Built();
        b.level = level;
        b.plan = plan;
        b.owner = player.getGameProfile().getName();
        b.summary = "a hidden piston door " + door.width() + " wide and 2 tall in the "
                + (door.existing() ? "doorway" : "new doorway") + " at " + door.frame().origin().x() + " "
                + door.frame().origin().y() + " " + door.frame().origin().z() + ", made of " + material
                + (plan.replaced().isEmpty() ? "" : "; it replaced " + plan.replaced().size() + " block(s)");
        b.placed = MechanismWorld.place(level, plan);
        return b;
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
