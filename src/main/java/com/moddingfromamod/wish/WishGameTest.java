package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * Gate B for the wish pipeline: the parts that only exist once a real server is running — command
 * execution, the journal on the world folder, and the throttle behaving under real calls.
 *
 * <p>Gate A already proves the decisions (what a wish resolves to). These prove the <em>effects</em>.
 */
@GameTestHolder(GateShards.CI)
public class WishGameTest {

    /** The /wish command is registered, so the keyboard path into the pipeline exists. */
    @GameTest(template = "empty_test")
    public static void wishCommandIsRegistered(GameTestHelper helper) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        helper.assertTrue(dispatcher.getRoot().getChild("wish") != null, Component.literal("/wish should be registered"));
        helper.assertTrue(dispatcher.getRoot().getChild("moddy").getChild("status") != null,
                Component.literal("/moddy status should exist"));
        helper.succeed();
    }

    /** A template's commands really do change the world. */
    @GameTest(template = "empty_test")
    public static void wishCommandsActuallyRunAndAffectTheWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));

        WishExecutor.Result result =
                WishExecutor.runOne(server, level, pos, null, "summon minecraft:chicken ~ ~ ~");
        helper.assertTrue(result.success(), Component.literal("summon should have succeeded: " + result.joined()));
        helper.assertEntityPresent(EntityTypes.CHICKEN);
        helper.succeed();
    }

    /**
     * <b>The most important assertion in this class.</b> A failing command must report its failure
     * back to the caller. With {@code withSuppressedOutput()} this would silently look like success —
     * and once an AI agent is driving, it would confidently tell the kid a broken wish worked.
     */
    @GameTest(template = "empty_test")
    public static void failedCommandsReportTheirError(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));

        WishExecutor.Result result =
                WishExecutor.runOne(server, level, pos, null, "summon minecraft:zombee ~ ~ ~");
        helper.assertFalse(result.success(), Component.literal("a bogus entity id must not report success"));
        helper.assertFalse(result.output().isEmpty(),
                Component.literal("the failure text must reach the caller, not be swallowed"));
        helper.succeed();
    }

    /**
     * <b>A 1.21.2-spelled attribute id inside an item COMPONENT is corrected on the real path.</b>
     *
     * <p>{@link AttributeIds} is pure and exhaustively unit-tested, which is exactly the arrangement
     * this repo keeps getting burned by — a rule tested only where it is DEFINED is not tested where
     * it RUNS. It was wired to this choke point for the {@code attribute <target> <id>} command and
     * had been green for months while the OTHER syntax that names an attribute, the
     * {@code minecraft:attribute_modifiers} item component, went straight through it.
     *
     * <p>So this drives the real {@link WishExecutor#runOne} with a command copied out of
     * {@code corpus/real-tool-calls.jsonl}, and asserts the item was actually equipped rather than
     * that a string was rewritten. Pre-fix it fails with <i>"Malformed 'minecraft:attribute_modifiers'
     * component: 'Unknown registry key … minecraft:armor'"</i>.
     *
     * <p><b>It uses {@code item replace entity} on an armour stand rather than {@code give}</b>,
     * which is not a detail: a GameTest server has no player, so every {@code give} form the agent
     * really wrote is unreachable here (docs/TESTING.md → the Gate C½ blind spot). The component is
     * parsed identically either way, and that parse is the thing that was failing.
     */
    @GameTest(template = "empty_test")
    public static void aRenamedAttributeInsideAnItemComponentIsCorrectedOnTheRealPath(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos rel = new BlockPos(3, 2, 3);
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(rel));

        var stand = helper.spawn(EntityTypes.ARMOR_STAND, rel);
        stand.addTag("attrfix");

        // Verbatim shape from the real failure, spelled for the OTHER target -- the 1.21.2 name
        // the agent wrote on 1.21.1, and since the 1.21.4 port also the 1.21.1 name on 1.21.4.
        String right = com.moddingfromamod.compat.Attrs.generic("armor");
        String other = right.contains("generic.") ? "minecraft:armor" : "minecraft:generic.armor";
        WishExecutor.Result result = WishExecutor.runOne(server, level, pos, null,
                "item replace entity @e[tag=attrfix,limit=1] armor.head with "
                        + "minecraft:diamond_helmet[minecraft:attribute_modifiers=["
                        + "{type:\"" + other + "\",id:\"wish:jump_armor\",amount:3,"
                        + "operation:\"add_value\",slot:\"head\"}]]");

        helper.assertTrue(result.success(),
                Component.literal("the renamed attribute id was not corrected on the path run_command uses: "
                        + result.joined()));
        helper.assertTrue(stand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                        .is(net.minecraft.world.item.Items.DIAMOND_HELMET),
                Component.literal("the command reported success but the helmet never arrived"));
        // Said out loud, or a correction nobody can see is indistinguishable from the command
        // having been right all along -- and the agent goes on writing the broken spelling.
        helper.assertTrue(result.joined().contains(right),
                Component.literal("the correction was silent: " + result.joined()));
        helper.succeed();
    }

    /**
     * ...and an attribute id that is already right is left alone, with nothing announced.
     *
     * <p>The over-reach half. Without it the test above is satisfied by a rewrite that fires on
     * every command, which would rename ids in worlds where the short spelling is the real one.
     */
    @GameTest(template = "empty_test")
    public static void anAttributeIdThatAlreadyWorksIsNotRewrittenOnTheRealPath(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos rel = new BlockPos(5, 2, 5);
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(rel));

        var stand = helper.spawn(EntityTypes.ARMOR_STAND, rel);
        stand.addTag("attrok");

        WishExecutor.Result result = WishExecutor.runOne(server, level, pos, null,
                "item replace entity @e[tag=attrok,limit=1] armor.head with "
                        + "minecraft:diamond_helmet[minecraft:attribute_modifiers=["
                        + "{type:\"" + com.moddingfromamod.compat.Attrs.generic("armor")
                        + "\",id:\"wish:ok\",amount:3,"
                        + "operation:\"add_value\",slot:\"head\"}]]");

        helper.assertTrue(result.success(), Component.literal(result.joined()));
        helper.assertFalse(result.joined().contains("corrected"),
                Component.literal("announced a correction it did not need to make: " + result.joined()));
        helper.succeed();
    }

    /** The guard is enforced at execution time, not just in unit tests. */
    @GameTest(template = "empty_test")
    public static void deniedCommandsNeverRun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));

        WishExecutor.Result result =
                WishExecutor.runOne(server, level, pos, null, "execute as @a run stop");
        helper.assertFalse(result.success(), Component.literal("a disallowed command must be refused"));
        helper.assertFalse(server.isStopped(), Component.literal("the server must still be running!"));
        helper.succeed();
    }

    /** Wishes are recorded, survive a re-read, and a damaged last line doesn't break the history. */
    @GameTest(template = "empty_test")
    public static void journalRoundTripsAndToleratesATruncatedLine(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("wishes").resolve("gametest-journal.jsonl");
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // fresh start is best-effort
        }

        WishJournal journal = new WishJournal(file);
        Wish a = Wish.create("make it rain chickens", WishSource.PRESET, 1000L,
                UUID.randomUUID(), "Kid", "minecraft:overworld", 1, 2, 3);
        Wish b = Wish.create("build me a tower", WishSource.TYPED, 2000L,
                UUID.randomUUID(), "Kid", "minecraft:overworld", 4, 5, 6);
        journal.append(a);
        journal.append(b);

        List<Wish> read = journal.readAll();
        helper.assertTrue(read.size() == 2, Component.literal("expected 2 wishes, got " + read.size()));
        helper.assertTrue(read.get(0).text().equals("make it rain chickens"),
                Component.literal("text should survive the round trip"));

        // Simulate a crash mid-write: append a half-finished line.
        try {
            Files.writeString(file, "{\"id\":\"not-finish", java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (Exception e) {
            helper.fail(Component.literal("could not simulate a truncated line: " + e));
            return;
        }
        List<Wish> afterDamage = journal.readAll();
        helper.assertTrue(afterDamage.size() == 2,
                Component.literal("a truncated final line must be skipped, not fatal — got " + afterDamage.size()));
        helper.succeed();
    }

    /** The book records wishes and can report the ones still waiting for an agent. */
    @GameTest(template = "empty_test")
    public static void bookTracksPendingWishes(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WishBook book = WishBook.get(server);

        // Scope every assertion to THIS wish's id, not a global pending() count — the book is a shared
        // singleton and other GameTests add/complete wishes concurrently, so a count delta is racy.
        Wish w = book.add(Wish.create("something nobody has a template for", WishSource.TYPED,
                System.currentTimeMillis(), UUID.randomUUID(), "Kid", "minecraft:overworld", 0, 0, 0));
        helper.assertTrue(book.pending().stream().anyMatch(x -> x.id().equals(w.id())),
                Component.literal("the wish should be pending"));

        book.complete(w.id(), WishStatus.DONE, "did it");
        helper.assertTrue(book.pending().stream().noneMatch(x -> x.id().equals(w.id())),
                Component.literal("a completed wish should no longer be pending"));
        helper.assertTrue(book.byId(w.id()).orElseThrow().status() == WishStatus.DONE,
                Component.literal("status should have been updated"));
        helper.succeed();
    }

    /**
     * RUNNING is a real, distinct state (fixes): a wish the agent has actually claimed leaves
     * the PENDING backlog (so it can't be mis-announced as a "queued" wish and isn't subject to the
     * give-up timeout), an OLDER leftover pending wish is not counted as "queued behind" it, and a
     * demote-on-interrupt puts it back to PENDING so it can be resumed.
     */
    @GameTest(template = "empty_test")
    public static void runningWishLeavesBacklogAndOnlyNewerCountsAsQueued(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WishBook book = WishBook.get(server);
        UUID kid = UUID.randomUUID();
        long t0 = System.currentTimeMillis();

        Wish active = book.add(Wish.create("spikor fireballs", WishSource.TYPED, t0, kid, "Kid",
                "minecraft:overworld", 0, 0, 0));
        // The agent claims it — RUNNING.
        book.complete(active.id(), WishStatus.RUNNING, "");
        helper.assertTrue(book.pending().stream().noneMatch(x -> x.id().equals(active.id())),
                Component.literal("a RUNNING wish must not be in the pending backlog"));
        WishStatus st = book.byId(active.id()).orElseThrow().status();
        helper.assertTrue(st == WishStatus.RUNNING && !st.isFinished(),
                Component.literal("RUNNING is not finished, so the progress ticker keeps it active"));

        long activeCreated = book.byId(active.id()).orElseThrow().createdMs();

        // A stale OLDER pending wish is NOT queued behind the active one (the phantom "next wish").
        Wish stale = book.add(Wish.create("old leftover", WishSource.TYPED, t0 - 10_000, kid, "Kid",
                "minecraft:overworld", 0, 0, 0));
        helper.assertTrue(WishProgress.queuedBehind(book.pending(), kid, activeCreated).isEmpty(),
                Component.literal("an older leftover pending wish is not 'queued behind' the active one"));

        // A genuinely newer wish IS queued behind it.
        Wish newer = book.add(Wish.create("also make it rain", WishSource.TYPED, t0 + 10_000, kid,
                "Kid", "minecraft:overworld", 0, 0, 0));
        var behind = WishProgress.queuedBehind(book.pending(), kid, activeCreated);
        helper.assertTrue(behind.size() == 1 && behind.get(0).id().equals(newer.id()),
                Component.literal("only the wish made AFTER the active one is queued behind it"));

        // Interrupt + demote (session end) restores it to PENDING so it can be resumed.
        book.complete(active.id(), WishStatus.PENDING, "__interrupted__");
        helper.assertTrue(book.pending().stream().anyMatch(x -> x.id().equals(active.id())),
                Component.literal("a demoted (interrupted) wish is pending again, ready to resume"));

        // Clean up ONLY the wishes this test created, so the shared book isn't polluted (and no
        // concurrent test's pending wishes are touched).
        book.complete(active.id(), WishStatus.DONE, "");
        book.complete(stale.id(), WishStatus.DONE, "");
        book.complete(newer.id(), WishStatus.DONE, "");
        helper.succeed();
    }

    /**
     * A resumed wish must arrive WARNED, not clean.
     *
     * <p>Quitting mid-wish used to hand the work back looking brand new — the interrupted marker was
     * deliberately wiped so the agent wouldn't read "__interrupted__" as a reason. Given a
     * fresh-looking wish the agent then did the only sensible thing and built it again: on
     * 2026-08-04 that gave a child a second Spikor, and an agent that could no longer tell which one
     * was its own.
     *
     * <p>{@code AwaitWishTool.describe} renders {@code result()} as the ⚠ line the agent reads, so
     * this asserts the resume note actually survives into what a resumed wish carries.
     */
    @GameTest(template = "empty_test")
    public static void aResumedWishTellsTheAgentItAlreadyStartedIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WishBook book = WishBook.get(server);
        UUID kid = UUID.randomUUID();

        Wish w = book.add(Wish.create("summon spikor with fire", WishSource.TYPED,
                System.currentTimeMillis(), kid, "Kid", "minecraft:overworld", 0, 0, 0));
        book.complete(w.id(), WishStatus.RUNNING, "");
        // The world closes mid-wish: onServerStopping demotes it and flags it internally.
        book.complete(w.id(), WishStatus.PENDING, "__interrupted__");
        // The world reopens. Drive the REAL transformation the resume path uses — asserting the
        // text of RESUME_NOTE would prove nothing, because the bug was never a bad note, it was
        // that no note was attached at all.
        Wish resumed = com.moddingfromamod.agent.WishAgent.markResumed(book, w);

        String note = resumed.result();
        helper.assertTrue(note != null && !note.isBlank(),
                Component.literal("a resumed wish must carry a note, or it looks brand new and gets rebuilt"));
        helper.assertTrue(!note.contains("__"),
                Component.literal("the agent-facing note must not be an internal marker: " + note));
        helper.assertTrue(note.contains("ALREADY EXIST"),
                Component.literal("the note must warn that the work may already be built: " + note));
        helper.assertTrue(note.toLowerCase(java.util.Locale.ROOT).contains("do not rebuild"),
                Component.literal("the note must say not to rebuild: " + note));

        book.complete(w.id(), WishStatus.DONE, "");
        helper.succeed();
    }

    /**
     * <b>A command that REFUSES is not a success</b>.
     *
     * <p>Our own commands refuse the Brigadier way the vanilla ones do not: they
     * {@code sendFailure} and return 0 instead of throwing. {@code runOne} used to call anything
     * that did not throw a success, so {@code wishdatapack <id> give} on a pack whose {@code give}
     * failed to load -- "isn't a function in that datapack" -- was a granted wish: the child read
     * "here's your lightning sword!" and got nothing, and the agent's {@code run_command} read "Ran,
     * OK". Found replaying the starter row on 1.21.4 with the dialect rewrite switched off.
     *
     * <p>🔴 Seen red before the fix: {@code success=true} with the refusal in the output.
     */
    @GameTest(template = "empty_test")
    public static void aCommandThatRefusesIsReportedAsAFailure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2)));
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);

        WishExecutor.Result refused = WishExecutor.runOne(server, level, pos, child,
                "wishdatapack mfam_no_such_pack_t118 give");
        helper.assertFalse(refused.success(),
                Component.literal("a refusal was reported as a success: " + refused.joined()));
        helper.assertTrue(refused.joined().contains("No datapack called"),
                Component.literal("the refusal's own words were lost: " + refused.joined()));

        // The over-reach half: a command that succeeds and says so is still a success.
        WishExecutor.Result said = WishExecutor.runOne(server, level, pos, child,
                "say the wish worked");
        helper.assertTrue(said.success(), Component.literal("an ordinary success became a failure: " + said.joined()));
        helper.succeed();
    }
}
