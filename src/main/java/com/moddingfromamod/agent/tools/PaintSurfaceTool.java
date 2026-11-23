package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.paint.ModPaint;
import com.moddingfromamod.paint.PaintArt;
import com.moddingfromamod.paint.PaintPanelBlockEntity;
import com.moddingfromamod.paint.PaintPanels;
import com.moddingfromamod.paint.PaintRecipe;
import com.moddingfromamod.paint.PaintRecord;
import com.moddingfromamod.paint.PaintRegistry;
import com.moddingfromamod.paint.PanelGeometry;
import com.moddingfromamod.runtime.ModelRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * <b>Put a real picture on a surface</b> — "paint Spikor on the side", "put a rainbow on that wall".
 *
 * <p>Why it exists at all: a picture made of coloured blocks is <b>nine pixels</b>, and that
 * is not tunable, because a block model can only draw what is in the stitched block atlas. This
 * paints one that is drawn by a renderer instead, so its detail has nothing to do with how many
 * blocks it covers — 65,536 pixels in the space that used to hold nine.
 *
 * <p><b>The description below deliberately names no ONE kind of thing.</b> {@code PhotographTool}'s
 * {@code subject} said "the <i>creature</i> id" and every framing path for a building, a train or an
 * item was skipped for years because the agent read the field as creature-only. A surface is
 * a surface — a wall, a car, a tower, the side of anything.
 */
public final class PaintSurfaceTool implements McpTool {

    @Override
    public String name() {
        return "paint_surface";
    }

    @Override
    public String description() {
        return "Paint a real picture on a flat surface — "
                + "a BUILDING's "
                + "wall, the front of a shop, the side of a MACHINE, anything with a face you can "
                + "see. It is not for creatures: a creature's own colours are paint_creature. "
                + "Use this whenever a wish "
                + "asks for a PICTURE OF something on something: a character's face, a logo, a "
                + "rainbow, a sign. Do NOT try to make a picture out of coloured blocks — a block "
                + "face is about nine pixels and a character comes out as a coloured square. This "
                + "draws thousands of pixels in the same space. Describe the picture as coloured "
                // ⚠ THE SECOND PLACE THIS FILE TAUGHT A BACKDROP. Fixing the `picture` schema's
                // worked example and leaving this sentence alone would have changed nothing: the
                // tool DESCRIPTION is read first and said "background, then shapes on top of it".
                // Exactly the rule CLAUDE.md keeps recording -- when you fix a shape in one place,
                // grep for it in the others before you close.
                + "rectangles, biggest first, and NEVER a background: whatever you do not paint is "
                + "see-through, so the blocks behind show past the picture. "
                + "Give 'wide'/'tall' to hang it across several blocks — one panel draws the whole "
                + "picture, so a 3x2 face needs ONE call, not six. "
                + "To CHANGE a picture he already has, call list_paintings first and pass its id "
                + "as start_from with only the shapes that change — never redraw it from nothing, "
                + "which loses everything you do not describe again. "
                // BlockyBob was painted from memory -- 17 rectangles that looked nothing
                // like him -- because nothing on this path ever said "look it up first".
                + "A PICTURE OF ANYTHING WITH A NAME (a game, cartoon or TV character, a mascot, a "
                + "YouTuber — 'a painting of Captain Comet'): call look_up_character FIRST, every "
                + "time, even for a character you are sure you know, and paint with what it "
                + "returns, never from memory. A "
                + "real PERSON is painted as their MINECRAFT SKIN: pass 'from_skin' with the NAME "
                // the public name is often somebody else's account ("Stampy" is a
                // stranger; he plays as stampylongnose), so the model never supplies a username.
                + "THEY ARE KNOWN BY and no 'picture' — never a username: the server paints only "
                + "the account an outside source links to that person. If none is linked it "
                + "fetches nothing and says so; then paint them from look_up_character's colours "
                + "and description. "
                // step 4: the spike's 8x6 board stretched a square face to 4:3.
                + "THE PICTURE IS SQUARE and is STRETCHED to fill wide x tall, so a face or "
                + "anything round goes on a SQUARE board (wide = tall); on a 2x1 board everything "
                + "comes out twice as wide.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "status", "string",
                "A SHORT kid-facing line saying what you are doing, shown the instant this call "
                        + "arrives, e.g. 'Painting the frog knight on the side'. Pass it EVERY time: it is "
                        + "free here, whereas a separate set_status costs the child a wait.");
        Schemas.prop(s, "id", "string",
                "A short name for this picture, lower-case, e.g. 'turtle_king_face'. Used to store it "
                        + "and to put it back if the wish is undone.");
        Schemas.prop(s, "start_from", "string",
                "CHANGE a picture he already has instead of redrawing it: its id (see "
                        + "list_paintings). The picture keeps every shape it had and the "
                        + "rectangles you send in 'picture' are painted ON TOP — so send ONLY what "
                        + "changes (\"make the sun bigger\" = one bigger sun rectangle). It stays "
                        + "where it hangs. If it is another child's or one of the built-in "
                        + "pictures, he gets HIS OWN copy on a new board and theirs is untouched "
                        + "— that is automatic, you do not choose it.");
        Schemas.prop(s, "as_new", "boolean",
                "With start_from: make a SECOND picture from it under the new 'id' (\"another "
                        + "one but at night\") on its own board, instead of changing the first.");
        Schemas.prop(s, "x", "integer", "OPTIONAL. Where the picture's BOTTOM-LEFT block goes (as "
                + "you look at it). Use look_around to find a surface. Leave x/y/z/facing out and "
                + "the picture goes on a new board in front of him, where the wish was aimed — "
                + "and that is also how a picture he made in ANOTHER world is hung again here.");
        Schemas.prop(s, "y", "integer", "The bottom of the picture.");
        Schemas.prop(s, "z", "integer",
                "The third coordinate of that same bottom-left block. Left blank in the first cut "
                + "of this schema, which its own test caught: an unexplained required field is "
                + "guessed at.");
        Schemas.prop(s, "at", "string", com.moddingfromamod.place.At.describe("the picture",
                "its front wall",
                "A picture hangs on a BUILDING's wall: at:\"<building>:<wall>\" with front, beside, behind or "
                        + "north/east/south/west (\"paint a flag on my castle's wall\" → at:\"<castle id>\"). His "
                        + "words pick the wall: none means the front; a SIDE of it is beside (or a compass side), "
                        + "never the front; its back is behind. It "
                        + "finds a flat patch the picture's size, at his eye height, middle first. Leave x/y/z "
                        + "and facing out when you pass it."));
        Schemas.prop(s, "facing", "string",
                "Which way the picture LOOKS: north, south, east or west. Get this wrong and the "
                        + "picture faces into the wall.");
        Schemas.prop(s, "wide", "integer",
                "How many blocks wide, 1-" + PanelGeometry.MAX_SPAN + " (default 1). It grows to "
                        + "the RIGHT as a viewer sees it.");
        Schemas.prop(s, "tall", "integer",
                "How many blocks tall, 1-" + PanelGeometry.MAX_SPAN + " (default 1). It grows UP, "
                        + "so the block you name is never buried.");
        Schemas.prop(s, "from_skin", "string",
                "The NAME a real public figure is KNOWN BY, e.g. 'BlockyBob' — INSTEAD of "
                        + "'picture'. NEVER a username, even one you are sure of: the account "
                        + "with the public name is often somebody else's. The server looks up "
                        + "the account minecraft.wiki or the generated creator list links to "
                        + "that person and paints that skin pixel for pixel; if no account is linked it fetches NOTHING and "
                        + "says so, and you paint them from look_up_character's colours "
                        + "instead. Only for a public figure (the server checks, so never pass a "
                        + "friend's or a sibling's name). The board's shape is set for you so the "
                        + "skin is not stretched.");
        Schemas.prop(s, "skin_view", "string",
                "With from_skin: 'face' (default) — their head, on a square board; or 'front' — "
                        + "the whole player standing, on a board twice as tall as wide.");
        Schemas.prop(s, "note", "string",
                com.moddingfromamod.provenance.ProvenanceContext.NOTE_HINT);
        // ⚠ THE EXAMPLE IS THE INSTRUCTION. This one used to open with a full-canvas green rect
        // and say "the background goes first", and every agent that read it duly painted one:
        // measured across 15 real textures from a Spikor train, ALL were 100% opaque with a solid
        // backdrop, so every Kroakling sat on a flat coloured slab instead of on the carriage.
        // Nothing was broken -- the picture was exactly what had been asked for. Same shape as the
        // copycat-disguise trap in CLAUDE.md: a schema that teaches a wrong form is followed.
        Schemas.prop(s, "picture", "string",
                "The picture, as JSON: {\"size\":256,\"rects\":[{\"x\":28,\"y\":24,\"w\":200,"
                        + "\"h\":210,\"colour\":\"green\"},{\"x\":74,\"y\":96,\"w\":28,\"h\":28,"
                        + "\"colour\":\"white\"}]}. x/y/w/h are in PICTURE pixels (0,0 is the "
                        + "top-left), NOT blocks. Painted in the order given, so later rectangles "
                        + "go on top. Colours are the usual names or hex. "
                        + "DRAW IT BIG: the subject must FILL the picture, edge to edge, like the "
                        + "200-wide head above in a 256 picture. Only the space AROUND it is left "
                        + "unpainted, and unpainted is see-through, so the blocks behind show past "
                        + "the subject instead of a coloured slab. Do NOT paint a background "
                        + "rectangle, and do NOT draw a small subject in the middle of a big "
                        + "picture -- at three blocks away that is a postage stamp on the side of a "
                        + "carriage.");
        // x/y/z/facing are NOT required: left out, the picture hangs on a new board in front of
        // him. picture is not required either: with start_from, a picture can be re-hung or re-sized
        // with no change at all, and PictureEdit refuses an empty NEW picture by itself.
        return Schemas.required(s, "id", "note");
    }

    @Override
    public ToolResult call(JsonObject args) {
        com.moddingfromamod.provenance.ProvenanceContext.note(
                args != null && args.has("note") && args.get("note").isJsonPrimitive()
                        ? args.get("note").getAsString() : null);
        String id = ModelRepository.sanitizeKey(str(args, "id"));
        if (id.isEmpty()) {
            return ToolResult.error("paint_surface needs an 'id' — a short lower-case name for the "
                    + "picture, like 'turtle_king_face'.");
        }
        // ── A SKIN, NOT A DRAWING. Turned into an ordinary 'picture' (and a board of the
        // right shape) BEFORE anything else reads the arguments, so every path below -- a new
        // board, a wall, a train, start_from -- paints it exactly as it paints a drawn picture, and
        // the recipe stored with it means a replay never fetches the skin again.
        SkinSource skin = null;
        if (!str(args, "from_skin").isBlank()) {
            SkinPrep prep = prepareSkin(args);
            if (prep.error() != null) {
                return prep.error();
            }
            args = prep.args();
            skin = prep.source();
        }
        ToolResult done = paint(args, id);
        if (skin == null || done.isError()) {
            return done;
        }
        com.moddingfromamod.provenance.ProvenancePublisher.publish(
                "painting", id, "skin.json", skin.toJson().toString());
        return ToolResult.ok(done.text() + "\nPainted " + skin.publicName() + "'s real Minecraft "
                + "skin (account " + skin.username() + ", as " + skin.link().sourceName()
                + " links it; "
                + skin.view().name().toLowerCase(java.util.Locale.ROOT) + ") on a "
                + intOf(args, "wide") + " by " + intOf(args, "tall") + " board — that shape keeps "
                + "the skin's pixels square. It came from their profile, not from memory.");
    }

    /** A hidden argument, as DROPPED_XYZ is: the lever-A note, carried from paint() to where it is hung. */
    static final String STANDIN_NOTE = "_standin_note";

    record Kept(PaintRecipe recipe, String note, String refusal) {
    }

    static Kept standInColour(String id, PaintRecipe recipe) {
        try {
            if (recipe == null || recipe.rects().isEmpty()) {
                return new Kept(recipe, "", null);
            }
            String intent = com.moddingfromamod.provenance.ProvenanceContext.currentIntent();
            var character = com.moddingfromamod.reference.CharacterLineage.resolve(intent, id);
            if (character.isEmpty()) {
                return new Kept(recipe, "", null);
            }
            var spec = com.moddingfromamod.reference.ReferenceResearcher.lookUp(character.get());
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            String words = run == null ? "" : run.text();
            var built = com.moddingfromamod.reference.MainColourCheck.pictureShares(recipe.size(), recipe.rects());
            if ("on".equalsIgnoreCase(System.getProperty(SculptCreatureTool.REFUSE_PROPERTY, "off"))) {
                var why = com.moddingfromamod.reference.MainColourCheck.forAgent(built, spec, character.get(), words,
                        "Repaint the same id");
                return why.isPresent() && SculptCreatureTool.Pieces.firstTime("picture:" + id, "maincolour")
                        ? new Kept(recipe, "", "Not painted yet. " + why.get()) : new Kept(recipe, "", null);
            }
            if (!com.moddingfromamod.reference.StandInColour.on()) {
                return new Kept(recipe, "", null);
            }
            var shift = com.moddingfromamod.reference.StandInColour.plan(built, spec, words);
            var turned = shift.isEmpty() ? recipe.rects()
                    : com.moddingfromamod.reference.StandInColour.rects(recipe.rects(), shift.get());
            return turned.equals(recipe.rects()) ? new Kept(recipe, "", null)
                    : new Kept(new PaintRecipe(recipe.size(), turned),
                            com.moddingfromamod.reference.StandInColour.note(character.get(), shift.get())
                                    .replace("re-sculpt", "repaint"), null);
        } catch (RuntimeException e) {
            return new Kept(recipe, "", null);
        }
    }

    /** The main-colour check for a picture of a named character, or "". Never blocks. */
    static String mainColourNote(String id, com.moddingfromamod.paint.PaintRecipe recipe) {
        try {
            String intent = com.moddingfromamod.provenance.ProvenanceContext.currentIntent();
            var character = com.moddingfromamod.reference.CharacterLineage.resolve(intent, id);
            if (character.isEmpty() || recipe == null) {
                return "";
            }
            var spec = com.moddingfromamod.reference.ReferenceResearcher.lookUp(character.get());
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            return com.moddingfromamod.reference.MainColourCheck.forAgent(
                    com.moddingfromamod.reference.MainColourCheck.pictureShares(recipe.size(), recipe.rects()),
                    spec, character.get(), run == null ? "" : run.text(), "Repaint the same id").orElse("");
        } catch (RuntimeException e) {
            return "";   // a check must never cost him the picture
        }
    }

    private ToolResult paint(JsonObject args, String id) {
        // Trains went with the Create mod. The `train` form was never offered without it,
        // and an agent that passes one anyway is told so in the words a no-Create server always used.
        if (!str(args, "train").isBlank()) {
            return ToolResult.error("There are no trains in this world (the Create mod is "
                    + "not installed). Give x/y/z/facing, or leave them out to hang it on a "
                    + "new board in front of him.");
        }
        // ── WHOSE PICTURE, AND WHAT IT BECOMES. Decided before anything is painted,
        // the same way make_item and sculpt_creature ask: his own is edited, another
        // child's or the library's is FORKED onto its own board, and with nobody to fork for
        // nothing happens. start_from inherits the recipe so only the change is sent.
        String asker = com.moddingfromamod.agent.AgentContext.currentPlayerId();
        String startFrom = str(args, "start_from");
        boolean asNew = args.has("as_new") && args.get("as_new").isJsonPrimitive()
                && args.get("as_new").getAsBoolean();
        String tooMany = tooManyShapes(args);
        if (tooMany != null) {
            return ToolResult.error(tooMany);
        }
        // a picture of a named character keeps its MAIN colour (lever A, default), or is
        // refused once (lever B, the comparison arm).
        Kept kept = standInColour(id, PaintRecipe.parse(pictureOf(args)));
        if (kept.refusal() != null) {
            return ToolResult.error(kept.refusal());
        }
        if (!kept.note().isEmpty()) {
            args.addProperty(STANDIN_NOTE, kept.note());   // read where the picture is hung
        }
        com.moddingfromamod.paint.PictureEdit.Plan plan =
                com.moddingfromamod.paint.PictureEdit.plan(asker, id, startFrom, asNew,
                        kept.recipe(), intOf(args, "wide"),
                        intOf(args, "tall"), STORE);
        if (plan.refused()) {
            // The second road for the picture lesson (LessonsReachTheAgentTest): an agent that
            // skipped list_paintings and guessed an id lands HERE, so this is where it must read
            // "start_from, never a redraw" rather than trying again blind.
            return ToolResult.error(plan.refusal()
                    + com.moddingfromamod.wish.WishLessons.appendTo("painting"));
        }
        // A kept picture hung again exactly as it is (no new picture, no new size) makes nothing new
        // to photograph (2026-10-03): CameraNeed.reused lets the wish's camera go.
        if (!startFrom.isBlank() && !args.has("picture") && !args.has("from_skin") && !args.has("wide")
                && !args.has("tall")) {
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            if (run != null) {
                com.moddingfromamod.wish.CameraNeed.shared().reused(run.wishId(), "painting", id,
                        System.currentTimeMillis());
            }
        }
        // ON A NAMED BUILDING'S WALL — "a flag on my castle's wall" when he is not looking at
        // it. Resolved to the x/y/z/facing an explicit wall takes, so every path below is unchanged.
        String at = str(args, "at").trim();
        if (!at.isEmpty()) {
            // The `at` WINS over coordinates the agent worked out itself (measured, the at-p1 grant:
            // both were passed, the coordinates won, and the flag went on the castle's back wall).
            if (args.has("x") || args.has("y") || args.has("z") || args.has("facing")) {
                args.addProperty(DROPPED_XYZ, at);
            }
            for (String k : new String[] {"x", "y", "z", "facing"}) {
                args.remove(k);
            }
            final JsonObject into = args;
            String why;
            try {
                why = AgentBridge.onServer(() -> aimAtWall(at, plan.wide(), plan.tall(), into));
            } catch (AgentBridge.Unavailable e) {
                return ToolResult.error(e.getMessage());
            }
            if (why != null) {
                return ToolResult.error("Could not hang it at " + at + ": " + why);
            }
        }
        Direction facing = facingOf(str(args, "facing"));
        boolean explicit = facing != null && args.has("x") && args.has("y") && args.has("z");
        if (!explicit && !str(args, "facing").isBlank()) {
            return ToolResult.error("'facing' must be north, south, east or west — which way the "
                    + "picture looks. A picture facing into the wall is invisible.");
        }
        // EVERY WORLD TOUCH ON THE SERVER THREAD. A tool call arrives on an HTTP thread; setting a
        // block from there is a race against the tick, and the failures are the corrupted-chunk
        // kind rather than an exception.
        try {
            return AgentBridge.onServer(() -> place(args, plan, facing, explicit));
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * <b>The wall {@code at} names</b>, written into {@code into} as x/y/z/facing. The thing
     * must be a standing BUILDING; the spot picks the wall (its front's by default; beside, behind or
     * a compass side; a spot it marked with a facing), and {@link
     * com.moddingfromamod.builder.BuildAutoSpots#wallFace} finds a flat patch the picture's size on
     * it. Null when it worked, else why not. Server thread.
     */
    /** Set on the call's args when an `at` overrode coordinates the agent also passed. */
    private static final String DROPPED_XYZ = "_at_dropped_xyz";

    static String aimAtWall(String at, int wide, int tall, JsonObject into) {
        var server = AgentBridge.serverOrNull();
        var a = com.moddingfromamod.place.At.parse(at);
        if (server == null || a == null) {
            return "it reads <building>:<wall> — a building's id, then front, beside, behind or north/east/south/west";
        }
        var place = com.moddingfromamod.place.PlaceFinder.find(server,
                com.moddingfromamod.agent.ActingChild.of(server), a, com.moddingfromamod.builder.BuildSpots.FRONT);
        if (!place.ok()) {
            return place.why();
        }
        if (place.buildId() == null) {
            return "a picture hangs on a BUILDING's wall; '" + a.thing() + "' is not a building (to put it in "
                    + "front of him, leave at out)";
        }
        var plan = com.moddingfromamod.builder.BuildRegistry.get(place.buildId());
        int[] toward = com.moddingfromamod.builder.BuildRegistry.towardOf(place.buildId());
        var spot = com.moddingfromamod.builder.BuildSpots.spot(plan, place.r().spot(), toward[0], toward[1]);
        String side = spot == null ? "" : spot.facing();
        if (side.isEmpty()) {
            return "'" + place.r().spot() + "' is not a wall — a picture goes on its front, beside, behind or a "
                    + "compass side (north, east, south, west)";
        }
        var face = com.moddingfromamod.builder.BuildAutoSpots.wallFace(plan, side, Math.max(1, wide), Math.max(1, tall));
        if (face == null) {
            return "its " + side + " wall has no flat patch " + Math.max(1, wide) + " wide and " + Math.max(1, tall)
                    + " tall — try a smaller picture or another side";
        }
        var me = com.moddingfromamod.agent.ActingChild.of(server);
        if (me != null && place.level() != me.level()) {
            // A picture is hung in HIS world (paintOnServer paints there): never another world's wall.
            return "'" + place.buildId() + "' stands in another world from him — he has to be there to hang a "
                    + "picture on it";
        }
        BlockPos anchor = com.moddingfromamod.place.PlaceFinder.anchor(place.buildId());
        into.addProperty("x", anchor.getX() + face.dx());
        into.addProperty("y", anchor.getY() + face.dy());
        into.addProperty("z", anchor.getZ() + face.dz());
        into.addProperty("facing", face.facing());
        return null;
    }

    /** The store {@link com.moddingfromamod.paint.PictureEdit} reads — the real one. */
    private static final com.moddingfromamod.paint.PictureEdit.Lookup STORE =
            new com.moddingfromamod.paint.PictureEdit.Lookup() {
                @Override
                public com.moddingfromamod.paint.PictureStore.Picture get(String id) {
                    return com.moddingfromamod.paint.PictureStore.get(id);
                }

                @Override
                public boolean inLibrary(String id) {
                    return com.moddingfromamod.paint.PictureStore.inLibrary(id);
                }
            };

    /**
     * <b>Where the planned picture goes</b>, in the order that keeps each child's pictures his:
     *
     * <ol>
     *   <li>a picture that must not go where another hangs (a fork, an {@code as_new}) gets a NEW
     *       board at his locked focus, whatever coordinates were given;</li>
     *   <li>an edit of a picture that already hangs in this world is repainted where it hangs;</li>
     *   <li>coordinates given → there, as before;</li>
     *   <li>otherwise — a picture from his shelf that has never hung in this world, or a new one
     *       with no wall named — a new board at his focus. Never a no-op because "that picture is
     *       not in this world": map ids cannot travel, so it is drawn fresh (item 5).</li>
     * </ol>
     */
    private ToolResult place(JsonObject args, com.moddingfromamod.paint.PictureEdit.Plan plan,
                             Direction facing, boolean explicit) {
        var server = AgentBridge.serverOrNull();
        if (server == null) {
            return ToolResult.error("No world is open.");
        }
        ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);
        ServerLevel level = player != null ? player.level() : server.overworld();
        PaintRecord hung = PaintRegistry.get(plan.targetId());
        ToolResult done;
        if (!plan.freshBoard() && hung != null && hung.hasVanillaCopy()
                && hung.dimension().equals(level.dimension().location().toString())) {
            done = paintOnServer(args, plan, Direction.byName(hung.facing()),
                    new BlockPos(hung.x(), hung.y(), hung.z()));
        } else if (!plan.freshBoard() && explicit) {
            done = paintOnServer(args, plan, facing,
                    new BlockPos(intOf(args, "x"), intOf(args, "y"), intOf(args, "z")));
        } else {
            if (player == null) {
                return ToolResult.noPlayerOnline("Nobody is in the world to hang the picture in "
                        + "front of — give x/y/z and facing to put it on a wall.");
            }
            String err = com.moddingfromamod.paint.PictureCommand.hangBoard(player,
                    plan.targetId(), plan.recipe(), plan.wide(), plan.tall(), plan.ownerId());
            if (err != null) {
                return ToolResult.error(err);
            }
            com.moddingfromamod.provenance.ProvenancePublisher.publish(
                    "painting", plan.targetId(), "painting.json", plan.recipe().toJson().toString());
            com.moddingfromamod.lite.MintStore.stamp(server, "painting", true);
            done = ToolResult.ok("Hung '" + plan.targetId() + "' on a new board in front of him, "
                    + "where the wish was aimed. It is a real drawn picture rather than block "
                    + "colours — photograph it to see what he will see.");
        }
        if (!done.isError()) {
            // THE RECIPE, KEPT APART FROM WHERE IT HANGS: on his shelf, so it follows him to another
            // world and the next change starts from it instead of from nothing.
            com.moddingfromamod.paint.PictureStore.put(new com.moddingfromamod.paint.PictureStore
                    .Picture(plan.targetId(), plan.ownerId(), plan.recipe(), plan.wide(),
                    plan.tall()));
            // a picture of a named character keeps its main colour (a sky-blue Sparkit was
            // painted as an "original" stand-in, an earlier issue's A/B). The reference is read from the cache
            // the wish's own prefetch filled; nothing here waits for a search.
            String mainColour = args.has(STANDIN_NOTE) ? str(args, STANDIN_NOTE)
                    : mainColourNote(plan.targetId(), plan.recipe());
            if (!mainColour.isEmpty()) {
                done = ToolResult.ok(done.text() + "\n" + mainColour);
            }
            String span = spanNote(args);
            if (span != null) {
                done = ToolResult.ok(done.text() + "\n" + span);
            }
            if (plan.recipe().rects().size() >= PaintRecipe.MAX_RECTS) {
                done = ToolResult.ok(done.text() + "\nThis picture is now at the most shapes a "
                        + "picture keeps (" + PaintRecipe.MAX_RECTS + "), so any more push the "
                        + "OLDEST ones underneath out. Use fewer, bigger rectangles.");
            }
            if (args.has(DROPPED_XYZ)) {
                done = ToolResult.ok(done.text() + "\nI hung it by `at` (" + str(args, DROPPED_XYZ) + ") and ignored the x/y/z/facing"
                        + " you also passed: with `at`, leave the coordinates out.");
            }
            if (!plan.note().isEmpty()) {
                done = ToolResult.ok(plan.note() + done.text());
            }
        }
        return done;
    }

    private ToolResult paintOnServer(JsonObject args,
                                     com.moddingfromamod.paint.PictureEdit.Plan plan,
                                     Direction facing, BlockPos at) {
        var server = AgentBridge.serverOrNull();
        if (server == null) {
            return ToolResult.error("No world is open.");
        }
        String id = plan.targetId();
        ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);
        ServerLevel level = player != null ? player.level() : server.overworld();
        int wide = plan.wide();
        int tall = plan.tall();
        // ⚠ NOT ONTO SOMEBODY ELSE'S PICTURE BY POSITION. The id check in PictureEdit cannot see a
        // NEW id aimed at the exact spot where another child's picture hangs, and map art reuses
        // the frames at a spot — so that call would repaint her picture under a new name.
        PaintRecord there = PaintRegistry.at(level.dimension().location().toString(),
                at.getX(), at.getY(), at.getZ());
        if (there != null && !there.id().equals(id)
                && !com.moddingfromamod.marshal.ArtifactOwner.visibleTo(
                        com.moddingfromamod.agent.AgentContext.currentPlayerId(), there.ownerId())) {
            return ToolResult.error("'" + there.id() + "' — another child's picture — already "
                    + "hangs at " + at.toShortString() + ". To change it, use start_from='"
                    + there.id() + "' (he gets his own copy on a new board); to paint something "
                    + "new, leave out x/y/z and it goes on a fresh board in front of him.");
        }

        // WHAT WAS HERE BEFORE, read before anything changes — it is the only chance to. The block
        // entity keeps no history, so if this is not captured now an undo can stop the wish but
        // never put the picture back.
        // RENAMED from placedPanel at the path-2 flip: "there was nothing here" and "we put a
        // panel here" used to be the same fact and are now two, because the vanilla route paints an
        // empty wall without placing anything. `rollback` below is the one that means placed.
        boolean wasEmpty = !(level.getBlockEntity(at) instanceof PaintPanelBlockEntity);
        String previousKey = "";
        PaintRecipe previousRecipe = PaintRecipe.blank();
        int previousWide = 1;
        int previousTall = 1;
        if (level.getBlockEntity(at) instanceof PaintPanelBlockEntity existing) {
            previousKey = existing.artKey();
            previousRecipe = existing.recipe();
            previousWide = existing.widthBlocks();
            previousTall = existing.heightBlocks();
        } else {
            // ── AND THE VANILLA ROUTE'S ANSWER TO THE SAME QUESTION. On a static wall there is no
            // block entity holding the current picture, so "what am I painting over" has to come
            // from the last record for this id — which is why PaintRecord now keeps the recipe it
            // painted. Without this, undoing "make him bluer" would take the mural down instead of
            // putting the first version back.
            //
            // Guarded on the POSITION as well as the id: the same id repainted somewhere else has
            // not painted over anything at the new spot. A DIFFERENT id at this position is
            // deliberately not searched for — the undo contract is "put back what the last wish
            // about THIS id did", and painting one picture over another was never reversible on the
            // panel route either (its record would point at a panel the second wish is now using).
            PaintRecord prior = PaintRegistry.get(id);
            if (prior != null && prior.hasVanillaCopy() && prior.hasRecipe()
                    && prior.x() == at.getX() && prior.y() == at.getY()
                    && prior.z() == at.getZ()) {
                previousKey = prior.key();
                previousRecipe = prior.recipe();
                previousWide = prior.wide();
                previousTall = prior.tall();
            }
        }

        // ── WHERE THE PATH-2 ROUTING DECISION IS TAKEN, and it is taken by DECIDING WHETHER TO
        // PLACE A PANEL rather than by asking a second time later which view to draw. A static wall
        // gets vanilla map art, which every client can see with no mod. PaintPanels then
        // reads the consequence ("a panel here means the mod renderer draws this"), so the decision
        // and the drawing cannot disagree. Turning VanillaArt off restores the pre-flip behaviour
        // exactly, which is what the both-ways gate drives.
        // ⚰️ LEGACY (panel-as-static-view): this branch always takes the vanilla side in a
        // shipped run. It survives as the A/B's switch point, not as a supported behaviour --
        // closed the lever (a panel is invisible to a stock client) and kept the seam.
        // Row and blocker: docs/COMPAT_SHIMS.md.
        boolean vanillaView = wasEmpty && !PaintPanels.panelOnStaticSurface();
        if (wasEmpty && !level.getBlockState(at).canBeReplaced()) {
            return ToolResult.error("There is already something at " + at.toShortString()
                    + " that is not a painted panel. Pick an empty spot on the FACE of the "
                    + "surface — the picture stands just in front of the block you name.");
        }
        if (wasEmpty && !vanillaView) {
            level.setBlockAndUpdate(at, ModPaint.PAINT_PANEL.get().defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, facing));
        } else if (!wasEmpty) {
            // An existing panel keeps being a panel — a world painted before the flip, or a
            // carriage. Repainting it must not silently change which view a child is looking at.
            level.setBlockAndUpdate(at, level.getBlockState(at)
                    .setValue(HorizontalDirectionalBlock.FACING, facing));
        }
        // Nothing was placed on the vanilla route, so nothing has to be taken away on a refusal.
        boolean rollback = wasEmpty && !vanillaView;

        // Parsed ONCE, and the same value both paints and publishes. Parsing it twice would let
        // the picture on the wall and the recipe in the manifest drift apart — which is the exact
        // thing a published "editable source" is supposed to make impossible.
        PaintRecipe recipe = plan.recipe();
        if (recipe.isEmpty()) {
            if (rollback) {
                level.removeBlock(at, false);
            }
            return ToolResult.error("That picture has no shapes in it, so it would paint nothing.");
        }
        PaintPanels.Result r = PaintPanels.paint(level, at, facing, id, recipe, wide, tall);
        if (!r.ok()) {
            if (rollback) {
                level.removeBlock(at, false);   // leave nothing behind on a refusal
            }
            return ToolResult.error(r.message());
        }

        // PUBLISH THE RECIPE, not the image. A picture is worth keeping because "the same
        // Spikor but bluer" can start from the shapes; a PNG would have to be traced.
        com.moddingfromamod.provenance.ProvenancePublisher.publish(
                "painting", id, "painting.json", recipe.toJson().toString());
        // THE FACING AND THE SPAN GO IN THE RECORD, and they have to, because on the vanilla route
        // there is no panel left in the world to read them back off. An undo that could not find
        // them left the maps hanging over a wish it reported as reversed.
        PaintRegistry.put(level.getServer(), new PaintRecord(id,
                level.dimension().location().toString(), at.getX(), at.getY(), at.getZ(),
                id, rollback, previousKey, previousRecipe, previousWide, previousTall,
                vanillaView ? facing.getName() : "", vanillaView ? wide : 0,
                vanillaView ? tall : 0, vanillaView ? recipe : PaintRecipe.blank())
                .withOwner(plan.ownerId()));
        // which path drew this picture. `vanillaView` rather than `VanillaArt.enabled()`,
        // because it is what actually decided: the flag can be on and the picture still land on
        // our own panel when one was already there. The mark records what the world contains.
        com.moddingfromamod.lite.MintStore.stamp(level.getServer(), "painting", vanillaView);

        return ToolResult.ok(r.message() + ", facing " + facing.getName() + " at "
                + at.toShortString() + ". It is a real drawn picture rather than block colours "
                + "— photograph it to see what he will see.");
    }

    /** Where a skin picture came from — what provenance records. */
    record SkinSource(String publicName,
                      com.moddingfromamod.reference.SkinLink link,
                      String username, java.util.UUID uuid, String textureUrl,
                      String textureHash, boolean slim,
                      com.moddingfromamod.paint.SkinPicture.View view) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("source", "minecraft-skin");
            o.addProperty("publicName", publicName);
            // WHY this account is this person — the page and revision (or the generated
            // list's row and its cited claims) that link them.
            o.add("linkedBy", link.toJson());
            o.addProperty("username", username);
            o.addProperty("uuid", uuid.toString());
            o.addProperty("textureUrl", textureUrl);
            o.addProperty("textureHash", textureHash);
            o.addProperty("model", slim ? "slim" : "classic");
            o.addProperty("view", view.name().toLowerCase(java.util.Locale.ROOT));
            return o;
        }
    }

    /** Either the arguments to paint with, or why not. */
    record SkinPrep(JsonObject args, SkinSource source, ToolResult error) {
        static SkinPrep refuse(String why) {
            return new SkinPrep(null, null, ToolResult.error(why));
        }
    }

    /**
     * Arguments a model might send as "the username" although the schema has none: the
     * username is the server's to find, so one of these is only ever CHECKED against it.
     */
    static final String[] USERNAME_ARGS = {"skin_username", "username", "minecraft_username"};

    /**
     * <b>from_skin → an ordinary picture.</b> In order, each step refusing on its own terms:
     * not both a skin and a drawing; <b>the PERSON's public name must be LINKED to a Java account
     * by an outside source</b> ({@code MinecraftWikiPerson}) — never fetched as a username
     * itself, because "Stampy" is somebody else's account; a username the agent sent must equal
     * that link; the player must exist; the PNG must be a skin. Then the board is forced to the
     * view's shape (step 4) and the recipe replaces 'picture'.
     *
     * <p>No link → <b>no Mojang call at all</b>. {@code PublicFigure} is then asked only to choose
     * the sentence: a public figure with no linked account is painted from the reference lookup;
     * a name that is not public is not made to look like anyone. A link is itself the outside fact
     * that the person is public (item 3), so a linked name needs no Wikipedia check.
     */
    static SkinPrep prepareSkin(JsonObject original) {
        JsonObject args = original.deepCopy();
        String name = str(args, "from_skin").trim();
        if (PaintRecipe.rectsSent(pictureOf(args)) > 0) {
            return SkinPrep.refuse("Pass 'from_skin' OR 'picture', not both — the skin IS the "
                    + "picture. Leave 'picture' out.");
        }
        var found = com.moddingfromamod.reference.SkinLink.resolve(name);
        if (found instanceof com.moddingfromamod.reference.SkinLink.Unreachable) {
            return SkinPrep.refuse("I could not check right now which Minecraft account belongs "
                    + "to '" + name + "', so no skin was fetched. Try once more in a moment, or "
                    + "paint them from look_up_character's colours and say so.");
        }
        if (!(found instanceof com.moddingfromamod.reference.SkinLink.Found(var linked))) {
            return SkinPrep.refuse(unlinked(name));
        }
        String username = linked.username();
        for (String k : USERNAME_ARGS) {
            String sent = str(args, k).trim();
            if (!sent.isEmpty() && !sent.equalsIgnoreCase(username)) {
                return SkinPrep.refuse("'" + sent + "' is not the account " + linked.sourceName()
                        + " links to " + name + ", so it is not painted — the server finds the "
                        + "username itself. Pass only from_skin = the name they are known by.");
            }
        }
        com.moddingfromamod.paint.SkinFetch.Result r =
                com.moddingfromamod.paint.SkinFetch.fetch(username);
        if (r instanceof com.moddingfromamod.paint.SkinFetch.NoSuchPlayer) {
            return SkinPrep.refuse(linked.sourceName() + " says " + name + " plays as '" + username
                    + "', but no Minecraft player has that name any more (it may have changed), "
                    + "so no skin was fetched. Paint them from look_up_character's colours "
                    + "instead and tell the child plainly.");
        }
        if (r instanceof com.moddingfromamod.paint.SkinFetch.Failed f) {
            return SkinPrep.refuse("Couldn't get " + name + "'s skin: " + f.why() + ". Tell the "
                    + "child plainly; do not invent a skin.");
        }
        com.moddingfromamod.paint.SkinFetch.Skin skin =
                ((com.moddingfromamod.paint.SkinFetch.Found) r).skin();
        // a source that PINNED the account (the generated list does) is held to it — a
        // name that now resolves to a different account is a rename or somebody else, the same
        // stranger problem the link exists for.
        if (linked.pinnedUuid() != null && !linked.pinnedUuid().equals(skin.uuid())) {
            return SkinPrep.refuse(linked.sourceName() + " links " + name + " to the account '"
                    + username + "', but that name now belongs to a different account (it was "
                    + "renamed or taken), so no skin was fetched. Paint them from "
                    + "look_up_character's colours instead and tell the child plainly.");
        }
        com.moddingfromamod.paint.SkinPicture.View view =
                com.moddingfromamod.paint.SkinPicture.View.of(str(args, "skin_view"));
        PaintRecipe recipe;
        try {
            recipe = com.moddingfromamod.paint.SkinPicture.recipe(skin.png(), skin.slim(), view);
        } catch (java.io.IOException e) {
            return SkinPrep.refuse("Couldn't paint " + name + "'s skin: " + e.getMessage() + ".");
        }
        int[] board = skinBoard(view, intOf(args, "wide"), intOf(args, "tall"));
        args.add("picture", recipe.toJson());
        args.addProperty("wide", board[0]);
        args.addProperty("tall", board[1]);
        return new SkinPrep(args, new SkinSource(name, linked, skin.username(), skin.uuid(),
                skin.textureUrl(), skin.textureHash(), skin.slim(), view), null);
    }

    /** What the agent is told when no outside source links {@code name} to an account. */
    static String unlinked(String name) {
        if (com.moddingfromamod.reference.PublicFigure.check(name)
                == com.moddingfromamod.reference.PublicFigure.Verdict.NO_ARTICLE) {
            return "'" + name + "' is not a public figure (no Wikipedia article, and no "
                    + "minecraft.wiki page naming their Minecraft account), so their skin is NOT "
                    + "fetched — it could be a friend's or a sibling's name. Paint something from "
                    + "the child's own words instead, and do not make it look like a real person.";
        }
        return "No outside source links '" + name + "' to a Minecraft account, so no skin is "
                + "fetched: the Minecraft account with that name may belong to somebody else, and "
                + "a stranger's skin is not painted as them. Paint them from look_up_character's "
                + "colours and description instead (call it if you have not), and tell the child "
                + "it is your picture of " + name + ", not their real skin. Do not pass a username "
                + "you know — the server only paints an account a source links to the person.";
    }

    /**
     * The board a skin view needs, {@code {wide, tall}}: its aspect, never what was asked (
     * step 4 — a square recipe on an 8x6 board came out 4:3). The size asked for is kept as far
     * as the shape allows: a face takes the larger of the two, a front the width (or half the
     * height). Defaults: a face 2x2, a front 2x4. Within {@link PanelGeometry#MAX_SPAN}.
     */
    static int[] skinBoard(com.moddingfromamod.paint.SkinPicture.View view, int wide, int tall) {
        int max = PanelGeometry.MAX_SPAN;
        if (view == com.moddingfromamod.paint.SkinPicture.View.FACE) {
            int side = Math.max(wide, tall) > 0 ? Math.max(wide, tall) : 2;
            side = Math.min(side, max);
            return new int[]{side, side};
        }
        int w = wide > 0 ? wide : tall > 0 ? Math.max(1, tall / 2) : 2;
        w = Math.max(1, Math.min(w, max / 2));
        return new int[]{w, view.tallFor(w)};
    }

    /** The picture argument as JSON, tolerating either a string or an inlined object. */

    /**
     * A picture sent with more shapes than a recipe keeps is REFUSED, never painted in part: the
     * cap used to cut it silently, which P3 forbids.
     */
    public static String tooManyShapes(JsonObject args) {
        int sent = PaintRecipe.rectsSent(pictureOf(args));
        return sent <= PaintRecipe.MAX_RECTS ? null
                : "That picture has " + sent + " shapes and a picture keeps at most "
                        + PaintRecipe.MAX_RECTS + ". Nothing was painted. Draw it with fewer, "
                        + "bigger rectangles — one per area of colour, not one per pixel.";
    }

    /**
     * A board asked for wider or taller than {@link com.moddingfromamod.paint.PanelGeometry#MAX_SPAN}
     * is hung at the cap, and the result SAYS so rather than quietly hanging a smaller picture than
     * the one asked for (P3). Null when nothing was clamped.
     */
    public static String spanNote(JsonObject args) {
        int max = com.moddingfromamod.paint.PanelGeometry.MAX_SPAN;
        int wide = intOf(args, "wide");
        int tall = intOf(args, "tall");
        if (wide <= max && tall <= max) {
            return null;
        }
        return "It was asked for " + Math.max(wide, 1) + " by " + Math.max(tall, 1)
                + " blocks and a picture is at most " + max + " by " + max
                + ", so it is hung at " + Math.min(Math.max(wide, 1), max) + " by "
                + Math.min(Math.max(tall, 1), max) + ". Tell him if the size mattered.";
    }

    private static JsonObject pictureOf(JsonObject args) {
        try {
            if (args.has("picture") && args.get("picture").isJsonObject()) {
                return args.getAsJsonObject("picture");
            }
            return com.google.gson.JsonParser.parseString(str(args, "picture")).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static Direction facingOf(String raw) {
        if (raw == null) {
            return null;
        }
        Direction d = Direction.byName(raw.trim().toLowerCase(java.util.Locale.ROOT));
        return d != null && d.getAxis().isHorizontal() ? d : null;
    }

    private static int intOf(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
