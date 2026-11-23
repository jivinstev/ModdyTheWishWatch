package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the add_template draft finish_wish hands back for an untaught command-granted wish. */
class TeachDraftTest {

    @Test
    @DisplayName("20 identical summons fold to one summon, repeat 20, countable")
    void identicalSummonsFold() {
        List<String> ran = new ArrayList<>(Collections.nCopies(20, "summon creeper ~ ~ ~"));
        ran.add(0, "tellraw @s {\"text\":\"here they come\"}");
        TeachDraft.Draft d = TeachDraft.of(ran, null, CommandGuard::isAllowed);
        assertEquals(List.of("summon creeper ~ ~ ~"), d.commands());
        assertEquals(20, d.repeat());
        assertTrue(d.countable(), "'spawn 50 creepers' must be able to set the number");
        assertTrue(d.toJson().toString().contains("\"repeat\":20"), d.toJson().toString());
        assertTrue(d.warnings().isEmpty(), d.warnings().toString());
    }

    @Test
    @DisplayName("a repeated BLOCK folds too; a one-off list is kept whole and not countable")
    void blocksAndOneOffs() {
        TeachDraft.Draft d = TeachDraft.of(List.of("summon zombie ~ ~ ~", "particle flame ~ ~ ~",
                "summon zombie ~ ~ ~", "particle flame ~ ~ ~"), null, CommandGuard::isAllowed);
        assertEquals(2, d.commands().size());
        assertEquals(2, d.repeat());
        TeachDraft.Draft nv = TeachDraft.of(List.of("effect give @s night_vision 999999 0 true"),
                null, CommandGuard::isAllowed);
        assertEquals(1, nv.repeat());
        assertFalse(nv.countable());
        assertFalse(nv.toJson().has("repeat"));
    }

    @Test
    @DisplayName("any successful line the guard refuses means no draft at all")
    void aRefusedLineMeansNoDraft() {
        assertNull(TeachDraft.of(List.of("summon creeper ~ ~ ~", "kill @e[type=creeper]"), null,
                CommandGuard::isAllowed), "kill is tag-scoped: no template can hold it");
        assertNull(TeachDraft.of(List.of(), null, CommandGuard::isAllowed));
        assertNull(TeachDraft.of(List.of("say hi"), null, CommandGuard::isAllowed),
                "a wish that only talked has nothing to teach");
    }

    @Test
    @DisplayName("take_me_to becomes wishvisit <id>, which the guard allows")
    void takeMeToBecomesWishvisit() {
        TeachDraft.Draft d = TeachDraft.of(List.of(), "spikors_castle", CommandGuard::isAllowed);
        assertEquals(List.of("wishvisit spikors_castle"), d.commands());
        assertTrue(CommandGuard.isAllowed("wishvisit spikors_castle"));
    }

    @Test
    @DisplayName("fixed coordinates are flagged, never rewritten")
    void absoluteCoordinatesAreFlagged() {
        TeachDraft.Draft d = TeachDraft.of(List.of("setblock 120 64 -35 minecraft:torch"), null,
                CommandGuard::isAllowed);
        assertEquals(List.of("setblock 120 64 -35 minecraft:torch"), d.commands());
        assertEquals(1, d.warnings().size());
        assertTrue(TeachDraft.of(List.of("setblock ~1 ~ ~ minecraft:torch"), null,
                CommandGuard::isAllowed).warnings().isEmpty());
        assertTrue(TeachDraft.refusal(d).contains("FIXED coordinates"));
    }

    @Test
    @DisplayName("the refusal names add_template, the draft, and the way to finish anyway")
    void refusalWording() {
        String r = TeachDraft.refusal(TeachDraft.of(List.of("give @s diamond 64"), null,
                CommandGuard::isAllowed));
        assertTrue(r.contains("add_template") && r.contains("give @s diamond 64")
                && r.contains("finish_wish again") && r.contains("only once"), r);
    }
}
