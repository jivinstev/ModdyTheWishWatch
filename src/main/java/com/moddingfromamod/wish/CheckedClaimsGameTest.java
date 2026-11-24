package com.moddingfromamod.wish;

import com.moddingfromamod.testing.gt.GameTestCompat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.FinishBuildingTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The pickleball court, replayed</b>.
 *
 * <p>Live, 2026-09-25: the child read <i>"✔ 2 mobs playing pickleball"</i> about two companions,
 * and companions walk to the nearest player — they had left the court. His next wish was <i>"theres
 * no mobs in the pickleball court playing"</i>. Here two "players" stand on a court and the real
 * {@code finish_building} is called with that claim and a {@code creature_count} check:
 *
 * <ul>
 *   <li>when they FOLLOW someone off the court, the claim is refused and the child is told nothing;</li>
 *   <li>when they stay, the claim is ticked.</li>
 * </ul>
 *
 * <p>Own batches: each drives {@code Marshal.global()} and a named worker.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class CheckedClaimsGameTest {

    private static final String CLAIM = "2 mobs playing pickleball";

    @GameTest(template = "empty_test", batch = "claims941walk", timeoutTicks = 1000000)
    public static void playersWhoWalkOffTheCourtAreNotTickedAsPlaying(GameTestHelper helper) {
        court(helper, true);
    }

    @GameTest(template = "empty_test", batch = "claims941stay", timeoutTicks = 1000000)
    public static void playersWhoStayOnTheCourtAreTicked(GameTestHelper helper) {
        court(helper, false);
    }

    private static void court(GameTestHelper helper, boolean theyFollowSomeoneAway) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, theyFollowSomeoneAway ? "mfam-cc-walk" : "mfam-cc-stay");
        var server = helper.getLevel().getServer();
        long tag = System.nanoTime();
        String worker = "gt-claims-" + tag;
        String name = "pickleball_player";

        int y = helper.getBlockState(new BlockPos(4, 0, 4)).isAir() ? 2 : 1;
        BlockPos courtRel = new BlockPos(4, y, 4);
        BlockPos court = helper.absolutePos(courtRel);
        // The child stands well off the court — further than a follower walks, so it HOPS to him.
        child.teleportTo(court.getX() + 20.5, court.getY(), court.getZ() + 0.5);
        for (int i = 0; i < 2; i++) {
            Cow player = helper.spawn(EntityType.COW, courtRel.offset(i, 0, 0));
            player.setCustomName(Component.literal(name));
            if (theyFollowSomeoneAway) {
                // What a `companion` does: go to the child, who is not on the court.
                PetBehaviours.apply(player, new PetBehaviour(true, null, null, null), child.getUUID());
            } else {
                player.setNoAi(true);
            }
        }

        Wish wish = WishBook.get(server).add(Wish.create("a pickleball court with 2 mobs playing",
                WishSource.TYPED, System.currentTimeMillis(), child.getUUID(), "Steve",
                helper.getLevel().dimension().identifier().toString(), court.getX(), court.getY(), court.getZ()));
        Marshal.global().submit(wish.id(), child.getUUID(), wish.text());
        Marshal.global().assign(worker, wish.id());
        BuildGate.startWish(wish.id());
        Marshal.global().runForWorker(worker).lockAnchor(new Spot(
                helper.getLevel().dimension().identifier().toString(), court.getX(), court.getY(), court.getZ()));
        WishScope.as(wish.id().toString(), () -> WishClaims.state(List.of(CLAIM)));

        AtomicReference<ToolResult> result = new AtomicReference<>();
        AtomicReference<Throwable> died = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong doneMs = new java.util.concurrent.atomic.AtomicLong();
        Thread t = new Thread(() -> {
            try {
                AgentContext.with(worker, () -> {
                    String label = "gt_claims_" + tag;
                    answerTheCamera(label);
                    JsonObject args = new JsonObject();
                    args.addProperty("say", "Your pickleball court is ready!");
                    args.addProperty("label", label);
                    JsonArray claims = new JsonArray();
                    JsonObject c = new JsonObject();
                    c.addProperty("claim", CLAIM);
                    c.addProperty("works", true);
                    c.addProperty("evidence", "Spawned 2 players and put them on the court");
                    JsonObject check = new JsonObject();
                    check.addProperty("kind", "creature_count");
                    check.addProperty("what", name);
                    check.addProperty("min", 2);
                    check.addProperty("near", "wish");
                    check.addProperty("radius", 3);
                    c.add("check", check);
                    claims.add(c);
                    args.add("claims", claims);
                    result.set(new FinishBuildingTool().call(args));
                    return null;
                });
            } catch (Throwable e) {
                died.set(e);
            } finally {
                doneMs.set(System.currentTimeMillis());
                done.set(true);
            }
        }, "checked-claims-gametest");
        t.setDaemon(true);
        t.start();

        long startedMs = System.currentTimeMillis();
        helper.succeedWhen(() -> {
            if (!done.get() && System.currentTimeMillis() - startedMs > 25_000L) {
                helper.testInfo.fail(GameTestCompat.assertion("finish_building did not return in 25 s of wall clock"));
                return;
            }
            helper.assertTrue(done.get(), Component.literal("waiting for finish_building"));
            // "it's here!" and the claims are said when the REVEAL has played, a second or
            // so after finish_building returns (never later than FINALE_DEADLINE_MS). Wait for it
            // rather than reading the chat the instant the tool returns — still "not yet", not a
            // verdict, until that deadline.
            if (!theyFollowSomeoneAway && result.get() != null && !result.get().isError()
                    && System.currentTimeMillis() - doneMs.get()
                            < com.moddingfromamod.wish.reveal.Reveals.FINALE_DEADLINE_MS + 3_000L
                    && MockPlayerNetwork.chat(child).stream()
                            .noneMatch(l -> l.contains("✔") && l.contains(CLAIM))) {
                throw GameTestCompat.assertion("waiting for the reveal's announcement");
            }
            try {
                helper.assertTrue(died.get() == null, Component.literal("the driver thread died: " + died.get()));
                ToolResult r = result.get();
                String text = r == null ? "" : String.valueOf(r.text());
                List<String> chat = MockPlayerNetwork.chat(child);
                // the child is shown no checklist — never a ✔, never "I tried" — and the
                // verdict is RECORDED (WishClaims + the claim_verdict log row) rather than told.
                boolean checklist = chat.stream().anyMatch(l -> l.contains("✔")
                        || l.contains("Here's what works") || l.contains("I tried"));
                helper.assertTrue(!checklist, Component.literal("the child was shown a checklist: " + chat));
                java.util.List<WishClaims.Claim> recorded = new java.util.ArrayList<>();
                WishScope.as(wish.id().toString(), () -> recorded.addAll(WishClaims.all()));
                if (theyFollowSomeoneAway) {
                    helper.assertTrue(r != null && r.isError() && text.contains("Not yet")
                                    && text.contains(CLAIM),
                            Component.literal("a claim the world contradicts must be refused: " + text));
                } else {
                    helper.assertTrue(r != null && !r.isError(), Component.literal("a true claim was refused: " + text));
                    helper.assertTrue(recorded.size() == 1
                                    && recorded.get(0).verdict() == WishClaims.Verdict.WORKS,
                            Component.literal("a checked, true claim must be recorded as WORKS: " + recorded));
                }
                helper.succeed();
            } catch (net.minecraft.gametest.framework.GameTestAssertException e) {
                // ONCE THE DRIVER IS DONE, AN ASSERTION IS A VERDICT, NOT "NOT YET".
                // succeedWhen retries a thrown assertion every tick until timeoutTicks runs out,
                // and this test's budget is a million ticks (its real deadline is the wall clock
                // above) -- so a deterministic failure was retried for 81 minutes and read as a
                // hang. Fail it now, with the message it would have died with.
                helper.testInfo.fail(GameTestCompat.failure(e));
            } finally {
                if (done.get()) {
                    Marshal.global().finish(wish.id());
                    BuildGate.settle(wish.id());
                    WishCapture.forgetForTest(child.getUUID());
                }
            }
        });
    }

    /** Be the client for one photograph (see OneEndingGameTest). */
    private static void answerTheCamera(String label) {
        com.moddingfromamod.testing.FakeCamera.answer(label);
    }
}
