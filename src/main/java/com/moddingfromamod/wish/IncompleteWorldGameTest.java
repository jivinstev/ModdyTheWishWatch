package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.FindContentTool;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * The "no mod provides it" claim is qualified when the world is incomplete —.
 *
 * <p>Driven through the real tool rather than the rule, because the rule passing proves only that
 * the sentence can be BUILT. What failed in the field is what the agent was TOLD:
 * {@code find_content} answers an empty search with <i>"No mod provides it, so now it IS right to
 * build a lookalike"</i> — a claim about the whole world, and false when the harness never booted
 * the mod that provides it. The agent then builds a lookalike on the strength of an incomplete
 * world and is never wrong at any step.
 *
 * <p>⚠ The call goes on its OWN thread and the assertions wait for it. A GameTest body runs ON the
 * server thread, and {@code AgentBridge.onServer} refuses that outright rather than deadlocking —
 * the first cut of this test failed with <i>"called from the server thread — that would
 * deadlock"</i>. Same shape as {@code MobIdentityGameTest.offThread}.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class IncompleteWorldGameTest {

    /** Three real calls, each with a different declared expectation, off the server thread. */
    private record Answers(String missing, String complete, String undeclared) {}

    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "incompleteworld")
    public void anIncompleteWorldSaysSoBeforeTheAgentBuildsALookalike(GameTestHelper helper) {
        AtomicReference<Answers> out = new AtomicReference<>();
        AtomicReference<String> boom = new AtomicReference<>();
        String prop = ExpectedMods.PROPERTY;
        String had = System.getProperty(prop);

        Thread t = new Thread(() -> {
            try {
                // 1. This run "meant" to boot a mod that is definitely not here.
                System.setProperty(prop, "othermod_not_really_here");
                String missing = find();
                // 2. The control: a run that booted everything it meant to must stay QUIET, or the
                //    warning is noise and gets skipped along with the real ones.
                System.setProperty(prop, ModdingFromAModMod.MODID);
                String complete = find();
                // 3. Undeclared is inert — this must never reach a real child's session.
                System.clearProperty(prop);
                String undeclared = find();
                out.set(new Answers(missing, complete, undeclared));
            } catch (RuntimeException e) {
                boom.set(String.valueOf(e));
            } finally {
                if (had == null) {
                    System.clearProperty(prop);
                } else {
                    System.setProperty(prop, had);
                }
            }
        }, "incomplete-world-call");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(boom.get() == null, Component.literal("the tool threw: " + boom.get()));
            Answers a = out.get();
            helper.assertTrue(a != null, Component.literal("waiting for find_content"));
            helper.assertTrue(a.missing().contains("INCOMPLETE"),
                    Component.literal("#182 — the agent was told no mod provides it, in a world that was supposed to "
                            + "have one that might: " + a.missing()));
            helper.assertTrue(a.missing().contains("othermod_not_really_here"),
                    Component.literal("the missing mod must be NAMED, or the agent cannot act on it: " + a.missing()));
            helper.assertFalse(a.complete().contains("INCOMPLETE"),
                    Component.literal("a complete world was reported as incomplete: " + a.complete()));
            helper.assertFalse(a.undeclared().contains("INCOMPLETE"),
                    Component.literal("the qualifier fired with no expectation declared at all: " + a.undeclared()));
        });
    }

    private static String find() {
        JsonObject args = new JsonObject();
        // A query nothing can match, so the empty branch is the one under test.
        args.addProperty("query", "zzz_no_such_content_zzz");
        return String.valueOf(new FindContentTool().call(args).text());
    }
}
