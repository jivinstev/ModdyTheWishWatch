package com.moddingfromamod.mob;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.marshal.ArtifactAffinity;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Phase 0: make the failure visible before fixing it.</b>
 *
 * <p>The invariant these four tests exist to defend,:
 *
 * <blockquote>The Nth wish must never silently pick a random mob or mob group when the child says
 * "it", "him/her", or "they".</blockquote>
 *
 * <h2>How a rung picks its target here, and why that is not a fiction</h2>
 *
 * There is no model in the loop, so something has to stand in for "what would the agent send as
 * {@code modify_mob}'s target". {@link #asAFreshWorkerWould} does it by reading the <b>real
 * {@code look_around} report</b> — the actual text a worker is handed — and following the procedure
 * the tools themselves prescribe:
 *
 * <ol>
 *   <li>if the report says which thing the child is talking about, use that;</li>
 *   <li>else, if exactly one mob of the right kind carries a handle, use the handle — the schema
 *       calls a handle "one EXACT entity" and prefers it in as many words;</li>
 *   <li>else the type word, which {@code modify_mob}'s own schema offers and calls <i>"a
 *       guess"</i>.</li>
 * </ol>
 *
 * <p>So every branch is behaviour the mod instructs. What the tests measure is not the model's
 * judgement but <b>whether the mod leaves a fresh worker anything better than a guess</b>.
 *
 * <h2>The one thing that must NOT fix these</h2>
 *
 * A pool of three granters serves one child, and {@code ArtifactAffinity} is a routing preference
 * that expires after {@link ArtifactAffinity#HOLD_MS} to "anyone". Anything that remembers the
 * referent <i>on the worker that did it</i> is broken by construction.
 * {@link #theReferentSurvivesADifferentWorkerOnEveryRung} is the test that catches that: it clears
 * the affinity map between rungs and runs every rung under a different worker id.
 *
 * <h2>Test hygiene, all of it earned elsewhere in this repo</h2>
 *
 * <ul>
 *   <li>Every tool call is on a <b>background thread</b>: a {@code @GameTest} body runs on the
 *       server thread and {@code AgentBridge} hops back onto it, so joining here deadlocks.</li>
 *   <li><b>No mock player.</b> Every tool resolves "the child" as the first player in the list, and
 *       a GameTest world deliberately has none — {@code ItemEditGameTest.anItemIsRecognisedByTheCamera}
 *       asserts on reaching "nobody is holding it", so a mock player left in the world by a
 *       concurrent test turns that one red for no reason its own author could see. The first cut of
 *       this file did exactly that. So these work where {@code modify_mob} works with nobody online:
 *       at <b>world spawn</b>. "He walks twenty blocks" is staged by moving the <i>mobs</i>, which is
 *       the same thing to the distance sort that is actually under test.</li>
 *   <li>Each test uses a <b>different species</b>, because "the nearest panda" is a question about
 *       the whole world and tests in a batch run concurrently.</li>
 *   <li>Cleanup runs on <b>entry</b> as well as at the end: teardown at the end only happens
 *       when the test passes, and a leaked mock or a leaked mob becomes somebody else's mystery.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class MobIdentityGameTest {

    /**
     * The marker the report carries to name the thing the child is talking about.
     *
     * <p>Read from {@code Surroundings} rather than copied, so renaming it there cannot leave this
     * gate green while the agent is told nothing. Before Phase 1 nothing printed it, which was the
     * point: with no such line a worker arriving at rung 3 has only the mob list, and a mob list
     * cannot say <i>which</i> of two pandas the last wish was about — nor that the fox it was about
     * is dead rather than merely out of sight.
     */
    static final String TALKING_ABOUT = com.moddingfromamod.agent.Surroundings.TALKING_ABOUT;

    // ===================================================================================
    //  1. The unique boss — "summon a goat" → "make him bigger" → "make him even bigger"
    // ===================================================================================

    /**
     * The sequence that says already works for the child. Asserted anyway, because it is the
     * control: if this one ever goes red, the sharper three below are measuring nothing.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void aSummonedMobIsTheSameMobOnAllThreeRungs(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.GOAT);
        Vec3 spawn = worldSpawn(level);

        // Rung 1 — "summon a goat", through the path run_command really uses: diff the entities
        // near the child before and after, and hand a handle to whatever is new.
        var before = EntityCapture.snapshot(level, spawn);
        LivingEntity goat = place(level, EntityType.GOAT, spawn.add(4, 0, 0));
        EntityCapture.rememberNew(level, spawn, before);

        AtomicReference<Ladder> out = new AtomicReference<>();
        ladder(out, l -> {
            l.rung(2, asAFreshWorkerWould("goat"), "\"scale\":2.0");
            l.rung(3, asAFreshWorkerWould("goat"), "\"scale\":4.0");
        });

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertFalse(l.failed(), Component.literal(l.why()));
            helper.assertTrue(goat.getAttributeValue(Attributes.SCALE) > 3.0,
                    Component.literal("all three rungs must be about the SAME goat — it ended at ×"
                            + goat.getAttributeValue(Attributes.SCALE) + ". " + l.trace()));
            done(helper, goat);
        });
    }

    // ===================================================================================
    //  2. The herd — the referent physically walks away, and another one is nearer
    // ===================================================================================

    /**
     * <b>The sharp one.</b> "Make that panda huge" is about a panda that was <i>already in the
     * world</i> — nobody summoned it, so nothing ever minted a handle for it. That is the majority
     * case and it is the one describes: rung 3 resolves to whichever panda is nearest NOW.
     *
     * <p>The mob a wish acted on has to become addressable BY THAT ACT, or the second half of every
     * ladder is a distance sort.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void theMobHePointedAtStaysTheMobEvenWhenAnotherIsNearer(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.PANDA);
        Vec3 spawn = worldSpawn(level);

        // His panda. Deliberately NOT registered: he pointed at one that was already there.
        LivingEntity his = place(level, EntityType.PANDA, spawn.add(4, 0, 0));
        AtomicReference<LivingEntity> decoyRef = new AtomicReference<>();

        AtomicReference<Ladder> out = new AtomicReference<>();
        ladder(out, l -> {
            l.rung(2, asAFreshWorkerWould("panda"), "\"scale\":2.0");
            // …the child walks twenty blocks, and another panda is standing nearer than his.
            AgentBridge.onServer(() -> {
                his.teleportTo(spawn.x + 20, spawn.y, spawn.z);
                decoyRef.set(place(level, EntityType.PANDA, spawn.add(3, 0, 0)));
                return null;
            });
            l.rung(3, asAFreshWorkerWould("panda"), "\"scale\":4.0");
        });

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertFalse(l.failed(), Component.literal(l.why()));
            LivingEntity decoy = decoyRef.get();
            helper.assertTrue(decoy != null, Component.literal("waiting for the second panda"));
            helper.assertTrue(his.getAttributeValue(Attributes.SCALE) > 3.0,
                    Component.literal("HIS panda must be the one that grew — it is ×"
                            + his.getAttributeValue(Attributes.SCALE) + " and the panda that "
                            + "happened to be nearer is ×" + decoy.getAttributeValue(Attributes.SCALE)
                            + ". " + l.trace()));
            helper.assertTrue(decoy.getAttributeValue(Attributes.SCALE) < 1.5,
                    Component.literal("the panda he never mentioned must be untouched, it is ×"
                            + decoy.getAttributeValue(Attributes.SCALE) + ". " + l.trace()));
            done(helper, his, decoy);
        });
    }

    // ===================================================================================
    //  3. The subject dies — "he's gone" is the CORRECT answer
    // ===================================================================================

    /**
     * No binding scheme fixes death, so the durable id has to be able to say so. Silently growing a
     * different fox is the invariant's sharpest failure: the child asked about one animal and a
     * different one changed, with a cheerful success message either way.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void whenTheMobDiesTheAnswerIsHeIsGoneAndNotAnotherFox(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.FOX);
        Vec3 spawn = worldSpawn(level);

        LivingEntity his = place(level, EntityType.FOX, spawn.add(4, 0, 0));
        AtomicReference<LivingEntity> otherRef = new AtomicReference<>();

        AtomicReference<Ladder> out = new AtomicReference<>();
        ladder(out, l -> {
            l.rung(2, asAFreshWorkerWould("fox"), "\"scale\":2.0");
            // …it burns at sunrise, and another fox wanders up.
            AgentBridge.onServer(() -> {
                his.discard();
                otherRef.set(place(level, EntityType.FOX, spawn.add(3, 0, 0)));
                return null;
            });
            l.rung(3, asAFreshWorkerWould("fox"), "\"scale\":4.0");
        });

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertTrue(l.done(), Component.literal("waiting for the ladder"));
            LivingEntity other = otherRef.get();
            helper.assertTrue(other != null, Component.literal("waiting for the second fox"));
            helper.assertTrue(other.getAttributeValue(Attributes.SCALE) < 1.5,
                    Component.literal("a DIFFERENT fox must not silently grow when the one he meant is dead — it is ×"
                            + other.getAttributeValue(Attributes.SCALE) + ". " + l.trace()));
            helper.assertTrue(l.replies.size() >= 2, Component.literal("both rungs should have answered. " + l.trace()));
            String rung3 = l.replies.get(1);
            helper.assertTrue(rung3.toLowerCase(java.util.Locale.ROOT).contains("gone"),
                    Component.literal("rung 3 must say he is GONE rather than report a success about somebody else: \""
                            + rung3 + "\". " + l.trace()));
            done(helper, other);
        });
    }

    // ===================================================================================
    //  4. The pool — three granters, and the fix must not be worker affinity in disguise
    // ===================================================================================

    /**
     * <b>The test that proves the fix is world-side.</b> Every rung runs under a different worker
     * id, and the trace records what {@code ArtifactAffinity} — a routing preference that expires
     * to "anyone" after three minutes — has to say about the thing rung 3 addressed, which is
     * nothing. If the referent only survives when one worker handles the whole ladder, the identity
     * is being carried on the worker and is wrong.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void theReferentSurvivesADifferentWorkerOnEveryRung(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.LLAMA);
        Vec3 spawn = worldSpawn(level);

        LivingEntity his = place(level, EntityType.LLAMA, spawn.add(4, 0, 0));
        AtomicReference<LivingEntity> decoyRef = new AtomicReference<>();

        AtomicReference<Ladder> out = new AtomicReference<>();
        ladder(out, l -> {
            l.rung(2, AgentContext.with("mobid-pool-w2", () -> asAFreshWorkerWould("llama")),
                    "\"scale\":2.0", "mobid-pool-w2");
            AgentBridge.onServer(() -> {
                his.teleportTo(spawn.x + 20, spawn.y, spawn.z);
                decoyRef.set(place(level, EntityType.LLAMA, spawn.add(3, 0, 0)));
                return null;
            });
            l.rung(3, AgentContext.with("mobid-pool-w3", () -> asAFreshWorkerWould("llama")),
                    "\"scale\":4.0", "mobid-pool-w3");
            // Whatever rung 3 addressed the llama as, ArtifactAffinity knows nothing about it —
            // recorded rather than assumed, so "the referent came from the routing table" can be
            // ruled out from the trace instead of by reasoning about it.
            l.affinityOwnerOfTarget = ArtifactAffinity.global().ownerOf(l.targets.get(1));
        });

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertFalse(l.failed(), Component.literal(l.why()));
            LivingEntity decoy = decoyRef.get();
            helper.assertTrue(decoy != null, Component.literal("waiting for the second llama"));
            // A THIRD worker, which has never asked anything, must reach the same conclusion. This
            // is the assertion that a per-worker memory cannot satisfy.
            helper.assertTrue(his.getAttributeValue(Attributes.SCALE) > 3.0,
                    Component.literal("a worker that never saw rung 2 must still land on the same llama — his is ×"
                            + his.getAttributeValue(Attributes.SCALE) + ", the nearer one is ×"
                            + decoy.getAttributeValue(Attributes.SCALE) + ". " + l.trace()));
            helper.assertFalse(EntityHandles.LAST.equals(l.targets.get(1)),
                    Component.literal("'@last' is per-run, so it can never be the answer for a worker that made "
                            + "nothing: " + l.trace()));
            helper.assertTrue(l.affinityOwnerOfTarget.isEmpty(),
                    Component.literal("the referent must not be coming from the routing table — ArtifactAffinity "
                            + "claims \"" + l.affinityOwnerOfTarget + "\" owns it. " + l.trace()));
            done(helper, his, decoy);
        });
    }

    // ===================================================================================
    //  5. …and it is still true after lunch
    // ===================================================================================

    /**
     * <b>The name is on disk, written by the real path and read by the real parser.</b>
     *
     * <p>Handles used to live only as long as the server, justified on the grounds that the agent's
     * context resets per run too. That is true of the agent and false of the child: he closes the
     * game, comes back in the evening and says "make him even bigger" about the same animal. A
     * ladder that spans lunch degrades to exactly the herd failure above.
     *
     * <p>Loaded into a <b>private</b> table rather than back over the global one, because these
     * tests run concurrently with everything else and {@code EntityHandles.global()} is shared —
     * reloading it here would delete another test's handles mid-assertion. What has to be proven is
     * that the file the real tool wrote is a file the real parser can read, and that is provable
     * without touching the world's own copy.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void theNameIsOnDiskAndSurvivesTheWorldClosing(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.TURTLE);
        Vec3 spawn = worldSpawn(level);
        LivingEntity turtle = place(level, EntityType.TURTLE, spawn.add(4, 0, 0));

        AtomicReference<Ladder> out = new AtomicReference<>();
        ladder(out, l -> l.rung(2, "turtle", "\"scale\":2.0"));

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertFalse(l.failed(), Component.literal(l.why()));
            var server = level.getServer();
            MobHandleStore.save(server);                     // what every change already does

            java.nio.file.Path file = MobHandleStore.file(server);
            helper.assertTrue(java.nio.file.Files.isRegularFile(file),
                    Component.literal("nothing was written to " + file + ". " + l.trace()));
            EntityHandles reloaded = new EntityHandles();
            try {
                var root = com.google.gson.JsonParser.parseString(
                        java.nio.file.Files.readString(file,
                                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                helper.assertTrue(root.has("version"),
                        Component.literal("an unversioned file cannot be identified later: " + root));
                reloaded.loadJson(root);
            } catch (java.io.IOException | RuntimeException e) {
                helper.fail(Component.literal("the file the real path wrote could not be read back: " + e));
                return;
            }
            String name = reloaded.handleFor(turtle.getUUID());
            helper.assertTrue(name != null,
                    Component.literal("the turtle a wish changed has no name after a reload — every ladder that "
                            + "spans a session is back to a distance sort. " + l.trace()));
            helper.assertTrue(reloaded.referent().isPresent()
                            && reloaded.referent().get().lastTargetedMs() > 0,
                    Component.literal("…and WHEN it was last changed must survive too, or nothing can say which of "
                            + "them he is talking about. " + l.trace()));
            done(helper, turtle);
        });
    }

    // ===================================================================================
    //  6. a mob is a real artifact — so a ladder can be SCORED, and undone
    // ===================================================================================

    /**
     * <b>The seam gets an artifact id, which is what makes a mob ladder measurable at all.</b>
     *
     * <p>an earlier issue's "works for the child, unmeasurable for us": {@code MadeKind.forTool} mapped nothing
     * for {@code modify_mob}, so {@code ArtifactAffinity} found no owner, every rung routed
     * {@code anyone}, and three wishes about one animal were indistinguishable in the log from three
     * wishes about nothing in particular. {@code modify_mob} has no id argument to read, so it
     * reports one — this asserts both halves of what {@code McpProtocol} then does with it.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void aChangedMobIsRecordedAsSomethingTheWishWasAbout(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.SNIFFER);
        Vec3 spawn = worldSpawn(level);
        LivingEntity sniffer = place(level, EntityType.SNIFFER, spawn.add(4, 0, 0));

        AtomicReference<String> reported = new AtomicReference<>();
        AtomicReference<Ladder> out = new AtomicReference<>();
        ladder(out, l -> {
            ModifyMobTool tool = new ModifyMobTool();
            l.targets.add("sniffer");
            l.workers.add("(none)");
            ToolResult r = tool.call(JsonParser.parseString(
                    "{\"scope\":\"one\",\"target\":\"sniffer\",\"scale\":2.0}").getAsJsonObject());
            l.replies.add(r == null ? "(no reply)" : r.text());
            reported.set(tool.lastMadeId());       // exactly what the dispatch seam reads
        });

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertFalse(l.failed(), Component.literal(l.why()));
            helper.assertTrue(com.moddingfromamod.marshal.MadeKind.MOB.equals(
                            com.moddingfromamod.marshal.MadeKind.forTool("modify_mob")),
                    Component.literal("modify_mob must make an artifact, or nothing about a mob ladder is scoreable"));
            String id = reported.get();
            helper.assertTrue(id != null && id.startsWith("@"),
                    Component.literal("the tool must report WHICH mob it changed — it said " + id + ". " + l.trace()));
            helper.assertTrue(EntityHandles.global().resolve(id).isPresent()
                            && EntityHandles.global().resolve(id).get().id()
                                .equals(sniffer.getUUID()),
                    Component.literal("…and it must be the one it actually changed. " + l.trace()));
            done(helper, sniffer);
        });
    }

    /**
     * <b>Undo puts the animal back rather than deleting it.</b>
     *
     * <p>{@code MadeKind}'s own rule is that a kind recorded but never reversed makes the
     * {@code [undo]} button lie. A mob is the one kind whose inverse is not a deletion — it was
     * standing there before the wish — so the reversal is the numbers recorded at the moment they
     * were overwritten, and this is the gate that says they really were.
     */
    // Its own batch, as aChangedMobIsSomethingHeCouldMeanByHim has. It failed 8 times in ~1,060
    // runs, always inside defaultBatch and never alone: "the wish should have grown it first", with both rungs
    // reporting the camel changed (x2, then x4) while the camel this test holds stayed under x3 for 900 ticks.
    // In that batch it shares world spawn — the same block, spawn+(4,0,0) — with ~15 other tests' animals, the
    // global handle table those tests write, and the marshal, where it is the ONLY live run: AgentContext's
    // single-live-run fallback then hands every sibling's worker-less modify_mob call THIS wish. Which of those
    // reaches the camel is not proven (whichCamels below now says which camel grew); a named batch runs with
    // nothing else standing at world spawn, which is how CreatureResolverGameTest keeps the same spot clean.
    @GameTest(template = "empty_test", timeoutTicks = 900, batch = "mobid_undo")
    public static void undoingAMobWishPutsTheAnimalBack(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.CAMEL);
        Vec3 spawn = worldSpawn(level);
        LivingEntity camel = place(level, EntityType.CAMEL, spawn.add(4, 0, 0));
        double sizeBefore = camel.getAttributeValue(Attributes.SCALE);

        var marshal = com.moddingfromamod.marshal.Marshal.global();
        java.util.UUID wishId = java.util.UUID.randomUUID();
        java.util.UUID child = java.util.UUID.nameUUIDFromBytes("mobid-undo".getBytes());
        String worker = "mobid-undo-w1";
        marshal.submit(wishId, child, "make the camel huge");
        var run = marshal.assign(worker, wishId);

        AtomicReference<Ladder> out = new AtomicReference<>();
        ladder(out, l -> {
            // TWO rungs in one wish, because undoing to the halfway point is the failure this
            // records against: it must go back to the size the camel actually was.
            l.rung(2, "camel", "\"scale\":2.0", worker);
            l.rung(3, "camel", "\"scale\":4.0", worker);
        });

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertFalse(l.failed(), Component.literal(l.why()));
            helper.assertTrue(camel.getAttributeValue(Attributes.SCALE) > 3.0,
                    Component.literal("the wish should have grown it first. " + l.trace()
                            + "\n" + whichCamels(level, spawn, camel)));

            String handle = EntityHandles.global().handleFor(camel.getUUID());
            helper.assertTrue(handle != null, Component.literal("the camel has no handle to undo. " + l.trace()));
            run.made(com.moddingfromamod.marshal.MadeKind.MOB, handle);   // what the seam records

            var result = com.moddingfromamod.wish.WishUndo.undo(level.getServer(), run);
            double after = camel.getAttributeValue(Attributes.SCALE);
            marshal.finish(wishId);                 // before the assertions — see the note above
            helper.assertTrue(result.reversed() == 1,
                    Component.literal("the mob edit should have been reversed, undo said: " + result.couldNot()));
            helper.assertTrue(Math.abs(after - sizeBefore) < 0.001,
                    Component.literal("it must go back to ×" + sizeBefore + ", not to the halfway point — it is ×"
                            + after + ". " + l.trace()));
            done(helper, camel);
        });
    }

    /**
     * <b>The ladder can now be handed the animal.</b>
     *
     * <p>{@code ArtifactIndex} enumerated species, buildings, machines, trains and items and no
     * mobs, so {@code WishResolver} had nothing to resolve "make him even bigger" TO: it returned
     * NONE, the child was told nothing and routing recorded no artifact.
     *
     * <p>The player here is constructed but deliberately <b>never placed in the player list</b>.
     * {@code ArtifactIndex} only reads its level, position and look direction, and a player in that
     * list is "the child" for every other test running at the same time — which is how the first cut
     * of this file turned {@code ItemEditGameTest.anItemIsRecognisedByTheCamera} red from a distance.
     */
    // Its own batch: ArtifactIndex offers the MAX_MOB_CANDIDATES most recently targeted
    // mobs WORLD-wide, so in defaultBatch every concurrent modify_mob (this class's and
    // ModifyMobGameTest's) could push the axolotl out between its rung and the assertion.
    @GameTest(template = "empty_test", timeoutTicks = 900, batch = "mobid_candidates")
    public static void aChangedMobIsSomethingHeCouldMeanByHim(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.AXOLOTL);
        Vec3 spawn = worldSpawn(level);
        LivingEntity axolotl = place(level, EntityType.AXOLOTL, spawn.add(4, 0, 0));

        AtomicReference<Ladder> out = new AtomicReference<>();
        ladder(out, l -> l.rung(2, "axolotl", "\"scale\":2.0"));

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertFalse(l.failed(), Component.literal(l.why()));
            String handle = EntityHandles.global().handleFor(axolotl.getUUID());
            helper.assertTrue(handle != null, Component.literal("no handle to offer. " + l.trace()));

            ServerPlayer looker = unplacedPlayer(level, spawn);
            var candidates = com.moddingfromamod.wish.ArtifactIndex.forPlayer(looker);
            var mine = candidates.stream()
                    .filter(c -> handle.equals(c.id())).findFirst().orElse(null);
            helper.assertTrue(mine != null,
                    Component.literal("the axolotl a wish changed is not one of the things \"him\" can mean — the "
                            + "ladder has " + candidates.size() + " candidate(s) and none is it. "
                            + l.trace()));
            helper.assertTrue("mob".equals(mine.kind()), Component.literal("…and it should say what kind it is"));
            helper.assertTrue(mine.aliases().contains("axolotl"),
                    Component.literal("…and answer to the word a child would say, not to \"" + handle + "\": "
                            + mine.aliases()));
            helper.assertTrue(mine.distance() >= 0 && mine.distance() < 32,
                    Component.literal("…and know where it is, or every distance rung is guessing: " + mine.distance()));
            done(helper, axolotl);
        });
    }

    /**
     * A {@link ServerPlayer} that exists but is not in the player list.
     *
     * <p>Enough for anything that only reads where a player is and what they are looking at, and
     * invisible to every tool that resolves "the child" as the first player online.
     */
    private static ServerPlayer unplacedPlayer(ServerLevel level, Vec3 at) {
        var profile = new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes("mobid-looker".getBytes()), "mobid-looker");
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        ServerPlayer p = new ServerPlayer(level.getServer(), level, profile,
                cookie.clientInformation());
        p.snapTo(at.x, at.y, at.z, 0, 0);
        return p;
    }

    // ===================================================================================
    //  7. — deciding WHICH READING, and being seen to refuse
    // ===================================================================================

    /**
     * <b>The scope cannot be skipped, and the refusal helps rather than stonewalls.</b>
     *
     * <p>Category and group produced byte-identical behaviour and an individual differed only by a
     * count, so nothing recorded which of the three he meant. An optional field would not have
     * fixed that — this repo measured a `run_command` list parameter used ZERO times in 752 calls —
     * so it is required, and the error tells the agent what his words read as so the retry is one
     * step rather than a guess.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void aMobWishMustSayHowManyAnimalsItMeans(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.BEE);
        Vec3 spawn = worldSpawn(level);
        LivingEntity bee = place(level, EntityType.BEE, spawn.add(4, 0, 0));

        AtomicReference<ToolResult> out = new AtomicReference<>();
        offThread(() -> out.set(new ModifyMobTool().call(JsonParser.parseString(
                "{\"target\":\"bee\",\"scale\":2.0}").getAsJsonObject())));

        helper.succeedWhen(() -> {
            ToolResult r = out.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            helper.assertTrue(r.isError(), Component.literal("a wish with no scope must be refused: " + r.text()));
            helper.assertTrue(r.text().contains("scope"), Component.literal("…and say what is missing: " + r.text()));
            helper.assertTrue(r.text().contains("one"),
                    Component.literal("…and what his words read as, or the retry is another guess: " + r.text()));
            helper.assertTrue(Math.abs(bee.getAttributeValue(Attributes.SCALE) - 1.0) < 0.001,
                    Component.literal("and nothing may have happened to the bee"));
            done(helper, bee);
        });
    }

    /**
     * <b>an earlier issue's expensive mistake, refused — and this is the gate that has been SEEN to refuse.</b>
     *
     * <p>"Make him invincible" about one pet, read as a category, makes every zombie in the world
     * invincible forever and nothing in the game shows him why. A guard nobody has watched fire is
     * decorative, so this drives a real wish whose text says "him" and asserts the tool says no.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void claimingTheWholeSpeciesAboutOneAnimalIsRefused(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.RABBIT);
        Vec3 spawn = worldSpawn(level);
        LivingEntity rabbit = place(level, EntityType.RABBIT, spawn.add(4, 0, 0));

        var marshal = com.moddingfromamod.marshal.Marshal.global();
        java.util.UUID wishId = java.util.UUID.randomUUID();
        String worker = "mobid-scope-w1";
        marshal.submit(wishId, java.util.UUID.nameUUIDFromBytes("mobid-scope".getBytes()),
                "make him bigger");
        marshal.assign(worker, wishId);

        AtomicReference<ToolResult> wide = new AtomicReference<>();
        AtomicReference<ToolResult> narrow = new AtomicReference<>();
        offThread(() -> {
            wide.set(com.moddingfromamod.agent.AgentContext.with(worker,
                    () -> new ModifyMobTool().call(JsonParser.parseString(
                        "{\"scope\":\"category\",\"target\":\"rabbit\",\"scale\":2.0}")
                            .getAsJsonObject())));
            // The A/B: the SAME call, read narrowly, must go through. A guard that refuses
            // everything is indistinguishable from a broken tool.
            narrow.set(com.moddingfromamod.agent.AgentContext.with(worker,
                    () -> new ModifyMobTool().call(JsonParser.parseString(
                        "{\"scope\":\"one\",\"target\":\"rabbit\",\"scale\":2.0}")
                            .getAsJsonObject())));
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(narrow.get() != null, Component.literal("waiting for both calls"));
            marshal.finish(wishId);                 // before the assertions — see the note above
            helper.assertTrue(wide.get().isError(),
                    Component.literal("a category claim about \"make him bigger\" must be refused: "
                            + wide.get().text()));
            helper.assertTrue(wide.get().text().contains("ASK") || wide.get().text().contains("ask"),
                    Component.literal("…and the way out is to ask him, not to give up: " + wide.get().text()));
            helper.assertFalse(narrow.get().isError(),
                    Component.literal("…while the narrow reading of the same wish goes straight through: "
                            + narrow.get().text()));
            helper.assertTrue(rabbit.getAttributeValue(Attributes.SCALE) > 1.5,
                    Component.literal("and the narrow one really did it"));
            done(helper, rabbit);
        });
    }

    /**
     * <b>A whole-species wish is sent to the datapack tier, and the recipe it is sent with
     * compiles.</b>
     *
     * <p>: this tool reaches at most 20 mobs within 64 blocks that are alive right now, so
     * answering "make zombies bigger" here looks right and then decays — those twenty burn at
     * sunrise and the wish stays in his list marked granted. A {@code clock tick} rule is
     * re-evaluated forever, which covers mobs that do not exist yet with no spawn hook at all.
     *
     * <p>The recipe is <b>compiled</b> rather than eyeballed. Advice the agent is given is code the
     * agent will paste, and advice that has quietly stopped compiling sends it round a retry loop
     * against a snippet the mod itself suggested. Skips when the compiler is not on this machine,
     * so a clean checkout stays green.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void awholeSpeciesWishGoesToADatapackWithARecipeThatCompiles(GameTestHelper helper) {
        var marshal = com.moddingfromamod.marshal.Marshal.global();
        java.util.UUID wishId = java.util.UUID.randomUUID();
        String worker = "mobid-cat-w1";
        marshal.submit(wishId, java.util.UUID.nameUUIDFromBytes("mobid-cat".getBytes()),
                "make zombies bigger");
        marshal.assign(worker, wishId);

        AtomicReference<ToolResult> out = new AtomicReference<>();
        offThread(() -> out.set(com.moddingfromamod.agent.AgentContext.with(worker,
                () -> new ModifyMobTool().call(JsonParser.parseString(
                    "{\"scope\":\"category\",\"target\":\"zombie\",\"scale\":2.0}")
                        .getAsJsonObject()))));

        helper.succeedWhen(() -> {
            ToolResult r = out.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            // BEFORE the assertions, not after. An assertion failure throws out of this lambda, so
            // a finish() below it only runs when the test PASSES — and a wish left live in the
            // shared Marshal becomes "the current wish" for every other test in the run. Measured:
            // one red test here turned three unrelated provenance tests red with
            // "manifest intent must be the wish, got: make zombies bigger". Same shape as the
            // mock-player leak, and the same fix: clean up on a path that cannot be skipped.
            marshal.finish(wishId);
            helper.assertTrue(r.isError(), Component.literal("it must not pretend to keep a forever-wish: " + r.text()));
            helper.assertTrue(r.text().contains("author_datapack"),
                    Component.literal("…and must say where the wish actually goes: " + r.text()));
            helper.assertTrue(r.text().contains("clock tick"),
                    Component.literal("…with the mechanism that covers mobs that do not exist yet: " + r.text()));

            if (!com.moddingfromamod.datapack.CbScriptCompiler.available()) {
                // LOUDLY. A skip that reads like a pass is how a gate ends up proving nothing for
                // months — the same failure the ratchet shouts about for the Create jar.
                System.out.println("MOB CATEGORY RECIPE: SKIPPED — no CBScript compiler on this "
                        + "machine, so the rule we tell the agent to write was NOT compiled. Set "
                        + "CBSCRIPT_DIR to actually check it.");
                helper.succeed();
                return;
            }
            try {
                java.nio.file.Path work = java.nio.file.Files.createTempDirectory("mobid-rule");
                var compiled = com.moddingfromamod.datapack.CbScriptCompiler.compile(work,
                        "mobid_rule_check", "every zombie is big, forever",
                        ModifyMobTool.categoryRuleRecipe("zombie"),
                        work.resolve("mobid_rule_check.zip"));
                helper.assertTrue(compiled.ok(),
                        Component.literal("the rule we TELL the agent to write must compile: " + compiled.failureSummary()));
                // AND THAT ITS IDS ARE REAL, which compiling cannot tell you. CBScript compiles any
                // command text at all; Minecraft rejects a bad id at PACK LOAD and simply drops the
                // function, so the pack reports enabled and the rule does nothing. Measured: the
                // recipe said `minecraft:scale`, which is 1.21.2's name — this gate was green, the
                // compile was green, and only a real client found it. The registry is the oracle.
                helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE
                                .containsKey(net.minecraft.resources.Identifier.parse(
                                        ModifyMobTool.CATEGORY_RULE_ATTRIBUTE)),
                        Component.literal("the recipe names an attribute this version does not have: "
                                + ModifyMobTool.CATEGORY_RULE_ATTRIBUTE
                                + " — the pack will load, report enabled, and do nothing."));
                System.out.println("MOB CATEGORY RECIPE: compiled for real by the CBScript "
                        + "compiler — the advice we give the agent is advice that works.");
            } catch (java.io.IOException e) {
                helper.fail(Component.literal("could not run the compiler: " + e));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>Two animals of one kind already changed: ask, never pick.</b>
     *
     * <p>Picking one of them would be the distance sort again, wearing the referent's clothes.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void twoOfTheSameKindAlreadyChangedMeansAskHim(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.OCELOT);
        Vec3 spawn = worldSpawn(level);
        LivingEntity first = place(level, EntityType.OCELOT, spawn.add(4, 0, 0));
        LivingEntity second = place(level, EntityType.OCELOT, spawn.add(8, 0, 0));

        AtomicReference<Ladder> out = new AtomicReference<>();
        ladder(out, l -> {
            // Establish two of them: the first by walking to the nearest, the second by name.
            l.rung(2, "ocelot", "\"scale\":2.0");
            String other = AgentBridge.onServer(() ->
                    EntityHandles.global().targeted(second.getUUID(), "minecraft:ocelot", null,
                            "mobid-two", "modify_mob", "size ×2", null));
            l.targets.add(other);
            l.workers.add("(none)");
            l.replies.add("(established " + other + " directly)");
            // …and now a bare type word, which can no longer mean either of them.
            l.rung(4, "ocelot", "\"scale\":8.0");
        });

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertFalse(l.failed(), Component.literal(l.why()));
            String last = l.replies.get(l.replies.size() - 1);
            helper.assertTrue(last.contains("Which one"),
                    Component.literal("with two changed ocelots, a bare \"ocelot\" must ask: " + last));
            helper.assertTrue(first.getAttributeValue(Attributes.SCALE) < 4.0
                            && second.getAttributeValue(Attributes.SCALE) < 4.0,
                    Component.literal("…and must not have quietly picked one: " + first.getAttributeValue(
                            Attributes.SCALE) + " / " + second.getAttributeValue(Attributes.SCALE)
                            + ". " + l.trace()));
            done(helper, first, second);
        });
    }

    /**
     * <b>He really does mean a different one: {@code @nearest} is the way to say so.</b>
     *
     * <p>Without an escape the referent becomes a trap. A child who means a DIFFERENT donkey is
     * talking about one that has no name yet, so there has to be a way to say "no, the one in front
     * of him" — and it is a token the agent cannot type by accident, so it lands in the log rather
     * than happening by default.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900, batch = "mobid_nearest")
    public static void thereIsAWayToSayHeMeansADifferentOne(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.MULE);
        Vec3 spawn = worldSpawn(level);
        // @nearest is "whichever mob is nearest", of ANY kind, so this is the one test here whose
        // answer depends on every other animal at world spawn: in defaultBatch,
        // ModifyMobGameTest's sheep stood ON spawn, @nearest grew it to ×5 instead of this mule,
        // and both tests went red. Hence its own batch (nothing runs beside it), and the stand
        // cleared of whatever earlier batches left there — that is safe only BECAUSE of the batch.
        for (LivingEntity e : com.moddingfromamod.mob.Creatures.nearby(level, spawn, 8)) {
            e.discard();
        }
        LivingEntity his = place(level, EntityType.MULE, spawn.add(20, 0, 0));
        LivingEntity other = place(level, EntityType.MULE, spawn.add(3, 0, 0));

        AtomicReference<Ladder> out = new AtomicReference<>();
        ladder(out, l -> {
            // Establish the far one as the referent, by name.
            String name = AgentBridge.onServer(() ->
                    EntityHandles.global().targeted(his.getUUID(), "minecraft:mule", null,
                            "mobid-near", "modify_mob", "size ×2", null));
            l.targets.add(name);
            l.workers.add("(none)");
            l.replies.add("(established " + name + ")");
            l.rung(3, "@nearest", "\"scale\":5.0");
        });

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertFalse(l.failed(), Component.literal(l.why()));
            helper.assertTrue(other.getAttributeValue(Attributes.SCALE) > 4.0,
                    Component.literal("@nearest must reach the one standing in front of him — it is ×"
                            + other.getAttributeValue(Attributes.SCALE) + ". " + l.trace()));
            helper.assertTrue(his.getAttributeValue(Attributes.SCALE) < 4.0,
                    Component.literal("…and must leave the referent alone. " + l.trace()));
            done(helper, his, other);
        });
    }

    // ===================================================================================
    //  8. — a wish must never be answered BACKWARDS
    // ===================================================================================

    /**
     * <b>"Make him bigger", answered with a smaller mob, is refused — and the right answer is not.</b>
     *
     * <p>an earlier issue's actual defect. This tool takes ABSOLUTE numbers, so "faster" means inventing one,
     * and a low guess makes the mob slower while the wish said faster: <i>"not merely unanswered; it
     * is answered backwards, and nothing in the world or the log says so."</i> Reading fixed half of
     * it — every field is in {@code look_around} now — but an agent can read 0.25 and write 0.2.
     *
     * <p>Both arms in one test, because a guard that refuses everything is indistinguishable from a
     * broken tool.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void makingHimBiggerMayNotMakeHimSmaller(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.FROG);
        Vec3 spawn = worldSpawn(level);
        LivingEntity frog = place(level, EntityType.FROG, spawn.add(4, 0, 0));
        // SIZE, not speed, even though an earlier issue's headline example is "make him faster". Every mob
        // starts at scale 1.0 against a ceiling of 8, whereas a frog's base MOVEMENT_SPEED is 1.0
        // and MobTweak clamps speed to 0.6 — so on a frog NO legal speed is faster, and the first
        // cut of this test asserted that the tool should accept one. The rule is field-agnostic
        // (MobDirectionTest covers speed); what this gate proves is that it fires and that the
        // right answer still goes through.
        double sizeBefore = frog.getAttributeValue(Attributes.SCALE);

        var marshal = com.moddingfromamod.marshal.Marshal.global();
        java.util.UUID wishId = java.util.UUID.randomUUID();
        String worker = "mobid-dir-w1";
        marshal.submit(wishId, java.util.UUID.nameUUIDFromBytes("mobid-dir".getBytes()),
                "make him bigger");
        marshal.assign(worker, wishId);

        AtomicReference<ToolResult> down = new AtomicReference<>();
        AtomicReference<ToolResult> up = new AtomicReference<>();
        offThread(() -> {
            down.set(com.moddingfromamod.agent.AgentContext.with(worker,
                    () -> new ModifyMobTool().call(JsonParser.parseString(
                        "{\"scope\":\"one\",\"target\":\"frog\",\"scale\":0.5}")
                            .getAsJsonObject())));
            up.set(com.moddingfromamod.agent.AgentContext.with(worker,
                    () -> new ModifyMobTool().call(JsonParser.parseString(
                        "{\"scope\":\"one\",\"target\":\"frog\",\"scale\":4.0}")
                            .getAsJsonObject())));
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(up.get() != null, Component.literal("waiting for both calls"));
            marshal.finish(wishId);            // before the assertions — a red test must not leak it
            helper.assertTrue(down.get().isError(),
                    Component.literal("asked BIGGER and given a smaller size than it already had — that must be "
                            + "refused: " + down.get().text()));
            helper.assertTrue(down.get().text().contains(String.valueOf(sizeBefore)),
                    Component.literal("…and the refusal must carry the CURRENT number, or the retry is another "
                            + "guess: " + down.get().text()));
            helper.assertFalse(up.get().isError(),
                    Component.literal("…while a bigger size goes straight through: " + up.get().text()));
            helper.assertTrue(frog.getAttributeValue(Attributes.SCALE) > sizeBefore,
                    Component.literal("and the frog really did grow, from " + sizeBefore + " to "
                            + frog.getAttributeValue(Attributes.SCALE)));
            done(helper, frog);
        });
    }

    /**
     * <b>The seventh field: glow is readable now too.</b>
     *
     * <p>Six of the seven things {@code modify_mob} can write were reported already; glow was the
     * one left, and it is where writing an absolute over an unread value shows most — "make him a
     * different colour" answered without knowing he is already pink can repaint him pink.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void aGlowingMobSaysWhatColourItIsAlreadyGlowing(GameTestHelper helper) {
        ServerLevel level = arrive(helper, EntityType.PARROT);
        Vec3 spawn = worldSpawn(level);
        LivingEntity parrot = place(level, EntityType.PARROT, spawn.add(4, 0, 0));

        AtomicReference<Ladder> out = new AtomicReference<>();
        AtomicReference<String> report = new AtomicReference<>();
        ladder(out, l -> {
            l.rung(2, "parrot", "\"glow\":\"pink\"");
            report.set(AgentBridge.onServer(() -> {
                var over = AgentBridge.serverOrNull().overworld();
                return com.moddingfromamod.agent.Surroundings.describe(over, worldSpawn(over));
            }));
        });

        helper.succeedWhen(() -> {
            Ladder l = waitFor(helper, out);
            helper.assertFalse(l.failed(), Component.literal(l.why()));
            helper.assertTrue(parrot.hasGlowingTag(), Component.literal("the parrot should be glowing. " + l.trace()));
            String text = report.get() == null ? "" : report.get();
            helper.assertTrue(text.contains("glowing light purple"),
                    Component.literal("look_around must say WHICH colour it is already glowing, or the next colour "
                            + "wish is an absolute write over an unread value. Report:\n" + text));
            done(helper, parrot);
        });
    }

    // ===================================================================================
    //  what a worker that has just arrived can address a mob as
    // ===================================================================================

    /**
     * Read the REAL {@code look_around} report and pick a target the way the tools tell a worker to.
     *
     * <p>Call from a background thread; it goes through {@code AgentBridge} like any tool would.
     */
    static String asAFreshWorkerWould(String typeWord) {
        // The mob half of look_around, verbatim. Called through Surroundings rather than through
        // LookAroundTool because that tool needs a player and a GameTest world must not have one
        // (see the class note). The header it adds — biome, weather, headroom — says nothing about
        // which mob is which, and this is the exact method the tool delegates to.
        String text = AgentBridge.onServer(() -> {
            var server = AgentBridge.serverOrNull();
            ServerLevel over = server.overworld();
            return com.moddingfromamod.agent.Surroundings.describe(over, worldSpawn(over));
        });

        // 1. Did the report say what he is talking about, and is it the KIND he just said? A
        //    referent of a different species is a different animal, and a worker reading "he is
        //    talking about @goat" when the child said "panda" must not take it — otherwise this
        //    would launder a hijack into a pass.
        for (String line : text.split("\n")) {
            int marker = line.indexOf(TALKING_ABOUT);
            if (marker >= 0 && line.contains(":" + typeWord)) {
                String handle = firstHandle(line.substring(marker + TALKING_ABOUT.length()));
                if (handle != null) {
                    return handle;
                }
            }
        }
        // 2. Exactly one mob of this kind carrying a handle: the schema says a handle names one
        //    EXACT entity, so a worker with one on offer takes it.
        List<String> handles = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.contains(":" + typeWord) && !line.contains(":" + typeWord + " ")) {
                continue;
            }
            String handle = firstHandle(line);
            if (handle != null) {
                handles.add(handle);
            }
        }
        if (handles.size() == 1) {
            return handles.get(0);
        }
        // 3. The type word. modify_mob's own schema offers it and calls it a guess.
        return typeWord;
    }

    /** The first {@code @token} on a line, or null. */
    private static String firstHandle(String line) {
        for (String word : line.trim().split("\\s+")) {
            if (word.length() > 1 && word.charAt(0) == '@') {
                return word;
            }
        }
        return null;
    }

    // ===================================================================================
    //  plumbing
    // ===================================================================================

    /** One ladder's worth of rungs, recorded so a failure can say what was actually sent. */
    static final class Ladder {
        final List<String> targets = new ArrayList<>();
        final List<String> replies = new ArrayList<>();
        final List<String> workers = new ArrayList<>();
        volatile boolean finished;
        volatile String blewUp;
        volatile String affinityOwnerOfTarget = "";

        void rung(int n, String target, String change) {
            rung(n, target, change, null);
        }

        void rung(int n, String target, String change, String workerId) {
            targets.add(target);
            workers.add(workerId == null ? "(none)" : workerId);
            JsonObject args = JsonParser.parseString(
                    "{\"scope\":\"one\",\"target\":\"" + target + "\"," + change + "}").getAsJsonObject();
            ToolResult r = workerId == null
                    ? new ModifyMobTool().call(args)
                    : AgentContext.with(workerId, () -> new ModifyMobTool().call(args));
            replies.add(r == null ? "(no reply)" : r.text());
        }

        boolean done() {
            return finished;
        }

        boolean failed() {
            return finished && blewUp != null;
        }

        String why() {
            return blewUp == null ? "" : "the ladder threw: " + blewUp;
        }

        /** What each rung sent and got back — the only way to read a failure here. */
        String trace() {
            StringBuilder sb = new StringBuilder("rungs:");
            for (int i = 0; i < targets.size(); i++) {
                sb.append("\n  [").append(i + 2).append("] worker=").append(workers.get(i))
                  .append(" target=\"").append(targets.get(i)).append("\" → ")
                  .append(i < replies.size() ? replies.get(i) : "(none)");
            }
            return sb.toString();
        }
    }

    /** One tool call on a background thread — a GameTest body is the server thread and would deadlock. */
    private static void offThread(Runnable work) {
        Thread t = new Thread(work, "mob-identity-call");
        t.setDaemon(true);
        t.start();
    }

    private static void ladder(AtomicReference<Ladder> out, java.util.function.Consumer<Ladder> body) {
        Ladder l = new Ladder();
        out.set(l);
        Thread t = new Thread(() -> {
            try {
                body.accept(l);
            } catch (Throwable e) {
                l.blewUp = e.toString();
            } finally {
                l.finished = true;
            }
        }, "mob-identity-ladder");
        t.setDaemon(true);
        t.start();
    }

    private static Ladder waitFor(GameTestHelper helper, AtomicReference<Ladder> out) {
        Ladder l = out.get();
        helper.assertTrue(l != null && l.done(), Component.literal("waiting for the ladder to finish"));
        return l;
    }

    /**
     * Clear anything an earlier (possibly failed) test left behind, and hand back the overworld.
     *
     * <p>On ENTRY, not at the end: teardown at the end of a test only runs when the test
     * PASSES, so a failure leaves its animals standing in the shared world for the next victim.
     * These all work at <b>world spawn</b>, which is outside every test plot and therefore never
     * reset, and a GameTest world is reused between runs.
     */
    private static ServerLevel arrive(GameTestHelper helper, EntityType<?> mine) {
        // Same premise as ModifyMobGameTest, and it was the file the first fix MISSED: these tests
        // place their subject at world spawn because that is where "the child" is when nobody is
        // online. See MockPlayerNetwork.requireNobodyIsOnline for what it looks like when that is
        // untrue.
        com.moddingfromamod.testing.MockPlayerNetwork.requireNobodyIsOnline(helper);
        ServerLevel level = helper.getLevel().getServer().overworld();
        Vec3 spawn = worldSpawn(level);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(spawn, spawn).inflate(64), e -> e.getType() == mine)) {
            e.discard();
        }
        // ...and the handles they left behind. Discarding the animal is not enough: a
        // targeted handle outlives its mob, so the next test to ask modify_mob about this species
        // resolves to a corpse and is told, correctly, that it is GONE.
        StaleHandles.forgetEvery(mine);
        return level;
    }

    private static void done(GameTestHelper helper, LivingEntity... mobs) {
        for (LivingEntity e : mobs) {
            if (e != null) {
                e.discard();
            }
        }
    }

    /**
     * Every camel near world spawn and which one {@code @camel} names — so a red run of the undo test says WHICH camel
     * the rungs grew, rather than only that this one did not.
     */
    private static String whichCamels(ServerLevel level, Vec3 spawn, LivingEntity mine) {
        StringBuilder sb = new StringBuilder("the test's camel ").append(mine.getUUID().toString(), 0, 8)
                .append(mine.isRemoved() ? " REMOVED (" + mine.getRemovalReason() + ")" : " in the world")
                .append(" at ").append(mine.blockPosition().toShortString())
                .append(", the level's entity of that uuid: ");
        var live = level.getEntity(mine.getUUID());
        sb.append(live == null ? "none" : live == mine ? "this object" : "a DIFFERENT object, scale "
                + (live instanceof LivingEntity le ? le.getAttributeValue(Attributes.SCALE) : -1));
        var h = EntityHandles.global().resolve("@camel");
        sb.append("; @camel names ").append(h.map(x -> x.id().toString().substring(0, 8)).orElse("nothing"));
        sb.append("; camels within 64 of spawn:");
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(spawn, spawn).inflate(64),
                e -> e.getType() == EntityType.CAMEL)) {
            sb.append(String.format(java.util.Locale.ROOT, " [%s %.1f blocks scale %.2f tags %s]",
                    e.getUUID().toString().substring(0, 8), e.position().distanceTo(spawn),
                    e.getAttributeValue(Attributes.SCALE), e.getTags()));
        }
        return sb.toString();
    }

    private static Vec3 worldSpawn(ServerLevel level) {
        return Vec3.atBottomCenterOf(level.getLevelData().getRespawnData().pos());
    }

    /**
     * Put a mob down and make it stay there.
     *
     * <p>No AI and persistence required, because every assertion here is about <i>which</i> mob was
     * nearest, and an animal that wanders four blocks turns a real result into a coin toss.
     */
    private static LivingEntity place(ServerLevel level, EntityType<?> type, Vec3 at) {
        LivingEntity e = (LivingEntity) type.create(level, EntitySpawnReason.COMMAND);
        e.snapTo(at.x, at.y, at.z, 0, 0);
        if (e instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        level.addFreshEntity(e);
        return e;
    }
}
