package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** A Builder item is handed over with exactly the command make_item's reply names. */
class BuilderHandOverTest {

    static final String REPLY = "Made 'Lightning Sword' and it is now LIVE in the world.  feature id: lightning_sword_lightning"
            + "  NOW PUT IT IN HIS HAND, with exactly this and nothing else:     run_command: wishdatapack "
            + "lightning_sword_lightning give The item exists but nobody is holding it until you do.";

    @Test
    void theReplysOwnGiveIsTheOneRun() {
        assertEquals("wishdatapack lightning_sword_lightning give", BuilderMake.handOverCommand(REPLY));
    }

    @Test
    void aReplyThatAsksForNoHandOverRunsNothing() {
        assertNull(BuilderMake.handOverCommand("Changed his own 'Lightning Sword' — the stacks he holds now do it."));
        assertNull(BuilderMake.handOverCommand(null));
    }

    @Test
    void onlyTheGiveShapeIsEverTakenFromAReply() {
        assertNull(BuilderMake.handOverCommand("PUT IT IN HIS HAND: run_command: op somebody"));
        assertNull(BuilderMake.handOverCommand("PUT IT IN HIS HAND: run_command: wishdatapack x kill"));
    }
}
