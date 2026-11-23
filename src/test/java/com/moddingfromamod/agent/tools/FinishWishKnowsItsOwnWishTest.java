package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.marshal.Marshal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>"Which wish?" is a question this server can answer itself.</b>
 *
 * <p>Measured over a real Muse Gate C½ run: {@code finish_wish} was refused <b>five times</b> for a
 * missing {@code id} and it was that run's dominant failure — the agent sent
 * {@code message, suggestion, verdict} and no id, five times, because it did not have one to hand.
 *
 * <p>The refusal was the same defect the tool already argues against three lines above it: a
 * missing or unknown <em>verdict</em> degrades to DONE rather than refusing, because <em>"a wish
 * that CANNOT end is the problem this whole tool exists to fix"</em>. That reasoning was applied to
 * the verdict and not to the id.
 *
 * <p>And the id was never unknown. {@code AgentContext.currentRun()} resolves the caller's own wish
 * from the marshal; {@code finish_wish} already called it further down its own method, and
 * {@code McpProtocol} uses the same lookup to attribute every call in the action log.
 */
class FinishWishKnowsItsOwnWishTest {

    /** Unique per run so this can never collide with another test's worker in the global marshal. */
    private final String worker = "test-finishid-" + UUID.randomUUID();
    private UUID open;

    @AfterEach
    void releaseTheWish() {
        // The marshal is a process-wide singleton; a WORKING run left behind changes the outcome
        // of whatever runs next.
        if (open != null) {
            Marshal.global().finish(open);
        }
        AgentContext.unbind();
    }

    private static ToolResult finishWithNoId() {
        return finishWith(null);
    }

    private static ToolResult finishWith(String id) {
        JsonObject args = new JsonObject();
        if (id != null) {
            args.addProperty("id", id);
        }
        args.addProperty("verdict", "done");
        args.addProperty("note", "built him");
        return new FinishWishTool().call(args);
    }

    @Test
    void aWorkerWithOneWishInFlightDoesNotHaveToBeToldWhichOne() {
        open = UUID.randomUUID();
        AgentContext.bind(worker);
        Marshal.global().submit(open, UUID.randomUUID(), "make me a Spikor that breathes fire");
        Marshal.global().assign(worker, open);

        String message = String.valueOf(finishWithNoId().text());

        assertFalse(message.contains("Which wish?"),
                "the server resolved this worker's wish to attribute the very call being made, "
                        + "then asked the agent to go and fetch it: " + message);
    }

    /**
     * The placeholder rule, tested on the PURE helper.
     *
     * <p>Two tests here first drove {@code call()} instead, and both passed against the unfixed
     * code — {@code AgentBridge.onServer} throws in a unit test, so they never reached the rule and
     * were measuring the absence of a Minecraft server. They looked exactly like passing tests. The
     * A/B against reverted source is the only thing that said otherwise.
     */
    static class TheRuleItself {

        private static final java.util.List<String> BOOK = java.util.List.of("a1b2c3", "d4e5f6");

        @Test
        void aPlaceholderIdMeansTheWorkersOwnWish() {
            // THE ONE THAT ACTUALLY HAPPENED: `id: "unknown"`, fifteen rounds, 900s, creature
            // already built. An empty-string check can never catch it -- "unknown" is not empty.
            assertEquals("a1b2c3", FinishWishTool.resolveWishId("unknown", "a1b2c3", BOOK));
        }

        @Test
        void aMissingIdMeansTheSameThing() {
            assertEquals("a1b2c3", FinishWishTool.resolveWishId("", "a1b2c3", BOOK));
            assertEquals("a1b2c3", FinishWishTool.resolveWishId(null, "a1b2c3", BOOK));
        }

        @Test
        void aREALIdIsNEVERSecondGuessed() {
            // The dangerous direction. Two wishes are in the book and the agent named the other
            // one deliberately; resolving that to "yours" would close the wrong wish and the
            // child's progress would follow the wrong build.
            assertEquals("d4e5f6", FinishWishTool.resolveWishId("d4e5f6", "a1b2c3", BOOK));
        }

        @Test
        void withNothingToResolveToItStaysAmbiguous() {
            // It must not guess. An empty answer sends the caller to the honest refusal.
            assertEquals("", FinishWishTool.resolveWishId("unknown", "", BOOK));
            assertEquals("", FinishWishTool.resolveWishId("unknown", "gone999", BOOK));
            assertEquals("", FinishWishTool.resolveWishId("unknown", "a1b2c3", java.util.List.of()));
            assertEquals("", FinishWishTool.resolveWishId(null, null, null));
        }

        @Test
        void idsAreMatchedTheWayTheyAreShown() {
            // list_wishes prints them lowercase; nothing guarantees the agent echoes that case.
            assertEquals("A1B2C3", FinishWishTool.resolveWishId("A1B2C3", "", BOOK));
        }
    }

    @Test
    void withNoWishAtAllItStillSaysWhichToolToCall() {
        // THE OTHER HALF. The fallback must not turn a genuine ambiguity into a silent guess —
        // with nothing to resolve, the refusal has to survive AND stay actionable, or an agent
        // told only "no" retries in a loop.
        AgentContext.bind(worker);   // bound, but no wish assigned

        ToolResult r = finishWithNoId();

        assertTrue(r.isError(), "with no wish in flight there is nothing to finish");
        assertTrue(String.valueOf(r.text()).contains("list_wishes"),
                "the refusal must name the way out: " + r.text());
    }
}
