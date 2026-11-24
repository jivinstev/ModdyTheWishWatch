package com.moddingfromamod.library;

import net.minecraft.network.chat.Component;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.TestPlots;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.WishCatalog;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.wish.WishTemplates;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Every starter a child can press, served from its own words and run to the end, on whichever
 * Minecraft this build is for</b> (D7/#593).
 *
 * <p>The starter row is the first thing a new child sees, and each button is a fixed list of
 * commands — {@code wishmob spawn}, {@code wishdatapack … give}, {@code wishbuild}, {@code
 * wishpaint}, a {@code summon} with NBT. Every one of those is a place where two Minecraft versions
 * disagree (a component spelling, an attribute id, a pack format), and until this test nothing
 * replayed the row as a whole: {@code WishLibraryGameTest} proves the WIRING with a fixture cake,
 * and {@code StarterDatapackDialectGameTest} parses the five packs without running them. So a
 * starter that stopped working on one target would have been found by a child pressing it.
 *
 * <p>Two steps per starter, both the shipped ones:
 * <ol>
 *   <li>its PHRASE — the child's own words — must match it through the live matcher
 *       ({@code matchConfident}, the stage a typed wish meets first), so the button and the typed
 *       wish are one recipe;</li>
 *   <li>each of its commands must succeed through {@link WishExecutor#runOne}, the unit a served
 *       wish runs, which also counts a HALF-worked command (Minecraft ignoring part of it)
 *       as the failure it is.</li>
 * </ol>
 *
 * <p><b>Where:</b> a lava-moat castle and a treehouse are real builds, so the replay happens on a
 * {@link TestPlots} site twenty thousand blocks from the grid, force-loaded, rather than beside a
 * plot a later batch will reuse.
 *
 * <p>🔴 <b>Verified to fail</b> on 1.21.4 with {@code DatapackDialect}'s rewrite switched off: it
 * names the teleport wand and the tiny cookie, whose {@code give} functions never load
 * ({@code custom_model_data} as an int). It took two corrections to get there, both worth keeping:
 * the first cut replayed straight away and passed on "Loaded — do that again!", and the second
 * reused packs a previous run had left loaded (Gate B's world is reused) — so phase 1 always
 * REINSTALLS and waits for that reload. It also needed {@code runOne} to stop calling a refusal a
 * success ({@code WishGameTest.aCommandThatRefusesIsReportedAsAFailure}).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class StarterReplayGameTest {

    /** Ticks between two starters: long enough for a spawn or a build to settle before the next. */
    private static final int SPACING = 20;

    /**
     * WHERE: one TestPlots site, the starters {@value #SPOT_BLOCKS} blocks apart along it, each with a
     * {@value #SPOT_RADIUS}-chunk square forced. It used to be a site per starter — fifteen
     * 169-chunk squares a thousand blocks apart — and the half-built chunks around them unloaded at the
     * next batch boundary and were saved one by one: 17–23 s here, 69 s on Cloud Build, every run,
     * charged to the one-test batch after this one. A starter's own commands load whatever else they
     * touch (a build places block by block; an event stages over ticks), and this test asserts that
     * each command SUCCEEDS, which is what the forced square is for.
     */
    private static final int SPOT_BLOCKS = 128;
    private static final int SPOT_RADIUS = 4;

    private static BlockPos spot(GameTestHelper helper, int index) {
        return TestPlots.STARTER_REPLAY.spot(helper, 1, index, SPOT_BLOCKS, SPOT_RADIUS);
    }

    /** The starter recipes the jar ships, read from the resource rather than typed here. */
    private static List<String> shippedStarterIds() throws Exception {
        try (InputStream in = StarterReplayGameTest.class
                .getResourceAsStream("/starter-library/templates.json")) {
            if (in == null) {
                return List.of();
            }
            JsonObject root = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> ids = new ArrayList<>();
            // Only the ones THIS build serves: an entry that needs what this Minecraft cannot do
            // (StarterNeeds -- worn boots on 1.21.1) is left off the row on purpose.
            root.getAsJsonArray("templates").forEach(t -> {
                String id = t.getAsJsonObject().get("id").getAsString();
                if (StarterNeeds.unmet(t.getAsJsonObject()) == null) {
                    ids.add(id);
                }
            });
            return ids;
        }
    }

    /** How long the starter packs may take to finish loading before the run says so. */
    private static final int LOAD_TICKS = 2400;

    @GameTest(template = "empty_test", batch = "starterreplay", timeoutTicks = 6000)
    public static void everyStarterIsServedFromItsWordsAndEveryCommandSucceeds(GameTestHelper helper) {
        List<String> shipped;
        try {
            shipped = shippedStarterIds();
        } catch (Exception e) {
            helper.fail(Component.literal("could not read the bundled starter-library/templates.json: " + e));
            return;
        }
        if (shipped.isEmpty()) {
            helper.fail(Component.literal("the jar ships no starter-library/templates.json — the starter row is empty"));
            return;
        }
        WishTemplates live = WishCatalog.templates();
        List<WishTemplates.Known> starters = live.known().stream()
                .filter(k -> shipped.contains(k.id())).toList();
        helper.assertTrue(starters.size() == shipped.size(),
                Component.literal("the jar ships " + shipped.size() + " starters but the live recipe set serves "
                        + starters.size() + ": " + starters.stream().map(WishTemplates.Known::id)
                        .toList()));

        var server = helper.getLevel().getServer();
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);

        // ── 1. THE PACKS, LOADED THE WAY A WORLD LOAD LOADS THEM ──────────────────────────────
        // `wishdatapack <id> give` on a pack that is not loaded yet does NOT give: it installs the
        // pack, starts an asynchronous reload, answers "Loaded — do that again!" and returns
        // success. A world load installs every stored pack first (DatapackStore.installAllIntoWorld)
        // so a child meets the loaded branch; Gate B skips that install, and a replay straight away
        // takes the other branch for every item starter and passes whatever the pack contains --
        // which is exactly how the first cut of this test passed with the dialect rewrite switched
        // off. So the first press here is the install, and the replay waits until it has landed.
        java.util.Set<String> packs = new java.util.LinkedHashSet<>();
        for (WishTemplates.Known k : starters) {
            live.byId(k.id(), k.phrase()).ifPresent(m -> m.plan().commands().forEach(c -> {
                java.util.regex.Matcher dp = java.util.regex.Pattern
                        .compile("^wishdatapack\\s+(\\S+)").matcher(c.trim());
                if (dp.find()) {
                    packs.add(dp.group(1));
                }
            }));
        }
        Vec3 home = Vec3.atBottomCenterOf(spot(helper, 0));
        child.snapTo(home.x, home.y, home.z);
        // ⚠ ALWAYS REINSTALL, never "install if missing": Gate B REUSES its world, so a pack a
        // previous run put in it is already loaded -- in whatever dialect THAT run wrote -- and a
        // presence check would replay it without this build's install path ever running. Measured:
        // the red arm (dialect rewrite off) passed exactly that way. installAndReload is the path
        // `wishdatapack` itself takes, and it writes DatapackStore.installBytes.
        for (String id : packs) {
            try {
                com.moddingfromamod.datapack.DatapackReloader.installAndReload(server, id,
                        com.moddingfromamod.datapack.DatapackStore.zipFor(id));
            } catch (java.io.IOException e) {
                helper.fail(Component.literal("could not install starter datapack '" + id + "': " + e));
                return;
            }
        }

        // ── 2. WAIT FOR THEM (the reload is asynchronous; ticks are not seconds here) ──────────
        java.util.function.Supplier<List<String>> notLoaded = () -> (
                com.moddingfromamod.datapack.DatapackReloader.reloadInProgress()
                        || com.moddingfromamod.datapack.DatapackReloader.reloadDeferred())
                ? List.copyOf(packs) : packs.stream()
                .filter(id -> server.getFunctions().get(
                        net.minecraft.resources.Identifier.fromNamespaceAndPath(id, "reset"))
                        .isEmpty())
                .toList();
        long[] waited = {0};
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            List<String> missing = notLoaded.get();
            if (missing.isEmpty()) {
                replay(helper, live, starters, child);
            } else if (waited[0] >= LOAD_TICKS) {
                helper.fail(Component.literal("starter datapack(s) never finished loading on this build: " + missing
                        + " — a child pressing them is told \"do that again\" forever"));
            } else {
                waited[0] += 10;
                helper.runAfterDelay(10, () -> poll[0].run());   // a FRESH Runnable: SourceRuleSweepTest
            }
        };
        helper.runAfterDelay(10, () -> poll[0].run());
    }

    // ── 3. EVERY STARTER, FROM ITS OWN WORDS, EVERY COMMAND ────────────────────────────────────
    private static void replay(GameTestHelper helper, WishTemplates live,
                               List<WishTemplates.Known> starters, ServerPlayer child) {
        var server = helper.getLevel().getServer();
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < starters.size(); i++) {
            WishTemplates.Known starter = starters.get(i);
            int site = i + 1;
            helper.runAfterDelay(1L + (long) i * SPACING, () -> {
                BlockPos at = spot(helper, site);
                Vec3 pos = Vec3.atBottomCenterOf(at);
                child.snapTo(pos.x, pos.y, pos.z);
                Optional<WishTemplates.Confident> matched = live.matchConfident(starter.phrase());
                if (matched.isEmpty() || !matched.get().templateId().equals(starter.id())) {
                    failures.add(starter.id() + ": its own words \"" + starter.phrase()
                            + "\" were served by " + matched.map(WishTemplates.Confident::templateId)
                            .orElse("nothing"));
                    return;
                }
                for (String cmd : matched.get().plan().commands()) {
                    WishExecutor.Result r = WishExecutor.runOne(server, helper.getLevel(), pos,
                            child, cmd);
                    if (!r.success()) {
                        failures.add(starter.id() + ": `" + cmd + "` failed -> " + r.joined());
                    } else if (!r.ignored().isEmpty()) {
                        failures.add(starter.id() + ": `" + cmd + "` half-worked, Minecraft ignored "
                                + r.ignored());
                    } else if (r.joined().contains("Getting '")) {
                        failures.add(starter.id() + ": `" + cmd + "` only loaded its pack — the "
                                + "child would be told it worked and get nothing");
                    }
                }
            });
        }
        helper.runAfterDelay(1L + (long) starters.size() * SPACING + 5, () -> {
            // The event starters leave a crater and a flock of chickens until undo, and this
            // world is reused: give them back so the next run does not start on last run's crater.
            for (com.moddingfromamod.event.StagedEvent e : com.moddingfromamod.event.EventDirector.all(server)) {
                com.moddingfromamod.event.EventDirector.undo(server, e.id());
            }
            if (failures.isEmpty()) {
                helper.succeed();
            } else {
                helper.fail(Component.literal(failures.size() + " starter command(s) do not work on this build: "
                        + String.join(" | ", failures)));
            }
        });
    }
}
