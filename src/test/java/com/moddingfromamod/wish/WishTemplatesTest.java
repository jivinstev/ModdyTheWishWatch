package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Gate A for the offline wish engine. Templates are the thing an AI agent will later write into, so
 * the invariants here ("never emit a slash-prefixed or newline-bearing command", "always clamp the
 * count") are what keep agent-authored data from becoming an injection route.
 */
class WishTemplatesTest {

    private static final String JSON = """
        {"templates":[
          {"id":"rain_chickens","all":["chicken"],"any":["rain","sky"],
           "commands":["summon minecraft:chicken ~ ~12 ~"],
           "repeat":20,"countable":true,"maxRepeat":60,"say":"It's raining chickens!"},
          {"id":"speed","all":["fast"],
           "commands":["effect give @p minecraft:speed 60 3"],"say":"Zoom!"},
          {"id":"zombies","all":["zombie"],
           "commands":["summon minecraft:zombie ~ ~ ~"],
           "repeat":3,"countable":true,"maxRepeat":40,"say":"Uh oh!"}
        ]}
        """;

    private static WishTemplates templates() {
        return WishTemplates.fromJson(JSON);
    }

    @Test
    void matchesOnKeywords() {
        Optional<CommandPlan> plan = templates().match("make it rain chickens");
        assertTrue(plan.isPresent(), "should have matched the chicken template");
        assertEquals("It's raining chickens!", plan.get().sayToPlayer());
        assertEquals(20, plan.get().commands().size(), "the template's default repeat should apply");
    }

    @Test
    void requiresAllTermsAndHonoursAny() {
        // "chicken" alone isn't enough — the template also needs one of rain/sky.
        assertFalse(templates().match("i love chicken").isPresent());
        assertTrue(templates().match("chicken from the sky").isPresent());
    }

    @Test
    void usesTheNumberTheKidAsked() {
        CommandPlan plan = templates().match("spawn 12 zombies").orElseThrow();
        assertEquals(12, plan.commands().size());
    }

    @Test
    void clampsCountsTheKidAsksFor() {
        // "1000 zombies" must not become 1000 commands.
        CommandPlan plan = templates().match("spawn 1000 zombies").orElseThrow();
        assertTrue(plan.commands().size() <= 40,
                "should clamp to the template's maxRepeat, got " + plan.commands().size());
    }

    @Test
    void everyEmittedCommandIsSafeShaped() {
        // Property over every template: no leading slash, no newlines, and the guard accepts it.
        for (String wish : new String[]{"make it rain chickens", "make me fast", "spawn 5 zombies"}) {
            CommandPlan plan = templates().match(wish).orElseThrow();
            for (String cmd : plan.commands()) {
                assertFalse(cmd.startsWith("/"), "no leading slash: " + cmd);
                assertFalse(cmd.contains("\n") || cmd.contains("\r"), "no newlines: " + cmd);
                assertTrue(CommandGuard.isAllowed(cmd), "guard must accept template output: " + cmd);
            }
        }
    }

    @Test
    void neverExceedsTheHardCommandCap() {
        String greedy = """
            {"templates":[{"id":"greedy","all":["x"],
             "commands":["summon minecraft:pig ~ ~ ~"],"repeat":100000,"maxRepeat":100000}]}
            """;
        CommandPlan plan = WishTemplates.fromJson(greedy).match("x").orElseThrow();
        assertTrue(plan.commands().size() <= CommandPlan.MAX_COMMANDS,
                "hard cap must hold, got " + plan.commands().size());
    }

    @Test
    void badTemplatesAreSkippedNotFatal() {
        // One malformed entry from an agent must not break the others.
        String mixed = """
            {"templates":[
              {"id":"broken"},
              {"nope":true},
              "not even an object",
              {"id":"catchall","commands":["say hi"]},
              {"id":"good","all":["pig"],"commands":["summon minecraft:pig ~ ~ ~"],"say":"Oink"}
            ]}
            """;
        WishTemplates t = WishTemplates.fromJson(mixed);
        assertEquals(1, t.size(), "only the well-formed, non-catchall template should load: " + t.ids());
        assertTrue(t.match("a pig please").isPresent());
    }

    @Test
    void garbageJsonYieldsNoTemplatesInsteadOfThrowing() {
        assertEquals(0, WishTemplates.fromJson("{{{not json").size());
        assertEquals(0, WishTemplates.fromJson(null).size());
        assertEquals(0, WishTemplates.fromJson("").size());
        assertFalse(WishTemplates.empty().canHandle("anything"));
    }

    @Test
    void unmatchedWishesEscalateRatherThanGuess() {
        // No match is a first-class outcome: it's what routes the wish to Claude.
        assertTrue(templates().match("build me a floating rainbow castle").isEmpty());
    }

    /**
     * "<wish>@<part>" re-wishes ONLY that part of a wish — the commands of the wish that
     * name it — and says none of the whole wish's line. A part no command names has no recipe;
     * never the whole wish in its place.
     */
    @Test
    void aPartRecipeRunsOnlyThatPartsCommands() {
        WishTemplates t = WishTemplates.fromJson("""
            {"templates":[{"id":"tropical_oasis","all":["oasis"],
              "commands":["wishbuild oasis_1","wishdatapack oasis_bar give",
                          "give @p minecraft:melon 4"],"say":"Here is your oasis!"}]}
            """);
        var bar = t.byId("tropical_oasis@oasis_bar", "").orElseThrow().plan();
        assertEquals(java.util.List.of("wishdatapack oasis_bar give"), bar.commands());
        assertEquals("", bar.sayToPlayer(), "a part does not announce the whole oasis");
        assertEquals(3, t.byId("tropical_oasis", "").orElseThrow().plan().commands().size(),
                "the wish entry still makes all of it");
        assertFalse(t.byId("tropical_oasis@palm_tree", "").isPresent());
        assertFalse(t.byId("nobodys_wish@oasis_bar", "").isPresent());
    }
}
