package com.moddingfromamod.runtime;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>{@code start_from} inherits the CREATURE, not only its body</b> (owner's edit film,
 * 2026-10-22). "make my Spikor bigger" re-sculpted Spikor from himself, sending a crown and no
 * name or powers, and he became "Wish Creature" with no powers until the agent sent them again —
 * the schema says "you inherit its whole body — then send only what CHANGED". Driven through the
 * real tool, off the server thread, as the agent calls it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SculptInheritsGameTest {

    @GameTest(template = "empty_test", batch = "sculptInherits", timeoutTicks = 6000)
    public static void anEditKeepsTheNameAndPowersItDidNotMention(GameTestHelper helper) {
        String id = "gt_inherit_" + helper.getLevel().getGameTime();
        JsonObject made = JsonParser.parseString("""
            {"id":"%s","name":"Big Boss","spawn":false,"health":120,"powers":["boss","hostile"],
             "parts":[
              {"name":"body","from":[-4,0,-4],"to":[4,12,4],"colour":"green"},
              {"name":"head","from":[-3,12,-3],"to":[3,18,3],"colour":"green"}]}
            """.formatted(id)).getAsJsonObject();
        JsonObject crown = JsonParser.parseString("""
            {"id":"%s","start_from":"%s","spawn":false,"parts":[
              {"name":"crown","parent":"head","from":[-2,18,-2],"to":[2,20,2],"colour":"gold"}]}
            """.formatted(id, id)).getAsJsonObject();
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> edit = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                first.set(said(new SculptCreatureTool().call(made)));
                edit.set(said(new SculptCreatureTool().call(crown)));
            } catch (RuntimeException e) {
                edit.set("ERROR threw: " + e);
            }
        }, "gametest-sculpt-inherits");
        t.setDaemon(true);
        t.start();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(edit.get() != null, Component.literal("waiting for both sculpts"));
            helper.assertFalse(first.get().startsWith("ERROR "), Component.literal("the creation failed: " + first.get()));
            helper.assertFalse(edit.get().startsWith("ERROR "), Component.literal("the edit failed: " + edit.get()));
            Species sp = SpeciesRegistry.get(id);
            helper.assertTrue(sp != null, Component.literal("the creature is gone"));
            helper.assertTrue("Big Boss".equals(sp.name()),
                    Component.literal("an edit that did not mention the name renamed him: " + sp.name()));
            helper.assertTrue(sp.has(Species.Power.BOSS) && sp.has(Species.Power.HOSTILE),
                    Component.literal("an edit that did not mention his powers took them away: " + sp.toJson()));
            helper.assertTrue(sp.health() == 120, Component.literal("his health was reset: " + sp.health()));
        });
    }

    private static String said(ToolResult r) {
        return (r.isError() ? "ERROR " : "") + r.text();
    }
}
