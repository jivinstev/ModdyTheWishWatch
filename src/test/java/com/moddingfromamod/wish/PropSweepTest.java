package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Scaffolding is what the wish made and did not keep —.</b>
 *
 * <p>Every case here is written from the real action log rather than invented. Across 147 real
 * {@code run_command} calls there are 17 summons and all 17 are test props: eleven armour stands,
 * three markers, an area-effect cloud, and a pig and a chicken both summoned at a fire target's
 * coordinates with {@code NoAI:1b}. The two named below are verbatim.
 *
 * <p>The tests are deliberately lopsided towards the KEEP rules, because the two directions are not
 * equally bad: failing to tidy leaves an armour stand standing, and tidying too eagerly deletes
 * something a child asked for.
 */
class PropSweepTest {

    private static PropSweep.Spawned prop(String type) {
        return new PropSweep.Spawned(UUID.randomUUID(), "", type, false, false, false);
    }

    /**
     * <b>The one that was caught, verbatim.</b> {@code summon minecraft:armor_stand -2 -60 4
     * {Tags:["firetest"],Invulnerable:0b,NoGravity:0b}} — no {@code Invisible}, no {@code Marker},
     * so it renders as a full armour stand and it was still there five seconds after the wish ended.
     */
    @Test
    @DisplayName("the fire-target armour stand is scaffolding and comes down")
    void theArmourStandComesDown() {
        PropSweep.Spawned stand = prop("minecraft:armor_stand");
        assertEquals(PropSweep.Kept.NOT_KEPT, PropSweep.verdict(stand, List.of()));
        assertEquals(List.of(stand.id()), PropSweep.toRemove(List.of(stand), List.of()));
    }

    /**
     * The rule is about what the wish kept, NOT about what the prop is — so the same verdict has to
     * fall on a chicken, which is what the very next run used.
     */
    @Test
    @DisplayName("…and so does the chicken, because the rule is not a list of prop-shaped types")
    void aLivingPropIsStillAProp() {
        assertEquals(PropSweep.Kept.NOT_KEPT, PropSweep.verdict(prop("minecraft:chicken"),
                List.of("spikor")));
        assertEquals(PropSweep.Kept.NOT_KEPT, PropSweep.verdict(prop("minecraft:marker"),
                List.of("spikor")));
        assertEquals(PropSweep.Kept.NOT_KEPT, PropSweep.verdict(prop("minecraft:area_effect_cloud"),
                List.of("spikor")));
    }

    /**
     * <b>A player is never scaffolding, whatever else is true.</b> Unreachable in practice — a
     * summon cannot make one — and asserted anyway, because this is the single worst thing a sweep
     * could do and "it cannot happen" is not a property a later refactor preserves.
     */
    @Test
    @DisplayName("a player is never swept, and the rule does not depend on anything else")
    void aPlayerIsNeverSwept() {
        PropSweep.Spawned child = new PropSweep.Spawned(
                UUID.randomUUID(), "", "minecraft:player", true, false, false);
        assertEquals(PropSweep.Kept.PLAYER, PropSweep.verdict(child, List.of()));
        assertTrue(PropSweep.toRemove(List.of(child), List.of()).isEmpty());
    }

    @Test
    @DisplayName("something the wish REGISTERED is what the child asked for, and stays")
    void aRegisteredThingStays() {
        PropSweep.Spawned kept = new PropSweep.Spawned(
                UUID.randomUUID(), "spikor", "minecraft:bee", false, false, false);
        assertEquals(PropSweep.Kept.REGISTERED, PropSweep.verdict(kept, List.of("spikor")));
        assertTrue(PropSweep.toRemove(List.of(kept), List.of("spikor")).isEmpty(),
                "a command that summoned the creature itself, and a wish that then recorded it, "
                        + "must survive its own tidy-up");
    }

    /**
     * <b>The two sides of that comparison are spelled differently, and a near-miss is silent.</b>
     *
     * <p>{@code EntityHandles} mints {@code @bee}; what a run records in {@code made()} is the
     * argument the tool was called with — {@code modify_mob}'s target carries the {@code @}, an
     * artifact id does not. A keep rule that missed on a leading character would never fire, which
     * looks exactly like not having written one.
     *
     * <p>That case is the whole reason this rule exists: an agent that summons something by command
     * and then MODIFIES it has said, in the only way it can, that it means to keep it.
     */
    @Test
    @DisplayName("…matched without the sigil, case-insensitively, ends trimmed")
    void theHandleMatchIsForgiving() {
        PropSweep.Spawned kept = new PropSweep.Spawned(
                UUID.randomUUID(), "  Spikor ", "minecraft:bee", false, false, false);
        assertEquals(PropSweep.Kept.REGISTERED, PropSweep.verdict(kept, List.of("spikor")));

        PropSweep.Spawned withSigil = new PropSweep.Spawned(
                UUID.randomUUID(), "@bee", "minecraft:bee", false, false, false);
        assertEquals(PropSweep.Kept.REGISTERED, PropSweep.verdict(withSigil, List.of("@bee")),
                "modify_mob records its target with the sigil on both sides");
        assertEquals(PropSweep.Kept.REGISTERED, PropSweep.verdict(withSigil, List.of("bee")),
                "and a wish that recorded the bare name must still keep it");
    }

    /**
     * <b>Deleting something a child is sitting on dismounts him mid-wish.</b> Cheap to check and
     * obviously right, so it is a rule rather than a thing to find out about later.
     */
    @Test
    @DisplayName("anything carrying a rider, or riding one, is left alone")
    void aRiddenThingIsLeftAlone() {
        PropSweep.Spawned mount = new PropSweep.Spawned(
                UUID.randomUUID(), "", "minecraft:horse", false, true, false);
        assertEquals(PropSweep.Kept.CARRYING, PropSweep.verdict(mount, List.of()));
        assertTrue(PropSweep.toRemove(List.of(mount), List.of()).isEmpty());
    }

    /**
     * <b>The keep rules beat the sweep rule, not the other way round.</b> A prop that is BOTH
     * unregistered and ridden must survive — the ordering inside {@code verdict} is the whole
     * safety property, and an inverted one would still pass every single-rule test above.
     */
    @Test
    @DisplayName("a keep rule wins even when every other signal says scaffolding")
    void keepRulesBeatTheFallthrough() {
        PropSweep.Spawned ridden = new PropSweep.Spawned(
                UUID.randomUUID(), "", "minecraft:armor_stand", false, true, false);
        assertEquals(PropSweep.Kept.CARRYING, PropSweep.verdict(ridden, List.of()),
                "an armour stand with a child standing on it is not tidy-up-able, however much it "
                        + "looks like the prop in the issue");
    }

    /**
     * <b>Read off the corpus, not invented.</b> All seventeen real props carry
     * {@code Tags:["firetest"]} and <b>none carries a {@code CustomName}</b>, so "somebody named
     * it" keeps the class of thing the fallthrough would otherwise reach — a named item frame hung
     * as part of a build, a named villager — at a measured cost of zero props missed.
     */
    @Test
    @DisplayName("anything somebody NAMED is kept, because naming it is meaning to keep it")
    void aNamedThingIsKept() {
        PropSweep.Spawned statue = new PropSweep.Spawned(
                UUID.randomUUID(), "", "minecraft:armor_stand", false, false, true);
        assertEquals(PropSweep.Kept.NAMED, PropSweep.verdict(statue, List.of()),
                "an armour stand is the exact shape of the prop in #517 — and a NAMED one is far "
                        + "more likely to be a statue somebody wished for");
        assertTrue(PropSweep.toRemove(List.of(statue), List.of()).isEmpty());
    }

    @Test
    @DisplayName("a mixed batch removes only the props, and never twice")
    void aMixedBatch() {
        PropSweep.Spawned stand = prop("minecraft:armor_stand");
        PropSweep.Spawned chicken = prop("minecraft:chicken");
        PropSweep.Spawned creature = new PropSweep.Spawned(
                UUID.randomUUID(), "spikor", "minecraft:bee", false, false, false);
        List<UUID> out = PropSweep.toRemove(
                List.of(stand, chicken, creature, stand), List.of("spikor"));
        assertEquals(List.of(stand.id(), chicken.id()), out,
                "in the order they were spawned, with the duplicate collapsed");
    }

    @Test
    @DisplayName("nothing spawned means nothing said")
    void aQuietWishSaysNothing() {
        assertEquals("", PropSweep.report(0, 0, 0));
        assertTrue(PropSweep.toRemove(null, List.of()).isEmpty());
        assertEquals(PropSweep.Kept.NOT_KEPT, PropSweep.verdict(null, List.of()));
    }

    /**
     * <b>The removal is REPORTED, and that is not decoration.</b> A silent tidy-up is the shape
     * this repo keeps writing up: the world changes, the run says nothing, and the next person to
     * wonder why a prop vanished has no thread to pull. It is also a level-2 check on the repo's
     * own ladder — told to the agent inside the authoring tool, before the reveal.
     */
    @Test
    @DisplayName("what was taken down is said out loud, in the child's terms")
    void theSweepSaysWhatItDid() {
        String one = PropSweep.report(1, 0, 0);
        assertTrue(one.contains("1 test prop") && !one.contains("props"), one);
        String many = PropSweep.report(3, 2, 1);
        assertTrue(many.contains("3 test props"), many);
        assertTrue(many.contains("2 you had already cleared"),
                "an agent that tidied up after itself should be told so, not silently credited "
                        + "with nothing: " + many);
        assertTrue(many.contains("part of the wish"), many);
        assertFalse(PropSweep.report(0, 1, 0).isBlank(),
                "'you had already cleared it' is worth saying — it is the difference between the "
                        + "sweep working and the sweep never having run");
    }

    // =====================: a living mob with its AI on is never a prop ===========

    /**
     * <b>The child's creepers, verbatim from production.</b> {@code "make 100 creepers"}: summoned
     * by {@code run_command}, unnamed, unregistered, not ridden — every rule above missed, and all
     * 100 were removed 3.5 s after the reveal.
     */
    @Test
    @DisplayName("#1112: a creeper with its AI on is LIVING and stays")
    void aCreeperWithItsAiOnIsKept() {
        PropSweep.Spawned creeper = new PropSweep.Spawned(
                UUID.randomUUID(), "@creeper", "minecraft:creeper", false, false, false, true);
        assertEquals(PropSweep.Kept.LIVING, PropSweep.verdict(creeper, List.of()));
        assertTrue(PropSweep.toRemove(List.of(creeper), List.of()).isEmpty());
    }

    /**
     * <b>an earlier issue's real living props still come down</b>: the pig and the chicken were summoned at a
     * fire target's coordinates with {@code NoAI:1b}, so {@code living} is false for them.
     */
    @Test
    @DisplayName("#1112: a NoAI pig is still a prop and comes down")
    void aNoAiPigIsStillRemoved() {
        PropSweep.Spawned pig = new PropSweep.Spawned(
                UUID.randomUUID(), "@pig", "minecraft:pig", false, false, false, false);
        assertEquals(PropSweep.Kept.NOT_KEPT, PropSweep.verdict(pig, List.of()));
        assertEquals(List.of(pig.id()), PropSweep.toRemove(List.of(pig), List.of()));
    }

    @Test
    @DisplayName("#1112: an armour stand is not a mob, so it is still a prop")
    void anArmourStandIsStillRemoved() {
        PropSweep.Spawned stand = new PropSweep.Spawned(
                UUID.randomUUID(), "", "minecraft:armor_stand", false, false, false, false);
        assertEquals(PropSweep.Kept.NOT_KEPT, PropSweep.verdict(stand, List.of()));
    }

    /**
     * <b>The new rule is LAST</b>, so every verdict the existing rows pin reads as it did: a
     * registered, named or ridden mob with AI on still reports its old reason.
     */
    @Test
    @DisplayName("#1112: LIVING never relabels a thing an older rule already keeps")
    void livingIsTheLastKeepRule() {
        UUID id = UUID.randomUUID();
        assertEquals(PropSweep.Kept.REGISTERED, PropSweep.verdict(new PropSweep.Spawned(
                id, "spikor", "minecraft:bee", false, false, false, true), List.of("spikor")));
        assertEquals(PropSweep.Kept.NAMED, PropSweep.verdict(new PropSweep.Spawned(
                id, "", "minecraft:villager", false, false, true, true), List.of()));
        assertEquals(PropSweep.Kept.CARRYING, PropSweep.verdict(new PropSweep.Spawned(
                id, "", "minecraft:horse", false, true, false, true), List.of()));
    }

    @Test
    @DisplayName("#1112: a mixed wish keeps its herd and takes down its prop")
    void aHerdAndItsPropInOneWish() {
        PropSweep.Spawned stand = prop("minecraft:armor_stand");
        PropSweep.Spawned a = new PropSweep.Spawned(
                UUID.randomUUID(), "@creeper", "minecraft:creeper", false, false, false, true);
        PropSweep.Spawned b = new PropSweep.Spawned(
                UUID.randomUUID(), "@creeper2", "minecraft:creeper", false, false, false, true);
        assertEquals(List.of(stand.id()), PropSweep.toRemove(List.of(a, stand, b), List.of()));
    }

    private static PropSweep.Spawned kept(String type) {
        return new PropSweep.Spawned(UUID.randomUUID(), "", type, false, false, false, false, true);
    }

    @Test
    @DisplayName("#1343: three minecarts summoned with keep:true are the wish, not props")
    void markedMinecartsAreKept() {
        List<PropSweep.Spawned> cars = List.of(kept("minecraft:minecart"), kept("minecraft:minecart"),
                kept("minecraft:minecart"));
        for (PropSweep.Spawned c : cars) {
            assertEquals(PropSweep.Kept.MARKED, PropSweep.verdict(c, List.of()));
        }
        assertTrue(PropSweep.toRemove(cars, List.of()).isEmpty());
    }

    @Test
    @DisplayName("#1343: the mark keeps only what was marked — the unmarked prop beside it still goes")
    void theMarkIsPerThingNotPerWish() {
        PropSweep.Spawned car = kept("minecraft:minecart");
        PropSweep.Spawned probe = new PropSweep.Spawned(UUID.randomUUID(), "", "minecraft:armor_stand",
                false, false, false, false, false);
        assertEquals(List.of(probe.id()), PropSweep.toRemove(List.of(car, probe), List.of()));
    }

    @Test
    @DisplayName("#1343: every verdict before the mark reads exactly as it did")
    void theMarkComesLast() {
        assertEquals(PropSweep.Kept.LIVING, PropSweep.verdict(new PropSweep.Spawned(UUID.randomUUID(),
                "", "minecraft:pig", false, false, false, true, true), List.of()));
        assertEquals(PropSweep.Kept.NAMED, PropSweep.verdict(new PropSweep.Spawned(UUID.randomUUID(),
                "", "minecraft:armor_stand", false, false, true, false, true), List.of()));
        assertEquals(PropSweep.Kept.PLAYER, PropSweep.verdict(new PropSweep.Spawned(UUID.randomUUID(),
                "", "minecraft:player", true, false, false, false, true), List.of()));
    }

    @Test
    @DisplayName("#1343: the warning names what will be tidied, by kind, in one sentence")
    void theWarningCountsWhatWillGo() {
        String w = PropSweep.warning(List.of(prop("minecraft:minecart"), prop("minecraft:minecart"),
                prop("minecraft:minecart")), List.of());
        assertEquals("\n(3 minecarts from this command will be tidied away when the wish ends. If they"
                + " are part of what he asked for, summon them with keep:true.)", w);
        String one = PropSweep.warning(List.of(prop("minecraft:armor_stand")), List.of());
        assertTrue(one.startsWith("\n(1 armor stand from") && one.contains("If it is part")
                && one.contains("summon it with keep:true"), one);
        String mixed = PropSweep.warning(List.of(prop("minecraft:oak_boat"), prop("minecraft:item"),
                prop("minecraft:item")), List.of());
        assertTrue(mixed.startsWith("\n(1 oak boat, 2 items from"), mixed);
    }

    @Test
    @DisplayName("#1343: no warning for what the sweep already keeps — alive, named, ridden, registered, marked")
    void noWarningForWhatIsKept() {
        UUID id = UUID.randomUUID();
        List<PropSweep.Spawned> keptAlready = List.of(
                new PropSweep.Spawned(id, "", "minecraft:cow", false, false, false, true),
                new PropSweep.Spawned(UUID.randomUUID(), "", "minecraft:armor_stand", false, false, true),
                new PropSweep.Spawned(UUID.randomUUID(), "", "minecraft:minecart", false, true, false),
                new PropSweep.Spawned(UUID.randomUUID(), "@stand", "minecraft:armor_stand", false, false,
                        false),
                kept("minecraft:oak_boat"));
        assertEquals("", PropSweep.warning(keptAlready, List.of("stand")));
        assertEquals("", PropSweep.warning(List.of(), List.of()));
        assertEquals("", PropSweep.warning(null, null));
    }

    @Test
    @DisplayName("#1343: many kinds are summarised, so one call cannot grow the result without bound")
    void manyKindsAreSummarised() {
        String w = PropSweep.warning(List.of(prop("minecraft:a"), prop("minecraft:b"), prop("minecraft:c"),
                prop("minecraft:d"), prop("minecraft:e")), List.of());
        assertTrue(w.startsWith("\n(1 a, 1 b, 1 c, 2 other kinds from"), w);
    }

    @Test
    @DisplayName("#1343: the warning names only what a command SUMMONS — never a kill's dropped loot")
    void summonedTypesReadsTheSummonsOnly() {
        assertEquals(java.util.Set.of("minecraft:minecart", "minecraft:armor_stand", "create:thing"),
                PropSweep.summonedTypes(List.of("summon minecart ~ ~ ~",
                        "execute positioned 1 2 3 run summon minecraft:armor_stand ~ ~ ~ {NoAI:1b}",
                        "/summon create:thing", "kill @e[tag=pulltest]", "give @s egg 64")));
        assertTrue(PropSweep.summonedTypes(null).isEmpty());
    }

    /** Marked, but a thing the mark cannot keep: a frozen mob, a drop, a projectile. */
    private static PropSweep.Spawned markedProbe(String type) {
        return new PropSweep.Spawned(UUID.randomUUID(), "", type, false, false, false, false, true,
                false);
    }

    @Test
    @DisplayName("#1343: keep:true cannot keep a frozen mob, a dropped item or a projectile")
    void theMarkCannotKeepWhatOnlyATestMakes() {
        for (String t : List.of("minecraft:pig", "minecraft:item", "minecraft:trident")) {
            PropSweep.Spawned p = markedProbe(t);
            assertEquals(PropSweep.Kept.NOT_KEPT, PropSweep.verdict(p, List.of()), t);
            assertEquals(List.of(p.id()), PropSweep.toRemove(List.of(p), List.of()), t);
        }
    }

    @Test
    @DisplayName("#1343: a name still keeps a frozen mob the mark cannot — the statue he asked for")
    void aNameKeepsAFrozenStatueTheMarkCannot() {
        PropSweep.Spawned statue = new PropSweep.Spawned(UUID.randomUUID(), "", "minecraft:pig", false,
                false, true, false, true, false);
        assertEquals(PropSweep.Kept.NAMED, PropSweep.verdict(statue, List.of()));
    }

    @Test
    @DisplayName("#1343: a refused mark is SAID, separately from the unmarked warning, and says how to keep")
    void aRefusedMarkIsSaid() {
        String w = PropSweep.warning(List.of(markedProbe("minecraft:pig"), markedProbe("minecraft:item"),
                prop("minecraft:armor_stand"), kept("minecraft:oak_boat")), List.of());
        assertTrue(w.contains("\n(1 armor stand from this command will be tidied away"), w);
        assertTrue(w.contains("\n(keep:true does not keep 1 pig, 1 item: "), w);
        assertTrue(w.contains("CustomName") && w.contains("give"), w);
        assertFalse(w.contains("oak boat"), w);
        assertEquals("", PropSweep.warning(List.of(kept("minecraft:minecart")), List.of()));
    }
}
