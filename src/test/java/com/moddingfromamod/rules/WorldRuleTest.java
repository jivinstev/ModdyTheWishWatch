package com.moddingfromamod.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/** The pure half of set_rule: what a rule means, and what is refused. */
class WorldRuleTest {

    private static WorldRule rule(String json) {
        return WorldRule.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static String refusal(String json) {
        return assertThrows(IllegalArgumentException.class, () -> rule(json)).getMessage();
    }

    @Test
    void eachTriggerDefaultsWhoToWhatItIsAbout() {
        assertEquals(WorldRule.ANY, rule("{when:'mob_spawns', do:['say hi']}").who());
        assertEquals(WorldRule.ANY, rule("{when:'mob_dies', do:['say hi']}").who());
        assertEquals(WorldRule.PLAYER, rule("{when:'jumps', do:['say hi']}").who());
        assertEquals(WorldRule.PLAYER, rule("{when:'every', do:['say hi']}").who());
        assertEquals(WorldRule.PLAYER, rule("{when:'sunrise', do:['say hi']}").who());
        assertNull(rule("{when:'projectile_hits', do:['say hi']}").who(),
                "a thrown thing can hit anything, the child included");
    }

    @Test
    void playerOnlyTriggersAreAboutThePlayerWhateverWhoSays() {
        assertEquals(WorldRule.PLAYER, rule("{when:'sneaks', who:'zombie', do:['say hi']}").who());
        assertEquals(WorldRule.PLAYER, rule("{when:'near_block', block:'torch', grow:3}").who());
    }

    @Test
    void idsGetTheirNamespaceAndTagsKeepTheirHash() {
        WorldRule r = rule("{when:'mob_dies', who:'Creeper', block:'#beds', do:['say hi']}");
        assertEquals("minecraft:creeper", r.who());
        assertEquals("#minecraft:beds", r.block());
        assertEquals(WorldRule.ANY, rule("{when:'mob_spawns', who:'all', do:['say hi']}").who());
        assertEquals(WorldRule.PLAYER, rule("{when:'lands', who:'me', push:{up:1}}").who());
    }

    @Test
    void aRuleThatDoesNothingIsRefused() {
        assertTrue(refusal("{when:'mob_dies', who:'zombie'}").contains("does nothing"));
        assertTrue(refusal("{when:'lands', push:{up:0}}").contains("does nothing"),
                "a zero push is no push");
    }

    @Test
    void theRefusalsNameTheFix() {
        assertTrue(refusal("{when:'tuesday', do:['say hi']}").contains("mob_spawns"));
        assertTrue(refusal("{when:'near_player', do:['say hi']}").contains("who"));
        assertTrue(refusal("{when:'near_block', do:['say hi']}").contains("block"));
        assertTrue(refusal("{when:'mob_dies', who:'player', do:['say hi']}").contains("mobs"));
        assertTrue(refusal("{when:'mob_dies', harmless:true}").contains("explodes"));
        assertTrue(refusal("{when:'jumps', who:'world', do:['say hi']}").contains("timed"));
        assertTrue(refusal("{when:'jumps', chance:0, do:['say hi']}").contains("chance"));
        assertTrue(refusal("{when:'jumps', only:'tuesday', do:['say hi']}").contains("day"));
    }

    @Test
    void numbersAreClampedNotTrusted() {
        WorldRule r = rule("{when:'every', seconds:0, grow:99, push:{up:50}, radius:500}");
        assertEquals(WorldRule.MIN_SECONDS, r.seconds());
        assertEquals(WorldRule.MAX_GROW, r.grow());
        assertEquals(WorldRule.Push.MAX, r.push().up());
        assertEquals(WorldRule.MAX_RADIUS, r.radius());
    }

    @Test
    void commandsLoseALeadingSlashAndAreCapped() {
        assertEquals(List.of("say hi"), rule("{when:'jumps', do:'/say hi'}").commands());
        JsonObject a = JsonParser.parseString("{when:'jumps', do:[]}").getAsJsonObject();
        for (int i = 0; i <= WorldRule.MAX_COMMANDS; i++) {
            a.getAsJsonArray("do").add("say " + i);
        }
        assertThrows(IllegalArgumentException.class, () -> WorldRule.parse(a));
    }

    @Test
    void aNameIsMadeWhenNoneIsGivenAndIsAlwaysASafeKey() {
        assertEquals("mob_dies_creeper", rule("{when:'mob_dies', who:'creeper', do:['say hi']}").name());
        assertEquals("my_cool_rule", rule("{when:'jumps', name:'My Cool Rule!!', do:['say hi']}").name());
    }

    @Test
    void theStoredCopyReadsBackTheSame() {
        WorldRule r = rule("{when:'projectile_hits', projectile:'snowball', who:'#minecraft:skeletons', "
                + "chance:0.5, only:'night', push:{away:1.5, up:0.3}, do:['particle heart ~ ~1 ~'], "
                + "name:'snow'}");
        assertEquals(r, WorldRule.fromJson(r.toJson()));
        WorldRule e = rule("{when:'explodes', who:'creeper', harmless:true, name:'confetti'}");
        assertEquals(e, WorldRule.fromJson(e.toJson()));
    }

    @Test
    void whoMatchesByIdByTagAndNeverMixesPlayersAndMobs() {
        WorldRule zombies = rule("{when:'mob_dies', who:'zombie', do:['say hi']}");
        assertTrue(zombies.whoMatches(false, "minecraft:zombie", t -> false));
        assertFalse(zombies.whoMatches(false, "minecraft:husk", t -> false));
        WorldRule tagged = rule("{when:'mob_dies', who:'#minecraft:undead', do:['say hi']}");
        assertTrue(tagged.whoMatches(false, "minecraft:husk", "minecraft:undead"::equals));
        WorldRule any = rule("{when:'mob_dies', do:['say hi']}");
        assertFalse(any.whoMatches(true, "minecraft:player", t -> false), "'any mob' is not him");
        WorldRule me = rule("{when:'jumps', do:['say hi']}");
        assertTrue(me.whoMatches(true, "minecraft:player", t -> false));
        assertFalse(me.whoMatches(false, "minecraft:pig", t -> false));
    }

    /** {@code who: tag=…} names the entities carrying that tag — his wished creatures — and never a player. */
    @Test
    void aWhoByEntityTagMatchesWhatCarriesItAndIsNoProblem() {
        WorldRule pets = rule("{when:'near_player', who:'tag=wishmob_zorbit', do:['effect give @s glowing 5']}");
        assertEquals("tag=wishmob_zorbit", pets.who());
        assertTrue(pets.whoMatches(false, "minecraft:pig", t -> false, "wishmob_zorbit"::equals));
        assertFalse(pets.whoMatches(false, "minecraft:pig", t -> false, "wishmob_other"::equals));
        assertFalse(pets.whoMatches(false, "minecraft:pig", t -> false), "the 3-argument form carries no tags");
        assertFalse(pets.whoMatches(true, "minecraft:player", t -> false, t -> true), "a player is never his pet");
        assertNull(WorldRule.whoProblem(pets.who(), id -> false, id -> false));
        assertTrue(pets.describe().contains("his zorbit"), pets.describe());
        assertThrows(IllegalArgumentException.class, () -> rule("{when:'mob_dies', who:'tag=a b!', do:['say hi']}"));
        assertEquals(pets, WorldRule.fromJson(pets.toJson()));
    }

    @Test
    void onlyAsksTheWorldAtTheMoment() {
        assertTrue(WorldRule.Only.NIGHT.holds(false, false));
        assertFalse(WorldRule.Only.NIGHT.holds(true, false));
        assertTrue(WorldRule.Only.RAIN.holds(true, true));
        assertFalse(WorldRule.Only.CLEAR.holds(true, true));
        assertTrue(WorldRule.Only.ANY.holds(false, true));
    }

    @Test
    void theDescriptionSaysWhenAndWhat() {
        String d = rule("{when:'jumps', block:'slime_block', push:{up:2}}").describe();
        assertTrue(d.startsWith("When he jumps standing on slime_block"), d);
        assertTrue(d.contains("a push"), d);
        String t = rule("{when:'every', seconds:30, who:'cow', chance:0.2, grow:2}").describe();
        assertTrue(t.contains("every 30 seconds") && t.contains("20%") && t.contains("plants grow"), t);
    }

    /** a `who` that names no entity type is a rule that never fires — refused, by why. */
    @Test
    void aWhoThatIsNoMobTypeIsRefused() {
        java.util.function.Predicate<String> types = id -> id.equals("minecraft:zombie");
        java.util.function.Predicate<String> wished = id -> id.equals("zorbit");
        assertNull(WorldRule.whoProblem("minecraft:zombie", types, wished));
        assertNull(WorldRule.whoProblem("#minecraft:skeletons", types, wished));
        assertNull(WorldRule.whoProblem(WorldRule.ANY, types, wished));
        assertNull(WorldRule.whoProblem(WorldRule.PLAYER, types, wished));
        assertNull(WorldRule.whoProblem(WorldRule.WORLD, types, wished));
        assertNull(WorldRule.whoProblem(null, types, wished));
        // The live cases: the species id, and its wishmob_ tag — both point at apply_effect.
        for (String who : List.of("zorbit", "wishmob_zorbit")) {
            String why = WorldRule.whoProblem(rule("{when:'mob_dies', who:'" + who + "', do:['say hi']}").who(),
                    types, wished);
            assertTrue(why != null && why.contains("WISHED creature") && why.contains("apply_effect")
                    && why.contains("tag=wishmob_zorbit"), String.valueOf(why));
        }
        String typo = WorldRule.whoProblem("minecraft:zombi", types, wished);
        assertTrue(typo != null && typo.contains("not a kind of mob"), String.valueOf(typo));
    }
}
