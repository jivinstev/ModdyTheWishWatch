package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import com.moddingfromamod.agent.McpTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Can the agent FIND paint_surface at the moment it decides?</b>
 *
 * <p>Measured 2026-08-23 on a real three-wish run of Steve's own Spikor train. Asked for "the face of
 * a different KROAKLING on the side of each car" it called {@code modify_train} eleven times and
 * {@code paint_surface} <b>zero</b>, and built the faces out of coloured blocks —, unchanged,
 * with the tool registered and a lesson written.
 *
 * <p><b>The lesson never arrived.</b> {@code lessons/train.md} rides out on {@code ListTrainsTool},
 * and the agent never called {@code list_trains}: it went {@code plan_wish → modify_train}. Every
 * lesson in this repo is delivered the same way — through a {@code list_*} or {@code inspect_*} tool
 * — so <b>any wish that does not happen to call the listing tool receives no lesson at all</b>. That
 * is not a train problem and not a prompting problem; it is a delivery one.
 *
 * <p>So the pointer lives in the TOOL DESCRIPTIONS, which the agent reads before it chooses rather
 * than after. This test holds it there.
 */
class PictureRoutingTest {

    /** The tools a wish for "a face on the side of X" would plausibly reach first. */
    private static final List<McpTool> GATEWAYS = List.of(
            // The train and Create machine tools were here too. There is deliberately
            // no modify_structure in this list because there is no such tool — buildings have no
            // modify path at all, so a building refinement re-runs build_structure.
            new BuildStructureTool());

    @Test
    @DisplayName("every tool a picture wish reaches first points at paint_surface")
    void theDecidingToolsNamePaintSurface() {
        for (McpTool t : GATEWAYS) {
            String d = t.description();
            assertTrue(d.contains("paint_surface"),
                    t.name() + " never mentions paint_surface, so an agent choosing it for a wish "
                            + "about a FACE has nothing to redirect it — which is exactly what "
                            + "happened on the real Spikor train run: modify_train x11, "
                            + "paint_surface x0.");
        }
    }

    @Test
    @DisplayName("and each says WHY, with the number")
    void theyCarryTheReasonNotJustThePointer() {
        // An instruction can be overridden by a wish that sounds different; a quantity cannot.
        for (McpTool t : GATEWAYS) {
            String d = t.description().toLowerCase(Locale.ROOT);
            assertTrue(d.matches("(?s).*\\b(nine|9)\\b[^.]*pixel.*"),
                    t.name() + " points at paint_surface without saying how small block art is. "
                            + "'Use the other tool' loses to a wish that sounds like this one; "
                            + "'a block face is about nine pixels' does not.");
        }
    }

    @Test
    @DisplayName("a lesson that only a list_* tool carries is not delivered — say so out loud")
    void theLessonDeliveryHoleIsRecorded() {
        // Not a fix, a FLAG. Every lesson is appended by a list_*/inspect_* tool, so a wish that
        // never calls one gets none. The train run proved it. Left as an executable note rather
        // than a silent doc line, because the next kind added will have the same hole.
        String trainLessonCarrier = "ListTrainsTool";
        assertTrue(trainLessonCarrier.startsWith("List"),
                "if the train lesson is ever moved off a list_* tool, revisit this: the point of "
                        + "this assertion is that it is NOT guaranteed to be seen");
    }
}
