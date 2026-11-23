package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The renamed-attribute corrector —. */
class AttributeIdsTest {

    /** A 1.21.1-shaped registry: generic.* exists, the short names do not. */
    private static final Predicate<String> REGISTRY = Set.of(
            "minecraft:generic.scale",
            "minecraft:generic.movement_speed",
            "minecraft:generic.attack_damage",
            "minecraft:generic.max_health",
            "minecraft:generic.armor",
            "minecraft:generic.jump_strength",
            "minecraft:generic.safe_fall_distance",
            // Not every attribute carries the prefix, and this one must be left alone.
            "minecraft:player.block_interaction_range")::contains;

    private static String fix(String cmd) {
        return AttributeIds.correct(cmd, REGISTRY).command();
    }

    @Test
    @DisplayName("the exact command from the broken 'giant' button is repaired")
    void theRealFailure() {
        // Saved by add_template as the child's way back, and it could never have worked.
        assertEquals("attribute Dev minecraft:generic.scale base set 4",
                fix("attribute Dev minecraft:scale base set 4"));
    }

    @Test
    @DisplayName("every shape the agent has actually written")
    void theObservedVariants() {
        assertEquals("attribute @s minecraft:generic.scale base set 1.0",
                fix("attribute @s minecraft:scale base set 1.0"));
        assertEquals("attribute @e[tag=wishgiant,limit=1] minecraft:generic.scale base set 4",
                fix("attribute @e[tag=wishgiant,limit=1] minecraft:scale base set 4"));
        assertEquals("attribute @p minecraft:generic.movement_speed base set 0.16",
                fix("attribute @p minecraft:movement_speed base set 0.16"));
        assertEquals("attribute @s minecraft:generic.attack_damage base set 12",
                fix("attribute @s minecraft:attack_damage base set 12"));
    }

    @Test
    @DisplayName("a bare name keeps its shape rather than gaining a namespace")
    void keepsTheAuthorsShape() {
        assertEquals("attribute Dev generic.scale base set 5",
                fix("attribute Dev scale base set 5"));
    }

    @Test
    @DisplayName("an id that already works is left completely alone")
    void doesNotTouchWhatWorks() {
        for (String good : new String[] {
                "attribute Dev minecraft:generic.scale base set 5",
                "attribute Dev generic.scale base set 5",
                "attribute @p minecraft:player.block_interaction_range base set 12"}) {
            assertEquals(good, fix(good), "rewrote a command that was already correct");
        }
    }

    @Test
    @DisplayName("an attribute nobody has heard of is left for Minecraft to complain about")
    void unknownIsNotInvented() {
        // Correcting to a generic.* that does not exist either would swap one failure for another
        // and hide which one it was.
        assertEquals("attribute Dev minecraft:wibble base set 1",
                fix("attribute Dev minecraft:wibble base set 1"));
    }

    @Test
    @DisplayName("commands that are not about attributes are untouched")
    void onlyAttributeCommands() {
        for (String other : new String[] {
                "give @p minecraft:diamond 12",
                "effect give @s minecraft:speed infinite 3 true",
                "summon minecraft:zombie ~ ~ ~ {Tags:[\"x\"]}"}) {
            assertEquals(other, fix(other));
        }
    }

    @Test
    @DisplayName("it says what it changed, so the correction is not silent")
    void reportsWhatItDid() {
        var fixed = AttributeIds.correct("attribute Dev minecraft:scale base set 4", REGISTRY);
        assertFalse(fixed.notes().isEmpty(), "a silent correction teaches nobody");
        assertTrue(fixed.notes().get(0).contains("minecraft:scale"));
        assertTrue(fixed.notes().get(0).contains("generic.scale"));
        assertTrue(AttributeIds.correct("give @p minecraft:diamond 1", REGISTRY).notes().isEmpty());
    }

    @Test
    @DisplayName("more than one attribute in one command is handled")
    void multipleInOneString() {
        String out = fix("attribute @s minecraft:scale base set 4 "
                + "attribute @s minecraft:movement_speed base set 0.2");
        assertTrue(out.contains("minecraft:generic.scale"), out);
        assertTrue(out.contains("minecraft:generic.movement_speed"), out);
    }

    // ── the attribute_modifiers COMPONENT, which the command-only matcher let straight through ──
    //
    // Every command in this block is copied verbatim out of corpus/real-tool-calls.jsonl. The
    // agent wrote them, the server refused them with `Unknown registry key ... minecraft:attribute`,
    // and the agent then re-sent the same thing with `generic.` — so the whole cost was a round
    // trip spent rediscovering what this class already knew for the `attribute` command form.

    @Test
    @DisplayName("the REAL super-jump helmet the agent could not give a child")
    void theJumpHelmetComponent() {
        String out = fix("give @p minecraft:diamond_helmet[minecraft:custom_name='{\"text\":\"Super "
                + "Jump Helmet\",\"color\":\"aqua\",\"italic\":false}',minecraft:enchantment_glint_"
                + "override=true,minecraft:unbreakable={},minecraft:attribute_modifiers=[{type:\""
                + "minecraft:jump_strength\",id:\"wish:super_jump\",amount:1.2,operation:\"add_value\","
                + "slot:\"head\"},{type:\"minecraft:safe_fall_distance\",id:\"wish:soft_landing\","
                + "amount:20,operation:\"add_value\",slot:\"head\"},{type:\"minecraft:armor\",id:\""
                + "minecraft:armor.helmet\",amount:3,operation:\"add_value\",slot:\"head\"}]] 1");
        assertTrue(out.contains("type:\"minecraft:generic.jump_strength\""), out);
        assertTrue(out.contains("type:\"minecraft:generic.safe_fall_distance\""), out);
        assertTrue(out.contains("type:\"minecraft:generic.armor\""), out);
        // `id:` is the MODIFIER's own name, not an attribute. Rewriting it would be a different bug.
        assertTrue(out.contains("id:\"minecraft:armor.helmet\""), "rewrote a modifier id: " + out);
        assertTrue(out.contains("id:\"wish:super_jump\""), out);
    }

    @Test
    @DisplayName("the REAL speed boots, and the other component shape")
    void theSpeedBootsComponent() {
        // Two syntaxes carry modifiers: a bare list, and a {modifiers:[…]} object. The agent has
        // written both, in consecutive calls, and only one of them being handled is how a fix
        // looks green while the failure survives.
        assertTrue(fix("give @p minecraft:diamond_boots[minecraft:attribute_modifiers=[{type:\""
                + "minecraft:movement_speed\",id:\"wish:speed_boots\",amount:0.6,operation:\""
                + "add_multiplied_base\",slot:\"feet\"}]] 1")
                .contains("minecraft:generic.movement_speed"));
        assertTrue(fix("give @p minecraft:diamond_boots[minecraft:attribute_modifiers={modifiers:[{"
                + "type:\"minecraft:armor\",id:\"wish:boots_armor\",amount:3,operation:\"add_value\","
                + "slot:\"feet\"}]}] 1")
                .contains("minecraft:generic.armor"));
    }

    @Test
    @DisplayName("A `type:` OUTSIDE an attribute_modifiers value is never touched")
    void onlyInsideTheComponent() {
        // The registry predicate would almost certainly make a looser match harmless — but almost
        // certainly is not a reason, and unrelated NBT is not this class's business.
        for (String other : new String[] {
                "summon minecraft:armor_stand ~ ~ ~ {type:\"minecraft:armor\"}",
                "data merge entity @s {Item:{type:\"minecraft:movement_speed\"}}",
                "give @p minecraft:stick[minecraft:custom_data={type:\"minecraft:scale\"}] 1"}) {
            assertEquals(other, fix(other), "rewrote a type: that names no attribute modifier");
        }
    }

    @Test
    @DisplayName("a component value that already works is left alone, and says nothing")
    void theSelfCorrectedFormIsAcceptedAsIs() {
        // This is the command the agent sent on its NEXT turn, having worked it out the expensive
        // way. It must pass through untouched, or the correction would be reported every time.
        String good = "give @p minecraft:diamond_helmet[minecraft:attribute_modifiers={modifiers:[{"
                + "type:\"minecraft:generic.armor\",id:\"wish:jump_armor\",amount:3,operation:\""
                + "add_value\",slot:\"head\"}]}] 1";
        var fixed = AttributeIds.correct(good, REGISTRY);
        assertEquals(good, fixed.command());
        assertTrue(fixed.notes().isEmpty(), "announced a correction it did not make");
    }

    @Test
    @DisplayName("brackets inside a quoted custom_name do not end the scan early")
    void quotedTextIsNotStructure() {
        // A custom name is arbitrary text a child chose, so it can hold any bracket at all. If the
        // bracket scan counted those, the modifier after it would silently never be corrected --
        // and the command would fail exactly as before, with the fix reporting success.
        String out = fix("give @p minecraft:diamond_helmet[minecraft:attribute_modifiers=[{type:\""
                + "minecraft:armor\",id:\"wish:a]}[{\",amount:3,operation:\"add_value\",slot:\"head\"},"
                + "{type:\"minecraft:jump_strength\",id:\"wish:b\",amount:1,operation:\"add_value\","
                + "slot:\"head\"}]] 1");
        assertTrue(out.contains("minecraft:generic.armor"), out);
        assertTrue(out.contains("minecraft:generic.jump_strength"),
                "a bracket inside a quoted string ended the scan early: " + out);
    }

    @Test
    @DisplayName("an unknown attribute in a component is left for Minecraft to complain about")
    void unknownInComponentIsNotInvented() {
        String same = "give @p minecraft:stick[minecraft:attribute_modifiers=[{type:\""
                + "minecraft:wibble\",id:\"wish:x\",amount:1,operation:\"add_value\",slot:\"head\"}]] 1";
        assertEquals(same, fix(same));
    }

    @Test
    @DisplayName("a malformed component neither throws nor mangles the rest of the command")
    void unbalancedInputIsSurvivable() {
        // The agent writes these by hand, so a missing bracket is a matter of time. Corrupting the
        // command here would turn a syntax error the agent can read into one it cannot.
        for (String broken : new String[] {
                "give @p minecraft:stick[minecraft:attribute_modifiers=[{type:\"minecraft:armor\"",
                "give @p minecraft:stick[minecraft:attribute_modifiers=",
                "give @p minecraft:stick[minecraft:attribute_modifiers=[{type:\"minecraft:armor\"}]"}) {
            String out = fix(broken);
            assertTrue(out.startsWith("give @p minecraft:stick[minecraft:attribute_modifiers="),
                    "mangled a malformed command: " + out);
        }
    }

    @Test
    @DisplayName("a space after the = does not make the whole component invisible")
    void whitespaceAfterTheEqualsIsSkipped() {
        // A false negative here is the worst kind: it looks exactly like the bug being fixed, and
        // every other test in this class still passes.
        assertTrue(fix("give @p minecraft:stick[minecraft:attribute_modifiers= [{type:\""
                + "minecraft:armor\",id:\"wish:a\",amount:3,operation:\"add_value\",slot:\"head\"}]] 1")
                .contains("minecraft:generic.armor"));
    }

    @Test
    @DisplayName("BOTH syntaxes in one command, because a wish gives more than one thing")
    void theCommandFormAndTheComponentFormTogether() {
        String out = fix("execute as @p run attribute @s minecraft:scale base set 2");
        assertTrue(out.contains("minecraft:generic.scale"), out);
        var fixed = AttributeIds.correct("give @p minecraft:diamond_helmet["
                + "minecraft:attribute_modifiers=[{type:\"minecraft:armor\",id:\"wish:a\",amount:3,"
                + "operation:\"add_value\",slot:\"head\"}]] 1", REGISTRY);
        assertEquals(1, fixed.notes().size(), fixed.notes().toString());
        assertTrue(fixed.notes().get(0).contains("minecraft:armor -> minecraft:generic.armor"),
                fixed.notes().toString());
    }

    /**
     * STEVE'S SHARK, 10 Sep 2026 — the third syntax, and the only one that fails silently.
     *
     * <p>His re-wish matched the template the agent had taught itself, which carried the short ids.
     * The summon SUCCEEDED, logged "Ignoring unknown attribute" at WARN, and delivered an ordinary
     * great white while the game announced a Great Big Shark.
     */
    @Test
    void itFixesAttributeIdsInSummonNbt() {
        String broken = "summon hybrid_aquatic:great_white_shark ~ ~ ~ "
                + "{CustomName:'\"Great Big Shark\"',Health:300f,"
                + "attributes:[{id:\"minecraft:max_health\",base:300},"
                + "{id:\"minecraft:scale\",base:4.0},"
                + "{id:\"minecraft:attack_damage\",base:20}]}";
        String fixed = fix(broken);
        assertTrue(fixed.contains("minecraft:generic.max_health"), fixed);
        assertTrue(fixed.contains("minecraft:generic.scale"), fixed);
        assertTrue(fixed.contains("minecraft:generic.attack_damage"), fixed);
        assertFalse(fixed.contains("\"minecraft:max_health\""), fixed);
        // Everything that was already right survives untouched.
        assertTrue(fixed.contains("base:300"), fixed);
        assertTrue(fixed.contains("hybrid_aquatic:great_white_shark"), fixed);
        assertTrue(fixed.contains("Health:300f"), fixed);
    }

    /**
     * SCOPE. `id:` means something different nearly everywhere in vanilla NBT, so the rewrite must
     * only reach inside an `attributes:[…]` list. An item id that happens to share an attribute's
     * name would otherwise be silently corrupted — trading one silent failure for another.
     */
    @Test
    void itLeavesIdsOutsideAnAttributesListAlone() {
        String items = "summon minecraft:armor_stand ~ ~ ~ {Items:[{id:\"minecraft:scale\",Count:1b}]}";
        assertEquals(items, fix(items));

        String effects = "summon minecraft:zombie ~ ~ ~ {active_effects:[{id:\"minecraft:max_health\"}]}";
        assertEquals(effects, fix(effects));
    }

    /**
     * A NESTED attributes list, which is how a template summons a rider or a mount — and the case
     * that most needs the scoping to be right, because the entity ids sitting beside it look
     * exactly like attribute ids and must survive untouched.
     */
    @Test
    void itFixesANestedAttributesListWithoutTouchingEntityIds() {
        String cmd = "summon minecraft:zombie ~ ~ ~ {attributes:[{id:\"minecraft:max_health\",base:80}],"
                + "Passengers:[{id:\"minecraft:chicken\",attributes:[{id:\"minecraft:scale\",base:3}]}]}";
        String fixed = fix(cmd);
        assertTrue(fixed.contains("minecraft:generic.max_health"), fixed);
        assertTrue(fixed.contains("minecraft:generic.scale"), fixed);
        // The passenger is an ENTITY id that merely sits next to an attributes list.
        assertTrue(fixed.contains("id:\"minecraft:chicken\""), fixed);
        assertFalse(fixed.contains("generic.chicken"), fixed);
    }
}
