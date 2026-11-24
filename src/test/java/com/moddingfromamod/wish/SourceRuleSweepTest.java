package com.moddingfromamod.wish;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The mechanism that turns a written-down lesson into one that cannot be violated.</b>
 *
 * <p>an earlier issue's central complaint, in its own words:
 *
 * <blockquote>A lesson that lands only in prose and one test hasn't landed. If a rule is worth
 * recording, it needs a mechanism that finds every place it's violated — or it will be true in the
 * tests and false in the product.</blockquote>
 *
 * <p>That is not hypothetical. On 2026-07-31 {@code LEARNINGS.md} recorded <i>"assert on the MOD'S
 * OWN MODEL, not on a spatial entity scan"</i>. It was applied to {@code TrainGameTest} and never to
 * {@code VerifyTrainTool} — the production oracle the agent actually believes. Four days later that
 * tool told a child his working train was broken. Every gate was green throughout, because
 * prose is not a gate.
 *
 * <p>So the mechanically-checkable rules live here, as source-tree sweeps that run in
 * {@code ./gradlew test}: fast, no game needed, and they fail <b>by name and by file</b>.
 *
 * <h2>Every rule carries the code it forbids</h2>
 *
 * <p>A regex-based rule has one dangerous failure mode: it silently stops matching (someone
 * reformats, a method is renamed) and becomes a no-op that reports green forever — the exact
 * disease this class exists to cure, reintroduced one level up. So every {@link Rule} carries a
 * {@code forbiddenSample}, a snippet of the real pre-fix code, and
 * {@link #everyRuleActuallyCatchesTheCodeItForbids()} asserts the rule flags it. A rule that has
 * rotted fails immediately instead of passing vacuously.
 */
class SourceRuleSweepTest {

    /** Where the mod's own source lives, relative to the Gradle project directory. */
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/moddingfromamod");

    /**
     * The shell tooling, which is product too.
     *
     * <p> happened entirely in {@code tools/}: five wait loops, no Java involved, ~40 minutes
     * producing nothing. A sweep that only reads {@code src/main/java} is green throughout.
     *
     * <p>⚠ A shell script is an input to NOTHING by default, so a change to a {@code .sh} would
     * leave {@code :test} <b>UP-TO-DATE</b> and re-report the previous run's XML — the false green
     * docs/REFINEMENT_LADDERS.md records for every test that reads a non-source file, and the same
     * disease as {@code runGameTestServer} exiting 0 when it never ran. {@code build.gradle}
     * already declares {@code tools/*.sh} as a test input for {@code LauncherIsolationTest};
     * {@link #theToolsDirectoryIsDeclaredAsATestInput()} asserts that declaration is still there,
     * because these rules are silently unenforceable without it. Mutation-test a shell rule with
     * {@code --rerun-tasks} and check the results file's mtime, never just the exit code.
     */
    private static final Path TOOLS_ROOT = Path.of("tools");

    /**
     * One mechanically-checkable rule from {@code docs/LEARNINGS.md}.
     *
     * @param name            short label, used in the failure message
     * @param why             what goes wrong when it is violated — the message a future reader gets
     * @param appliesTo       which files the rule governs
     * @param violates        true when this file's text breaks the rule
     * @param forbiddenSample real code the rule MUST flag, so the rule cannot rot into a no-op
     */
    record Rule(String name, String why, Predicate<Path> appliesTo,
                Predicate<String> violates, String forbiddenSample) {
    }

    /**
     * The idiom behind half 's defect class, verbatim from the pre-sweep source.
     *
     * <p>Right only while exactly one player is online AND standing in the artifact's own world.
     * Fails silently otherwise: a machine probed in the wrong world reports "no moving parts", a
     * train reads as "never built", and remove_build clears the wrong world's terrain.
     */
    /**
     * The shape that would silently un-rig the creature path, verbatim in the form it would take.
     *
     * <p>{@code addWithUUID} and {@code addFreshEntityWithPassengers} both put an entity in the
     * world WITHOUT firing {@code EntityJoinLevelEvent} for it in the way the bridge needs, so a
     * creature spawned that way is a {@code wish_mob} that never gets swapped — a modded entity type
     * standing in a world that is supposed to contain none, rendering on the legacy path while
     * everything around it is a rig.
     */
    private static final String SPAWN_BYPASSING_THE_BRIDGE = """
            WishMobEntity mob = ModEntities.WISH_MOB.get().create(level);
            mob.setSpeciesId(id);
            level.addWithUUID(mob);
            """;

    private static final String FIRST_PLAYER_LEVEL = """
            ServerLevel level = server.getPlayerList().getPlayers().stream()
                    .findFirst().map(p -> p.serverLevel()).orElse(server.overworld());
            """;

    /**
     * D9, verbatim from the pre-sweep source: the acting child resolved as <b>join order</b>.
     *
     * <p>Right with one child and unrelated to who wished with two — {@code take_me_to} teleports
     * the wrong boy, {@code give_items} hands over the wished thing to the wrong boy. It also
     * cannot tell a child from a client the mod launched for itself, which is what made a camera
     * unshippable. 34 sites carried it; one function carries it now.
     */
    private static final String FIRST_PLAYER_AS_THE_CHILD = """
            ServerPlayer player = server.getPlayerList().getPlayers().stream()
                    .findFirst().orElse(null);
            """;

    /** itself: a verdict decided by an entity scan anchored on world spawn. */
    private static final String SPAWN_ANCHORED_SCAN = """
            for (Entity e : level.getEntities((Entity) null,
                    new AABB(level.getSharedSpawnPos()).inflate(256), x -> true)) {
            """;

    /**
     * guidance that described the MECHANISM instead of naming the verb, verbatim from the
     * pre-fix source. The agent obeyed it — `modify_train` 0 calls across 4 refinements, every rung
     * `remove_train` + `build_train`.
     */
    private static final String MECHANISM_NOT_VERB = """
            .append("To CHANGE it later (\"add a smoke stack\"), remember a running train is "
                    + "an entity and its blocks are not in the world: it must be taken apart, "
                    + "edited, RE-GLUED, and assembled again.");
            """;

    /**
     * The size-delta check that refused every repaint, verbatim from the pre-fix source.
     *
     * <p>Fine while {@code withAddition} only ever APPENDED. The moment it began replacing at an
     * occupied coordinate, a repaint stopped changing the body's size and this returned 0 —
     * so the tool refused, claiming the blocks were outside the carriage when they were all inside.
     */
    private static final String WIPING_A_SHARED_REGISTRY = """

    public void aDesignedTrainSurvivesAReload(GameTestHelper helper) {
        TrainRegistry.put(server, plan, builtAt);
        TrainRegistry.clear();
        TrainRegistry.load(server);
        TrainPlan back = TrainRegistry.planOf(id);
    }
            """;

    private static final String TWO_TIMELINES = """

    public void aBuiltTrainCanBeModifiedAndStillKeepsItsNewBlocks(GameTestHelper helper) {
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(TrainAssembler.isBusy(), "a"))
                .thenExecute(() -> {
            TrainModifier.start(level, origin, updated);
        });

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(TrainModifier.isBusy(), "b"))
                .thenExecute(() -> {
        });
    }
            """;

    /**
     * The shape of a wait-for-a-queue written as a wait-for-a-clock: a fixed delay whose body's
     * first act is to check an {@code isBusy()} that the sequence API can be told to poll instead.
     * Deliberately narrow -- it matches only the guard idiom, so a genuine "let it run for N ticks
     * and then measure" (RampDriveGameTest drives a train and measures the climb) is untouched.
     */
    private static final java.util.regex.Pattern WAIT_ON_A_CLOCK = java.util.regex.Pattern.compile(
            "runAfterDelay\\(\\s*[^,]+,\\s*\\(\\)\\s*->\\s*\\{\\s*\\n\\s*if\\s*\\(\\s*[A-Za-z0-9_.]*isBusy\\(\\)");

    private static final String WAITING_ON_A_CLOCK = """
            helper.runAfterDelay(220, () -> {
                if (TrainAssembler.isBusy()) {
                    helper.fail("the train never finished building");
                    return;
                }
            """;

    private static final String LANDED_BY_SIZE = """
            added = withBlocks.cars().get(car).body().size() - updated.cars().get(car).body().size();
            """;

    /**
     *, verbatim from the pre-fix source: the concurrency GameTests submitted to the
     * process-wide marshal and then asked it to PICK the next wish. Two overlapping tests then
     * claim from one queue, so a worker is handed the other test's wish — which is exactly what
     * {@code twoSessionsEachGrantTheirOwnWish} reported, naming a wish text that lives in a
     * different test file.
     */
    private static final String CLAIM_FROM_THE_SHARED_QUEUE = """
            Marshal.global().submit(dragonId, me, "an ice dragon");
            WishRun dragon = Marshal.global().claim(one);
            """;

    /**
     * an earlier issue's other half, also verbatim: a test wiped the process-wide ownership registry to make
     * its own "nothing owns this yet" assertion true, deleting every other in-flight test's
     * ownership with it. That made the test a CAUSE of overlap failures rather than only a victim,
     * and is why the failing set moved run to run on an unchanged tree.
     */
    private static final String CLEARING_THE_SHARED_REGISTRY = """
            ArtifactAffinity.global().clear();
            helper.assertTrue(ArtifactAffinity.global().ownerOf(artifact).isEmpty(),
                    "nothing may own it before the wish ends");
            """;

    /** places a real Create module and never asks whether Create is there. */
    private static final String PLACING_WITHOUT_A_CREATE_GUARD =
            "ContraptionPlan plan = ContraptionLibrary.get(\"belt_line\");\n"
                    + "ContraptionPlacer.place(level, at, plan);";

    /** The pre-fix line, kept so the rule above cannot rot into a no-op. */
    private static final String ADD_BOOLEAN_HAND_OVER = """
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            """;

    /**
     * Does any single method start more than one {@link net.minecraft.gametest.framework
     * .GameTestSequence}? Split out because the rule is per-METHOD and a {@code Rule}'s predicate
     * sees the whole file — two sequences in two different tests is perfectly normal.
     */
    private static boolean hasTwoTimelinesInOneMethod(String text) {
        // Tolerant of indentation on purpose: the rule's own forbiddenSample is a text block,
        // whose incidental indent Java strips, so a splitter pinned to four spaces would flag
        // real source and miss its own sample -- which is exactly what
        // everyRuleActuallyCatchesTheCodeItForbids caught here.
        String[] methods = text.split(
                "\\n\\s{0,12}(?:public|private|protected|static)[^\\n]*\\bvoid\\b[^\\n]*\\(");
        for (int i = 1; i < methods.length; i++) {
            int n = 0;
            int at = 0;
            while ((at = methods[i].indexOf("helper.startSequence()", at)) >= 0) {
                n++;
                at += 1;
            }
            if (n > 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Registries whose {@code clear()} a GameTest is still allowed to call — NAMED, so the debt is
     * visible rather than absent.
     *
     * <p>Every one of these is the same hazard the trains pair had, and each needs the same
     * {@code savedOnDisk}-style seam. They are listed rather than fixed because the fix is per
     * registry and this rule exists to stop the class GROWING while that happens. Deleting a name
     * from this list is how you close one.
     */
    /**
     * Registries a GameTest may still wipe. <b>EMPTY, and it should stay that way.</b>
     *
     * <p>It held six names for one commit while each was given its seam. Adding a name back is
     * adding the hazard back, so do that only with the measurement that says the registry cannot
     * be seamed — and put it in the entry.
     */
    private static final java.util.Set<String> CLEAR_STILL_ALLOWED = java.util.Set.of();

    private static final java.util.regex.Pattern SHARED_CLEAR = java.util.regex.Pattern.compile(
            "\\b([A-Z][A-Za-z]*(?:Registry|Affinity|Store|Ability))\\.(?:clear|reset)\\(\\)");

    /**
     * Java source with string literals and comments blanked out, so a rule can match on code.
     *
     * <p>Needed because this repo documents its hazards where they happened. Two files carry a
     * comment saying, correctly, that they do NOT call {@code clear()} and why — and a regex over
     * raw text reads those as the violation they warn about. Blanking literals first so a
     * {@code "//"} inside a string cannot swallow the rest of a line.
     */
    private static String executableJavaOnly(String text) {
        String noStrings = text.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
        return noStrings.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", "");
    }

    /** Does this file wipe a shared registry that now has a per-test seam instead? */
    private static boolean wipesASeamedRegistry(String text) {
        java.util.regex.Matcher m = SHARED_CLEAR.matcher(executableJavaOnly(text));
        while (m.find()) {
            if (!CLEAR_STILL_ALLOWED.contains(m.group(1))) {
                return true;
            }
        }
        return false;
    }

    /** The pre-fix idiom, verbatim: a refusal that costs a turn and cannot be counted. */
    private static final String UNNAMED_NO_PLAYER =
            "return ToolResult.error(\"Nobody is in the world to show it to.\");";

    /**
     * Picking ONE player out of the list by position. Deliberately the whole chain rather than
     * {@code getPlayers().stream()}: banning the idiom was tried once in this file for the
     * ArtifactLevel rule and was recorded as "both too strict and too weak", and it would ban the
     * broadcast helpers that hand the whole list to {@link com.moddingfromamod.marshal.WishAudience}
     * — which is exactly the correct form.
     */
    private static final java.util.regex.Pattern FIRST_PLAYER_PICK =
            java.util.regex.Pattern.compile(
                    "getPlayerList\\(\\)\\.getPlayers\\(\\)\\s*\\.\\s*stream\\(\\)"
                    + "\\s*\\.?\\s*\\n?\\s*\\.\\s*findFirst\\(\\)");

    private static final List<Rule> RULES = List.of(
            new Rule("a refusal because NOBODY IS ONLINE goes through ToolResult.noPlayerOnline",
                    """
                    Ten tools refuse for this reason and each said it in its own words, so the \
                    outcome had no name and nothing could count it. It is not rare -- it is the \
                    PERMANENT state of Gate C½, whose submitter is a FakePlayer and so has an empty \
                    player list for the whole run. Measured over the first eight real wishes mined \
                    into corpus/real-tool-calls.jsonl: conjure_at opened 7 of 8 and failed 7 of 7 \
                    purely for this, i.e. 6-14% of the calls in a C½ wish were turns that could \
                    never have been spent well. Read as agent behaviour that is a headline finding \
                    for #343's "a wish takes ~2x the turns it needs"; it is the harness, and it was \
                    nearly written up as a defect twice in one session. Counted at the SOURCE \
                    rather than matched on the message later: matching would be the denylist that \
                    silently rots, and the eleventh tool simply would not be counted.""",
                    p -> name(p).endsWith("Tool.java"),
                    text -> text.contains("ToolResult.error(\"Nobody is in the world"),
                    UNNAMED_NO_PLAYER),
            new Rule("nothing hands a player an item by reading Inventory.add's boolean",
                    """
                    Inventory.add does NOT report a full bag to a Creative player -- the only mode                     he plays in. From the 1.21.1 bytecode: nothing moved, hasInfiniteMaterials(),                     stack.setCount(0), return TRUE. So `if (!add(stack)) drop(stack)` never once                     dropped anything for him, and the caller above it went on to report how much                     it gave. Measured 2026-08-31 by ItemRefsClientTest on the SERVER's own count:                     200 asked for with a full bag, 0 in the bag and 0 on the ground, and                     give_items saying "Gave 200x minecraft:diamond". The same line was already                     live in WishMobCommands, where a full bag ate a wish egg while the command                     said "Here's your egg!". A wished thing goes through item.delivery.WishDelivery                     (the hotbar, then the Wish Bag, said and recorded for undo); anything                     else through HandOver.toPlayer, which asks for room first and also does the                     broadcastChanges a hand-over needs.""",
                    p -> !name(p).equals("HandOver.java"),
                    text -> text.contains("getInventory().add("),
                    ADD_BOOLEAN_HAND_OVER),
            new Rule("a wished thing is handed over through WishDelivery, never HandOver",
                    """
                    #1338: on 2026-10-03 an oasis wish's drinks, its bar and its \
                    ingredients never reached the child, because five hand-over paths went five \
                    ways and the designed one (a made item's datapack /give) discarded into a full \
                    Creative inventory. There is now ONE path -- item.delivery.WishDelivery: the \
                    hotbar, then the Wish Bag, never the main inventory or the floor, said to him \
                    and recorded for the wish's undo. HandOver.toPlayer is left for the four \
                    things that are not a wished thing: Moddy's own watch, a refill of his own \
                    bucket (PropertyRides), the world's guide book (GuideBookService: \
                    given on arrival, not by a wish, so it has no wish to undo it), and the \
                    client tests' fixtures.""",
                    p -> !name(p).equals("HandOver.java") && !name(p).equals("VanillaWatchItem.java")
                            && !name(p).equals("GuideBookService.java")
                            && !name(p).equals("PropertyRides.java") && !name(p).endsWith("GameTest.java")
                            && !p.toString().replace('\\', '/').contains("/client/test/"),
                    text -> text.contains("HandOver.toPlayer("),
                    "com.moddingfromamod.item.HandOver.toPlayer(player, stack);"),

            new Rule("nothing listens for PlayerNegotiationEvent -- NeoForge never fires it",
                    """
                    NeoForge declares the event and constructs it NOWHERE (21.4.157 and 21.1.228, \
                    read off the patched sources), so a handler for it compiles, registers and \
                    never runs. D14's camera-key gate captured the joining name there, so it \
                    challenged nobody: measured on a rehearsal house with MODDY_ENV=prod, a \
                    keyless client calling itself Moddy-Camera-4 simply joined. Read \
                    the name where it is needed -- ServiceClientGate.registerTasks takes it from \
                    the listener's public getOwner().""",
                    p -> true,
                    text -> text.contains("(net.neoforged.neoforge.event.entity.player.PlayerNegotiationEvent ")
                            || text.contains("(PlayerNegotiationEvent "),
                    "public static void onNegotiation(PlayerNegotiationEvent event) {"),

            new Rule("a GameTest never asks the shared marshal to PICK a wish",
                    """
                    Every GameTest in a batch runs interleaved on the one server thread, so \
                    Marshal.global().claim() hands a worker whatever wish is next in a queue every \
                    other test is also submitting to, which may be somebody else's. Measured \
                    (#260): twoSessionsEachGrantTheirOwnWish failed with "worker one's tool call \
                    was attributed to: make it rain chickens", a wish text belonging to \
                    AwaitWishGameTest, and the failing SET moved between two runs of an unchanged \
                    tree. Take a marshal of your own (Marshal.forTest, and hand it to McpProtocol \
                    so the dispatch asks the same one), or name the wish with assign() instead of \
                    letting the marshal choose.""",
                    p -> name(p).contains("GameTest"),
                    text -> text.contains("Marshal.global().claim("),
                    CLAIM_FROM_THE_SHARED_QUEUE),

            new Rule("a GameTest that PLACES a Create machine says so when Create is absent",
                    """
                    Every Create GameTest in this package skip-succeeds with a loud SKIPPED line \
                    when the mod is not on the classpath -- except HandoffGameTest and \
                    PartCensusGameTest, which shipped without it. Measured (#356): a bare \
                    `runGameTestServer` on an unchanged tree went 3-red, every declared create: \
                    block reporting "nothing is there", because none of them exist. That reads as \
                    PLACEMENT IS BROKEN -- the write-up put the blast radius at the whole machines \
                    class and spent five runs ruling out the world, the branch and flakiness, on \
                    the strength of seeing `create:track` in the log. It is in the log without \
                    Create too: those are OUR OWN strings being printed, which is the \
                    `create:zinc_ore is not evidence` trap CLAUDE.md already records. \
                    Ask BuiltInRegistries.BLOCK whether a create: block exists and helper.succeed() \
                    when it does not; ratchet.sh is what stops a skip reading as coverage.""",
                    p -> name(p).contains("GameTest"),
                    text -> (text.contains("ContraptionPlacer.place(")
                                    || text.contains("ContraptionLibrary.get("))
                            // Any of the three house idioms counts as a guard. Matching only the
                            // SKIPPED literal flagged ten files that are already correct, which is
                            // a rule nobody would keep.
                            && !text.contains("createInstalled")
                            && !text.contains("SKIPPED (Create not loaded")
                            && !text.contains("\"create:controls\""),
                    PLACING_WITHOUT_A_CREATE_GUARD),

            new Rule("a GameTest never wipes a shared registry",
                    """
                    clear() on a process-wide singleton deletes what every other in-flight test \
                    put there, which turns one test into the cause of another's failure. #260 \
                    traced exactly this: finishingACreationLeavesTheThingOwnedByItsMaker cleared \
                    ArtifactAffinity mid-run to make its own precondition true. Neither the clear \
                    nor the cleanup was needed -- artifact ids are already unique per run, so ask \
                    about YOUR id; and where a test really needs a registry of its own, \
                    ArtifactAffinity.forTest() and WishQueue(affinity, clock) already exist.""",
                    p -> name(p).contains("GameTest"),
                    text -> text.contains("ArtifactAffinity.global().clear()"),
                    CLEARING_THE_SHARED_REGISTRY),

            new Rule("whether an edit landed is not measured by how much bigger it made things",
                    """
                    A tool that edits an artifact IN PLACE must not decide "did anything land" from \
                    a size delta. Replacing is an edit that changes nothing's size, so a size delta \
                    reads a correct edit as a no-op — and the refusal that follows is worse than \
                    silence, because it tells the agent something untrue about its own input. \
                    Measured: modify_train refused every repaint with "None of those blocks are \
                    inside carriage 0" for blocks that were all inside carriage 0, 17 times in one \
                    12-cell matrix and 24 in the next, and the agent obeyed it and rebuilt the \
                    train from memory — a five-carriage train came back as two. Count what landed.""",
                    p -> inAgentTools(p) && name(p).startsWith("Modify"),
                    text -> text.contains("body().size()\n") || text.contains("body().size() -"),
                    LANDED_BY_SIZE),

            new Rule("a wait for a JOB is not spelled as a wait for a CLOCK",
                    """
                    `runAfterDelay(N, () -> { if (X.isBusy()) fail(...) })` pays N ticks in full \
                    however fast the job finished, and in a 50-test batch every tick is shared -- \
                    batches run strictly sequentially, so one test's slack is the whole suite's. \
                    Measured 2026-09-01 (#389): converting eleven of these to \
                    startSequence().thenWaitUntil(...) took one test from 28.3s to 3.5s and \
                    another from 76.5s to 50.0s, with the deadline unchanged -- timeoutTicks still \
                    governs, the test just stops overpaying. The contraption package had declared \
                    37,130 delay ticks against 245 runAfterDelay calls and ZERO succeedWhen. \
                    ⚠ Only for a POSITIVE, monotone condition (a queue emptied, an item arrived). \
                    An absence assertion must keep its fixed delay: thenWaitUntil on "there is no \
                    flour" passes at tick 0, before the machine could have made any.""",
                    p -> name(p).contains("GameTest"),
                    text -> WAIT_ON_A_CLOCK.matcher(text).find(),
                    WAITING_ON_A_CLOCK),

            new Rule("one GameTest method starts at most ONE sequence",
                    """
                    Every GameTestSequence begins at tick 0 and they run in PARALLEL, so splitting \
                    one timeline across two startSequence() calls makes the second wait's condition \
                    true before the work it is waiting for has been queued -- and every assertion \
                    after it then runs against a world where nothing happened. \
                    ⚠ THE USUAL OUTCOME IS A VACUOUS PASS, not a failure. Measured 2026-09-01 \
                    (#389): a mechanical conversion of 9 waits split two methods this way, and \
                    exactly ONE went red -- aBuiltTrainCanBeModifiedAndStillKeepsItsNewBlocks, only \
                    because its assertion was strong enough to notice an unmodified train. The \
                    other was silently asserting nothing. \
                    Chain the phases instead: .thenWaitUntil(a).thenExecute(x).thenWaitUntil(b) \
                    .thenExecute(y).thenSucceed(), and put any deferred setup inside the sequence \
                    with thenExecuteAfter rather than in a runAfterDelay beside it.""",
                    p -> name(p).contains("GameTest"),
                    SourceRuleSweepTest::hasTwoTimelinesInOneMethod,
                    TWO_TIMELINES),

            new Rule("a GameTest never wipes a registry that has a per-test seam",
                    """
                    clear() on a process-wide registry deletes what every concurrently-running test \
                    put there. It is invisible while the tests that use one registry happen to be \
                    SPREAD across batches -- which is an accident of defaultBatch being partitioned \
                    in declaration order, not a design. Measured 2026-09-02 (#389): a sharding \
                    experiment put 50 train tests in one batch instead of ~8, and \
                    aCarriageCanBeMadeLongerAndStillAssembles went deterministically red with "the \
                    saved design did not keep the new length: no plan" -- wiped mid-flight by a \
                    neighbour, with no logic change anywhere. \
                    Two of the three clears were pure cleanup and were deleted; the third was \
                    proving persistence, and now uses TrainRegistry.savedOnDisk(server, id), which \
                    reads the same file through the same parser into a map of its own. That is also \
                    the truer test: "the design reached the disk" is the claim, and "clear() empties \
                    a map" was an implementation detail asserted on the way past. \
                    Every registry a GameTest used to wipe now has one of three seams, and which \
                    one is decided by what the test is really asking. A persistence round trip \
                    reads the FILE past the mirror -- TrainRegistry/PaintRegistry/ContraptionRegistry\
                    .savedOnDisk(server, id), HandoverStore/ArtifactMemoryStore.savedOnDisk(id) -- \
                    which is also the truer assertion, since "the design reached the disk" is the \
                    claim and "clear() empties a map" was an implementation detail asserted on the \
                    way past. A wish-scoped holder that must be empty to be read gets a scoped \
                    swap: BuiltAbility.inIsolationForTest(body), safe because a GameTest method \
                    body runs to completion inside one tick. And a clear() that was only tidying \
                    up gets DELETED -- ItemSpecStore's three lived in finally blocks, and every \
                    test in that class already used its own id. \
                    ⚠ That third case is the one worth recognising, because it does not fail \
                    loudly. RemoveItemGameTest puts two fixture specs and then asserts ACROSS \
                    TICKS that remove_item took them away; a neighbour's clear() satisfies that \
                    assertion for it. The test does not go red, it goes green having proved \
                    nothing.""",
                    p -> name(p).contains("GameTest"),
                    SourceRuleSweepTest::wipesASeamedRegistry,
                    WIPING_A_SHARED_REGISTRY),

            new Rule("a tool that tells the agent to CHANGE something names the verb",
                    """
                    Guidance in a tool result must name the tool to call, not describe the \
                    mechanism that tool performs. verify_train's success message said a train "must \
                    be taken apart, edited, RE-GLUED, and assembled again" — a true description of \
                    what modify_train does internally, in the passive voice, naming no tool. Read \
                    by an agent deciding what to do next it is an instruction to do those steps by \
                    hand, and it did: measured on the first trains ladder cell, modify_train was \
                    called ZERO times across four refinements and every rung re-authored the whole \
                    design (#188). It saw that sentence five times in five rungs. This is the FIFTH \
                    mechanism in this codebase caught instructing the agent wrongly in an \
                    authoritative voice. If a result tells the agent how to change a thing, the \
                    name of the tool has to be in the same sentence.""",
                    // Only the tools that actually hand out change-this guidance. Scoped by the
                    // phrase rather than by file name, so a NEW tool that grows the same advice is
                    // covered without anyone remembering to add it here.
                    p -> inAgentTools(p),
                    text -> saysHowToChangeSomethingWithoutNamingATool(text),
                    MECHANISM_NOT_VERB),

            new Rule("artifact tools resolve their world from the artifact",
                    """
                    A tool that acts on an ALREADY-RECORDED artifact (it looks up an origin or an \
                    anchor) must resolve its ServerLevel through ArtifactLevel, from the dimension \
                    recorded beside that position — never from whichever player happens to be \
                    online. See docs/LEARNINGS.md, "Resolve an artifact's world from the artifact".""",
                    // Governs exactly the tools that read a recorded position back out of a store.
                    // Build/conjure/photograph tools legitimately act relative to the player NOW.
                    // PhotographTool is named for that reason: it satisfied this rule only through
                    // its TRAIN branch (ArtifactLevel), which went with Create; its building
                    // orbit frames from the camera's own level and always has.
                    p -> inAgentTools(p) && readsARecordedPosition(p)
                            && !name(p).equals("PhotographTool.java"),
                    // POSITIVE, not a ban on an idiom. Banning "getPlayers().stream()" was the
                    // first attempt and it was both too strict and too weak: too strict because a
                    // tool may legitimately need the PLAYER (to pick an anchor for a NEW build)
                    // while resolving the artifact's own LEVEL, and too weak because the very
                    // defect it was written for reached the source as a two-line variant the
                    // pattern did not match. Requiring the named mechanism instead is precise, and
                    // it cannot be satisfied by accident or defeated by reformatting.
                    text -> !text.contains("ArtifactLevel"),
                    FIRST_PLAYER_LEVEL),

            new Rule("the acting child is resolved through ActingChild, never by join order",
                    """
                    A tool that needs THE child — the one whose wish it is — must ask \
                    ActingChild, which prefers the run's owner and refuses a client the mod \
                    launched for itself. Picking the first player in the list is join order: right \
                    with one child, unrelated to who asked with two, and blind to a camera. See \
                    docs/MULTIPLAYER_DECISIONS.md D9/D10.

                    Iterating the list is NOT banned — a broadcast legitimately walks everybody, \
                    and SetStatusTool/NotifyPlayerTool hand the whole list to WishAudience on \
                    purpose. What is banned is picking ONE out of it by position.""",
                    p -> inAgentTools(p),
                    text -> FIRST_PLAYER_PICK.matcher(text).find(),
                    FIRST_PLAYER_AS_THE_CHILD),

            new Rule("no verdict is decided by a spawn-anchored search",
                    """
                    A verify/inspect tool must not reference world spawn at all. Anchoring a search \
                    on getSharedSpawnPos() is how verify_train came to report every train built more \
                    than 256 blocks out as broken (#10). Locate from the artifact's own recorded \
                    origin, and decide from the owning system's model. See docs/LEARNINGS.md, \
                    "Assert on the mod's own model".""",
                    p -> inAgentTools(p) && (name(p).startsWith("Verify") || name(p).startsWith("Inspect")),
                    text -> text.contains("getSharedSpawnPos"),
                    SPAWN_ANCHORED_SCAN),

            new Rule("a wished creature reaches the world through addFreshEntity",
                    """
                    VanillaRigBridge intercepts EntityJoinLevelEvent, which is what makes the \
                    vanilla-compat rig the default without every spawn site knowing about it -- the \
                    wish egg, the agent's sculpt_creature, /wishmob and the test fixtures all go \
                    through one seam and none of them names the rig. A spawn route that skips that \
                    event leaves a modded wish_mob standing in a world that is supposed to contain \
                    no modded entity type (scoreboard D1), rendering on the legacy path while \
                    everything else is a rig -- and nothing fails, because a wish_mob is a perfectly \
                    good entity. It is the "there is no check" versus "the check is not wired to \
                    this path" pair from docs/CAMERA.md, in the one place where the wiring IS the \
                    feature.""",
                    p -> true,
                    text -> text.contains("WISH_MOB")
                            && (text.contains(".addWithUUID(")
                                    || text.contains("addFreshEntityWithPassengers(")),
                    SPAWN_BYPASSING_THE_BRIDGE),
            new Rule("a comment claiming an open bug is dated or cites an issue",
                    """
                    A superseded comment is an oracle that lies. TrainAssembler carried "the last \
                    carriage still merges ... about 1 run in 4" for four days after it was \
                    disproved, and it cost a fresh reader a hunt for a bug that did not exist. If a \
                    comment asserts a currently-broken thing, it must carry a date or an issue \
                    number so the claim can be re-checked instead of believed forever. See \
                    docs/LEARNINGS.md.

                    KNOWN COST: this is keyword-based, so it occasionally flags a sentence that                     uses one of those phrases about something other than a live defect ("if the                     driver breaks, this still fails" is a design statement, not a bug report).                     Rewording is the intended response. A guard that can be argued with is a                     guard that gets argued with, so the false-positive rate is paid deliberately                     rather than papered over with an opt-out marker.""",
                    SourceRuleSweepTest::isJavaSource,
                    SourceRuleSweepTest::hasUndatedOpenBugClaim,
                    "// the last carriage still merges into the one before it about 1 run in 4\n"),
            new Rule("a GameTest poll does not re-schedule the same Runnable object",
                    """
                    GameTestInfo keeps runAfterDelay's schedule in an Object2LongMap KEYED BY THE \
                    Runnable, runs each due entry and then iterator.remove()s it (read from the \
                    1.21.4 bytecode, #1012). So `helper.runAfterDelay(10, poll[0])` from INSIDE \
                    poll[0] re-puts the key and the removal deletes it: the poll runs ONCE and the \
                    test times out with "Didn't succeed or fail", which reads as a slow feature. \
                    Measured: PendingPressGameTest polled once and timed out in both arms. Schedule \
                    a fresh lambda each time: `helper.runAfterDelay(10, () -> poll[0].run())`.""",
                    SourceRuleSweepTest::isJavaSource,
                    text -> java.util.regex.Pattern.compile(
                            "runAfterDelay\\([^,;]+,\\s*[A-Za-z_][A-Za-z0-9_]*\\[0\\]\\s*\\)").matcher(text).find(),
                    "helper.runAfterDelay(10, poll[0]);\n"),
            new Rule("a GameTest chains a future with the ASYNC continuation",
                    """
                    A plain thenApply/thenAccept/thenRun/thenCompose runs its callback on the \
                    CALLING thread when the future has already finished — in a GameTest, the SERVER \
                    thread, where anything that hops to it (a datapack compile's AgentBridge.onServer) \
                    throws "called from the server thread — that would deadlock". It is a race: \
                    DatapackItemGivesGameTest passed 20/20 and then lost it once (2026-09-28), \
                    because the call before it was a fast refusal. Use thenApplyAsync and friends.""",
                    path -> isJavaSource(path) && path.getFileName().toString().endsWith("GameTest.java"),
                    text -> java.util.regex.Pattern.compile(
                            "\\.then(Apply|Accept|Run|Compose|Combine)\\(").matcher(text).find(),
                    "CompletableFuture<ToolResult> b = a.thenApply(r -> call());\n"),
            new Rule("an entity is added to a client at its exact position, not its block's corner",
                    """
                    new ClientboundAddEntityPacket(entity, data, blockPos) is the HANGING-entity \
                    constructor (paintings, item frames): in 1.21.8 it writes blockPos.getX/Y/Z, \
                    so any other entity added with entity.blockPosition() lands up to a block off \
                    on each axis and stays there on a stock client until a move or teleport \
                    corrects it. RigVeilService's reveal did this, and a revealed creature that \
                    held still stayed pulled apart (#59). Send the exact form: id, UUID, getX/Y/Z, \
                    rotations, type, data, getDeltaMovement() and getYHeadRot(). A hanging entity \
                    passes its own getPos(), never blockPosition().""",
                    SourceRuleSweepTest::isJavaSource,
                    text -> java.util.regex.Pattern.compile(
                            "new\\s+ClientboundAddEntityPacket\\([^;]*?\\.blockPosition\\(\\)")
                            .matcher(text).find(),
                    "p.connection.send(new ClientboundAddEntityPacket(e, 0, e.blockPosition()));\n"));

    // ──────────────────────────── the same idea, for the shell tooling ────────────────────────────

    /**
     * One mechanically-checkable rule over a shell script, reported <b>by file and by line</b>.
     *
     * <p>Separate from {@link Rule} because a shell defect is a LINE, not a file: "somewhere in
     * run-model-arm.sh" is not actionable when the script is 90 lines of queueing logic, and the
     * whole point of a sweep is that the failure message is the fix instruction.
     *
     * @param offendingLines 1-based line numbers this rule flags in the given file text
     */
    record ShellRule(String name, String why, Predicate<Path> appliesTo,
                     java.util.function.Function<String, List<Integer>> offendingLines,
                     String forbiddenSample) {
    }

    /**
     * verbatim: the wait loop that could never exit.
     *
     * <p>Measured 2026-08-23 — five of these accumulated at 43, 41, 40, 39 and 37 minutes, and a
     * run queued behind one never launched at all, so no log file was ever created. Roughly 40
     * minutes produced nothing while the session reported a run in flight.
     */
    private static final String SELF_MATCHING_WAIT = """
            until ! pgrep -f runGameTestServer; do sleep 20; done
            """;

    /** verbatim: the line that wrote a fake curl over /usr/bin/curl when the disk was full. */
    private static final String UNCHECKED_TEMP_DIR = """
                W="$(mktemp -d)"; mkdir -p "$W/bin" "$W/state"
            """;

    private static final List<ShellRule> SHELL_RULES = List.of(
            new ShellRule("a wait loop does not ask the process table about itself",
                    """
                    A `while`/`until` loop whose CONDITION runs `pgrep -f` (or a `ps ... | grep` \
                    pipeline) must go through tools/wait-for.sh. `pgrep -f` matches FULL COMMAND \
                    LINES, and the waiting shell's own command line contains the pattern it is \
                    searching for — so the loop matches itself and can never exit. Measured \
                    2026-08-23 (#225): five such waiters accumulated at 37-43 minutes and a run \
                    queued behind one never launched, ~40 minutes producing nothing while the \
                    session reported a run in flight.

                    EXCLUDING $$ IS NOT THE FIX, and that was tested rather than assumed: with \
                    `pgrep -f X | grep -v "^$$$"` the loop STILL hangs, because the caller's shell \
                    and any wrapper carry the pattern too and are not $$. Nor is self-matching the \
                    only way this family lies — docs/REFINEMENT_LADDERS.md records three in one \
                    day: it also matched ANOTHER WORKTREE's identically-named script, and a \
                    `ps | grep -c` counted its own ps and grep (4 clients reported where there \
                    were 2). A fix for one leaves two live.

                    PREFER THE ARTIFACT OVER THE PROCESS ENTIRELY:

                        ./tools/wait-for.sh log "$LOG" 'required tests (passed|failed)'

                    `runGameTestServer` exits 0 when it never ran (a second one in the same \
                    worktree dies on session.lock and Gradle still prints BUILD SUCCESSFUL), so \
                    the absence of a process proves nothing and the log line is the only \
                    trustworthy verdict. `./tools/wait-for.sh gone <pattern>` is the fallback when \
                    there is genuinely no artifact to watch.

                    KNOWN COST: this governs the loop CONDITION only, so a one-shot `pgrep` is \
                    left alone -- tools/check-client-ram.sh and tools/client-test.sh both dodge \
                    the hazard deliberately and are documented in place. There is no opt-out \
                    marker, on purpose: a guard that can be argued with is a guard that gets \
                    argued with.""",
                    SourceRuleSweepTest::isSweptShellScript,
                    SourceRuleSweepTest::selfMatchingWaitLoops,
                    SELF_MATCHING_WAIT),
            new ShellRule("a temporary directory is checked before anything is written under it",
                    """
                    `W="$(mktemp -d)"` must be followed by `|| exit 1` (or `||` anything that \
                    stops). When the disk is full mktemp prints an error and W is EMPTY, so \
                    "$W/bin/curl" is /bin/curl. Measured 2026-11-17 (#2421): a full disk during \
                    ci-static turned a server tool's test's fake curl, gcloud and \
                    sleep into the container's real ones, and other suites did the same to apt, \
                    dpkg and systemctl and scattered their output over / . Every later network \
                    test then failed against a fake curl that printed nothing, which read as a \
                    broken proxy, and apt could not repair it because dpkg was a fake too.""",
                    p -> true,
                    SourceRuleSweepTest::uncheckedTempDirs,
                    UNCHECKED_TEMP_DIR));

    private static final java.util.regex.Pattern TEMP_DIR_ASSIGNMENT =
            java.util.regex.Pattern.compile("(?:^|[;\\s])(?:local\\s+)?[A-Za-z_][A-Za-z0-9_]*=\"?\\$\\(mktemp -d[^)]*\\)\"?(.*)$");

    /** Lines where a {@code mktemp -d} assignment is not immediately followed by {@code ||}. */
    private static List<Integer> uncheckedTempDirs(String text) {
        List<Integer> hits = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripFullLineComment(lines[i]);
            java.util.regex.Matcher m = TEMP_DIR_ASSIGNMENT.matcher(line);
            if (m.find() && !m.group(1).strip().startsWith("||")) {
                hits.add(i + 1);
            }
        }
        return hits;
    }

    // ───────────────────────────────────────── the sweep ─────────────────────────────────────────

    @Test
    @DisplayName("every mechanically-checkable LEARNINGS rule holds across the whole source tree")
    void theSourceTreeObeysTheRulesWeWroteDown() throws IOException {
        List<Path> files = javaFiles();
        assertFalse(files.isEmpty(),
                "found no source files under " + SOURCE_ROOT.toAbsolutePath()
                        + " — the sweep would pass vacuously, which is worse than failing");

        List<String> violations = new ArrayList<>();
        for (Rule rule : RULES) {
            for (Path f : files) {
                if (!rule.appliesTo().test(f)) {
                    continue;
                }
                String text = read(f);
                if (rule.violates().test(text)) {
                    violations.add(SourceRoots.relativize(SOURCE_ROOT, f) + "\n      breaks: " + rule.name()
                            + "\n      " + rule.why().replace("\n", "\n      "));
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "A rule recorded in docs/LEARNINGS.md is violated in the product:\n\n  - "
                        + String.join("\n\n  - ", violations)
                        + "\n\nThese are not style notes. Each one is a bug that already happened "
                        + "once and was written down so it would not happen twice.");
    }

    /**
     * <b>The guard on the guard.</b> Each rule must still flag the code it was written to forbid.
     *
     * <p>Without this a rule can quietly stop matching — a rename, a reformat — and report green
     * forever while the defect walks back in. That is the same "true in the tests, false in the
     * product" failure this whole class exists to prevent, so it gets its own check.
     */
    @Test
    @DisplayName("every rule actually catches the code it forbids (no rule has rotted into a no-op)")
    void everyRuleActuallyCatchesTheCodeItForbids() {
        for (Rule rule : RULES) {
            assertTrue(rule.violates().test(rule.forbiddenSample()),
                    "the rule \"" + rule.name() + "\" no longer flags the very code it was written "
                            + "to forbid:\n" + rule.forbiddenSample()
                            + "\nIt is now a no-op reporting green. Fix the rule, or delete it and "
                            + "say why in docs/LEARNINGS.md.");
        }
    }

    /**
     * The sweep must actually reach the tools it governs. A predicate that matched nothing would
     * make the whole sweep vacuous while still passing.
     */
    @Test
    @DisplayName("the sweep reaches real files for every rule")
    void noRuleGovernsAnEmptySetOfFiles() throws IOException {
        List<Path> files = javaFiles();
        for (Rule rule : RULES) {
            long governed = files.stream().filter(rule.appliesTo()).count();
            assertTrue(governed > 0,
                    "the rule \"" + rule.name() + "\" governs NO files, so it proves nothing. "
                            + "Its file predicate has drifted from the tree it was written against.");
        }
    }

    @Test
    @DisplayName("no shell tool waits on the process table by asking about itself (#225)")
    void theShellToolingObeysTheRulesWeWroteDown() throws IOException {
        List<Path> files = shellFiles();
        assertFalse(files.isEmpty(),
                "found no shell scripts under " + TOOLS_ROOT.toAbsolutePath()
                        + " — the sweep would pass vacuously, which is worse than failing");

        List<String> violations = new ArrayList<>();
        for (ShellRule rule : SHELL_RULES) {
            for (Path f : files) {
                if (!rule.appliesTo().test(f)) {
                    continue;
                }
                String text = read(f);
                String[] lines = text.split("\n", -1);
                for (int line : rule.offendingLines().apply(text)) {
                    // BY FILE AND LINE, with the offending line quoted: the message has to be the
                    // fix instruction, or the next person greps for the rule name instead.
                    violations.add(f + ":" + line + "\n      " + lines[line - 1].strip()
                            + "\n      breaks: " + rule.name()
                            + "\n      " + rule.why().replace("\n", "\n      "));
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "A wait loop in tools/ asks the process table a question it will answer about "
                        + "itself:\n\n  - " + String.join("\n\n  - ", violations)
                        + "\n\nRoute it through ./tools/wait-for.sh — `log` for a verdict, `gone` "
                        + "or `running` when there is genuinely no artifact to watch.");
    }

    /** The guard on the guard, for the shell rules. See the Java version above. */
    @Test
    @DisplayName("every shell rule still catches the code it forbids")
    void everyShellRuleActuallyCatchesTheCodeItForbids() {
        for (ShellRule rule : SHELL_RULES) {
            assertFalse(rule.offendingLines().apply(rule.forbiddenSample()).isEmpty(),
                    "the rule \"" + rule.name() + "\" no longer flags the very code it was written "
                            + "to forbid:\n" + rule.forbiddenSample()
                            + "\nIt is now a no-op reporting green. Fix the rule, or delete it and "
                            + "say why in docs/LEARNINGS.md.");
        }
    }

    @Test
    @DisplayName("the shell sweep reaches real files for every rule")
    void noShellRuleGovernsAnEmptySetOfFiles() throws IOException {
        List<Path> files = shellFiles();
        for (ShellRule rule : SHELL_RULES) {
            long governed = files.stream().filter(rule.appliesTo()).count();
            assertTrue(governed > 0,
                    "the rule \"" + rule.name() + "\" governs NO files, so it proves nothing.");
        }
    }

    /**
     * <b>The wiring the shell rules silently depend on.</b>
     *
     * <p>A {@code .sh} is an input to nothing by default, so without this declaration Gradle keeps
     * {@code :test} UP-TO-DATE across an edit to one and replays the previous run's XML — the rule
     * above would report green over a violation nobody ran it against. That exact false green has
     * already been measured twice in this repo (docs/REFINEMENT_LADDERS.md; and the
     * {@code LauncherIsolationTest} note in build.gradle, where a deliberate regression produced
     * BUILD SUCCESSFUL in 598ms without running).
     */
    @Test
    @DisplayName("gate-a.gradle (the `test {}` task, #2222) still declares tools/*.sh as a test input")
    void theToolsDirectoryIsDeclaredAsATestInput() throws IOException {
        String gradle = Files.readString(Path.of("gate-a.gradle"), StandardCharsets.UTF_8);
        assertTrue(gradle.contains("fileTree('tools')") && gradle.contains("include '*.sh'"),
                "gate-a.gradle no longer declares tools/*.sh as an input to :test. Every shell rule "
                        + "in this class is now unenforceable: Gradle will keep the task UP-TO-DATE "
                        + "across a change to a script and re-report the last run's results.");
    }

    // ───────────────────────────────────────── helpers ─────────────────────────────────────────

    /**
     * <b>Every broadcast goes through {@code Send}, because the guard already existed and nothing
     * used it.</b>
     *
     * <p>{@code PacketDistributor.sendToAllPlayers} does not skip a player who cannot receive the
     * payload — {@code ServerCommonPacketListenerImpl.send} calls
     * {@code NetworkRegistry.checkPacket} as its first line, which <b>throws</b>
     * {@code UnsupportedOperationException: Payload … may not be sent to the client!}. One such
     * player aborts the whole broadcast, on the server thread, from whatever tick handler called
     * it. The players who cannot receive are a GameTest mock and — the case that matters — a
     * <b>vanilla client</b>.
     *
     * <p>{@code net/Send.java} was written for exactly this, documents a dedicated server it killed
     * 24 seconds into a run, and had only {@code to(player, payload)}: <b>all nine</b> broadcast
     * sites went straight past it. That is the shape {@code docs/CAMERA.md} names — <i>"there is no
     * check" and "the check is not wired to this path" look identical from outside</i> — and the
     * only fix that stays fixed is a rule, because the next broadcast will be written by someone
     * who has never read this paragraph.
     *
     * <p>No exemptions on purpose. The Gate-C harnesses were converted too rather than excluded: an
     * exemption list is a place for the next one to be added quietly.
     */
    @Test
    @DisplayName("no broadcast bypasses Send — sendToAllPlayers throws for a channel-less client")
    void everyBroadcastGoesThroughSend() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaFiles()) {
            if (p.endsWith(Path.of("net", "Send.java"))) {
                continue;   // the one place allowed to call it — that is what Send IS
            }
            String code = Files.readString(p, StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("//[^\n]*", "");
            if (code.contains("sendToAllPlayers")) {
                offenders.add(SourceRoots.relativize(SOURCE_ROOT, p).toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "PacketDistributor.sendToAllPlayers THROWS for a player with no channel rather "
                        + "than skipping them — a GameTest mock, or a vanilla client, aborts the "
                        + "whole broadcast on the server thread. Use com.moddingfromamod.net.Send"
                        + ".all(payload), which asks first:\n  "
                        + String.join("\n  ", offenders));
    }

    /**
     * <b>Never RESOLVE a base item and then pass the answer on.</b>
     *
     * <p>{@code ItemBase.id} turns "our own item" into whatever id we are currently handing out —
     * the mod item, or a vanilla carrier standing in for it. Every renderer downstream of it takes
     * the <b>raw</b> base and resolves it itself, because the raw base is the only thing that still
     * knows the difference between <i>"ours, possibly substituted"</i> and <i>"an item the child
     * named"</i>. Those two are indistinguishable once resolved, and three separate things key on
     * telling them apart: whether our icon library applies, whether the carrier's own attributes
     * need neutralising, and whether to stamp the carrier mark the creative-menu shim looks for.
     *
     * <p><b>This is not hypothetical and it is not the recipe path.</b> It shipped on the line that
     * renders the give for every {@code make_item} wish. Measured: a from-scratch item came out as
     * a plain brown leather horse armour reading <i>"+3 Armor"</i> under the child's own name, with
     * no icon, no colour, and nothing for the menu to find. Gates A, B and C were all green,
     * because every one of them drove {@code itemArg} or a {@code gives} overload directly and none
     * of them drove the argument {@code call} actually passes — this repo's own rule that a rule
     * tested where it is DEFINED is not tested where it RUNS.
     *
     * <p>Harmless for as long as the mod item was the default (resolved and raw were the same
     * string), which is exactly why a grep is worth more here than a reviewer: the defect is
     * invisible until the day the default moves, and then it is invisible again because nothing
     * logs it.
     */
    @Test
    @DisplayName("no give-line renderer is handed an already-resolved base item")
    void noRendererIsHandedAResolvedBase() throws IOException {
        // The renderers that take a RAW base and resolve it themselves. Anything else may hold the
        // resolved id, because it renders that string and passes it to nobody.
        Pattern resolveThenPass = Pattern.compile(
                "\\b(gives|itemArg|yieldBlock|clearPredicate)\\s*\\([^;]*?ItemBase\\.id\\s*\\(",
                Pattern.DOTALL);
        List<String> offenders = new ArrayList<>();
        for (Path p : javaFiles()) {
            String code = Files.readString(p, StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("//[^\n]*", "");
            Matcher m = resolveThenPass.matcher(code);
            while (m.find()) {
                offenders.add(SourceRoots.relativize(SOURCE_ROOT, p) + " → " + m.group(1) + "(… ItemBase.id(…))");
            }
        }
        assertTrue(offenders.isEmpty(),
                "these renderers take the RAW base and resolve it themselves — handing them an "
                        + "already-resolved id makes our own item look like one the child named, "
                        + "so it silently loses its icon, its colour, its attribute neutralisation "
                        + "and its carrier mark:\n  " + String.join("\n  ", offenders));
    }

    /**
     * <b>Every capture teleport that MOVES a body must tell the freeze where it put it —.</b>
     *
     * <p>{@code WishCapture} holds a borrowed child at an anchor every five ticks so he cannot walk
     * out of his own wish card, and the capture path moves that same body eighteen blocks out to an
     * orbit seat. Unless the mover says so, the two fight, and whichever wrote last before the
     * shutter decides the photograph — <b>measured, 37 {@code moved too quickly} warnings in the
     * five seconds of one capture, in matched ± pairs, and none at all on the camera arm.</b>
     *
     * <p>This is the CALLERS rule docs/CAMERA.md keeps recording, applied before the fact: the
     * check exists, and what makes it work is that every path which moves a frozen body is wired to
     * it. A grep is worth more than a reviewer here because a seventh mover added later compiles,
     * runs, produces a plausible photograph of the wrong thing, and logs nothing. It earned that on
     * its first honest run, naming a SECOND unwired mover nobody had noticed — {@code aimAt}'s
     * travelling-camera seat, which is harmless only for as long as that branch stays camera-only.
     *
     * <p><b>⚠ THE SCOPE IS THE HARD PART, and the first version of this test got it 20x too wide.</b>
     * It governed every {@code connection.teleport} in the tree and reported twenty-one offenders,
     * all of them client tests driving their own local camera and a probe moving a dedicated one —
     * none of which can ever be frozen. A sweep that is permanently loud is one people learn to
     * silence. So the scope is <b>the files that can be handed a frozen body at all</b>: those whose
     * code (not their prose) names {@code WishCapture} or {@code Viewpoint}. Two shapes are exempt
     * inside those files — a teleport that re-sends the body's own coordinates (an aim-in-place
     * cannot trip the drift threshold), and the freeze's own restore, which IS the anchor.
     */
    @Test
    @DisplayName("every capture teleport that MOVES a body announces where it put it")
    void everyCaptureMoveTellsTheFreeze() throws IOException {
        Pattern teleport = Pattern.compile(
                "\\bconnection\\s*\\.\\s*teleport\\s*\\(([^;]*?)\\)\\s*;", Pattern.DOTALL);
        // An aim-in-place re-sends the body's own position; that is the one argument shape exempt.
        Pattern inPlace = Pattern.compile("\\w+\\s*\\.\\s*get[XYZ]\\s*\\(\\s*\\)");
        List<String> offenders = new ArrayList<>();
        List<String> inScope = new ArrayList<>();
        int moves = 0;
        for (Path p : javaFiles()) {
            String code = read(p)
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("//[^\n]*", "");
            // Can this file ever be handed a body that WishCapture is holding still?
            if (!code.contains("WishCapture") && !code.contains("Viewpoint")) {
                continue;
            }
            if (!code.contains("connection.teleport")) {
                continue;
            }
            inScope.add(SourceRoots.relativize(SOURCE_ROOT, p).toString());
            Matcher m = teleport.matcher(code);
            while (m.find()) {
                String args = m.group(1);
                if (inPlace.matcher(args).find()) {
                    continue;   // same spot, new angle
                }
                // The freeze's own restore and hold ARE the anchor; telling them would be circular.
                if (args.contains("o.x()") || args.contains("to[0]")) {
                    continue;
                }
                moves++;
                // The announcement rides with the move -- same method, allowing for a comment block
                // between them. Deliberately per-SITE rather than per-file: a file with two movers
                // and one announcement is exactly the half-wired shape this rule exists to catch,
                // and is what it found.
                int from = Math.max(0, m.start() - 200);
                int to = Math.min(code.length(), m.end() + 1200);
                if (!code.substring(from, to).contains("aimedAt(")) {
                    offenders.add(SourceRoots.relativize(SOURCE_ROOT, p) + " → " + args.replaceAll("\\s+", " "));
                }
            }
        }
        // ASSERT THE SCOPE, NOT ONLY THE FINDINGS: "I looked and found nothing" and "I looked at
        // nothing" print the same word, and this test has already been wrong in BOTH directions --
        // once at 21 false offenders, and it would read green on a pattern that matched nothing.
        System.out.println("[capture-move-sweep] in scope: " + inScope + "; real moves: " + moves);
        assertTrue(inScope.contains("agent/tools/PhotographTool.java"),
                "the orbit and the film both move a possibly-frozen body from PhotographTool, so "
                        + "it not being in scope means the scoping rule has rotted: " + inScope);
        assertTrue(moves >= 1,
                "no real MOVE found in " + inScope + " — the teleport pattern has rotted, and a "
                        + "sweep over nothing prints the same word as a sweep that found nothing");
        assertTrue(offenders.isEmpty(),
                "these teleports move a body that may be FROZEN without telling WishCapture where "
                        + "they put it, so the freeze drags it back within five ticks and the next "
                        + "shutter photographs wherever the child was standing (#513):\n  "
                        + String.join("\n  ", offenders));
    }

    /**
     * <b>The transitional register and the code cannot drift apart.</b>
     *
     * <p>A doc listing "the temporary code" is worth nothing the first time somebody adds a file to
     * the carrier route and forgets the doc — and this repo already records that a step you must
     * REMEMBER is not a control. So the marker in the code is the register of record, and this
     * asserts the map still matches it: every file carrying the marker must be named in
     * {@code docs/VANILLA_CARRIER_TRANSITIONAL.md}.
     *
     * <p>Deliberately one-directional. The doc also lists things that are NOT transitional (§3) and
     * seams inside otherwise-permanent files, so requiring the reverse — every name in the doc to
     * carry a marker — would fail on its own contents.
     */
    @Test
    @DisplayName("every TRANSITIONAL-marked file is named in the transitional register")
    void theTransitionalRegisterMatchesTheCode() throws IOException {
        // ONE RULE PER SPIKE, swept together. A register enforced for the first vanilla-compat
        // path and not the second is the same defect class the guard exists to prevent -- so the
        // marker text and its register are a table, and a third spike is one row.
        Map<String, Path> registers = new java.util.LinkedHashMap<>();
        registers.put("TRANSITIONAL \u2014 carrier spike",
                Path.of("docs/VANILLA_CARRIER_TRANSITIONAL.md"));
        registers.put("TRANSITIONAL \u2014 art spike",
                Path.of("docs/VANILLA_ART_TRANSITIONAL.md"));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Path> e : registers.entrySet()) {
            if (!Files.exists(e.getValue())) {
                continue;   // that route landed or was removed; its register goes with it
            }
            String doc = Files.readString(e.getValue(), StandardCharsets.UTF_8);
            for (Path root : List.of(SOURCE_ROOT, Path.of("src/test/java/com/moddingfromamod"))) {
                if (!Files.exists(root)) {
                    continue;
                }
                try (Stream<Path> walk = SourceRoots.walk(root)) {
                    for (Path p : walk.filter(SourceRuleSweepTest::isJavaSource).toList()) {
                        String code = Files.readString(p, StandardCharsets.UTF_8);
                        if (code.contains(e.getKey())
                                && !doc.contains(p.getFileName().toString())) {
                            missing.add(p.getFileName() + " (register " + e.getValue() + ")");
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "these files are marked TRANSITIONAL but the register does not name them, so "
                        + "whoever removes that route will miss them (#452). Add them to the "
                        + "register named beside each:\n  "
                        + String.join("\n  ", missing));
    }

    /**
     * <b>THE GOVERNING INVARIANT of every vanilla-compat path: the agent cannot be told which one
     * it is on.</b>
     *
     * <p>See {@code docs/VANILLA_COMPAT_METHODOLOGY.md} §0.5. Divergence between a mod path and a
     * vanilla-compatible one is allowed in the VIEW (which item renders it, which client code draws
     * it) and in world persistence — and nowhere else. The MCP contract, the internal model and
     * what the tools DO are identical on both.
     *
     * <p>Two reasons it is a build failure rather than a convention. Switching modes has to stay a
     * DEPLOYMENT decision: the moment a lesson or a schema names a path, a wish class has to be
     * built and calibrated twice. And it is the only reason the A/B evaluation is valid at all —
     * both arms consume the SAME agent output, so a measured difference is attributable to the
     * path. A path-specific prompt makes every number in the spike meaningless.
     *
     * <p>Scoped to what the agent actually READS: the lessons, and the schema/description strings
     * in the tool classes. A path name in the give-line RENDERER is fine and expected — that is
     * downstream of the contract, which is where the substitution is supposed to live.
     */
    @Test
    @DisplayName("nothing the agent reads names a compat path")
    void theAgentContractNeverNamesAPath() throws IOException {
        List<String> leaks = new ArrayList<>();
        Path lessons = Path.of("src/main/resources/lessons");
        if (Files.exists(lessons)) {
            try (Stream<Path> walk = Files.walk(lessons)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(p, StandardCharsets.UTF_8).toLowerCase(
                            java.util.Locale.ROOT);
                    // EVERY compat path, and the list is CUMULATIVE — a spike adds its own names
                    // and never replaces the ones already here. A guard written for one wish class
                    // and not swept across the others is the shape CLAUDE.md keeps recording (the
                    // silent-cap bug was fixed in BuildPlan and was still live in ContraptionPlan),
                    // and this list is the only thing that knows about the hazard: a lesson naming
                    // a route would let the agent tell which path it is on, which turns one feature
                    // with two views into two features and voids every A/B, because the two arms
                    // would stop sharing an input. Substitute DOWNSTREAM of the contract.
                    //   carrier -> docs/VANILLA_ITEM_SPIKE_RESULTS.md
                    //   watch   -> docs/VANILLA_WATCH_SPIKE.md
                    //   art     -> docs/VANILLA_ART_SPIKE_RESULTS.md
                    for (String banned : List.of("vanillacarrier", "vanilla carrier",
                            "leather_horse_armor", "carrier route",
                            "vanillawatch", "vanilla watch", "watch panel", "watchmenu",
                            "vanillaart", "vanilla art", "map art", "item frame",
                            "filled_map", "art route")) {
                        if (text.contains(banned)) {
                            leaks.add(p.getFileName() + " says \"" + banned + "\"");
                        }
                    }
                }
            }
        }
        // The agent-facing half of a tool is its schema and description strings. A reference in the
        // renderer is not a leak, so match only inside the argument list of a schema call.
        Pattern schema = Pattern.compile(
                "(?s)(Schemas\\.prop|Schemas\\.enumProp|\\.describe)\\((.{0,6000}?)\\);");
        Path toolRoot = SOURCE_ROOT.resolve("agent");
        if (Files.exists(toolRoot)) {
            try (Stream<Path> walk = SourceRoots.walk(toolRoot)) {
                for (Path p : walk.filter(SourceRuleSweepTest::isJavaSource).toList()) {
                    Matcher m = schema.matcher(Files.readString(p, StandardCharsets.UTF_8));
                    while (m.find()) {
                        String body = m.group(2).toLowerCase(java.util.Locale.ROOT);
                        if (body.contains("vanillacarrier") || body.contains("leather_horse_armor")
                                || body.contains("vanillawatch") || body.contains("watchmenu")
                                || body.contains("vanillaart") || body.contains("filled_map")
                                || body.contains("item frame") || body.contains("map art")) {
                            leaks.add(p.getFileName() + " names a path inside a schema description");
                        }
                    }
                }
            }
        }
        assertTrue(leaks.isEmpty(),
                "the agent must not be able to tell which compat path it is on — that turns one "
                        + "feature with two views into two features, and it invalidates every A/B "
                        + "measurement, because the two arms would no longer share an input. "
                        + "Substitute DOWNSTREAM of the contract (ItemBase.id is that seam):\n  "
                        + String.join("\n  ", leaks));
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> walk = SourceRoots.walk(SOURCE_ROOT)) {
            return walk.filter(SourceRuleSweepTest::isJavaSource).sorted().toList();
        }
    }

    private static boolean isJavaSource(Path p) {
        return Files.isRegularFile(p) && p.toString().endsWith(".java");
    }

    private static String name(Path p) {
        return p.getFileName().toString();
    }

    private static List<Path> shellFiles() throws IOException {
        if (!Files.isDirectory(TOOLS_ROOT)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(TOOLS_ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".sh") || f.toString().endsWith(".command"))
                    .sorted().toList();
        }
    }

    /**
     * Which shell scripts the wait-loop rule governs: all of them except the helper that implements
     * it and the suite that proves it.
     *
     * <p>{@code wait-for.sh} calls {@code pgrep} because that IS its job, and
     * {@code test-wait-for.sh} contains the forbidden loop on purpose — it asserts the hazard still
     * reproduces on this box before claiming to have avoided it, because a suite that cannot fail
     * proves nothing. Two named files, both of which exist to enforce the rule; everything else is
     * governed with no opt-out.
     */
    private static boolean isSweptShellScript(Path p) {
        String n = name(p);
        return !n.equals("wait-for.sh") && !n.equals("test-wait-for.sh");
    }

    /**
     * Every {@code while}/{@code until} loop whose CONDITION interrogates the process table without
     * going through {@code tools/wait-for.sh}.
     *
     * <p>Scoped to the condition, not the whole script, so a one-shot {@code pgrep} is left alone:
     * {@code check-client-ram.sh} and {@code client-test.sh} both probe deliberately and explain
     * themselves in place. The condition may span lines ({@code until ! pgrep -f X} … {@code do}),
     * so it is accumulated up to the {@code do} rather than read one line at a time.
     *
     * <p>Note what is NOT an exemption: excluding {@code $$}. Measured — a loop that filters its own
     * PID out of {@code pgrep} still hangs, because the CALLER's shell carries the pattern too and
     * is not {@code $$}. Only the helper is accepted.
     */
    private static List<Integer> selfMatchingWaitLoops(String text) {
        List<Integer> hits = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String head = stripFullLineComment(lines[i]).strip();
            if (!head.startsWith("while") && !head.startsWith("until")) {
                continue;
            }
            StringBuilder condition = new StringBuilder();
            for (int j = i; j < lines.length && j < i + 12; j++) {
                String l = stripFullLineComment(lines[j]);
                condition.append(l).append('\n');
                if (closesTheCondition(l)) {
                    break;
                }
            }
            String c = condition.toString();
            if (!probesTheProcessTable(c) || c.contains("wait-for.sh")) {
                continue;
            }
            hits.add(i + 1);
        }
        return hits;
    }

    /**
     * A whole-line comment contributes nothing executable — and every migrated call site now
     * carries a comment explaining the {@code pgrep} it no longer runs, so not stripping these
     * would make the rule fire on its own documentation. Inline {@code #} is deliberately left
     * alone: in shell it is only a comment after whitespace, and guessing wrong inside a quoted
     * string would lose real code.
     */
    private static String stripFullLineComment(String line) {
        return line.strip().startsWith("#") ? "" : line;
    }

    /**
     * Where the loop's condition ends and its body begins.
     *
     * <p>⚠ {@code ; do} ANYWHERE on the line, not only at the end. The first cut only matched a
     * trailing {@code do}, so the entirely ordinary one-liner
     * {@code until ! pgrep -f X; do sleep 20; done} never closed — the scan kept swallowing the
     * following lines, reached the {@code ./tools/wait-for.sh} call in the function below it, and
     * exempted itself. Measured: 's own loop pasted back into
     * {@code tools/archive-eval-runs.sh}, the sweep reported GREEN.
     *
     * <p>The lesson is about the guard, not the regex. {@code forbiddenSample} is a bare snippet
     * with nothing after it, so {@link #everyShellRuleActuallyCatchesTheCodeItForbids()} passed
     * throughout — a rot-guard proves the rule fires in ISOLATION and says nothing about whether
     * it fires IN SITU. The only thing that caught this was putting the defect back into a real
     * file and watching the build, which is why that is the required step and not an optional one.
     */
    private static boolean closesTheCondition(String line) {
        String t = line.strip();
        return t.equals("do") || t.endsWith(" do") || t.matches(".*;\\s*do(\\s.*)?$");
    }

    private static boolean probesTheProcessTable(String condition) {
        if (condition.contains("pgrep")) {
            return true;
        }
        boolean listsProcesses = condition.contains("ps -e") || condition.contains("ps -A")
                || condition.contains("ps aux") || condition.contains("ps -ef");
        return listsProcesses && condition.contains("grep");
    }

    /**
     * Does this file tell the agent how to CHANGE an artifact without naming the tool to call?
     *
     * <p>Deliberately narrow: it fires only on the passive "taken apart / re-glued" phrasing that
     * actually misled the agent, and only when no {@code modify_} verb appears anywhere in the same
     * file. A tool whose own result describes what IT is doing for you (ModifyTrainTool says "the
     * train is being put back down, edited, RE-GLUED") is correct and names itself, so it passes.
     */
    private static boolean saysHowToChangeSomethingWithoutNamingATool(String text) {
        boolean describesTheMechanism =
                text.contains("taken apart") || text.contains("RE-GLUED, and assembled again");
        return describesTheMechanism && !text.contains("modify_");
    }

    private static boolean inAgentTools(Path p) {
        return p.toString().replace('\\', '/').contains("/agent/tools/");
    }

    /**
     * Does this tool act on something already built, <b>in a world</b>?
     *
     * <p>The discriminator for the level rule, and it needs both halves. Reading a stored
     * origin/anchor alone is not enough: the {@code list_*} tools read anchors purely to print the
     * coordinates in a listing and never touch a {@link net.minecraft.server.level.ServerLevel} at
     * all, so there is no world for them to get wrong. Only a tool that reads a recorded position
     * <i>and</i> resolves a level has to resolve the <i>right</i> one.
     *
     * <p>(Both halves were learned by running this: the first version flagged three list tools.)
     */
    private static boolean readsARecordedPosition(Path p) {
        String text = read(p);
        boolean recalls = text.contains("originOf(") || text.contains("anchorOf(");
        boolean needsAWorld = text.contains("ServerLevel");
        return recalls && needsAWorld;
    }

    /**
     * A comment asserting something is currently broken, with no date and no issue reference.
     *
     * <p>Deliberately narrow. The target is the confident present-tense claim about a live defect
     * ("still fails", "does NOT fix", "known bug") — not every mention of the word bug, and not a
     * historical note, which is exactly what a date or an issue number turns it into.
     */
    private static boolean hasUndatedOpenBugClaim(String text) {
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (!t.startsWith("//") && !t.startsWith("*")) {
                continue;
            }
            String lower = t.toLowerCase(java.util.Locale.ROOT);
            boolean claimsOpen = lower.contains("still fails")
                    || lower.contains("still merges")
                    || lower.contains("still broken")
                    || lower.contains("known bug")
                    || lower.contains("outstanding bug")
                    || lower.contains("currently broken");
            if (!claimsOpen) {
                continue;
            }
            // A date (2026-…) or an issue reference makes it auditable rather than eternal.
            boolean auditable = lower.matches(".*\\b20\\d\\d-\\d\\d-\\d\\d\\b.*")
                    || lower.matches(".*#\\d+.*")
                    || lower.contains("used to")
                    || lower.contains("was false")
                    || lower.contains("disproved");
            if (!auditable) {
                return true;
            }
        }
        return false;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + p, e);
        }
    }

}
