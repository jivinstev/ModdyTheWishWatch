package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** every made-up event is a real motion, reached through the menus, and lowers to a stage_event. */
class BuilderEventsTest {

    @Test
    void everyMotionAndWhatIsReachedAndLowers() {
        for (BuilderEvents.Motion m : BuilderEvents.ALL) {
            assertTrue(Catalogue.problem("motion:" + m.motion()) == null, Catalogue.problem("motion:" + m.motion()));
            for (BuilderEvents.What w : m.whats()) {
                assertTrue(!w.id().contains("lava") && !w.id().contains("fire") && !w.id().contains("tnt"), w.id());
                BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
                f.press("event");
                assertEquals(BuilderFlow.Step.EVENT_MOTION, f.step(), "#1997: the event kind opens on what happens");
                f.press("event:" + m.id());
                if (m.whats().size() > 1) {
                    assertEquals(BuilderFlow.Step.EVENT_WHAT, f.step());
                    f.press("what:" + w.id());
                }
                assertEquals(BuilderFlow.Step.TUNE, f.step());
                BuilderSpec spec = f.spec(Map.of("size", "2", "count", "3"));
                assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty(), BuilderAllowlist.REVIEWED.problems(spec).toString());
                var call = BuilderLowering.lower(spec, "ev_1", LibraryPresets.SHIPPED).stream()
                        .filter(c -> c.tool().equals("stage_event")).findFirst().orElseThrow();
                assertEquals(m.motion(), call.args().get("motion").getAsString());
                if (!w.id().equals("minecraft:air")) {
                    assertEquals(w.id(), call.args().get(w.field() != null ? w.field() : m.field()).getAsString());
                }
                assertTrue(call.args().has("title"), "his event says what it is across his screen");
            }
        }
        assertEquals("It's raining pigs!", BuilderEvents.title("rain", "minecraft:pig"));
        assertEquals("The ground turns into a bounce pit!", BuilderEvents.title("turn", "minecraft:slime_block"));
    }
}
