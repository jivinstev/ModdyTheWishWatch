package com.moddingfromamod.world;

import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.marshal.Spot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A make with no agent that a world rule refused tells the child that rule.</b> The pure parts: the reach rule names
 * the effect it enforced beside its words ({@link BaseReach#judge}), that effect finds the world's rule
 * ({@link WorldRefusal#of}), and the rule's own words become his line ({@link WorldRulesWords#madeRefused}) — for both
 * refusing rule types. The wiring is {@code WorldRuleWordsGameTest}'s.
 */
class WorldRefusalTest {

    private static final String RULES = "{\"store\":\"world-rules\",\"v\":1,\"world\":\"isles\",\"rules\":["
            + "{\"id\":\"same-time-for-all\",\"type\":\"deny_world_effect\",\"effects\":[\"time\"],"
            + "\"message\":{\"rule\":\"Time is the same for every island\",\"why\":\"so the sun rises for everyone together\"}},"
            + "{\"id\":\"no-world-rules\",\"type\":\"deny_world_effect\",\"effects\":[\"worldwide_datapacks_standing_rules\"],"
            + "\"message\":{\"rule\":\"A rule here can't change the whole world\",\"why\":\"every island shares it\"}},"
            + "{\"id\":\"your-own-island\",\"type\":\"base_reach\",\"effects\":[\"outside_own_base\",\"others_bases\","
            + "\"other_dimensions\"],\"message\":{\"rule\":\"A wish stays on your island\","
            + "\"why\":\"so every island stays its owner's\"}}]}";

    private static final String OW = Bases.OVERWORLD;
    private final UUID him = UUID.randomUUID();
    private final Base base = new Base(him, OW, 0, 64, 0, 64, new Region(-48, 48, -48, 48), new Region(-40, 40, -40, 40));
    private Bases.Pos at = new Bases.Pos(OW, 0.5, 64, 0.5);

    private final Bases world = new Bases() {
        @Override public String noun() {
            return "island";
        }

        @Override public Base baseOf(UUID p) {
            return him.equals(p) ? base : null;
        }

        @Override public UUID ownerAt(String d, double x, double z) {
            return base.area().contains(x, z) ? him : null;
        }

        @Override public Bases.Pos positionOf(UUID p) {
            return him.equals(p) ? at : null;
        }
    };

    private static WorldRules rules() {
        WorldRules.Parsed p = WorldRules.parse(RULES, "isles");
        assertTrue(p.ok(), p.errors().toString());
        return p.rules();
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void aBuildAimedOffHisIslandIsRefusedByTheBaseReachRule() {
        BaseReach.Refusal r = BaseReach.judge("build_structure", args("{}"), him, new Spot(OW, 500, 64, 0), world, rules());
        assertNotNull(r);
        assertEquals(WorldEffect.OUTSIDE_OWN_BASE, r.effect());
        assertTrue(r.words().contains("NOT HIS ISLAND"), "the agent's words are unchanged: " + r.words());
        assertEquals(r.words(), BaseReach.refusal("build_structure", args("{}"), him, new Spot(OW, 500, 64, 0), world,
                rules()), "refusal() is judge()'s words");
        WorldRefusal why = WorldRefusal.of(r.effect(), rules(), world.noun());
        assertEquals("your-own-island", why.rule().id());
        assertTrue(why.aboutPlace());
    }

    @Test
    void aBuildAimedInTheNetherIsTheOtherDimensionsEffect() {
        BaseReach.Refusal r = BaseReach.judge("build_structure", args("{}"), him,
                new Spot("minecraft:the_nether", 0, 64, 0), world, rules());
        assertEquals(WorldEffect.OTHER_DIMENSIONS, r.effect());
        assertEquals("your-own-island", WorldRefusal.of(r.effect(), rules(), "island").rule().id());
    }

    @Test
    void aWorldWideRuleIsRefusedByTheDenyRule() {
        BaseReach.Refusal r = BaseReach.judge("set_rule", args("{\"when\":\"player_jumps\",\"do\":[\"say hi\"]}"), him,
                null, world, rules());
        assertNotNull(r);
        assertEquals(WorldEffect.WORLDWIDE_DATAPACKS_STANDING_RULES, r.effect());
        WorldRefusal why = WorldRefusal.of(r.effect(), rules(), "island");
        assertEquals("no-world-rules", why.rule().id());
        assertFalse(why.aboutPlace());
    }

    @Test
    void aWorldWideCommandNamesItsOwnEffect() {
        BaseReach.Refusal r = BaseReach.judge("run_command", args("{\"command\":[\"time set night\"]}"), him, null,
                world, rules());
        assertEquals(WorldEffect.TIME, r.effect());
        BaseReach.Refusal far = BaseReach.judge("run_command", args("{\"command\":[\"tp @s 5000 100 0\"]}"), him, null,
                world, rules());
        assertEquals(WorldEffect.OUTSIDE_OWN_BASE, far.effect());
    }

    @Test
    void aRefusalThatIsNoRulesNamesNone() {
        BaseReach.Refusal r = BaseReach.judge("no_such_tool", args("{}"), him, null, world, rules());
        assertNotNull(r);
        assertNull(r.effect());
        assertNull(WorldRefusal.of(r.effect(), rules(), "island"), "no effect, no rule: the generic line stays");
        assertNull(WorldRefusal.of(WorldEffect.WEATHER, rules(), "island"), "an effect this world allows has no rule");
    }

    @Test
    void noRulesFileReadIsTheClosedWorldsRule() {
        WorldRefusal why = WorldRefusal.of(WorldEffect.OUTSIDE_OWN_BASE, null, null);
        assertEquals("closed-base-reach", why.rule().id());
        assertEquals("base", why.noun());
    }

    @Test
    void heReadsTheBaseReachRuleAndHowToFixHisAim() {
        WorldRefusal why = WorldRefusal.of(WorldEffect.OUTSIDE_OWN_BASE, rules(), "island");
        assertEquals("Your Cozy House can't go there: a wish stays on your island — so every island stays its owner's. "
                + "Aim it at your own island and wish again!", WorldRulesWords.madeRefused("Your Cozy House", why));
    }

    @Test
    void heReadsTheDenyRuleAndWhereItWorks() {
        WorldRefusal why = WorldRefusal.of(WorldEffect.WORLDWIDE_DATAPACKS_STANDING_RULES, rules(), "island");
        assertEquals("Your Jump Fireworks can't work on this world: a rule here can't change the whole world — every "
                + "island shares it. It works in " + rules().workshop() + "!",
                WorldRulesWords.madeRefused("Your Jump Fireworks", why));
    }

    @Test
    void aReplayHasNoNameAndSaysThatWish() {
        WorldRefusal why = WorldRefusal.of(WorldEffect.TIME, rules(), "island");
        String line = WorldRulesWords.madeRefused(null, why);
        assertTrue(line.startsWith("That wish can't work on this world: time is the same for every island — so the sun "
                + "rises for everyone together."), line);
    }

    @Test
    void theLineIsShortAndSaysNoPriceAndNoQueue() {
        for (WorldEffect e : new WorldEffect[] {WorldEffect.OUTSIDE_OWN_BASE, WorldEffect.TIME}) {
            String line = WorldRulesWords.madeRefused("Your Cozy House", WorldRefusal.of(e, rules(), "island"));
            assertTrue(line.length() <= 200, "a child's line stays short: " + line.length() + " " + line);
            String low = line.toLowerCase(java.util.Locale.ROOT);
            assertFalse(low.contains("refused") || low.contains("wish bank") || low.contains("price"), line);
        }
    }
}
