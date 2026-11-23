package com.moddingfromamod.paint;


// ── TRANSITIONAL — art spike. See docs/VANILLA_ART_SPIKE_RESULTS.md §11.
// The switch. It now turns the route OFF rather than on; the classification is path 2, so vanilla
// map art is the DEFAULT view of a picture and the panel renderer is the shim for the one surface
// vanilla structurally cannot draw on. Gated.

/**
 * <b>WHETHER a picture is drawn for a client that has no mod, and how big a tile is.</b>
 *
 * <p>The one place that answers "are we serving vanilla clients". Everything else — the tool, the
 * hanging code, the gates — asks here rather than carrying its own copy of the answer, because two
 * copies of "are we in vanilla mode?" is exactly how the paths drift (methodology &sect;3.B.7).
 *
 * <p><b>ON by default since the path-2 reclassification, and the inversion is the whole point.</b>
 * A picture now hangs as vanilla map art on any static surface, which every client can see with no
 * mod, no resource pack and no new network channel. {@link PaintPanelBlock},
 * {@link PaintPanelBlockEntity} and {@code PaintPanelRenderer} are still built, still tested and
 * still the view on a <b>contraption</b> — a Create carriage carries a block and cannot carry an
 * item frame (§19) — but they are no longer what a child on a wall sees. Turning this OFF restores
 * the pre-spike behaviour exactly, which is what makes the A/B still runnable and what the both-ways
 * gate drives.
 *
 * <p><b>One view per surface, and that is why the flip is not just this line.</b> The two views draw
 * a block apart, so leaving both on would show a modded client the panel picture with the map
 * picture behind it. {@link PaintPanels#paint} therefore routes: a panel at the position means the
 * mod renderer is drawing that picture, and no panel means vanilla map art is.
 *
 * <h2>Why the divergence is allowed to be here and nowhere above it</h2>
 *
 * <p>Methodology &sect;0.5: only the <b>view</b> may diverge. {@link PaintArt},
 * {@link PaintRecipe}, {@link PanelGeometry}, {@link PaintRecord} and {@link PaintRegistry} are the
 * internal model and are untouched by this route — a picture means the same thing, is recorded the
 * same way and is undone the same way whichever view draws it. {@code paint_surface}'s schema, the
 * lessons and the agent prompt name no path and cannot tell which is active;
 * {@code SourceRuleSweepTest.theAgentContractNeverNamesAPath} fails the build if that stops being
 * true.
 *
 * <p>Minecraft-free on purpose, like {@code VanillaCarrier}: whether the route is on is a property
 * decision, and keeping it here means the tiling arithmetic below can be unit-tested without
 * booting a game.
 */
public final class VanillaArt {

    /**
     * ⚠ <b>{@code vanillaart} IS A TEST SEAM, NOT A LEVER —, 2026-09-22.</b>
     *
     * <p>The system property it used to read is gone. asked how long the flag stays *"a
     * supported lever rather than a test seam. It is currently both"*, and
     * {@code docs/COMPAT_SHIMS.md}'s decision settles it against the lever for one reason: the
     * {@code off} arm puts a <b>panel</b> on a static wall, and a panel is <b>invisible to a stock
     * client</b> — which is v1's entire audience. A switch a grown-up can set that makes a child's
     * picture disappear is not a rollback, it is a trap.
     *
     * <p>It was never much of a lever anyway, and that was checked rather than assumed: it is in no
     * {@code RuntimeConfig} entry, no environment variable, no {@code config.properties} key, no
     * launcher and no runbook. The only way to reach it was a raw {@code -D} on a JVM, so removing
     * the read closes a hole nothing was using.
     *
     * <p><b>{@link #force} stays, and that is the whole point of the distinction.</b> Unlike
     * {@code vanillarig} — a rollback switch with no oracle, deleted outright — this arm is
     * <b>actively measured</b>: six Gate C comparisons drive both sides
     * ({@code ArtSideBySideClientTest} photographs one real wish drawn both ways,
     * {@code ArtSurvivalClientTest} asks what a mined wall and a train assembly do to each arm,
     * plus {@code ArtWishSurvivalClientTest}, {@code VanillaArtAbClientTest},
     * {@code PaintPanelClientTest} and {@code VanillaArtGameTest}). An A/B somebody still measures
     * against is an arm; the day nobody does, it becomes debt and the register row says so.
     */

    /**
     * A Minecraft map is always 128&times;128 pixels, and always exactly one block in a frame.
     *
     * <p>Not a tunable. It is vanilla's own constant ({@code MapItemSavedData.colors} is
     * {@code new byte[16384]}), and it is what makes the tiling arithmetic below fall out: one map
     * per block of span, so an <i>n</i>-block-wide picture is <i>n</i> tiles wide with no
     * remainder and no seam that lands mid-block.
     */
    public static final int TILE = 128;

    /** A test's override, or null to read the property. Always restore it in a {@code finally}. */
    private static volatile Boolean forced;

    private VanillaArt() {
    }

    /**
     * Whether a picture on a static surface is hung as map art every client can see.
     *
     * <p><b>Always true in a shipped run.</b> Only {@link #force} can answer otherwise, and only
     * for the length of one test — see the note on the removed property above.
     */
    public static boolean enabled() {
        Boolean override = forced;
        return override == null || override;
    }

    /**
     * The A/B seam: answer {@link #enabled} differently for the length of one measurement.
     *
     * <p>Always restore it with {@code force(null)} in a {@code finally}. It is a static, so a
     * leaked override is one test silently measuring the other arm — the shape CLAUDE.md names for
     * GameTests sharing statics within a batch.
     */
    public static void force(Boolean on) {
        forced = on;
    }

    /**
     * How many map tiles a picture of this many blocks needs, across or down.
     *
     * <p>One per block, because a map in a frame occupies exactly one block face. The span is
     * clamped through {@link PanelGeometry#clampSpan} rather than by a second rule here — the panel
     * and the map art must agree about how big a picture is, and the only way to guarantee that is
     * for them to ask the same function.
     */
    public static int tiles(int blocks) {
        return PanelGeometry.clampSpan(blocks);
    }

    /**
     * The pixel resolution the picture is rasterised at for this route.
     *
     * <p><b>Deliberately derived from the span rather than from {@link PaintArt#SIZE}.</b> The mod
     * renderer draws ONE 256&sup2; texture across however many blocks the picture spans, so a
     * 4&times;1 mural is 64 texels per block; a map is a fixed 128&sup2; PER BLOCK, so the same
     * mural is 512 wide. Rasterising at 256 and then stretching would throw away half the detail
     * the route is capable of and then blame the palette for it — which is exactly the kind of
     * measurement error &sect;3.C.12 warns about, since a blurry A/B and a quantised A/B look the
     * same in a mean.
     */
    public static int pixels(int blocks) {
        return tiles(blocks) * TILE;
    }
}
