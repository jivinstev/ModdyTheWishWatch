package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.datapack.EffectCatalogue;
import com.moddingfromamod.ui.PickPage;
import org.junit.jupiter.api.Test;

/** every special move is a real catalogue effect, touches only monsters, and is reached by the menus. */
class BuilderAbilitiesTest {

    @Test
    void everyMoveIsARealEffectWithRealParameters() {
        List<String> wrong = new ArrayList<>();
        for (BuilderAbilities.Ability a : BuilderAbilities.ALL) {
            EffectCatalogue.Effect e = EffectCatalogue.load().get(a.effect());
            if (e == null) {
                wrong.add(a.id() + ": no effect '" + a.effect() + "'");
                continue;
            }
            for (String k : a.params().keySet()) {
                if (!e.params().contains(k) && !e.defaults().containsKey(k)) {
                    wrong.add(a.id() + ": '" + a.effect() + "' takes no " + k + " (it takes " + e.params() + ")");
                }
            }
            // WISH_BUILDER_PLAN §6.2: a move that lands on anybody lands on monsters, never on him or a friend.
            if (e.params().contains("TARGET") || e.defaults().containsKey("TARGET")) {
                assertEquals("monsters", a.params().get("TARGET"), a.id() + " must name TARGET monsters");
            } else if (e.params().contains("COMMAND") || e.defaults().containsKey("COMMAND")) {
                // No TARGET to name: the command it runs on whoever is caught must leave players out itself.
                String cmd = a.params().getOrDefault("COMMAND", e.defaults().getOrDefault("COMMAND", ""));
                // and MONSTERS only, like a TARGET — "anything but a player" killed a child's other creature beside it
                assertTrue(!cmd.contains("@s") || cmd.contains("@s[type=" + com.moddingfromamod.datapack.EffectVictims.MONSTERS_TAG + "]"),
                        a.id() + "'s command reaches more than monsters: " + cmd);
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void aCreatureGetsItsMovesThroughTheMenusAndTheyLowerWithTheirParameters() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("creature");
        f.press("species/cat");
        assertEquals(BuilderFlow.Step.PARTS, f.step());
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.POWERS, f.step());
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.ABILITIES, f.step());
        f.press("ability:fire_breath");
        f.press("ability:flower_trail");
        assertEquals(BuilderFlow.Step.ABILITIES, f.step(), "#1998: the last page before the tune dialog (no Back) waits");
        // no count — a third move of another effect is taken; a second BREATH is not, because apply_effect would
        // replace the first (one pack per effect per creature), and the page says so before he presses.
        assertTrue(f.press("ability:treats").say().isEmpty(), "a third special move of its own effect is taken");
        PickPage.Tile frost = f.page().tiles().stream().filter(t -> t.id().equals("ability:frost_breath")).findFirst().orElseThrow();
        assertEquals("Can't with Breathes fire", f.page().blocked(frost));
        assertTrue(f.press("ability:frost_breath").say().contains("Can't with Breathes fire"), "and a press says why");
        f.press("ability:treats");   // off again: the make below is the two-move one
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.TUNE, f.step());
        BuilderSpec spec = f.spec(Map.of());
        assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty(), BuilderAllowlist.REVIEWED.problems(spec).toString());
        var calls = BuilderLowering.lower(spec, "cat_1", LibraryPresets.SHIPPED).stream()
                .filter(c -> c.tool().equals("apply_effect")).toList();
        assertEquals(2, calls.size());
        assertEquals("breath_cone", calls.get(0).args().get("effect").getAsString());
        assertEquals("cat_1", calls.get(0).args().get("tag").getAsString());
        assertEquals("monsters", calls.get(0).args().getAsJsonObject("params").get("TARGET").getAsString());
        assertEquals("trail", calls.get(1).args().get("effect").getAsString());
        assertNotNull(calls.get(1).args().getAsJsonObject("params").get("BLOCK"));
    }
}
