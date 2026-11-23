package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.marshal.ArtifactAffinity;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishAffinity;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for {@link WishAffinity}: <b>two wishes about one thing really do land on the same worker
 * under {@code same}, and on different workers under {@code different}.</b>
 *
 * <p>Gate A already proves the picker and the queue's routing. What only this can prove is that the
 * control is wired into the path a real parked agent takes — the whole of {@link AwaitWishTool},
 * including the {@link AgentContext} binding that says who is asking. Proving it against
 * {@code Marshal.claim} instead would be proving it about the scripted pool, which is not the seam
 * production uses; that substitution is exactly how "concurrency proven at wishslots=2" was recorded
 * while real play ran serial.
 *
 * <h2>One batch EACH, not one batch between them</h2>
 * Tests in a batch run <b>concurrently</b>; batches run one after another. {@link WishAffinity} is a
 * JVM-wide switch, so three tests sharing a batch each set the arm out from under the other two —
 * which is precisely what happened on the first run here: all three failed together, never reaching
 * an outcome, and the failure read like a hang rather than like three tests fighting over one
 * global. Isolation therefore has to be per test, not per class.
 *
 * <p>Artifact and worker ids are unique per test for the same reason: {@link ArtifactAffinity} is a
 * process-wide map, so two tests using "zippy" would route each other's wishes.
 *
 * <h2>Ticks are not seconds</h2>
 * A GameTest server ticks as fast as it can — ~120 ticks in a fifth of a second on this machine —
 * while {@code await_wish}'s timeout is real seconds. The tick budget below is therefore many times
 * what the wall-clock work needs; sized off the tick count it looks absurd, and sized off the
 * seconds it is barely two. Same trap {@code AwaitWishGameTest} has already been bitten by.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishAffinityGameTest {

    /** Short, because the whole point of a refused ask is that it comes back empty quickly. */
    private static final int ASK_SECONDS = 1;

    private static Wish wish(String text) {
        return new Wish(UUID.randomUUID(), text, WishSource.TYPED, WishStatus.PENDING,
                System.currentTimeMillis(), UUID.randomUUID(), "Steve",
                "minecraft:overworld", 1, 64, 2, "", "", 0L);
    }

    /** What the scripted worker sequence found out. */
    private record Outcome(boolean makerGotIt, boolean strangerGotIt, String error) {
    }

    /**
     * Drive two workers through the real tool, in order, off the server thread.
     *
     * <p>Off the server thread because {@code await_wish} blocks for its whole timeout, and the tool
     * itself hops back onto the server thread to read the world — park the server here and the two
     * deadlock, which is the one bug {@link AwaitWishGameTest} exists to catch.
     */
    private static AtomicReference<Outcome> ask(WishAffinity mode, String artifact,
                                                String maker, String stranger, String wishText) {
        AtomicReference<Outcome> out = new AtomicReference<>();
        // ITS OWN OWNERSHIP MAP. WishQueue already offered this seam and this test did not
        // take it: it wrote into the process-wide ArtifactAffinity and then cleared the whole thing
        // in its finally, deleting every other in-flight test's ownership along with its own. With
        // a private one there is nothing to clean up and nothing anyone else can see.
        ArtifactAffinity affinity = ArtifactAffinity.forTest();
        WishQueue q = new WishQueue(affinity, com.moddingfromamod.marshal.Clock.system())
                .resolvingSubjectsWith(
                        w -> Map.of(wishText, artifact).getOrDefault(w.text(), ""));
        Thread t = new Thread(() -> {
            try {
                WishAffinity.override(mode);
                affinity.remember(artifact, maker);
                q.offer(wish(wishText));

                // The STRANGER asks first in both arms, so neither arm is measuring "whoever
                // happened to be quicker". Under `same` it must be turned away; under `different` it
                // must be served.
                boolean strangerGot = took(stranger, q);
                boolean makerGot = took(maker, q);
                out.set(new Outcome(makerGot, strangerGot, null));
            } catch (RuntimeException e) {
                out.set(new Outcome(false, false, e.toString()));
            } finally {
                WishAffinity.override(null);
                // Do not leave two runs WORKING in the process-wide marshal: activeCount() gates
                // every other test's claim, so a leak here fails somebody else's test.
                for (var run : List.copyOf(Marshal.global().working())) {
                    if (maker.equals(run.workerId()) || stranger.equals(run.workerId())) {
                        Marshal.global().finish(run.wishId());
                    }
                }
                q.clear();
            }
        }, "wish-affinity-gametest");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /** One worker asking for work through the real tool, as that worker. */
    private static boolean took(String workerId, WishQueue q) {
        AgentContext.bind(workerId);
        try {
            JsonObject args = new JsonObject();
            args.addProperty("timeoutSeconds", ASK_SECONDS);
            ToolResult reply = new AwaitWishTool(q).call(args);
            return reply != null && !reply.text().startsWith("No wish arrived in time");
        } finally {
            AgentContext.unbind();
        }
    }

    private static void settle(GameTestHelper helper, AtomicReference<Outcome> out,
                               java.util.function.Consumer<Outcome> assertions) {
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            Outcome o = out.get();
            helper.assertTrue(o != null, Component.literal("waiting for both workers to have asked"));
            helper.assertTrue(o.error() == null, Component.literal("the worker sequence threw: " + o.error()));
            assertions.accept(o);
        });
    }

    /** {@code same}: the refinement goes back to the worker that made the thing. */
    @GameTest(template = "empty_test", timeoutTicks = 20000, batch = "wishAffinitySame")
    public static void underSameBothWishesLandOnTheSameWorker(GameTestHelper helper) {
        var out = ask(WishAffinity.SAME, "gt_affinity_same", "gt-same-w1", "gt-same-w2",
                "make gt_affinity_same taller");
        settle(helper, out, o -> {
            helper.assertTrue(o.makerGotIt(),
                    Component.literal("under `same` the wish must go back to the worker that made the thing"));
            helper.assertFalse(o.strangerGotIt(),
                    Component.literal("under `same` a worker that has never seen it must be turned away"));
        });
    }

    /** {@code different}: the refinement goes to anyone but the worker that made the thing. */
    @GameTest(template = "empty_test", timeoutTicks = 20000, batch = "wishAffinityDifferent")
    public static void underDifferentTheTwoWishesLandOnDifferentWorkers(GameTestHelper helper) {
        var out = ask(WishAffinity.DIFFERENT, "gt_affinity_diff", "gt-diff-w1", "gt-diff-w2",
                "make gt_affinity_diff taller");
        settle(helper, out, o -> {
            helper.assertTrue(o.strangerGotIt(),
                    Component.literal("under `different` a worker that has never seen it must be served"));
            helper.assertFalse(o.makerGotIt(),
                    Component.literal("under `different` the maker must NOT get its own thing back"));
        });
    }

    /**
     * <b>A REFINEMENT MUST RESOLVE TO AN ARTIFACT AT ROUTING TIME.</b> The gate that would have
     * caught the first ladder run's real failure.
     *
     * <p>Everything else here forces an arm and asserts who got the wish — with the artifact handed
     * in by the test. Production has to work it out from the wish text, and it could not: rung 0
     * resolves only wishes that NAME a thing, and a child refining one says "him". So every routing
     * decision in a real run came back {@code artifactId=""} and {@code routing=anyone}, arm A and
     * arm B were the same experiment, and every gate stayed green because none of them asked this.
     */
    @GameTest(template = "empty_test", batch = "wishAffinityResolve")
    public static void aRefinementResolvesToWhatTheChildLastFinished(GameTestHelper helper) {
        java.util.UUID child = java.util.UUID.nameUUIDFromBytes("gt-resolve-child".getBytes());
        String artifact = "gt_resolve_" + helper.getLevel().getGameTime();
        java.util.UUID first = java.util.UUID.randomUUID();
        Marshal.global().submit(first, child, "make a " + artifact + " mob next to me");
        var run = Marshal.global().assign("gt-resolve-w1", first);
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, artifact);
        Marshal.global().finish(first);

        // The refinement a child actually types: it names nothing at all.
        Wish refinement = new Wish(java.util.UUID.randomUUID(), "make him a bit taller",
                WishSource.TYPED, WishStatus.PENDING, System.currentTimeMillis(), child, "Steve",
                "minecraft:overworld", 0, 64, 0, "", "", 0L);
        helper.assertTrue(artifact.equals(WishAgent.subjectOf(refinement)),
                Component.literal("a refinement that names nothing must resolve to the last thing he finished — "
                        + "otherwise affinity can never bind and every arm is 'anyone'. Got '"
                        + WishAgent.subjectOf(refinement) + "'"));

        // THE CHAIN MUST SURVIVE A MODIFY-ONLY RUNG, which is most of a ladder. A refinement
        // records nothing in made() — only a creation does — so taking "what the LAST run made"
        // goes empty on the first "make him tougher" and every rung after it routes `anyone`.
        // Measured on the first real arm-A pair: 4 of 8 refinements failed to bind, and all four
        // were the rung after a modify-only one.
        java.util.UUID modifyOnly = java.util.UUID.randomUUID();
        Marshal.global().submit(modifyOnly, child, "make him really tough");
        Marshal.global().assign("gt-resolve-w2", modifyOnly);   // makes nothing: it edits
        Marshal.global().finish(modifyOnly);
        helper.assertTrue(artifact.equals(WishAgent.subjectOf(refinement)),
                Component.literal("the chain must survive a rung that modified rather than created — got '"
                        + WishAgent.subjectOf(refinement) + "'"));

        // …and a fresh creation must NOT be attached to it.
        Wish creation = new Wish(java.util.UUID.randomUUID(), "make me a castle",
                WishSource.TYPED, WishStatus.PENDING, System.currentTimeMillis(), child, "Steve",
                "minecraft:overworld", 0, 64, 0, "", "", 0L);
        helper.assertTrue(WishAgent.subjectOf(creation).isEmpty(),
                Component.literal("a new thing must not inherit the last one's owner, got '"
                        + WishAgent.subjectOf(creation) + "'"));
        helper.succeed();
    }

    /**
     * {@code off} — today's behaviour, and the default. Whoever asks first takes it, even the worker
     * that made the thing's history.
     *
     * <p>Here because the control defaulting to a forcing mode would change what a child sees before
     * the thing it controls has been measured, and nothing else in the suite would notice.
     */
    @GameTest(template = "empty_test", timeoutTicks = 20000, batch = "wishAffinityOff")
    public static void withNoArmForcedTheFirstWorkerToAskTakesIt(GameTestHelper helper) {
        var out = ask(WishAffinity.OFF, "gt_affinity_off", "gt-off-w1", "gt-off-w2",
                "make gt_affinity_off taller");
        settle(helper, out, o -> {
            helper.assertTrue(o.strangerGotIt(), Component.literal("OFF hands it to whoever asked first"));
            helper.assertFalse(o.makerGotIt(), Component.literal("and there is only one wish to hand out"));
            helper.assertTrue(WishAffinity.DEFAULT == WishAffinity.OFF,
                    Component.literal("OFF must stay the default, or Phase 1 changed what a child sees"));
        });
    }
}
