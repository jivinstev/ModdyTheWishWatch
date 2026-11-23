package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Gate A for entity handles — the thing that turns "make it bigger" from a proximity guess into an
 * exact reference.
 */
class EntityHandlesTest {

    @Test
    void namesComeFromTheEntitysOwnId() {
        EntityHandles h = new EntityHandles();
        assertEquals("@wither_storm",
                h.remember(UUID.randomUUID(), "witherstormmod:wither_storm", null));
        assertEquals("@zombie", h.remember(UUID.randomUUID(), "minecraft:zombie", null));
    }

    @Test
    void aSecondOfTheSameKindGetsItsOwnName() {
        // Two zombies must be separately addressable, or "make it bigger" is ambiguous again.
        EntityHandles h = new EntityHandles();
        String first = h.remember(UUID.randomUUID(), "minecraft:zombie", null);
        String second = h.remember(UUID.randomUUID(), "minecraft:zombie", null);
        assertNotEquals(first, second);
        assertEquals("@zombie", first);
        assertEquals("@zombie2", second);
    }

    @Test
    void anExistingHandleIsNeverSilentlyRepointed() {
        // If the agent is holding @zombie, that name must keep meaning the SAME zombie.
        EntityHandles h = new EntityHandles();
        UUID original = UUID.randomUUID();
        h.remember(original, "minecraft:zombie", null);
        for (int i = 0; i < 5; i++) {
            h.remember(UUID.randomUUID(), "minecraft:zombie", null);
        }
        assertEquals(original, h.resolve("@zombie").orElseThrow().id());
    }

    @Test
    void resolvesTheFormsAnAgentMightWrite() {
        EntityHandles h = new EntityHandles();
        UUID id = UUID.randomUUID();
        h.remember(id, "witherstormmod:wither_storm", "The Wither Storm");

        assertEquals(id, h.resolve("@wither_storm").orElseThrow().id());
        assertEquals(id, h.resolve("wither_storm").orElseThrow().id(), "a missing @ is forgiven");
        assertEquals(id, h.resolve("@WITHER_STORM").orElseThrow().id(), "case is forgiven");
        assertEquals(id, h.resolve(EntityHandles.LAST).orElseThrow().id(), "@last = most recent");
    }

    @Test
    void lastFollowsTheNewestCreation() {
        EntityHandles h = new EntityHandles();
        h.remember(UUID.randomUUID(), "minecraft:cow", null);
        UUID newest = UUID.randomUUID();
        h.remember(newest, "minecraft:pig", null);
        assertEquals(newest, h.resolve(EntityHandles.LAST).orElseThrow().id());
    }

    @Test
    void unknownHandlesResolveToNothingRatherThanGuessing() {
        EntityHandles h = new EntityHandles();
        assertTrue(h.resolve("@nope").isEmpty());
        assertTrue(h.resolve("").isEmpty());
        assertTrue(h.resolve(null).isEmpty());
        assertTrue(h.resolve(EntityHandles.LAST).isEmpty(), "@last with nothing remembered");
    }

    @Test
    void handlesAreDistinguishedFromTypeWords() {
        assertTrue(EntityHandles.looksLikeHandle("@wither_storm"));
        assertTrue(EntityHandles.looksLikeHandle("  @last "));
        assertFalse(EntityHandles.looksLikeHandle("wither_storm"), "a bare word is a type search");
        assertFalse(EntityHandles.looksLikeHandle(null));
    }

    @Test
    void staysBoundedAndEvictsOldest() {
        // A "rain 60 chickens" wish must not fill memory with handles.
        EntityHandles h = new EntityHandles();
        UUID first = UUID.randomUUID();
        h.remember(first, "minecraft:chicken", null);
        for (int i = 0; i < EntityHandles.MAX_HANDLES + 10; i++) {
            h.remember(UUID.randomUUID(), "minecraft:chicken", null);
        }
        assertEquals(EntityHandles.MAX_HANDLES, h.size());
        assertTrue(h.resolve("@chicken").isEmpty(), "the oldest handle should have been evicted");
    }

    @Test
    void recentIsNewestFirst() {
        EntityHandles h = new EntityHandles();
        h.remember(UUID.randomUUID(), "minecraft:cow", null);
        h.remember(UUID.randomUUID(), "minecraft:pig", null);
        assertEquals("@pig", h.recent(5).get(0).name());
        assertEquals(2, h.recent(5).size());
        assertTrue(h.recent(0).isEmpty());
    }

    @Test
    void forgettingRemovesBothLookups() {
        EntityHandles h = new EntityHandles();
        UUID id = UUID.randomUUID();
        h.remember(id, "minecraft:zombie", null);
        h.forget(id);
        assertTrue(h.resolve("@zombie").isEmpty());
        assertEquals(0, h.size());
    }

    @Test
    void survivesRubbishInput() {
        EntityHandles h = new EntityHandles();
        assertEquals("", h.remember(null, "minecraft:zombie", null), "no id, no handle");
        assertEquals("@thing", h.remember(UUID.randomUUID(), null, null));
        assertEquals("@thing2", h.remember(UUID.randomUUID(), "", null));
        assertEquals("@thing3", h.remember(UUID.randomUUID(), "::::", null));
    }

    @Test
    void aLabelIsUsedForProseButTheHandleStaysStable() {
        EntityHandles h = new EntityHandles();
        String name = h.remember(UUID.randomUUID(), "minecraft:iron_golem", "Robo Buddy");
        assertEquals("@iron_golem", name);
        assertEquals("Robo Buddy", h.resolve(name).orElseThrow().label());
    }

    // ===================================================================================
    //  acting on a mob is what makes it addressable, and outlives the session
    // ===================================================================================

    @Test
    void actingOnAMobNobodyCreatedGivesItAName() {
        // The majority case, and the one a table of CREATIONS cannot answer: "make that cow huge"
        // is about a cow that was already standing there.
        EntityHandles h = new EntityHandles();
        UUID cow = UUID.randomUUID();
        assertEquals("@cow", h.targeted(cow, "minecraft:cow", null));
        assertEquals(cow, h.resolve("@cow").orElseThrow().id());
    }

    @Test
    void actingOnTheSameMobTwiceKeepsOneName() {
        // Two names for one animal is the same failure as one name for two: an agent holding the
        // first is addressing something nothing else calls that.
        EntityHandles h = new EntityHandles();
        UUID cow = UUID.randomUUID();
        assertEquals(h.targeted(cow, "minecraft:cow", null), h.targeted(cow, "minecraft:cow", null));
        assertEquals(1, h.size());
    }

    @Test
    void theReferentIsWhatWasLastCHANGED_notWhatWasLastMADE() {
        // A child who summons a goat and then spends three wishes on a cow is talking about the cow.
        EntityHandles h = new EntityHandles();
        long[] now = {1000L};
        h.clockIs(() -> now[0]);
        UUID cow = UUID.randomUUID();
        h.targeted(cow, "minecraft:cow", null);
        now[0] = 2000L;
        h.remember(UUID.randomUUID(), "minecraft:goat", null);   // made later, never talked about
        assertEquals(cow, h.referent().orElseThrow().id());
    }

    @Test
    void withNothingEverChangedThereIsNoReferentAtAll() {
        // Emphatically NOT "fall back to the newest thing made". A referent nobody established is
        // the silent pick the whole mechanism exists to remove.
        EntityHandles h = new EntityHandles();
        h.remember(UUID.randomUUID(), "minecraft:goat", null);
        assertTrue(h.referent().isEmpty());
    }

    @Test
    void theReferentIsStillKnownAfterTheMobIsGone() {
        // "He's gone" is the correct answer and can only be given by something that still knows who
        // he was. The table has no idea what is alive — that is the caller's job — so what it must
        // guarantee is that the name does not evaporate.
        EntityHandles h = new EntityHandles();
        UUID fox = UUID.randomUUID();
        h.targeted(fox, "minecraft:fox", null);
        for (int i = 0; i < 5; i++) {
            h.remember(UUID.randomUUID(), "minecraft:fox", null);
        }
        assertEquals(fox, h.referent().orElseThrow().id());
    }

    @Test
    void theOneHeKeepsTalkingAboutIsNotEvictedByAChickenRain() {
        // Eviction by age alone would throw away the animal a whole session has been spent on, as
        // soon as one wish makes thirty-two new handles — and that is exactly the handle the next
        // wish needs.
        EntityHandles h = new EntityHandles();
        long[] now = {1000L};
        h.clockIs(() -> now[0]);
        UUID pet = UUID.randomUUID();
        h.targeted(pet, "minecraft:wolf", null);
        for (int i = 0; i < EntityHandles.MAX_HANDLES + 10; i++) {
            now[0] += 1;
            h.remember(UUID.randomUUID(), "minecraft:chicken", null);
        }
        now[0] += 1;
        h.targeted(pet, "minecraft:wolf", null);       // he is still talking about it
        assertEquals(EntityHandles.MAX_HANDLES, h.size());
        assertEquals(pet, h.resolve("@wolf").orElseThrow().id(),
                "the pet must outlive the chickens");
    }

    @Test
    void theWholeTableSurvivesASaveAndReload() {
        EntityHandles before = new EntityHandles();
        UUID cow = UUID.randomUUID();
        before.targeted(cow, "minecraft:cow", "Daisy");
        before.remember(UUID.randomUUID(), "minecraft:cow", null);      // becomes @cow2

        EntityHandles after = new EntityHandles();
        assertEquals(2, after.loadJson(before.toJson()));
        assertEquals(cow, after.resolve("@cow").orElseThrow().id());
        assertEquals("Daisy", after.resolve("@cow").orElseThrow().label());
        assertEquals(cow, after.referent().orElseThrow().id(), "…including WHO he was talking about");

        // The counters too, or the next cow takes back a name an older one still answers to.
        assertEquals("@cow3", after.remember(UUID.randomUUID(), "minecraft:cow", null));
    }

    @Test
    void aDamagedFileCostsOneNameAndNeverTheWorld() {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        com.google.gson.JsonObject good = new com.google.gson.JsonObject();
        good.addProperty("name", "@cow");
        good.addProperty("id", UUID.randomUUID().toString());
        good.addProperty("type", "minecraft:cow");
        arr.add(good);
        com.google.gson.JsonObject noId = new com.google.gson.JsonObject();
        noId.addProperty("name", "@pig");
        arr.add(noId);
        com.google.gson.JsonObject badUuid = new com.google.gson.JsonObject();
        badUuid.addProperty("name", "@goat");
        badUuid.addProperty("id", "not-a-uuid");
        arr.add(badUuid);
        arr.add(new com.google.gson.JsonPrimitive("a string where an object should be"));
        root.add("handles", arr);

        EntityHandles h = new EntityHandles();
        assertEquals(1, h.loadJson(root), "the three broken records cost only themselves");
        assertTrue(h.resolve("@cow").isPresent());
    }

    @Test
    void rubbishWhereTheTableShouldBeLoadsAsEmpty() {
        EntityHandles h = new EntityHandles();
        assertEquals(0, h.loadJson(null));
        com.google.gson.JsonObject wrongShape = new com.google.gson.JsonObject();
        wrongShape.addProperty("handles", "not an array");
        assertEquals(0, h.loadJson(wrongShape));
        assertEquals(0, h.size());
    }

    @Test
    void aChangeIsAnnouncedButClearingIsNot() {
        // The store writes the file on every change. Clearing happens when a world CLOSES, so a
        // notification there would write an empty table over the save it was meant to protect.
        EntityHandles h = new EntityHandles();
        int[] saves = {0};
        h.onChange(() -> saves[0]++);
        h.remember(UUID.randomUUID(), "minecraft:cow", null);
        assertTrue(saves[0] > 0, "a new handle must be written out");
        int afterRemember = saves[0];
        h.targeted(UUID.randomUUID(), "minecraft:pig", null);
        assertTrue(saves[0] > afterRemember, "…and so must a change of referent");
        int beforeClear = saves[0];
        h.clear();
        assertEquals(beforeClear, saves[0], "clearing a closing world must not save over it");
        h.remember(UUID.randomUUID(), "minecraft:cow", null);
        assertEquals(beforeClear, saves[0], "…and the listener must be gone with it");
    }

    // ===================================================================================
    //  the history of who was targeted, which is also the only honest undo
    // ===================================================================================

    @Test
    void aChangeRecordsWhoDidItAndWhatItWasLike() {
        EntityHandles h = new EntityHandles();
        UUID cow = UUID.randomUUID();
        MobState was = new MobState(1.0, 10.0, null, 0.2, null, null, false);
        h.targeted(cow, "minecraft:cow", null, "wish-1", "modify_mob", "size ×2", was);

        var history = h.resolve("@cow").orElseThrow().history();
        assertEquals(1, history.size());
        assertEquals("wish-1", history.get(0).wishId());
        assertEquals("size ×2", history.get(0).summary());
        assertEquals(was, history.get(0).before());
    }

    @Test
    void undoingAWishGoesBackToBeforeItsFIRSTChange() {
        // The whole difference on a ladder. One wish that made him x2 and then x4 must undo to x1;
        // reversing only its last change leaves the child with a mob still twice the size he asked
        // to put back, and a button that said it had.
        EntityHandles h = new EntityHandles();
        UUID cow = UUID.randomUUID();
        MobState original = new MobState(1.0, null, null, null, null, null, null);
        MobState halfway = new MobState(2.0, null, null, null, null, null, null);
        h.targeted(cow, "minecraft:cow", null, "wish-1", "modify_mob", "size ×2", original);
        h.targeted(cow, "minecraft:cow", null, "wish-1", "modify_mob", "size ×4", halfway);

        assertEquals(original, h.restorePointFor("@cow", "wish-1"));
    }

    @Test
    void oneWishNeverUndoesAnothersWork() {
        EntityHandles h = new EntityHandles();
        UUID cow = UUID.randomUUID();
        MobState atOne = new MobState(1.0, null, null, null, null, null, null);
        MobState atTwo = new MobState(2.0, null, null, null, null, null, null);
        h.targeted(cow, "minecraft:cow", null, "wish-1", "modify_mob", "size ×2", atOne);
        h.targeted(cow, "minecraft:cow", null, "wish-2", "modify_mob", "size ×4", atTwo);

        assertEquals(atTwo, h.restorePointFor("@cow", "wish-2"),
                "undoing the second wish goes back to what the FIRST one left, not to the start");
        assertEquals(atOne, h.restorePointFor("@cow", "wish-1"));
    }

    @Test
    void thereIsNothingToPutBackForAWishThatNeverTouchedIt() {
        // Refusing is the honest answer; the undo report then says "could not" rather than claiming
        // a reversal that changed nothing.
        EntityHandles h = new EntityHandles();
        UUID cow = UUID.randomUUID();
        h.targeted(cow, "minecraft:cow", null, "wish-1", "modify_mob", "size ×2",
                new MobState(1.0, null, null, null, null, null, null));
        assertNull(h.restorePointFor("@cow", "wish-9"));
        assertNull(h.restorePointFor("@cow", null));
        assertNull(h.restorePointFor("@nope", "wish-1"));
    }

    @Test
    void aMobIsNotAnUnboundedLog() {
        EntityHandles h = new EntityHandles();
        UUID cow = UUID.randomUUID();
        for (int i = 0; i < EntityHandles.MAX_TOUCHES + 5; i++) {
            h.targeted(cow, "minecraft:cow", null, "wish-" + i, "modify_mob", "size ×" + i,
                    new MobState((double) i, null, null, null, null, null, null));
        }
        assertEquals(EntityHandles.MAX_TOUCHES, h.resolve("@cow").orElseThrow().history().size());
        assertNull(h.restorePointFor("@cow", "wish-0"), "the oldest is what goes");
        assertNotNull(h.restorePointFor("@cow", "wish-12"), "the newest is what stays");
    }

    @Test
    void theHistorySurvivesASaveAndReload() {
        EntityHandles before = new EntityHandles();
        UUID cow = UUID.randomUUID();
        MobState was = new MobState(1.0, 10.0, null, 0.2, "red", "Daisy", false);
        before.targeted(cow, "minecraft:cow", "Daisy", "wish-1", "modify_mob", "size ×2", was);

        EntityHandles after = new EntityHandles();
        after.loadJson(before.toJson());
        assertEquals(was, after.restorePointFor("@cow", "wish-1"),
                "an undo offered before lunch must still work after it");
    }

    @Test
    void clearResetsEverythingForANewRun() {
        EntityHandles h = new EntityHandles();
        h.remember(UUID.randomUUID(), "minecraft:zombie", null);
        h.clear();
        assertEquals(0, h.size());
        // Naming starts fresh, so the first zombie of the new run is @zombie again.
        assertEquals("@zombie", h.remember(UUID.randomUUID(), "minecraft:zombie", null));
    }

    /**
     * <b>The mechanism behind.</b> A handle survives the mob it points at — {@link
     * EntityHandles#forget(UUID)} exists but nothing in the product calls it on death — and a
     * targeted handle stays in {@link EntityHandles#targetedRecently(int)} for the rest of the run.
     *
     * <p>That is correct for a child ("your cow is GONE" beats silently growing a different cow),
     * and it is why Gate B was intermittently red: {@code EntityHandles.global()} is process-wide,
     * so once ANY test had targeted a cow, the next test to ask {@code modify_mob} about "cow"
     * resolved to that other test's corpse. The cure is for a test to forget the species it is
     * about to use, which is what {@code StaleHandles.forgetEvery} does.
     */
    @Test
    void aTargetedHandleOutlivesItsMobUntilSomebodyForgetsIt() {
        EntityHandles h = new EntityHandles();
        UUID theirs = UUID.randomUUID();
        h.targeted(theirs, "minecraft:cow", null);

        // The mob is discarded. Nothing forgets the handle, so it is still the established referent.
        assertEquals(1, h.targetedRecently(EntityHandles.MAX_HANDLES).size(),
                "a targeted handle must outlive its mob — that is what makes 'he is GONE' possible");
        assertEquals(theirs, h.targetedRecently(EntityHandles.MAX_HANDLES).get(0).id());

        // A second cow does NOT take the name over: an established referent wins, which is the
        // whole point of the mechanism and also why one test can poison another.
        UUID mine = UUID.randomUUID();
        h.remember(mine, "minecraft:cow", null);
        assertEquals(theirs, h.targetedRecently(EntityHandles.MAX_HANDLES).get(0).id(),
                "the dead cow still wins until somebody forgets it");

        h.forget(theirs);
        assertTrue(h.targetedRecently(EntityHandles.MAX_HANDLES).isEmpty(),
                "forgetting the stale handle is what lets the next test start clean");
    }
}
