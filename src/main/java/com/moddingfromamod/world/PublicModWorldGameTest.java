package com.moddingfromamod.world;

import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.ChildCommands;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The public single-player mod's world: no plug-in, no world id</b> (epic). The core asks its
 * world hooks ({@link WorldModuleType}, {@link WorldModule#helpLines}, {@code ChildBranch}) of an EMPTY registry, as the
 * open-source mod with no private plug-ins does, and reads {@code worlds/*.json} with no world named, as a
 * child's own single-player game does. Each answer must be the plain one: no module, no bases, no extra help
 * line, no {@code /moddy island}, the invite-only words and no intro — and nothing throws. A world
 * module's half is tested with its plug-in.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PublicModWorldGameTest {

    private static final String BATCH = "public_mod_world";

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void withNoPluginTheCoreRunsNoModuleAndAddsNothing(GameTestHelper helper) {
        Plugins none = new Plugins();
        var types = WorldModuleHost.modules(none);
        helper.assertTrue(types.isEmpty(), Component.literal("no plug-in, yet module types: " + types.keySet()));
        helper.assertTrue(WorldModuleHost.chooseModule(null, WorldModuleHost.impliedModule(types)).isEmpty(),
                Component.literal("a world naming no module must run none"));

        ServerPlayer kid = MockPlayerNetwork.inLevel(helper);
        WorldModule bare = new WorldModule() {
            @Override
            public String id() {
                return "bare";
            }

            @Override
            public Bases start(net.minecraft.server.MinecraftServer server) {
                return null;
            }
        };
        helper.assertTrue(bare.helpLines(kid).isEmpty(), Component.literal("a module adds help lines by default"));

        var tree = com.moddingfromamod.wish.WishCommands.moddyTree(none).build();
        helper.assertTrue(tree.getChild(ChildCommands.ISLAND) == null,
                Component.literal("no plug-in, yet /moddy " + ChildCommands.ISLAND));
        for (String core : List.of(ChildCommands.RESCUE, ChildCommands.REPORT, ChildCommands.CREDITS)) {
            helper.assertTrue(tree.getChild(core) != null, Component.literal("the core's /moddy " + core + " is gone"));
        }
        // The real entry points still speak, and add nothing a world would: the core help lines only.
        com.moddingfromamod.wish.GuidePages.sayLinks(kid);
        List<String> chat = MockPlayerNetwork.chat(kid);
        helper.assertTrue(chat.stream().anyMatch(l -> l.contains("Help & links")),
                Component.literal("the help lines were not said: " + chat));
        helper.assertTrue(chat.stream().noneMatch(l -> l.contains("start over")),
                Component.literal("a player with no island was offered a fresh one: " + chat));
        MockPlayerNetwork.remove(kid);
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void withNoWorldIdEveryManifestReaderAnswersPlainly(GameTestHelper helper) {
        // Gate B names no world (the single-player case); the readers are asked with that id.
        String id = WorldFacts.manifestId();
        helper.assertTrue(id.isEmpty(), Component.literal("this run names a world: '" + id + "'"));
        helper.assertTrue(WorldFacts.current() == WorldFacts.NONE, Component.literal("facts: " + WorldFacts.current()));
        helper.assertTrue(WorldFacts.forId("") == WorldFacts.NONE, Component.literal("forId(\"\")"));
        helper.assertTrue(WorldFacts.current().guide().equals(WorldGuide.NONE),
                Component.literal("a guide with no world: " + WorldFacts.current().guide()));
        helper.assertTrue(WorldCrafters.forId("") == WorldCrafters.NONE, Component.literal("crafters forId(\"\")"));
        WorldCrafters.current();                                   // never throws
        helper.assertTrue(!JoinIntro.enabled("", id), Component.literal("an intro with no world"));
        JoinIntroService.enabled();                                // never throws
        String refusal = com.moddingfromamod.net.JoinWords.notOnTheList("Kid", WorldFacts.NONE, null, null);
        helper.assertTrue(refusal.contains("invite-only"), Component.literal("no world's refusal: " + refusal));
        helper.assertTrue(com.moddingfromamod.net.JoinWords.serverFull(WorldFacts.NONE, 4) != null,
                Component.literal("no world's server-full words"));
        helper.succeed();
    }
}
