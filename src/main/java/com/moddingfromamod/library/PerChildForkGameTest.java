package com.moddingfromamod.library;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.marshal.ArtifactOwner;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.species.CreatureFork;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.wish.WishPresets;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for per-child forks, per-child panels, and promotion</b>.
 *
 * <h2>The methodology is {@code TwoChildrenGameTest}'s, and it is not decoration</h2>
 *
 * <p><b>Both children are mid-wish at once</b>, with a marshal run each and a worker id each, and
 * every agent-side call is made inside {@code AgentContext.bind(...)}. That is the whole fixture:
 * The rules read {@code AgentContext.currentPlayerId()}, which is {@code ""} for an unbound
 * caller and makes every one of them a no-op. A fixture that forgot the binding would pass on the
 * pre-HD-7 code and on a completely dead implementation alike.
 *
 * <p><b>Every assertion is an identity, never an absence.</b> "B does not see A's fork" passes when
 * the panel is empty for both of them; the claim made here is "B sees the LIBRARY's Spikor and A
 * sees HIS", which goes red when the addressing breaks and red again when the feature stops working
 * at all.
 *
 * <p><b>Its own batch, and every test puts the world back.</b> The library directory is a
 * process-wide system property, {@code SpeciesRegistry} is a process-wide map and the composed
 * template set is a singleton — batches are strictly sequential (CLAUDE.md), which makes a
 * named batch the cheapest real isolation there is. No {@code clearStaleMocks}: that sweep matches
 * on the mock NAME and would delete a concurrent sibling's players, so each test removes only the
 * two children it made, in a finally.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PerChildForkGameTest {

    static final String BATCH = "perchildfork";
    static final String DIR_PROP = ModdingFromAModMod.MODID + ".libraryDir";

    /**
     * A creature with THREE real boxes in it, not an empty geometry object.
     *
     * <p>The count is the point. {@code REFINEMENT_LADDERS.md}'s headline number is <b>part
     * survival</b> — 0.40 against 1.00 on the run where the edit path was broken — and the one way
     * a fork could turn an edit into a rebuild is by failing to carry the parts across.
     * {@code CreatureFork.copyArt} is best-effort per file and logs a warning, so a fork whose
     * geometry never copied is a creature that renders as nothing, which a child reports as "it
     * disappeared" and which no assertion about the RECORD would notice.
     */
    static final String GEOMETRY = """
            {"format_version":"1.12.0","minecraft:geometry":[{
              "description":{"identifier":"geometry.gt","texture_width":64,"texture_height":64},
              "bones":[
                {"name":"body","pivot":[0,0,0],"cubes":[
                  {"origin":[-4,0,-2],"size":[8,12,4],"uv":[0,0]},
                  {"origin":[-4,12,-2],"size":[8,8,8],"uv":[0,16]}]},
                {"name":"tail","pivot":[0,4,2],"cubes":[
                  {"origin":[-1,2,2],"size":[2,2,6],"uv":[0,32]}]}
              ]}]}
            """;

    /**
     * A curated creature, with the geometry that makes the library able to SEE it.
     *
     * <p>⚠ The key is UNIQUE PER TEST and the registry is never cleared. {@code SpeciesRegistry}
     * is a process-wide map that fourteen other GameTest classes read, and a {@code clear()} here
     * would delete what a concurrently-running neighbour had just put there — invisibly, because
     * the neighbour does not go red, it goes green having asserted nothing (LEARNINGS.md;
     * {@code GateShardsTest} fails the build over it). Unique ids make the cleanup unnecessary
     * rather than careful.
     */
    static Path libraryWith(String key) throws Exception {
        Path dir = Path.of("gametest-hd7-" + key + "-" + System.nanoTime()).toAbsolutePath();
        Path species = dir.resolve("species").resolve(key);
        Files.createDirectories(species);
        // The marker LibrarySnapshot proves a species directory by. Minimal but real: a fork copies
        // whatever is here, and a fixture with no geometry would exercise the "nothing to copy" arm.
        Files.writeString(species.resolve("geometry.json"), GEOMETRY, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("templates.json"),
                "{\"templates\":[{\"id\":\"" + key + "\",\"all\":[\"" + key + "\"],"
                        + "\"commands\":[\"wishmob spawn " + key + "\"],"
                        + "\"say\":\"Here!\",\"icon\":\"minecraft:fire_charge\"}]}",
                StandardCharsets.UTF_8);
        return dir;
    }

    static Species creature(String id) {
        return Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"name\":\"Spikor\",\"body\":\"humanoid\","
                        + "\"texture\":\"zombie\",\"scale\":2.0,\"health\":60,\"speed\":0.25,"
                        + "\"damage\":6,\"modelKey\":\"" + id + "\"}").getAsJsonObject());
    }

    /** Put a child mid-wish and hand back the worker id to bind when speaking as his agent. */
    static String startWishing(ServerPlayer child, UUID wishId, String text) {
        Marshal m = Marshal.global();
        m.submit(wishId, child.getUUID(), text);
        String worker = "gt-hd7-" + wishId;
        m.assign(worker, wishId);
        return worker;
    }

    static void asAgentFor(String workerId, Runnable body) {
        AgentContext.bind(workerId);
        try {
            body.run();
        } finally {
            AgentContext.unbind();
        }
    }

    static void cleanUp(MinecraftServer server, UUID... wishIds) {
        for (UUID id : wishIds) {
            try {
                Marshal.global().finish(id);
            } catch (RuntimeException ignored) {
                // a failed test must not poison the next one
            }
        }
        System.clearProperty(DIR_PROP);
        LibraryService.reloadNow(server);
    }

    /** Drive a real operator command. Returns its result, so a command that never PARSED is
     * distinguishable from one that ran and did nothing — the two look identical from the world,
     * and telling them apart is what found the `~`-in-a-Brigadier-word bug. */
    static int rcon(MinecraftServer server, String command) {
        try {
            // THE DISPATCHER, not performPrefixedCommand, because that one returns void and
            // swallows a syntax error into a chat message nobody reads in a headless run.
            return server.getCommands().getDispatcher().execute(command,
                    server.createCommandSourceStack().withSuppressedOutput().withPermission(4));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return -1;
        }
    }

    static long hashOf(Path file) throws Exception {
        return java.util.Arrays.hashCode(Files.readAllBytes(file));
    }

    // ── (a) A refines the library Spikor ─────────────────────────────────────────────────────

    // refiningTheLibrarySpikorForksItAndLeavesTheLibraryAlone is in PerChildForkOpsGameTest: it drives /moddyop, the private operator root.


    // ── (b) A's add_template is on A's panel and not on B's ─────────────────────────────────

    /**
     * <b>🔴 A teaches the watch a wish; it is a button for A and not for B.</b>
     *
     * <p>Plan §9 item 2, driven through the real {@code LearnedTemplates.addAndSave} rather than
     * through {@code WishPresets} — the RULE already has a unit test, and what this proves is that
     * the stamp is actually written on the path a wish takes. <i>A rule tested only where it is
     * DEFINED is not tested where it RUNS</i> (CLAUDE.md), and the enabling condition here is a
     * bound {@code AgentContext}, which nothing but a real call site supplies.
     *
     * <p>Both identities are asserted: A sees HERS and B sees the built-ins. An "B does not see A's"
     * test alone passes with the learned section dead.
     */
    // hisAccountPageDeletesOnlyHisOwnButton is in PerChildForkOpsGameTest: it drives /moddyop, the private operator root.


    // hisAccountPageIsHisOwnWatch is in PerChildForkOpsGameTest: it drives /moddyop, the private operator root.


    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void whatOneChildTeachesIsOnHisOwnPanelOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);
        UUID aliceWish = UUID.nameUUIDFromBytes(("hd7-tpl-a-" + server.getTickCount()).getBytes());
        UUID bobWish = UUID.nameUUIDFromBytes(("hd7-tpl-b-" + server.getTickCount()).getBytes());
        List<String> saved = LearnedTemplates.savedJsonForTest();
        try {
            String aliceWorker = startWishing(alice, aliceWish, "a floating cheese island");
            startWishing(bob, bobWish, "a rocket ship");
            String id = "hd7_cheese_" + server.getTickCount();

            asAgentFor(aliceWorker, () -> LearnedTemplates.addAndSave(server,
                    JsonParser.parseString("{\"id\":\"" + id + "\",\"all\":[\"" + id + "\"],"
                            + "\"commands\":[\"give @p minecraft:cake 1\"],"
                            + "\"say\":\"Cheese!\"}").getAsJsonObject()));

            helper.assertTrue(alice.getUUID().toString()
                            .equalsIgnoreCase(LearnedTemplates.ownerOf(id)),
                    Component.literal("the learned recipe must be stamped with the child whose wish taught it; "
                            + "it says '" + LearnedTemplates.ownerOf(id) + "'"));

            var hers = WishPresets.forScreen(alice.getUUID().toString(),
                    WishLibrary.asPresets(), LearnedTemplates.asPresets());
            var his = WishPresets.forScreen(bob.getUUID().toString(),
                    WishLibrary.asPresets(), LearnedTemplates.asPresets());

            helper.assertTrue(hers.stream().anyMatch(p -> id.equals(p.templateId())),
                    Component.literal("A must see the wish A taught"));
            helper.assertTrue(!WishLibrary.asPresets().isEmpty() && his.stream().anyMatch(
                            p -> WishLibrary.asPresets().get(0).wishText().equals(p.wishText())),
                    Component.literal("B must still have a panel at all — the library's buttons are everybody's"));
            helper.assertTrue(his.stream().noneMatch(p -> id.equals(p.templateId())),
                    Component.literal("…and A's teaching must not be crowding B's row"));
            helper.succeed();
        } finally {
            LearnedTemplates.restoreForTest(saved, server);
            cleanUp(server, aliceWish, bobWish);
            MockPlayerNetwork.remove(alice);
            MockPlayerNetwork.remove(bob);
        }
    }

    // ── (c) promotion puts A's fork in front of a third child ───────────────────────────────

    // promotingAForkPutsItOnEveryChildsPanel is in PerChildForkOpsGameTest: it drives /moddyop, the private operator root.


    /** A creature directory the library will accept once it is promoted. */
    static void writeGeometry(String key) {
        try {
            Path dir = com.moddingfromamod.runtime.ModelRepository.dir(key);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("geometry.json"),
                    "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[]}",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not write the fixture creature's art: " + e, e);
        }
    }

    // ── (d) the wait is visible ─────────────────────────────────────────────────────────────

    /**
     * <b>🔴 B's wish, queued behind two of A's, says "2 wishes ahead of yours".</b>
     *
     * <p>Plan §9 item 6. Driven through the real {@link Marshal}, because the count is a fact about
     * the real FIFO and a fixture that handed {@code QueueNotice} a number would be testing the
     * wording twice and the wiring not at all.
     *
     * <p>Note what is NOT asserted: a message reaching B's screen. A mock player's connection
     * swallows the packet, so an assertion about delivery would be an assertion about the shim —
     * {@code TwoChildrenGameTest} made the same call about the status line and says so.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 6000)
    public static void aWishQueuedBehindTwoOthersSaysHowManyAreAhead(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);
        UUID a1 = UUID.nameUUIDFromBytes(("hd7-q1-" + server.getTickCount()).getBytes());
        UUID a2 = UUID.nameUUIDFromBytes(("hd7-q2-" + server.getTickCount()).getBytes());
        UUID mine = UUID.nameUUIDFromBytes(("hd7-q3-" + server.getTickCount()).getBytes());
        try {
            Marshal m = Marshal.global();
            m.submit(a1, alice.getUUID(), "a castle");
            m.submit(a2, alice.getUUID(), "a dragon");
            m.submit(mine, bob.getUUID(), "a rocket");

            // ⚠ THE DIFFERENCE, NOT THE ABSOLUTE COUNT. `Marshal.global()` is process-wide and
            // tests in a batch run interleaved, so a sibling's queued wish sits in front of these
            // three — measured, this read 5 rather than 2 on its first run, which is the marshal
            // being RIGHT (every waiting wish is genuinely ahead) and the fixture being wrong.
            // Asking for the gap between two of our own wishes is the same claim about the FIFO
            // and is immune to what else is queued.
            int ahead = m.aheadOf(mine);
            int head = m.aheadOf(a1);
            helper.assertTrue(head >= 0 && ahead - head == 2,
                    Component.literal("B's wish sits two places behind A's first, so two wishes of A's are ahead of "
                            + "it; the marshal says " + ahead + " ahead of B and " + head
                            + " ahead of A's first"));
            helper.assertTrue("2 wishes ahead of yours"
                            .equals(com.moddingfromamod.marshal.QueueNotice.aheadLine(2)),
                    Component.literal("the line a child reads must name the count"));
            helper.assertTrue(com.moddingfromamod.marshal.QueueNotice.aheadLine(0).isEmpty(),
                    Component.literal("the child at the head of the queue is told nothing — there is no queue to "
                            + "make him think about"));
            helper.succeed();
        } finally {
            cleanUp(server, a1, a2, mine);
            MockPlayerNetwork.remove(alice);
            MockPlayerNetwork.remove(bob);
        }
    }

    // ──: reading a library creature makes nothing HIS; changing it forks ───────────────────

    // spawningALibraryCreatureCopiesNothingAndChangingItForks is in PerChildForkOpsGameTest: it drives /moddyop, the private operator root.

}
