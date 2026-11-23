package com.moddingfromamod.wish;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>{@code conjure_at} must actually USE the settling rule.</b>
 *
 * <p>{@code GroundAnchorTest} pins down what the rule decides. This pins down that anybody asks it —
 * which is the failure mode this repo keeps hitting: a capability that exists, compiles, has passing
 * tests of its own, and is wired to nothing. It has happened with a whole tool
 * ({@code author_recipe}, silently unregistered by a merge, shipped dead in a jar that passed both
 * gates) and with the {@code /function} rewrite, which two tools called and the one path the agent
 * actually typed on did not.
 *
 * <p>Checked at the source level on purpose: {@code conjure_at} needs a real
 * {@code ServerPlayer} in the world, and a headless GameTest has none — so there is no runtime seam
 * to assert on, and the choice is between this and nothing.
 */
class ConjureAtSettlesToGroundTest {

    private static final Path TOOL =
            Path.of("src/main/java/com/moddingfromamod/agent/tools/ConjureAtTool.java");

    /**
     * Since the settle happens ONCE, when he wishes — the spot {@code conjure_at} confirms is
     * already on the ground, and settling it again from the live player is what made the pillar
     * jump. So the wiring to pin is: every wish is locked through {@code WishAimer}, and
     * {@code WishAimer} asks {@code WishAim}, whose {@code standAt} only ever settles DOWN.
     */
    @Test
    void theLockedSpotIsSettledToTheGround() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/moddingfromamod/wish/WishService.java"));
        String aimer = Files.readString(
                Path.of("src/main/java/com/moddingfromamod/wish/WishAimer.java"));
        assertTrue(service.contains("WishAimer.lockAtWishTime"),
                "a wish is no longer locked through WishAimer, so nothing settles its spot to the "
                        + "ground — live, a skyscraper locked to y=26 over ground at y=4 and hung there");
        assertTrue(aimer.contains("WishAim.aim("),
                "WishAimer no longer asks the aim rule, so the spot is whatever the ray hit");
        String tool = Files.readString(TOOL);
        assertTrue(!tool.contains("anchorInFrontOf(player, forward, up)"),
                "conjure_at re-aims from the LIVE player again — the jump of #1267");
    }

    @Test
    void anExplicitUpStillReachesTheRule() throws Exception {
        String src = Files.readString(TOOL);
        assertTrue(src.contains("args.has(\"up\")"),
                "the 'did the agent ask for the sky' flag is gone. Without it every deliberate "
                        + "up: 40 flying-castle wish gets dragged down to the ground, which is the "
                        + "opposite bug and a worse one — the sky case can no longer be expressed "
                        + "at all");
    }
}
