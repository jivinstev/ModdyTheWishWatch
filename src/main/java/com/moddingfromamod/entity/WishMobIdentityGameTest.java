package com.moddingfromamod.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for creature identity: a wish creature can actually be FOUND by a command selector.
 *
 * <p>Unit tests pin the tag format ({@code WishMobTagsTest}); only a real server proves the tag
 * reaches a live entity, survives a species edit, and — the part that matters for creatures made
 * before any of this existed — appears on load without a migration.
 *
 * <p>The bug being guarded: on 2026-08-04 the agent sculpted Spikor and then could not select him.
 * It spent four minutes on {@code data get entity @e[...] Tags}, {@code CustomName} matching with
 * escaped colour codes, and guesses at entity types, and broke a datapack compile deriving a
 * scoreboard objective from the display name. There was no handle to grab.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishMobIdentityGameTest {

    private static Species species(String id, String name) {
        return Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"health\":20}").getAsJsonObject());
    }

    private static WishMobEntity spawn(GameTestHelper helper, String speciesId) {
        ServerLevel level = helper.getLevel();
        WishMobEntity mob = com.moddingfromamod.entity.ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(mob != null, Component.literal("the wish mob entity type should create"));
        net.minecraft.core.BlockPos at = helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2));
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setSpeciesId(speciesId);
        level.addFreshEntity(mob);
        return mob;
    }

    /** The headline: the creature carries its own selector tag the moment it exists. */
    @GameTest(template = "empty_test")
    public static void aSpawnedCreatureCarriesItsSelectorTag(GameTestHelper helper) {
        com.moddingfromamod.testing.SpeciesForTest.put(helper, species("gt_tag_beast", "Tag Beast"));
        WishMobEntity mob = spawn(helper, "gt_tag_beast");

        helper.assertTrue(mob.entityTags().contains("wishmob_gt_tag_beast"),
                Component.literal("should carry its species tag, had: " + mob.entityTags()));
        helper.assertTrue(mob.entityTags().contains(WishMobTags.ALL),
                Component.literal("should also carry the generic wishmob tag, had: " + mob.entityTags()));
        mob.discard();
        helper.succeed();
    }

    /**
     * The tag is not decoration — a real selector must find the creature through it. This is the
     * exact command shape the agent is now told to use.
     */
    @GameTest(template = "empty_test")
    public static void aRealCommandSelectorFindsItByTag(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        com.moddingfromamod.testing.SpeciesForTest.put(helper, species("gt_findme", "Find Me"));
        spawn(helper, "gt_findme");

        // ⚠ THE SELECTOR MUST REACH WHAT IS STANDING THERE, NOT WHAT WE CONSTRUCTED.
        // `VanillaRigBridge` is total, so the wish mob is cancelled and a rig carrier takes its
        // place at the end of the tick. This test used to dispatch the command in the SAME tick
        // and read the tags off the mob object -- which is the one entity the selector can never
        // match, because it is not in the level. It has to wait for the body and then ask.
        //
        // That waiting is the point rather than an inconvenience: the selector is what the agent
        // is handed, so this is the assertion that the tag survives the swap onto the carrier.
        helper.succeedWhen(() -> {
            var body = com.moddingfromamod.testing.RigProbe.carrierFor(
                    helper.getLevel(), "gt_findme");
            helper.assertTrue(body != null, Component.literal("the creature has not manifested yet"));
            body.removeTag("gt_selector_reached_me");
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(),
                    "tag " + WishMobTags.selector("gt_findme") + " add gt_selector_reached_me");
            helper.assertTrue(body.entityTags().contains("gt_selector_reached_me"),
                    Component.literal("the published selector must actually match the creature; tags were: "
                            + body.entityTags()));
        });
    }

    /**
     * A creature that already existed before tagging shipped gets its tag on LOAD. This is what
     * makes the fix retroactive for the three Spikors already standing in the child's world — no
     * migration step, same trick as the model-derived hitbox.
     */
    @GameTest(template = "empty_test")
    public static void anUntaggedCreatureIsTaggedWhenItsSpeciesIsReapplied(GameTestHelper helper) {
        com.moddingfromamod.testing.SpeciesForTest.put(helper, species("gt_legacy", "Legacy Beast"));
        WishMobEntity mob = spawn(helper, "gt_legacy");

        // Simulate the pre-fix state: an entity in the world with no identity tags at all.
        for (String tag : new java.util.ArrayList<>(mob.entityTags())) {
            mob.removeTag(tag);
        }
        helper.assertTrue(mob.entityTags().isEmpty(), Component.literal("the legacy state is genuinely untagged"));

        mob.applySpecies();   // exactly what readAdditionalSaveData does on world load
        helper.assertTrue(mob.entityTags().contains("wishmob_gt_legacy"),
                Component.literal("loading an old creature must tag it, had: " + mob.entityTags()));
        mob.discard();
        helper.succeed();
    }

    /**
     * Re-sculpting under a different id must not leave the creature answering to BOTH names, or
     * {@code @e[tag=wishmob_spikor]} starts matching last week's dragon.
     */
    @GameTest(template = "empty_test")
    public static void changingSpeciesClearsTheStaleTagButKeepsForeignOnes(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        com.moddingfromamod.testing.SpeciesForTest.put(helper, species("gt_before", "Before"));
        com.moddingfromamod.testing.SpeciesForTest.put(helper, species("gt_after", "After"));
        WishMobEntity mob = spawn(helper, "gt_before");
        // A tag some datapack put on it — ours to preserve, not to tidy away.
        mob.addTag("bwf");

        mob.setSpeciesId("gt_after");

        helper.assertTrue(mob.entityTags().contains("wishmob_gt_after"), Component.literal("gains the new species tag"));
        helper.assertTrue(!mob.entityTags().contains("wishmob_gt_before"),
                Component.literal("must drop the stale species tag, had: " + mob.entityTags()));
        helper.assertTrue(mob.entityTags().contains("bwf"),
                Component.literal("a datapack's own tag must survive, had: " + mob.entityTags()));
        mob.discard();
        helper.succeed();
    }
}
