package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for what the agent is TOLD about painting.</b>
 *
 * <p>A tool is its description. {@code PhotographTool}'s {@code subject} said "the <b>creature</b>
 * id", and every framing path for a building, a train or an item was skipped for years because the
 * agent read the field as creature-only — a defect with no failing test anywhere, in code
 * that was completely correct. So the wording is asserted, not just written.
 */
class PaintSurfaceToolTest {

    private static final PaintSurfaceTool TOOL = new PaintSurfaceTool();

    /** Kinds of thing a surface might belong to. Naming ONE of these is the trap. */
    private static final List<String> CLASSES =
            List.of("creature", "mob", "building", "train", "item", "machine", "contraption");

    @Test
    @DisplayName("the description names no single class of thing — a surface is a surface")
    void itDoesNotNameOneKindOfThing() {
        String d = TOOL.description().toLowerCase(Locale.ROOT);
        // At least two, so "the side of a train car" alone cannot pass as generality.
        long named = CLASSES.stream().filter(d::contains).count();
        assertTrue(named >= 2,
                "the description mentions " + named + " kind(s) of thing. Naming exactly one is how "
                        + "photograph's `subject` came to mean creature-only for years: the agent "
                        + "reads it as the ONLY thing the tool applies to. Say a wall AND a car.");
    }

    @Test
    @DisplayName("it tells the agent why NOT to make a picture out of blocks")
    void itSaysWhyBlockArtIsNotAnOption() {
        // The whole reason the tool exists. An agent that does not know a block face is
        // nine pixels will keep answering "a picture of Spikor" with six coloured squares, and be
        // right to — structurally that IS what was asked for.
        String d = TOOL.description().toLowerCase(Locale.ROOT);
        assertTrue(d.contains("block"),
                "the description never mentions blocks, so nothing steers the agent away from the "
                        + "answer it has always given");
        // ⚠ A NUMBER, not merely the word "pixel". The first cut asked only for "pixel" somewhere
        // in the text, and a mutant that deleted the whole reason — leaving "use it for pictures.
        // This draws thousands of pixels" — SURVIVED it. An instruction can be overridden by a
        // wish that sounds different; a quantity cannot be argued with.
        assertTrue(d.matches("(?s).*\\b(nine|9)\\b[^.]*pixel.*"),
                "the description does not say HOW SMALL block art is. 'Use this instead' is an "
                        + "instruction; 'a block face is about nine pixels' is a reason, and a "
                        + "reason survives being paraphrased into a different wish. Description "
                        + "was: " + TOOL.description());
    }

    @Test
    @DisplayName("it says one call paints the whole picture, however many blocks it covers")
    void itSaysAPictureIsOneCallNotOnePerBlock() {
        // The thing most likely to be got wrong by an agent that has only ever built with blocks:
        // six calls for a 3x2 face, producing six pictures instead of one.
        String d = TOOL.description().toLowerCase(Locale.ROOT);
        assertTrue(d.contains("one call") || d.contains("one panel"),
                "nothing tells the agent that a multi-block picture is a single call");
    }

    @Test
    @DisplayName("every field the agent must fill has an explanation")
    void everyRequiredFieldIsExplained() {
        JsonObject schema = TOOL.inputSchema();
        JsonObject props = schema.getAsJsonObject("properties");
        for (var el : schema.getAsJsonArray("required")) {
            String field = el.getAsString();
            assertTrue(props.has(field), "required field '" + field + "' is not in the schema");
            String desc = props.getAsJsonObject(field).has("description")
                    ? props.getAsJsonObject(field).get("description").getAsString() : "";
            assertFalse(desc.isBlank(),
                    "required field '" + field + "' has no description. An unexplained required "
                            + "field is guessed at, and a guessed 'facing' points the picture into "
                            + "the wall.");
        }
    }

    @Test
    @DisplayName("the picture field says its units are PIXELS, not blocks")
    void itSaysTheCoordinatesArePixels() {
        // Both units are in play in the same call — x/y/z are blocks and the rects are pixels — so
        // this is the single likeliest confusion in the whole schema.
        String picture = TOOL.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("picture").get("description").getAsString().toLowerCase(Locale.ROOT);
        assertTrue(picture.contains("pixel"), "the picture field never says its units");
        assertTrue(picture.contains("not blocks") || picture.contains("not block"),
                "the picture field does not warn that these are NOT the block coordinates in the "
                        + "same call");
    }

    @Test
    @DisplayName("paint and remove are a matched pair, and the undo contract knows both")
    void thePairIsCompleteAndClassified() {
        // A kind with no reversal makes the [undo] button lie.
        String paint = TOOL.name();
        String remove = new RemovePaintingTool().name();
        assertTrue(com.moddingfromamod.marshal.UndoContract.undoable(List.of(paint, remove)),
                "painting is not classified as reversible, so any wish that paints loses its undo");
        assertTrue(com.moddingfromamod.marshal.MadeKind.PAINTING
                        .equals(com.moddingfromamod.marshal.MadeKind.forTool(paint)),
                "paint_surface does not record what it made, so undo has nothing to reverse");
    }

    @Test
    @DisplayName("a picture with more shapes than a recipe keeps is refused, never painted in part")
    void tooManyShapesIsRefusedOutLoud() {
        JsonObject pic = new JsonObject();
        com.google.gson.JsonArray rs = new com.google.gson.JsonArray();
        for (int i = 0; i <= com.moddingfromamod.paint.PaintRecipe.MAX_RECTS; i++) {
            JsonObject r = new JsonObject();
            r.addProperty("x", i % 64); r.addProperty("y", 0); r.addProperty("w", 1);
            r.addProperty("h", 1); r.addProperty("colour", "red");
            rs.add(r);
        }
        pic.add("rects", rs);
        JsonObject args = new JsonObject();
        args.addProperty("picture", pic.toString());
        String why = PaintSurfaceTool.tooManyShapes(args);
        assertTrue(why != null && why.contains("Nothing was painted"), String.valueOf(why));
        rs.remove(0);
        args.addProperty("picture", pic.toString());
        assertTrue(PaintSurfaceTool.tooManyShapes(args) == null, "exactly the cap is allowed");
    }

    // ──: from_skin ──────────────────────────────────────────────────────────────────────

    private static byte[] steve() throws java.io.IOException {
        try (var in = PaintSurfaceToolTest.class.getResourceAsStream(
                "/assets/minecraft/textures/entity/player/wide/steve.png")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in);
            return in.readAllBytes();
        }
    }

    private static JsonObject skinArgs(String name, String view, int wide, int tall) {
        JsonObject a = new JsonObject();
        a.addProperty("id", "skin_test");
        a.addProperty("from_skin", name);
        if (view != null) {
            a.addProperty("skin_view", view);
        }
        if (wide > 0) {
            a.addProperty("wide", wide);
        }
        if (tall > 0) {
            a.addProperty("tall", tall);
        }
        return a;
    }

    /** Asked of the skin service, in order — the usernames Mojang would have been sent. */
    private static final java.util.List<String> FETCHED =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * an earlier issue's world: Smajor is LINKED to Smajor1995 by his minecraft.wiki page; Stampy is public
     * (an article) but his page lists only an Xbox gamertag; MyBrotherJake is nobody. And Mojang
     * has an account called "Stampy" — a stranger's — which must never be fetched.
     */
    private static void fakeServices(byte[] png) {
        FETCHED.clear();
        com.moddingfromamod.reference.MinecraftWikiPerson.useOracle(n -> n.equalsIgnoreCase("Smajor")
                ? new com.moddingfromamod.reference.MinecraftWikiPerson.Linked(n, "Smajor",
                        3774932L, "minecraft_name", "Smajor1995")
                : new com.moddingfromamod.reference.MinecraftWikiPerson.Unmapped(n,
                        com.moddingfromamod.reference.MinecraftWikiPerson.Why.NO_JAVA_NAME, n));
        // The generated list holds one creator, "Bloxo", pinned to a uuid; nothing else.
        com.moddingfromamod.reference.CreatorAccounts.useRows(java.util.List.of(BLOXO_ROW));
        com.moddingfromamod.reference.PublicFigure.useOracle(n -> n.equalsIgnoreCase("Stampy")
                || n.equalsIgnoreCase("Smajor")
                ? com.moddingfromamod.reference.PublicFigure.Verdict.PUBLIC
                : com.moddingfromamod.reference.PublicFigure.Verdict.NO_ARTICLE);
        com.moddingfromamod.paint.SkinFetch.useSource(n -> {
            FETCHED.add(n);
            if (n.equalsIgnoreCase("Xisuma")) {     // a renamed-away name: a different account now
                return new com.moddingfromamod.paint.SkinFetch.Found(
                        new com.moddingfromamod.paint.SkinFetch.Skin(n, java.util.UUID.fromString(
                                "11111111-2222-3333-4444-555555555555"),
                                "https://textures.minecraft.net/texture/abc0123456789abcdef",
                                "abc", false, png));
            }
            return n.equalsIgnoreCase("Smajor1995") || n.equalsIgnoreCase("Stampy")
                    || n.equalsIgnoreCase("Bloxo") || n.equalsIgnoreCase("BlockyBob")
                    ? new com.moddingfromamod.paint.SkinFetch.Found(
                            new com.moddingfromamod.paint.SkinFetch.Skin(n,
                                    java.util.UUID.fromString("55a2e72e-0161-4c85-b191-a0b227ff758a"),
                                    "https://textures.minecraft.net/texture/abc0123456789abcdef",
                                    "abc", false, png))
                    : new com.moddingfromamod.paint.SkinFetch.NoSuchPlayer(n);
        });
    }

    /** A generated-list row as the generator writes it: Bloxo, pinned to the fake skin's uuid. */
    static final String BLOXO_ROW = "{\"v\":1,\"rank\":7,\"creator\":\"Bloxo\",\"aliases\":[\"Bloxo YT\"],"
            + "\"rule\":\"A1:one-curated-field\",\"name\":\"Bloxo\","
            + "\"uuid\":\"55a2e72e01614c85b191a0b227ff758a\",\"claims\":[{\"source\":\"mcc.fandom\","
            + "\"page\":\"Bloxo\",\"url\":\"https://mcchampionship.fandom.com/wiki/Bloxo\","
            + "\"revision\":4242,\"field\":\"ign\",\"value\":\"Bloxo\",\"names\":[\"Bloxo\"]}]}";

    /** Xisuma's row pins an account; Mojang's "Xisuma" is now somebody else (a rename). */
    static final String XISUMA_ROW = BLOXO_ROW.replace("Bloxo", "Xisuma")
            .replace("\"Bloxo YT\"", "\"X\"");

    @Test
    @DisplayName("#1302: the generated list links a creator minecraft.wiki does not, and provenance cites the row")
    void theGeneratedListLinksTheGaps() throws Exception {
        fakeServices(steve());
        var p = PaintSurfaceTool.prepareSkin(skinArgs("Bloxo YT", null, 0, 0));
        nul(p.error());
        eq(java.util.List.of("Bloxo"), FETCHED, "the row's account, found by an alias");
        var by = p.source().toJson().getAsJsonObject("linkedBy");
        eq("creator-accounts", by.get("source").getAsString());
        eq("mcc.fandom", by.get("claimSource").getAsString());
        eq("55a2e72e-0161-4c85-b191-a0b227ff758a", by.get("uuid").getAsString());
    }

    @Test
    @DisplayName("#1302: 'a picture of BlockyBob' paints his own skin — an owner-confirmed (O1) row")
    void aPictureOfBlockyBobPaintsHisSkin() throws Exception {
        fakeServices(steve());
        // A fixture row: the real table is private (D33); CreatorAccountsBundledTest reads it.
        com.moddingfromamod.reference.CreatorAccounts.useRows(java.util.List.of(BLOXO_ROW
                .replace("\"Bloxo YT\"", "\"Seth\"").replace("Bloxo", "BlockyBob")
                .replace("A1:one-curated-field", "O1:owner-confirmed")
                .replace("\"source\":\"mcc.fandom\"", "\"source\":\"owner\"")));
        var p = PaintSurfaceTool.prepareSkin(skinArgs("BlockyBob", null, 0, 0));
        nul(p.error());
        eq(java.util.List.of("BlockyBob"), FETCHED, "Mojang is asked for the confirmed account only");
        var by = p.source().toJson().getAsJsonObject("linkedBy");
        eq("creator-accounts", by.get("source").getAsString());
        eq("O1:owner-confirmed", by.get("rule").getAsString());
        eq("owner", by.get("claimSource").getAsString());
        eq("55a2e72e-0161-4c85-b191-a0b227ff758a", by.get("uuid").getAsString(),
                "the fetched skin is the pinned account (the stub answers with it)");
    }

    @Test
    @DisplayName("#1302: a pinned account that now answers to somebody else is not painted")
    void aPinnedUuidThatMovedIsRefused() throws Exception {
        fakeServices(steve());
        com.moddingfromamod.reference.CreatorAccounts.useRows(java.util.List.of(XISUMA_ROW));
        var p = PaintSurfaceTool.prepareSkin(skinArgs("Xisuma", null, 0, 0));
        yes(p.error() != null && p.error().text().contains("now belongs to a different account"),
                String.valueOf(p.error()));
    }

    @org.junit.jupiter.api.AfterEach
    void resetSkinServices() {
        com.moddingfromamod.reference.CreatorAccounts.reset();
        com.moddingfromamod.reference.PublicFigure.reset();
        com.moddingfromamod.reference.MinecraftWikiPerson.reset();
        com.moddingfromamod.paint.SkinFetch.reset();
    }

    @Test
    @DisplayName("#1264: from_skin becomes a picture, on a board whose shape keeps the pixels square")
    void fromSkinBecomesASquarePixelPicture() throws Exception {
        fakeServices(steve());
        var face = PaintSurfaceTool.prepareSkin(skinArgs("Smajor", null, 8, 6));
        nul(face.error());
        eq(8, face.args().get("wide").getAsInt());
        eq(8, face.args().get("tall").getAsInt(),
                "the spike's 8x6 board stretched a face to 4:3; a face board is square");
        yes(face.args().getAsJsonObject("picture").getAsJsonArray("rects").size() > 0);
        var front = PaintSurfaceTool.prepareSkin(skinArgs("Smajor", "front", 3, 0));
        eq(3, front.args().get("wide").getAsInt());
        eq(6, front.args().get("tall").getAsInt());
        eq("front", front.source().toJson().get("view").getAsString());
    }

    @Test
    @DisplayName("#1302: the LINKED account is fetched, never the public name, and provenance says why")
    void theLinkedAccountIsFetched() throws Exception {
        fakeServices(steve());
        var p = PaintSurfaceTool.prepareSkin(skinArgs("Smajor", null, 0, 0));
        nul(p.error());
        eq(java.util.List.of("Smajor1995"), FETCHED, "Mojang is asked for the linked account only");
        var j = p.source().toJson();
        eq("Smajor", j.get("publicName").getAsString());
        eq("Smajor1995", j.get("username").getAsString());
        eq("minecraft.wiki", j.getAsJsonObject("linkedBy").get("source").getAsString());
        eq(3774932L, j.getAsJsonObject("linkedBy").get("revision").getAsLong());
    }

    @Test
    @DisplayName("#1302: a public name with NO link makes zero Mojang calls — 'Stampy' is a stranger's account")
    void anUnlinkedPublicNameFetchesNothing() throws Exception {
        fakeServices(steve());
        var stampy = PaintSurfaceTool.prepareSkin(skinArgs("Stampy", null, 0, 0));
        yes(stampy.error() != null && stampy.error().isError());
        yes(stampy.error().text().contains("look_up_character")
                && stampy.error().text().contains("No outside source links"), stampy.error().text());
        eq(java.util.List.of(), FETCHED, "an unlinked name must not reach Mojang at all");
    }

    @Test
    @DisplayName("#1302: a username the agent sends is only ever CHECKED against the link")
    void aSentUsernameMustEqualTheLink() throws Exception {
        fakeServices(steve());
        JsonObject wrong = skinArgs("Smajor", null, 0, 0);
        wrong.addProperty("username", "Stampy");
        var refused = PaintSurfaceTool.prepareSkin(wrong);
        yes(refused.error() != null && refused.error().text().contains("not the account"),
                String.valueOf(refused.error()));
        eq(java.util.List.of(), FETCHED);
        JsonObject right = skinArgs("Smajor", null, 0, 0);
        right.addProperty("skin_username", "smajor1995");
        nul(PaintSurfaceTool.prepareSkin(right).error());
    }

    private static void nul(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o, String.valueOf(o));
    }

    private static void yes(boolean b) {
        org.junit.jupiter.api.Assertions.assertTrue(b);
    }

    private static void yes(boolean b, String why) {
        org.junit.jupiter.api.Assertions.assertTrue(b, why);
    }

    private static void eq(Object want, Object got) {
        org.junit.jupiter.api.Assertions.assertEquals(want, got);
    }

    private static void eq(Object want, Object got, String why) {
        org.junit.jupiter.api.Assertions.assertEquals(want, got, why);
    }

    @Test
    @DisplayName("#1264: a skin board is capped by shape, never stretched to fit the cap")
    void skinBoardsKeepTheirShapeAtTheCap() {
        var face = com.moddingfromamod.paint.SkinPicture.View.FACE;
        var front = com.moddingfromamod.paint.SkinPicture.View.FRONT;
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{2, 2},
                PaintSurfaceTool.skinBoard(face, 0, 0));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{2, 4},
                PaintSurfaceTool.skinBoard(front, 0, 0));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{16, 16},
                PaintSurfaceTool.skinBoard(face, 40, 3));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{8, 16},
                PaintSurfaceTool.skinBoard(front, 12, 0));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{5, 10},
                PaintSurfaceTool.skinBoard(front, 0, 10));
    }

    @Test
    @DisplayName("#1264: a name with no article fetches NO skin; a linked account gone is said plainly")
    void theGateHoldsAtTheTool() throws Exception {
        fakeServices(steve());
        var friend = PaintSurfaceTool.prepareSkin(skinArgs("MyBrotherJake", null, 0, 0));
        yes(friend.error().isError());
        yes(friend.error().text().contains("not a public figure"), friend.error().text());
        eq(java.util.List.of(), FETCHED, "a name with no outside fact must not reach Mojang at all");
        com.moddingfromamod.paint.SkinFetch.useSource(n -> {
            FETCHED.add(n);
            return new com.moddingfromamod.paint.SkinFetch.NoSuchPlayer(n);
        });
        var gone = PaintSurfaceTool.prepareSkin(skinArgs("Smajor", null, 0, 0));
        yes(gone.error().text().contains("no Minecraft player has that name"), gone.error().text());
    }

    @Test
    @DisplayName("#1264: from_skin and a drawn picture together are refused — the skin IS the picture")
    void notBothASkinAndADrawing() throws Exception {
        fakeServices(steve());
        JsonObject a = skinArgs("Smajor", null, 0, 0);
        a.addProperty("picture", "{\"rects\":[{\"x\":0,\"y\":0,\"w\":9,\"h\":9,\"colour\":\"red\"}]}");
        org.junit.jupiter.api.Assertions.assertTrue(PaintSurfaceTool.prepareSkin(a).error().isError());
    }
}
