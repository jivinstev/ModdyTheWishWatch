package com.moddingfromamod.paint;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.testing.GateShards;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import com.moddingfromamod.testing.gt.GameTestHolder;

// ── TRANSITIONAL — art spike. Not production. See docs/VANILLA_ART_SPIKE_RESULTS.md

/**
 * <b>Gate B for the vanilla map-art route.</b>
 *
 * <p>Everything a headless server can honestly say about a picture drawn for a client that has no
 * mod: that the palette it is quantised against is the LIVE one and is the right way round, that
 * real frames and real maps appear with our bytes in them, that the neutralisations off the
 * &sect;3.B.10 checklist actually took, that repainting does not leak level data — and the
 * negatives, which is where this kind of work regresses.
 *
 * <p>What it deliberately does NOT claim: that anything was <i>seen</i>. A byte in
 * {@code MapItemSavedData.colors} is not a pixel on a screen, and the whole point of this route is
 * a client we do not control. That is {@code VanillaArtAbClientTest}'s job, and the reason the
 * photographic swatch there is not redundant with the arithmetic here is written out in
 * {@code MapArtTest.aRoundTripThroughOneTableCannotCatchAWrongTable}.
 */
@GameTestHolder(GateShards.DEFAULT)
public class VanillaArtGameTest {

    /**
     * Where a test hangs its picture.
     *
     * <p>⚠ <b>Directly ABOVE the plot, never on a distant lane — and that is a correction.</b> The
     * first cut used CLAUDE.md's big-build lane rule (its own Z lane, 21,000 blocks out), which is
     * right for a test that writes BLOCKS and wrong for one that spawns ENTITIES: 21,000 blocks
     * away is an unloaded chunk, {@code addFreshEntity} drops the frame on the floor, and
     * {@code getEntitiesOfClass} finds nothing. Six tests failed with "expected 4 frames, found 0"
     * — a message that reads exactly like a broken feature and was a broken fixture.
     *
     * <p>The plot's own column IS loaded, and each GameTest has its own plot, so that is already
     * the isolation the lane was reaching for. The vertical stagger separates this file's own
     * slots; every test takes its frames down afterwards, because a GameTest world is reused and
     * never reset.
     */
    private static BlockPos wall(GameTestHelper helper, int slot) {
        BlockPos at = helper.absolutePos(BlockPos.ZERO).above(24 + slot * 6);
        backing(helper, at);
        return at;
    }

    /**
     * <b>An actual wall behind the picture — added at the path-2 flip, and it is a finding.</b>
     *
     * <p>Every test in this file used to hang its frames in <b>mid-air</b> and read them back
     * within the same tick. `BlockAttachedEntity.tick` checks `survives()` every 100 ticks and
     * discards a frame whose support has gone (&sect;19), and it makes no distinction between a
     * wall a child mined and a wall that was never there — so all of them were measuring a picture
     * with about a five-second life. It cost nothing while the panel was drawing the picture too;
     * once the map art became the ONLY view it is the difference between a mural and a pile of
     * maps on the floor, which is why {@link MapArtPanels#hang} now refuses to hang on nothing and
     * why this exists to satisfy it.
     *
     * <p>Generous on purpose: the footprint grows to the viewer's RIGHT, which for a north-facing
     * picture is WEST, and two of these tests also put a child's own frame one block EAST. Walling
     * both ways costs a few dozen blocks and removes a whole class of "the fixture pointed the
     * wrong way" failure that this file has already had twice.
     */
    private static void backing(GameTestHelper helper, BlockPos at) {
        ServerLevel level = helper.getLevel();
        for (int east = -PanelGeometry.MAX_SPAN - 1; east <= 2; east++) {
            for (int up = 0; up <= PanelGeometry.MAX_SPAN + 1; up++) {
                BlockPos behind = at.east(east).above(up).relative(Direction.NORTH.getOpposite());
                if (level.getBlockState(behind).isAir()) {
                    level.setBlockAndUpdate(behind, Blocks.STONE.defaultBlockState());
                }
            }
        }
    }

    /**
     * Past this, a colour is not a coarse version of what was asked for, it is a different colour.
     *
     * <p>Derived from what this gate has to separate rather than from a hope about the palette:
     * measured worst honest quantisation <b>115</b> (magenta), a red/blue swap of a saturated
     * colour <b>~360</b>, a wrong hue <b>300+</b>.
     */
    private static final double WRONG_COLOUR = 200.0;

    /** A picture with four different quadrant colours — a symmetrical one hides a flip. */
    private static PaintRecipe card() {
        int s = 256;
        return new PaintRecipe(s, List.of(
                new CreatureSculpt.PaintRect(0, 0, s / 2, s / 2, 0xFF0000),
                new CreatureSculpt.PaintRect(s / 2, 0, s / 2, s / 2, 0x00FF00),
                new CreatureSculpt.PaintRect(0, s / 2, s / 2, s / 2, 0x0000FF),
                new CreatureSculpt.PaintRect(s / 2, s / 2, s / 2, s / 2, 0xFFFF00)));
    }

    // ===================== 🔴 the palette, against an independent truth =====================

    /**
     * <b>THE ABGR GUARD, on the live table.</b>
     *
     * <p>{@code MapArtTest} proves the arithmetic is self-consistent; it cannot prove the table is
     * right, because a round trip through one table agrees with itself however wrong the table is
     * (that is the mechanism by which a mod repo's red LEGO logo shipped blue with every gate
     * green). This asserts against a genuinely independent truth: {@code MapColor.col}, which is a
     * public field and really is {@code 0xRRGGBB}.
     *
     * <p>⚠ <b>The expected value is COMPUTED from {@code Brightness.modifier}, never assumed.</b>
     * The first cut of this test asserted that {@code Brightness.NORMAL} returns {@code col}
     * unchanged, and it went red on a perfectly correct palette: NORMAL's modifier is <b>220</b>,
     * not 255 (LOW 180, NORMAL 220, HIGH 255, LOWEST 135 — read out of the bytecode). That is the
     * methodology's own §4 trap, "a test can pin a wrong belief", caught by running it. Deriving
     * the expectation from the public field instead means this cannot be wrong about which
     * constant is which, and it checks all FOUR brightnesses rather than a chosen one.
     */
    @GameTest(template = "empty_test")
    public static void theLivePaletteIsTrueRgbAndNotAbgr(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (int base = 1; base < 64; base++) {
            MapColor c = MapColor.byId(base);
            if (c == null || c == MapColor.NONE || c.col == 0) {
                continue;
            }
            for (MapColor.Brightness b : MapColor.Brightness.values()) {
                checked++;
                // Vanilla's own arithmetic, per channel: (channel * modifier) / 255. Written out
                // rather than reusing MapArtPalette, which is the thing under test.
                int r = (((c.col >> 16) & 0xFF) * b.modifier) / 255;
                int g = (((c.col >> 8) & 0xFF) * b.modifier) / 255;
                int bl = ((c.col & 0xFF) * b.modifier) / 255;
                int want = (r << 16) | (g << 8) | bl;
                int got = MapArtPalette.rgb(c, b);
                if (got != want) {
                    boolean swapped = got == ((bl << 16) | (g << 8) | r);
                    wrong.add(String.format("id %d @%s: expected #%06X, palette says #%06X%s",
                            base, b, want, got,
                            swapped ? "  <-- RED AND BLUE ARE SWAPPED" : ""));
                }
            }
        }
        // The scope, not just the finding (CLAUDE.md: "checked == 0 is NOT a pass").
        if (checked < 80) {
            helper.fail(Component.literal("only " + checked + " map colours were checked, so this asserts nothing "
                    + "about the palette — the live table was not read"));
        }
        if (!wrong.isEmpty()) {
            helper.fail(Component.literal("the map palette is not true RGB (" + wrong.size() + " of " + checked
                    + " colours wrong). If red and blue are swapped, MapArtPalette.rgb has lost its "
                    + "ABGR unswap and EVERY picture on this route is the wrong colour: "
                    + wrong.get(0)));
        }
        helper.succeed();
    }

    /**
     * The palette is built from the live table and offers no transparent entry.
     *
     * <p>A hardcoded table is a second source of truth that goes stale silently; a palette
     * containing {@code NONE} would let the quantiser choose "draw nothing" for a pixel somebody
     * painted.
     */
    @GameTest(template = "empty_test")
    public static void theLivePaletteIsBigAndNeverOffersTransparency(GameTestHelper helper) {
        MapArt.Palette p = MapArtPalette.get();
        if (p.size() < 100) {
            helper.fail(Component.literal("the live map palette came back with only " + p.size() + " colours, which "
                    + "means the table was not read — a real one is 4 brightnesses x ~60 colours"));
        }
        for (byte id : p.id()) {
            if (id == MapArt.TRANSPARENT) {
                helper.fail(Component.literal("the live palette contains map colour 0, so the quantiser could choose "
                        + "transparency for a pixel that was painted"));
            }
        }
        helper.succeed();
    }

    // ===================== the route actually draws =====================

    /**
     * <b>A picture is hung as real frames holding real maps with our bytes in them.</b>
     *
     * <p>Drives {@link MapArtPanels#hang} — and then reads the world back rather than the return
     * value, because "the method said it worked" and "there is a picture on that wall" are
     * different claims and only the second is the feature.
     */
    @GameTest(template = "empty_test")
    public static void aPictureIsHungAsMapsAVanillaClientCanSee(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 0);
        MapArtPanels.Result r = MapArtPanels.hang(level, at, Direction.NORTH, card(), 2, 2);
        if (!r.ok()) {
            helper.fail(Component.literal("hanging failed: " + r.message()));
        }
        if (r.tiles() != 4) {
            helper.fail(Component.literal("a 2x2 picture must be 4 map tiles, got " + r.tiles()));
        }
        int frames = 0;
        int painted = 0;
        for (MapId id : r.ids()) {
            MapItemSavedData d = level.getMapData(id);
            if (d == null) {
                helper.fail(Component.literal("map " + id + " has no saved data, so there is nothing to send a client"));
            }
            for (byte b : d.colors) {
                if (b != MapArt.TRANSPARENT) {
                    painted++;
                }
            }
        }
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class,
                new net.minecraft.world.phys.AABB(at).inflate(4))) {
            if (MapArtPanels.isOurs(level, f)) {
                frames++;
            }
        }
        if (frames != 4) {
            helper.fail(Component.literal("expected 4 invisible frames holding locked maps, found " + frames));
        }
        // A full-canvas card must paint every pixel of every tile. A count that is merely non-zero
        // is the "a map full of colours" trap: it passes over a picture that is one stray dot.
        int expected = 4 * VanillaArt.TILE * VanillaArt.TILE;
        if (painted != expected) {
            helper.fail(Component.literal("expected all " + expected + " pixels painted across the 4 tiles, got "
                    + painted));
        }
        cleanUp(helper, at, Direction.NORTH, 2, 2);
        helper.succeed();
    }

    /**
     * <b>The quadrants land the right way up and the right way round.</b>
     *
     * <p>Upside-down and mirrored both photograph perfectly, so the four quadrant colours are read
     * back out of the map bytes and matched against where {@link PanelGeometry}'s convention says
     * they must be. This is the same claim {@code PanelGeometryTest} pins for the mod renderer,
     * asserted again on this route, so a mural drawn by one view cannot come out flipped relative
     * to the other.
     */
    @GameTest(template = "empty_test")
    public static void theQuadrantsLandWhereTheViewerExpectsThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 1);
        MapArt.Palette p = MapArtPalette.get();
        MapArtPanels.Result r = MapArtPanels.hang(level, at, Direction.NORTH, card(), 2, 2);
        if (!r.ok() || r.ids().size() != 4) {
            helper.fail(Component.literal("could not hang the calibration card: " + r.message()));
        }
        // ids() is emitted in the same reading order MapArt.split() produces: TL, TR, BL, BR.
        int[] want = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00};
        String[] where = {"top-left", "top-right", "bottom-left", "bottom-right"};
        for (int i = 0; i < 4; i++) {
            MapItemSavedData d = level.getMapData(r.ids().get(i));
            byte mid = d.colors[(VanillaArt.TILE / 2) * VanillaArt.TILE + VanillaArt.TILE / 2];
            int got = MapArt.renders(mid, p);
            // ⚠ CLASSIFY, do not threshold. The first cut demanded each quadrant land within 60 of
            // the colour asked for, and pure green failed at 69.3 -- the map palette simply has no
            // pure green (#00FF00 -> #00D93A), which is a QUANTISATION and is what this route is
            // known to cost. A threshold picked before measuring cannot tell that from a flip.
            // Asking which of the FOUR it is nearest to can: a flip or a mirror swaps a quadrant
            // for one of its neighbours, and those are 300+ apart, so this is both stricter about
            // the thing it tests and completely insensitive to how coarse the palette is.
            int nearest = 0;
            for (int j = 1; j < 4; j++) {
                if (MapArt.distance(got, want[j]) < MapArt.distance(got, want[nearest])) {
                    nearest = j;
                }
            }
            if (nearest != i) {
                helper.fail(Component.literal("the " + where[i] + " quadrant holds #" + String.format("%06X", got)
                        + ", which is nearest the colour painted in the " + where[nearest]
                        + " — the picture is flipped or mirrored"));
            }
        }
        cleanUp(helper, at, Direction.NORTH, 2, 2);
        helper.succeed();
    }

    // ===================== the neutralisations off the checklist =====================

    /**
     * <b>The map is LOCKED, so a child who breaks the frame and pockets it still has the picture.</b>
     *
     * <p>The axis the precedent does not cover. {@code MapItem.inventoryTick} redraws a carried map
     * from terrain; verified from bytecode, it reads {@code locked} and jumps past the redraw. Battle
     * of Lord reasons that its maps never leave their frames — true of a shopping mall, false of a
     * picture a child can mine.
     */
    @GameTest(template = "empty_test")
    public static void theMapIsLockedSoCarryingItDoesNotRedrawItAsTerrain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 2);
        MapArtPanels.Result r = MapArtPanels.hang(level, at, Direction.NORTH, card(), 1, 1);
        if (!r.ok()) {
            helper.fail(Component.literal("could not hang: " + r.message()));
        }
        MapItemSavedData d = level.getMapData(r.ids().get(0));
        if (!d.locked) {
            helper.fail(Component.literal("the map is not locked, so the first time a child picks it up vanilla will "
                    + "overwrite the picture with a picture of the ground"));
        }
        cleanUp(helper, at, Direction.NORTH, 1, 1);
        helper.succeed();
    }

    /** Invisible, invulnerable, unrotated — the rest of the checklist, asserted in the world. */
    @GameTest(template = "empty_test")
    public static void theFramesAreInvisibleUnrotatedAndInvulnerable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 3);
        MapArtPanels.hang(level, at, Direction.NORTH, card(), 2, 1);
        int seen = 0;
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class,
                new net.minecraft.world.phys.AABB(at).inflate(4))) {
            if (f.getItem().get(DataComponents.MAP_ID) == null) {
                continue;
            }
            seen++;
            if (!f.isInvisible()) {
                helper.fail(Component.literal("a visible frame puts a wooden border round every tile and a seam "
                        + "across the picture"));
            }
            if (!f.isInvulnerable()) {
                helper.fail(Component.literal("the picture must not be destroyable by a stray arrow"));
            }
            if (f.getRotation() != 0) {
                helper.fail(Component.literal("a rotated frame rotates its share of the picture, rotation="
                        + f.getRotation()));
            }
        }
        if (seen != 2) {
            helper.fail(Component.literal("expected 2 tiles, found " + seen));
        }
        cleanUp(helper, at, Direction.NORTH, 2, 1);
        helper.succeed();
    }

    /**
     * <b>Repainting reuses the map ids rather than leaking a file in level data every time.</b>
     *
     * <p>{@code paint_surface} explicitly supports repainting the same id — "the same Spikor but
     * bluer" is the case the whole recipe format exists for — and map data is a file per id with a
     * world-global counter. Minting fresh ids per repaint is a save that grows with how often a
     * child changes his mind.
     */
    @GameTest(template = "empty_test")
    public static void repaintingReusesTheMapsRatherThanLeakingLevelData(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 4);
        MapArtPanels.Result first = MapArtPanels.hang(level, at, Direction.NORTH, card(), 2, 1);
        PaintRecipe bluer = new PaintRecipe(256, List.of(
                new CreatureSculpt.PaintRect(0, 0, 256, 256, 0x0000FF)));
        MapArtPanels.Result second = MapArtPanels.hang(level, at, Direction.NORTH, bluer, 2, 1);
        if (!second.ok()) {
            helper.fail(Component.literal("the repaint failed: " + second.message()));
        }
        if (second.reused() != 2) {
            helper.fail(Component.literal("a repaint must reuse both tiles, reused=" + second.reused()));
        }
        if (!first.ids().equals(second.ids())) {
            helper.fail(Component.literal("the repaint minted new map ids (" + first.ids() + " -> " + second.ids()
                    + "), so every 'make him bluer' leaks a map_N.dat"));
        }
        // And it really did repaint -- reuse must not mean "left it alone".
        MapArt.Palette p = MapArtPalette.get();
        MapItemSavedData d = level.getMapData(second.ids().get(0));
        int got = MapArt.renders(d.colors[VanillaArt.TILE * 64 + 64], p);
        // Nearer the NEW colour than the OLD one, rather than within a distance of the new one.
        // The palette's nearest blue is #3737DC, 85 away from #0000FF, so a "within 60" test fails
        // on a repaint that worked perfectly -- the third time in this file that a threshold picked
        // before measuring indicted the feature instead of itself. A comparison needs no threshold
        // and answers the question actually being asked: did this tile change to the new picture?
        int wasRed = 0xFF0000;      // the card's top-left, i.e. what this tile held before
        if (MapArt.distance(got, wasRed) <= MapArt.distance(got, 0x0000FF)) {
            helper.fail(Component.literal("the tile was reused but not repainted — it holds #"
                    + String.format("%06X", got) + ", which is still nearer the old colour"));
        }
        cleanUp(helper, at, Direction.NORTH, 2, 1);
        helper.succeed();
    }

    // ===================== the negatives (§3.C.15) =====================

    /**
     * <b>An ordinary map or item frame elsewhere in the world is untouched.</b>
     *
     * <p>The regression this route could most easily cause: a take-down that deletes a child's own
     * item frame, or a repaint that grabs a map somebody made. Both are tested by putting a real,
     * ordinary, unlocked, visible framed map right next to ours and demanding it survives.
     */
    @GameTest(template = "empty_test")
    public static void anOrdinaryMapAndItemFrameAreLeftAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 5);
        BlockPos theirs = at.east(1);

        ItemStack ordinary = MapItem.create(level, 0, 0, (byte) 0, true, false);
        MapId theirId = ordinary.get(DataComponents.MAP_ID);
        ItemFrame mine = new ItemFrame(level, theirs, Direction.NORTH);
        mine.setItem(ordinary, false);          // VISIBLE, UNLOCKED — a child's own frame
        level.addFreshEntity(mine);
        MapItemSavedData theirData = level.getMapData(theirId);
        byte[] before = theirData.colors.clone();

        MapArtPanels.hang(level, at, Direction.NORTH, card(), 1, 1);
        MapArtPanels.takeDown(level, at, Direction.NORTH, 4, 4);   // deliberately over-wide

        if (mine.isRemoved()) {
            helper.fail(Component.literal("the take-down deleted a frame that was not ours"));
        }
        if (!java.util.Arrays.equals(before, level.getMapData(theirId).colors)) {
            helper.fail(Component.literal("an ordinary map's pixels were overwritten"));
        }
        if (MapArtPanels.isOurs(level, mine)) {
            helper.fail(Component.literal("a plain visible frame with an unlocked map is being claimed as ours, so "
                    + "the take-down would eat a child's own pictures"));
        }
        // AND THE OTHER HALF: hanging a picture ACROSS their frame must refuse rather than
        // replace what is in it. Only one frame fits a block face, so there is no working around
        // it -- found by re-reading the diff rather than by a failing test, which is why it has
        // one now.
        // ⚠ HANG IT ON THEIR SPOT, not "two wide from next door". The first cut hung a 2-wide
        // picture at `at` expecting it to reach `at.east(1)`, and a picture grows to the VIEWER's
        // right -- which for a north-facing picture is WEST (Direction.NORTH.getCounterClockWise()).
        // So it never touched their frame, the hang correctly succeeded, and the test reported that
        // the refusal was broken. A test that hardcodes a compass direction is asserting its own
        // guess about handedness; starting the picture AT their block cannot be wrong about it.
        MapArtPanels.Result over = MapArtPanels.hang(level, theirs, Direction.NORTH, card(), 1, 1);
        if (over.ok()) {
            helper.fail(Component.literal("hanging a picture over a child's own item frame was allowed"));
        }
        if (mine.getItem().get(DataComponents.MAP_ID) == null
                || !mine.getItem().get(DataComponents.MAP_ID).equals(theirId)) {
            helper.fail(Component.literal("the refusal still swapped what was in their frame"));
        }
        mine.discard();
        cleanUp(helper, at, Direction.NORTH, 1, 1);
        helper.succeed();
    }

    /**
     * <b>With the route OFF, a wall gets a panel and not one frame is hung.</b>
     *
     * <p>This is the "without the shim" half of the both-ways gate path 2 requires, and it is also
     * the back-compat promise: turning {@code vanillaart=off} restores exactly what a child played
     * before the spike existed.
     */
    @GameTest(template = "empty_test")
    public static void withTheRouteOffAPanelIsWhatDrawsThePicture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 6);
        VanillaArt.force(false);
        try {
            if (!PaintPanels.panelOnStaticSurface()) {
                helper.fail(Component.literal("with the route off a static wall must still get a panel"));
            }
            level.setBlockAndUpdate(at, ModPaint.PAINT_PANEL.get().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                            Direction.NORTH));
            PaintPanels.Result r = PaintPanels.paint(level, at, "vanillaartoff", card(), 1, 1);
            if (!r.ok()) {
                helper.fail(Component.literal("the panel itself must still paint with the route off: " + r.message()));
            }
            if (r.message().contains("map tile")) {
                helper.fail(Component.literal("the OFF path mentioned map tiles: " + r.message()));
            }
            long frames = level.getEntitiesOfClass(ItemFrame.class,
                    new net.minecraft.world.phys.AABB(at).inflate(4)).size();
            if (frames != 0) {
                helper.fail(Component.literal("with the route off, " + frames + " item frame(s) appeared"));
            }
        } finally {
            VanillaArt.force(null);
            level.removeBlock(at, false);
        }
        helper.succeed();
    }

    /**
     * <b>The oracle above, verified to FAIL</b> — with the route ON and no panel, the same wall is
     * painted as map art instead.
     *
     * <p>Without this, {@code withTheRouteOffAPanelIsWhatDrawsThePicture} is equally consistent
     * with a route that never works at all, which is the greenest possible way to measure nothing.
     */
    @GameTest(template = "empty_test")
    public static void withTheRouteOnTheSameWallIsPaintedAsMapArt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 7);
        VanillaArt.force(true);
        try {
            if (PaintPanels.panelOnStaticSurface()) {
                helper.fail(Component.literal("with the route on a static wall must NOT get a panel"));
            }
            PaintPanels.Result r =
                    PaintPanels.paint(level, at, Direction.NORTH, "vanillaarton", card(), 2, 1);
            if (!r.ok()) {
                helper.fail(Component.literal("painting failed with the route on: " + r.message()));
            }
            long frames = level.getEntitiesOfClass(ItemFrame.class,
                    new net.minecraft.world.phys.AABB(at).inflate(6)).stream()
                    .filter(f -> MapArtPanels.isOurs(level, f)).count();
            if (frames != 2) {
                helper.fail(Component.literal("the route is on but " + frames + " frame(s) were hung, so the OFF "
                        + "test is not proving anything: " + r.message()));
            }
            if (!r.message().contains("map tile")) {
                helper.fail(Component.literal("the ON path did not report its tiles: " + r.message()));
            }
            if (level.getBlockEntity(at) instanceof PaintPanelBlockEntity) {
                helper.fail(Component.literal("the vanilla route left a panel behind, so a modded client would see "
                        + "the panel picture with the map picture behind it"));
            }
            MapArtPanels.takeDown(level, at, Direction.NORTH, 2, 1);
        } finally {
            VanillaArt.force(null);
        }
        helper.succeed();
    }

    /**
     * <b>ONE VIEW PER SURFACE — a panel present means the mod renderer draws it, even with the
     * route on.</b>
     *
     * <p>The rule that keeps a contraption working after the flip, and the one the spike
     * deliberately broke: it ran the two arms additively so a single wish could be photographed
     * both ways (&sect;18). In production that would show a modded client the panel picture with
     * the map picture a block behind it, so a picture that HAS a panel gets no map art.
     *
     * <p>Not a hypothetical: a carriage's picture is a panel added to a train plan, and it reaches
     * {@link PaintPanels#paint} the same way this does.
     */
    @GameTest(template = "empty_test")
    public static void aPanelMeansNoMapArtEvenWithTheRouteOn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 11);
        VanillaArt.force(true);
        try {
            level.setBlockAndUpdate(at, ModPaint.PAINT_PANEL.get().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                            Direction.NORTH));
            PaintPanels.Result r = PaintPanels.paint(level, at, "bothviews", card(), 2, 1);
            if (!r.ok()) {
                helper.fail(Component.literal("a panel must still paint with the route on: " + r.message()));
            }
            long frames = level.getEntitiesOfClass(ItemFrame.class,
                    new net.minecraft.world.phys.AABB(at).inflate(6)).stream()
                    .filter(f -> MapArtPanels.isOurs(level, f)).count();
            if (frames != 0) {
                helper.fail(Component.literal(frames + " map tile(s) were hung over a panel, so a modded client sees "
                        + "two pictures a block apart: " + r.message()));
            }
            if (!(level.getBlockEntity(at) instanceof PaintPanelBlockEntity p)
                    || !"bothviews".equals(p.artKey())) {
                helper.fail(Component.literal("the panel was not the thing that got painted"));
            }
        } finally {
            VanillaArt.force(null);
            level.removeBlock(at, false);
        }
        helper.succeed();
    }

    /**
     * <b>A picture with nothing behind it is refused rather than hung and then quietly lost.</b>
     *
     * <p>Verified to fail, in the sense that matters: the same call one block along, where there IS
     * a wall, succeeds. `BlockAttachedEntity.tick` discards a frame whose support has gone within
     * 100 ticks (&sect;19) and never says so, so before the flip every test in this file was hanging
     * its pictures in mid-air and reading them back inside the same tick — a green gate over a
     * mural with a five-second life. Once map art is the only view, that has to be an error at hang
     * time.
     */
    @GameTest(template = "empty_test")
    public static void aPictureWithNoWallBehindItIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 12);          // walled by the fixture
        BlockPos midAir = at.above(PanelGeometry.MAX_SPAN + 4);
        MapArtPanels.Result nothing =
                MapArtPanels.hang(level, midAir, Direction.NORTH, card(), 1, 1);
        if (nothing.ok()) {
            helper.fail(Component.literal("a picture was hung on thin air, so it will be on the floor in five "
                    + "seconds and nothing will have said so"));
        }
        if (!nothing.message().contains("hang on")) {
            helper.fail(Component.literal("the refusal did not say what was missing: " + nothing.message()));
        }
        MapArtPanels.Result walled = MapArtPanels.hang(level, at, Direction.NORTH, card(), 1, 1);
        if (!walled.ok()) {
            helper.fail(Component.literal("the same picture ON a wall was refused too, so the check is not "
                    + "measuring the wall: " + walled.message()));
        }
        cleanUp(helper, at, Direction.NORTH, 1, 1);
        helper.succeed();
    }

    /**
     * <b>Undoing a repaint puts the EARLIER picture back, with no panel anywhere.</b>
     *
     * <p>The promise the panel route has always kept, and the one the path-2 flip nearly dropped in
     * silence: the previous picture used to be read off the block entity, and a static wall no
     * longer has one. {@code PaintRecord} carries it now, and this drives the real
     * {@code WishUndo.undoPainting} rather than a copy of it.
     *
     * <p>Asserted on the PIXELS rather than on the return value, because "undo said true" and
     * "the wall shows the first picture again" are different claims and only the second is the
     * feature.
     */
    @GameTest(template = "empty_test")
    public static void undoingARepaintPutsTheEarlierPictureBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 13);
        String id = "undorepaint";
        VanillaArt.force(true);
        try {
            PaintRecipe first = card();
            PaintRecipe second = new PaintRecipe(256, List.of(
                    new CreatureSculpt.PaintRect(0, 0, 256, 256, 0x0000FF)));
            if (!PaintPanels.paint(level, at, Direction.NORTH, id, first, 1, 1).ok()) {
                helper.fail(Component.literal("the first painting failed"));
            }
            byte[] wanted = level.getMapData(mapAt(level, at)).colors.clone();
            PaintRegistry.put(level.getServer(), new PaintRecord(id,
                    level.dimension().identifier().toString(), at.getX(), at.getY(), at.getZ(),
                    id, false, "", PaintRecipe.blank(), 1, 1, "north", 1, 1, first));

            if (!PaintPanels.paint(level, at, Direction.NORTH, id, second, 1, 1).ok()) {
                helper.fail(Component.literal("the repaint failed"));
            }
            if (java.util.Arrays.equals(wanted, level.getMapData(mapAt(level, at)).colors)) {
                helper.fail(Component.literal("the repaint changed nothing, so the undo below proves nothing"));
            }
            PaintRegistry.put(level.getServer(), new PaintRecord(id,
                    level.dimension().identifier().toString(), at.getX(), at.getY(), at.getZ(),
                    id, false, id, first, 1, 1, "north", 1, 1, second));

            if (!com.moddingfromamod.wish.WishUndo.undoOne(level.getServer(),
                    com.moddingfromamod.marshal.MadeKind.PAINTING, id)) {
                helper.fail(Component.literal("undo reported that it put nothing back"));
            }
            if (level.getBlockEntity(at) instanceof PaintPanelBlockEntity) {
                helper.fail(Component.literal("undo left a panel behind on a wall that never had one"));
            }
            byte[] now = level.getMapData(mapAt(level, at)).colors;
            if (!java.util.Arrays.equals(wanted, now)) {
                helper.fail(Component.literal("undo did not put the first picture back — the wall shows something "
                        + "else, so a child who said \"no, the old one\" lost both"));
            }
        } finally {
            VanillaArt.force(null);
            PaintRegistry.remove(level.getServer(), id);
            MapArtPanels.takeDown(level, at, Direction.NORTH, 1, 1);
        }
        helper.succeed();
    }

    /** The one map our frame at {@code at} is holding, or a failure if there is not exactly one. */
    private static MapId mapAt(ServerLevel level, BlockPos at) {
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class,
                new net.minecraft.world.phys.AABB(at).inflate(0.4))) {
            if (MapArtPanels.isOurs(level, f)) {
                MapId id = f.getItem().get(DataComponents.MAP_ID);
                if (id != null) {
                    return id;
                }
            }
        }
        throw new IllegalStateException("no map of ours at " + at.toShortString());
    }

    /**
     * <b>The CONTROL (&sect;3.C.13): nothing this route puts in the world carries a mod id.</b>
     *
     * <p>The whole claim is "a client with no mod can see this". A frame, a map or a component
     * naming {@code moddingfromamod:} would be a claim a stock client cannot parse, and the failure
     * would be invisible from the server side — which is exactly the shape of thing this guard
     * exists for.
     */
    @GameTest(template = "empty_test")
    public static void nothingHungCarriesAModId(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 8);
        MapArtPanels.Result r = MapArtPanels.hang(level, at, Direction.NORTH, card(), 2, 1);
        if (!r.ok()) {
            helper.fail(Component.literal("could not hang: " + r.message()));
        }
        int checked = 0;
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class,
                new net.minecraft.world.phys.AABB(at).inflate(4))) {
            if (!MapArtPanels.isOurs(level, f)) {
                continue;
            }
            checked++;
            String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(f.getType()).toString();
            if (type.contains(ModdingFromAModMod.MODID)) {
                helper.fail(Component.literal("the picture is hung on a MOD entity (" + type + "), which a vanilla "
                        + "client cannot spawn"));
            }
            ItemStack held = f.getItem();
            String item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(held.getItem()).toString();
            if (!item.equals("minecraft:filled_map")) {
                helper.fail(Component.literal("the tile is not a vanilla filled map, it is " + item));
            }
            String components = held.getComponents().toString();
            if (components.contains(ModdingFromAModMod.MODID)) {
                helper.fail(Component.literal("the map stack carries a mod component a stock client would have to be "
                        + "taught: " + components));
            }
        }
        if (checked != 2) {
            helper.fail(Component.literal("the control checked " + checked + " frames, which is not the 2 that were "
                    + "hung — it is asserting about nothing"));
        }
        cleanUp(helper, at, Direction.NORTH, 2, 1);
        helper.succeed();
    }

    /** The take-down really does take our own art down. */
    @GameTest(template = "empty_test")
    public static void takingAPictureDownRemovesOurFrames(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = wall(helper, 9);
        MapArtPanels.hang(level, at, Direction.NORTH, card(), 2, 2);
        int removed = MapArtPanels.takeDown(level, at, Direction.NORTH, 2, 2);
        if (removed != 4) {
            helper.fail(Component.literal("expected to take 4 tiles down, took " + removed));
        }
        long left = level.getEntitiesOfClass(ItemFrame.class,
                new net.minecraft.world.phys.AABB(at).inflate(4)).stream()
                .filter(f -> !f.isRemoved() && MapArtPanels.isOurs(level, f)).count();
        if (left != 0) {
            helper.fail(Component.literal(left + " of our frames survived the take-down"));
        }
        helper.succeed();
    }

    /** A picture nobody described is refused rather than hung as a black wall. */
    @GameTest(template = "empty_test")
    public static void anEmptyPictureIsRefused(GameTestHelper helper) {
        MapArtPanels.Result r = MapArtPanels.hang(helper.getLevel(), wall(helper, 10),
                Direction.NORTH, PaintRecipe.blank(), 1, 1);
        if (r.ok()) {
            helper.fail(Component.literal("an empty recipe was hung"));
        }
        helper.succeed();
    }

    // ===================== the measurement (§3.C.12) =====================

    /**
     * <b>The static delta, measured and REPORTED rather than asserted into a verdict.</b>
     *
     * <p>Per &sect;3.C.12 the number is the deliverable. Quantisation is deterministic — the same
     * recipe gives the same bytes every time — so the <b>noise floor is exactly 0.0</b>, and that
     * is asserted here rather than assumed, because a floor that is genuinely zero is what makes a
     * per-colour delta directly readable instead of something to compare against a spread.
     *
     * <p>The assertion is deliberately loose (a per-colour ceiling that a working palette clears
     * comfortably); the LOG LINE is what the results table is written from.
     */
    @GameTest(template = "empty_test")
    public static void thePaletteQuantisationIsMeasuredPerColour(GameTestHelper helper) {
        MapArt.Palette p = MapArtPalette.get();
        // The colours a real wish uses: the agent's own vocabulary, not a synthetic ramp.
        int[] colours = {
            0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
            0xFFFFFF, 0x000000, 0x808080, 0xFF8000, 0x8000FF, 0x2FA84F,
            0xFFD800, 0xC01818, 0x101010, 0xFF69B4,
        };
        double total = 0;
        double worst = 0;
        StringBuilder log = new StringBuilder("VANILLA-ART PALETTE DELTA (RGB distance, 0-441):\n");
        for (int c : colours) {
            byte id = MapArt.nearest(0xFF000000 | c, p);
            int got = MapArt.renders(id, p);
            double d = MapArt.distance(c, got);
            total += d;
            worst = Math.max(worst, d);
            log.append(String.format("  #%06X -> #%06X  delta %.1f%n", c, got, d));
            // ⚠ THE NUMBER IS THE DELIVERABLE; the assertion only has to catch a WRONG colour.
            // The first cut gated at 90, which is a threshold chosen before anything was measured
            // -- exactly what methodology §3.C.12 says not to do ("report the delta, not a
            // verdict"). Magenta legitimately costs 115: the map palette has no vivid magenta.
            // WRONG_COLOUR is set from what this gate must be able to separate, not from a hope
            // about the palette: a red/blue swap of a saturated colour is ~360 and a picked-wrong
            // hue is 300+, against a measured worst honest quantisation of 115. 200 sits between
            // them with room on both sides.
            if (d > WRONG_COLOUR) {
                helper.fail(Component.literal(String.format("#%06X came back #%06X, a distance of %.1f — that is a "
                        + "different colour, not a quantisation", c, got, d)));
            }
        }
        double mean = total / colours.length;
        // NOISE FLOOR, asserted rather than claimed: quantisation is a pure function.
        for (int c : colours) {
            if (MapArt.nearest(0xFF000000 | c, p) != MapArt.nearest(0xFF000000 | c, p)) {
                helper.fail(Component.literal("quantisation is not deterministic, so the noise floor is not 0"));
            }
        }
        log.append(String.format("  MEAN %.1f   WORST %.1f   NOISE FLOOR 0.0 (deterministic)%n",
                mean, worst));
        log.append("  palette size ").append(p.size()).append('\n');
        System.out.println(log);
        helper.succeed();
    }

    /**
     * <b>The whole picture's delta, pixel for pixel, against the arm the mod renderer draws.</b>
     *
     * <p>Both arms go through {@link CreatureSculpt#rasterise}, so this is a measurement of the
     * QUANTISATION and of nothing else — which is the only reason the number means anything.
     * Reported, with the share of pixels that are byte-identical, the way the item spike reported
     * 135/162.
     */
    @GameTest(template = "empty_test")
    public static void theWholePictureDeltaIsMeasured(GameTestHelper helper) {
        MapArt.Palette p = MapArtPalette.get();
        PaintRecipe face = PaintPanels.demoFace();          // a REAL picture, not the card
        int size = VanillaArt.TILE;
        int[] armA = CreatureSculpt.rasterise(size, face.rects());
        byte[] armB = MapArt.tilesOf(new PaintRecipe(size, face.rects()), 1, 1, p)[0];

        long identical = 0;
        long opaque = 0;
        double total = 0;
        double worst = 0;
        for (int i = 0; i < armA.length; i++) {
            boolean aClear = ((armA[i] >>> 24) & 0xFF) < MapArt.ALPHA_CUTOFF;
            boolean bClear = armB[i] == MapArt.TRANSPARENT;
            if (aClear || bClear) {
                if (aClear != bClear) {
                    helper.fail(Component.literal("pixel " + i + " disagrees about transparency between the arms, so "
                            + "the picture's see-through space has moved"));
                }
                continue;
            }
            opaque++;
            int a = armA[i] & 0xFFFFFF;
            int b = MapArt.renders(armB[i], p);
            if (a == b) {
                identical++;
            }
            double d = MapArt.distance(a, b);
            total += d;
            worst = Math.max(worst, d);
        }
        System.out.printf("VANILLA-ART PICTURE DELTA: %d/%d opaque pixels byte-identical (%.1f%%), "
                        + "mean %.2f, worst %.1f, noise floor 0.0%n",
                identical, opaque, 100.0 * identical / opaque, total / opaque, worst);
        if (opaque < 1000) {
            helper.fail(Component.literal("only " + opaque + " opaque pixels were compared — the fixture is empty and "
                    + "this measured nothing"));
        }
        helper.succeed();
    }

    private static void cleanUp(GameTestHelper helper, BlockPos at, Direction facing, int w, int h) {
        // A GameTest world is REUSED and never reset, so an entity left behind outlives every
        // future run and poisons whatever asks about entities near this lane (CLAUDE.md).
        MapArtPanels.takeDown(helper.getLevel(), at, facing, w, h);
    }
}
