package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B item 3, driven through the REAL {@code make_item}.
 *
 * <p>{@link SecondSentenceTest} covers the decision. This covers the two things a unit test cannot:
 * that the check is reached from the tool's own entry path <b>before</b> a spec is built, and that
 * it reads the live {@link ItemSpecStore} and the wish this worker is actually bound to.
 *
 * <p><b>It binds a real worker to a real run, and that is the point rather than setup.</b> The first
 * version set the wish text through {@code ProvenanceContext} and 11 unrelated tests went red:
 * {@code AgentContext.currentRun()} falls back to the single live run, so {@code make_item} refused
 * good calls quoting a NEIGHBOUR's wish. The fix was to make the refusal depend on a bound run, and
 * this test is the only thing that can prove it — with no binding the check is inert, which is
 * exactly what the other 554 tests now rely on.
 *
 * <p>Its own batch: {@link ItemSpecStore} and the marshal are process-wide. Ids carry the game time,
 * so the assertions ask about this test's own artifacts rather than about the store being empty.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SecondSentenceGameTest {

    private static JsonObject fullCall(String name) {
        JsonObject a = new JsonObject();
        a.addProperty("name", name);
        a.addProperty("icon", "wand");
        a.addProperty("colour", "purple");
        a.addProperty("shape", "thrown");
        a.addProperty("effect", "launch");
        a.addProperty("count", 1);
        // The `launch` effect requires POWER — make_item's own vocabulary check refuses without it,
        // POWER is a WORD (nudge/shove/launch/huge), not a number — the catalogue is a vocabulary.
        // It refuses BEFORE the SecondSentence check, which is how the first two runs of this
        // test reported the wrong refusal and looked like the feature was inert.
        JsonObject params = new JsonObject();
        params.addProperty("POWER", "shove");
        a.add("params", params);
        return a;
    }

    /**
     * <b>The wish this exists for.</b> "and one that…" carries only the DIFFERENCE, so a call that
     * inherits nothing invents the shape, the icon and the colour from nowhere — and that reads as
     * a successful grant at every tier we run.
     */
    @GameTest(template = "empty_test", batch = "secondSentence")
    public static void aSecondSentenceThatInheritsNothingIsRefused(GameTestHelper helper) {
        long now = helper.getLevel().getGameTime();
        String id = "gt_ss_" + now;
        ItemSpecStore.put(new ItemSpecStore.Spec(id, id, "wand", "purple",
                "thrown", 1, java.util.List.of("launch"), null, null, null, null));

        String worker = "gt-ss-worker-" + now;
        UUID wishId = UUID.randomUUID();
        UUID player = UUID.nameUUIDFromBytes(("gt-ss-child-" + now).getBytes());
        Marshal.global().submit(wishId, player, "and one that shoves the noob in lava");
        Marshal.global().assign(worker, wishId);

        AgentContext.bind(worker);
        try {
            var refused = new MakeItemTool().call(fullCall("lava pearl " + now));
            helper.assertTrue(refused.isError(),
                    Component.literal("a wish pointing back, inheriting nothing, with designs in the store, must be "
                            + "refused rather than invented: " + refused.text()));
            helper.assertTrue(refused.text().contains(id),
                    Component.literal("the refusal must name what he might have meant — an agent cannot pick from a "
                            + "message that lists nothing: " + refused.text()));
            helper.assertTrue(refused.text().contains("as_new:true")
                            && refused.text().contains("from_scratch:true"),
                    Component.literal("both routes have to be spelled out: " + refused.text()));

            // THE CONTROL, and it is the half that keeps this from being a wall. The agent saying
            // it checked must actually get through — a guard whose escape does not work strands a
            // legitimate wish and is indistinguishable from a bug.
            JsonObject said = fullCall("unrelated wand " + now);
            said.addProperty("from_scratch", true);
            var allowed = new MakeItemTool().call(said);
            helper.assertFalse(allowed.isError(),
                    Component.literal("from_scratch:true is the agent SAYING it checked, and it has to work: "
                            + allowed.text()));
        } finally {
            // FINISH THE RUN, always, and this is not tidying up. A run left WORKING in the
            // marshal is the "single live run" every other test's AgentContext.currentRun() falls
            // back to -- measured: leaving it here made three provenance tests fail with
            // "manifest intent must be the wish, got: and one that shoves the noob in lava". That
            // is the exact bug this test exists for, arriving from the other side, and it is why
            // the fix had to be on the READ side too: a test can forget this, and production code
            // must not depend on nobody forgetting.
            Marshal.global().finish(wishId);
            // Unbind, always. The binding is a ThreadLocal on the SERVER thread, which every other
            // test in this batch also runs on -- leaving it set would make the next test's
            // make_item call look like it belonged to this test's wish, which is a smaller version
            // of the very bug this test exists for.
            AgentContext.unbind();
        }
        helper.succeed();
    }

    /**
     * <b>An unbound worker never refuses</b> — the property every other GameTest depends on.
     *
     * <p>This is the regression test for the 11 collateral failures. The same call, the same
     * non-empty store, the same back-referring wish sitting in the marshal — but no worker bound,
     * so {@code make_item} must not reach for the ambient run and refuse on somebody else's
     * sentence. Cheap to state and it is the case that actually broke.
     */
    @GameTest(template = "empty_test", batch = "secondSentence")
    public static void anUnboundWorkerIsNeverRefused(GameTestHelper helper) {
        long now = helper.getLevel().getGameTime();
        String id = "gt_ss_unbound_" + now;
        ItemSpecStore.put(new ItemSpecStore.Spec(id, id, "wand", "purple",
                "thrown", 1, java.util.List.of("launch"), null, null, null, null));
        UUID loose = UUID.randomUUID();
        Marshal.global().submit(loose,
                UUID.nameUUIDFromBytes(("gt-ss-loose-" + now).getBytes()),
                "and one that is blue");

        var made = new MakeItemTool().call(fullCall("plain wand " + now));
        Marshal.global().finish(loose);          // never leave a live run behind — see above
        helper.assertFalse(made.isError(),
                Component.literal("with no worker bound there is no wish this call belongs to, so the check must be "
                        + "inert rather than firing on whatever run happens to be live: "
                        + made.text()));
        helper.succeed();
    }
}
