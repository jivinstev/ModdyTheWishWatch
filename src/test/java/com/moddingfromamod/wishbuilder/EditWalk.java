package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.moddingfromamod.ui.PickPage;

/**
 * an edit of a composed make reopens at its kind's first page. This walks past every page keeping its choice,
 * exactly as {@link BuilderEdit} does and as a child pressing the same tiles would, to the tune dialog.
 */
final class EditWalk {

    private EditWalk() {
    }

    static BuilderFlow toTune(BuilderFlow f) {
        for (int guard = 0; guard < 48 && f.step() != BuilderFlow.Step.TUNE; guard++) {
            BuilderFlow.Step here = f.step();
            String keep = BuilderFlow.multiPick(here) ? PickPage.NEXT_ID : f.keepPress();
            assertNotNull(keep, "the '" + here + "' page has no choice to keep");
            String said = f.press(keep).say();
            assertEquals("", said, "walking past '" + here + "'");
        }
        assertEquals(BuilderFlow.Step.TUNE, f.step(), "the edit did not reach the tune dialog");
        return f;
    }
}
