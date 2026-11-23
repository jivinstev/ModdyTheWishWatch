package com.moddingfromamod.runtime;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * Gate B for the runtime custom-mob system: the things only a real server proves. The renderer is
 * client-side (covered by the {@code custommodel} client test), so this checks the server half — that
 * {@code /wishmob} actually built into the command tree, that {@code /wishmob list} runs, and that the
 * one generic entity accepts a <b>custom-model</b> species and lives (a missing attribute or a
 * modelKey the server mishandles would crash on spawn here).
 */
@GameTestHolder(GateShards.CI)
public class WishMobRuntimeGameTest {

    @GameTest(template = "empty_test")
    public static void wishmobCommandIsRegistered(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var root = server.getCommands().getDispatcher().getRoot();
        var wishmob = root.getChild("wishmob");
        helper.assertTrue(wishmob != null, Component.literal("/wishmob should be registered"));
        for (String sub : new String[]{"spawn", "list", "reload"}) {
            helper.assertTrue(wishmob.getChild(sub) != null, Component.literal("/wishmob " + sub + " should exist"));
        }
        // 'persist' is GONE and must stay gone. It existed only to copy a creature into a shared
        // every-world folder — half of the split brain that let a re-sculpt in one world rewrite a
        // different world's creature (see WorldPaths). It was also unreachable in practice: only a
        // hand-typed command called it, the agent had no tool for it, and the folder it wrote to
        // was never created on any real machine. Cross-world creatures are worth having, but as an
        // explicit import, not as a shared mutable directory.
        helper.assertTrue(wishmob.getChild("persist") == null,
                Component.literal("/wishmob persist must not come back — it re-creates the split brain"));
        // list runs without throwing (empty repo is fine — returns 0).
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "wishmob list");
        helper.succeed();
    }

    /**
     * The spawn egg really is per-creature: the stack carries the species, names itself after it, and
     * hatches that exact creature. This is the claim that "no restart needed" rests on — one
     * registered item behaving like many.
     *
     * <p>⚠ <b>This asserts the STACK, not the hatch</b> — it never uses the egg on a block. It
     * was called {@code spawnEggCarriesAndHatchesItsCreature} and the name outran the body,
     * which mattered once the vanilla rig became the default: the test was green whether the
     * bridge handled eggs perfectly or ignored them entirely. The hatch is measured in
     * {@code VanillaRigBridgeGameTest.aSpawnEggHatchesARigRatherThanAWishMob} and its
     * flag-off sibling.
     */
    @GameTest(template = "empty_test")
    public static void spawnEggCarriesItsCreature(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Species s = Species.fromJson(JsonParser.parseString(
                "{\"id\":\"gt_egg_beast\",\"name\":\"Egg Beast\",\"health\":18}").getAsJsonObject());
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, s);

        var stack = com.moddingfromamod.item.WishEggItem.of(
                com.moddingfromamod.item.ModItems.WISH_EGG.get(), "gt_egg_beast");
        helper.assertTrue("gt_egg_beast".equals(
                        com.moddingfromamod.item.WishEggItem.speciesId(stack)),
                Component.literal("the egg should remember its creature"));
        helper.assertTrue(com.moddingfromamod.item.WishEggItem.species(stack) != null,
                Component.literal("the egg should resolve its species"));
        // It names itself after the creature, like a vanilla egg does.
        helper.assertTrue(stack.getHoverName().getString().contains("Egg Beast"),
                Component.literal("the egg should be named after its creature, was: "
                        + stack.getHoverName().getString()));

        // A different creature's egg is a different egg from the same registered item.
        var other = com.moddingfromamod.item.WishEggItem.of(
                com.moddingfromamod.item.ModItems.WISH_EGG.get(), "something_else");
        helper.assertTrue(!com.moddingfromamod.item.WishEggItem.speciesId(other)
                        .equals(com.moddingfromamod.item.WishEggItem.speciesId(stack)),
                Component.literal("two eggs of the same item must still be different creatures"));
        helper.succeed();
    }

    /**
     * Sculpting writes a real creature: geometry, skin, animations, and a species pointing at them.
     *
     * <p>The case this guards is the one that shipped wrong — a whimsical character. The vanilla-parts
     * path could only ever costume a monster and darken it, so this asserts the sculpted creature
     * actually carries a custom model rather than falling back to a vanilla body.
     */
    @GameTest(template = "empty_test")
    public static void sculptingBuildsACreatureWithItsOwnModel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String key = "gt_clown";
        java.nio.file.Path dir = com.moddingfromamod.runtime.ModelRepository.dir(key);

        var parts = com.moddingfromamod.runtime.CreatureSculpt.parseParts(
                JsonParser.parseString("""
                    [
                      {"name":"body","from":[-5,12,-3],"to":[5,26,3],"colour":"yellow"},
                      {"name":"head","parent":"body","from":[-4,26,-4],"to":[4,36,4],"colour":"white"},
                      {"name":"hair","parent":"head","from":[-5,34,-5],"to":[5,38,5],"colour":"red"}
                    ]
                    """).getAsJsonArray());
        helper.assertTrue(parts.size() == 3, Component.literal("all three parts should parse"));

        try {
            java.nio.file.Path written = com.moddingfromamod.runtime.CreatureFiles.write(key, parts);
            helper.assertTrue(written != null, Component.literal("sculpting should write a model folder"));
            helper.assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("geometry.json")),
                    Component.literal("geometry.json should exist"));
            helper.assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("texture.png")),
                    Component.literal("texture.png should exist"));
            helper.assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("animations.json")),
                    Component.literal("a sculpted creature should move, not stand there like a statue"));

            // The geometry really parses back, with the bones we described.
            var geo = com.moddingfromamod.runtime.ModelRepository.geometry(key);
            helper.assertTrue(geo != null && geo.bones().size() == 3,
                    Component.literal("the written geometry should read back with 3 bones"));
            // And the clips are real.
            var clips = com.moddingfromamod.runtime.ModelRepository.animations(key);
            helper.assertTrue(!clips.isEmpty(), Component.literal("starter animations should be written"));

            // A species built on it must be marked as custom-model, not a vanilla body.
            Species s = Species.fromJson(JsonParser.parseString(
                    "{\"id\":\"" + key + "\",\"modelKey\":\"" + key + "\",\"name\":\"Clown\"}")
                    .getAsJsonObject());
            com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, s);
            helper.assertTrue(s.hasCustomModel(),
                    Component.literal("a sculpted creature must carry its own model, not a costumed vanilla one"));
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("sculpting threw: " + e));
        }
        helper.succeed();
    }

    /** The agent can actually reach the sculpt tool — it was unreachable for the whole first build. */
    @GameTest(template = "empty_test")
    public static void sculptToolIsOfferedToTheAgent(GameTestHelper helper) {
        var tools = com.moddingfromamod.agent.WishAgent.buildTools();
        helper.assertTrue(tools.byName("sculpt_creature") != null,
                Component.literal("the agent must be offered sculpt_creature"));
        // And it must outrank the vanilla-parts path, which is what produced the monster clown.
        var names = tools.all().stream().map(com.moddingfromamod.agent.McpTool::name).toList();
        helper.assertTrue(names.indexOf("sculpt_creature") < names.indexOf("define_creature"),
                Component.literal("sculpt_creature should be offered before define_creature"));

        // Being registered is not the same as being CALLABLE. The agent runs with
        // --permission-mode dontAsk, so any tool missing from --allowed-tools is silently denied
        // rather than prompted for — it would look to a child like the feature just doesn't work.
        // The list is derived from this registry; this locks that so the two can never drift.
        String allowed = com.moddingfromamod.agent.AgentSession.allowedTools();
        for (String n : names) {
            helper.assertTrue(allowed.contains("mcp__wishwatch__" + n),
                    Component.literal("tool '" + n + "' is registered but NOT in the agent's allowed-tools, so the "
                            + "agent would be denied it silently"));
        }

        // Being permitted is still not the same as being USED. A tool the agent is allowed to call
        // but never told about is very nearly as invisible as one it's denied — the brief has to
        // route to it. (sculpt_creature was registered and permitted while the brief only ever
        // mentioned define_creature.)
        String brief = com.moddingfromamod.agent.AgentSession.prompt();
        for (String n : new String[]{"sculpt_creature", "paint_creature", "photograph"}) {
            helper.assertTrue(brief.contains(n),
                    Component.literal("the agent's standing brief never mentions '" + n + "', so it will not reach "
                            + "for it no matter how well the tool works"));
        }
        helper.succeed();
    }

    /** /wishmob egg is registered — the reliable way to get an egg regardless of tab caching. */
    @GameTest(template = "empty_test")
    public static void eggCommandIsRegistered(GameTestHelper helper) {
        var wishmob = helper.getLevel().getServer().getCommands().getDispatcher()
                .getRoot().getChild("wishmob");
        helper.assertTrue(wishmob != null && wishmob.getChild("egg") != null,
                Component.literal("/wishmob egg should exist"));
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void customModelSpeciesSpawnsAndIsMarkedCustom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Species s = Species.fromJson(JsonParser.parseString(
                "{\"id\":\"gt_dragon\",\"name\":\"GT Dragon\",\"modelKey\":\"gt_dragon\","
                        + "\"health\":24,\"scale\":1.4}").getAsJsonObject());
        helper.assertTrue(s != null && s.hasCustomModel(), Component.literal("species should carry a model key"));
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, s);

        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        mob.setSpeciesId(s.id());
        level.addFreshEntity(mob);

        helper.assertTrue(mob.isAlive(), Component.literal("custom-model creature should spawn and live"));
        helper.assertTrue(mob.species() != null && mob.species().hasCustomModel(),
                Component.literal("the live entity should resolve to its custom-model species"));
        helper.assertTrue(Math.abs(mob.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) - 24) < 0.01,
                Component.literal("custom-model species stats should apply server-side"));
        helper.succeed();
    }
}
