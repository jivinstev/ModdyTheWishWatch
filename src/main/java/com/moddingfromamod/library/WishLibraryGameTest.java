package com.moddingfromamod.library;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchIcons;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.wish.WishCatalog;
import com.moddingfromamod.wish.WishPresets;
import com.moddingfromamod.wish.WishService;
import com.moddingfromamod.wish.WishSource;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for the curated wish library</b>.
 *
 * <p>{@code WishLibraryTest} owns the rules — precedence, icons, snapshot immutability — without a
 * game. This file owns the thing a unit test cannot see: that the library is <b>wired</b>. A
 * template file appearing on disk becomes a button and a served wish; a creature directory becomes
 * something {@code /wishmob spawn} can spawn in a world that has never seen it; the reload happens
 * over {@code /moddyop library reload} rather than by calling a method the operator has no access
 * to; and the poll notices a change on its own.
 *
 * <p><b>Its own batch, and every test puts the library back.</b> The library directory is a
 * process-wide system property and the composed template set is a process-wide singleton, so a
 * sibling test reading either would be measuring this one's fixture. Batches are strictly
 * sequential (CLAUDE.md), which makes a named batch the cheapest real isolation there is.
 *
 * <p><b>No seconds are asserted.</b> A GameTest server ticks as fast as it can, so the poll's
 * interval is set to ~1 s through the shipped property and the assertion is a {@code succeedWhen} —
 * the arithmetic of "thirty seconds" belongs to {@code WishLibraryTest}, which can sleep.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishLibraryGameTest {

    static final String DIR_PROP = ModdingFromAModMod.MODID + ".libraryDir";
    static final String POLL_PROP = ModdingFromAModMod.MODID + ".libraryPollMs";

    /** A fixture library under the run directory, with a templates file if one is given. */
    static Path fixture(String name, String templatesJson) throws Exception {
        Path dir = Path.of("gametest-library-" + name + "-" + System.nanoTime()).toAbsolutePath();
        Files.createDirectories(dir);
        if (templatesJson != null) {
            Files.writeString(dir.resolve("templates.json"), templatesJson, StandardCharsets.UTF_8);
        }
        return dir;
    }

    /** Point the mod at a library, exactly as the launcher's {@code -D} does, and reload. */
    static void useLibrary(MinecraftServer server, Path dir) {
        System.setProperty(DIR_PROP, dir.toString());
        LibraryService.reloadNow(server);
    }

    static void restore(MinecraftServer server) {
        System.clearProperty(DIR_PROP);
        System.clearProperty(POLL_PROP);
        LibraryService.reloadNow(server);
    }

    static void rcon(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput().withPermission(4),
                command);
    }

    // ── (a) a templates file becomes a button and a served wish ─────────────────────────────

    // aCuratedTemplateBecomesAButtonAndServesATypedWish is in WishLibraryOpsGameTest: it drives /moddyop, the private operator root.


    // ──: one pool of canned wishes, paged on the SAME chest ─────────────────────────────

    /** This child's newest wish in the book, or null. */
    static com.moddingfromamod.wish.Wish newestOf(MinecraftServer server,
                                                          ServerPlayer child) {
        return com.moddingfromamod.wish.WishBook.get(server).recent(40).stream()
                .filter(w -> child.getUUID().equals(w.playerId()))
                .reduce((a, b) -> b).orElse(null);
    }

    /**
     * Forty-five fixture presets — five whole lines, more than a page's four — so the panel needs
     * a second page even for a child with no history. Test-only: none of them ships.
     */
    static String pagingFixtureTemplates() {
        StringBuilder sb = new StringBuilder("{\"templates\":[");
        String sep = "";
        for (int i = 0; i < 45; i++) {
            String id = "gtpage_" + i;
            sb.append(sep).append("{\"id\":\"").append(id)
                    .append("\",\"label\":\"Page wish ").append(i)
                    .append("\",\"all\":[\"gtpageword").append(i)
                    .append("\"],\"commands\":[\"give @p minecraft:cake 1\"],")
                    .append("\"say\":\"Page cake!\"}");
            sep = ",";
        }
        return sb.append("]}").toString();
    }

    /**
     * <b>A preset reached through "More wishes ▶" is pressed like any preset</b>.
     *
     * <p>Two claims, both through the REAL click path. (1) Paging re-lays THE SAME MENU — same
     * container, new items — so a stock client, which knows nothing but a vanilla 9×6 chest, sees
     * the page turn; a design that opened a second chest would flicker, lose the cursor and change
     * the container id under it. (2) A cell on a later page is a preset like any other: its press
     * records {@code WishSource.PRESET} and its own recipe id, so the design he pressed is served
     * rather than re-derived from its label.
     */
    @GameTest(template = "empty_test", batch = "wishlibrary", timeoutTicks = 6000)
    public static void aPresetOnALaterPageIsReachedByPagingAndPressesAsAPreset(
            GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer p = null;
        try {
            useLibrary(server, fixture("paging", pagingFixtureTemplates()));
            com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
            ServerPlayer child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
            p = child;
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(child, () -> {
                helper.assertTrue(com.moddingfromamod.wish.WatchMenu.open(child),
                        Component.literal("the panel refused to open"));
                var menu = (com.moddingfromamod.wish.WatchMenu) child.containerMenu;
                helper.assertTrue(menu.layout().pageCount() > 1,
                        Component.literal("45 fixture presets fit on one page — the fixture no longer pages"));
                helper.assertTrue(menu.layout().at(
                                com.moddingfromamod.marshal.WatchPanel.PREV_PAGE_SLOT) == null,
                        Component.literal("page one offers '◀ Back'"));
                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                menu.layout().cells().forEach(c -> seen.add(c.recipeId()));
                com.moddingfromamod.marshal.WatchPanel.Cell pick = null;
                int pickPage = -1;
                for (int guard = 0; guard < 20; guard++) {
                    var next = menu.layout().at(
                            com.moddingfromamod.marshal.WatchPanel.NEXT_PAGE_SLOT);
                    if (next == null) {
                        break;
                    }
                    int before = menu.layout().page();
                    child.containerMenu.clicked(next.slot(), 0,
                            net.minecraft.world.inventory.ClickType.PICKUP, child);
                    helper.assertTrue(child.containerMenu == menu,
                            Component.literal("'More wishes ▶' opened a NEW menu instead of re-laying this one"));
                    helper.assertTrue(menu.layout().page() == before + 1,
                            Component.literal("'More wishes ▶' did not turn the page"));
                    menu.layout().cells().forEach(c -> seen.add(c.recipeId()));
                    // The re-lay reached the CONTAINER, not only the layout object.
                    var first = menu.layout().at(0);
                    if (first != null) {
                        String shown = menu.getContainer().getItem(0).getHoverName().getString();
                        helper.assertTrue(shown.equals(first.label()),
                                Component.literal("slot 0 shows '" + shown + "', not the page's '"
                                        + first.label() + "'"));
                    }
                    if (pick == null) {
                        pick = menu.layout().cells().stream()
                                .filter(c -> c.recipeId().startsWith("gtpage_")).findFirst()
                                .orElse(null);
                        pickPage = menu.layout().page();
                    }
                }
                for (int i = 0; i < 45; i++) {
                    helper.assertTrue(seen.contains("gtpage_" + i),
                            Component.literal("gtpage_" + i + " is on no page — the panel dropped a preset"));
                }
                helper.assertTrue(pick != null && pickPage >= 1,
                        Component.literal("no fixture preset on a later page to press"));
                // Walk BACK (the real ◀ cell) to the page the wish is on, so the press lands on
                // the cell it names.
                for (int guard = 0; guard < 20 && menu.layout().page() > pickPage; guard++) {
                    var back = menu.layout().at(
                            com.moddingfromamod.marshal.WatchPanel.PREV_PAGE_SLOT);
                    helper.assertTrue(back != null, Component.literal("a later page has no '◀ Back'"));
                    int before = menu.layout().page();
                    child.containerMenu.clicked(back.slot(), 0,
                            net.minecraft.world.inventory.ClickType.PICKUP, child);
                    helper.assertTrue(child.containerMenu == menu,
                            Component.literal("'◀ Back' opened a NEW menu instead of re-laying this one"));
                    helper.assertTrue(menu.layout().page() == before - 1,
                            Component.literal("'◀ Back' did not turn the page back"));
                }
                var here = menu.layout().at(pick.slot());
                helper.assertTrue(here != null && pick.recipeId().equals(here.recipeId()),
                        Component.literal("walking back did not return to the page " + pick.recipeId() + " is on"));
                // NOT a size comparison: the book keeps at most 500 in memory, so late in a full
                // Gate B run an add evicts the oldest and the size does not move. Ask for HIS
                // newest wish instead, before and after.
                var before = newestOf(server, child);
                child.containerMenu.clicked(pick.slot(), 0,
                        net.minecraft.world.inventory.ClickType.PICKUP, child);
                var made = newestOf(server, child);
                helper.assertTrue(made != null && (before == null
                                || !made.id().equals(before.id())),
                        Component.literal("pressing a page-" + (pickPage + 1) + " wish ('" + pick.arg()
                        + "') recorded no wish; Moddy said: "
                        + com.moddingfromamod.testing.MockPlayerNetwork.chat(child)));
                helper.assertTrue(made.source() == WishSource.PRESET,
                        Component.literal("a later-page press was submitted as " + made.source()));
                helper.assertTrue(pick.recipeId().equals(made.templateId()),
                        Component.literal("a later-page press lost its recipe id: " + made.templateId()));
                com.moddingfromamod.wish.WishCancel.stop(child, "",
                        com.moddingfromamod.marshal.CancelMode.STOP, "");
            });
            helper.succeed();
        } catch (Exception e) {
            helper.fail(Component.literal("paging fixture failed: " + e));
        } finally {
            if (p != null) {
                com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
            }
            restore(server);
        }
    }

    // ── (b) a creature in the library spawns in a world that never made it ───────────────────

    /**
     * <b>A creature directory in the library, and {@code /wishmob spawn} works in this world.</b>
     *
     * <p>The second exit criterion, and the reason {@code ModelRepository.readable} exists. The
     * models directory is per-WORLD, so before this the curated creature was a folder full of
     * correct files that nothing could reach; the published Mod-Repo fallback already did it for
     * the SPEC and could not do it for the model.
     *
     * <p>🔴 <b>Verified to fail</b> by putting {@code has()} back to
     * {@code Files.isRegularFile(geometryFile(key))}: the spawn then reports "No model named …".
     */
    @GameTest(template = "empty_test", batch = "wishlibrary", timeoutTicks = 6000)
    public static void aCuratedCreatureSpawnsInAWorldThatHasNeverSeenIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String key = "gatecritter";
        try {
            Path dir = fixture("species", null);
            Path creature = dir.resolve("species").resolve(key);
            Files.createDirectories(creature);
            // A real (tiny) Bedrock geometry, so nothing downstream is being handed a stub.
            Files.writeString(creature.resolve("geometry.json"), """
                    {"format_version":"1.12.0","minecraft:geometry":[{
                      "description":{"identifier":"geometry.gatecritter",
                        "texture_width":16,"texture_height":16},
                      "bones":[{"name":"body","pivot":[0,0,0],
                        "cubes":[{"origin":[-4,0,-4],"size":[8,8,8],"uv":[0,0]}]}]}]}
                    """, StandardCharsets.UTF_8);
            Files.writeString(creature.resolve("species.json"),
                    "{\"name\":\"Gate Critter\",\"scale\":1.5,\"health\":20}", StandardCharsets.UTF_8);

            helper.assertTrue(!ModelRepository.has(key),
                    Component.literal("this world already has a creature called " + key + " — the test proves nothing"));

            useLibrary(server, dir);

            helper.assertTrue(ModelRepository.has(key),
                    Component.literal("the library's creature is not reachable, so /wishmob spawn cannot draw it"));
            helper.assertTrue(com.moddingfromamod.runtime.WishMobCommands.repositoryKeys()
                            .contains(key),
                    Component.literal("/wishmob list (and tab-complete) does not offer the library's creature, so a "
                            + "working command looks like a typo"));

            ServerPlayer child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
            server.getCommands().performPrefixedCommand(
                    child.createCommandSourceStack().withSuppressedOutput().withPermission(2),
                    "wishmob spawn " + key);
            // ⚠ COUNT WHAT A CHILD CAN SEE, NOT WHAT WE REGISTERED.
            //
            // This used to count `wish_mob` entities before and after, and that is the one thing a
            // stock client cannot draw. A library creature ships GEOMETRY and no sculpt spec, so
            // it was exactly an earlier issue's second fallback: the bridge declined to rig it and left it on
            // the client-side legacy renderer, which means a library creature was INVISIBLE to
            // every stock child while this test reported the spawn working. `StandInSculpt` closed
            // that, and this is the assertion that says so.
            //
            // The rig is spawned on the server's own queue at the END of the tick the command ran
            // in, so this waits rather than asking straight away.
            com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
                helper.assertTrue(com.moddingfromamod.testing.RigProbe.carrierFor(
                                helper.getLevel(), key) != null,
                        Component.literal("/wishmob spawn " + key + " put nothing in the world that a stock client "
                                + "could see"));
                helper.assertTrue(com.moddingfromamod.species.SpeciesRegistry.has(key),
                        Component.literal("the library creature spawned but was never registered as a species, so no "
                                + "client would be told how to draw it"));
            });
        } catch (Exception e) {
            helper.fail(Component.literal("library creature fixture failed: " + e));
        } finally {
            restore(server);
        }
    }

    // ── (c) a reload does not change a replay already under way ─────────────────────────────

    // aReloadDoesNotChangeAReplayAlreadyUnderWay is in WishLibraryOpsGameTest: it drives /moddyop, the private operator root.


    // ── (d) the poll notices a change with nobody asking ────────────────────────────────────

    /**
     * <b>The 30-second poll reloads on its own.</b>
     *
     * <p>{@code library update} is a pull plus an RCON reload, so the poll is the belt to that
     * braces — a library edited by hand, or pulled by something other than the launcher, still
     * reaches the children. The interval is set through the shipped property rather than a field a
     * test reached into, so what runs here is the shipped loop.
     *
     * <p>🔴 <b>Verified to fail</b> by returning a constant from
     * {@code LibrarySnapshot.fingerprintOf}: the poll then never notices and the test times out.
     *
     * <p>⚠ <b>Its OWN batch, because the library directory is a global and the other three reset
     * it.</b> Each sibling points the library at its fixture and {@code restore()}s it in a
     * {@code finally}, inside its own body. This test's wait happens AFTER its body, on a real
     * 250 ms clock — so in a shared batch it passed only when its body happened to run LAST, and
     * any sibling running after it swapped the library back to the default before the poll woke,
     * which then "noticed a change" and reloaded the wrong directory for ever. Measured
     * 2026-09-23: green for 12 recorded runs, then red twice when an unrelated GameTest class was
     * added and the scan order put this body second — {@code inflight, poll, species,
     * template} against {@code main}'s {@code inflight, template, species, poll}. Batches are
     * strictly sequential, so a batch of one is isolation whatever the order.
     */
    @GameTest(template = "empty_test", batch = "wishlibrarypoll", timeoutTicks = 6000)
    public static void thePollNoticesAChangeWithNobodyAskingIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            Path dir = fixture("poll", """
                    {"templates":[
                      {"id":"gatepoll_one","all":["gatepollone"],"commands":["say one"]}
                    ]}
                    """);
            System.setProperty(POLL_PROP, "250");
            useLibrary(server, dir);
            WishLibrary.stopPolling();
            WishLibrary.startPolling();       // …now at the test's interval
            helper.assertTrue(WishLibrary.isPolling(), Component.literal("the poll thread is not running"));

            Files.writeString(dir.resolve("templates.json"), """
                    {"templates":[
                      {"id":"gatepoll_one","all":["gatepollone"],"commands":["say one"]},
                      {"id":"gatepoll_two","all":["gatepolltwo"],"commands":["say two"]}
                    ]}
                    """, StandardCharsets.UTF_8);
            // A tick-free clock: touch the directory too, because a fingerprint is mtimes and a
            // filesystem with one-second granularity can otherwise write the same value twice.
            Files.setLastModifiedTime(dir.resolve("templates.json"),
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));

            // succeedWhen, never a tick count: the poll is timed in real seconds and the server is
            // not. This is the assertion that hangs if the poll is dead, which is what we want.
            com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
                helper.assertTrue(WishLibrary.snapshot().hasTemplate("gatepoll_two"),
                        Component.literal("the poll has not picked up the change yet"));
                // Put the shipped library back HERE rather than in a finally: succeedWhen returns
                // immediately and the condition is what runs later, so a finally would undo the
                // fixture before the poll had a chance to see it.
                restore(server);
                WishLibrary.stopPolling();
                WishLibrary.startPolling();
            });
        } catch (Exception e) {
            helper.fail(Component.literal("library poll fixture failed: " + e));
        }
    }

}
