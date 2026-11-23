package com.moddingfromamod.library;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.ListBuildsTool;
import com.moddingfromamod.agent.tools.ListCreaturesTool;
import com.moddingfromamod.agent.tools.ListPaintingsTool;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Every content part the library ships is one the AGENT can find</b> — through the listing
 * it is told to check first, not merely present in {@code starter-library/}.
 *
 * <p>The distinction is the whole reason this exists: a shipped species is read from the library on
 * demand and enters {@code SpeciesRegistry} only once spawned, so until {@code list_creatures} named
 * library bodies a shipped wolf was on disk, counted by {@code parts-coverage}, and invisible to the
 * agent that was supposed to start from it. "The file exists" and "the agent can reach it" look the
 * same from a file listing and want different fixes.
 *
 * <p>The parts are listed here by hand, deliberately: a list derived from the directory would pass
 * after a part was deleted.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ContentKitsGameTest {

    static final List<String> BODIES = List.of("wolf", "turtle", "cat", "chicken", "frog", "sheep", "snail");
    static final List<String> PICTURES = List.of("heart", "steve", "wolf", "castle", "frog");
    static final List<String> BLUEPRINTS = List.of("underwater_dome", "floating_island", "mushroom_village");

    /** Its own batch: other library tests swap the library directory and reload it. */
    @GameTest(template = "empty_test", batch = "contentKits", timeoutTicks = 400)
    public static void everyShippedPartIsNamedByTheListingTheAgentChecksFirst(GameTestHelper helper) {
        WishLibrary.reload();
        AtomicReference<String[]> said = new AtomicReference<>();
        // Off the server thread: list_builds hops onto it, and AgentBridge refuses a caller already there.
        Thread t = new Thread(() -> said.set(new String[] {
                new ListCreaturesTool().call(new JsonObject()).text(),
                new ListBuildsTool().call(new JsonObject()).text(),
                new ListPaintingsTool().call(new JsonObject()).text()}), "content-kits-gametest");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(said.get() != null, Component.literal("waiting for the three listings"));
            String creatures = said.get()[0];
            String builds = said.get()[1];
            String pictures = said.get()[2];
            for (String b : BODIES) {
                helper.assertTrue(WishLibrary.keys("species").contains(b), Component.literal("body '" + b + "' does not ship"));
                helper.assertTrue(creatures.contains(b),
                        Component.literal("list_creatures does not name the library body '" + b + "'"));
            }
            helper.assertTrue(creatures.contains("sculpt_creature start_from"),
                    Component.literal("list_creatures names the bodies but not how to start from one"));
            for (String p : BLUEPRINTS) {
                helper.assertTrue(builds.contains("id=" + p), Component.literal("list_builds does not name the blueprint '" + p + "'"));
            }
            for (String p : PICTURES) {
                helper.assertTrue(pictures.contains(p), Component.literal("list_paintings does not name the picture '" + p + "'"));
            }
        });
    }
}
